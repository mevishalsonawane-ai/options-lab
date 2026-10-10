package com.optionslab.engine.orb

import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * The shadows Boss added on 06 Oct 2026 from the losing-trades study (research "losers", read-only: its replay of the app's
 * own arm rules on six years of real BANKNIFTY option minutes, generalised with entry / exit switches; FIT to 30 Jun 2024,
 * TEST from 01 Jul 2024). For each retired arm the variant with the best TEST net among its FIT-chosen finalists, plus one
 * long shot. None made money out of sample: they are tracked to see whether the fix holds live, never traded. They record
 * what they WOULD have done - no order, paper or live - exactly as the study replayed them, minute by minute on the option's
 * own 1-minute candles. Pure: no clock, no network, no storage.
 *
 * Common to all: BANKNIFTY, the ORB's opening range (5-minute bars 09:15..10:00), the 09:20 bar's ATM strike (half-up to
 * 100), the ORB family's expiry (on or after today), 1 lot. A completed 5-minute bar decides; the entry is priced on the
 * option's first 1-minute candle starting at or after the bar's close (the decision minute): a MARKET buy at its open
 * (+5 bps), refused at a premium of 40 or less. Exits are walked on each completed 1-minute candle in this order: the -40
 * SL-M (its trigger or the open below it, -10 bps), the plain profit-lock ladder ([ProfitLock.level] on the reference,
 * charges not in its breakeven rung; at the lock or the open below it, -5 bps), the premium target (at it or the open
 * above it, -5 bps), out at the 15:10 minute's open (-5 bps).
 *
 * "c_max1_pull10_rvge20" - first entry only, a 10-point pullback, skip the quietest volatility:
 *   [ORB_P10] (ORB's signal: TEST 393 trades, net -39,267; FIT 557, -36,034), [ORBF_P10] (ORB Fresh's: TEST 314, -36,164;
 *   FIT 462, -31,809), [FADE_P10] (Range Fade's: TEST 327, -50,584; FIT 449, -32,567). The arm's own signal
 *   ([OrbRules.entrySignal], [RangeFadeRules.entrySignal]), at most one trade a day, and only when the index's realised
 *   volatility [rv30] at the decision minute is at least [RV_MIN] (2.73 bp a minute: the FIT period's 20th percentile,
 *   pinned from the study's thresholds.txt). The buy is a LIMIT [PULL_POINTS] (10) under the decision minute's open, on the
 *   0.05 tick, waiting [PULL_WAIT] (15) minutes (and not past 15:10): filled at the first candle whose low reaches it, at the
 *   limit or that candle's open when it opened below; unfilled, no trade (a later signal may still be taken). Stop -40,
 *   target +40, the ladder on +40.
 * "c_max1_hold" - [SWEEP_H1] (ORB Sweep's signal [SweepRules.entrySignal]: TEST 456 trades, net -57,913; FIT 564, -67,863):
 *   first entry only, a MARKET buy; the -40 stop and 15:10 only - no target, no ladder.
 * The long shot - [SWEEP_14] (ORB Sweep's own rules with entries only from 14:00: its signal on the 13:55..14:25 bars, so
 *   the decision is at 14:00 or later; at most 2 a day as the arm; -40 / +80 with the ladder on +80). The study's
 *   time-of-day check on the arm's own trades decided from 14:00: 41 trades +5,214 in TEST, 59 trades -6,798 in FIT.
 *
 * Why each shadow loses ([lossGroup]) uses the study's own definitions (forensics.py): of the losing trades (net after
 * charges below 0), an exit on the profit lock is a failed lock; a gross gain the charges turned into a loss is "charges
 * ate it"; never green after charges while held (at any candle's high) and never green to 15:10 either is "wrong way from
 * the start", but green after the exit is "stopped, then it turned"; any other loser was green, then gave it back.
 */
object ShadowStudy {
    /** How a variant enters and exits; [target] +Infinity is none, [ladder] null is no ladder. */
    data class Rule(val signal: Signal, val pull: Boolean, val rvMin: Double?, val maxEntries: Int, val target: Double, val ladder: Double?,
                    val firstBar: LocalTime? = null)

    /** Whose signal the variant takes. */
    enum class Signal { ORB, ORB_FRESH, SWEEP, FADE }

    const val PULL_POINTS = 10.0
    const val PULL_WAIT = 15L
    /** rv30 at or above this (bp a minute) is not the quietest fifth: FIT-only (thresholds.txt bn_rv_p20). */
    const val RV_MIN = 2.73
    const val RV_MINUTES = 30L
    const val RV_MIN_RETURNS = 10
    /** A pending candle missing from the feed is waited for this long after its window ends. */
    const val GRACE_MINUTES = 2L
    val SWEEP_14_FIRST_BAR: LocalTime = LocalTime.of(13, 55)
    private const val EPS = 1e-9

    private val P10 = Rule(Signal.ORB, pull = true, rvMin = RV_MIN, maxEntries = 1, target = OrbRules.TARGET_POINTS, ladder = OrbRules.TARGET_POINTS)
    private val RULES: Map<String, Rule> = mapOf(
        "orb_p10" to P10,
        "orbf_p10" to P10.copy(signal = Signal.ORB_FRESH),
        "fade_p10" to P10.copy(signal = Signal.FADE),
        "sweep_h1" to Rule(Signal.SWEEP, pull = false, rvMin = null, maxEntries = 1, target = Double.POSITIVE_INFINITY, ladder = null),
        "sweep_14" to Rule(Signal.SWEEP, pull = false, rvMin = null, maxEntries = SweepRules.MAX_ENTRIES, target = SweepRules.TARGET_POINTS,
            ladder = SweepRules.TARGET_POINTS, firstBar = SWEEP_14_FIRST_BAR),
    )

    /** The rule of a study shadow, or null for the others (V43, S17, R20, O08: [ShadowRules.exitReason] on the LTP). */
    fun ruleOf(id: String): Rule? = RULES[id]

    // ---- entries ----------------------------------------------------------------------------------------------------

    /**
     * Realised volatility at [at]: the sample standard deviation of the index's 1-minute log returns (in basis points) of the
     * minutes of [at]'s day that started in the 30 minutes before [at] (each against the minute before it, today's only);
     * null with fewer than 10 returns. Reads only what had closed by [at].
     */
    fun rv30(ones: List<Bar>, at: LocalDateTime): Double? {
        val pre = ones.filter { it.start.toLocalDate() == at.toLocalDate() && it.start.isBefore(at) }.sortedBy { it.start }.distinctBy { it.start }
        val rs = ArrayList<Double>()
        var j = pre.size - 1
        while (j >= 1 && !pre[j].start.isBefore(at.minusMinutes(RV_MINUTES))) { rs += ln(pre[j].close / pre[j - 1].close) * 1e4; j-- }
        if (rs.size < RV_MIN_RETURNS) return null
        val mu = rs.average()
        return sqrt(rs.sumOf { (it - mu) * (it - mu) } / (rs.size - 1))
    }

    /**
     * The decision of [rule] on the last of the day's completed 5-minute BANKNIFTY [bars]: the arm's signal with [taken]
     * trades today and the [lastExit], then the window, the day's cap and the volatility floor (on the index's 1-minute
     * [ones]). The entry's [ShadowRules.Entry.signalBar] is the bar; the price comes later ([enter]).
     */
    fun decide(rule: Rule, bars: List<Bar>, ones: List<Bar>, taken: Int, lastExit: LocalDateTime?): ShadowRules.Decision {
        val rng = OrbRules.openingRange(bars) ?: return ShadowRules.Decision(null, "waiting_for_opening_range")
        val strike = OrbRules.atmStrike(OrbRules.strikeBar(bars)!!.close)
        val last = bars.last()
        val (side, why) = when (rule.signal) {
            Signal.ORB -> OrbRules.entrySignal(bars, rng, OrbRules.ORB, lastExit)
            Signal.ORB_FRESH -> OrbRules.entrySignal(bars, rng, OrbRules.ORB_FRESH, lastExit)
            Signal.SWEEP -> SweepRules.entrySignal(bars, rng, lastExit, taken)
            Signal.FADE -> RangeFadeRules.entrySignal(bars, rng, lastExit, taken)
        }
        if (side == 0) return ShadowRules.Decision(null, why)
        if (rule.firstBar != null && last.start.toLocalTime().isBefore(rule.firstBar)) return ShadowRules.Decision(null, "before_14")
        if (taken >= rule.maxEntries) return ShadowRules.Decision(null, "done_for_today")
        if (rule.rvMin != null) {
            val rv = rv30(ones, last.start.plusMinutes(5)) ?: return ShadowRules.Decision(null, "no_volatility_reading")
            if (rv < rule.rvMin) return ShadowRules.Decision(null, "quiet_volatility")
        }
        return ShadowRules.Decision(ShadowRules.Entry(side, strike, last.start), why)
    }

    /** An entry being priced: [state] "waiting" (keep looking), "filled" ([at], [price]), "unfilled" or "refused" ([why]). */
    data class Priced(val state: String, val why: String, val at: LocalDateTime? = null, val price: Double? = null)

    /** The LIMIT price [PULL_POINTS] under [open], on the 0.05 tick. */
    fun pullLimit(open: Double): Double = Math.round(Math.round((open - PULL_POINTS) / OrbRules.TICK) * OrbRules.TICK * 100) / 100.0

    private fun done(b: Bar, now: LocalDateTime) = !b.start.plusMinutes(1).isAfter(now)

    /**
     * Prices an entry decided at [from] (the signal bar's close) on the option's 1-minute candles [opt] as of [now]: the
     * first completed candle at or after [from] sets the price; with a pullback ([Rule.pull]) the LIMIT then waits for a low
     * at or under it until [PULL_WAIT] minutes after [from] (not past 15:10). Waits while a candle it needs has not closed.
     */
    fun enter(rule: Rule, opt: List<Bar>, from: LocalDateTime, now: LocalDateTime): Priced {
        val sq = from.toLocalDate().atTime(OrbRules.SQUARE_OFF)
        val end = minOf(from.plusMinutes(PULL_WAIT), sq)
        val seen = opt.filter { it.start.toLocalDate() == from.toLocalDate() && done(it, now) }.sortedBy { it.start }
        val first = seen.firstOrNull { !it.start.isBefore(from) }
            ?: return if (!now.isBefore(end.plusMinutes(GRACE_MINUTES))) Priced("unfilled", "no_option_minutes") else Priced("waiting", "waiting_for_the_price")
        if (!first.start.isBefore(sq)) return Priced("unfilled", "no_option_minutes")
        val market = ShadowRules.entryFill(first.open)
        if (ShadowRules.refused(market)) return Priced("refused", "refused_premium_at_or_under_40")
        if (!rule.pull) return Priced("filled", "market", first.start, market)
        val lim = pullLimit(first.open)
        seen.firstOrNull { !it.start.isBefore(first.start) && it.start.isBefore(end) && it.low <= lim + EPS }
            ?.let { return Priced("filled", "pullback", it.start, minOf(lim, it.open)) }
        val over = seen.any { !it.start.isBefore(end.minusMinutes(1)) } || !now.isBefore(end.plusMinutes(GRACE_MINUTES))
        return if (over) Priced("unfilled", "pullback_unfilled") else Priced("waiting", "waiting_for_the_pullback")
    }

    // ---- the walk -----------------------------------------------------------------------------------------------------

    /**
     * A held study shadow: bought at [entry] in the candle starting [at]; [peak] the best high before the next candle (the
     * ladder's), [mfe] / [mae] the best and worst move from [entry] in premium points while held (highs and lows), [last] the
     * last candle walked.
     */
    data class Walk(val entry: Double, val at: LocalDateTime, val peak: Double = entry, val mfe: Double? = null, val mae: Double? = null,
                    val last: LocalDateTime? = null)

    /** The walk so far, and the exit when one came: its fill [exit], the candle [exitAt] and [why]. */
    data class Step(val walk: Walk, val exit: Double? = null, val exitAt: LocalDateTime? = null, val why: String? = null)

    /** Walks [w] over the option's candles [opt] that closed by [now] and come after the last one walked; stops at the exit. */
    fun walk(rule: Rule, w0: Walk, opt: List<Bar>, now: LocalDateTime): Step {
        val sq = w0.at.toLocalDate().atTime(OrbRules.SQUARE_OFF)
        val trig = OrbRules.stopTrigger(w0.entry)
        var w = w0
        for (b in opt.filter { done(it, now) && !it.start.isBefore(w0.at) && (w0.last == null || it.start.isAfter(w0.last)) }
            .sortedBy { it.start }.distinctBy { it.start }) {
            if (!b.start.isBefore(sq)) return Step(w.copy(last = b.start), ShadowRules.exitFill("session_end", w.entry, b.open), b.start, "session_end")
            val e = w.entry
            w = w.copy(mfe = maxOf(w.mfe ?: Double.NEGATIVE_INFINITY, b.high - e), mae = maxOf(w.mae ?: Double.NEGATIVE_INFINITY, e - b.low), last = b.start)
            if (trig != null && b.low <= trig) return Step(w, ShadowRules.exitFill("stop", e, b.open), b.start, "stop")
            val lock = rule.ladder?.let { ProfitLock.level(e, it, w.peak) }
            if (lock != null && b.low <= lock + EPS) return Step(w, ShadowRules.exitFill("profit_lock", e, minOf(lock, b.open)), b.start, "profit_lock")
            if (rule.target.isFinite() && b.high >= e + rule.target - EPS) {
                return Step(w, ShadowRules.exitFill("target", e, maxOf(e + rule.target, b.open)), b.start, "target")
            }
            w = w.copy(peak = maxOf(w.peak, b.high))
        }
        return Step(w)
    }

    // ---- why a shadow loses -------------------------------------------------------------------------------------------

    /** The study's loser groups, in its order (G1, G2, G3, G4, G5 + G6). */
    enum class LossGroup(val words: String) {
        FAILED_LOCK("failed lock (back to the price paid)"),
        CHARGES("charges ate it"),
        WRONG_WAY("wrong way from the start"),
        STOP_THEN_TURNED("stopped, then it turned"),
        GAVE_BACK("green, then gave it back"),
    }

    /** The best and worst move from [entry] (premium points) over the candles from [entryAt] to [exitAt], and to 15:10 ([dayMfe]). */
    data class Excursion(val mfe: Double?, val mae: Double?, val dayMfe: Double?)

    /**
     * A trade's excursion on the option's 1-minute candles [opt] (the day's): while held, the candles from the entry's to the
     * exit's (both included); for [Excursion.dayMfe], every candle from the entry's to the last before 15:10. Null: no candle.
     */
    fun excursion(entry: Double, entryAt: LocalDateTime, exitAt: LocalDateTime, opt: List<Bar>): Excursion {
        val from = entryAt.withSecond(0).withNano(0)
        val to = exitAt.withSecond(0).withNano(0)
        val sq = from.toLocalDate().atTime(OrbRules.SQUARE_OFF)
        val day = opt.filter { !it.start.isBefore(from) && it.start.isBefore(sq) }
        val held = day.filter { !it.start.isAfter(to) }
        return Excursion(held.maxOfOrNull { it.high - entry }, held.maxOfOrNull { entry - it.low }, day.maxOfOrNull { it.high - entry })
    }

    /** The net after charges had the trade been sold at [entry] + [move] (the green test: at or above 0). */
    private fun netAt(entry: Double, move: Double, qty: Int) = move * qty - ShadowRules.charges(entry, entry + move, qty)

    /**
     * The study's group for a trade bought at [entry] and sold at [exit] ([why]) with [charges]; null for a trade that did
     * not lose. [mfe]: its best move while held (points; null: never seen, so never green); [dayMfe]: to 15:10 (null: not
     * known, read as no better than [mfe]).
     */
    fun lossGroup(entry: Double, exit: Double, qty: Int, charges: Double, why: String?, mfe: Double?, dayMfe: Double?): LossGroup? {
        val gross = (exit - entry) * qty
        if (gross - charges >= 0) return null
        if (why == "profit_lock") return LossGroup.FAILED_LOCK
        if (gross >= 0) return LossGroup.CHARGES
        val green = mfe != null && netAt(entry, mfe, qty) >= 0
        if (green) return LossGroup.GAVE_BACK
        val day = dayMfe ?: mfe
        return if (day != null && netAt(entry, day, qty) >= 0) LossGroup.STOP_THEN_TURNED else LossGroup.WRONG_WAY
    }

    /** The most common group among [groups] (ties: the study's order) and how many; null with none. */
    fun topReason(groups: List<LossGroup>): Pair<LossGroup, Int>? =
        groups.groupingBy { it }.eachCount().entries.sortedWith(compareByDescending<Map.Entry<LossGroup, Int>> { it.value }.thenBy { it.key.ordinal })
            .firstOrNull()?.let { it.key to it.value }

    /** "mostly green, then gave it back (5 of 9 losers)", or null with no loser. */
    fun reasonLine(groups: List<LossGroup>): String? = topReason(groups)?.let { (g, n) -> "mostly ${g.words} ($n of ${groups.size} loser${if (groups.size == 1) "" else "s"})" }
}
