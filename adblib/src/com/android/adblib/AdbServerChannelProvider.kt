package com.android.adblib

import com.android.adblib.impl.AdbChannelProviderConnectAddresses
import com.android.adblib.impl.AdbChannelProviderOpenLocalHost
import com.android.adblib.impl.AdbChannelProviderWithServerStartup
import com.android.adblib.impl.TimeoutTracker
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private const val DEFAULT_ADB_HOST_PORT = 5037

/**
 * A provider of [AdbChannel] instances ready for communication with an ADB host.
 *
 * This abstraction is used to ensure various service implementations don't depend on concrete
 * implementations of acquiring connections to the ADB host.
 *
 * See the Companion object for getting access to the commonly used implementations.
 */
interface AdbServerChannelProvider {

    /**
     * Opens a new [AdbChannel] to communicate with an ADB Server. Implementations can decide to
     * re-use an existing ADB Server or start a new instance on-demand if needed. Callers are
     * responsible for [closing][AutoCloseable.close] the returned [AdbChannel] instance when
     * done.
     *
     * [timeout] and [unit] determine the timeout before the method fails with a [TimeoutException].
     *
     * @param timeout the maximum time allowed to open the channel (0 means "timeout immediately")
     * @param unit the time unit of the timeout argument
     *
     * @throws TimeoutException if a channel can't be opened before the timeout expires
     * @throws IOException for errors related to communicating with the ADB Server
     * @throws Exception for any other error
     */
    suspend fun createChannel(
        timeout: Long = Long.MAX_VALUE,
        unit: TimeUnit = TimeUnit.MILLISECONDS
    ): AdbChannel

    companion object {

        fun createOpenLocalHost(
            host: AdbSessionHost,
            portSupplier: suspend () -> Int = { DEFAULT_ADB_HOST_PORT }
        ): AdbServerChannelProvider {
            return AdbChannelProviderOpenLocalHost(host, portSupplier)
        }

        fun createConnectAddresses(
            host: AdbSessionHost,
            socketAddressesSupplier: suspend () -> List<InetSocketAddress>
        ): AdbServerChannelProvider {
            return AdbChannelProviderConnectAddresses(host, socketAddressesSupplier)
        }

        fun createConnectAddressesWithServerStartup(
            host: AdbSessionHost
        ): AdbServerChannelProvider {
            return AdbChannelProviderWithServerStartup(host)
        }
    }
}

internal suspend fun AdbServerChannelProvider.createChannel(timeout: TimeoutTracker): AdbChannel {
    return createChannel(timeout.remainingNanos, TimeUnit.NANOSECONDS)
}
