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
package com.android.adblib

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Duration

/**
 * Abstraction over a device currently connected to ADB. An instance of [ConnectedDevice] is
 * valid as long as the device is connected to the underlying ADB server, and becomes
 * invalid as soon as the device is disconnected or ADB server is restarted.
 *
 * @see [AdbSession.connectedDevicesTracker]
 */
@IsThreadSafe
interface ConnectedDevice {

    /**
     * The [session][AdbSession] this device belongs to. When the session is
     * [closed][AdbSession.close], this [ConnectedDevice] instance becomes invalid.
     */
    val session: AdbSession

    /**
     * Returns a [CoroutineScopeCache] associated to this [ConnectedDevice]. The cache
     * is cleared when the device is disconnected.
     */
    val cache: CoroutineScopeCache

    /**
     * The [StateFlow] of [DeviceInfo] corresponding to change of state of the device.
     * Once the device is disconnected, the [DeviceInfo.deviceState] is always
     * set to [DeviceState.DISCONNECTED].
     */
    val deviceInfoFlow: StateFlow<DeviceInfo>
}

/**
 * A [CoroutineScope] tied to this [ConnectedDevice] instance. The scope is cancelled
 * when the device is disconnected, when the [ConnectedDevice.session] is closed or when
 * the ADB server is restarted.
 */
val ConnectedDevice.scope: CoroutineScope
    get() = cache.scope

/**
 * The "serial number" of this [device][ConnectedDevice], used to identify a device with
 * the ADB server as long as the device is connected.
 */
val ConnectedDevice.serialNumber: String
    get() = deviceInfoFlow.value.serialNumber

/**
 * The [DeviceSelector] of this [device][ConnectedDevice], used to identify a device with
 * the ADB server as long as the device is connected.
 */
val ConnectedDevice.selector: DeviceSelector
    get() = DeviceSelector.fromSerialNumber(serialNumber)

/**
 * Whether the device is [DeviceState.ONLINE], i.e. ready to be used.
 */
val ConnectedDevice.isOnline: Boolean
    get() = deviceInfoFlow.value.deviceState == DeviceState.ONLINE

/**
 * The current (or last known) [DeviceInfo] for this [ConnectedDevice].
 */
val ConnectedDevice.deviceInfo: DeviceInfo
    get() = deviceInfoFlow.value

/**
 * Shortcut to the [DeviceProperties] of this device.
 */
fun ConnectedDevice.deviceProperties(): DeviceProperties =
    session.deviceServices.deviceProperties(DeviceSelector.fromSerialNumber(serialNumber))

/**
 * When the device comes online, starts and returns the flow from [transform].
 * Retries the flow if an exception occurs and the device is still connected.
 */
fun <R> ConnectedDevice.flowWhenOnline(
    retryDelay: Duration,
    transform: suspend (device: ConnectedDevice) -> Flow<R>
): Flow<R> {
    val device = this
    return deviceInfoFlow
        .map {
            it.deviceState
        }
        .filter {
            it == DeviceState.ONLINE
        }
        .distinctUntilChanged()
        .flatMapConcat {
            transform(device)
        }
        .retryWhen { throwable, _ ->
            device.adbLogger(session).warn(
                throwable,
                "Device $device flow failed with error '${throwable.message}', " +
                        "retrying in ${retryDelay.seconds} sec"
            )
            // We retry as long as the device is valid
            if (device.scope.isActive) {
                delay(retryDelay.toMillis())
            }
            device.scope.isActive
        }.flowOn(session.host.ioDispatcher)
}

/**
 * Returns a [WithDeviceScopeContext] used to invoke the [action] coroutine using the
 * [CoroutineScope] of this [ConnectedDevice].
 *
 * * Optionally call [withRetry][WithDeviceScopeContext.withRetry] to set a predicate to invoke
 * as retry policy
 * * Optionally call [withFinally][WithDeviceScopeContext.withFinally] to set an action to
 * invoke before [execute][WithDeviceScopeContext.execute] terminates (exceptionally or not), i.e.
 * when [action] throws an exception not handled by [withRetry][WithDeviceScopeContext.withRetry],
 * or when the [withRetry][WithDeviceScopeContext.withRetry] predicate throws an exception, or
 * when the [ConnectedDevice.scope] is cancelled.
 * * Call [execute][WithDeviceScopeContext.execute] to start the execution of [action], using the
 * previously applied [withRetry][WithDeviceScopeContext.withRetry] and
 * [withFinally][WithDeviceScopeContext.withFinally] lamdbas.
 *
 *  Example:
 *  ```
 *      device.withScopeContext {
 *          device.trackJdwp().collect {
 *             (...)
 *          }
 *      }.withRetry { throwable ->
 *          delay(2_000)
 *          true // try again
 *      }.withFinally {
 *          (...)
 *      }.execute()
 *  ```
 */
