/*
 * Copyright (C) 2023 The Android Open Source Project
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

import java.time.Duration

/**
 * By convention, all [properties][AdbSessionHost.Property] of this module are defined in
 * this singleton.
 */
internal object AdbLibProperties {
    private const val NAME_PREFIX = "com.android.adblib"

    val TRACK_DEVICES_RETRY_DELAY = AdbSessionHost.DurationProperty(
        name = "$NAME_PREFIX.track.devices.retry.delay",
        defaultValue = Duration.ofSeconds(2)
    )
}
