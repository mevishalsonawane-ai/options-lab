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

class FirstMoveTest {
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
     * Every session opens at 24,000 and each bar trades 10 either side of its close. [Kind.first]: the price from 09:15 to
     * 09:44 against the open; [Kind.rest]: the price from 09:45 to the close.
     */
    enum class Kind(val first: Double, val rest: Double) {
        UP_MATCH(60.0, 150.0), UP_BIG(120.0, 200.0), UP_FADE(60.0, -100.0), DOWN_MATCH(-60.0, -30.0), FLAT(0.0, 100.0), UP_FLATCLOSE(60.0, 5.0)
    }

    private fun session(d: LocalDate, kind: Kind, minutes: Int = 375): List<Candle> {
        val open = 24_000.0
        val out = ArrayList<Candle>()
        for (i in 0 until minutes) {
            val t = LocalDateTime.of(d, LocalTime.of(9, 15)).plusMinutes(i.toLong())
            val c = if (t.toLocalTime().isBefore(LocalTime.of(9, 45))) open + kind.first else open + kind.rest
            out += Candle(t, if (i == 0) open else c, c + 10, c - 10, c)
        }
        return out
    }

    private fun build(kinds: List<Kind>): List<Candle> = weekdays(kinds.size).zip(kinds).flatMap { (d, k) -> session(d, k) }

    /** 6 UP_MATCH, 5 UP_BIG, 3 UP_FADE, 4 DOWN_MATCH, 2 FLAT, 1 UP_FLATCLOSE. */
    private val mix: List<Kind> = List(6) { Kind.UP_MATCH } + List(5) { Kind.UP_BIG } + List(3) { Kind.UP_FADE } +
        List(4) { Kind.DOWN_MATCH } + List(2) { Kind.FLAT } + listOf(Kind.UP_FLATCLOSE)

    @Test fun theQuestionsAreReadAsTheRecord() {
        assertEquals(FirstMove.Q(30, null), FirstMove.asked("How often does the first 30 minutes direction match the day's close?"))
        assertEquals(FirstMove.Q(30, null), FirstMove.asked("does the opening move usually decide the day"))
        assertEquals(FirstMove.Q(30, 1), FirstMove.asked("when nifty is up in the first half hour how often does it close up"))
        assertEquals(FirstMove.Q(30, -1), FirstMove.asked("when banknifty is down in the first half hour how often does it close up"))
        assertEquals(FirstMove.Q(15, null), FirstMove.asked("how often does the first 15 minutes direction hold till the close"))
        assertEquals(FirstMove.Q(60, null), FirstMove.asked("does the first hour usually set the direction of the day"))
        assertEquals(FirstMove.Q(30, null), FirstMove.asked("first move record for sensex"))
        assertNotNull(FirstMove.asked("pehle aadhe ghante ki direction se din ka close kitni baar milta hai"))
    }

    @Test fun aSingleDayAForecastAdviceOrAnotherReaderIsNotTheRecord() {
        for (s in listOf("how much did nifty move in the first half hour", "will the first move hold today", "should I buy after the first 30 minutes",
            "is the first half hour usually the busiest", "how often is the high made in the first half hour", "how often does nifty break its first 30 minute range",
            "how often does the opening range breakout hold", "how often does the gap hold till the close", "does the first move usually hold on expiry days",
            "alert me if the first move reverses", "how often does gold's first move match the close", "how often does nifty close up",
            "what is the opening drive", "did the first move hold today", "how often does the last hour reverse"))
            assertNull(FirstMove.asked(s), s)
    }

    @Test fun theIndexAskedAbout() {
        assertEquals(Market.NIFTY, FirstMove.market(emptyList()))
        assertEquals(Market.BANKNIFTY, FirstMove.market(listOf(Market.BANKNIFTY)))
        assertNull(FirstMove.market(listOf(Market.GOLD)))
        assertNull(FirstMove.market(listOf(Market.VIX)))
    }

