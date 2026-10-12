package com.optionslab.ira

import java.time.LocalDate
import java.util.Locale

/**
 * The diagnostics diary's errors, counted (12 Oct): "[error] Missing required field: quantity" was written three times between
 * 09:05 and 09:07 (a second close of an already flat paper row) and "[watch] a check failed in Jarvis words: ij1" twice, and
 * no agent read the diary - each was found only when Boss sent his diagnostics. Each diary line is sorted here, on the diary's
 * own thread, into a kind; the order review and the app's health read the day's counts.
 *
 *  - [kindOf]: an error's kind, its words with numbers and symbols folded ("Missing required field: quantity").
 *  - [unreadable]: a failure named only by a shrunk class name ("ij1": R8's renaming before the -keepnames rule) - nobody can
 *    act on that, so it is a problem of its own.
 *
 * Words and counts only (the diary is already redacted). Thread-safe. Pure: the day is given.
 */
object DiagErrors {
    /** At most this many kinds a day (the rest under [OTHER]). */
    const val KINDS = 30
    const val OTHER = "other errors"

    private val NUM = Regex("[0-9]+(?:[.,][0-9]+)*")
    private val SYMBOL = Regex("\\b[A-Z]{3,}[0-9A-Z]*[0-9][0-9A-Z]*\\b")
    private val FAILED = Regex("failed in ([^:]{1,60}): ([A-Za-z0-9_$.]+)")

    /** An error's kind: its words, numbers as "#" and contract symbols as "<symbol>", lower case, at most 80 characters. */
    fun kindOf(text: String): String = text.replace(SYMBOL, "<symbol>").replace(NUM, "#").replace(Regex("\\s+"), " ").trim()
        .lowercase(Locale.ENGLISH).take(80).ifEmpty { "error" }

    /** A class name that says nothing (R8's shrunk names: one to three letters, maybe digits - "ij1", "a", "Ab"). */
    fun obfuscated(name: String): Boolean {
        val n = name.substringAfterLast('.').substringAfterLast('$')
        return n.isNotEmpty() && Regex("^[A-Za-z]{1,3}[0-9]{0,2}$").matches(n) && n !in setOf("IO", "Oom", "OOM")
    }

    /** A failure line's step and its class when the class is unreadable ("Jarvis words" to "ij1"), else null. */
    fun unreadable(text: String): Pair<String, String>? {
        val m = FAILED.find(text) ?: return null
        val cls = m.groupValues[2].trimEnd('.', ' ')
        return if (obfuscated(cls)) m.groupValues[1].trim() to cls else null
    }

    /** One day's counts: [errors] by kind, [unreadable] failures by "step: class". */
    data class Day(val day: LocalDate, val errors: Map<String, Int> = emptyMap(), val unreadable: Map<String, Int> = emptyMap())

    /** The diary's counts, today's only (a new day starts afresh). */
    class Tally {
        private var d: Day? = null

        @Synchronized fun add(day: LocalDate, area: String, text: String) {
            val cur = d?.takeIf { it.day == day } ?: Day(day)
            var next = cur
            if (area.equals("error", true)) {
                val k = kindOf(text).let { if (it in cur.errors || cur.errors.size < KINDS) it else OTHER }
                next = next.copy(errors = next.errors + (k to (next.errors[k] ?: 0) + 1))
            }
            unreadable(text)?.let { (step, cls) ->
                val k = "$step: $cls"
                next = next.copy(unreadable = next.unreadable + (k to (next.unreadable[k] ?: 0) + 1))
            }
            d = next
        }

        /** [day]'s counts (empty for another day). */
        @Synchronized fun of(day: LocalDate): Day = d?.takeIf { it.day == day } ?: Day(day)

        @Synchronized fun clear() { d = null }
    }
}
