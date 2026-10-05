package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Jarvis learning the question Boss asks every morning (learning round 20, 2026-10-05). Each market question Boss asks
 * on a weekday morning (from [FROM_HOUR]:00 to before [UNTIL_HOUR]:00) notes only its kind - [Routine.key]'s market key,
 * "NIFTY|levels" - and its day: never his words, never a question about his account (his P&L is never counted). The
 * 09:00 morning check marks each trading morning it ran ([checked]).
 *
 * A kind he asked on at least [MIN_MORNINGS] of the last [MORNINGS] trading mornings (before today, since the last reset)
 * is one of his morning questions ([usual]). The spoken morning check, and its chat note, then end with ONE line offering
 * the strongest of them he has not asked yet today ([offer], [line]): "You usually ask about Nifty's levels in the
 * morning, Boss - say "yes" for it." The answer is never given unasked: only Boss's bare "yes" ([yes]) as the very next
 * words, within [YES_MINUTES] minutes, asks that very question ([question]) - as if he had said it, a market question
 * that is no command and no order ([Routine.safe], checked when offered and on the yes). Anything else he says ends the
 * offer; a yes while something else waits for his yes or Confirm is never taken as it.
 *
 * Nothing learned here acts: it adds one line of words to the check and understands one "yes" - never arming, trading,
 * switching or confirming anything. "What do you offer me in the morning?" names his morning questions
 * ([Request.WHICH]); "don't offer my usual morning question" ([UNDO], [Request.RESET]) starts the count afresh. Shown in
 * the Learnings ledger with that undo and reset by "undo everything you learned this week". Pure: the app keeps the log.
 */
object MorningAsks {
    /** The last this many trading mornings (before today) count. */
    const val MORNINGS = 5
    /** A kind asked on at least this many of them is one of his morning questions. */
    const val MIN_MORNINGS = 3
    /** A morning question: asked on a weekday from this hour... */
    const val FROM_HOUR = 7
    /** ...to before this one. */
    const val UNTIL_HOUR = 11
    /** Boss's "yes" counts within this many minutes of the offer. */
    const val YES_MINUTES = 15L
    /** Mornings kept at most. */
    const val KEEP = 20

    const val UNDO = "don't offer my usual morning question"

    /** One weekday morning: the kinds Boss asked ([Routine.key]'s market keys), and whether the morning check ran then. */
    data class Morning(val day: LocalDate, val keys: Set<String>, val checked: Boolean = false)

    /** The mornings noted, and when Boss last asked to stop the offers (nothing before it counts). */
    data class Log(val mornings: List<Morning> = emptyList(), val resetAt: LocalDateTime? = null)

    /** Is [at] a weekday morning, when a question counts? */
    fun morning(at: LocalDateTime): Boolean =
        at.dayOfWeek != DayOfWeek.SATURDAY && at.dayOfWeek != DayOfWeek.SUNDAY && at.hour >= FROM_HOUR && at.hour < UNTIL_HOUR

    /** The kind [said] is counted as: a market question's key only (never the account, a command or an order), else null. */
    fun kind(said: String): String? {
        val k = runCatching { Routine.key(said) }.getOrNull() ?: return null
        if (Routine.account(k) || k.startsWith("ACCOUNT|")) return null
        return k.takeIf { Routine.safe(it) && Routine.about(it) != null }
    }

    private fun put(log: Log, m: Morning): Log =
        log.copy(mornings = (log.mornings.filter { it.day != m.day } + m).sortedBy { it.day }.takeLast(KEEP))

    /** [log] with Boss's question [said] at [at] noted: its kind only, and only on a weekday morning. */
    fun heard(log: Log, said: String, at: LocalDateTime): Log {
        if (!morning(at)) return log
        val k = kind(said) ?: return log
        val d = at.toLocalDate()
        val old = log.mornings.firstOrNull { it.day == d }
        if (old != null && k in old.keys) return log
        return put(log, Morning(d, (old?.keys ?: emptySet()) + k, old?.checked ?: false))
    }

    /** [log] with [day] marked a trading morning (the morning check ran). */
    fun checked(log: Log, day: LocalDate): Log {
        val old = log.mornings.firstOrNull { it.day == day }
        if (old?.checked == true) return log
        return put(log, Morning(day, old?.keys ?: emptySet(), true))
    }

    /** "Don't offer my usual morning question": nothing before [now] counts any more. */
    fun reset(log: Log, now: LocalDateTime): Log = Log(emptyList(), now)

