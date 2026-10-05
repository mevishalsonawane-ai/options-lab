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

class ExtremeClosesTest {
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
     * One session of 1-minute candles from 09:15 to [until] (15:29 whole): a straight walk from [open] to [close], with the
     * 12:00 candle reaching [hi] and [lo].
     */
    private fun session(d: LocalDate, open: Double, close: Double, hi: Double, lo: Double, until: LocalTime = LocalTime.of(15, 29)): List<Candle> {
        val first = LocalTime.of(9, 15).toSecondOfDay(); val last = LocalTime.of(15, 29).toSecondOfDay()
        fun px(t: LocalTime) = open + (close - open) * (t.toSecondOfDay() - first).toDouble() / (last - first)
        val out = ArrayList<Candle>()
        var t = LocalTime.of(9, 15)
        while (!t.isAfter(until)) {
            val o = if (t == LocalTime.of(9, 15)) open else px(t.minusMinutes(1))
            val c = px(t)
            val noon = t == LocalTime.of(12, 0)
            out += Candle(d.atTime(t), o, if (noon) hi else maxOf(o, c), if (noon) lo else minOf(o, c), c)
            t = t.plusMinutes(1)
        }
        return out
    }

    private val ADVICE = Regex("(?i)\\b(you should|i recommend|i suggest|buy|sell|go long|go short|will (rise|fall|go|break)|likely|expect|target)\\b")

    @Test fun theQuestions() {
        assertEquals(ExtremeCloses.Q(1), ExtremeCloses.asked("How often does Nifty close near its high?"))
        assertEquals(ExtremeCloses.Q(-1), ExtremeCloses.asked("what happens the day after BankNifty closes at the low"))
        assertEquals(ExtremeCloses.Q(1), ExtremeCloses.asked("strong close record for sensex"))
        assertEquals(ExtremeCloses.Q(1), ExtremeCloses.asked("high pe close hone ke baad agle din kya hota hai"))
        assertEquals(ExtremeCloses.Q(null), ExtremeCloses.asked("how often does nifty close at the high or the low of the day"))
        assertEquals(ExtremeCloses.Q(-1), ExtremeCloses.asked("after a weak close what does finnifty do next day"))
        assertEquals(ExtremeCloses.Q(null), ExtremeCloses.asked("how often are there extreme closes"))
        assertEquals(ExtremeCloses.Q(1), ExtremeCloses.asked("how often does nifty finish near its high"))
        assertEquals(ExtremeCloses.Q(-1), ExtremeCloses.asked("what does banknifty do the day after it settles at the low"))
        assertEquals(ExtremeCloses.Q(1), ExtremeCloses.asked("high pe khatam hone ke baad agle din kya hota hai"))
        // Not this record: a forecast, advice, an alert, the week's high, an option, gold, today alone.
        assertNull(ExtremeCloses.asked("will nifty close near the high tomorrow"))
        assertNull(ExtremeCloses.asked("should I buy after a strong close"))
        assertNull(ExtremeCloses.asked("alert me if nifty closes at the high"))
        assertNull(ExtremeCloses.asked("how often does nifty close at the weekly high"))
        assertNull(ExtremeCloses.asked("gold closing at the high record"))
        assertNull(ExtremeCloses.asked("how did nifty close today"))
    }

    @Test fun theMarket() {
        assertEquals(Market.NIFTY, ExtremeCloses.market(emptyList()))
        assertEquals(Market.BANKNIFTY, ExtremeCloses.market(listOf(Market.BANKNIFTY)))
        assertNull(ExtremeCloses.market(listOf(Market.GOLD)))
        assertNull(ExtremeCloses.market(listOf(Market.VIX)))
    }

