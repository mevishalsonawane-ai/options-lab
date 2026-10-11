package com.optionslab.ira

import com.optionslab.engine.NseHours
import java.time.LocalDate
import java.util.Locale
import kotlin.math.floor

/**
 * The rules of the paper arm that hosts approved forge ideas (S2, 11 Oct: "when Boss approves 'start on paper' it must
 * actually trade on the PAPER account"). The app's [com.optionslab.app.data.GrammarArm] reads the market and the paper book;
 * every decision is made here, pure and tested:
 *
 *  - [hostRefusal]: only an idea Boss approved for paper ([StrategyForge.Stage.PAPER]) with valid rules, at most [MAX_HOSTED]
 *    at once, never one he stopped (a stopped idea comes back only by his hand).
 *  - [signal]: the grammar's own entry ([ShadowSim.run]: the same signal code the forge backtested) on today's finished
 *    index minutes, acted on only at the bar that just closed (a watch [LATE_MIN] minutes late lets it go).
 *  - [entryRefusal]: the kill switch, the bot's stop for today, the account day lock, F&O's hours ([NseHours.foOpen]), the
 *    entry cut-off (the rules' own last entry, never after the account's), and no entry on expiry day from the expiry
 *    square-off's minute ([EXPIRY_EXIT]).
 *  - [exit]: the grammar's exits on the held option's finished minutes (stop / target on the wick, the no-progress exit,
 *    the time stop, the index-level exit), its square-off, never past F&O's close, and on expiry day by [EXPIRY_EXIT].
 *  - [judge]: each idea's paper record against its forward test: worse than it (the mSPRT's always-valid p, sign -1, at
 *    [STOP_ALPHA] after [MIN_JUDGE] trades) or a fall beyond the capital rule ([StrategyForge.MAX_DD]) stops it - stopping
 *    only lowers risk.
 *
 * 1 lot, buying at the money or one strike in the money as the grammar says. Nothing here places an order. No clock, no I/O.
 */
object GrammarPaper {
    const val MAX_HOSTED = 3
    /** The trade manager's family for these trades (unknown to its list: SHADOW, records only). */
    const val FAMILY = "forge"
    /** A decision more than this many minutes old is let go (the watch was not in time). */
    const val LATE_MIN = 3
    /** The expiry square-off's minute (ExpirySquareOff, 15:05): a held option expiring today is out by then. */
    const val EXPIRY_EXIT = 15 * 60 + 5
    /** Exits are done this many minutes before F&O's close at the latest. */
    const val CLOSE_MARGIN = 10
    const val STOP_ALPHA = 0.05
    const val MIN_JUDGE = 5
    /** The rupees-per-trade sd floor of the sequential test (as the forge's forward test). */
    const val MIN_SD = 100.0

    // ---- hosting ------------------------------------------------------------------------------------------------------------

    /** Why [rec] cannot be hosted now ([active]: the ids on paper now, [stopped]: the ids Boss or the record stopped), else null. */
    fun hostRefusal(rec: StrategyForge.Record?, active: Collection<String>, stopped: Collection<String> = emptyList()): String? = when {
        rec == null -> "no such idea"
        rec.stage != StrategyForge.Stage.PAPER -> "only an idea you approved for paper is hosted (${rec.c.id} is ${rec.stage.words})"
        active.any { it.equals(rec.c.id, ignoreCase = true) } -> "${rec.c.id} is already on paper"
        stopped.any { it.equals(rec.c.id, ignoreCase = true) } -> "${rec.c.id} was stopped; it is not started again by itself"
        active.size >= MAX_HOSTED -> "$MAX_HOSTED ideas are already on paper: stop one first"
        rec.c.rules.invalid() != null -> "its rules are not valid (${rec.c.rules.invalid()})"
        else -> null
    }

    /** What it is expected to make a trade: its forward record's mean (else its backtest's), with how many trades say so. */
    fun expectation(rec: StrategyForge.Record): Pair<Double, Int> = when {
        rec.fwd.isNotEmpty() -> rec.fwd.average() to rec.fwd.size
        rec.bt != null && rec.bt.trades > 0 -> rec.bt.perTrade to rec.bt.trades
        else -> 0.0 to 0
    }

    // ---- entries ------------------------------------------------------------------------------------------------------------

    /** An entry: +1 call / -1 put, the decision minute (the bar's close) and the index's close then. */
    data class Signal(val dir: Int, val decisionMin: Int, val indexClose: Double)

