package com.optionslab.ira

/**
 * The same Pine strategy saved twice (Boss's paper diagnostics, 06 Oct: scripts #3 and #4 were both "Jarvis: bullish
 * engulfing Nifty 15m" - the Strategy Lab added it again at 09:50 - so every trade was doubled). Two scripts are the same
 * when their code, read without its comments and whitespace, trades the same symbol on the same chart. The Strategy
 * Lab's approval refuses a script that is already saved ([duplicateOf]: "already armed as #N"), and once, on this update,
 * the armed duplicates already saved are disarmed - the oldest armed one is kept ([extraArmed]). Nothing is deleted.
 * Pure: no files, no clock.
 */
object PineDupes {
    /** One saved script: its id (#N), code, symbol, chart ("15m") and whether it auto-trades now. */
    data class Script(val id: Long, val code: String, val symbol: String, val interval: String, val armed: Boolean)

    /**
     * [code] without its comments (a `//` to the end of the line, outside a string) and without any whitespace, so a
     * script re-indented, re-commented or re-headed ("// Written by Jarvis ... at 09:50") reads the same.
     */
    fun normalize(code: String): String {
        val out = StringBuilder(code.length)
        var quote: Char? = null
        var i = 0
        while (i < code.length) {
            val ch = code[i]
            if (quote != null) {
                out.append(ch)
                if (ch == '\\' && i + 1 < code.length) { out.append(code[i + 1]); i += 2; continue }
                if (ch == quote) quote = null
                i++; continue
            }
            if (ch == '"' || ch == '\'') { quote = ch; out.append(ch); i++; continue }
            if (ch == '/' && i + 1 < code.length && code[i + 1] == '/') {
                while (i < code.length && code[i] != '\n') i++
                continue
            }
            if (!ch.isWhitespace()) out.append(ch)
            i++
        }
        return out.toString()
    }

    /** What makes two scripts the same: the code as [normalize] reads it, the symbol and the chart. */
    fun key(code: String, symbol: String, interval: String): String =
        normalize(code) + "|" + symbol.trim().uppercase() + "|" + interval.trim().lowercase()

    /**
     * The saved script a new one ([code] on [symbol] [interval]) would repeat - an armed one first, then the oldest -
     * or null when it is new.
     */
    fun duplicateOf(code: String, symbol: String, interval: String, saved: List<Script>): Script? {
        val k = key(code, symbol, interval)
        return saved.filter { key(it.code, it.symbol, it.interval) == k }.sortedWith(compareBy<Script>({ !it.armed }, { it.id })).firstOrNull()
    }

    /** Why a new script was not added, for the chat and the Requests panel. */
    fun refusal(dup: Script): String = if (dup.armed) "already armed as #${dup.id}" else "already saved as #${dup.id} (switched off)"

    /**
     * The armed scripts that repeat an older armed one (same code, symbol and chart), each with the id kept: the oldest
     * armed script of each group (the lowest id) stays, every other armed one is to be disarmed. Empty when none.
     */
    fun extraArmed(saved: List<Script>): List<Pair<Script, Long>> =
        saved.filter { it.armed }.groupBy { key(it.code, it.symbol, it.interval) }.values.flatMap { g ->
            val keep = g.minByOrNull { it.id }!!
            g.filter { it.id != keep.id }.sortedBy { it.id }.map { it to keep.id }
        }

    /** The Pine log's line for a script disarmed as a repeat of [kept]. */
    fun note(kept: Long): String = "Switched off: the same strategy as #$kept (same code, symbol and chart), so every trade was doubled. " +
        "#$kept keeps trading; this one is kept (not deleted) and any open holding is managed to its exit."
}
