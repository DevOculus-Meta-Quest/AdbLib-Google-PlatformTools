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
package com.android.adblib.utils

import com.android.adblib.testingutils.CoroutineTestUtils.runBlockingWithTimeout
import com.android.adblib.testingutils.CoroutineTestUtils.yieldUntil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.Assert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException

class RunAlongOtherScopeTest {

    @JvmField
    @Rule
    var exceptionRule: ExpectedException = ExpectedException.none()

    @Test
    fun testSimpleInvocationWorks() = runBlockingWithTimeout {
        // Prepare
        val otherScope = createChildScope(isSupervisor = true)

        // Act
        val foo = runAlongOtherScope(otherScope) {
            "foo"
        }

        // Assert
        assertEquals("foo", foo)

        // (Let test scope terminate)
        otherScope.cancel("Test ended")
    }

    @Test
    fun testSimpleSuspendingInvocationWorks() = runBlockingWithTimeout {
        // Prepare
        val otherScope = createChildScope(isSupervisor = true)

        // Act
        val foo = runAlongOtherScope(otherScope) {
            delay(10)
            "foo"
        }
        otherScope.cancel("End of test")

        // Assert
        assertEquals("foo", foo)
    }

    @Test
    fun testInvocationIsTransparentToException() = runBlockingWithTimeout {
        // Prepare
        val otherScope = createChildScope(isSupervisor = true)

        // Act
        exceptionRule.expect(Exception::class.java)
        exceptionRule.expectMessage("foo")
        runAlongOtherScope(otherScope) {
            throw Exception("foo")
        }

        // Assert
        @Suppress("UNREACHABLE_CODE")
        Assert.fail("Should not reach")
    }

    @Test
    fun testInvocationIsTransparentToCancellation() = runBlockingWithTimeout {
        // Prepare
        val otherScope = createChildScope(isSupervisor = true)

        // Act
        exceptionRule.expect(CancellationException::class.java)
        exceptionRule.expectMessage("foo")
        runAlongOtherScope(otherScope) {
            throw CancellationException("foo")
        }

        // Assert
        @Suppress("UNREACHABLE_CODE")
        Assert.fail("Should not reach")
    }

    @Test
    fun testInvocationIsTransparentToCancel() = runBlockingWithTimeout {
        // Prepare
        val otherScope = createChildScope(isSupervisor = true)

        // Act
        exceptionRule.expect(CancellationException::class.java)
        exceptionRule.expectMessage("foo")
        coroutineScope {
            runAlongOtherScope(otherScope) {
                cancel("foo")
            }
        }

        // Assert
        Assert.fail("Should not reach")
    }

    @Test
    fun testInvocationIsCancelledWhenOtherScopeIsCancelledBeforeInvocation() =
        runBlockingWithTimeout {
            // Prepare
            val otherScope = createChildScope(isSupervisor = true)

            // Act
            exceptionRule.expect(CancellationException::class.java)
            exceptionRule.expectMessage("foo")
            otherScope.cancel("foo")
            runAlongOtherScope(otherScope) {
                delay(1_000_000)
            }

            // Assert
            Assert.fail("Should not reach")
        }

    @Test
    fun testInvocationIsCancelledWhenOtherScopeIsCancelledDuringInvocation(): Unit =
        runBlockingWithTimeout {
            // Prepare
            val otherScope = createChildScope(isSupervisor = true)

            // Act
            val started = CompletableDeferred<Unit>()
            launch {
                started.await()
                otherScope.cancel("foo")
            }
            exceptionRule.expect(CancellationException::class.java)
            exceptionRule.expectMessage("foo")
            runAlongOtherScope(otherScope) {
                started.complete(Unit)
                delay(1_000_000)
            }

            // Assert
            Assert.fail("Should not reach")
        }

    @Test
    fun testInvocationIsCancelledWhenJobIsCancelled(): Unit = runBlockingWithTimeout {
        // Prepare
        val otherScope = createChildScope(isSupervisor = true)
        val parentScope = createChildScope()

        // Act
        val started = CompletableDeferred<Unit>()
        var exception: Throwable? = null
        val job = parentScope.async {
            runAlongOtherScope(otherScope) {
                try {
                    started.complete(Unit)
                    delay(1_000_000)
                } catch (t: Throwable) {
                    exception = t
                }
            }
        }

        started.await()
        job.cancel("foo")
        yieldUntil { exception != null }

        // Assert
        val result = kotlin.runCatching { job.await() }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals("foo", result.exceptionOrNull()?.message)

        assertTrue(exception is CancellationException)
        assertEquals("foo", exception?.message)

        // (Let test scope terminate)
        parentScope.cancel("Test ended")
        otherScope.cancel("Test ended")
    }

    @Test
    fun testInvocationIsCancelledWhenParentScopeIsCancelled(): Unit = runBlockingWithTimeout {
        // Prepare
        val otherScope = createChildScope(isSupervisor = true)
        val parentScope = createChildScope()

        // Act
        val started = CompletableDeferred<Unit>()
        var exception: Throwable? = null
        val job = parentScope.async {
            runAlongOtherScope(otherScope) {
                try {
                    started.complete(Unit)
                    delay(1_000_000)
                } catch (t: Throwable) {
                    exception = t
                }
            }
        }

        started.await()
        parentScope.cancel("foo")
        yieldUntil { exception != null }

        // Assert
        val result = kotlin.runCatching { job.await() }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals("foo", result.exceptionOrNull()?.message)

        assertTrue(exception is CancellationException)
        assertEquals("foo", exception?.message)

        // (Let test scope terminate)
        parentScope.cancel("Test ended")
        otherScope.cancel("Test ended")
    }
}
