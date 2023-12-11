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

import com.android.adblib.impl.SessionDeviceTracker
import com.android.adblib.testingutils.CoroutineTestUtils.runBlockingWithTimeout
import com.android.adblib.testingutils.FakeAdbServerProviderRule
import com.android.fakeadbserver.DeviceState
import com.android.fakeadbserver.devicecommandhandlers.SyncCommandHandler
import com.android.fakeadbserver.hostcommandhandlers.ListDevicesCommandHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException
import java.io.IOException
import java.time.Duration
import java.util.Collections
import kotlin.coroutines.ContinuationInterceptor

class AdbSessionTest {

    @JvmField
    @Rule
    val fakeAdbRule = FakeAdbServerProviderRule {
        installDefaultCommandHandlers()
        installDeviceHandler(SyncCommandHandler())
    }

    private val fakeAdb get() = fakeAdbRule.fakeAdb
    private val session get() = fakeAdbRule.adbSession
    private val hostServices get() = session.hostServices
    private val host get() = fakeAdbRule.host

    @JvmField
    @Rule
    var exceptionRule: ExpectedException = ExpectedException.none()

    @Test
    fun testSessionScopeUsesSupervisorJob(): Unit = runBlockingWithTimeout {
        // Prepare

        // Act
        val job1 = session.scope.launch {
            // Throwing an exception here cancels this job, but should not
            // cancel the "session.scope" job.
            throw IOException("MyException")
        }
        job1.join()

        val job2 = session.scope.async {
            "A test string"
        }

        // Assert
        Assert.assertTrue(job1.isCancelled)
        Assert.assertFalse(session.scope.coroutineContext.job.isCancelled)
        Assert.assertFalse(session.scope.coroutineContext.job.isCompleted)
        Assert.assertTrue(session.scope.coroutineContext.job.isActive)
        Assert.assertEquals("A test string",  job2.await())
        Assert.assertEquals(1, host.uncaughtExceptions.size)
        Assert.assertEquals("MyException", host.uncaughtExceptions[0].message)
    }

    @Test
    fun testSessionScopeUsesHostDispatcher(): Unit = runBlockingWithTimeout {
        // Prepare

        // Act
        val sessionDispatcher = session.scope.async {
            currentCoroutineContext()[ContinuationInterceptor.Key]
        }.await()

        // Assert
        Assert.assertSame(host.ioDispatcher, sessionDispatcher)
    }

    @Test
    fun testSessionShouldReturnHostServices(): Unit = runBlockingWithTimeout {
        // Prepare

        // Act
        val services = session.hostServices
        val version = services.version()

        // Assert
        Assert.assertTrue(version > 0)
    }

    @Test
    fun testSessionShouldReturnDeviceServices(): Unit = runBlockingWithTimeout {
        // Prepare

        // Act
        /*val services = */ session.deviceServices
    }

    @Test
    fun testSessionShouldThrowIfClosed(): Unit = runBlockingWithTimeout {
        // Prepare

        // Act
        session.close()
        exceptionRule.expect(ClosedSessionException::class.java)
        /*val services = */ session.hostServices

        // Assert
        Assert.fail("Should be unreachable")
    }

    @Test
    fun testTrackDevicesIsStartedEagerly(): Unit = runBlockingWithTimeout {
        // Prepare
        val fakeDevice = fakeAdb.connectDevice(
            "1234",
            "test1",
            "test2",
            "model",
            "sdk",
            DeviceState.HostConnectionType.USB
        )
        fakeDevice.deviceStatus = DeviceState.DeviceStatus.ONLINE

        // Act
        // Ensure the connected device shows in the stateFlow.value property even
        // if nobody is consuming the flow
        yieldUntil {
            session.trackDevices().value.devices.isNotEmpty()
        }
        val device = session.trackDevices().value.devices.first()

        // Assert
        Assert.assertEquals("1234", device.serialNumber)
        Assert.assertEquals(com.android.adblib.DeviceState.ONLINE, device.deviceState)
        Assert.assertEquals("test1", device.product)
        Assert.assertEquals("test2", device.model)
        Assert.assertEquals("model", device.device)
        Assert.assertEquals(fakeDevice.transportId.toString(), device.transportId)
        Assert.assertNull(device.maxSpeed)
        Assert.assertNull(device.negotiatedSpeed)
        Assert.assertNull(device.connectionType)
    }

