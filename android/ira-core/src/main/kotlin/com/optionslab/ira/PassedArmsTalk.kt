package com.optionslab.ira

import com.optionslab.engine.orb.BreadthDonchianArm
import com.optionslab.engine.orb.DonchianArm
import com.optionslab.engine.orb.OovArm
import com.optionslab.engine.orb.PassedArms
import com.optionslab.engine.orb.Solo2
import com.optionslab.engine.orb.Watchlist
import java.util.Locale

/**
 * Jarvis on the arms that PASSED research and on the shadow Watchlist (15 Oct 2026, Boss: "Both"): "how is the Donchian arm
 * doing", "what did open outside value do today", "how is Solo 2.0 doing", "what's on the watchlist", "how are the passed
 * arms doing", "solo 2.0 kaisa chal raha hai", and (16 Oct) "how is breadth donchian doing" - with Boss's comparison of R72
 * Breadth Donchian and R51 Donchian 20 on the same days, and R72's risk line. Answered from the arms' own book and the Watchlist's log: each arm's switch
 * (paper only), its rules and signal source, today's trades, its record (the index the research said to report apart, apart),
 * its forward-test bar - always with [PassedArms.PASSED]; the Watchlist's scorecard always with
 * "shadow, not traded; information only, not a trade signal" (and C4 marked NON-DEFAULT: an option-chain signal). Read only:
 * a sentence with something to do ("switch off the donchian arm") is not read here, and nothing is switched, bought, sold or
 * promoted. Pure.
 */
object PassedArmsTalk {
    const val LOCKED = "Unlock the phone for that, Boss."

    enum class Topic { DONCHIAN, OOV, SOLO2, WATCHLIST, ALL, BREADTH }

    /** Words of doing: such a sentence is an action (or a setting), never this reader's question. */
    private val ACT = Regex(" (switch|switched|turn|band|chalu|enable|disable|set|change|buy|sell|close|cancel|square|start|stop|pause|resume|arm on|disarm|add|remove|delete|promote|undo) ")
    private val DONCHIAN = Regex(" (donchian|r51) ")
    /** 16 Oct: R72 Breadth Donchian ("breadth donchian", "breadth arm", "r72 arm"). */
    private val BREADTH = Regex(" (breadth donchian|breadth arm|breadth paper|r72 arm|r72 paper|r72 breadth) ")
    private val DON_CONTEXT = Regex(" (arm|arms|r51|paper|trade|trades|doing|today|record|kaisa|kya|forward|test|bot) ")
    private val CHANNEL = Regex(" (channel|channels|indicator|formula|explain|meaning) ")
    private val OOV = Regex(" (open outside value|outside value|oov|r52 arm|r52 paper) ")
    private val SOLO2 = Regex(" (solo 2 0|solo 2|solo two|solo2|second solo|new solo|solo version 2) ")
    private val WATCH = Regex(" (watchlist|watch list|near miss|near misses|shadow rules|shadow watchlist) ")
    /** Boss's own market watchlist ("what is in my watchlist") is the market's, not this shadow list. */
    private val MINE = Regex(" (my|mera|meri) (watchlist|watch list) ")
    private val ALL = Regex(" (passed arms|research arms|arms that passed|passed research arms|passed the research|new paper arms) ")

    /** The topic asked about, or null. */
    fun asked(text: String): Topic? {
        val t = Spaced.joined(text)
        if (ACT.containsMatchIn(t)) return null
        return when {
            SOLO2.containsMatchIn(t) -> Topic.SOLO2
            OOV.containsMatchIn(t) -> Topic.OOV
            BREADTH.containsMatchIn(t) -> Topic.BREADTH
            DONCHIAN.containsMatchIn(t) && DON_CONTEXT.containsMatchIn(t) && !CHANNEL.containsMatchIn(t) -> Topic.DONCHIAN
            WATCH.containsMatchIn(t) && !MINE.containsMatchIn(t) -> Topic.WATCHLIST
            ALL.containsMatchIn(t) -> Topic.ALL
            else -> null
        }
    }

    /** One trade as said: [line] (contract, prices, why, rupees). */
    data class Arm(
        val source: String, val on: Boolean?, val todayLines: List<String>, val closed: Int, val net: Double, val wins: Int,
        /** The index the research said to report apart: its closed trades and net. */
        val apartTrades: Int, val apartNet: Double,
        /** "Forward test: x of 40 ..." (or Solo 2.0's 60-trade line). */
        val forward: String,
        /** Why it switched itself off (null: it did not). */
        val pausedWhy: String? = null,
        /** Solo 2.0: today's scored candidates in words. */
        val scores: List<String> = emptyList(),
        /** 16 Oct (R73 review): R51 Donchian 20's Supertrend filter is on for new breaks (false for the other arms). */
        val stOn: Boolean = false,
        /** 16 Oct (R73 review): today's breaks the filter skipped because Supertrend could not judge them (no earlier session in). */
        val stUnjudged: Int = 0,
        /**
         * 18 Oct (review D6): its live copy's records - the SHADOW record and the LIVE (real orders) record, labelled apart from this
         * paper book - and its Trade live / Shadow switches, in words; null when not read.
         */
        val live: String? = null,
        /** 19 Oct (Boss: "Yes, keep only breadth donchain"): set to record only - would-be trades, nothing bought ([on] is false). */
        val recordOnly: Boolean = false,
        /** 19 Oct: of [closed], the would-be trades (never bought: record only, the twin rule or the same-direction cap), and their net. */
        val wouldBe: Int = 0, val wouldBeNet: Double = 0.0,
    )

