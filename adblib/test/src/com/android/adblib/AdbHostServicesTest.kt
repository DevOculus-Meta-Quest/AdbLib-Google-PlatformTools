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

import com.android.adblib.AdbHostServices.DeviceInfoFormat.LONG_FORMAT
import com.android.adblib.AdbHostServices.DeviceInfoFormat.SHORT_FORMAT
import com.android.adblib.DeviceState.ONLINE
import com.android.adblib.impl.AdbHostServicesImpl
import com.android.adblib.testingutils.CloseablesRule
import com.android.adblib.testingutils.FakeAdbServerProvider
import com.android.adblib.testingutils.TestingAdbLibHost
import com.android.fakeadbserver.DeviceState
import com.android.fakeadbserver.MdnsService
import com.android.fakeadbserver.hostcommandhandlers.FaultyVersionCommandHandler
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Timeout for opening a socket connection to the local fake adb server.
 *
 * In most cases, socket connections are established within a few milliseconds,
 * but the time can dramatically increase under stress testing. This behavior was
 * observed on the Windows platform specifically.
 */
val SOCKET_CONNECT_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(2)

class AdbHostServicesTest {

    @JvmField
    @Rule
    val closeables = CloseablesRule()

    @JvmField
    @Rule
    var exceptionRule: ExpectedException = ExpectedException.none()

    private fun <T : AutoCloseable> registerCloseable(item: T): T {
        return closeables.register(item)
    }

    @Test
    fun testVersion() {
        // Prepare
        val fakeAdb = registerCloseable(FakeAdbServerProvider().buildDefault().start())
        val host = registerCloseable(TestingAdbLibHost())
        val channelProvider = fakeAdb.createChannelProvider(host)
        val hostServices = AdbHostServicesImpl(host, channelProvider, SOCKET_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)

        // Act
        val internalVersion = runBlocking { hostServices.version() }

        // Assert
        Assert.assertEquals(40, internalVersion)
    }

    @Test
    fun testVersionConnectionFailure() {
        // Prepare
        val fakeAdb = registerCloseable(FakeAdbServerProvider())
        val host = registerCloseable(TestingAdbLibHost())
        val channelProvider = fakeAdb.createChannelProvider(host)
        val hostServices = AdbHostServicesImpl(host, channelProvider, SOCKET_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)

        // Act (should throw)
        exceptionRule.expect(IOException::class.java)
        /*val internalVersion = */runBlocking { hostServices.version() }

        // Assert (should not reach this point)
        Assert.fail()
    }

    @Test
    fun testVersionFaultyProtocol() {
        // Prepare
        val fakeAdb = registerCloseable(FakeAdbServerProvider())
        fakeAdb.installHostHandler(FaultyVersionCommandHandler.COMMAND) { FaultyVersionCommandHandler() }
        fakeAdb.build().start()
        val host = registerCloseable(TestingAdbLibHost())
        val channelProvider = fakeAdb.createChannelProvider(host)
        val hostServices = AdbHostServicesImpl(host, channelProvider, SOCKET_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)

        // Act (should throw)
        exceptionRule.expect(AdbProtocolErrorException::class.java)
        /*val internalVersion = */runBlocking { hostServices.version() }

        // Assert (should not reach this point)
        Assert.fail()
    }

    @Test
    fun testHostFeaturesWorks() {
        // Prepare
        val fakeAdb = registerCloseable(FakeAdbServerProvider().buildDefault().start())
        val host = registerCloseable(TestingAdbLibHost())
        val channelProvider = fakeAdb.createChannelProvider(host)
        val hostServices = AdbHostServicesImpl(host, channelProvider, SOCKET_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)

        // Act
        val featureList = runBlocking { hostServices.hostFeatures() }

        // Assert
        Assert.assertTrue(featureList.contains("shell_v2"))
        Assert.assertTrue(featureList.contains("fixed_push_mkdir"))
        Assert.assertTrue(featureList.contains("push_sync"))
        Assert.assertTrue(featureList.contains("abb_exec"))
    }

    @Test
    fun testDevicesShortFormatWorks() {
        // Prepare
        val fakeAdb = registerCloseable(FakeAdbServerProvider().buildDefault().start())
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
        val host = registerCloseable(TestingAdbLibHost())
        val channelProvider = fakeAdb.createChannelProvider(host)
        val hostServices = AdbHostServicesImpl(host, channelProvider, SOCKET_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)

        // Act
        val deviceList = runBlocking { hostServices.devices(SHORT_FORMAT) }

        // Assert
        Assert.assertEquals(1, deviceList.devices.size)
        Assert.assertEquals(0, deviceList.errors.size)
        deviceList.devices[0].let { device ->
            Assert.assertEquals("1234", device.serialNumber)
            Assert.assertEquals(ONLINE, device.deviceState)
            Assert.assertNull(device.product)
            Assert.assertNull(device.model)
            Assert.assertNull(device.device)
            Assert.assertNull(device.transportId)
        }
    }

