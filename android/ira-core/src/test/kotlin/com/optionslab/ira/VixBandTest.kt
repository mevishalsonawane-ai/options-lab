package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VixBandTest {
    private val today = LocalDate.of(2026, 10, 7)   // a Wednesday
    /** India VIX at the square root of 252: one day's move is exactly 1% of the previous close. */
    private val vixAt = sqrt(252.0)

    /**
     * One session of 1-minute candles from 09:15 to [until]: from [pc] up to [hi]% at 11:00, down to [lo]% at 13:00 and on
     * to [close]% at 15:29 (all % of [pc]).
     */
    private fun session(d: LocalDate, pc: Double, hi: Double, lo: Double, close: Double, until: LocalTime = LocalTime.of(15, 29)): List<Candle> {
        val pts = listOf(LocalTime.of(9, 15) to 0.0, LocalTime.of(11, 0) to hi, LocalTime.of(13, 0) to lo, LocalTime.of(15, 29) to close)
        fun px(t: LocalTime): Double {
            val s = t.toSecondOfDay()
            for (i in 1 until pts.size) {
                val (a, pa) = pts[i - 1]; val (b, pb) = pts[i]
                if (s <= b.toSecondOfDay()) return pc * (1 + (pa + (pb - pa) * (s - a.toSecondOfDay()).toDouble() / (b.toSecondOfDay() - a.toSecondOfDay())) / 100)
            }
            return pc * (1 + close / 100)
        }
        val out = ArrayList<Candle>()
        var t = LocalTime.of(9, 15)
        while (!t.isAfter(until)) {
            val o = if (t == LocalTime.of(9, 15)) pc else px(t.minusMinutes(1))
            val c = px(t)
            out += Candle(d.atTime(t), o, maxOf(o, c), minOf(o, c), c)
            t = t.plusMinutes(1)
        }
        return out
    }

    private fun flat(d: LocalDate, v: Double): List<Candle> {
        val out = ArrayList<Candle>()
        var t = LocalTime.of(9, 15)
        while (!t.isAfter(LocalTime.of(15, 29))) { out += Candle(d.atTime(t), v, v, v, v); t = t.plusMinutes(1) }
        return out
    }

    /** The [n] weekdays before today, oldest first. */
    private fun weekdays(n: Int): List<LocalDate> {
        val out = ArrayList<LocalDate>()
        var d = today.minusDays(1)
        while (out.size < n) { if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) out += d; d = d.minusDays(1) }
        return out.reversed()
    }

    /**
     * A first day, then [n] days by turns against the close before (high, low, close in %): (+0.7, -0.2, +0.5) inside;
     * (+1.6, 0, +1.5) above, the high beyond; (+0.3, -1.2, -0.3) inside, the low beyond; (+0.1, -2.7, -2.5) below.
     */
    private fun nifty(n: Int): List<Candle> {
        val ds = weekdays(n + 1)
        val out = ArrayList<Candle>()
        out += session(ds[0], 25000.0, 0.2, -0.2, 0.0)
        var pc = 25000.0
        for (i in 1..n) {
            val (hi, lo, end) = when ((i - 1) % 4) {
                0 -> Triple(0.7, -0.2, 0.5)
                1 -> Triple(1.6, 0.0, 1.5)
                2 -> Triple(0.3, -1.2, -0.3)
                else -> Triple(0.1, -2.7, -2.5)
            }
            out += session(ds[i], pc, hi, lo, end)
            pc *= 1 + end / 100
        }
        return out
    }

    private fun vix(n: Int): List<Candle> = weekdays(n + 1).flatMap { flat(it, vixAt) }

    private val ADVICE = Regex("(?i)\\b(you should|i recommend|i suggest|buy|sell|go long|go short|will (rise|fall|go|break|move|stay)|likely|expect|target)\\b")

    @Test fun theQuestions() {
        assertEquals(VixBand.Q(1.0), VixBand.asked("how often does nifty stay within the vix expected move"))
        assertEquals(VixBand.Q(1.0), VixBand.asked("does nifty really move as much as vix implies"))
        assertEquals(VixBand.Q(1.0), VixBand.asked("how often does banknifty move more than vix implies"))
        assertEquals(VixBand.Q(1.0), VixBand.asked("does india vix usually overstate the move"))
        assertEquals(VixBand.Q(1.0), VixBand.asked("vix expected move record"))
        assertEquals(VixBand.Q(1.0), VixBand.asked("implied vs realised volatility for nifty"))
        assertEquals(VixBand.Q(1.0), VixBand.asked("vix ke expected move se nifty kitni baar bahar jata hai"))
        assertEquals(VixBand.Q(2.0), VixBand.asked("how often does nifty break twice the vix move"))
        assertEquals(VixBand.Q(2.0), VixBand.asked("how often does sensex close beyond 2 sigma of the vix implied move"))
        assertEquals(VixBand.Q(1.0), VixBand.asked("does vix overprice nifty's moves"))
        // The expected move itself, by expiry or today, VIX's level, its jumps, a forecast, advice, a meaning, gold are others'.
        for (q in listOf("what's the expected move", "expected move today", "expected move by expiry", "is vix high", "how high is vix",
                "when vix jumps how big is the next day", "will nifty stay within the expected move today", "vix percentile",
                "what is implied volatility", "how often does nifty fall 1% intraday", "does gold stay within its vix range usually",
                "how often is vix more than 20", "how often does vix move more than 5%", "should i sell a straddle if vix overstates the move",
                "is nifty inside the vix range now", "how is nifty", "how often does vix fall"))
            assertNull(VixBand.asked(q), q)
    }

    @Test fun theIndex() {
        assertEquals(Market.NIFTY, VixBand.market(emptyList()))
        assertEquals(Market.NIFTY, VixBand.market(listOf(Market.VIX)))
        assertEquals(Market.BANKNIFTY, VixBand.market(listOf(Market.VIX, Market.BANKNIFTY)))
        assertNull(VixBand.market(listOf(Market.GOLD)))
    }

    @Test fun theNormalShares() {
        assertEquals(68.27, VixBand.normalInside(1.0), 0.01)
        assertEquals(95.45, VixBand.normalInside(2.0), 0.01)
    }

    @Test fun theRecord() {
        val days = VixBand.past(nifty(40), vix(40), today)
        assertEquals(40, days.size)
        assertTrue(days.all { abs(it.band / it.prevClose - 0.01) < 1e-9 })
        val r = VixBand.record(days, 1.0)
        assertEquals(20, r.inside); assertEquals(10, r.above); assertEquals(10, r.below)
        assertEquals(30, r.touched); assertEquals(10, r.touchedUp); assertEquals(20, r.touchedDown)
        assertEquals(1.0, r.medianClose, 1e-6); assertEquals(1.4, r.medianReach, 1e-6)
        val r2 = VixBand.record(days, 2.0)
        assertEquals(30, r2.inside); assertEquals(0, r2.above); assertEquals(10, r2.below); assertEquals(10, r2.touched)

        val said = VixBand.answer(VixBand.Q(), Market.NIFTY, nifty(40), vix(40), today, today.atTime(16, 0))
        assertTrue(said.startsWith("Over the last 40 whole sessions of Nifty on this phone"), said)
        assertTrue(said.contains("against one day's VIX move - India VIX's close that evening over the square root of 252, a median ±1.00% of the price:"), said)
        assertTrue(said.contains("Nifty closed inside it on 20 of 40 (50%; a normal spread would put about 68% inside), above it on 10 and below it on 10."), said)
        assertTrue(said.contains("In the day, its high or low went beyond it on 30 (75%): above on 10, below on 20."), said)
        assertTrue(said.contains("The median close was 1.00 of one day's VIX move from the previous close, and the day's furthest point 1.40 of it."), said)
        assertTrue(said.contains("the moves that came were bigger than VIX had priced"), said)
        assertTrue(said.contains("-2.50%, 2.50 of that day's VIX move (VIX 15.87 the evening before)"), said)
        assertTrue(said.contains("only 40 sessions"), said)
        assertFalse(said.contains("rough yardstick"), said)
        assertFalse(said.contains("Today"), said)
        assertTrue(said.endsWith(VixBand.NOTE), said)
        assertFalse(ADVICE.containsMatchIn(said), said)

        val twice = VixBand.answer(VixBand.Q(2.0), Market.BANKNIFTY, nifty(40), vix(40), today, today.atTime(16, 0))
        assertTrue(twice.contains("against twice the day's VIX move"), twice)
        assertTrue(twice.contains("closed inside it on 30 of 40 (75%; a normal spread would put about 95% inside)"), twice)
        assertTrue(twice.contains("for BankNifty it is a rough yardstick"), twice)
    }

    @Test fun aDayWithoutVixTheEveningBeforeIsLeftOut() {
        val ds = weekdays(41)
        val noVix = vix(40).filter { it.t.toLocalDate() != ds[10] }
        assertEquals(39, VixBand.past(nifty(40), noVix, today).size)
    }

    @Test fun todayIsBesideTheRecord() {
        val past = nifty(40)
        val pc = past.last().c
        val bars = past + session(today, pc, 0.4, -1.3, -0.5, LocalTime.of(14, 0))
        val said = VixBand.answer(VixBand.Q(), Market.NIFTY, bars, vix(40), today, today.atTime(14, 0))
        assertEquals(40, VixBand.past(bars, vix(40), today).size)
        assertTrue(Regex("Today's band from India VIX's close of 15\\.87 on \\d+ \\w+ is ±\\d+ points around [\\d,.]+, [\\d,]+ to [\\d,]+; " +
            "Nifty is -0\\.9\\d% on the previous close, inside it, and in the day it went beyond it below\\.").containsMatchIn(said), said)
        val quiet = past + session(today, pc, 0.3, -0.4, 0.1, LocalTime.of(14, 0))
        val calm = VixBand.answer(VixBand.Q(), Market.NIFTY, quiet, vix(40), today, today.atTime(14, 0))
        assertTrue(calm.contains("inside it, and its high and low have stayed within it so far."), calm)
    }

    @Test fun tooFewSessionsAndHalfDays() {
        val said = VixBand.answer(VixBand.Q(), Market.NIFTY, nifty(10), vix(10), today, today.atTime(10, 0))
        assertTrue(said.startsWith("I have only 10 whole sessions"), said)
        assertTrue(said.contains("Boss"))
        val ds = weekdays(2)
        val half = session(ds[0], 25000.0, 0.2, -0.5, -0.3, LocalTime.of(12, 30)) + session(ds[1], 24900.0, 1.0, -1.0, 0.2)
        assertTrue(VixBand.past(half, vix(1), today).isEmpty())
        // No India VIX on the phone at all: nothing to measure against.
        assertTrue(VixBand.past(nifty(40), emptyList(), today).isEmpty())
    }

    @Test fun neverActsNorAnOrder() {
        for (q in listOf("how often does nifty stay within the vix expected move", "does india vix usually overstate the move",
                "vix ke expected move se nifty kitni baar bahar jata hai", "how often does nifty break twice the vix move", "vix expected move record")) {
            val p = Ask.parse(q)
            assertNull(p.order, q); assertNull(p.command, q)
            assertFalse(Bundle.acts(q), q)
        }
    }
}
