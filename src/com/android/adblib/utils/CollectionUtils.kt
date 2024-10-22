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

import java.util.Collections

/**
 * Returns a [Map] that can't be modified from both Kotlin and Java consumers,
 * even through casting to mutable interfaces or calling mutating methods.
 *
 * Note: It is harmless to call this multiple times, i.e.
 * `toImmutable(toImmutable(map)) == toImmutable(map)`
 */
fun <K, V> Map<K, V>.toImmutableMap(): Map<K, V> {
    return if (this is ImmutableMap) {
        this
    } else {
        ImmutableMap(Collections.unmodifiableMap(this))
    }
}

private class ImmutableMap<K, V>(val map: Map<K, V>) : Map<K, V> by map

/**
 * Returns a [List] that can't be modified from both Kotlin and Java consumers,
 * even through casting to mutable interfaces or calling mutating methods.
 *
 * Note: It is harmless to call this multiple times, i.e.
 * `toImmutableList(toImmutableList(list)) == toImmutableList(list)`
 */
fun <T> Iterable<T>.toImmutableList(): List<T> {
    return when (this) {
        is ImmutableList -> this
        is List -> ImmutableList(this)
        else -> ImmutableList(this.toList())
    }
}

private class ImmutableList<T>(val list: List<T>) : List<T> by list

/**
 * Returns a [Set] that can't be modified from both Kotlin and Java consumers,
 * even through casting to mutable interfaces or calling mutating methods.
 *
 * Note: It is harmless to call this multiple times, i.e.
 * `toImmutableSet(toImmutableSet(list)) == toImmutableSet(list)`
 */
fun <T> Iterable<T>.toImmutableSet(): Set<T> {
    return when (this) {
        is ImmutableSet -> this
        is Set -> ImmutableSet(this)
        else -> ImmutableSet(this.toSet())
    }
}

private class ImmutableSet<T>(val set: Set<T>) : Set<T> by set
