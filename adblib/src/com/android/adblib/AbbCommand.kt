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

import kotlinx.coroutines.flow.Flow
import java.time.Duration
import java.util.concurrent.TimeoutException

/**
 * Supports customization of various aspects of the execution of an abb command on a device.
 *
 * Once a [AbbCommand] is configured with various `withXxx` methods, use the [execute]
 * method to launch the abb command execution, returning a [Flow&lt;T&gt;][Flow].
 *
 * @see [AdbDeviceServices.abb]
 * @see [AdbDeviceServices.abb_exec]
 */
interface AbbCommand<T> {

    val session: AdbSession

    /**
     * Applies a [ShellV2Collector] to transfer the raw binary abb command output.
     * This changes the type of this [AbbCommand] from [T] to the final target type [U].
     */
    fun <U> withCollector(collector: ShellV2Collector<U>): AbbCommand<U>

    /**
     * Applies a legacy [ShellCollector] to transfer the raw binary abb command output.
     * This changes the type of this [AbbCommand] from [T] to the final target type [U].
     */
    fun <U> withLegacyCollector(collector: ShellCollector<U>): AbbCommand<U>

    /**
     * The [AdbInputChannel] to send to the device for `stdin`.
     *
     * The default value is `null`.
     */
    fun withStdin(stdinChannel: AdbInputChannel?): AbbCommand<T>

    /**
     * Applies a [timeout] that triggers [TimeoutException] exception if the abb command
     * does not terminate within the specified [Duration].
     *
     * The default value is [INFINITE_DURATION].
     */
    fun withCommandTimeout(timeout: Duration): AbbCommand<T>

    /**
     * Applies a [timeout] that triggers a [TimeoutException] exception when the command does
     * not generate any output (`stdout` or `stderr`) for the specified [Duration].
     *
     * The default value is [INFINITE_DURATION].
     */
    fun withCommandOutputTimeout(timeout: Duration): AbbCommand<T>

    /**
     * Overrides the default buffer size used for buffering `stdout`, `stderr` and `stdin`.
     *
     * The default value is [AdbLibProperties.DEFAULT_SHELL_BUFFER_SIZE].
     */
    fun withBufferSize(size: Int): AbbCommand<T>

    /**
     * Allows [execute] to use [AdbDeviceServices.abb] if available.
     *
     * The default value is `true`.
     */
    fun allowShellV2Protocol(value: Boolean): AbbCommand<T>

    /**
     * Allows [execute] to fall back to [AdbDeviceServices.abb_exec] if [AdbDeviceServices.abb]
     * is not available or not allowed.
     *
     * The default value is `true`.
     */
    fun allowExecProtocol(value: Boolean): AbbCommand<T>

    /**
     * Force [execute] to using [AdbDeviceServices.abb].
     *
     * The default value is `false`.
     */
    fun forceShellV2Protocol(): AbbCommand<T>

    /**
     * Force [execute] to using [AdbDeviceServices.abb_exec].
     *
     * The default value is `false`.
     */
    fun forceExecProtocol(): AbbCommand<T>

    /**
     * When using ABB_EXEC, this method allows to specify whether the device channel output
     * is shutdown after piping `stdinChannel`.
     *
     * The default value is `true`.
     */
    fun shutdownOutputForExecProtocol(shutdownOutput: Boolean): AbbCommand<T>

    /**
     * Returns a [Flow] that executes the abb command on the device, according to the
     * various customization rules set by the `withXxx` methods.
     *
     * If [withCollector] or [withLegacyCollector] was not invoked before [execute],
     * an [IllegalArgumentException] is thrown, as a shell collector is mandatory.
     *
     * Once [execute] is called, further customization is not allowed.
     */
    fun execute(): Flow<T>

    /**
     * Execute the abb command on the device, assuming there is a single output
     * emitted by [AbbCommand.withCollector]. The single output is passed as
     * an argument to [block] **while the shell command is still active**.
     *
     * Note: This operator is reserved for [ShellV2Collector] that collect a single value.
     */
    suspend fun <R> executeAsSingleOutput(block: suspend (T) -> R): R
}

fun <T> AbbCommand<T>.withLineCollector(): AbbCommand<ShellCommandOutputElement> {
    return this.withCollector(LineShellV2Collector())
}

fun <T> AbbCommand<T>.withLineBatchCollector(): AbbCommand<BatchShellCommandOutputElement> {
    return this.withCollector(LineBatchShellV2Collector())
}

fun <T> AbbCommand<T>.withTextCollector(): AbbCommand<ShellCommandOutput> {
    return this.withCollector(TextShellV2Collector())
}

fun <T> AbbCommand<T>.withInputChannelCollector(): AbbCommand<InputChannelShellOutput> {
    return this.withCollector(InputChannelShellCollector(this.session))
}
