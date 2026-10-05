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

class InsideDaysTest {
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

    /** A session's shape: its [hi], [lo] and [close]. */
    data class Shape(val hi: Double, val lo: Double, val close: Double)

    // Wide (400), shifted wide (400), and a narrow one inside the shifted wide one (100).
    private val S1 = Shape(24_200.0, 23_800.0, 24_150.0)
    private val S2 = Shape(24_300.0, 23_900.0, 24_100.0)
    private val N = Shape(24_100.0, 24_000.0, 24_050.0)
    // A wide day, an inside day of half its range, and a day past the inside day's high that closes above it.
    private val W = Shape(24_200.0, 23_800.0, 24_000.0)
    private val I = Shape(24_100.0, 23_900.0, 24_000.0)
    private val E = Shape(24_250.0, 23_950.0, 24_220.0)

    /** Every bar trades a point either side of the middle; one bar makes the high, another the low, the last closes. */
    private fun session(d: LocalDate, s: Shape, minutes: Int = 375): List<Candle> {
        val mid = (s.hi + s.lo) / 2
        val out = ArrayList<Candle>()
        for (i in 0 until minutes) {
            val t = LocalDateTime.of(d, LocalTime.of(9, 15)).plusMinutes(i.toLong())
            val c = if (i == minutes - 1) s.close else mid
            val h = if (i == 10) s.hi else maxOf(c, mid) + 1
            val l = if (i == 20) s.lo else minOf(c, mid) - 1
            out += Candle(t, mid, h, l, c)
        }
        return out
    }

    private fun build(shapes: List<Shape>): List<Candle> = weekdays(shapes.size).zip(shapes).flatMap { (d, k) -> session(d, k) }

    /** 5 blocks of S1 S2 S1 S2 S1 S2 N, and an S1 after the last. */
    private val nr: List<Shape> = List(5) { listOf(S1, S2, S1, S2, S1, S2, N) }.flatten() + S1
    /** 6 blocks of W I E. */
    private val ins: List<Shape> = List(6) { listOf(W, I, E) }.flatten()

    @Test fun theQuestionsAreReadAsTheRecord() {
        assertEquals(InsideDays.Q(InsideDays.Kind.INSIDE), InsideDays.asked("After an inside day, how often does Nifty's range expand the next day?"))
        assertEquals(InsideDays.Q(InsideDays.Kind.NARROW), InsideDays.asked("how often does an NR7 day lead to a bigger day"))
        assertEquals(InsideDays.Q(InsideDays.Kind.INSIDE), InsideDays.asked("inside day record for banknifty"))
        assertEquals(InsideDays.Q(InsideDays.Kind.NARROW), InsideDays.asked("do narrow range days usually break out the next day"))
        assertEquals(InsideDays.Q(null), InsideDays.asked("how often do inside days and nr7 days expand"))
        assertEquals(InsideDays.Q(InsideDays.Kind.NARROW), InsideDays.asked("after the narrowest day in 7 how often is the next day wider for sensex"))
        assertNotNull(InsideDays.asked("inside day ke baad kitni baar bada move aata hai"))
    }

    @Test fun aSingleDayAForecastAdviceThePatternOrAnotherReaderIsNotTheRecord() {
        for (s in listOf("will tomorrow expand after the inside day", "will today be an inside day", "was yesterday an inside bar",
            "should I buy after an inside day", "how often does an inside bar break out", "what is an inside day",
            "alert me after an inside day", "how often does gold expand after an inside day", "inside day",
            "how often do inside days on fridays expand", "narrow range day?", "is the range narrow today?",
            "how often does nifty expand", "my inside day trades"))
            assertNull(InsideDays.asked(s), s)
    }

