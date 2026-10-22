package com.optionslab.app.data

import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeUpstox
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.testing.TradeFixtures
import com.optionslab.engine.KiteTicks
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 22 Oct (R100 audit C): the best price a lock or trail reads for a contract Zerodha's stream streams. [Paper.highSince] reads the
 * stream alone, as before (no network). The stream keeps only its last five minutes of prices, so after a longer gap (a restart
 * mid-trade, a stall) the arms also ask [Paper.gapHighSince] for the 1-minute candles' highs: a spike inside the gap still counts,
 * a read that did not come is said ([Paper.GapHigh.read] false: tried again later), and the caller is never held past its wait. The market's clock is pinned
 * ([RobolectricTest]'s MarketClockRule).
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class PaperHighSinceTest : RobolectricTest() {
    private lateinit var upstox: FakeUpstox
    private lateinit var since: LocalDateTime
    private val made = ArrayList<Paper.Contract>()

    /** Each test its own contract (the paper book keeps a contract's candles for a while: no test may see another's). */
    private fun contract(n: Int): Pair<Paper.Contract, Long> {
        val c = Paper.contractFor("BANKNIFTY", Market.today().plusDays(7), 54_000.0 + 100 * n, Right.CE)!!
        val token = 44_000_000L + n
        Paper.tokenForTest(c.symbol, token)
        made += c
        // The candle feed: a spike to 150 at 10:00, long before the stream's last five minutes.
        upstox.minutes["NSE_FO|HS$n"] = listOf(FakeUpstox.Candle(LocalTime.of(10, 0), 110.0, 150.0, 109.0, 112.0),
            FakeUpstox.Candle(LocalTime.of(10, 1), 112.0, 113.0, 111.0, 112.0))
        return c to token
    }

    private fun tick(token: Long, last: Double) =
        KiteStream.feedForTest(listOf(KiteTicks.Tick(token, last, exchangeTime = System.currentTimeMillis() / 1000)))

    @Before fun up() {
        upstox = FakeUpstox()
        TradeFixtures.paperSettings()
        KiteStream.resetForTest()
        val expiry = Market.today().plusDays(7)
        AutomationSupport.contracts(context, (1..5).map { n ->
            Upstox.Contract("BANKNIFTY", expiry, 54_000.0 + 100 * n, Right.CE, 30, "NSE_FO|HS$n", "BANKNIFTY-HS-${54_000 + 100 * n}CE")
        })
        // The start of the market's day: the stream's tick (stamped by the real clock) is after it at any hour CI runs.
        since = Market.today().atStartOfDay()
    }

    @After fun down() {
        made.forEach { Paper.tokenForTest(it.symbol, null) }
        KiteStream.resetForTest()
        upstox.close()
    }

    @Test fun aStreamedContractsBestPriceIsTheStreamsAsBeforeAndReadsNothing() {
        val (c, token) = contract(1)
        tick(token, 120.0)
        assertTrue(Paper.streams(c))
        val before = upstox.requests.size
        assertEquals(120.0, runBlocking { Paper.highSince(c, since) }!!, 1e-9)
        assertEquals("no candle read for a look the stream covers", before, upstox.requests.size)
    }

    @Test fun theGapReadFindsTheHighTheStreamNoLongerHolds() {
        val (c, token) = contract(2)
        tick(token, 120.0)
        val r = runBlocking { Paper.gapHighSince(c, since) }
        assertTrue(r.read)
        assertEquals("the 10:00 spike to 150", 150.0, r.high!!, 1e-9)
        // Only candles that started after the given minute count: none after 10:01 (read, no high).
        val later = runBlocking { Paper.gapHighSince(c, Market.today().atTime(10, 1)) }
        assertTrue(later.read)
        assertNull(later.high)
    }

    @Test fun aGapReadWithTheFeedDownFailsSoTheCallerTriesAgain() {
        val (c, token) = contract(3)
        tick(token, 120.0)
        upstox.down = true
        assertFalse(runBlocking { Paper.gapHighSince(c, since, waitMs = 20_000) }.read)
        upstox.down = false
        assertEquals(150.0, runBlocking { Paper.gapHighSince(c, since, waitMs = 20_000) }.high!!, 1e-9)
    }

    @Test fun aSlowGapReadNeverHoldsTheCallerAndItsCandlesAreKeptForTheNextTry() {
        val (c, token) = contract(4)
        tick(token, 120.0)
        val t0 = System.nanoTime()
        assertFalse("no wait at all: not read in time", runBlocking { Paper.gapHighSince(c, since, waitMs = 0) }.read)
        assertTrue("returned at once", System.nanoTime() - t0 < 2_000_000_000L)
        // The read went on in the background, once (tries while it runs wait on it again), and kept its candles: a later try
        // answers from them without a new read.
        val end = System.currentTimeMillis() + 15_000
        var r = runBlocking { Paper.gapHighSince(c, since, waitMs = 0) }
        while (!r.read && System.currentTimeMillis() < end) { Thread.sleep(20); r = runBlocking { Paper.gapHighSince(c, since, waitMs = 0) } }
        assertEquals(150.0, r.high!!, 1e-9)
        assertEquals("one read in all", 1, upstox.requests.count { it.contains("HS4") })
    }

    @Test fun aContractWithoutTheStreamDoesNotStream() {
        val c = Paper.contractFor("BANKNIFTY", Market.today().plusDays(7), 54_500.0, Right.CE)!!
        assertFalse(Paper.streams(c))
    }
}
