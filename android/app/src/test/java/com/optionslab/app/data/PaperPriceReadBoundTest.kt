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

    @Test fun aSlowCandleReadIsGivenUpAtTheBoundAndTheNextReadWorks() {
        Paper.priceReadWaitMs = 400L
        // The feed holds this contract's read for 5 s (a rate limit's wait, a stalled connection).
        upstox.gate = { key -> if (key == c.feedKey) Thread.sleep(5_000) }
        val t0 = System.currentTimeMillis()
        val px = runBlocking { Paper.lastPrice(c) }
        val took = System.currentTimeMillis() - t0
        assertNull("no price now", px)
        assertTrue("the caller was held $took ms, not the read's 5 s", took < 3_000)
        // The feed answers at once again: the price is read as before.
        upstox.gate = null
        assertNotNull(runBlocking { Paper.lastPrice(c) })
    }

    @Test fun aReadWithinTheBoundIsUsedAsBefore() {
        upstox.gate = { key -> if (key == c.feedKey) Thread.sleep(150) }
        val px = runBlocking { Paper.lastPrice(c) }
        assertNotNull(px)
        assertEquals(100.0, px!!, 0.5)
    }
}
