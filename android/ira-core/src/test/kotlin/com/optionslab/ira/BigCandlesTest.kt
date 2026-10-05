package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BigCandlesTest {
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
     * One session of 1-minute candles from 09:15 to [until] (15:29 whole): flat at 25000 to 09:29, then the 09:30-09:34
     * 5-minute candle moves [jump] points, flat again to 12:00, then a walk to [end] by 15:29.
     */
    private fun session(d: LocalDate, jump: Double, end: Double, until: LocalTime = LocalTime.of(15, 29)): List<Candle> {
        val j0 = LocalTime.of(9, 29).toSecondOfDay(); val j1 = LocalTime.of(9, 34).toSecondOfDay()
        val noon = LocalTime.of(12, 0).toSecondOfDay(); val last = LocalTime.of(15, 29).toSecondOfDay()
        fun px(t: LocalTime): Double {
            val s = t.toSecondOfDay()
            return when {
                s <= j0 -> 25000.0
                s <= j1 -> 25000 + jump * (s - j0).toDouble() / (j1 - j0)
                s <= noon -> 25000 + jump
                else -> 25000 + jump + (end - 25000 - jump) * (s - noon).toDouble() / (last - noon)
            }
        }
        val out = ArrayList<Candle>()
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
        assertEquals(BigCandles.Q(null), BigCandles.asked("After a big 5-minute candle in the first hour, does the day continue?"))
        assertEquals(BigCandles.Q(null), BigCandles.asked("big candle record for BankNifty"))
        assertEquals(BigCandles.Q(1), BigCandles.asked("how often does a big green candle in the morning follow through?"))
        assertEquals(BigCandles.Q(null), BigCandles.asked("subah bada candle aane ke baad nifty kya karta hai"))
        assertEquals(BigCandles.Q(-1), BigCandles.asked("after a big red 5 min candle in the first hour does nifty keep falling"))
        assertEquals(BigCandles.Q(null), BigCandles.asked("what happens after a 0.4% 5-minute candle"))
        assertEquals(BigCandles.Q(null), BigCandles.asked("large first hour candles record for finnifty"))
        // Not this record: a forecast, advice, an alert, a definition, a pattern, gold.
        assertNull(BigCandles.asked("will nifty continue after this big candle tomorrow"))
        assertNull(BigCandles.asked("should I buy after a big green candle in the morning"))
        assertNull(BigCandles.asked("alert me on a big 5 minute candle after the open"))
        assertNull(BigCandles.asked("what is a big candle"))
        assertNull(BigCandles.asked("big bullish engulfing candle record"))
        assertNull(BigCandles.asked("gold big candle record"))
        assertNull(BigCandles.asked("how did nifty do today"))
    }

    @Test fun theMarket() {
        assertEquals(Market.NIFTY, BigCandles.market(emptyList()))
        assertEquals(Market.BANKNIFTY, BigCandles.market(listOf(Market.BANKNIFTY)))
        assertNull(BigCandles.market(listOf(Market.GOLD)))
        assertNull(BigCandles.market(listOf(Market.VIX)))
    }

    @Test fun theFirstHourIsBuiltIntoFiveMinuteCandles() {
        val d = weekdaysBefore(1).first()
        val hour = BigCandles.firstHour(session(d, 125.0, 25300.0))
        assertEquals(12, hour.size)
        assertEquals(LocalTime.of(9, 30), hour[3].start)
        assertEquals(0.5, hour[3].movePct, 1e-9)
        val day = BigCandles.read(MarketStory.Session(d, session(d, 125.0, 25300.0)))
        assertNotNull(day)
        assertTrue(day.continued)
        assertNull(BigCandles.read(MarketStory.Session(d, session(d, 125.0, 25300.0, until = LocalTime.of(12, 10)))))
        assertNull(BigCandles.read(MarketStory.Session(d, session(d, 50.0, 25300.0)))!!.big)
    }

    @Test fun tooFewSessionsIsSaidPlainly() {
        val bars = weekdaysBefore(5).flatMap { session(it, 125.0, 25300.0) }
        val said = BigCandles.answer(BigCandles.Q(null), Market.NIFTY, bars, today, today.atTime(10, 0))
        assertTrue(said.contains("only 5 whole Nifty sessions"), said)
        assertTrue(said.contains("too few"), said)
    }

    @Test fun theRecordOfBigCandleDays() {
        val days = weekdaysBefore(30)
        // 6 big rises that kept going, 2 big rises given back, 4 big falls that kept going, 18 days with no big candle.
        val bars = days.withIndex().flatMap { (i, d) ->
            when {
                i < 6 -> session(d, 125.0, 25300.0)
                i < 8 -> session(d, 125.0, 24900.0)
                i < 12 -> session(d, -125.0, 24800.0)
                else -> session(d, 50.0, 25100.0)
            }
        }
        val said = BigCandles.answer(BigCandles.Q(null), Market.NIFTY, bars, today, today.atTime(9, 0))
        assertTrue(said.startsWith("Over the last 30 whole Nifty sessions on this phone"), said)
        assertTrue(said.contains("12 (40%) had a 5-minute candle of 0.4% or more in the first hour - 8 up and 4 down"), said)
        assertTrue(said.contains("On the 12 big-candle days the day closed beyond that candle's close, in its direction, on 10 (83%), and back past the candle's open on 2 (17%); the median close was +0.50% from the candle's close"), said)
        assertFalse(said.contains("After a big rise"), said)
        assertTrue(said.contains("only 30 sessions"), said)
        assertTrue(said.endsWith(BigCandles.NOTE), said)
        assertFalse(ADVICE.containsMatchIn(said), said)
        val up = BigCandles.answer(BigCandles.Q(1), Market.NIFTY, bars, today, today.atTime(9, 0))
        assertTrue(up.contains("On the 8 big-rise days the day closed beyond that candle's close, in its direction, on 6 (75%), and back past the candle's open on 2 (25%); the median close was +0.70%"), up)
        val down = BigCandles.answer(BigCandles.Q(-1), Market.NIFTY, bars, today, today.atTime(9, 0))
        assertTrue(down.contains("too few big-fall days (4)"), down)
    }

    @Test fun todayIsSetBeside() {
        val past = weekdaysBefore(25).flatMap { session(it, 50.0, 25100.0) }
        val withBig = past + session(today, 125.0, 25300.0, until = LocalTime.of(11, 0))
        val said = BigCandles.answer(BigCandles.Q(null), Market.NIFTY, withBig, today, LocalDateTime.of(today, LocalTime.of(11, 1)))
        assertTrue(said.contains("too few big-candle days (0)"), said)
        assertTrue(said.contains("Today Nifty had a +0.50% 5-minute candle at 09:30; now it is 0.00% from that candle's close"), said)
        val quiet = past + session(today, 50.0, 25100.0, until = LocalTime.of(9, 50))
        val early = BigCandles.answer(BigCandles.Q(null), Market.NIFTY, quiet, today, LocalDateTime.of(today, LocalTime.of(9, 51)))
        assertTrue(early.contains("Today Nifty has had no 5-minute candle of 0.4% or more in the first hour so far - the biggest was +0.20% at 09:30."), early)
    }
}
