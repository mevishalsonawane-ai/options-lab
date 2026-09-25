package com.optionslab.engine.strategy

/**
 * A small JSON reader and writer, so strategies and runs can be persisted
 * without adding a library to an engine that is deliberately stdlib-only.
 *
 * Values are plain Kotlin: `null`, [Boolean], [Long] (a number written without
 * a fraction or exponent), [Double], [String], [List] and [Map] (insertion
 * ordered). Keeping integers and floats apart matters: the Python this engine
 * mirrors treats `5` and `5.0` differently in messages, and a lot count read
 * back as `5.0` would print differently from the one that was saved.
 *
 * `NaN`, `Infinity` and `-Infinity` are read and written as bare tokens, the
 * way Python's `json` module does by default. A risk figure that became
 * non-finite must round-trip as itself rather than fail the save.
 */
object Json {
    class ParseError(msg: String) : IllegalArgumentException(msg)

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.ws()
        val v = p.value()
        p.ws()
        if (p.i != text.length) throw ParseError("trailing data at ${p.i}")
        return v
    }

    fun write(value: Any?): String = StringBuilder().also { write(value, it) }.toString()

    fun write(value: Any?, out: StringBuilder) {
        when (value) {
            null -> out.append("null")
            is Boolean -> out.append(if (value) "true" else "false")
            is Int, is Long, is Short, is Byte -> out.append(value.toString())
            is Double -> out.append(number(value))
            is Float -> out.append(number(value.toDouble()))
            is String -> string(value, out)
            is Enum<*> -> string(value.toString(), out)
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) out.append(',')
                    first = false
                    string(k.toString(), out)
                    out.append(':')
                    write(v, out)
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                var first = true
                for (v in value) {
                    if (!first) out.append(',')
                    first = false
                    write(v, out)
                }
                out.append(']')
            }
            is Array<*> -> write(value.asList(), out)
            else -> throw IllegalArgumentException("cannot write ${value::class.simpleName} as JSON")
        }
    }

    /** A double that reads back as a double: `5.0`, never `5`. */
    private fun number(d: Double): String = when {
        d.isNaN() -> "NaN"
        d == Double.POSITIVE_INFINITY -> "Infinity"
        d == Double.NEGATIVE_INFINITY -> "-Infinity"
        else -> d.toString()
    }

    private fun string(s: String, out: StringBuilder) {
        out.append('"')
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (c < ' ') out.append(String.format("\\u%04x", c.code)) else out.append(c)
            }
        }
        out.append('"')
    }

    private class Parser(val s: String) {
        var i = 0

        fun ws() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            if (i >= s.length) throw ParseError("unexpected end")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                'N' -> lit("NaN", Double.NaN)
                'I' -> lit("Infinity", Double.POSITIVE_INFINITY)
                else -> if (c == '-' || c.isDigit()) num() else throw ParseError("unexpected '$c' at $i")
            }
        }

        fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw ParseError("expected $word at $i")
            i += word.length
            return v
        }

        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++
            ws()
            if (s[i] == '}') { i++; return m }
            while (true) {
                ws()
                if (s[i] != '"') throw ParseError("expected key at $i")
                val k = str()
                ws()
                if (s[i] != ':') throw ParseError("expected ':' at $i")
                i++
                ws()
                m[k] = value()
                ws()
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return m }
                    else -> throw ParseError("expected ',' or '}' at $i")
                }
            }
        }

        fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++
            ws()
            if (s[i] == ']') { i++; return l }
            while (true) {
                ws()
                l.add(value())
                ws()
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return l }
                    else -> throw ParseError("expected ',' or ']' at $i")
                }
            }
        }

        fun str(): String {
            val b = StringBuilder()
            i++
            while (true) {
                if (i >= s.length) throw ParseError("unterminated string")
                val c = s[i++]
                when (c) {
                    '"' -> return b.toString()
                    '\\' -> {
                        when (val e = s[i++]) {
                            '"' -> b.append('"')
                            '\\' -> b.append('\\')
                            '/' -> b.append('/')
                            'b' -> b.append('\b')
                            'f' -> b.append('\u000C')
                            'n' -> b.append('\n')
                            'r' -> b.append('\r')
                            't' -> b.append('\t')
                            'u' -> { b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> throw ParseError("bad escape '\\$e'")
                        }
                    }
                    else -> b.append(c)
                }
            }
        }

        fun num(): Any {
            val start = i
            if (s[i] == '-') {
                i++
                if (s.startsWith("Infinity", i)) { i += 8; return Double.NEGATIVE_INFINITY }
            }
            var integral = true
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) {
                if (s[i] in ".eE") integral = false
                i++
            }
            val t = s.substring(start, i)
            if (integral) t.toLongOrNull()?.let { return it }
            return t.toDoubleOrNull() ?: throw ParseError("bad number '$t'")
        }
    }
}

/** Typed reads over a parsed JSON object, failing with the field name. */
internal class JsonObj(val m: Map<String, Any?>) {
    fun has(k: String) = m[k] != null
    fun str(k: String): String? = m[k]?.toString()
    fun reqStr(k: String): String = str(k) ?: throw Json.ParseError("missing $k")
    fun dbl(k: String): Double? = (m[k] as Number?)?.toDouble()
    fun int(k: String): Int? = (m[k] as Number?)?.toInt()
    fun long(k: String): Long? = (m[k] as Number?)?.toLong()
    fun bool(k: String): Boolean? = m[k] as Boolean?
    @Suppress("UNCHECKED_CAST")
    fun obj(k: String): JsonObj? = (m[k] as Map<String, Any?>?)?.let { JsonObj(it) }
    @Suppress("UNCHECKED_CAST")
    fun list(k: String): List<Any?>? = m[k] as List<Any?>?
}
