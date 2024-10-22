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

import org.junit.Assert
import org.junit.Test

class DeviceSelectorTest {

    @Test
    fun hostPrefixWorks() {
        // Prepare
        val device1 = DeviceSelector.fromSerialNumber("12345")
        val device2 = DeviceSelector.fromTransportId(2)
        val device3 = DeviceSelector.local()
        val device4 = DeviceSelector.usb()
        val device5 = DeviceSelector.any()

        // Act

        // Assert
        Assert.assertEquals("host-serial:12345", device1.hostPrefix)
        Assert.assertEquals("host-transport-id:2", device2.hostPrefix)
        Assert.assertEquals("host-local", device3.hostPrefix)
        Assert.assertEquals("host-usb", device4.hostPrefix)
        Assert.assertEquals("host", device5.hostPrefix)
    }

    @Test
    fun transportPrefixWorks() {
        // Prepare
        val device1 = DeviceSelector.fromSerialNumber("12345")
        val device2 = DeviceSelector.fromTransportId(2)
        val device3 = DeviceSelector.local()
        val device4 = DeviceSelector.usb()
        val device5 = DeviceSelector.any()

        // Act

        // Assert
        Assert.assertEquals("host:transport:12345", device1.transportPrefix)
        Assert.assertEquals("host:transport-id:2", device2.transportPrefix)
        Assert.assertEquals("host:transport-local", device3.transportPrefix)
        Assert.assertEquals("host:transport-usb", device4.transportPrefix)
        Assert.assertEquals("host:transport-any", device5.transportPrefix)
    }

    @Test
    fun shortDescriptionWorks() {
        // Prepare
        val device1 = DeviceSelector.fromSerialNumber("12345")
        val device2 = DeviceSelector.fromTransportId(2)
        val device3 = DeviceSelector.local()
        val device4 = DeviceSelector.usb()
        val device5 = DeviceSelector.any()
        val device6 = DeviceSelector.factoryWithTransportId.fromSerialNumber("12345")
        val device7 = DeviceSelector.factoryWithTransportId.local()
        val device8 = DeviceSelector.factoryWithTransportId.usb()
        val device9 = DeviceSelector.factoryWithTransportId.any()
        val device10 = DeviceSelector.factoryWithTransportIdTracking.fromSerialNumber("12345")
        val device11 = DeviceSelector.factoryWithTransportIdTracking.local()
        val device12 = DeviceSelector.factoryWithTransportIdTracking.usb()
        val device13 = DeviceSelector.factoryWithTransportIdTracking.any()

        // Act

        // Assert
        Assert.assertEquals("device serial #12345", device1.shortDescription)
        Assert.assertEquals("device transport id 2", device2.shortDescription)
        Assert.assertEquals("device 'local'", device3.shortDescription)
        Assert.assertEquals("device 'usb'", device4.shortDescription)
        Assert.assertEquals("device 'any'", device5.shortDescription)
        Assert.assertEquals("device serial #12345", device6.shortDescription)
        Assert.assertEquals("device 'local'", device7.shortDescription)
        Assert.assertEquals("device 'usb'", device8.shortDescription)
        Assert.assertEquals("device 'any'", device9.shortDescription)
        Assert.assertEquals("device serial #12345", device10.shortDescription)
        Assert.assertEquals("device 'local'", device11.shortDescription)
        Assert.assertEquals("device 'usb'", device12.shortDescription)
        Assert.assertEquals("device 'any'", device13.shortDescription)
    }

    @Test
    fun transportPrefixWithTransportIdWorks() {
        // Prepare
        val device1 = DeviceSelector.factoryWithTransportId.fromSerialNumber("12345")
        val device3 = DeviceSelector.factoryWithTransportId.local()
        val device4 = DeviceSelector.factoryWithTransportId.usb()
        val device5 = DeviceSelector.factoryWithTransportId.any()

        // Act
        device1.transportId = 10
        device3.transportId = 15
        device4.transportId = 20
        device5.transportId = 25

        // Assert
        Assert.assertEquals("host:tport:serial:12345", device1.transportPrefix)
        Assert.assertNull(device1.transportId)
        Assert.assertEquals("host:tport:local", device3.transportPrefix)
        Assert.assertNull(device3.transportId)
        Assert.assertEquals("host:tport:usb", device4.transportPrefix)
        Assert.assertNull(device4.transportId)
        Assert.assertEquals("host:tport:any", device5.transportPrefix)
        Assert.assertNull(device5.transportId)
    }

