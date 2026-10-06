package com.optionslab.ira

import java.time.LocalDate

/**
 * Jarvis learning which part of a market read Boss asks for on its own (learning round 26, 2026-10-05), from what the app
 * already keeps: the daily tally of question kinds ([SelfDoubt.count], kind keys and counts only - "topic:LEVELS" - never
 * a word). Nothing new is recorded; only the day Boss last asked to undo it is kept ([Log]).
 *
 * Of his questions mainly about the levels, the trend or patterns in the last [WINDOW_DAYS] days (after the reset day),
 * the levels or patterns asked at least [MIN_TIMES] times, on at least [MIN_DAYS] days, at least [SHARE] of the three
 * together and more often than the trend, is learned ([learned]). A plain "how is Nifty?" (an overview and nothing else,
 * [reorders]) then says that part right after the price line, before the trend: "Nifty is at 24,600 ... Nearest level
 * above: ... The 15-minute trend is up ..." ([order]). Only the order of the sentences changes - never a word, a figure,
 * which sentences are said, any other question's answer, an order, an arm, a setting or a reminder. Nothing learned acts.
 *
 * "What do you say first in an overview?" / "why do you give the levels first?" names it and why ([Request.WHICH]; his
 * habit, so on an unlocked phone only); "say your overviews in the usual order" ([UNDO], [Request.RESET]) undoes it and
 * starts the count afresh from the next day. Shown in the Learnings ledger with that undo, and reset by "undo everything
 * you learned this week". Pure: the app keeps the tally and the reset day.
 */
object LeadPart {
    /** Only the last this many days count (the tally keeps no more). */
    const val WINDOW_DAYS = 30L
    /** The part is asked for at least this many times... */
    const val MIN_TIMES = 10
    /** ...on at least this many days... */
    const val MIN_DAYS = 3
    /** ...and in at least this share of his questions about the levels, the trend or patterns (and more than the trend). */
    const val SHARE = 0.5

    const val UNDO = "say your overviews in the usual order"
    const val LOCKED = "Unlock the phone for that, Boss."

    /** The parts an overview can lead with, after the price line (the trend leads already, as always). */
    val PARTS = listOf(Topic.LEVELS, Topic.PATTERNS)
    /** The parts counted against each other. */
    private val COUNTED = listOf(Topic.LEVELS, Topic.TREND, Topic.PATTERNS)

    private val LABEL = mapOf(Topic.LEVELS to "the levels", Topic.TREND to "the trend", Topic.PATTERNS to "patterns")

    /** The day Boss last asked to undo it: the tally's days up to it no longer count. */
    data class Log(val resetOn: LocalDate? = null)

    /** The part learned: asked [times] times on [days] days, the trend [trend] times, the other part [other], the newest on [newest]. */
    data class Record(val part: Topic, val times: Int, val trend: Int, val other: Int, val days: Int, val newest: LocalDate) {
        val phrase: String get() = LABEL[part] ?: part.name.lowercase()
        fun say(): String = "you asked about $phrase $times times on $days days, the trend $trend" +
            (if (part == Topic.LEVELS) " and patterns $other" else " and the levels $other")
    }

    private fun key(t: Topic) = "topic:${t.name}"

    /** The learned part at [today] from [tally] ([SelfDoubt]'s), or null (the usual order). */
    fun learned(tally: DoubtTally, log: Log, today: LocalDate): Record? {
        val reset = log.resetOn
        val days = tally.filterKeys { d -> !d.isAfter(today) && d.isAfter(today.minusDays(WINDOW_DAYS)) && (reset == null || d.isAfter(reset)) }
        fun times(t: Topic) = days.values.sumOf { maxOf(0, it[key(t)] ?: 0) }
        val total = COUNTED.sumOf { times(it) }
        if (total <= 0) return null
        val p = PARTS.maxByOrNull { times(it) } ?: return null
        val mine = times(p)
        val trend = times(Topic.TREND)
        val on = days.filter { (it.value[key(p)] ?: 0) > 0 }.keys
        if (mine < MIN_TIMES || on.size < MIN_DAYS || mine < SHARE * total || mine <= trend) return null
        if (PARTS.any { it != p && times(it) >= mine }) return null
        return Record(p, mine, trend, total - mine - trend, on.size, on.max())
    }

    /** True for a plain overview ("how is Nifty?"): only then may the learned part lead. */
    fun reorders(topics: Set<Topic>): Boolean = topics == setOf(Topic.OVERVIEW)

    /** An overview's [parts] (keyed by topic, in their usual order) with [lead]'s part first, or as they are. */
    fun <T> order(parts: List<Pair<Topic, T>>, lead: Topic?): List<T> =
        (if (lead == null || lead !in PARTS || parts.none { it.first == lead }) parts
        else parts.filter { it.first == lead } + parts.filter { it.first != lead }).map { it.second }

    /** "Say your overviews in the usual order" on [today]: the tally up to today no longer counts. */
    fun reset(today: LocalDate): Log = Log(today)

