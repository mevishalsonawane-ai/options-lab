package com.optionslab.engine.pine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How statements and expressions run: variables, loops, history, functions, arrays, security and the run limits. */
class PineCoverageRuntimeTest {
    private val H = "indicator(\"r\")\n"
    private fun v(expr: String, pre: String = "", b: List<Pine.Bar> = PC.bars(1.0)) = PC.value(expr, b, pre)
    private fun series(src: String, b: List<Pine.Bar>, i: Int = 0) = PC.plot(H + src.trimIndent(), b, i).toList()
    private fun err(src: String, needle: String, b: List<Pine.Bar> = PC.bars(1.0)) = PC.runError(H + src.trimIndent(), b, needle)

    // ---- variables ------------------------------------------------------------------------

    @Test fun varKeepsItsValueAcrossBarsAndPlainDeclarationsDoNot() {
        val b = PC.bars(1.0, 2.0, 3.0)
        assertEquals(listOf(1.0, 2.0, 3.0), series("var c = 0\nc += 1\nplot(c)", b))
        assertEquals(listOf(1.0, 1.0, 1.0), series("c = 0\nc += 1\nplot(c)", b))
        // var inside a block and inside a function: one per place it is written, kept per call site.
        assertEquals(listOf(1.0, 2.0, 3.0), series("x = 0\nif true\n    var k = 0\n    k += 1\n    x := k\nplot(x)", b))
        assertEquals(listOf(11.0, 22.0, 33.0), series("f() =>\n    var n = 0\n    n += 1\n    n\nplot(f() + f() * 10)", b))
        // varip behaves as var on history.
        assertEquals(listOf(1.0, 2.0, 3.0), series("varip c = 0\nc += 1\nplot(c)", b))
    }

    @Test fun tupleDeclarationsAtRunTime() {
        assertEquals(3.0, v("a + b", "[a, b] = [1, 2]"))
        assertEquals(10.0, v("a", "f() => [10, 20, 30]\n[a, b] = f()"))          // extra values are ignored
        // Declared again on the next bar, the same variables are reused.
        assertEquals(listOf(2.0, 4.0), series("[a, b] = [close, close * 2]\nplot(b)", PC.bars(1.0, 2.0)))
        // In a local scope too.
        assertEquals(6.0, v("x", "x = 0.0\nif true\n    [p, q] = [2, 3]\n    x := p * q"))
        err("[a, b] = close\nplot(a)", "The right side does not return 2 values")
        err("[a, b, c] = [1, 2]\nplot(a)", "Expected 3 values, got 2")
    }

    @Test fun assignmentOperatorsOnNumbersAndText() {
        assertEquals(1.0, v("x", "x = 10\nx -= 1\nx *= 2\nx /= 3\nx %= 5"))
        assertEquals(0.0, v("nz(x, 0)", "x = 1\nx /= 0"))                       // divide by zero is na
        assertEquals(0.0, v("nz(x, 0)", "x = 1\nx %= 0"))
        assertEquals(1.0, v("s == \"ab\" ? 1 : 0", "s = \"a\"\ns += \"b\""))
        err("s = \"a\"\ns -= \"b\"\nplot(close)", "Cannot use '-' on text")
        err("s = \"a\"\nx = s * 2\nplot(close)", "Cannot use '*' on text")
    }

    @Test fun textLengthIsCapped() {
        err("s = \"x\"\nfor i = 0 to 12\n    s := s + s\nplot(close)", "Text longer than 4000 characters")
        err("var a = array.new_string(0)\nfor i = 0 to 1000\n    a.push(\"abcdef\")\ns = array.join(a, \"-\")\nplot(close)", "Text longer than 4000 characters")
        err("s = str.format(\"{0}{0}{0}{0}{0}{0}{0}{0}{0}{0}\", \"0123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789" +
            "0123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789" +
            "0123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789" +
            "0123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789" +
            "0123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789\")\nplot(close)", "Text longer")
    }

    // ---- operators ------------------------------------------------------------------------

    @Test fun equalityWithNaAndMixedTypes() {
        assertEquals(0.0, v("(na == na) ? 1 : 0")); assertEquals(0.0, v("(na != 1) ? 1 : 0"))     // na compares as neither
        assertEquals(1.0, v("(1 == true) ? 1 : 0")); assertEquals(1.0, v("(\"a\" != \"b\") ? 1 : 0"))
        assertEquals(1.0, v("(true == true) ? 1 : 0")); assertEquals(0.0, v("(\"1\" == 1) ? 1 : 0"))
        assertEquals(0.0, v("(na < 1 or na > 1 or na <= 1 or na >= 1) ? 1 : 0"))
    }

    @Test fun arithmeticEdges() {
        PC.near(Double.NaN, v("1 / 0")); PC.near(Double.NaN, v("5 % 0")); assertEquals(1.0, v("7 % 3"))
        PC.near(Double.NaN, v("na + 1")); PC.near(Double.NaN, v("-na"))
        assertEquals(2.0, v("true + true"))
        assertEquals(-1.0, v("+(-1)"))
        // and / or stop early: the right side does not run.
        assertEquals(0.0, v("n", "n = 0\nf() =>\n    n := n + 1\n    true\nx = false and f()\ny = true or f()"))
        assertEquals(1.0, v("(not false) ? 1 : 0"))
    }

