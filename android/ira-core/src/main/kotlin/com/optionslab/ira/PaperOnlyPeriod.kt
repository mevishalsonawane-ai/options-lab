package com.optionslab.ira

import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * Boss's paper-only period (12 Oct: "For 10 days I will be on paper only"): an end date and a reason Boss sets in Settings.
 * Until the end date, switching the app to Live asks his PIN AND shows the period's reason and end date first; it never blocks
 * an exit or a square-off (nothing on the order path reads it) and it only ever lowers risk:
 *
 *  - setting a period, or moving its end LATER, lowers risk: no PIN;
 *  - ending it early, or moving its end EARLIER, raises risk: the PIN ([needsPin]);
 *  - it is never set or changed by voice, a backup restore or an automatic change (its key is one of the private, never-carried
 *    settings; only the Settings card calls the setter).
 *
 * Pure: the day is given.
 */
object PaperOnlyPeriod {
    /** The longest period one setting may hold. */
    const val MAX_DAYS = 90L
    const val MAX_REASON = 120

    data class Period(val until: LocalDate, val reason: String, val setOn: LocalDate)

    /** Whether [p] is in force on [today] (through its end date). */
    fun active(p: Period?, today: LocalDate): Boolean = p != null && !today.isAfter(p.until)

    /** Why [until] cannot be the end date on [today], or null when it can. */
    fun invalid(until: LocalDate, today: LocalDate): String? = when {
        until.isBefore(today) -> "The end date has already passed."
        until.isAfter(today.plusDays(MAX_DAYS)) -> "At most $MAX_DAYS days at a time."
        else -> null
    }

    /** Does going from [old] to [new] need Boss's PIN on [today]? Only what raises risk: ending it, or an earlier end. */
    fun needsPin(old: Period?, new: Period?, today: LocalDate): Boolean {
        if (!active(old, today)) return false
        if (new == null) return true
        return new.until.isBefore(old!!.until)
    }

    fun day(d: LocalDate): String = "${d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} ${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}"

    /** The words before the PIN when Boss switches to Live inside the period. */
    fun liveAsk(p: Period): String =
        "You set a paper-only period until ${day(p.until)}" + (if (p.reason.isNotBlank()) ": \"${p.reason}\"" else "") +
            ". Enter your PIN to switch to Live anyway. Exits and square-offs are never blocked."

    /** The Settings card's status line. */
    fun status(p: Period?, today: LocalDate): String =
        if (!active(p, today)) "No paper-only period set." else
            "Paper only until ${day(p!!.until)} (${p.until.toEpochDay() - today.toEpochDay() + 1} days left)" + (if (p.reason.isNotBlank()) ": ${p.reason}" else "")

    fun encode(p: Period?): String? = p?.let { "${it.until}|${it.setOn}|${it.reason.replace('|', '/').replace('\n', ' ').take(MAX_REASON)}" }

    fun decode(s: String?): Period? = runCatching {
        val f = s!!.split('|', limit = 3)
        Period(LocalDate.parse(f[0]), f.getOrElse(2) { "" }, LocalDate.parse(f[1]))
    }.getOrNull()
}
