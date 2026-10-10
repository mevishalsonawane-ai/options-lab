package com.optionslab.ira

import java.time.LocalDateTime

/**
 * Jarvis learning after which short answers Boss usually asks for more (learning round 28, 2026-10-05). With short answers
 * on (Boss's choice, the default: one precise line, [ShortAnswer]), Boss's "more", "tell me more", "go on", "details" or
 * "aur batao" ([wantsMore]) as his very next words after a short line notes that answer's KIND (its main topic,
 * [Clarity.kind]: the levels, the trend, his account...) and the minute - never his words, never the answer ([heard]).
 * "Repeat", "come again", "pardon" and "say it again" are not wanting more ([Clarity] keeps those) and never count.
 *
 * Set against how often Boss asked that kind at all (the daily kind tally [DoubtTally] that [SelfDoubt] already keeps), a
 * kind he asked more after at least [MIN_MORE] times, on at least [MIN_DAYS] days and in at least [SHARE] of the times he
 * asked it, in the last [WINDOW_DAYS] days (since the last reset), is learned ([learned]): its answers are then SAID in full
 * straight away aloud - as "tell me more" says them - instead of the short line first ([detailedOf]).
 *
 * A display choice of the voice only: the words are the answer's own, nothing is worked out again, and the chat keeps the
 * short line with its "Details". Never on a locked phone (the app passes no records then: the account is never said in full
 * aloud there), never with Boss's own "shorter" / "short answers" ([Command.Kind.BRIEF_ON]) on, never for a command, an
 * order or anything without a kind, and never an action's, a confirm's or a command's result (they are said whole anyway).
 * Nothing learned acts: it never arms, trades, switches, confirms or answers anything unasked.
 *
 * "Which answers do I usually ask more about?" names the kinds ([Request.WHICH]; his habit, so on an unlocked phone only);
 * "keep my short answers short" ([UNDO], [Request.RESET]) undoes it and starts the count afresh. Shown in the Learnings
 * ledger with that undo, and reset by "undo everything you learned this week". Pure: the app keeps the log.
 */
object MoreAfter {
    /** Only the last this many days count. */
    const val WINDOW_DAYS = 30L
    /** "More" after a kind's short line at least this many times... */
    const val MIN_MORE = 3
    /** ...on at least this many days... */
    const val MIN_DAYS = 2
    /** ...and in at least this share of the times Boss asked that kind. */
    const val SHARE = 0.5
    /** Notes kept at most. */
    const val KEEP = 200
    /** Kinds named at most. */
    const val SHOW = 3

    const val UNDO = "keep my short answers short"
    const val LOCKED = "Unlock the phone for that, Boss."
    /** The undo on a locked phone: done all the same, in words that never name what was learned (or whether). */
    const val RESET_LOCKED = "Done, Boss: every answer starts with its short line again, and my count starts afresh from now."

    /** One "more" after a short line: the answer's kind ([Clarity.kind]'s key) and when. Never words. */
    data class Note(val at: LocalDateTime, val kind: String)

    /**
     * The "more"s noted, when Boss last asked to keep his short answers short (nothing before it counts), and each kind
     * learned with the counts it was learned on ([settle]). Once a kind is said in full Boss has no short line to say "more"
     * after, so its later asks would only shrink the share and un-learn it, then learn it again: the count is frozen at
     * learning and the kind stays learned until the undo ([reset]) clears it.
     */
    data class Log(val notes: List<Note> = emptyList(), val resetAt: LocalDateTime? = null, val learnedAt: Map<String, Record> = emptyMap())

    private fun norm(text: String) = Spaced.joined(text)

    /** "Repeat", "say it again", "come again", "pardon": hearing it again, not wanting more. */
    private val NOT_MORE = rx(" (repeat|again|pardon|sorry what|what did you say|once more|one more time) ")

