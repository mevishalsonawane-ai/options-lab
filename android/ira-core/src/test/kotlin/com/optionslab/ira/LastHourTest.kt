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

class LastHourTest {
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
     * Every session opens at 24,000 and each bar trades 10 either side of its close. [Kind.at]: the price from 10:00 to
     * 14:29 above (or below) the open; [Kind.last]: the price from 14:30 to the close.
     * UP_CONT +300 then +350 (a bigger day continued); UP_REV +300 then +250 (a bigger day reversed); UP_SMALL +100 then +150;
     * DOWN_UNDO -100 then +20 (reversed and undid the day); FLAT 0 then 0 (no direction); UP_FLAT +100 then +105 (flat).
     */
    enum class Kind(val at: Double, val last: Double) {
        UP_CONT(300.0, 350.0), UP_REV(300.0, 250.0), UP_SMALL(100.0, 150.0), DOWN_UNDO(-100.0, 20.0), FLAT(0.0, 0.0), UP_FLAT(100.0, 105.0)
    }

    private fun session(d: LocalDate, kind: Kind, minutes: Int = 375): List<Candle> {
        val open = 24_000.0
        val out = ArrayList<Candle>()
        for (i in 0 until minutes) {
            val t = LocalDateTime.of(d, LocalTime.of(9, 15)).plusMinutes(i.toLong())
            val tt = t.toLocalTime()
            val c = when {
                tt.isBefore(LocalTime.of(10, 0)) -> open
                tt.isBefore(LocalTime.of(14, 30)) -> open + kind.at
                else -> open + kind.last
            }
            out += Candle(t, if (i == 0) open else c, c + 10, c - 10, c)
        }
        return out
    }

    private fun build(kinds: List<Kind>): List<Candle> = weekdays(kinds.size).zip(kinds).flatMap { (d, k) -> session(d, k) }

    /** 6 UP_CONT, 2 UP_REV, 4 UP_SMALL, 3 DOWN_UNDO, 3 FLAT, 2 UP_FLAT. */
    private val mix: List<Kind> = List(6) { Kind.UP_CONT } + List(2) { Kind.UP_REV } + List(4) { Kind.UP_SMALL } +
        List(3) { Kind.DOWN_UNDO } + List(3) { Kind.FLAT } + List(2) { Kind.UP_FLAT }

    @Test fun theQuestionsAreReadAsTheRecord() {
        assertEquals(LastHour.Q(null), LastHour.asked("How often does the last hour continue the day's direction?"))
        assertEquals(LastHour.Q(null), LastHour.asked("does nifty usually reverse in the last hour"))
        assertEquals(LastHour.Q(1), LastHour.asked("on up days does banknifty usually extend in the closing hour"))
        assertEquals(LastHour.Q(-1), LastHour.asked("how often does the final hour reverse on down days"))
        assertEquals(LastHour.Q(null), LastHour.asked("last hour record for sensex"))
        assertEquals(LastHour.Q(null), LastHour.asked("power hour behaviour"))
        assertNotNull(LastHour.asked("aakhri ghante mein kitni baar palat jata hai"))
    }

    @Test fun aSingleDayAForecastAdviceOrAnotherReaderIsNotTheRecord() {
        for (s in listOf("how much did banknifty move in the last hour", "will the last hour reverse today", "should I sell in the last hour",
            "is the last hour usually volatile", "which index is leading in the last hour", "did nifty reverse in the last hour",
            "how often does the last hour reverse on fridays", "does the last hour usually reverse on expiry days",
            "alert me if the last hour reverses", "how often does gold reverse in the last hour", "how often does nifty reverse",
            "is the last hour usually the busiest", "what is power hour"))
            assertNull(LastHour.asked(s), s)
    }

    @Test fun theIndexAskedAbout() {
        assertEquals(Market.NIFTY, LastHour.market(emptyList()))
        assertEquals(Market.BANKNIFTY, LastHour.market(listOf(Market.BANKNIFTY)))
        assertNull(LastHour.market(listOf(Market.GOLD)))
        assertNull(LastHour.market(listOf(Market.VIX)))
    }

