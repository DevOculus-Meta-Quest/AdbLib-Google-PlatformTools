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
package com.android.adblib.impl

import com.android.adblib.CoroutineScopeCache
import com.android.adblib.CoroutineScopeCache.Key
import com.android.adblib.utils.SuppressedExceptions
import com.android.adblib.utils.createChildScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.util.concurrent.ConcurrentHashMap

internal class CoroutineScopeCacheImpl(
    parentScope: CoroutineScope,
    val description: String
) : CoroutineScopeCache {

    override var scope = parentScope.createChildScope(isSupervisor = true)

    private val job: Job
        get() = scope.coroutineContext.job

    @Volatile
    private var isClosed = false

    private val valueMap = ValueMap()

    private val suspendingMap = SuspendingMap(scope)

    init {
        // Ensure map is cleared and closed when scope/job is completed
        job.invokeOnCompletion { throwable ->
            runCatching {
                close()
            }.onFailure {
                throwable?.addSuppressed(it)
            }
        }
    }

    override fun <T> getOrPut(key: Key<T>, defaultValue: () -> T): T {
        if (isClosed) {
            return defaultValue()
        }
        return valueMap.getOrPut(key, defaultValue)
    }

    override suspend fun <T> getOrPutSuspending(
        key: Key<T>,
        defaultValue: suspend CoroutineScope.() -> T
    ): T {
        return suspendingMap.getOrPut(key, defaultValue)
    }

    override fun <T> getOrPutSuspending(
        key: Key<T>,
        fastDefaultValue: () -> T,
        defaultValue: suspend CoroutineScope.() -> T
    ): T {
        return suspendingMap.getOrPut(key, fastDefaultValue, defaultValue)
    }

    override fun close() {
        isClosed = true
        scope.cancel("$description: ${this::class.simpleName} has been closed")
        valueMap.close()
        suspendingMap.close()
    }

    private class ValueMap {

        private val map = ConcurrentHashMap<Key<*>, Any>()

        fun <T> getOrPut(key: Key<T>, defaultValue: () -> T): T {
            val result = map.getOrPut(key) { defaultValue() }
            @Suppress("UNCHECKED_CAST")
            return (result as T)
        }

        fun close() {
            val toClose = map.values.filterIsInstance<AutoCloseable>()
            map.clear()
            closeAll(toClose)
        }
    }

    private class SuspendingMap(private val scope: CoroutineScope) {

        private val map = ConcurrentHashMap<Key<*>, Any>()

        fun <T> getOrPut(
            key: Key<T>,
            fastDefaultValue: () -> T,
            defaultValue: suspend CoroutineScope.() -> T
        ): T {
            return when (val currentEntry = map[key]) {
                is Result<*> -> {
                    if (currentEntry.isFailure) {
                        // Retry if previous computation failed
                        launchComputeValue(currentEntry, key, defaultValue)
                        fastDefaultValue()
                    } else {
                        @Suppress("UNCHECKED_CAST")
                        return currentEntry.getOrThrow() as T
                    }
                }
                is Computing -> {
                    // Value is currently being computed, fast exit
                    fastDefaultValue()
                }
                null -> {
                    // Compute value asynchronously
                    try {
                        launchComputeValue(null, key, defaultValue)
                    } catch (e: CancellationException) {
                        // `scope` has been cancelled. Don't throw a `CancellationException`,
                        // but instead  return a fast default to as this method always returns
                        // a fast default when there is an error.
                    }
                    fastDefaultValue()
                }
                else -> {
                    throw IllegalStateException("Internal error: SuspendingMap contains an invalid object type")
                }
            }
        }

        suspend fun <T> getOrPut(key: Key<T>, defaultValue: suspend CoroutineScope.() -> T): T {
            var recomputeOnFailure = true
            while (true) {
                when (val currentEntry = map[key]) {
                    is Result<*> -> {
                        if (currentEntry.isFailure && recomputeOnFailure) {
                            // Retry if previous computation failed
                            launchComputeValue(currentEntry, key, defaultValue)
                        } else {
                            @Suppress("UNCHECKED_CAST")
                            return currentEntry.getOrThrow() as T
                        }
                    }
                    is Computing -> {
                        // Value is currently being computed, wait for computing coroutine to
                        // finish and try again
                        currentEntry.job?.join()
                        yield()
                    }
                    null -> {
                        // Compute value asynchronously and try again
                        launchComputeValue(null, key, defaultValue)
                        yield()
                    }
                    else -> {
                        throw IllegalStateException("Internal error: SuspendingMap contains an invalid object type")
                    }
                }
                recomputeOnFailure = false
            }
        }

        /**
         * Launches a new computation. This should be called only if the result was not previously
         * computed or if the previous computation failed.
         */
        private fun <T> launchComputeValue(
            currentResult: Result<*>?, key: Key<T>, defaultValue: suspend CoroutineScope.() -> T
        ) {
            scope.ensureActive()
            // Mark the "key" as "Computing", and ensure we compute only once, as there
            // may be other threads doing the same thing concurrently
            val computing = Computing()
            val valueWasStored = when {
                currentResult == null -> {
                    map.putIfAbsent(key, computing) == null
                }

                currentResult.isFailure -> {
                    map.replace(key, currentResult, computing)
                }

                else -> {
                    throw IllegalArgumentException("Internal error: invalid entry type $currentResult")
                }
            }

            if (valueWasStored) {
                computing.job = scope.launch {
                    val result = runCatching { defaultValue() }

                    // Replace in the cache only if we were the computing call
                    map.replace(key, computing, result).also { wasReplaced ->
                        // Note: If the cache have been closed, the key may not be present anymore,
                        //       so we need to also check the scope is active
                        assert(wasReplaced || !scope.isActive) {
                            "The 'computing' coroutine should always be the one storing " +
                                    "the computed value in the cache."
                        }
                    }
                }
            }
        }

        fun close() {
            val toClose = map.values.filterIsInstance<AutoCloseable>()
            val toCancel = map.values.filterIsInstance<Computing>()
            map.clear()
            closeAll(toClose)
            toCancel.forEach { it.job?.cancel("${this::class.java.simpleName} has been closed") }
        }

        /**
         * Marker value set in the [suspendingMap] when a value if computed asynchronously
         *
         * Note that we rely on object identity.
         */
        private class Computing(var job: Job? = null)
    }

    companion object {

        private fun closeAll(toClose: List<AutoCloseable>) {
            val closeExceptions = SuppressedExceptions.init()
            toClose.forEach {
                runCatching {
                    it.close()
                }.onFailure {
                    SuppressedExceptions.add(closeExceptions, it)
                }
            }
            if (closeExceptions.isNotEmpty()) {
                val error = Exception("One or more errors closing elements of cache")
                closeExceptions.forEach { error.addSuppressed(it) }
                throw error
            }
        }
    }
}
