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

import com.android.adblib.utils.JdkLoggerFactory
import com.android.adblib.utils.SystemNanoTime
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import java.nio.channels.AsynchronousChannelGroup
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The host of a single ADB instance. Calling the [.close] method on the host
 * should release all resources acquired for running the corresponding ADB instance.
 */
@IsThreadSafe
open class AdbSessionHost : AutoCloseable {

    private val loggingFilter = OnlyOnceFilter<Property<*>>()

    /**
     * The [SystemNanoTimeProvider] for this host.
     */
    open val timeProvider: SystemNanoTimeProvider = SystemNanoTime()

    /**
     * The [AdbLoggerFactory] for this host.
     */
    open val loggerFactory: AdbLoggerFactory = JdkLoggerFactory()

    /**
     * The "main" or "root" logger from the [loggerFactory]
     */
    val logger: AdbLogger
        get() = loggerFactory.logger

    /**
     * The [AsynchronousChannelGroup] used for running [java.nio.channels.AsynchronousSocketChannel] completions.
     *
     * The default value (`null`) corresponds to the default JVM value.
     */
    open val asynchronousChannelGroup: AsynchronousChannelGroup? = null

    /**
     * CoroutineContext elements to include in the scope, such as [CoroutineExceptionHandler] and
     * [CoroutineName].
     *
     * Note that any [CoroutineDispatcher] or [Job] in this context will not be used; [ioDispatcher]
     * and a new [SupervisorJob] will be used instead.
     */
    open val parentContext: CoroutineContext = EmptyCoroutineContext

    /**
     * The coroutine dispatcher to use to execute asynchronous I/O and
     * compute intensive operations.
     *
     * The default value is [Dispatchers.Default]
     */
    open val ioDispatcher
        get() = Dispatchers.Default

    /**
     * The coroutine dispatcher to use to execute blocking I/O blocking operations.
     *
     * The default value is [Dispatchers.IO]
     */
    open val blockingIoDispatcher
        get() = Dispatchers.IO

    /**
     * Returns `true` if the current thread runs an event dispatching queue that should **not**
     * allow blocking operations, e.g. the current thread is an AWT event dispatching thread.
     *
     * @see SwingUtilities.isEventDispatchThread()
     */
    open val isEventDispatchThread: Boolean
        get() = SwingUtilities.isEventDispatchThread()

    /**
     * Returns [Instant.now]
     */
    open fun utcNow(): Instant = Instant.now()

    /**
     * Return the value of [property], either the [Property.defaultValue] or the value
     * this [AdbSessionHost] instance wants to override the property with.
     *
     * @see getSystemProperty
     */
    open fun <T : Any> getPropertyValue(property: Property<T>): T {
        val propertyValue = getSystemProperty(property.name) ?: return property.defaultValue

        return try {
            property.fromStringValue(propertyValue)
        } catch (t: Throwable) {
            // We log only once per property to prevent spamming the log
            loggingFilter.filter(property) {
                logger.warn(
                    t,
                    "Invalid or unsupported value '$propertyValue' for property " +
                            "'${property.name}', using default value " +
                            "'${property.defaultValue}' instead"
                )
            }
            property.defaultValue
        }
    }

    /**
     * Release resources acquired by this host. Any operation still pending
     * will either be immediately cancelled or fail at time of completion.
     */
    @Throws(Exception::class)
    override fun close() {
        // Nothing to do by default
    }

    /**
     * Returns the value of a system property (see [System.getProperty]), or `null` if the
     * system property is not set.
     *
     * @see System.getProperty
     */
    protected open fun getSystemProperty(name: String): String? {
        return System.getProperty(name)
    }

    /**
     * A named value of type [T] that has a [defaultValue] and can be deserialized from
     * a string value as needed.
     *
     * All property instances are expected to be unique, i.e. have identity equality.
     */
    abstract class Property<T : Any>(
        /**
         * The identifier of the property, typically in a java package name
         * format (e.g. `foo.bar.blah`)
         */
        val name: String,
        /**
         * The default value of the property, if not overridden
         */
        val defaultValue: T,
        /**
         * Whether the property value can change at runtime, i.e. whether it is safe to
         * cache the property value.
         */
        val isVolatile: Boolean = false
    ) {

        /**
         * Convert a string value to a valid value for this property.
         * Throws any [Throwable] exception if the conversion failed for any reason.
         */
        abstract fun fromStringValue(value: String): T

        override fun hashCode(): Int {
            return System.identityHashCode(this)
        }

        override fun equals(other: Any?): Boolean {
            return this === other
        }

        override fun toString(): String {
            return "Property(name=\"$name\", " +
                    "type=${defaultValue::class.java.simpleName}, " +
                    "defaultValue=${maybeQuoteValue(defaultValue)})"
        }

        private fun maybeQuoteValue(value: T): String {
            return if (value is String) {
                "\"$value\""
            } else {
                "$value"
            }
        }
    }

    class StringProperty(name: String, defaultValue: String, isVolatile: Boolean = false)
        : Property<String>(name, defaultValue, isVolatile) {

        override fun fromStringValue(value: String): String {
            return value
        }
    }

    class IntProperty(name: String, defaultValue: Int, isVolatile: Boolean = false)
        : Property<Int>(name, defaultValue, isVolatile) {

        override fun fromStringValue(value: String): Int {
            return value.toInt()
        }
    }

    class BooleanProperty(name: String, defaultValue: Boolean, isVolatile: Boolean = false)
        : Property<Boolean>(name, defaultValue, isVolatile) {

        override fun fromStringValue(value: String): Boolean {
            return value.toBoolean()
        }
    }

    class DurationProperty(name: String, defaultValue: Duration, isVolatile: Boolean = false) :
        Property<Duration>(name, defaultValue, isVolatile) {

        override fun fromStringValue(value: String): Duration {
            return Duration.parse(value)
        }
    }

    private class OnlyOnceFilter<T: Any> {

        private val seenKeys = ConcurrentHashMap.newKeySet<T>()

        inline fun filter(key: T, block: () -> Unit) {
            if (seenKeys.add(key)) {
                block()
            }
        }
    }
}
