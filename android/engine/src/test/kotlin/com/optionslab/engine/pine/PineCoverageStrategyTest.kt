package com.optionslab.engine.pine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** strategy.* calls, every argument and setting, and the broker emulator's less travelled paths. */
class PineCoverageStrategyTest {
    private fun flat(i: Int, px: Double = 100.0) = PC.bar(i, px, px + 0.5, px - 0.5, px)
    private fun flats(vararg px: Double) = px.mapIndexed { i, p -> flat(i, p) }
    private fun run(src: String, bars: List<Pine.Bar>, qty: Double? = null, costs: Pine.Costs = Pine.Costs()) = PC.run(src, bars, qty = qty, costs = costs)
    private fun settings(decl: String) = PC.ok("$decl\nplot(close)").settings

    // ---- the declaration's settings --------------------------------------------------------

    @Test fun declarationSettingsAndTheirFallbacks() {
        val s = PC.ok("strategy(\"T\", overlay = true, initial_capital = 5000, default_qty_type = strategy.cash, default_qty_value = 250, pyramiding = 3, " +
            "commission_type = strategy.commission.cash_per_order, commission_value = 2, process_orders_on_close = true, slippage = 4)\nplot(close)")
        assertEquals("T", s.title); assertTrue(s.overlay); assertEquals(Pine.Kind.STRATEGY, s.kind)
        assertEquals(Pine.Settings(5000.0, "cash", 250.0, 3, "cash_per_order", 2.0, true, 4.0), s.settings)
        // Out of range or of the wrong kind: TradingView's defaults.
        assertEquals(Pine.Settings(), settings("strategy(\"T\", initial_capital = 1e14, default_qty_type = \"weird\", default_qty_value = 0, pyramiding = -3, " +
            "commission_type = \"bogus\", commission_value = 2e6, process_orders_on_close = 1, slippage = -1)"))
        assertEquals(1_000_000.0, settings("strategy(\"T\", initial_capital = 1e308 * 10)").initialCapital)           // not finite
        assertEquals(1_000_000.0, settings("strategy(\"T\", initial_capital = 10 / 0)").initialCapital)              // no value
        assertEquals(1_000_000.0, settings("strategy(\"T\", initial_capital = close)").initialCapital)               // not a literal
        assertEquals(10_000.0, settings("strategy(\"T\", slippage = 20000)").slippageTicks)
        assertEquals(100, settings("strategy(\"T\", pyramiding = 500)").pyramiding)
        assertEquals("percent_of_equity", settings("strategy(\"T\", default_qty_type = strategy.percent_of_equity)").qtyType)
        assertEquals("cash_per_contract", settings("strategy(\"T\", commission_type = strategy.commission.cash_per_contract)").commissionType)
        // Titles that are not text; indicators have no strategy settings.
        assertEquals("Strategy", PC.ok("strategy(5)").title); assertEquals("Indicator", PC.ok("indicator(5)\nplot(close)").title)
        assertEquals("Study", PC.ok("study(\"Study\", overlay = \"yes\")\nplot(close)").title)
        assertTrue(!PC.ok("indicator(\"i\", overlay = \"yes\")\nplot(close)").overlay)
        assertEquals(Pine.Settings(), PC.ok("indicator(\"i\")\nplot(close)").settings)
        assertEquals(Pine.Kind.INDICATOR, PC.ok("study(\"s\")\nplot(close)").kind)
        // Computed literals: (1 + 1) * 1000 - 500 / 2.
        assertEquals(1750.0, settings("strategy(\"T\", initial_capital = (1 + 1) * 1000 - 500 / 2)").initialCapital)
        assertEquals(1_000_000.0, settings("strategy(\"T\", initial_capital = 5 % 3)").initialCapital)             // % is not folded
        assertEquals(1_000_000.0, settings("strategy(\"T\", initial_capital = 5 + \"a\")").initialCapital)
        assertEquals(1_000_000.0, settings("strategy(\"T\", initial_capital = -\"a\")").initialCapital)
    }

    // ---- when = false, and the other arguments of each call ------------------------------------

