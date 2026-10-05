package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StructureTest {
    private val today = LocalDate.of(2026, 10, 5)   // a Monday
    private val prior = LocalDate.of(2026, 10, 2)

    /** [minutes] 1-minute candles from 09:15 today along [path] (minute -> close), with the prior session's close [prevClose]. */
    private fun day(minutes: Int, prevClose: Double = 23_950.0, path: (Int) -> Double): List<Candle> {
        val out = ArrayList<Candle>()
        out += Candle(prior.atTime(15, 29), prevClose, prevClose + 1, prevClose - 1, prevClose)
        var px = path(0)
        for (i in 0 until minutes) {
            val o = px; px = path(i + 1)
            out += Candle(today.atTime(9, 15).plusMinutes(i.toLong()), o, maxOf(o, px) + 0.5, minOf(o, px) - 0.5, px)
        }
        return out
    }

    /** A rising day in waves: higher highs and higher lows. */
    private val up = day(270) { i -> 24_000.0 + i * 0.5 + 30 * sin(2 * PI * i / 90) }
    /** A falling day in waves. */
    private val down = day(270, prevClose = 24_100.0) { i -> 24_000.0 - i * 0.5 - 30 * sin(2 * PI * i / 90) }
    /** A day that swings round its open. */
    private val flat = day(240) { i -> 24_000.0 + 40 * sin(2 * PI * i / 60) }

    // Words that would make it advice or a forecast.
    private val ADVICE = Regex("(?i)\\b(you should|i recommend|i suggest|buy|sell|go long|go short|will (rise|fall|go|break)|likely|expect|target)\\b")

    @Test fun theQuestions() {
        assertEquals(Structure.Ask.ALL, Structure.asked("What's the structure today?"))
        assertEquals(Structure.Ask.ALL, Structure.asked("Jarvis, nifty market structure"))
        assertEquals(Structure.Ask.ALL, Structure.asked("what's the intraday structure on banknifty"))
        assertEquals(Structure.Ask.HIGHER_HIGHS, Structure.asked("Is Nifty making higher highs?"))
        assertEquals(Structure.Ask.HIGHER_HIGHS, Structure.asked("is banknifty making lower lows"))
        assertEquals(Structure.Ask.HIGHER_HIGHS, Structure.asked("higher highs and higher lows today?"))
        assertEquals(Structure.Ask.SWINGS, Structure.asked("Where are the swing levels?"))
        assertEquals(Structure.Ask.SWINGS, Structure.asked("nifty swing highs and lows today"))
        assertEquals(Structure.Ask.TREND_RANGE, Structure.asked("Trend or range so far?"))
        assertEquals(Structure.Ask.TREND_RANGE, Structure.asked("is it a trend day or a range day"))
        assertEquals(Structure.Ask.TREND_RANGE, Structure.asked("is today a range day"))
        assertEquals(Structure.Ask.TREND_RANGE, Structure.asked("sideways or trending today?"))
        // Not these: forecasts, advice, Boss's own, other spans, the market memory, the app's fees, gold.
        listOf("will nifty make higher highs tomorrow", "should I buy if it makes a higher high", "will it be a trend day",
            "how many trend days this month", "when was the last trend day", "what's the weekly structure", "higher highs on the daily chart",
            "what is the brokerage structure", "explain the fee structure", "my swing trades", "gold structure today",
            "what is a swing high", "how has the day gone", "recap", "nifty so far", "opening range", "has nifty broken the opening range",
            "what happened in the market today", "what's different about today", "make the case", "is nifty bullish").forEach {
            assertNull(Structure.asked(it), it)
        }
    }

    /** These questions stay with the answers they had (DayStory, OpeningRange, MarketStory), and mine are not theirs. */
    @Test fun routingKeepsTheOthers() {
        assertTrue(DayStory.asked("how has the day gone"))
        assertTrue(OpeningRange.asked("has nifty broken the opening range"))
        assertNotNull(MarketStory.asked("what happened in the market today"))
        val mine = listOf("what's the structure today?", "is nifty making higher highs?", "where are the swing levels?", "trend or range so far?",
            "is it a trend day or a range day")
        for (q in mine) {
            assertNotNull(Structure.asked(q), q)
            assertNull(MarketStory.asked(q), q)
            assertFalse(OpeningRange.asked(q), q)
            assertFalse(TradeCase.asked(q), q)
            assertNull(ChainIntel.asked(q), q)
            assertNull(MarketMemory.asked(q), q)
            assertNull(Honest.asked(q), q)
            assertFalse(DataAge.asked(q), q)
            assertFalse(Airtime.asked(q), q)
            assertNull(AlertSense.asked(q), q)
            assertNull(Thinking.asked(q), q)
            val p = Ask.parse(q)
            assertNull(p.order, q); assertNull(p.command, q)
        }
        // "Trend or range so far" has DayStory's "so far" (and the account's): the structure route comes first in the app.
        assertTrue(DayStory.asked("trend or range so far?"))
        assertEquals(Market.NIFTY, Structure.market(emptyList()))
        assertEquals(Market.BANKNIFTY, Structure.market(listOf(Market.BANKNIFTY)))
        assertEquals(Market.SENSEX, Structure.market(listOf(Market.VIX, Market.SENSEX)))
        assertNull(Structure.market(listOf(Market.GOLD)))
    }

    @Test fun aRisingDay() {
        val r = assertNotNull(Structure.read(Market.NIFTY, up, today))
        assertEquals("higher highs and higher lows", r.five.label)
        assertEquals("higher highs and higher lows", r.fifteen.label)
        assertEquals(1, r.dayKind)
        assertEquals(3, r.swingHighs.size); assertEquals(3, r.swingLows.size)
        assertTrue(r.swingHighs.zipWithNext().all { (a, b) -> a.at.isBefore(b.at) })
        assertEquals(1, r.orSide?.side); assertEquals(1, r.prevSide?.side)
        // Above the prior close all day: since the first minute.
        assertEquals(today.atTime(9, 15), r.prevSide?.since)
        assertTrue(r.low.at.toLocalTime().isBefore(java.time.LocalTime.of(10, 15)), "the low early: ${r.low}")

        val all = Structure.answer(Structure.Ask.ALL, Market.NIFTY, up, today)
        assertTrue(all.startsWith("Nifty's structure as of 13:45, Boss. On 15-minute candles: higher highs and higher lows"), all)
        assertTrue(all.contains("(in the first hour)"), all)
        assertTrue(all.contains("above its opening range ("), all)
        assertTrue(all.contains("above the prior close of 23,950.00 since 09:15"), all)
        assertTrue(all.contains("Recent swing levels on 5-minute candles - highs: "), all)
        assertTrue(all.contains("Trend or range: trend-like so far, up. The measure: the net move from the open"), all)
        assertTrue(all.endsWith(Structure.NOTE), all)
        assertFalse(ADVICE.containsMatchIn(all), all)

        val hh = Structure.answer(Structure.Ask.HIGHER_HIGHS, Market.NIFTY, up, today)
        assertTrue(hh.contains("On 5-minute candles: higher highs and higher lows - swing highs "), hh)
        assertFalse(hh.contains("Trend or range"), hh)
        val sw = Structure.answer(Structure.Ask.SWINGS, Market.NIFTY, up, today)
        assertTrue(Regex("highs: [0-9,.]+ \\(\\d\\d:\\d\\d\\), [0-9,.]+ \\(\\d\\d:\\d\\d\\), [0-9,.]+ \\(\\d\\d:\\d\\d\\); lows: ").containsMatchIn(sw), sw)
        val tr = Structure.answer(Structure.Ask.TREND_RANGE, Market.NIFTY, up, today)
        assertTrue(tr.contains("I call it trend-like at 60% or more with the price in the outer quarter, range-like at 30% or less."), tr)
    }

    @Test fun aFallingDayAndARangeDay() {
        val d = assertNotNull(Structure.read(Market.NIFTY, down, today))
        assertEquals("lower highs and lower lows", d.five.label)
        assertEquals(-1, d.dayKind)
        assertEquals(-1, d.orSide?.side); assertEquals(-1, d.prevSide?.side)
        val ds = Structure.answer(Structure.Ask.ALL, Market.NIFTY, down, today)
        assertTrue(ds.contains("below its opening range"), ds)
        assertTrue(ds.contains("trend-like so far, down"), ds)

        val f = assertNotNull(Structure.read(Market.NIFTY, flat, today))
        assertEquals(0, f.dayKind)
        assertTrue(f.openCrosses >= 6, "crosses: ${f.openCrosses}")
        val fs = Structure.answer(Structure.Ask.TREND_RANGE, Market.NIFTY, flat, today)
        assertTrue(fs.contains("Trend or range: range-like so far."), fs)
    }

    @Test fun earlyStaleAndNotHere() {
        val early = day(20) { i -> 24_000.0 + i }
        assertNull(Structure.read(Market.NIFTY, early, today))
        assertTrue(Structure.answer(Structure.Ask.ALL, Market.NIFTY, early, today).startsWith("It's early for Nifty, Boss: only 20 minutes"))
        assertTrue(Structure.answer(Structure.Ask.ALL, Market.NIFTY, early.take(1), today).startsWith("There are no Nifty candles for today"))
        assertEquals(Structure.NOT_HERE, Structure.answer(Structure.Ask.ALL, Market.GOLD, up, today))
        // The newest candle is 13:44; asked at 14:10 while the market trades: its age is said.
        val stale = Structure.answer(Structure.Ask.SWINGS, Market.NIFTY, up, today, now = today.atTime(14, 10))
        assertTrue(stale.startsWith("Nifty's structure as of 13:45 (the newest candle on the phone; it is 26 minutes old), Boss."), stale)
        val fresh = Structure.answer(Structure.Ask.SWINGS, Market.NIFTY, up, today, now = today.atTime(13, 46))
        assertFalse(fresh.contains("minutes old"), fresh)
    }

    @Test fun inTheCase() {
        val line = assertNotNull(Structure.caseLine(Market.NIFTY, up, today))
        assertTrue(line.startsWith("Nifty's structure at 13:45: higher highs and higher lows on 15-minute candles; above its opening range and above the prior close; trend-like so far, up (net move"), line)
        assertFalse(ADVICE.containsMatchIn(line), line)
        val n = TradeCheck.Now(true, true, 13 * 60 + 45, false, true, null, true, false, false, false, null, 10_000.0,
            14.0, 1.0, 0.3, 0.1, 0.05, 0.15, null, emptyList(), false, emptyList(), emptyList())
        val case = TradeCase.build(TradeCase.Input(n, today.atTime(13, 15), bars = mapOf(Market.NIFTY to up), locked = true)).say()
        assertTrue(case.contains(line), case)
        assertTrue(case.endsWith(TradeCase.YOURS), case)
        // No candles today: no line.
        assertNull(TradeCase.build(TradeCase.Input(n, today.atTime(13, 15), locked = true)).structure)
    }
}