    @Test
    fun testDevicesLongFormatWorks() {
        // Prepare
        val fakeAdb = registerCloseable(FakeAdbServerProvider().buildDefault().start())
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
        val host = registerCloseable(TestingAdbLibHost())
        val channelProvider = fakeAdb.createChannelProvider(host)
        val hostServices = AdbHostServicesImpl(host, channelProvider, SOCKET_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)

        // Act
        val deviceList = runBlocking { hostServices.devices(LONG_FORMAT) }

        // Assert
        Assert.assertEquals(1, deviceList.devices.size)
        Assert.assertEquals(0, deviceList.errors.size)
        deviceList.devices[0].let { device ->
            Assert.assertEquals("1234", device.serialNumber)
            Assert.assertEquals(ONLINE, device.deviceState)
            Assert.assertEquals("test1", device.product)
            Assert.assertEquals("test2", device.model)
            Assert.assertEquals("model", device.device)
            Assert.assertEquals(fakeDevice.transportId.toString(), device.transportId)
        }
    }

    @Test
    fun testTrackDevicesWorks() {
        // Prepare
        val fakeAdb = registerCloseable(FakeAdbServerProvider().buildDefault().start())
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
        val host = registerCloseable(TestingAdbLibHost())
        val channelProvider = fakeAdb.createChannelProvider(host)
        val hostServices = AdbHostServicesImpl(host, channelProvider, SOCKET_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)

        // Act
        val deviceList = runBlocking {
            val flow = hostServices.trackDevices(LONG_FORMAT)

            // Wait for the first list of devices (and terminate the flow, since `first` is a
            // flow termination operator)
            flow.first()
        }

        // Assert
        Assert.assertEquals(1, deviceList.devices.size)
        Assert.assertEquals(0, deviceList.errors.size)
        deviceList.devices[0].let { device ->
            Assert.assertEquals("1234", device.serialNumber)
            Assert.assertEquals(ONLINE, device.deviceState)
            Assert.assertEquals("test1", device.product)
            Assert.assertEquals("test2", device.model)
            Assert.assertEquals("model", device.device)
            Assert.assertEquals(fakeDevice.transportId.toString(), device.transportId)
        }

        // Check SocketChannel has been closed
        Assert.assertNotNull(channelProvider.lastCreatedChannel)
        Assert.assertFalse(channelProvider.lastCreatedChannel!!.isOpen)
    }

    @Test
    fun testTrackDevicesPropagatesExceptions() {
        // Prepare
        val fakeAdb = registerCloseable(FakeAdbServerProvider().buildDefault().start())
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
        val host = registerCloseable(TestingAdbLibHost())
        val channelProvider = fakeAdb.createChannelProvider(host)
        val hostServices = AdbHostServicesImpl(host, channelProvider, SOCKET_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)

        // Act
        var exception: Throwable? = null
        try {
            /*val deviceList = */runBlocking {
                val flow = hostServices.trackDevices(LONG_FORMAT)

                flow.collect {
                    throw IllegalStateException()
                }
            }
        } catch(t: Throwable) {
            exception = t
        }

        // Assert
        Assert.assertNotNull(exception)
        Assert.assertTrue(exception is IllegalStateException)

        // Check SocketChannel has been closed
        Assert.assertNotNull(channelProvider.lastCreatedChannel)
        Assert.assertFalse(channelProvider.lastCreatedChannel!!.isOpen)
    }

    @Test
    fun testKillServer() {
        // Prepare
        val fakeAdb = registerCloseable(FakeAdbServerProvider().buildDefault().start())
        val host = registerCloseable(TestingAdbLibHost())
        val channelProvider = fakeAdb.createChannelProvider(host)
        val hostServices = AdbHostServicesImpl(host, channelProvider, SOCKET_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)

        // Act
        runBlocking { hostServices.kill() }

        // Note: We need to for the server to terminate before verifying sending another
        //       command fails, because the current implementation of "kill" in fakeAdbServer
        //       does not wait for the server to be fully shutdown before sending and
        //       'OKAY response.
        fakeAdb.awaitTermination()

        exceptionRule.expect(IOException::class.java)
        runBlocking { hostServices.version() }

        // Assert (should not reach this point)
        Assert.fail()
    }

