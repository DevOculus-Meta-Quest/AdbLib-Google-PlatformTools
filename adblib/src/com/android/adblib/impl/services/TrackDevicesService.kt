package com.android.adblib.impl.services

import com.android.adblib.AdbChannel
import com.android.adblib.AdbFeatures
import com.android.adblib.AdbHostServices.DeviceInfoFormat
import com.android.adblib.DeviceList
import com.android.adblib.adbLogger
import com.android.adblib.impl.DeviceListProtoParser
import com.android.adblib.impl.DeviceListParser
import com.android.adblib.impl.DeviceListTextParser
import com.android.adblib.impl.TimeoutTracker
import com.android.adblib.impl.TimeoutTracker.Companion.INFINITE
import com.android.adblib.utils.ResizableBuffer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.util.concurrent.TimeUnit

internal class TrackDevicesService(private val serviceRunner: AdbServiceRunner) {

    private val logger = adbLogger(host)

    private val host
        get() = serviceRunner.host

    fun invoke(format: DeviceInfoFormat, timeout: Long, unit: TimeUnit): Flow<DeviceList> = flow {
        val tracker = TimeoutTracker(host.timeProvider, timeout, unit)
        val service: String
        val deviceParser: DeviceListParser
        when (format) {
            DeviceInfoFormat.SHORT_FORMAT -> {
                service = "host:track-devices"
                deviceParser = DeviceListTextParser(format)
            }
            DeviceInfoFormat.LONG_FORMAT -> {
                service = "host:track-devices-l"
                deviceParser = DeviceListTextParser(format)
            }
            DeviceInfoFormat.BINARY_PROTO_FORMAT -> {
                service = "host:track-devices-proto-binary"
                deviceParser = DeviceListProtoParser()
            }
        }

        val workBuffer = ResizableBuffer()
        logger.info { "\"${service}\" - opening connection to ADB server, timeout: $tracker" }

        serviceRunner.startHostQuery(workBuffer, service, tracker).use { channel ->
            collectAdbResponses(channel, workBuffer, service, deviceParser, this)
        }
    }.flowOn(host.ioDispatcher)

    private suspend fun collectAdbResponses(
        channel: AdbChannel,
        workBuffer: ResizableBuffer,
        service: String,
        deviceParser: DeviceListParser,
        flowCollector: FlowCollector<DeviceList>
    ) {
        while (true) {
            // Note: We use an infinite timeout here, as the only way to end this request is to close
            //       the underlying ADB socket channel (or cancel the coroutine). This is by design.
            logger.debug { "\"${service}\" - waiting for next device tracking message" }
            val buffer = serviceRunner.readLengthPrefixedData(channel, workBuffer, INFINITE)

            // Process list of device and send it to the flow

            val devices = deviceParser.parse(buffer)

            logger.debug { "\"${service}\" - sending list of (${devices.size} device(s))" }
            flowCollector.emit(devices)
        }
    }
}
