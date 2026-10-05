package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenHighLowTest {
    private val today = LocalDate.of(2026, 10, 5)   // a Monday

    /** The [n] weekdays before [today], oldest first. */
    private fun weekdaysBefore(n: Int): List<LocalDate> {
        val out = ArrayList<LocalDate>()
        var d = today.minusDays(1)
        while (out.size < n) {
            if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) out += d
            d = d.minusDays(1)
        }
        return out.reversed()
    }

    /**
     * One session of 1-minute candles from 09:15 to [until] (15:29 whole): the price walks from 25000 to [mid] by 12:00 and
     * on to [end] by 15:29; each candle's high and low are its open and close (no wicks), so a day that only falls opens at
     * its high.
     */
    private fun session(d: LocalDate, mid: Double, end: Double, until: LocalTime = LocalTime.of(15, 29)): List<Candle> {
        val out = ArrayList<Candle>()
        val noon = LocalTime.of(12, 0).toSecondOfDay(); val start = LocalTime.of(9, 15).toSecondOfDay(); val last = LocalTime.of(15, 29).toSecondOfDay()
        fun px(t: LocalTime): Double {
            val s = t.toSecondOfDay()
            return if (s <= noon) 25000 + (mid - 25000) * (s - start).toDouble() / (noon - start)
            else mid + (end - mid) * (s - noon).toDouble() / (last - noon)
        }
        var t = LocalTime.of(9, 15)
        while (!t.isAfter(until)) {
            val o = if (t == LocalTime.of(9, 15)) 25000.0 else px(t.minusMinutes(1))
            val c = px(t)
            out += Candle(d.atTime(t), o, maxOf(o, c), minOf(o, c), c)
            t = t.plusMinutes(1)
        }
        return out
    }

    private val ADVICE = Regex("(?i)\\b(you should|i recommend|i suggest|buy|sell|go long|go short|will (rise|fall|go|break)|likely|expect|target)\\b")

    @Test fun theQuestions() {
        assertEquals(OpenHighLow.Q(1), OpenHighLow.asked("How often is the open the high of the day?"))
        assertEquals(OpenHighLow.Q(-1), OpenHighLow.asked("open = low days record"))
        assertEquals(OpenHighLow.Q(1), OpenHighLow.asked("how do open high days close for BankNifty?"))
        assertEquals(OpenHighLow.Q(-1), OpenHighLow.asked("open low wale din nifty kaise band hota hai"))
        assertEquals(OpenHighLow.Q(null), OpenHighLow.asked("open high open low record"))
        assertEquals(OpenHighLow.Q(null), OpenHighLow.asked("how often does sensex open at the day's high or low"))
        assertEquals(OpenHighLow.Q(1), OpenHighLow.asked("how often has nifty opened at the high"))
        assertEquals(OpenHighLow.Q(null), OpenHighLow.asked("o=h o=l record for finnifty"))
        // Not this record: a quote, a forecast, advice, an alert, a stock screen, gold.
        assertNull(OpenHighLow.asked("what is nifty's open high low close today"))
        assertNull(OpenHighLow.asked("what's the open, high and low of banknifty"))
        assertNull(OpenHighLow.asked("will nifty open at the high tomorrow"))
        assertNull(OpenHighLow.asked("should I sell open high days"))
        assertNull(OpenHighLow.asked("alert me on open high setups"))
        assertNull(OpenHighLow.asked("open high open low stocks scanner"))
        assertNull(OpenHighLow.asked("gold open = high days"))
    }

    @Test fun theMarket() {
        assertEquals(Market.NIFTY, OpenHighLow.market(emptyList()))
        assertEquals(Market.BANKNIFTY, OpenHighLow.market(listOf(Market.BANKNIFTY)))
        assertNull(OpenHighLow.market(listOf(Market.GOLD)))
        assertNull(OpenHighLow.market(listOf(Market.VIX)))
    }

    @Test fun tooFewSessionsIsSaidPlainly() {
        val bars = weekdaysBefore(5).flatMap { session(it, 24900.0, 24800.0) }
        val said = OpenHighLow.answer(OpenHighLow.Q(null), Market.NIFTY, bars, today, today.atTime(10, 0))
        assertTrue(said.contains("only 5 whole Nifty sessions"), said)
        assertTrue(said.contains("too few"), said)
    }

    @Test fun aPartSessionIsNotWhole() {
        val d = weekdaysBefore(1).first()
        assertNull(OpenHighLow.read(MarketStory.Session(d, session(d, 24900.0, 24800.0, until = LocalTime.of(12, 10)))))
        val whole = OpenHighLow.read(MarketStory.Session(d, session(d, 24900.0, 24800.0)))!!
        assertTrue(whole.atHigh)
        assertFalse(whole.atLow)
    }

    @Test fun theRecordCountsOpensAtTheEnds() {
        val days = weekdaysBefore(30)
        // 8 days fall all day (open = high), 3 rise all day (open = low), 19 rise then fall back past the open.
        val bars = days.withIndex().flatMap { (i, d) ->
            when {
                i < 8 -> session(d, 24900.0, 24800.0)
                i < 11 -> session(d, 25100.0, 25200.0)
                else -> session(d, 25100.0, 24950.0)
            }
        }
        val said = OpenHighLow.answer(OpenHighLow.Q(null), Market.NIFTY, bars, today, today.atTime(9, 0))
        assertTrue(said.startsWith("Over the last 30 whole Nifty sessions on this phone"), said)
        assertTrue(said.contains("within 0.05% of the day's high on 8 days (27%)"), said)
        assertTrue(said.contains("within 0.05% of the day's low on 3 days (10%)"), said)
        assertTrue(said.contains("On the 8 open-high days the close was below the open on 8 (100%) and above it on 0 (0%), a median close of -0.80% from the open"), said)
        assertTrue(said.contains("their median range was 0.80%, against 0.60% on the other days"), said)
        assertTrue(said.contains("too few open-low days (3)"), said)
        assertTrue(said.contains("only 30 sessions"), said)
        assertTrue(said.endsWith(OpenHighLow.NOTE), said)
        assertFalse(ADVICE.containsMatchIn(said), said)
        // Asked of the open-low days only: the open-high line is left out.
        val low = OpenHighLow.answer(OpenHighLow.Q(-1), Market.NIFTY, bars, today, today.atTime(9, 0))
        assertTrue(low.contains("too few open-low days"), low)
        assertFalse(low.contains("open-high days the close"), low)
    }

    @Test fun todayIsSetBeside() {
        val past = weekdaysBefore(25).flatMap { session(it, 25100.0, 24950.0) }
        val falling = past + session(today, 24900.0, 24800.0, until = LocalTime.of(11, 0))
        val said = OpenHighLow.answer(OpenHighLow.Q(null), Market.NIFTY, falling, today, LocalDateTime.of(today, LocalTime.of(11, 1)))
        assertTrue(said.contains("Today Nifty's open, 25,000.00, is within 0.05% of its high so far"), said)
        val mixed = past + session(today, 25100.0, 24950.0)
        val after = OpenHighLow.answer(OpenHighLow.Q(null), Market.NIFTY, mixed, today, LocalDateTime.of(today, LocalTime.of(16, 0)))
        assertTrue(after.contains("Today Nifty's open, 25,000.00, is 0.40% below its high and 0.20% above its low."), after)
    }
}