    @Test fun oneNamedDayIsAskedOfApartFromTheRecord() {
        // Routing round 14: "was yesterday an NR7 day?" - one session, answered directly (never Boss's own history).
        val one = InsideDays.One.LAST
        assertEquals(InsideDays.Q(InsideDays.Kind.NARROW, one), InsideDays.asked("was yesterday an nr7 day"))
        assertEquals(InsideDays.Q(InsideDays.Kind.INSIDE, one), InsideDays.asked("was yesterday an inside day"))
        assertEquals(InsideDays.Q(InsideDays.Kind.INSIDE, one), InsideDays.asked("kal inside day tha kya"))
        assertEquals(InsideDays.Q(InsideDays.Kind.NARROW, one), InsideDays.asked("kya kal nr7 tha"))
        assertEquals(InsideDays.Q(InsideDays.Kind.INSIDE, one), InsideDays.asked("was the last session an inside day"))
        assertEquals(InsideDays.Q(InsideDays.Kind.INSIDE, InsideDays.One.TODAY), InsideDays.asked("is today an inside day"))
        assertEquals(InsideDays.Q(InsideDays.Kind.NARROW, InsideDays.One.WEEKDAY, DayOfWeek.FRIDAY), InsideDays.asked("was friday an nr7 day"))
        assertEquals(InsideDays.Q(InsideDays.Kind.INSIDE, one), InsideDays.asked("was yesterday an inside day for banknifty"))

        val shapes = nr.dropLast(1)   // ... S1 S2 S1 S2 S1 S2 N, N on Fri 2 Oct
        val a = InsideDays.answer(InsideDays.asked("was yesterday an nr7 day")!!, Market.NIFTY, build(shapes), today, after)
        assertEquals("Boss, the last whole Nifty session was Fri 2 Oct. It was an NR7 day: its range of 100.00 points was the narrowest of its own and the six before it " +
            "(the narrowest of those six was 400.00). It was an inside day: its high 24,100.00 and low 24,000.00 were both within Thu 1 Oct's high 24,300.00 and low 23,900.00. " +
            "Ask what usually follows an NR7 day for the record.", a)
        val thu = InsideDays.answer(InsideDays.asked("was thursday an inside day")!!, Market.NIFTY, build(shapes), today, after)
        assertTrue(thu.startsWith("Boss, the newest whole Nifty session on that weekday was Thu 1 Oct. It was not an inside day: its high 24,300.00 went above Wed 30 Sep's 24,200.00."), thu)
        assertTrue(thu.contains("It was not an NR7 day: its range of 400.00 points was wider than") && thu.contains("100.00, the narrowest of the six before it."), thu)
        val live = build(shapes) + session(today, Shape(24_080.0, 24_010.0, 24_050.0), minutes = 120)
        val t = InsideDays.answer(InsideDays.asked("is today an inside day")!!, Market.NIFTY, live, today, LocalDateTime.of(today, LocalTime.of(11, 15)))
        assertEquals("Boss, Nifty today (Mon 5 Oct) so far. It is an inside day: its high 24,080.00 and low 24,010.00 are both within Fri 2 Oct's high 24,100.00 and low 24,000.00. " +
            "It is an NR7 day so far: its range of 70.00 points is the narrowest of its own and the six before it (the narrowest of those six was 100.00). " +
            "The session is still on, so its high, low and range can still change. Ask what usually follows an inside day for the record.", t)
        val none = InsideDays.answer(InsideDays.asked("is today an inside day")!!, Market.NIFTY, build(shapes), today, LocalDateTime.of(today, LocalTime.of(8, 30)))
        assertTrue(none.startsWith("Nifty hasn't traded today yet, Boss"), none)
    }

    @Test fun theIndexAskedAbout() {
        assertEquals(Market.NIFTY, InsideDays.market(emptyList()))
        assertEquals(Market.BANKNIFTY, InsideDays.market(listOf(Market.BANKNIFTY)))
        assertNull(InsideDays.market(listOf(Market.GOLD)))
        assertNull(InsideDays.market(listOf(Market.VIX)))
    }

    @Test fun insideAndNarrowDaysAreFound() {
        val days = InsideDays.past(build(nr), today)
        assertEquals(nr.size, days.size)
        assertEquals(400.0, days[0]!!.range)
        assertFalse(InsideDays.inside(days, 0)); assertFalse(InsideDays.inside(days, 1)); assertFalse(InsideDays.inside(days, 2))
        assertTrue(InsideDays.inside(days, 6)); assertTrue(InsideDays.narrow(days, 6))
        assertFalse(InsideDays.narrow(days, 5)); assertFalse(InsideDays.narrow(days, 7))
        assertEquals(5, InsideDays.afters(days, InsideDays.Kind.NARROW).size)
        val a = InsideDays.afters(days, InsideDays.Kind.INSIDE).first()
        assertTrue(a.wider); assertEquals(4.0, a.ratio); assertTrue(a.above); assertTrue(a.below); assertTrue(a.closedAbove)
        assertEquals(nr.size - 1, InsideDays.all(days).size)
    }

