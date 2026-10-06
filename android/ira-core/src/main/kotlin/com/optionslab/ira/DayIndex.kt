package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * Jarvis learning which index Boss follows on which weekday (learning round 30, 2026-10-06) - "BankNifty on Wednesdays" -
 * from what the app already keeps: the daily tally of question kinds ([SelfDoubt.count], kind keys and counts only -
 * "market:BANKNIFTY" - never a word). Nothing new is recorded; only the day Boss last asked to undo it is kept ([Log]).
 *
 * For each weekday (Monday to Friday) on its own: of the mentions of Nifty or BankNifty (a question naming both counts once
 * for each) on that weekday in the last [WINDOW_DAYS] days (after the reset day), an index other than Nifty named at least
 * [MIN_TIMES] times, on at least [MIN_DAYS] of those weekdays, and in at least [SHARE] of them is learned for that weekday
 * ([learnedAll]). On that weekday, a bare "how's the market?" / "market kaisa hai?" (the trade check's market read) then
 * gives that index's read first ([leadReads]) - only the order of the index lines changes: never a word, a figure, the
 * verdict, its reasons, which index a question is answered for ([UsualIndex]'s), the greeting's order ([LeadIndex]'s), an
 * order, an arm, a setting or a reminder. Nothing learned acts or places anything. Never on a locked phone (his habit is
 * not shown there: Nifty first, as always), never in IraGoldAlgo.
 *
 * A weekday habit, where [LeadIndex] is the habit across all days: BankNifty on Wednesdays alone never reaches LeadIndex's
 * share, and is learned here for Wednesdays only. The tally keeps days, not hours, so it is by weekday, not time of day.
 *
 * "Which index do you lead with on Wednesdays?" / "why did you start with BankNifty today?" names it and why
 * ([Request.WHICH]; his habit, so on an unlocked phone only); "lead with Nifty every day again" / "stop leading with BankNifty
 * on Wednesdays" ([UNDO], [Request.RESET]) undoes it and starts the count afresh from the next day - a habit's undo, never a
 * STOP of an arm ([Commands]' habit undo). Shown in the Learnings ledger with that undo, and reset by "undo everything you
 * learned this week". Pure: the app keeps the tally and the reset day.
 */
object DayIndex {
    /** Only the last this many days count (the tally keeps no more): four or five of each weekday. */
    const val WINDOW_DAYS = SelfDoubt.WINDOW_DAYS
    /** The index is named at least this many times on that weekday (mentions, not questions)... */
    const val MIN_TIMES = 6
    /** ...on at least this many of those weekdays... */
    const val MIN_DAYS = 3
    /** ...and in at least this share of that weekday's mentions of Nifty or BankNifty. */
    const val SHARE = 2.0 / 3.0

    const val UNDO = "lead with Nifty every day again"
    const val LOCKED = "Unlock the phone for that, Boss."

    /** The two indices the market read gives (Nifty first, unless learned otherwise for the day). */
    val INDICES = listOf(Market.NIFTY, Market.BANKNIFTY)

    /** The weekdays learned for: the market's own (a weekend tally, if any, is never read). */
    val WEEKDAYS = listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)

    /** The day Boss last asked to undo it: the tally's days up to it no longer count. */
    data class Log(val resetOn: LocalDate? = null)

    /** "Wednesdays". */
    fun days(d: DayOfWeek): String = d.getDisplayName(TextStyle.FULL, Locale.ENGLISH) + "s"

    /** The index learned for [day]: named [times] times on [days] of them, Nifty [others] times, the newest on [newest]. */
    data class Record(val day: DayOfWeek, val market: Market, val times: Int, val others: Int, val days: Int, val newest: LocalDate) {
        val phrase: String get() = "${market.label} on ${days(day)}"
        /** Counted by mention (a question naming both indices counts for each), so "mentions", never "questions". */
        fun say(): String = "on ${days(day)}, ${market.label} was $times of your ${times + others} mentions of Nifty or BankNifty, on $days ${days(day)}"
    }

    private fun key(m: Market) = "market:${m.name}"

    /** Every weekday's learned index at [today] from [tally] ([SelfDoubt]'s), Monday first; empty: Nifty first every day. */
    fun learnedAll(tally: DoubtTally, log: Log, today: LocalDate): List<Record> {
        val reset = log.resetOn
        val inWindow = tally.filterKeys { d -> !d.isAfter(today) && d.isAfter(today.minusDays(WINDOW_DAYS)) && (reset == null || d.isAfter(reset)) }
        return WEEKDAYS.mapNotNull { wd ->
            val days = inWindow.filterKeys { it.dayOfWeek == wd }
            fun times(m: Market) = days.values.sumOf { maxOf(0, it[key(m)] ?: 0) }
            val total = INDICES.sumOf { times(it) }
            if (total <= 0) return@mapNotNull null
            val m = INDICES.filter { it != Market.NIFTY }.maxByOrNull { times(it) } ?: return@mapNotNull null
            val mine = times(m)
            val on = days.filter { (it.value[key(m)] ?: 0) > 0 }.keys
            if (mine < MIN_TIMES || on.size < MIN_DAYS || mine < SHARE * total) null
            else Record(wd, m, mine, total - mine, on.size, on.max())
        }
    }

    /** The index learned for [today]'s weekday, or null (Nifty first, as always). */
    fun learned(tally: DoubtTally, log: Log, today: LocalDate): Record? = learnedAll(tally, log, today).firstOrNull { it.day == today.dayOfWeek }

    /** The index the market read leads with today, or null: never on a [locked] phone (his habit is not shown there). */
    fun lead(tally: DoubtTally, log: Log, today: LocalDate, locked: Boolean): Market? = if (locked) null else learned(tally, log, today)?.market

    /**
     * The market read's lines [reads] ("BankNifty is bullish now ...", one an index, [TradeCheck.read]) with [lead]'s line
     * first and the rest in their order - the very same lines, only reordered; as they are when [lead] is null or has none.
     */
    fun leadReads(reads: List<String>, lead: Market?): List<String> {
        if (lead == null) return reads
        val head = "${lead.label} is "
        val first = reads.filter { it.startsWith(head) }
        return if (first.isEmpty()) reads else first + reads.filter { !it.startsWith(head) }
    }

    /** "Lead with Nifty every day again" on [today]: the tally up to today no longer counts. */
    fun reset(today: LocalDate): Log = Log(today)

    // ---- asked -----------------------------------------------------------------------------------------------------

    enum class Request { WHICH, RESET }

    private fun norm(text: String) = " " + spacedWords(text.lowercase().replace("'", "").replace("’", "")) + " "

    private const val LEAD = "^ (hey |ok |okay )?(jarvis )?(so )?(please )?(can you |could you |would you )?(tell me )?"
    private const val TAIL = "( please| boss| jarvis| now| again| from now on| for me)* $"
    private const val INDEX = "(bank ?nifty|nifty bank|bnf|nifty)"
    private const val BANK = "(bank ?nifty|nifty bank|bnf)"
    private const val DAY = "(mondays?|tuesdays?|wednesdays?|thursdays?|fridays?)"
    /** A weekday named, today, or the days in general: what makes it this habit, never [LeadIndex]'s across all days. */
    private const val ON_DAY = "(on $DAY|today|on (which|what|some|certain|different) days?|(by|for) (the )?(day|days|weekday|weekdays|day of the week)|each day|day by day|depending on the day)"
    private const val HDAY = "(somvar|mangalvar|budhvar|guruvar|shukravar|monday|tuesday|wednesday|thursday|friday)"

    private val WHICH = rx(LEAD + "(which|what) index do you (lead with|start the market read with|start the market with|open the market read with|open with)( $ON_DAY)?" + TAIL + "|" +
        LEAD + "(which|what) index do you (start with|put first|give first|name first|mention first|say first) $ON_DAY" + TAIL + "|" +
        LEAD + "why (do|did) you (always )?(lead with|start the market read with|start the market with|open the market read with|open with) $INDEX( first)?( $ON_DAY)?" + TAIL + "|" +
        LEAD + "why did you (start|begin) with $INDEX( first)?( $ON_DAY)?" + TAIL + "|" +
        LEAD + "why do you (start|begin) with $INDEX( first)? $ON_DAY" + TAIL + "|" +
        LEAD + "why (is|was) $INDEX (first|on top|at the top|leading) $ON_DAY" + TAIL + "|" +
        LEAD + "(which|what) index do i (ask|talk) (you )?about (the )?(most )?$ON_DAY" + TAIL + "|" +
        LEAD + "(kis|kaun se|konse|kon se) din (kaunsa|kaun sa|konsa|kon sa) index pehle( (bolte|batate|lete) ho)?" + TAIL + "|" +
        LEAD + "(aaj|$HDAY ko) $INDEX pehle (kyun|kyu|kyon)( (bola|bataya|liya|bolte ho|batate ho|lete ho))?" + TAIL)

    /**
     * Only a clear undo: "lead with Nifty every day again", "stop leading with BankNifty on Wednesdays", "stop changing the
     * index by day", "don't start with BankNifty on Wednesdays", "har din nifty pehle lo". Never [LeadIndex]'s ("mention Nifty
     * first again") nor a market question that merely puts an index first.
     */
    private val RESET = rx(LEAD + "(lead|open) with nifty (every day|on every day|on all days|each day|daily|always|whatever the day)( again| once again)?" + TAIL + "|" +
        LEAD + "(stop|dont|do not) (leading|lead|opening|open) with $BANK( first)?( $ON_DAY)?" + TAIL + "|" +
        LEAD + "(stop|dont|do not) (starting|start|beginning|begin) with $BANK( first)? $ON_DAY" + TAIL + "|" +
        LEAD + "stop (changing|switching) (the |my )?(index|indices|index order|order of the indices|lead index)( you lead with| i lead with)? $ON_DAY" + TAIL + "|" +
        LEAD + "forget (which|what) index i (ask|talk) (you )?about (the )?(most )?$ON_DAY" + TAIL + "|" +
        LEAD + "(har din|roz|rozana) nifty (ko )?pehle (lo|bolo|batao|rakho)( phir se| fir se| dobara)?" + TAIL + "|" +
        LEAD + "(aaj|$HDAY ko) $BANK (ko )?pehle (mat|na) (lo|bolo|batao|rakho)" + TAIL + "|" +
        LEAD + "$BANK (ko )?pehle (mat|na) (lo|bolo|batao|rakho) (aaj|$HDAY ko)" + TAIL)

    /** "Which index do you lead with on Wednesdays?" or "lead with Nifty every day again", else null. */
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

    const val ONLY_ORDER = "Only the order of the index lines changes: never a figure, the verdict, which index I answer a question for, or anything that acts."

    private fun dayList(rs: List<Record>): String {
        val names = rs.map { days(it.day) }
        return if (names.size <= 1) names.joinToString("") else names.dropLast(1).joinToString(", ") + " and " + names.last()
    }

    /** "Which index do you lead with on Wednesdays?" ([today]: whether today is one of them). */
    fun say(rs: List<Record>, today: LocalDate): String =
        if (rs.isEmpty()) "I lead \"how's the market\" with Nifty every day, Boss. If on one weekday you keep naming BankNifty - $MIN_TIMES " +
            "times or more, on $MIN_DAYS of those days, two in three of your mentions of either - I'll give its read first on that weekday."
        else "Boss, " + rs.joinToString("; ") { it.say() } + " in the last $WINDOW_DAYS days - so on ${dayList(rs)} \"how's the market\" " +
            "gives " + (rs.map { it.market.label }.distinct().singleOrNull() ?: "that index") + "'s read first" +
            (if (rs.any { it.day == today.dayOfWeek }) ", today among them" else "") + ". $ONLY_ORDER Say \"$UNDO\" to undo it."

    /** "Lead with Nifty every day again". */
    fun sayReset(rs: List<Record>): String =
        if (rs.isEmpty()) "I already lead with Nifty every day, Boss. I'll start my count afresh from tomorrow."
        else "Done, Boss: Nifty first every day again, and my count of the index you follow on each weekday starts afresh from tomorrow."

    /** "Lead with Nifty every day again" on a locked phone: done all the same, in words that never name what was learned (or whether). */
    const val RESET_LOCKED = "Done, Boss: Nifty first every day in the market read, and my count starts afresh from tomorrow."

    /** The ledger's lines for [r]. */
    fun ledgerWhat(r: Record): String = "${r.market.label}'s read first in \"how's the market\" on ${days(r.day)}"
    fun ledgerWhy(r: Record): String = "${r.say()} in the last $WINDOW_DAYS days; only the order changes"
}