    @Test fun whenFalseSkipsEveryCall() {
        val src = """
            strategy("w")
            strategy.entry("E", strategy.long, when = false)
            strategy.order("O", strategy.long, when = false)
            if bar_index == 0
                strategy.entry("L", strategy.long, qty = 2)
                strategy.entry("P", strategy.long, stop = 1000)
            strategy.close("L", when = false)
            strategy.close_all(when = false)
            strategy.exit("x", "L", stop = 1000, when = false)
            strategy.cancel("P", when = false)
            strategy.cancel_all(when = false)
        """
        val r = run(src, flats(100.0, 100.0, 100.0))
        assertEquals(listOf(0.0, 2.0, 2.0), r.position.toList())
        assertTrue(PC.closed(r).isEmpty())
        // With when = true they act: cancel_all at the end of each bar drops every order placed on it.
        val on = run(src.replace("when = false", "when = true"), flats(100.0, 100.0, 100.0))
        assertEquals(listOf(0.0, 0.0, 0.0), on.position.toList())
        assertTrue(on.report!!.trades.isEmpty())
        // Without the cancels, E and the strategy.order O fill (O ignores pyramiding; L is refused by it).
        val kept = run(src.replace("when = false", "when = true").lines().filter { !it.contains("cancel") }.joinToString("\n"), flats(100.0, 100.0, 100.0))
        assertEquals(listOf("E", "O"), kept.report!!.trades.filter { it.entryBar == 1 }.map { it.entryId })
    }

    @Test fun badEntryArgumentsStopTheRun() {
        val b = flats(100.0, 100.0)
        PC.runError("strategy(\"s\")\nstrategy.entry(na, strategy.long)", b, "strategy.entry() needs an id")
        PC.runError("strategy(\"s\")\nstrategy.entry(\"L\", \"sideways\")", b, "strategy.entry(): direction must be strategy.long or strategy.short")
        PC.runError("strategy(\"s\")\nstrategy.order(\"L\", na)", b, "strategy.order(): direction must be strategy.long or strategy.short")
        PC.runError("strategy(\"s\")\nstrategy.entry(\"L\", strategy.long, qty = 0)", b, "qty must be above 0 and at most 1e9")
        PC.runError("strategy(\"s\")\nstrategy.entry(\"L\", strategy.long, qty = -1)", b, "qty must be above 0")
        PC.runError("strategy(\"s\")\nstrategy.exit(na, stop = 1)", b, "strategy.exit() needs an id")
    }

    @Test fun directionAsABoolAndQtyNa() {
        val r = run("strategy(\"s\")\nif bar_index == 0\n    strategy.entry(\"L\", true, qty = na)\nif bar_index == 1\n    strategy.entry(\"S\", false, qty = 3)", flats(100.0, 100.0, 100.0))
        assertEquals(listOf(0.0, 1.0, -3.0), r.position.toList())
        // Long ids are cut to 64 characters.
        val id = "x".repeat(100)
        val t = run("strategy(\"s\")\nstrategy.entry(\"$id\", strategy.long)", flats(100.0, 100.0)).report!!.trades.single()
        assertEquals(64, t.entryId.length)
    }

    @Test fun closeImmediatelyAndInPieces() {
        val src = """
            strategy("c")
            if bar_index == 0
                strategy.entry("L", strategy.long, qty = 4)
            if bar_index == 2
                strategy.close("L", qty = 1, immediately = true)
            if bar_index == 3
                strategy.close("L", qty_percent = 50, immediately = true)
            if bar_index == 4
                strategy.close_all(immediately = true)
        """
        val b = flats(100.0, 100.0, 101.0, 102.0, 103.0, 104.0)
        val r = run(src, b)
        val t = PC.closed(r)
        assertEquals(listOf(1.0, 1.5, 1.5), t.map { it.qty })
        assertEquals(listOf(101.0, 102.0, 103.0), t.map { it.exitPrice })        // each at its bar's close
        assertEquals(listOf(2, 3, 4), t.map { it.exitBar })
        assertEquals(listOf("Close L", "Close L", "Close all"), t.map { it.exitId })
        assertEquals(t.map { b[it.exitBar].time + 299 }, t.map { it.exitFillTime })     // by the close
        assertEquals(listOf(0.0, 4.0, 3.0, 1.5, 0.0, 0.0), r.position.toList())
        // Bad amounts are ignored (the whole entry closes) and a percentage is capped at 100.
        val whole = run(src.replace("qty = 1, ", "qty = -1, ").replace("qty_percent = 50", "qty_percent = 500"), b)
        assertEquals(listOf(4.0), PC.closed(whole).map { it.qty })
        // Closing an id with nothing open does nothing.
        val none = run("strategy(\"c\")\nstrategy.close(\"nothing\", immediately = true)\nstrategy.close(\"nothing\", qty = 1, immediately = true)", b)
        assertTrue(none.report!!.trades.isEmpty())
    }

