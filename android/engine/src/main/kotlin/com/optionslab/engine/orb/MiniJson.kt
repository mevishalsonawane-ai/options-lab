package com.optionslab.engine.orb

/**
 * A small JSON reader for the files the engine itself must read (Solo 2.0's model, research R57's `A2.json`): objects, arrays,
 * strings (with escapes), numbers, true / false / null. Objects come back as [Map]s (in file order), arrays as [List]s, numbers
 * as [Double] (Kotlin's `toDouble` is correctly rounded, as Python's float is, so a number reads back to the same double),
 * null as null. Anything else is an error with its position. Pure.
 */
object MiniJson {
    class Error(msg: String) : IllegalArgumentException(msg)

    fun parse(text: String): Any? {
        val p = Reader(text)
        p.ws()
        val v = p.value()
        p.ws()
        if (p.i != text.length) throw Error("trailing text at ${p.i}")
        return v
    }

    private class Reader(val s: String) {
        var i = 0

        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }

        fun value(): Any? {
            if (i >= s.length) throw Error("unexpected end at $i")
            return when (val ch = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> if (ch == '-' || ch.isDigit()) num() else throw Error("unexpected '$ch' at $i")
            }
        }

        private fun word(w: String, v: Any?): Any? {
            if (!s.startsWith(w, i)) throw Error("expected $w at $i")
            i += w.length
            return v
        }

        private fun num(): Double {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
            return s.substring(start, i).toDoubleOrNull() ?: throw Error("bad number at $start")
        }

        private fun str(): String {
            i++
            val b = StringBuilder()
            while (true) {
                if (i >= s.length) throw Error("unterminated string")
                val ch = s[i++]
                when (ch) {
                    '"' -> return b.toString()
                    '\\' -> {
                        if (i >= s.length) throw Error("unterminated escape")
                        when (val e = s[i++]) {
                            'n' -> b.append('\n'); 't' -> b.append('\t'); 'r' -> b.append('\r'); 'b' -> b.append('\b'); 'f' -> b.append('\u000c')
                            'u' -> {
                                if (i + 4 > s.length) throw Error("bad escape at $i")
                                b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4
                            }
                            else -> b.append(e)
                        }
                    }
                    else -> b.append(ch)
                }
            }
        }

        private fun arr(): List<Any?> {
            i++
            val out = ArrayList<Any?>()
            ws()
            if (i < s.length && s[i] == ']') { i++; return out }
            while (true) {
                ws(); out += value(); ws()
                if (i >= s.length) throw Error("unterminated array")
                when (s[i++]) { ',' -> continue; ']' -> return out; else -> throw Error("expected , or ] at ${i - 1}") }
            }
        }

        private fun obj(): Map<String, Any?> {
            i++
            val out = LinkedHashMap<String, Any?>()
            ws()
            if (i < s.length && s[i] == '}') { i++; return out }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') throw Error("expected a key at $i")
                val k = str()
                ws()
                if (i >= s.length || s[i] != ':') throw Error("expected : at $i")
                i++
                ws(); out[k] = value(); ws()
                if (i >= s.length) throw Error("unterminated object")
                when (s[i++]) { ',' -> continue; '}' -> return out; else -> throw Error("expected , or } at ${i - 1}") }
            }
        }
    }
}
