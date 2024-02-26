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

    /**
     * The default size (in bytes) of in-memory buffers when sending or receiving
     * data for adb services related to the "shell" (legacy or "v2") protocol,
     * e.g. [AdbDeviceServices.shell] or [AdbDeviceServices.abb_exec].
     *
     * This value can be overridden globally (using this property) or locally
     * when calling specific [AdbDeviceServices] services, typically by setting
     * the value of the `bufferSize` parameter.
     *
     * Note: The current value of 64KB matches the `adb` implementation as of
     * [Oct 2023](https://cs.android.com/android/platform/superproject/main/+/05d405aeb41b63dc1258b64e3156e6b4f94315bb:packages/modules/adb/client/commandline.cpp;l=386)
     *
     * Note: Although smaller values (e.g. 8KB) are supported in theory, in practice, they
     *  can make `adb` unreliable on some platforms.
     *  See [b/272429909](https://issuetracker.google.com/issues/272429909)
     *
     * @see AdbDeviceServices.shell
     * @see AdbDeviceServices.exec
     * @see AdbDeviceServices.shellV2
     * @see AdbDeviceServices.abb_exec
     * @see AdbDeviceServices.abb
     */
    val DEFAULT_SHELL_BUFFER_SIZE  = AdbSessionHost.IntProperty(
        name = "$NAME_PREFIX.default.shell.buffer.size",
        defaultValue = 64 * 1024
    )

    /**
     * This property is used to control `AsynchronousSocketChannel` `SO_KEEPALIVE` option.
     *
     * Enabling `SO_KEEPALIVE` for long-running connections (e.g., those created by
     * `DeviceServices.rawExec`) can prevent connection problems. At the same time this should not
     * adversely impact short-lived connections.
     */
    val SOCKET_CHANNEL_KEEPALIVE = AdbSessionHost.BooleanProperty(
        name = "$NAME_PREFIX.connect.channel.provider.socket.channel.keepalive",
        defaultValue = true
    )
}