    // ---- control flow ---------------------------------------------------------------------

    @Test fun forLoopEdges() {
        assertEquals(0.0, v("s", "s = 0\nfor i = na to 3\n    s += 1"))                   // na bounds: no loop
        assertEquals(0.0, v("s", "s = 0\nfor i = 0 to na\n    s += 1"))
        assertEquals(9.0, v("s", "s = 0\nfor i = 5 to 1 by -2\n    s += i"))
        assertEquals(0.0, v("s", "s = 0\nfor i = 1 to 5 by -1\n    s += i"))                 // wrong way: no steps
        err("for i = 0 to 3 by 0\n    x = i\nplot(close)", "The loop step cannot be 0")
        err("for i = 0 to 3 by na\n    x = i\nplot(close)", "The loop step cannot be 0")
        err("x = 0.0\nfor i = 0 to 600000\n    x += 1\nplot(x)", "This loop takes too long (over 500,000 steps on one bar)")
        // The loop variable counts down on its own when to < from.
        assertEquals(listOf(3.0), series("s = 0\nfor i = 3 to 3\n    s += i\nplot(s)", PC.bars(1.0)))
    }

    @Test fun forInLoops() {
        assertEquals(6.0, v("s", "s = 0.0\nfor x in array.from(1, 2, 3)\n    s += x"))
        assertEquals(3.0, v("s", "s = 0.0\nfor [i, x] in array.from(5, 5, 5)\n    s += i"))
        // The body may change the array: the loop sees the items it started with.
        assertEquals(3.0, v("s", "a = array.from(1, 1, 1)\ns = 0.0\nfor x in a\n    a.push(1)\n    s += x"))
        assertEquals(1.0, v("s", "s = 0.0\nfor x in array.from(1, 2, 3)\n    if x == 2\n        break\n    s += x"))
        assertEquals(4.0, v("s", "s = 0.0\nfor x in array.from(1, 2, 3)\n    if x == 2\n        continue\n    s += x"))
        err("for x in close\n    y = x\nplot(close)", "for...in needs an array")
        err("a = array.new_float(100000, 1)\nb = array.new_float(100000, 1)\nfor x in a\n    for y in b\n        z = y\nplot(close)", "This loop takes too long")
    }

    @Test fun whileLoops() {
        assertEquals(5.0, v("i", "i = 0\nwhile i < 5\n    i += 1"))
        assertEquals(3.0, v("i", "i = 0\nwhile true\n    i += 1\n    if i >= 3\n        break"))
        assertEquals(12.0, v("s", "i = 0\ns = 0\nwhile i < 5\n    i += 1\n    if i == 3\n        continue\n    s += i"))
        err("i = 0\nwhile i >= 0\n    i += 1\nplot(close)", "This loop takes too long")
    }

    @Test fun switchAtRunTime() {
        val pre = "k = switch x\n    1 => \"one\"\n    \"a\" => \"text\"\n    => \"other\""
        assertEquals(1.0, v("k == \"one\" ? 1 : 0", "x = 1\n$pre"))
        assertEquals(1.0, v("k == \"one\" ? 1 : 0", "x = true\n$pre"))            // a bool matches as 1
        assertEquals(1.0, v("k == \"text\" ? 1 : 0", "x = \"a\"\n$pre"))
        assertEquals(1.0, v("k == \"other\" ? 1 : 0", "x = na\n$pre"))            // na matches no case
        // No default and no match: na.
        assertEquals(1.0, v("na(k) ? 1 : 0", "k = switch 5\n    1 => 1"))
        assertEquals(1.0, v("na(k) ? 1 : 0", "k = switch\n    close > 100 => 1"))
        // A switch with a case whose value is na never matches it.
        assertEquals(2.0, v("k", "k = switch 1\n    na => 1\n    => 2"))
        // switch as a statement.
        assertEquals(7.0, v("y", "y = 0\nswitch\n    close > 0 =>\n        y := 7\n    =>\n        y := 9"))
    }

    @Test fun ifWithoutElseIsNa() {
        assertEquals(1.0, v("na(x) ? 1 : 0", "x = if close > 100\n    1"))
        assertEquals(2.0, v("x", "x = if close > 100\n    1\nelse if close > 0\n    2"))
    }

    // ---- history --------------------------------------------------------------------------

