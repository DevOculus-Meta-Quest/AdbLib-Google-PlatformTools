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

import com.android.adblib.AdbSessionHost
import com.android.adblib.adbLogger
import kotlinx.coroutines.CancellableContinuation
import java.nio.channels.CompletionHandler
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal abstract class ContinuationCompletionHandler<T>(host: AdbSessionHost) : CompletionHandler<T, CancellableContinuation<Unit>> {

    private val logger = adbLogger(host)

    open fun completed(result: T) {
    }

    override fun completed(result: T, continuation: CancellableContinuation<Unit>) {
        try {
            completed(result)
        } finally {
            logger.debug { "'continuation[${continuation.hashCode()}].resume(Unit)', isCompleted=${continuation.isCompleted}, isCancelled=${continuation.isCancelled}" }
            continuation.resume(Unit)
        }
    }

    open fun wrapError(e: Throwable): Throwable {
        return e
    }

    override fun failed(e: Throwable, continuation: CancellableContinuation<Unit>) {
        logger.debug { "'continuation[${continuation.hashCode()}].resumeWithException(wrapError($e))', isCompleted=${continuation.isCompleted}, isCancelled=${continuation.isCancelled}" }
        continuation.resumeWithException(wrapError(e))
    }
}

internal open class TypedContinuationCompletionHandler<T>(host: AdbSessionHost) : CompletionHandler<T, CancellableContinuation<T>> {

    private val logger = adbLogger(host)

    open fun completed(result: T) {
    }

    override fun completed(result: T, continuation: CancellableContinuation<T>) {
        try {
            completed(result)
        } finally {
            logger.debug { "'continuation[${continuation.hashCode()}].resume(result)', isCompleted=${continuation.isCompleted}, isCancelled=${continuation.isCancelled}" }
            continuation.resume(result)
        }
    }

    open fun wrapError(e: Throwable): Throwable {
        return e
    }

    override fun failed(e: Throwable, continuation: CancellableContinuation<T>) {
        logger.debug { "'continuation[${continuation.hashCode()}].resumeWithException(wrapError($e))', isCompleted=${continuation.isCompleted}, isCancelled=${continuation.isCancelled}" }
        continuation.resumeWithException(wrapError(e))
    }
}
