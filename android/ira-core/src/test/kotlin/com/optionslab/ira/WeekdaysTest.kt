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

class WeekdaysTest {
    private val today = LocalDate.of(2026, 10, 5)   // a Monday
    private val after = LocalDateTime.of(2026, 10, 5, 18, 0)

    /** [count] weekdays before [today], oldest first. */
    private fun weekdays(count: Int, end: LocalDate = today.minusDays(1)): List<LocalDate> {
        val out = ArrayList<LocalDate>()
        var d = end
        while (out.size < count) {
            if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) out += d
            d = d.minusDays(1)
        }
        return out.reversed()
    }

    /**
     * One whole session on [d] opening at [open]: range [rangePts] (one spike each way at 11:00), closing [move] from the
     * open, the last hour from 14:30 moving [lastHour]; [minutes] bars from 09:15.
     */
    private fun session(d: LocalDate, open: Double, rangePts: Double, move: Double, lastHour: Double = 0.0, minutes: Int = 375): List<Candle> {
        val out = ArrayList<Candle>()
        val close = open + move
        for (i in 0 until minutes) {
            val t = LocalDateTime.of(d, LocalTime.of(9, 15)).plusMinutes(i.toLong())
            val tt = t.toLocalTime()
            val c = when {
                i == minutes - 1 -> close
                tt.isAfter(LocalTime.of(14, 30)) -> close
                !tt.isBefore(LocalTime.of(12, 0)) -> close - lastHour
                else -> open
            }
            var h = maxOf(open, c) + 0.5; var l = minOf(open, c) - 0.5
            if (tt == LocalTime.of(11, 0)) { h = open + rangePts / 2; l = open - rangePts / 2 }
            out += Candle(t, if (i == 0) open else c, h, l, c)
        }
        return out
    }

    /** Sessions on [days]: Mondays wide (200 points), the rest 100; each closes +[up] on even days, -[down] on odd ones. */
    private fun build(days: List<LocalDate>, wide: DayOfWeek = DayOfWeek.MONDAY, expiries: Set<LocalDate> = emptySet()): List<Candle> {
        var px = 24_000.0
        val out = ArrayList<Candle>()
        for ((i, d) in days.withIndex()) {
            val r = if (d.dayOfWeek == wide) 200.0 else if (d in expiries) 300.0 else 100.0
            val mv = if (i % 2 == 0) 40.0 else -20.0
            out += session(d, px, r, mv, lastHour = if (d in expiries) 60.0 else 10.0)
            px += mv
        }
        return out
    }

    @Test fun questionsAreUnderstood() {
        for (s in listOf("are mondays more volatile", "which day of the week moves the most", "how does nifty usually do on fridays",
            "is monday usually the widest day", "weekday record for banknifty", "monday ko nifty kaisa chalta hai",
            "how are mondays for nifty", "which weekday is the most volatile", "nifty day of the week effect",
            "kis din market sabse zyada hilta hai"))
            assertNotNull(Weekdays.asked(s), s)
        assertEquals(DayOfWeek.FRIDAY, Weekdays.asked("how does nifty usually do on fridays")?.day)
        assertEquals(DayOfWeek.MONDAY, Weekdays.asked("monday ko nifty kaisa chalta hai")?.day)
        assertNull(Weekdays.asked("which day of the week moves the most")?.day)
        for (s in listOf("are expiry days more volatile", "are expiry days wider than other days", "expiry day range vs normal days",
            "do expiries usually move more", "expiry ke din range zyada hota hai kya"))
            assertEquals(true, Weekdays.asked(s)?.expiry, s)
    }

    @Test fun otherQuestionsAreLeftAlone() {
        for (s in listOf("is monday a holiday", "is the market open on friday", "what will nifty do on monday", "monday prediction for banknifty",
            "what happened on friday", "how did the last expiry go", "how is expiry going", "what happened the last 3 expiries",
            "when is the next expiry", "do i trade better on mondays", "should i trade on fridays", "levels for monday",
            "what was the range on friday", "what is expiry day", "how does gold do on mondays", "what is today's range",
            "expected move by expiry", "remember that i trade on fridays", "find my notes on fridays", "is today expiry"))
            assertNull(Weekdays.asked(s), s)
    }

    @Test fun marketIsTheIndexAsked() {
        assertEquals(Market.NIFTY, Weekdays.market(emptyList()))
        assertEquals(Market.BANKNIFTY, Weekdays.market(listOf(Market.BANKNIFTY)))
        assertNull(Weekdays.market(listOf(Market.GOLD)))
        assertNull(Weekdays.market(listOf(Market.VIX)))
    }

    @Test fun pastReadsWholeSessionsWithTheirMoves() {
        val days = weekdays(20)
        val bars = build(days) + session(today.minusDays(40).let { d -> d }, 24_000.0, 100.0, 0.0, minutes = 100) // a part session: left out
        val past = Weekdays.past(bars, today)
        assertEquals(20, past.size)
        assertNull(past.first().movePct)                     // nothing before the first
        assertTrue(past.drop(1).all { it.movePct != null })
        val mon = past.first { it.weekday == DayOfWeek.MONDAY }
        assertEquals(200.0 / 24_000 * 100, mon.rangePct, 0.05)
        // Today's own session is never in the record.
        val withToday = bars + session(today, 24_000.0, 50.0, 0.0)
        assertEquals(20, Weekdays.past(withToday, today).size)
    }

    @Test fun everyWeekdayWithTheWidest() {
        val days = weekdays(30)
        val a = Weekdays.answer(Weekdays.Q(null, false), Market.NIFTY, build(days), today, after)
        assertTrue(a.startsWith("Over the last 30 whole Nifty sessions on this phone"), a)
        assertTrue(a.contains("Mondays (6): median range"), a)
        assertTrue(a.contains("Widest by median range: Monday"), a)
        assertTrue(a.contains("2.0 times"), a)
        assertTrue(a.contains("closed up on"), a)
        assertTrue(a.endsWith(Weekdays.NOTE), a)
        assertFalse(a.contains("expiry"), a)
    }

    @Test fun oneWeekdayAgainstTheRest() {
        val days = weekdays(30)
        val a = Weekdays.answer(Weekdays.Q(DayOfWeek.MONDAY, false), Market.BANKNIFTY, build(days), today, after)
        assertTrue(a.contains("6 were Mondays: median range 0.8"), a)
        assertTrue(a.contains("The other 24 days: median range 0.4"), a)
        assertTrue(a.contains("2.0 times the other days'"), a)
        assertTrue(a.contains("The widest Monday was"), a)
        val f = Weekdays.answer(Weekdays.Q(DayOfWeek.FRIDAY, false), Market.NIFTY, build(days), today, after)
        assertTrue(f.contains("1.0 times the other days'"), f)
    }

    @Test fun expiryDaysAgainstTheRest() {
        val days = weekdays(30)
        val ex = days.filter { it.dayOfWeek == DayOfWeek.TUESDAY }.toSet()
        val a = Weekdays.answer(Weekdays.Q(null, true), Market.NIFTY, build(days, expiries = ex), today, after, ex)
        assertTrue(a.contains("6 were expiry days: median range 1.2"), a)
        assertTrue(a.contains("the last hour from 14:30 moved a median"), a)
        assertTrue(a.contains("Their last hour moved"), a)
        assertTrue(a.contains("I only know an expiry day from the option candles saved that day"), a)
        // None known: said honestly.
        val none = Weekdays.answer(Weekdays.Q(null, true), Market.NIFTY, build(days), today, after)
        assertTrue(none.contains("I know of 0 expiry days - too few"), none)
        // Every weekday: the expiries named with their weekday.
        val all = Weekdays.answer(Weekdays.Q(null, false), Market.NIFTY, build(days, expiries = ex), today, after, ex)
        assertTrue(all.contains("6 of these were expiry days (6 on a Tuesday)"), all)
    }

    @Test fun tooFewIsSaidHonestly() {
        val a = Weekdays.answer(Weekdays.Q(null, false), Market.NIFTY, build(weekdays(8)), today, after)
        assertTrue(a.startsWith("I have only 8 whole Nifty sessions"), a)
        val sat = Weekdays.answer(Weekdays.Q(DayOfWeek.SATURDAY, false), Market.NIFTY, build(weekdays(30)), today, after)
        assertTrue(sat.contains("does not trade on Saturdays"), sat)
        // A weekday with too few: said so beside the rest.
        val days = weekdays(30).filter { it.dayOfWeek != DayOfWeek.WEDNESDAY || it.isAfter(today.minusDays(10)) }
        val b = Weekdays.answer(Weekdays.Q(null, false), Market.NIFTY, build(days), today, after)
        assertTrue(b.contains("Wednesdays: only 1, too few to say"), b)
    }

    @Test fun todayIsSetBeside() {
        val days = weekdays(30)
        val bars = build(days) + session(today, 24_000.0, 120.0, 10.0, minutes = 120)
        val live = Weekdays.answer(Weekdays.Q(DayOfWeek.MONDAY, false), Market.NIFTY, bars, today, LocalDateTime.of(today, LocalTime.of(11, 15)))
        assertTrue(live.contains("Today, a Monday, Nifty's range so far is 0.50% against the median Monday's 0.8"), live)
        assertTrue(live.contains("still on"), live)
        // Another weekday asked: today is not set beside it.
        val fri = Weekdays.answer(Weekdays.Q(DayOfWeek.FRIDAY, false), Market.NIFTY, bars, today, LocalDateTime.of(today, LocalTime.of(11, 15)))
        assertFalse(fri.contains("Today,"), fri)
    }
}
