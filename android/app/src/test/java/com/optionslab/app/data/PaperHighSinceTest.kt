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
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 22 Oct (R100 audit C): the best price a lock or trail reads ([Paper.highSince]) for a contract Zerodha's stream streams. The
 * stream keeps only its last five minutes of prices, so after a longer gap (a restart mid-trade, a stall) the look asks for the
 * 1-minute candles' highs too ([com.optionslab.engine.orb.ProfitLock.needsCandles]): a spike inside the gap still counts. A look
 * the stream covers reads nothing from the network, as before. The market's clock is pinned ([RobolectricTest]'s MarketClockRule).
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
        AutomationSupport.contracts(context, (1..3).map { n ->
            Upstox.Contract("BANKNIFTY", expiry, 54_000.0 + 100 * n, Right.CE, 30, "NSE_FO|HS$n", "BANKNIFTY-HS-${54_000 + 100 * n}CE")
        })
        since = Market.today().atTime(9, 30)
    }

    @After fun down() {
        made.forEach { Paper.tokenForTest(it.symbol, null) }
        KiteStream.resetForTest()
        upstox.close()
    }

    @Test fun aLookTheStreamCoversReadsOnlyTheStream() {
        val (c, token) = contract(1)
        tick(token, 120.0)
        val before = upstox.requests.size
        assertEquals(120.0, runBlocking { Paper.highSince(c, since) }!!, 1e-9)
        assertEquals("no candle read for a look the stream covers", before, upstox.requests.size)
    }

    @Test fun afterAGapTheCandlesHighCountsToo() {
        val (c, token) = contract(2)
        tick(token, 120.0)
        assertEquals("the 10:00 spike to 150, which the stream no longer holds", 150.0,
            runBlocking { Paper.highSince(c, since, candles = true) }!!, 1e-9)
    }

    @Test fun afterAGapWithTheCandleFeedDownTheStreamStillAnswers() {
        val (c, token) = contract(3)
        tick(token, 120.0)
        upstox.down = true
        assertEquals(120.0, runBlocking { Paper.highSince(c, since, candles = true) }!!, 1e-9)
    }
}
