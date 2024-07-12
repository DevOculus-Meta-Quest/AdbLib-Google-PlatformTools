/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.adblib.impl.channels

import com.android.adblib.AdbInputChannel
import com.android.adblib.AdbOutputChannel
import com.android.adblib.AdbSessionHost
import com.android.adblib.adbLogger
import com.android.adblib.impl.TimeoutTracker
import kotlinx.coroutines.CancellableContinuation
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousCloseException
import java.nio.channels.AsynchronousFileChannel
import java.nio.channels.AsynchronousSocketChannel
import java.nio.channels.Channel
import java.nio.channels.CompletionHandler
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Allows calling read/writes methods of an NIO [Channel] that require a [CompletionHandler] as
 * Kotlin coroutines.
 *
 * Note: Do not instantiate or inherit from this class directly, instead use [ChannelReadHandler]
 * or [ChannelWriteHandler] for read or write operations.
 *
 * @see ChannelReadHandler
 * @see ChannelWriteHandler
 * @see java.nio.channels.AsynchronousFileChannel
 * @see java.nio.channels.AsynchronousSocketChannel
 * @see java.nio.channels.CompletionHandler
 */
internal abstract class ChannelReadOrWriteHandler protected constructor(
    private val host: AdbSessionHost,
    private val nioChannel: Channel
) {

    private val logger = adbLogger(host)

    private val completionHandler = object : CompletionHandler<Int, CancellableContinuation<Unit>> {
        override fun completed(result: Int, continuation: CancellableContinuation<Unit>) {
            // We've seen this method occasionally called with a `continuation` in a `Resumed` state.
            // It is happening on Windows when using async File I/O (see b/338008891)
            // and we suspect this is a defect in the JDK.
            // To mitigate this problem we can skip completion handling as the invocation of
            // `Continuation.resume` or `Continuation.resumeWithException` in a resumed state produces
            // an `IllegalStateException`, and is ignored in a cancelled state.
            if (!continuation.isActive) {
                logger.debug { "CompletionHandler.completed() called for inactive continuation: 'continuation[${continuation.hashCode()}] isCompleted=${continuation.isCompleted}, isCancelled=${continuation.isCancelled}" }
                return
            }
            completionHandlerCompleted(result, continuation)
        }

        /**
         * Convert [AsynchronousCloseException] to [CancellationException]
         *
         * Note:
         * [AsynchronousCloseException] is thrown when an async socket is closed during
         * a pending async read or write operation. This function wraps these exceptions as
         * [CancellationException] to play "nicely" with coroutine cancellation processing.
         *
         * For example
         *
         *     suspend fun myFun(x : AdbInputChannel) {
         *         coroutineScope {
         *             launch {
         *                 x.read(buffer)
         *             }
         *             throw CancellationException("Foo")
         *         }
         *     }
         *
         * In the code above, the `throw CancellationException("Foo")` statement
         * * cancels the current scope,
         * * which results in cancelling the `async` block, because it is a child scope,
         * * which results in closing the `x` socket channel, because async socket operations
         *   close the socket when a pending I/O operation is cancelled,
         * * which results in the pending `read` operation failing,
         * * which results with an [AsynchronousCloseException] being sent to the
         *   async. read "completed" callback (i.e. this callback).
         * * which is caught by the `async` operation
         *
         * If we don't convert the [AsynchronousCloseException] into a [CancellationException],
         * the `async` block throws a non-cancellation exception ([AsynchronousCloseException]),
         * which may end up taking precedence over the initial [CancellationException] because
         * the behavior is more or less undefined when a non cancellation exception is thrown
         * in a scope already in the process of being cancelled.
         *
         * By wrapping the [AsynchronousCloseException] into a [CancellationException], the
         * `async` block throws a cancellation exception that is ignored because the parent
         * scope is already being cancelled (i.e. the coroutine runtime behavior seems
         * to be more deterministic dealing with "cancellation of cancellation").
         */
        private fun wrapError(e: Throwable): Throwable {
            return if (e is AsynchronousCloseException) {
                CancellationException(e)
            } else {
                e
            }
        }

        override fun failed(e: Throwable, continuation: CancellableContinuation<Unit>) {
            if (!continuation.isActive) {
                logger.debug { "CompletionHandler.failed() called for inactive continuation: 'continuation[${continuation.hashCode()}] isCompleted=${continuation.isCompleted}, isCancelled=${continuation.isCancelled}" }
                return
            }
            if (e is IOException &&
                    e.message == "The specified network name is no longer available") {
                // Treat this Windows-specific exception as the end of channel.
                completionHandlerCompleted(-1, continuation)
            }
            else {
                logger.debug { "'continuation[${continuation.hashCode()}].resumeWithException(wrapError($e))', isCompleted=${continuation.isCompleted}, isCancelled=${continuation.isCancelled}" }
                continuation.resumeWithException(wrapError(e))
            }
        }
    }

    /**
     * The [ByteBuffer] used when [runExactly] is active.
     * It's marked as @Volatile since it's accessed on a different thread from a completion handler.
     */
    @Volatile
    private var bufferForRunExactly: ByteBuffer? = null

    /**
     * The [TimeoutTracker] used when [runExactly] is active.
     * It's marked as @Volatile since it's accessed on a different thread from a completion handler.
     */
    @Volatile
    private var timeoutTracker: TimeoutTracker? = null

    /**
     * `true` if the [asyncReadOrWrite] implementation natively supports timeouts,
     * `false` if the timeout handling should be done by this base class
     *
     * @see suspendChannelCoroutine
     */
    protected abstract val supportsTimeout: Boolean

    /**
     * Invokes a single asynchronous `read` or `write` operation on the underlying [nioChannel]
     */
    protected abstract fun asyncReadOrWrite(
        buffer: ByteBuffer,
        timeout: Long,
        unit: TimeUnit,
        continuation: CancellableContinuation<Unit>,
        completionHandler: CompletionHandler<Int, CancellableContinuation<Unit>>
    )

    /**
     * Invoked when a single [asyncReadOrWrite] successfully completes, passing the number of bytes
     * read/written as [byteCount]
     */
    protected abstract fun asyncReadOrWriteCompleted(byteCount: Int)

    protected suspend fun run(buffer: ByteBuffer, timeout: Long, unit: TimeUnit) {
        // Special case of 0 bytes
        if (!buffer.hasRemaining()) {
            return
        }

        return if (supportsTimeout) {
            suspendChannelCoroutine(logger, nioChannel) { continuation ->
                asyncReadOrWrite(buffer, timeout, unit, continuation, completionHandler)
            }
        } else {
            suspendChannelCoroutine(logger, host, nioChannel, timeout, unit) { continuation ->
                asyncReadOrWrite(buffer, timeout, unit, continuation, completionHandler)
            }
        }
    }

    protected suspend fun runExactly(buffer: ByteBuffer, timeout: Long, unit: TimeUnit) {
        runExactlyBegin(buffer, timeout, unit)
        try {
            run(buffer, timeout, unit)
        } finally {
            runExactlyEnd()
        }
    }

    private fun runExactlyBegin(buffer: ByteBuffer, timeout: Long, unit: TimeUnit) {
        if (this.bufferForRunExactly != null) {
            throw IllegalStateException("An Async I/O operation is still pending")
        }

        this.bufferForRunExactly = buffer
        this.timeoutTracker = TimeoutTracker.fromTimeout(unit, timeout)
    }

    private fun runExactlyEnd() {
        bufferForRunExactly = null
        timeoutTracker = null
    }

     private fun completionHandlerCompleted(result: Int, continuation: CancellableContinuation<Unit>) {
        try {
            logger.verbose { "Async I/O operation completed successfully ($result bytes)" }

            if (bufferForRunExactly == null) {
                // Not a "runExactly" completion: complete right away
                asyncReadOrWriteCompleted(result)
                logContinuationResume(continuation)
                continuation.resume(Unit)
            } else {
                // A "runExactly" completion: starts another async operation
                // if the buffer is not fully processed, and so we should only `resume` continuation
                // on the last completion.
                val isFinalCompletion = runExactlyCompleted(result, continuation)
                if (isFinalCompletion) {
                    logContinuationResume(continuation)
                    continuation.resume(Unit)
                }
            }
        } catch (t: Throwable) {
            logger.debug { "'continuation[${continuation.hashCode()}].resumeWithException($t)', isCompleted=${continuation.isCompleted}, isCancelled=${continuation.isCancelled}" }
            continuation.resumeWithException(t)
        }
    }

    private fun logContinuationResume(continuation: CancellableContinuation<Unit>) {
        logger.debug { "'continuation[${continuation.hashCode()}].resume(Unit)', isCompleted=${continuation.isCompleted}, isCancelled=${continuation.isCancelled}, isRunExactly=${bufferForRunExactly != null}" }
        addResumeStackTrace(continuation)
        if (continuation.isCompleted && !continuation.isCancelled) {
            // This is unexpected
            logger.warn(
                "Resuming previously completed continuation[${continuation.hashCode()}].\nPrevious resume calls:\n" +
                        recentResumeCallStackTraces.toList().joinToString("\n")
            )
        }
    }

    private fun addResumeStackTrace(continuation: CancellableContinuation<Unit>) {
        // Keep at most 10 last stack traces
        if (recentResumeCallStackTraces.size >= 10) {
            recentResumeCallStackTraces.poll()
        }
        recentResumeCallStackTraces.offer("continuation[${continuation.hashCode()}], timestamp[${System.currentTimeMillis()}], handler[${hashCode()}]:\n${Throwable().stackTraceToString()}")
    }

    /**
     * This method is called by a completion handler when executing a `runExactly` operation.
     * Returns `true` if the buffer has been completely processed. Otherwise, triggers a further
     * async read or write and returns `false`.
     * Throws `EOFException` if EOF is reached during a read operation.
     */
    private fun runExactlyCompleted(
        result: Int,
        continuation: CancellableContinuation<Unit>
    ): Boolean {
        val tempBuffer = bufferForRunExactly ?: internalError("buffer is null")
        if (tempBuffer.remaining() == 0) {
            // Buffer is now completely processed
            asyncReadOrWriteCompleted(result)
            return true
        }

        // EOF, stop reading more (-1 never happens with a "write" operation)
        if (result == -1) {
            logger.verbose { "Reached EOF" }
            throw EOFException("Unexpected end of asynchronous channel")
        }

        // One more async read/write since buffer is not full and channel is not EOF
        asyncReadOrWriteCompleted(result)

        val tempTimeoutTracker = timeoutTracker ?: internalError("timeout tracker is null")
        asyncReadOrWrite(
            tempBuffer,
            tempTimeoutTracker.remainingMills,
            TimeUnit.MILLISECONDS,
            continuation,
            completionHandler
        )
        return false
    }

    private fun internalError(message: String): Nothing {
        val error = IllegalStateException("Internal error during Async I/O: $message")
        logger.error(error, message)
        throw error
    }

    companion object {
        val recentResumeCallStackTraces = ConcurrentLinkedDeque<String>()
    }
}

