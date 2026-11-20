package com.optionslab.app.data

import com.optionslab.app.testing.FakeUpstox
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.testing.TradeFixtures
import com.optionslab.engine.Right
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode

/**
 * 20 Nov 2026 (audit: "a paper price read can wait 10-160 seconds"): a paper price read from the candle feed holds its caller (a
 * paper stop, lock or exit check) at most [Paper.PRICE_READ_WAIT_MS]; a slower read is given up and reads as "no price now", as a
 * failed read did, and the next read works as before.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class PaperPriceReadBoundTest : RobolectricTest() {
    private lateinit var upstox: FakeUpstox
    private lateinit var c: Paper.Contract

    @Before fun up() {
        TradeFixtures.paperSettings()
        upstox = FakeUpstox()
        TradeFixtures.seed(context, upstox)
        c = Paper.contractFor("NIFTY", TradeFixtures.nearExpiry, 24_500.0, Right.PE) ?: throw AssertionError("contract not listed")
    }

    @After fun down() {
        upstox.gate = null
        Paper.priceReadWaitMs = Paper.PRICE_READ_WAIT_MS
        upstox.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    @Test fun theBoundIsAFewSecondsNotMinutes() {
        assertTrue(Paper.PRICE_READ_WAIT_MS in 1_000L..10_000L)
    }

    @Test fun aSlowCandleReadIsNotWaitedForPastTheBoundAndTheNextLookTakesItsAnswer() {
        Paper.priceReadWaitMs = 400L
        // The feed holds this contract's read for 1.5 s (a rate limit's wait, a slow link).
        upstox.gate = { key -> if (key == c.feedKey) Thread.sleep(1_500) }
        val reads0 = upstox.requests.count { it.contains(c.feedKey) || it.contains(c.feedKey.replace("|", "%7C")) }
        val t0 = System.currentTimeMillis()
        val px = runBlocking { Paper.lastPrice(c) }
        val took = System.currentTimeMillis() - t0
        assertNull("no price now", px)
        assertTrue("the caller was held $took ms, not the read's 1.5 s", took < 1_200)
        // A second look while it is still running waits on the same read (never a second one beside it).
        assertNull(runBlocking { Paper.lastPrice(c) })
        // The read finishes in the background; the next look takes its answer with no new read.
        Thread.sleep(1_600)
        upstox.gate = null
        assertEquals(100.0, runBlocking { Paper.lastPrice(c) }!!, 0.5)
        val reads1 = upstox.requests.count { it.contains(c.feedKey) || it.contains(c.feedKey.replace("|", "%7C")) }
        assertEquals("one read for the three looks", 1, reads1 - reads0)
    }

    /** Review: a read stuck past twice the bound never blocks the next look - a fresh read starts beside it. */
    @Test fun aStuckReadDoesNotBlockAFreshOne() {
        Paper.priceReadWaitMs = 300L
        val stuck = java.util.concurrent.CountDownLatch(1)
        var first = true
        upstox.gate = { key -> if (key == c.feedKey && first) { first = false; stuck.await(10, java.util.concurrent.TimeUnit.SECONDS) } }
        try {
            assertNull(runBlocking { Paper.lastPrice(c) })
            Thread.sleep(700)                                       // past twice the bound: the read counts as stuck
            assertEquals(100.0, runBlocking { Paper.lastPrice(c) }!!, 0.5)
        } finally { stuck.countDown() }
    }

    @Test fun aReadWithinTheBoundIsUsedAsBefore() {
        upstox.gate = { key -> if (key == c.feedKey) Thread.sleep(150) }
        val px = runBlocking { Paper.lastPrice(c) }
        assertNotNull(px)
        assertEquals(100.0, px!!, 0.5)
    }
}
