package com.optionslab.engine.orb

import java.time.LocalDate
import java.util.Locale

/**
 * "R72 Breadth Donchian" (16 Oct 2026, Boss: "Okay add it"): research R72's one pass, C1 "breadth breakout" at its original
 * setting (research/HUNT_R72_CROSS_INDEX.md, research/hunt/r72/APP_SPEC.md; code research/hunt/r72/r72rules.py
 * `breadth_breakout`, M = 4), as a PAPER arm BESIDE the R51 Donchian 20 paper arm ([DonchianArm]): both run, so the two can be
 * compared on the same unseen days. 1 lot, its own book, never live.
 *
 *   signal   the INDICES' own 5-minute bars, read together: on each bar, [DonchianArm.sideAt] for each of NIFTY, BANKNIFTY,
 *            FINNIFTY, MIDCPNIFTY and SENSEX; when at least [MIN_BREADTH] of them break the same way on the same bar (same
 *            date, same bar of the day), every index that broke that way gets the signal (+1 the call, -1 the put); fewer:
 *            nothing, even where Donchian alone would trade. An index with no bar then (missing data, a feed down) counts as
 *            not breaking; the threshold stays 4 (absolute, not a share).
 *   the rest Donchian's, through its own helpers: the 1-ITM strike from the signal bar's close ([PassedArms.itmStrike]), the
 *            09:20-15:10 window ([DonchianArm.inWindow]), the 2-minute fill slack ([DonchianArm.mayFill]), one position a side
 *            and 2 entries a side a day per index in THIS arm's own book ([DonchianArm.mayTake]), never on the index's own
 *            expiry day, 1 lot; exits [EXITS] (Donchian's, with the 30-60-100 lock and L25 when the book has them on, as the
 *            Donchian paper arm runs today).
 *   forward  [PassedArms.ForwardBar], as Donchian.
 *
 * Not a new edge: 77% of its research trades were Donchian's own (a filter). Pure: no clock, no network, no storage, no orders.
 */
object BreadthDonchianArm {
    const val SOURCE = "r72_breadth_donchian"
    const val LABEL = "R72 Breadth Donchian"
    const val RESEARCH_ID = "R72 (C1, breadth 4 of 5)"
    val ARM = Arm(SOURCE, LABEL, paperOnly = true)
    val UNDERLYINGS = DonchianArm.UNDERLYINGS
    /** Reported apart like Donchian, so the two records compare index for index. */
    const val REPORT_APART = DonchianArm.REPORT_APART
    /** At least this many of the five break the same way on the same bar (research M = 4). */
    const val MIN_BREADTH = 4
    val EXITS = DonchianArm.EXITS

    const val RULES = "On each 5-minute bar: when at least 4 of NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY and SENSEX close above the " +
        "highest high of their previous 20 bars, buy the 1-ITM call of each index that did (below the lowest low: the put); fewer " +
        "than 4: no trade. Next bar's open, 09:20-15:10; stop −15%, +5% once +25%, the 30/60/100 ladder, out after 30 minutes, " +
        "square-off 15:15; one position a side, at most 2 entries a side a day per index; never on the index's own expiry day; " +
        "1 lot; paper only"
    const val RECORD = "Research R72 final test: 1,202 trades, +₹1,71,366 (Donchian on the same days +₹55,439 / +₹81,845 with L25), " +
        "p 0.008, q 0.032; +₹1,37,648 at twice the slippage. Same trades as Donchian 77% of the time: a filter, not a new edge"
    /** Shown with it (the spec's risk line). */
    const val RISK = "Backtest worst day −₹58,613, drawdown ₹1.99 lakh at 1 lot on five indices: beyond the −₹40k account stop; " +
        "paper only"
    /** What R72 found for Donchian's trades that breadth did or did not confirm (final test). */
    const val SPLIT = "in research, Donchian's trades breadth confirmed made +₹1,37,635 (752) and the ones it did not lost −₹55,790 (1,029)"