    /**
     * Is [said] Boss wanting more of the last answer ("more", "tell me more", "go on", "details", "aur batao", "pura batao")
     * - [Commands]' own "more" - and never his "what?", "repeat" or "say it again"?
     */
    fun wantsMore(said: String): Boolean = runCatching {
        Commands.parse(said)?.kind == Command.Kind.MORE && !Clarity.unclear(said) && !NOT_MORE.containsMatchIn(norm(said))
    }.getOrDefault(false)

    /** Was [answer] to [question] said as a short line with more behind it ([ShortAnswer])? */
    fun shortened(question: String?, answer: String): Boolean =
        runCatching { ShortAnswer.of(question, answer).details != null }.getOrDefault(false)

    /**
     * The kind noted when Boss wants more after the short answer [answer] to [question], or null: no question, a question
     * with no kind (a command, an order, words not understood), or an answer that was not shortened.
     */
    fun kindAfter(question: String?, answer: String): String? {
        if (question.isNullOrBlank() || !shortened(question, answer)) return null
        val p = runCatching { Ask.parse(question) }.getOrNull() ?: return null
        if (p.command != null || p.order != null || Topic.ORDER in p.topics || Topic.COMMAND in p.topics) return null
        return Clarity.kind(question)
    }

    /**
     * [log] with Boss's "more" after a short answer of [kind] at [at] noted; older than the window (and past [KEEP]) dropped.
     * With the kinds [tally], a kind learned by it is frozen there and then ([settle]).
     */
    fun heard(log: Log, kind: String, at: LocalDateTime, tally: DoubtTally? = null): Log {
        val from = at.minusDays(WINDOW_DAYS)
        val noted = log.copy(notes = (log.notes.filter { it.at.isAfter(from) } + Note(at, kind)).sortedBy { it.at }.takeLast(KEEP))
        return if (tally != null) settle(noted, tally, at) else noted
    }

    /** [log] with each kind learned at [now] and not yet frozen kept with the counts it was learned on ([Log.learnedAt]). */
    fun settle(log: Log, tally: DoubtTally, now: LocalDateTime): Log {
        val fresh = counted(log, tally, now).filter { it.kind !in log.learnedAt }
        return if (fresh.isEmpty()) log else log.copy(learnedAt = log.learnedAt + fresh.associateBy { it.kind })
    }

    /** "Keep my short answers short": nothing before [now] counts any more, and nothing learned stays learned. */
    fun reset(now: LocalDateTime): Log = Log(emptyList(), now, emptyMap())

    /** A learned kind: "more" asked after it [more] times of the [asked] times Boss asked it, on [days] days, the newest at [newest]. */
    data class Record(val kind: String, val more: Int, val asked: Int, val days: Int, val newest: LocalDateTime) {
        val phrase: String get() = SelfDoubt.phraseOf(kind)
        fun say(): String = "you asked for more after $more of my $asked $phrase, on $days days"
    }

    /**
     * The kinds learned at [now] from [log] against [tally] (how often each kind was asked), the clearest first: those frozen
     * when learned ([settle]) as they were learned - the asks after it, answered in full, never un-learn them - and the rest
     * counted afresh.
     */
    fun learned(log: Log, tally: DoubtTally, now: LocalDateTime): List<Record> {
        val kept = log.learnedAt.values.filter { r -> !r.newest.isAfter(now) && log.resetAt.let { it == null || r.newest.isAfter(it) } }
        val keptKinds = kept.map { it.kind }.toSet()
        return (kept + counted(log, tally, now).filter { it.kind !in keptKinds })
            .sortedWith(compareByDescending<Record> { it.more.toDouble() / it.asked }.thenByDescending { it.more }.thenBy { it.kind })
    }

