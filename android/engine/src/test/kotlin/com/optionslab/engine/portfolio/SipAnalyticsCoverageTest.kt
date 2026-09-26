package com.optionslab.engine.portfolio

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SIP analytics on degenerate runs: nothing invested, zero prices, empty and flat series, crises at the edges. */
class SipAnalyticsCoverageTest {
    private val d0 = LocalDate.of(2020, 1, 1)
    private fun run(value: DoubleArray, invested: DoubleArray, total: Double = invested.lastOrNull() ?: 0.0, fin: Double = value.lastOrNull() ?: 0.0,
                    avgCost: Double = 0.0, avgPrice: Double = 0.0) =
        SipRun(emptyList(), List(value.size) { d0.plusDays(it.toLong()) }, value, invested, DoubleArray(value.size), emptyList(),
            total, fin, 0.0, avgCost, avgPrice, 0.0, 0.0, emptyMap(), emptyList())
    private val kw = SipKw("monthly", 1, 0.0, null)

    /** Business-day closes between two dates. */
    private fun closes(from: LocalDate, to: LocalDate, px: (Int) -> Double): Ser {
        val ds = ArrayList<LocalDate>(); var c = from
        while (c <= to) { if (c.dayOfWeek.value <= 5) ds.add(c); c = c.plusDays(1) }
        return Ser(ds, DoubleArray(ds.size) { px(it) })
    }

    @Test fun `the headline with nothing invested and no average price`() {
        val h = SipAnalyticsCalc.headline(run(doubleArrayOf(0.0, 0.0), doubleArrayOf(0.0, 0.0)))
        assertEquals(0.0, h.invested); assertNull(h.multiple, "0/0 is no multiple"); assertNull(h.costAdvantage, "no average price")
        assertNull(h.xirr, "no cash flows"); assertEquals(1.0 / 365, h.years)
        val paid = SipAnalyticsCalc.headline(run(doubleArrayOf(0.0, 120.0), doubleArrayOf(0.0, 100.0), avgCost = 99.0, avgPrice = 100.0))
        assertEquals(1.2, paid.multiple); assertEquals(-0.01, paid.costAdvantage!!, 1e-12); assertEquals(20.0, paid.gain)
        assertNull(SipAnalyticsCalc.f(null)); assertNull(SipAnalyticsCalc.f(Double.POSITIVE_INFINITY)); assertNull(SipAnalyticsCalc.f(Double.NaN))
        assertEquals(1.5, SipAnalyticsCalc.f(1.5))
    }

    @Test fun `underwater time on degenerate curves`() {
        val empty = SipAnalyticsCalc.underwater(run(DoubleArray(0), DoubleArray(0)))
        assertEquals(Underwater(0, 0, null, 0, null, null, false), empty)
        assertEquals(Underwater(0, 1, 0.0, 0, null, null, false), SipAnalyticsCalc.underwater(run(doubleArrayOf(0.0), doubleArrayOf(0.0))),
            "nothing invested is never a shortfall")
        // Below water on days 1..3 (NaN compares false, so day 2 breaks the streak); the NaN gap is skipped.
        val uw = SipAnalyticsCalc.underwater(run(doubleArrayOf(100.0, 90.0, Double.NaN, 95.0, 97.0, 120.0), DoubleArray(6) { 100.0 }))
        assertEquals(3, uw.sessionsBelow); assertEquals(2, uw.longestStreakSessions); assertEquals(d0.plusDays(4), uw.longestStreakEnded)
        assertEquals(-0.1, uw.worstShortfall!!, 1e-12); assertTrue(uw.everUnderwater); assertEquals(0.5, uw.shareBelow)
    }

    @Test fun `drawdown on degenerate curves`() {
        val flat = SipAnalyticsCalc.drawdown(run(doubleArrayOf(0.0, 0.0), doubleArrayOf(0.0, 0.0)))
        assertNull(flat.maxDrawdown); assertNull(flat.troughDate); assertNull(flat.recoverySessions); assertFalse(flat.recovered)
        assertTrue(flat.curve.all { it.value == null })
        val dd = SipAnalyticsCalc.drawdown(run(doubleArrayOf(100.0, 80.0, 90.0), doubleArrayOf(100.0, 100.0, 100.0)))
        assertEquals(-0.2, dd.maxDrawdown!!, 1e-12); assertEquals(d0.plusDays(1), dd.troughDate); assertFalse(dd.recovered)
        val back = SipAnalyticsCalc.drawdown(run(doubleArrayOf(100.0, 80.0, 100.0), doubleArrayOf(100.0, 100.0, 100.0)))
        assertEquals(1, back.recoverySessions); assertTrue(back.recovered)
    }