/**
 * Provides services to [readBuffer] data from any NIO [Channel] that supports asynchronous
 * reads, e.g. [AsynchronousFileChannel] or [AsynchronousSocketChannel].
 */
internal abstract class ChannelReadHandler(
    host: AdbSessionHost,
    nioChannel: Channel
) : ChannelReadOrWriteHandler(host, nioChannel) {

    /**
     * Reads up to [ByteBuffer.remaining] bytes from the underlying channel
     *
     * @see AdbInputChannel.readBuffer
     */
    suspend inline fun readBuffer(buffer: ByteBuffer, timeout: Long, unit: TimeUnit) {
        run(buffer, timeout, unit)
    }

    /**
     * Reads exactly [ByteBuffer.remaining] bytes from the underlying channel, throwing an
     * [EOFException] if the channel reaches EOF.
     *
     * @see AdbInputChannel.readExactly
     */
    suspend inline fun readExactly(buffer: ByteBuffer, timeout: Long, unit: TimeUnit) {
        runExactly(buffer, timeout, unit)
    }

    override fun asyncReadOrWrite(
        buffer: ByteBuffer,
        timeout: Long,
        unit: TimeUnit,
        continuation: CancellableContinuation<Unit>,
        completionHandler: CompletionHandler<Int, CancellableContinuation<Unit>>
    ) {
        asyncRead(buffer, timeout, unit, continuation, completionHandler)
    }

    override fun asyncReadOrWriteCompleted(byteCount: Int) {
        asyncReadCompleted(byteCount)
    }

    protected abstract fun asyncRead(
        buffer: ByteBuffer,
        timeout: Long,
        unit: TimeUnit,
        continuation: CancellableContinuation<Unit>,
        completionHandler: CompletionHandler<Int, CancellableContinuation<Unit>>
    )

    protected open fun asyncReadCompleted(byteCount: Int) {
    }
}

