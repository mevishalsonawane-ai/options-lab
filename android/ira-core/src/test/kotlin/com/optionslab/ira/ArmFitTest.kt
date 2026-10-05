package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArmFitTest {
    private val today = LocalDate.of(2026, 10, 5)   // a Monday

    private fun weekdays(count: Int): List<LocalDate> {
        val out = ArrayList<LocalDate>()
        var d = today.minusDays(1)
        while (out.size < count) {
            if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) out += d
            d = d.minusDays(1)
        }
        return out.reversed()
    }

    private fun session(d: LocalDate, open: Double, close: Double, range: Double, minutes: Int = 375): List<Candle> =
        (0 until minutes).map { i ->
            val t = LocalDateTime.of(d, LocalTime.of(9, 15)).plusMinutes(i.toLong())
            val c = if (t.toLocalTime().isBefore(LocalTime.of(10, 15))) open else close
            if (t.toLocalTime() == LocalTime.of(9, 30)) Candle(t, c, c + range / 2, c - range / 2, c) else Candle(t, c, c, c, c)
        }

    private fun vix(d: LocalDate, level: Double) = (0 until 375).map { i ->
        val t = LocalDateTime.of(d, LocalTime.of(9, 15)).plusMinutes(i.toLong()); Candle(t, level, level, level, level) }

    private val days = weekdays(30)

    /** Every third day (i % 3 == 0) opens 0.5% up, ranges 0.5% with VIX 14; the rest open flat, range 1% with VIX 18. Today is like the first kind. */
    private fun data(todayMinutes: Int = 375, todayVix: Boolean = true): Pair<List<Candle>, List<Candle>> {
        val bars = ArrayList<Candle>(); val vx = ArrayList<Candle>()
        days.forEachIndexed { i, d ->
            if (i % 3 == 0) { bars += session(d, 24120.0, 24000.0, 120.0); vx += vix(d, 14.0) }
            else { bars += session(d, 24000.0, 24000.0, 240.0); vx += vix(d, 18.0) }
        }
        bars += session(today, 24120.0, 24200.0, 120.0, todayMinutes); if (todayVix) vx += vix(today, 14.0)
        return bars to vx
    }

    /** +1,000 on every day like today, -500 on the rest. */
    private val tested: Map<LocalDate, Double> = days.withIndex().associate { (i, d) -> d to if (i % 3 == 0) 1000.0 else -500.0 }

    @Test fun asksAreTakenAndOthersAreNot() {
        for (s in listOf("which of my arms suits today", "which arm suits today", "which bot fits today's conditions",
            "how do my arms do on days like today", "orb's record on days like this", "my bots on days like today",
            "which strategy suits today", "aaj ke din kaun sa bot suit karta hai", "aaj jaise din par mere arms",
            "which arms fit today", "how does range fade do on days like today")) assertTrue(ArmFit.asked(s), s)
        for (s in listOf("which arms suit this market", "is today like any past day", "which arm should i disarm",
            "why did orb lose today", "which arm will work today", "should i run orb today", "how are my bots doing",
            "how did days like today end", "what's the regime", "which arm suits tomorrow")) assertFalse(ArmFit.asked(s), s)
    }

    @Test fun bandsTodayAndSplitsEachRecord() {
        val (bars, vx) = data()
        val a = ArmFit.answer(listOf(ArmFit.Arm("ORB", true, tested, ArmFit.daily(listOf(days.last() to 200.0, days.last() to -50.0))),
            ArmFit.Arm("Range Fade", false, emptyMap(), emptyMap())), bars, vx, today, LocalDateTime.of(today, LocalTime.of(11, 0)))
        assertTrue("opened +0.50% from the previous close (a gap of 0.25-0.75%" in a, a)
        assertTrue("the lowest third of 29 past sessions" in a, a)
        assertTrue("India VIX stood at 14.00 (VIX 13 to 17)" in a, a)
        assertTrue("ORB (armed) - its backtest, one lot (29 days with candles on the phone, 1 more without)" in a, a)
        assertTrue("gaps of 0.25-0.75% like today +Rs 1,000 a day over 9 (9 up), the other 20 -Rs 500 a day (0 up)" in a, a)
        assertTrue("narrow starts like today +Rs 1,000 a day over 9 (9 up)" in a, a)
        assertTrue("VIX 13 to 17 like today +Rs 1,000 a day over 9 (9 up)" in a, a)
        assertTrue("like today on all three: +Rs 1,000 a day over 9 (9 up)" in a, a)
        assertTrue("On paper (1 day with candles on the phone): gaps of 0.25-0.75% like today only 0 days, the other 1 day" in a, a)
        assertTrue("1 arm not armed is left out." in a, a)
        assertFalse("Range Fade" in a, a)
        assertTrue(a.endsWith(ArmFit.NOTE), a)
    }

    @Test fun noneArmedReadsAllAndNoVixIsSaid() {
        val (bars, vx) = data(todayVix = false)
        val a = ArmFit.answer(listOf(ArmFit.Arm("ORB", false, tested, emptyMap())), bars, vx, today, LocalDateTime.of(today, LocalTime.of(12, 0)))
        assertTrue("None of your arms is armed right now, so here are all 1." in a, a)
        assertTrue("India VIX is left out" in a, a)
        assertTrue("like today on all both" !in a && "like today on all two" !in a, a)
        assertTrue("On paper: no traded days kept" in a, a)
    }

    @Test fun tooEarlyAndTooFewAreSaidHonestly() {
        val (bars, vx) = data(todayMinutes = 10)
        val arms = listOf(ArmFit.Arm("ORB", true, tested, emptyMap()))
        assertTrue("from 09:30" in ArmFit.answer(arms, bars, vx, today, LocalDateTime.of(today, LocalTime.of(9, 24))))
        val few = bars.filter { it.t.toLocalDate() >= days[20] }
        assertTrue("too few" in ArmFit.answer(arms, few, vx, today, LocalDateTime.of(today, LocalTime.of(11, 0))))
        assertEquals(ArmFit.NONE, ArmFit.answer(emptyList(), bars, vx, today, LocalDateTime.of(today, LocalTime.of(11, 0))))
    }

    @Test fun bandsAreFixed() {
        assertEquals(0, ArmFit.gapBand(-0.2)); assertEquals(1, ArmFit.gapBand(-0.3)); assertEquals(2, ArmFit.gapBand(0.75))
        assertEquals(0, ArmFit.vixBand(12.9)); assertEquals(1, ArmFit.vixBand(13.0)); assertEquals(2, ArmFit.vixBand(17.0))
    }
}
