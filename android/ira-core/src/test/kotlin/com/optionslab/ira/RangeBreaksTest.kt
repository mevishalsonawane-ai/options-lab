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

class RangeBreaksTest {
    private val today = LocalDate.of(2026, 10, 5)   // a Monday
    private val after = LocalDateTime.of(2026, 10, 5, 18, 0)

    /** [count] weekdays before [today], oldest first. */
    private fun weekdays(count: Int): List<LocalDate> {
        val out = ArrayList<LocalDate>()
        var d = today.minusDays(1)
        while (out.size < count) {
            if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) out += d
            d = d.minusDays(1)
        }
        return out.reversed()
    }

    enum class Kind { HOLD_UP, FAIL_UP, REVERSE_UP, HOLD_DOWN, NONE }

    /**
     * One session on [d] opening at [open]: the first 15 minutes swing ±20; from 10:00 the [kind]'s path - up 40 (held),
     * up 40 then back to the open at 12:00 (failed), up 40 then down 40 at 13:00 (reversed), down 40 (held) or flat.
     */
    private fun session(d: LocalDate, open: Double, kind: Kind, minutes: Int = 375): List<Candle> {
        val out = ArrayList<Candle>()
        for (i in 0 until minutes) {
            val t = LocalDateTime.of(d, LocalTime.of(9, 15)).plusMinutes(i.toLong())
            val tt = t.toLocalTime()
            val c = when {
                i < 15 -> open + (if (i % 2 == 0) 20.0 else -20.0)
                tt.isBefore(LocalTime.of(10, 0)) -> open
                kind == Kind.NONE -> open
                kind == Kind.HOLD_DOWN -> open - 40
                kind == Kind.HOLD_UP -> open + 40
                kind == Kind.FAIL_UP -> if (tt.isBefore(LocalTime.NOON)) open + 40 else open
                else -> if (tt.isBefore(LocalTime.of(13, 0))) open + 40 else open - 40
            }
            out += Candle(t, if (i == 0) open else c, c, c, c)
        }
        return out
    }

    private fun build(kinds: List<Kind>): List<Candle> {
        val days = weekdays(kinds.size)
        return days.zip(kinds).flatMap { (d, k) -> session(d, 24_000.0, k) }
    }

    /** 20 sessions: 10 held up, 3 failed up, 2 reversed up, 3 held down, 2 never broke. */
    private val mix = List(10) { Kind.HOLD_UP } + List(3) { Kind.FAIL_UP } + List(2) { Kind.REVERSE_UP } + List(3) { Kind.HOLD_DOWN } + List(2) { Kind.NONE }

    @Test fun questionsAreUnderstood() {
        for (s in listOf("when nifty breaks its first 15-minute range, how often does it hold by the close?",
            "do opening range breakouts usually hold", "how often do orb breakouts fail", "opening range breakout record for banknifty",
            "how often does nifty stay inside the opening range all day", "what's the opening range breakout success rate",
            "opening range todne ke baad kitni baar tikta hai", "how often does the first hour range break hold",
            "when nifty breaks below the opening range how often does it close there", "orb breakout stats for sensex"))
            assertNotNull(RangeBreaks.asked(s), s)
        assertEquals(15, RangeBreaks.asked("do opening range breakouts usually hold")?.minutes)
        assertNull(RangeBreaks.asked("do opening range breakouts usually hold")?.dir)
        assertEquals(60, RangeBreaks.asked("how often does the first hour range break hold")?.minutes)
        assertEquals(30, RangeBreaks.asked("how often does a break of the first 30 minute range hold")?.minutes)
        assertEquals(-1, RangeBreaks.asked("when nifty breaks below the opening range how often does it close there")?.dir)
        assertEquals(1, RangeBreaks.asked("how often does an upside break of the opening range hold")?.dir)
        assertNull(RangeBreaks.asked("when nifty breaks its first 15-minute range, how often does it hold by the close?")?.dir)
    }

    @Test fun otherQuestionsAreLeftAlone() {
        for (s in listOf("what's the opening range", "did nifty break the opening range", "how is the opening range",
            "opening range of nifty", "is the orb arm behaving", "how is my orb arm doing", "start orb", "stop orb 5",
            "is orb running", "will nifty break the opening range", "should i buy the opening range breakout",
            "backtest the orb strategy", "what is an opening range breakout", "has nifty broken the opening range today",
            "how often does the gap fill", "how often does the opening range break hold for gold", "is the orb record good for my bot"))
            assertNull(RangeBreaks.asked(s), s)
    }

    @Test fun marketIsTheIndexAsked() {
        assertEquals(Market.NIFTY, RangeBreaks.market(emptyList()))
        assertEquals(Market.BANKNIFTY, RangeBreaks.market(listOf(Market.BANKNIFTY)))
        assertNull(RangeBreaks.market(listOf(Market.GOLD)))
        assertNull(RangeBreaks.market(listOf(Market.VIX)))
    }

    @Test fun eachSessionIsRead() {
        val days = weekdays(5)
        val kinds = listOf(Kind.HOLD_UP, Kind.FAIL_UP, Kind.REVERSE_UP, Kind.HOLD_DOWN, Kind.NONE)
        val bars = days.zip(kinds).flatMap { (d, k) -> session(d, 24_000.0, k) } + session(today.minusDays(40), 24_000.0, Kind.HOLD_UP, minutes = 100)
        val p = RangeBreaks.past(bars, today)
        assertEquals(5, p.size)                       // the part session is left out
        assertEquals(24_020.0, p[0].hi); assertEquals(23_980.0, p[0].lo)
        assertEquals(1, p[0].first); assertEquals(LocalTime.of(10, 0), p[0].at); assertTrue(p[0].held); assertFalse(p[0].both)
        assertEquals(1, p[1].first); assertFalse(p[1].held); assertFalse(p[1].reversed)
        assertTrue(p[2].both && p[2].reversed && !p[2].held)
        assertEquals(-1, p[3].first); assertTrue(p[3].held)
        assertEquals(0, p[4].first); assertNull(p[4].at)
        // Today's own session is never in the record.
        assertEquals(5, RangeBreaks.past(bars + session(today, 24_000.0, Kind.HOLD_UP), today).size)
    }

    @Test fun theRecordWithBothSides() {
        val a = RangeBreaks.answer(RangeBreaks.Q(null, 15), Market.NIFTY, build(mix), today, after)
        assertTrue(a.startsWith("Over the last 20 whole Nifty sessions on this phone"), a)
        assertTrue(a.contains("the first 15 minutes (09:15 to 09:30) as the opening range"), a)
        assertTrue(a.contains("it broke up first on 15, down first on 3 and never broke on 2."), a)
        assertTrue(a.contains("Of the 15 upside breaks first, the close held above the range high on 10 (67%); the low broke too later on 2 (13%), and on 2 (13%) it closed beyond the low"), a)
        assertTrue(a.contains("Only 3 downside breaks came first - too few"), a)
        assertTrue(a.contains("All told the first break held to the close on 13 of 18 (72%); the median first break came at 10:00."), a)
        assertTrue(a.contains("The median opening range was 0.17% of the open."), a)
        assertTrue(a.endsWith(RangeBreaks.NOTE), a)
    }

    @Test fun oneSideAsked() {
        val a = RangeBreaks.answer(RangeBreaks.Q(1, 15), Market.BANKNIFTY, build(mix), today, after)
        assertTrue(a.contains("Of the 15 upside breaks first"), a)
        assertFalse(a.contains("downside breaks"), a)
        assertFalse(a.contains("All told"), a)
    }

    @Test fun tooFewIsSaidHonestly() {
        val a = RangeBreaks.answer(RangeBreaks.Q(null, 15), Market.NIFTY, build(mix.take(8)), today, after)
        assertTrue(a.startsWith("I have only 8 whole Nifty sessions"), a)
    }

    @Test fun todayIsSetBeside() {
        val bars = build(mix) + session(today, 24_000.0, Kind.HOLD_UP, minutes = 120)
        val early = RangeBreaks.answer(RangeBreaks.Q(null, 15), Market.NIFTY, bars, today, LocalDateTime.of(today, LocalTime.of(9, 25)))
        assertTrue(early.contains("Today's opening range is not over yet: it runs to 09:30."), early)
        val live = RangeBreaks.answer(RangeBreaks.Q(null, 15), Market.NIFTY, bars, today, LocalDateTime.of(today, LocalTime.of(11, 15)))
        assertTrue(live.contains("Today Nifty's opening range was 23,980.00 to 24,020.00; it broke up first at 10:00, and is above the range now at 24,040.00 - the session is still on."), live)
        val flat = RangeBreaks.answer(RangeBreaks.Q(null, 15), Market.NIFTY, build(mix) + session(today, 24_000.0, Kind.NONE, minutes = 60),
            today, LocalDateTime.of(today, LocalTime.of(10, 15)))
        assertTrue(flat.contains("and it has not broken yet - the session is still on."), flat)
    }
}