    /**
     * 1 Nov 2026 (Boss: "Make the change the arms"): research R140's FB-R72-A10 - no new break on a day after a very WIDE day:
     * yesterday's range (high - low) at least [WIDE_CUT] times the average range of the 20 sessions up to yesterday. Frozen at
     * R140's design cut; it cut R72's losing days in all six periods tested, two of them never seen (R143, R144a's fresh year:
     * -Rs 3.15 lakh vs -Rs 3.89 lakh, beat random skipping at p < 0.001). The research measured the ranges from the index's own
     * minutes; here they come from the daily candles (the same session high and low).
     */
    const val WIDE_CUT = 1.3206
    /** The research's average daily range: the 20 sessions up to yesterday, at least 10 of them known. */
    const val WIDE_SESSIONS = 20
    const val WIDE_MIN_SESSIONS = 10
    const val WIDE_RULE = "no new break the day after a very wide day (yesterday's range at least 1.32x the 20-session average; R140 FB-R72-A10)"
    const val WIDE_RECORD = "held in all six periods tested; fresh year Nov 2021 - Nov 2022: losing days −₹3.15 lakh instead of " +
        "−₹3.89 lakh, net −₹53,404 instead of −₹67,223"

    /**
     * Yesterday's range over the average range of the [WIDE_SESSIONS] sessions up to it, from the index's daily candles [daily]
     * (oldest first, sessions before today only). [prevSession]: the session before today; the newest candle must be that day,
     * else (a candle missing, a stale list) null. Null too with fewer than [WIDE_MIN_SESSIONS] known ranges. Pure.
     */
    fun wideRatio(daily: List<Bar>, prevSession: LocalDate?): Double? {
        if (prevSession == null) return null
        val days = daily.filter { it.high.isFinite() && it.low.isFinite() && it.high >= it.low }
            .sortedBy { it.start }.distinctBy { it.start.toLocalDate() }
        val last = days.lastOrNull() ?: return null
        if (last.start.toLocalDate() != prevSession) return null
        val win = days.takeLast(WIDE_SESSIONS)
        if (win.size < WIDE_MIN_SESSIONS) return null
        val adr = win.sumOf { it.high - it.low } / win.size
        if (!(adr > 0)) return null
        return (last.high - last.low) / adr
    }

    /** The wide-day rule skips today's breaks ([wideRatio] at or above [WIDE_CUT]); unknown never skips. */
    fun wideDay(ratio: Double?): Boolean = ratio != null && ratio.isFinite() && ratio >= WIDE_CUT - 1e-12

    /**
     * The breaks on bar [b] of [date] for each index of [seriesByIndex] (the continuous 5-minute series, oldest first): +1, -1,
     * or 0 (no break, or no bar then). Only the five [UNDERLYINGS] count.
     */
    fun sidesAt(seriesByIndex: Map<String, List<PassedArms.Bar5>>, date: LocalDate, b: Int): Map<String, Int> =
        UNDERLYINGS.associateWith { u ->
            val s = seriesByIndex[u].orEmpty()
            val i = s.indexOfLast { it.date == date && it.b == b }
            if (i < 0) 0 else DonchianArm.sideAt(s, i)
        }

    /** The bar's call (+1) or put (-1) breadth, or 0 when fewer than [MIN_BREADTH] break the same way. */
    fun breadth(sides: Map<String, Int>): Int = when {
        sides.values.count { it > 0 } >= MIN_BREADTH -> 1
        sides.values.count { it < 0 } >= MIN_BREADTH -> -1
        else -> 0
    }

    /**
     * Today's signals from the five indices' series ([seriesByIndex]: each index's earlier sessions' 5-minute bars, then
     * [today]'s closed ones): on every bar of [today] whose entry minute is in the window, every index that broke the way at
     * least [MIN_BREADTH] of them did, as a [DonchianArm.Signal] (its strike from that index's bar close). Oldest bar first,
     * then the five in [UNDERLYINGS]' order. The day's caps and the fill window are [DonchianArm.mayTake] / [DonchianArm.mayFill].
     */
    fun signals(seriesByIndex: Map<String, List<PassedArms.Bar5>>, today: LocalDate): List<DonchianArm.Signal> {
        // Each index's side and bar on each of today's bars (one walk per index, the channel being Donchian's own).
        val by = HashMap<Int, MutableMap<String, Pair<Int, PassedArms.Bar5>>>()
        for (u in UNDERLYINGS) {
            val s = seriesByIndex[u] ?: continue
            for (i in s.indices) {
                val bar = s[i]
                if (bar.date != today) continue
                by.getOrPut(bar.b) { HashMap() }[u] = DonchianArm.sideAt(s, i) to bar
            }
        }
        val out = ArrayList<DonchianArm.Signal>()
        for (b in by.keys.sorted()) {
            val row = by.getValue(b)
            val side = breadth(row.mapValues { it.value.first })
            if (side == 0) continue
            for (u in UNDERLYINGS) {
                val (s, bar) = row[u] ?: continue
                if (s != side || !DonchianArm.inWindow(bar.closesAt.toLocalTime())) continue
                out += DonchianArm.Signal(u, side, bar, PassedArms.itmStrike(u, side, bar.close, DonchianArm.MONEY))
            }
        }
        return out
    }