    @Test fun whereTheCloseSits() {
        val d = ExtremeCloses.Day(today, 100.0, 90.0, 99.5, 100.5, 101.0)
        assertEquals(1, d.side)
        assertEquals(0.95, d.at!!, 1e-9)
        assertEquals(-1, ExtremeCloses.Day(today, 100.0, 90.0, 90.5).side)
        assertEquals(0, ExtremeCloses.Day(today, 100.0, 90.0, 95.0).side)
        assertEquals(0, ExtremeCloses.Day(today, 100.0, 100.0, 100.0).side)
        // A broken session is not read; a session with no next whole one has no next day.
        val days = weekdaysBefore(3)
        val bars = session(days[0], 25000.0, 25190.0, 25200.0, 24990.0) +
            session(days[1], 25000.0, 25100.0, 25200.0, 24900.0, until = LocalTime.of(13, 0)) +
            session(days[2], 25000.0, 25100.0, 25200.0, 24900.0)
        val past = ExtremeCloses.past(bars, today)
        assertEquals(2, past.size)
        assertEquals(1, past[0].side)
        assertEquals(25000.0, past[0].nextOpen)
        assertFalse(past[1].hasNext)
    }

    @Test fun tooFewSessionsIsSaidPlainly() {
        val bars = weekdaysBefore(5).flatMap { session(it, 25000.0, 25190.0, 25200.0, 24990.0) }
        val said = ExtremeCloses.answer(ExtremeCloses.Q(null), Market.NIFTY, bars, today, today.atTime(10, 0))
        assertTrue(said.contains("only 5 whole Nifty sessions"), said)
        assertTrue(said.contains("too few"), said)
    }

    @Test fun theRecordOfCloses() {
        val days = weekdaysBefore(30)
        // Every day opens at 25000. Days 0-11 alternate: an even day closes at its high end (25190 in 24990-25200) and the
        // odd day after opens there and closes higher (a carried-on day, itself closing mid-range); days 12-17 close at the
        // low end, days 18-29 close mid-range.
        val bars = days.withIndex().flatMap { (i, d) ->
            when {
                i < 12 && i % 2 == 0 -> session(d, 25000.0, 25190.0, 25200.0, 24990.0)
                i < 12 -> session(d, 25190.0, 25240.0, 25400.0, 25100.0)
                i < 18 -> session(d, 25000.0, 24810.0, 25010.0, 24800.0)
                else -> session(d, 25000.0, 25000.0, 25100.0, 24900.0)
            }
        }
        val said = ExtremeCloses.answer(ExtremeCloses.Q(null), Market.NIFTY, bars, today, today.atTime(9, 0))
        assertTrue(said.startsWith("Over the last 30 whole Nifty sessions on this phone"), said)
        assertTrue(said.contains("6 (20%) closed in the top 10% of the day's range and 6 (20%) in the bottom 10%."), said)
        assertTrue(said.contains("After the 6 high-end closes with a next session, the next day opened higher on 0 (0%) and closed higher than that close on 6 (100%); the median next-day change was +0.20%"), said)
        assertTrue(said.contains("After the 6 low-end closes with a next session"), said)
        assertTrue(said.contains("only 30 sessions"), said)
        assertTrue(said.endsWith(ExtremeCloses.NOTE), said)
        assertFalse(ADVICE.containsMatchIn(said), said)
        val low = ExtremeCloses.answer(ExtremeCloses.Q(-1), Market.NIFTY, bars, today, today.atTime(9, 0))
        assertFalse(low.contains("high-end closes"), low)
        assertTrue(low.contains("low-end closes"), low)
    }

    @Test fun todayIsSetBeside() {
        val past = weekdaysBefore(25).flatMap { session(it, 25000.0, 25000.0, 25100.0, 24900.0) }
        val live = past + session(today, 25000.0, 25100.0, 25200.0, 24900.0, until = LocalTime.of(13, 0))
        val said = ExtremeCloses.answer(ExtremeCloses.Q(1), Market.NIFTY, live, today, LocalDateTime.of(today, LocalTime.of(13, 1)))
        assertTrue(said.contains("too few high-end closes with a next session on the phone (0)"), said)
        assertTrue(said.contains("Today Nifty, now "), said)
        assertTrue(said.contains("of the way up the day's range (low 24,900.00, high 25,200.00) so far."), said)
    }
}
