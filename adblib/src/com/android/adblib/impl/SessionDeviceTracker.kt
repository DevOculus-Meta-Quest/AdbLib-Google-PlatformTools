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

import com.android.adblib.AdbFeatures
import com.android.adblib.AdbHostServices
import com.android.adblib.AdbSession
import com.android.adblib.DeviceList
import com.android.adblib.ErrorLine
import com.android.adblib.TrackedDeviceList
import com.android.adblib.adbLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import java.io.EOFException
import java.time.Duration

internal class SessionDeviceTracker(
  private val session: AdbSession,
  private val retryDelay: Duration
) {

    private val logger = adbLogger(session.host)

    /**
     * The [StateFlow] of [TrackedDeviceList], created lazily.
     *
     * Note: It is important to use [LazyThreadSafetyMode.SYNCHRONIZED] since
     *  [createStateFlow] uses the [Flow.stateIn] operator, which never terminates.
     */
    val stateFlow: StateFlow<TrackedDeviceList> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        createStateFlow()
    }

    private fun createStateFlow(): StateFlow<TrackedDeviceList> {
        var connectionId = 0
        return startTrackDevices()
                .onStart {
                    connectionId++
                    logger.debug { "trackDevices() is starting, connection id=$connectionId" }
                }
                .map { deviceList ->
                    logger.debug { "trackDevices(): mapping deviceList $deviceList" }
                    TrackedDeviceList(connectionId, deviceList, null)
                }
                .retryWhen { throwable, _ ->
                    if (throwable is CancellationException) {
                        false
                    } else {
                        connectionId++
                        if (throwable is EOFException) {
                            logger.info { "trackDevices() reached EOF, will retry in ${retryDelay.toMillis()} millis, connection id=$connectionId" }
                        } else {
                            logger.info(throwable) { "trackDevices() failed, will retry in ${retryDelay.toMillis()} millis, connection id=$connectionId" }
                        }
                        // emit TrackerDisconnected state while we wait to retry the collection
                        emit(TrackedDeviceList(connectionId, TrackerDisconnected.instance, throwable))
                        delay(retryDelay.toMillis())
                        true
                    }
                }.stateIn(
                    session.scope,
                    SharingStarted.Eagerly,
                    TrackedDeviceList(connectionId, TrackerConnecting.instance, null)
                )
    }

    private fun startTrackDevices() = flow {
        val format = pickBestFormat()
        emitAll(session.hostServices.trackDevices(format))
    }

    private suspend fun pickBestFormat(): AdbHostServices.DeviceInfoFormat {
        // If protobuffer output is not supported, we need to fallback to long text instead.
        return if (supportsDevicesListBinaryProto()) {
            AdbHostServices.DeviceInfoFormat.BINARY_PROTO_FORMAT
        } else {
            AdbHostServices.DeviceInfoFormat.LONG_FORMAT
        }
    }

    private suspend fun supportsDevicesListBinaryProto() : Boolean {
        return session.hostServices.hostFeatures().contains(AdbFeatures.DEVICE_LIST_BINARY_PROTO)
    }
}

internal object TrackerDisconnected {

    private val error = ErrorLine("Device tracking session has been disconnected from ADB", 0, "")
    val instance = DeviceList(emptyList(), listOf(error))
}

internal object TrackerConnecting {

    private val error = ErrorLine("Device tracking session has not started yet", 0, "")
    val instance = DeviceList(emptyList(), listOf(error))
}