    /**
     * 25 Oct (Boss's decision on the arms audit: keep R72's place under the same-direction cap only when it has a break due, not
     * on every bar): the sides (+1 calls, -1 puts) R72 has a break due on at [now] - one of [signals] inside its fill window
     * ([DonchianArm.mayFill]) that is not decided yet ([decided]: bought, refused or let go) - each with the end of the latest
     * such window. Empty when nothing is due. Pure.
     */
    fun dueSides(signals: List<DonchianArm.Signal>, now: java.time.LocalDateTime, decided: (DonchianArm.Signal) -> Boolean): Map<Int, java.time.LocalDateTime> =
        signals.filter { DonchianArm.mayFill(it, now) && !decided(it) }.groupBy { it.side }
            .mapValues { (_, xs) -> xs.maxOf { it.entryTime.plusMinutes(DonchianArm.FILL_SLACK_MINUTES + 1) } }

    /**
     * Boss's comparison (the point of running both): since [since] (the switch-on day), this arm's closed paper trades
     * ([breadthNets]) next to Donchian's ([donchianNets]), and Donchian's trades that breadth did not confirm ([unconfirmedNets];
     * null: not known yet). Nets after charges, in words. 16 Oct (R73 T7 on R51 only): [stSplit], Donchian's trades split into
     * those taken under its Supertrend filter and those on its plain rules (null: no filtered trade yet and the filter off), so
     * the comparison says which Donchian record it is set against.
     */
    fun comparison(since: LocalDate?, breadthNets: List<Double>, donchianNets: List<Double>, unconfirmedNets: List<Double>?,
                   stSplit: Pair<List<Double>, List<Double>>? = null): String {
        fun side(n: List<Double>) = "${n.size} trade${if (n.size == 1) "" else "s"}, ${rs(n.sum())}"
        val from = since?.let { "since ${it.dayOfMonth} ${it.month.name.lowercase().replaceFirstChar { c -> c.uppercase() }.take(3)}" } ?: "so far"
        return "Breadth Donchian vs Donchian on paper $from: $LABEL ${side(breadthNets)}; ${DonchianArm.LABEL} ${side(donchianNets)}" +
            (unconfirmedNets?.let { "; Donchian's trades breadth did not confirm: ${side(it)}" } ?: "") +
            (stSplit?.let { (f, p) -> "; Donchian's with its Supertrend filter: ${side(f)}, on its plain rules: ${side(p)}" } ?: "") +
            " (after charges; $SPLIT)"
    }

    private fun rs(x: Double) = (if (x < 0) "−₹" else "+₹") + "%,.0f".format(Locale.ENGLISH, kotlin.math.abs(x))

    // ---- Verified Options-Buying Upgrades (Nov 2026 real 1m option-price walk-forward) -----------------------------------

    /**
     * Execution universe (Ex-BANKNIFTY): all 5 [UNDERLYINGS] remain in the [MIN_BREADTH] breadth vote, while BANKNIFTY is
     * excluded from execution due to monthly-expiry drag on real 1m option prices.
     */
    val EXEC_UNDERLYINGS = listOf("NIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")

    /**
     * Opposing-wick absorption ceiling: skip a breakout bar whose opposing rejection wick is at or above 35% of the bar range.
     * Verified across 4/4 Ex-BANKNIFTY indices in both Design (Jul 2024 - Dec 2025) and Holdout (Jan - Oct 2026):
     * multi-slot str>0 & brk>=0.50 lifts 1x PnL +₹1,66,233 -> +₹2,11,404 (2x +₹2,04,878), PF 1.33 -> 1.48, day-t +2.49 -> +2.88.
     */
    const val MAX_OPPOSING_WICK = 0.35

    /** Minimum breakout extension in ATR(14) units beyond the 20-bar Donchian channel. */
    const val MIN_BREAK_STRENGTH = 0.50

    /** Lookback for causal ATR used by [breakStrength]. */
    const val BREAK_ATR_LEN = 14