    /** 19 Oct: a passed arm set to record only, in words (its row, the diagnostics, Jarvis). */
    const val RECORD_ONLY_WORDS = "off - runs in shadow (would-be trades, nothing bought)"

    data class Facts(val arms: List<Arm>, val watch: List<String>, val watchOn: Boolean?, val modelLoaded: Boolean? = null,
                     /** 16 Oct: the premium exits NEW trades enter with (the 30-60-100 lock, R66's +5% once +25%), in words; null: unknown. */
                     val exits: String? = null,
                     /** 16 Oct: R72 Breadth Donchian vs R51 Donchian 20 on the same days, in words; null: unknown. */
                     val comparison: String? = null)

    private fun rs(x: Double) = (if (x < 0) "−₹" else "+₹") + "%,.0f".format(Locale.ENGLISH, kotlin.math.abs(x))

    private fun label(source: String) = when (source) {
        DonchianArm.SOURCE -> DonchianArm.LABEL
        BreadthDonchianArm.SOURCE -> BreadthDonchianArm.LABEL
        OovArm.SOURCE -> OovArm.LABEL
        else -> Solo2.LABEL
    }

    private fun rules(source: String, stOn: Boolean = false) = when (source) {
        DonchianArm.SOURCE -> DonchianArm.rules(stOn)
        BreadthDonchianArm.SOURCE -> BreadthDonchianArm.RULES + ". " + BreadthDonchianArm.RISK
        OovArm.SOURCE -> OovArm.RULES + ". " + OovArm.STOP_ADDED
        else -> Solo2.RULES
    }

    private fun record(source: String) = when (source) {
        DonchianArm.SOURCE -> DonchianArm.RECORD
        BreadthDonchianArm.SOURCE -> BreadthDonchianArm.RECORD
        OovArm.SOURCE -> OovArm.RECORD
        else -> Solo2.RECORD
    }

    private fun apart(source: String) = when (source) {
        DonchianArm.SOURCE -> DonchianArm.REPORT_APART
        BreadthDonchianArm.SOURCE -> BreadthDonchianArm.REPORT_APART
        OovArm.SOURCE -> OovArm.REPORT_APART
        else -> Solo2.REPORT_APART
    }

    private fun bar(source: String) = if (source == Solo2.SOURCE) Solo2.bar() else PassedArms.ForwardBar.BAR

    /** R66's L25 step in words (16 Oct, Boss's choice): what the popup, the notices and the rows say. */
    const val L25_WORDS = "+5% locked once +25%"

    /** The premium ladder in words: with L25 ([l25]) its first step, then R64's; locks only rise. */
    fun ladderWords(l25: Boolean): String =
        if (l25) "$L25_WORDS, breakeven once +30% (never below the +5%), +30% once +60%, +60% once +100%"
        else "breakeven locked once +30%, +30% once +60%, +60% once +100%"

    /**
     * 16 Oct: the premium exits NEW trades of the three arms enter with, from the book's switches: R64's 30-60-100 lock ([lockOn])
     * and R66's +5% once +25% on top of it ([l25On]; it acts only with the lock). Open trades keep the exits they entered with.
     */
    fun exitsWords(lockOn: Boolean, l25On: Boolean): String = when {
        lockOn && l25On -> "New trades' exits: own stop and time exit, no fixed target; ${ladderWords(true)} (the 30-60-100 lock with R66's " +
            "+5% step, your choice; a small improvement in research, not proven; paper only). Solo 2.0's S1 keeps its index exits with this " +
            "ladder beside them. Open trades keep the exits they entered with"
        lockOn -> "New trades' exits: own stop and time exit, no fixed target; ${ladderWords(false)} (the 30-60-100 lock, your choice; " +
            "paper only). Open trades keep the exits they entered with"
        else -> "New trades' exits: the research's own stop, target and time exit" +
            (if (l25On) " (the +5% once +25% step is on, but it acts only with the 30-60-100 lock, which is off)" else "") +
            ". Open trades keep the exits they entered with"
    }

