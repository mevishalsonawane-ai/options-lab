package com.optionslab.ira

import java.time.LocalDate
import java.util.Locale

/**
 * The day each arm status line was written (21 Oct, Boss's 11:09 diagnostics: the arms' rows showed the previous session's
 * notes, "13:45 put break missed", as if they were today's). Each arm keeps, beside its status map, the day each line was last
 * written; a line from an earlier day is shown as "from 20 Oct: ...". Nothing is cleared or acted on: words only. Pure.
 */
object ArmStatus {
    /** "from 20 Oct: " + [text]. */
    fun earlier(text: String, day: LocalDate): String =
        "from ${day.dayOfMonth} ${day.month.getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH)}: $text"

    /**
     * [status] as shown on [today]: a line written on an earlier day ([days]) is marked [earlier]; [sticky] lines (a switch-off's
     * reason, a line that carries its own day) and lines of no known day are shown as they are.
     */
    fun shown(status: Map<String, String>, days: Map<String, LocalDate>, today: LocalDate,
              sticky: (String, String) -> Boolean = { _, _ -> false }): Map<String, String> =
        status.mapValues { (k, text) ->
            val d = days[k]
            if (d != null && d.isBefore(today) && !sticky(k, text)) earlier(text, d) else text
        }

    /**
     * 10 Nov (Boss's diagnostics: R51 showed "exit from 30 Oct: exit_retry BANKNIFTY23NOV2654900PE" every day for a week after
     * that trade had closed): the contract an arm's "<arm>|exit" line ([line]) reports a failed exit of - a sale that did not fill
     * ("exit_retry SYMBOL"), or an exit check or closed-elsewhere check that failed on it - or null for any other line. Such a
     * line is cleared once that contract's trade closes, or when the arm holds it no longer.
     */
    fun exitRetrySymbol(line: String?): String? {
        if (line == null) return null
        val sym = when {
            line.startsWith("exit_retry ") -> line.removePrefix("exit_retry ").takeIf { ' ' !in it }
            else -> EXIT_FAILED.firstOrNull { line.startsWith(it) }?.let { line.removePrefix(it).substringBefore(':', "").takeIf { s -> ' ' !in s } }
        }
        return sym?.takeIf { it.isNotEmpty() }
    }
    private val EXIT_FAILED = listOf("closed-elsewhere check failed on ", "exit check failed on ")

    /**
     * The "<arm>|exit" keys of [status] whose failed-exit line ([exitRetrySymbol]) names a contract that arm no longer holds open
     * ([open]: arm to symbol pairs): cleared at the next look, so a line left from before this rule (or by a close that missed
     * [exitRetryOf]) ends too. Lines of any other form, and keys of any other kind, are kept.
     */
    fun staleExitKeys(status: Map<String, String>, open: Set<Pair<String, String>>): List<String> = status.mapNotNull { (k, v) ->
        if (!k.endsWith("|exit")) return@mapNotNull null
        val sym = exitRetrySymbol(v) ?: return@mapNotNull null
        k.takeIf { (k.removeSuffix("|exit") to sym) !in open }
    }

    /** Whether [line] reports a failed exit of [symbol] itself ([exitRetrySymbol]); a line about another contract is kept. */
    fun exitRetryOf(line: String?, symbol: String): Boolean = symbol.isNotEmpty() && exitRetrySymbol(line) == symbol

    /** A kept day read back ("2026-10-20", or an older "2026-10-20|..." form); null when broken. */
    fun decodeDay(s: String?): LocalDate? = s?.substringBefore('|')?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    /** The day a line kept without one is given: the day its book was last saved, never later than [today]. */
    fun unknownDay(savedOn: LocalDate?, today: LocalDate): LocalDate = savedOn?.takeIf { !it.isAfter(today) } ?: today
}