    /**
     * Fraction of [bar]'s high-low range consumed by the opposing rejection wick for [side] (+1 call: upper wick; -1 put: lower
     * wick). Returns 0.0 for flat bars or non-finite prices. Pure.
     */
    fun opposingWick(bar: PassedArms.Bar5, side: Int): Double {
        if (!bar.open.isFinite() || !bar.high.isFinite() || !bar.low.isFinite() || !bar.close.isFinite()) return 0.0
        val range = bar.high - bar.low
        if (!(range > 0.0)) return 0.0
        val wick = if (side > 0) bar.high - maxOf(bar.open, bar.close) else minOf(bar.open, bar.close) - bar.low
        return (wick / range).coerceIn(0.0, 1.0)
    }

    /** True when [bar] does not show passive opposing-wick absorption ([opposingWick] strictly below [maxWick]). Pure. */
    fun passesAbsorptionGate(bar: PassedArms.Bar5, side: Int, maxWick: Double = MAX_OPPOSING_WICK): Boolean =
        side != 0 && opposingWick(bar, side) < maxWick - 1e-12

    /** True when causal ATM straddle expansion vs 09:20 ([straddleVs0920]) is strictly positive. Unknown/NaN returns false. Pure. */
    fun straddleExpanding(straddleVs0920: Double?): Boolean =
        straddleVs0920 != null && straddleVs0920.isFinite() && straddleVs0920 > 0.0

    /**
     * Breakout extension at bar [i] of [series] in ATR([atrLen]) units beyond the prior [lookback]-bar Donchian edge for [side]
     * (+1: (close - maxHigh) / atr; -1: (minLow - close) / atr). Null when history is insufficient or ATR is non-positive. Pure.
     */
    fun breakStrength(
        series: List<PassedArms.Bar5>,
        i: Int,
        side: Int,
        lookback: Int = DonchianArm.LOOKBACK,
        atrLen: Int = BREAK_ATR_LEN,
    ): Double? {
        if (side == 0 || i < lookback || i >= series.size) return null
        val w = series.subList(i - lookback, i)
        if (w.any { !it.high.isFinite() || !it.low.isFinite() }) return null
        val bar = series[i]
        if (!bar.close.isFinite()) return null
        val start = maxOf(0, i - atrLen + 1)
        var trSum = 0.0
        var trCount = 0
        for (k in start..i) {
            val cur = series[k]
            if (!cur.high.isFinite() || !cur.low.isFinite()) continue
            val tr = if (k > 0 && series[k - 1].close.isFinite()) {
                val pc = series[k - 1].close
                maxOf(cur.high - cur.low, kotlin.math.abs(cur.high - pc), kotlin.math.abs(cur.low - pc))
            } else {
                cur.high - cur.low
            }
            trSum += tr
            trCount++
        }
        if (trCount < minOf(5, atrLen)) return null
        val atr = trSum / trCount
        if (!(atr > 0.0)) return null
        return if (side > 0) (bar.close - w.maxOf { it.high }) / atr else (w.minOf { it.low } - bar.close) / atr
    }

