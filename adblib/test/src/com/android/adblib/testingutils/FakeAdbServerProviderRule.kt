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

import com.android.adblib.AdbSession
import com.android.adblib.SOCKET_CONNECT_TIMEOUT_MS
import org.junit.rules.ExternalResource
import java.time.Duration

/** Manages the lifecycle of a FakeAdbServerProvider */
open class FakeAdbServerProviderRule(
    configure: (FakeAdbServerProvider.() -> FakeAdbServerProvider)? = null
) : ExternalResource() {

    private val configure: FakeAdbServerProvider.() -> FakeAdbServerProvider = configure ?: {
        installDefaultCommandHandlers()
    }

    lateinit var fakeAdb: FakeAdbServerProvider
        private set

    lateinit var host: TestingAdbSessionHost
        private set

    lateinit var adbSession: AdbSession
        private set

    public override fun before() {
        fakeAdb = FakeAdbServerProvider().configure().build().start()
        host = TestingAdbSessionHost()
        adbSession = AdbSession.create(
            host,
            createChannelProvider(),
            Duration.ofMillis(SOCKET_CONNECT_TIMEOUT_MS),
        )
    }

    override fun after() {
        adbSession.close()
        host.close()
        fakeAdb.close()
    }

    fun createChannelProvider(): FakeAdbServerProvider.TestingChannelProvider {
        return fakeAdb.createChannelProvider(host)
    }
}
