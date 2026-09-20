package dev.agenticscheduler.server.sync

import kotlin.test.Test
import kotlin.test.assertFailsWith

class ServerConfigTest {
    @Test
    fun `non-loopback binding requires explicit TLS termination`() {
        assertFailsWith<IllegalArgumentException> {
            SyncServerConfig("jdbc:test", "user", "password", bindHost = "0.0.0.0")
        }
        SyncServerConfig("jdbc:test", "user", "password", bindHost = "0.0.0.0", tlsTerminated = true)
    }
}
