package com.optionslab.ira

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Boss's own reminders ("remind me at 3 pm to check Nifty"): said at that time, nothing done - a reminder only ever
 * speaks (Boss, 4 Oct: it was read as a Nifty question and no reminder was set). Also the time and date, at once. Pure.
 */
object Reminder {
    private val ASK = Regex("(?i)^\\s*(please |jarvis,? |hey jarvis,? )?(remind me|set (a|an) (reminder|alarm)|reminder)\\b")

    /** A reminder asked for: what to say and when, or null (not a reminder, or no time still ahead). */
    fun parse(text: String, now: LocalDateTime): Later.When? {
        if (!ASK.containsMatchIn(text)) return null
        val w = Later.split(ASK.replace(text, ""), now) ?: return null
        val what = w.rest.replace(Regex("(?i)^\\s*(to|that|about|of)\\b"), "").replace(Regex("\\s+"), " ").trim().trimEnd('.', '?', '!').take(160)
        return Later.When(what.ifEmpty { "you asked me to remind you now" }, w.at)
    }

    /** Asked for a reminder with no usable time: what to say. */
    fun asked(text: String): Boolean = ASK.containsMatchIn(text)

    fun said(what: String) = "Boss, your reminder: $what."

    private val TIME = Regex("(?i)^ (what s|whats|what is) the time( now)? $|^ (what time is it|time please|current time|time now|tell me the time)( now)? $")
    private val DATE = Regex("(?i)^ (what s|whats|what is) (the |today s )?date( today)? $|^ (what day is (it|today)|which day is (it|today)|today s date|date today) $")

    /** "What time is it?" / "What's the date?" answered from the phone's clock (India time), or null. */
    fun clock(text: String, now: LocalDateTime): String? {
        val t = " " + text.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\b(jarvis|please|boss|hey)\\b"), " ").replace(Regex("\\s+"), " ").trim() + " "
        return when {
            TIME.containsMatchIn(t) -> "It's " + now.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)) + ", Boss."
            DATE.containsMatchIn(t) -> "Today is " + now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH)) + ", Boss."
            else -> null
        }
    }

    private val TOMORROW = Regex("(?i)\\b(plan|outlook|setup|set up|ready|prepare|expect|look(s|ing)? like)\\b.*\\b(tomorrow|tmrw|kal)\\b|\\b(tomorrow|tmrw)( s|'s)? (plan|outlook|setup)\\b|^\\s*(what about|how about) tomorrow\\s*\\??$|\\b(how|what) (does|will) tomorrow look\\b")

    /** "What's the plan for tomorrow?" */
    fun tomorrow(text: String): Boolean = TOMORROW.containsMatchIn(text)
}
