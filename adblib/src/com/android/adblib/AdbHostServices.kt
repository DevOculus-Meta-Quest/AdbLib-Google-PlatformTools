package com.android.adblib

import com.android.adblib.utils.toImmutableSet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Exposes services specific to the ADB Server (or "host") as `suspend` functions
 *
 * The underlying implementation is responsible for creating connections to the ADB server
 * as needed, as well as ensuring resources are released when coroutines complete or
 * are cancelled.
 */
@IsThreadSafe
interface AdbHostServices {

    /**
     * The session this [AdbHostServices] instance belongs to.
     */
    val session: AdbSession

    /**
     * Returns the internal version of the ADB server ("host:version" query).
     *
     * The internal version is an integer value that is incremented when newer builds of ADB
     * are incompatible with older ADB clients. This value is somewhat opaque to
     * public consumers, but this API is provided for completeness.
     */
    suspend fun version(): Int

    /**
     * Returns the list of features supported by the ADB server ("host:host-features" query).
     *
     * Note: Not all features supported by the ADB Server may be usable depending on the
     * list of features supported by a given device (see [AdbHostServices.features]).
     * Use [AdbHostServices.availableFeatures] to get the list of features supported
     * by both the ADB server and a given device.
     *
     * @see [AdbFeatures]
     * @see [AdbHostServices.availableFeatures]
     */
    suspend fun hostFeatures(): List<String>

    /**
     * Returns the list of devices known to the ADB Server as a [DeviceList] object
     * ("host:devices" query).
     *
     * Use the [format] parameter to specify how much information to collect for each
     * device ([short][DeviceInfoFormat.SHORT_FORMAT] or [long][DeviceInfoFormat.LONG_FORMAT]
     * format supported).
     */
    suspend fun devices(format: DeviceInfoFormat = DeviceInfoFormat.SHORT_FORMAT): DeviceList

    /**
     * Returns a [Flow] that emits a new [DeviceList] everytime a device state change is
     * detected by the ADB Host ("host:track-devices" query). The flow is active until
     * an exception is thrown or cancellation is requested by the flow consumer.
     */
    fun trackDevices(format: DeviceInfoFormat = DeviceInfoFormat.SHORT_FORMAT): Flow<DeviceList>

    enum class DeviceInfoFormat {
        /**
         * [DeviceInfo.serialNumber] and [DeviceInfo.deviceState] only
         */
        SHORT_FORMAT,

        /**
         * [DeviceInfo.serialNumber], [DeviceInfo.deviceState], and additional fields, such as [DeviceInfo.transportId]
         */
        LONG_FORMAT,

        /**
         * Binary protobuf output. See proto/devices.proto for details of the format
         */
        BINARY_PROTO_FORMAT,
    }

    /**
     * Kills the running instance of the ADB server ("host:kill" query).
     */
    suspend fun kill()

    /**
     * Checks mDNS is supported on this version of ADB ("host:mdns:check" query).
     */
    suspend fun mdnsCheck(): MdnsCheckResult

    /**
     * Returns a list of mDNS services known to the ADB server ("host:mdns:services" query).
     */
    suspend fun mdnsServices(): MdnsServiceList

    /**
     * Pairs this ADB server with a device given its [deviceAddress] and a [pairingCode].
     */
    suspend fun pair(deviceAddress: DeviceAddress, pairingCode: String): PairResult

    /**
     * Returns the [DeviceState] of the [device] ("<device-prefix>:get-state" query).
     */
    suspend fun getState(device: DeviceSelector): DeviceState

    /**
     * Returns the serial number of the [device] ("<device-prefix>:get-serialno" query).
     *
     * Note: [forceRoundTrip] prevents the implementation from looking at the serial number
     * value that may already be present in the [DeviceSelector] of [device]. It essentially
     * ensures a `get-serialno` query is sent to the ADB Server even if the serial number
     * is known.
     */
    suspend fun getSerialNo(device: DeviceSelector, forceRoundTrip: Boolean = false): String

    /**
     * Returns the `dev-path` of the [device] ("<device-prefix>:get-devpath" query).
     */
    suspend fun getDevPath(device: DeviceSelector): String

    /**
     * Returns the list of features of the [device] ("<device-prefix>:features" query).
     * See [AdbFeatures] for a (subset of the) list of possible features.
     *
     * Note: Not all features supported by a device may be usable depending on the list
     * of features supported by ADB server (see [AdbHostServices.hostFeatures]).
     * Use [AdbHostServices.availableFeatures] to get the list of features supported
     * by both the device and the ADB server.
     *
     * @see [AdbFeatures]
     * @see [AdbHostServices.availableFeatures]
     */
    suspend fun features(device: DeviceSelector): List<String>

    /**
     * Returns the list of all forward socket connections ("`host:list-forward`" query)
     * as a [list][ForwardSocketList] of [ForwardSocketInfo].
     */
    suspend fun listForward(): ForwardSocketList

