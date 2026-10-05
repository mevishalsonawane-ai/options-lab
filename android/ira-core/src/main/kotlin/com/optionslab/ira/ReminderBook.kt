package com.optionslab.ira

import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale

/**
 * Boss's reminders, one at a time (usefulness round 27): "what reminders do I have" lists them, each with its time;
 * "cancel the 14:30 reminder" / "delete the reminder about Nifty" finds the one meant and asks first - only that one
 * is dropped, on Confirm. "Cancel my reminders" names them all and asks first too (usefulness round 28): only the ones
 * named are dropped, on Confirm - one set while it waits stays. A reminder only ever speaks, so nothing here trades or
 * stops anything. Pure: unit-tested on the JVM.
 */
object ReminderBook {
    /** A reminder as kept on the phone. */
    data class Kept(val id: Long, val text: String, val at: LocalDateTime, val daily: Boolean = false)

    /** Which reminder Boss named: by its time, by its words, or both. */
    data class Pick(val time: LocalTime?, val about: String?)

    private fun norm(text: String) = text.lowercase(Locale.ENGLISH).replace("’", "'").replace(rx("[?!,]"), " ")
        .replace(rx("^\\s*(hey\\s+)?jarvis\\s+"), " ").replace(rx("\\s+"), " ").trim()

    private val LIST = Regex("^(please )?(what|which) reminders? (do i have|have i (got|set)|are (set|there|pending|on)|did i set|have you (got|set))( for (today|tomorrow|me))?( set)?( please)?$|" +
        "^(please )?(list|show|read|tell me)( me)? (all )?(my|the) reminders( please)?$|^(my reminders|any reminders( set| for me| today)?|do i have (any )?reminders( set| today)?|are there any reminders( set)?)$|" +
        "^(mere |my )?reminders? (kya|kaun se|kaunse|konse) (hai|hain)$")

    /** "What reminders do I have?", "list my reminders", "any reminders?", "mere reminders kya hain". */
    fun listAsked(text: String): Boolean = LIST.containsMatchIn(norm(text).trimEnd('.'))

    private val VERB = Regex("^(please )?(cancel|delete|remove|drop|clear|scrap)\\b")
    private val HINDI_END = Regex("\\b(hata|hatao|cancel|band)( (do|karo|kar do|dijiye))?$")
    private val TIME = Regex("\\b(\\d{1,2})[:.](\\d{2})\\s*(am|pm|a\\.m\\.|p\\.m\\.)?(?![\\d])|\\b(\\d{1,2})\\s*(am|pm|a\\.m\\.|p\\.m\\.|o ?clock|baje)(?![a-z])|\\b(?:at|for) (\\d{1,2})\\b(?![:.]\\d|\\s*(am|pm|a\\.m|p\\.m|o ?clock|baje))")
    private val FILLER = Regex("\\b(please|my|the|that|this|one|reminder|set|for|at|about|to|of|on|wala|waala|vala|ka|ki|ke|today|tomorrow)\\b")

