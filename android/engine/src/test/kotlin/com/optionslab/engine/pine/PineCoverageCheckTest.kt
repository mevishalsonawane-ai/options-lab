package com.optionslab.engine.pine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The checker (PineCheck.kt): every compile error and warning it gives. */
class PineCoverageCheckTest {
    private val H = "indicator(\"x\")\n"
    private val S = "strategy(\"s\")\n"

    @Test fun declarationsAndRedeclarations() {
        PC.oneError("${H}f(x) => x\nf = 1\nplot(f)", "'f' is already a function name")
        PC.oneError("${H}a = 1\n[a, b] = [1, 2]\nplot(a)", "'a' is already defined")
        PC.oneError("${H}[a, a] = [1, 2]\nplot(a)", "'a' is already defined")
        PC.oneError("${H}close := 1", "'close' is built in and cannot be changed")
        PC.oneError("${H}color.red := 1", "'color.red' is built in and cannot be changed")
        // A variable in an inner block may reuse the name of one outside.
        assertEquals(1.0, PC.value("a", pre = "a = 1\nif true\n    a = 2"))
    }

    @Test fun loopsAndJumps() {
        PC.oneError("${H}continue", "'continue' can only be used inside a loop")
        PC.oneError("${H}if true\n    break", "'break' can only be used inside a loop")
        // Inside every loop kind they are fine, and loop variables are declared in the body only.
        PC.ok("${H}for i = 0 to 3 by 1\n    break\nwhile false\n    continue\na = array.from(1)\nfor [j, x] in a\n    continue\nplot(close)")
        PC.oneError("${H}for i = 0 to 3\n    x = i\nplot(i)", "Undeclared identifier 'i'")
    }

    @Test fun functionDefinitionRules() {
        PC.oneError("${H}if true\n    f(x) => x", "Functions must be declared at the top level, not inside a block")
        PC.oneError("${H}f(x) => x\nf(x) => x + 1", "Function 'f' is already defined")
        assertTrue(PC.warnings("${H}nz(x) => x\nplot(nz(1))").contains("'nz' replaces the built-in function of that name"))
        // A parameter's default value is checked too.
        PC.oneError("${H}f(x = foo) => x\nplot(f())", "Undeclared identifier 'foo'")
        PC.ok("${H}f(x, y = close) => x + y\nplot(f(1))")
    }

    @Test fun userFunctionCallArguments() {
        PC.oneError("${H}f(a) => a\nplot(f(1, 2))", "f() takes 1 argument(s), got 2")
        PC.oneError("${H}f(a) => a\nplot(f(b = 1))", "f() has no parameter 'b'")
        PC.oneError("${H}f(a, b) => a\nplot(f(1))", "f() is missing the argument 'b'")
        PC.ok("${H}f(a, b = 2) => a + b\nplot(f(b = 3, a = 1))")
    }

    @Test fun namesThatAreNotValues() {
        PC.oneError("${H}plot(ta.sma)", "'ta.sma' is a function: call it with ()")
        PC.oneError("${H}f() => 1\nplot(f)", "'f' is a function: call it with ()")
        PC.oneError("${H}plot(close, color = color.pink)", "Unknown colour 'color.pink'")
        for (ns in listOf("ta", "math", "strategy", "syminfo", "barstate", "timeframe"))
            PC.oneError("${H}plot($ns.nothing ? 1 : 0)", "'$ns.nothing' is not a known $ns.* value")
        PC.oneError("${H}plot(foo.bar)", "Undeclared identifier 'foo.bar'")
        // Styling constants are accepted in any member.
        PC.ok("${H}plot(close, style = plot.style_whatever, display = display.none)\nx = shape.anything\nplot(close)")
    }

