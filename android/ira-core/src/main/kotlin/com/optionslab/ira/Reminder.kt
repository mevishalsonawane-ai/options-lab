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

    /** "Mujhe 3 baje Nifty dekhna yaad dilana" -> "remind me at 3 Nifty dekhna" (Hindi puts it last). */
    private val HINDI = Regex("(?i)\\s*\\b(?:yaad\\s+(?:dila(?:na|o|do|dena|diyo|dijiye)|karana|karwana))\\s*$")

    private fun english(text: String): String {
        // Only with a time in it ("kal kya hua tha yaad dilao" is a question about yesterday, not a reminder).
        if (!HINDI.containsMatchIn(text) || !Regex("(?i)\\b(baje|minute|minutes|minit|ghante|ghanta)\\b").containsMatchIn(text)) return text
        val t = HINDI.replace(text, "").replace(Regex("(?i)^\\s*(?:jarvis,?\\s+)?(?:mujhe|muje)\\s+"), "")
            // "Shaam 9 baje" is 9 pm, "subah 9 baje" 9 am (review, 4 Oct: an evening reminder went off next morning).
            .replace(Regex("(?i)\\b(?:(shaam|sham|raat|dopahar|subah|savere)\\s+)?(\\d{1,2})(?:[:.](\\d{2}))?\\s+baje\\b")) { m ->
                val part = m.groupValues[1].lowercase()
                val ap = when { part.isEmpty() -> ""; part == "subah" || part == "savere" -> " am"; part == "dopahar" && m.groupValues[2].toInt() == 12 -> " pm"; else -> " pm" }
                "at " + m.groupValues[2] + (m.groupValues[3].takeIf { it.isNotEmpty() }?.let { ":$it" } ?: "") + ap }
            .replace(Regex("(?i)\\b(\\d{1,3})\\s+(?:minute|minutes|minit)\\s+(?:mein|me|baad)\\b"), "in $1 minutes")
            .replace(Regex("(?i)\\bkal\\b"), "tomorrow").replace(Regex("(?i)\\s+(?:ko|ka|ki|ke)\\s*$"), "")
        return "remind me " + t.trim()
    }

    // Only right before the time ("every day at 9:20", "roz 9 baje"): "check the daily pnl" is one reminder (review, 4 Oct).
    private val DAILY = Regex("(?i)\\s*\\b(every ?day|daily|each day|every trading day|roz|rozana|har din)\\b\\s*(?=(at\\b|@|\\d|subah|savere|shaam|sham|raat|dopahar))")

    /** "Remind me every day at 9:20 to ...", "roz 9 baje ... yaad dilana": said each trading day at that time. */
    fun daily(said: String): Boolean = DAILY.containsMatchIn(said)

    /** A reminder asked for: what to say and when, or null (not a reminder, or no time still ahead). */
    fun parse(said: String, now: LocalDateTime): Later.When? {
        val text = english(DAILY.replace(said, " ").trim())
        if (!ASK.containsMatchIn(text)) return null
        val w = Later.split(ASK.replace(text, ""), now) ?: return null
        val what = w.rest.replace(Regex("(?i)^\\s*(to|that|about|of)\\b"), "").replace(Regex("\\s+"), " ").trim().trimEnd('.', '?', '!').take(160)
        return Later.When(what.ifEmpty { "you asked me to remind you now" }, w.at)
    }

    /** Asked for a reminder with no usable time: what to say. */
    fun asked(text: String): Boolean = ASK.containsMatchIn(english(DAILY.replace(text, " ").trim()))

    /** "Cancel my reminders", "delete the reminder", "reminder hata do": reminders only (timed commands stay). */
    fun cancelAsked(text: String): Boolean =
        Regex("(?i)^\\s*(jarvis,?\\s+)?(please\\s+)?((cancel|clear|delete|remove|drop)( all)?( my| the)? reminders?|reminders? (hata|cancel) (do|karo|kar do))\\s*$").containsMatchIn(text)

    fun said(what: String) = "Boss, your reminder: $what."

    private val TIME = Regex("(?i)^ (what s|whats|what is) the time( now)? $|^ (what time is it|time please|current time|time now|tell me the time|what is time)( now)? $|^ (time kya (hua|hai)|kya time (hua|hai)|kitne baje (hain|hai)|kitna baja hai|samay kya hai) $")
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

    /** "What did I miss?", "kya hua jab main nahi tha", "any updates for me": what Jarvis said on his own since. */
    fun missedAsked(text: String): Boolean =
        Regex("(?i)^\\W*(jarvis,?\\s+)?(what did i miss|what have i missed|did i miss anything|anything i missed|catch me up|kya hua jab (main|mai) nahi tha|maine kya miss kiya|kuch miss hua)\\W*$").containsMatchIn(text)

    /** A chat message for "what did I miss": Boss's words, or Jarvis's - said on his own ([unasked]) or as a reply. */
    data class Said(val fromBoss: Boolean, val unasked: Boolean, val text: String)

    /** What Jarvis said on his own since Boss last asked (replies, however late, left out), newest last, at most [max]. */
    fun sinceLastAsked(msgs: List<Said>, max: Int = 6): List<String> {
        val lastBoss = msgs.indexOfLast { it.fromBoss }
        return msgs.drop(lastBoss + 1).filter { !it.fromBoss && it.unasked }.map { it.text }.takeLast(max)
    }

    /** "What did you hear?", "what did I say", "kya suna": the recognizer's last words, for checking the ears. */
    fun heardAsked(text: String): Boolean =
        Regex("(?i)^\\W*(jarvis,?\\s+)?(what did you (just )?hear|what did i (just )?say|what was that you heard|repeat what i said|tumne kya suna|kya suna)\\W*$").containsMatchIn(text)

    /** "How did you do today?", "your report card", "how many questions did I ask": Jarvis's own day in numbers. */
    fun usageAsked(text: String): Boolean =
        Regex("(?i)^\\W*(jarvis,?\\s+)?(how did you do( today)?|how have you done( today)?|your (report card|score|stats)( today)?|how many (questions|things) did i ask( you)?( today)?|tumne aaj kaisa kiya)\\W*$").containsMatchIn(text)

    /** "Which AI model are you using?", "kaunsa model hai": answered from the app's own choice. */
    fun modelAsked(text: String): Boolean =
        Regex("(?i)\\b(which|what) (ai |language )?model (are you (using|on|running)|do you use|is (this|it|running|loaded))\\b|\\b(kaunsa|konsa|kon sa|kaun sa) model\\b|^\\W*(jarvis,?\\s+)?(what is |what s |what's |whats )?your (ai )?model\\W*$").containsMatchIn(text)

    private val TOMORROW = Regex("(?i)\\b(plan|outlook|setup|set up|ready|prepare|expect|look(s|ing)? like)\\b.*\\b(tomorrow|tmrw|kal)\\b|\\b(tomorrow|tmrw)( s|'s)? (plan|outlook|setup)\\b|^\\s*(what about|how about) tomorrow\\s*\\??$|\\b(how|what) (does|will) tomorrow look\\b|\\b(tomorrow|kal)\\b.{0,12}\\bplan\\b")

    /** "What's the plan for tomorrow?" */
    fun tomorrow(text: String): Boolean = TOMORROW.containsMatchIn(text)
}
