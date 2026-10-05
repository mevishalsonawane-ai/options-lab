package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SplitDaysTest {
    private val today = LocalDate.of(2026, 10, 5)   // a Monday

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

    /** Whole sessions on [days], each closing [moves]% from the session before (the first day at [start]). */
    private fun build(days: List<LocalDate>, moves: List<Double>, start: Double, minutes: Int = 375): List<Candle> {
        var prev = start
        val out = ArrayList<Candle>()
        for ((d, m) in days.zip(moves)) {
            val close = prev * (1 + m / 100)
            for (i in 0 until minutes) {
                val c = if (i == minutes - 1) close else prev
                out += Candle(d.atTime(9, 15).plusMinutes(i.toLong()), prev, maxOf(prev, c) + 1, minOf(prev, c) - 1, c)
            }
            prev = close
        }
        return out
    }

    private val ADVICE = Regex("(?i)\\b(you should|i recommend|i suggest|buy|sell|go long|go short|will (rise|fall|go|break)|likely|expect|target)\\b")

    /**
     * 31 sessions (30 days with the day before): 6 split days (Nifty up 0.5%, BankNifty down 0.4%; 4 of them Nifty rose,
     * 2 the other way), each followed by a day both rose; the other 18 days both rose, flat Nifty on none.
     */
    private val pattern: List<Pair<Double, Double>> = run {
        val out = ArrayList<Pair<Double, Double>>()
        out += 0.0 to 0.0   // the first session: only a base
        repeat(4) { out += 0.5 to -0.4; out += 0.3 to 0.3 }
        repeat(2) { out += -0.5 to 0.4; out += 0.3 to 0.3 }
        repeat(18) { out += 0.2 to 0.25 }
        out
    }
    private val days = weekdays(pattern.size)
    private val nifty = build(days, pattern.map { it.first }, 25_000.0)
    private val bank = build(days, pattern.map { it.second }, 55_000.0)

    @Test fun theQuestions() {
        assertEquals(SplitDays.Q(Market.NIFTY, Market.BANKNIFTY), SplitDays.asked("How often do Nifty and BankNifty close opposite ways?"))
        assertNotNull(SplitDays.asked("nifty banknifty divergence record"))
        assertNotNull(SplitDays.asked("what happens the day after nifty and banknifty diverge"))
        assertEquals(SplitDays.Q(Market.NIFTY, Market.BANKNIFTY), SplitDays.asked("how often does banknifty go the other way from nifty"))
        assertEquals(SplitDays.Q(Market.NIFTY, Market.SENSEX), SplitDays.asked("how often does sensex diverge"))
        assertEquals(SplitDays.Q(Market.NIFTY, Market.BANKNIFTY), SplitDays.asked("how often do the indices go opposite ways"))
        assertNotNull(SplitDays.asked("nifty aur banknifty kitni baar ulte chalte hain"))
        assertNotNull(SplitDays.asked("how many days did nifty and finnifty end in opposite directions"))
        // Not this: today alone (Together), a forecast, his own book, an indicator's divergence, a span, the last time, gold.
        assertNull(SplitDays.asked("is banknifty moving with nifty"))
        assertNull(SplitDays.asked("are nifty and banknifty diverging today"))
        assertNull(SplitDays.asked("will nifty and banknifty diverge tomorrow"))
        assertNull(SplitDays.asked("should i trade the nifty banknifty divergence"))
        assertNull(SplitDays.asked("how often does rsi divergence work on nifty"))
        assertNull(SplitDays.asked("how many times did nifty and banknifty diverge this month"))
        assertNull(SplitDays.asked("when did nifty and banknifty last split"))
        assertNull(SplitDays.asked("how often do gold and nifty go opposite ways"))
        assertNull(SplitDays.asked("what does divergence mean"))
    }

    @Test fun thePair() {
        val q = SplitDays.Q(Market.NIFTY, Market.BANKNIFTY)
        assertEquals(Market.NIFTY to Market.BANKNIFTY, SplitDays.market(emptyList(), q))
        assertEquals(Market.FINNIFTY to Market.SENSEX, SplitDays.market(listOf(Market.SENSEX, Market.FINNIFTY), q))
        assertEquals(Market.NIFTY to Market.SENSEX, SplitDays.market(listOf(Market.SENSEX), q))
        assertNull(SplitDays.market(listOf(Market.GOLD), q))
    }

    @Test fun theRecord() {
        val past = SplitDays.past(nifty, bank, today)
        assertEquals(30, past.size)
        assertEquals(6, past.count { it.split })
        val said = SplitDays.answer(Market.NIFTY, Market.BANKNIFTY, nifty, bank, today, today.atTime(18, 0))
        assertTrue("Over the last 30 days" in said, said)
        assertTrue("opposite sides of their previous closes on 6 (20%)" in said, said)
        assertTrue("On 4 of those Nifty rose while BankNifty fell, on 2 the other way round" in said, said)
        assertTrue("the two moved the same way on 6 of 6 (100%" in said, said)
        assertTrue("split again on 0" in said, said)
        assertTrue("both rose on 6 and both fell on 0" in said, said)
        assertTrue(said.endsWith(SplitDays.NOTE), said)
        assertFalse(ADVICE.containsMatchIn(said), said)
    }

    @Test fun tooFewDaysIsSaidPlainly() {
        val few = weekdays(8)
        val said = SplitDays.answer(Market.NIFTY, Market.BANKNIFTY, build(few, List(8) { 0.3 }, 25_000.0), build(few, List(8) { -0.3 }, 55_000.0), today, today.atTime(18, 0))
        assertTrue("I have only 7 days" in said && "too few" in said, said)
        // A short day (not whole) is never counted.
        val short = build(days, pattern.map { it.first }, 25_000.0, minutes = 200)
        assertTrue("I have only 0 days" in SplitDays.answer(Market.NIFTY, Market.BANKNIFTY, short, bank, today, today.atTime(18, 0)))
        assertTrue("Name two different" in SplitDays.answer(Market.NIFTY, Market.NIFTY, nifty, nifty, today, today.atTime(18, 0)))
    }

    @Test fun todayIsSetBeside() {
        val lastN = nifty.last().c; val lastB = bank.last().c
        val n = nifty + build(listOf(today), listOf(0.4), lastN, minutes = 60)
        val b = bank + build(listOf(today), listOf(-0.3), lastB, minutes = 60)
        val said = SplitDays.answer(Market.NIFTY, Market.BANKNIFTY, n, b, today, today.atTime(10, 15))
        assertTrue("Today so far Nifty is +0.40% and BankNifty -0.30% from their previous closes: on opposite sides." in said, said)
        // Today's own session is never in the record.
        assertEquals(30, SplitDays.past(n, b, today).size)
    }
}
