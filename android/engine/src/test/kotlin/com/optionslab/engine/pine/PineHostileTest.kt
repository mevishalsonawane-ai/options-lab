package com.optionslab.engine.pine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Scripts written to break the app: none may crash it, hang it or eat its memory.
 * Each must end in a compile error or a run error, quickly.
 */
class PineHostileTest {
    private fun bars(n: Int) = List(n) { Pine.Bar(1790048700L + it * 300L, 100.0 + it % 7, 105.0 + it % 7, 95.0 + it % 7, 101.0 + it % 5, 10.0) }

    private fun refused(src: String, needle: String) {
        when (val c = Pine.compile(src)) {
            is Pine.Compiled.Ok -> fail("expected a compile error containing '$needle'")
            is Pine.Compiled.Failed -> assertTrue(c.errors.any { it.message.contains(needle) }, c.errors.toString())
        }
    }

    private fun stops(src: String, needle: String, n: Int = 50, budgetMs: Long = 10_000) {
        val s = (Pine.compile(src) as? Pine.Compiled.Ok)?.script ?: fail("expected to compile: ${Pine.compile(src)}")
        val t0 = System.nanoTime()
        val r = Pine.run(s, bars(n), budgetMs = budgetMs)
        val e = assertNotNull(r.error, "expected a run error")
        assertTrue(e.message.contains(needle), e.message)
        assertTrue((System.nanoTime() - t0) / 1_000_000 < budgetMs + 2_000, "took too long")
    }

    @Test fun deepNestingIsRefusedNotAStackOverflow() {
        refused("indicator(\"x\")\nplot(" + "(".repeat(20000) + "1" + ")".repeat(20000) + ")\n", "too deeply")
        refused("indicator(\"x\")\nplot(" + List(5000) { "1" }.joinToString("+") + ")\n", "too long or nested")
        refused("indicator(\"x\")\nplot(" + "-".repeat(5000) + "1)\n", "too deeply")
        refused("indicator(\"x\")\nplot(" + "not ".repeat(5000) + "true ? 1 : 0)\n", "too deeply")
        refused("indicator(\"x\")\nplot(" + "f(".repeat(3000) + "1" + ")".repeat(3000) + ")\n", "too deeply")
        refused("indicator(\"x\")\n" + (0 until 45).joinToString("") { "    ".repeat(it) + "if true\n" } + "    ".repeat(45) + "x = 1\n", "nested too deeply")
    }

    @Test fun oversizedScriptsAreRefused() {
        refused("indicator(\"x\")\n// " + "a".repeat(Pine.MAX_CHARS), "too long")
        refused("indicator(\"x\")\n" + "x0 = 1\n".repeat(Pine.MAX_LINES), "too long")
    }

    @Test fun recursionIsRefused() {
        refused("indicator(\"x\")\nf(n) => n <= 0 ? 0 : f(n - 1) + f(n - 1)\nplot(f(30))\n", "cannot call itself")
        // Mutual recursion needs a call to a function declared later: refused as unknown.
        refused("indicator(\"x\")\na(n) => b(n)\nb(n) => a(n)\nplot(a(1))\n", "Unknown function 'b'")
    }

    @Test fun endlessWorkStopsAtTheBudget() {
        stops("indicator(\"x\")\nx = 0.0\nwhile true\n    x += 1\nplot(x)\n", "too long")
        stops("indicator(\"x\")\nx = 0.0\nfor i = 0 to 400000\n    x += 1\nplot(x)\n", "too long", n = 5000, budgetMs = 500)
        // A chain of functions each calling the one before twice: 2^40 calls if nothing stops it.
        val chain = StringBuilder("indicator(\"x\")\nf0(n) => n + 1\n")
        for (i in 1..40) chain.append("f$i(n) => f${i - 1}(n) + f${i - 1}(n)\n")
        chain.append("plot(f40(1))\n")
        stops(chain.toString(), "too long", budgetMs = 500)
    }

