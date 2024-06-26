package com.android.adblib.impl.channels

import com.android.adblib.AdbOutputChannel
import com.android.adblib.AdbSession
import com.android.adblib.adbLogger
import com.android.adblib.withErrorTimeout
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Implementation of [AdbOutputChannel] over a [OutputStream]
 *
 * TODO(rpaquay): Add unit test
 */
internal class AdbOutputStreamChannel(
  private val session: AdbSession,
  private val stream: OutputStream,
  bufferSize: Int = DEFAULT_CHANNEL_BUFFER_SIZE
) : AdbOutputChannel {

    private val logger = adbLogger(session)

    private val bytes = ByteArray(bufferSize)

    override fun toString(): String {
        return "AdbOutputStreamChannel(\"$stream\")"
    }

    @Throws(Exception::class)
    override fun close() {
        logger.debug { "closing output stream channel" }
        stream.close()
    }

    override suspend fun writeBuffer(buffer: ByteBuffer, timeout: Long, unit: TimeUnit) {
        session.withErrorTimeout(timeout, unit) {
            // Note: Since OutputStream.write is a blocking I/O operation, we use the IO dispatcher
            runInterruptibleIO(session.blockingIoDispatcher) {
                val count = min(bytes.size, buffer.remaining())
                buffer.get(bytes, 0, count)
                stream.write(bytes, 0, count)
            }
        }
    }
}
