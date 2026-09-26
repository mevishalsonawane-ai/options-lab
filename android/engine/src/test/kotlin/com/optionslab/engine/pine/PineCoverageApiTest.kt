package com.optionslab.engine.pine

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pine's public helpers: compile guards, report statistics, colours and literals. */
class PineCoverageApiTest {
    private val day = 86400L

    private fun trade(entryBar: Int, exitBar: Int, pnl: Double, entryTime: Long, exitTime: Long, long: Boolean = true, open: Boolean = false) =
        Pine.Trade("E", "X", long, 1.0, entryBar, entryTime, 100.0, exitBar, exitTime, 100.0 + pnl, pnl, pnl, 0.0, open)

    private fun report(trades: List<Pine.Trade>, equity: DoubleArray, capital: Double = 100.0): Pine.Report {
        val closed = trades.filter { !it.open }
        val wins = closed.filter { it.pnl > 0 }; val losses = closed.filter { it.pnl < 0 }
        return Pine.Report(capital, closed.sumOf { it.pnl }, 0.0, wins.sumOf { it.pnl }, -losses.sumOf { it.pnl }, null, closed.size, wins.size, losses.size, 0.0,
            0.0, if (wins.isEmpty()) 0.0 else wins.sumOf { it.pnl } / wins.size, if (losses.isEmpty()) 0.0 else losses.sumOf { it.pnl } / losses.size,
            0.0, 0.0, equity.let { e -> var pk = capital; var dd = 0.0; e.forEach { pk = maxOf(pk, it); dd = maxOf(dd, pk - it) }; dd }, 0.0, 0.0, 0.0, 0.0, 0.0, trades, equity)
    }

    @Test fun extraStatisticsOverSeveralDays() {
        // One candle a day for 7 days; equity at each day's close.
        val bars = List(7) { Pine.Bar(PC.T0 + it * day, 1.0, 1.0, 1.0, 1.0, 1.0) }
        val equity = doubleArrayOf(100.0, 110.0, 105.0, 120.0, 90.0, 95.0, 130.0)
        val t = listOf(
            trade(0, 1, 10.0, PC.T0, PC.T0 + day),                              // win, day 2
            trade(1, 2, -5.0, PC.T0 + day, PC.T0 + 2 * day, long = false),      // loss, day 3
            trade(2, 2, -3.0, PC.T0 + 2 * day, PC.T0 + 2 * day + 600),          // loss, day 3
            trade(3, 3, 0.0, PC.T0 + 3 * day, PC.T0 + 3 * day + 60),            // flat, day 4
            trade(4, 5, 7.0, PC.T0 + 4 * day, PC.T0 + 5 * day, long = false),   // win, day 6
            trade(5, 5, 1.0, PC.T0 + 5 * day, PC.T0 + 5 * day + 120),           // win, day 6
            trade(6, 9, 2.0, PC.T0 + 6 * day, PC.T0 + 9 * day),                 // win, 1 October (past the candles)
            trade(6, 6, 50.0, PC.T0 + 6 * day, PC.T0 + 6 * day, open = true),   // still open: not counted
        )
        val r = report(t, equity)
        val x = Pine.extraOf(r, bars)
        assertEquals(Pine.Side(5, 3, 60.0, 10.0, 2.0), x.long)
        assertEquals(Pine.Side(2, 1, 50.0, 2.0, 1.0), x.short)
        assertEquals(3, x.maxConsecWins); assertEquals(2, x.maxConsecLosses)      // the flat trade breaks both runs
        assertEquals((20.0 / 4) / (8.0 / 2), x.payoffRatio!!, 1e-12)
        assertEquals(r.netProfit / r.maxDrawdown, x.recoveryFactor!!, 1e-12)
        val eq = listOf(100.0) + equity.toList()
        val rets = (1 until eq.size).map { eq[it] / eq[it - 1] - 1 }
        val mean = rets.average(); val sd = sqrt(rets.sumOf { (it - mean) * (it - mean) } / (rets.size - 1))
        val dsd = sqrt(rets.filter { it < 0 }.sumOf { it * it } / rets.size)
        assertEquals(mean / sd * sqrt(252.0), x.sharpe!!, 1e-12); assertEquals(mean / dsd * sqrt(252.0), x.sortino!!, 1e-12)
        assertEquals(100.0, x.exposurePct)                                          // every candle had a trade on
        assertEquals(t.filter { !it.open }.map { (it.exitTime - it.entryTime) / 60.0 }.average(), x.avgMinutesInTrade, 1e-9)
        assertEquals(7, x.tradingDays); assertEquals(7.0 / 7, x.tradesPerDay)
        assertEquals(listOf("2026-09-23", "2026-09-24", "2026-09-25", "2026-09-27", "2026-10-01"), x.days.map { it.label })
        assertEquals(listOf(10.0, -8.0, 0.0, 8.0, 2.0), x.days.map { it.pnl })
        assertEquals(3, x.winningDays); assertEquals(1, x.losingDays)
        assertEquals("2026-09-23", x.bestDay!!.label); assertEquals("2026-09-24", x.worstDay!!.label)
        assertEquals(listOf("Sep 2026", "Oct 2026"), x.months.map { it.label }); assertEquals(listOf(6, 1), x.months.map { it.trades })
        assertEquals(30.0, x.returnPct, 1e-9)
        assertEquals(2, x.maxDrawdownDays)                                          // under the 120 peak from day 4 to day 6
    }