    @Test fun eachSessionIsReadFromItsOpenTo1430AndOn() {
        val days = LastHour.past(build(mix), today)
        assertEquals(mix.size, days.size)
        val d = days.first()   // UP_CONT
        assertEquals(24_000.0, d.open); assertEquals(24_300.0, d.at); assertEquals(24_350.0, d.close)
        assertEquals(1, d.dir); assertEquals(1, d.turn); assertTrue(d.extended); assertFalse(d.undid)
        val rev = days[6]   // UP_REV
        assertEquals(-1, rev.turn); assertFalse(rev.extended)
        val undo = days[12]   // DOWN_UNDO
        assertEquals(-1, undo.dir); assertEquals(-1, undo.turn); assertTrue(undo.undid)
        assertEquals(0, days[15].dir)   // FLAT
        assertEquals(0, days[18].turn)   // UP_FLAT: within 0.05%
    }

    @Test fun theRecordIsCountedAndSaidAsAPastRecord() {
        val a = LastHour.answer(LastHour.Q(null), Market.NIFTY, build(mix), today, after)
        assertTrue(a.contains("Over the last 20 whole Nifty sessions"), a)
        assertTrue(a.contains("14 were up by 14:30, 3 down, and 3 within 0.10% of the open"), a)
        assertTrue(a.contains("On the 17 days with a direction, the last hour continued the day's way on 10 (59%), went against it on 5 (29%) " +
            "and stayed within 0.05% on 2; it undid the whole day (closing on the other side of the open) on 3 (18%), " +
            "and set a new extreme of the day the day's way on 12 (71%)."), a)
        assertTrue(a.contains("On the 8 that had moved 1.00% or more by 14:30, the last hour continued on 6 (75%) and reversed on 2 (25%); " +
            "on the 9 smaller ones it continued on 4 (44%) and reversed on 3 (33%)."), a)
        assertTrue(a.contains("It moved a median 0.21% when it continued"), a)
        assertTrue(a.endsWith(LastHour.NOTE), a)
        assertFalse(a.contains("Today"), a)
        val up = LastHour.answer(LastHour.Q(1), Market.NIFTY, build(mix), today, after)
        assertTrue(up.contains("On the 14 up days, the last hour continued the day's way on 10 (71%), went against it on 2 (14%)"), up)
        assertTrue(up.contains("set a new high of the day the day's way on 12 (86%)"), up)
        val down = LastHour.answer(LastHour.Q(-1), Market.NIFTY, build(mix), today, after)
        assertTrue(down.contains("Only 3 down days - too few"), down)
    }

    @Test fun tooFewSessionsAreSaidHonestly() {
        val a = LastHour.answer(LastHour.Q(null), Market.NIFTY, build(List(6) { Kind.UP_CONT }), today, after)
        assertTrue(a.startsWith("I have only 6 whole Nifty sessions"), a)
    }

    @Test fun halfDaysAreNotRead() {
        val bars = build(mix).filterNot { it.t.toLocalDate() == weekdays(mix.size)[5] && it.t.toLocalTime().isAfter(LocalTime.of(13, 0)) }
        assertEquals(mix.size - 1, LastHour.past(bars, today).size)
    }

    @Test fun todayIsSetBeside() {
        val live = build(mix) + session(today, Kind.UP_SMALL, minutes = 331)
        val a = LastHour.answer(LastHour.Q(null), Market.NIFTY, live, today, LocalDateTime.of(today, LocalTime.of(14, 46)))
        assertTrue(a.contains("Today Nifty was +0.42% from the open at 14:30 (24,100.00), and the last hour so far has moved +0.21% to 24,150.00, " +
            "with the day - the session is still on."), a)
        assertTrue(a.contains("Over the last 20 whole"), a)
        val early = build(mix) + session(today, Kind.DOWN_UNDO, minutes = 120)
        val b = LastHour.answer(LastHour.Q(null), Market.NIFTY, early, today, LocalDateTime.of(today, LocalTime.of(11, 15)))
        assertTrue(b.contains("Today Nifty is -0.42% from the open at 23,900.00; the last hour has not begun."), b)
    }
}
