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
package com.android.adblib

interface DeviceCacheProvider {

    /**
     * Attempt to retrieve data from cache. If data is not in cache execute `block` to
     * produce the result and store it in the cache if the device cache is available/supported.
     */
    suspend fun <R> withDeviceCacheIfAvailable(
        device: DeviceSelector,
        cacheKey: CoroutineScopeCache.Key<R>,
        block: suspend () -> R
    ): R
}