fun ConnectedDevice.withScopeContext(
    action: suspend CoroutineScope.() -> Unit
): WithDeviceScopeContext {
    return WithDeviceScopeContext(this, action)
}

class WithDeviceScopeContext(
    private val device: ConnectedDevice,
    private val action: suspend CoroutineScope.() -> Unit
) {

    private val logger = adbLogger(device.session).withPrefix("device=$device - ")

    private var retryPredicate: suspend (Throwable) -> Boolean = { false }
    private var finallyAction: () -> Unit = {}

    /**
     * Sets the [predicate] to invoke when the block passed to [execute] throws a [Throwable]
     * and the device is still connected. This is a suspending function, so [delay] can
     * be safely used.
     */
    fun withRetry(predicate: suspend (Throwable) -> Boolean): WithDeviceScopeContext {
        this.retryPredicate = predicate
        return this
    }

    /**
     * Sets the [action] to invoke before [execute] terminates (exceptionally or not), i.e.
     * * either when [action] throws an exception not handled by [withRetry],
     * * or when the [withRetry] predicate throws an exception,
     * * or when the [ConnectedDevice.scope] is cancelled
     */
    fun withFinally(action: () -> Unit): WithDeviceScopeContext {
        this.finallyAction = action
        return this
    }

    /**
     * Executes the coroutine [action] in the context of the [scope][ConnectedDevice.scope] of
     * the [ConnectedDevice], retrying on failure for as long as the device scope is
     * [active][CoroutineScope.isActive].
     */
    suspend fun execute() {
        try {
            withContext(device.scope.coroutineContext) {
                var shouldRetry = true
                while (shouldRetry) {
                    ensureActive()
                    try {
                        action()
                        // If action succeeds, we don't need to retry anymore
                        shouldRetry = false
                    } catch (t: Throwable) {
                        logger.verbose(t) { "Exception occurred during execution of service: $t" }
                        runCatching {
                            retryPredicate(t)
                        }.onSuccess {
                            shouldRetry = it
                            if (!shouldRetry) {
                                throw t
                            }
                        }.onFailure { throwable ->
                            logger.debug(throwable) {
                                "Retry predicate failed with an exception, " +
                                        "rethrowing original exception '$t'"
                            }
                            t.addSuppressed(throwable)
                            throw t
                        }
                    }
                }
            }
        } finally {
            logger.debug { "End of retry loop, calling finally (device scope=${device.scope})" }
            finallyAction()
        }
    }
}

private val ShellManagerKey = CoroutineScopeCache.Key<ShellManager>("ShellManager")

/**
 * The [ShellManager] instance for executing shell commands on this [ConnectedDevice]
 */
val ConnectedDevice.shell: ShellManager
    get() {
        return cache.getOrPut(ShellManagerKey) {
            ShellManager(this)
        }
    }


private val FileSystemManagerKey = CoroutineScopeCache.Key<FileSystemManager>("FileSystemManager")

/**
 * The [FileSystemManager] instance for managing files on this [ConnectedDevice]
 */
val ConnectedDevice.fileSystem: FileSystemManager
    get() {
        return cache.getOrPut(FileSystemManagerKey) {
            FileSystemManager(this)
        }
    }

private val ReverseForwardManagerKey = CoroutineScopeCache.Key<ReverseForwardManager>("ReverseForwardManager")

/**
 * The [ReverseForwardManager] instance for managing "reverse forward" connections associated to
 * this device.
 */
val ConnectedDevice.reverseForward: ReverseForwardManager
    get() {
        return cache.getOrPut(ReverseForwardManagerKey) {
            ReverseForwardManager(this)
        }
    }

/**
 * Restarts the device as "root"
 *
 * @see AdbDeviceServices.root
 */
suspend fun ConnectedDevice.root(): RootResult {
    return session.deviceServices.root(selector)
}

