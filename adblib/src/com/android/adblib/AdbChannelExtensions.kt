package com.android.adblib

import com.android.adblib.impl.channels.DEFAULT_CHANNEL_BUFFER_SIZE
import com.android.adblib.utils.ResizableBuffer
import java.nio.ByteBuffer

/**
 * Forwards the contents of this [AdbInputChannel] to an [AdbOutputChannel] and returns the
 * number of bytes read.
 *
 * @param outputChannel The [AdbOutputChannel] to write to
 * @param workBuffer (optional) A [ResizableBuffer] used for transferring bytes
 * @param bufferSize (optional) The maximum number of bytes to read when transferring bytes
 */
suspend fun AdbInputChannel.forwardTo(
    outputChannel: AdbOutputChannel,
    workBuffer: ResizableBuffer = ResizableBuffer(),
    bufferSize: Int = DEFAULT_CHANNEL_BUFFER_SIZE,
): Int {
    var totalCount = 0
    while (true) {
        workBuffer.clear()
        val count = read(workBuffer.forChannelRead(bufferSize))
        if (count < 0) {
            // EOF, nothing left to forward
            break
        }
        totalCount += count
        outputChannel.writeExactly(workBuffer.afterChannelRead())
    }
    return totalCount
}

/**
 * Skips all remaining bytes of this [AdbInputChannel]
 *
 * @param workBuffer (optional) A [ResizableBuffer] used for transferring bytes
 * @param bufferSize (optional) The maximum number of bytes to read when transferring bytes
 */
suspend fun AdbInputChannel.skipRemaining(
    workBuffer: ResizableBuffer = ResizableBuffer(),
    bufferSize: Int = DEFAULT_BUFFER_SIZE
): Int {
    var totalCount = 0
    while (true) {
        workBuffer.clear()
        val count = read(workBuffer.forChannelRead(bufferSize))
        if (count == -1) {
            return totalCount
        }
        totalCount += count
    }
}

/**
 * Appends all remaining bytes of this [AdbInputChannel] to a [ResizableBuffer],
 * starting at [ResizableBuffer.position], and returns the number of bytes read.
 * [ResizableBuffer.position] is incremented by the numbers of bytes read.

 * @param workBuffer A [ResizableBuffer] used to store the bytes read
 * @param bufferSize (optional) The maximum number of bytes to read in one operation
 */
suspend fun AdbInputChannel.readRemaining(
    workBuffer: ResizableBuffer,
    bufferSize: Int = DEFAULT_BUFFER_SIZE
): Int {
    var totalCount = 0
    while (true) {
        val previousPosition = workBuffer.position
        // Data is from [0, position], ensure limit = position + bufferSize
        val count = read(workBuffer.forChannelRead(bufferSize))
        if (count < 0) {
            return totalCount
        }
        totalCount += count
        // Data is now from [0, previousPosition + count], set limit = capacity
        workBuffer.clearToPosition(previousPosition + count)
    }
}

/**
 * Reads [count] bytes from this [AdbInputChannel] and appends them to [workBuffer], increasing
 * the [ResizableBuffer] size as needed.
 *
 * Use [ResizableBuffer.afterChannelRead] to access the [ByteBuffer] where the
 * [count] bytes are stored.
 */
suspend inline fun AdbInputChannel.readNBytes(workBuffer: ResizableBuffer, count: Int) {
    // Optional data is from [0, position], ensure limit = position + count
    val buffer = workBuffer.forChannelRead(count)
    readExactly(buffer)
}