    @Test fun historyReferences() {
        val b = PC.bars(1.0, 2.0, 3.0, 4.0)
        assertEquals(listOf(Double.NaN, 1.0, 2.0, 3.0).toString(), series("plot(close[1])", b).toString())
        assertEquals(listOf(Double.NaN, Double.NaN, 0.0, 1.0).toString(), series("plot(bar_index[2])", b).toString())
        assertEquals(PC.T0 * 1000.0, series("plot(time[3])", b)[3])
        for (s in listOf("open", "high", "low", "volume", "hl2", "hlc3", "ohlc4", "hlcc4")) assertTrue(!series("plot($s[1])", b)[1].isNaN(), s)
        assertEquals((b[2].high + b[2].low + 2 * b[2].close) / 4, series("plot(hlcc4[1])", b)[3])
        assertEquals((b[2].open + b[2].high + b[2].low + b[2].close) / 4, series("plot(ohlc4[1])", b)[3])
        // A global variable's history, before it has any.
        assertEquals(listOf(Double.NaN, 1.0, 2.0, 3.0).toString(), series("x = close\nplot(x[1])", b).toString())
        assertEquals(listOf(1.0, 2.0, 3.0, 4.0), series("x = close\nplot(x[0])", b))
        assertEquals(listOf(Double.NaN, Double.NaN, Double.NaN, Double.NaN).toString(), series("x = close\nplot(x[9])", b).toString())
        // An expression's and a local variable's history.
        assertEquals(listOf(Double.NaN, 2.0, 3.0, 4.0).toString(), series("plot((close + 1)[1])", b).toString())
        assertEquals(listOf(Double.NaN, 10.0, 20.0, 30.0).toString(), series("f(x) =>\n    y = x * 10\n    y[1]\nplot(f(close))", b).toString())
        // An na or fractional offset.
        assertEquals(listOf(Double.NaN, Double.NaN, Double.NaN, Double.NaN).toString(), series("plot(close[na])", b).toString())
        assertEquals(2.0, series("plot(close[1.7])", b)[2])
        err("plot(close[-1])", "A history reference cannot look into the future ([-1])")
        // A global declared after its first use in a bar.
        assertEquals(listOf(Double.NaN, 1.0, 2.0, 3.0).toString(), series("var float y = na\ny := close\nplot(y[1])", b).toString())
    }

    // ---- functions ------------------------------------------------------------------------

    @Test fun userFunctionArguments() {
        assertEquals(7.0, v("f(3)", "f(a, b = 4) => a + b"))
        assertEquals(5.0, v("f(b = 2, a = 3)", "f(a, b = 4) => a + b"))
        // Defaults are worked out in the global scope.
        assertEquals(11.0, v("f(1)", "g = 10\nf(a, b = g) => a + b"))
        // Each call site keeps its own series.
        assertEquals(listOf(Double.NaN, 1.0, 2.0).toString(), series("f(x) => x[1]\nplot(f(close))", PC.bars(1.0, 2.0, 3.0)).toString())
    }

    @Test fun deepCallChainsStop() {
        val sb = StringBuilder(H).append("f0(x) => x + 1\n")
        for (i in 1..105) sb.append("f$i(x) => f${i - 1}(x)\n")
        sb.append("plot(f105(1))\n")
        PC.runError(sb.toString(), PC.bars(1.0), "calls itself too deeply")
        val ok = StringBuilder(H).append("f0(x) => x + 1\n")
        for (i in 1..90) ok.append("f$i(x) => f${i - 1}(x)\n")
        ok.append("plot(f90(1))\n")
        assertEquals(2.0, PC.plot(ok.toString(), PC.bars(1.0))[0])
    }

    // ---- inputs ---------------------------------------------------------------------------

    @Test fun inputsAreReadAndOverridden() {
        val src = """
            indicator("i")
            n = input.int(3, "N", minval = 1, maxval = 10)
            f = input.float(1.5, "F")
            p = input.price(100, "P")
            b = input.bool(true, "B")
            s = input.string("a", "S", options = ["a", "b"])
            src = input.source(close, "Src")
            tf = input.timeframe("D", "TF")
            c = input.color(color.red, "C")
            anyv = input(2.5, "Any")
            anyb = input(false, "AnyB")
            anys = input("txt", "AnyS")
            plot(n)
            plot(f)
            plot(p)
            plot(b ? 1 : 0)
            plot(s == "b" ? 1 : 0)
            plot(src)
            plot(anyv)
            plot(anyb ? 1 : 0)
            plot(anys == "zz" ? 1 : 0)
        """.trimIndent()
        val s = PC.ok(src)
        assertEquals(listOf("int", "float", "price", "bool", "string", "source", "timeframe", "color", "float", "bool", "string"), s.inputs.map { it.kind })
        val n = s.inputs[0]
        assertEquals("N", n.key); assertEquals(3.0, n.default); assertEquals(1.0, n.min); assertEquals(10.0, n.max)
        assertEquals(listOf("a", "b"), s.inputs[4].options)
        assertEquals("close", s.inputs[5].default)
        val b = listOf(PC.bar(0, 10.0, 15.0, 5.0, 12.0))
        fun last(inputs: Map<String, Any?>) = Pine.run(s, b, inputs).also { assertNull(it.error) }.plots.map { it[0] }
        assertEquals(listOf(3.0, 1.5, 100.0, 1.0, 0.0, 12.0, 2.5, 0.0, 0.0), last(emptyMap()))
        assertEquals(listOf(7.0, 2.5, 99.0, 0.0, 1.0, 5.0, 4.0, 1.0, 1.0),
            last(mapOf("N" to 7.9, "F" to 2.5, "P" to 99.0, "B" to false, "S" to "b", "Src" to "low", "Any" to 4.0, "AnyB" to "true", "AnyS" to "zz")))
        // Unreadable numbers keep the default; an unknown source falls back to the close.
        assertEquals(listOf(3.0, 1.5, 100.0, 0.0), last(mapOf("N" to "abc", "F" to "x", "P" to null, "B" to "no", "Src" to "bogus")).take(4))
        assertEquals(12.0, last(mapOf("Src" to "bogus"))[5])
        assertEquals(12.0, last(mapOf("Src" to 5.0))[5])
    }

