/*
 * Copyright (C) 2024 The Android Open Source Project
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

import com.android.adblib.AdbSession
import com.android.adblib.CoroutineScopeCache
import com.android.adblib.DeviceCacheProvider
import com.android.adblib.DeviceSelector
import com.android.adblib.connectedDevicesTracker
import com.android.adblib.device

internal class ConnectedDevicesDeviceCacheProvider(val adbSession: AdbSession) : DeviceCacheProvider {
    /**
     * Attempt to use a `ConnectedDevice`'s device cache if found through `ConnectedDevicesTracker`.
     * Otherwise, always run `block` to produce a new result.
     */
    override suspend fun <R> withDeviceCacheIfAvailable(
        device: DeviceSelector,
        cacheKey: CoroutineScopeCache.Key<R>,
        block: suspend () -> R
    ): R {
        val deviceCache = adbSession.connectedDevicesTracker.device(device)?.cache

        return deviceCache?.getOrPutSuspending(cacheKey) { block() } ?: block()
    }
}
