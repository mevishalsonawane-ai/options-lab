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
import kotlinx.coroutines.async
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

    private var server: java.net.ServerSocket? = null

    @After fun down() {
        Relay.clock = { System.currentTimeMillis() }
        Relay.sshPort = 22
        Relay.close()
        runCatching { server?.close() }
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    @Test fun aCallRightAfterAFailedConnectFailsAtOnceWithTheSameReason() {
        Relay.host = "203.0.113.5"
        Relay.enabled = true
        Relay.clock = { 1_000_000L }                                     // pinned: the window cannot run out mid-test
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

    /**
     * A local stand-in "server" on loopback ([hold]: keeps each connection open without a word, else closes it at once),
     * counting the connects that reach it. JSch fails on it either way (no SSH banner): a connect that really fails.
     */
    private fun standIn(hold: Boolean, accepted: java.util.concurrent.atomic.AtomicInteger, kept: MutableList<java.net.Socket>): Int {
        val ss = java.net.ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())
        server = ss
        Thread {
            while (!ss.isClosed) {
                val c = runCatching { ss.accept() }.getOrNull() ?: break
                if (hold) kept += c else runCatching { c.close() }
                accepted.incrementAndGet()                               // after it is kept: the test closes what it counted
            }
        }.apply { isDaemon = true; start() }
        return ss.localPort
    }

    private fun relayTo(port: Int) {
        Relay.sshPort = port
        Relay.host = "127.0.0.1"
        Relay.newKey()
        Relay.enabled = true
    }

    @Test fun aRealFailedConnectIsTriedOnceAndTheCallsBehindItFailAtOnce() {
        val accepted = java.util.concurrent.atomic.AtomicInteger()
        relayTo(standIn(hold = false, accepted = accepted, kept = mutableListOf()))
        val first = try { Relay.proxy(); null } catch (e: IOException) { e.message }
        assertTrue("the connect failed with a reason: $first", first != null && first.startsWith("Relay:"))
        assertEquals("one connect reached the server", 1, accepted.get())
        assertEquals("the failure is remembered", first, Relay.recentFailure())
        val t0 = System.nanoTime()
        repeat(5) {
            try { Relay.proxy(); fail("never a route while the relay is down") } catch (e: IOException) { assertEquals(first, e.message) }
        }
        assertTrue("the five calls behind it failed at once", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 2_000)
        assertEquals("and none of them tried the server again", 1, accepted.get())
        // Once the window is over, the next call tries the server again.
        val later = System.currentTimeMillis() + Relay.FAIL_FAST_MS
        Relay.clock = { later }
        try { Relay.proxy(); fail("still down") } catch (e: IOException) { assertEquals(first, e.message) }
        assertEquals(2, accepted.get())
    }

    @Test fun aConnectThatFailsAfterTheRelayWasClosedIsNotRemembered() {
        val accepted = java.util.concurrent.atomic.AtomicInteger()
        val kept = java.util.Collections.synchronizedList(mutableListOf<java.net.Socket>())
        relayTo(standIn(hold = true, accepted = accepted, kept = kept))
        Relay.clock = { 1_000_000L }                                     // pinned: only the close can explain no failure kept
        val outcome = java.util.concurrent.atomic.AtomicReference<String?>()
        val caller = Thread { outcome.set(try { Relay.proxy(); "connected" } catch (e: IOException) { e.message }) }.apply { isDaemon = true; start() }
        val end = System.currentTimeMillis() + 10_000
        while (accepted.get() == 0 && System.currentTimeMillis() < end) Thread.sleep(10)
        assertEquals("the connect is waiting on the server", 1, accepted.get())
        val t0 = System.nanoTime()
        Relay.close()                                                    // while that connect holds the relay's lock
        assertTrue("close did not wait for the connect", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 2_000)
        synchronized(kept) { kept.forEach { runCatching { it.close() } } }   // the server goes away: the connect fails
        caller.join(10_000)
        assertTrue("the connect failed: ${outcome.get()}", outcome.get()?.startsWith("Relay:") == true)
        assertNull("a failure from before the close is not held against the next call", Relay.recentFailure())
        assertTrue(!Relay.connected)
    }

    /** 6 Nov: the switch's saves land one by one in the order asked, on the relay's own queue (never the card's). */
    @Test fun theSwitchSavesLandInTheOrderAsked() {
        Relay.host = "203.0.113.5"
        val asked = (1..40).map { it % 3 != 0 }                               // a mixed run of on and off
        val landed = java.util.Collections.synchronizedList(mutableListOf<Boolean>())
        Relay.savedForTest = { landed += it }
        try {
            kotlinx.coroutines.runBlocking {
                asked.map { on -> kotlinx.coroutines.GlobalScope.async(kotlinx.coroutines.Dispatchers.Unconfined) { Relay.saveSwitch(on) } }.forEach { assertNull(it.await()) }
            }
        } finally { Relay.savedForTest = null }
        assertEquals("every save, in tap order", asked, landed.toList())
        assertEquals("the last tap wins", asked.last(), Relay.enabled)
    }

    /**
     * 6 Nov (review): switching the relay off while Connect & test waits on a server that does not answer is saved at once (not
     * after the test's 15-45 s), the test then fails, and the switch stays off.
     */
    @Test fun switchingOffDuringConnectAndTestLandsAtOnce() {
        val accepted = java.util.concurrent.atomic.AtomicInteger()
        val kept = java.util.Collections.synchronizedList(mutableListOf<java.net.Socket>())
        val port = standIn(hold = true, accepted = accepted, kept = kept)
        Relay.sshPort = port
        Relay.newKey()
        kotlinx.coroutines.runBlocking {
            val test = kotlinx.coroutines.GlobalScope.async(kotlinx.coroutines.Dispatchers.IO) { Relay.connectAndTest("127.0.0.1") }
            val end = System.currentTimeMillis() + 10_000
            while (accepted.get() == 0 && System.currentTimeMillis() < end) Thread.sleep(10)
            assertEquals("the test is waiting on the server", 1, accepted.get())
            val t0 = System.nanoTime()
            assertNull(kotlinx.coroutines.withTimeout(5_000) { Relay.saveSwitch(false) })
            assertTrue("off was saved without waiting for the test", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 2_000)
            assertTrue(!Relay.enabled)
            synchronized(kept) { kept.forEach { runCatching { it.close() } } }   // the server goes away
            val r = kotlinx.coroutines.withTimeout(30_000) { test.await() }
            assertTrue("the test failed: $r", r.isFailure)
        }
        assertTrue("still off", !Relay.enabled)
        assertTrue(!Relay.connected)
    }

    /** 6 Nov (review): a failed Connect & test leaves Boss's later switch tap alone (it only undoes its own "on"). */
    @Test fun aFailedConnectAndTestLeavesALaterTapAlone() {
        val accepted = java.util.concurrent.atomic.AtomicInteger()
        val kept = java.util.Collections.synchronizedList(mutableListOf<java.net.Socket>())
        Relay.sshPort = standIn(hold = true, accepted = accepted, kept = kept)
        Relay.newKey()
        kotlinx.coroutines.runBlocking {
            val test = kotlinx.coroutines.GlobalScope.async(kotlinx.coroutines.Dispatchers.IO) { Relay.connectAndTest("127.0.0.1") }
            val end = System.currentTimeMillis() + 10_000
            while (accepted.get() == 0 && System.currentTimeMillis() < end) Thread.sleep(10)
            assertNull(Relay.saveSwitch(false))
            assertNull(Relay.saveSwitch(true))                                // off, then on again, while the test runs
            synchronized(kept) { kept.forEach { runCatching { it.close() } } }
            assertTrue(kotlinx.coroutines.withTimeout(30_000) { test.await() }.isFailure)
        }
        assertTrue("Boss's last tap (on) stands", Relay.enabled)
    }

    /** 6 Nov: Connect & test against a server that does not answer leaves the relay OFF (never on, pointing at a dead server). */
    @Test fun aFailedConnectAndTestLeavesTheRelayOff() {
        val accepted = java.util.concurrent.atomic.AtomicInteger()
        val port = standIn(hold = false, accepted = accepted, kept = mutableListOf())
        Relay.sshPort = port
        Relay.newKey()
        val r = kotlinx.coroutines.runBlocking { Relay.connectAndTest("127.0.0.1") }
        assertTrue("it failed: $r", r.isFailure)
        assertEquals("127.0.0.1", Relay.host)
        assertTrue("switched back off", !Relay.enabled)
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