    @Test
    fun testTraceDevicesWithProtobuffer(): Unit = runBlockingWithTimeout {
        fakeAdb.fakeAdbServer.features = fakeAdb.fakeAdbServer.features.toMutableSet().also{
            it.add(AdbFeatures.DEVICE_LIST_BINARY_PROTO)
        }

        val fakeDevice = fakeAdb.connectDevice(
            "deviceID",
            "manufacturer",
            "deviceModel",
            "FakePixel device",
            "sdk",
            DeviceState.HostConnectionType.USB
        )
        fakeDevice.deviceStatus = DeviceState.DeviceStatus.ONLINE

        yieldUntil {
            session.trackDevices().value.devices.isNotEmpty()
        }
        val device = session.trackDevices().value.devices.first()

        Assert.assertEquals("deviceID", device.serialNumber)
        Assert.assertEquals(com.android.adblib.DeviceState.ONLINE, device.deviceState)
        Assert.assertEquals("manufacturer", device.product)
        Assert.assertEquals("deviceModel", device.model)
        Assert.assertEquals(fakeDevice.transportId.toString(), device.transportId)
        Assert.assertEquals(ListDevicesCommandHandler.DEFAULT_SPEED, device.maxSpeed)
        Assert.assertEquals(ListDevicesCommandHandler.DEFAULT_SPEED, device.negotiatedSpeed)
        Assert.assertEquals(DeviceConnectionType.USB, device.connectionType)
        Assert.assertEquals("FakePixel device", device.device)
    }

    @Test
    fun testTrackDevicesRetriesOnError(): Unit = runBlockingWithTimeout {
        // Prepare
        val fakeDevice =
            fakeAdb.connectDevice(
                "1234",
                "test1",
                "test2",
                "model",
                "sdk",
                DeviceState.HostConnectionType.USB
            )
        fakeDevice.deviceStatus = DeviceState.DeviceStatus.ONLINE

        // Act
        val flow = hostServices.session.trackDevices(retryDelay = Duration.ofSeconds(1))

        // Collect first list of devices, restart adb server, collect another list of devices
        val deviceListArray = ArrayList<TrackedDeviceList>()
        launch {
            flow.collect { trackedDeviceList ->
                hostServices.session.host.logger.debug { "Collected: $trackedDeviceList" }
                deviceListArray.add(trackedDeviceList)
                if (trackedDeviceList.devices.size > 0) {
                    if (deviceListArray.count { it.devices.isNotEmpty() } == 1) {
                        // Simulate ADB server killed and restarted
                        fakeAdb.restart()
                    }
                    if (deviceListArray.count { it.devices.isNotEmpty() } == 2) {
                        // Cancel
                        currentCoroutineContext().cancel()
                    }
                }
            }
        }.join()

        // Assert
        // Note: Given how `stateIn` behaves, i.e. it runs a coroutine concurrently that does not
        // guarantee all values are delivered to all collectors, there is no guarantee that our
        // test collector collects the initial state as well as the error state (between retries),
        // however if we collect empty device list, we know for sure they should be one of these
        // states.
        Assert.assertTrue(deviceListArray.size >= 2)
        Assert.assertEquals(2, deviceListArray.count { it.devices.isNotEmpty() })
        var previousConnectionId: Int? = null
        var isTrackerDisconnectedSeen = false
        deviceListArray.forEach { deviceList ->
            if (previousConnectionId != null) {
                Assert.assertNotEquals(deviceList.connectionId, previousConnectionId)
            }
            previousConnectionId = deviceList.connectionId
            Assert.assertNotNull(deviceList)
            if (deviceList.devices.isEmpty()) {
                Assert.assertTrue(deviceList.isTrackerConnecting || deviceList.isTrackerDisconnected)
                if (deviceList.isTrackerConnecting) {
                    Assert.assertFalse(isTrackerDisconnectedSeen)
                } else if (deviceList.isTrackerDisconnected) {
                    isTrackerDisconnectedSeen = true
                }
            } else {
                Assert.assertEquals(1, deviceList.devices.size)
                Assert.assertEquals(0, deviceList.devices.errors.size)
                deviceList.devices[0].let { device ->
                    Assert.assertEquals("1234", device.serialNumber)
                    Assert.assertEquals(com.android.adblib.DeviceState.ONLINE, device.deviceState)
                    Assert.assertEquals("test1", device.product)
                    Assert.assertEquals("test2", device.model)
                    Assert.assertEquals("model", device.device)
                    Assert.assertEquals(fakeDevice.transportId.toString(), device.transportId)
                }
            }
        }
    }

