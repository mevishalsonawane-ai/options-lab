package com.optionslab.engine.gold

import com.optionslab.engine.orb.Bar
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GoldDipTest {
    private val day = LocalDate.of(2026, 9, 29)          // a Tuesday

    private fun bar(t: LocalDateTime, o: Double, c: Double) = Bar(t, o, maxOf(o, c) + 1, minOf(o, c) - 1, c)

    /** 30-minute candles from 00:00, each opening at the last close and moving by the given step. */
    private fun thirties(steps: List<Double>, from: LocalDateTime = day.atStartOfDay()): List<Bar> {
        var px = 2000.0
        return steps.mapIndexed { i, d -> val o = px; px += d; bar(from.plusMinutes(30L * i), o, px) }
    }

    /** 1-hour candles folded from 30-minute ones. */
    private fun hours(t: List<Bar>) = t.chunked(2).map { (a, b) -> Bar(a.start, a.open, maxOf(a.high, b.high), minOf(a.low, b.low), b.close) }

    @Test fun minutesFoldIntoHalfHours() {
        val t = day.atTime(10, 0)
        val ones = (0 until 60).map { m -> bar(t.plusMinutes(m.toLong()), 2000.0 + m, 2001.0 + m) } + bar(day.atTime(21, 10), 1.0, 1.0)
        val th = GoldDip.thirty(ones)
        assertEquals(listOf(t, t.plusMinutes(30)), th.map { it.start })             // the 21:10 break minute is dropped
        assertEquals(2000.0, th[0].open); assertEquals(2030.0, th[0].close); assertEquals(2060.0, th[1].close)
        assertEquals(day.atTime(10, 30), GoldDip.slot(day.atTime(10, 59)))
        assertEquals(1, GoldDip.completed(th, t.plusMinutes(59)).size)
    }

    @Test fun twoGreenHoursThenADipThatTurnsUpIsASignal() {
        // 16 quiet candles, then two green hours (4 green halves), a red half and a green half.
        val steps = List(16) { if (it % 2 == 0) 1.0 else -1.0 } + listOf(2.0, 2.0, 2.0, 2.0, -1.0, 1.5)
        val th = thirties(steps)
        val hrs = hours(th)
        val s = assertNotNull(GoldDip.signal(th, hrs))
        assertEquals(th.last().start, s.bar)
        assertEquals(GoldTrend.atr(th, GoldDip.ATR_LENGTH).last(), s.atr, 1e-9)
        // Without the turn up, or after a red hour, or with too little history: nothing.
        assertNull(GoldDip.signal(th.dropLast(1) + bar(th.last().start, th.last().open, th.last().open - 1), hrs))
        val redHour = thirties(List(16) { if (it % 2 == 0) 1.0 else -1.0 } + listOf(2.0, 2.0, -2.0, -1.0, -1.0, 1.5))
        assertNull(GoldDip.signal(redHour, hours(redHour)))
        assertNull(GoldDip.signal(th.takeLast(6), hrs))
        assertNull(GoldDip.signal(th, hrs.take(1)))
    }

    @Test fun noSignalOnTheCandleBeforeTheBreak() {
        val steps = List(16) { if (it % 2 == 0) 1.0 else -1.0 } + listOf(2.0, 2.0, 2.0, 2.0, -1.0, 1.5)
        val th = thirties(steps, day.atTime(10, 0))                  // its last candle starts at 20:30
        assertEquals(GoldDip.LAST_BEFORE_BREAK, th.last().start.toLocalTime())
        assertNull(GoldDip.signal(th, hours(thirties(steps, day.atTime(10, 0)))))
    }

    @Test fun theLockAndTheTimeLimits() {
        assertNull(GoldDip.lockStop(2000.0, 2009.0, 10.0))
        assertEquals(2010.0 - 20.0, GoldDip.lockStop(2000.0, 2010.0, 10.0))
        assertNull(GoldDip.lockStop(2000.0, 2050.0, 0.0))
        val buy = day.atTime(10, 10)
        assertEquals(day.atTime(20, 55), GoldDip.cutAfter(buy))
        assertEquals(day.plusDays(1).atTime(20, 55), GoldDip.cutAfter(day.atTime(22, 40)))
        assertNull(GoldDip.timeExit(buy, buy.plusHours(7).plusMinutes(59)))
        assertEquals("dip_time", GoldDip.timeExit(buy, buy.plusHours(8)))
        assertEquals("dip_break", GoldDip.timeExit(day.atTime(14, 0), day.atTime(20, 55)))
        assertEquals("dip_break", GoldDip.timeExit(day.atTime(14, 0), day.plusDays(3).atTime(9, 0)))   // the phone was off
    }
}
