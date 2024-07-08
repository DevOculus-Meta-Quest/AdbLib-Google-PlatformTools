package com.android.adblib.impl.channels

import com.android.adblib.AdbChannel
import com.android.adblib.AdbSessionHost
import com.android.adblib.adbLogger
import com.android.adblib.impl.remainingTimeoutToString
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousSocketChannel
import java.nio.channels.ClosedChannelException
import java.nio.channels.CompletionHandler
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Implementation of [AdbChannel] over an [AsynchronousSocketChannel] socket connection
 */
internal class AdbSocketChannelImpl(
    private val host: AdbSessionHost,
    private val socketChannel: AsynchronousSocketChannel
) : AdbChannel {

    private val logger = adbLogger(host)

    private val channelWriteHandler = object : ChannelWriteHandler(host, socketChannel) {
        override val supportsTimeout: Boolean
            get() = true

        override fun asyncWrite(
            buffer: ByteBuffer,
            timeout: Long,
            unit: TimeUnit,
            continuation: CancellableContinuation<Unit>,
            completionHandler: CompletionHandler<Int, CancellableContinuation<Unit>>
        ) {
            socketChannel.write(buffer, timeout, unit, continuation, completionHandler)
        }
    }

    private val channelReadHandler = object : ChannelReadHandler(host, socketChannel) {
        override val supportsTimeout: Boolean
            get() = true

        override fun asyncRead(
            buffer: ByteBuffer,
            timeout: Long,
            unit: TimeUnit,
            continuation: CancellableContinuation<Unit>,
            completionHandler: CompletionHandler<Int, CancellableContinuation<Unit>>
        ) {
            socketChannel.read(buffer, timeout, unit, continuation, completionHandler)
        }
    }

    /**
     * Tells whether the underlying [AsynchronousSocketChannel] is open.
     */
    internal val isOpen: Boolean
        get() = socketChannel.isOpen

    override fun toString(): String {
        val remoteAddress = try {
            socketChannel.remoteAddress
        } catch (e: ClosedChannelException) {
            "<channel-closed>"
        } catch (e: Throwable) {
            "<error: $e>"
        }
        return "AdbSocketChannelImpl(remote=$remoteAddress)"
    }

    @Throws(Exception::class)
    override fun close() {
        logger.debug { "${loggerPrefix()}: Closing socket channel" }
        socketChannel.close()
    }

    suspend fun connect(address: InetSocketAddress, timeout: Long, unit: TimeUnit) {
        logger.debug {
            "${loggerPrefix()}: Connecting to IP address $address, timeout=${remainingTimeoutToString(timeout, unit)}"
        }

        // Note: We use a local completion handler so that we can report the address in
        // case of failure.
        val connectCompletionHandler = object : CompletionHandler<Void?, CancellableContinuation<Unit>> {

            override fun completed(result: Void?, continuation: CancellableContinuation<Unit>) {
                logger.debug { "${loggerPrefix()}: Connection completed successfully" }
                logger.debug { "'continuation[${continuation.hashCode()}].resume(Unit)', isCompleted=${continuation.isCompleted}, isCancelled=${continuation.isCancelled}" }
                continuation.resume(Unit)
            }

            override fun failed(e: Throwable, continuation: CancellableContinuation<Unit>) {
                logger.debug { "'continuation[${continuation.hashCode()}].resumeWithException(wrapError($e))', isCompleted=${continuation.isCompleted}, isCancelled=${continuation.isCancelled}" }
                continuation.resumeWithException(wrapError(e))
            }

            private fun wrapError(e: Throwable): Throwable {
                return IOException("Error connecting channel to address '$address'", e)
            }
        }

        suspendChannelCoroutine<Unit>(logger, host, socketChannel, timeout, unit) { continuation ->
            socketChannel.connect(address, continuation, connectCompletionHandler)
        }
    }

    override suspend fun readBuffer(buffer: ByteBuffer, timeout: Long, unit: TimeUnit) {
        channelReadHandler.readBuffer(buffer, timeout, unit)
    }

    override suspend fun readExactly(buffer: ByteBuffer, timeout: Long, unit: TimeUnit) {
        channelReadHandler.readExactly(buffer, timeout, unit)
    }

    override suspend fun writeBuffer(buffer: ByteBuffer, timeout: Long, unit: TimeUnit) {
        channelWriteHandler.writeBuffer(buffer, timeout, unit)
    }

    override suspend fun writeExactly(buffer: ByteBuffer, timeout: Long, unit: TimeUnit) {
        channelWriteHandler.writeExactly(buffer, timeout, unit)
    }

    override suspend fun shutdownInput() {
        withContext(host.ioDispatcher) {
            logger.debug { "${loggerPrefix()}: Shutting down input channel" }
            @Suppress("BlockingMethodInNonBlockingContext")
            socketChannel.shutdownInput()
        }
    }

    override suspend fun shutdownOutput() {
        withContext(host.ioDispatcher) {
            logger.debug { "${loggerPrefix()}: Shutting down output channel" }
            @Suppress("BlockingMethodInNonBlockingContext")
            socketChannel.shutdownOutput()
        }
    }

    private fun loggerPrefix(): String {
        return toString()
    }
}
