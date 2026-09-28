package com.optionslab.engine.pine

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Shared helpers for the PineCoverage* tests. */
internal object PC {
    /** 2026-09-22 09:15 IST (a Tuesday). */
    const val T0 = 1790048700L

    /** Candles [step] seconds apart from [T0], one per close: open = previous close, high/low one point outside. */
    fun bars(vararg close: Double, step: Long = 300L, volume: Double = 100.0): List<Pine.Bar> = close.mapIndexed { i, c ->
        val o = if (i == 0) c else close[i - 1]
        Pine.Bar(T0 + i * step, o, maxOf(o, c) + 1, minOf(o, c) - 1, c, volume)
    }

    fun bar(i: Int, o: Double, h: Double, l: Double, c: Double, v: Double = 100.0, step: Long = 300L) = Pine.Bar(T0 + i * step, o, h, l, c, v)

    fun ok(src: String): Pine.Script = when (val r = Pine.compile(src.trimIndent())) {
        is Pine.Compiled.Ok -> r.script
        is Pine.Compiled.Failed -> fail("expected to compile: ${r.errors}")
    }

    fun failed(src: String): Pine.Compiled.Failed = when (val r = Pine.compile(src.trimIndent())) {
        is Pine.Compiled.Ok -> fail("expected errors for:\n$src")
        is Pine.Compiled.Failed -> r
    }

    fun errors(src: String): List<String> = failed(src).errors.map { it.message }

    /** The script fails to compile with exactly one error containing [needle]; that error is returned. */
    fun oneError(src: String, needle: String): Pine.Problem {
        val e = failed(src).errors
        assertTrue(e.any { it.message.contains(needle) }, "wanted '$needle' in $e")
        return e.first { it.message.contains(needle) }
    }

    fun warnings(src: String): List<String> = ok(src).warnings.map { it.message }

    fun run(src: String, bars: List<Pine.Bar>, inputs: Map<String, Any?> = emptyMap(), interval: String = "5m", symbol: String = "NIFTY",
            qty: Double? = null, costs: Pine.Costs = Pine.Costs()): Pine.Run {
        val r = Pine.run(ok(src), bars, inputs, symbol, interval, qty, costs = costs)
        assertNull(r.error, r.error?.toString())
        return r
    }

    /** The run stops with a runtime error containing [needle]. */
    fun runError(src: String, bars: List<Pine.Bar>, needle: String, interval: String = "5m"): Pine.Problem {
        val r = Pine.run(ok(src), bars, interval = interval)
        val e = assertNotNull(r.error, "expected a run error containing '$needle'")
        assertTrue(e.message.contains(needle), e.message)
        return e
    }

    /** Plot [i] of the indicator "indicator(...)\n<body>" over [bars]. */
    fun plot(src: String, bars: List<Pine.Bar>, i: Int = 0, interval: String = "5m", inputs: Map<String, Any?> = emptyMap()): DoubleArray =
        run(src, bars, inputs, interval).plots[i]

    /** The last value of a single-expression indicator: plot([expr]). */
    fun value(expr: String, bars: List<Pine.Bar> = bars(1.0, 2.0, 3.0), pre: String = "", interval: String = "5m"): Double =
        plot("indicator(\"v\")\n$pre\nplot($expr)\n", bars, interval = interval).last()

    fun near(expected: Double, actual: Double, tol: Double = 1e-9, msg: String? = null) {
        if (expected.isNaN()) assertTrue(actual.isNaN(), msg ?: "expected NaN, got $actual") else assertEquals(expected, actual, tol, msg)
    }

    fun closed(r: Pine.Run) = r.report!!.trades.filter { !it.open }
}
