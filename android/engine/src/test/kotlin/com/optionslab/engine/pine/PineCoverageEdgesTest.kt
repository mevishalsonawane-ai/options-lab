package com.optionslab.engine.pine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Smaller edges across the package that the feature-by-feature tests leave out. */
class PineCoverageEdgesTest {
    private val H = "indicator(\"e\")\n"
    private fun v(expr: String, pre: String = "", b: List<Pine.Bar> = PC.bars(1.0)) = PC.value(expr, b, pre)
    private fun yes(cond: String, pre: String = "", b: List<Pine.Bar> = PC.bars(1.0)) = assertEquals(1.0, v("($cond) ? 1 : 0", pre, b), cond)
    private fun flat(i: Int, px: Double = 100.0) = PC.bar(i, px, px + 0.5, px - 0.5, px)
    private fun flats(vararg px: Double) = px.mapIndexed { i, p -> flat(i, p) }

    // ---- values and operators ----------------------------------------------------------------

    @Test fun truthinessOfEveryKind() {
        yes("not (\"abc\" ? true : false)"); yes("not (array.from(1) ? true : false)"); yes("not (na ? true : false)")
        yes("0.5 ? true : false"); yes("not (0 ? true : false)")
    }

    @Test fun mixedComparisons() {
        yes("not (1 == na)"); yes("not (\"a\" < 1)"); yes("not (1 < \"a\")"); yes("not (\"a\" >= 1)")
        yes("na(na + \"a\")"); yes("true != false")
        PC.runError("${H}x = 1 - \"a\"\nplot(close)", PC.bars(1.0), "Cannot use '-' on text")
        // A switch on bools and on text against numbers.
        assertEquals(1.0, v("k", "k = switch true\n    false => 0\n    true => 1"))
        assertEquals(2.0, v("k", "k = switch 1\n    \"1\" => 1\n    => 2"))
    }

    @Test fun historyOfValuesThatAreNotSeries() {
        // A built-in value with no stored series of its own keeps one per expression.
        assertEquals(listOf(Double.NaN, 9.0).toString(), PC.plot("${H}plot(hour[1])", PC.bars(1.0, 2.0)).toList().toString())
        // An expression evaluated only on some bars: the gaps are na.
        val r = PC.plot("${H}x = 0.0\nif bar_index % 2 == 0\n    x := (close * 10)[2]\nplot(x)", PC.bars(1.0, 2.0, 3.0, 4.0, 5.0))
        assertEquals(listOf(Double.NaN, 0.0, 10.0, 0.0, 30.0).toString(), r.toList().toString())
        // Evaluated twice on one bar: the second value replaces the first.
        val twice = PC.plot("${H}x = 0.0\nfor i = 0 to 1\n    x := (close + i)[1]\nplot(x)", PC.bars(1.0, 2.0, 3.0))
        assertEquals(listOf(Double.NaN, 2.0, 3.0).toString(), twice.toList().toString())
        // ta.* called twice on a bar (in a loop) replaces that bar's value rather than adding one.
        assertEquals(listOf(Double.NaN, 1.5, 2.5), PC.plot("${H}s = 0.0\nfor i = 0 to 2\n    s := ta.sma(close, 2)\nplot(s)", PC.bars(1.0, 2.0, 3.0)).toList())
    }

    @Test fun tuplesOfNothing() {
        PC.runError("${H}[a] = []\nplot(close)", PC.bars(1.0), "Expected 1 values, got 0")
        assertEquals(0.0, v("0", "x = []"))
    }

