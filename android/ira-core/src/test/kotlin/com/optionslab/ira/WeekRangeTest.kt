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

class WeekRangeTest {
    private val today = LocalDate.of(2026, 10, 7)   // a Wednesday

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

    /** [n] whole past weeks before today's: Monday makes the low, Friday the high (range 25000 to 25200 on a 25050 open). */
    private fun weeks(n: Int): List<Candle> {
        val monday = WeekRange.weekStart(today)
        return (n downTo 1).flatMap { w ->
            val start = monday.minusWeeks(w.toLong())
            (0..4).flatMap { i ->
                val d = start.plusDays(i.toLong())
                when (i) {
                    0 -> session(d, 25050.0, 25060.0, 25100.0, 25000.0)
                    4 -> session(d, 25100.0, 25150.0, 25200.0, 25050.0)
                    else -> session(d, 25060.0, 25100.0, 25120.0, 25040.0)
                }
            }
        }
    }

    private val ADVICE = Regex("(?i)\\b(you should|i recommend|i suggest|buy|sell|go long|go short|will (rise|fall|go|break)|likely|expect|target)\\b")

    @Test fun theQuestions() {
        assertEquals(WeekRange.Q(1), WeekRange.asked("which day of the week usually makes the weekly high"))
        assertEquals(WeekRange.Q(null), WeekRange.asked("weekly range record for nifty"))
        assertEquals(WeekRange.Q(null), WeekRange.asked("how big is a normal week for banknifty"))
        assertEquals(WeekRange.Q(-1), WeekRange.asked("on which day does nifty make its weekly low"))
        assertEquals(WeekRange.Q(1), WeekRange.asked("hafte ka high kis din banta hai"))
        assertEquals(WeekRange.Q(1), WeekRange.asked("how often does the week's high come on monday"))
        assertEquals(WeekRange.Q(null), WeekRange.asked("what is the usual weekly range of sensex"))
        // Not this record: a weekday's own range, a forecast, advice, the 52-week high, an option, gold, Boss's own week.
        assertNull(WeekRange.asked("which day of the week moves the most"))
        assertNull(WeekRange.asked("will nifty make a new weekly high"))
        assertNull(WeekRange.asked("should I buy at the weekly low"))
        assertNull(WeekRange.asked("how often does nifty hit its 52 week high"))
        assertNull(WeekRange.asked("weekly range record for gold"))
        assertNull(WeekRange.asked("how did nifty do this week"))
        assertNull(WeekRange.asked("how was my week"))
        assertNull(WeekRange.asked("are mondays more volatile"))
    }

    @Test fun theMarket() {
        assertEquals(Market.NIFTY, WeekRange.market(emptyList()))
        assertEquals(Market.SENSEX, WeekRange.market(listOf(Market.SENSEX)))
        assertNull(WeekRange.market(listOf(Market.GOLD)))
        assertNull(WeekRange.market(listOf(Market.VIX)))
    }

    @Test fun aWeekIsReadFromItsWholeSessions() {
        val monday = WeekRange.weekStart(today)
        assertEquals(DayOfWeek.MONDAY, monday.dayOfWeek)
        val short = monday.minusWeeks(1)
        // A week with only three whole sessions (one broken) is not read; this week is never a past week.
        val bars = weeks(2).filter { !it.t.toLocalDate().isAfter(monday.minusWeeks(2).plusDays(4)) } +
            session(short, 25000.0, 25100.0, 25150.0, 24950.0) + session(short.plusDays(1), 25000.0, 25100.0, 25150.0, 24950.0) +
            session(short.plusDays(2), 25000.0, 25100.0, 25150.0, 24950.0, until = LocalTime.of(12, 30)) + session(short.plusDays(3), 25000.0, 25100.0, 25150.0, 24950.0) +
            session(monday, 25000.0, 25100.0, 25150.0, 24950.0)
        val past = WeekRange.past(bars, today)
        assertEquals(1, past.size)
        val w = past[0]
        assertEquals(5, w.days)
        assertEquals(DayOfWeek.FRIDAY, w.highDay)
        assertEquals(DayOfWeek.MONDAY, w.lowDay)
        assertEquals(200.0 / 25050 * 100, w.rangePct, 1e-9)
        assertTrue(w.up)
    }

    @Test fun tooFewWeeksIsSaidPlainly() {
        val said = WeekRange.answer(WeekRange.Q(null), Market.NIFTY, weeks(3), today, today.atTime(10, 0))
        assertTrue(said.contains("only 3 whole Nifty weeks"), said)
        assertTrue(said.contains("too few"), said)
    }

    @Test fun theRecordOfWeeks() {
        val bars = weeks(10)
        val said = WeekRange.answer(WeekRange.Q(null), Market.NIFTY, bars, today, today.atTime(9, 0))
        assertTrue(said.startsWith("Over the last 10 whole Nifty weeks on this phone"), said)
        assertTrue(said.contains("the median week's range was 0.80% of its first open"), said)
        assertTrue(said.contains("and 10 (100%) closed above that open."), said)
        assertTrue(said.contains("The week's high came on Monday 0 (0%), Tuesday 0 (0%), Wednesday 0 (0%), Thursday 0 (0%), Friday 10 (100%) - most often on Friday."), said)
        assertTrue(said.contains("The week's low came on Monday 10 (100%)"), said)
        assertTrue(said.contains("by chance alone"), said)
        assertTrue(said.contains("only 10 weeks"), said)
        assertTrue(said.endsWith(WeekRange.NOTE), said)
        assertFalse(ADVICE.containsMatchIn(said), said)
        val high = WeekRange.answer(WeekRange.Q(1), Market.NIFTY, bars, today, today.atTime(9, 0))
        assertTrue(high.contains("The week's high came on"), high)
        assertFalse(high.contains("The week's low came on"), high)
    }

    @Test fun thisWeekIsSetBeside() {
        val monday = WeekRange.weekStart(today)
        val live = weeks(8) + session(monday, 25000.0, 25100.0, 25150.0, 24950.0) + session(monday.plusDays(1), 25100.0, 25200.0, 25300.0, 25050.0) +
            session(today, 25200.0, 25250.0, 25280.0, 25150.0, until = LocalTime.of(13, 0))
        val said = WeekRange.answer(WeekRange.Q(null), Market.NIFTY, live, today, LocalDateTime.of(today, LocalTime.of(13, 1)))
        assertTrue(said.contains("This week so far (3 sessions), Nifty's range is 1.40% of the week's first open (high 25,300.00 on Tuesday, low 24,950.00 on Monday)."), said)
    }
}