    @Test fun waitingPartialClosesOfEveryEntry() {
        val src = """
            strategy("c", pyramiding = 2)
            if bar_index == 0
                strategy.entry("A", strategy.long, qty = 2)
            if bar_index == 1
                strategy.entry("B", strategy.long, qty = 2)
            if bar_index == 2
                strategy.close_all()
                strategy.close(na, qty = 3)
        """
        val r = run(src, flats(100.0, 100.0, 100.0, 100.0, 100.0))
        // The partial close of 3 is taken oldest first (A 2, B 1)... after the close-all already took everything.
        assertEquals(listOf("Close all", "Close all"), PC.closed(r).map { it.exitId })
        assertEquals(0.0, r.position.last())
        val part = run(src.lines().filter { !it.contains("close_all") }.joinToString("\n"), flats(100.0, 100.0, 100.0, 100.0, 100.0))
        assertEquals(listOf(2.0, 1.0), PC.closed(part).map { it.qty })
        assertEquals(listOf("Close all", "Close all"), PC.closed(part).map { it.exitId })
        assertEquals(1.0, part.position.last())
    }

    @Test fun exitsWithTicksForShortsAndOnlyQuantities() {
        // Short at 100; profit 100 ticks (95) and loss 100 ticks (105) of 0.05.
        val src = """
            strategy("x")
            if bar_index == 0
                strategy.entry("S", strategy.short, qty = 2)
            strategy.exit("x", "S", profit = 100, loss = 100, qty = 1)
            strategy.exit("y", "S", qty = 1)
        """
        val r = run(src, listOf(flat(0), flat(1), PC.bar(2, 100.0, 101.0, 94.0, 95.0), flat(3, 95.0)))
        val t = PC.closed(r).single()
        assertEquals("x", t.exitId); assertEquals(95.0, t.exitPrice); assertEquals(1.0, t.qty); assertEquals(5.0, t.pnl, 1e-9)
        assertTrue(t.exitIntrabar)
        // The exit with no price never fills: one unit stays open.
        assertEquals(-1.0, r.position.last())
        // The stop side for a short: price rising to 105.
        val up = run(src, listOf(flat(0), flat(1), PC.bar(2, 100.0, 106.0, 99.5, 104.0), flat(3, 104.0)))
        assertEquals(105.0, PC.closed(up).single().exitPrice); assertEquals(-5.0, PC.closed(up).single().pnl, 1e-9)
    }

    @Test fun exitsForAnyEntryGoWhenFlat() {
        // An exit with no from_entry covers every entry; once flat it is dropped and does not touch a later entry.
        val src = """
            strategy("x")
            if bar_index == 0
                strategy.entry("L", strategy.long)
                strategy.exit("all", stop = 95)
            if bar_index == 2
                strategy.close("L")
            if bar_index == 4
                strategy.entry("L2", strategy.long)
        """
        val r = run(src, flats(100.0, 100.0, 100.0, 100.0, 100.0, 100.0) + PC.bar(6, 100.0, 100.5, 90.0, 92.0))
        assertEquals(listOf("Close L"), PC.closed(r).map { it.exitId })
        assertEquals("L2", r.report!!.trades.single { it.open }.entryId)
        // While the entry is open it does fill.
        val hit = run(src, flats(100.0, 100.0) + PC.bar(2, 100.0, 100.5, 90.0, 92.0) + flats(92.0))
        assertEquals("all", PC.closed(hit).first().exitId)
    }