    /** One arm, plain: the label, the switch, the rules, today, the record, the bar. */
    fun armWords(a: Arm, full: Boolean = true): String = buildString {
        append("${label(a.source)}: ")
        append(when (a.on) { true -> "on (paper book; live through your live gates)"; false -> RECORD_ONLY_WORDS; null -> "its switch could not be read just now" })
        a.pausedWhy?.let { append(" (it switched itself off: $it)") }
        append(". ${PassedArms.PASSED}. ")
        // 16 Oct (R73 review): R51's rules with its Supertrend filter when on; the short answer still says the filter is on, and
        // both say the breaks it could not judge today.
        if (full) append(rules(a.source, a.stOn)).append(". ").append(PassedArms.SIGNAL_INDEX).append(". ")
        else if (a.stOn && a.source == DonchianArm.SOURCE) append("Its Supertrend (10, 3) filter is on for new breaks (paper only). ")
        if (a.stOn && a.source == DonchianArm.SOURCE && a.stUnjudged > 0)
            append("Today Supertrend could not judge ${a.stUnjudged} break${if (a.stUnjudged == 1) "" else "s"} (no earlier session in), so ${if (a.stUnjudged == 1) "it was" else "they were"} not taken. ")
        append(if (a.todayLines.isEmpty()) "No trade today." else "Today: " + a.todayLines.joinToString("; ") + ".")
        if (a.scores.isNotEmpty()) append(" Today's candidates: ").append(a.scores.joinToString("; ")).append(".")
        append(" Its own record: ")
        append(if (a.closed == 0) "no closed trade yet" else "${a.closed} trade${if (a.closed == 1) "" else "s"}, ${a.wins} won, ${rs(a.net)} after charges")
        // 19 Oct: the would-be trades (nothing bought) said apart within that record.
        if (a.wouldBe > 0) append(" (of them ${a.wouldBe} would-be, never bought: ${rs(a.wouldBeNet)})")
        append("; ${apart(a.source)} apart: ${a.apartTrades} trade${if (a.apartTrades == 1) "" else "s"}, ${rs(a.apartNet)}. ")
        append(a.forward).append(". ")
        if (full) append(bar(a.source)).append(". ").append(record(a.source)).append(".")
        if (full && a.stOn && a.source == DonchianArm.SOURCE) append(" ").append(DonchianArm.ST_RECORD).append(".")
        if (full && a.source == Solo2.SOURCE) append(" ").append(Solo2.RETRAIN_TODO).append(".")
        a.live?.let { append(" Its live copy, apart from this paper record: ").append(it) }
    }

    /** The Watchlist: the note, the written rule, each rule's scorecard (C4 marked NON-DEFAULT), what was left out. */
    fun watchWords(lines: List<String>, on: Boolean?): String = buildString {
        append("The Watchlist (${Watchlist.NOTE})")
        if (on == false) append(", switched off")
        append(": ")
        append(Watchlist.Rule.entries.joinToString("; ") { r -> "${r.label} (${r.research}, ${r.tag})" })
        append(". ")
        append(if (lines.isEmpty()) "No shadow trade yet." else lines.joinToString("; ") + ".")
        append(" ${Watchlist.SELECTION} ${Watchlist.LEFT_OUT}")
    }

    /** The answer to [topic] from [f]. */
    fun answer(topic: Topic, f: Facts): String {
        fun arm(src: String) = f.arms.firstOrNull { it.source == src }
        // 16 Oct: the exits new trades enter with (the lock and R66's +5% step), said once after the arm's own words.
        val exits = f.exits?.let { " $it." } ?: ""
        return when (topic) {
            Topic.DONCHIAN -> arm(DonchianArm.SOURCE)?.let { armWords(it) + exits } ?: "I could not read ${DonchianArm.LABEL}'s book just now, Boss."
            Topic.OOV -> arm(OovArm.SOURCE)?.let { armWords(it) + exits } ?: "I could not read ${OovArm.LABEL}'s book just now, Boss."
            Topic.BREADTH -> arm(BreadthDonchianArm.SOURCE)?.let { armWords(it) + exits + (f.comparison?.let { c -> " $c." } ?: "") }
                ?: "I could not read ${BreadthDonchianArm.LABEL}'s book just now, Boss."
            Topic.SOLO2 -> arm(Solo2.SOURCE)?.let { armWords(it) + exits + if (f.modelLoaded == false) " Its model is not loaded yet, so it scores nothing until it is." else "" }
                ?: "I could not read ${Solo2.LABEL}'s book just now, Boss."
            Topic.WATCHLIST -> watchWords(f.watch, f.watchOn)
            Topic.ALL -> (f.arms.map { armWords(it, full = false) } + listOfNotNull(f.exits?.let { "$it." }, f.comparison?.let { "$it." }) +
                watchWords(f.watch, f.watchOn)).joinToString(" ")
        }
    }
}