    @Test fun plainInputOfASeriesIsASourceInput() {
        val s = PC.ok("indicator(\"i\")\nsrc = input(high, \"Src\")\nplot(src)")
        assertEquals("source", s.inputs.single().kind)
        assertEquals("high", s.inputs.single().default)
        val b = listOf(PC.bar(0, 10.0, 15.0, 5.0, 12.0))
        assertEquals(5.0, Pine.run(s, b, mapOf("Src" to "low")).plots[0][0])
    }

    @Test fun plainInputOfAColourIsAColour() {
        val s = PC.ok("indicator(\"i\")\nc = input(color.red, \"Col\")\nplot(c == color.red ? 1 : 0)")
        assertTrue(s.inputs.single().kind != "source", s.inputs.toString())
        assertEquals(1.0, Pine.run(s, PC.bars(7.0)).plots[0][0])
    }

    @Test fun inputKindsAndTitles() {
        val s = PC.ok("indicator(\"i\")\na = input.session(\"0915-1530\")\nb = input.symbol(\"NSE:X\")\nc = input.time(0)\nd = input.text_area(\"t\")\ne = input(1, title = 5)\nplot(close)")
        assertEquals(listOf("session", "symbol", "time", "text_area", "float"), s.inputs.map { it.kind })
        assertEquals(listOf("Input 1", "Input 2", "Input 3", "Input 4", "Input 5"), s.inputs.map { it.key })
        // Options that are not literals are left out; min / max that are not numbers are null.
        val o = PC.ok("indicator(\"i\")\nx = 3\na = input.int(1, \"A\", options = [1, x, 2], minval = \"lo\")\nb = input.int(1, \"B\", options = 5)\nplot(close)")
        assertEquals(listOf("1.0", "2.0"), o.inputs[0].options); assertNull(o.inputs[0].min)
        assertEquals(emptyList(), o.inputs[1].options)
        // Negative and computed literal defaults.
        val n = PC.ok("indicator(\"i\")\na = input.float(-1.5, \"A\")\nb = input.float(2 * 3 - 1, \"B\")\nc = input.float(+2, \"C\")\nd = input.float(-close, \"D\")\nplot(close)")
        assertEquals(listOf(-1.5, 5.0, 2.0, null), n.inputs.map { it.default })
    }

    // ---- plots, shapes and signals ----------------------------------------------------------

    @Test fun plotDefinitions() {
        val s = PC.ok("""
            indicator("p")
            plot(close)
            plot(close, title = 5, color = color.new(color.blue, 50))
            plot(close, style = plot.style_histogram, color = color.rgb(1, 2, 3))
            plot(close, style = plot.style_columns, color = close > 1 ? color.red : color.green)
            plot(close, style = plot.style_circles, color = na)
            plot(close, style = plot.style_cross, color = color.rgb(1, 2, close))
            plot(close, style = plot.style_stepline, color = color.from_gradient(1, 0, 2, color.red, color.green))
            plot(close, style = "x")
            hline(5)
            hline(6, "Six", color.new(#FF0000, 20))
        """)
        assertEquals(listOf("Plot 1", "Plot 2", "Plot 3", "Plot 4", "Plot 5", "Plot 6", "Plot 7", "Plot 8", "Level", "Six"), s.plots.map { it.title })
        assertEquals(listOf("line", "line", "histogram", "column", "points", "points", "line", "line", "hline", "hline"), s.plots.map { it.style })
        assertEquals("#2962FF", s.plots[1].color); assertEquals("#010203", s.plots[2].color); assertEquals("#F23645", s.plots[3].color)
        assertEquals(Pine.PALETTE[4], s.plots[4].color); assertEquals(Pine.PALETTE[5], s.plots[5].color); assertEquals(Pine.PALETTE[6], s.plots[6].color)
        assertEquals("#FF0000", s.plots[9].color)
        // A ternary colour whose first branch is unknown takes the second.
        assertEquals("#089981", PC.ok("indicator(\"p\")\nplot(close, color = close > 1 ? na : color.green)").plots[0].color)
        // hline plots its level on every bar.
        assertEquals(listOf(5.0, 5.0), Pine.run(s, PC.bars(1.0, 2.0)).plots[8].toList())
    }

