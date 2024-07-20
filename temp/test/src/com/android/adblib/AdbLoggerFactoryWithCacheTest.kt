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

import org.junit.Assert
import org.junit.Test

class AdbLoggerFactoryWithCacheTest {

    @Test
    fun rootLoggerWorks() {
        val factory = MyAdbLoggerFactory()

        // Act
        val rootLoggerName = factory.logger.name

        // Assert
        Assert.assertEquals("category:foo", rootLoggerName)
    }

    @Test
    fun classLoggersAreCached() {
        // Prepare
        val factory =  MyAdbLoggerFactory()

        // Act
        repeat(10)
        {
            factory.createLogger(Int::class.java)
            factory.createLogger(String::class.java)
            factory.createLogger(MyAdbLogger::class.java)
        }

        // Assert
        Assert.assertEquals(3, factory.instances.size)
        Assert.assertNotNull(factory.instances.firstOrNull { it.name == "class:${Int::class.java}" })
        Assert.assertNotNull(factory.instances.firstOrNull { it.name == "class:${String::class.java}" })
        Assert.assertNotNull(factory.instances.firstOrNull { it.name == "class:${MyAdbLogger::class.java}" })
    }

    @Test
    fun categoryLoggersAreCached() {
        // Prepare
        val factory =  MyAdbLoggerFactory()

        // Act
        repeat(10)
        {
            factory.createLogger("foo")
            factory.createLogger("bar")
            factory.createLogger("blah")
        }

        // Assert
        Assert.assertEquals(3, factory.instances.size)
        Assert.assertNotNull(factory.instances.firstOrNull { it.name == "category:foo" })
        Assert.assertNotNull(factory.instances.firstOrNull { it.name == "category:bar" })
        Assert.assertNotNull(factory.instances.firstOrNull { it.name == "category:blah" })
    }

    class MyAdbLoggerFactory : AdbLoggerFactoryWithCache<MyAdbLogger>() {
        val instances = mutableListOf<MyAdbLogger>()

        override fun createRootLogger(): MyAdbLogger {
            return createCategoryLogger("foo")
        }

        override fun createClassLogger(cls: Class<*>): MyAdbLogger {
            return MyAdbLogger("class:$cls").also {
                instances.add(it)
            }
        }

        override fun createCategoryLogger(category: String): MyAdbLogger {
            return MyAdbLogger("category:$category").also {
                instances.add(it)
            }
        }
    }

    class MyAdbLogger(val name: String) : AdbLogger() {

        override fun log(level: Level, message: String) {
        }

        override fun log(level: Level, exception: Throwable?, message: String) {
        }

        override val minLevel: Level
            get() = Level.VERBOSE
    }
}
