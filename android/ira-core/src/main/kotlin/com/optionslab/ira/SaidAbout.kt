package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * "What did I say about X?" (usefulness, round 14, 2026-10-05): "what did I say about BankNifty?", "remind me what I
 * said about expiry", "did I note anything about the hammer last week?", "find my notes on Fridays", "maine expiry ke
 * baare mein kya kaha tha" - a search over Boss's own words that Jarvis already keeps: the notes he asked him to remember
 * ("remember that ..."; what he told about himself is kept with them, [Memory]), the notes he gave with his trades
 * ("Jarvis, note: I bought because of the hammer") and his answers to the journal's questions ([DayJournal.Answer]).
 *
 * Each place that holds every word asked about (one may be missing from three or more) is read back as he said it, the
 * best match first and then the newest, with where and when it was said - at most [MAX_SAID], and how many more there
 * are. Index names are matched however they are said ("bank nifty", "BNF", "BankNifty"), and plurals and "-ing" forms
 * alike ("Fridays" finds "Friday"). An optional period ("today", "yesterday", "this week", "last week", "this month")
 * narrows it. His own words, read back only: never taken as a rule, an order or a setting, and nothing acts. They are
 * his, so the app answers only on an unlocked phone. Pure.
 */
object SaidAbout {
    /** Where Boss's words were kept. */
    enum class Source { NOTE, TRADE_NOTE, JOURNAL }

    /** The period asked about, if any. */
    enum class Period(val label: String) { TODAY("today"), YESTERDAY("yesterday"), WEEK("this week"), LAST_WEEK("last week"), MONTH("this month") }

    /** What was asked: the words [topic] as Boss said them, and the [period] if he named one. */
    data class Asked(val topic: String, val period: Period? = null)

    /** One thing Boss said: [at] when the time is known (a trade note, a journal answer), [question] the journal's question. */
    data class Said(val source: Source, val day: LocalDate, val text: String, val at: LocalDateTime? = null, val question: String? = null)

    /** At most this many read back. */
    const val MAX_SAID = 6
    /** A topic longer than this many words is not a topic (a whole sentence). */
    const val MAX_TOPIC_WORDS = 6

    private fun norm(text: String) = " " + text.lowercase(Locale.ENGLISH).replace("'", "").replace("’", "")
        .replace(rx("[^a-z0-9 ]"), " ").replace(rx("\\s+"), " ").trim()
        .replace(rx("^(jarvis|hey jarvis|ok jarvis|okay jarvis|boss|please)( |$)"), "").trim() + " "

    private const val SAID = "(say|said|tell you|told you|note|noted|write|wrote|written|mention|mentioned|jot|jotted|journal|journaled|journalled)"
    private const val ABOUT = "(about|on|regarding|re)"
    private const val PERIOD = "(?: (today|yesterday|this week|last week|this month))?"
    private const val TAIL = "(?: (before|earlier|again|ever|please))?"
    /** Where he said it, said before the topic ("what did I say in my journal about revenge trading", routing round 11). */
    private const val WHERE = "(to you |in (my |the )?(journal|notes|trade notes|journal answers|notes with trades) )?"

    private val ENGLISH = listOf(
        // "what did I say about X", "what have I said about X", "what all did I tell you about X"
        " (what|wat|wht)( all)? (did|have|had) i (ever )?$SAID $WHERE$ABOUT (.+?)$PERIOD$TAIL $",
        // "remind me what I said about X", "tell me what I noted about X", "show me what I wrote on X"
        " (remind me|tell me|show me|read me|read out) what i (have )?$SAID $WHERE$ABOUT (.+?)$PERIOD$TAIL $",
        // "did I say anything about X", "have I noted something about X"
        " (did|have) i (ever )?$SAID (anything|something) $WHERE$ABOUT (.+?)$PERIOD$TAIL $",
        // "find / search my notes for X", "look up my notes on X", "search what I said about X"
        " (find|search|look up|look through|check|go through|pull up)( in)? (my|our) (notes|words|journal|journal answers|own words) (for|about|on|regarding|mentioning) (.+?)$PERIOD$TAIL $",
        // "my notes on X", "what are my notes about X", "any notes about X"
        " (what are |show |show me |read |any |are there any )?(my )?notes (i made |i gave you )?$ABOUT (.+?)$PERIOD$TAIL $",
        // "what did I tell you regarding X" with "anything" etc. already above; "whatever I said about X"
        " (everything|anything|whatever) i (have )?$SAID $ABOUT (.+?)$PERIOD$TAIL $",
    ).map { Regex("^$it") }