    @Test fun drawingsAndRiskRulesWarn() {
        val w = PC.warnings("${H}label.new(bar_index, close, \"hi\")\nline.new(0, 0, 1, 1)\nbox.new(0, 0, 1, 1)\nchart.point.now(close)\nplot(close)")
        assertTrue(w.contains("label.* drawings are accepted but not drawn on the phone chart"), w.toString())
        assertTrue(w.contains("line.* drawings are accepted but not drawn on the phone chart"), w.toString())
        assertTrue(w.any { it.startsWith("chart.point.*") }, w.toString())
        // The same warning is given once.
        assertEquals(1, PC.warnings("${H}label.new(0, 0)\nlabel.new(1, 1)\nplot(close)").size)
        val sw = PC.warnings("${S}strategy.risk.allow_entry_in(strategy.direction.long)")
        assertTrue(sw.contains("strategy.risk.* rules are ignored in the phone backtest"), sw.toString())
        // And they run without drawing anything.
        val r = PC.run("${H}l = label.new(bar_index, close, \"x\")\nlabel.set_text(l, \"y\")\nplot(close)", PC.bars(1.0, 2.0))
        assertEquals(2.0, r.plots[0][1])
    }

    @Test fun ignoredFunctionsWarn() {
        for (f in listOf("bgcolor(color.red)", "barcolor(color.red)", "fill(1, 2)", "alert(\"m\")", "plotcandle(open, high, low, close)",
                "plotbar(open, high, low, close)", "max_bars_back(close, 10)", "log.info(\"a\")", "log.warning(\"a\")", "log.error(\"a\")")) {
            val name = f.substringBefore('(')
            val w = PC.warnings("${H}$f\nplot(close)")
            assertTrue(w.contains("$name() is accepted but not drawn on the phone chart"), "$f: $w")
            // They run and do nothing.
            assertEquals(3.0, PC.plot("${H}$f\nplot(close)", PC.bars(3.0))[0])
        }
    }

    @Test fun arrayMethodsOnVariables() {
        PC.oneError("${H}a = array.new_float()\na.push()\nplot(close)", "a.push(): wrong number of arguments")
        PC.oneError("${H}a = array.new_float()\na.get(1, 2)\nplot(close)", "a.get(): wrong number of arguments")
        PC.oneError("${H}a = array.new_float()\na.nothing()\nplot(close)", "Unknown function 'a.nothing'")
        // A method on an undeclared name is an unknown function.
        PC.oneError("${H}b.push(1)\nplot(close)", "Unknown function 'b.push'")
        // A dotted namespace is not taken for a variable.
        PC.oneError("${H}x.y.push(1)\nplot(close)", "Unknown function 'x.y.push'")
    }

    @Test fun unsupportedNamespacesAndRequests() {
        PC.oneError("${H}m = matrix.new<float>(2, 2)\nplot(close)", "Generic types are only supported for arrays")
        PC.oneError("${H}m = matrix.new_float(2, 2)\nplot(close)", "matrix.new_float() is not supported yet")
        PC.oneError("${H}m = map.new_string()\nplot(close)", "map.new_string() is not supported yet")
        PC.oneError("${H}n = str.length(\"abc\")\nplot(close)", "str.length() is not supported yet")
        PC.oneError("${H}t = ticker.new(\"NSE\", \"X\")\nplot(close)", "ticker.new() is not supported yet")
        PC.oneError("${H}d = request.dividends(\"NSE:X\")\nplot(close)", "request.dividends() is not supported: only request.security on the chart's own symbol")
    }

    @Test fun builtinArgumentChecks() {
        PC.oneError("${H}plot(math.abs(1, 2))", "math.abs() takes at most 1 argument(s), got 2")
        PC.oneError("${H}plot(ta.sma(close, length = 3, foo = 1))", "ta.sma() has no parameter 'foo'")
        PC.oneError("${H}plot(ta.sma(close, 3, length = 4))", "ta.sma(): 'length' is given twice")
        PC.oneError("${H}plot(ta.sma(close))", "ta.sma() is missing the argument 'length'")
        PC.oneError("${H}plot(math.max())", "math.max() needs at least 1 argument(s)")
        PC.oneError("${H}a = array.from(1)\nplot(array.size(a, 1))", "array.size() takes at most 1 argument(s)")
        PC.oneError("${H}plot(array.get(array.from(1)))", "array.get() needs at least 2 argument(s)")
        // ta.highest and friends may be given just a length.
        for (f in listOf("ta.highest", "ta.lowest", "ta.highestbars", "ta.lowestbars")) PC.ok("${H}plot($f(3))")
        // Named arguments may come in any order.
        PC.ok("${H}plot(ta.sma(length = 3, source = close))")
    }