    // ---- asked -----------------------------------------------------------------------------------------------------

    enum class Request { WHICH, RESET }

    private fun norm(text: String) = Spaced.joined(text)

    private const val LEAD = "^ (hey |ok |okay )?(jarvis )?(so )?(please )?(can you |could you |would you )?(tell me )?"
    private const val TAIL = "( please| boss| jarvis| now| again| from now on| for me)* $"
    private const val PART = "(the )?(levels|level|patterns|pattern|support and resistance)"

    private val WHICH = rx(LEAD + "(why|how come) (do you|are you) (always )?(say|saying|give|giving|tell me|telling me|mention|mentioning|put|putting|start with|starting with) $PART first( in (the|an|your) overviews?)?" + TAIL + "|" +
        LEAD + "why do you (always )?(start|begin) (with|on) $PART( in (the|an|your) overviews?)?" + TAIL + "|" +
        LEAD + "why (are|were|is|was) $PART (said |given |put )?first( in (the|an|your) overviews?)?" + TAIL + "|" +
        LEAD + "what do you (say|tell me|give|mention|put) first in (the|an|your) overviews?" + TAIL + "|" +
        LEAD + "what comes first in your overviews?" + TAIL + "|" +
        LEAD + "(which|what) part (of the market |of a market read )?do i ask (you )?(about|for) (the )?most" + TAIL + "|" +
        LEAD + "overview (mein|me|main) pehle kya (bolte|batate) ho" + TAIL + "|" +
        LEAD + "(level|levels|patterns|pattern) pehle (kyun|kyu|kyon) (bolte|batate) ho" + TAIL)

    /**
     * Only a clear undo: "say your overviews in the usual order", "stop (or don't) putting the levels first", "go back to the
     * usual order in your overviews", "levels pehle mat batao". Never a market question: "give me the overview", "levels
     * first", "levels pehle batao" keep their route.
     */
    private val RESET = rx(LEAD + "(say|give) your overviews (in|back in) the usual order" + TAIL + "|" +
        LEAD + "stop (putting|saying|giving|mentioning|starting with) $PART first( in (the|your) overviews?)?" + TAIL + "|" +
        LEAD + "stop (starting|beginning) (with|on) $PART( in (the|your) overviews?)?" + TAIL + "|" +
        LEAD + "(dont|do not) (put|say|give|mention|start with) $PART first( in (the|your) overviews?)?" + TAIL + "|" +
        LEAD + "go back to the usual order in (the|your) overviews?" + TAIL + "|" +
        LEAD + "(level|levels|patterns|pattern) pehle (mat|na) (bolo|batao|bolna|batana)" + TAIL)

    /** "What do you say first in an overview?" or "say your overviews in the usual order", else null. */
    fun asked(text: String): Request? = askedKept.of(text) { askedFresh(text) }

    private val askedKept = Kept<Request?>(64)

    private fun askedFresh(text: String): Request? {
        // "Why do you say the levels first?", "don't put the levels first" are this one's; FigureFirst keeps the number and
        // figure wordings and the levels only with "your answers" / "reads" said ("don't start your answers with the levels",
        // routed before this in the hub, which then undoes this one too).
        if (FigureFirst.asked(text) != null) return null
        val t = norm(text)
        return when {
            RESET.containsMatchIn(t) -> Request.RESET
            WHICH.containsMatchIn(t) -> Request.WHICH
            else -> null
        }
    }

    const val ONLY_ORDER = "Only the order of the sentences changes: never a figure, what is said, or anything that acts."

    /** "What do you say first in an overview?". */
    fun say(r: Record?): String =
        if (r == null) "In an overview I say the price, then the trend, then the levels and any pattern, Boss, as always. If you keep " +
            "asking for the levels or patterns on their own - $MIN_TIMES times or more, on $MIN_DAYS days, more than the trend and at " +
            "least half of those questions - I'll say that part right after the price."
        else "Boss, ${r.say()} in the last $WINDOW_DAYS days - so when you ask how the market is, I say ${r.phrase} right after the " +
            "price, before the trend. $ONLY_ORDER Say \"$UNDO\" to undo it."

    /** "Say your overviews in the usual order". */
    fun sayReset(r: Record?): String =
        if (r == null) "My overviews are already in the usual order, Boss. I'll start my count afresh from tomorrow."
        else "Done, Boss: the trend right after the price again, and my count starts afresh from tomorrow."

    /** The undo on a locked phone: done all the same, in words that never name what was learned (or whether). */
    const val RESET_LOCKED = "Done, Boss: my overviews in the usual order, and my count starts afresh from tomorrow."

    /** The ledger's lines for [r]. */
    fun ledgerWhat(r: Record): String = "${r.phrase.replaceFirstChar { it.uppercase() }} said right after the price in an overview"
    fun ledgerWhy(r: Record): String = "${r.say()} in the last $WINDOW_DAYS days; only the order changes"
}
