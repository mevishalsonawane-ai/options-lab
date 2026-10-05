package com.optionslab.ira

import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A command for later ("start all the arms tomorrow at 9am", "stop strategy 2 at 3:15 pm", "in 30 minutes stop all"):
 * the time is read off and the rest is the command. Only starting and stopping strategies and arms, the kill switch on
 * and paper mode may wait for a time - never an order, live mode, the kill switch off or anything else (Boss's rule:
 * nothing riskier can be set to happen while he is not there). Pure: unit-tested on the JVM.
 */
object Later {
    /** What may be set for a time. */
    val ALLOWED = setOf(Command.Kind.START_ALL, Command.Kind.START_ONE, Command.Kind.STOP_ALL, Command.Kind.STOP_ONE,
        Command.Kind.KILL_ON, Command.Kind.MODE_PAPER)

    /** At most this far ahead. */
    const val MAX_DAYS = 7L

    data class When(val rest: String, val at: LocalDateTime)

    private val IN = Regex("(?i)\\bin\\s+(\\d{1,3})\\s*(minutes?|mins?|hours?|hrs?)\\b")
    private val DAY = Regex("(?i)\\b(tomorrow|tmrw|tomorow|kal|today|aaj)\\b(\\s+(morning|afternoon|evening|subah|shaam))?")
    // A time needs "at", am / pm, or minutes: "at 9", "9am", "9:15", "9.30 am" (a bare number is a strategy's or a level).
    private val TIME = Regex("(?i)(?:\\b(?:at|by|@)\\s*)?\\b(\\d{1,2})(?:[:.](\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?)(?![a-z])|\\b(?:at|by|@)\\s*(\\d{1,2})(?:[:.](\\d{2}))?\\b|\\b(\\d{1,2})[:.](\\d{2})\\b")
    // ("on" is not dropped: "kill switch on at 3" must keep its "on".)
    private val FILLER = Regex("(?i)\\b(by|from|at|ko|baje)\\b")

    /**
     * Does [text] name a time at all ("tomorrow", "in 30 minutes", "at 9:15", "3 pm") - even one [split] cannot use
     * (passed, too far, no hour)? Then a command in it is never done now.
     */
    fun mentionsTime(text: String): Boolean {
        // ("Stop all strategies today" is for now; "tomorrow" never is.)
        if (IN.containsMatchIn(text) || rx("(?i)\\b(tomorrow|tmrw|tomorow|kal)\\b").containsMatchIn(text)) return true
        return TIME.findAll(text).any { m ->
            val g = m.groupValues
            val h = (g[1].ifEmpty { g[4].ifEmpty { g[6] } }).toIntOrNull() ?: return@any false
            val mm = (g[2].ifEmpty { g[5].ifEmpty { g[7] } }).ifEmpty { "0" }.toIntOrNull() ?: return@any false
            h in 0..23 && mm in 0..59
        }
    }

    /** The time in [text] (and the text without it), or null when it names none, is past or too far ahead. */
    fun split(text: String, now: LocalDateTime): When? {
        var t = text
        IN.find(t)?.let { m ->
            val n = m.groupValues[1].toLong()
            val at = if (m.groupValues[2].lowercase().startsWith("h")) now.plusHours(n) else now.plusMinutes(n)
            if (n <= 0 || at.isAfter(now.plusDays(MAX_DAYS))) return null
            return When(clean(t.removeRange(m.range)), at.withSecond(0).withNano(0))
        }
        val day = DAY.find(t)
        val dayWord = day?.groupValues?.get(1)?.lowercase()
        val part = day?.groupValues?.get(3)?.lowercase()
        if (day != null) t = t.removeRange(day.range)
        val m = TIME.find(t)
        if (m == null && day == null) return null
        var time: LocalTime? = null
        if (m != null) {
            val g = m.groupValues
            val (h, mm, ap) = when {
                g[1].isNotEmpty() -> Triple(g[1].toInt(), g[2].ifEmpty { "0" }.toInt(), g[3].lowercase().replace(".", ""))
                g[4].isNotEmpty() -> Triple(g[4].toInt(), g[5].ifEmpty { "0" }.toInt(), "")
                else -> Triple(g[6].toInt(), g[7].toInt(), "")
            }
            if (h !in 0..23 || mm !in 0..59) return null
            val hour = when {
                ap == "pm" && h in 1..11 -> h + 12
                ap == "am" && h == 12 -> 0
                ap.isNotEmpty() -> h
                // No am / pm: market hours read naturally ("at 2" is 2 pm; "at 9" is 9 am); an evening or afternoon is pm.
                (part == "afternoon" || part == "evening" || part == "shaam") && h in 1..11 -> h + 12
                h in 1..7 -> h + 12
                else -> h
            }
            if (ap.isNotEmpty() && h !in 1..12) return null
            time = LocalTime.of(hour, mm)
            t = t.removeRange(m.range)
        } else if (part == "morning" || part == "subah") time = LocalTime.of(9, 15)
        if (time == null) return null       // "tomorrow" alone names no time
        var date = now.toLocalDate()
        if (dayWord == "tomorrow" || dayWord == "tmrw" || dayWord == "tomorow" || dayWord == "kal") date = date.plusDays(1)
        var at = date.atTime(time)
        // No day named and the time has passed today: the next one.
        if (day == null && !at.isAfter(now)) at = at.plusDays(1)
        if (!at.isAfter(now) || at.isAfter(now.plusDays(MAX_DAYS))) return null
        return When(clean(t), at)
    }

    private fun clean(s: String) = s.replace(FILLER, " ").replace(rx("\\s+"), " ").trim().trimEnd(',', '.', '?', '!').trim()

    /** "Sat 4 Oct at 09:00" (or "today at 15:15"). */
    fun say(at: LocalDateTime, now: LocalDateTime): String {
        val hm = at.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH))
        return when (at.toLocalDate()) {
            now.toLocalDate() -> "today at $hm"
            now.toLocalDate().plusDays(1) -> "tomorrow (" + at.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)) + ") at $hm"
            else -> at.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)) + " at $hm"
        }
    }
}
