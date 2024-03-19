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
package com.android.adblib.impl

import com.android.adblib.CoroutineScopeCache
import com.android.adblib.getOrPutSynchronized
import com.android.adblib.testingutils.CloseablesRule
import com.android.adblib.testingutils.CoroutineTestUtils.runBlockingWithTimeout
import com.android.adblib.testingutils.CoroutineTestUtils.yieldUntil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals

class CoroutineScopeCacheTest {

    @JvmField
    @Rule
    val closeables = CloseablesRule()

    @JvmField
    @Rule
    var exceptionRule: ExpectedException = ExpectedException.none()

    private fun <T : AutoCloseable> registerCloseable(item: T): T {
        return closeables.register(item)
    }

    data class TestKey(val id: String) : CoroutineScopeCache.Key<Any>("test key $id")

    @Test
    fun test_GetOrPut_Works() {
        // Prepare
        val scope = CoroutineScope(SupervisorJob())
        val cache = registerCloseable(CoroutineScopeCacheImpl(scope))

        // Act
        val value = cache.getOrPut(TestKey("5")) { "bar" }

        // Assert
        Assert.assertEquals("bar", value)
    }

    @Test
    fun test_GetOrPut_DoesNotOverrideValue() {
        // Prepare
        val scope = CoroutineScope(SupervisorJob())
        val cache = registerCloseable(CoroutineScopeCacheImpl(scope))
        val key = TestKey("5")

        // Act
        val value1 = cache.getOrPut(key) { "bar" }
        val value2 = cache.getOrPut(key) { "bar2" }

        // Assert
        Assert.assertEquals("bar", value1)
        Assert.assertEquals("bar", value2)
    }

    @Test
    fun test_Close_CallsAutoCloseable() {
        // Prepare
        val scope = CoroutineScope(SupervisorJob())
        val cache = registerCloseable(CoroutineScopeCacheImpl(scope))
        val key = TestKey("5")
        val closeable = object : AutoCloseable {
            var closed = false
            override fun close() {
                closed = true
            }
        }

        // Act
        cache.getOrPut(key) { closeable }
        val value1 = closeable.closed
        cache.close()
        val value2 = closeable.closed

        // Assert
        Assert.assertFalse(value1)
        Assert.assertTrue(value2)
    }

    @Test
    fun test_ScopeCancel_CallsAutoCloseable() {
        // Prepare
        val scope = CoroutineScope(SupervisorJob())
        val cache = registerCloseable(CoroutineScopeCacheImpl(scope))
        val key = TestKey("5")
        val closeable = object : AutoCloseable {
            var closed = false
            override fun close() {
                closed = true
            }
        }

        // Act
        cache.getOrPut(key) { closeable }
        val value1 = closeable.closed
        scope.cancel("Testing cancellation")
        val value2 = closeable.closed

        // Assert
        Assert.assertFalse(value1)
        Assert.assertTrue(value2)
    }

    @Test
    fun test_GetOrPutSuspending_WaitsForCoroutineToExecute() = runBlockingWithTimeout {
        // Prepare
        val scope = CoroutineScope(SupervisorJob())
        val cache = registerCloseable(CoroutineScopeCacheImpl(scope))
        val key = TestKey("5")

        // Act
        val syncChannel = Channel<Unit>()
        launch {
            syncChannel.send(Unit)
        }
        val value = cache.getOrPutSuspending(key) {
            syncChannel.receive()
            "bar"
        }

        // Assert
        Assert.assertEquals("bar", value)
    }

    @Test
    fun test_GetOrPutSuspending_CallsDefaultValueOnlyOnce() = runBlockingWithTimeout {
        // Prepare
        val scope = CoroutineScope(SupervisorJob())
        val cache = registerCloseable(CoroutineScopeCacheImpl(scope))
        val key = TestKey("5")
        val syncChannel = Channel<Unit>()
        var callCount = 0
        suspend fun getOrPutSuspending(): Any {
            return cache.getOrPutSuspending(key) {
                callCount++
                syncChannel.receive()
                "bar"
            }
        }

        // Act
        launch {
            delay(50)
            syncChannel.send(Unit) // Wakeup pending coroutine
        }
        val value = getOrPutSuspending()
        val value2 = getOrPutSuspending()

        delay(100) // Wait until map is updated
        val value3 = getOrPutSuspending()

        // Assert
        Assert.assertEquals("bar", value)
        Assert.assertEquals("bar", value2)
        Assert.assertEquals("bar", value3)
        Assert.assertEquals(1, callCount)
    }