    @Test fun directionalMovementBarByBar() {
        // With lengths of 1 each value is that bar's own: +DI = 100 * +DM / TR.
        val b = listOf(PC.bar(0, 10.0, 12.0, 8.0, 10.0), PC.bar(1, 10.0, 11.0, 9.0, 10.0), PC.bar(2, 10.0, 13.0, 9.5, 12.0),
            PC.bar(3, 12.0, 12.5, 7.0, 8.0), PC.bar(4, 8.0, 10.0, 6.0, 9.0), PC.bar(5, 9.0, 11.0, 5.0, 10.0))
        val pre = "[p, m, a] = ta.dmi(1, 1)"
        fun s(x: String) = PC.plot("$H$pre\nplot($x)", b).toList().drop(1)
        assertEquals(listOf(0.0, 100 * 2 / 3.5, 0.0, 0.0, 0.0), s("p"))                      // inside bar, up, down, down, equal moves
        assertEquals(listOf(0.0, 0.0, 100 * 2.5 / 5.5, 100 * 1 / 4.0, 0.0), s("m"))
        assertEquals(listOf(0.0, 100.0, 100.0, 100.0, 0.0), s("a"))
    }

    @Test fun inputOverridesOfEveryKind() {
        val s = PC.ok("indicator(\"i\")\np = input.price(100, \"P\")\nb = input.bool(false, \"B\")\nt = input.timeframe(\"D\", \"T\")\nsrc = input.source(5, \"S\")\nplot(p)\nplot(b ? 1 : 0)\nplot(t == \"W\" ? 1 : 0)\nplot(src)")
        assertEquals("close", s.inputs[3].default)                // a source input that is not a series name reads the close
        val r = Pine.run(s, PC.bars(7.0), mapOf("P" to "zz", "B" to true, "T" to "W"))
        assertEquals(listOf(100.0, 1.0, 1.0, 7.0), r.plots.map { it[0] })
    }

    @Test fun timeWithoutATimeframeAndTimestampOfOneNumber() {
        assertEquals(PC.T0 * 1000.0, v("time(na)"))
        PC.near(Double.NaN, v("timestamp(5)"))
    }

    @Test fun plotshapeEdges() {
        // na, text and zero are not hits; triangleup at an absolute location sits below.
        val r = PC.run("${H}plotshape(na)\nplotshape(\"x\")\nplotshape(0)\nplotshape(true, style = shape.triangleup, location = location.absolute)", PC.bars(1.0))
        assertEquals(listOf(false), r.markers.map { it.above })
        assertEquals(listOf(listOf(false), listOf(false), listOf(false), listOf(true)), r.signals.map { it.toList() })
        // Styles and locations that are not constants fall back to circles above the bar.
        val s = PC.ok("${H}plotshape(true, style = \"diamond\", location = \"x\")\nplotchar(true, char = 5)")
        assertEquals(listOf("circle", "circle"), s.shapes.map { it.style }); assertEquals(listOf("abovebar", "abovebar"), s.shapes.map { it.location })
        assertEquals(listOf("", ""), s.shapes.map { it.text })
    }

    @Test fun arrayJoinWithAnNaSeparator() {
        yes("array.join(array.from(1, 2), na) == \"1,2\"")
    }

    // ---- security -----------------------------------------------------------------------------

    @Test fun securityUnderItsOldNameAndWithNaArguments() {
        val b = PC.bars(1.0, 2.0)
        assertEquals(listOf(1.0, 2.0), PC.plot("${H}plot(security(syminfo.tickerid, \"5\", close))", b).toList())
        assertEquals(listOf(1.0, 2.0), PC.plot("${H}plot(request.security(na, na, close))", b).toList())
        PC.oneError("${H}plot(request.security(syminfo.tickerid, \"D\"))", "request.security() is missing the argument 'expression'")
    }

    @Test fun drawingsAndRiskRulesRunAsNothing() {
        val r = PC.run("strategy(\"s\")\nstrategy.risk.allow_entry_in(strategy.direction.long)\np = chart.point.now(close)\nlinefill.new(na, na, color.red)\n" +
            "if bar_index == 0\n    strategy.entry(\"L\", strategy.long)", PC.bars(1.0, 2.0))
        assertEquals(listOf(0.0, 1.0), r.position.toList())
    }