    @Test fun memoryBombsStop() {
        stops("indicator(\"x\")\nvar s = \"ab\"\nfor i = 0 to 60\n    s := s + s\nplot(close)\n", "Text longer")
        stops("indicator(\"x\")\ns = str.format(\"{0}{0}{0}{0}{0}{0}{0}{0}\", \"x\")\nt = str.format(\"{0}{0}{0}{0}{0}{0}{0}{0}{0}{0}\", s + s + s + s + s + s + s + s + s + s + s + s + s + s + s + s + s + s + s + s + s + s + s + s + s + s + s + s + s + s + s + s + s + s + s + s + s + s + s + s + s + s + s + s + s + s + s + s + s + s + s + s + s + s + s + s + s + s + s + s + s + s + s + s)\nplot(close)\n", "Text longer")
        stops("indicator(\"x\")\nplot(ta.sma(close, 1e12))\n", "at most 5000")
        stops("indicator(\"x\")\nplot(ta.pivothigh(2000000000, 2000000000))\n", "at most 5000")
        stops("strategy(\"x\")\nstrategy.entry(\"L\", strategy.long, qty = 1e300)\n", "qty")
    }

    @Test fun sillySettingsFallBackToDefaults() {
        val s = (Pine.compile("strategy(\"x\", pyramiding = 1e9, initial_capital = -5, default_qty_value = 1e300, commission_value = -1)\n") as Pine.Compiled.Ok).script
        assertTrue(s.settings.pyramiding <= 100 && s.settings.initialCapital > 0 && s.settings.qtyValue == 1.0 && s.settings.commissionValue == 0.0)
    }

    @Test fun colourAndTextFromAScriptAreChecked() {
        val s = (Pine.compile("indicator(\"x\", overlay = true)\nplotshape(true, \"B\", shape.circle, location.abovebar, \"</style><script>\")\n") as Pine.Compiled.Ok).script
        val r = Pine.run(s, bars(3))
        assertTrue(r.markers.all { Pine.safeColor(it.color) != null })
    }

    /** Random token soup: every input ends in a result, never an exception or a hang. */
    @Test fun fuzzNeverThrows() {
        val parts = listOf("indicator(\"x\")", "strategy(\"s\")", "plot(", ")", "(", "[", "]", "close", "x", "=", ":=", "if", "else", "for", "to",
            "while", "\n", "\n    ", "    ", "+", "-", "*", "/", "%", "?", ":", ",", "ta.sma", "ta.ema(close, 3)", "1", "0", "-1", "1e308", "na",
            "\"s\"", "'", "\"", "#FF0000", "#GG", "and", "or", "not", "f(a) =>", "f(1)", "var", "strategy.entry(\"L\", strategy.long)",
            "strategy.exit(\"X\", stop = close - 1)", "[a, b] = ta.macd(close, 12, 26, 9)", "break", "continue", "=>", ".", "..", "//", "@", "$", "\t")
        val rnd = Random(42)
        repeat(3000) {
            val src = buildString { repeat(rnd.nextInt(1, 40)) { append(parts[rnd.nextInt(parts.size)]); if (rnd.nextInt(4) == 0) append(' ') } }
            val c = try { Pine.compile(src) } catch (e: Throwable) { fail("compile threw ${e.javaClass.simpleName} on:\n$src") }
            if (c is Pine.Compiled.Ok) {
                try { Pine.run(c.script, bars(30), budgetMs = 300) } catch (e: Throwable) { fail("run threw ${e.javaClass.simpleName}: ${e.message} on:\n$src") }
            }
        }
    }

    @Test fun hiddenDirectionCharactersAreRefused() {
        refused("indicator(\"x\")\n// safe \u202E comment\nplot(close)\n", "Hidden character U+202E")
        refused("indicator(\"x\")\nplot(cl\u200Bose)\n", "Hidden character U+200B")
        refused("indicator(\"\u2066x\u2069\")\n", "Hidden character")
    }
}