    @Test fun trailingStopForAShort() {
        // Short at 100; trail activates 5 below (100 ticks), 2 behind (40 ticks). Path high first: 100 -> 100.5 -> 90 -> 97:
        // best 90, stop 92, filled on the way up.
        val src = """
            strategy("t")
            if bar_index == 0
                strategy.entry("S", strategy.short)
                strategy.exit("tr", "S", trail_points = 100, trail_offset = 40, stop = 110)
        """
        val r = run(src, listOf(flat(0), flat(1), PC.bar(2, 100.0, 100.5, 90.0, 97.0), flat(3, 97.0)))
        val t = PC.closed(r).single()
        assertEquals(92.0, t.exitPrice, 1e-9); assertEquals(2, t.exitBar)
        // A trail_price activation level and a trail that never activates keep the fixed stop.
        val never = run(src.replace("trail_points = 100", "trail_price = 80"), listOf(flat(0), flat(1), PC.bar(2, 100.0, 111.0, 99.0, 105.0), flat(3, 105.0)))
        assertEquals(110.0, PC.closed(never).single().exitPrice, 1e-9)
    }

    @Test fun shortStopAndLimitEntriesAndGaps() {
        fun entry(order: String, bar1: Pine.Bar) =
            run("strategy(\"e\")\nif bar_index == 0\n    $order", listOf(flat(0), bar1, flat(2, bar1.close))).report!!.trades.singleOrNull()
        // A short stop fills on the way down, a short limit on the way up.
        assertEquals(95.0, entry("strategy.entry(\"S\", strategy.short, stop = 95)", PC.bar(1, 100.0, 101.0, 90.0, 92.0))!!.entryPrice)
        assertEquals(105.0, entry("strategy.entry(\"S\", strategy.short, limit = 105)", PC.bar(1, 100.0, 107.0, 99.0, 101.0))!!.entryPrice)
        // Levels already passed at the open fill at the open.
        val gap = entry("strategy.entry(\"L\", strategy.long, stop = 105)", PC.bar(1, 110.0, 111.0, 109.0, 110.0))!!
        assertEquals(110.0, gap.entryPrice); assertTrue(!gap.entryIntrabar); assertEquals(PC.T0 + 300, gap.entryFillTime)
        assertEquals(90.0, entry("strategy.entry(\"S\", strategy.short, stop = 95)", PC.bar(1, 90.0, 91.0, 89.0, 90.0))!!.entryPrice)
        // Never reached: nothing.
        assertNull(entry("strategy.entry(\"S\", strategy.short, limit = 200)", PC.bar(1, 100.0, 101.0, 99.0, 100.0)))
        // A short stop-limit armed at 95 whose limit (96) is not on offer then fills as a limit when the price comes back.
        val sl = run("strategy(\"e\")\nif bar_index == 0\n    strategy.entry(\"S\", strategy.short, stop = 95, limit = 96)",
            listOf(flat(0), PC.bar(1, 100.0, 100.5, 90.0, 91.0), PC.bar(2, 91.0, 97.0, 90.5, 96.0), flat(3, 96.0)))
        val t = sl.report!!.trades.single()
        assertEquals(2, t.entryBar); assertEquals(96.0, t.entryPrice)
    }

    @Test fun pyramidingAndAveragePrice() {
        val src = """
            strategy("p", pyramiding = 2)
            strategy.entry("L", strategy.long, qty = 1)
            plot(nz(strategy.position_avg_price, -1))
        """
        val r = run(src, flats(100.0, 102.0, 106.0, 110.0))
        assertEquals(listOf(0.0, 1.0, 2.0, 2.0), r.position.toList())
        assertEquals(listOf(-1.0, 102.0, 104.0, 104.0), r.plots[0].toList())
    }

