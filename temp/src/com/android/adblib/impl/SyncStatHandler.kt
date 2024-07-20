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
import com.android.adblib.AdbProtocolErrorException
import com.android.adblib.AdbSessionHost
import com.android.adblib.DeviceSelector
import com.android.adblib.FileStat
import com.android.adblib.RemoteFileMode
import com.android.adblib.adbLogger
import com.android.adblib.impl.services.AdbServiceRunner
import com.android.adblib.utils.AdbProtocolUtils
import com.android.adblib.withPrefix
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit

/**
 * Implementation of the `STAT` protocol of the `SYNC` command
 *
 * See [SYNC.TXT](https://cs.android.com/android/platform/superproject/+/fbe41e9a47a57f0d20887ace0fc4d0022afd2f5f:packages/modules/adb/SYNC.TXT)
 */
internal class SyncStatHandler(
    private val serviceRunner: AdbServiceRunner,
    private val device: DeviceSelector,
    private val deviceChannel: AdbChannel
) {

    private val logger = adbLogger(host).withPrefix("device:$device,sync:STAT - ")

    private val host: AdbSessionHost
        get() = serviceRunner.host

    private val workBuffer = serviceRunner.newResizableBuffer().order(ByteOrder.LITTLE_ENDIAN)

    /**
     * See (SYNC.TXT)[https://cs.android.com/android/platform/superproject/+/fbe41e9a47a57f0d20887ace0fc4d0022afd2f5f:packages/modules/adb/SYNC.TXT]
     *
     * ```
     * STAT:
     * Returns information about the file or null if file is not found
     */
    suspend fun stat(remoteFilePath: String) : FileStat? {
        return withContext(host.ioDispatcher) {
            // Receive the file using the "RECV" query
            startStatRequest(remoteFilePath)

            workBuffer.clear()
            deviceChannel.readExactly(workBuffer.forChannelRead(16))
            val buffer = workBuffer.afterChannelRead()
            // Consume `STAT` which is always returned in the first 4 bytes
            // https://cs.android.com/android/platform/superproject/+/fbe41e9a47a57f0d20887ace0fc4d0022afd2f5f:packages/modules/adb/daemon/file_sync_service.cpp;l=142
            if (!AdbProtocolUtils.isStat(buffer)) {
                val contents = AdbProtocolUtils.bufferToByteDumpString(buffer)
                val errorMessage =
                    "Received an invalid packet from a STAT sync query: $contents"
                throw AdbProtocolErrorException(errorMessage)
            }
            buffer.getInt()

            val mode = buffer.getInt()
            val size = buffer.getInt()
            val lastModifiedSecs = buffer.getInt()
            // When file is not found `mode`, `size` and `lastModifiedSecs` are all 0
            if (mode == 0 && size == 0 && lastModifiedSecs == 0) {
                return@withContext null
            }
            FileStat(
                RemoteFileMode.fromModeBits(mode),
                size,
                FileTime.from(lastModifiedSecs.toLong(), TimeUnit.SECONDS)
            )
        }
    }

    private suspend fun startStatRequest(remoteFilePath: String) {
        logger.debug { "sending \"STAT\" command to device $device" }
        // Bytes 0-3: 'STAT'
        // Bytes 4-7: request size (little endian)
        // Bytes 8-xx: An utf-8 string with the remote file path
        workBuffer.clear()
        workBuffer.appendString("STAT", AdbProtocolUtils.ADB_CHARSET)
        val lengthPos = workBuffer.position
        workBuffer.appendInt(0) // Set later
        workBuffer.appendString(remoteFilePath, AdbProtocolUtils.ADB_CHARSET)
        workBuffer.setInt(lengthPos, workBuffer.position - 8)

        deviceChannel.writeExactly(workBuffer.forChannelWrite())
    }
}
