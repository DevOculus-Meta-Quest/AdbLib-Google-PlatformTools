package com.android.adblib.impl

import com.android.adblib.AdbHostServices.DeviceInfoFormat
import com.android.adblib.DeviceConnectionType
import com.android.server.adb.protos.DevicesProto
import com.android.adblib.DeviceInfo
import com.android.adblib.DeviceList
import com.android.adblib.DeviceState
import com.android.adblib.ListWithErrors
import com.android.adblib.utils.AdbProtocolUtils
import java.nio.ByteBuffer
import java.util.regex.Pattern

/** ADB returns "(no serial number)" when serial number is not available  */
private const val NO_SERIAL_PATTERN = "\\(.*\\)"

/** Any serial number that is not [.NO_SERIAL_PATTERN]  */
private const val REGULAR_SERIAL_PATTERN = "[\\S&&[^(]]\\S*"
private const val SERIAL_PATTERN = "$REGULAR_SERIAL_PATTERN|$NO_SERIAL_PATTERN"

/** TODO: This is not correct for the "no permissions" pattern  */
private const val DEVICE_STATE_PATTERN = "\\S+"
private const val ADDITIONAL_FIELDS_PATTERN = ".*"
private const val SHORT_LINE_STATE_PATTERN = ".*"

/** 3 groups: serial - state - list of additional fields  */
private val LONG_LINE_PATTERN = Pattern.compile(
    "(" + SERIAL_PATTERN + ")" + "\\s+" +
            "(" + DEVICE_STATE_PATTERN + ")" + "\\s*" +
            "(" + ADDITIONAL_FIELDS_PATTERN + ")$"
)
private val ONE_FIELD_PATTERN = Pattern.compile("(\\w+):(\\w+)")

/** 2 groups: serial - state  */
private val SHORT_LINE_PATTERN = Pattern.compile(
    "(" + SERIAL_PATTERN + ")" + "\t" +
            "(" + SHORT_LINE_STATE_PATTERN + ")$"
)

internal abstract class DeviceListParser {

    abstract fun parse(buffer: ByteBuffer): DeviceList;

}

internal class DeviceListProtoParser : DeviceListParser() {

    fun protoState(state: DevicesProto.ConnectionState): DeviceState {
        return when (state) {
            DevicesProto.ConnectionState.UNRECOGNIZED,
            DevicesProto.ConnectionState.ANY -> DeviceState.UNKNOWN
            DevicesProto.ConnectionState.CONNECTING -> DeviceState.CONNECTING
            DevicesProto.ConnectionState.AUTHORIZING -> DeviceState.AUTHORIZING
            DevicesProto.ConnectionState.UNAUTHORIZED -> DeviceState.UNAUTHORIZED
            DevicesProto.ConnectionState.NOPERMISSION -> DeviceState.NO_PERMISSIONS
            DevicesProto.ConnectionState.DETACHED-> DeviceState.DETACHED
            DevicesProto.ConnectionState.OFFLINE-> DeviceState.OFFLINE
            DevicesProto.ConnectionState.BOOTLOADER-> DeviceState.BOOTLOADER
            DevicesProto.ConnectionState.DEVICE-> DeviceState.ONLINE
            DevicesProto.ConnectionState.HOST-> DeviceState.HOST
            DevicesProto.ConnectionState.RECOVERY-> DeviceState.RECOVERY
            DevicesProto.ConnectionState.SIDELOAD-> DeviceState.SIDELOAD
            DevicesProto.ConnectionState.RESCUE-> DeviceState.RESCUE
        }
    }

    override fun parse(buffer: ByteBuffer): DeviceList {
        val result = ListWithErrors.Builder<DeviceInfo>()
        val devices = DevicesProto.Devices.parseFrom(buffer)
        devices.deviceList.forEach {
            result.addEntry(DeviceInfo(
                serialNumber = it.serial,
                deviceState = protoState(it.state),
                product = it.product,
                model = it.model,
                device = it.device,
                transportId = it.transportId.toString(),
                maxSpeed = it.maxSpeed,
                negotiatedSpeed = it.negotiatedSpeed,
                connectionType =  when (it.connectionType) {
                    DevicesProto.ConnectionType.USB ->DeviceConnectionType.USB
                    null,
                    DevicesProto.ConnectionType.SOCKET,
                    DevicesProto.ConnectionType.UNKNOWN,
                    DevicesProto.ConnectionType.UNRECOGNIZED -> DeviceConnectionType.SOCKET
                }
            )
            )
        }
        return result.build()
    }
}