    @Test fun strategyValuesThroughARun() {
        val src = """
            strategy("v", initial_capital = 1000)
            if bar_index == 0
                strategy.entry("L", strategy.long, qty = 2)
            if bar_index == 2
                strategy.close("L")
            if bar_index == 3
                strategy.entry("S", strategy.short, qty = 1)
            plot(nz(strategy.position_avg_price, -1))
            plot(strategy.equity)
            plot(strategy.netprofit)
            plot(strategy.openprofit)
            plot(strategy.opentrades)
            plot(strategy.closedtrades)
            plot(strategy.wintrades)
            plot(strategy.losstrades)
            plot(strategy.initial_capital)
            plot(strategy.grossprofit)
            plot(strategy.grossloss)
            plot(strategy.max_drawdown)
            plot(strategy.position_entry_name == "S" ? 1 : strategy.position_entry_name == "L" ? 2 : 0)
            plot(strategy.long == "long" and strategy.direction.short == "short" ? 1 : 0)
        """
        val r = run(src, flats(100.0, 100.0, 110.0, 105.0, 108.0, 90.0))
        fun p(i: Int) = r.plots[i].map { it + 0.0 }          // -0.0 (an empty sum negated) reads as 0
        assertEquals(listOf(-1.0, 100.0, 100.0, -1.0, 108.0, 108.0), p(0))
        assertEquals(listOf(1000.0, 1000.0, 1020.0, 1010.0, 1010.0, 1028.0), p(1))
        assertEquals(listOf(0.0, 0.0, 0.0, 10.0, 10.0, 10.0), p(2))
        assertEquals(listOf(0.0, 0.0, 20.0, 0.0, 0.0, 18.0), p(3))
        assertEquals(listOf(0.0, 1.0, 1.0, 0.0, 1.0, 1.0), p(4))
        assertEquals(listOf(0.0, 0.0, 0.0, 1.0, 1.0, 1.0), p(5))
        assertEquals(listOf(0.0, 0.0, 0.0, 1.0, 1.0, 1.0), p(6))
        assertEquals(List(6) { 0.0 }, p(7))
        assertEquals(List(6) { 1000.0 }, p(8))
        assertEquals(listOf(0.0, 0.0, 0.0, 10.0, 10.0, 10.0), p(9))
        assertEquals(List(6) { 0.0 }, p(10))
        assertEquals(listOf(0.0, 0.0, 0.0, 10.0, 10.0, 10.0), p(11))
        assertEquals(listOf(0.0, 2.0, 2.0, 0.0, 1.0, 1.0), p(12))
        assertEquals(List(6) { 1.0 }, p(13))
        // A losing trade.
        val loss = run("strategy(\"l\")\nif bar_index == 0\n    strategy.entry(\"L\", strategy.long)\nif bar_index == 1\n    strategy.close(\"L\")\nplot(strategy.losstrades)\nplot(strategy.grossloss)\nplot(strategy.wintrades)",
            flats(100.0, 100.0, 90.0, 90.0))
        assertEquals(listOf(0.0, 0.0, 1.0, 1.0), loss.plots[0].toList()); assertEquals(listOf(0.0, 0.0, 10.0, 10.0), loss.plots[1].map { it + 0.0 })
        assertEquals(List(4) { 0.0 }, loss.plots[2].map { it + 0.0 })
        assertEquals(0.0, loss.report!!.profitFactor); assertEquals(0.0, loss.report!!.winRate)
        assertEquals(-10.0, loss.report!!.largestLoss); assertEquals(0.0, loss.report!!.largestWin)
    }

    // ---- sizes and costs ---------------------------------------------------------------------

    @Test fun cashSizingAndTooSmallOrders() {
        val src = "strategy(\"c\", default_qty_type = strategy.cash, default_qty_value = 250)\nif bar_index == 0\n    strategy.entry(\"L\", strategy.long)"
        assertEquals(2.0, run(src, flats(100.0, 100.0)).report!!.trades.single().qty)
        // Less cash than one unit costs: no trade, and none expected next either.
        val tiny = run(src.replace("250", "50"), flats(100.0, 100.0))
        assertTrue(tiny.report!!.trades.isEmpty())
        assertEquals(0.0, run(src.replace("250", "50").replace("if bar_index == 0\n    ", ""), flats(100.0, 100.0)).nextPosition)
    }

    @Test fun commissionKinds() {
        val src = "strategy(\"c\", commission_type = strategy.commission.KIND, commission_value = 5)\nif bar_index == 0\n    strategy.entry(\"L\", strategy.long, qty = 3)\nif bar_index == 1\n    strategy.close(\"L\")"
        val b = flats(100.0, 100.0, 100.0, 100.0)
        assertEquals(10.0, run(src.replace("KIND", "cash_per_order"), b).report!!.commission, 1e-9)
        assertEquals(30.0, run(src.replace("KIND", "cash_per_contract"), b).report!!.commission, 1e-9)
        assertEquals(30.0, run(src.replace("KIND", "percent"), b).report!!.commission, 1e-9)       // 5% of 300, twice
        // An unknown kind (only possible by building the settings directly) costs nothing.
        val markers = ArrayList<Pine.Marker>()
        val br = Broker(Pine.Settings(commissionType = "weird", commissionValue = 5.0, qtyType = "weird", qtyValue = 2.0), null, 0.05, b, markers)
        br.beforeBar(0); br.entry("L", true, null, null, null, 0, false); br.afterScript(0)
        br.beforeBar(1); br.afterScript(1)
        val rep = br.report()
        assertEquals(0.0, rep.commission); assertEquals(2.0, rep.trades.single().qty)
    }

