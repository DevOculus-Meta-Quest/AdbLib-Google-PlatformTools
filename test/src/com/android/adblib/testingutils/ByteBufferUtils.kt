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
package com.android.adblib.testingutils

import com.android.adblib.utils.AdbProtocolUtils
import java.nio.ByteBuffer

object ByteBufferUtils {

    fun stringToByteBuffer(value: String): ByteBuffer {
        return ByteBuffer.wrap(stringToBytes(value))
    }

    fun stringToBytes(value: String): ByteArray {
        return value.toByteArray(AdbProtocolUtils.ADB_CHARSET)
    }

    fun byteBufferToByteArray(buffer: ByteBuffer): ByteArray {
        val result = ByteArray(buffer.remaining())
        val savedPosition = buffer.position()
        buffer.get(result)
        buffer.position(savedPosition)
        return result
    }
}
