package com.optionslab.engine.risk

import com.optionslab.engine.strategy.Json
import java.util.zip.GZIPInputStream
import kotlin.test.fail

/**
 * Loads the parity fixtures and compares decisions the way the fixtures were
 * written: numbers by value (an int in Python is a Long here, a float a
 * Double), maps by key, lists in order, NaN equal to NaN.
 *
 * The fixtures are produced by the generator scripts that sit beside them in
 * `src/test/resources/{risk,strategy}/`, which run IraAlgo's Python over the
 * same inputs. They are gzipped only to keep the repository small.
 */
object Fixtures {
    @Suppress("UNCHECKED_CAST")
    fun load(path: String): Map<String, Any?> {
        val stream = Fixtures::class.java.getResourceAsStream("/$path") ?: fail("missing fixture $path")
        val text = (if (path.endsWith(".gz")) GZIPInputStream(stream) else stream).bufferedReader().use { it.readText() }
        return Json.parse(text) as Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    fun cases(path: String): List<Map<String, Any?>> = load(path)["cases"] as List<Map<String, Any?>>

    /** Null when equal, else the path of the first difference. */
    fun diff(expected: Any?, actual: Any?, at: String = "$"): String? {
        if (expected == null || actual == null) return if (expected == null && actual == null) null else "$at: expected $expected, got $actual"
        if (expected is Number && actual is Number) {
            val e = expected.toDouble()
            val a = actual.toDouble()
            return if (e == a || (e.isNaN() && a.isNaN())) null else "$at: expected $e, got $a"
        }
        if (expected is Map<*, *> && actual is Map<*, *>) {
            if (expected.keys != actual.keys) return "$at: keys ${expected.keys} vs ${actual.keys}"
            for (k in expected.keys) diff(expected[k], actual[k], "$at.$k")?.let { return it }
            return null
        }
        if (expected is List<*> && actual is List<*>) {
            if (expected.size != actual.size) return "$at: size ${expected.size} vs ${actual.size}: $expected vs $actual"
            for (i in expected.indices) diff(expected[i], actual[i], "$at[$i]")?.let { return it }
            return null
        }
        return if (expected == actual) null else "$at: expected '$expected', got '$actual'"
    }
}