    private const val HSAID = "(kaha|bola|likha|bataya|note kiya|noted kiya)"
    private val HINGLISH = listOf(
        // "maine X ke baare mein kya kaha tha", "maine X par kya bola tha"
        " (maine|mene|mainey|maine kabhi) (.+?) (ke baare mein|ke bare me|ke bare mein|ke baare me|ke baare main|par|pe|ke liye) (kya|kya kya) $HSAID( tha| thaa| hai)? $",
        // "X ke baare mein maine kya kaha tha"
        " (.+?) (ke baare mein|ke bare me|ke bare mein|ke baare me|ke baare main|par|pe) (maine|mene) (kya|kya kya) $HSAID( tha| thaa| hai)? $",
    ).map { Regex("^$it") }

    private val PRONOUN = Regex("^(it|that|this|them|those|these|me|you|him|her|us|everything|anything|something|all|kuch|ye|yeh|woh|wo|vo)$")

    /** Does [text] ask what Boss said about something? The topic as he said it (and the period), or null. */
    fun asked(text: String): Asked? {
        val t = norm(text)
        for (r in ENGLISH) {
            val m = r.find(t) ?: continue
            // The topic is the group before the period (the last two groups are the period and the tail).
            val g = m.groupValues
            val topic = g[g.size - 3].trim()
            return make(topic, g[g.size - 2])
        }
        for ((i, r) in HINGLISH.withIndex()) {
            val m = r.find(t) ?: continue
            val topic = (if (i == 0) m.groupValues[2] else m.groupValues[1]).trim()
            return make(topic, "")
        }
        return null
    }

    private fun make(topic0: String, period: String): Asked? {
        val topic = topic0.replace(rx("^(the|a|an|my|our) "), "").trim()
        if (topic.isEmpty() || PRONOUN.matches(topic) || topic.split(" ").size > MAX_TOPIC_WORDS) return null
        if (terms(topic).isEmpty()) return null
        val p = when (period) {
            "today" -> Period.TODAY; "yesterday" -> Period.YESTERDAY; "this week" -> Period.WEEK
            "last week" -> Period.LAST_WEEK; "this month" -> Period.MONTH; else -> null
        }
        return Asked(topic, p)
    }

    private val STOP = setOf("the", "a", "an", "my", "our", "of", "to", "in", "on", "for", "and", "or", "about", "with", "at", "is", "are",
        "was", "i", "me", "it", "that", "this", "ke", "ki", "ka", "mein", "me", "se", "aur", "wala", "wali", "vala", "vali", "do", "did", "be")

    /** One word as matched: the index names as one word, plurals and "-ing"/"-ed" forms alike. */
    private fun stem(w: String): String = when {
        w.length > 5 && w.endsWith("ing") -> w.dropLast(3)
        w.length > 4 && w.endsWith("ies") -> w.dropLast(3) + "y"
        w.length > 4 && w.endsWith("sses") -> w.dropLast(2)
        w.length > 4 && (w.endsWith("xes") || w.endsWith("ches") || w.endsWith("shes")) -> w.dropLast(2)
        w.length > 4 && w.endsWith("ed") && !w.endsWith("eed") -> w.dropLast(2)
        w.length > 3 && w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us") -> w.dropLast(1)
        else -> w
    }

    /** The words of [text] that a match is made on. */
    fun terms(text: String): List<String> {
        val t = (" " + text.lowercase(Locale.ENGLISH).replace(rx("[^a-z0-9 ]"), " ").replace(rx("\\s+"), " ").trim() + " ")
            .replace(" bank nifty ", " banknifty ").replace(" bnf ", " banknifty ").replace(" bank nifti ", " banknifty ")
            .replace(" fin nifty ", " finnifty ").replace(" nifty bank ", " banknifty ").replace(" nifty 50 ", " nifty ").replace(" nifty fifty ", " nifty ")
            .replace(" stop loss ", " stoploss ").replace(" sl ", " stoploss ").replace(" stop losses ", " stoploss ")
            .replace(" ce ", " call ").replace(" pe ", " put ")
        return t.trim().split(" ").filter { it.isNotEmpty() && it !in STOP }.map { stem(it) }.distinct()
    }

