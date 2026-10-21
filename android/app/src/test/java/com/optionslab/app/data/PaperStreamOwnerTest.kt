package com.optionslab.app.data

import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.FakeUpstox
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.testing.TradeFixtures
import com.optionslab.engine.Right
import com.optionslab.engine.sandbox.Quote
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode

/**
 * 21 Oct, enhancement round 3 (idea 3): the paper book's open positions and working orders are their own owner on Zerodha's
 * stream ([Paper.STREAM_OWNER]) for as long as they are open, not kept by three-minute price touches; let go when they close.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class PaperStreamOwnerTest : RobolectricTest() {
    private lateinit var kite: FakeKite
    private lateinit var upstox: FakeUpstox
    private val token = 31_000_002L
    private val sym = "NIFTYTEST24600PE"
    private lateinit var c: Paper.Contract

    @Before fun up() {
        TradeFixtures.paperSettings()
        upstox = FakeUpstox()
        TradeFixtures.seed(context, upstox)
        kite = FakeKite()
        kite.keepAlive = true
        kite.login(live = false)
        kite.instruments += FakeKite.Ins(token, sym, "NIFTY", TradeFixtures.nearExpiry, 24_600.0, "PE", 75)
        kite.quote("NFO:$sym", 80.0, 79.95, 80.05)
        runBlocking { Broker.instruments() }
        c = Paper.contractFor("NIFTY", TradeFixtures.nearExpiry, 24_600.0, Right.PE) ?: throw AssertionError("contract not listed")
        Paper.testSkipFeedChecks = true
        KiteStream.resetForTest()
    }

    @After fun down() {
        Paper.testSkipFeedChecks = false
        KiteStream.resetForTest()
        kite.close(); upstox.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun owned() = KiteStream.wantsForTest(Paper.STREAM_OWNER)
    private fun place(side: String, type: String = "MARKET", price: Double? = null) =
        runBlocking { Paper.place(c, side, 1, type, "NRML", price, null, Quote(80.0)) }.also { assertTrue(it.message, it.ok) }

    @Test fun anOpenPaperPositionIsFollowedUntilItCloses() {
        place("BUY")
        assertEquals("followed while open, not by a price touch", setOf(token), owned())
        place("SELL")
        assertTrue("let go once closed", owned().isEmpty())
    }

    @Test fun aWorkingPaperOrderIsFollowedTooAndLetGoWhenCancelled() {
        val r = place("BUY", type = "LIMIT", price = 10.0)                 // far below the price: it rests
        assertEquals(setOf(token), owned())
        runBlocking { Paper.cancel(r.orderId!!, "test") }
        assertTrue(owned().isEmpty())
    }

    @Test fun afterARestartTheOpenPositionIsFollowedAsTheBookLoads() {
        place("BUY")
        Paper.forgetForTest()                                               // a new process: the book not in memory
        assertTrue(owned().isEmpty())
        Paper.state                                                         // the book read back
        assertEquals(setOf(token), owned())
        // And the fast path's own look follows it too (after a restart, the first look that finds its token).
        assertEquals(setOf(token), Paper.streamCover().tokens)
        assertEquals(setOf(token), owned())
        place("SELL")
        assertTrue(owned().isEmpty())
    }

    @Test fun aWipeLetsGoOfEverything() {
        place("BUY")
        Paper.wipe()
        assertTrue(owned().isEmpty())
    }
}
