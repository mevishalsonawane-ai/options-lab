package com.optionslab.ira

import java.time.LocalDate

/**
 * Jarvis learning which index Boss asks about by name (learning round 25, 2026-10-05), from what the app already keeps:
 * the daily tally of question kinds ([SelfDoubt.count], kind keys and counts only - "market:BANKNIFTY" - never a word).
 * Nothing new is recorded; only the day Boss last asked to undo it is kept ([Log]).
 *
 * Of the mentions of Nifty or BankNifty (a question naming both counts once for each) in the last [WINDOW_DAYS] days (after the reset day), an index other
 * than Nifty named at least [MIN_TIMES] times, on at least [MIN_DAYS] days, and in at least [SHARE] of them is learned
 * ([learned]). Where Jarvis names both indices anyway, he then names that one first ([order]): the greeting ("Good
 * morning, Boss. ... BankNifty is at ...; Nifty is at ...") and the outlook lines of the 09:00 check and of "what's the plan
 * for tomorrow?". Only the order changes - never a word, a figure, which index a question is answered for (that is
 * [UsualIndex]'s, from his corrections), an order, an arm, a setting or a reminder. Nothing learned acts.
 *
 * "Which index do you mention first?" / "which index do I ask about most?" names it and why ([Request.WHICH]; his habit,
 * so on an unlocked phone only); "mention Nifty first again" ([UNDO], [Request.RESET]) undoes it and starts the count
 * afresh from the next day. Shown in the Learnings ledger with that undo, and reset by "undo everything you learned this
 * week". Pure: the app keeps the tally and the reset day.
 */
object LeadIndex {
    /** Only the last this many days count (the tally keeps no more). */
    const val WINDOW_DAYS = 30L
    /** The index is named at least this many times (mentions, not questions)... */
    const val MIN_TIMES = 10
    /** ...on at least this many days... */
    const val MIN_DAYS = 3
    /** ...and in at least this share of those naming Nifty or BankNifty. */
    const val SHARE = 2.0 / 3.0

    const val UNDO = "mention Nifty first again"
    const val LOCKED = "Unlock the phone for that, Boss."

    /** The two indices Jarvis names together (Nifty first, unless learned otherwise). */
    val INDICES = listOf(Market.NIFTY, Market.BANKNIFTY)

    /** The day Boss last asked to undo it: the tally's days up to it no longer count. */
    data class Log(val resetOn: LocalDate? = null)

    /** The index learned: named [times] times on [days] days, Nifty [others] times, the newest on [newest]. */
    data class Record(val market: Market, val times: Int, val others: Int, val days: Int, val newest: LocalDate) {
        val phrase: String get() = market.label
        /** Counted by mention (a question naming both indices counts for each), so "mentions", never "questions". */
        fun say(): String = "${market.label} was $times of your ${times + others} mentions of Nifty or BankNifty, on $days days"
    }

    private fun key(m: Market) = "market:${m.name}"

    /** The learned index at [today] from [tally] ([SelfDoubt]'s), or null (Nifty first, as always). */
    fun learned(tally: DoubtTally, log: Log, today: LocalDate): Record? {
        val reset = log.resetOn
        val days = tally.filterKeys { d -> !d.isAfter(today) && d.isAfter(today.minusDays(WINDOW_DAYS)) && (reset == null || d.isAfter(reset)) }
        fun times(m: Market) = days.values.sumOf { maxOf(0, it[key(m)] ?: 0) }
        val total = INDICES.sumOf { times(it) }
        if (total <= 0) return null
        val m = INDICES.filter { it != Market.NIFTY }.maxByOrNull { times(it) } ?: return null
        val mine = times(m)
        val on = days.filter { (it.value[key(m)] ?: 0) > 0 }.keys
        if (mine < MIN_TIMES || on.size < MIN_DAYS || mine < SHARE * total) return null
        return Record(m, mine, total - mine, on.size, on.max())
    }

    /** [markets] with [lead] first (the rest in their order), or as they are when [lead] is null, Nifty or not among them. */
    fun <T> order(markets: List<T>, lead: T?): List<T> =
        if (lead == null || lead !in markets) markets else listOf(lead) + markets.filter { it != lead }

    /** "Mention Nifty first again" on [today]: the tally up to today no longer counts. */
    fun reset(today: LocalDate): Log = Log(today)