    @Test fun `grids over empty or short histories`() {
        val none = Ser(emptyList(), DoubleArray(0))
        assertTrue(SipAnalyticsCalc.rollingXirr(none, 1000.0, kw).isEmpty())
        assertTrue(SipAnalyticsCalc.crisis(none, 1000.0, kw).isEmpty())
        assertEquals(StartDateHeatmap(emptyList(), emptyList(), emptyList()), SipAnalyticsCalc.startDateHeatmap(none, 1000.0, kw))
        // Six months: no one-year window completes; a zero duration is dropped from the columns.
        val short = closes(d0, d0.plusMonths(6)) { 100.0 + it }
        assertTrue(SipAnalyticsCalc.rollingXirr(short, 1000.0, kw).isEmpty())
        val grid = SipAnalyticsCalc.startDateHeatmap(short, 1000.0, kw, listOf(0, 1))
        assertEquals(listOf("1Y"), grid.columns); assertEquals(7, grid.rows.size); assertEquals("Jan 2020", grid.rows.first())
        assertTrue(grid.values.flatten().all { it == null })
        // An empty SIP window (end before start) fails every day: no best, worst or spread.
        val days = SipAnalyticsCalc.sipDateHeatmap(short, d0.plusMonths(3), d0, 1000.0, kw)
        assertEquals(28, days.values.size); assertTrue(days.values.all { it == null })
        assertNull(days.bestDay); assertNull(days.worstDay); assertNull(days.spread)
    }

    @Test fun `a very long history is thinned to fit the grid`() {
        val long = closes(LocalDate.of(1950, 1, 1), LocalDate.of(2030, 1, 1)) { 100.0 }
        val grid = SipAnalyticsCalc.startDateHeatmap(long, 1000.0, kw, listOf(90))
        assertTrue(grid.rows.size <= 900, "${grid.rows.size} rows")
        assertEquals("Jan 1950", grid.rows[0]); assertEquals("Mar 1950", grid.rows[1], "every other start dropped until it fits")
        assertTrue(grid.values.flatten().all { it == null }, "no 90-year window fits")
    }

    @Test fun `crisis windows at the edges of the data`() {
        val px = closes(LocalDate.of(2020, 1, 1), LocalDate.of(2021, 12, 31)) { i -> 100.0 + (i % 20) - 10.0 }
        val periods = listOf(
            CrisisPeriod("before", "Before data", "2019-01-01", "2019-06-01"),             // starts before the data
            CrisisPeriod("after", "After data", "2021-06-01", "2022-06-01"),               // ends after the data
            CrisisPeriod("late", "Too late", "2021-10-01", "2021-11-01"),                  // too little time after it
            CrisisPeriod("oneday", "One day", "2020-03-07", "2020-03-08"),                 // a weekend: no closes inside
            CrisisPeriod("real", "Real", "2020-03-02", "2020-04-30"),
        )
        val rows = SipAnalyticsCalc.crisis(px, 1000.0, kw, periods)
        assertEquals(listOf("oneday", "real"), rows.map { it.key }, "latest crisis start first")
        val weekend = rows.single { it.key == "oneday" }
        assertNull(weekend.marketMove); assertNull(weekend.marketTrough)
        val real = rows.single { it.key == "real" }
        assertTrue(real.marketMove != null && real.marketTrough!! <= real.marketMove!!)
        assertEquals(real.xirrAdvantage != null && real.xirrAdvantage!! > 0, real.startingEarlyWon)
        val s = SipAnalyticsCalc.crisisSummary(rows)
        assertEquals(rows.count { it.xirrAdvantage != null }, s.crises)
        assertEquals(SipCrisisSummary(0, 0, null, null), SipAnalyticsCalc.crisisSummary(emptyList()))
        // A row with no advantage is not scored.
        val unscored = real.copy(xirrAdvantage = null)
        assertEquals(SipCrisisSummary(0, 0, null, null), SipAnalyticsCalc.crisisSummary(listOf(unscored)))
        val two = SipAnalyticsCalc.crisisSummary(listOf(real.copy(xirrAdvantage = 0.02, startingEarlyWon = true), real.copy(xirrAdvantage = -0.04, startingEarlyWon = false)))
        assertEquals(SipCrisisSummary(2, 1, 0.5, -0.01), two)
    }
}