    /** One of his morning questions: asked on [mornings] of the last [of] trading mornings, the newest on [newest]. */
    data class Usual(val key: String, val mornings: Int, val of: Int, val newest: LocalDate) {
        /** "Nifty's levels". */
        val phrase: String get() = Routine.about(key) ?: "your usual question"
    }

    /** The last [MORNINGS] trading mornings before [today], since the reset. */
    private fun last(log: Log, today: LocalDate): List<Morning> {
        val reset = log.resetAt
        return log.mornings.filter { it.checked && it.day.isBefore(today) && (reset == null || it.day.atTime(UNTIL_HOUR, 0).isAfter(reset)) }
            .sortedBy { it.day }.takeLast(MORNINGS)
    }

    /** His morning questions at [today], the most-asked first (then the newest). Empty until [MIN_MORNINGS] mornings. */
    fun usual(log: Log, today: LocalDate): List<Usual> {
        val ms = last(log, today)
        if (ms.size < MIN_MORNINGS) return emptyList()
        val keys = ms.flatMap { m -> m.keys.map { it to m.day } }
        return keys.groupBy { it.first }.mapNotNull { (k, xs) ->
            val days = xs.map { it.second }.distinct()
            if (days.size < MIN_MORNINGS || Routine.about(k) == null || !Routine.safe(k)) null
            else Usual(k, days.size, ms.size, days.max())
        }.sortedWith(compareByDescending<Usual> { it.mornings }.thenByDescending { it.newest }.thenBy { it.key })
    }

    /** The one to offer at [today]'s morning check: the strongest of [usual] Boss has not already asked today, or null. */
    fun offer(log: Log, today: LocalDate): Usual? {
        val asked = log.mornings.firstOrNull { it.day == today }?.keys ?: emptySet()
        return usual(log, today).firstOrNull { it.key !in asked }
    }

    /** The morning check's last line: the offer, never the answer. */
    fun line(u: Usual): String = "You usually ask about ${u.phrase} in the morning, Boss - say \"yes\" for it."

    /** The question asked on Boss's yes to [key], or null when it would not be a plain market question. */
    fun question(key: String): String? = if (Routine.account(key) || key.startsWith("ACCOUNT|") || !Routine.safe(key)) null else Routine.question(key)

    private val YES = rx("^(jarvis )?(yes|yeah|yep|yup|haan|haa|han|ha|ji|ji haan|haan ji)( please| jarvis| boss| bolo| batao| go on| tell me)*$")

    /** A bare "yes" ("yes please", "haan batao") - nothing else, and never with a "no" in it. */
    fun yes(text: String): Boolean = YES.matches(text.lowercase().replace(rx("[^a-z ]"), " ").replace(rx("\\s+"), " ").trim())

    /**
     * Boss's words [said] at [now] after [key] was offered at [offeredAt]: the question to ask, or null. Only a bare
     * [yes], within [YES_MINUTES], and never while anything else waits for his yes or Confirm ([waiting]: a pending
     * action or order) - that yes is never taken as the offer, nor the offer's yes as that.
     */
    fun taken(key: String, offeredAt: LocalDateTime, said: String, waiting: Boolean, now: LocalDateTime): String? =
        if (waiting || !yes(said) || !fresh(offeredAt, now)) null else question(key)

    /** Is a yes at [now] still for an offer made at [offeredAt]? */
    fun fresh(offeredAt: LocalDateTime, now: LocalDateTime): Boolean =
        !now.isBefore(offeredAt) && !now.isAfter(offeredAt.plusMinutes(YES_MINUTES))

    // ---- asked -----------------------------------------------------------------------------------------------------

    enum class Request { WHICH, RESET }

    private fun norm(text: String) = " " + text.lowercase().replace("'", "").replace("’", "")
        .replace(rx("[^a-z0-9 ]"), " ").replace(rx("\\s+"), " ").trim() + " "

    private const val LEAD = "^ (hey |ok |okay )?(jarvis )?(so )?(please )?(can you |could you |would you )?(tell me )?"
    private const val TAIL = "( please| boss| jarvis| now| any ?more| from now on| again)* $"
    private const val MORNING = "(in the morning|every morning|in the mornings|each morning|at the morning check|in the morning check|at 9( ?am)?|at nine)"
    private const val CHECK = "(the |my |your )?(morning|9 ?am|nine oclock) (check|checklist|report|briefing)"
    private const val QUESTION = "(usual )?(morning )?(questions?|ask)"

