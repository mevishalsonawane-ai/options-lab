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

class LunchRangeTest {
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
     * One session of 1-minute candles from 09:15 to [until] (15:29 whole): the morning swings 25000 +-[morningSwing] with its
     * high at 10:00 and low at 11:00, lunch sits 25000 +-[lunchSwing], and from 13:30 the price walks to [afternoonEnd] by 15:29.
     */
    private fun session(d: LocalDate, morningSwing: Double = 100.0, lunchSwing: Double = 20.0, afternoonEnd: Double = 25080.0,
                        until: LocalTime = LocalTime.of(15, 29)): List<Candle> {
        val out = ArrayList<Candle>()
        var t = LocalTime.of(9, 15)
        val afternoonMinutes = 119.0
        while (!t.isAfter(until)) {
            val px = when {
                t == LocalTime.of(10, 0) -> 25000 + morningSwing
                t == LocalTime.of(11, 0) -> 25000 - morningSwing
                t.isBefore(LocalTime.of(12, 0)) -> 25000.0
                t.isBefore(LocalTime.of(13, 30)) -> if (t.minute % 2 == 0) 25000 + lunchSwing else 25000 - lunchSwing
                else -> 25000 + (afternoonEnd - 25000) * ((t.toSecondOfDay() - LocalTime.of(13, 30).toSecondOfDay()) / 60.0 / afternoonMinutes)
            }
            out += Candle(d.atTime(t), px, px + 1, px - 1, px)
            t = t.plusMinutes(1)
        }
        return out
    }

    private val ADVICE = Regex("(?i)\\b(you should|i recommend|i suggest|buy|sell|go long|go short|will (rise|fall|go|break)|likely|expect|target)\\b")

    @Test fun theQuestions() {
        assertEquals(LunchRange.Q(null), LunchRange.asked("Does Nifty break the lunch range?"))
        assertEquals(LunchRange.Q(null), LunchRange.asked("lunch range breakout record"))
        assertEquals(LunchRange.Q(null), LunchRange.asked("is the lunch lull real"))
        assertEquals(LunchRange.Q(null), LunchRange.asked("how wide is BankNifty's lunch range against the morning?"))
        assertEquals(LunchRange.Q(null), LunchRange.asked("lunch ki range todne ke baad nifty kya karta hai"))
        assertEquals(LunchRange.Q(1), LunchRange.asked("how often does sensex close above the lunch range"))
        assertEquals(LunchRange.Q(-1), LunchRange.asked("how often does the afternoon break below the lunch range"))
        assertEquals(LunchRange.Q(null), LunchRange.asked("midday range record for finnifty"))
        // Not this record: a forecast, advice, an alert, Boss's own trades, how quiet lunch is, gold.
        assertNull(LunchRange.asked("will nifty break the lunch range"))
        assertNull(LunchRange.asked("should I buy the lunch range breakout"))
        assertNull(LunchRange.asked("alert me when nifty breaks the lunch range"))
        assertNull(LunchRange.asked("is the lunch hour usually quiet"))
        assertNull(LunchRange.asked("order lunch from swiggy"))
        assertNull(LunchRange.asked("gold lunch range"))
    }

    @Test fun theMarket() {
        assertEquals(Market.NIFTY, LunchRange.market(emptyList()))
        assertEquals(Market.BANKNIFTY, LunchRange.market(listOf(Market.BANKNIFTY)))
        assertNull(LunchRange.market(listOf(Market.GOLD)))
        assertNull(LunchRange.market(listOf(Market.VIX)))
    }

    @Test fun tooFewSessionsIsSaidPlainly() {
        val bars = weekdaysBefore(5).flatMap { session(it) }
        val said = LunchRange.answer(LunchRange.Q(null), Market.NIFTY, bars, today, today.atTime(10, 0))
        assertTrue(said.contains("only 5 whole Nifty sessions"), said)
        assertTrue(said.contains("too few"), said)
    }

    @Test fun aPartSessionIsNotWhole() {
        val d = weekdaysBefore(1).first()
        assertNull(LunchRange.read(MarketStory.Session(d, session(d, until = LocalTime.of(12, 10)))))
        val whole = LunchRange.read(MarketStory.Session(d, session(d)))!!
        assertEquals(1, whole.first)
        assertTrue(whole.narrowest)
        assertFalse(whole.highInLunch)
        assertFalse(whole.lowInLunch)
    }

    @Test fun theRecordCountsBreaksAndCloses() {
        val days = weekdaysBefore(30)
        // 20 days the afternoon walks up out of lunch and holds; 6 it walks down and holds; 4 it stays inside (ends at 25000).
        val bars = days.withIndex().flatMap { (i, d) ->
            when {
                i < 20 -> session(d, afternoonEnd = 25080.0)
                i < 26 -> session(d, afternoonEnd = 24920.0)
                else -> session(d, afternoonEnd = 25000.0)
            }
        }
        val said = LunchRange.answer(LunchRange.Q(null), Market.NIFTY, bars, today, today.atTime(9, 0))
        assertTrue(said.startsWith("Over the last 30 whole Nifty sessions on this phone"), said)
        assertTrue(said.contains("it was the narrowest of the three on 26 of 30 days (87%)"), said)
        assertTrue(said.contains("The day's high was made inside it on 0 days (0%) and the low on 0 (0%)"), said)
        assertTrue(said.contains("closed a minute above the lunch high first on 20 days"), said)
        assertTrue(said.contains("the day's close held above the lunch high on 20 (100%)"), said)
        assertTrue(said.contains("closed a minute below the lunch low first on 6 days"), said)
        assertTrue(said.contains("never closed a minute outside the lunch range on 4 of 30 days (13%)"), said)
        assertTrue(said.contains("only 30 sessions"), said)
        assertTrue(said.endsWith(LunchRange.NOTE), said)
        assertFalse(ADVICE.containsMatchIn(said), said)
        // Asked of the upside only: the downside's line is left out.
        val up = LunchRange.answer(LunchRange.Q(1), Market.NIFTY, bars, today, today.atTime(9, 0))
        assertTrue(up.contains("above the lunch high first"), up)
        assertFalse(up.contains("below the lunch low first"), up)
    }

    @Test fun todayIsSetBeside() {
        val bars = weekdaysBefore(25).flatMap { session(it) } + session(today, afternoonEnd = 24900.0, until = LocalTime.of(14, 30))
        val said = LunchRange.answer(LunchRange.Q(null), Market.NIFTY, bars, today, LocalDateTime.of(today, LocalTime.of(14, 31)))
        assertTrue(said.contains("Today Nifty's lunch range was 24,979.00 to 25,021.00"), said)
        assertTrue(said.contains("the afternoon first closed a minute below its low at"), said)
        val lunch = weekdaysBefore(25).flatMap { session(it) } + session(today, until = LocalTime.of(12, 30))
        val during = LunchRange.answer(LunchRange.Q(null), Market.NIFTY, lunch, today, LocalDateTime.of(today, LocalTime.of(12, 31)))
        assertTrue(during.contains("Today Nifty's lunch range so far is 24,979.00 to 25,021.00"), during)
        assertTrue(during.contains("to 12:30"), during)
    }
}
