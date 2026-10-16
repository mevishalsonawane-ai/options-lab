package com.optionslab.app.data

import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.FakeUpstox
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.testing.TradeFixtures
import com.optionslab.engine.KiteTicks
import com.optionslab.engine.Right
import com.optionslab.engine.sandbox.Quote
import com.optionslab.engine.sandbox.SandboxEvent
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode

/**
 * 16 Oct (Boss's diagnostics: "Prices were up to 3.6 s old when used" for the passed arms' paper entries): a paper fill never
 * uses a quote older than 2 s when a newer one can be had - a stream tick that came in within 2 s, else Zerodha's own quote
 * (waited 2 s at most), and only then the caller's quote.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class PaperFreshFillTest : RobolectricTest() {
    private lateinit var kite: FakeKite
    private lateinit var upstox: FakeUpstox
    private val token = 31_000_001L
    private val sym = "NIFTYTEST24500PE"
    private lateinit var c: Paper.Contract

    @Before fun up() {
        TradeFixtures.paperSettings()
        upstox = FakeUpstox()
        TradeFixtures.seed(context, upstox)
        kite = FakeKite()
        kite.keepAlive = true
        kite.login(live = false)
        kite.instruments += FakeKite.Ins(token, sym, "NIFTY", TradeFixtures.nearExpiry, 24_500.0, "PE", 75)
        runBlocking { Broker.instruments() }
        c = Paper.contractFor("NIFTY", TradeFixtures.nearExpiry, 24_500.0, Right.PE) ?: throw AssertionError("contract not listed")
        Paper.testSkipFeedChecks = true
        KiteStream.resetForTest()
    }

    @After fun down() {
        Paper.testSkipFeedChecks = false
        KiteStream.resetForTest()
        kite.close(); upstox.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun tick(last: Double, agoMs: Long) = KiteStream.feedForTest(listOf(KiteTicks.Tick(token, last, bid = last - 0.05, ask = last + 0.05,
        exchangeTime = (System.currentTimeMillis() - agoMs) / 1000)), System.currentTimeMillis() - agoMs)

    private fun buyAt(known: Quote): Double {
        val r = runBlocking { Paper.place(c, "BUY", 1, "MARKET", "NRML", null, null, known) }
        assertTrue(r.message, r.ok)
        val f = r.events.filterIsInstance<SandboxEvent.Fill>().first()
        runBlocking { Paper.place(c, "SELL", 1, "MARKET", "NRML", null, null, Quote(f.price)) }
        return f.price
    }

    @Test fun aFreshStreamTickIsUsedOverTheCallersOlderQuote() {
        tick(77.0, agoMs = 300)
        val px = buyAt(Quote(50.0))
        assertTrue("filled at $px", px in 76.5..78.5)
    }

    @Test fun aTickOlderThanTwoSecondsIsReadAgainFromZerodha() {
        tick(90.0, agoMs = 3_600)
        kite.quote("NFO:$sym", 66.0, 65.95, 66.05)
        val px = buyAt(Quote(90.0))
        assertTrue("filled at $px (Zerodha's quote 66, the 3.6 s old tick 90)", px in 65.5..67.5)
        assertTrue(kite.requests.any { it.path == "/quote" })
    }

    @Test fun withNoNewerPriceToHaveTheCallersQuoteIsUsedAsBefore() {
        // Zerodha knows no quote for it (and no tick): the caller's quote, as before.
        val px = buyAt(Quote(55.0, bid = 54.95, ask = 55.05))
        assertTrue("filled at $px", px in 54.5..56.5)
    }
}
