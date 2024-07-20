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
package com.android.adblib.testing

import com.android.adblib.AdbChannelFactory
import com.android.adblib.AdbSession
import com.android.adblib.ClosedSessionException
import com.android.adblib.CoroutineScopeCache
import com.android.adblib.impl.CoroutineScopeCacheImpl
import com.android.adblib.impl.channels.AdbChannelFactoryImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * A fake implementation of [FakeAdbSession] for tests.
 */
class FakeAdbSession : AdbSession {

    private var isClosed = false

    override val parentSession: AdbSession?
        get() = null

    override val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val cache: CoroutineScopeCache = CoroutineScopeCacheImpl(scope, "fake-session")
        get() {
            throwIfClosed()
            return field
        }

    override fun throwIfClosed() {
        if (isClosed) {
            throw ClosedSessionException("ADB session is closed")
        }
    }

    override val hostServices = FakeAdbHostServices(this)
        get() {
            throwIfClosed()
            return field
        }

    override val deviceServices = FakeAdbDeviceServices(this)
        get() {
            throwIfClosed()
            return field
        }

    override val host = FakeAdbSessionHost()

    override val channelFactory: AdbChannelFactory = AdbChannelFactoryImpl(this)
        get() {
            throwIfClosed()
            return field
        }

    override fun close() {
        (cache as CoroutineScopeCacheImpl).close()
        scope.cancel("adblib session has been cancelled")
        isClosed = true
    }

    /**
     * This can be used to ensure that there's no work leftover in [scope], that might potentially
     * interfere with later tests.
     */
    suspend fun closeAndJoin() {
        close()
        scope.coroutineContext[Job]?.join()
    }
}