    @Test
    fun test_GetOrPutSuspending_retryAfterFailureCallsDefaultValueOnlyOnce() = runBlockingWithTimeout {
        // Prepare
        val scope = CoroutineScope(SupervisorJob())
        val cache = registerCloseable(CoroutineScopeCacheImpl(scope))
        val key = TestKey("5")
        val syncChannel = Channel<Unit>()
        var callCount = 0
        suspend fun getOrPutSuspending(causeFailure: Boolean): Any {
            return cache.getOrPutSuspending(key) {
                if (causeFailure) {
                    throw Exception("My Exception")
                }
                callCount++
                syncChannel.receive()
                "bar"
            }
        }

        // Act
        // First put a cache value into a failed state
        try {
            getOrPutSuspending(true)
            Assert.fail("Should have thrown")
        } catch (t: Throwable) {
            // Expected exception
            Assert.assertEquals("My Exception", t.message)
        }
        launch {
            delay(50)
            syncChannel.send(Unit) // Wakeup pending coroutine
        }
        // Retry now and have a `defaultValue` not throw an exception any longer
        val value = getOrPutSuspending(false)
        val value2 = getOrPutSuspending(false)

        delay(100) // Wait until map is updated
        val value3 = getOrPutSuspending(false)

        // Assert
        Assert.assertEquals("bar", value)
        Assert.assertEquals("bar", value2)
        Assert.assertEquals("bar", value3)
        Assert.assertEquals(1, callCount)
    }

    @Test
    fun test_GetOrPutSuspending_WaitsForPendingComputation() = runBlockingWithTimeout {
        // Prepare
        val scope = CoroutineScope(SupervisorJob())
        val cache = registerCloseable(CoroutineScopeCacheImpl(scope))
        val key = TestKey("5")

        // Act
        val syncChannel1 = Channel<Unit>()
        val value1 = cache.getOrPutSuspending(key, { "blah" }) {
            syncChannel1.send(Unit)
            syncChannel1.send(Unit)
            "bar"
        }
        // Wait until computing value
        syncChannel1.receive()

        val deferredValue2 = async {
            cache.getOrPutSuspending(key) { "blah" }
        }
        delay(20)
        syncChannel1.receive()
        val value2 = deferredValue2.await()

        // Assert
        Assert.assertEquals("blah", value1)
        Assert.assertEquals("bar", value2)
    }

    @Test
    fun test_GetOrPutSuspending_ReturnsFastValueThenReturnsCoroutineValue() = runBlockingWithTimeout {
        // Prepare
        val scope = CoroutineScope(SupervisorJob())
        val cache = registerCloseable(CoroutineScopeCacheImpl(scope))
        val key = TestKey("5")

        // Act
        val syncChannel = Channel<Unit>()
        val value = cache.getOrPutSuspending(key, { "blah" }) {
            syncChannel.receive()
            "bar"
        }
        syncChannel.send(Unit) // Wakeup pending coroutine
        delay(50) // Wait until map is updated
        val value2 = cache.getOrPutSuspending(key, { "blah" }) {
            syncChannel.receive()
            "bar2"
        }

        // Assert
        Assert.assertEquals("blah", value)
        Assert.assertEquals("bar", value2)
    }

    @Test
    fun test_GetOrPutSuspendingWithFastDefault_CallsDefaultValueOnlyOnce() = runBlockingWithTimeout {
        // Prepare
        val scope = CoroutineScope(SupervisorJob())
        val cache = registerCloseable(CoroutineScopeCacheImpl(scope))
        val key = TestKey("5")
        val syncChannel = Channel<Unit>()
        var callCount = 0
        fun getOrPutSuspending(): Any {
            return cache.getOrPutSuspending(key, { "blah" }) {
                callCount++
                syncChannel.receive()
                "bar"
            }
        }

        // Act
        val value = getOrPutSuspending()
        val value2 = getOrPutSuspending()
        syncChannel.send(Unit) // Wakeup pending coroutine
        delay(50) // Wait until map is updated
        val value3 = getOrPutSuspending()

        // Assert
        Assert.assertEquals("blah", value)
        Assert.assertEquals("blah", value2)
        Assert.assertEquals("bar", value3)
        Assert.assertEquals(1, callCount)
    }