    @Test
    fun testTrackDevicesOpensOnlyOneAdbConnection(): Unit = runBlockingWithTimeout {
        // Prepare
        val fakeDevice =
            fakeAdb.connectDevice(
                "1234",
                "test1",
                "test2",
                "model",
                "sdk",
                DeviceState.HostConnectionType.USB
            )
        fakeDevice.deviceStatus = DeviceState.DeviceStatus.ONLINE

        // Act
        val flow = hostServices.session.trackDevices(retryDelay = Duration.ofMillis(100))

        val started = Array(10) { false }
        suspend fun waitAllStarted() {
            while (!started.all { it }) {
                delay(10)
            }
        }

        fun launchCollector(scope: CoroutineScope, index: Int): Job {
            return scope.launch {
                flow.collect { trackedDeviceList ->
                    hostServices.session.host.logger.debug { "Collected: $trackedDeviceList" }
                    if (trackedDeviceList.devices.size == 1) {
                        started[index] = true
                        // Wait for the other collector
                        waitAllStarted()
                    }
                }
            }
        }

        // Ensure we have all collectors active at the same time
        val job = launch {
            // Launch collectors concurrently
            val jobs = started.mapIndexed { index, _ ->
                launchCollector(this, index)
            }

            // Wait for all collectors to have collected at least one element
            waitAllStarted()

            // Cancel all collector jobs so this scope can finish
            jobs.forEach { it.cancel() }
        }
        job.join()

        // Assert that get got two channels. One for features retrieval, the other to get the list
        // of devices
        Assert.assertEquals(2, fakeAdb.channelProvider.createdChannels.size)
    }

    @Test
    fun testTrackDevicesKeepsWorkingAfterExceptionsInDownstreamCollector(): Unit = runBlockingWithTimeout {
        // Prepare
        val fakeDevice =
            fakeAdb.connectDevice(
                "1234",
                "test1",
                "test2",
                "model",
                "sdk",
                DeviceState.HostConnectionType.USB
            )
        fakeDevice.deviceStatus = DeviceState.DeviceStatus.ONLINE

        // Act
        val exceptions = Collections.synchronizedList(ArrayList<MyTestException>())
        coroutineScope {
            val flow = hostServices.session
                .trackDevices(retryDelay = Duration.ofMillis(100))

            val started = Array(10) { false }

            suspend fun waitAllStarted() {
                while (!started.all { it }) {
                    delay(10)
                }
            }

            // Every other collector throws an exception after receiving the first list
            suspend fun launchCollector(index: Int): Job {
                return launch {
                    try {
                        flow.collect { trackedDeviceList ->
                            hostServices.session.host.logger.debug { "Collected: $trackedDeviceList" }
                            if (trackedDeviceList.devices.size == 1) {
                                // Wait for the other collectors so we all remain active
                                started[index] = true
                                waitAllStarted()

                                // Half the collectors throw an exception
                                if (index.mod(2) == 0) {
                                    throw MyTestException("Test")
                                } else {
                                    cancel()
                                }
                            }
                        }
                    } catch (e: MyTestException) {
                        exceptions.add(e)
                    }
                }
            }

            val job = launch {
                // Launch collectors concurrently
                started.mapIndexed { index, _ ->
                    launchCollector(index)
                }

                // Wait for all collectors to have collected at least one element
                waitAllStarted()
            }
            job.join()
        }

        // Assert
        // We expect two channels. One to retrieve features, the other one to get the devices list.
        Assert.assertEquals(2, fakeAdb.channelProvider.createdChannels.size)
        Assert.assertEquals(5, exceptions.size)
    }