    @Test fun shapesCharsArrowsAndAlerts() {
        val s = PC.ok("""
            indicator("s")
            plotshape(close > 1, "Up", shape.triangleup, location.belowbar, color.green, text = "B")
            plotshape(close > 1, style = shape.labeldown, location = location.top)
            plotshape(close > 1, style = shape.labelup, location = location.bottom, color = #123456)
            plotshape(close > 1, style = shape.xcross, location = location.absolute)
            plotshape(close > 1, style = shape.arrowdown, location = location.absolute)
            plotchar(close > 1, "Ch", "*", location.abovebar)
            plotchar(close > 1, "Ch2", "*", text = "T")
            plotarrow(close - 2, "Arr")
            alertcondition(close > 2, "Big")
            alertcondition(close > 2)
            if close > 0
                plotshape(close > 1, "Inner")
                alertcondition(close > 0, "InnerAlert")
            f() => plotshape(true, "InFunc")
        """)
        assertEquals(listOf("Up", "Shape 2", "Shape 3", "Shape 4", "Shape 5", "Ch", "Ch2", "Arr", "Inner"), s.shapes.map { it.title })
        assertEquals(listOf("triangleup", "labeldown", "labelup", "xcross", "arrowdown", "circle", "circle", "arrowup", "circle"), s.shapes.map { it.style })
        assertEquals(listOf("belowbar", "top", "bottom", "absolute", "absolute", "abovebar", "abovebar", "abovebar", "abovebar"), s.shapes.map { it.location })
        assertEquals(listOf("B", "", "", "", "", "*", "T", "", ""), s.shapes.map { it.text })
        assertEquals("#123456", s.shapes[2].color)
        assertEquals(listOf("Up", "Shape 2", "Shape 3", "Shape 4", "Shape 5", "Ch", "Ch2", "Arr", "Big", "Alert 10", "Inner", "InnerAlert"), s.signals)
        val r = Pine.run(s, PC.bars(1.0, 3.0, 0.5))
        assertNull(r.error)
        val m1 = r.markers.filter { it.bar == 1 }
        assertEquals(listOf(false, true, false, true, true, true, true, false, true), m1.map { it.above })
        assertEquals(listOf("triangleUp", "labelDown", "labelUp", "circle", "triangleDown", "circle", "circle", "triangleUp", "circle"), m1.map { it.shape })
        assertEquals("#123456", m1[2].color)
        // plotarrow: below the bar for a rise, above for a fall, never on zero.
        assertTrue(r.markers.any { it.bar == 0 && it.shape == "triangleDown" && it.above }, r.markers.toString())       // close - 2 = -1
        assertTrue(r.markers.any { it.bar == 2 && it.shape == "triangleDown" && it.above }, r.markers.toString())    // -1.5
        assertEquals(listOf(false, true, false), r.signals[8].toList())
        assertEquals(listOf(true, true, true), r.signals[11].toList())
    }

    @Test fun plotshapeColourFromTheCallAndBadColours() {
        // The colour given on each bar is used when it is a real colour (alpha dropped), else the definition's.
        val r = PC.run("indicator(\"s\")\nplotshape(true, color = close > 1 ? #00FF0080 : color.red)", PC.bars(1.0, 2.0))
        assertEquals(listOf("#F23645", "#00FF00"), r.markers.map { it.color })
        val bad = PC.run("indicator(\"s\")\nplotshape(true, color = close > 1 ? \"junk\" : #0000FF)", PC.bars(1.0, 2.0))
        assertEquals(listOf("#0000FF", "#0000FF"), bad.markers.map { it.color })
        // No colour at all: the palette.
        assertEquals(Pine.PALETTE[3], PC.run("indicator(\"s\")\nplotshape(true)", PC.bars(1.0)).markers.single().color)
    }

    @Test fun shapeAndSignalLimits() {
        val many = "indicator(\"s\")\n" + (1..70).joinToString("\n") { "plotshape(close > $it, \"S$it\")" }
        val s = PC.ok(many)
        assertEquals(64, s.shapes.size); assertEquals(64, s.signals.size)
        // Shapes past the limit still run, and draw nothing.
        val r = Pine.run(s, PC.bars(100.0))
        assertNull(r.error); assertEquals(64, r.markers.size)
        val alerts = "indicator(\"s\")\n" + (1..130).joinToString("\n") { "alertcondition(close > $it, \"A$it\")" }
        assertEquals(128, PC.ok(alerts).signals.size)
        assertNull(Pine.run(PC.ok(alerts), PC.bars(1.0)).error)
    }

    @Test fun plotsWalkEveryStatementKindForShapes() {
        val s = PC.ok("""
            indicator("w")
            x = 0.0
            x := nz(plotshape(true, "Assign")) + 1
            [a, b] = [plotshape(true, "Tuple"), 1]
            for i = 0 to 1
                plotshape(true, "For")
            while false
                plotshape(true, "While")
            for e in array.from(1)
                plotshape(true, "ForIn")
            switch 1
                1 => plotshape(true, "Switch")
            y = if true
                plotshape(true, "IfExpr")
            z = -nz(plotshape(true, "Unary"))
            w = (close > 1 ? plotshape(true, "Tern") : 0)
            q = close[nz(plotshape(true, "Index"))]
        """)
        assertEquals(listOf("Assign", "Tuple", "For", "While", "ForIn", "Switch", "IfExpr", "Unary", "Tern", "Index"), s.shapes.map { it.title })
    }

    // ---- arrays ---------------------------------------------------------------------------