    /**
     * The grammar's entry at [nowMin] on [d] (today's FINISHED index minutes; no option legs - the entry is the index's), or
     * null: [takenToday] entries already (its [ShadowSim.Rules.maxTrades] a day), a decision at or before [lastDecisionMin]
     * already acted on, or one more than [LATE_MIN] minutes old. The signal is [ShadowSim.run]'s own (the code the forge
     * backtested); a minute at [nowMin] is added at the last close so a decision of this minute can be read.
     */
    fun signal(r: ShadowSim.Rules, d: ShadowSim.Day, nowMin: Int, takenToday: Int, lastDecisionMin: Int): Signal? {
        if (takenToday >= r.maxTrades || d.index.size == 0) return null
        val ix = d.index
        val last = ix.minutes[ix.size - 1]
        val live = if (last >= nowMin) d else {
            fun plus(a: DoubleArray, x: Double) = a.copyOf(a.size + 1).also { it[a.size] = x }
            val c = ix.c[ix.size - 1]
            val s = ShadowSim.Series(ix.minutes.copyOf(ix.size + 1).also { it[ix.size] = nowMin }, plus(ix.o, c), plus(ix.h, c), plus(ix.l, c), plus(ix.c, c))
            ShadowSim.Day(d.date, d.underlying, d.lot, d.expiryDay, s, emptyList(), d.prevHigh, d.prevLow, d.prevClose, d.vix, d.busy, d.strikeStep)
        }
        val t = ShadowSim.run(r, live).lastOrNull { it.entryMin in (lastDecisionMin + 1)..nowMin } ?: return null
        if (nowMin - t.entryMin > LATE_MIN) return null
        val i = ix.atOrBefore(t.entryMin - 1)
        if (i < 0) return null
        return Signal(t.dir, t.entryMin, ix.c[i])
    }

    /** The strike the grammar buys: at the money, or [itm] strikes in the money for [dir] (+1 call, -1 put). */
    fun strike(index: Double, step: Int, dir: Int, itm: Int): Double {
        val atm = floor(index / step + 0.5) * step
        return atm - dir * itm * step
    }

    /** The last minute a new entry may be decided: the rules' own, never after the account's cut-off [accountCutoff]. */
    fun cutoff(r: ShadowSim.Rules, accountCutoff: Int?): Int = minOf(r.untilMin, accountCutoff ?: r.untilMin)

    /** Why no new entry now (null: it may go). Exits never ask this. */
    fun entryRefusal(day: LocalDate, nowMin: Int, r: ShadowSim.Rules, accountCutoff: Int?, kill: Boolean, stoppedToday: Boolean,
                     dayLock: String?, expiryToday: Boolean): String? = when {
        kill -> "kill_switch"
        stoppedToday -> "stopped_today"
        dayLock != null -> "day_lock ($dayLock)"
        !NseHours.foOpen(day, nowMin) -> "fo_closed"
        nowMin > cutoff(r, accountCutoff) -> "after_cutoff ${ShadowSim.hhmm(cutoff(r, accountCutoff))}"
        expiryToday && nowMin >= EXPIRY_EXIT -> "expiry_day_after_1505"
        else -> null
    }

    // ---- exits --------------------------------------------------------------------------------------------------------------

    /**
     * A held option: its fill [entry], the minute it was decided, +1 call / -1 put, the index then, and whether it expires today.
     * [fromMin]: the first minute walked for its exits - the first that started at or after the fill ([firstAfterFill]), so a
     * low printed before the order filled is never read as its stop (the resting stop in the paper book covers the rest of the
     * fill's own minute).
     */
    data class Held(val entry: Double, val entryMin: Int, val dir: Int, val indexAtEntry: Double, val expiryToday: Boolean,
                    val fromMin: Int = entryMin)

    /** The first whole minute after a fill at [fill] (the fill's own minute when it filled on its first second). */
    fun firstAfterFill(fill: java.time.LocalTime): Int =
        fill.hour * 60 + fill.minute + if (fill.second > 0 || fill.nano > 0) 1 else 0

    /** One finished minute of the held option. */
    data class Minute(val minute: Int, val high: Double, val low: Double, val close: Double)

    /** The minute it is out by: its square-off, F&O's close less [CLOSE_MARGIN], and on expiry day [EXPIRY_EXIT]. */
    fun squareOffMin(r: ShadowSim.Rules, day: LocalDate, expiryToday: Boolean): Int =
        minOf(r.squareOff, NseHours.foClose(day) - CLOSE_MARGIN, if (expiryToday) EXPIRY_EXIT else Int.MAX_VALUE)

