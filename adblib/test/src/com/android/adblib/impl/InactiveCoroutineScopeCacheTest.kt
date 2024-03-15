/*
 * Copyright (C) 2024 The Android Open Source Project
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
import com.android.adblib.testingutils.CoroutineTestUtils.runBlockingWithTimeout
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException

class InactiveCoroutineScopeCacheTest {
    @JvmField
    @Rule
    var exceptionRule: ExpectedException = ExpectedException.none()

    data class TestKey(val id: String) : CoroutineScopeCache.Key<Any>("test key $id")

    @Test
    fun inactiveCacheGetOrPutDoesNotCache() {
        // Prepare
        val key = TestKey("5")

        // Act
        val value1 = InactiveCoroutineScopeCache.getOrPut(key) { 100 }
        val value2 = InactiveCoroutineScopeCache.getOrPut(key) { 200 }
        val value3 = InactiveCoroutineScopeCache.getOrPut(key) { 300 }

        // Assert
        Assert.assertEquals(100, value1)
        Assert.assertEquals(200, value2)
        Assert.assertEquals(300, value3)
    }

    @Test
    fun inactiveCacheGetOrPutSuspendingDoesNotCache() = runBlockingWithTimeout {
        // Prepare
        val key = TestKey("5")

        // Act
        val value1 = InactiveCoroutineScopeCache.getOrPutSuspending(key) { 100 }
        val value2 = InactiveCoroutineScopeCache.getOrPutSuspending(key) { 200 }
        val value3 = InactiveCoroutineScopeCache.getOrPutSuspending(key) { 300 }

        // Assert
        Assert.assertEquals(100, value1)
        Assert.assertEquals(200, value2)
        Assert.assertEquals(300, value3)
    }

    @Test
    fun inactiveCacheGetOrPutSuspendingWithFastDefaultDoesNotCache() = runBlockingWithTimeout {
        // Prepare
        val key = TestKey("5")

        // Act
        val value1 = InactiveCoroutineScopeCache.getOrPutSuspending(key, { 50 }) { 100 }
        val value2 = InactiveCoroutineScopeCache.getOrPutSuspending(key, { 60 }) { 200 }
        val value3 = InactiveCoroutineScopeCache.getOrPutSuspending(key, { 70 }) { 300 }

        // Assert
        Assert.assertEquals(50, value1)
        Assert.assertEquals(60, value2)
        Assert.assertEquals(70, value3)
    }

    @Test
    fun inactiveCacheGetOrPutSuspendingTransparentToExceptions() = runBlockingWithTimeout {
        // Prepare
        val key = CoroutineScopeCacheTest.TestKey("5")
        exceptionRule.expect(Exception::class.java)
        exceptionRule.expectMessage("My Exception")

        // Act
        InactiveCoroutineScopeCache.getOrPutSuspending(key) {
            throw Exception("My Exception")
        }

        // Assert
        Assert.fail("Should not reach")
    }
}
