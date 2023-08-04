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
package com.android.adblib

/**
 * This annotation indicates that the class/interface it is applied to is thread safe.
 *
 * Note: This annotation is similar to the
 * [ThreadSafe Error Prone annotation](https://errorprone.info/api/latest/com/google/errorprone/annotations/ThreadSafe.html)
 * and the [javax.annotation.concurrent.ThreadSafe JSR-305 annotation](https://jcp.org/en/jsr/detail?id=305),
 * but specific to `adblib` for documentation purposes.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class IsThreadSafe