    @Test fun runOverridesQuantityAndSanitisesCosts() {
        val src = "strategy(\"q\")\nif bar_index == 0\n    strategy.entry(\"L\", strategy.long, qty = 5)\nif bar_index == 1\n    strategy.close(\"L\")"
        val b = flats(100.0, 100.0, 110.0, 110.0)
        assertEquals(3.0, run(src, b, qty = 3.0).report!!.trades.single().qty)
        assertEquals(5.0, run(src, b, qty = Double.NaN).report!!.trades.single().qty)
        assertEquals(5.0, run(src, b, qty = -2.0).report!!.trades.single().qty)
        assertEquals(1e9, run(src, b, qty = 1e12).report!!.trades.single().qty)
        // Costs: not a number is 0, a negative is 0, a percentage is capped at 10.
        val c = run(src, b, costs = Pine.Costs(slippagePoints = Double.NaN, perOrder = -5.0, percent = 50.0)).report!!
        assertEquals((100.0 * 5 + 110.0 * 5) * 0.10, c.commission, 1e-9)
        val inf = run(src, b, costs = Pine.Costs(slippagePoints = Double.POSITIVE_INFINITY, perOrder = Double.NaN, percent = Double.NaN)).report!!
        assertEquals(0.0, inf.commission); assertEquals(50.0, inf.netProfit, 1e-9)
    }

    @Test fun slippageForShorts() {
        val src = "strategy(\"s\", slippage = 10)\nif bar_index == 0\n    strategy.entry(\"S\", strategy.short, qty = 1)\nif bar_index == 1\n    strategy.close(\"S\")"
        val t = run(src, flats(100.0, 100.0, 90.0, 90.0)).report!!.trades.single()
        assertEquals(99.5, t.entryPrice, 1e-9)          // sold 10 ticks lower
        assertEquals(90.5, t.exitPrice, 1e-9)           // bought back 10 ticks higher
    }

    @Test fun processOrdersOnClose() {
        val src = "strategy(\"c\", process_orders_on_close = true)\nif bar_index == 1\n    strategy.entry(\"L\", strategy.long)\nif bar_index == 2\n    strategy.close(\"L\")"
        val b = flats(100.0, 101.0, 105.0, 106.0)
        val t = run(src, b).report!!.trades.single()
        assertEquals(1, t.entryBar); assertEquals(101.0, t.entryPrice); assertEquals(2, t.exitBar); assertEquals(105.0, t.exitPrice)
        assertEquals(b[1].time + 299, t.entryFillTime)
    }

    // ---- when fills happen -------------------------------------------------------------------

    @Test fun closeInstantUsesTheSmallestGapTheNextBarAndTheDaysEnd() {
        val src = "strategy(\"c\", process_orders_on_close = true)\nstrategy.entry(\"L\", strategy.long)"
        // One candle: a minute.
        assertEquals(PC.T0 + 59, run(src, flats(100.0)).report!!.trades.single().entryFillTime)
        // Two candles at the same time: still a minute.
        val same = listOf(flat(0), flat(0))
        assertEquals(PC.T0 + 59, run(src, same).report!!.trades.single().entryFillTime)
        // A candle whose interval would run past midnight ends at the day's last second.
        val midnight = PC.T0 + (24 * 3600 - (9 * 3600 + 15 * 60))
        val late = listOf(Pine.Bar(midnight - 900, 100.0, 100.5, 99.5, 100.0, 1.0), Pine.Bar(midnight - 120, 100.0, 100.5, 99.5, 100.0, 1.0),
            Pine.Bar(midnight + 86400, 100.0, 100.5, 99.5, 100.0, 1.0))
        val tr = run(src.replace("strategy.entry", "if bar_index == 1\n    strategy.entry"), late).report!!.trades
        assertEquals(midnight - 1, tr.single().entryFillTime)
        // The next candle coming sooner than the usual gap ends this one early.
        val uneven = listOf(flat(0), PC.bar(1, 100.0, 100.5, 99.5, 100.0), Pine.Bar(PC.T0 + 600 - 180, 100.0, 100.5, 99.5, 100.0, 1.0),
            Pine.Bar(PC.T0 + 600, 100.0, 100.5, 99.5, 100.0, 1.0))
        val u = run(src.replace("strategy.entry", "if bar_index == 1\n    strategy.entry"), uneven).report!!.trades.single()
        assertEquals(PC.T0 + 300 + 120 - 1, u.entryFillTime)
    }

