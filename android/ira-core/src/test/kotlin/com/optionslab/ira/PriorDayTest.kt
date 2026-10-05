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

class PriorDayTest {
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

    /**
     * How a session goes against the one before it (every session opens at 24,000 and, flat, trades 23,990 to 24,010):
     * EARLY_HOLD takes out the prior high at 09:45 and closes 30 above it; LATE_HOLD at 13:00 and holds; EARLY_FAIL takes it
     * out at 09:45 and closes back at the open; REVERSE takes it out at 09:45 and closes below the prior low; LOW_HOLD takes
     * out the prior low at 11:00 and holds; INSIDE stays within.
     */
    enum class Kind { EARLY_HOLD, LATE_HOLD, EARLY_FAIL, REVERSE, LOW_HOLD, INSIDE }

    private fun session(d: LocalDate, kind: Kind, minutes: Int = 375): List<Candle> {
        val open = 24_000.0
        val out = ArrayList<Candle>()
        for (i in 0 until minutes) {
            val t = LocalDateTime.of(d, LocalTime.of(9, 15)).plusMinutes(i.toLong())
            val tt = t.toLocalTime()
            val c = when (kind) {
                Kind.INSIDE -> open
                Kind.EARLY_HOLD -> if (tt.isBefore(LocalTime.of(9, 45))) open else open + 40
                Kind.LATE_HOLD -> if (tt.isBefore(LocalTime.of(13, 0))) open else open + 40
                Kind.EARLY_FAIL -> if (tt.isBefore(LocalTime.of(9, 45)) || !tt.isBefore(LocalTime.of(12, 0))) open else open + 40
                Kind.REVERSE -> if (tt.isBefore(LocalTime.of(9, 45))) open else if (tt.isBefore(LocalTime.of(12, 0))) open + 40 else open - 40
                Kind.LOW_HOLD -> if (tt.isBefore(LocalTime.of(11, 0))) open else open - 40
            }
            out += Candle(t, c, c + 10, c - 10, c)
        }
        return out
    }

    /** Sessions of [kinds], the first of them only the base for the second. */
    private fun build(kinds: List<Kind>): List<Candle> = weekdays(kinds.size).zip(kinds).flatMap { (d, k) -> session(d, k) }

    /**
     * Each moving session follows an INSIDE one (prior high 24,010, low 23,990), and the INSIDE one after it stays within
     * the moving one's range: 10 EARLY_HOLD, 4 LATE_HOLD, 3 EARLY_FAIL, 2 REVERSE, 5 LOW_HOLD.
     */
    private val mix: List<Kind> = (List(10) { Kind.EARLY_HOLD } + List(4) { Kind.LATE_HOLD } + List(3) { Kind.EARLY_FAIL } +
        List(2) { Kind.REVERSE } + List(5) { Kind.LOW_HOLD }).flatMap { listOf(Kind.INSIDE, it) }

    @Test fun theQuestionsAreReadAsTheRecord() {
        val q = PriorDay.asked("When Nifty takes out yesterday's high in the first hour, how often does it close above it?")
        assertEquals(PriorDay.Q(1, true), q)
        assertEquals(PriorDay.Q(1, false), PriorDay.asked("how often does nifty break the previous day's high"))
        assertEquals(PriorDay.Q(-1, false), PriorDay.asked("does banknifty usually hold below yesterday's low after breaking it"))
        assertEquals(PriorDay.Q(null, false), PriorDay.asked("PDH PDL record"))
        assertEquals(PriorDay.Q(null, false), PriorDay.asked("how often does nifty take out the prior day high or low"))
        assertEquals(PriorDay.Q(1, false), PriorDay.asked("kal ka high todne ke baad kitni baar upar band hota hai"))
        assertEquals(PriorDay.Q(-1, true), PriorDay.asked("how often is the previous day low broken in the first hour"))
        assertNotNull(PriorDay.asked("prior day high low stats for sensex"))
    }

    @Test fun aSingleDayAForecastAdviceOrAnAlertIsNotTheRecord() {
        for (s in listOf("what was yesterday's high", "did nifty break yesterday's high", "is nifty above yesterday's high today",
            "will nifty take out yesterday's high", "should I buy above yesterday's high", "how far is nifty from yesterday's high",
            "alert me when nifty breaks yesterday's high", "where is yesterday's low", "how often does nifty gap above yesterday's high",
            "how is today different from yesterday", "do opening range breakouts usually hold", "what is pdh",
            "how often does gold take out yesterday's high", "buy nifty if it breaks yesterday's high usually"))
            assertNull(PriorDay.asked(s), s)
    }