internal class DeviceListTextParser(val format: DeviceInfoFormat) : DeviceListParser() {
    override fun parse(buffer: ByteBuffer): DeviceList {

            val parser = when (format) {
                DeviceInfoFormat.SHORT_FORMAT -> this::parseOneShortFormatLine
                DeviceInfoFormat.LONG_FORMAT -> this::parseOneLongFormatLine
                else -> throw IllegalStateException("Unsupported format: $format")
            }

            return parserWorker(buffer, parser)

    }

    private fun parserWorker(buffer: ByteBuffer, oneLineParser: OneLineParser): DeviceList {
        return parserWorker(AdbProtocolUtils.byteBufferToString(buffer), oneLineParser)
    }

    fun parse(responseText: String): DeviceList {

        val parser = when (format) {
            DeviceInfoFormat.SHORT_FORMAT -> this::parseOneShortFormatLine
            DeviceInfoFormat.LONG_FORMAT -> this::parseOneLongFormatLine
            else -> throw IllegalStateException("Unsupported format: $format")
        }
        return parserWorker(responseText, parser)

    }

    private fun parserWorker(responseText: String, oneLineParser: OneLineParser): DeviceList {
        val result = ListWithErrors.Builder<DeviceInfo>()

        // Special case of <no devices>
        if (responseText.isEmpty()) {
            return result.build()
        }

        // There should be one device per line
        val lines = responseText.split(AdbProtocolUtils.ADB_NEW_LINE)
        for ((lineIndex, line) in lines.withIndex()) {
            if (line.isNotEmpty()) {
                oneLineParser.call(result, lineIndex, line)
            }
        }
        return result.build()
    }

    private fun parseOneShortFormatLine(
        result: ListWithErrors.Builder<DeviceInfo>,
        lineIndex: Int,
        lineText: CharSequence
    ) {
        // Output format in ADB Host code:
        // https://cs.android.com/android/platform/superproject/+/790d619575aea7032a4fe5f097d412adedf6623b:packages/modules/adb/transport.cpp;l=1316
        val matcher = SHORT_LINE_PATTERN.matcher(lineText)
        if (!matcher.matches()) {
            result.addError("Device line format is not recognized", lineIndex, lineText.toString())
            return
        }
        val serialNumber = matcher.group(1)
        if (serialNumber == null || serialNumber.isEmpty()) {
            result.addError("Device serial number is empty", lineIndex, lineText)
            return
        }
        val deviceState = matcher.group(2)
        if (deviceState == null || deviceState.isEmpty()) {
            result.addError("Device state is empty", lineIndex, lineText)
            return
        }
        result.addEntry(DeviceInfo.fromParserValues(serialNumber, deviceState))
    }

    private fun parseOneLongFormatLine(
        result: ListWithErrors.Builder<DeviceInfo>,
        lineIndex: Int,
        lineText: CharSequence
    ) {
        // Output format in ADB Host code:
        // https://cs.android.com/android/platform/superproject/+/790d619575aea7032a4fe5f097d412adedf6623b:packages/modules/adb/transport.cpp;l=1316
        val matcher = LONG_LINE_PATTERN.matcher(lineText)
        if (!matcher.matches()) {
            result.addError("Device line format is not recognized", lineIndex, lineText)
            return
        }
        val serialNumber = matcher.group(1)
        if (serialNumber == null || serialNumber.isEmpty()) {
            //TODO: If there is a "transport_id" field, we could expose the device, as it can be identified
            // with a "host-transport-id" prefix.
            result.addError("Device serial number is empty", lineIndex, lineText)
            return
        }
        val deviceState = matcher.group(2)
        if (deviceState == null || deviceState.isEmpty()) {
            result.addError("Device state is empty", lineIndex, lineText)
            return
        }
        var product: String? = null
        var model: String? = null
        var device: String? = null
        var transportId: String? = null
        val moreFields = mutableMapOf<String, String>()
        // Each field is "name:value", see ADB Host code:
        // https://cs.android.com/android/platform/superproject/+/790d619575aea7032a4fe5f097d412adedf6623b:packages/modules/adb/transport.cpp;l=1331
        val matcherFields = ONE_FIELD_PATTERN.matcher(lineText)
        while (matcherFields.find()) {
            val name = matcherFields.group(1)
            val value = matcherFields.group(2)
            when (name) {
                "product" -> product = value
                "model" -> model = value
                "device" -> device = value
                "transport_id" -> transportId = value
                else -> moreFields[name] = value
            }

        }
        val deviceInfo =
            DeviceInfo.fromParserValues(
                serialNumber,
                deviceState,
                product,
                model,
                device,
                transportId,
                additionalFields = if (moreFields.isEmpty()) emptyMap() else moreFields
            )
        result.addEntry(deviceInfo)
    }

    internal fun interface OneLineParser {

        fun call(
            result: ListWithErrors.Builder<DeviceInfo>,
            lineIndex: Int,
            lineText: CharSequence
        )
    }
}
