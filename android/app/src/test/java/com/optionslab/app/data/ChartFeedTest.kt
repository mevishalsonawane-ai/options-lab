package com.optionslab.app.data

import com.optionslab.app.testing.Background
import com.optionslab.app.testing.Background.WED
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.testing.UpstoxStub
import com.optionslab.app.testing.UpstoxStub.Companion.isHistory
import com.optionslab.app.testing.UpstoxStub.Companion.isIntraday
import com.optionslab.app.testing.UpstoxStub.Companion.status
import com.optionslab.engine.IST
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode
import java.io.IOException
import java.time.LocalTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The chart's candle feed: finished sessions are remembered for the day (least recently used
 * dropped first, everything dropped when the day turns), the market watch has its own quick lane,
 * and today's read failing in session is an error rather than a chart that silently stops at yesterday.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class ChartFeedTest : RobolectricTest() {
    private lateinit var upstox: UpstoxStub
    private val tue = WED.minusDays(1)

    @Before fun up() {
        upstox = UpstoxStub()
        Background.calendar(WED)
        Background.clearChartCache()
        Background.at(WED, 18, 0)
        upstox.reply = { p ->
            when {
                isIntraday(p) -> UpstoxStub.candles(UpstoxStub.minutes(Market.today(), LocalTime.of(9, 15), 5, 24_900.0))
                isHistory(p) -> UpstoxStub.candles(UpstoxStub.minutes(tue, LocalTime.of(15, 20), 10, 24_800.0))
                else -> status(404)
            }
        }
    }

    @After fun down() {
        upstox.close(); Background.reset()
        assertEquals("a test reached the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun history(n: Int) = upstox.count { isHistory(it) && it.contains("/minutes/$n/") }

    @Test fun finishedSessionsAreFetchedOnceADay() = runBlocking {
        val a = ChartFeed.bars("NIFTY", "1m", null, null)
        assertEquals("Tuesday's 10 and today's 5", 15, a.size)
        assertEquals(a.sortedBy { it.epochSecond }, a)
        val h = history(1)
        assertTrue(h >= 1)
        val b = ChartFeed.bars("NIFTY", "1m", null, null)
        assertEquals(a.map { it.epochSecond }, b.map { it.epochSecond })
        assertEquals("history came from memory", h, history(1))
        assertEquals("today's session is always fresh", 2, upstox.count(::isIntraday))
    }

    @Test fun theLeastRecentlyUsedChartIsDroppedFirst() = runBlocking {
        for (n in 1..12) ChartFeed.bars("NIFTY", "${n}m", null, null)
        val fetched = (1..13).associateWith { history(it) }
        ChartFeed.bars("NIFTY", "1m", null, null)          // 1m is now the most recent
        assertEquals(fetched[1], history(1))
        ChartFeed.bars("NIFTY", "13m", null, null)         // a 13th chart: 2m, the eldest, goes
        ChartFeed.bars("NIFTY", "1m", null, null)
        assertEquals("1m was kept", fetched[1], history(1))
        ChartFeed.bars("NIFTY", "3m", null, null)
        assertEquals("3m was kept", fetched[3], history(3))
        ChartFeed.bars("NIFTY", "2m", null, null)
        assertTrue("2m was dropped and fetched again", history(2) > fetched[2]!!)
    }

    @Test fun yesterdaysMemoryIsDroppedWhenTheDayTurns() = runBlocking {
        ChartFeed.bars("NIFTY", "1m", null, null)
        val h = history(1)
        Background.at(WED.plusDays(1), 8, 0)
        ChartFeed.bars("NIFTY", "1m", null, null)
        assertTrue("a new day asks for history again", history(1) > h)
    }

    @Test fun weekAndMonthBarsAreNeverRemembered() = runBlocking {
        ChartFeed.bars("NIFTY", "1w", null, null)
        ChartFeed.bars("NIFTY", "1w", null, null)
        assertEquals(2, upstox.count { isHistory(it) && it.contains("/weeks/1/") })
        assertEquals("no intraday endpoint for weeks", 0, upstox.count(::isIntraday))
    }

    @Test fun inSessionAFailedTodayReadIsAnError() = runBlocking {
        Background.at(WED, 11, 0)
        val ok = upstox.reply
        upstox.reply = { p -> if (isIntraday(p)) status(500) else ok(p) }
        try { ChartFeed.bars("NIFTY", "1m", null, null); fail("history passed off as the whole chart") } catch (e: Net.HttpFailure) { assertEquals(500, e.code) }
        Background.at(WED, 18, 0)
        val after = ChartFeed.bars("NIFTY", "1m", null, null)
        assertEquals("after the close the finished sessions show", 10, after.size)
        assertTrue(after.all { it.istDate == tue })
    }

    @Test fun oneFailedChunkCostsOnlyItsDaysAndEveryChunkFailingFails() = runBlocking {
        val ok = upstox.reply
        val from = WED.minusDays(70).atStartOfDay(IST).toEpochSecond()
        upstox.reply = { p -> if (isHistory(p) && p.endsWith("/${WED.minusDays(70)}")) status(404) else ok(p) }
        val bars = ChartFeed.bars("NIFTY", "1m", from, null)
        assertEquals(15, bars.size)
        val first = history(1)
        assertTrue("70 days of minutes are three chunks", first >= 3)
        ChartFeed.bars("NIFTY", "1m", from, null)
        assertTrue("a load with a gap is not remembered", history(1) > first)

        upstox.reply = { p -> if (isHistory(p)) status(404) else ok(p) }
        Background.clearChartCache()
        try { ChartFeed.bars("NIFTY", "1m", from, null); fail() } catch (e: Net.HttpFailure) { assertEquals(404, e.code) }
    }

    @Test fun theQuickLaneTriesOnceAndNeverQueuesBehindAChartLoad() = runBlocking {
        val ok = upstox.reply
        val release = CountDownLatch(1)
        upstox.reply = { p ->
            if (isHistory(p) && p.contains("/minutes/1/")) { release.await(30, TimeUnit.SECONDS); ok(p) } else ok(p)
        }
        // A two-year minute history: dozens of chunks, four at a time through the shared gate, all held.
        val longLoad = async(Dispatchers.IO) { ChartFeed.bars("NIFTY", "1m", WED.minusDays(700).atStartOfDay(IST).toEpochSecond(), null) }
        Background.await("the shared gate to fill") { history(1) >= 4 }
        Thread.sleep(300)
        assertEquals("only four chunks in flight at once", 4, history(1))
        val quick = withTimeout(15_000) { ChartFeed.bars("NIFTY", "5m", null, null, quick = true) }
        assertEquals(15, quick.size)
        release.countDown()
        assertTrue(longLoad.await().isNotEmpty())

        // One attempt per request on the quick lane.
        upstox.reply = { status(503) }
        Background.clearChartCache()
        upstox.paths.clear()
        try { ChartFeed.bars("NIFTY", "5m", null, null, quick = true); fail() } catch (e: Net.HttpFailure) { assertEquals(503, e.code) }
        assertEquals(1, history(5))
        assertEquals(1, upstox.count(::isIntraday))
    }

    @Test fun symbolsIntervalsAndSearch() = runBlocking {
        val next = WED.plusDays(6)
        Background.contracts(context, WED, listOf(
            Upstox.Contract("NIFTY", next, 24_500.0, Right.PE, 75, "NSE_FO|1", "NIFTY25O2124500PE"),
            Upstox.Contract("NIFTY", next, 24_600.0, Right.CE, 75, "NSE_FO|2", "NIFTY25O2124600CE"),
            Upstox.Contract("NIFTY", WED.minusDays(7), 24_500.0, Right.PE, 75, "NSE_FO|3", "NIFTY25O0824500PE"),
        ))
        assertEquals("NSE_FO|1", ChartFeed.instrumentKey("nifty25o2124500pe"))
        assertEquals(Upstox.INDEX_KEYS.getValue("BANKNIFTY"), ChartFeed.instrumentKey("banknifty"))
        assertEquals("BSE_INDEX|SENSEX", ChartFeed.instrumentKey("SENSEX"))
        assertTrue(ChartFeed.isIndex("finnifty"))
        try { ChartFeed.instrumentKey("NIFTY25O0824500PE"); fail() } catch (e: IOException) { assertTrue(e.message!!.contains("expired")) }
        try { ChartFeed.instrumentKey("RELIANCE"); fail() } catch (e: IOException) { assertTrue(e.message!!.contains("not an index")) }
        try { ChartFeed.bars("NIFTY", "7x", null, null); fail() } catch (e: IOException) { assertTrue(e.message!!.contains("unknown interval")) }

        assertEquals(6, ChartFeed.search("").size)
        assertEquals(listOf("NIFTY25O2124500PE"), ChartFeed.search("nifty 24500").map { it.symbol }.filter { it.startsWith("NIFTY25") })
        assertEquals("SENSEX", ChartFeed.search("sensex").single().symbol)
        assertEquals("BSE", ChartFeed.search("sensex").single().exchange)

        val opt = ChartFeed.bars("NIFTY25O2124500PE", "1m", null, null)
        assertEquals(15, opt.size)
        assertTrue(upstox.paths.any { it.contains("/NSE_FO|1/minutes/1/") })
        assertEquals("nothing downloaded", 0, upstox.count { it.contains("complete.json.gz") })
    }
}