    /** The kinds that meet the bar at [now], counted over the window from the notes and the [tally] alone. */
    private fun counted(log: Log, tally: DoubtTally, now: LocalDateTime): List<Record> {
        val from = listOfNotNull(now.minusDays(WINDOW_DAYS), log.resetAt).max()
        val notes = log.notes.filter { it.at.isAfter(from) && !it.at.isAfter(now) }
        if (notes.isEmpty()) return emptyList()
        val fromDay = from.toLocalDate(); val today = now.toLocalDate()
        val asked = HashMap<String, Int>()
        tally.filterKeys { !it.isBefore(fromDay) && !it.isAfter(today) }.values
            .forEach { m -> m.forEach { (k, n) -> asked[k] = (asked[k] ?: 0) + maxOf(0, n) } }
        return notes.groupBy { it.kind }.mapNotNull { (k, xs) ->
            val more = xs.size
            val of = maxOf(more, asked[k] ?: 0)
            val days = xs.map { it.at.toLocalDate() }.distinct().size
            if (more < MIN_MORE || days < MIN_DAYS || more < SHARE * of) null
            else Record(k, more, of, days, xs.maxOf { it.at })
        }
    }

    /** Is a question of kind [kind] ([Clarity.kind], read once by the caller; null: none) one to say in full straight away? */
    fun detailedOf(kind: String?, learned: List<Record>): Boolean = kind != null && learned.any { it.kind == kind }

    /** Is [question] of a kind learned ([learned]) to say in full straight away? Never a command or an order (no kind). */
    fun detailed(question: String, learned: List<Record>): Boolean {
        if (learned.isEmpty()) return false
        val p = runCatching { Ask.parse(question) }.getOrNull() ?: return false
        if (p.command != null || p.order != null) return false
        return detailedOf(Clarity.kind(question), learned)
    }

    // ---- asked -----------------------------------------------------------------------------------------------------

    enum class Request { WHICH, RESET }

    private const val LEAD = "^ (hey |ok |okay )?(jarvis )?(so )?(please )?(can you |could you |would you )?(tell me )?"
    private const val TAIL = "( please| boss| jarvis| now| any ?more| from now on| again| for me| always)* $"
    private const val ANSWERS = "(of )?(your |my )?(answers|replies|kinds of answers?)"
    private const val OFTEN = "(usually |always |often |mostly |normally )?"
    /** Said in full without the short line first. */
    private const val STRAIGHT = "(straight away|right away|directly|at once|first|up front|upfront|without the short (line|answer|version))"
    private const val WHOLE = "(the )?(whole|full|detailed|entire) (answers?|thing|replies|reply)"
    private const val SHORT_LINE = "(the )?short (line|answer|version)s?"

    private val WHICH = rx(LEAD + "(which|what) $ANSWERS do i $OFTEN(ask|want) (you )?(for )?more (about|on|after|of)" + TAIL + "|" +
        LEAD + "(after )?(which|what) $ANSWERS do i $OFTEN(ask|want) (you )?for more" + TAIL + "|" +
        LEAD + "(where|when) do i $OFTEN(ask|want) (you )?for more" + TAIL + "|" +
        LEAD + "(which|what) $ANSWERS do you (give|say|tell)( me)? (in full|whole) $STRAIGHT" + TAIL + "|" +
        LEAD + "(which|what) $ANSWERS do you (skip|drop|leave out) $SHORT_LINE (for|on)" + TAIL + "|" +
        LEAD + "why (did|do) you (give|say|tell)( me)? $WHOLE( $STRAIGHT)?" + TAIL + "|" +
        LEAD + "why (didnt|did not|dont|do not) you (keep it short|give me $SHORT_LINE|say $SHORT_LINE)" + TAIL + "|" +
        "^ (jarvis )?(kis|kaun se|kaunse|kon se|kin) (jawab|jawabon|answers?) (ke baad|par|pe) (main|mai) $OFTEN(aur|zyada|more|details?) (poochta|puchta|maangta|mangta) (hoon|hu|hun)" + TAIL + "|" +
        "^ (jarvis )?(poora|pura) jawab seedha (kyun|kyu|kyon) (diya|bola|bataya)" + TAIL)

