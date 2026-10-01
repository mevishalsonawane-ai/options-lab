package com.optionslab.engine.gold

import com.optionslab.engine.orb.Bar
import com.optionslab.engine.orb.LiquidityRules
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoldLiquidityTest {
    private val monday = LocalDate.of(2026, 9, 28)

    /** The session's hours, Monday 07:00 UTC onwards, 14 a weekday, weekends skipped. */
    private fun sessionHours(n: Int): List<LocalDateTime> {
        val out = ArrayList<LocalDateTime>()
        var d = monday
        while (out.size < n) {
            if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) for (h in 7 until 21) out += d.atTime(h, 0)
            d = d.plusDays(1)
        }
        return out.take(n)
    }

    /** LiquidityRulesTest's synthetic series, laid on the session's hours. */
    private fun wave(n: Int = 400): List<Bar> {
        val t = sessionHours(n)
        val c = (0 until n).map { 2400 + 40 * sin(it / 9.0) + 25 * sin(it / 3.7) + 10 * sin(it / 1.3) }
        val o = (0 until n).map { if (it == 0) c[0] else c[it - 1] }
        val h = (0 until n).map { maxOf(o[it], c[it]) + 3 + 8 * abs(sin(it / 2.3)) }
        val l = (0 until n).map { minOf(o[it], c[it]) - 3 - 8 * abs(cos(it / 2.9)) }
        return (0 until n).map { Bar(t[it], o[it], h[it], l[it], c[it]) }
    }

    @Test fun theChartIs24x5WithoutTheDailyBreakOrTheWeekend() {
        val fri = LocalDate.of(2026, 10, 2)
        val sat = LocalDate.of(2026, 10, 3)
        val minutes = listOf(
            Bar(monday.atTime(6, 59), 1.0, 1.0, 1.0, 1.0),                                   // an early hour: on the chart now
            Bar(monday.atTime(7, 0), 10.0, 12.0, 9.0, 11.0), Bar(monday.atTime(7, 30), 11.0, 15.0, 8.0, 14.0),
            Bar(monday.atTime(7, 59), 14.0, 14.0, 13.0, 13.5),
            Bar(monday.atTime(21, 10), 99.0, 99.0, 99.0, 99.0),                              // the daily break
            Bar(monday.atTime(23, 0), 5.0, 5.0, 5.0, 5.0),                                   // after it: trading again
            Bar(fri.atTime(21, 30), 99.0, 99.0, 99.0, 99.0),                                 // Friday after the close
            Bar(sat.atTime(10, 0), 99.0, 99.0, 99.0, 99.0),                                  // the weekend
        )
        val h = GoldLiquidity.hourly(minutes.shuffled(java.util.Random(1)))
        assertEquals(listOf(monday.atTime(6, 0), monday.atTime(7, 0), monday.atTime(23, 0)), h.map { it.start })
        assertEquals(Bar(monday.atTime(7, 0), 10.0, 15.0, 8.0, 13.5), h[1])
        assertEquals(h.take(2), GoldLiquidity.completed(h, monday.atTime(8, 0)))
        assertTrue(GoldLiquidity.inSession(monday.atTime(3, 0)))
        assertFalse(GoldLiquidity.inSession(monday.atTime(21, 30)))
        assertTrue(GoldLiquidity.inSession(monday.atTime(22, 0)))
        assertTrue(GoldLiquidity.inSession(fri.atTime(20, 59)))
        assertFalse(GoldLiquidity.inSession(fri.atTime(22, 0)))
        assertFalse(GoldLiquidity.inSession(sat.atTime(12, 0)))
    }

    @Test fun buysOnlyAnyTradingHourButMidnightAndLateFriday() {
        val fri = LocalDate.of(2026, 10, 2)
        assertTrue(GoldLiquidity.mayEnterAt(monday.atTime(1, 0)))
        assertTrue(GoldLiquidity.mayEnterAt(monday.atTime(7, 0)))
        assertTrue(GoldLiquidity.mayEnterAt(monday.atTime(20, 0)))
        assertTrue(GoldLiquidity.mayEnterAt(monday.atTime(23, 0)))
        assertFalse(GoldLiquidity.mayEnterAt(monday.atTime(0, 0)))
        assertFalse(GoldLiquidity.mayEnterAt(monday.atTime(21, 0)), "the break")
        assertTrue(GoldLiquidity.mayEnterAt(fri.atTime(19, 0)))
        assertFalse(GoldLiquidity.mayEnterAt(fri.atTime(20, 0)), "too near the weekend")
        assertFalse(GoldLiquidity.mayEnterAt(LocalDate.of(2026, 10, 4).atTime(10, 0)))
        // On the shared series: every buy LiquidityRules finds, and none of its sells.
        val b = wave()
        val gold = (60 until b.size).mapNotNull { i -> GoldLiquidity.signal(b.subList(0, i + 1))?.let { i to it } }
        val all = (60 until b.size).mapNotNull { i -> val s = b.subList(0, i + 1); LiquidityRules.signal(s, LiquidityRules.zones(s))?.let { i to it } }
        assertTrue(gold.isNotEmpty() && all.any { it.second.side < 0 }, "the series has buys and sells: ${all.map { it.second.side }}")
        assertEquals(all.filter { it.second.side > 0 }, gold)
        assertNull(GoldLiquidity.signal(b.take(10)), "too little history for a swing")
    }

    @Test fun heldOvernightAndSoldBeforeTheWeekend() {
        val entry = monday.atTime(10, 0)
        val signal = monday.atTime(9, 0)
        val flat = listOf(Bar(monday.atTime(9, 0), 2400.0, 2401.0, 2399.0, 2400.0), Bar(monday.atTime(10, 0), 2400.0, 2401.0, 2399.0, 2400.5))
        assertEquals(LocalDate.of(2026, 10, 2).atTime(20, 40), GoldLiquidity.weekendCut(entry))
        assertNull(GoldLiquidity.exitReason(2399.0, null, signal, entry, flat, emptyList(), monday.atTime(20, 40)), "Monday evening: held")
        assertNull(GoldLiquidity.exitReason(2399.0, null, signal, entry, flat, emptyList(), monday.plusDays(1).atTime(9, 0)), "overnight: held")
        assertEquals("cut_off", GoldLiquidity.exitReason(2399.0, null, signal, entry, flat, emptyList(), LocalDate.of(2026, 10, 2).atTime(20, 40)))
        assertEquals("cut_off", GoldLiquidity.exitReason(2399.0, null, signal, entry, flat, emptyList(), LocalDate.of(2026, 10, 5).atTime(1, 0)),
            "the phone was off over the weekend")
        val touched = listOf(Bar(monday.atTime(10, 30), 2400.0, 2412.0, 2400.0, 2411.0))
        assertEquals("next_liquidity", GoldLiquidity.exitReason(2399.0, 2410.0, signal, entry, flat, touched, monday.atTime(10, 31)))
        val failed = flat + Bar(monday.atTime(11, 0), 2400.0, 2400.0, 2390.0, 2395.0)
        assertEquals("failed_break", GoldLiquidity.exitReason(2399.0, null, signal, entry, failed, emptyList(), monday.atTime(12, 0)))
    }

    @Test fun pricesAndPnlIncludeTheSpreadAndCommission() {
        assertEquals(2400.15, GoldLiquidity.buyPrice(2400.0), 1e-9)
        assertEquals(2399.85, GoldLiquidity.sellPrice(2400.0), 1e-9)
        assertEquals(2409.85, GoldLiquidity.exitPrice("next_liquidity", 2410.0, 2412.0), 1e-9)
        assertEquals(2411.85, GoldLiquidity.exitPrice("failed_break", 2410.0, 2412.0), 1e-9)
        // 0.01 lot (1 oz): +10.00 an ounce less $0.07 commission.
        assertEquals(9.93, GoldLiquidity.pnl(2400.0, 2410.0, 0.01), 1e-9)
        assertEquals(-507.0, GoldLiquidity.pnl(2400.0, 2395.0, 1.0), 1e-9)
    }
}
