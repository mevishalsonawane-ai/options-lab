package com.optionslab.app.data

import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 4 Nov (Boss's diagnostics: the relay did not answer 10:23-10:55, one relay round trip took 427 s, and the 08:38 close was
 * "main thread in Relay.close, waiting for a lock of Relay"): a failed connect is remembered for a few seconds, so the calls
 * queued behind it fail at once instead of 15 s each; and closing the relay never waits on a connect in progress.
 * Nothing here reaches the network.
 */
class RelayFailFastTest : RobolectricTest() {
    private val why = "Relay: cannot reach 203.0.113.5 on port 22 (is the server running, with the IP attached?)"

    @After fun down() {
        Relay.clock = { System.currentTimeMillis() }
        Relay.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    @Test fun aCallRightAfterAFailedConnectFailsAtOnceWithTheSameReason() {
        Relay.host = "203.0.113.5"
        Relay.enabled = true
        Relay.failedForTest(why)
        val t0 = System.nanoTime()
        try { Relay.proxy(); fail("the relay is on and down: no proxy, and never a direct route") }
        catch (e: IOException) { assertEquals(why, e.message) }
        val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
        assertTrue("failed fast, not after another 15 s connect: $ms ms", ms < 2_000)
        assertTrue(Relay.warm().not())
    }

    @Test fun theFailureIsKeptOnlyForTheWindow() {
        val at = 1_000_000L
        Relay.failedForTest(why, at)
        assertEquals(why, Relay.recentFailure(at))
        assertEquals(why, Relay.recentFailure(at + Relay.FAIL_FAST_MS - 1))
        assertNull("the window is over: the next call tries the server again", Relay.recentFailure(at + Relay.FAIL_FAST_MS))
        assertNull("a clock that went back never holds a failure forever", Relay.recentFailure(at - 1))
    }

    @Test fun closingOrChangingTheRelayForgetsTheFailure() {
        Relay.failedForTest(why)
        Relay.close()
        assertNull(Relay.recentFailure())
        Relay.failedForTest(why)
        Relay.host = "203.0.113.6"                                       // a new server: tried afresh, not refused for the old one
        assertNull(Relay.recentFailure())
        Relay.failedForTest(why)
        Relay.wipe()
        assertNull(Relay.recentFailure())
    }

    @Test fun switchingTheRelayOffNeverWaitsOnAConnectInProgress() {
        Relay.host = "203.0.113.5"
        Relay.enabled = true
        // A connect holds the relay's lock (up to 45 s when the server does not answer): held here for 5 s.
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val connecting = Thread {
            synchronized(Relay) { held.countDown(); release.await(5, TimeUnit.SECONDS) }
        }.apply { isDaemon = true; start() }
        assertTrue(held.await(5, TimeUnit.SECONDS))
        try {
            val t0 = System.nanoTime()
            Relay.close()
            Relay.enabled = false
            val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
            assertTrue("close and the switch returned without the lock: $ms ms", ms < 2_000)
            assertTrue(!Relay.enabled)
            assertTrue(!Relay.connected)
        } finally {
            release.countDown(); connecting.join(5_000)
        }
    }
}