    /** How many of [want] are in [have] (a word matches its own stem, or one starting with it of 5+ letters). */
    private fun hits(want: List<String>, have: List<String>): Int = want.count { w -> have.any { h -> h == w || (w.length >= 5 && h.startsWith(w)) } }

    /** Is [day] within [p] as of [today]? (Weeks start on Monday.) */
    fun within(day: LocalDate, p: Period?, today: LocalDate): Boolean {
        if (p == null) return !day.isAfter(today)
        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        return when (p) {
            Period.TODAY -> day == today
            Period.YESTERDAY -> day == today.minusDays(1)
            Period.WEEK -> !day.isBefore(monday) && !day.isAfter(today)
            Period.LAST_WEEK -> !day.isBefore(monday.minusDays(7)) && day.isBefore(monday)
            Period.MONTH -> day.year == today.year && day.month == today.month && !day.isAfter(today)
        }
    }

    /** The places that match [a], the best match first, then the newest. */
    fun find(a: Asked, all: List<Said>, today: LocalDate): List<Said> {
        val want = terms(a.topic)
        if (want.isEmpty()) return emptyList()
        // Every word, or all but one when three or more were asked.
        val need = if (want.size >= 3) want.size - 1 else want.size
        return all.asSequence().filter { within(it.day, a.period, today) }
            .map { it to hits(want, terms(it.text)) }
            .filter { it.second >= need }
            .sortedWith(compareByDescending<Pair<Said, Int>> { it.second }.thenByDescending { it.first.at ?: it.first.day.atTime(23, 59, 59) })
            .map { it.first }.toList()
    }

    private fun day(d: LocalDate, today: LocalDate): String = when (d) {
        today -> "today"
        today.minusDays(1) -> "yesterday"
        else -> "on ${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}" + if (d.year != today.year) " ${d.year}" else ""
    }

    private fun hm(t: LocalDateTime) = "%02d:%02d".format(Locale.ENGLISH, t.hour, t.minute)

    private fun quote(s: String): String = "\"" + s.trim().trimEnd('.', '!', ' ').take(240) + "\""

    /** One place read back, with where and when. */
    fun line(s: Said, today: LocalDate): String {
        val `when` = day(s.day, today).replaceFirstChar { it.uppercase() }
        return when (s.source) {
            Source.NOTE -> "$`when` you asked me to remember: ${quote(s.text)}"
            Source.TRADE_NOTE -> "$`when`${s.at?.let { " at ${hm(it)}" } ?: ""}, a note with a trade: ${quote(s.text)}"
            Source.JOURNAL -> "$`when`, in your journal" + (s.question?.let { " (I asked ${quote(it)})" } ?: "") + ", you said: ${quote(s.text)}"
        }
    }

    /** The answer: what Boss said about [a] in [all] (his notes, trade notes and journal answers), as of [today]. */
    fun say(a: Asked, all: List<Said>, today: LocalDate): String {
        val shown = "\"${a.topic}\"" + (a.period?.let { " ${it.label}" } ?: "")
        if (all.isEmpty())
            return "You haven't left me any words of yours yet, Boss - no notes to remember, no notes with trades and no journal answers. " +
                "Say \"remember that ...\" or \"Jarvis, note: ...\" and I'll keep them."
        val found = find(a, all, today)
        if (found.isEmpty())
            return "I don't find anything you said about $shown, Boss. I looked through the notes you asked me to remember, " +
                "your notes with trades and your journal answers (${all.size} in all)" +
                (if (a.period != null) " - ask without \"${a.period.label}\" to look further back." else " - try another word for it.")
        val top = found.take(MAX_SAID)
        val head = if (found.size == 1) "One place you spoke of $shown, Boss: " else
            "${found.size} places you spoke of $shown, Boss" + (if (found.size > MAX_SAID) " - the $MAX_SAID that match best, newest first" else ", the best match first") + ". "
        val more = if (found.size > MAX_SAID) " And ${found.size - MAX_SAID} more - a narrower word, or a period like \"last week\", finds them." else ""
        return head + top.joinToString(" ") { line(it, today) + "." } + more + " Your own words as kept, read back only."
    }
}