    @Test
    fun testTrackDeviceInfoWorks(): Unit = runBlockingWithTimeout {
        // Prepare
        val fakeDevice =
            fakeAdb.connectDevice(
                "1234",
                "test1",
                "test2",
                "model",
                "sdk",
                DeviceState.HostConnectionType.USB
            )
        val deviceSelector = DeviceSelector.fromSerialNumber(fakeDevice.deviceId)

        // Act
        val deviceInfoList = mutableListOf<DeviceInfo>()
        val channel = Channel<DeviceInfo>(Channel.UNLIMITED)
        val job = launch {
            hostServices.session.trackDeviceInfo(deviceSelector).collect {
                channel.send(it)
            }
        }

        // UNAUTHORIZED device state
        fakeDevice.deviceStatus = DeviceState.DeviceStatus.UNAUTHORIZED
        deviceInfoList.add(channel.receive())

        // ONLINE device state
        fakeDevice.deviceStatus = DeviceState.DeviceStatus.ONLINE
        deviceInfoList.add(channel.receive())

        // Disconnect device
        fakeAdb.disconnectDevice(fakeDevice.deviceId)

        job.join()

        // Assert
        Assert.assertEquals(2, deviceInfoList.size)
        Assert.assertEquals(
            listOf(
                com.android.adblib.DeviceState.UNAUTHORIZED,
                com.android.adblib.DeviceState.ONLINE
            ), deviceInfoList.map { it.deviceState }
        )
    }

    @Test
    fun testTrackDeviceInfoStopAfterDeviceDisconnects(): Unit = runBlockingWithTimeout {
        // Prepare
        val fakeDevice =
            fakeAdb.connectDevice(
                "1234",
                "test1",
                "test2",
                "model",
                "sdk",
                DeviceState.HostConnectionType.USB
            )
        val deviceSelector = DeviceSelector.fromSerialNumber(fakeDevice.deviceId)

        // Act
        val channel = Channel<DeviceInfo>(Channel.UNLIMITED)
        val job = launch {
            hostServices.session.trackDeviceInfo(deviceSelector).collect {
                channel.send(it)
            }
        }

        // ONLINE device state
        fakeDevice.deviceStatus = DeviceState.DeviceStatus.ONLINE
        val deviceInfo = channel.receive()

        // Disconnect device
        fakeAdb.disconnectDevice(fakeDevice.deviceId)

        job.join()

        // Assert
        Assert.assertEquals(com.android.adblib.DeviceState.ONLINE, deviceInfo.deviceState)
    }

    @Test
    fun testTrackDeviceInfoStopsAfterAdbRestart(): Unit = runBlockingWithTimeout {
        // Prepare
        val fakeDevice =
            fakeAdb.connectDevice(
                "1234",
                "test1",
                "test2",
                "model",
                "sdk",
                DeviceState.HostConnectionType.USB
            )
        val deviceSelector = DeviceSelector.fromSerialNumber(fakeDevice.deviceId)

        // Act
        fakeDevice.deviceStatus = DeviceState.DeviceStatus.UNAUTHORIZED
        val deviceInfoList = mutableListOf<DeviceInfo>()
        val channel = Channel<DeviceInfo>(Channel.UNLIMITED)
        val job = launch {
            hostServices.session.trackDeviceInfo(deviceSelector).collect {
                channel.send(it)
            }
        }

        // UNAUTHORIZED device state
        deviceInfoList.add(channel.receive())

        // Restart ADB to force new connection ID
        fakeAdb.restart()

        // Update device state to ONLINE and wait a little
        fakeDevice.deviceStatus = DeviceState.DeviceStatus.ONLINE

        job.join()

        // Assert
        Assert.assertEquals(1, deviceInfoList.size)
        Assert.assertEquals(
            listOf(
                com.android.adblib.DeviceState.UNAUTHORIZED,
            ), deviceInfoList.map { it.deviceState }
        )
    }

    @Test
    fun testTrackDeviceInfoEndsIfDeviceNotFound(): Unit = runBlockingWithTimeout {
        // Prepare
        fakeAdb.connectDevice(
            "1234",
            "test1",
            "test2",
            "model",
            "sdk",
            DeviceState.HostConnectionType.USB
        )
        val deviceSelector = DeviceSelector.fromSerialNumber("abcd")

        // Act
        val deviceInfoList = hostServices.session.trackDeviceInfo(deviceSelector).toList()

        // Assert
        Assert.assertEquals(0, deviceInfoList.size)
    }

    @Test
    fun testTrackDeviceInfoEndsIfNoDeviceConnected(): Unit = runBlockingWithTimeout {
        // Prepare
        val deviceSelector = DeviceSelector.fromSerialNumber("1234")

        // Act
        val deviceInfoList = hostServices.session.trackDeviceInfo(deviceSelector).toList()

        // Assert
        Assert.assertEquals(0, deviceInfoList.size)
    }