    /**
     * Upgraded signal generator keeping all 5 [UNDERLYINGS] in the [MIN_BREADTH] breadth vote, while applying:
     *   1. Ex-BANKNIFTY execution ([execUnderlyings]),
     *   2. Opposing-wick absorption gate ([maxOpposingWick], default 0.35),
     *   3. Breakout extension floor ([minBreakStrength], default 0.50 ATR),
     *   4. Optional causal ATM straddle expansion gate ([straddleVs0920ByBar]: map of (underlying, barIndex) -> straddle_vs_0920),
     *   5. Highest [breakStrength] tie-breaking within the same bar for strict 1-position Rs 56,000 slot selection.
     * Existing [signals] remains unchanged for parity with legacy callers and tests. Pure.
     */
    fun filteredSignals(
        seriesByIndex: Map<String, List<PassedArms.Bar5>>,
        today: LocalDate,
        execUnderlyings: List<String> = EXEC_UNDERLYINGS,
        maxOpposingWick: Double = MAX_OPPOSING_WICK,
        minBreakStrength: Double = MIN_BREAK_STRENGTH,
        straddleVs0920ByBar: Map<Pair<String, Int>, Double>? = null,
        fkSemiVolRatioByBar: Map<Pair<String, Int>, Double>? = null,
        minFkSemiVolRatio: Double = MIN_FK_SEMI_VOL_RATIO,
    ): List<DonchianArm.Signal> {
        val by = HashMap<Int, MutableMap<String, Triple<Int, PassedArms.Bar5, Double?>>>()
        for (u in UNDERLYINGS) {
            val s = seriesByIndex[u] ?: continue
            for (i in s.indices) {
                val bar = s[i]
                if (bar.date != today) continue
                val side = DonchianArm.sideAt(s, i)
                val brk = if (side != 0) breakStrength(s, i, side) else null
                by.getOrPut(bar.b) { HashMap() }[u] = Triple(side, bar, brk)
            }
        }
        val execSet = execUnderlyings.toSet()
        val out = ArrayList<DonchianArm.Signal>()
        for (b in by.keys.sorted()) {
            val row = by.getValue(b)
            val side = breadth(row.mapValues { it.value.first })
            if (side == 0) continue
            val barCandidates = ArrayList<Pair<Double, DonchianArm.Signal>>()
            for (u in UNDERLYINGS) {
                if (u !in execSet) continue
                val (s, bar, brk) = row[u] ?: continue
                if (s != side || !DonchianArm.inWindow(bar.closesAt.toLocalTime())) continue
                if (!passesAbsorptionGate(bar, side, maxOpposingWick)) continue
                if (brk == null || brk < minBreakStrength - 1e-12) continue
                if (straddleVs0920ByBar != null && !straddleExpanding(straddleVs0920ByBar[u to b])) continue
                if (fkSemiVolRatioByBar != null) {
                    val fk = fkSemiVolRatioByBar[u to b]
                    if (fk == null || !fk.isFinite() || fk < minFkSemiVolRatio - 1e-12) continue
                }
                val sig = DonchianArm.Signal(u, side, bar, PassedArms.itmStrike(u, side, bar.close, DonchianArm.MONEY))
                barCandidates += brk to sig
            }
            barCandidates.sortByDescending { it.first }
            for ((_, sig) in barCandidates) out += sig
        }
        return out
    }

    // ---- Verified Feynman-Kac Directional Semi-Vol Gate (Nov 2026 MC + Walk-Forward) ------------------------------------

    const val FK_SEMI_VOL_WINDOW = 30
    const val FK_SEMI_VOL_MIN_BARS = 15
    const val MIN_FK_SEMI_VOL_RATIO = 0.70
    private val ANNUALIZE_1M_PCT = kotlin.math.sqrt(252.0 * 375.0) * 100.0

    fun directionalSemiVolPct(
        ones: List<Bar>,
        side: Int,
        window: Int = FK_SEMI_VOL_WINDOW,
        minBars: Int = FK_SEMI_VOL_MIN_BARS,
    ): Double? {
        if (side == 0 || ones.isEmpty() || window <= 0) return null
        val sessionDate = ones.maxOf { it.start.toLocalDate() }
        val dayBars = ones.filter { it.start.toLocalDate() == sessionDate && it.close.isFinite() && it.close > 0.0 }
            .sortedBy { it.start }
        if (dayBars.size < 2) return null
        val rets = ArrayList<Double>(dayBars.size - 1)
        for (i in 1 until dayBars.size) {
            rets += kotlin.math.ln(dayBars[i].close / dayBars[i - 1].close)
        }
        val win = rets.takeLast(window)
        if (win.size < minBars) return null
        var sumSq = 0.0
        for (r in win) {
            if (side > 0 && r > 0.0) sumSq += r * r
            else if (side < 0 && r < 0.0) sumSq += r * r
        }
        val meanSq2 = (sumSq / win.size) * 2.0
        return kotlin.math.sqrt(meanSq2) * ANNUALIZE_1M_PCT
    }

    fun feynmanKacSemiVolRatio(semiVolPct: Double?, atmIvPct: Double?): Double? {
        if (semiVolPct == null || atmIvPct == null || !semiVolPct.isFinite() || !atmIvPct.isFinite()) return null
        if (semiVolPct < 0.0 || atmIvPct <= 0.0) return null
        return semiVolPct / maxOf(1.0, atmIvPct)
    }

    fun passesFeynmanKacSemiVolGate(
        semiVolPct: Double?,
        atmIvPct: Double?,
        minRatio: Double = MIN_FK_SEMI_VOL_RATIO,
    ): Boolean {
        val ratio = feynmanKacSemiVolRatio(semiVolPct, atmIvPct) ?: return false
        return ratio >= minRatio - 1e-12
    }
}
