package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

/**
 * Jarvis learning the hours of the day Boss actually talks to him (learning round 19, 2026-10-05). Each question Boss
 * asks notes only its day and its hour (never the words, never what it was about). An hour he asked something in on at
 * least [MIN_DAYS] different days of the last [WINDOW_DAYS] - once he has talked to Jarvis on at least [MIN_ACTIVE]
 * days at all - is one of his hours ([hours]).
 *
 * An unasked, non-urgent briefing of [LONG] sentences or more that comes more than [SLACK_MIN] minutes away from any of
 * his hours (a weekly review at 07:00 when he only ever talks from 09:00) is said SHORTER aloud: its first sentence,
 * then "the rest is in the chat" ([aloud]). Only ever shorter, never more and never later; the chat, the pop-up and the
 * notification keep every word. Never a safety warning (those are urgent and never come here), never a reply to
 * Boss's own question, never a Hindi answer or one holding a data-age, doubt or market-closed note ([FigureFirst.hasNote]),
 * which are said as they always were.
 *
 * "When do I usually talk to you?" names his hours ([Request.WHICH]); "say your briefings in full at any hour"
 * ([UNDO], [Request.RESET]) starts the count afresh. Shown in the Learnings ledger with that undo and reset by "undo
 * everything you learned this week". Nothing learned here acts: it changes how much his voice says, nothing else.
 * Pure: the app keeps the log.
 */
object TalkHours {
    /** Only the last this many days count. */
    const val WINDOW_DAYS = 21L
    /** Boss talked in an hour on at least this many days before it is one of his. */
    const val MIN_DAYS = 3
    /** And talked to Jarvis on at least this many days at all before anything is learned. */
    const val MIN_ACTIVE = 5
    /** A briefing within this many minutes of one of his hours counts as in it. */
    const val SLACK_MIN = 30L
    /** A briefing this many sentences long or longer is shortened outside his hours. */
    const val LONG = 3
    /** Days kept at most. */
    const val KEEP = 40

    const val UNDO = "say your briefings in full at any hour"

    /** One day Boss talked to Jarvis: the hours (0 to 23) he asked something in. */
    data class Day(val day: LocalDate, val hours: Set<Int>)

    /** The days noted, and when Boss last asked for his briefings in full (nothing before it counts). */
    data class Log(val days: List<Day> = emptyList(), val resetAt: LocalDateTime? = null)

    /** [log] with a question at [at] noted (its day and hour only); days older than the window (and past [KEEP]) dropped. */
    fun heard(log: Log, at: LocalDateTime): Log {
        val d = at.toLocalDate()
        val from = d.minusDays(WINDOW_DAYS)
        val old = log.days.firstOrNull { it.day == d }
        val today = Day(d, (old?.hours ?: emptySet()) + at.hour)
        val kept = log.days.filter { it.day != d && it.day.isAfter(from) } + today
        return log.copy(days = kept.sortedBy { it.day }.takeLast(KEEP))
    }

    /** "Say your briefings in full at any hour": nothing before [now] counts any more. */
    fun reset(log: Log, now: LocalDateTime): Log = Log(emptyList(), now)

    /** The days that count at [now]: in the window, not after [now], each with only the hours after the reset. */
    private fun counted(log: Log, now: LocalDateTime): List<Day> {
        val today = now.toLocalDate()
        val from = today.minusDays(WINDOW_DAYS)
        val reset = log.resetAt
        return log.days.filter { it.day.isAfter(from) && !it.day.isAfter(today) }.mapNotNull { d ->
            val hs = d.hours.filter { h -> (reset == null || !d.day.atTime(h, 59).isBefore(reset)) && !d.day.atTime(h, 0).isAfter(now) }.toSet()
            if (hs.isEmpty()) null else Day(d.day, hs)
        }
    }

    /** The hours Boss talks to Jarvis in, each with the days he did; empty until he has talked on [MIN_ACTIVE] days. */
    data class Record(val hours: List<Int>, val days: Map<Int, Int>, val active: Int, val newest: LocalDate?) {
        val learned: Boolean get() = hours.isNotEmpty()
    }

    fun record(log: Log, now: LocalDateTime): Record {
        val ds = counted(log, now)
        val per = (0..23).associateWith { h -> ds.count { h in it.hours } }.filterValues { it > 0 }
        val hours = if (ds.size < MIN_ACTIVE) emptyList() else per.filterValues { it >= MIN_DAYS }.keys.sorted()
        return Record(hours, per, ds.size, ds.maxOfOrNull { it.day })
    }

    /** Is [at] in (or within [SLACK_MIN] minutes of) one of [hours]? Always true while nothing is learned. */
    fun inHours(hours: List<Int>, at: LocalDateTime): Boolean =
        hours.isEmpty() || listOf(at, at.minusMinutes(SLACK_MIN), at.plusMinutes(SLACK_MIN)).any { it.hour in hours }

    private val SENTENCE = rx("(?<=[.!?\\u0964])\\s+")

    /**
     * What is said aloud of an unasked, non-urgent briefing [text] at [at]: inside his [hours] (or nothing learned), a
     * short one, a Hindi one or one holding a note - [text] as it is; else its first sentence and "the rest is in the
     * chat". Never a word changed, only fewer said.
     */
    fun aloud(text: String, at: LocalDateTime, hours: List<Int>): String {
        if (inHours(hours, at) || Aloud.hindi(text) || FigureFirst.hasNote(text)) return text
        val parts = SENTENCE.split(text.trim()).filter { it.isNotBlank() }
        if (parts.size < LONG) return text
        return Address.boss(parts[0].trim() + " The rest is in the chat.")
    }