    // ---- the broker ------------------------------------------------------------------------------

    @Test fun closeEverythingByNaId() {
        val src = "strategy(\"c\", pyramiding = 2)\nif bar_index == 0\n    strategy.entry(\"A\", strategy.long)\n    strategy.entry(\"B\", strategy.long, qty = 3)\nif bar_index == 2\n    CLOSE"
        val b = flats(100.0, 100.0, 104.0, 104.0)
        val all = PC.run(src.replace("CLOSE", "strategy.close(na, immediately = true)"), b)
        assertEquals(listOf("Close all", "Close all"), PC.closed(all).map { it.exitId }); assertEquals(0.0, all.position.last())
        val half = PC.run(src.replace("CLOSE", "strategy.close(na, qty_percent = 50, immediately = true)"), b)
        assertEquals(listOf(1.0, 1.0), PC.closed(half).map { it.qty }); assertEquals(2.0, half.position.last())
        // A cancel with no id cancels everything waiting.
        val c = PC.run("strategy(\"c\")\nif bar_index == 0\n    strategy.entry(\"L\", strategy.long)\n    strategy.cancel(na)", b)
        assertTrue(c.report!!.trades.isEmpty())
    }

    @Test fun exitAmountsThatAreNotUsable() {
        // qty 0 and a percentage over 100: the whole entry.
        for (args in listOf("qty = 0", "qty_percent = 150", "qty = na, qty_percent = -5")) {
            val r = PC.run("strategy(\"x\")\nif bar_index == 0\n    strategy.entry(\"L\", strategy.long, qty = 4)\nstrategy.exit(\"x\", \"L\", $args, limit = 101)", flats(100.0, 100.0) + flat(2, 102.0))
            assertEquals(4.0, PC.closed(r).single().qty, args)
        }
    }

    @Test fun plainOrderUsedUpOnTheFirstEntry() {
        val r = PC.run("strategy(\"o\", pyramiding = 2)\nif bar_index <= 1\n    strategy.entry(\"L\" + str.tostring(bar_index), strategy.long)\nif bar_index == 2\n    strategy.order(\"S\", strategy.short, qty = 1)",
            flats(100.0, 100.0, 100.0, 100.0))
        assertEquals(listOf("L0"), PC.closed(r).map { it.entryId })
        assertEquals(1.0, r.position.last())
    }

    @Test fun exitsStayWhileAnotherEntryOfTheSameIdIsOpen() {
        // Two entries named L; a partial close takes the first, and the exit still guards the second.
        val src = "strategy(\"s\", pyramiding = 2)\nif bar_index <= 1\n    strategy.entry(\"L\", strategy.long)\nif bar_index == 1\n    strategy.exit(\"sl\", \"L\", stop = 95)\n" +
            "if bar_index == 2\n    strategy.close(\"L\", qty = 1)"
        val r = PC.run(src, flats(100.0, 100.0, 100.0, 100.0) + PC.bar(4, 100.0, 100.5, 90.0, 92.0))
        assertEquals(listOf("Close L", "sl"), PC.closed(r).map { it.exitId })
    }

    @Test fun trailingOptionsThatDoNotTrail() {
        // An offset without an activation does not trail: the fixed stop alone.
        val src = "strategy(\"t\")\nif bar_index == 0\n    strategy.entry(\"L\", strategy.long)\n    strategy.exit(\"x\", \"L\", trail_offset = 20, stop = 95)"
        val r = PC.run(src, listOf(flat(0), flat(1), PC.bar(2, 100.0, 120.0, 99.0, 119.0), PC.bar(3, 119.0, 119.5, 94.0, 96.0)))
        assertEquals(95.0, PC.closed(r).single().exitPrice)
        // With a fixed stop and a trail, a long keeps the higher of the two.
        val both = PC.run(src.replace("trail_offset = 20", "trail_points = 100, trail_offset = 40"),
            listOf(flat(0), flat(1), PC.bar(2, 100.0, 100.2, 99.9, 100.1), PC.bar(3, 100.0, 110.0, 99.9, 109.0), PC.bar(4, 109.0, 109.5, 90.0, 91.0)))
        assertEquals(108.0, PC.closed(both).single().exitPrice, 1e-9)
    }