    @Test fun arrayConstructors() {
        assertEquals(3.0, v("array.size(array.new_int(3, 0))"))
        assertEquals(1.0, v("array.get(array.new_bool(2, true), 1) ? 1 : 0"))
        assertEquals(1.0, v("array.get(array.new_string(1, \"a\"), 0) == \"a\" ? 1 : 0"))
        assertEquals(1.0, v("array.get(array.new_color(1, color.red), 0) == color.red ? 1 : 0"))
        assertEquals(1.0, v("na(array.get(array.new_float(1), 0)) ? 1 : 0"))
        assertEquals(0.0, v("array.size(array.new_float())"))
        assertEquals(0.0, v("array.size(array.new_float(na))"))
        err("a = array.new_float(-1)\nplot(close)", "An array may hold 0 to 100000 items")
        err("a = array.new_float(100001)\nplot(close)", "An array may hold 0 to 100000 items")
    }

    @Test fun arrayReadAndWrite() {
        val pre = "a = array.from(10, 20, 30)"
        assertEquals(30.0, v("array.get(a, -1)", pre)); assertEquals(10.0, v("a.get(0)", pre))
        assertEquals(99.0, v("a.get(1)", "$pre\narray.set(a, 1, 99)"))
        assertEquals(99.0, v("a.get(-1)", "$pre\na.set(-1, 99)"))
        assertEquals(4.0, v("a.size()", "$pre\narray.push(a, 1)"))
        assertEquals(5.0, v("a.get(0)", "$pre\narray.unshift(a, 5)"))
        assertEquals(7.0, v("a.get(1)", "$pre\narray.insert(a, 1, 7)"))
        assertEquals(7.0, v("a.get(0)", "$pre\narray.insert(a, -5, 7)"))            // clamped to the start
        assertEquals(7.0, v("a.get(3)", "$pre\narray.insert(a, 50, 7)"))            // and the end
        assertEquals(7.0, v("a.get(0)", "$pre\narray.insert(a, na, 7)"))
        assertEquals(30.0, v("array.pop(a)", pre)); assertEquals(10.0, v("array.shift(a)", pre))
        assertEquals(20.0, v("array.remove(a, 1)", pre)); assertEquals(2.0, v("a.size()", "$pre\na.remove(0)"))
        assertEquals(0.0, v("a.size()", "$pre\narray.clear(a)"))
        assertEquals(10.0, v("array.first(a)", pre)); assertEquals(30.0, v("array.last(a)", pre))
        assertEquals(1.0, v("na(array.first(b)) ? 1 : 0", "b = array.from(na, 1)"))  // an na item is not an error
        assertEquals(1.0, v("na(array.last(b)) ? 1 : 0", "b = array.from(1, na)"))
    }

    @Test fun arrayErrors() {
        err("a = array.from(1)\nx = array.get(a, na)\nplot(close)", "array.get(): the index is na")
        err("a = array.from(1)\nx = array.get(a, 1)\nplot(close)", "array.get(): index 1 is out of range (the array has 1)")
        err("a = array.from(1)\nx = array.get(a, -2)\nplot(close)", "index -2 is out of range")
        err("a = array.new_float(0)\nx = array.pop(a)\nplot(close)", "array.pop(): the array is empty")
        err("a = array.new_float(0)\nx = array.shift(a)\nplot(close)", "array.shift(): the array is empty")
        err("a = array.new_float(0)\nx = array.first(a)\nplot(close)", "array.first(): the array is empty")
        err("a = array.new_float(0)\nx = array.last(a)\nplot(close)", "array.last(): the array is empty")
        err("x = array.size(close)\nplot(close)", "array.size(): expected an array")
        err("a = array.from(1)\nx = array.concat(a, 5)\nplot(close)", "array.concat(): expected an array")
        err("a = array.new_float(0)\nx = a.pop()\nplot(close)", "array.pop(): the array is empty")
    }

    @Test fun arraySizeIsCapped() {
        err("a = array.new_float(100000)\narray.push(a, 1)\nplot(close)", "An array may hold at most 100000 items")
        err("a = array.new_float(100000)\narray.unshift(a, 1)\nplot(close)", "An array may hold at most 100000 items")
        err("a = array.new_float(100000)\narray.insert(a, 0, 1)\nplot(close)", "An array may hold at most 100000 items")
        err("a = array.new_float(60000)\nb = array.new_float(60000)\narray.concat(a, b)\nplot(close)", "An array may hold at most 100000 items")
    }

    @Test fun arrayStatistics() {
        val pre = "a = array.from(4, 1, na, 3, 1)"
        assertEquals(9.0, v("array.sum(a)", pre)); assertEquals(2.25, v("array.avg(a)", pre))
        assertEquals(1.0, v("array.min(a)", pre)); assertEquals(4.0, v("array.max(a)", pre))
        assertEquals(1.0, v("array.min(a, 1)", pre)); assertEquals(3.0, v("array.max(a, 1)", pre))
        assertEquals(1.0, v("na(array.max(a, 9)) ? 1 : 0", pre))
        assertEquals(3.0, v("array.range(a)", pre))
        assertEquals(2.0, v("array.median(a)", pre)); assertEquals(3.0, v("array.median(array.from(1, 3, 5))"))
        assertEquals(1.0, v("array.mode(a)", pre)); assertEquals(2.0, v("array.mode(array.from(3, 2))"))   // ties: the smallest
        val m = 2.25; val ss = listOf(4.0, 1.0, 3.0, 1.0).sumOf { (it - m) * (it - m) }
        assertEquals(ss / 4, v("array.variance(a)", pre), 1e-12); assertEquals(ss / 3, v("array.variance(a, false)", pre), 1e-12)
        assertEquals(kotlin.math.sqrt(ss / 4), v("array.stdev(a)", pre), 1e-12); assertEquals(kotlin.math.sqrt(ss / 3), v("array.stdev(a, false)", pre), 1e-12)
        assertEquals(0.0, v("array.stdev(array.from(5), false)"))
        // Empty arrays: na, and a sum of 0.
        val e = "e = array.new_float(0)"
        for (f in listOf("avg", "min", "max", "range", "median", "mode", "stdev", "variance")) assertEquals(1.0, v("na(array.$f(e)) ? 1 : 0", e), f)
        assertEquals(0.0, v("array.sum(e)", e))
    }