    /**
     * Only a clear undo, always naming the short line or Boss asking for more: "keep my short answers short", "stop skipping
     * the short line", "don't give me the whole answer straight away", "forget which answers I ask more about". Never
     * "short answers" or "shorter" (his own commands), never a market question, never a stop of an arm ([Commands]' habit undo).
     */
    private val RESET = rx(LEAD + "keep (my |your |the )short answers short" + TAIL + "|" +
        LEAD + "(always )?(give|say|tell)( me)? $SHORT_LINE first( for every (answer|topic|kind)| for everything| every time)?" + TAIL + "|" +
        LEAD + "stop (skipping|leaving out) $SHORT_LINE( for (some|any|certain) (answers|topics|kinds))?" + TAIL + "|" +
        LEAD + "(dont|do not|no need to) (skip|drop|leave out) $SHORT_LINE( for (some|any|certain) (answers|topics|kinds))?" + TAIL + "|" +
        LEAD + "stop giving( me)? $WHOLE $STRAIGHT" + TAIL + "|" +
        LEAD + "(dont|do not) (give|say|tell)( me)? $WHOLE $STRAIGHT" + TAIL + "|" +
        LEAD + "(forget|reset|clear|unlearn) (which|the|what) (answers|replies|kinds of answers?) i $OFTEN(ask|want) (you )?(for )?more (about|on|after|of)" + TAIL + "|" +
        LEAD + "(forget|reset|clear|unlearn) (where|when) i $OFTEN(ask|want) (you )?for more" + TAIL + "|" +
        "^ (jarvis )?(poora|pura) jawab seedha (mat|na) (do|bolo|dena|sunao)" + TAIL)

    /** "Which answers do I usually ask more about?" or "keep my short answers short", else null. */
    fun asked(text: String): Request? = askedKept.of(text) { askedFresh(text) }

    private val askedKept = Kept<Request?>(64)

    private fun askedFresh(text: String): Request? {
        val t = norm(text)
        return when {
            RESET.containsMatchIn(t) -> Request.RESET
            WHICH.containsMatchIn(t) -> Request.WHICH
            else -> null
        }
    }

    const val ONLY_VOICE = "Only what my voice says first changes: the chat keeps the short line with its details, your own \"shorter\" comes first, and nothing I learn acts."

    private fun named(rs: List<Record>): String = rs.take(SHOW).map { "${it.phrase} - ${it.say()}" }.let {
        (if (it.size <= 1) it.joinToString("") else it.dropLast(1).joinToString("; ") + "; and " + it.last()) +
            (if (rs.size > SHOW) "; and ${rs.size - SHOW} more" else "")
    }

    /** "Which answers do I usually ask more about?". */
    fun say(rs: List<Record>): String =
        if (rs.isEmpty()) "I start every answer with its short line, Boss. If you keep saying \"more\" right after one kind of short answer - " +
            "at least $MIN_MORE times, on $MIN_DAYS days and half the times you ask it - I'll say that kind in full straight away. $ONLY_VOICE"
        else "Boss, aloud I say these in full straight away, not as a short line: ${named(rs)}, in the last $WINDOW_DAYS days. $ONLY_VOICE Say \"$UNDO\" to undo it."

    /** "Keep my short answers short". */
    fun sayReset(rs: List<Record>): String =
        if (rs.isEmpty()) "I already start every answer with its short line, Boss. I'll start my count afresh from now."
        else "Done, Boss: every answer starts with its short line again, and my count starts afresh from now."

    /** Noted in the app's activity when a kind becomes learned (kind phrase only). */
    fun learnedNote(r: Record): String = "Learned: Boss usually asks for more after ${r.phrase} - said in full straight away aloud (undo: \"$UNDO\")."

    /** The ledger's lines for [r]. */
    fun ledgerWhat(r: Record): String = "${r.phrase.replaceFirstChar { it.uppercase() }}: in full straight away aloud, not the short line first"
    fun ledgerWhy(r: Record): String = "${r.say()} in the last $WINDOW_DAYS days; only my voice changes, the chat keeps the short line"
}
