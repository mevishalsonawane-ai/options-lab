package com.optionslab.engine.pine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class PineFeaturesTest {
    private val t0 = 1790048700L            // 2026-09-22 09:15 IST
    private fun bars(vararg close: Double, step: Long = 300L) = close.mapIndexed { i, c ->
        val o = if (i == 0) c else close[i - 1]
        Pine.Bar(t0 + i * step, o, maxOf(o, c) + 1, minOf(o, c) - 1, c, 100.0)
    }
    private fun ok(src: String) = when (val r = Pine.compile(src)) {
        is Pine.Compiled.Ok -> r.script
        is Pine.Compiled.Failed -> fail("expected to compile: ${r.errors}")
    }
    private fun plot(src: String, b: List<Pine.Bar>, i: Int = 0): DoubleArray {
        val r = Pine.run(ok(src), b)
        assertNull(r.error, r.error?.toString())
        return r.plots[i]
    }

    @Test fun switchWithAndWithoutSubject() {
        val src = """
            indicator("s")
            mode = input.string("b", "Mode")
            k = switch mode
                "a" => 1
                "b" => 2
                => 3
            size = switch
                close > 3 => "big"
                close > 1 => "mid"
                => "small"
            plot(k)
            plot(size == "big" ? 2 : size == "mid" ? 1 : 0)
        """.trimIndent()
        val b = bars(1.0, 2.0, 5.0)
        assertEquals(2.0, plot(src, b)[0], 1e-9)
        assertEquals(listOf(0.0, 1.0, 2.0), plot(src, b, 1).toList())
    }

    @Test fun arraysMethodsAndForIn() {
        val src = """
            indicator("a")
            var float[] last = array.new_float(0)
            last.push(close)
            if last.size() > 3
                last.shift()
            total = 0.0
            for x in last
                total += x
            idxSum = 0.0
            for [i, x] in last
                idxSum += i
            a = array.from(3, 1, 2)
            array.sort(a)
            plot(total)
            plot(array.avg(last))
            plot(idxSum)
            plot(array.get(a, 0) * 100 + array.get(a, -1))
            plot(array.size(array.new<float>(4, 0)))
        """.trimIndent()
        val b = bars(1.0, 2.0, 3.0, 4.0, 5.0)
        val r = Pine.run(ok(src), b)
        assertNull(r.error, r.error?.toString())
        assertEquals(12.0, r.plots[0][4], 1e-9)             // 3 + 4 + 5
        assertEquals(4.0, r.plots[1][4], 1e-9)
        assertEquals(3.0, r.plots[2][4], 1e-9)              // 0 + 1 + 2
        assertEquals(103.0, r.plots[3][0], 1e-9)            // sorted 1..3
        assertEquals(4.0, r.plots[4][0], 1e-9)
    }

    @Test fun arrayErrorsStopTheRun() {
        val r = Pine.run(ok("indicator(\"x\")\na = array.new_float(2)\nplot(array.get(a, 5))\n"), bars(1.0))
        assertTrue(assertNotNull(r.error).message.contains("out of range"))
    }

    @Test fun securityOnADailyTimeframeHasNoLookAhead() {
        // Two days of 5-minute candles: day 1 closes at 10..84, day 2 at 200..274.
        val day1 = (0 until 75).map { 10.0 + it }
        val day2 = (0 until 75).map { 200.0 + it }
        val b1 = day1.mapIndexed { i, c -> Pine.Bar(t0 + i * 300L, c, c + 1, c - 1, c, 1.0) }
        val b2 = day2.mapIndexed { i, c -> Pine.Bar(t0 + 86400 + i * 300L, c, c + 1, c - 1, c, 1.0) }
        val b = b1 + b2
        val src = "indicator(\"d\")\ndc = request.security(syminfo.tickerid, \"D\", close)\nplot(dc)\nplot(request.security(syminfo.tickerid, \"D\", close, lookahead = barmerge.lookahead_on))\nplot(ta.change(time(\"D\")) != 0 ? 1 : 0)\n"
        val r = Pine.run(ok(src), b, symbol = "NIFTY")
        assertNull(r.error, r.error?.toString())
        assertTrue(r.plots[0][10].isNaN())                    // day 1 not closed yet
        assertEquals(84.0, r.plots[0][74], 1e-9)             // the candle that closes day 1
        assertEquals(84.0, r.plots[0][80], 1e-9)             // during day 2: yesterday's close
        assertEquals(274.0, r.plots[1][80], 1e-9)            // look-ahead on: today's final close
        assertEquals(1.0, r.plots[2][75], 1e-9)              // a new day starts
        assertEquals(0.0, r.plots[2][76], 1e-9)
    }

    @Test fun securityRefusesOtherSymbolsAndChartVariables() {
        val r = Pine.run(ok("indicator(\"x\")\nplot(request.security(\"NSE:BANKNIFTY\", \"D\", close))\n"), bars(1.0, 2.0), symbol = "NIFTY")
        assertTrue(assertNotNull(r.error).message.contains("another symbol"))
        val c = Pine.compile("indicator(\"x\")\nx = close * 2\nplot(request.security(syminfo.tickerid, \"D\", x))\n")
        assertTrue(c is Pine.Compiled.Failed && c.errors.any { it.message.contains("only use built-in") })
        // An input is fine inside it.
        ok("indicator(\"x\")\nlen = input.int(3, \"Len\")\nplot(request.security(syminfo.tickerid, \"60\", ta.sma(close, len)))\n")
    }

    @Test fun partialExitsTakeTheirPieces() {
        val src = """
            strategy("p")
            if bar_index == 0
                strategy.entry("L", strategy.long, qty = 4)
            strategy.exit("TP1", "L", qty_percent = 50, limit = 105)
            strategy.exit("TP2", "L", limit = 110)
        """.trimIndent()
        val b = listOf(
            Pine.Bar(t0, 100.0, 100.0, 100.0, 100.0, 0.0),
            Pine.Bar(t0 + 300, 100.0, 101.0, 99.0, 100.0, 0.0),        // entry at 100
            Pine.Bar(t0 + 600, 100.0, 106.0, 99.0, 105.0, 0.0),        // TP1: 2 at 105
            Pine.Bar(t0 + 900, 105.0, 111.0, 104.0, 110.0, 0.0),       // TP2: the other 2 at 110
        )
        val r = Pine.run(ok(src), b)
        val t = r.report!!.trades
        assertEquals(2, t.size)
        assertEquals(2.0, t[0].qty, 1e-9); assertEquals(105.0, t[0].exitPrice, 1e-9); assertEquals("TP1", t[0].exitId)
        assertEquals(2.0, t[1].qty, 1e-9); assertEquals(110.0, t[1].exitPrice, 1e-9)
        assertEquals(30.0, r.report!!.netProfit, 1e-9)
        val c = ok("strategy(\"c\")\nif bar_index == 0\n    strategy.entry(\"L\", strategy.long, qty = 10)\nif bar_index == 2\n    strategy.close(\"L\", qty_percent = 30)\n")
        val r2 = Pine.run(c, bars(100.0, 100.0, 100.0, 102.0, 102.0))
        assertEquals(3.0, r2.report!!.trades.first { !it.open }.qty, 1e-9)
        assertEquals(7.0, r2.position.last(), 1e-9)
    }

    @Test fun slippageAndCostsReduceTheResult() {
        val src = "strategy(\"c\")\nif bar_index == 0\n    strategy.entry(\"L\", strategy.long, qty = 1)\nif bar_index == 2\n    strategy.close(\"L\")\n"
        val b = bars(100.0, 100.0, 100.0, 110.0, 110.0)
        val plain = Pine.run(ok(src), b).report!!.netProfit
        val costly = Pine.run(ok(src), b, costs = Pine.Costs(slippagePoints = 1.0, perOrder = 20.0)).report!!.netProfit
        // 2 points of slippage (1 each side) and 40 rupees of orders.
        assertEquals(plain - 2.0 - 40.0, costly, 1e-9)
        val viaScript = Pine.run(ok(src.replace("strategy(\"c\")", "strategy(\"c\", slippage = 20)")), b).report!!.netProfit
        assertEquals(plain - 2.0, viaScript, 1e-9)        // 20 ticks of 0.05 = 1 point a side
    }

    @Test fun optimiserRanksCombinationsAndSplitsInAndOutOfSample() {
        val src = """
            strategy("o")
            fastLen = input.int(5, "Fast")
            slowLen = input.int(20, "Slow")
            fast = ta.sma(close, fastLen)
            slow = ta.sma(close, slowLen)
            if ta.crossover(fast, slow)
                strategy.entry("L", strategy.long)
            if ta.crossunder(fast, slow)
                strategy.close("L")
        """.trimIndent()
        val c = DoubleArray(800) { 100 + 10 * kotlin.math.sin(it / 15.0) + it * 0.01 }
        val b = bars(*c)
        val plan = PineOptimise.Plan(listOf(PineOptimise.Range("Fast", 3.0, 9.0, 3.0), PineOptimise.Range("Slow", 20.0, 40.0, 10.0)), inSamplePct = 70)
        assertEquals(9, PineOptimise.combos(plan.ranges))
        var calls = 0
        val res = PineOptimise.run(ok(src), b, emptyMap(), plan) { _, _ -> calls++ }
        assertEquals(9, res.rows.size); assertEquals(9, calls); assertTrue(!res.stoppedEarly)
        assertTrue(res.rows.zipWithNext().all { (a, z) -> a.inSample.net >= z.inSample.net })
        assertNotNull(res.rows.first().outSample)
        // A single combination equals a plain run split by entry time.
        val one = res.rows.first { it.values == mapOf("Fast" to 3.0, "Slow" to 20.0) }
        val full = Pine.run(ok(src), b, mapOf("Fast" to 3.0, "Slow" to 20.0)).report!!.trades.filter { !it.open }
        assertEquals(full.sumOf { it.pnl }, one.inSample.net + one.outSample!!.net, 1e-6)
    }
}