    @Test fun securityExpressionsMayOnlyUseBuiltIns() {
        val bad = "only use built-in values, inputs and functions, not 'v'"
        val pre = "${H}v = close * 2\n"
        // Every expression form that can hide a chart variable.
        for (x in listOf("v[1]", "-v", "v + 1", "v > 1 ? 1 : 0", "close > 1 ? v : 0", "close > 1 ? 0 : v", "math.max(v, 1)", "[v, 1]"))
            assertTrue(PC.errors("${pre}plot(request.security(syminfo.tickerid, \"D\", $x))").any { it.contains(bad) }, x)
        // The old name, and the expression given by name.
        assertTrue(PC.errors("${pre}plot(security(syminfo.tickerid, \"D\", v))").any { it.contains(bad) })
        assertTrue(PC.errors("${pre}plot(request.security(syminfo.tickerid, \"D\", expression = v))").any { it.contains(bad) })
        // Inputs, builtins, constants and styling constants are fine.
        PC.ok("${H}len = input.int(3)\nsrc = input(close)\nplot(request.security(syminfo.tickerid, \"D\", ta.sma(src, len) + math.pi + (color.red == \"\" ? 1 : 0)))")
        // Drawing and trading cannot run inside it.
        PC.ok("${S}x = request.security(syminfo.tickerid, \"D\", strategy.position_size + strategy.opentrades)\nplot(x)")
        assertTrue(PC.errors("${S}x = request.security(syminfo.tickerid, \"D\", strategy.close_all())").any { it.contains("strategy.close_all() cannot run inside request.security") })
        assertTrue(PC.errors("${H}x = request.security(syminfo.tickerid, \"D\", plot(close))").any { it.contains("plot() cannot run inside request.security") })
    }

    @Test fun securityLooksThroughUserFunctionBodies() {
        val pre = "${H}v = close * 2\n"
        fun refused(fn: String) {
            val e = PC.errors("$pre$fn\nplot(request.security(syminfo.tickerid, \"D\", f(close)))")
            assertTrue(e.any { it.contains("not 'v' (read by f())") }, "$fn -> $e")
        }
        refused("f(x) =>\n    y = v\n    y")
        refused("f(x) =>\n    [a, b] = [v, 1]\n    a")
        refused("f(x) =>\n    y = 0.0\n    y := v\n    y")
        refused("f(x) =>\n    y = 0.0\n    if x > 1\n        y := 1\n    else\n        y := v\n    y")
        refused("f(x) =>\n    y = 0.0\n    for i = 0 to v\n        y += i\n    y")
        refused("f(x) =>\n    y = 0.0\n    for i = 0 to 3 by v\n        y += i\n    y")
        refused("f(x) =>\n    y = 0.0\n    for i = v to 3\n        y += i\n    y")
        refused("f(x) =>\n    y = 0.0\n    for i = 0 to 3\n        y += v\n        break\n    y")
        refused("f(x) =>\n    y = 0.0\n    for e in array.from(v)\n        y += e\n    y")
        refused("f(x) =>\n    y = 0.0\n    while y < v\n        y += 1\n    y")
        refused("f(x) =>\n    switch v\n        1 => 1\n        => 2")
        refused("f(x) =>\n    switch\n        x > v => 1\n        => 2")
        refused("f(x) =>\n    switch\n        x > 1 => v\n        => 2")
        refused("f(x) =>\n    y = if x > 1\n        v\n    else\n        1\n    y")
        refused("f(x = v) => x")
        refused("f(x) =>\n    v.size()")
        // Locals of every kind declared in the body shadow chart variables of the same name.
        for (body in listOf("f(x) =>\n    v = x\n    v", "f(x) =>\n    [v, w] = [x, 1]\n    v",
                "f(x) =>\n    r = 0.0\n    if x > 1\n        v = 1\n        r := v\n    else\n        v = 2\n        r := v\n    r",
                "f(x) =>\n    r = 0.0\n    for v = 0 to 2\n        r += v\n    r",
                "f(x) =>\n    r = 0.0\n    for [v, e] in array.from(1, 2)\n        r += v\n    r",
                "f(x) =>\n    r = 0.0\n    for v in array.from(1, 2)\n        r += v\n    r",
                "f(x) =>\n    r = 0.0\n    while r < 1\n        v = 1\n        r += v\n    r",
                "f(x) =>\n    switch\n        x > 1 =>\n            v = 1\n            v\n        => 0",
                "f(v) => v"))
            PC.ok("$pre$body\nplot(request.security(syminfo.tickerid, \"D\", f(close)))")
        // A function reached twice is walked once, and one calling itself through a chain is not looped.
        val e = PC.errors("${pre}g() => v\nf(x) => g() + g()\nplot(request.security(syminfo.tickerid, \"D\", f(close)))")
        assertEquals(1, e.count { it.contains("read by g()") }, e.toString())
    }

