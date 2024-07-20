package com.android.adblib.impl

import com.android.adblib.AdbServerStartup
import com.android.adblib.testingutils.CloseablesRule
import com.android.adblib.testingutils.TestingAdbSessionHost
import com.android.fakeadbserver.FakeAdbServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException
import java.io.IOException
import java.util.concurrent.TimeUnit

class AdbChannelProviderWithServerStartupTest {

    @JvmField
    @Rule
    val closeables = CloseablesRule()

    @JvmField
    @Rule
    var exceptionRule: ExpectedException = ExpectedException.none()

    private fun <T : AutoCloseable> registerCloseable(item: T): T {
        return closeables.register(item)
    }

    @Test
    fun test_StartAdbServerAndCreateChannel_whenInitialCreateChannelFails(): Unit = runBlocking {
        // Setup
        // Note that attempting to connect to port 0 should always fail and we'll attempt to
        // spin up the Adb Server
        val port = 0
        val server = FakeAdbServer.Builder().build()
        registerCloseable(server)
        val host = TestingAdbSessionHost()
        val adbServerStartup = object : AdbServerStartup {
            override suspend fun start(port: Int, timeout: Long, unit: TimeUnit): Int {
                server.start()
                return server.port
            }
        }
        val adbChannelProviderConnectAddresses =
            AdbChannelProviderWithServerStartup(
                host,
                AdbServerChannelConnectOptions(port),
                adbServerStartup
            )

        // Act
        val adbChannel =
            registerCloseable(
                adbChannelProviderConnectAddresses.createChannel(
                    Long.MAX_VALUE,
                    TimeUnit.MILLISECONDS
                )
            )

        // Assert
        Assert.assertNotNull(adbChannel)
    }

    @Test
    fun test_AdbServerNotFoundAndCannotBeStarted_throws(): Unit = runBlocking {
        // Setup
        // Note that attempting to connect to port 0 should always fail and we'll attempt to
        // spin up the Adb Server
        val port = 0
        val host = TestingAdbSessionHost()
        val adbServerStartup = object : AdbServerStartup {
            override suspend fun start(port: Int, timeout: Long, unit: TimeUnit): Int {
                throw IOException("Couldn't start a server")
            }
        }
        val adbChannelProviderConnectAddresses =
            AdbChannelProviderWithServerStartup(
                host,
                AdbServerChannelConnectOptions(port),
                adbServerStartup
            )
        exceptionRule.expect(IOException::class.java)
        exceptionRule.expectMessage("Couldn't start a server")

        // Act
        registerCloseable(
            adbChannelProviderConnectAddresses.createChannel(
                Long.MAX_VALUE,
                TimeUnit.MILLISECONDS
            )
        )

        // Assert
        Assert.fail("Test should have thrown exception")
    }

    @Test
    fun test_DoNotStartAdbServer_whenInitialCreateChannelAttemptSucceeds(): Unit = runBlocking {
        // Setup
        var triedToSpinUpAdbServer = false
        val server = FakeAdbServer.Builder().build()
        registerCloseable(server)
        server.start()
        val host = TestingAdbSessionHost()
        val adbServerStartup = object : AdbServerStartup {
            override suspend fun start(port: Int, timeout: Long, unit: TimeUnit): Int {
                // If this is called then `AdbChannelProviderWithServerStartup` is trying to spin
                // up a server
                triedToSpinUpAdbServer = true
                return port
            }
        }
        val adbChannelProviderConnectAddresses =
            AdbChannelProviderWithServerStartup(
                host,
                AdbServerChannelConnectOptions(server.port),
                adbServerStartup
            )

        // Act
        val adbChannel =
            registerCloseable(
                adbChannelProviderConnectAddresses.createChannel(
                    Long.MAX_VALUE,
                    TimeUnit.MILLISECONDS
                )
            )

        // Assert
        Assert.assertFalse(triedToSpinUpAdbServer)
        Assert.assertNotNull(adbChannel)
    }
}
