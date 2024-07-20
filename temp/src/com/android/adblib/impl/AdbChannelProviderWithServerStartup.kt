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
package com.android.adblib.impl

import com.android.adblib.AdbChannel
import com.android.adblib.AdbServerChannelProvider
import com.android.adblib.AdbServerStartup
import com.android.adblib.AdbSessionHost
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

internal data class AdbServerChannelConnectOptions(val defaultPort: Int)

/**
 * [AdbServerChannelProvider] that can start the Adb Server if it was not located
 */
internal class AdbChannelProviderWithServerStartup(
    private val host: AdbSessionHost,
    private val connectOptions: AdbServerChannelConnectOptions,
    private val adbServerStartup: AdbServerStartup = AdbServerStartupImpl(host)
) : AdbServerChannelProvider {

    override suspend fun createChannel(timeout: Long, unit: TimeUnit): AdbChannel {
        val tracker = TimeoutTracker(host.timeProvider, timeout, unit)

        var port = connectOptions.defaultPort
        try {
            return delegateCreateChannel(port, tracker)
        } catch (e: IOException) {
            host.logger.debug { "Couldn't open ADB connection to ADB server. Will try starting ADB server first." }
        }
        tracker.throwIfElapsed()
        port = adbServerStartup.start(port, tracker.remainingNanos, TimeUnit.NANOSECONDS)

        return delegateCreateChannel(port, tracker)
    }

    private suspend fun delegateCreateChannel(port: Int, tracker: TimeoutTracker): AdbChannel {
        val socketAddresses = listOf(
            InetSocketAddress("127.0.0.1", port),
            InetSocketAddress("::1", port)
        )
        val delegateAdbChannelProvider =
            AdbChannelProviderConnectAddresses(host) { socketAddresses }

        return delegateAdbChannelProvider.createChannel(
            tracker.remainingNanos,
            TimeUnit.NANOSECONDS
        )
    }
}
