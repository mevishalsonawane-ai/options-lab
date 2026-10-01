package com.optionslab.app.data

import com.optionslab.engine.Upstox
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** IraGoldAlgo's chart candles: which Yahoo request each chart interval makes, folding, parsing and the time window. */
class GoldChartTest {
    @org.junit.Before fun up() = GoldChart.resetForTest()
    @After fun down() { GoldChart.testFetch = null; GoldChart.resetForTest() }

    @Test fun eachIntervalAsksYahooForTheRightCandles() {
        assertEquals(Triple("1m", "5d", 0), GoldChart.plan("1m"))
        assertEquals(Triple("5m", "1mo", 0), GoldChart.plan("5m"))
        assertEquals(Triple("15m", "1mo", 0), GoldChart.plan("15m"))
        assertEquals(Triple("1m", "5d", 3), GoldChart.plan("3m"))
        assertEquals(Triple("5m", "1mo", 10), GoldChart.plan("10m"))
        assertEquals(Triple("60m", "6mo", 0), GoldChart.plan("1h"))
        assertEquals(Triple("60m", "2y", 240), GoldChart.plan("4h"))
        assertEquals(Triple("1d", "5y", 0), GoldChart.plan("1D"))
        assertEquals(Triple("1d", "5y", 0), GoldChart.plan("1d"))
        assertEquals(Triple("1wk", "10y", 0), GoldChart.plan("1w"))
        assertEquals(Triple("1mo", "max", 0), GoldChart.plan("1M"))
        assertNull(GoldChart.plan("soon"))
    }

    @Test fun candlesAreFoldedOnTheClock() {
        val h = 3600L
        val bars = (0 until 8).map { Upstox.Bar(1_790_000_000L - 1_790_000_000L % (4 * h) + it * h, 10.0 + it, 20.0 + it, 5.0 + it, 11.0 + it, 1, 0) }
        val f = GoldChart.fold(bars, 4 * h)
        assertEquals(2, f.size)
        assertEquals(Upstox.Bar(bars[0].epochSecond, 10.0, 23.0, 5.0, 14.0, 4, 0), f[0])
        assertEquals(Upstox.Bar(bars[4].epochSecond, 14.0, 27.0, 9.0, 18.0, 4, 0), f[1])
    }

    @Test fun yahoosJsonBecomesCandlesInTheWindowAskedFor() = runBlocking {
        val asked = mutableListOf<String>()
        GoldChart.testFetch = { url -> asked += url; yahoo(listOf(100L, 400L, 700L)) }
        val all = GoldChart.bars("1h", null, null)
        assertEquals(listOf(100L, 700L), all.map { it.epochSecond })          // the candle with no price is skipped
        assertEquals(2400.0, all[0].open, 1e-9); assertEquals(55L, all[0].volume)
        assertEquals(listOf(700L), GoldChart.bars("1h", 500, 800).map { it.epochSecond })
        assertEquals("one request, kept 15 s", 1, asked.size)
        assertEquals("https://query1.finance.yahoo.com/v8/finance/chart/GC%3DF?interval=60m&range=6mo", asked[0])
    }

    private fun yahoo(ts: List<Long>): JSONObject {
        fun arr(vararg v: Any?) = JSONArray().apply { v.forEach { put(it ?: JSONObject.NULL) } }
        val q = JSONObject().put("open", arr(2400.0, null, 2410.0)).put("high", arr(2405.0, null, 2415.0))
            .put("low", arr(2395.0, null, 2405.0)).put("close", arr(2402.0, null, 2412.0)).put("volume", arr(55, null, 60))
        val r = JSONObject().put("timestamp", JSONArray(ts)).put("indicators", JSONObject().put("quote", JSONArray().put(q)))
        return JSONObject().put("chart", JSONObject().put("result", JSONArray().put(r)))
    }
}