    @Test
    fun testDeviceCoroutineScopeWorksForOnlineDevice(): Unit = runBlockingWithTimeout {
        // Prepare
        val fakeDevice =
            fakeAdb.connectDevice(
                "1234",
                "test1",
                "test2",
                "model",
                "sdk",
                DeviceState.HostConnectionType.USB
            )
        fakeDevice.deviceStatus = DeviceState.DeviceStatus.ONLINE
        val deviceSelector = DeviceSelector.fromSerialNumber(fakeDevice.deviceId)

        // Act
        var deviceCoroutineIsRunning = false
        // Wait for device to show up in device tracker
        session.hostServices.trackDevices().first {
            it.size == 1
        }

        // Create coroutine scope for device
        val deviceScope = session.createDeviceScope(deviceSelector)
        val job = deviceScope.launch {
            deviceCoroutineIsRunning = true
            try {
                while (true) {
                    delay(20)
                }
            } finally {
                deviceCoroutineIsRunning = false
            }
        }

        // Wait for coroutine to start
        while (!deviceCoroutineIsRunning) {
            yield()
        }

        // Disconnect device
        fakeAdb.disconnectDevice(fakeDevice.deviceId)

        // Wait for coroutine to stop
        job.join()

        // Assert
        Assert.assertFalse(deviceCoroutineIsRunning)
    }

    @Test
    fun testDeviceCoroutineScopeWorksForDisconnectedDevice(): Unit = runBlockingWithTimeout {
        // Prepare
        val deviceSelector = DeviceSelector.fromSerialNumber("1234")

        // Act
        var deviceCoroutineIsRunning = false
        // Create coroutine scope for device
        val deviceScope = session.createDeviceScope(deviceSelector)
        val job = deviceScope.launch {
            deviceCoroutineIsRunning = true
            try {
                while (true) {
                    delay(20)
                }
            } finally {
                deviceCoroutineIsRunning = false
            }
        }

        // Wait for coroutine to stop
        job.join()

        // Assert
        Assert.assertFalse(deviceCoroutineIsRunning)
    }

    @Test
    fun testDeviceCoroutineScopeIsCancelledWithSessionClose(): Unit = runBlockingWithTimeout {
        // Prepare
        val fakeDevice =
            fakeAdb.connectDevice(
                "1234",
                "test1",
                "test2",
                "model",
                "sdk",
                DeviceState.HostConnectionType.USB
            )
        fakeDevice.deviceStatus = DeviceState.DeviceStatus.ONLINE
        val deviceSelector = DeviceSelector.fromSerialNumber(fakeDevice.deviceId)

        // Act
        var deviceCoroutineIsRunning = false
        // Create coroutine scope for device
        val deviceScope = session.createDeviceScope(deviceSelector)
        val job = deviceScope.launch {
            deviceCoroutineIsRunning = true
            try {
                while (true) {
                    delay(20)
                }
            } finally {
                deviceCoroutineIsRunning = false
            }
        }

        // Wait for coroutine to start
        while (!deviceCoroutineIsRunning) {
            yield()
        }

        // Close the session should cancel the device scope
        session.close()

        // Wait for coroutine to stop
        job.join()

        // Assert
        Assert.assertFalse(deviceCoroutineIsRunning)
    }

    @Test
    fun testChildSessionWorks(): Unit = runBlockingWithTimeout {
        // Prepare
        val childSession = AdbSession.createChildSession(session,
                                                         session.host,
                                                         fakeAdbRule.createChannelProvider())

        // Act
        val version = childSession.hostServices.version()

        // Assert
        Assert.assertEquals(40, version)
    }

    @Test
    fun testChildSessionScopeIsCancelledWhenParentSessionIsClosed(): Unit = runBlockingWithTimeout {
        // Prepare
        val childSession = AdbSession.createChildSession(session,
                                                         session.host,
                                                         fakeAdbRule.createChannelProvider())

        // Act
        val job = childSession.scope.launch {
            delay(10_000)
        }
        session.close()
        job.join()

        // Assert
        Assert.assertFalse(childSession.scope.isActive)
    }

    class MyTestException(message: String) : IOException(message)

    private suspend fun yieldUntil(
        timeout: Duration = Duration.ofSeconds(5),
        predicate: suspend () -> Boolean
    ) {
        try {
            withTimeout(timeout.toMillis()) {
                while (!predicate()) {
                    delay(10)
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError(
                "A yieldUntil condition was not satisfied within " +
                        "5 seconds, there is a bug somewhere (in the test or in the tested code)", e
            )
        }
    }
}

