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
package com.android.adblib

/**
 * An [AutoCloseable] object that may need a "normal" asynchronous termination ([shutdown] method)
 * in addition to a "synchronous" cancellation/termination ([close] method).
 *
 * * [shutdown] is a `suspend` function that allows the object to invoke a coroutine
 * (e.g. to clean-up or perform final async i/o) without blocking the main
 * thread. [shutdown], being a coroutine, maybe be cancelled at any time.
 *
 * * [close] is meant to perform an immediate cleanup (e.g. cancel all pending async I/O),
 * and should be called even in the presence of coroutine cancellation/exception.
 */
interface AutoShutdown : AutoCloseable {
    /**
     * Shuts down this resource, performing any remaining async. work required for "normal"
     * termination.
     *
     * Note: If an exception is thrown during [shutdown], the [close] should still be
     * called to ensure any remaining resource cleanup if necessary.
     */
    suspend fun shutdown()
}