    /**
     * "Cancel the 14:30 reminder", "delete the reminder about Nifty", "3 baje wala reminder hata do": one reminder named,
     * or null (not that, or "cancel my reminders" - all of them - which is [Reminder.cancelAsked]'s).
     */
    fun cancelOne(text: String): Pick? {
        val t = norm(text).trimEnd('.')
        if (!rx("\\breminder\\b").containsMatchIn(t) || rx("\\breminders\\b").containsMatchIn(t)) return null
        var rest = when {
            VERB.containsMatchIn(t) -> VERB.replace(t, " ")
            rx("\\breminder\\b").containsMatchIn(t) && HINDI_END.containsMatchIn(t) -> HINDI_END.replace(t, " ")
            else -> return null
        }
        if (rx("\\b(all|every|everything|sab)\\b").containsMatchIn(rest)) return null
        var time: LocalTime? = null
        TIME.find(rest)?.let { m ->
            val g = m.groupValues
            val h = (g[1].ifEmpty { g[4].ifEmpty { g[6] } }).toInt()
            val mm = g[2].ifEmpty { "0" }.toInt()
            val ap = (g[3].ifEmpty { g[5] }).replace(".", "")
            if (h !in 0..23 || mm !in 0..59) return null
            if ((ap == "am" || ap == "pm") && h !in 1..12) return null
            val hour = when {
                ap == "pm" && h in 1..11 -> h + 12
                ap == "am" && h == 12 -> 0
                ap == "am" || ap == "pm" -> h
                // As a reminder is set: "at 2" is 2 pm, "at 9" 9 am ([Later.split]).
                h in 1..7 -> h + 12
                else -> h
            }
            time = LocalTime.of(hour, mm)
            rest = rest.removeRange(m.range)
        }
        val about = rest.replace(FILLER, " ").replace(rx("[^a-z0-9 ]"), " ").replace(rx("\\s+"), " ").trim().takeIf { it.length >= 3 }
        if (time == null && about == null) return null
        return Pick(time, about)
    }

    /** The reminders [pick] names: by time (hour and minute, any day), then by every word said being in its text. */
    fun matches(kept: List<Kept>, pick: Pick): List<Kept> = kept.filter { k ->
        (pick.time == null || k.at.toLocalTime().withSecond(0).withNano(0) == pick.time) &&
            (pick.about == null || pick.about.split(' ').filter { it.length >= 2 }.all { w -> rx("\\b" + Regex.escape(w)).containsMatchIn(k.text.lowercase(Locale.ENGLISH)) })
    }.sortedBy { it.at }

    private fun one(k: Kept, now: LocalDateTime) =
        (if (k.daily) "every trading day at " + "%02d:%02d".format(Locale.ENGLISH, k.at.hour, k.at.minute) else Later.say(k.at, now)) + ": " + k.text

    /** "What reminders do I have?" */
    fun list(kept: List<Kept>, now: LocalDateTime): String {
        val s = kept.sortedBy { it.at }
        return when (s.size) {
            0 -> "You have no reminders set, Boss."
            1 -> "One reminder, Boss: " + one(s[0], now) + "."
            else -> "${s.size} reminders, Boss: " + s.mapIndexed { i, k -> "${i + 1}) " + one(k, now) }.joinToString("; ") + "."
        }
    }

    /** What the Confirm asks for: dropping this one reminder. */
    fun confirm(k: Kept, now: LocalDateTime): String = "cancel your reminder " + one(k, now)

    /** Said once it is dropped. */
    fun cancelled(k: Kept, now: LocalDateTime): String = "Done, Boss: your reminder " + one(k, now) + " is cancelled."

    /** What the Confirm asks for when Boss cancels all of them ("cancel my reminders"): each named, so he knows what goes. */
    fun confirmAll(kept: List<Kept>, now: LocalDateTime): String {
        val s = kept.sortedBy { it.at }
        return if (s.size == 1) confirm(s[0], now)
        else "cancel all ${s.size} of your reminders - " + s.joinToString("; ") { one(it, now) }
    }

    /** Said once the reminders named are dropped ([n]: how many were still there to drop). */
    fun cancelledAll(n: Int): String = when (n) {
        0 -> "Those reminders are gone already, Boss - nothing to cancel."
        1 -> "Done, Boss: 1 reminder cancelled."
        else -> "Done, Boss: $n reminders cancelled."
    }

    /** No single reminder found: none, or several (named, nothing dropped). */
    fun notOne(found: List<Kept>, all: List<Kept>, now: LocalDateTime): String = when {
        all.isEmpty() -> "You have no reminders set, Boss."
        found.isEmpty() -> "Boss, I found no reminder like that - nothing was cancelled. " + list(all, now)
        else -> "Boss, ${found.size} reminders match - nothing was cancelled. Say its time or its words: " +
            found.joinToString("; ") { one(it, now) } + "."
    }
}
