package com.optionslab.ira

import java.time.LocalDateTime

/**
 * Jarvis learning to say the figure first in a market read Boss keeps asking again for it (learning round 14,
 * 2026-10-05). [AskedAgain] notes a market read asked again within minutes; this notes, at each such re-ask, only
 * whether the re-ask was after a FIGURE - the level, the price, how much, how far ("where exactly", "what level",
 * "how many points", "kitna"; a re-ask of the levels always is) - by its kind (the topic key) and a yes or no, never the
 * words. A kind Boss re-asked for a figure at least [MIN_FIGURE] times, at least half of its re-asks, in the last
 * [WINDOW_DAYS] days ([leading]) is said aloud with its first sentence that holds a figure moved to the front
 * ([lead]): "Boss, support is 24,500 and resistance 24,700. Nifty is trading in a range..." instead of the figure third.
 *
 * Only the ORDER of what is said aloud changes - never a word, a figure or a sentence, and never the chat (which keeps
 * the answer as written). The voice keeps the reordered text as its full answer, so "go on" after a cut finds the same
 * sentences. Never an order, a command or anything but a market read ([AskedAgain.read]); a sentence that leans on the
 * one before ("It", "That", "So"...) is never moved, nor anything in a Hindi or a multi-line answer. "Which reads do you
 * start with the number?" names them ([Request.WHICH]); "say your market reads in the usual order" undoes it and starts
 * the count afresh ([Request.RESET]); shown in the Learnings ledger with that undo and reset by "undo everything you
 * learned this week". Nothing learned here acts. Pure: the app keeps the log.
 */
object FigureFirst {
    /** Only the last this many days count. */
    const val WINDOW_DAYS = 30L
    /** Re-asked for a figure this many times before anything changes (twice may be chance). */
    const val MIN_FIGURE = 3
    /** And at least this share of the kind's re-asks. */
    const val MIN_SHARE = 0.5
    /** The figure is looked for in the first this many sentences only. */
    const val LOOK = 6
    /** Events kept at most. */
    const val KEEP = 300
    /** Kinds named at most. */
    const val SHOW = 3

    /** One re-ask: the read's kind ("topic:LEVELS"), whether it was after a figure, and when. No words are kept. */
    data class Event(val kind: String, val figure: Boolean, val at: LocalDateTime)

    /** The re-asks noted, and when Boss last asked for the usual order (nothing before it counts). */
    data class Log(val events: List<Event> = emptyList(), val resetAt: LocalDateTime? = null)

    private fun norm(text: String) = " " + text.lowercase().replace("'", "").replace("’", "")
        .replace(rx("[^a-z0-9% ]"), " ").replace(rx("\\s+"), " ").trim() + " "

    private val WANTS = rx(" (exact|exactly|number|numbers|figure|figures|how much|how many|how far|what level|which level|" +
        "what levels|which levels|at what|what price|which price|price|points|point|percent|%|where exactly|where is|where are|" +
        "kitna|kitne|kitni|kahan tak|kaha tak|kis level|kaunsa level|konsa level|level kya) ")

    /** Was the re-ask [text] of a read of [kind] after a figure? The levels always are; else by its words. */
    fun wantsFigure(kind: String, text: String): Boolean = kind == "topic:LEVELS" || WANTS.containsMatchIn(norm(text))

    /** [log] with one re-ask of [kind] noted ([figure]: it was after a figure); older than the window (and past [KEEP]) dropped. */
    fun heard(log: Log, kind: String, figure: Boolean, at: LocalDateTime): Log {
        val from = at.minusDays(WINDOW_DAYS)
        return log.copy(events = (log.events.filter { it.at.isAfter(from) } + Event(kind, figure, at)).takeLast(KEEP))
    }

    /** Boss asked for the usual order: the count starts afresh from [now]. */
    fun reset(log: Log, now: LocalDateTime): Log = Log(emptyList(), now)

