package com.optionslab.engine.pine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** The broker emulator against TradingView's fill rules (cases found in review). */
class PineBrokerTest {
    private val t0 = 1790048700L            // 2026-09-22 09:15 IST
    private fun bar(i: Int, o: Double, h: Double, l: Double, c: Double) = Pine.Bar(t0 + i * 300L, o, h, l, c, 100.0)
    private fun flat(i: Int, px: Double = 100.0) = bar(i, px, px + 0.5, px - 0.5, px)
    private fun run(src: String, bars: List<Pine.Bar>): Pine.Run {
        val s = when (val r = Pine.compile(src.trimIndent())) {
            is Pine.Compiled.Ok -> r.script
            is Pine.Compiled.Failed -> fail("expected to compile: ${r.errors}")
        }
        val r = Pine.run(s, bars)
        assertNull(r.error, r.error?.toString())
        return r
    }
    private fun closed(r: Pine.Run) = r.report!!.trades.filter { !it.open }

    @Test fun stopEntryExitIgnoresPricesBeforeTheFill() {
        // Low (95) comes before the entry at 105; the 98 stop must not fill on this bar.
        val r = run("""
            strategy("t")
            if bar_index == 0
                strategy.entry("L", strategy.long, stop=105)
                strategy.exit("x", "L", stop=98)
        """, listOf(flat(0), bar(1, 100.0, 110.0, 95.0, 108.0), flat(2, 108.0)))
        assertTrue(closed(r).isEmpty(), "trades: ${closed(r)}")
        assertEquals(1, r.report!!.trades.count { it.open })
    }

    @Test fun limitEntryTargetIgnoresHighBeforeTheFill() {
        // High (104) is nearer the open and comes first; the entry at 97 is later, so no target fill.
        val r = run("""
            strategy("t")
            if bar_index == 0
                strategy.entry("L", strategy.long, limit=97)
                strategy.exit("x", "L", limit=104)
        """, listOf(flat(0), bar(1, 100.0, 104.0, 90.0, 95.0), flat(2, 95.0)))
        assertTrue(closed(r).isEmpty(), "trades: ${closed(r)}")
    }

    @Test fun exitAfterTheFillOnTheSameBarStillFills() {
        // Low first (95), entry at 105 on the way up, then high 112 reaches the 110 target.
        val r = run("""
            strategy("t")
            if bar_index == 0
                strategy.entry("L", strategy.long, stop=105)
                strategy.exit("x", "L", limit=110)
        """, listOf(flat(0), bar(1, 100.0, 112.0, 95.0, 108.0), flat(2, 108.0)))
        val t = closed(r).single()
        assertEquals(105.0, t.entryPrice, 1e-9); assertEquals(110.0, t.exitPrice, 1e-9)
    }

    @Test fun stopLimitEntryRespectsItsLimit() {
        // Gaps to 110 through the 105 stop; the 106 limit is never offered, so no fill.
        val r = run("""
            strategy("t")
            if bar_index == 0
                strategy.entry("L", strategy.long, stop=105, limit=106)
        """, listOf(flat(0), bar(1, 110.0, 111.0, 108.0, 110.0), flat(2, 110.0)))
        assertTrue(r.report!!.trades.isEmpty(), "trades: ${r.report!!.trades}")
        // Armed, it rests as a limit: a later dip to 106 fills it there.
        val r2 = run("""
            strategy("t")
            if bar_index == 0
                strategy.entry("L", strategy.long, stop=105, limit=106)
        """, listOf(flat(0), bar(1, 110.0, 111.0, 108.0, 110.0), bar(2, 109.0, 109.5, 105.5, 107.0)))
        assertEquals(106.0, r2.report!!.trades.single().entryPrice, 1e-9)
    }

    @Test fun oppositeStrategyOrderTradesItsQuantity() {
        val r = run("""
            strategy("t")
            if bar_index == 0
                strategy.order("a", strategy.long, 3)
            if bar_index == 1
                strategy.order("b", strategy.short, 1)
            plot(strategy.position_size)
        """, (0..4).map { flat(it) })
        assertEquals(2.0, r.plots[0][3], 1e-9)
        val r2 = run("""
            strategy("t")
            if bar_index == 0
                strategy.order("a", strategy.long, 3)
            if bar_index == 1
                strategy.order("b", strategy.short, 5)
            plot(strategy.position_size)
        """, (0..4).map { flat(it) })
        assertEquals(-2.0, r2.plots[0][3], 1e-9)
    }

    @Test fun strategyOrderIgnoresPyramiding() {
        val r = run("""
            strategy("t")
            if bar_index <= 1
                strategy.order("a", strategy.long, 1)
            plot(strategy.position_size)
        """, (0..4).map { flat(it) })
        assertEquals(2.0, r.plots[0][3], 1e-9)
    }

    @Test fun maxDrawdownIsZeroWhileEquityOnlyRises() {
        val closes = listOf(100.0, 101.0, 102.0, 103.0, 104.0, 105.0)
        val r = run("""
            strategy("t")
            if bar_index == 0
                strategy.entry("L", strategy.long, 1)
            plot(strategy.max_drawdown)
        """, closes.mapIndexed { i, c -> bar(i, if (i == 0) c else closes[i - 1], c + 0.0, (if (i == 0) c else closes[i - 1]) - 0.0, c) })
        r.plots[0].forEach { assertEquals(0.0, it, 1e-9) }
    }

    @Test fun netProfitLeavesOutOpenTradesCommission() {
        val r = run("""
            strategy("t", commission_type=strategy.commission.cash_per_order, commission_value=5)
            if bar_index == 0
                strategy.entry("L", strategy.long, 1)
            plot(strategy.netprofit)
            plot(strategy.equity - strategy.initial_capital - strategy.netprofit - strategy.openprofit)
        """, (0..3).map { flat(it) })
        assertEquals(0.0, r.plots[0][3], 1e-9)
        r.plots[1].forEach { assertEquals(0.0, it, 1e-9) }
    }

    @Test fun optimiserCopesWithOneBar() {
        val s = (Pine.compile("strategy(\"t\")\nlen = input.int(5)\nplot(close)") as Pine.Compiled.Ok).script
        val res = PineOptimise.run(s, listOf(flat(0)), emptyMap(), PineOptimise.Plan(listOf(PineOptimise.Range("len", 1.0, 2.0, 1.0))))
        assertNotNull(res)
        assertEquals(2, res.tried)
    }
}