    @Test
    fun test_GetOrPut_UsesDifferentCacheThanGetOrPutSuspending() = runBlockingWithTimeout {
        // Prepare
        val scope = CoroutineScope(SupervisorJob())
        val cache = registerCloseable(CoroutineScopeCacheImpl(scope))
        val key = TestKey("5")

        // Act
        val value1 = cache.getOrPut(key) { "value2" }
        val value2 = cache.getOrPutSuspending(key) { "blah" }

        // Assert
        Assert.assertEquals("value2", value1)
        Assert.assertEquals("blah", value2)
    }

    @Test
    fun test_GetOrPutSuspending_RetriesOnCoroutineException() = runBlockingWithTimeout {
        // Prepare
        val scope = CoroutineScope(SupervisorJob())
        val cache = registerCloseable(CoroutineScopeCacheImpl(scope))
        val key = TestKey("5")

        // Act
        var exceptionThrown: Throwable? = null
        try {
            cache.getOrPutSuspending(key) {
                throw Exception("My Exception")
            }
        } catch (e: Exception) {
            exceptionThrown = e
        }

        delay(50) // Wait until map is updated and retry
        val value2 = cache.getOrPutSuspending(key) {
            "foo"
        }

        // Retry again. This time a cached value should be returned.
        delay(50)
        val value3 = cache.getOrPutSuspending(key) {
            "other"
        }

        // Assert
        Assert.assertNotNull(exceptionThrown)
        Assert.assertEquals("My Exception", exceptionThrown?.message)
        Assert.assertEquals("foo", value2)
        Assert.assertEquals("foo", value3)
    }

    @Test
    fun test_GetOrPutSuspendingWithFastDefault_RetriesOnCoroutineException() = runBlockingWithTimeout {
        // Prepare
        val scope = CoroutineScope(SupervisorJob())
        val cache = registerCloseable(CoroutineScopeCacheImpl(scope))
        val key = TestKey("5")

        // Act
        val value1 = cache.getOrPutSuspending(key, { "blah" }) {
            throw Exception("My Exception")
        }

        delay(50) // Wait until map is updated and retry
        val value2 = cache.getOrPutSuspending(key, { "blah" }) {
            "foo"
        }

        // Retry again. This time there should be a computed value in the cache.
        delay(50)
        val value3 = cache.getOrPutSuspending(key, { "blah" }) {
            "other"
        }

        // Assert
        Assert.assertEquals("blah", value1)
        Assert.assertEquals("blah", value2)
        Assert.assertEquals("foo", value3)
    }

    @Test
    fun test_Close_CancelsScope() = runBlockingWithTimeout {
        // Prepare
        val scope = CoroutineScope(SupervisorJob())
        val cache = registerCloseable(CoroutineScopeCacheImpl(scope))
        val key = TestKey("5")

        // Act
        val syncChannel = Channel<Unit>(1)
        var job: Job? = null
        val value = cache.getOrPutSuspending(key, { "blah" }) {
            job = this.coroutineContext.job
            syncChannel.receive()
            "bar"
        }
        yieldUntil {
            job != null
        }
        cache.close()

        // Assert
        Assert.assertEquals("blah", value)
        Assert.assertFalse(job!!.isActive)
        Assert.assertFalse(cache.scope.isActive)
    }

    @Test
    fun test_computation_getsCancelledOnCacheClose() = runBlockingWithTimeout {
        // Prepare
        val scope = CoroutineScope(SupervisorJob())
        val cache = registerCloseable(CoroutineScopeCacheImpl(scope))
        val key = TestKey("5")
        exceptionRule.expect(CancellationException::class.java)
        exceptionRule.expectMessage("CoroutineScopeCacheImpl has been closed")

        // Act
        launch {
            delay(100)
            cache.close()
        }

        cache.getOrPutSuspending(key) {
            delay(Long.MAX_VALUE)
            "bar"
        }

        // Assert
        Assert.fail("Test should have thrown exception")
    }

    @Test
    fun test_computationWithFastDefault_getsCancelledOnCacheClose() = runBlockingWithTimeout {
        // Prepare
        val scope = CoroutineScope(SupervisorJob())
        val cache = registerCloseable(CoroutineScopeCacheImpl(scope))
        val key = TestKey("5")

        // Act
        launch {
            delay(100)
            cache.close()
        }

        var computationIsCancelled = false
        val value = cache.getOrPutSuspending(key, { "foo" }) {
            try {
                delay(Long.MAX_VALUE)
            } catch (e: CancellationException) {
                computationIsCancelled = true
            }
            "bar"
        }
        // Wait for the `cache.close` call to complete
        delay(200)

        // Assert
        Assert.assertEquals("foo", value)
        Assert.assertTrue(computationIsCancelled)
        Assert.assertFalse(cache.scope.isActive)
    }