    @Test fun eachSessionIsReadFromItsOpenToTheFirstMovesEndAndTheClose() {
        val days = FirstMove.past(build(mix), today, 30)
        assertEquals(mix.size, days.size)
        val d = days.first()   // UP_MATCH
        assertEquals(24_000.0, d.open); assertEquals(24_060.0, d.at); assertEquals(24_150.0, d.close)
        assertEquals(1, d.first); assertEquals(1, d.match); assertTrue(d.carried)
        assertEquals(-1, days[11].match)   // UP_FADE
        val down = days[14]   // DOWN_MATCH
        assertEquals(-1, down.first); assertEquals(1, down.match); assertFalse(down.carried)
        assertEquals(0, days[18].first)   // FLAT
        assertEquals(0, days[20].match)   // UP_FLATCLOSE: the close within 0.05%
        assertEquals(24_060.0, FirstMove.past(build(mix), today, 15).first().at)
    }

    @Test fun theRecordIsCountedAndSaidAsAPastRecord() {
        val a = FirstMove.answer(FirstMove.Q(), Market.NIFTY, build(mix), today, after)
        assertTrue(a.contains("Over the last 21 whole Nifty sessions"), a)
        assertTrue(a.contains("the first 30 minutes went up on 15, down on 4, and stayed within 0.05% of the open on 2"), a)
        assertTrue(a.contains("On the 19 days with a first move, the day closed the first move's way on 15 (79%), the other way on 3 (16%) " +
            "and within 0.05% of the open on 1; the rest of the day carried on beyond the first move's end, its way, on 11 (58%)."), a)
        assertTrue(a.contains("After an up first move the close was up on 11 of 15 (73%); only 4 down first moves, too few to set apart."), a)
        assertTrue(a.contains("all 21 sessions closed up on 13 and down on 7, so if the first move had nothing to do with the close about 56% of these would have matched anyway."), a)
        assertTrue(a.contains("When the first 30 minutes moved 0.40% or more (5 days) the close matched on 5 (100%); when it moved less (14 days) on 10 (71%)."), a)
        assertTrue(a.contains("the first 30 minutes moved a median 0.25% either way"), a)
        assertTrue(a.endsWith(FirstMove.NOTE), a)
        assertFalse(a.contains("Today"), a)
        val up = FirstMove.answer(FirstMove.Q(30, 1), Market.NIFTY, build(mix), today, after)
        assertTrue(up.contains("On the 15 days the first 30 minutes went up, the day closed the first move's way on 11 (73%)"), up)
        val down = FirstMove.answer(FirstMove.Q(30, -1), Market.NIFTY, build(mix), today, after)
        assertTrue(down.contains("Only 4 days the first 30 minutes went down - too few"), down)
    }

    @Test fun tooFewSessionsAreSaidHonestly() {
        val a = FirstMove.answer(FirstMove.Q(), Market.NIFTY, build(List(6) { Kind.UP_MATCH }), today, after)
        assertTrue(a.startsWith("I have only 6 whole Nifty sessions"), a)
    }

    @Test fun halfDaysAreNotRead() {
        val bars = build(mix).filterNot { it.t.toLocalDate() == weekdays(mix.size)[5] && it.t.toLocalTime().isAfter(LocalTime.of(13, 0)) }
        assertEquals(mix.size - 1, FirstMove.past(bars, today, 30).size)
    }

    @Test fun todayIsSetBeside() {
        val live = build(mix) + session(today, Kind.UP_FADE, minutes = 76)
        val a = FirstMove.answer(FirstMove.Q(), Market.NIFTY, live, today, LocalDateTime.of(today, LocalTime.of(10, 31)))
        assertTrue(a.contains("Today the first 30 minutes went up (+0.25% to 24,060.00 at 09:45), and Nifty is now -0.42% from the open at 23,900.00, " +
            "the other way - the session is still on."), a)
        val early = build(mix) + session(today, Kind.UP_MATCH, minutes = 20)
        val b = FirstMove.answer(FirstMove.Q(), Market.NIFTY, early, today, LocalDateTime.of(today, LocalTime.of(9, 35)))
        assertTrue(b.contains("Today Nifty is +0.25% from the open at 24,060.00; the first 30 minutes are not over in the candles yet."), b)
    }
}