    /** The stop and target on the premium, from the fill (as the forge's walk: [ShadowSim.Rules] stopPct / stopPts, target...). */
    fun levels(r: ShadowSim.Rules, entry: Double): Pair<Double, Double> {
        val stop = maxOf(r.stopPct?.let { entry * (1 - it) } ?: Double.NEGATIVE_INFINITY, r.stopPts?.let { entry - it } ?: Double.NEGATIVE_INFINITY)
        val target = minOf(r.targetPct?.let { entry * (1 + it) } ?: Double.POSITIVE_INFINITY, r.targetPts?.let { entry + it } ?: Double.POSITIVE_INFINITY)
        return stop to target
    }

    /**
     * Why [h] is sold at [nowMin] (null: held): its option's finished minutes from [Held.fromMin] ([bars], oldest first) walked
     * as the forge walked them - the stop on a minute's low before the target on its high, the no-progress exit at its minute,
     * the time stop, the index [indexNow] back [ShadowSim.Rules.indexStopPct] from the entry - then the square-off.
     */
    fun exit(r: ShadowSim.Rules, day: LocalDate, h: Held, bars: List<Minute>, indexNow: Double?, nowMin: Int): String? {
        val sq = squareOffMin(r, day, h.expiryToday)
        if (nowMin >= sq) return if (h.expiryToday && sq == EXPIRY_EXIT) "expiry_1505" else "square_off_${ShadowSim.hhmm(sq).replace(":", "")}"
        val (stop, target) = levels(r, h.entry)
        var peak = h.entry
        val from = maxOf(h.entryMin, h.fromMin)
        val first = bars.firstOrNull { it.minute >= from }?.minute ?: return indexStop(r, h, indexNow)
        for (b in bars) {
            if (b.minute < from) continue
            if (stop > 0 && b.low <= stop) return "stop"
            if (b.high >= target) return "target"
            peak = maxOf(peak, b.high)
            val held = b.minute - first + 1
            r.noProgressMin?.let { t -> if (held >= t && peak < h.entry * (1 + r.noProgressGain)) return "no_progress_$t" }
            r.timeStopMin?.let { t -> if (held >= t) return "time_stop_$t" }
        }
        return indexStop(r, h, indexNow)
    }

    private fun indexStop(r: ShadowSim.Rules, h: Held, indexNow: Double?): String? {
        val p = r.indexStopPct ?: return null
        val ix = indexNow ?: return null
        return if (h.dir * (ix - h.indexAtEntry) <= -p * h.indexAtEntry) "index_stop" else null
    }

    // ---- the record against its forward test ---------------------------------------------------------------------------------

    /** A paper record judged: [stop] it (and why), with the sequential p "worse than its forward test". */
    data class Verdict(val stop: Boolean, val why: String, val pWorse: Double)

    /**
     * [paper] (rupees per closed paper trade, oldest first) against [expectPerTrade] (its forward test's mean): null before
     * [MIN_JUDGE] trades; else stopped when its deepest fall breaks the capital rule or the always-valid p that it is worse
     * than its forward test reaches [STOP_ALPHA] (the same mSPRT as the forge's forward test, [LearnStats.alwaysValidP]).
     */
    fun judge(paper: List<Double>, expectPerTrade: Double): Verdict? {
        if (paper.size < MIN_JUDGE) return null
        val dd = LearnStats.maxDrawdown(paper)
        val diffs = paper.map { it - expectPerTrade }
        val p = LearnStats.alwaysValidP(diffs, -1, MIN_SD)
        val record = "${paper.size} paper trades, ${rs(paper.sum())} (${rs(paper.average())} a trade against ${rs(expectPerTrade)} in its forward test)"
        return when {
            dd > StrategyForge.MAX_DD -> Verdict(true, "its deepest fall on paper ${rs(-dd)} broke the capital rule - $record", p)
            p <= STOP_ALPHA -> Verdict(true, "worse than its forward test: $record, sequential p ${fmtP(p)}", p)
            else -> Verdict(false, "in line so far: $record, sequential p ${fmtP(p)}", p)
        }
    }

    fun rs(x: Double): String = DiscoveryStats.rs(x)
    fun fmtP(p: Double): String = if (p < 0.001) "< 0.001" else String.format(Locale.ENGLISH, "%.3f", p)
}
