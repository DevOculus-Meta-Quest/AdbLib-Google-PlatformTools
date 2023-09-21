/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.adblib

import com.android.adblib.impl.channels.AdbInputStreamChannel
import com.android.adblib.impl.channels.AdbOutputStreamChannel
import com.android.adblib.testingutils.CoroutineTestUtils.runBlockingWithTimeout
import com.android.adblib.testingutils.FakeAdbServerProviderRule
import com.android.adblib.testingutils.TestingAdbSessionHost
import com.android.adblib.testingutils.TimeWaitSocketsThrottler
import com.android.adblib.testingutils.asAdbInputChannel
import com.android.fakeadbserver.DeviceFileState
import com.android.fakeadbserver.devicecommandhandlers.SyncCommandHandler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermission
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class ConnectedDeviceTest {

    @JvmField
    @Rule
    var exceptionRule: ExpectedException = ExpectedException.none()

    @JvmField
    @Rule
    val fakeAdbRule = FakeAdbServerProviderRule {
        installDefaultCommandHandlers()
        installDeviceHandler(SyncCommandHandler())
    }

    @JvmField
    @Rule
    var temporaryFolder = TemporaryFolder()

    private val fakeAdb get() = fakeAdbRule.fakeAdb

    private val adbSession get() = fakeAdbRule.adbSession

    @Test
    fun testIsOnlineWorks(): Unit = runBlockingWithTimeout {
        // Prepare/Act
        val connectedDevice = addFakeConnectedDevice()

        // Assert
        Assert.assertTrue(connectedDevice.isOnline)
    }

    @Test
    fun testScopeIsCacheScope(): Unit = runBlockingWithTimeout {
        // Prepare/Act
        val connectedDevice = addFakeConnectedDevice()

        // Assert
        Assert.assertSame(connectedDevice.scope, connectedDevice.cache.scope)
    }

    @Test
    fun testDisconnectedDeviceState(): Unit = runBlockingWithTimeout {
        // Prepare
        val connectedDevice = addFakeConnectedDevice()

        // Act
        fakeAdb.disconnectDevice(connectedDevice.serialNumber)
        connectedDevice.session.connectedDevicesTracker.connectedDevices.first { it.isEmpty() }

        // Assert
        Assert.assertEquals(DeviceState.OFFLINE, connectedDevice.deviceInfo.deviceState)
        Assert.assertTrue(connectedDevice.isOffline)
        Assert.assertFalse(connectedDevice.cache.scope.isActive)
    }

    @Test
    fun testShellExecuteAsTextWorks(): Unit = runBlockingWithTimeout {
        // Prepare
        val connectedDevice = addFakeConnectedDevice()
        val input = """
            stdout: This is some text with
            stdout: split in multiple lines
            stderr: and containing non-ascii
            stdout: characters such as
            stderr: - ඒ (SINHALA LETTER EEYANNA (U+0D92))
            stdout: - ⿉ (KANGXI RADICAL MILLET (U+2FC9))
            exit: 10
        """.trimIndent()

        // Act
        val commandOutput = connectedDevice.shell.executeAsText(
            "shell-protocol-echo",
            stdinChannel = input.asAdbInputChannel(adbSession)
        )

        // Assert
        val expectedStdout = """
            This is some text with
            split in multiple lines
            characters such as
            - ⿉ (KANGXI RADICAL MILLET (U+2FC9))

        """.trimIndent()

        val expectedStderr = """
            and containing non-ascii
            - ඒ (SINHALA LETTER EEYANNA (U+0D92))

        """.trimIndent()

        Assert.assertEquals(expectedStdout, commandOutput.stdout)
        Assert.assertEquals(expectedStderr, commandOutput.stderr)
        Assert.assertEquals(10, commandOutput.exitCode)
    }

    @Test
    fun testFlowWhenOnlineWorks(): Unit = runBlockingWithTimeout {
        // Prepare
        val connectedDevice = addFakeConnectedDevice()

        // Act
        val valuesDeferred = CompletableDeferred<List<Int>>()
        connectedDevice.scope.launch {
            connectedDevice
                .flowWhenOnline(retryDelay = Duration.ofMillis(5)) {
                    flowOf(1, 3, 5)
                }
                .take(3)
                .toList().also {
                    valuesDeferred.complete(it)
                }
        }
        val values = valuesDeferred.await()

        // Assert
        Assert.assertEquals(listOf(1, 3, 5), values)
    }

    @Test
    fun testFlowWhenOnlineRetriesWhenException(): Unit = runBlockingWithTimeout {
        // Prepare
        val connectedDevice = addFakeConnectedDevice()

        // Act
        val exceptionDeferred = CompletableDeferred<Unit>()
        val valuesDeferred = CompletableDeferred<List<Int>>()
        var pass = 1
        connectedDevice.scope.launch {
            connectedDevice
                .flowWhenOnline(retryDelay = Duration.ofMillis(5)) {
                    if (pass++ == 1) {
                        exceptionDeferred.complete(Unit)
                        throw Exception("Unit Test Exception To Retry")
                    } else {
                        flowOf(1, 3, 5)
                    }
                }
                .take(3)
                .toList().also {
                    valuesDeferred.complete(it)
                }
        }
        awaitAll(exceptionDeferred, valuesDeferred)
        val values = valuesDeferred.await()

        // Assert
        Assert.assertEquals(listOf(1, 3, 5), values)
    }

    @Test
    fun testShellAsLines(): Unit = runBlockingWithTimeout {
        // Prepare
        val fakeDevice = addFakeConnectedDevice()

        // Act
        val lines =
            fakeDevice.shell.executeAsLines("getprop", bufferSize = 10)
                .filterIsInstance<ShellCommandOutputElement.StdoutLine>()
                .map { it.contents }
                .toList()

        // Assert
        val expectedOutput = """
            # This is some build info
            # This is more build info

            [ro.build.version.release]: [model]
            [ro.build.version.sdk]: [30]
            [ro.product.cpu.abi]: [x86_64]
            [ro.product.manufacturer]: [test1]
            [ro.product.model]: [test2]
            [ro.serialno]: [1234]

        """.trimIndent()
        Assert.assertEquals(expectedOutput.lines(), lines)
    }

    @Test
    fun testShellAsTextWorks(): Unit = runBlockingWithTimeout {
        // Prepare
        val fakeDevice = addFakeConnectedDevice()
        val input = """
            stdout: This is some text with
            stdout: split in multiple lines
            stderr: and containing non-ascii
            stdout: characters such as
            stderr: - ඒ (SINHALA LETTER EEYANNA (U+0D92))
            stdout: - ⿉ (KANGXI RADICAL MILLET (U+2FC9))
            exit: 10
        """.trimIndent()

        // Act
        val commandOutput = fakeDevice.shell.executeAsText(
            "shell-protocol-echo",
            stdinChannel = input.asAdbInputChannel(adbSession)
        )

        // Assert
        val expectedStdout = """
            This is some text with
            split in multiple lines
            characters such as
            - ⿉ (KANGXI RADICAL MILLET (U+2FC9))

        """.trimIndent()

        val expectedStderr = """
            and containing non-ascii
            - ඒ (SINHALA LETTER EEYANNA (U+0D92))

        """.trimIndent()

        Assert.assertEquals(expectedStdout, commandOutput.stdout)
        Assert.assertEquals(expectedStderr, commandOutput.stderr)
        Assert.assertEquals(10, commandOutput.exitCode)
    }

    @Test
    fun testShellAsLinesWorks(): Unit = runBlockingWithTimeout {
        // Prepare
        val fakeDevice = addFakeConnectedDevice()
        val input = """
            stdout: This is some text with
            stdout: split in multiple lines
            stderr: and containing non-ascii
            stdout: characters such as
            stderr: - ඒ (SINHALA LETTER EEYANNA (U+0D92))
            stdout: - ⿉ (KANGXI RADICAL MILLET (U+2FC9))
            exit: 10
        """.trimIndent()

        // Act
        val collectedStdout = ArrayList<String>()
        val collectedStderr = ArrayList<String>()
        var collectedExitCode = -1
        fakeDevice.shell.executeAsLines(
            "shell-protocol-echo",
            stdinChannel = input.asAdbInputChannel(adbSession)
        ).collect { entry ->
            when (entry) {
                is ShellCommandOutputElement.StdoutLine -> collectedStdout.add(entry.contents)
                is ShellCommandOutputElement.StderrLine -> collectedStderr.add(entry.contents)
                is ShellCommandOutputElement.ExitCode -> collectedExitCode = entry.exitCode
            }
        }

        // Assert
        val expectedStdout = """
            This is some text with
            split in multiple lines
            characters such as
            - ⿉ (KANGXI RADICAL MILLET (U+2FC9))

        """.trimIndent()

        val expectedStderr = """
            and containing non-ascii
            - ඒ (SINHALA LETTER EEYANNA (U+0D92))

        """.trimIndent()

        Assert.assertEquals(expectedStdout, collectedStdout.joinToString(separator = "\n"))
        Assert.assertEquals(expectedStderr, collectedStderr.joinToString(separator = "\n"))
        Assert.assertEquals(10, collectedExitCode)
    }

    @Test
    fun testShellAsLineBatchesWorks(): Unit = runBlockingWithTimeout {
        // Prepare
        val fakeDevice = addFakeConnectedDevice()
        val input = """
            stdout: This is some text with
            stdout: split in multiple lines
            stderr: and containing non-ascii
            stdout: characters such as
            stderr: - ඒ (SINHALA LETTER EEYANNA (U+0D92))
            stdout: - ⿉ (KANGXI RADICAL MILLET (U+2FC9))
            exit: 10
        """.trimIndent()

        // Act
        val collectedStdout = ArrayList<String>()
        val collectedStderr = ArrayList<String>()
        var collectedExitCode = -1
        fakeDevice.shell.executeAsLineBatches(
            "shell-protocol-echo",
            stdinChannel = input.asAdbInputChannel(adbSession)
        ).collect { entry ->
            when (entry) {
                is BatchShellCommandOutputElement.StdoutLine -> collectedStdout.addAll(entry.lines)
                is BatchShellCommandOutputElement.StderrLine -> collectedStderr.addAll(entry.lines)
                is BatchShellCommandOutputElement.ExitCode -> collectedExitCode = entry.exitCode
            }
        }

        // Assert
        val expectedStdout = """
            This is some text with
            split in multiple lines
            characters such as
            - ⿉ (KANGXI RADICAL MILLET (U+2FC9))

        """.trimIndent()

        val expectedStderr = """
            and containing non-ascii
            - ඒ (SINHALA LETTER EEYANNA (U+0D92))

        """.trimIndent()

        Assert.assertEquals(expectedStdout, collectedStdout.joinToString(separator = "\n"))
        Assert.assertEquals(expectedStderr, collectedStderr.joinToString(separator = "\n"))
        Assert.assertEquals(10, collectedExitCode)
    }

    @Test
    fun testShellCommandAllowsCrLfOnOldDevices(): Unit = runBlockingWithTimeout {
        // Prepare
        // Below API 21, only "shell" is supported
        val fakeDevice = addFakeConnectedDevice(19)

        // Act
        val output = fakeDevice.shell.command("getprop")
            .withTextCollector()
            .allowStripCrLfForLegacyShell(false)
            .execute()
            .first()
            .stdout

        // Assert
        val expectedOutput = """
            # This is some build info
            # This is more build info

            [ro.build.version.release]: [model]
            [ro.build.version.sdk]: [19]
            [ro.product.cpu.abi]: [x86_64]
            [ro.product.manufacturer]: [test1]
            [ro.product.model]: [test2]
            [ro.serialno]: [1234]

        """.trimIndent().replace("\n", "\r\n")
        Assert.assertEquals(expectedOutput, output)
    }

    // Checks public contract of the ShellCommandOutputElement.*.toString methods.
    @Test
    fun testShellCommandOutputElement(): Unit = runBlockingWithTimeout {
        Assert.assertEquals(
            "contents",
            ShellCommandOutputElement.StdoutLine("contents").toString()
        )
        Assert.assertEquals(
            "contents",
            ShellCommandOutputElement.StderrLine("contents").toString()
        )
        Assert.assertEquals(
            "42",
            ShellCommandOutputElement.ExitCode(42).toString()
        )
    }

    @Test
    fun testDevicePropertiesAllWorks(): Unit = runBlockingWithTimeout {
        // Prepare
        val device = addFakeConnectedDevice()

        // Act
        val props = device.deviceProperties().all().associate { it.name to it.value }

        // Assert
        Assert.assertEquals("30", props["ro.build.version.sdk"])
        Assert.assertEquals("model", props["ro.build.version.release"])
        Assert.assertEquals("test2", props["ro.product.model"])
    }

    @Test
    fun testDevicePropertiesAllWorksOnApi16(): Unit = runBlockingWithTimeout {
        // Prepare
        val device = addFakeConnectedDevice(sdk = 16)

        // Act
        val props = device.deviceProperties().all().associate { it.name to it.value }

        // Assert
        Assert.assertEquals("16", props["ro.build.version.sdk"])
        Assert.assertEquals("model", props["ro.build.version.release"])
        Assert.assertEquals("test2", props["ro.product.model"])
    }

    @Test
    fun testDevicePropertiesAllReadonlyWorks() = runBlockingWithTimeout {
        // Prepare
        val device = addFakeConnectedDevice()

        // Act
        // Call 3 times so that we can assert later there was only one connection
        val props = device.deviceProperties().allReadonly()

        val connectionCountBefore = fakeAdb.channelProvider.createdChannels.size
        val props2 = device.deviceProperties().allReadonly()
        val props3 = device.deviceProperties().allReadonly()
        val connectionCountAfter = fakeAdb.channelProvider.createdChannels.size

        // Assert
        Assert.assertEquals("30", props["ro.build.version.sdk"])
        Assert.assertEquals("model", props["ro.build.version.release"])
        Assert.assertEquals("test2", props["ro.product.model"])

        // Assert we made no additional connections
        Assert.assertEquals(connectionCountBefore, connectionCountAfter)
        Assert.assertSame(props, props2)
        Assert.assertSame(props, props3)
    }

    @Test
    fun testDevicePropertiesApiWorks(): Unit = runBlockingWithTimeout {
        // Prepare
        val device = addFakeConnectedDevice()

        // Act
        val api = device.deviceProperties().api()

        // Assert
        Assert.assertEquals(30, api)
    }

    @Test
    fun testSyncSendFileWorks(): Unit = runBlockingWithTimeout {
        // Prepare
        val fakeDevice = addFakeConnectedDevice()

        val filePath = "/sdcard/foo/bar.bin"
        val fileBytes = createFileBytes(128_000)
        val fileMode = RemoteFileMode.fromPosixPermissions(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE
        )
        val fileDate = FileTime.from(1_000_000, TimeUnit.SECONDS)
        val progress = TestSyncProgress()

        // Act
        fakeDevice.fileSystem.sendFile(
            fakeDevice.session.channelFactory.wrapInputStream(fileBytes.inputStream()),
            filePath,
            fileMode,
            fileDate,
            progress,
            bufferSize = 1_024
        )

        // Assert
        Assert.assertTrue(progress.started)
        Assert.assertTrue(progress.progress)
        Assert.assertTrue(progress.done)

        Assert.assertNotNull(fakeDevice.toDeviceState().getFile(filePath))
        fakeDevice.toDeviceState().getFile(filePath)?.run {
            Assert.assertEquals(filePath, path)
            Assert.assertEquals(fileMode.modeBits, permission)
            Assert.assertEquals(fileDate.toMillis() / 1_000, modifiedDate.toLong())
            Assert.assertEquals(fileBytes.size, bytes.size)
        }
    }

    @Test
    fun testSyncSendFileFromDiskWorks(): Unit = runBlockingWithTimeout {
        // Prepare
        val fakeDevice = addFakeConnectedDevice()

        val filePath = "/sdcard/foo/bar.bin"
        val fileBytes = createFileBytes(128_000)
        val fileMode = RemoteFileMode.fromPosixPermissions(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE
        )
        val fileDate = FileTime.from(1_000_000, TimeUnit.SECONDS)
        val progress = TestSyncProgress()
        val inputFile = temporaryFolder.newFile().toPath()
        Files.write(inputFile, fileBytes)

        // Act
        fakeDevice.fileSystem.sendFile(
            inputFile,
            filePath,
            fileMode,
            fileDate,
            progress,
            bufferSize = 1_024
        )

        // Assert
        Assert.assertTrue(progress.started)
        Assert.assertTrue(progress.progress)
        Assert.assertTrue(progress.done)

        Assert.assertNotNull(fakeDevice.toDeviceState().getFile(filePath))
        fakeDevice.toDeviceState().getFile(filePath)?.run {
            Assert.assertEquals(filePath, path)
            Assert.assertEquals(fileMode.modeBits, permission)
            Assert.assertEquals(fileDate.toMillis() / 1_000, modifiedDate.toLong())
            Assert.assertEquals(fileBytes.size, bytes.size)
        }
    }

    @Test
    fun testSyncSendEmptyFileWorks(): Unit = runBlockingWithTimeout {
        // Prepare
        val fakeDevice = addFakeConnectedDevice()

        val filePath = "/sdcard/foo/bar.bin"
        val fileBytes = createFileBytes(0)
        val fileMode = RemoteFileMode.fromPosixPermissions(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE
        )
        val fileDate = FileTime.from(1_000_000, TimeUnit.SECONDS)
        val progress = TestSyncProgress()

        // Act
        fakeDevice.fileSystem.sendFile(
            fakeDevice.session.channelFactory.wrapInputStream(fileBytes.inputStream()),
            filePath,
            fileMode,
            fileDate,
            progress,
            bufferSize = 1_024
        )

        // Assert
        Assert.assertTrue(progress.started)
        Assert.assertFalse(progress.progress)
        Assert.assertTrue(progress.done)

        Assert.assertNotNull(fakeDevice.toDeviceState().getFile(filePath))
        fakeDevice.toDeviceState().getFile(filePath)?.run {
            Assert.assertEquals(filePath, path)
            Assert.assertEquals(fileMode.modeBits, permission)
            Assert.assertEquals(fileDate.toMillis() / 1_000, modifiedDate.toLong())
            Assert.assertEquals(fileBytes.size, bytes.size)
        }
    }

    @Test
    fun testSyncSendWithTimeoutWorks(): Unit = runBlockingWithTimeout {
        // Prepare
        val fakeDevice = addFakeConnectedDevice()

        val filePath = "/sdcard/foo/bar.bin"
        val fileMode = RemoteFileMode.fromPosixPermissions(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE
        )
        val fileDate = FileTime.from(1_000_000, TimeUnit.SECONDS)
        val progress = TestSyncProgress()
        val slowInputChannel = object : AdbInputChannel {
            var firstCall = true
            override suspend fun readBuffer(buffer: ByteBuffer, timeout: Long, unit: TimeUnit) {
                if (firstCall) {
                    firstCall = false
                    buffer.putInt(5)
                } else {
                    delay(200)
                }
            }

            override fun close() {
            }
        }

        // Act
        exceptionRule.expect(TimeoutException::class.java)
        adbSession.host.timeProvider.withErrorTimeout(Duration.ofMillis(100)) {
            fakeDevice.fileSystem.sendFile(
                slowInputChannel,
                filePath,
                fileMode,
                fileDate,
                progress,
                bufferSize = 1_024
            )
        }

        // Assert
        Assert.fail() // Should not be reached
    }

    @Test
    fun testSyncSendRethrowsProgressException(): Unit = runBlockingWithTimeout {
        // Prepare
        val fakeDevice = addFakeConnectedDevice()

        val filePath = "/sdcard/foo/bar.bin"
        val fileBytes = createFileBytes(1_024)
        val fileMode = RemoteFileMode.fromPosixPermissions(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE
        )
        val fileDate = FileTime.from(1_000_000, TimeUnit.SECONDS)
        val progress = object : TestSyncProgress() {
            override suspend fun transferProgress(remotePath: String, totalBytesSoFar: Long) {
                throw MyTestException("An error in progress callback")
            }
        }

        // Act
        exceptionRule.expect(MyTestException::class.java)
        fakeDevice.fileSystem.sendFile(
            fakeDevice.session.channelFactory.wrapInputStream(fileBytes.inputStream()),
            filePath,
            fileMode,
            fileDate,
            progress,
            bufferSize = 1_024
        )

        // Assert
        Assert.fail() // Should not be reached
    }

    @Test
    fun testSyncSendTwoFilesInSameSessionWorks(): Unit = runBlockingWithTimeout {
        // Prepare
        val fakeDevice = addFakeConnectedDevice()

        val filePath = "/sdcard/foo/bar.bin"
        val fileBytes = createFileBytes(128_000)
        val fileMode = RemoteFileMode.fromPosixPermissions(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE
        )
        val fileDate = FileTime.from(1_000_000, TimeUnit.SECONDS)
        val fileProgress = TestSyncProgress()

        val filePath2 = "/sdcard/foo/bar2.bin"
        val fileBytes2 = createFileBytes(96_000)
        val fileMode2 = RemoteFileMode.fromPosixPermissions(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE
        )
        val fileDate2 = FileTime.from(2_000_000, TimeUnit.SECONDS)
        val fileProgress2 = TestSyncProgress()

        // Act
        fakeDevice.fileSystem.withSyncServices {
            it.send(
                AdbInputStreamChannel(adbSession.host, fileBytes.inputStream()),
                filePath,
                fileMode,
                fileDate,
                fileProgress,
                bufferSize = 1_024
            )

            it.send(
                AdbInputStreamChannel(adbSession.host, fileBytes2.inputStream()),
                filePath2,
                fileMode2,
                fileDate2,
                fileProgress2,
                bufferSize = 1_024
            )
        }

        // Assert
        Assert.assertTrue(fileProgress.started)
        Assert.assertTrue(fileProgress.progress)
        Assert.assertTrue(fileProgress.done)

        Assert.assertNotNull(fakeDevice.toDeviceState().getFile(filePath))
        fakeDevice.toDeviceState().getFile(filePath)?.run {
            Assert.assertEquals(filePath, path)
            Assert.assertEquals(fileMode.modeBits, permission)
            Assert.assertEquals(fileDate.toMillis() / 1_000, modifiedDate.toLong())
            Assert.assertArrayEquals(fileBytes, bytes)
        }

        Assert.assertTrue(fileProgress2.started)
        Assert.assertTrue(fileProgress2.progress)
        Assert.assertTrue(fileProgress2.done)

        Assert.assertNotNull(fakeDevice.toDeviceState().getFile(filePath2))
        fakeDevice.toDeviceState().getFile(filePath2)?.run {
            Assert.assertEquals(filePath2, path)
            Assert.assertEquals(fileMode2.modeBits, permission)
            Assert.assertEquals(fileDate2.toMillis() / 1_000, modifiedDate.toLong())
            Assert.assertArrayEquals(fileBytes2, bytes)
        }
    }

    @Test
    fun testSyncSendFileWithNoDateSetsModifiedDateToCurrentTime(): Unit = runBlockingWithTimeout {
        // Prepare
        val fakeDevice = addFakeConnectedDevice()

        val filePath = "/sdcard/foo/bar.bin"
        val fileBytes = createFileBytes(1_024)
        val fileMode = RemoteFileMode.fromPosixPermissions(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE
        )
        val progress = TestSyncProgress()

        val currentTime = Instant.ofEpochSecond(1_234_567L)
        (adbSession.host as TestingAdbSessionHost).overrideUtcNow = currentTime

        // Act
        fakeDevice.fileSystem.sendFile(
            AdbInputStreamChannel(adbSession.host, fileBytes.inputStream()),
            filePath,
            fileMode,
            remoteFileTime = null,
            progress,
            bufferSize = 1_024
        )

        // Assert
        Assert.assertTrue(progress.started)
        Assert.assertTrue(progress.progress)
        Assert.assertTrue(progress.done)

        Assert.assertNotNull(fakeDevice.toDeviceState().getFile(filePath))
        fakeDevice.toDeviceState().getFile(filePath)?.run {
            Assert.assertEquals(currentTime.epochSecond, modifiedDate.toLong())
        }
    }

    @Test
    fun testSyncRecvFileToDiskWorks(): Unit = runBlockingWithTimeout {
        // Prepare
        val fakeDevice = addFakeConnectedDevice()

        val filePath = "/sdcard/foo/bar.bin"
        val fileBytes = createFileBytes(128_000)
        val fileMode = RemoteFileMode.fromPosixPermissions(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE
        )
        val fileDate = FileTime.from(1_000_000, TimeUnit.SECONDS)
        fakeDevice.toDeviceState().createFile(
            DeviceFileState(
                filePath,
                fileMode.modeBits,
                (fileDate.toMillis() / 1_000).toInt(),
                fileBytes
            )
        )
        val progress = TestSyncProgress()
        val outputFile = temporaryFolder.newFile().toPath()

        // Act
        fakeDevice.fileSystem.receiveFile(
            filePath,
            outputFile,
            progress,
            bufferSize = 1_024
        )

        // Assert
        Assert.assertTrue(progress.started)
        Assert.assertTrue(progress.progress)
        Assert.assertTrue(progress.done)

        Assert.assertArrayEquals(fileBytes, Files.readAllBytes(outputFile))
    }

    @Test
    fun testSyncRecvTwoFileInSameSessionWorks(): Unit = runBlockingWithTimeout {
        // Prepare
        val fakeDevice = addFakeConnectedDevice()

        val filePath = "/sdcard/foo/bar.bin"
        val fileBytes = createFileBytes(10)
        val fileMode = RemoteFileMode.fromPosixPermissions(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE
        )
        val fileDate = FileTime.from(1_000_000, TimeUnit.SECONDS)
        fakeDevice.toDeviceState().createFile(
            DeviceFileState(
                filePath,
                fileMode.modeBits,
                (fileDate.toMillis() / 1_000).toInt(),
                fileBytes
            )
        )
        val progress = TestSyncProgress()
        val outputStream = ByteArrayOutputStream()
        val outputChannel = AdbOutputStreamChannel(adbSession, outputStream)

        val filePath2 = "/sdcard/foo/bar2.bin"
        val fileBytes2 = createFileBytes(12)
        val fileMode2 = RemoteFileMode.fromPosixPermissions(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE
        )
        val fileDate2 = FileTime.from(2_000_000, TimeUnit.SECONDS)
        fakeDevice.toDeviceState().createFile(
            DeviceFileState(
                filePath2,
                fileMode2.modeBits,
                (fileDate2.toMillis() / 1_000).toInt(),
                fileBytes2
            )
        )
        val progress2 = TestSyncProgress()
        val outputStream2 = ByteArrayOutputStream()
        val outputChannel2 = AdbOutputStreamChannel(adbSession, outputStream2)

        // Act
        fakeDevice.fileSystem.withSyncServices {
            it.recv(
                filePath,
                outputChannel,
                progress,
                bufferSize = 1_024
            )

            it.recv(
                filePath2,
                outputChannel2,
                progress2,
                bufferSize = 1_024
            )
        }

        // Assert
        Assert.assertTrue(progress.started)
        Assert.assertTrue(progress.progress)
        Assert.assertTrue(progress.done)

        Assert.assertArrayEquals(fileBytes, outputStream.toByteArray())

        Assert.assertTrue(progress2.started)
        Assert.assertTrue(progress2.progress)
        Assert.assertTrue(progress2.done)

        Assert.assertArrayEquals(fileBytes2, outputStream2.toByteArray())
    }

    @Test
    fun testSyncSendThenRecvFileInSameSessionWorks(): Unit = runBlockingWithTimeout {
        // Prepare
        val fakeDevice = addFakeConnectedDevice()

        val filePath = "/sdcard/foo/bar.bin"
        val fileBytes = createFileBytes(10_000)
        val fileMode = RemoteFileMode.fromPosixPermissions(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE
        )
        val fileDate = FileTime.from(1_000_000, TimeUnit.SECONDS)
        val sendProgress = TestSyncProgress()

        val recvProgress = TestSyncProgress()
        val outputStream = ByteArrayOutputStream()
        val outputChannel = AdbOutputStreamChannel(adbSession, outputStream)

        // Act
        fakeDevice.fileSystem.withSyncServices {
            it.send(
                AdbInputStreamChannel(adbSession.host, fileBytes.inputStream()),
                filePath,
                fileMode,
                fileDate,
                sendProgress,
                bufferSize = 1_024
            )
            it.recv(
                filePath,
                outputChannel,
                recvProgress,
                bufferSize = 1_024
            )
        }

        // Assert
        Assert.assertTrue(sendProgress.started)
        Assert.assertTrue(sendProgress.progress)
        Assert.assertTrue(sendProgress.done)

        Assert.assertNotNull(fakeDevice.toDeviceState().getFile(filePath))
        fakeDevice.toDeviceState().getFile(filePath)?.run {
            Assert.assertEquals(filePath, path)
            Assert.assertEquals(fileMode.modeBits, permission)
            Assert.assertEquals(fileDate.toMillis() / 1_000, modifiedDate.toLong())
            Assert.assertArrayEquals(fileBytes, bytes)
        }

        Assert.assertTrue(recvProgress.started)
        Assert.assertTrue(recvProgress.progress)
        Assert.assertTrue(recvProgress.done)

        Assert.assertArrayEquals(fileBytes, outputStream.toByteArray())
    }

    /**
     * This test tries do download a file that does not exist on the device
     */
    @Test
    fun testSyncRecvFileThrowsExceptionIfFileDoesNotExist(): Unit = runBlockingWithTimeout {
        // Prepare
        val fakeDevice = addFakeConnectedDevice()

        val filePath = "/sdcard/foo/bar.bin"
        val progress = TestSyncProgress()
        val outputStream = ByteArrayOutputStream()
        val outputChannel = AdbOutputStreamChannel(adbSession, outputStream)

        // Act
        exceptionRule.expect(AdbFailResponseException::class.java)
        fakeDevice.fileSystem.receiveFile(
            filePath,
            outputChannel,
            progress,
            bufferSize = 1_024
        )

        // Assert
        Assert.fail() // Should not be reachable
    }

    @Test
    fun testSyncRecvRethrowsProgressException(): Unit = runBlockingWithTimeout {
        // Prepare
        val fakeDevice = addFakeConnectedDevice()

        val filePath = "/sdcard/foo/bar.bin"
        val fileBytes = createFileBytes(128_000)
        val fileMode = RemoteFileMode.fromPosixPermissions(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE
        )
        val fileDate = FileTime.from(1_000_000, TimeUnit.SECONDS)
        fakeDevice.toDeviceState().createFile(
            DeviceFileState(
                filePath,
                fileMode.modeBits,
                (fileDate.toMillis() / 1_000).toInt(),
                fileBytes
            )
        )
        val progress = object : TestSyncProgress() {
            override suspend fun transferProgress(remotePath: String, totalBytesSoFar: Long) {
                throw MyTestException("An error in progress callback")
            }
        }
        val outputStream = ByteArrayOutputStream()
        val outputChannel = AdbOutputStreamChannel(adbSession, outputStream)

        // Act
        exceptionRule.expect(MyTestException::class.java)
        fakeDevice.fileSystem.receiveFile(
            filePath,
            outputChannel,
            progress,
            bufferSize = 1_024
        )

        // Assert
        Assert.fail() // Should not be reached
    }

    @Test
    fun testSyncRecvRethrowsOutputChannelException(): Unit = runBlockingWithTimeout {
        // Prepare
        val fakeDevice = addFakeConnectedDevice()

        val filePath = "/sdcard/foo/bar.bin"
        val fileBytes = createFileBytes(128_000)
        val fileMode = RemoteFileMode.fromPosixPermissions(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE
        )
        val fileDate = FileTime.from(1_000_000, TimeUnit.SECONDS)
        fakeDevice.toDeviceState().createFile(
            DeviceFileState(
                filePath,
                fileMode.modeBits,
                (fileDate.toMillis() / 1_000).toInt(),
                fileBytes
            )
        )
        val progress = TestSyncProgress()
        val outputChannel = object : AdbOutputChannel {
            override suspend fun writeBuffer(buffer: ByteBuffer, timeout: Long, unit: TimeUnit) {
                throw MyTestException("this stream simulates an error writing to local storage")
            }

            override fun close() {
            }
        }

        // Act
        exceptionRule.expect(MyTestException::class.java)
        fakeDevice.fileSystem.receiveFile(
            filePath,
            outputChannel,
            progress,
            bufferSize = 1_024
        )

        // Assert
        Assert.fail() // Should not be reached
    }

    @Test
    fun testReverseForward(): Unit = runBlockingWithTimeout {
        // Prepare
        val fakeDevice = addFakeConnectedDevice()

        // Act
        val port = fakeDevice.reverseForward.add(SocketSpec.Tcp(), SocketSpec.Tcp(4000))

        // Assert
        Assert.assertTrue(port != null && port.toInt() > 0)
    }

    @Test
    fun testReverseForwardNoRebind(): Unit = runBlockingWithTimeout {
        // Prepare
        val fakeDevice = addFakeConnectedDevice()
        val port = fakeDevice.reverseForward.add(
            SocketSpec.Tcp(),
            SocketSpec.Tcp(4000)
        )?.toIntOrNull()
            ?: throw AssertionError("Port should have been an integer")

        // Act
        exceptionRule.expect(AdbFailResponseException::class.java)
        runBlocking {
            fakeDevice.reverseForward.add(
                SocketSpec.Tcp(port),
                SocketSpec.Tcp(4000),
                rebind = false
            )
        }

        // Assert
        Assert.fail()
    }

    @Test
    fun testReverseForwardRebind(): Unit = runBlockingWithTimeout {
        // Prepare
        val fakeDevice = addFakeConnectedDevice()
        val port = fakeDevice.reverseForward.add(
            SocketSpec.Tcp(),
            SocketSpec.Tcp(4000)
        )?.toIntOrNull() ?: throw AssertionError("Port should have been an integer")

        // Act
        val port2 = fakeDevice.reverseForward.add(
            SocketSpec.Tcp(port),
            SocketSpec.Tcp(4000),
            rebind = true
        )

        // Assert
        Assert.assertNull(port2)
    }

    @Test
    fun testReverseKillForward(): Unit = runBlockingWithTimeout {
        // Prepare
        val fakeDevice = addFakeConnectedDevice()
        val port = fakeDevice.reverseForward.add(
            SocketSpec.Tcp(),
            SocketSpec.Tcp(4000)
        ) ?: throw Exception("`forward` command should have returned a port")
        Assert.assertEquals(1, fakeDevice.toDeviceState().allReversePortForwarders.size)

        // Act
        fakeDevice.reverseForward.kill(SocketSpec.Tcp(port.toInt()))

        // Assert
        Assert.assertEquals(0, fakeDevice.toDeviceState().allReversePortForwarders.size)
    }

    @Test
    fun testReverseKillForwardAll(): Unit = runBlockingWithTimeout {
        // Prepare
        val fakeDevice = addFakeConnectedDevice()
        fakeDevice.reverseForward.add(
            SocketSpec.Tcp(),
            SocketSpec.Tcp(4000)
        )
        Assert.assertEquals(1, fakeDevice.toDeviceState().allReversePortForwarders.size)

        // Act
        fakeDevice.reverseForward.killAll()

        // Assert
        Assert.assertEquals(0, fakeDevice.toDeviceState().allPortForwarders.size)
    }

    @Test
    fun testReverseListForward(): Unit = runBlockingWithTimeout {
        // Prepare
        val fakeDevice = addFakeConnectedDevice()
        fakeDevice.reverseForward.add(
            SocketSpec.Tcp(1000),
            SocketSpec.Tcp(4000)
        )
        Assert.assertEquals(1, fakeDevice.toDeviceState().allReversePortForwarders.size)

        // Act
        val reverseList = fakeDevice.reverseForward.list()

        // Assert
        Assert.assertEquals(1, reverseList.size)
        Assert.assertEquals(0, reverseList.errors.size)
        reverseList[0].let { forwardEntry ->
            Assert.assertEquals("UsbFfs", forwardEntry.transportName)
            Assert.assertEquals("tcp:1000", forwardEntry.remote.toQueryString())
            Assert.assertEquals("tcp:4000", forwardEntry.local.toQueryString())
        }
    }

    @Test
    fun testRoot(): Unit = runBlockingWithTimeout {
        // Prepare
        val fakeDevice = addFakeConnectedDevice()

        // Act
        val status = fakeDevice.root()

        // Assert
        Assert.assertTrue(status.restarting)
        Assert.assertTrue(status.status.startsWith("restarting"))
        Assert.assertEquals(status.rawStatus, status.status + '\n')
    }

    @Test
    fun testUnRoot(): Unit = runBlockingWithTimeout {
        // Prepare
        val fakeDevice = addFakeConnectedDevice()
        fakeAdb.fakeAdbServer.restartDeviceAsync(fakeDevice.toDeviceState()) {
            it.copy(isRoot = true)
        }.await()

        // Act
        val status = fakeDevice.unRoot()

        // Assert
        Assert.assertTrue(status.restarting)
        Assert.assertTrue(status.status.startsWith("restarting"))
        Assert.assertEquals(status.rawStatus, status.status + '\n')
    }

    @Test
    fun testUnRootIfAlreadyUnRoot(): Unit = runBlockingWithTimeout {
        // Prepare
        val fakeDevice = addFakeConnectedDevice()

        // Act
        val status = fakeDevice.unRoot()

        // Assert
        Assert.assertFalse(status.restarting)
        Assert.assertEquals(status.rawStatus, status.status + '\n')
    }

    @Test
    fun testRootAndWait(): Unit = runBlockingWithTimeout {
        // Prepare
        val fakeDevice = addFakeConnectedDevice()

        // Act
        val status = fakeDevice.rootAndWait()

        // Assert
        Assert.assertTrue(status.restarting)
        Assert.assertTrue(status.status.startsWith("restarting"))
        Assert.assertEquals(status.rawStatus, status.status + '\n')
    }

    @Test
    fun testUnRootAndWait(): Unit = runBlockingWithTimeout {
        // Prepare
        val fakeDevice = addFakeConnectedDevice()
        fakeAdb.fakeAdbServer.restartDeviceAsync(fakeDevice.toDeviceState()) {
            it.copy(isRoot = true)
        }.await()

        // Act
        val status = fakeDevice.unRootAndWait()

        // Assert
        Assert.assertTrue(status.restarting)
        Assert.assertTrue(status.status.startsWith("restarting"))
        Assert.assertEquals(status.rawStatus, status.status + '\n')
    }

    open class TestSyncProgress : SyncProgress {

        var started = false
        var progress = false
        var done = false

        override suspend fun transferStarted(remotePath: String) {
            started = true
        }

        override suspend fun transferProgress(remotePath: String, totalBytesSoFar: Long) {
            progress = true
        }

        override suspend fun transferDone(remotePath: String, totalBytes: Long) {
            done = true
        }
    }

    private fun createFileBytes(size: Int): ByteArray {
        val result = ByteArray(size)
        for (i in 0 until size) {
            result[i] = (i and 0xff).toByte()
        }
        return result
    }

    class MyTestException(message: String) : IOException(message)

    private suspend fun addFakeConnectedDevice(sdk: Int = 30): ConnectedDevice {
        val deviceState =
            fakeAdbRule.fakeAdb.connectDevice(
                "1234",
                "test1",
                "test2",
                "model",
                "$sdk",
                com.android.fakeadbserver.DeviceState.HostConnectionType.USB
            )
        deviceState.deviceStatus = com.android.fakeadbserver.DeviceState.DeviceStatus.ONLINE
        val connectedDevice =
            fakeAdbRule.adbSession.connectedDevicesTracker.waitForDevice(deviceState.deviceId)
        connectedDevice.deviceInfoFlow.first { it.deviceState == com.android.adblib.DeviceState.ONLINE }
        return connectedDevice
    }

    private suspend fun ConnectedDevice.toDeviceState(): com.android.fakeadbserver.DeviceState {
        return fakeAdbRule.fakeAdb.device(serialNumber)
    }

    companion object {

        @JvmStatic
        @BeforeClass
        fun before() {
            TimeWaitSocketsThrottler.throttleIfNeeded()
        }
    }
}