    /**
     * May an announcement go through [aloud] at all? Never a reply to Boss ([prompted]), never a safety warning
     * ([urgent]), and never one said [full]: the 09:00 morning check (a kill switch on, Zerodha not logged in, a battery
     * warning - it shortens its own minor items) and Boss's own reminders (his words).
     */
    fun mayShorten(prompted: Boolean, urgent: Boolean, full: Boolean): Boolean = !prompted && !urgent && !full

    /** "09:00 to 11:00 and 14:00 to 15:00": [hours] as spans. */
    fun spans(hours: List<Int>): String {
        val out = ArrayList<String>()
        var i = 0
        val hs = hours.sorted()
        while (i < hs.size) {
            var j = i
            while (j + 1 < hs.size && hs[j + 1] == hs[j] + 1) j++
            out += "%02d:00 to %02d:00".format(Locale.ENGLISH, hs[i], (hs[j] + 1) % 24)
            i = j + 1
        }
        return if (out.size <= 1) out.joinToString("") else out.dropLast(1).joinToString(", ") + " and " + out.last()
    }

    // ---- asked -----------------------------------------------------------------------------------------------------

    enum class Request { WHICH, RESET }

    private fun norm(text: String) = " " + text.lowercase().replace("'", "").replace("’", "")
        .replace(rx("[^a-z0-9 ]"), " ").replace(rx("\\s+"), " ").trim() + " "

    private const val LEAD = "^ (hey |ok |okay )?(jarvis )?(so )?(please )?(can you |could you |would you )?(tell me )?"
    private const val TAIL = "( please| boss| jarvis| now| aloud| again| from now on| always)* $"
    private const val BRIEF = "(briefings?|updates?|unasked updates?)"

    private val WHICH = rx(LEAD + "(when|at what time|what time|what times|which hours|what hours) (of (the )?day )?do i (usually |normally |mostly )?(talk|speak|chat) (to|with) you" + TAIL + "|" +
        LEAD + "(which|what) hours (of (the )?day )?(am i|do i) (usually |normally )?(active|around|talking to you)" + TAIL + "|" +
        LEAD + "(which|what) hours do you (keep|say|cut) (your )?$BRIEF (short|shorter|brief)" + TAIL + "|" +
        LEAD + "(when|why) do you (keep|cut|say) (your )?$BRIEF (short|shorter|brief)" + TAIL + "|" +
        LEAD + "why (was|is) (the |your |that )?$BRIEF (so )?(short|brief|cut short)( today| this morning| last night)?" + TAIL + "|" +
        LEAD + "(main|mai) (tumse|aapse|tumhe|aapko) (kab|kis time) (baat karta|bolta) (hoon|hu|hun)" + TAIL)
    private val RESET = rx(LEAD + "(say|give|read|tell) (me )?(your |the |my )?$BRIEF in full (at any hour|any time|anytime|at all hours|whatever the hour|always)" + TAIL + "|" +
        LEAD + "(dont|do not|stop|no need to) (shorten|shortening|cut|cutting) (your |the |my )?$BRIEF( short)?( outside my hours| at odd hours| any ?more)?" + TAIL + "|" +
        LEAD + "(always )?(say|read) (your |the |my )?$BRIEF in full whatever the (time|hour)" + TAIL + "|" +
        LEAD + "$BRIEF (poori|puri|poora|pura) (bolo|sunao) (hamesha|kabhi bhi)" + TAIL)

    /** "When do I usually talk to you?" or "say your briefings in full at any hour", else null. */
    fun asked(text: String): Request? {
        val t = norm(text)
        return when {
            RESET.containsMatchIn(t) -> Request.RESET
            WHICH.containsMatchIn(t) -> Request.WHICH
            else -> null
        }
    }

    const val ONLY_VOICE = "Only my voice is shorter, and only for long unasked briefings: the chat keeps every word, safety warnings are always said in full, and nothing I learn acts."

    /** "When do I usually talk to you?". */
    fun say(log: Log, now: LocalDateTime): String {
        val r = record(log, now)
        if (!r.learned) return "I haven't learned your hours yet, Boss: you've talked to me on ${r.active} day${if (r.active == 1) "" else "s"} " +
            "in the last $WINDOW_DAYS days, and I wait for $MIN_ACTIVE, and an hour you use on $MIN_DAYS of them. Until then every briefing is said as it is."
        return "Boss, you usually talk to me between ${spans(r.hours)} (an hour you asked me something in on at least $MIN_DAYS of the ${r.active} days " +
            "you talked to me in the last $WINDOW_DAYS days). A long briefing I bring up unasked outside those hours I say in one sentence aloud, and the rest is in the chat. " +
            "$ONLY_VOICE Say \"$UNDO\" to undo it."
    }

    /** "Say your briefings in full at any hour". */
    fun sayReset(log: Log, now: LocalDateTime): String =
        if (!record(log, now).learned) "I already say every briefing in full, Boss. I'll start my count of your hours afresh from now."
        else "Done, Boss: every briefing in full aloud at any hour again, and my count of your hours starts afresh from now."

    /** The ledger's lines for [r]. */
    fun ledgerWhat(r: Record): String = "long unasked briefings outside ${spans(r.hours)}: the first sentence aloud, the rest in the chat"
    fun ledgerWhy(r: Record): String = "those are the hours you talked to me on at least $MIN_DAYS of the ${r.active} days you did in the last $WINDOW_DAYS days; " +
        "safety warnings and your own questions are always said in full"
}
