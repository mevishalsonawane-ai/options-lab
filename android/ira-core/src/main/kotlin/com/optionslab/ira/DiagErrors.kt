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
 * Severities (20 Oct, Boss's 19 Oct diagnostics: "34 errors in 10 kinds" held a good-morning line, the day report, five
 * passphrase hints and the paper ticket's risk notices, so the count said nothing): every red banner used to be "[error]".
 * Each counted line now has a [Severity]: an ERROR is something that failed; a WARNING is a risk or a fault the app rides
 * out by itself (a stream drop it reconnects, a position near its breakeven, the loss limit stopping the day); a NOTICE is
 * information or a form's hint (the morning check, the day report, a passphrase too weak). Banners write "[error]",
 * "[warning]" or "[notice]" ([severityOf]); an old "[error]" line is sorted by its words ([classify]).
 *
 * Words and counts only (the diary is already redacted). Thread-safe. Pure: the day is given.
 */
object DiagErrors {
    /** How bad a counted line is; [area] is its diary area. */
    enum class Severity(val area: String) { ERROR("error"), WARNING("warning"), NOTICE("notice") }

    /** Information and form hints, by how the line starts (lower case): never an error. */
    private val NOTICE_STARTS = listOf("good morning", "day report", "week report", "good evening", "that passphrase is too easy",
        "the two passphrases differ", "the backup passphrase needs", "passphrase too weak")
    /** Risks and faults the app rides out by itself, by how the line starts (lower case). */
    private val WARNING_STARTS = listOf("index nearing your breakeven", "index below your breakeven", "short put is in the money", "daily loss limit reached",
        "live price stream dropped")

    /** A red banner's severity by its words ("Title: text"): a notice, a warning, else an error. */
    fun classify(text: String): Severity {
        val t = text.trim().lowercase(Locale.ENGLISH)
        return when {
            NOTICE_STARTS.any { t.startsWith(it) } -> Severity.NOTICE
            WARNING_STARTS.any { t.startsWith(it) } -> Severity.WARNING
            else -> Severity.ERROR
        }
    }

    /** A diary line's severity by its [area] ("error" sorted by [classify]; "warning", "notice"), or null: not counted. */
    fun severityOf(area: String, text: String): Severity? = when (area.lowercase(Locale.ENGLISH)) {
        "error" -> classify(text)
        "warning" -> Severity.WARNING
        "notice" -> Severity.NOTICE
        else -> null
    }

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

    /** One day's counts: [errors] by kind, [unreadable] failures by "step: class", and the [warnings] and [notices] apart. */
    data class Day(val day: LocalDate, val errors: Map<String, Int> = emptyMap(), val unreadable: Map<String, Int> = emptyMap(),
                   val warnings: Map<String, Int> = emptyMap(), val notices: Map<String, Int> = emptyMap()) {
        val empty: Boolean get() = errors.isEmpty() && unreadable.isEmpty() && warnings.isEmpty() && notices.isEmpty()
    }

    /**
     * Two counts of one day put together, kind by kind the larger (20 Oct): the diary's own tally starts again at each process
     * start (memory only), and the diary file may have lost the day's first lines to its cap; neither alone is the whole day.
     */
    fun merge(a: Day?, b: Day?): Day? {
        if (a == null) return b
        if (b == null || b.day != a.day) return a
        fun m(x: Map<String, Int>, y: Map<String, Int>) = (x.keys + y.keys).associateWith { maxOf(x[it] ?: 0, y[it] ?: 0) }
        return Day(a.day, m(a.errors, b.errors), m(a.unreadable, b.unreadable), m(a.warnings, b.warnings), m(a.notices, b.notices))
    }

    private fun bump(m: Map<String, Int>, kind: String): Map<String, Int> {
        val k = if (kind in m || m.size < KINDS) kind else OTHER
        return m + (k to (m[k] ?: 0) + 1)
    }

    /** The diary's counts, today's only (a new day starts afresh). */
    class Tally {
        private var d: Day? = null

        @Synchronized fun add(day: LocalDate, area: String, text: String) {
            val cur = d?.takeIf { it.day == day } ?: Day(day)
            var next = cur
            when (severityOf(area, text)) {
                Severity.ERROR -> next = next.copy(errors = bump(next.errors, kindOf(text)))
                Severity.WARNING -> next = next.copy(warnings = bump(next.warnings, kindOf(text)))
                Severity.NOTICE -> next = next.copy(notices = bump(next.notices, kindOf(text)))
                null -> {}
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