    @Test fun arraySearchAndReshape() {
        val pre = "a = array.from(1, 2, 1, \"x\")"
        assertEquals(1.0, v("array.includes(a, 2) ? 1 : 0", pre)); assertEquals(0.0, v("array.includes(a, 5) ? 1 : 0", pre))
        assertEquals(1.0, v("array.includes(a, \"x\") ? 1 : 0", pre))
        assertEquals(0.0, v("array.indexof(a, 1)", pre)); assertEquals(2.0, v("array.lastindexof(a, 1)", pre)); assertEquals(-1.0, v("array.indexof(a, 9)", pre))
        val b = "b = array.from(5, 6, 7, 8)"
        assertEquals(2.0, v("array.size(array.slice(b, 1, 3))", b)); assertEquals(6.0, v("array.get(array.slice(b, 1, 3), 0)", b))
        assertEquals(0.0, v("array.size(array.slice(b, 3, 1))", b)); assertEquals(4.0, v("array.size(array.slice(b, -3, 99))", b))
        // copy is separate; slices, like TradingView's, share nothing here either.
        assertEquals(5.0, v("b.get(0)", "$b\nc = array.copy(b)\nc.set(0, 0)"))
        assertEquals(8.0, v("b.get(0)", "$b\narray.reverse(b)"))
        assertEquals(1.0, v("b.get(0)", "$b\narray.fill(b, 1)")); assertEquals(1.0, v("b.get(3)", "$b\narray.fill(b, 1)"))
        assertEquals(listOf(5.0, 0.0, 0.0, 8.0), listOf(0, 1, 2, 3).map { v("b.get($it)", "$b\narray.fill(b, 0, 1, 3)") })
        assertEquals(listOf(5.0, 6.0, 0.0, 0.0), listOf(0, 1, 2, 3).map { v("b.get($it)", "$b\narray.fill(b, 0, 2)") })
        assertEquals(6.0, v("array.size(array.concat(b, array.from(1, 2)))", b))
        assertEquals(1.0, v("array.join(array.from(1, 2.5, \"a\", na), \"-\") == \"1-2.5-a-NaN\" ? 1 : 0"))
        assertEquals(1.0, v("array.join(array.from(1, 2)) == \"1,2\" ? 1 : 0"))
        assertEquals(1.0, v("str.tostring(array.from(1, 2)) == \"[1.0, 2.0]\" ? 1 : 0"))
    }

    @Test fun arraySorting() {
        fun sorted(pre: String, n: Int) = (0 until n).map { v("nz(a.get($it), -1)", pre) }
        assertEquals(listOf(1.0, 2.0, 3.0), sorted("a = array.from(3, 1, 2)\narray.sort(a)", 3))
        assertEquals(listOf(3.0, 2.0, 1.0), sorted("a = array.from(3, 1, 2)\narray.sort(a, order.descending)", 3))
        assertEquals(listOf(1.0, 3.0, -1.0), sorted("a = array.from(3, na, 1)\narray.sort(a)", 3))           // na last
        assertEquals(listOf(1.0, 3.0, -1.0), sorted("a = array.from(3, na, 1)\na.sort(order.ascending)", 3))
        assertEquals(1.0, v("a.get(0) == \"a\" and a.get(2) == \"c\" ? 1 : 0", "a = array.from(\"c\", \"a\", \"b\")\narray.sort(a)"))
    }

    @Test fun arraysAreSharedByReference() {
        assertEquals(2.0, v("a.size()", "a = array.new_float(0)\nb = a\nb.push(1)\nf(x) =>\n    x.push(2)\n    0\nz = f(a)"))
    }

    // ---- request.security ---------------------------------------------------------------------

    private val day2 = PC.T0 + 86400
    private fun twoDays(): List<Pine.Bar> = listOf(10.0, 11.0, 12.0).mapIndexed { i, c -> Pine.Bar(PC.T0 + i * 300L, c, c + 1, c - 1, c, 1.0) } +
        listOf(20.0, 21.0).mapIndexed { i, c -> Pine.Bar(day2 + i * 300L, c, c + 1, c - 1, c, 1.0) }

    @Test fun securityOnTheSameTimeframeIsTheExpressionItself() {
        val b = twoDays()
        assertEquals(b.map { it.close }, series("plot(request.security(syminfo.tickerid, \"5\", close))", b))
        assertEquals(b.map { it.close }, series("plot(request.security(\"\", \"\", close))", b))
        assertEquals(b.map { it.close }, series("plot(request.security(\"NSE:NIFTY\", timeframe.period, close))", b))
        assertEquals(b.map { it.close }, series("plot(request.security(\"nifty\", \"5\", close))", b))
    }