/**
 * Restarts the device as "unroot"
 *
 * @see AdbDeviceServices.unRoot
 */
suspend fun ConnectedDevice.unRoot(): RootResult {
    return session.deviceServices.unRoot(selector)
}

/**
 * Restarts the device as "root", waiting until it is restarted
 *
 * @see AdbDeviceServices.rootAndWait
 */
suspend fun ConnectedDevice.rootAndWait(): RootResult {
    return session.deviceServices.rootAndWait(selector)
}

/**
 * Restarts the device as "unroot", waiting until it is restarted
 *
 * @see AdbDeviceServices.unRootAndWait
 */
suspend fun ConnectedDevice.unRootAndWait(): RootResult {
    return session.deviceServices.unRootAndWait(selector)
}

/**
 * Manages "reverse forward" connections of a given [ConnectedDevice]
 */
class ReverseForwardManager(val device: ConnectedDevice) {
    /**
     * Returns the list of active [reverse forwards][ReverseSocketInfo] of this [device].
     *
     * @see AdbDeviceServices.reverseListForward
     */
    suspend fun list(): ListWithErrors<ReverseSocketInfo> {
        return device.session.deviceServices.reverseListForward(device.selector)
    }

    /**
     * Creates a "reverse forward" socket connection from [remote] to [local] on this [device]
     *
     * @see AdbDeviceServices.reverseForward
     */
    suspend fun add(remote: SocketSpec, local: SocketSpec, rebind: Boolean = false): String? {
        return device.session.deviceServices.reverseForward(device.selector, remote, local, rebind)
    }

    /**
     * Closes the "reverse forward" socket connection identified by [remote] on this [device]
     *
     * @see AdbDeviceServices.reverseKillForward
     */
    suspend fun kill(remote: SocketSpec) {
        device.session.deviceServices.reverseKillForward(device.selector, remote)
    }

    /**
     * Closes all "reverse forward" socket connection on this [device]
     *
     * @see AdbDeviceServices.reverseKillForwardAll
     */
    suspend fun killAll() {
        device.session.deviceServices.reverseKillForwardAll(device.selector)
    }
}

/**
 * Manages file transfer for a given [ConnectedDevice]
 */
class FileSystemManager(val device: ConnectedDevice) {

    /**
     * Opens a [AdbDeviceSyncServices] session on this [device] for performing one or more file
     * transfer operation. The returned [AdbDeviceSyncServices] should be
     * [closed][AdbDeviceSyncServices.close] when not needed anymore.
     *
     * @see AdbDeviceServices.sync
     */
    suspend fun openSyncServices(): AdbDeviceSyncServices {
        return device.session.deviceServices.sync(device.selector)
    }

    /**
     * Opens a [AdbDeviceSyncServices] session on this [device] for performing one or more file
     * transfer operation in the given [block].
     *
     * @see AdbDeviceServices.sync
     */
    suspend inline fun <R> withSyncServices(block: (AdbDeviceSyncServices) -> R): R {
        return openSyncServices().use {
            block(it)
        }
    }

    /**
     * Copies the contents of [sourceChannel] to the [remoteFilePath] of this [device].
     *
     * @see AdbDeviceServices.syncSend
     */
    suspend fun sendFile(
        sourceChannel: AdbInputChannel,
        remoteFilePath: String,
        remoteFileMode: RemoteFileMode,
        remoteFileTime: FileTime? = null,
        progress: SyncProgress? = null,
        bufferSize: Int = SYNC_DATA_MAX
    ) {
        device.session.deviceServices.syncSend(
            device.selector,
            sourceChannel,
            remoteFilePath,
            remoteFileMode,
            remoteFileTime,
            progress,
            bufferSize
        )
    }

    /**
     * Copies the contents of [sourcePath] to the [remoteFilePath] of this [device].
     *
     * @see AdbDeviceServices.syncSend
     */
    suspend fun sendFile(
        sourcePath: Path,
        remoteFilePath: String,
        remoteFileMode: RemoteFileMode,
        remoteFileTime: FileTime? = null,
        progress: SyncProgress? = null,
        bufferSize: Int = SYNC_DATA_MAX
    ) {
        device.session.deviceServices.syncSend(
            device.selector,
            sourcePath,
            remoteFilePath,
            remoteFileMode,
            remoteFileTime,
            progress,
            bufferSize
        )
    }

