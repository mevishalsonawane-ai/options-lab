package com.optionslab.engine.pine

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class PineTest {
    /** Candles 5 minutes apart from 09:15 IST on 2026-09-22, one per close given. */
    private fun bars(vararg close: Double): List<Pine.Bar> {
        val t0 = 1790048700L          // 2026-09-22 09:15 IST
        return close.mapIndexed { i, c ->
            val o = if (i == 0) c else close[i - 1]
            Pine.Bar(t0 + i * 300L, o, maxOf(o, c) + 1, minOf(o, c) - 1, c, 1000.0)
        }
    }

    private fun ok(src: String): Pine.Script = when (val r = Pine.compile(src)) {
        is Pine.Compiled.Ok -> r.script
        is Pine.Compiled.Failed -> fail("expected to compile: ${r.errors}")
    }

    private fun errors(src: String): List<Pine.Problem> = when (val r = Pine.compile(src)) {
        is Pine.Compiled.Ok -> fail("expected errors")
        is Pine.Compiled.Failed -> r.errors
    }

    @Test fun syntaxErrorsCarryLineAndColumn() {
        val e = errors("//@version=5\nindicator(\"x\")\nplot(close +)\n")
        assertEquals(3, e.single().line)
        assertTrue(e.single().message.contains("Expected a value"), e.single().message)
        val s = errors("indicator(\"x\")\nx = \"abc\n")
        assertTrue(s.single().message.contains("never closed"))
    }

    @Test fun compileErrorsNameTheProblem() {
        val e = errors("indicator(\"x\")\nplot(foo)\ny := 1\nz = ta.smaa(close, 3)\nplot(ta.sma(close))\n")
        val msgs = e.map { it.message }
        assertTrue(msgs.any { it.contains("Undeclared identifier 'foo'") }, msgs.toString())
        assertTrue(msgs.any { it.contains("Undeclared identifier 'y'") }, msgs.toString())
        assertTrue(msgs.any { it.contains("Unknown function 'ta.smaa'") }, msgs.toString())
        assertTrue(msgs.any { it.contains("missing the argument 'length'") }, msgs.toString())
        assertTrue(errors("x = 1\nplot(x)\n").single().message.contains("declaration"))
        assertTrue(errors("indicator(\"x\")\nx = 1\nx = 2\n").single().message.contains("already defined"))
        assertTrue(errors("indicator(\"x\")\nif close > open\n    plot(close)\n").single().message.contains("local scope"))
        assertTrue(errors("indicator(\"x\")\ns = request.security(\"NSE:NIFTY\", \"D\", close)\n").single().message.contains("not supported"))
    }

    @Test fun smaAndPlotsMatchByHand() {
        val s = ok("indicator(\"SMA\", overlay=true)\nplot(ta.sma(close, 3), \"avg\", color=color.red)\n")
        assertEquals(listOf("avg"), s.plots.map { it.title })
        assertEquals("#F23645", s.plots[0].color)
        val r = Pine.run(s, bars(1.0, 2.0, 3.0, 4.0, 5.0))
        assertNull(r.error)
        assertTrue(r.plots[0][0].isNaN() && r.plots[0][1].isNaN())
        assertEquals(2.0, r.plots[0][2], 1e-9); assertEquals(4.0, r.plots[0][4], 1e-9)
    }

    @Test fun emaSeedsWithTheAverageThenSmooths() {
        val s = ok("indicator(\"e\")\nplot(ta.ema(close, 3))\n")
        val r = Pine.run(s, bars(1.0, 2.0, 3.0, 4.0))
        assertEquals(2.0, r.plots[0][2], 1e-9)            // sma of 1,2,3
        assertEquals(3.0, r.plots[0][3], 1e-9)            // 0.5*4 + 0.5*2
    }

    @Test fun languageFeaturesWork() {
        val src = """
            //@version=5
            indicator("features")
            diff(x) => x - x[1]
            pair(a, b) =>
                s = a + b
                [s, a * b]
            var float total = 0
            total += 1
            [sm, pr] = pair(2, 3)
            level = if close > 3
                1
            else
                -1
            acc = 0.0
            for i = 1 to 3
                acc += i
            plot(total, "total")
            plot(diff(close), "diff")
            plot(sm + pr, "tuple")
            plot(level, "level")
            plot(acc, "loop")
            plot(close[2], "back")
            plot(nz(close[10], -5), "nz")
        """.trimIndent()
        val s = ok(src)
        val r = Pine.run(s, bars(1.0, 2.0, 4.0, 7.0))
        assertNull(r.error, r.error?.toString())
        fun p(t: String) = r.plots[s.plots.indexOfFirst { it.title == t }]
        assertEquals(4.0, p("total")[3], 1e-9)
        assertTrue(p("diff")[0].isNaN()); assertEquals(3.0, p("diff")[3], 1e-9)
        assertEquals(11.0, p("tuple")[0], 1e-9)
        assertEquals(-1.0, p("level")[1], 1e-9); assertEquals(1.0, p("level")[2], 1e-9)
        assertEquals(6.0, p("loop")[0], 1e-9)
        assertEquals(1.0, p("back")[2], 1e-9)
        assertEquals(-5.0, p("nz")[3], 1e-9)
    }

    @Test fun crossoverStrategyFillsAtTheNextOpenAndReverses() {
        val src = """
            strategy("cross", overlay=true, initial_capital=100000)
            fast = ta.sma(close, 2)
            slow = ta.sma(close, 4)
            if ta.crossover(fast, slow)
                strategy.entry("L", strategy.long)
            if ta.crossunder(fast, slow)
                strategy.entry("S", strategy.short)
        """.trimIndent()
        val s = ok(src)
        assertEquals(Pine.Kind.STRATEGY, s.kind)
        val closes = doubleArrayOf(10.0, 9.0, 8.0, 7.0, 8.0, 10.0, 12.0, 13.0, 11.0, 9.0, 7.0, 6.0)
        val b = bars(*closes)
        val r = Pine.run(s, b)
        assertNull(r.error, r.error?.toString())
        val rep = assertNotNull(r.report)
        val first = rep.trades.first()
        assertTrue(first.long)
        // The cross is seen on bar 5's close; the order fills at bar 6's open.
        assertEquals(6, first.entryBar); assertEquals(b[6].open, first.entryPrice, 1e-9)
        assertEquals("S", first.exitId)                       // reversed by the short entry
        val short = rep.trades[1]
        assertTrue(!short.long && short.open)
        assertEquals(-1.0, r.position.last(), 1e-9)
        assertEquals(first.pnl, rep.netProfit, 1e-9)
        assertEquals(1, rep.closedTrades)
        // The equity curve includes the open short's profit at the last close.
        assertEquals(100000 + rep.netProfit + rep.openPnl, rep.equity.last(), 1e-6)
    }

    @Test fun exitStopAndTargetFillInsideTheBar() {
        val src = """
            strategy("exit")
            if bar_index == 0
                strategy.entry("L", strategy.long, qty=2)
            strategy.exit("X", "L", stop=95, limit=120)
        """.trimIndent()
        val s = ok(src)
        val t0 = 1790048700L
        val b = listOf(
            Pine.Bar(t0, 100.0, 101.0, 99.0, 100.0, 0.0),
            Pine.Bar(t0 + 300, 100.0, 104.0, 98.0, 103.0, 0.0),      // entry at 100
            Pine.Bar(t0 + 600, 103.0, 105.0, 94.0, 96.0, 0.0),       // stop at 95
            Pine.Bar(t0 + 900, 96.0, 97.0, 95.0, 96.0, 0.0),
        )
        val r = Pine.run(s, b)
        val t = r.report!!.trades.single()
        assertEquals(100.0, t.entryPrice, 1e-9); assertEquals(95.0, t.exitPrice, 1e-9)
        assertEquals("X", t.exitId); assertEquals(-10.0, t.pnl, 1e-9)
        assertEquals(0.0, r.position.last(), 1e-9)
    }

    @Test fun commissionAndPercentSizing() {
        val src = """
            strategy("c", default_qty_type=strategy.percent_of_equity, default_qty_value=50, initial_capital=1000, commission_type=strategy.commission.percent, commission_value=0.1)
            if bar_index == 0
                strategy.entry("L", strategy.long)
            if bar_index == 2
                strategy.close("L")
        """.trimIndent()
        val s = ok(src)
        val r = Pine.run(s, bars(10.0, 10.0, 12.0, 12.0))
        val t = r.report!!.trades.single()
        assertEquals(50.0, t.qty, 1e-9)                         // 50% of 1000 at 10
        val comm = 10.0 * 50 * 0.001 + 12.0 * 50 * 0.001
        assertEquals((12.0 - 10.0) * 50 - comm, t.pnl, 1e-9)
    }

    @Test fun shapesBecomeMarkersAndSignals() {
        val src = """
            indicator("sig", overlay=true)
            buy = ta.crossover(close, ta.sma(close, 3))
            sell = ta.crossunder(close, ta.sma(close, 3))
            plotshape(buy, "Buy", shape.labelup, location.belowbar, color.green, text="BUY")
            plotshape(sell, title="Sell", style=shape.triangledown, location=location.abovebar, color=color.red)
            alertcondition(buy, "Buy alert")
        """.trimIndent()
        val s = ok(src)
        assertEquals(listOf("Buy", "Sell", "Buy alert"), s.signals)
        val r = Pine.run(s, bars(5.0, 4.0, 3.0, 6.0, 7.0, 2.0))
        assertTrue(r.signals[0][3]); assertTrue(r.signals[2][3])
        assertTrue(r.signals[1][5])
        val m = r.markers.first { it.bar == 3 }
        assertEquals("labelUp", m.shape); assertTrue(!m.above); assertEquals("BUY", m.text)
        assertTrue(r.markers.any { it.bar == 5 && it.above && it.shape == "triangleDown" })
    }

    @Test fun rsiAndAtrStayInRange() {
        val s = ok("indicator(\"r\")\nplot(ta.rsi(close, 14))\nplot(ta.atr(14))\n[st, dir] = ta.supertrend(3, 10)\nplot(st)\n[m, sg, h] = ta.macd(close, 12, 26, 9)\nplot(h)\n")
        val c = DoubleArray(120) { 100 + 10 * kotlin.math.sin(it / 7.0) + it * 0.1 }
        val r = Pine.run(s, bars(*c))
        assertNull(r.error)
        val rsi = r.plots[0].filter { !it.isNaN() }
        assertTrue(rsi.isNotEmpty() && rsi.all { it in 0.0..100.0 })
        assertTrue(r.plots[1].filter { !it.isNaN() }.all { it > 0 })
        assertTrue(r.plots[2].count { !it.isNaN() } > 80)
        assertTrue(r.plots[3].count { !it.isNaN() } > 60)
    }

    @Test fun inputsAreListedAndOverridable() {
        val s = ok("indicator(\"i\")\nlen = input.int(3, \"Length\", minval=1)\nsrc = input.source(close, \"Source\")\nplot(ta.sma(src, len))\n")
        assertEquals(listOf("Length", "Source"), s.inputs.map { it.key })
        assertEquals(3.0, s.inputs[0].default)
        val r = Pine.run(s, bars(1.0, 2.0, 3.0, 4.0), mapOf("Length" to 2.0, "Source" to "open"))
        // open of bar 3 is 3, of bar 2 is 2
        assertEquals(2.5, r.plots[0][3], 1e-9)
    }

    @Test fun nextPositionShowsWhatTheLastBarOrdered() {
        val s = ok("strategy(\"n\")\nif bar_index == last_bar_index\n    strategy.entry(\"L\", strategy.long, qty=3)\n")
        val r = Pine.run(s, bars(1.0, 2.0, 3.0))
        assertEquals(0.0, r.position.last(), 1e-9)
        assertEquals(3.0, r.nextPosition, 1e-9)
    }

    @Test fun sessionFilterAndTimeFields() {
        val s = ok("indicator(\"t\")\ninSess = not na(time(timeframe.period, \"0920-0930\"))\nplot(inSess ? 1 : 0)\nplot(hour * 100 + minute)\n")
        val r = Pine.run(s, bars(1.0, 2.0, 3.0, 4.0))
        assertEquals(listOf(0.0, 1.0, 1.0, 0.0), r.plots[0].toList())
        assertEquals(915.0, r.plots[1][0], 1e-9)
    }

    @Test fun runtimeErrorsStopWithTheLine() {
        val s = ok("indicator(\"x\")\nx = 0\nwhile true\n    x += 1\nplot(x)\n")
        val r = Pine.run(s, bars(1.0))
        val e = assertNotNull(r.error)
        assertEquals(3, e.line); assertTrue(e.message.contains("too long"))
        assertTrue(abs(0.0) < 1)
    }

    @Test fun aTypicalTradingViewScriptRuns() {
        val src = """
            // This source code is subject to the terms of the Mozilla Public License 2.0
            //@version=5
            strategy("EMA + RSI", overlay = true, initial_capital = 100000,
                 default_qty_type = strategy.fixed, default_qty_value = 25,
                 commission_type = strategy.commission.cash_per_order, commission_value = 20)

            fastLen = input.int(9, title = "Fast EMA", minval = 1, group = "Trend")
            slowLen = input.int(21, "Slow EMA", minval = 1, group = "Trend")
            rsiLen  = input(14, title = "RSI length")
            useRsi  = input.bool(true, "Filter with RSI")
            slPts   = input.float(40.0, "Stop (points)")

            fast = ta.ema(close, fastLen)
            slow = ta.ema(close, slowLen)
            r = ta.rsi(close, rsiLen)

            longCondition  = ta.crossover(fast, slow) and (not useRsi or r > 50)
            shortCondition = ta.crossunder(fast, slow) and (not useRsi or r < 50)

            if (longCondition)
                strategy.entry("Long", strategy.long)
            if (shortCondition)
                strategy.entry("Short", strategy.short)

            strategy.exit("XL", from_entry = "Long", stop = strategy.position_avg_price - slPts, limit = strategy.position_avg_price + 2 * slPts)
            strategy.exit("XS", from_entry = "Short", loss = slPts / syminfo.mintick)

            inTrade = strategy.position_size != 0
            bgcolor(inTrade ? color.new(color.green, 90) : na)
            plot(fast, "Fast", color = color.new(color.blue, 0), linewidth = 2)
            plot(slow, "Slow", color = #FF9800)
            var label lbl = na
            if barstate.islast
                lbl := label.new(bar_index, high, str.tostring(strategy.netprofit, "#.##"))
            plotshape(longCondition, "Buy", shape.triangleup, location.belowbar, color.green, size = size.small)
        """.trimIndent()
        val s = ok(src)
        assertEquals("EMA + RSI", s.title); assertTrue(s.overlay)
        assertEquals(25.0, s.settings.qtyValue); assertEquals("cash_per_order", s.settings.commissionType)
        assertEquals(listOf("Fast EMA", "Slow EMA", "RSI length", "Filter with RSI", "Stop (points)"), s.inputs.map { it.key })
        assertEquals("#2962FF", s.plots[0].color); assertEquals("#FF9800", s.plots[1].color)
        val c = DoubleArray(600) { 25000 + 300 * kotlin.math.sin(it / 25.0) + 80 * kotlin.math.sin(it / 4.0) }
        val r = Pine.run(s, bars(*c))
        assertNull(r.error, r.error?.toString())
        val rep = r.report!!
        assertTrue(rep.closedTrades > 3, "trades: ${rep.closedTrades}")
        assertTrue(rep.trades.all { it.qty == 25.0 })
        assertTrue(rep.commission > 0)
        assertTrue(r.markers.isNotEmpty())
    }

    @Test fun rsiAtrAndHistoryMatchIndependentMaths() {
        val c = DoubleArray(80) { 100 + 8 * kotlin.math.sin(it / 5.0) + (it % 3) }
        val b = bars(*c)
        val s = ok("indicator(\"m\")\nplot(ta.rsi(close, 14))\nplot(ta.atr(14))\nx = close\nx := x * 2\nplot(x[1])\nplot(ta.wma(close, 4))\nplot(ta.stdev(close, 5))\n")
        val r = Pine.run(s, b)
        // Wilder's RSI: seeded with the average of the first 14 changes.
        val ch = (1 until c.size).map { c[it] - c[it - 1] }
        var up = ch.take(14).map { maxOf(it, 0.0) }.average(); var dn = ch.take(14).map { maxOf(-it, 0.0) }.average()
        val rsi = DoubleArray(c.size) { Double.NaN }
        rsi[14] = 100 - 100 / (1 + up / dn)
        for (i in 15 until c.size) { up = (up * 13 + maxOf(ch[i - 1], 0.0)) / 14; dn = (dn * 13 + maxOf(-ch[i - 1], 0.0)) / 14; rsi[i] = 100 - 100 / (1 + up / dn) }
        for (i in 15 until c.size) assertEquals(rsi[i], r.plots[0][i], 1e-9, "rsi at $i")
        // ATR: Wilder's average of the true range, seeded with the first 14.
        val tr = b.indices.map { i -> if (i == 0) b[i].high - b[i].low else maxOf(b[i].high - b[i].low, abs(b[i].high - b[i - 1].close), abs(b[i].low - b[i - 1].close)) }
        var atr = tr.take(14).average()
        assertEquals(atr, r.plots[1][13], 1e-9)
        for (i in 14 until b.size) { atr = (atr * 13 + tr[i]) / 14; assertEquals(atr, r.plots[1][i], 1e-9, "atr at $i") }
        // A variable's history is its value at the end of each bar.
        for (i in 1 until b.size) assertEquals(2 * c[i - 1], r.plots[2][i], 1e-9)
        assertEquals((c[0] * 1 + c[1] * 2 + c[2] * 3 + c[3] * 4) / 10, r.plots[3][3], 1e-9)
        val w = c.slice(0..4); val m = w.average()
        assertEquals(kotlin.math.sqrt(w.sumOf { (it - m) * (it - m) } / 5), r.plots[4][4], 1e-9)
    }

    @Test fun profitAndLossInTicksAndTrailing() {
        val t0 = 1790048700L
        fun bar(i: Int, o: Double, h: Double, l: Double, c: Double) = Pine.Bar(t0 + i * 300L, o, h, l, c, 0.0)
        val s = ok("strategy(\"t\")\nif bar_index == 0\n    strategy.entry(\"L\", strategy.long)\nstrategy.exit(\"X\", \"L\", profit = 200, loss = 100)\n")
        // mintick 0.05: profit 200 ticks = 10 points, loss 100 ticks = 5 points.
        val r = Pine.run(s, listOf(bar(0, 100.0, 100.0, 100.0, 100.0), bar(1, 100.0, 104.0, 99.0, 103.0), bar(2, 103.0, 111.0, 102.0, 110.0)))
        val t = r.report!!.trades.single()
        assertEquals(110.0, t.exitPrice, 1e-9); assertEquals(10.0, t.pnl, 1e-9)
        val tr = ok("strategy(\"t\")\nif bar_index == 0\n    strategy.entry(\"L\", strategy.long)\nstrategy.exit(\"T\", \"L\", trail_points = 20, trail_offset = 40)\n")
        // Trail starts once the high reaches entry + 1 point, then follows the best high 2 points back.
        val r2 = Pine.run(tr, listOf(bar(0, 100.0, 100.0, 100.0, 100.0), bar(1, 100.0, 106.0, 99.5, 105.0), bar(2, 105.0, 105.5, 103.0, 103.5)))
        val t2 = r2.report!!.trades.single()
        assertEquals(104.0, t2.exitPrice, 1e-9)
    }

    @Test fun gapThroughAStopFillsAtTheOpen() {
        val t0 = 1790048700L
        val s = ok("strategy(\"g\")\nif bar_index == 0\n    strategy.entry(\"S\", strategy.short)\nstrategy.exit(\"X\", \"S\", stop = 105)\n")
        val r = Pine.run(s, listOf(Pine.Bar(t0, 100.0, 100.0, 100.0, 100.0, 0.0), Pine.Bar(t0 + 300, 100.0, 101.0, 99.0, 100.0, 0.0),
            Pine.Bar(t0 + 600, 108.0, 109.0, 107.0, 108.0, 0.0)))
        val t = r.report!!.trades.single()
        assertEquals(108.0, t.exitPrice, 1e-9); assertEquals(-8.0, t.pnl, 1e-9)
    }
}
