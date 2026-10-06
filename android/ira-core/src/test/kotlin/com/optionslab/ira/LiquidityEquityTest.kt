package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiquidityEquityTest {
    private val day0 = LocalDate.of(2026, 10, 6)
    private val liq = ForwardCheck.LIQUIDITY
    private fun t(i: Int, net: Double, index: String = "BANKNIFTY", at: LocalTime? = null) = LiquidityEquity.Trade(day0.plusDays(i.toLong()), net, index, at)
    private fun of(vararg nets: Double) = LiquidityEquity.of(nets.mapIndexed { i, x -> t(i, x) })

    @Test fun runningTotalDrawdownAndPointsInOrder() {
        val q = of(1_000.0, -400.0, -700.0, 1_500.0, -200.0)
        assertEquals(5, q.trades)
        assertEquals(listOf(0.0, 1_000.0, 600.0, -100.0, 1_400.0, 1_200.0), q.totals)
        assertEquals(listOf(0.0, 0.0, -400.0, -1_100.0, 0.0, -200.0), q.drawdowns)
        assertEquals(1_200.0, q.net, 1e-9)
        assertEquals(-1_100.0, q.maxDrawdown, 1e-9)
        assertEquals(listOf(1, 2, 3, 4, 5), q.points.map { it.n })
        assertEquals(listOf(1_000.0, -400.0, -700.0, 1_500.0, -200.0), q.points.map { it.net })
        assertTrue(q.enough)
        // The same as the forward check on the same trades: its running net, drawdown and net.
        assertEquals(q.check.cumulative, q.totals)
        assertEquals(q.check.drawdown, q.maxDrawdown, 1e-9)
        assertEquals(q.check.net, q.net, 1e-9)
        assertEquals(ForwardCheck.drawdown(q.points.map { it.net }), q.maxDrawdown, 1e-9)
    }

    @Test fun drawdownCountsFromZeroWhenTheFirstTradeLoses() {
        val q = of(-500.0, -300.0, 900.0)
        assertEquals(listOf(0.0, -500.0, -800.0, 0.0), q.drawdowns)
        assertEquals(-800.0, q.maxDrawdown, 1e-9)
        // Every gain: no drawdown at all.
        assertEquals(0.0, of(100.0, 200.0).maxDrawdown, 0.0)
    }

    @Test fun bandIsTheBacktestMeanTimesNWithTwoSdRootN() {
        val q = of(100.0, 200.0, 300.0, 400.0)
        assertEquals(5, q.band.size)
        assertEquals(listOf(0, 1, 2, 3, 4), q.band.map { it.n })
        assertEquals(0.0, q.band[0].low, 0.0); assertEquals(0.0, q.band[0].high, 0.0)
        q.band.forEach { b ->
            assertEquals(liq.mean * b.n, b.expected, 1e-9)
            assertEquals(2 * liq.sd * sqrt(b.n.toDouble()), b.high - b.expected, 1e-9)
            assertEquals(b.expected - b.low, b.high - b.expected, 1e-9)
            // On the mean a trade: ± 2·sd/√n, the card's band.
            if (b.n > 0) assertEquals(ForwardCheck.band(liq, b.n)!!.half, (b.high - b.expected) / b.n, 1e-9)
        }
        assertEquals(ForwardCheck.expectedPath(liq, 4).map { it.second }, q.band.map { it.expected })
    }

    @Test fun onlyTradesSinceTheForwardTestBeganInDayThenTimeOrder() {
        val before = LiquidityEquity.Trade(day0.minusDays(1), 9_999.0, "BANKNIFTY")
        val late = t(0, -300.0, "FINNIFTY", LocalTime.of(13, 5))
        val early = t(0, 700.0, "BANKNIFTY", LocalTime.of(9, 40))
        val next = t(1, 50.0, "BANKNIFTY", null)
        val q = LiquidityEquity.of(listOf(next, late, before, early))
        assertEquals(listOf(700.0, -300.0, 50.0), q.points.map { it.net })
        assertEquals(listOf("BANKNIFTY", "FINNIFTY", "BANKNIFTY"), q.points.map { it.index })
        assertEquals(listOf(day0, day0, day0.plusDays(1)), q.points.map { it.day })
        assertEquals(3, q.check.trades)
        // No time sorts first on its day, ties keep their order.
        val tie = LiquidityEquity.of(listOf(t(0, 1.0, at = LocalTime.NOON), t(0, 2.0), t(0, 3.0)))
        assertEquals(listOf(2.0, 3.0, 1.0), tie.points.map { it.net })
        // Another expectation with no start keeps every trade.
        assertEquals(4, LiquidityEquity.of(listOf(next, late, before, early), ForwardCheck.SOLO).trades)
    }

    @Test fun aNetThatIsNotANumberIsLeftOut() {
        val q = LiquidityEquity.of(listOf(t(0, 100.0), t(1, Double.NaN), t(2, Double.POSITIVE_INFINITY), t(3, -50.0)))
        assertEquals(listOf(100.0, -50.0), q.points.map { it.net })
        assertEquals(50.0, q.net, 1e-9)
        assertEquals(2, q.check.trades)
    }

    @Test fun streakOfTheLatestTrades() {
        assertNull(LiquidityEquity.streak(emptyList()))
        assertEquals(LiquidityEquity.Streak(true, 3), LiquidityEquity.streak(listOf(-1.0, 5.0, 2.0, 1.0)))
        assertEquals(LiquidityEquity.Streak(false, 2), LiquidityEquity.streak(listOf(5.0, -2.0, 0.0)))
        assertEquals(LiquidityEquity.Streak(false, 1), LiquidityEquity.streak(listOf(0.0)))
        assertEquals(LiquidityEquity.Streak(true, 1), LiquidityEquity.streak(listOf(-3.0, 0.5)))
        assertEquals("3 wins", LiquidityEquity.streakWords(LiquidityEquity.Streak(true, 3)))
        assertEquals("1 win", LiquidityEquity.streakWords(LiquidityEquity.Streak(true, 1)))
        assertEquals("1 loss", LiquidityEquity.streakWords(LiquidityEquity.Streak(false, 1)))
        assertEquals("4 losses", LiquidityEquity.streakWords(LiquidityEquity.Streak(false, 4)))
        assertEquals("—", LiquidityEquity.streakWords(null))
        assertEquals(LiquidityEquity.Streak(false, 2), of(1_000.0, -400.0, -700.0).streak)
    }

    @Test fun statsAndVerdict() {
        val q = of(1_000.0, -400.0, -700.0, 1_500.0, -200.0)
        assertEquals(listOf("Trades" to "5", "Net" to "+₹1,200", "Max drawdown" to "−₹1,100", "Streak" to "1 loss"), LiquidityEquity.stats(q))
        assertEquals(ForwardCheck.line(q.check), LiquidityEquity.verdict(q))
        assertTrue(LiquidityEquity.verdict(q).startsWith("Live vs backtest: too few trades (<20)"))
        val none = LiquidityEquity.of(emptyList())
        assertEquals(listOf("Trades" to "0", "Net" to "₹0", "Max drawdown" to "₹0", "Streak" to "—"), LiquidityEquity.stats(none))
        assertEquals("Live vs backtest: no forward trades yet (expected ₹228/trade).", LiquidityEquity.verdict(none))
        // 24 in line (as the card's fixture): the verdict says so.
        val inLine = LiquidityEquity.of((0 until 24).map { t(it, if (it % 2 == 0) 1_400.0 else -1_000.0) })
        assertEquals(ForwardCheck.Verdict.IN_LINE, inLine.check.verdict)
        assertTrue(LiquidityEquity.verdict(inLine).startsWith("Live vs backtest: in line"))
    }

    @Test fun waitingForTradesUnderTwo() {
        val none = LiquidityEquity.of(emptyList())
        assertFalse(none.enough)
        assertNull(none.streak)
        assertEquals(listOf(0.0), none.totals)
        assertEquals(1, none.band.size)
        assertEquals("Waiting for trades: no closed paper trade since 6 Oct 2026 yet. The chart starts at 2.", LiquidityEquity.waiting(none))
        val one = of(-420.0)
        assertFalse(one.enough)
        assertEquals("Waiting for trades: 1 closed paper trade since 6 Oct 2026 (−₹420 a lot). The chart starts at 2.", LiquidityEquity.waiting(one))
        assertTrue(of(1.0, 2.0).enough)
        // An expectation with no start says none.
        assertEquals("Waiting for trades: no closed paper trade yet. The chart starts at 2.", LiquidityEquity.waiting(LiquidityEquity.of(emptyList(), ForwardCheck.SOLO)))
    }

    @Test fun rangeHoldsTheBandTheLineAndZero() {
        val q = of(1_000.0, -400.0, -700.0)
        val (lo, hi) = LiquidityEquity.range(q)
        assertTrue(lo <= 0 && hi >= 0)
        assertTrue(q.band.all { it.low >= lo && it.high <= hi })
        assertTrue(q.totals.all { it in lo..hi })
        assertEquals(q.band.minOf { it.low }, lo, 1e-9)
        // A flat record with a zero-spread expectation never gives a zero height.
        val flat = LiquidityEquity.of(listOf(t(0, 0.0), t(1, 0.0)), liq.copy(mean = 0.0, sd = 0.0))
        assertEquals(0.0 to 1.0, LiquidityEquity.range(flat))
        // A line far above the band stretches the range.
        val big = of(100_000.0, 100_000.0)
        assertEquals(200_000.0, LiquidityEquity.range(big).second, 1e-9)
    }

    @Test fun floorOfTheDrawdownStrip() {
        assertEquals(-1_100.0, LiquidityEquity.floor(of(1_000.0, -400.0, -700.0)), 1e-9)
        assertEquals(-1.0, LiquidityEquity.floor(of(10.0, 20.0)), 0.0)
    }

    @Test fun nearestTradeToATap() {
        assertEquals(0, LiquidityEquity.nearest(0, 0.5))
        assertEquals(1, LiquidityEquity.nearest(4, 0.0))
        assertEquals(1, LiquidityEquity.nearest(4, 0.3))
        assertEquals(2, LiquidityEquity.nearest(4, 0.5))
        assertEquals(3, LiquidityEquity.nearest(4, 0.7))
        assertEquals(4, LiquidityEquity.nearest(4, 1.0))
        assertEquals(4, LiquidityEquity.nearest(4, 7.0))
        assertEquals(1, LiquidityEquity.nearest(4, -2.0))
        assertEquals(1, LiquidityEquity.nearest(4, Double.NaN))
        assertEquals(1, LiquidityEquity.nearest(1, 0.9))
    }

    @Test fun eachTradeInWords() {
        val q = LiquidityEquity.of(listOf(t(0, 420.4, "BANKNIFTY"), t(1, -1_234.6, ""), t(2, 0.0, "FINNIFTY")))
        assertEquals("6 Oct 2026 · #1 · BANKNIFTY · +₹420 · total +₹420", LiquidityEquity.pointLine(q.points[0]))
        assertEquals("7 Oct 2026 · #2 · −₹1,235 · total −₹814", LiquidityEquity.pointLine(q.points[1]))
        assertEquals("8 Oct 2026 · #3 · FINNIFTY · ₹0 · total −₹814", LiquidityEquity.pointLine(q.points[2]))
    }

    @Test fun expectedInWords() {
        val q = of(1.0, 2.0, 3.0, 4.0)
        assertEquals("Expected after 4 trades: +₹911 (−₹7,347 to +₹9,168)", LiquidityEquity.expectedLine(q))
        assertEquals("Expected after 1 trade: +₹228 (−₹3,901 to +₹4,356)", LiquidityEquity.expectedLine(q, 1))
        // Past the record: its last.
        assertEquals(LiquidityEquity.expectedLine(q), LiquidityEquity.expectedLine(q, 99))
        assertEquals("Expected after 0 trades: ₹0 (₹0 to ₹0)", LiquidityEquity.expectedLine(LiquidityEquity.of(emptyList())))
    }

    @Test fun rupees() {
        assertEquals("+₹1,235", LiquidityEquity.rs(1_234.6, sign = true))
        assertEquals("₹1,235", LiquidityEquity.rs(1_234.6))
        assertEquals("−₹200,000", LiquidityEquity.rs(-200_000.0))
        assertEquals("₹0", LiquidityEquity.rs(-0.4, sign = true))
        assertEquals("6 Oct 2026", LiquidityEquity.date(day0))
    }
}