/**
 * Provides services to [writeBuffer] data from any NIO [Channel] that supports asynchronous
 * writes, e.g. [AsynchronousFileChannel] or [AsynchronousSocketChannel].
 */
internal abstract class ChannelWriteHandler(
    host: AdbSessionHost,
    nioChannel: Channel
) : ChannelReadOrWriteHandler(host, nioChannel) {

    /**
     * Writes up to [ByteBuffer.remaining] bytes to the underlying channel, returning
     * the number of bytes successfully written.
     *
     * @see AdbOutputChannel.writeBuffer
     */
    suspend inline fun writeBuffer(buffer: ByteBuffer, timeout: Long, unit: TimeUnit) {
        run(buffer, timeout, unit)
    }

    /**
     * Writes exactly [ByteBuffer.remaining] bytes to the underlying channel.
     *
     * @see AdbOutputChannel.writeExactly
     */
    suspend inline fun writeExactly(buffer: ByteBuffer, timeout: Long, unit: TimeUnit) {
        runExactly(buffer, timeout, unit)
    }

    override fun asyncReadOrWrite(
        buffer: ByteBuffer,
        timeout: Long,
        unit: TimeUnit,
        continuation: CancellableContinuation<Unit>,
        completionHandler: CompletionHandler<Int, CancellableContinuation<Unit>>
    ) {
        asyncWrite(buffer, timeout, unit, continuation, completionHandler)
    }

    override fun asyncReadOrWriteCompleted(byteCount: Int) {
        asyncWriteCompleted(byteCount)
    }

    protected abstract fun asyncWrite(
        buffer: ByteBuffer,
        timeout: Long,
        unit: TimeUnit,
        continuation: CancellableContinuation<Unit>,
        completionHandler: CompletionHandler<Int, CancellableContinuation<Unit>>
    )

    protected open fun asyncWriteCompleted(byteCount: Int) {
    }
}
