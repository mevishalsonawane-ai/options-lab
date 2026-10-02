package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.time.LocalDate
import java.time.LocalDateTime

class StudyTest {
    /** A session of 5-minute candles from [open], moving [first15] in the first 15 minutes then [rest] by the close. */
    private fun session(d: LocalDate, open: Double, first15: Double, rest: Double): List<Candle> {
        val out = ArrayList<Candle>()
        var p = open
        for (i in 0 until 75) {
            val t = LocalDateTime.of(d, java.time.LocalTime.of(9, 15)).plusMinutes(5L * i)
            val step = if (i < 3) first15 / 3 else rest / 72
            val n = p + step
            out += Candle(t, p, maxOf(p, n) + 1, minOf(p, n) - 1, n)
            p = n
        }
        return out
    }

    private fun weekdays(n: Int): List<LocalDate> {
        var d = LocalDate.of(2024, 10, 1); val out = ArrayList<LocalDate>()
        while (out.size < n) { if (d.dayOfWeek.value <= 5) out += d; d = d.plusDays(1) }
        return out
    }

    @Test fun aSetupThatHeldInBothYearsIsKnowledgeAndAToday() {
        // Every day the first 15 minutes rise 0.3% and the day keeps going up: "f15up -> closed above 09:30" holds 100%.
        var p = 50_000.0
        val bars = weekdays(120).flatMap { d -> session(d, p, p * 0.003, p * 0.002).also { p = it.last().c } }
        val f = Study.run(Market.BANKNIFTY, bars)
        val up = f.single { it.key == "f15up" }
        assertEquals(120, up.days); assertEquals(1.0, up.rate, 1e-9); assertTrue(up.held)
        assertTrue(up.text().startsWith("BankNifty, the first 15 minutes up 0.2% or more (120 days in two years): it closed above its 09:30 price on 100%"), up.text())
        assertTrue(Study.today(f, bars).any { it.startsWith("Today: the first 15 minutes up") })
        assertTrue(Study.summary(f).last().contains("not a promise"))
    }

    @Test fun aCoinTossIsSaidToBeOne() {
        // Alternate days: up after the first 15 minutes, then down - half and half in both halves.
        var p = 50_000.0
        val bars = weekdays(120).mapIndexed { i, d -> session(d, p, p * 0.003, if (i % 2 == 0) p * 0.004 else -p * 0.008).also { p = it.last().c } }.flatten()
        val up = Study.run(Market.NIFTY, bars).single { it.key == "f15up" }
        assertTrue(!up.held); assertTrue(up.text().endsWith("a coin toss, no edge."))
    }

    @Test fun tooFewDaysGiveNothing() {
        var p = 50_000.0
        val bars = weekdays(20).flatMap { d -> session(d, p, p * 0.003, p * 0.002).also { p = it.last().c } }
        assertTrue(Study.run(Market.NIFTY, bars).none { it.key == "f15up" })
    }

    @Test fun beforeTheOpenOnlyWhatIsAlreadyKnownApplies() {
        // Every day rises over 1% and the next day rises again: "bigup" holds; before the open it applies, f15up not yet.
        var p = 50_000.0
        val ds = weekdays(121)
        val bars = ds.dropLast(1).flatMap { d -> session(d, p, p * 0.003, p * 0.012).also { p = it.last().c } }
        val f = Study.run(Market.NIFTY, bars)
        val next = Study.next(f, bars, ds.last())
        assertTrue(next.any { it.startsWith("Today is the day after a rise of 1% or more.") }, next.toString())
        assertTrue(next.none { "first 15 minutes" in it })
    }
}
