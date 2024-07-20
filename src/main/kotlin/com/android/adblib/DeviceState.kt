package com.android.adblib

/**
 * The state of a device.
 *
 * See [states](https://cs.android.com/android/platform/superproject/+/790d619575aea7032a4fe5f097d412adedf6623b:packages/modules/adb/transport.cpp;l=1132)
 */
@Suppress("unused", "SpellCheckingInspection")
enum class DeviceState(val state: String) {

    /**
     * Device is fully online and available to perform most services, such as installing
     * an application, debugging, etc.
     */
    ONLINE("device"),

    /**
     * Device is connected but not generally available yet
     */
    OFFLINE("offline"),

    /**
     * Device running fastboot OS (fastboot) or userspace fastboot (fastbootd)
     */
    BOOTLOADER("bootloader"),

    /**
     * What a device sees from its end of a Transport (adb host)
     */
    HOST("host"),

    /**
     * Device with bootloader loaded but no ROM OS loaded (adbd)
     */
    RECOVERY("recovery"),

    /**
     * Device running Android OS Rescue mode (minadbd rescue mode)
     */
    RESCUE("rescue"),

    /**
     * TODO: This state is actually a short sentence
     *
     * See [no permissions](https://cs.android.com/android/platform/superproject/+/790d619575aea7032a4fe5f097d412adedf6623b:packages/modules/adb/transport.cpp;l=1148)
     */
    NO_PERMISSIONS("no permissions"),

    /**
     * Device is in "sideload" state either through `adb sideload` or recovery menu
     */
    SIDELOAD("sideload"),

    /**
     * ADB_VENDOR_KEYS exhausted, fell back to user prompt.
     */
    UNAUTHORIZED("unauthorized"),

    /**
     * Authorizing with keys from ADB_VENDOR_KEYS
     */
    AUTHORIZING("authorizing"),

    /**
     * Haven't received a response from the device yet
     */
    CONNECTING("connecting"),

    /**
     * Unknown state, i.e. a state that we somehow did not recognize
     */
    UNKNOWN("unknown"),

    /**
     * bootloader mode with is-userspace = true though `adb reboot fastboot`
     */
    FASTBOOTD("fastbootd"),

    /**
     * USB device that's detached from the adb server
     */
    DETACHED("detached"),

    /**
     * The terminal state of a ConnectedDevice. This is not emitted by ADB; this is emitted to
     * signal the end of the device session. If the device reconnects, it will have a new
     * ConnectedDevice instance.
     */
    DISCONNECTED("[disconnected]");

    companion object {
        /**
         * Returns a [DeviceState] from the string returned by `adb devices`.
         *
         * @param state the device state.
         * @return a [DeviceState] object or `null` if the state is unknown.
         */
        fun parseStateOrNull(state: String): DeviceState? {
            for (deviceState in values()) {
                if (deviceState.state == state) {
                    return deviceState
                }
            }
            return null
        }

        /**
         * Returns a [DeviceState] from the string returned by `adb devices`.
         *
         * @param state the device state.
         * @return a [DeviceState] object or [UNKNOWN] if the state is unknown.
         */
        fun parseState(state: String): DeviceState {
            return parseStateOrNull(state) ?: UNKNOWN
        }
    }
}
