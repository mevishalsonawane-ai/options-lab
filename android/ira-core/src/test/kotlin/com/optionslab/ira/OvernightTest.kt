package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OvernightTest {
    private val today = LocalDate.of(2026, 10, 7)   // a Wednesday

    /** One session of 1-minute candles from 09:15 to [until]: a straight walk from [open] to [close] at 15:29. */
    private fun session(d: LocalDate, open: Double, close: Double, until: LocalTime = LocalTime.of(15, 29), from: LocalTime = LocalTime.of(9, 15)): List<Candle> {
        val first = LocalTime.of(9, 15).toSecondOfDay(); val last = LocalTime.of(15, 29).toSecondOfDay()
        fun px(t: LocalTime) = open + (close - open) * (t.toSecondOfDay() - first).toDouble() / (last - first)
        val out = ArrayList<Candle>()
        var t = from
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
     * A flat first day, then [n] days by turns: an open 0.4% up that slips 0.2% in the session, and an open 0.2% down that
     * gains 0.1% - except Mondays, which open 0.8% away from Friday's close (up or down by the same turn).
     */
    private fun nifty(n: Int): List<Candle> {
        val ds = weekdays(n + 1)
        val out = ArrayList<Candle>()
        out += session(ds[0], 25000.0, 25000.0)
        var pc = 25000.0
        for (i in 1..n) {
            val up = i % 2 == 1
            val night = if (ds[i].dayOfWeek == DayOfWeek.MONDAY) (if (up) 0.8 else -0.8) else if (up) 0.4 else -0.2
            val day = if (up) -0.2 else 0.1
            val open = pc * (1 + night / 100)
            val close = open * (1 + day / 100)
            out += session(ds[i], open, close)
            pc = close
        }
        return out
    }

    private val ADVICE = Regex("(?i)\\b(you should|i recommend|i suggest|buy|sell|go long|go short|will (rise|fall|go|break|move|gap)|likely|expect|target)\\b")

    @Test fun theQuestions() {
        for (q in listOf("does nifty make its moves overnight or during the day", "overnight vs intraday returns for banknifty",
                "how big are nifty's overnight moves usually", "is the trend made in the gaps or in market hours",
                "nifty ka move raat mein banta hai ya din mein", "overnight returns record for sensex", "how much of nifty's move comes overnight",
                "close to open vs open to close for nifty"))
            assertEquals(Overnight.Q(false), Overnight.asked(q), q)
        assertEquals(Overnight.Q(true), Overnight.asked("are weekend gaps bigger than weekday overnight moves"))
        assertEquals(Overnight.Q(true), Overnight.asked("how big is nifty's move over the weekend compared to an ordinary overnight"))
        // Today's gap, a fill, a forecast, advice, Boss's own holding, a what-if, a reason, a meaning, options, gold and VIX are others'.
        for (q in listOf("how big was today's gap", "do gaps usually fill during the session", "will nifty gap up tomorrow",
                "should i hold my put overnight", "is it safe to carry a straddle overnight", "what if nifty gaps down 1% overnight",
                "why did nifty gap up", "what is an overnight gap", "how much do nifty options lose overnight", "how much does gold move overnight",
                "how much does vix move overnight", "how often does nifty gap up and close higher in the session", "how is nifty",
                "do gap downs usually fill", "when nifty is down 1% intraday how often does it recover", "how often does nifty move 1% in the day"))
            assertNull(Overnight.asked(q), q)
    }

    @Test fun theIndex() {
        assertEquals(Market.NIFTY, Overnight.market(emptyList()))
        assertEquals(Market.BANKNIFTY, Overnight.market(listOf(Market.BANKNIFTY)))
        assertNull(Overnight.market(listOf(Market.GOLD)))
        assertNull(Overnight.market(listOf(Market.VIX)))
    }

    @Test fun theRecord() {
        val days = Overnight.past(nifty(40), today)
        assertEquals(40, days.size)
        val r = Overnight.record(days)
        assertEquals(20, r.nightUp); assertEquals(20, r.nightDown); assertEquals(20, r.dayUp); assertEquals(20, r.dayDown)
        assertEquals(0.15, r.medianDay, 1e-6)
        assertEquals(8, r.breaks)
        assertEquals(0.8, r.medianBreakNight!!, 1e-6)
        assertEquals(days.count { it.day.dayOfWeek == DayOfWeek.MONDAY }, r.breaks)
        assertTrue(days.filter { it.afterBreak }.all { it.day.dayOfWeek == DayOfWeek.MONDAY })
        // Each day's parts add up to its whole move.
        for (d in days) assertEquals((1 + d.totalPct / 100), (1 + d.nightPct / 100) * (1 + d.dayPct / 100), 1e-9)
        assertTrue(r.nightShare > 60, "${r.nightShare}")
        assertEquals(0.8, kotlin.math.abs(r.biggestNight.nightPct), 1e-6)

        val said = Overnight.answer(Overnight.Q(), Market.NIFTY, nifty(40), today, today.atTime(16, 0))
        assertTrue(said.startsWith("Over the last 40 whole sessions of Nifty on this phone"), said)
        assertTrue(said.contains("Nifty opened up on 20 (50%) and down on 20; the session closed above its open on 20 (50%) and below it on 20."), said)
        assertTrue(said.contains("the median session move 0.15%"), said)
        assertTrue(said.contains("The 8 opens after a weekend or holiday moved a median 0.80% from the close before, the 32 after an ordinary night"), said)
        assertTrue(said.contains("only 40 sessions"), said)
        assertTrue(said.endsWith(Overnight.NOTE), said)
        assertFalse(ADVICE.containsMatchIn(said), said)
        assertFalse(said.contains("Today"), said)
        // The weekend asked: its line comes first.
        val wk = Overnight.answer(Overnight.Q(true), Market.NIFTY, nifty(40), today, today.atTime(16, 0))
        assertTrue(wk.indexOf("opens after a weekend") < wk.indexOf("Added up"), wk)
    }

    @Test fun todaySoFarIsBesideTheRecord() {
        val past = nifty(40)
        val pc = past.last().c
        val bars = past + session(today, pc * 1.005, pc * 1.005 * 1.003, LocalTime.of(11, 0))
        val said = Overnight.answer(Overnight.Q(), Market.NIFTY, bars, today, today.atTime(11, 0))
        assertEquals(40, Overnight.past(bars, today).size)
        assertTrue(Regex("Today Nifty opened \\+0\\.50% on \\d+ \\w+'s close and is \\+0\\.\\d\\d% on its open so far\\.").containsMatchIn(said), said)
    }

    @Test fun aMissingSessionIsNeverBridged() {
        val ds = weekdays(41)
        val gap = (5 until 35).first { ds[it].dayOfWeek == DayOfWeek.WEDNESDAY }
        val bars = nifty(40).filter { it.t.toLocalDate() != ds[gap] }
        val days = Overnight.past(bars, today)
        assertEquals(38, days.size)
        assertTrue(days.none { it.day == ds[gap + 1] })
        // A holiday on the exchange calendar is no missing session: Thursday follows Tuesday, as an open after a holiday.
        val held = Overnight.past(bars, today) { it.dayOfWeek.value <= 5 && it != ds[gap] }
        assertEquals(39, held.size)
        assertTrue(held.single { it.day == ds[gap + 1] }.afterBreak)
        // Yesterday missing: today is not set against Monday's close.
        val past = nifty(40).filter { it.t.toLocalDate() != today.minusDays(1) }
        val pc = past.last().c
        val bars2 = past + session(today, pc * 1.005, pc * 1.006, LocalTime.of(11, 0))
        assertFalse(Overnight.answer(Overnight.Q(), Market.NIFTY, bars2, today, today.atTime(11, 0)).contains("Today"))
    }

    @Test fun tooFewSessionsAndHalfDays() {
        val few = Overnight.answer(Overnight.Q(), Market.NIFTY, nifty(10), today, today.atTime(16, 0))
        assertTrue(few.startsWith("I have only 10 whole sessions of Nifty with the one just before on the phone, Boss"), few)
        // A half day is not whole: neither it nor the day after it is counted.
        val ds = weekdays(41)
        val half = ds[20]
        val bars = nifty(40).filter { it.t.toLocalDate() != half || it.t.toLocalTime().isBefore(LocalTime.of(13, 0)) }
        val days = Overnight.past(bars, today)
        assertEquals(38, days.size)
        assertTrue(days.none { it.day == half || it.day == ds[21] })
        // A session starting late is not whole either.
        val late = nifty(40).filter { it.t.toLocalDate() != half || !it.t.toLocalTime().isBefore(LocalTime.of(10, 0)) }
        assertEquals(38, Overnight.past(late, today).size)
    }
}
