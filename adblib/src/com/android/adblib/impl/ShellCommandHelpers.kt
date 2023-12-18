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

import com.android.adblib.ShellCollector
import com.android.adblib.ShellCollectorCapabilities
import com.android.adblib.ShellV2Collector
import kotlinx.coroutines.flow.FlowCollector
import java.nio.ByteBuffer

internal object ShellCommandHelpers {
    fun <T> mapToLegacyCollector(shellV2Collector: ShellV2Collector<T>): ShellCollector<T> {
        return if (shellV2Collector is LegacyShellToShellV2Collector) {
            shellV2Collector.legacyShellCollector
        } else {
            ShellV2ToLegacyCollector(shellV2Collector)
        }
    }

    fun <T> mapToShellV2Collector(shellCollector: ShellCollector<T>): ShellV2Collector<T> {
        return if (shellCollector is ShellV2ToLegacyCollector) {
            shellCollector.shellV2Collector
        } else {
            return LegacyShellToShellV2Collector(shellCollector)
        }
    }

}

internal val <T> ShellV2Collector<T>.isSingleOutputCollector: Boolean
    get() {
        return (this as? ShellCollectorCapabilities)?.isSingleOutput ?: false
    }

internal class LegacyShellToShellV2Collector<T>(
    internal val legacyShellCollector: ShellCollector<T>
) : ShellV2Collector<T>, ShellCollectorCapabilities {

    override val isSingleOutput: Boolean
        get() = (legacyShellCollector as? ShellCollectorCapabilities)?.isSingleOutput ?: false

    override suspend fun start(collector: FlowCollector<T>) {
        legacyShellCollector.start(collector)
    }

    override suspend fun collectStdout(collector: FlowCollector<T>, stdout: ByteBuffer) {
        legacyShellCollector.collect(collector, stdout)
    }

    override suspend fun collectStderr(collector: FlowCollector<T>, stderr: ByteBuffer) {
        legacyShellCollector.collect(collector, stderr)
    }

    override suspend fun end(collector: FlowCollector<T>, exitCode: Int) {
        legacyShellCollector.end(collector)
    }

}

internal class ShellV2ToLegacyCollector<T>(
    internal val shellV2Collector: ShellV2Collector<T>
) : ShellCollector<T>, ShellCollectorCapabilities {

    override val isSingleOutput: Boolean
        get() = (shellV2Collector as? ShellCollectorCapabilities)?.isSingleOutput ?: false

    override suspend fun start(collector: FlowCollector<T>) {
        shellV2Collector.start(collector)
    }

    override suspend fun collect(collector: FlowCollector<T>, stdout: ByteBuffer) {
        shellV2Collector.collectStdout(collector, stdout)
    }

    override suspend fun end(collector: FlowCollector<T>) {
        shellV2Collector.end(collector, 0)
    }
}