    @Test fun extraStatisticsWithNothingToGoOn() {
        val x = Pine.extraOf(report(emptyList(), DoubleArray(0)), emptyList())
        assertNull(x.payoffRatio); assertNull(x.recoveryFactor); assertNull(x.sharpe); assertNull(x.sortino)
        assertEquals(0.0, x.exposurePct); assertEquals(0.0, x.avgMinutesInTrade); assertEquals(0.0, x.tradesPerDay)
        assertEquals(0, x.tradingDays); assertNull(x.bestDay); assertNull(x.worstDay); assertEquals(0.0, x.returnPct); assertEquals(0, x.maxDrawdownDays)
        assertEquals(Pine.Side(0, 0, 0.0, 0.0, 0.0), x.long)
        // Only losing days: no best day; only winning: no worst day.
        val bars = List(2) { Pine.Bar(PC.T0 + it * day, 1.0, 1.0, 1.0, 1.0, 1.0) }
        val lose = Pine.extraOf(report(listOf(trade(0, 1, -1.0, PC.T0, PC.T0 + day)), doubleArrayOf(100.0, 99.0)), bars)
        assertNull(lose.bestDay); assertNotNull(lose.worstDay); assertNull(lose.payoffRatio)
        val win = Pine.extraOf(report(listOf(trade(0, 1, 1.0, PC.T0, PC.T0 + day)), doubleArrayOf(100.0, 101.0)), bars)
        assertNull(win.worstDay); assertNotNull(win.bestDay); assertNull(win.sharpe)          // under 5 days
    }

    @Test fun extraStatisticsWithAShortEquityCurveAndOddTrades() {
        // More candles than equity points, a zero equity, and trades reaching outside the candles.
        val bars = List(8) { Pine.Bar(PC.T0 + it * day, 1.0, 1.0, 1.0, 1.0, 1.0) }
        val equity = doubleArrayOf(100.0, 0.0, 50.0, 50.0, 50.0, 60.0)
        val t = listOf(trade(-2, 1, 1.0, PC.T0, PC.T0 + day), trade(6, 20, 1.0, PC.T0 + 6 * day, PC.T0 + 7 * day))
        val x = Pine.extraOf(report(t, equity), bars)
        assertEquals(6, x.tradingDays)                                               // days with an equity point
        assertEquals(4 * 100.0 / 8, x.exposurePct)                                   // bars 0, 1, 6, 7
        assertNotNull(x.sharpe)                                                      // the fall to 0 then a 0 return from it
        assertEquals(-40.0, x.returnPct, 1e-9)
        assertEquals(5, x.maxDrawdownDays)
    }

    @Test fun reportsCarryTheirExtra() {
        val r = Pine.signalBacktest(PC.bars(1.0, 2.0, 3.0, 2.0), booleanArrayOf(true), booleanArrayOf(false, false, true), reverse = false)
        assertNotNull(r.extra)
        assertEquals(1, r.extra!!.long.trades)
    }