    /**
     * Copies the contents of the [remoteFilePath] of this [device] to [destinationChannel].
     *
     * @see AdbDeviceServices.syncRecv
     */
    suspend fun receiveFile(
        remoteFilePath: String,
        destinationChannel: AdbOutputChannel,
        progress: SyncProgress? = null,
        bufferSize: Int = SYNC_DATA_MAX
    ) {
        device.session.deviceServices.syncRecv(
            device.selector,
            remoteFilePath,
            destinationChannel,
            progress,
            bufferSize
        )
    }

    /**
     * Copies the contents of the [remoteFilePath] of this [device] to [destinationPath].
     *
     * @see AdbDeviceServices.syncRecv
     */
    suspend fun receiveFile(
        remoteFilePath: String,
        destinationPath: Path,
        progress: SyncProgress? = null,
        bufferSize: Int = SYNC_DATA_MAX
    ) {
        device.session.deviceServices.syncRecv(
            device.selector,
            remoteFilePath,
            destinationPath,
            progress,
            bufferSize
        )
    }
}

/**
 * Manages execution of shell commands for a given [ConnectedDevice]
 */
class ShellManager(val device: ConnectedDevice) {

    /**
     * Returns a [ShellCommand] instance for executing an arbitrary shell command.
     *
     * @see AdbDeviceServices.shellCommand
     */
    fun command(command: String): ShellCommand<*> {
        return device.session.deviceServices.shellCommand(device.selector, command)
    }

    /**
     * Executes a shell [command] on the device, and returns the result of the execution
     * as a [ShellCommandOutput].
     *
     * Note: This method should be used only for commands that output a relatively small
     * amount of text.
     *
     * @see AdbDeviceServices.shellAsText
     */
    suspend fun executeAsText(
        command: String,
        stdinChannel: AdbInputChannel? = null,
        commandTimeout: Duration = INFINITE_DURATION,
        bufferSize: Int = device.session.property(AdbLibProperties.DEFAULT_SHELL_BUFFER_SIZE),
    ): ShellCommandOutput {
        return device.session.deviceServices.shellAsText(
            device.selector,
            command,
            stdinChannel,
            commandTimeout,
            bufferSize
        )
    }

    /**
     * Executes a shell [command] on the device, and returns the result of the execution
     * as a [Flow] of [ShellCommandOutputElement].
     *
     * @see AdbDeviceServices.shellAsLines
     */
    fun executeAsLines(
        command: String,
        stdinChannel: AdbInputChannel? = null,
        commandTimeout: Duration = INFINITE_DURATION,
        bufferSize: Int = device.session.property(AdbLibProperties.DEFAULT_SHELL_BUFFER_SIZE),
    ): Flow<ShellCommandOutputElement> {
        return device.session.deviceServices.shellAsLines(
            device.selector,
            command,
            stdinChannel,
            commandTimeout,
            bufferSize
        )
    }

    /**
     * Executes a shell [command] on the device, and returns the result of the execution
     * as a [Flow] of [BatchShellCommandOutputElement].
     *
     * @see AdbDeviceServices.shellAsLineBatches
     */
    fun executeAsLineBatches(
        command: String,
        stdinChannel: AdbInputChannel? = null,
        commandTimeout: Duration = INFINITE_DURATION,
        bufferSize: Int = device.session.property(AdbLibProperties.DEFAULT_SHELL_BUFFER_SIZE),
    ): Flow<BatchShellCommandOutputElement> {
        return device.session.deviceServices.shellAsLineBatches(
            device.selector,
            command,
            stdinChannel,
            commandTimeout,
            bufferSize
        )
    }
}

/**
 * Returns the list of features supported by both the [device] and the ADB server.
 *
 * See [AdbFeatures] for a (subset of the) list of possible features.
 */
suspend fun ConnectedDevice.availableFeatures(): Set<String> {
    return session.hostServices.availableFeatures(selector)
}

/**
 * Whether an [AdbFeatures] is supported by both the [device] and the ADB server.
 *
 * See [AdbFeatures] for a (subset of the) list of possible features.
 */
suspend fun ConnectedDevice.hasAvailableFeature(feature: String): Boolean {
    return session.hostServices.hasAvailableFeature(selector, feature)
}
