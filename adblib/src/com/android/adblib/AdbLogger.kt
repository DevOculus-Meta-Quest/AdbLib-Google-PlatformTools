package com.android.adblib

import java.util.concurrent.ConcurrentHashMap
import java.util.function.Function


/**
 * A general purpose "Logger" abstraction used throughout adblib.
 */
@IsThreadSafe
abstract class AdbLogger {

    /**
     * The current minimum logging [Level]. This [AdbLogger] instance logs messages
     * only if they are of greater or equal severity than [minLevel].
     *
     * For example, if [minLevel] is [Level.WARN], only [Level.WARN] and [Level.ERROR] messages
     * will be logged, while [Level.INFO], [Level.VERBOSE] and [Level.DEBUG] will be skipped.
     */
    abstract val minLevel: Level

    abstract fun log(level: Level, message: String)
    abstract fun log(level: Level, exception: Throwable?, message: String)

    inline fun logIf(level: Level, message: () -> String) {
        if (minLevel <= level) {
            log(level, message())
        }
    }

    inline fun logIf(level: Level, exception: Throwable?, message: () -> String) {
        if (minLevel <= level) {
            log(level, exception, message())
        }
    }

    inline fun verbose(message: () -> String) {
        logIf(Level.VERBOSE, message)
    }

    inline fun verbose(exception: Throwable?, message: () -> String) {
        logIf(Level.VERBOSE, exception, message)
    }

    inline fun debug(message: () -> String) {
        logIf(Level.DEBUG, message)
    }

    inline fun debug(exception: Throwable?, message: () -> String) {
        logIf(Level.DEBUG, exception, message)
    }

    inline fun info(message: () -> String) {
        logIf(Level.INFO, message)
    }

    inline fun info(exception: Throwable?, message: () -> String) {
        logIf(Level.INFO, exception, message)
    }

    fun warn(message: String) {
        log(Level.WARN, message)
    }

    fun warn(exception: Throwable?, message: String) {
        log(Level.WARN, exception, message)
    }

    fun error(message: String) {
        log(Level.ERROR, message)
    }

    fun error(exception: Throwable?, message: String) {
        log(Level.ERROR, exception, message)
    }

    enum class Level {
        VERBOSE, DEBUG, INFO, WARN, ERROR
    }
}

/**
 * Wraps this [AdbLogger] instance as an [AdbLogger] instance that re-formats all messages
 * with the given [prefix].
 */
fun AdbLogger.withPrefix(prefix: String): AdbLogger {
    return object : AdbLoggerDelegate(this) {
        override fun formatMessage(message: String): String {
            return "$prefix$message"
        }
    }
}

/**
 * Returns an [AdbLogger] that overrides the [AdbLogger.minLevel] of this [AdbLogger].
 *
 * Note: Use for debugging purposes only
 */
@Suppress("unused")
fun AdbLogger.withMinLevel(minLevel: AdbLogger.Level): AdbLogger {
    return object : AdbLogger() {
        override val minLevel: Level
            get() = minLevel

        override fun log(level: Level, message: String) {
            this@withMinLevel.log(level, message)
        }

        override fun log(level: Level, exception: Throwable?, message: String) {
            this@withMinLevel.log(level, exception, message)
        }
    }
}

/**
 * Creates an [AdbLogger] for the class of this instance.
 */
@Suppress("unused") // "T" is reified
inline fun <reified T : Any> T.adbLogger(loggerFactory: AdbLoggerFactory): AdbLogger {
    return loggerFactory.createLogger(T::class.java)
}

/**
 * Creates an [AdbLogger] for the class of this instance.
 */
inline fun <reified T : Any> T.adbLogger(host: AdbSessionHost): AdbLogger {
    return adbLogger(host.loggerFactory)
}

/**
 * Creates an [AdbLogger] for the class of this instance.
 */
inline fun <reified T : Any> T.adbLogger(session: AdbSession): AdbLogger {
    return adbLogger(session.host)
}

/**
 * Abstract implementation of [AdbLogger] that delegates to another [AdbLogger] instance after
 * allowing derived classes to re-format the message by implementing [formatMessage].
 */
private abstract class AdbLoggerDelegate(private val delegate: AdbLogger) : AdbLogger() {

    abstract fun formatMessage(message: String): String

    override val minLevel: Level
        get() = delegate.minLevel

    override fun log(level: Level, message: String) {
        delegate.log(level, formatMessage(message))
    }

    override fun log(level: Level, exception: Throwable?, message: String) {
        delegate.log(level, exception, formatMessage(message))
    }
}

/**
 * Factory of [AdbLogger] instances. See [AdbLoggerFactoryWithCache] for an implementation that
 * uses a simple in-memory cache to minimize GC allocations of [AdbLogger] instances.
 *
 * @see AdbLoggerFactoryWithCache
 */
interface AdbLoggerFactory {

    /**
     * The "root" logger
     */
    val logger: AdbLogger

    /**
     * Creates a [AdbLogger] specific to the given [cls]
     */
    fun createLogger(cls: Class<*>): AdbLogger

    /**
     * Creates a [AdbLogger] with a given [category]
     */
    fun createLogger(category: String): AdbLogger
}

/**
 * Implementation of [AdbLoggerFactory] that caches instances of [AdbLogger] created by
 * the [createLogger] functions.
 */
abstract class AdbLoggerFactoryWithCache<T> : AdbLoggerFactory where T : AdbLogger {

    /**
     * We cache `class` loggers in memory because
     *
     * * there are only a few dozens created in practice, and
     * * the use of the cache decreases GC allocation pressure, as adblib code tends
     *   to use non-static fields for [AdbLogger] instances.
     */
    private val classLoggerCache = LoggerCache<Class<*>, T> { createClassLogger(it) }

    /**
     * We cache `category` loggers in memory, see [classLoggerCache] for justification.
     */
    private val categoryLoggerCache = LoggerCache<String, T> { createCategoryLogger(it) }

    /**
     * Cache for the root [logger] value
     */
    private var _rootLogger: T? = null

    final override val logger: T
        get() {
            return _rootLogger ?: synchronizedRootLogger()
        }

    final override fun createLogger(cls: Class<*>): T {
        return classLoggerCache.computeIfAbsent(cls)
    }

    final override fun createLogger(category: String): T {
        return categoryLoggerCache.computeIfAbsent(category)
    }

    /**
     * Creates the root [AdbLogger] instance the first time the logger is requested through [logger]
     */
    abstract fun createRootLogger(): T

    /**
     * Creates a [AdbLogger] instance of a given [Class] the first time the
     * logger is requested through [createLogger].
     */
    abstract fun createClassLogger(cls: Class<*>): T

    /**
     * Creates an [AdbLogger] instance of a given [Category][String] the first
     * time the logger is requested through [createLogger].
     */
    abstract fun createCategoryLogger(category: String): T

    private fun synchronizedRootLogger(): T {
        return synchronized(this) {
            _rootLogger ?: createRootLogger().also {
                _rootLogger = it
            }
        }
    }

    private class LoggerCache<TKey : Any, T>(
        /**
         * Note: We use a [Function] lambda (as opposed to a `Kotlin` lambda) to ensure no allocation is
         * performed when calling [ConcurrentHashMap.computeIfAbsent]
         */
        private val mappingFunction: Function<TKey, T>
    ) where T : AdbLogger {

        /**
         * Note: We use a [ConcurrentHashMap] so that we get a lock-free lookup.
         */
        private val loggers = ConcurrentHashMap<TKey, T>()

        fun computeIfAbsent(key: TKey): T {
            return loggers.computeIfAbsent(key, mappingFunction)
        }
    }
}
