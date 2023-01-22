/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.adblib

import com.android.adblib.testingutils.CloseablesRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Duration

class AdbSessionHostTest {

    @JvmField
    @Rule
    val closeables = CloseablesRule()

    private fun <T : AutoCloseable> registerCloseable(item: T): T {
        return closeables.register(item)
    }

    @Test
    fun propertyToStringWorks() {
        // Act
        val property = AdbSessionHost.StringProperty("foo", "bar")

        // Assert
        assertEquals(
            "Property(name=\"foo\", type=String, defaultValue=\"bar\")",
            property.toString()
        )
    }

    @Test
    fun hostReturnsSystemProperties() {
        // Prepare
        val host = registerCloseable(AdbSessionHost())
        val systemProperties = System.getProperties()
            .mapNotNull { entry ->
                val key = entry.key
                val value = entry.value
                if (key is String && value is String) {
                    AdbSessionHost.StringProperty(key, "")
                } else {
                    null
                }
            }

        // Act
        val properties = systemProperties.associateBy({ it.name }) {
            host.getPropertyValue(it)
        }

        // Assert
        assertTrue(
            "This test assumes there is at least one system property set",
            systemProperties.isNotEmpty()
        )
        assertEquals(systemProperties.size, properties.size)
        systemProperties.forEach { systemProperty ->
            assertEquals(System.getProperty(systemProperty.name), properties[systemProperty.name])
        }
    }

    @Test
    fun hostPropertyReturnsOverriddenValue() {
        // Prepare
        val host = registerCloseable(TestAdbSessionHost())
        host.setSystemProperty("foo", "200")
        val prop = AdbSessionHost.IntProperty("foo", 10)

        // Act
        val value = host.getPropertyValue(prop)

        // Assert
        assertEquals(200, value)
    }

    @Test
    fun hostPropertyReturnsDefaultValueIfNotOverridden() {
        // Prepare
        val host = registerCloseable(TestAdbSessionHost())
        val prop = AdbSessionHost.IntProperty("foo", 10)

        // Act
        val value = host.getPropertyValue(prop)

        // Assert
        assertEquals(10, value)
    }

    @Test
    fun hostPropertyHandlesIncorrectValueFormat() {
        // Prepare
        val host = registerCloseable(TestAdbSessionHost())
        host.setSystemProperty("foo", "adbc")
        val prop = AdbSessionHost.IntProperty("foo", 10)

        // Act
        val value = host.getPropertyValue(prop)

        // Assert
        assertEquals(10, value)
    }

    @Test
    fun hostPropertyWorksWithStringProperty() {
        // Prepare
        val host = registerCloseable(TestAdbSessionHost())
        host.setSystemProperty("foo", "abc")
        val prop = AdbSessionHost.StringProperty("foo", "def")

        // Act
        val value = host.getPropertyValue(prop)

        // Assert
        assertEquals("abc", value)
    }

    @Test
    fun hostPropertyWorksWithDurationProperty() {
        // Prepare
        val host = registerCloseable(TestAdbSessionHost())
        host.setSystemProperty("foo", Duration.ofMillis(100).toString())
        val prop = AdbSessionHost.DurationProperty("foo", Duration.ofMillis(200))

        // Act
        val value = host.getPropertyValue(prop)

        // Assert
        assertEquals(Duration.ofMillis(100), value)
    }

    @Test
    fun hostPropertyValueFormatErrorsAreLoggedOnlyOnce() {
        // Prepare
        val host = registerCloseable(TestAdbSessionHost())
        host.setSystemProperty("foo", "abc")
        val prop = AdbSessionHost.IntProperty("foo", 100)

        // Act
        repeat(10) {
            host.getPropertyValue(prop)
        }

        // Assert
        assertEquals(1, host.loggerFactory.logger.messages.size)
        assertEquals(
            "Invalid or unsupported value 'abc' for property 'foo', " +
                    "using default value '100' instead",
            host.loggerFactory.logger.messages[0]
        )
    }

    private class TestAdbSessionHost : AdbSessionHost() {

        val systemProperties = mutableMapOf<String, String>()

        override val loggerFactory: TestingAdbLoggerFactory = TestingAdbLoggerFactory()

        fun setSystemProperty(name: String, value: String) {
            systemProperties[name] = value
        }

        override fun getSystemProperty(name: String): String? {
            return systemProperties[name]
        }
    }

    private class TestingAdbLoggerFactory : AdbLoggerFactory {

        override val logger: TestingAdbLogger = TestingAdbLogger()

        override fun createLogger(cls: Class<*>): AdbLogger {
            return logger
        }

        override fun createLogger(category: String): AdbLogger {
            return logger
        }
    }

    private class TestingAdbLogger : AdbLogger() {

        val messages = mutableListOf<String>()

        override var minLevel: Level = Level.VERBOSE

        override fun log(level: Level, message: String) {
            log(level, null, message)
        }

        override fun log(level: Level, exception: Throwable?, message: String) {
            synchronized(messages) {
                messages.add(message)
            }
        }
    }
}

