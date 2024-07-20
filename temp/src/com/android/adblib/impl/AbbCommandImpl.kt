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

import com.android.adblib.AbbCommand
import com.android.adblib.AdbFeatures
import com.android.adblib.AdbInputChannel
import com.android.adblib.AdbLibProperties
import com.android.adblib.AdbSession
import com.android.adblib.DeviceSelector
import com.android.adblib.INFINITE_DURATION
import com.android.adblib.ShellCollector
import com.android.adblib.ShellV2Collector
import com.android.adblib.adbLogger
import com.android.adblib.availableFeatures
import com.android.adblib.property
import com.android.adblib.utils.SuspendingLazy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.time.Duration

internal class AbbCommandImpl<T>(
    override val session: AdbSession,
    private val device: DeviceSelector,
    private val args: List<String>,
) : AbbCommand<T> {

    private val logger = adbLogger(session)

    private var _allowExec: Boolean = true
    private var _allowShellV2: Boolean = true
    private var collector: ShellV2Collector<T>? = null
    private var commandTimeout: Duration = INFINITE_DURATION
    private var commandOutputTimeout: Duration? = null
    private var stdinChannel: AdbInputChannel? = null
    private var _shutdownOutputForExecProtocol: Boolean = true
    private var bufferSize: Int = session.property(AdbLibProperties.DEFAULT_SHELL_BUFFER_SIZE)

    /**
     * The protocol used for [executing][execute] an [AbbCommand]
     */
    private enum class Protocol {
        ABB,
        ABB_EXEC
    }

    override fun <U> withCollector(collector: ShellV2Collector<U>): AbbCommand<U> {
        @Suppress("UNCHECKED_CAST")
        val result = this as AbbCommandImpl<U>

        result.collector = collector
        return result
    }

    override fun <U> withLegacyCollector(collector: ShellCollector<U>): AbbCommand<U> {
        @Suppress("UNCHECKED_CAST")
        val result = this as AbbCommandImpl<U>

        result.collector = ShellCommandHelpers.mapToShellV2Collector(collector)
        return result
    }

    override fun withStdin(stdinChannel: AdbInputChannel?): AbbCommand<T> {
        this.stdinChannel = stdinChannel
        return this
    }

    override fun withCommandTimeout(timeout: Duration): AbbCommand<T> {
        this.commandTimeout = timeout
        return this
    }

    override fun withCommandOutputTimeout(timeout: Duration): AbbCommand<T> {
        this.commandOutputTimeout = timeout
        return this
    }

    override fun withBufferSize(size: Int): AbbCommand<T> {
        this.bufferSize = size
        return this
    }

    override fun allowShellV2Protocol(value: Boolean): AbbCommand<T> {
        this._allowShellV2 = value
        return this
    }

    override fun allowExecProtocol(value: Boolean): AbbCommand<T> {
        this._allowExec = value
        return this
    }

    override fun forceShellV2Protocol(): AbbCommand<T> {
        this._allowShellV2 = true
        this._allowExec = false
        return this
    }

    override fun forceExecProtocol(): AbbCommand<T> {
        this._allowShellV2 = false
        this._allowExec = true
        return this
    }

    override fun shutdownOutputForExecProtocol(shutdownOutput: Boolean): AbbCommand<T> {
        this._shutdownOutputForExecProtocol = shutdownOutput
        return this
    }

    override fun execute() = flow {
        shellFlow().collect {
            emit(it)
        }
    }

    override suspend fun <R> executeAsSingleOutput(block: suspend (T) -> R): R {
        val collector = collector ?: throw IllegalArgumentException("Shell Collector is not set")
        require(collector.isSingleOutputCollector) {
            "Shell Collector '$collector' is not a single output collector"
        }
        return execute().map { singleOutput ->
            try {
                block(singleOutput)
            } finally {
                (singleOutput as? AutoCloseable)?.close()
            }
        }.first()
    }

    private suspend fun shellFlow(): Flow<T> {
        val collector = collector ?: throw IllegalArgumentException("Collector is not set")

        val protocol = pickProtocol()
        val commandOutputTimeout = this.commandOutputTimeout

        return if (commandOutputTimeout != null) {
            logger.debug { "Executing abb command with protocol=$protocol and commandOutputTimeout=$commandOutputTimeout, args=$args" }
            when (protocol) {
                Protocol.ABB -> {
                    AbbWithIdleMonitoring(
                        ShellWithIdleMonitoring.Parameters(
                            deviceServices = session.deviceServices,
                            device = device,
                            command = args,
                            shellCollector = collector,
                            stdinChannel = stdinChannel,
                            commandTimeout = commandTimeout,
                            commandOutputTimeout = commandOutputTimeout,
                            bufferSize = bufferSize,
                            stripCrLf = false,
                            shutdownOutput = false
                        )
                    ).createFlow()
                }
                Protocol.ABB_EXEC -> {
                    AbbExecWithIdleMonitoring(
                        ShellWithIdleMonitoring.Parameters(
                            deviceServices = session.deviceServices,
                            device = device,
                            command = args,
                            shellCollector = ShellCommandHelpers.mapToLegacyCollector(collector),
                            stdinChannel = stdinChannel,
                            commandTimeout = commandTimeout,
                            commandOutputTimeout = commandOutputTimeout,
                            bufferSize = bufferSize,
                            stripCrLf = false,
                            shutdownOutput = _shutdownOutputForExecProtocol
                        )
                    ).createFlow()
                }
            }
        } else {
            logger.debug { "Executing abb command with protocol=$protocol, args=$args" }
            return when (protocol) {
                Protocol.ABB -> {
                    session.deviceServices.abb(
                        device = device,
                        args = args,
                        shellCollector = collector,
                        stdinChannel = stdinChannel,
                        commandTimeout = commandTimeout,
                        bufferSize = bufferSize
                    )
                }

                Protocol.ABB_EXEC -> {
                    session.deviceServices.abb_exec(
                        device = device,
                        args = args,
                        shellCollector = ShellCommandHelpers.mapToLegacyCollector(collector),
                        stdinChannel = stdinChannel,
                        commandTimeout = commandTimeout,
                        bufferSize = bufferSize,
                        shutdownOutput = _shutdownOutputForExecProtocol
                    )
                }
            }
        }
    }

    private suspend fun pickProtocol(): Protocol {
        val shellV2Supported = SuspendingLazy {
            // abb support is exposed as a device (and ADB feature).
            session.hostServices.availableFeatures(device).contains(AdbFeatures.ABB)
        }
        val execSupported = SuspendingLazy {
            // abb_exec support is exposed as a device (and ADB feature).
            session.hostServices.availableFeatures(device).contains(AdbFeatures.ABB_EXEC)
        }
        val protocol = when {
            _allowExec && execSupported.value() -> Protocol.ABB_EXEC
            _allowShellV2 && shellV2Supported.value() -> Protocol.ABB
            else -> throw IllegalArgumentException("No compatible abb protocol is supported or allowed")
        }
        return protocol
    }
}
