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

    // ---- orders fill in the order the bar's path reaches them ------------------------------

    @Test fun exitStopBeforeAPendingReversalOnTheWayDown() {
        // L at 100, stop 95, a short limit waiting at 108. Low first: 100 -> 94 -> 109.
        // L is stopped at 95 first, then S opens at 108 - not a reversal of L at 108.
        val r = run("""
            strategy("t")
            if bar_index == 0
                strategy.entry("L", strategy.long)
                strategy.exit("x", "L", stop=95)
                strategy.entry("S", strategy.short, limit=108)
        """, listOf(flat(0), flat(1), bar(2, 100.0, 109.0, 94.0, 100.0), flat(3)))
        val t = closed(r).single()
        assertEquals("x", t.exitId); assertEquals(95.0, t.exitPrice, 1e-9)
        val s = r.report!!.trades.single { it.open }
        assertEquals("S", s.entryId); assertEquals(108.0, s.entryPrice, 1e-9); assertEquals(2, s.entryBar)
    }

    @Test fun twoStopEntriesFillInPathOrder() {
        // Long stop 105 and short stop 95; low first (100 -> 94 -> 107): short at 95, then reversed long at 105.
        val r = run("""
            strategy("t")
            if bar_index == 0
                strategy.entry("L", strategy.long, stop=105)
                strategy.entry("S", strategy.short, stop=95)
        """, listOf(flat(0), bar(1, 100.0, 107.0, 94.0, 100.0), flat(2)))
        val t = closed(r).single()
        assertEquals("S", t.entryId); assertEquals(95.0, t.entryPrice, 1e-9)
        assertEquals("L", t.exitId); assertEquals(105.0, t.exitPrice, 1e-9)
        val l = r.report!!.trades.single { it.open }
        assertEquals("L", l.entryId); assertEquals(105.0, l.entryPrice, 1e-9)
    }

    @Test fun severalExitsOfOneEntryFillInPathOrder() {
        // Target 110 placed before stop 95; low first (100 -> 94 -> 111): the stop is reached first.
        val r = run("""
            strategy("t")
            if bar_index == 0
                strategy.entry("L", strategy.long)
                strategy.exit("tp", "L", limit=110)
                strategy.exit("sl", "L", stop=95)
        """, listOf(flat(0), flat(1), bar(2, 100.0, 111.0, 94.0, 100.0), flat(3)))
        val t = closed(r).single()
        assertEquals("sl", t.exitId); assertEquals(95.0, t.exitPrice, 1e-9)
    }

    @Test fun trailingStopActivatesAndFillsInsideTheSameBar() {
        // Long at 100; trail activates 10 above (200 ticks of 0.05), trails 5 behind (100 ticks).
        // High first (105 -> 115 -> 94): best 115, stop 110, filled on the way down on this bar.
        val r = run("""
            strategy("t")
            if bar_index == 0
                strategy.entry("L", strategy.long)
                strategy.exit("tr", "L", trail_points=200, trail_offset=100)
        """, listOf(flat(0), flat(1), bar(2, 105.0, 115.0, 94.0, 100.0), flat(3, 100.0)))
        val t = closed(r).single()
        assertEquals("tr", t.exitId); assertEquals(2, t.exitBar); assertEquals(110.0, t.exitPrice, 1e-9)
    }

    @Test fun trailingBestIgnoresPricesBeforeAMidBarFill() {
        // High first (100 -> 108 -> 90): the limit entry at 95 fills after the 108 high, so the
        // trail (active at 105) was never reached by this position and nothing trails next bar.
        val r = run("""
            strategy("t")
            if bar_index == 0
                strategy.entry("L", strategy.long, limit=95)
                strategy.exit("tr", "L", trail_price=105, trail_offset=20)
        """, listOf(flat(0), bar(1, 100.0, 108.0, 90.0, 92.0), flat(2, 93.0)))
        assertTrue(closed(r).isEmpty(), "trades: ${closed(r)}")
        assertEquals(95.0, r.report!!.trades.single().entryPrice, 1e-9)
    }

    @Test fun cancelRemovesExitOrdersAndWaitingCloses() {
        val src = """
            strategy("t")
            if bar_index == 0
                strategy.entry("L", strategy.long)
                strategy.exit("x", "L", stop=95)
            if bar_index == 1
                CANCEL
        """
        val bars = listOf(flat(0), flat(1), bar(2, 100.0, 101.0, 90.0, 92.0), flat(3, 92.0))
        // Without the cancel the stop fills; strategy.cancel("x") and cancel_all() both remove it.
        assertEquals("x", closed(run(src.replace("CANCEL", "strategy.cancel(\"none\")"), bars)).single().exitId)
        assertTrue(closed(run(src.replace("CANCEL", "strategy.cancel(\"x\")"), bars)).isEmpty())
        assertTrue(closed(run(src.replace("CANCEL", "strategy.cancel_all()"), bars)).isEmpty())
        // A waiting strategy.close is cancelled by its id too.
        val c = run("""
            strategy("t")
            if bar_index == 0
                strategy.entry("L", strategy.long)
            if bar_index == 1
                strategy.close("L")
                strategy.cancel("L")
        """, (0..3).map { flat(it) })
        assertTrue(closed(c).isEmpty(), "trades: ${closed(c)}")
    }

    @Test fun tradesCarryWhenTheirFillsHappened() {
        // 5-minute bars. Market entry at bar 1's open; stop exit inside bar 2 (by its last minute's close).
        val r = run("""
            strategy("t")
            if bar_index == 0
                strategy.entry("L", strategy.long)
                strategy.exit("x", "L", stop=95)
        """, listOf(flat(0), flat(1), bar(2, 100.0, 101.0, 90.0, 92.0), flat(3, 92.0)))
        val t = closed(r).single()
        assertEquals(t0 + 300, t.entryFillTime); assertTrue(!t.entryIntrabar)
        assertEquals(t0 + 600 + 299, t.exitFillTime); assertTrue(t.exitIntrabar)
        // process_orders_on_close: filled at the close of the bar that placed it.
        val c = run("""
            strategy("t", process_orders_on_close=true)
            if bar_index == 1
                strategy.entry("L", strategy.long)
            if bar_index == 2
                strategy.close("L")
        """, (0..3).map { flat(it) })
        val tc = closed(c).single()
        assertEquals(1, tc.entryBar); assertEquals(t0 + 300 + 299, tc.entryFillTime); assertTrue(!tc.entryIntrabar)
        assertEquals(t0 + 600 + 299, tc.exitFillTime)
    }

    @Test fun equityHoldsAfterARuntimeErrorAndTheOptimiserRanksSuchRunsLast() {
        val src = """
            strategy("t")
            v = input.int(1, "V")
            if bar_index == 0
                strategy.entry("L", strategy.long, qty=v)
            if bar_index == 3
                strategy.close("L")
            if v == 2 and bar_index == 5
                runtime.error("boom")
        """.trimIndent()
        val closes = (0..7).map { 100.0 + it * 2 }
        val bars = closes.mapIndexed { i, c -> bar(i, if (i == 0) c else closes[i - 1], c + 0.5, (if (i == 0) c else closes[i - 1]) - 0.5, c) }
        val sc = (Pine.compile(src) as Pine.Compiled.Ok).script
        val r = Pine.run(sc, bars, mapOf("V" to 2.0))
        assertNotNull(r.error)
        val eq = r.report!!.equity
        // Flat after bar 4's open: equity stays at capital + profit, not back at the capital.
        val final = r.report!!.initialCapital + r.report!!.netProfit
        for (k in 4 until eq.size) assertEquals(final, eq[k], 1e-9, "equity[$k]")
        val res = PineOptimise.run(sc, bars, emptyMap(), PineOptimise.Plan(listOf(PineOptimise.Range("V", 1.0, 2.0, 1.0)), inSamplePct = 99))
        assertEquals(listOf(1.0, 2.0), res.rows.map { it.values["V"] })
        assertNull(res.rows[0].error); assertNotNull(res.rows[1].error)
        assertTrue(res.rows[1].inSample.net > res.rows[0].inSample.net)
    }
}
