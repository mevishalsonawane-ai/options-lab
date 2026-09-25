package com.optionslab.engine.strategy

import com.optionslab.engine.risk.RiskValues

/**
 * Python's `repr()` and `str()` for the values a strategy payload can carry.
 *
 * The validator's messages embed the offending value the way Python prints it
 * (`Got 'weeky'`, `got 5.0`), and those messages are what the user reads, so
 * they are reproduced rather than approximated. Parity fixtures compare them
 * character for character.
 */
internal object Py {
    fun str(v: Any?): String = when (v) {
        is String -> v
        is Map<*, *>, is List<*> -> repr(v)
        else -> RiskValues.pyStr(v)
    }

    fun repr(v: Any?): String = when (v) {
        null -> "None"
        is String -> reprString(v)
        is Boolean -> if (v) "True" else "False"
        is Double -> RiskValues.pyRepr(v)
        is Float -> RiskValues.pyRepr(v.toDouble())
        is Number -> v.toString()
        is Map<*, *> -> v.entries.joinToString(", ", "{", "}") { "${repr(it.key)}: ${repr(it.value)}" }
        is List<*> -> v.joinToString(", ", "[", "]") { repr(it) }
        else -> v.toString()
    }

    private fun reprString(s: String): String {
        val quote = if (s.contains('\'') && !s.contains('"')) '"' else '\''
        val b = StringBuilder().append(quote)
        for (c in s) {
            when {
                c == quote || c == '\\' -> b.append('\\').append(c)
                c == '\n' -> b.append("\\n")
                c == '\r' -> b.append("\\r")
                c == '\t' -> b.append("\\t")
                c < ' ' || c == '\u007f' -> b.append(String.format("\\x%02x", c.code))
                else -> b.append(c)
            }
        }
        return b.append(quote).toString()
    }

    /** Python's `int(text)` for a string, or null where Python raises. */
    fun parseInt(text: String): Long? {
        val t = text.trim()
        if (!Regex("""[+-]?\d(?:_?\d)*""").matches(t)) return null
        return t.replace("_", "").toLongOrNull()
    }

    /** Python's `len()` counts code points, not UTF-16 units. */
    fun len(s: String): Int = s.codePointCount(0, s.length)
}