    private val WHICH = rx(LEAD + "what do i (usually |normally |always |mostly )?ask( you)? (about )?$MORNING" + TAIL + "|" +
        LEAD + "(what|which) (question|questions) do i (usually |normally |always )?ask( you)? $MORNING" + TAIL + "|" +
        // Round 16: "what is my usual morning question", "which morning questions have you learned"
        LEAD + "(what is|whats|what are|which is|which are) my (usual )?morning questions?" + TAIL + "|" +
        LEAD + "(what|which) (usual )?morning questions? (have you|did you) (learned|learnt|noticed)( about me| of mine)?" + TAIL + "|" +
        LEAD + "(what|which) $QUESTION do you offer( me)?( $MORNING| (in|at the end of) $CHECK)?" + TAIL + "|" +
        LEAD + "what do you offer( me)? ($MORNING|(in|at the end of) $CHECK)" + TAIL + "|" +
        LEAD + "why (did|do) you (offer|ask)( me)? (that|a|the|my usual) (question )?(in|at the end of) $CHECK" + TAIL + "|" +
        LEAD + "why (did|does) $CHECK (offer|end with) (a|that|my usual) question" + TAIL + "|" +
        LEAD + "(main|mai) (subah|roz subah) (kya|kaunsa sawal|kaun sa sawal) (poochta|puchta|pucchta) (hoon|hu|hun)" + TAIL)
    private val RESET = rx(LEAD + "(quit|stop) offering( me)? (my |the |a |that )?$QUESTION( $MORNING| (in|at the end of) $CHECK)?" + TAIL + "|" +
        LEAD + "(dont|do not|no need to) offer( me)? (my |the |a |any |that )?$QUESTION( $MORNING| (in|at the end of) $CHECK)" + TAIL + "|" +
        LEAD + "(dont|do not|no need to) offer( me)? (my |the |a |that )?(usual )?morning (questions?|ask)" + TAIL + "|" +
        LEAD + "no more (morning )?(question )?offers( $MORNING| (in|at the end of) $CHECK)?" + TAIL + "|" +
        LEAD + "(subah|morning) (ka|wala|vala) (sawal|question) (mat|na) (poocho|pucho|offer karo|bolo)" + TAIL + "|" +
        // Round 16: "subah wala sawal offer mat karo"
        LEAD + "(subah|morning) (ka|wala|vala) (sawal|question) offer (mat|na) (karo|kiya karo)" + TAIL)

    /** "What do you offer me in the morning?" or "don't offer my usual morning question", else null. */
    fun asked(text: String): Request? {
        val t = norm(text)
        return when {
            RESET.containsMatchIn(t) -> Request.RESET
            WHICH.containsMatchIn(t) -> Request.WHICH
            else -> null
        }
    }

    const val ONLY_WORDS = "It is one line of words at the end of the morning check: I never answer it unasked, a \"yes\" only asks the question, and nothing I learn acts."

    private fun named(us: List<Usual>): String = us.map { "${it.phrase} (${it.mornings} of the last ${it.of} mornings)" }.let {
        if (it.size <= 1) it.joinToString("") else it.dropLast(1).joinToString(", ") + " and " + it.last()
    }

    /** "What do you offer me in the morning?". */
    fun say(log: Log, today: LocalDate): String {
        val us = usual(log, today)
        if (us.isEmpty()) return "I don't offer you a morning question yet, Boss: I wait for a market question you ask on $MIN_MORNINGS of the last " +
            "$MORNINGS trading mornings (before 11:00). Until then the morning check ends as it always did."
        return "Boss, in the morning you usually ask about ${named(us)}. When you haven't asked yet, the morning check ends by offering " +
            "${us.first().phrase} - say \"yes\" and I'll tell you. $ONLY_WORDS Say \"$UNDO\" to undo it."
    }

    /** "Don't offer my usual morning question". */
    fun sayReset(log: Log, today: LocalDate): String =
        if (usual(log, today).isEmpty()) "I don't offer you a morning question, Boss. I'll start my count of your morning questions afresh from now."
        else "Done, Boss: the morning check ends as it used to, with no question offered, and my count of your morning questions starts afresh from now."

    /** The ledger's lines for [u]. */
    fun ledgerWhat(u: Usual): String = "${u.phrase}: offered in one line at the end of the morning check when you haven't asked yet"
    fun ledgerWhy(u: Usual): String = "you asked it on ${u.mornings} of the last ${u.of} trading mornings; never answered unasked - your \"yes\" asks it"
}
