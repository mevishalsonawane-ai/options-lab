package com.optionslab.engine.options

import kotlin.math.abs
import kotlin.math.max
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Just enough JSON to read the fixtures under `resources/options/`, which are
 * written by calling IraAlgo's own Python and TypeScript; the scripts that do
 * it live in `resources/options/generators/` (usage in `env.py`) and are
 * deterministic, so a regenerated fixture is byte-identical. The engine takes
 * no dependencies, so neither do its tests. Non-finite
 * numbers arrive as the strings "Infinity", "-Infinity" and "NaN".
 */
object Json {
    fun parse(text: String): Any? = Parser(text).run { val v = value(); ws(); require(i == s.length) { "trailing data at $i" }; v }

    fun resource(name: String): Any? =
        parse(Json::class.java.getResource("/options/$name")!!.readText())

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                'N' -> lit("NaN", Double.NaN)
                'I' -> lit("Infinity", Double.POSITIVE_INFINITY)
                else -> if (c == '-' && s.startsWith("-Infinity", i)) lit("-Infinity", Double.NEGATIVE_INFINITY) else num()
            }
        }
        fun lit(word: String, v: Any?): Any? { require(s.startsWith(word, i)) { "bad literal at $i" }; i += word.length; return v }
        fun num(): Any {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            val t = s.substring(st, i)
            return if (t.any { it in ".eE" }) t.toDouble() else t.toLong()
        }
        fun str(): String {
            i++
            val sb = StringBuilder()
            while (s[i] != '"') {
                if (s[i] == '\\') {
                    i++
                    when (s[i]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000c')
                        'u' -> { sb.append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }
                        else -> sb.append(s[i])
                    }
                } else sb.append(s[i])
                i++
            }
            i++
            return sb.toString()
        }
        fun arr(): List<Any?> {
            i++; ws()
            val out = ArrayList<Any?>()
            if (s[i] == ']') { i++; return out }
            while (true) {
                out.add(value()); ws()
                if (s[i] == ',') { i++; continue }
                require(s[i] == ']') { "expected ] at $i" }; i++; return out
            }
        }
        fun obj(): Map<String, Any?> {
            i++; ws()
            val out = LinkedHashMap<String, Any?>()
            if (s[i] == '}') { i++; return out }
            while (true) {
                ws(); val k = str(); ws(); require(s[i] == ':'); i++
                out[k] = value(); ws()
                if (s[i] == ',') { i++; continue }
                require(s[i] == '}') { "expected } at $i" }; i++; return out
            }
        }
    }
}

// ---- typed access -----------------------------------------------------------

@Suppress("UNCHECKED_CAST")
fun Any?.obj(): Map<String, Any?> = this as Map<String, Any?>

@Suppress("UNCHECKED_CAST")
fun Any?.list(): List<Any?> = this as List<Any?>

fun Any?.dbl(): Double = when (this) {
    is Number -> this.toDouble()
    "Infinity" -> Double.POSITIVE_INFINITY
    "-Infinity" -> Double.NEGATIVE_INFINITY
    "NaN" -> Double.NaN
    else -> error("not a number: $this")
}

fun Any?.dblOrNull(): Double? = if (this == null) null else dbl()
fun Any?.lng(): Long = (this as Number).toLong()
fun Any?.str(): String = this as String
fun Map<String, Any?>.d(k: String): Double = getValue(k).dbl()
fun Map<String, Any?>.dn(k: String): Double? = this[k].dblOrNull()
fun Map<String, Any?>.o(k: String): Map<String, Any?> = getValue(k).obj()
fun Map<String, Any?>.l(k: String): List<Any?> = getValue(k).list()
fun Map<String, Any?>.s(k: String): String = getValue(k).str()
fun Map<String, Any?>.dl(k: String): List<Double> = l(k).map { it.dbl() }

// ---- numeric assertions -------------------------------------------------------

/** |a - b| <= rel * max(|a|, |b|) + abs. Infinities must match exactly; NaN matches NaN. */
fun near(expected: Double, actual: Double, rel: Double = 1e-6, abs: Double = 1e-9, what: String = "") {
    if (expected.isNaN() || actual.isNaN()) { assertTrue(expected.isNaN() && actual.isNaN(), "$what: python $expected vs kotlin $actual"); return }
    if (expected.isInfinite() || actual.isInfinite()) { assertEquals(expected, actual, what); return }
    val tol = rel * max(abs(expected), abs(actual)) + abs
    assertTrue(abs(expected - actual) <= tol, "$what: python $expected vs kotlin $actual (diff ${expected - actual})")
}

fun nearOrNull(expected: Double?, actual: Double?, rel: Double = 1e-6, abs: Double = 1e-9, what: String = "") {
    if (expected == null) { assertNull(actual, "$what: python null vs kotlin $actual"); return }
    assertTrue(actual != null, "$what: python $expected vs kotlin null")
    near(expected, actual!!, rel, abs, what)
}