    @Test fun securityHigherTimeframes() {
        val b = twoDays()
        // Daily: the high of the day so far only once the day has closed.
        assertEquals(listOf(Double.NaN, Double.NaN, 13.0, 13.0, 22.0).toString(),
            series("plot(request.security(syminfo.tickerid, \"D\", high))", b).toString())
        assertEquals(listOf(13.0, 13.0, 13.0, 22.0, 22.0).toString(),
            series("plot(request.security(syminfo.tickerid, \"D\", high, lookahead = true))", b).toString())
        assertEquals(listOf(3.0, 3.0, 3.0, 2.0, 2.0).toString(),
            series("plot(request.security(syminfo.tickerid, \"D\", volume, barmerge.gaps_off, barmerge.lookahead_on))", b).toString())
        // Weekly and monthly group both days into one candle.
        assertEquals(listOf(Double.NaN, Double.NaN, Double.NaN, Double.NaN, 9.0).toString(),
            series("plot(request.security(syminfo.tickerid, \"W\", low))", b).toString())
        assertEquals(listOf(21.0, 21.0, 21.0, 21.0, 21.0), series("plot(request.security(syminfo.tickerid, \"M\", close, lookahead = barmerge.lookahead_on))", b))
        // Hourly from 09:15.
        assertEquals(listOf(12.0, 12.0, 12.0, 21.0, 21.0), series("plot(request.security(syminfo.tickerid, \"60\", close, lookahead = barmerge.lookahead_on))", b))
        // The higher timeframe's own built-ins: its bar index and its period.
        assertEquals(listOf(0.0, 0.0, 0.0, 1.0, 1.0), series("plot(request.security(syminfo.tickerid, \"D\", bar_index, lookahead = barmerge.lookahead_on))", b))
        assertEquals(listOf(1.0, 1.0, 1.0, 1.0, 1.0), series("plot(request.security(syminfo.tickerid, \"D\", timeframe.isdaily ? 1 : 0, lookahead = barmerge.lookahead_on))", b))
    }

    @Test fun securityErrors() {
        val b = twoDays()
        err("plot(request.security(syminfo.tickerid, \"1\", close))", "request.security to a lower timeframe (1) than the chart's is not supported", b)
        err("plot(request.security(\"NSE:BANKNIFTY\", \"D\", close))", "request.security on another symbol (NSE:BANKNIFTY) is not supported: only NIFTY", b)
        err("plot(request.security(syminfo.tickerid, \"D\", request.security(syminfo.tickerid, \"W\", close)))", "request.security cannot be nested", b)
        err("x = request.security(syminfo.tickerid, \"D\", alertcondition(close > 1))\nplot(close)", "alertcondition() cannot run inside request.security", b)
        err("x = request.security(syminfo.tickerid, \"D\", plotshape(close > 1))\nplot(close)", "plotshape() cannot run inside request.security", b)
    }

    @Test fun securityInsideAFunctionHasItsOwnSeriesPerCallSite() {
        val b = twoDays()
        val r = PC.plot("${H}f(tf) => request.security(syminfo.tickerid, tf, close, lookahead = barmerge.lookahead_on)\nplot(f(\"D\"))\nplot(f(\"5\"))", b)
        assertEquals(listOf(12.0, 12.0, 12.0, 21.0, 21.0), r.toList())
        assertEquals(b.map { it.close }, PC.plot("${H}f(tf) => request.security(syminfo.tickerid, tf, close, lookahead = barmerge.lookahead_on)\nplot(f(\"D\"))\nplot(f(\"5\"))", b, 1).toList())
    }

    // ---- limits -----------------------------------------------------------------------------

    @Test fun timeBudgetStopsTheRun() {
        val s = PC.ok("${H}x = 0.0\nfor i = 0 to 400000\n    x += 1\nplot(x)")
        val r = Pine.run(s, PC.bars(*DoubleArray(200) { 1.0 }), budgetMs = 50)
        assertTrue(assertNotNull(r.error).message.contains("The script takes too long (over 0.05 s): simplify its loops"), r.error.toString())
    }

    @Test fun strategyCallBeforeAnIndicatorDeclarationFailsWhenRun() {
        PC.runError("strategy.entry(\"L\", strategy.long)\nindicator(\"x\")\nplot(close)", PC.bars(1.0), "strategy.entry() needs a strategy() declaration")
    }

    @Test fun emptyBarListRunsNothing() {
        val r = Pine.run(PC.ok("${H}plot(close)"), emptyList())
        assertNull(r.error); assertEquals(0, r.plots[0].size)
        val s = Pine.run(PC.ok("strategy(\"s\")\nstrategy.entry(\"L\", strategy.long)"), emptyList())
        assertNull(s.error); assertEquals(0.0, s.nextPosition); assertEquals(0, s.report!!.trades.size)
        assertEquals(0.0, s.report!!.maxDrawdown); assertEquals(0.0, s.report!!.buyHoldPct)
    }
}