    @Test fun safeColourConstantsAndColoursOf() {
        assertEquals("#A1B2C3", Pine.safeColor("#A1B2C3")); assertEquals("#a1b2c3ff", Pine.safeColor("#a1b2c3ff"))
        assertNull(Pine.safeColor(null)); assertNull(Pine.safeColor("red")); assertNull(Pine.safeColor("#12345")); assertNull(Pine.safeColor("#1234567"))
        fun e(src: String): Expr = Parser(Lexer.lex(src)).expr()
        assertEquals(-3.0, Pine.constOf(e("-3"))); assertEquals(3.0, Pine.constOf(e("+3"))); assertEquals(true, Pine.constOf(e("true")))
        assertEquals(null, Pine.constOf(e("-\"a\""))); assertEquals("a", Pine.constOf(e("+\"a\"")))
        assertEquals(6.0, Pine.constOf(e("2 * 3"))); assertEquals(-1.0, Pine.constOf(e("2 - 3"))); assertEquals(1.5, Pine.constOf(e("3 / 2")))
        assertNull(Pine.constOf(e("3 / 0"))); assertNull(Pine.constOf(e("3 % 2"))); assertNull(Pine.constOf(e("3 > 2")))
        assertNull(Pine.constOf(e("close + 1"))); assertNull(Pine.constOf(e("1 + close"))); assertNull(Pine.constOf(e("f(1)")))
        assertEquals("#FF0000", Pine.constOf(e("#FF0000"))); assertEquals("long", Pine.constOf(e("strategy.long")))
        assertEquals("#FF0000", Pine.colorOf(e("#FF000080"))); assertEquals("#F23645", Pine.colorOf(e("color.red")))
        assertEquals("#F23645", Pine.colorOf(e("color.new(color.red, 50)"))); assertNull(Pine.colorOf(e("color.new()")))
        assertEquals("#0A0B0C", Pine.colorOf(e("color.rgb(10, 11, 12)"))); assertEquals("#FF0000", Pine.colorOf(e("color.rgb(300, -1, 0)")))
        assertNull(Pine.colorOf(e("color.rgb(1, 2)"))); assertNull(Pine.colorOf(e("color.rgb(1, 2, x)")))
        assertNull(Pine.colorOf(e("f(1)"))); assertNull(Pine.colorOf(e("1"))); assertNull(Pine.colorOf(null)); assertNull(Pine.colorOf(e("close")))
        assertEquals("#F23645", Pine.colorOf(e("x ? color.red : color.blue"))); assertEquals("#2962FF", Pine.colorOf(e("x ? y : color.blue")))
        assertNull(Pine.colorOf(e("x ? y : z")))
        assertEquals(39, Pine.weekOfYear(PC.T0)); assertEquals(1, Pine.weekOfYear(1767225600L))       // 1 Jan 2026 (IST)
        assertEquals("Line 3:4  oops", Pine.Problem(3, 4, "oops").toString())
    }

    @Test fun deepFunctionChainsInsideSecurityDoNotCrashTheChecker() {
        // Each function calls the one before; request.security walks all their bodies.
        val sb = StringBuilder("indicator(\"x\")\nf0(x) => x\n")
        for (i in 1 until 4000) sb.append("f$i(x) => f${i - 1}(x)\n")
        sb.append("plot(request.security(syminfo.tickerid, \"D\", f3999(close)))\n")
        var result: Pine.Compiled? = null
        val t = Thread(null, { result = Pine.compile(sb.toString()) }, "small-stack", 256 * 1024)
        t.start(); t.join()
        val c = assertNotNull(result)
        // On a small stack the walk overflows: refused cleanly, never a crash.
        assertEquals(listOf("The script is nested too deeply to check"), (c as Pine.Compiled.Failed).errors.map { it.message })
    }

    @Test fun deepRecursionAtRunTimeIsCaught() {
        // 100 nested user calls, each deep inside a long expression: on a small stack this overflows, and must end in an error.
        val deep = List(110) { "1 + (" }.joinToString("") + "x" + ")".repeat(110)
        val sb = StringBuilder("indicator(\"x\")\nf0(x) => $deep\n")
        for (i in 1..99) sb.append("f$i(x) => ${deep.replace("x)", "f${i - 1}(x))")}\n")
        sb.append("plot(f99(1))\n")
        val s = PC.ok(sb.toString())
        var run: Pine.Run? = null
        val t = Thread(null, { run = Pine.run(s, PC.bars(1.0)) }, "small-stack", 256 * 1024)
        t.start(); t.join()
        val r = assertNotNull(run)
        assertEquals("The script recursed too deeply", assertNotNull(r.error).message)
        assertEquals(0, r.error!!.line)
    }
}