    // ---- asked -----------------------------------------------------------------------------------------------------

    enum class Request { WHICH, RESET }

    private fun norm(text: String) = Spaced.joined(text)

    private const val LEAD = "^ (hey |ok |okay )?(jarvis )?(so )?(please )?(can you |could you |would you )?(tell me )?"
    private const val TAIL = "( please| boss| jarvis| now| again| from now on| for me)* $"
    private const val INDEX = "(bank ?nifty|nifty bank|bnf|nifty)"
    private const val FIRST = "(first|before nifty|ahead of nifty|at the top)"

    private val WHICH = rx(LEAD + "(which|what) index do you (mention|say|name|give|read out|list) first" + TAIL + "|" +
        LEAD + "why do you (always )?(mention|say|name|give|start with|read out|list) $INDEX( first)?( in the greeting| in the morning check)?" + TAIL + "|" +
        LEAD + "why (is|was) $INDEX (mentioned |said |named |listed )?$FIRST" + TAIL + "|" +
        LEAD + "(which|what) index do i (ask|talk) (you )?about (the )?most" + TAIL + "|" +
        LEAD + "(which|what) index do i (ask|talk) (you )?(the )?most about" + TAIL + "|" +
        LEAD + "(kaunsa|kaun sa|konsa|kon sa) index pehle (bolte|batate|lete) ho" + TAIL + "|" +
        LEAD + "$INDEX pehle (kyun|kyu|kyon) (bolte|batate|lete) ho" + TAIL + "|" +
        LEAD + "(main|mai) (kaunsa|kaun sa|konsa|kon sa) index sabse (zyada|jyada) (poochta|puchta|puchhta) (hoon|hu|hun)" + TAIL)
    /**
     * Only a clear undo: "mention / say / name Nifty first again", "don't (or stop) saying BankNifty first", "go back to
     * naming Nifty first", "nifty pehle bolo phir se", "banknifty pehle mat bolo". Never a market question that merely
     * puts Nifty first - "nifty pehle batao", "nifty ko pehle lo", "give nifty first", "say nifty first" keep their route.
     */
    private val RESET = rx(LEAD + "(mention|say|name) nifty first (again|once again)" + TAIL + "|" +
        LEAD + "(dont|do not) (mention|say|name|list) (bank ?nifty|nifty bank|my index) first" + TAIL + "|" +
        LEAD + "stop (mentioning|saying|naming|listing) (bank ?nifty|nifty bank|my index) first" + TAIL + "|" +
        LEAD + "go back to (mentioning |saying |naming )?nifty first" + TAIL + "|" +
        LEAD + "nifty (ko )?pehle (bolo|bolna) (phir se|fir se|dobara)" + TAIL + "|" +
        LEAD + "(bank ?nifty|banknifty) pehle (mat|na) (bolo|bolna)" + TAIL)

    /** "Which index do you mention first?" or "mention Nifty first again", else null. */
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

    const val ONLY_ORDER = "Only the order I name them in changes: never a figure, which index I answer a question for, or anything that acts."

    /** "Which index do you mention first?". */
    fun say(r: Record?): String =
        if (r == null) "I name Nifty first, Boss, as always. If you keep asking about BankNifty by name - $MIN_TIMES times or more, on " +
            "$MIN_DAYS days, two in three of your mentions of either - I'll name it first in the greeting and the morning outlook."
        else "Boss, ${r.say()} in the last $WINDOW_DAYS days - so I name ${r.phrase} first where I give both: the greeting and the " +
            "morning outlook. $ONLY_ORDER Say \"$UNDO\" to undo it."

    /** "Mention Nifty first again". */
    fun sayReset(r: Record?): String =
        if (r == null) "I already name Nifty first, Boss. I'll start my count afresh from tomorrow."
        else "Done, Boss: Nifty first again, and my count of the index you ask about starts afresh from tomorrow."

    /** "Mention Nifty first again" on a locked phone: done all the same, in words that never name what was learned (or whether). */
    const val RESET_LOCKED = "Done, Boss: Nifty first where I name both, and my count starts afresh from tomorrow."

    /** The ledger's lines for [r]. */
    fun ledgerWhat(r: Record): String = "${r.phrase} named first where I give both indices (the greeting, the morning outlook)"
    fun ledgerWhy(r: Record): String = "${r.say()} in the last $WINDOW_DAYS days; only the order changes"
}