    @Test fun theIndexAskedAbout() {
        assertEquals(Market.NIFTY, PriorDay.market(emptyList()))
        assertEquals(Market.BANKNIFTY, PriorDay.market(listOf(Market.BANKNIFTY)))
        assertNull(PriorDay.market(listOf(Market.GOLD)))
        assertNull(PriorDay.market(listOf(Market.VIX)))
    }

    @Test fun eachSessionIsReadAgainstTheOneBeforeIt() {
        val days = PriorDay.past(build(mix), today)
        assertEquals(mix.size - 1, days.size)
        val d = days.first()   // EARLY_HOLD after INSIDE
        assertEquals(24_010.0, d.prevHigh); assertEquals(23_990.0, d.prevLow)
        assertEquals(LocalTime.of(9, 45), d.up.at)
        assertTrue(d.up.held); assertFalse(d.up.reversed); assertFalse(d.up.gapped)
        assertNull(d.down.at)
        val inside = days[1]   // INSIDE after EARLY_HOLD (23,990-24,050)
        assertNull(inside.up.at); assertNull(inside.down.at)
    }

    @Test fun theRecordIsCountedAndSaidAsAPastRecord() {
        val a = PriorDay.answer(PriorDay.Q(1, true), Market.NIFTY, build(mix), today, after)
        // 47 pairs: 19 took out the prior high (10 early held, 4 late held, 3 early failed, 2 reversed); 5 the prior low (and
        // the 2 reversals took out the prior low too).
        assertTrue(a.contains("Over the last 47 whole Nifty sessions"), a)
        assertTrue(a.contains("it took out the prior high on 19 (40%) and the prior low on 7 (15%); both on 2, neither on 23."), a)
        assertTrue(a.contains("Of the 19 that took out the prior high, the close held above it on 14 (74%) and ended below the prior low on 2 (11%); 0 opened above it."), a)
        assertTrue(a.contains("Taken out in the first hour (09:15 to 10:15) on 15, the close held above it on 10 (67%); only 4 came later."), a)
        assertTrue(a.contains("the median run beyond it was 0.17%"), a)
        assertTrue(a.endsWith(PriorDay.NOTE), a)
        assertFalse(a.contains("Of the 7 that took out the prior low"), a)
        val both = PriorDay.answer(PriorDay.Q(null, false), Market.NIFTY, build(mix), today, after)
        assertTrue(both.contains("Of the 7 that took out the prior low, the close held below it on 7 (100%)"), both)
        assertFalse(both.contains("first hour"), both)
    }

    @Test fun tooFewSessionsAreSaidHonestly() {
        val a = PriorDay.answer(PriorDay.Q(1, false), Market.NIFTY, build(List(6) { Kind.INSIDE }), today, after)
        assertTrue(a.startsWith("I have only 5 whole Nifty sessions"), a)
        val few = PriorDay.answer(PriorDay.Q(-1, false), Market.NIFTY, build(List(20) { Kind.INSIDE } + Kind.LOW_HOLD), today, after)
        assertTrue(few.contains("Only 1 session took out the prior low - too few"), few)
    }

    @Test fun halfDaysAreNotRead() {
        val bars = build(mix).filterNot { it.t.toLocalDate() == weekdays(mix.size)[5] && it.t.toLocalTime().isAfter(LocalTime.of(12, 0)) }
        // The half day is in two pairs, as the later day and as the earlier one: both go.
        assertEquals(mix.size - 3, PriorDay.past(bars, today).size)
    }

    @Test fun todayIsSetBesideAgainstYesterday() {
        val bars = build(mix) + session(today, Kind.EARLY_HOLD, minutes = 120)
        val a = PriorDay.answer(PriorDay.Q(1, false), Market.NIFTY, bars, today, LocalDateTime.of(today, LocalTime.of(11, 15)))
        // The last past session was LOW_HOLD (23,950-24,010).
        assertTrue(a.contains("Today Nifty, against 2 Oct's high 24,010.00 and low 23,950.00, took out the prior high at 09:45 and not the prior low, " +
            "and is above the prior high now at 24,040.00 - the session is still on."), a)
        // Today never counts in the record.
        assertTrue(a.contains("Over the last 47 whole"), a)
    }
}
