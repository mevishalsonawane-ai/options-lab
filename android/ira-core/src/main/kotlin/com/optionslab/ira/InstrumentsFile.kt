package com.optionslab.ira

import com.optionslab.engine.Kite
import com.optionslab.engine.Right
import java.io.Reader
import java.time.LocalDate

/**
 * The phone's saved instruments list (kite_instruments.json: `{"day":"YYYY-MM-DD","i":[[token,"symbol","name","expiry",strike,
 * lot,"CE",tick],...]}`), read as a stream (memory audit, 14 Oct 2026, item 5). Before, the whole file was read into one
 * string and parsed into a JSON tree - several MB at once, after every restart - before the list was made. Now the rows
 * become instruments as they are read, and the words every row repeats (the index's name, the expiry date) are one shared
 * object each instead of a copy per contract ([compact] does the same for a list just downloaded).
 *
 * Any row that does not read is skipped; a file that is not this shape gives null (the caller keeps what it had). Pure.
 */
object InstrumentsFile {
    /** The file's day and its instruments, or null when it is not a list of this shape. Reads [r] to its end (the caller closes it). */
    fun read(r: Reader): Pair<String, List<Kite.Instrument>>? = runCatching {
        val p = Pull(r)
        val names = HashMap<String, String>()
        val dates = HashMap<String, LocalDate>()
        var day = ""
        var list: ArrayList<Kite.Instrument>? = null
        p.expect('{')
        if (!p.endOf('}')) do {
            val key = p.string()
            p.expect(':')
            when (key) {
                "day" -> day = p.valueText() ?: ""
                "i" -> {
                    val out = ArrayList<Kite.Instrument>()
                    p.expect('[')
                    if (!p.endOf(']')) do {
                        val row = ArrayList<String?>(8)
                        p.expect('[')
                        if (!p.endOf(']')) do { row += p.valueText() } while (p.more(']'))
                        row(row, names, dates)?.let { out += it }
                    } while (p.more(']'))
                    list = out
                }
                else -> p.valueText()
            }
        } while (p.more('}'))
        list?.let { day to it }
    }.getOrNull()

    private fun row(c: List<String?>, names: HashMap<String, String>, dates: HashMap<String, LocalDate>): Kite.Instrument? = runCatching {
        if (c.size < 8) return null
        val token = c[0]!!.let { it.toLongOrNull() ?: it.toDouble().toLong() }
        val name = c[2]!!.let { names.getOrPut(it) { it } }
        val expiry = c[3]!!.let { dates.getOrPut(it) { LocalDate.parse(it) } }
        val right = if (c[6] == "CE") Right.CE else Right.PE
        Kite.Instrument(token, c[1]!!, name, expiry, c[4]!!.toDouble(), c[5]!!.let { it.toIntOrNull() ?: it.toDouble().toInt() }, right, c[7]!!.toDouble())
    }.getOrNull()

    /** [list] with each repeated name and expiry shared (one object each); the rows are the same. */
    fun compact(list: List<Kite.Instrument>): List<Kite.Instrument> {
        val names = HashMap<String, String>()
        val dates = HashMap<LocalDate, LocalDate>()
        return list.map { i ->
            val n = names.getOrPut(i.name) { i.name }; val e = dates.getOrPut(i.expiry) { i.expiry }
            if (n === i.name && e === i.expiry) i else i.copy(name = n, expiry = e)
        }
    }

    /** A small pull reader over JSON text: just what the instruments file needs (strings, numbers, literals, nesting). */
    private class Pull(private val r: Reader) {
        private var peeked = -2

        private fun peek(): Int { if (peeked == -2) peeked = r.read(); return peeked }
        private fun next(): Int { val c = peek(); peeked = -2; return c }
        private fun skipWs() { while (true) { val c = peek(); if (c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code) next() else return } }

        fun expect(ch: Char) { skipWs(); val c = next(); require(c == ch.code) { "expected $ch" } }

        /** The container closes here ([ch] taken) - an empty one. */
        fun endOf(ch: Char): Boolean { skipWs(); if (peek() == ch.code) { next(); return true }; return false }

        /** After an element: a comma (more to come, true) or [ch] (the end, false). */
        fun more(ch: Char): Boolean {
            skipWs()
            val c = next()
            if (c == ','.code) return true
            require(c == ch.code) { "expected , or $ch" }
            return false
        }

        fun string(): String {
            skipWs()
            require(next() == '"'.code) { "expected a string" }
            val b = StringBuilder()
            while (true) {
                val c = next()
                require(c >= 0) { "unended string" }
                when (c) {
                    '"'.code -> return b.toString()
                    '\\'.code -> when (val e = next()) {
                        'n'.code -> b.append('\n'); 't'.code -> b.append('\t'); 'r'.code -> b.append('\r')
                        'b'.code -> b.append('\b'); 'f'.code -> b.append('\u000C')
                        'u'.code -> { var v = 0; repeat(4) { v = v * 16 + Character.digit(next(), 16).also { d -> require(d >= 0) } }; b.append(v.toChar()) }
                        else -> { require(e >= 0); b.append(e.toChar()) }
                    }
                    else -> b.append(c.toChar())
                }
            }
        }

        /** A scalar's text (a string's content, a number's digits; null for null), or a nested value skipped (null). */
        fun valueText(): String? {
            skipWs()
            return when (peek()) {
                '"'.code -> string()
                '{'.code, '['.code -> { skip(); null }
                else -> {
                    val b = StringBuilder()
                    while (true) {
                        val c = peek()
                        if (c < 0 || c == ','.code || c == ']'.code || c == '}'.code || c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code) break
                        b.append(next().toChar())
                    }
                    val t = b.toString()
                    require(t.isNotEmpty()) { "expected a value" }
                    if (t == "null") null else t
                }
            }
        }

        /** One nested object or array skipped whole. */
        private fun skip() {
            skipWs()
            val open = next()
            val close = if (open == '{'.code) '}' else ']'
            if (endOf(close)) return
            do {
                if (open == '{'.code) { string(); expect(':') }
                skipWs()
                if (peek() == '{'.code || peek() == '['.code) skip() else valueText()
            } while (more(close))
        }
    }
}
