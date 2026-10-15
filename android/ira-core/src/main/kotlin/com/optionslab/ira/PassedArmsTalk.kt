package com.optionslab.ira

import com.optionslab.engine.orb.DonchianArm
import com.optionslab.engine.orb.OovArm
import com.optionslab.engine.orb.PassedArms
import com.optionslab.engine.orb.Solo2
import com.optionslab.engine.orb.Watchlist
import java.util.Locale

/**
 * Jarvis on the arms that PASSED research and on the shadow Watchlist (15 Oct 2026, Boss: "Both"): "how is the Donchian arm
 * doing", "what did open outside value do today", "how is Solo 2.0 doing", "what's on the watchlist", "how are the passed
 * arms doing", "solo 2.0 kaisa chal raha hai". Answered from the arms' own book and the Watchlist's log: each arm's switch
 * (paper only), its rules and signal source, today's trades, its record (the index the research said to report apart, apart),
 * its forward-test bar - always with "Passed research (R51/R52/R57) — paper only"; the Watchlist's scorecard always with
 * "shadow, not traded; information only, not a trade signal" (and C4 marked NON-DEFAULT: an option-chain signal). Read only:
 * a sentence with something to do ("switch off the donchian arm") is not read here, and nothing is switched, bought, sold or
 * promoted. Pure.
 */
object PassedArmsTalk {
    const val LOCKED = "Unlock the phone for that, Boss."

    enum class Topic { DONCHIAN, OOV, SOLO2, WATCHLIST, ALL }

    /** Words of doing: such a sentence is an action (or a setting), never this reader's question. */
    private val ACT = Regex(" (switch|switched|turn|band|chalu|enable|disable|set|change|buy|sell|close|cancel|square|start|stop|pause|resume|arm on|disarm|add|remove|delete|promote|undo) ")
    private val DONCHIAN = Regex(" (donchian|r51) ")
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
    )

    data class Facts(val arms: List<Arm>, val watch: List<String>, val watchOn: Boolean?, val modelLoaded: Boolean? = null)

    private fun rs(x: Double) = (if (x < 0) "−₹" else "+₹") + "%,.0f".format(Locale.ENGLISH, kotlin.math.abs(x))

    private fun label(source: String) = when (source) {
        DonchianArm.SOURCE -> DonchianArm.LABEL
        OovArm.SOURCE -> OovArm.LABEL
        else -> Solo2.LABEL
    }

    private fun rules(source: String) = when (source) {
        DonchianArm.SOURCE -> DonchianArm.RULES
        OovArm.SOURCE -> OovArm.RULES + ". " + OovArm.STOP_ADDED
        else -> Solo2.RULES
    }

    private fun record(source: String) = when (source) {
        DonchianArm.SOURCE -> DonchianArm.RECORD
        OovArm.SOURCE -> OovArm.RECORD
        else -> Solo2.RECORD
    }

    private fun apart(source: String) = when (source) {
        DonchianArm.SOURCE -> DonchianArm.REPORT_APART
        OovArm.SOURCE -> OovArm.REPORT_APART
        else -> Solo2.REPORT_APART
    }

    private fun bar(source: String) = if (source == Solo2.SOURCE) Solo2.bar() else PassedArms.ForwardBar.BAR

    /** One arm, plain: the label, the switch, the rules, today, the record, the bar. */
    fun armWords(a: Arm, full: Boolean = true): String = buildString {
        append("${label(a.source)}: ")
        append(when (a.on) { true -> "on, paper only"; false -> "off"; null -> "its switch could not be read just now" })
        a.pausedWhy?.let { append(" (it switched itself off: $it)") }
        append(". ${PassedArms.PASSED}. ")
        if (full) append(rules(a.source)).append(". ").append(PassedArms.SIGNAL_INDEX).append(". ")
        append(if (a.todayLines.isEmpty()) "No trade today." else "Today: " + a.todayLines.joinToString("; ") + ".")
        if (a.scores.isNotEmpty()) append(" Today's candidates: ").append(a.scores.joinToString("; ")).append(".")
        append(" Its own record: ")
        append(if (a.closed == 0) "no closed trade yet" else "${a.closed} trade${if (a.closed == 1) "" else "s"}, ${a.wins} won, ${rs(a.net)} after charges")
        append("; ${apart(a.source)} apart: ${a.apartTrades} trade${if (a.apartTrades == 1) "" else "s"}, ${rs(a.apartNet)}. ")
        append(a.forward).append(". ")
        if (full) append(bar(a.source)).append(". ").append(record(a.source)).append(".")
        if (full && a.source == Solo2.SOURCE) append(" ").append(Solo2.RETRAIN_TODO).append(".")
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
        return when (topic) {
            Topic.DONCHIAN -> arm(DonchianArm.SOURCE)?.let { armWords(it) } ?: "I could not read ${DonchianArm.LABEL}'s book just now, Boss."
            Topic.OOV -> arm(OovArm.SOURCE)?.let { armWords(it) } ?: "I could not read ${OovArm.LABEL}'s book just now, Boss."
            Topic.SOLO2 -> arm(Solo2.SOURCE)?.let { armWords(it) + if (f.modelLoaded == false) " Its model is not loaded yet, so it scores nothing until it is." else "" }
                ?: "I could not read ${Solo2.LABEL}'s book just now, Boss."
            Topic.WATCHLIST -> watchWords(f.watch, f.watchOn)
            Topic.ALL -> (f.arms.map { armWords(it, full = false) } + watchWords(f.watch, f.watchOn)).joinToString(" ")
        }
    }
}