    /** One kind's record: re-asked for a figure of re-asked. */
    data class Record(val kind: String, val figure: Int, val again: Int, val newest: LocalDateTime) {
        val leads: Boolean get() = figure >= MIN_FIGURE && figure >= MIN_SHARE * again
        val phrase: String get() = SelfDoubt.phraseOf(kind)
        fun say(): String = "you asked again for a figure after $figure of the $again times you asked my $phrase again within minutes"
    }

    /** Every kind re-asked since the window (or the reset), most re-asked for a figure first. */
    fun records(log: Log, now: LocalDateTime): List<Record> {
        val from = listOfNotNull(now.minusDays(WINDOW_DAYS), log.resetAt).max()
        return log.events.filter { it.at.isAfter(from) && !it.at.isAfter(now) }.groupBy { it.kind }
            .map { (k, es) -> Record(k, es.count { it.figure }, es.size, es.maxOf { it.at }) }
            .sortedWith(compareByDescending<Record> { it.figure }.thenByDescending { it.again })
    }

    /** The kinds whose figure he says first aloud now. */
    fun leading(log: Log, now: LocalDateTime): List<Record> = records(log, now).filter { it.leads }

    // ---- the order aloud -------------------------------------------------------------------------------------------

    private val SENTENCE = rx("(?<=[.!?\\u0964])\\s+")
    /** A figure worth leading with: a percent, points, a grouped number, three or more digits, or a decimal. */
    private val FIGURE = rx("\\d+(\\.\\d+)?\\s?(%|percent\\b|pts?\\b|points?\\b)|(?<![\\d,.])\\d{1,3}(,\\d{2,3})+(\\.\\d+)?|(?<![\\d,.:])\\d{3,}(\\.\\d+)?(?![\\d:])|(?<![\\d,.])\\d+\\.\\d+")
    /** A sentence leaning on the one before it: never moved to the front. */
    private val LEANS = rx("^(boss,?\\s+)?(it|its|it's|that|thats|that's|this|these|those|they|their|there|then|so|but|and|also|which|he|she|its|here|both|either|neither|however|still|yet|meanwhile|again|instead|otherwise|as|while|because|since)\\b", RegexOption.IGNORE_CASE)

    fun hasFigure(sentence: String): Boolean = FIGURE.containsMatchIn(sentence)

    /**
     * [text] with its first sentence holding a figure moved to the front (the rest in their order), or [text] as it is:
     * the first sentence already holds one, none of the first [LOOK] does, the one found leans on the sentence before it,
     * or the answer is Hindi or in lines. Never a word changed, added or dropped.
     */
    fun reorder(text: String): String {
        if (text.contains('\n') || Aloud.hindi(text)) return text
        val parts = SENTENCE.split(text.trim()).filter { it.isNotBlank() }
        if (parts.size < 2 || hasFigure(parts[0])) return text
        val i = (1 until minOf(parts.size, LOOK)).firstOrNull { hasFigure(parts[it]) } ?: return text
        if (LEANS.containsMatchIn(parts[i].trim())) return text
        return (listOf(parts[i]) + parts.filterIndexed { j, _ -> j != i }).joinToString(" ")
    }

    /** What is said aloud answering [question] with [text]: its figure first for a kind in [leading], else [text] as it is. */
    fun lead(question: String, text: String, leading: List<Record>): String {
        if (leading.isEmpty()) return text
        val r = runCatching { AskedAgain.read(question) }.getOrNull() as? AskedAgain.Read.Reading ?: return text
        return if (leading.any { it.kind == r.kind }) reorder(text) else text
    }

    // ---- asked -----------------------------------------------------------------------------------------------------

    enum class Request { WHICH, RESET }

