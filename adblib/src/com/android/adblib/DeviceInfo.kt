package com.android.adblib

/**
 * A device definition as returned from [AdbHostServices.devices]
 */
data class DeviceInfo(
    /**
     * The serial number of the device that can be used to identify the devices
     * when sending requests to the ADB server.
     *
     * Note: Devices sometimes don't have a serial numbers, in which case they don't have
     * a corresponding [DeviceInfo] instance, i.e. this value is guaranteed to be valid.
     */
    val serialNumber: String,

    /**
     * The device state as defined in [DeviceState]. Any unknown or unsupported state is mapped
     * to [DeviceState.UNKNOWN]
     */
    val deviceState: DeviceState,

    /**
     * The product name of the device, or `null` if the value is not known.
     * This is usually the same as the "ro.product.name" property on the device.
     */
    val product: String? = null,

    /**
     * The model name of the device, or `null` if the value is not known.
     * This is usually the same as the "ro.product.model" property on the device.
     */
    val model: String? = null,

    /**
     * The device name of the device, or `null` if the value is not known.
     * This is usually the same as the "ro.product.device" property on the device.
     */
    val device: String? = null,

    /**
     * The transport identifier that can be used to identify the device when
     * sending requests to the ADB server, or `null` if the transport identifier
     * is not known.
     *
     * Note: The transport identifier is only present when using
     * [AdbHostServices.DeviceInfoFormat.LONG_FORMAT] when enumerating
     * devices with [AdbHostServices.devices]
     */
    val transportId: String? = null,

    /**
     * The type of connection detected by ADB.
     */
    val connectionType : DeviceConnectionType? = null,

    /**
     * The negotiated speed (in Mbits per seconds) the device is operating at.
     *
     * This value is null unless [AdbHostServices.DeviceInfoFormat.BINARY_PROTO_FORMAT] is used.
     * The value is 0 when the speed is unknown.
     * This is currently only implemented for [DeviceConnectionType.USB] devices.
     */
    val negotiatedSpeed: Long? = null,

    /**
     * The maximum speed (in Mbits per seconds) the device is capable off.
     *
     * This value is null unless [AdbHostServices.DeviceInfoFormat.BINARY_PROTO_FORMAT] is used.
     * The value is 0 when the speed is unknown.
     * This is currently only implemented for [DeviceConnectionType.USB] devices.
     */
    val maxSpeed: Long? = null,

    /**
     * Optional additional fields as name-value pairs.
     */
    val additionalFields: Map<String, String> = emptyMap(),
    ) {

    /**
     * The device state as received from the ADB server, just in case the value does not
     * fall into the pre-defined [DeviceState] enum.
     */
    val deviceStateString: String
        get() = additionalFields[RAW_DEVICE_STATE_VALUE] ?: deviceState.state

    companion object {

        private const val RAW_DEVICE_STATE_VALUE = "RAW_DEVICE_STATE_VALUE"

        internal fun fromParserValues(
            serialNumber: String,
            rawDeviceStateString: String,
            product: String? = null,
            model: String? = null,
            device: String? = null,
            transportId: String? = null,
            connectionType: DeviceConnectionType? = null,
            negotiatedSpeed: Long? = null,
            maxSpeed: Long? = null,
            additionalFields: Map<String, String> = emptyMap(),
        ): DeviceInfo {
            val deviceState = DeviceState.parseStateOrNull(rawDeviceStateString)

            // If we have an unknown device state value, we want to keep track of it
            // in a custom field
            val extraFields = if (deviceState == null) {
                mutableMapOf<String, String>().also {
                    it.putAll(additionalFields)
                    it[RAW_DEVICE_STATE_VALUE] = rawDeviceStateString
                }
            } else {
                additionalFields
            }

            return DeviceInfo(
                serialNumber = serialNumber,
                deviceState = deviceState ?: DeviceState.UNKNOWN,
                product = product,
                model = model,
                device = device,
                transportId = transportId,
                maxSpeed = maxSpeed,
                negotiatedSpeed = negotiatedSpeed,
                connectionType = connectionType,
                additionalFields = extraFields,
            )
        }
    }
}

/**
 * The connection type between a device and ADB server. [SOCKET] is the
 * default, [USB] is reported only for physical devices connected via a USB cable.
 */
enum class DeviceConnectionType {USB, SOCKET}

/**
 * List of [DeviceInfo] as returned by [AdbHostServices.devices], as well as list of
 * [ErrorLine] in case some lines in the output from ADB were not recognized.
 */
typealias DeviceList = ListWithErrors<DeviceInfo>
