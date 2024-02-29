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

interface AdbUsageTracker {

    /** Log data about the usage of an adblib feature */
    fun logUsage(event: Event)

    data class Event(
        // Info about the connected device
        val device: ConnectedDevice?,

        // Info about `JdwpProcessPropertiesCollector` success/failure
        val jdwpProcessPropertiesCollector: JdwpProcessPropertiesCollectorEvent?,
    )

    enum class JdwpProcessPropertiesCollectorFailureType {
        NO_RESPONSE,
        CLOSED_CHANNEL_EXCEPTION,
        CONNECTION_CLOSED_ERROR,
        OTHER_ERROR,
    }

    data class JdwpProcessPropertiesCollectorEvent(
        val isSuccess: Boolean,
        val failureType: JdwpProcessPropertiesCollectorFailureType? = null,
        val previouslyFailedCount: Int,
        val previousFailureType: JdwpProcessPropertiesCollectorFailureType? = null
    )
}

internal class NoopAdbUsageTracker : AdbUsageTracker {

    override fun logUsage(event: AdbUsageTracker.Event) {
        // Do nothing
    }
}
