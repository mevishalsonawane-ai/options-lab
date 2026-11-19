package com.optionslab.app.testing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Proxy
import java.net.URI

/** 20 Nov 2026 (minor fixes, item 6): the guard blocks a host, records it, and [NetworkGuard.reset] forgets both lists. */
class NetworkGuardTest {
    @Test fun aBlockedHostIsRecordedAndTheResetForgetsIt() {
        try {
            assertEquals(listOf(Proxy.NO_PROXY), NetworkGuard.select(URI("http://127.0.0.1:8080/x")))
            val sent = NetworkGuard.select(URI("https://blocked.invalid/x"))
            assertEquals(Proxy.Type.HTTP, sent.single().type())
            assertTrue("blocked.invalid" in NetworkGuard.blocked)
            assertTrue(NetworkGuard.traces.any { it.contains("blocked.invalid") })
        } finally {
            NetworkGuard.reset()
        }
        assertTrue(NetworkGuard.blocked.isEmpty())
        assertTrue(NetworkGuard.traces.isEmpty())
    }
}
