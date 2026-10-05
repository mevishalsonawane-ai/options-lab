package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComebacksTest {
    private val today = LocalDate.of(2026, 10, 7)   // a Wednesday

    /**
     * One session of 1-minute candles from 09:15 to [until]: a straight walk from [open] to [ext] (reached on the minute
     * [at]) and on to [close] at 15:29 (or to wherever the walk is at [until]).
     */
    private fun session(d: LocalDate, open: Double, ext: Double, at: LocalTime, close: Double, until: LocalTime = LocalTime.of(15, 29)): List<Candle> {
        val first = LocalTime.of(9, 15).toSecondOfDay(); val mid = at.toSecondOfDay(); val last = LocalTime.of(15, 29).toSecondOfDay()
        fun px(t: LocalTime): Double {
            val s = t.toSecondOfDay()
            return if (s <= mid) open + (ext - open) * (s - first).toDouble() / (mid - first)
            else ext + (close - ext) * (s - mid).toDouble() / (last - mid)
        }
        val out = ArrayList<Candle>()
        var t = LocalTime.of(9, 15)
        while (!t.isAfter(until)) {
            val o = if (t == LocalTime.of(9, 15)) open else px(t.minusMinutes(1))
            val c = px(t)
            out += Candle(d.atTime(t), o, maxOf(o, c), minOf(o, c), c)
            t = t.plusMinutes(1)
        }
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
     * A flat first day, then [n] days by turns, each against the close before it: a fall to -1.5% by 10:30 that ends
     * +0.2%; a fall to -1.2% by 13:00 that ends -1.1%; a rally to +1.3% by 11:00 that ends +0.3%; a quiet day (-0.3% at
     * noon, ending +0.1%).
     */
    private fun nifty(n: Int): List<Candle> {
        val ds = weekdays(n + 1)
        val out = ArrayList<Candle>()
        out += session(ds[0], 25000.0, 24990.0, LocalTime.of(12, 0), 25000.0)
        var pc = 25000.0
        for (i in 1..n) {
            val (ext, at, end) = when ((i - 1) % 4) {
                0 -> Triple(-1.5, LocalTime.of(10, 30), 0.2)
                1 -> Triple(-1.2, LocalTime.of(13, 0), -1.1)
                2 -> Triple(1.3, LocalTime.of(11, 0), 0.3)
                else -> Triple(-0.3, LocalTime.of(12, 0), 0.1)
            }
            val close = pc * (1 + end / 100)
            out += session(ds[i], pc, pc * (1 + ext / 100), at, close)
            pc = close
        }
        return out
    }

    private val ADVICE = Regex("(?i)\\b(you should|i recommend|i suggest|buy|sell|go long|go short|will (rise|fall|go|break|move|recover)|likely|expect|target)\\b")

    @Test fun theQuestions() {
        assertEquals(Comebacks.Q(-1, 1.0), Comebacks.asked("when nifty is down 1% intraday how often does it recover"))
        assertEquals(Comebacks.Q(-1, 1.5), Comebacks.asked("how often does banknifty bounce back after falling 1.5% in the day"))
        assertEquals(Comebacks.Q(-1, null), Comebacks.asked("how often does nifty bounce back up after an intraday fall"))
        assertEquals(Comebacks.Q(1, 1.0), Comebacks.asked("does a 1% intraday rally usually hold"))
        assertEquals(Comebacks.Q(1, 1.0), Comebacks.asked("when sensex is up 1 percent in the day how often does it give it back"))
        assertEquals(Comebacks.Q(-1, null), Comebacks.asked("intraday recovery record for nifty"))
        assertEquals(Comebacks.Q(null, null), Comebacks.asked("comeback record for banknifty"))
        assertEquals(Comebacks.Q(-1, 1.0), Comebacks.asked("1% girne ke baad nifty kitni baar recover karta hai"))
        assertEquals(Comebacks.Q(1, 1.0), Comebacks.asked("nifty 1% upar jaane ke baad kitni baar wapas aata hai"))
        // Today's move, a reason, a forecast, advice, Boss's own book, a what-if, the gap, the last hour, options, gold and VIX are others'.
        for (q in listOf("is nifty recovering today", "why did nifty recover", "will nifty recover tomorrow", "should i hold my put if nifty falls 1%",
                "what if nifty falls 1%", "how often does a gap down fill", "does the last hour usually reverse", "how often does nifty fall 1% intraday",
                "do nifty puts recover after a 1% fall", "does gold usually recover after a 1% fall", "how often does vix fall 1% and recover",
                "how is nifty", "has nifty recovered from the low", "how often does nifty recover", "how many days in a row has nifty risen"))
            assertNull(Comebacks.asked(q), q)
    }

    @Test fun theIndex() {
        assertEquals(Market.NIFTY, Comebacks.market(emptyList()))
        assertEquals(Market.BANKNIFTY, Comebacks.market(listOf(Market.BANKNIFTY)))
        assertNull(Comebacks.market(listOf(Market.GOLD)))
        assertNull(Comebacks.market(listOf(Market.VIX)))
    }

    @Test fun theRecord() {
        val days = Comebacks.past(nifty(40), today, 1.0)
        assertEquals(40, days.size)
        val down = Comebacks.side(days, -1, 1.0)
        assertEquals(20, down.touched); assertEquals(10, down.back); assertEquals(10, down.half); assertEquals(10, down.held)
        assertEquals(10, down.morning); assertEquals(10, down.morningBack); assertEquals(10, down.afternoon); assertEquals(0, down.afternoonBack)
        assertEquals(LocalTime.of(11, 14), down.medianAt)
        val up = Comebacks.side(days, 1, 1.0)
        assertEquals(10, up.touched); assertEquals(0, up.back); assertEquals(10, up.half); assertEquals(0, up.held)

        val said = Comebacks.answer(Comebacks.Q(-1, null), Market.NIFTY, nifty(40), today, today.atTime(16, 0))
        assertTrue(said.startsWith("Over the last 40 whole sessions of Nifty on this phone"), said)
        assertTrue(said.contains("Nifty fell 1% or more below the previous close in the day on 20 of them (50%), first reaching it at 11:14 on the median day."), said)
        assertTrue(said.contains("Of those, 10 ended back above the previous close (50%), 10 won back at least half of the day's fall (50%), and 10 ended still 1% or more down (50%); the median such day ended -0.45% on the previous close."), said)
        assertTrue(said.contains("Falls reached before noon ended back above it on 10 of 10, those reached after noon on 0 of 10."), said)
        assertFalse(said.contains("rose 1%"), said)
        assertTrue(said.endsWith(Comebacks.NOTE), said)
        assertFalse(ADVICE.containsMatchIn(said), said)
        // Nothing today on the phone: no line for it.
        assertFalse(said.contains("Today"), said)

        // Both sides when no side is asked; a rally's record is said its own way.
        val both = Comebacks.answer(Comebacks.Q(null, null), Market.NIFTY, nifty(40), today, today.atTime(16, 0))
        assertTrue(both.contains("Nifty rose 1% or more above the previous close in the day on 10 of them (25%), first reaching it at 10:36 on the median day."), both)
        assertTrue(both.contains("Of those, 0 ended back below the previous close (0%), 10 gave back at least half of the day's rise (100%)"), both)
        assertTrue(both.contains("only 10 days"), both)
    }

    @Test fun aBiggerSizeAskedIsCountedSo() {
        val days = Comebacks.past(nifty(40), today, 1.4)
        val down = Comebacks.side(days, -1, 1.4)
        assertEquals(10, down.touched)
        val said = Comebacks.answer(Comebacks.Q(-1, 1.4), Market.NIFTY, nifty(40), today, today.atTime(16, 0))
        assertTrue(said.contains("fell 1.4% or more below"), said)
    }

    @Test fun todaySoFarIsBesideTheRecord() {
        val past = nifty(40)
        val pc = past.last().c
        val bars = past + session(today, pc, pc * 0.989, LocalTime.of(10, 0), pc * 0.98, LocalTime.of(11, 0))
        val withToday = Comebacks.answer(Comebacks.Q(-1, null), Market.NIFTY, bars, today, today.atTime(11, 0))
        assertEquals(40, Comebacks.past(bars, today, 1.0).size)
        assertTrue(Regex("Today Nifty fell 1% below it at 09:56, and is -1\\.\\d\\d% on the previous close\\.").containsMatchIn(withToday), withToday)
        val quiet = past + session(today, pc, pc * 0.996, LocalTime.of(10, 0), pc * 1.0, LocalTime.of(11, 0))
        val calm = Comebacks.answer(Comebacks.Q(-1, null), Market.NIFTY, quiet, today, today.atTime(11, 0))
        assertTrue(calm.contains("Today Nifty has not moved 1% either way from the previous close so far"), calm)
    }

    @Test fun aMissingSessionIsNeverBridged() {
        // A Wednesday missing on the phone: Thursday is never set against Tuesday's close (round 22 review).
        val ds = weekdays(41)
        val gap = (5 until 35).first { ds[it].dayOfWeek == DayOfWeek.WEDNESDAY }
        val bars = nifty(40).filter { it.t.toLocalDate() != ds[gap] }
        val days = Comebacks.past(bars, today, 1.0)
        assertEquals(38, days.size)
        assertTrue(days.none { it.day == ds[gap + 1] })
        // A holiday on the exchange calendar is no missing session: Thursday follows Tuesday.
        val held = Comebacks.past(bars, today, 1.0) { it.dayOfWeek.value <= 5 && it != ds[gap] }
        assertEquals(39, held.size)
        assertTrue(held.any { it.day == ds[gap + 1] })
        // Yesterday missing: today's line is not set against Monday's close.
        val past = nifty(40).filter { it.t.toLocalDate() != today.minusDays(1) }
        val pc = past.last().c
        val bars2 = past + session(today, pc, pc * 0.989, LocalTime.of(10, 0), pc * 0.98, LocalTime.of(11, 0))
        assertFalse(Comebacks.answer(Comebacks.Q(-1, null), Market.NIFTY, bars2, today, today.atTime(11, 0)).contains("Today"))
    }

    @Test fun tooFewSessionsAndHalfDays() {
        val said = Comebacks.answer(Comebacks.Q(-1, null), Market.NIFTY, nifty(10), today, today.atTime(10, 0))
        assertTrue(said.startsWith("I have only 10 whole sessions"), said)
        assertTrue(said.contains("Boss"))
        // A half session on the phone is no whole day, and the day after it has no whole one before it.
        val ds = weekdays(2)
        val half = session(ds[0], 25000.0, 24700.0, LocalTime.of(10, 0), 24800.0, LocalTime.of(12, 30)) +
            session(ds[1], 24800.0, 24500.0, LocalTime.of(10, 0), 24900.0)
        assertTrue(Comebacks.past(half, today, 1.0).isEmpty())
    }

    @Test fun neverActsNorAnOrder() {
        for (q in listOf("when nifty is down 1% intraday how often does it recover", "1% girne ke baad nifty kitni baar recover karta hai",
                "does a 1% intraday rally usually hold", "comeback record for banknifty")) {
            val p = Ask.parse(q)
            assertNull(p.order, q); assertNull(p.command, q)
            assertFalse(Bundle.acts(q), q)
        }
    }
}
