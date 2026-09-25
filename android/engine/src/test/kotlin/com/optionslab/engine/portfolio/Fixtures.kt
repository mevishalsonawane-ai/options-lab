package com.optionslab.engine.portfolio

import java.lang.reflect.Modifier
import java.time.LocalDate
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.math.max

/**
 * Reads the Python reference fixtures (generate_fixtures.py) and compares a
 * Kotlin result, walked by reflection with camelCase fields mapped to the
 * payload's snake_case keys, against IraAlgo's JSON payload.
 */
object Fixtures {
    fun load(name: String): Any? {
        val stream = Fixtures::class.java.getResourceAsStream("/portfolio/$name.json.gz") ?: error("missing fixture $name")
        return Json(GZIPInputStream(stream).bufferedReader().readText()).parse()
    }

    @Suppress("UNCHECKED_CAST")
    fun obj(x: Any?) = x as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    fun arr(x: Any?) = x as List<Any?>

    fun num(x: Any?): Double = when (x) {
        is Double -> x
        is Map<*, *> -> (x["nonfinite"] as String).let { if (it == "nan") Double.NaN else if (it.startsWith("-")) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY }
        else -> error("not a number: $x")
    }

    val prices: Map<String, List<DailyBar>> by lazy {
        val bars = obj(obj(load("prices"))["bars"])
        bars.mapValues { (_, rows) ->
            arr(rows).map { r ->
                val row = arr(r)
                DailyBar(LocalDate.parse(row[0] as String), num(row[1]), num(row[2]), num(row[3]), (row[4] as Double?) ?: Double.NaN, num(row[5]))
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    val names: Map<String, String> by lazy { obj(load("prices"))["names"] as Map<String, String> }

    @Suppress("UNCHECKED_CAST")
    fun overrides(x: Any?): Map<String, Map<String, Double?>> =
        (x as Map<String, Map<String, Any?>>?)?.mapValues { (_, v) -> v.mapValues { (_, n) -> n as Double? } } ?: emptyMap()

    // ── Kotlin object -> JSON-like tree ─────────────────────────────────────

    private fun snake(name: String) = name.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase()

    private val RENAME = mapOf("ret" to "return")

    fun tree(x: Any?): Any? = when (x) {
        null -> null
        is Double -> x
        is Float -> x.toDouble()
        is Int -> x.toDouble()
        is Long -> x.toDouble()
        is Boolean, is String -> x
        is LocalDate -> x.toString()
        is Pair<*, *> -> listOf(tree(x.first), tree(x.second))
        is DoubleArray -> x.map { it }
        is Array<*> -> x.map { tree(it) }
        is Iterable<*> -> x.map { tree(it) }
        is Map<*, *> -> x.entries.associate { it.key.toString() to tree(it.value) }
        else -> {
            val out = LinkedHashMap<String, Any?>()
            var cls: Class<*>? = x.javaClass
            while (cls != null && cls != Any::class.java) {
                for (f in cls.declaredFields) {
                    if (Modifier.isStatic(f.modifiers) || f.isSynthetic) continue
                    f.isAccessible = true
                    out[RENAME[f.name] ?: snake(f.name)] = tree(f.get(x))
                }
                cls = cls.superclass
            }
            when (x) {
                is AssetReturn -> { @Suppress("UNCHECKED_CAST") val w = out.remove("windows") as Map<String, Any?>; out.putAll(w) }
                is CostsSummary -> { @Suppress("UNCHECKED_CAST") val l = out.remove("lines") as Map<String, Any?>; out.putAll(l) }
            }
            out
        }
    }

    // ── comparison ──────────────────────────────────────────────────────────

    class Diff { val problems = ArrayList<String>(); var compared = 0; var worstRel = 0.0; var worstPath = "" }

    /** `tol(path)` gives (relative, absolute) tolerance for a numeric leaf. */
    fun compare(
        kotlin: Any?, python: Any?, path: String = "$", diff: Diff = Diff(),
        tol: (String) -> Pair<Double, Double> = { Pair(1e-9, 1e-12) },
        skip: (String) -> Boolean = { false },
    ): Diff {
        if (skip(path)) return diff
        val py = if (python is Map<*, *> && python.keys == setOf("nonfinite")) num(python) else python
        when {
            py == null && kotlin == null -> {}
            py == null || kotlin == null -> {
                // A Kotlin non-finite double is IraAlgo's null.
                val k = kotlin
                if (!(py == null && k is Double && !k.isFinite())) diff.problems.add("$path: kotlin=$kotlin python=$py")
            }
            py is Double && kotlin is Double -> {
                diff.compared++
                val (rel, absTol) = tol(path)
                val ok = (py.isNaN() && kotlin.isNaN()) || py == kotlin ||
                    abs(py - kotlin) <= rel * max(abs(py), abs(kotlin)) + absTol
                if (!ok) diff.problems.add("$path: kotlin=$kotlin python=$py")
                if (py.isFinite() && kotlin.isFinite() && py != kotlin) {
                    val r = abs(py - kotlin) / max(abs(py), abs(kotlin))
                    if (r > diff.worstRel) { diff.worstRel = r; diff.worstPath = path }
                }
            }
            py is Map<*, *> && kotlin is Map<*, *> -> {
                val keys = (py.keys + kotlin.keys).map { it.toString() }.toSortedSet()
                for (k in keys) {
                    if (k == "status") continue
                    compare(kotlin[k], py[k], "$path.$k", diff, tol, skip)
                }
            }
            py is List<*> && kotlin is List<*> -> {
                if (py.size != kotlin.size) diff.problems.add("$path: size kotlin=${kotlin.size} python=${py.size}")
                else py.indices.forEach { compare(kotlin[it], py[it], "$path[$it]", diff, tol, skip) }
            }
            else -> {
                diff.compared++
                if (py != kotlin) diff.problems.add("$path: kotlin=$kotlin python=$py")
            }
        }
        return diff
    }
}

/** A minimal JSON reader (objects -> LinkedHashMap, numbers -> Double). */
class Json(private val s: String) {
    private var i = 0

    fun parse(): Any? { val v = value(); ws(); require(i == s.length) { "trailing data at $i" }; return v }

    private fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }

    private fun value(): Any? {
        ws()
        return when (val c = s[i]) {
            '{' -> { i++; val m = LinkedHashMap<String, Any?>(); ws(); if (s[i] == '}') { i++; return m }
                while (true) { ws(); val k = string(); ws(); expect(':'); m[k] = value(); ws(); if (s[i] == ',') { i++; continue }; expect('}'); return m } }
            '[' -> { i++; val l = ArrayList<Any?>(); ws(); if (s[i] == ']') { i++; return l }
                while (true) { l.add(value()); ws(); if (s[i] == ',') { i++; continue }; expect(']'); return l } }
            '"' -> string()
            't' -> { i += 4; true }
            'f' -> { i += 5; false }
            'n' -> { i += 4; null }
            else -> { val st = i; if (c == '-') i++; while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++; s.substring(st, i).toDouble() }
        }
    }

    private fun expect(c: Char) { require(s[i] == c) { "expected $c at $i" }; i++ }

    private fun string(): String {
        expect('"')
        val sb = StringBuilder()
        while (true) {
            val c = s[i++]
            when (c) {
                '"' -> return sb.toString()
                '\\' -> when (val e = s[i++]) {
                    'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r'); 'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                    'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                    else -> sb.append(e)
                }
                else -> sb.append(c)
            }
        }
    }
}