    @Test fun declarationStatementRules() {
        PC.oneError("x = indicator(\"a\")\nplot(close)", "indicator() must be a statement of its own at the top level")
        PC.oneError("if true\n    indicator(\"a\")\nplot(close)", "indicator() must be a statement of its own at the top level")
        PC.oneError("${H}study(\"b\")\nplot(close)", "Only one indicator() or strategy() declaration is allowed")
        PC.oneError("plot(close)", "The script needs a declaration")
        // Without the declaration, other errors are reported rather than the missing declaration.
        val e = PC.errors("plot(foo)")
        assertEquals(listOf("Undeclared identifier 'foo'"), e)
    }

    @Test fun plotLimitsAndPlacement() {
        PC.ok(H + (1..64).joinToString("\n") { "plot(close)" })
        PC.oneError(H + (1..65).joinToString("\n") { "plot(close)" }, "At most 64 plots per script")
        PC.oneError("${H}f() =>\n    plot(close)\n    1\nx = f()", "Cannot use 'plot' in a local scope")
        PC.oneError("${H}if true\n    hline(1)", "Cannot use 'hline' in a local scope")
    }

    @Test fun inputLimitsAndPlacement() {
        PC.ok(H + (1..100).joinToString("\n") { "i$it = input.int($it)" } + "\nplot(close)")
        PC.oneError(H + (1..101).joinToString("\n") { "i$it = input.int($it)" } + "\nplot(close)", "At most 100 inputs per script")
        PC.oneError("${H}f() => input.int(1)\nplot(f())", "Inputs must be declared at the top level")
    }

    @Test fun strategyCallsNeedAStrategy() {
        PC.oneError("${H}strategy.entry(\"L\", strategy.long)", "strategy.entry() needs a strategy() declaration, not indicator()")
        // A strategy.* call before the declaration is accepted: the declaration may come later in the script.
        PC.ok("strategy.entry(\"L\", strategy.long)\n${S}")
        assertTrue(PC.warnings("${S}strategy.exit(\"x\", oca_name = \"g\", stop = 1)").contains("OCA groups are ignored in the phone backtest"))
        assertTrue(PC.warnings("${S}strategy.exit(\"x\", stop = 1)").none { it.contains("OCA") })
    }

    @Test fun errorsAreCappedAt25AndSortedByLine() {
        val src = H + (1..40).joinToString("\n") { "plot(u$it)" }
        val e = PC.failed(src).errors
        assertEquals(25, e.size)
        assertEquals(e.sortedBy { it.line }, e)
    }

    @Test fun recursionIsReportedOnce() {
        val e = PC.errors("${H}f(n) => f(n - 1)\nplot(f(1))")
        assertEquals(listOf("f() cannot call itself: Pine functions are not recursive"), e)
    }
}