    @Test
    fun testMdnsCheck() {
        // Prepare
        val fakeAdb = registerCloseable(FakeAdbServerProvider().buildDefault().start())
        val host = registerCloseable(TestingAdbLibHost())
        val channelProvider = fakeAdb.createChannelProvider(host)
        val hostServices = AdbHostServicesImpl(host, channelProvider, SOCKET_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)

        // Act
        val result = runBlocking { hostServices.mdnsCheck() }

        // Assert
        Assert.assertEquals(MdnsStatus.Enabled, result.status)
        Assert.assertTrue(result.rawText.contains("mdns daemon"))
    }

    @Test
    fun testMdnsServices() {
        // Prepare
        val fakeAdb = registerCloseable(FakeAdbServerProvider().buildDefault().start())
        val host = registerCloseable(TestingAdbLibHost())
        val channelProvider = fakeAdb.createChannelProvider(host)
        val hostServices = AdbHostServicesImpl(host, channelProvider, SOCKET_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)

        // Act
        fakeAdb.addMdnsService(
            MdnsService(
                "foo-bar",
                "service",
                InetAddress.getByName("192.168.1.1"),
                10
            )
        )
        fakeAdb.addMdnsService(
            MdnsService(
                "foo-bar2",
                "service",
                InetAddress.getByName("192.168.1.1"),
                10
            )
        )
        val result = runBlocking { hostServices.mdnsServices() }

        // Assert
        Assert.assertEquals(2, result.services.size)
        result.services[0].let { service ->
            Assert.assertEquals("foo-bar", service.instanceName)
            Assert.assertEquals("service", service.serviceName)
            Assert.assertEquals("192.168.1.1", service.ipAddress.hostAddress)
            Assert.assertEquals(10, service.port)

        }
        result.services[1].let { service ->
            Assert.assertEquals("foo-bar2", service.instanceName)
            Assert.assertEquals("service", service.serviceName)
            Assert.assertEquals("192.168.1.1", service.ipAddress.hostAddress)
            Assert.assertEquals(10, service.port)

        }
        Assert.assertEquals(0, result.errors.size)
    }

    @Test
    fun testGetState() {
        // Prepare
        val fakeAdb = registerCloseable(FakeAdbServerProvider().buildDefault().start())
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
        val host = registerCloseable(TestingAdbLibHost())
        val channelProvider = fakeAdb.createChannelProvider(host)
        val deviceServices = AdbHostServicesImpl(host, channelProvider, SOCKET_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)

        // Act
        val state = runBlocking {
            deviceServices.getState(DeviceSelector.fromSerialNumber("1234"))
        }

        // Assert
        Assert.assertEquals(ONLINE, state)
    }

    @Test
    fun testGetSerialNo() {
        // Prepare
        val fakeAdb = registerCloseable(FakeAdbServerProvider().buildDefault().start())
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
        val host = registerCloseable(TestingAdbLibHost())
        val channelProvider = fakeAdb.createChannelProvider(host)
        val hostServices = AdbHostServicesImpl(host, channelProvider, SOCKET_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)

        // Act
        val serialNumber = runBlocking {
            hostServices.getSerialNo(DeviceSelector.fromSerialNumber("1234"))
        }

        // Assert
        Assert.assertEquals("1234", serialNumber)
    }

    @Test
    fun testGetDevPath() {
        // Prepare
        val fakeAdb = registerCloseable(FakeAdbServerProvider().buildDefault().start())
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
        val host = registerCloseable(TestingAdbLibHost())
        val channelProvider = fakeAdb.createChannelProvider(host)
        val hostServices = AdbHostServicesImpl(host, channelProvider, SOCKET_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)

        // Act
        val devPath = runBlocking {
            hostServices.getDevPath(DeviceSelector.fromSerialNumber("1234"))
        }

        // Assert
        Assert.assertEquals("dev-path-reply", devPath)
    }

    @Test
    fun testFeatures() {
        // Prepare
        val fakeAdb = registerCloseable(FakeAdbServerProvider().buildDefault().start())
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
        val host = registerCloseable(TestingAdbLibHost())
        val channelProvider = fakeAdb.createChannelProvider(host)
        val hostServices = AdbHostServicesImpl(host, channelProvider, SOCKET_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)

        // Act
        val featureList = runBlocking {
            hostServices.features(DeviceSelector.fromSerialNumber("1234"))
        }

        // Assert
        Assert.assertTrue(featureList.contains("shell_v2"))
        Assert.assertTrue(featureList.contains("fixed_push_mkdir"))
        Assert.assertTrue(featureList.contains("push_sync"))
        Assert.assertTrue(featureList.contains("abb_exec"))
    }
}