    @Test
    fun test_GetOrPutSynchronized_Works() {
        // Prepare
        val scope = CoroutineScope(SupervisorJob())
        val cache = registerCloseable(CoroutineScopeCacheImpl(scope))
        val key = TestKey("5")

        // Act
        val value = cache.getOrPutSynchronized(key) { "bar" }

        // Assert
        Assert.assertEquals("bar", value)
    }

    @Test
    fun test_GetOrPutSynchronized_DoesNotOverrideValue() {
        // Prepare
        val scope = CoroutineScope(SupervisorJob())
        val cache = registerCloseable(CoroutineScopeCacheImpl(scope))
        val key = TestKey("5")

        // Act
        val value1 = cache.getOrPutSynchronized(key) { "bar" }
        val value2 = cache.getOrPutSynchronized(key) { "bar2" }

        // Assert
        Assert.assertEquals("bar", value1)
        Assert.assertEquals("bar", value2)
    }

    @Test
    fun test_GetOrPutSynchronized_IsSynchronized(): Unit = runBlockingWithTimeout {
        // Prepare
        val scope = CoroutineScope(SupervisorJob())
        val cache = registerCloseable(CoroutineScopeCacheImpl(scope))
        class MyValue
        val key = CoroutineScopeCache.Key<MyValue>("myValue")

        // Act
        val runCount = AtomicInteger()
        val values = (1..1_000).map {
            async(Dispatchers.Default) {
                cache.getOrPutSynchronized(key) {
                    runCount.incrementAndGet()
                    MyValue()
                }
            }
        }.awaitAll()

        // Assert
        assertEquals(1, runCount.get())
        assertEquals(1, values.toSet().size)
    }

    @Test
    fun test_GetOrPutSynchronized_Supports_AutoCloseable(): Unit = runBlockingWithTimeout {
        // Prepare
        val scope = CoroutineScope(SupervisorJob())
        val cache = registerCloseable(CoroutineScopeCacheImpl(scope))
        class MyValue : AutoCloseable {
            var closed = false
            override fun close() {
                closed = true
            }
        }
        val key = CoroutineScopeCache.Key<MyValue>("myValue")

        // Act
        val value = cache.getOrPutSynchronized(key) { MyValue() }
        cache.close()

        // Assert
        Assert.assertTrue(value.closed)
    }

    @Test
    fun test_usingClosedCache_getOrPut() = runBlockingWithTimeout {
        // Prepare
        val scope = CoroutineScope(SupervisorJob())
        val cache = registerCloseable(CoroutineScopeCacheImpl(scope))
        val key = TestKey("5")

        // Act
        cache.close()
        val value1 = cache.getOrPut(key) { 100 }
        val value2 = cache.getOrPut(key) { 200 }

        // Assert
        Assert.assertEquals(100, value1)
        Assert.assertEquals(200, value2)
    }

    @Test
    fun test_usingClosedCache_getOrPutSuspending_throwsCancellationException() =
        runBlockingWithTimeout {
            // Prepare
            val scope = CoroutineScope(SupervisorJob())
            val cache = registerCloseable(CoroutineScopeCacheImpl(scope))
            val key = TestKey("5")
            exceptionRule.expect(CancellationException::class.java)
            exceptionRule.expectMessage("CoroutineScopeCacheImpl has been closed")

            // Act
            cache.close()
            cache.getOrPutSuspending(key) { 3000 }

            // Assert
            Assert.fail("Test should have thrown exception")
        }

    @Test
    fun test_usingClosedCache_getOrPutSuspendingWithFastDefault() = runBlockingWithTimeout {
        // Prepare
        val scope = CoroutineScope(SupervisorJob())
        val cache = registerCloseable(CoroutineScopeCacheImpl(scope))
        val key = TestKey("5")

        // Act
        cache.close()
        val value1 = cache.getOrPutSuspending(key, {50000}) { 200000 }
        val value2 = cache.getOrPutSuspending(key, {60000}) { 300000 }

        // Assert
        Assert.assertEquals(50000, value1)
        Assert.assertEquals(60000, value2)
    }
}
