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
import java.io.IOException

class AdbFailResponseExceptionTest {

    @Test
    fun hostExceptionMessageWorks() {
        // Prepare

        // Act
        val exception = AdbHostFailResponseException("foo-bar", "host not available")

        // Assert
        Assert.assertEquals(
            "'host not available' error executing ADB service 'foo-bar'",
            exception.message
        )
    }

    @Test
    fun deviceExceptionMessageWorks() {
        // Prepare

        // Act
        val exception =
            AdbDeviceFailResponseException(DeviceSelector.usb(), "foo-bar", "device not available")

        // Assert
        Assert.assertEquals(
            "'device not available' error on device 'usb' executing service 'foo-bar'",
            exception.message
        )
    }
}
