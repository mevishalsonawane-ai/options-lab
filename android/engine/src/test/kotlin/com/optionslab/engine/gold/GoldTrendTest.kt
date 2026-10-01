package com.optionslab.engine.gold

import com.optionslab.engine.orb.Bar
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoldTrendTest {
    private val monday = LocalDate.of(2026, 9, 28)

    private fun bar(t: LocalDateTime, o: Double, c: Double, wick: Double = 2.0) = Bar(t, o, maxOf(o, c) + wick, minOf(o, c) - wick, c)

    /** 4-hour candles in a row from Monday 00:00, closes as given. */
    private fun fours(closes: List<Double>): List<Bar> = closes.mapIndexed { i, c ->
        bar(monday.atStartOfDay().plusHours(4L * i), if (i == 0) c else closes[i - 1], c)
    }

    @Test fun hoursFoldIntoFourHourCandlesFromMidnight() {
        val d = monday.atStartOfDay()
        val hours = listOf(
            bar(d.withHour(19), 100.0, 101.0), bar(d.withHour(20), 101.0, 103.0),
            bar(d.withHour(22), 103.0, 99.0, wick = 5.0), bar(d.withHour(23), 99.0, 100.0),
            bar(d.plusDays(1), 100.0, 102.0),
        )
        val c = GoldTrend.chart(hours)
        assertEquals(listOf(d.withHour(16), d.withHour(20), d.plusDays(1)), c.map { it.start })
        val night = c[1]                                         // 20:00 + 22:00 + 23:00 after the break
        assertEquals(101.0, night.open); assertEquals(100.0, night.close); assertEquals(108.0, night.high); assertEquals(94.0, night.low)
        assertEquals(d.withHour(20), GoldTrend.slot(d.withHour(23).plusMinutes(59)))
        // The 20:00 candle has closed at midnight, not before.
        assertEquals(1, GoldTrend.completed(c, d.withHour(23).plusMinutes(59)).size)
        assertEquals(2, GoldTrend.completed(c, d.plusDays(1)).size)
    }

    @Test fun atrIsWildersAverageSeededWithTheFirstRange() {
        val b = fours(listOf(100.0, 110.0, 105.0))
        val a = GoldTrend.atr(b, 2)
        assertEquals(4.0, a[0], 1e-9)                               // high - low of a flat candle with 2 wicks
        assertEquals(4.0 + (14.0 - 4.0) / 2, a[1], 1e-9)            // range 98..112
        assertEquals(9.0 + (9.0 - 9.0) / 2, a[2], 1e-9)             // range 103..112 against the 110 close: 9
    }

    @Test fun theTrendNeedsARunInThenFollowsThePrice() {
        assertNull(GoldTrend.state(fours(List(GoldTrend.MIN_BARS - 1) { 2000.0 })))
        val rising = fours(List(60) { 2000.0 + 10 * it })
        val up = assertNotNull(GoldTrend.state(rising))
        assertTrue(up.up)
        assertTrue(up.line < rising.last().close)
        assertTrue(up.atr > 0)
        val falling = fours(List(60) { 2600.0 - 10 * it })
        assertFalse(assertNotNull(GoldTrend.state(falling)).up)
        // Up, then a crash far below the line: down.
        val crash = fours(List(60) { 2000.0 + 10 * it } + listOf(2300.0, 2200.0))
        assertFalse(assertNotNull(GoldTrend.state(crash)).up)
        // A small dip inside the bands keeps it up.
        val dip = fours(List(60) { 2000.0 + 10 * it } + listOf(2585.0))
        assertTrue(assertNotNull(GoldTrend.state(dip)).up)
    }

    @Test fun theProfitLockStartsAtOneAtrAndGivesBackFour() {
        assertNull(GoldTrend.lockStop(entry = 2000.0, peak = 2019.0, atr = 20.0))
        assertEquals(2020.0 - 80.0, GoldTrend.lockStop(2000.0, 2020.0, 20.0))
        assertEquals(2150.0 - 80.0, GoldTrend.lockStop(2000.0, 2150.0, 20.0))
        assertNull(GoldTrend.lockStop(2000.0, 2150.0, 0.0))
    }
}
