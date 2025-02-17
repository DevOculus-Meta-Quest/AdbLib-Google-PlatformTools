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

import java.io.IOException
import java.nio.file.attribute.FileTime

/**
 * Sync has a max limit of 64KB for sending/receiving file blocks
 */
const val SYNC_DATA_MAX = 64 * 1024

/**
 * Allows transferring files to and from a device, using the protocol documented in
 * [SYNC.TXT](https://cs.android.com/android/platform/superproject/+/fbe41e9a47a57f0d20887ace0fc4d0022afd2f5f:packages/modules/adb/SYNC.TXT)
 *
 * The implementation maintains an open connection to the remote device to allow transferring
 * more than one file, so [AutoCloseable.close] should be invoked when the file operations are
 * done.
 */
interface AdbDeviceSyncServices : AutoCloseable {

    /**
     * Sends the contents of an [AdbInputChannel] to file on the remote device (`SEND` command).
     *
     * If [remoteFileTime] is not provided, it defaults to the current system time.
     *
     * Note: If the directory for [remoteFilePath] does not exist on the device, an attempt
     * is made to create this directory (and its parent). This may fail for various
     * reasons, in which case an [AdbFailResponseException] is thrown.
     *
     * @throws AdbFailResponseException if the ADB daemon cannot process the file contents
     * @throws AdbProtocolErrorException if there is an unexpected ADB protocol error
     * @throws IOException if there is an I/O error
     */
    suspend fun send(
        sourceChannel: AdbInputChannel,
        remoteFilePath: String,
        remoteFileMode: RemoteFileMode,
        remoteFileTime: FileTime?,
        progress: SyncProgress?,
        bufferSize: Int = SYNC_DATA_MAX
    )

    /**
     * Retrieve the contents of a file from the remote device to an [AdbOutputChannel]
     * ("RECV" command)
     *
     * @throws AdbFailResponseException if the ADB daemon cannot send the file contents
     * @throws AdbProtocolErrorException if there is an unexpected ADB protocol error
     * @throws IOException if there is an I/O error
     */
    suspend fun recv(
        remoteFilePath: String,
        destinationChannel: AdbOutputChannel,
        progress: SyncProgress?,
        bufferSize: Int = SYNC_DATA_MAX
    )

    /**
     * Stat a file on the remote device
     * ("STAT" command)
     *
     * @throws AdbFailResponseException if the ADB daemon cannot stat the file contents
     * @throws AdbProtocolErrorException if there is an unexpected ADB protocol error
     * @throws IOException if there is an I/O error
     */
    suspend fun stat(remoteFilePath: String) : FileStat?
}

data class FileStat(val remoteFileMode: RemoteFileMode, val size: Int, val lastModified: FileTime)

/**
 * Reports progress about a single remote file transfer.
 *
 * @see [AdbDeviceSyncServices.send]
 * @see [AdbDeviceSyncServices.recv]
 */
interface SyncProgress {

    /**
     * Invoked just before the file transfer starts
     */
    suspend fun transferStarted(remotePath: String)

    /**
     * Invoked (multiple times) during the file transfer
     */
    suspend fun transferProgress(remotePath: String, totalBytesSoFar: Long)

    /**
     * Invoked just after the file transfer has successfully finished
     */
    suspend fun transferDone(remotePath: String, totalBytes: Long)
}

/**
 * Trivial implementation of the [SyncProgress] interface.
 */
open class SyncProgressAdapter : SyncProgress {

    override suspend fun transferStarted(remotePath: String) {}

    override suspend fun transferProgress(remotePath: String, totalBytesSoFar: Long) {}

    override suspend fun transferDone(remotePath: String, totalBytes: Long) {}
}