    // ---- what the last bar ordered -----------------------------------------------------------

    @Test fun nextPositionForEachKindOfWaitingOrder() {
        val b = flats(100.0, 100.0, 100.0)
        fun next(last: String, decl: String = "strategy(\"n\")") =
            run("$decl\nif bar_index == 0\n    strategy.entry(\"L\", strategy.long, qty = 2)\nif barstate.islast\n$last", b).nextPosition
        assertEquals(2.0, next("    x = 0"))
        assertEquals(0.0, next("    strategy.close_all()"))
        assertEquals(0.0, next("    strategy.close(\"L\")"))
        assertEquals(2.0, next("    strategy.close(\"other\")"))
        assertEquals(3.0, next("    strategy.order(\"O\", strategy.long, qty = 1)"))
        assertEquals(0.0, next("    strategy.order(\"O\", strategy.short, qty = 2)"))
        assertEquals(-1.0, next("    strategy.entry(\"S\", strategy.short, qty = 1)"))
        assertEquals(2.0, next("    strategy.entry(\"L2\", strategy.long, qty = 1)"))                    // pyramiding 1: refused
        assertEquals(3.0, next("    strategy.entry(\"L2\", strategy.long, qty = 1)", "strategy(\"n\", pyramiding = 3)"))
        assertEquals(2.0, next("    strategy.entry(\"L2\", strategy.long, limit = 90)"))                  // a limit order waits
        // From flat: an entry, and a close of everything followed by an entry.
        assertEquals(1.0, run("strategy(\"n\")\nif barstate.islast\n    strategy.entry(\"L\", strategy.long)", b).nextPosition)
        assertEquals(-5.0, next("    strategy.close_all()\n    strategy.entry(\"S\", strategy.short, qty = 5)"))
    }

    @Test fun runtimeErrorsFreezeTheEquity() {
        val r = Pine.run(PC.ok("strategy(\"h\")\nif bar_index == 0\n    strategy.entry(\"L\", strategy.long)\nif bar_index == 3\n    runtime.error(\"stop\")"),
            flats(100.0, 100.0, 110.0, 120.0, 130.0))
        assertTrue(r.error!!.message.contains("Bar 4: stop"))
        assertEquals(listOf(1_000_000.0, 1_000_000.0, 1_000_010.0, 1_000_020.0, 1_000_020.0), r.report!!.equity.toList())
    }

    // ---- signal backtests --------------------------------------------------------------------

    @Test fun signalBacktestArgumentsAndReverse() {
        val b = flats(100.0, 100.0, 110.0, 110.0, 100.0, 100.0)
        val buy = booleanArrayOf(true, false, false, false, true)
        val sell = booleanArrayOf(false, true, false, true)                   // shorter than the bars
        val exitOnly = Pine.signalBacktest(b, buy, sell, reverse = false, qty = 2.0, capital = -5.0)
        assertEquals(100_000.0, exitOnly.initialCapital)                        // a bad capital falls back
        assertEquals(listOf(true), exitOnly.trades.filter { !it.open }.map { it.long })
        assertEquals(2.0, exitOnly.trades.first().qty)
        val rev = Pine.signalBacktest(b, buy, sell, reverse = true, qty = 0.0, capital = Double.NaN)
        assertEquals(listOf("Buy", "Sell", "Buy"), rev.trades.map { it.entryId })
        assertEquals(1e-9, rev.trades.first().qty)                              // at least a sliver
        assertTrue(rev.trades.last().open)
    }
}