    /**
     * Creates a forward socket connection from [local] to [remote]
     * ("`<device-prefix>:forward(:norebind)`" query).
     *
     * This method tells the ADB server to open a [server socket][SocketSpec] on the local machine,
     * forwarding all client connections made to that server socket to a
     * [remote socket][SocketSpec] on the specified [device].
     *
     * When invoking this method, the ADB Server does not validate the format of the [remote]
     * socket specification, nor does it connect to the [device]. A connection to the device
     * (ADB Daemon) is made only when a client connects to the local server socket. At that point,
     * if [remote] is invalid, the new client connection is immediately closed.
     *
     * This method fails if there is already a forward connection from [local], unless
     * [rebind] is `true`.
     *
     * Returns the ADB Server reply to the request, typically a TCP port number if using
     * `tcp:0` for [local]
     */
    suspend fun forward(
        device: DeviceSelector,
        local: SocketSpec,
        remote: SocketSpec,
        rebind: Boolean = false
    ): String?

    /**
     * Closes a previously created forward socket connection for the given [device]
     * ("`<device-prefix>:kill-forward`" query).
     */
    suspend fun killForward(device: DeviceSelector, local: SocketSpec)

    /**
     * Closes all previously created forward socket connections for the given [device].
     */
    suspend fun killForwardAll(device: DeviceSelector)

    /**
     * Connects to the specified device ("host:connect:$deviceAddress").
     */
    suspend fun connect(deviceAddress: DeviceAddress)

    /**
     * Disconnects the specified device ("host:disconnect:$deviceAddress").
     */
    suspend fun disconnect(deviceAddress: DeviceAddress)

    /**
     * Waits for [device] to be in a given [deviceState] using the given [transport] option
     * ("`<device-prefix>:wait-for-<transport>-<state>`" query).
     *
     * From the output of [`adb help`](https://cs.android.com/android/platform/superproject/+/3a52886262ae22477a7d8ffb12adba64daf6aafa:packages/modules/adb/client/commandline.cpp;l=209):
     *
     *     wait-for-TRANSPORT-STATE: wait for device to be in a given state
     *      TRANSPORT: "local" | "usb" | "any"
     *      STATE: "device" | "recovery" | "rescue" | "sideload" | "bootloader" | "any" | "disconnect"
     *
     * **Note**
     *
     * The intent of this service is for CLI applications that want to expose a way
     * to wait for a device to be in a given state before processing to the next command
     * in a script.
     *
     * For other types of applications, it is usually easier to use [AdbHostServices.trackDevices]
     * and wait on the [StateFlow].
     */
    suspend fun waitFor(
        device: DeviceSelector,
        deviceState: WaitForState,
        transport: WaitForTransport = WaitForTransport.ANY
    )
}

/**
 * A device state value to use when calling [AdbHostServices.waitFor]
 *
 * @see ONLINE
 * @see RECOVERY
 * @see RESCUE
 * @see SIDELOAD
 * @see BOOTLOADER
 * @see ANY
 * @see DISCONNECT
 */
class WaitForState(private val queryValue: String) {

    /**
     * Returns the string to use in the underlying ADB protocol command/query
     */
    internal fun toQueryString(): String {
        return queryValue
    }

    override fun toString(): String {
        return "wait-for-state: " + toQueryString()
    }

    companion object {

        val ONLINE = WaitForState("device")
        val RECOVERY = WaitForState("recovery")
        val RESCUE = WaitForState("rescue")
        @Suppress("SpellCheckingInspection")
        val SIDELOAD = WaitForState("sideload")
        val BOOTLOADER = WaitForState("bootloader")
        val ANY = WaitForState("any")
        val DISCONNECT = WaitForState("disconnect")
    }
}

/**
 * A device transport value to use when calling [AdbHostServices.waitFor]
 *
 * @see USB
 * @see LOCAL
 * @see ANY
 */
class WaitForTransport(private val queryValue: String) {
    /**
     * Returns the string to use in the underlying ADB protocol command/query
     */
    internal fun toQueryString(): String {
        return queryValue
    }

    override fun toString(): String {
        return "wait-for-transport: " + toQueryString()
    }

    companion object {
        val USB = WaitForTransport("usb")
        val LOCAL = WaitForTransport("local")
        val ANY = WaitForTransport("any")
    }
}

/**
 * Returns the list of features supported by both the [device] and the ADB server.
 *
 * See [AdbFeatures] for a (subset of the) list of possible features.
 */
suspend fun AdbHostServices.availableFeatures(device: DeviceSelector): Set<String> {
    return session.deviceCacheProvider.withDeviceCacheIfAvailable(device, availableFeaturesKey) {
        // We must return only the set of features common to both the host and the device.
        val deviceFeaturesSet = features(device).toSet()
        val hostFeaturesSet = hostFeatures().toSet()
        hostFeaturesSet.intersect(deviceFeaturesSet).toImmutableSet()
    }
}

/**
 * Whether an [AdbFeatures] is supported by both the [device] and the ADB server.
 *
 * See [AdbFeatures] for a (subset of the) list of possible features.
 */
suspend fun AdbHostServices.hasAvailableFeature(device: DeviceSelector, feature: String): Boolean {
    return availableFeatures(device).contains(feature)
}

/**
 * Returns true if the device with a specified serial number is known to the ADB Server.
 */
suspend fun AdbHostServices.isKnownDevice(serialNumber: String) =
    devices().any { it.serialNumber == serialNumber }

private val availableFeaturesKey = CoroutineScopeCache.Key<Set<String>>("availableFeaturesKey")
