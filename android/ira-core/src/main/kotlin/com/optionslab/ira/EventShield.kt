package com.optionslab.ira

import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * The event shield (TODO I6, 13 Oct 2026; research/EVENT_SHIELD.md): a warning the evening before and on the morning of
 * an event day - RBI policy, the Union Budget, a US CPI or Fed (FOMC) night, a heavyweight's results, or any event Boss
 * added - with how BANKNIFTY and NIFTY behaved on such days in 2024-26, each figure with its sample size. Advisory only.
 *
 * And Boss's optional switch "pause paper arms on event days" ([Mode]): OFF by default. SHADOW logs what it would have
 * skipped and skips nothing. ON skips a PAPER arm's NEW entry on an event day - never a live order, never an exit, never
 * Liquidity BANKNIFTY while it is frozen (the caller passes [frozen]). It can only lower risk. Pure.
 */
object EventShield {
    enum class Group(val words: String) {
        INDIA_POLICY("India policy (RBI / Budget)"), US_MACRO("US CPI / Fed night"), RESULTS("heavyweight results"), OTHER("event")
    }

    /** One group's record on one index, from research/EVENT_SHIELD.md (2024-26, the research-data release). */
    data class Record(
        val index: String, val group: Group, val sessions: Int, val rangePct: Double, val normalRangePct: Double,
        val gapPct: Double, val normalGapPct: Double, val significant: Boolean,
    ) {
        val rangeRatio: Double get() = rangePct / normalRangePct
    }

    const val SOURCE = "research/EVENT_SHIELD.md (13 Oct 2026; Feb 2024 - Apr 2026)"

    /** The research's medians (day range and |gap|, % of the previous close; normal days: no event, no expiry). */
    val RECORDS: List<Record> = listOf(
        Record("BANKNIFTY", Group.INDIA_POLICY, 15, 1.44, 0.98, 0.15, 0.23, significant = true),
        Record("BANKNIFTY", Group.US_MACRO, 39, 0.99, 0.98, 0.35, 0.23, significant = false),
        Record("BANKNIFTY", Group.RESULTS, 12, 1.11, 0.98, 0.39, 0.23, significant = false),
        Record("NIFTY", Group.INDIA_POLICY, 15, 1.06, 0.83, 0.11, 0.23, significant = false),
        Record("NIFTY", Group.US_MACRO, 38, 0.84, 0.83, 0.26, 0.23, significant = false),
        Record("NIFTY", Group.RESULTS, 16, 0.91, 0.83, 0.33, 0.23, significant = false),
    )

    /** The group of an event by its name ("RBI policy", "US Fed decision overnight (FOMC)", "HDFC Bank results", ...). */
    fun group(name: String): Group {
        val n = " " + name.uppercase(Locale.ENGLISH).replace(Regex("[^A-Z0-9 ]"), " ") + " "
        return when {
            " RBI " in n || "BUDGET" in n || " MPC " in n -> Group.INDIA_POLICY
            "FOMC" in n || " FED " in n || " CPI " in n -> Group.US_MACRO
            "RESULT" in n || "EARNINGS" in n -> Group.RESULTS
            else -> Group.OTHER
        }
    }

    /** An event that counts for the shield (expiries and index rebalances are not events here: they have their own cards). */
    fun counts(name: String): Boolean {
        val n = name.uppercase(Locale.ENGLISH)
        return !n.endsWith(" EXPIRY") && "EXPIRY MOVED" !in n && "REBALANCE" !in n
    }

    private fun p2(x: Double) = String.format(Locale.ENGLISH, "%.2f", x)
    private fun r1(x: Double) = String.format(Locale.ENGLISH, "%.1f", x)
    private fun day(d: LocalDate) = "${d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} ${d.dayOfMonth} " +
        d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)

    /** What such days looked like, one line per index ("BANKNIFTY moved 1.5x a normal day (...; 15 days)"). */
    fun record(g: Group): List<String> = RECORDS.filter { it.group == g }.map { r ->
        val what = when {
            g == Group.INDIA_POLICY && r.significant -> "moved ${r1(r.rangeRatio)}x a normal day (median range ${p2(r.rangePct)}% vs ${p2(r.normalRangePct)}%)"
            r.gapPct / r.normalGapPct >= 1.3 && r.rangeRatio < 1.2 -> "opened with a bigger gap (median ${p2(r.gapPct)}% vs ${p2(r.normalGapPct)}%) but the day's range was about normal (${r1(r.rangeRatio)}x)"
            else -> "moved about ${r1(r.rangeRatio)}x a normal day (median range ${p2(r.rangePct)}% vs ${p2(r.normalRangePct)}%)"
        }
        "${r.index} $what; ${r.sessions} such days" + if (r.significant) " (a tested difference)" else " (not a tested difference)"
    }

    /**
     * The warning for [events] (names) on [eventDay], said on [today]: the evening before ([today] is the session before) or
     * that morning. Null when nothing counts.
     */
    fun warning(events: List<String>, eventDay: LocalDate, today: LocalDate): String? {
        val es = events.filter(::counts).distinct()
        if (es.isEmpty()) return null
        val whenStr = if (eventDay == today) "Today" else "${day(eventDay)} (next session)"
        val groups = es.map(::group).distinct().filter { it != Group.OTHER }
        val facts = groups.flatMap(::record)
        return "$whenStr is an event day: ${es.joinToString(", ")}." +
            (if (facts.isEmpty()) "" else " On such days in 2024-26: " + facts.joinToString("; ") + ".") +
            " Context, not a forecast; sizes and stops are yours to set."
    }

    // ---- the optional pause (paper only, OFF by default) --------------------------------------------------------------

    enum class Mode(val words: String) {
        OFF("off"), SHADOW("shadow: logged only, nothing skipped"), ON("on: paper arms skip new entries on event days")
    }

    val DEFAULT_MODE = Mode.OFF

    fun mode(saved: String?): Mode = Mode.entries.firstOrNull { it.name == saved } ?: DEFAULT_MODE

    /** [block]: skip this new entry (paper, ON, an event day); [shadow]: what SHADOW would have done (logged only). */
    data class Verdict(val block: Boolean, val why: String? = null, val shadow: String? = null)

    val GO = Verdict(false)

    /**
     * A NEW entry by an arm: [live] true for a Zerodha entry (never paused here), [frozen] true for Liquidity BANKNIFTY while
     * frozen (untouched, not even logged), [events] today's counting event names. Never asked by an exit.
     */
    fun decide(mode: Mode, events: List<String>, live: Boolean, frozen: Boolean): Verdict {
        if (mode == Mode.OFF || live || frozen) return GO
        val es = events.filter(::counts)
        if (es.isEmpty()) return GO
        val why = "event day (${es.joinToString(", ")})"
        return if (mode == Mode.ON) Verdict(true, "paused: $why - your switch pauses paper arms on event days")
        else Verdict(false, shadow = "would pause: $why")
    }
}
