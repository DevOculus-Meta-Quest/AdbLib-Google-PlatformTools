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
import com.android.adblib.testingutils.FakeAdbServerProviderRule
import com.android.fakeadbserver.DeviceState
import com.android.fakeadbserver.MdnsService
import com.android.fakeadbserver.devicecommandhandlers.SyncCommandHandler
import com.android.fakeadbserver.hostcommandhandlers.FaultyVersionCommandHandler
import com.android.fakeadbserver.hostcommandhandlers.VersionCommandHandler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException
import java.io.IOException
import java.net.InetSocketAddress
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
    val fakeAdbRule = FakeAdbServerProviderRule {
        installDefaultCommandHandlers()
        installDeviceHandler(SyncCommandHandler())
    }

    private val fakeAdb get() = fakeAdbRule.fakeAdb
    private val hostServices get() = fakeAdbRule.adbSession.hostServices

    @JvmField
    @Rule
    var exceptionRule: ExpectedException = ExpectedException.none()

    @Test
    fun testConnect() {
        // Prepare
        fakeAdb.registerNetworkDevice("localhost:12345",
                                      "1234",
                                      "test1",
                                      "test2",
                                      "model",
                                      "sdk")

        // Act
        runBlocking { hostServices.connect(DeviceAddress("localhost:12345")) }

        // Assert
        runBlocking {
            Assert.assertEquals(
                ONLINE,
                hostServices.getState(DeviceSelector.fromSerialNumber("1234"))
            )
        }
    }

    @Test
    fun testDisconnect() {
        // Prepare
        fakeAdb.registerNetworkDevice("localhost:12345",
                                      "1234",
                                      "test1",
                                      "test2",
                                      "model",
                                      "sdk")
        runBlocking { hostServices.connect(DeviceAddress("localhost:12345")) }

        // Act
        runBlocking { hostServices.disconnect(DeviceAddress("localhost:12345")) }

        // Assert
        runBlocking {
            try {
                hostServices.getState(DeviceSelector.fromSerialNumber("1234"))
            } catch (expected: AdbFailResponseException) {
                Assert.assertEquals(
                    "No device with serial: '1234' is connected.",
                    expected.failMessage
                )
            }
        }
    }

    @Test
    fun testVersion() {
        // Prepare

        // Act
        val internalVersion = runBlocking { hostServices.version() }

        // Assert
        Assert.assertEquals(40, internalVersion)
    }

    @Test
    fun testVersionConnectionFailure() {
        // Prepare
        fakeAdb.stop()

        // Act (should throw)
        exceptionRule.expect(IOException::class.java)
        /*val internalVersion = */runBlocking { hostServices.version() }

        // Assert (should not reach this point)
        Assert.fail()
    }

    @Test
    fun testVersionFaultyProtocol() {
        // Prepare
        fakeAdb.installHostHandler(FaultyVersionCommandHandler())

        // Act (should throw)
        exceptionRule.expect(AdbProtocolErrorException::class.java)
        /*val internalVersion = */runBlocking { hostServices.version() }

        // Assert (should not reach this point)
        Assert.fail()
    }

    @Test
    fun testHostFeaturesWorks() {
        // Prepare

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
        val deviceList = runBlocking { hostServices.devices(SHORT_FORMAT) }

        // Assert
        Assert.assertEquals(1, deviceList.size)
        Assert.assertEquals(0, deviceList.errors.size)
        deviceList[0].let { device ->
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
        val deviceList = runBlocking { hostServices.devices(LONG_FORMAT) }

        // Assert
        Assert.assertEquals(1, deviceList.size)
        Assert.assertEquals(0, deviceList.errors.size)
        deviceList[0].let { device ->
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
        val deviceList = runBlocking {
            val flow = hostServices.trackDevices(LONG_FORMAT)

            // Wait for the first list of devices (and terminate the flow, since `first` is a
            // flow termination operator)
            flow.first()
        }

        // Assert
        Assert.assertEquals(1, deviceList.size)
        Assert.assertEquals(0, deviceList.errors.size)
        deviceList[0].let { device ->
            Assert.assertEquals("1234", device.serialNumber)
            Assert.assertEquals(ONLINE, device.deviceState)
            Assert.assertEquals("test1", device.product)
            Assert.assertEquals("test2", device.model)
            Assert.assertEquals("model", device.device)
            Assert.assertEquals(fakeDevice.transportId.toString(), device.transportId)
        }

        // Check SocketChannel has been closed
        Assert.assertNotNull(fakeAdb.channelProvider.lastCreatedChannel)
        Assert.assertFalse(fakeAdb.channelProvider.lastCreatedChannel!!.isOpen)
    }

    @Test
    fun testTrackDevicesPropagatesExceptions() {
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
        var exception: Throwable? = null
        try {
            /*val deviceList = */runBlocking {
                val flow = hostServices.trackDevices(LONG_FORMAT)

                flow.collect {
                    throw IllegalStateException()
                }
            }
        } catch (t: Throwable) {
            exception = t
        }

        // Assert
        Assert.assertNotNull(exception)
        Assert.assertTrue(exception is IllegalStateException)

        // Check SocketChannel has been closed
        Assert.assertNotNull(fakeAdb.channelProvider.lastCreatedChannel)
        Assert.assertFalse(fakeAdb.channelProvider.lastCreatedChannel!!.isOpen)
    }

    @Test
    fun testKillServer() {
        // Prepare

        // Act
        runBlocking { hostServices.kill() }

        // Note: We need to for the server to terminate before verifying sending another
        //       command fails, because the current implementation of "kill" in fakeAdbServer
        //       does not wait for the server to be fully shutdown before sending and
        //       'OKAY' response.
        fakeAdb.awaitTermination()

        exceptionRule.expect(IOException::class.java)
        runBlocking { hostServices.version() }

        // Assert (should not reach this point)
        Assert.fail()
    }

    @Test
    fun testMdnsCheck() {
        // Prepare

        // Act
        val result = runBlocking { hostServices.mdnsCheck() }

        // Assert
        Assert.assertEquals(MdnsStatus.Enabled, result.status)
        Assert.assertTrue(result.rawText.contains("mdns daemon"))
    }

    @Test
    fun testMdnsServices() {
        // Prepare

        // Act
        fakeAdb.addMdnsService(
            MdnsService(
                "foo-bar",
                "service",
                InetSocketAddress.createUnresolved("192.168.1.1", 10)
            )
        )
        fakeAdb.addMdnsService(
            MdnsService(
                "foo-bar2",
                "service",
                InetSocketAddress.createUnresolved("192.168.1.1", 11)
            )
        )
        val result = runBlocking { hostServices.mdnsServices() }

        // Assert
        Assert.assertEquals(2, result.size)
        result[0].let { service ->
            Assert.assertEquals("foo-bar", service.instanceName)
            Assert.assertEquals("service", service.serviceName)
            Assert.assertEquals("192.168.1.1:10", service.deviceAddress.address)

        }
        result[1].let { service ->
            Assert.assertEquals("foo-bar2", service.instanceName)
            Assert.assertEquals("service", service.serviceName)
            Assert.assertEquals("192.168.1.1:11", service.deviceAddress.address)

        }
        Assert.assertEquals(0, result.errors.size)
    }

    @Test
    fun testPair() {
        // Prepare
        fakeAdb.addMdnsService(
            MdnsService(
                "foo-bar2",
                "service",
                InetSocketAddress.createUnresolved("foo", 11)
            )
        )

        // Act
        val result = runBlocking {
            hostServices.pair(DeviceAddress("foo:11"), "123456")
        }

        // Assert
        Assert.assertTrue(result.success)
    }

    @Test
    fun testPairFailsIfDeviceNotPresent() {
        // Prepare

        // Act
        val result = runBlocking {
            hostServices.pair(DeviceAddress("foo:100"), "123456")
        }

        // Assert
        Assert.assertFalse(result.success)
    }

    @Test
    fun testGetState() {
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
        val state = runBlocking {
            hostServices.getState(DeviceSelector.fromSerialNumber("1234"))
        }

        // Assert
        Assert.assertEquals(ONLINE, state)
    }

    @Test
    fun testGetSerialNo() {
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
        val serialNumber = runBlocking {
            hostServices.getSerialNo(DeviceSelector.any())
        }

        // Assert
        Assert.assertEquals("1234", serialNumber)
    }

    @Test
    fun testGetSerialNoUsesKnownSerialNumber() {
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
        val serialNumber = runBlocking {
            hostServices.getSerialNo(DeviceSelector.fromSerialNumber("1234"))
        }

        // Assert
        Assert.assertEquals("1234", serialNumber)
        Assert.assertTrue(fakeAdb.channelProvider.createdChannels.isEmpty())
    }

    @Test
    fun testGetSerialNoConnectsWhenForceRoundTripIsTrue() {
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
        val serialNumber = runBlocking {
            hostServices.getSerialNo(DeviceSelector.fromSerialNumber("1234"), forceRoundTrip = true)
        }

        // Assert
        Assert.assertEquals("1234", serialNumber)
        Assert.assertEquals(1, fakeAdb.channelProvider.createdChannels.size)
    }

    @Test
    fun testGetSerialConnectsWhenNoSerialNumber() {
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
        val serialNumber = runBlocking {
            hostServices.getSerialNo(DeviceSelector.usb())
        }

        // Assert
        Assert.assertEquals("1234", serialNumber)
        Assert.assertEquals(1, fakeAdb.channelProvider.createdChannels.size)
    }

    @Test
    fun testGetDevPath() {
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
        val devPath = runBlocking {
            hostServices.getDevPath(DeviceSelector.fromSerialNumber("1234"))
        }

        // Assert
        Assert.assertEquals("dev-path-reply", devPath)
    }

    @Test
    fun testFeatures() {
        // Prepare
        val fakeDevice =
            fakeAdb.connectDevice(
                "1234",
                "test1",
                "test2",
                "model",
                "30", // SDK >= 30 is required for abb_exec feature.
                DeviceState.HostConnectionType.USB
            )
        fakeDevice.deviceStatus = DeviceState.DeviceStatus.ONLINE

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

    @Test
    fun testForward() {
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
        val port = runBlocking {
            hostServices.forward(
                DeviceSelector.any(),
                SocketSpec.Tcp(),
                SocketSpec.Tcp(4000)
            )
        }

        // Assert
        Assert.assertTrue(port != null && port.toInt() > 0)
    }

    @Test
    fun testForwardNoRebind() {
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
        val port = runBlocking {
            hostServices.forward(
                DeviceSelector.any(),
                SocketSpec.Tcp(),
                SocketSpec.Tcp(4000)
            )
        }?.toIntOrNull() ?: throw AssertionError("Port should have been an integer")

        // Act
        exceptionRule.expect(AdbFailResponseException::class.java)
        runBlocking {
            hostServices.forward(
                DeviceSelector.any(),
                SocketSpec.Tcp(port),
                SocketSpec.Tcp(4000),
                rebind = false
            )
        }

        // Assert
        Assert.fail()
    }

    @Test
    fun testForwardRebind() {
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
        val port = runBlocking {
            hostServices.forward(
                DeviceSelector.any(),
                SocketSpec.Tcp(),
                SocketSpec.Tcp(4000)
            )
        }?.toIntOrNull() ?: throw AssertionError("Port should have been an integer")

        // Act
        val port2 = runBlocking {
            hostServices.forward(
                DeviceSelector.any(),
                SocketSpec.Tcp(port),
                SocketSpec.Tcp(4000),
                rebind = true
            )
        }

        // Assert
        Assert.assertTrue(port2 != null && port2.toInt() == port)
    }

    @Test
    fun testKillForward() {
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
        val port = runBlocking {
            hostServices.forward(
                DeviceSelector.any(),
                SocketSpec.Tcp(),
                SocketSpec.Tcp(4000)
            )
        } ?: throw Exception("`forward` command should have returned a port")
        Assert.assertEquals(1, fakeDevice.allPortForwarders.size)

        // Act
        runBlocking {
            hostServices.killForward(
                DeviceSelector.any(),
                SocketSpec.Tcp(port.toInt())
            )
        }

        // Assert
        Assert.assertEquals(0, fakeDevice.allPortForwarders.size)
    }

    @Test
    fun testKillForwardAll() {
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
        runBlocking {
            hostServices.forward(
                DeviceSelector.any(),
                SocketSpec.Tcp(),
                SocketSpec.Tcp(4000)
            )
        }
        Assert.assertEquals(1, fakeDevice.allPortForwarders.size)

        // Act
        runBlocking {
            hostServices.killForwardAll(DeviceSelector.any())
        }

        // Assert
        Assert.assertEquals(0, fakeDevice.allPortForwarders.size)
    }

    @Test
    fun testListForward() {
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
        runBlocking {
            hostServices.forward(
                DeviceSelector.any(),
                SocketSpec.Tcp(1000),
                SocketSpec.Tcp(4000)
            )
        }
        Assert.assertEquals(1, fakeDevice.allPortForwarders.size)

        // Act
        val forwardList = runBlocking {
            hostServices.listForward()
        }

        // Assert
        Assert.assertEquals(1, forwardList.size)
        Assert.assertEquals(0, forwardList.errors.size)
        forwardList[0].let { forwardEntry ->
            Assert.assertEquals("1234", forwardEntry.deviceSerial)
            Assert.assertEquals("tcp:1000", forwardEntry.local.toQueryString())
            Assert.assertEquals("tcp:4000", forwardEntry.remote.toQueryString())
        }
    }
}