    @Test fun exitLevelsAlreadyPassedAtTheOpen() {
        fun first(entry: String, exit: String, gap: Pine.Bar) =
            PC.closed(PC.run("strategy(\"g\")\nif bar_index == 0\n    strategy.entry(\"E\", $entry)\n    strategy.exit(\"x\", \"E\", $exit)", listOf(flat(0), flat(1), gap, flat(3, gap.close)))).single()
        val longTarget = first("strategy.long", "limit = 105", PC.bar(2, 108.0, 109.0, 107.0, 108.0))
        assertEquals(108.0, longTarget.exitPrice); assertTrue(!longTarget.exitIntrabar)
        assertEquals(90.0, first("strategy.short", "limit = 95", PC.bar(2, 90.0, 91.0, 89.0, 90.0)).exitPrice)
        assertEquals(110.0, first("strategy.short", "stop = 105", PC.bar(2, 110.0, 111.0, 109.0, 110.0)).exitPrice)
    }

    @Test fun shortStopLimitOnOffer() {
        val r = PC.run("strategy(\"e\")\nif bar_index == 0\n    strategy.entry(\"S\", strategy.short, stop = 95, limit = 94)",
            listOf(flat(0), PC.bar(1, 100.0, 100.5, 90.0, 91.0), flat(2, 91.0)))
        assertEquals(95.0, r.report!!.trades.single().entryPrice)
    }

    @Test fun nextPositionWithAWaitingCloseOfAShort() {
        val r = PC.run("strategy(\"n\")\nif bar_index == 0\n    strategy.entry(\"S\", strategy.short, qty = 2)\nif barstate.islast\n    strategy.close(\"S\")", flats(100.0, 100.0, 100.0))
        assertEquals(-2.0, r.position.last()); assertEquals(0.0, r.nextPosition)
    }

    // ---- compile details ---------------------------------------------------------------------

    @Test fun syntaxCornerCases() {
        assertTrue(PC.errors("${H}x = close.").single().contains("Unexpected character '.'"))
        assertTrue(PC.errors("${H}if true\n    x = 1 +\ny = 2").single().contains("Expected a value but found end of block"))
        assertTrue(PC.errors("${H}x = 1 +\n    2").single().contains("Expected a value but found an indented block"))
        assertTrue(PC.errors("${H}float x").single().contains("Unexpected 'x'"))
        assertTrue(PC.errors("${H}float[1] = 2").single().contains("Cannot assign to this"))
        // A type word used as a name after var or as a parameter.
        assertEquals(3.0, v("float", "var float = 3"))
        assertEquals(4.0, v("f(4)", "f(int) => int"))
    }

    @Test fun securityThroughAnIfWithoutElse() {
        val e = PC.errors("${H}v = close * 2\nf(x) =>\n    y = 0.0\n    if x > 1\n        y := v\n    y\nplot(request.security(syminfo.tickerid, \"D\", f(close)))")
        assertTrue(e.any { it.contains("not 'v' (read by f())") }, e.toString())
        PC.ok("${H}v = close * 2\nf(x) =>\n    y = 0.0\n    if x > 1\n        v = 1\n        y := v\n    y\nplot(request.security(syminfo.tickerid, \"D\", f(close)))")
    }

    @Test fun runQuantityThatIsInfinite() {
        val r = PC.run("strategy(\"q\")\nstrategy.entry(\"L\", strategy.long, qty = 2)", flats(100.0, 100.0), qty = Double.POSITIVE_INFINITY)
        assertEquals(2.0, r.report!!.trades.single().qty)
    }
}
