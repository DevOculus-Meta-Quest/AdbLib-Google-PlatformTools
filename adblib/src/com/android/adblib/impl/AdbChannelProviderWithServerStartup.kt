/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.adblib.impl

import com.android.adblib.AdbChannel
import com.android.adblib.AdbServerChannelProvider
import com.android.adblib.AdbSessionHost
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

private const val DEFAULT_ADB_PORT = 5037

/**
 * [AdbServerChannelProvider] that can start the Adb Server if it was not located
 */
internal class AdbChannelProviderWithServerStartup(
    private val host: AdbSessionHost
) : AdbServerChannelProvider {

    private val socketAddresses = listOf(
        InetSocketAddress("127.0.0.1", DEFAULT_ADB_PORT),
        InetSocketAddress("::1", DEFAULT_ADB_PORT)
    )
    private val delegateAdbChannelProvider =
        AdbChannelProviderConnectAddresses(host) { socketAddresses }

    override suspend fun createChannel(timeout: Long, unit: TimeUnit): AdbChannel {
        val tracker = TimeoutTracker(host.timeProvider, timeout, unit)

        try {
            return delegateAdbChannelProvider.createChannel(
                tracker.remainingNanos,
                TimeUnit.NANOSECONDS
            )
        } catch (e: IOException) {
            host.logger.debug { "Couldn't open ADB connection to ADB server. Will try starting ADB server first." }
        }

        tracker.throwIfElapsed()
        startAdbServer(tracker.remainingNanos, TimeUnit.NANOSECONDS)

        return delegateAdbChannelProvider.createChannel(
            tracker.remainingNanos,
            TimeUnit.NANOSECONDS
        )
    }

    private suspend fun startAdbServer(timeout: Long, unit: TimeUnit) {
        host.logger.debug { "Starting ADB server on port $DEFAULT_ADB_PORT." }
        host.timeProvider.withErrorTimeout(timeout, unit) {
            runInterruptible(host.blockingIoDispatcher) {
                val processBuilder = ProcessBuilder(getAdbLaunchCommand())
                val process = processBuilder.start()
                val exitCode = process.waitFor()
                if (exitCode != 0) {
                    throw IOException("adb start-server failed. Exit code: $exitCode")
                }
            }
        }
    }

    private fun getAdbLaunchCommand(): List<String> {
        return listOf(getAdbFile(), "-P", DEFAULT_ADB_PORT.toString(), "start-server")
    }

    private fun getAdbFile(): String {
        val os = System.getProperty("os.name")
        val adbExecutableName = if (os.startsWith("Windows")) "adb.exe" else "adb"
        return findOnPath(adbExecutableName)
            ?: throw IOException("Couldn't locate '$adbExecutableName' on PATH")
    }

    private fun findOnPath(executableName: String): String? {
        val pathEnvVariable =
            System.getenv("PATH")
                ?: throw IOException("No PATH environmental variable is defined")
        for (binDir in pathEnvVariable.split(File.pathSeparator)) {
            val file = Paths.get(binDir).resolve(executableName).toFile()
            if (file.isFile) {
                return file.path
            }
        }
        host.logger.debug { "$executableName could not be located in any of the $pathEnvVariable folders" }
        return null
    }
}