    @Test fun theRecordIsCountedAndSetAgainstEveryDay() {
        val a = InsideDays.answer(InsideDays.Q(InsideDays.Kind.NARROW), Market.NIFTY, build(nr), today, after)
        assertTrue(a.startsWith("Over the last 36 whole Nifty sessions on this phone"), a)
        assertTrue(a.contains("the six before it: 5 NR7 days."), a)
        assertTrue(a.contains("After the 5 narrow-range (NR7) days, the next session's range was wider than the setup's on 5 (100%), a median 4.00 times it; " +
            "it traded above the setup's high only on 0 (0%), below its low only on 0 (0%), beyond both on 5 and stayed inside on 0; " +
            "it closed above the setup's high on 5 (100%) and below its low on 0 (0%)."), a)
        assertTrue(a.contains("For comparison, after every whole session on the phone the next one's range was wider on 5 of 35 (14%), a median 1.00 times"), a)
        assertTrue(a.contains("The last whole session, Fri 2 Oct, was neither an inside day nor the narrowest of its 7"), a)
        assertTrue(a.endsWith(InsideDays.NOTE), a)
        assertFalse(a.contains("Today"), a)

        val b = InsideDays.answer(InsideDays.Q(InsideDays.Kind.INSIDE), Market.NIFTY, build(ins), today, after)
        assertTrue(b.contains(": 6 inside days."), b)
        assertTrue(b.contains("After the 6 inside days, the next session's range was wider than the setup's on 6 (100%), a median 1.50 times it; " +
            "it traded above the setup's high only on 6 (100%), below its low only on 0 (0%)"), b)
        val c = InsideDays.answer(InsideDays.Q(null), Market.NIFTY, build(ins), today, after)
        assertTrue(c.contains(": 6 inside days and 0 NR7 days."), c)
        assertTrue(c.contains("No narrow-range (NR7) days had a whole session after them"), c)
    }

    @Test fun tooFewAreSaidHonestly() {
        val a = InsideDays.answer(InsideDays.Q(null), Market.NIFTY, build(nr.take(10)), today, after)
        assertTrue(a.startsWith("I have only 10 whole Nifty sessions"), a)
        val b = InsideDays.answer(InsideDays.Q(InsideDays.Kind.NARROW), Market.NIFTY, build(nr.take(22)), today, after)
        assertTrue(b.contains("Only 3 of the narrow-range (NR7) days had a whole session after them on the phone - too few"), b)
    }

    @Test fun aHalfDayBreaksTheChain() {
        val dates = weekdays(nr.size)
        val bars = build(nr).filterNot { it.t.toLocalDate() == dates[5] && it.t.toLocalTime().isAfter(LocalTime.of(13, 0)) }
        val days = InsideDays.past(bars, today)
        assertNull(days[5])
        assertFalse(InsideDays.inside(days, 6)); assertFalse(InsideDays.narrow(days, 6))
        assertEquals(nr.size - 3, InsideDays.all(days).size)
    }

    @Test fun todayIsSetBesideTheLastSession() {
        val shapes = nr.dropLast(1)
        val live = build(shapes) + session(today, Shape(24_080.0, 24_010.0, 24_050.0), minutes = 120)
        val a = InsideDays.answer(InsideDays.Q(null), Market.NIFTY, live, today, LocalDateTime.of(today, LocalTime.of(11, 15)))
        assertTrue(a.contains("The last whole session, Fri 2 Oct, was an inside day and the narrowest of its 7 (NR7) (high 24,100.00, low 24,000.00, range 100.00 points). " +
            "Today Nifty so far has ranged 70.00 points (0.70 times) and traded inside its range - the session is still on."), a)
        assertTrue(a.contains("Over the last 35 whole"), a)
    }
}