class PinePremiumTest {
    @Test fun tradesArePricedOnTheAtmOptionWithCharges() {
        val day = java.time.LocalDate.of(2026, 9, 22)
        val expiry = java.time.LocalDate.of(2026, 9, 23)
        val mins = IntArray(60) { 555 + it }                   // 09:15 .. 10:14
        fun opt(k: Double, r: com.optionslab.engine.Right, f: (Int) -> Double) =
            com.optionslab.engine.Series(expiry, k, r, 75, mins, DoubleArray(60) { f(it) }, DoubleArray(60) { f(it) }, null, null, null, LongArray(60))
        val session = com.optionslab.engine.Session(day, 75, listOf(
            opt(25000.0, com.optionslab.engine.Right.CE) { 100.0 + it }, opt(25000.0, com.optionslab.engine.Right.PE) { 100.0 - it * 0.5 },
            opt(25050.0, com.optionslab.engine.Right.CE) { 80.0 + it }))
        val t0 = 1790048700L                                      // 09:15 IST
        val bars = List(60) { Pine.Bar(t0 + it * 60L, 25000.0, 25001.0, 24999.0, 25000.0, 0.0) }
        val trade = Pine.Trade("L", "S", true, 1.0, 5, t0 + 5 * 60, 25010.0, 25, t0 + 25 * 60, 25030.0, 20.0, 0.08, 0.0, false)
        val short = Pine.Trade("S", "X", false, 1.0, 30, t0 + 30 * 60, 25004.0, 40, t0 + 40 * 60, 24990.0, 14.0, 0.05, 0.0, false)
        val r = PinePremium.run(listOf(trade, short), bars, { if (it == day) session else null }, strikeStep = 50, lots = 2, capital = 100_000.0)
        assertEquals(2, r.priced); assertEquals(0, r.skipped)
        val ce = r.report!!.trades[0]
        assertEquals(105.0, ce.entryPrice, 1e-9); assertEquals(125.0, ce.exitPrice, 1e-9); assertEquals(150.0, ce.qty, 1e-9)
        assertTrue(ce.commission > 0); assertEquals((125.0 - 105.0) * 150 - ce.commission, ce.pnl, 1e-6)
        val pe = r.report!!.trades[1]
        assertTrue(pe.entryId.endsWith("PE")); assertEquals(85.0, pe.entryPrice, 1e-9); assertEquals(80.0, pe.exitPrice, 1e-9)
        // A day without data is skipped with its reason.
        val later = trade.copy(entryTime = t0 + 86400 * 3, exitTime = t0 + 86400 * 3 + 600)
        val r2 = PinePremium.run(listOf(later), bars, { if (it == day) session else null }, 50, 1, 100_000.0)
        assertEquals(0, r2.priced); assertEquals(1, r2.reasons["no option data for the entry day"])
    }
}
