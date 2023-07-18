/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.adblib.testingutils

import org.hamcrest.BaseMatcher
import org.hamcrest.CoreMatchers.isA
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.hamcrest.core.AnyOf

/**
 * Matches at least one of many [Exception] type.
 *
 * This [BaseMatcher] is intended to be a replacement for an "[anyOf] ([isA] (...), [isA] (...))"
 * expression while avoiding potential type system warnings.
 *
 * For example,
 *
 *    val ee: ExpectedException
 *    ee.expect(anyOf(isA(FooException::class.java), isA(BarException::class.java)))
 *
 *  currently produces the following warning:
 *
 *  > Type argument for a type parameter T can't be inferred because it has incompatible
 *  upper bounds: FooException, BarException (multiple incompatible classes). This will
 *  become an error in Kotlin 1.9
 *
 * This can be replaced with
 *
 *    val ee: ExpectedException
 *    ee.expect(anyExceptionOf(FooException::class.java, BarException::class.java))
 *
 * See [org.hamcrest.core.AnyOf]
 */
class AnyExceptionOfMatcher(vararg classes: Class<*>) : BaseMatcher<Class<*>>() {

    private val anyOf = AnyOf(mapClasses(classes))

    override fun describeTo(description: Description?) {
        anyOf.describeTo(description)
    }

    override fun matches(item: Any?): Boolean {
        return anyOf.matches(item)
    }

    companion object {
        fun anyExceptionOf(vararg classes: Class<*>): AnyExceptionOfMatcher {
            return AnyExceptionOfMatcher(*classes)
        }

        private fun mapClasses(classes: Array<out Class<*>>): Iterable<Matcher<*>> {
            return classes.map { type -> isA(type) }.asIterable()
        }
    }
}
