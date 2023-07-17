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
package com.android.adblib.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Same as [launch], except cancellation from the child coroutine [block] is propagated to the
 * parent coroutine (scope).
 *
 * The behavior is the same as [launch] wrt the following aspects:
 * * The parent coroutine won't complete until the child coroutine [block] completes.
 * * The parent coroutine fails with an exception if the child coroutine [block] throws
 *   an exception.
 */
inline fun CoroutineScope.launchCancellable(
    context: CoroutineContext = EmptyCoroutineContext,
    start: CoroutineStart = CoroutineStart.DEFAULT,
    crossinline block: suspend CoroutineScope.() -> Unit
): Job {
    return launch(context, start) {
        try {
            block()
        } catch (e: CancellationException) {
            // Note: this is a no-op is the parent scope is already cancelled
            this@launchCancellable.cancel(e)
            throw e
        }
    }
}

/**
 * Creates a child [CoroutineScope] of this scope.
 *
 * @param isSupervisor whether to use a regular [Job] or a [SupervisorJob]
 * @param context [CoroutineContext] to apply in addition to the parent scope [CoroutineContext]
 */
fun CoroutineScope.createChildScope(
    isSupervisor: Boolean = false,
    context: CoroutineContext = EmptyCoroutineContext
): CoroutineScope {
    val newJob = if (isSupervisor) {
        SupervisorJob(this.coroutineContext.job)
    } else {
        Job(this.coroutineContext.job)
    }
    return CoroutineScope(this.coroutineContext + newJob + context)
}

/**
 * Re-entrant version of [Mutex.lock]
 *
 * See [Phantom of the Coroutine](https://elizarov.medium.com/phantom-of-the-coroutine-afc63b03a131)
 * See [Reentrant lock #1686](https://github.com/Kotlin/kotlinx.coroutines/issues/1686#issuecomment-777357672)
 * See [ReentrantMutex implementation for Kotlin Coroutines](https://gist.github.com/elizarov/9a48b9709ffd508909d34fab6786acfe)
 */
suspend fun <T> Mutex.withReentrantLock(block: suspend () -> T): T {
    val key = ReentrantMutexContextKey(this)
    // call block directly when this mutex is already locked in the context
    if (currentCoroutineContext()[key] != null) return block()
    // otherwise add it to the context and lock the mutex
    return withContext(ReentrantMutexContextElement(key)) {
        withLock(null) { block() }
    }
}

private class ReentrantMutexContextElement(
    override val key: ReentrantMutexContextKey
) : CoroutineContext.Element

private data class ReentrantMutexContextKey(
    val mutex: Mutex
) : CoroutineContext.Key<ReentrantMutexContextElement>
