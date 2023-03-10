package com.android.adblib

import com.android.adblib.utils.AdbProtocolUtils
import kotlinx.coroutines.CopyableThrowable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Exception thrown when a `FAIL` response is received from the ADB host
 */
sealed class AdbFailResponseException(
    val service: String,
    val failMessage: String
) : IOException() {

    abstract override val message: String

    override fun toString(): String {
        return message
    }

    companion object {

        internal fun extractMessageFromBuffer(buffer: ByteBuffer): String {
            return try {
                AdbProtocolUtils.byteBufferToString(buffer)
            } catch (e: Throwable) {
                "<unspecified>"
            }
        }
    }
}

/**
 * Exception thrown when a `FAIL` response is received from the ADB host when executing
 * a "host" (i.e. not device related) [service]
 */
@OptIn(
    /**
     * Needed to support [CopyableThrowable], see
     * https://github.com/Kotlin/kotlinx.coroutines/blob/master/docs/topics/debugging.md#stacktrace-recovery
     */
    ExperimentalCoroutinesApi::class
)
class AdbHostFailResponseException(service: String, failMessage: String) :
    AdbFailResponseException(service, failMessage),
    CopyableThrowable<AdbHostFailResponseException> {

    constructor(service: String, buffer: ByteBuffer) :
            this(service, extractMessageFromBuffer(buffer))

    override val message: String
        get() = "'$failMessage' error executing ADB service '$service'"

    override fun createCopy(): AdbHostFailResponseException {
        return AdbHostFailResponseException(service, failMessage).also {
            it.initCause(this)
        }
    }
}

/**
 * Exception thrown when a `FAIL` response is received from the ADB host when executing
 * [service] for a given [device].
 */
@OptIn(
    /**
     * Needed to support [CopyableThrowable], see
     * https://github.com/Kotlin/kotlinx.coroutines/blob/master/docs/topics/debugging.md#stacktrace-recovery
     */
    ExperimentalCoroutinesApi::class
)
class AdbDeviceFailResponseException(
    val device: DeviceSelector,
    service: String,
    failMessage: String
) : AdbFailResponseException(service, failMessage), CopyableThrowable<AdbDeviceFailResponseException> {

    constructor(deviceSerial: DeviceSelector, service: String, buffer: ByteBuffer) :
            this(deviceSerial, service, extractMessageFromBuffer(buffer))

    override val message: String
        get() = "'$failMessage' error on ${device.shortDescription} executing service '$service'"

    override fun createCopy(): AdbDeviceFailResponseException {
        return AdbDeviceFailResponseException(device, service, failMessage).also {
            it.initCause(this)
        }
    }

    override fun toString(): String {
        return message
    }
}
