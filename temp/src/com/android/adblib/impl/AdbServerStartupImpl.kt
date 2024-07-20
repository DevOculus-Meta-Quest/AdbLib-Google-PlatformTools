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

import com.android.adblib.AdbServerStartup
import com.android.adblib.AdbSessionHost
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.io.IOException
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

internal class AdbServerStartupImpl(private val host: AdbSessionHost) : AdbServerStartup {

    override suspend fun start(port: Int, timeout: Long, unit: TimeUnit): Int {
        host.logger.debug { "Starting ADB server on port $port." }
        host.timeProvider.withErrorTimeout(timeout, unit) {
            runInterruptible(host.blockingIoDispatcher) {
                val processBuilder = ProcessBuilder(getAdbLaunchCommand(port))
                val process = processBuilder.start()
                val exitCode = process.waitFor()
                if (exitCode != 0) {
                    throw IOException("adb start-server failed. Exit code: $exitCode")
                }
            }
        }
        // We only attempt to spin up the Adb Server on the requested port
        return port
    }

    private fun getAdbLaunchCommand(port: Int): List<String> {
        return listOf(getAdbFile(), "-P", port.toString(), "start-server")
    }

    private fun getAdbFile(): String {
        val os = System.getProperty("os.name")
        val adbExecutableName = if (os.startsWith("Windows")) "adb.exe" else "adb"
        return findOnPath(adbExecutableName)
            ?: throw IOException("Couldn't locate '$adbExecutableName' on PATH")
    }

    private fun findOnPath(executableName: String): String? {
        System.getenv().forEach { (k, v) -> System.err.println("$k=$v") }
        val pathEnvVariable =
            System.getenv("PATH")
                ?: throw IOException("No PATH environmental variable is defined")
        for (binDir in pathEnvVariable.split(File.pathSeparator)) {
            val file = Paths.get(binDir).resolve(executableName).toFile()
            if (file.isFile) {
                return file.path
            }
        }
        host.logger.debug { "$executableName could not be located in any of the $pathEnvVariable folders" }
        return null
    }
}