    private const val LEAD = "^ (hey |ok |okay )?(jarvis )?(so )?(please )?(can you |could you |would you )?(tell me )?"
    private const val TAIL = "( please| boss| jarvis| now| aloud| again| from now on)* $"
    private const val FIG = "(the )?(number|numbers|figure|figures|level|levels)"
    private val WHICH = rx(LEAD + "(which|what) (of )?(your )?(answers|replies|market reads|reads) (do you|have you) (start|begin|lead|open) with $FIG( first)?" + TAIL + "|" +
        LEAD + "(why|how come) (do you|are you|did you) (start|begin|lead|open|starting|beginning|leading|opening) (your answers |your reads |my answers )?with $FIG( first)?" + TAIL + "|" +
        LEAD + "(why|how come) (do you|are you) (say|saying|put|putting) $FIG first" + TAIL + "|" +
        LEAD + "(which|what) (answers|reads|market reads) (do you )?(say|put) $FIG first" + TAIL + "|" +
        LEAD + "(kaun se|kon se|kaunse) (jawab|answers) (mein |me )?(number|figure|level) (pehle|pahle) (bolte|batate) ho" + TAIL)
    private val RESET = rx(LEAD + "(say|give|read) (your |me your |me |my )?(market reads|answers|reads) in the (usual|normal|old|original) order" + TAIL + "|" +
        LEAD + "(dont|do not|no need to) (starting|start|leading|lead|beginning|begin|opening|open) (your answers |your reads |answers |reads )?with $FIG( first)?( any ?more)?" + TAIL + "|" +
        LEAD + "(dont|do not) (saying|say|putting|put) $FIG first( any ?more)?" + TAIL + "|" +
        LEAD + "(number|figure|level) (pehle|pahle) mat (bolo|batao)" + TAIL)

    /** "Which reads do you start with the number?" or "say your market reads in the usual order", else null. */
    fun asked(text: String): Request? {
        val t = norm(text)
        return when {
            RESET.containsMatchIn(t) -> Request.RESET
            WHICH.containsMatchIn(t) -> Request.WHICH
            else -> null
        }
    }

    const val UNDO = "say your market reads in the usual order"
    const val ONLY_ORDER = "Only the order I say them aloud changes - never a word or a figure, and the chat keeps every answer as written; nothing I learn acts."

    private fun how(r: Record) = "${r.phrase} (${r.say()})"

    /** "Which reads do you start with the number?". */
    fun say(log: Log, now: LocalDateTime): String {
        val s = leading(log, now)
        if (s.isEmpty()) {
            val all = records(log, now)
            val fig = all.sumOf { it.figure }
            return "I say every market read in its usual order, Boss" +
                (if (fig > 0) " - you asked a read again for a figure $fig time${if (fig == 1) "" else "s"} lately, too few of one kind to change anything" else "") +
                ". If you keep asking one kind of read again for its figure, I'll say that figure first aloud. $ONLY_ORDER"
        }
        return "Boss, I say the figure first aloud in these: " + s.take(SHOW).joinToString("; ") { how(it) } +
            (if (s.size > SHOW) "; and ${s.size - SHOW} more" else "") + ", in the last $WINDOW_DAYS days. $ONLY_ORDER Say \"$UNDO\" to undo it."
    }

    /** "Say your market reads in the usual order". */
    fun sayReset(log: Log, now: LocalDateTime): String =
        if (leading(log, now).isEmpty()) "I'm already saying every market read in its usual order, Boss. I'll start my count afresh from now."
        else "Done, Boss: every market read in its usual order aloud again, and my count starts afresh from now."

    /** The ledger's line for [r]. */
    fun ledgerWhat(r: Record): String = "${r.phrase}: the figure first aloud"
    fun ledgerWhy(r: Record): String = "${r.say()}, in the last $WINDOW_DAYS days; only the order aloud changes, the chat keeps it as written"

    /** The newest re-ask of [kind] counted (for the ledger's date). */
    fun newest(log: Log, kind: String): LocalDateTime? =
        log.events.filter { it.kind == kind && (log.resetAt == null || it.at.isAfter(log.resetAt)) }.maxOfOrNull { it.at }
}
