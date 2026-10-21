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

    /** A kept day read back ("2026-10-20", or an older "2026-10-20|..." form); null when broken. */
    fun decodeDay(s: String?): LocalDate? = s?.substringBefore('|')?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    /** The day a line kept without one is given: the day its book was last saved, never later than [today]. */
    fun unknownDay(savedOn: LocalDate?, today: LocalDate): LocalDate = savedOn?.takeIf { !it.isAfter(today) } ?: today
}
