package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoundClosesTest {
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

    /** Whole sessions on [days], each closing at its [closes] entry (opening 100 points below it). */
    private fun build(days: List<LocalDate>, closes: List<Double>, minutes: Int = 375): List<Candle> {
        val out = ArrayList<Candle>()
        for ((d, close) in days.zip(closes)) {
            val open = close - 100
            for (i in 0 until minutes) {
                val c = if (i == minutes - 1) close else open
                out += Candle(d.atTime(9, 15).plusMinutes(i.toLong()), open, maxOf(open, c) + 1, minOf(open, c) - 1, c)
            }
        }
        return out
    }

    private val ADVICE = Regex("(?i)\\b(you should|i recommend|i suggest|buy|sell|go long|go short|will (rise|fall|go|break)|likely|expect|target)\\b")

    @Test fun theQuestions() {
        assertEquals(RoundCloses.Q(null), RoundCloses.asked("Does Nifty close near round numbers?"))
        assertEquals(RoundCloses.Q(null), RoundCloses.asked("Are round numbers a magnet for Nifty?"))
        assertEquals(RoundCloses.Q(1000.0), RoundCloses.asked("How often does BankNifty end near a round thousand?"))
        assertEquals(RoundCloses.Q(null), RoundCloses.asked("round number record"))
        assertEquals(RoundCloses.Q(null), RoundCloses.asked("nifty gol figure ke paas kitni baar band hota hai"))
        assertEquals(RoundCloses.Q(500.0), RoundCloses.asked("how often does sensex close near round 500 levels"))
        assertEquals(RoundCloses.Q(100.0), RoundCloses.asked("do nifty closes cluster at round hundreds"))
        // Not this record: a forecast, advice, one price, the expiry pin, a meaning, now.
        assertNull(RoundCloses.asked("will nifty close near a round number tomorrow"))
        assertNull(RoundCloses.asked("should I buy at round numbers"))
        assertNull(RoundCloses.asked("is 25000 a round number"))
        assertNull(RoundCloses.asked("does nifty pin to round numbers on expiry"))
        assertNull(RoundCloses.asked("what is a round number"))
        assertNull(RoundCloses.asked("is nifty near a round number now"))
        assertNull(RoundCloses.asked("how often does nifty close up"))
        assertNull(RoundCloses.asked("how often does gold close near round numbers"))
    }

    @Test fun theMarket() {
        assertEquals(Market.NIFTY, RoundCloses.market(emptyList()))
        assertEquals(Market.BANKNIFTY, RoundCloses.market(listOf(Market.BANKNIFTY)))
        assertNull(RoundCloses.market(listOf(Market.GOLD)))
        assertNull(RoundCloses.market(listOf(Market.VIX)))
        assertEquals(500.0, RoundCloses.step(Market.NIFTY))
        assertEquals(1000.0, RoundCloses.step(Market.SENSEX))
    }

    @Test fun tooFewDaysIsSaidPlainly() {
        val days = weekdays(10)
        val said = RoundCloses.answer(RoundCloses.Q(), Market.NIFTY, build(days, days.map { 25_000.0 }), today, today.atTime(11, 0))
        assertTrue("too few" in said && "10 whole sessions" in said, said)
    }

    @Test fun closesHeldAtRoundNumbersAreMoreThanChance() {
        // 40 sessions: 20 close 5 points off 25,000 or 25,500, 20 close 200 points off.
        val days = weekdays(40)
        val closes = (0 until 40).map { i -> if (i % 2 == 0) (if (i % 4 == 0) 25_005.0 else 25_495.0) else 25_200.0 + (i % 3) }
        val said = RoundCloses.answer(RoundCloses.Q(), Market.NIFTY, build(days, closes), today, today.atTime(11, 0))
        assertTrue("on 20 (50%)" in said, said)
        assertTrue("about 10%" in said, said)
        assertTrue("more often than chance" in said, said)
        assertTrue("every 500 points" in said, said)
        assertTrue("The last was" in said && "25,495.00" in said, said)
        assertTrue(", 10 times." in said, said)
        assertTrue(RoundCloses.NOTE in said, said)
        assertFalse(ADVICE.containsMatchIn(said), said)
    }

    @Test fun closesSpreadEvenlyShowNoPull() {
        // 50 sessions closing evenly across the step: 25,000 + 10 * i (i = 0..49) - 25,000 to 25,490.
        val days = weekdays(50)
        val closes = (0 until 50).map { 25_013.0 + 10 * it }
        val said = RoundCloses.answer(RoundCloses.Q(), Market.NIFTY, build(days, closes), today, today.atTime(11, 0))
        assertTrue("no clear pull" in said, said)
        assertFalse(ADVICE.containsMatchIn(said), said)
    }

    @Test fun todaySetBeside() {
        val days = weekdays(40)
        val bars = build(days, days.map { 25_200.0 }) + Candle(today.atTime(10, 0), 24_980.0, 24_990.0, 24_970.0, 24_985.0)
        val said = RoundCloses.answer(RoundCloses.Q(), Market.NIFTY, bars, today, today.atTime(10, 1))
        assertTrue("Now Nifty is at 24,985.00, 15 points below 25,000." in said, said)
        assertTrue("on 0 (0%)" in said, said)
    }
}