    @Test
    fun transportPrefixWithTransportIdTrackingWorks() {
        // Prepare
        val device1 = DeviceSelector.factoryWithTransportIdTracking.fromSerialNumber("12345")
        val device3 = DeviceSelector.factoryWithTransportIdTracking.local()
        val device4 = DeviceSelector.factoryWithTransportIdTracking.usb()
        val device5 = DeviceSelector.factoryWithTransportIdTracking.any()

        // Act
        device1.transportId = 10
        device3.transportId = 15
        device4.transportId = 20
        device5.transportId = 25

        // Assert
        Assert.assertEquals("host:tport:serial:12345", device1.transportPrefix)
        Assert.assertEquals(10L, device1.transportId)
        Assert.assertEquals("host:tport:local", device3.transportPrefix)
        Assert.assertEquals(15L, device3.transportId)
        Assert.assertEquals("host:tport:usb", device4.transportPrefix)
        Assert.assertEquals(20L, device4.transportId)
        Assert.assertEquals("host:tport:any", device5.transportPrefix)
        Assert.assertEquals(25L, device5.transportId)
    }

    @Test
    fun transportPrefixWithTransportIdTrackingReturnsNewInstances() {
        // Prepare
        val device1 = DeviceSelector.factoryWithTransportIdTracking.fromSerialNumber("12345")
        val device12 = DeviceSelector.factoryWithTransportIdTracking.fromSerialNumber("12345")
        val device3 = DeviceSelector.factoryWithTransportIdTracking.local()
        val device32 = DeviceSelector.factoryWithTransportIdTracking.local()
        val device4 = DeviceSelector.factoryWithTransportIdTracking.usb()
        val device42 = DeviceSelector.factoryWithTransportIdTracking.usb()
        val device5 = DeviceSelector.factoryWithTransportIdTracking.any()
        val device52 = DeviceSelector.factoryWithTransportIdTracking.any()

        // Act
        device1.transportId = 10
        device12.transportId = 102
        device3.transportId = 15
        device32.transportId = 152
        device4.transportId = 20
        device42.transportId = 202
        device5.transportId = 25
        device52.transportId = 252

        // Assert
        Assert.assertEquals(10L, device1.transportId)
        Assert.assertEquals(102L, device12.transportId)
        Assert.assertEquals(15L, device3.transportId)
        Assert.assertEquals(152L, device32.transportId)
        Assert.assertEquals(20L, device4.transportId)
        Assert.assertEquals(202L, device42.transportId)
        Assert.assertEquals(25L, device5.transportId)
        Assert.assertEquals(252L, device52.transportId)
    }

    @Test
    fun withTransportIdInResponseAllowsStoringTransportId() {
        // Prepare
        val device1 = DeviceSelector.fromSerialNumber("12345").withTransportIdInResponse()
        val device2 = DeviceSelector.fromTransportId(1).withTransportIdInResponse()
        val device3 = DeviceSelector.local().withTransportIdInResponse()
        val device4 = DeviceSelector.usb().withTransportIdInResponse()
        val device5 = DeviceSelector.any().withTransportIdInResponse()

        // Act
        device1.transportId = 10
        device2.transportId = 15 // Note: This is ignored because it already has a transport id
        device3.transportId = 20
        device4.transportId = 25
        device5.transportId = 30

        // Assert
        Assert.assertEquals(10L, device1.transportId)
        Assert.assertEquals(1L, device2.transportId)
        Assert.assertEquals(20L, device3.transportId)
        Assert.assertEquals(25L, device4.transportId)
        Assert.assertEquals(30L, device5.transportId)
    }
}
