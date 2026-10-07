package com.optionslab.engine.orb

import com.optionslab.engine.sandbox.SandboxCosts
import java.math.BigDecimal
import java.util.Locale

/**
 * The owner's profit-lock ladder (2026-10-01) for the arms with a fixed premium target (ORB, ORB Fresh, ORB Sweep,
 * Range Fade): once the option has traded 25% of the way to its target the stop moves to the price paid, at 50% to
 * price paid + 25% of the target, at 75% to price paid + 50%. A trade that got most of the way and turned back no
 * longer runs to the full -40 stop. Since 07 Oct (research/HUNT_H20.md) the lock MOVES THE RESTING STOP ([raise]): the
 * -40 SL order is modified up to the lock (never down), so the exit happens at the exchange / in the paper book when the
 * price reaches it, with the app closed too; the best price is every stream tick or the 1-minute candles' highs.
 * ORB Sweep's rungs are on its +80 target: +20 / +40 / +60 ([rungs]). Backtest (research/PROFIT_LOCK.md, two BANKNIFTY years): ORB -218k / -194k ->
 * -77k / -70k, ORB Fresh -69k / -57k -> -17k / -6k; Sweep and Range Fade about unchanged.
 * Liquidity 15+5 has no fixed target (it sells at the next liquidity level), so it is not laddered.
 * The Pine scripts add a percentage trail on the gain ([Trail], 2026-10-06), so a script with no stop or target is
 * locked too; their lock is the higher of the ladder and the trail ([lockLevel]).
 * Pure: no clock, no network, no orders.
 */
object ProfitLock {
    /** (share of the target reached, share of the target locked in). */
    val LADDER: List<Pair<Double, Double>> = listOf(0.25 to 0.0, 0.50 to 0.25, 0.75 to 0.50)
    private const val EPS = 1e-9

    /** The arm's premium target in points, or null for an arm without one. */
    fun targetOf(arm: Arm): Double? = when {
        arm.liquidity -> null
        arm.hero -> null              // the Hero arm has no target: it holds to its exit time
        arm.sweep -> SweepRules.TARGET_POINTS
        else -> OrbRules.TARGET_POINTS
    }

    /**
     * The ladder's reference for a Pine auto-trade script (2026-10-06), whose premium stop and target are the owner's own
     * numbers: its target in points when set; with no target but a stop, twice the stop (the same 1 : 2 the ORB arms use,
     * -40 / +80); with neither, null - no ladder, as there is nothing to measure the way to the target by (the
     * percentage [Trail] still locks such a script).
     */
    fun pineReference(targetPts: Double, stopPts: Double): Double? = when {
        targetPts.isFinite() && targetPts > 0 -> targetPts
        stopPts.isFinite() && stopPts > 0 -> 2 * stopPts
        else -> null
    }

    /**
     * The locked stop for a buy at [entry] whose best price so far is [peak], or null below the first rung.
     *
     * [costPerUnit] (the round trip's charges per unit, [roundTripPerUnit]; Boss's 06 Oct fix): no rung locks less than
     * the buy price plus the charges, so the "breakeven" rung is a real breakeven after charges and a protective exit is
     * never a certain small loss booked as a profit lock. A rung whose level would not sit below the best price seen
     * does not count (it would sell at once), so the lock only ever rises with the best price and never widens risk.
     * 0 (the default) is the plain ladder the backtests and the replay use.
     */
    fun level(entry: Double, target: Double, peak: Double, costPerUnit: Double = 0.0): Double? {
        val be = entry + (if (costPerUnit.isFinite()) costPerUnit.coerceAtLeast(0.0) else 0.0)
        return LADDER.filter { peak >= entry + it.first * target - EPS }.map { maxOf(entry + it.second * target, be) }
            .filter { it < peak }.maxOrNull()
    }

    /**
     * True when [ltp] is at or below the lock earned by the best price seen BEFORE it ([peakBefore]): a rung counts
     * from the next look on, as the backtest applies it from the next minute. [costPerUnit] as in [level].
     */
    fun exits(entry: Double, target: Double, peakBefore: Double, ltp: Double, costPerUnit: Double = 0.0): Boolean =
        level(entry, target, peakBefore, costPerUnit)?.let { ltp <= it + EPS } == true

    // ---- the lock as the resting stop (2026-10-07, research/HUNT_H20.md F1-F6) ---------------------------------------

    /**
     * The rungs on [target] in premium points: (how far up, what is locked). ORB / ORB Fresh / Range Fade (+40):
     * +10 -> breakeven, +20 -> +10, +30 -> +20; ORB Sweep (+80): +20 -> breakeven, +40 -> +20, +60 -> +40.
     */
    fun rungs(target: Double): List<Pair<Double, Double>> = LADDER.map { it.first * target to it.second * target }

    /** The rungs in words for the screens: "+10 → breakeven, +20 → +10, +30 → +20" (+40), "+20 → breakeven, ..." (+80). */
    fun describeRungs(target: Double): String =
        rungs(target).joinToString(", ") { (up, kept) -> "+${n(up)} → " + if (kept == 0.0) "breakeven" else "+${n(kept)}" }

    /** A sell stop's trigger on the [tick] for a lock [level]: rounded UP, so it never sits under the breakeven it locks. */
    fun onTick(level: Double, tick: Double = OrbRules.TICK): Double {
        val n = Math.ceil(level / tick - 1e-6)            // a float hair over a tick is that tick
        return BigDecimal.valueOf(tick).multiply(BigDecimal.valueOf(n.toLong())).toDouble()
    }

    /**
     * The lock as a resting sell stop: the trigger the stop moves UP to for a buy at [entry] whose best price so far is
     * [peak] ([level] on [target], on the [tick]), or null when it must not move: no rung reached, the lock is not above
     * the stop resting now ([current]; a stop is never moved down), or it does not sit below the price now ([ltp]): a sell
     * stop must rest below the price (Zerodha refuses one above it), and a price already at or under the lock is sold by
     * the app's own check instead.
     */
    fun raise(entry: Double, target: Double, peak: Double, current: Double?, ltp: Double, costPerUnit: Double = 0.0,
              tick: Double = OrbRules.TICK): Double? {
        val lock = level(entry, target, peak, costPerUnit)?.let { onTick(it, tick) } ?: return null
        if (current != null && lock <= current + EPS) return null
        return lock.takeIf { it < ltp - EPS }
    }

    /**
     * The best price since the entry after one more look: the best before it ([peakBefore]), the price now ([ltp]) and the
     * highest price traded since the last look ([high]: every stream tick, else the 1-minute candles' highs; null: none
     * known). The lock this earns counts from the NEXT look ([exits] and [raise] read the best from before it).
     */
    fun nextPeak(peakBefore: Double, ltp: Double?, high: Double?): Double =
        listOfNotNull(peakBefore, ltp?.takeIf { it.isFinite() }, high?.takeIf { it.isFinite() }).max()

    /**
     * The stop resting for a laddered buy: the higher of its own stop ([base], -40; null: none) and the lock its best
     * price [peak] has earned ([level] on [target], on the [tick]); null when it has neither. What the backtests rest, and
     * where a failed exit puts the stop back (never the -40 once a rung is earned).
     */
    fun restingStop(entry: Double, base: Double?, target: Double?, peak: Double, costPerUnit: Double = 0.0,
                    tick: Double = OrbRules.TICK): Double? {
        val lock = target?.let { level(entry, it, peak, costPerUnit) }?.let { onTick(it, tick) }
        return listOfNotNull(base, lock).maxOrNull()
    }

    /**
     * A resting sell stop at [trigger] over one bar ([open], [low]): filled at the trigger, or at the open when the bar
     * opened under it (a gap through the stop); null when the bar never reached it. As an SL-M fills at an exchange.
     */
    fun sellStopFill(trigger: Double, open: Double, low: Double): Double? =
        if (low <= trigger + EPS) minOf(trigger, open) else null

    // ---- the percentage trail (2026-10-06): every Pine script, with or without a stop or target ----------------------

    /**
     * The owner's percentage trail for the Pine scripts (2026-10-06: "my profit went from 10 percent to 2"): measured as the
     * gain of the option's premium from the buy price to its best price since (the peak), so it needs no stop or target.
     * From [breakevenPct] up the stop moves to the buy price plus the round trip's charges (breakeven after charges); from
     * each [Step.startPct] up it keeps [Step.keepPct] percent of the best gain (sells at entry + keep x (best - entry)).
     * The highest level reached counts. 0 switches a rung off. It runs beside the target ladder ([lockLevel]: the higher
     * of the two), only ever sells sooner, and never buys or lowers a stop.
     */
    data class Trail(val breakevenPct: Double = 5.0, val steps: List<Step> = DEFAULT_STEPS) {
        data class Step(val startPct: Double, val keepPct: Double)

        /**
         * Safe numbers: a broken or negative start is off (0), the share kept within 0..95%. The steps keep their places
         * (the screen's boxes); their order does not matter, as the highest level reached counts.
         */
        fun clean(): Trail = Trail(pct(breakevenPct),
            steps.map { Step(pct(it.startPct), if (it.keepPct.isFinite()) it.keepPct.coerceIn(0.0, MAX_KEEP) else 0.0) })

        /** One line for the screen and Jarvis: "from +5% breakeven after charges; from +8% keeps 50% of the best gain, ...". */
        fun describe(): String {
            val parts = ArrayList<String>()
            if (breakevenPct > 0) parts += "from +${n(breakevenPct)}% breakeven after charges"
            val on = steps.filter { it.startPct > 0 }
            if (on.isNotEmpty()) parts += on.mapIndexed { i, s ->
                if (i == 0) "from +${n(s.startPct)}% keeps ${n(s.keepPct)}% of the best gain" else "from +${n(s.startPct)}% ${n(s.keepPct)}%"
            }.joinToString(", ")
            return if (parts.isEmpty()) "trail off" else parts.joinToString("; ")
        }
    }

    /** The default trail's steps: from +8% keep 50% of the best gain, from +20% 65%, from +40% 75%. */
    val DEFAULT_STEPS: List<Trail.Step> = listOf(Trail.Step(8.0, 50.0), Trail.Step(20.0, 65.0), Trail.Step(40.0, 75.0))
    private const val MAX_KEEP = 95.0
    private const val MAX_PCT = 1000.0
    /** Charges as a share of the buy price (percent) when the quantity is not known: a conservative 0.3%. */
    const val FALLBACK_COST_PCT = 0.3

    private fun pct(x: Double) = if (x.isFinite() && x > 0) x.coerceAtMost(MAX_PCT) else 0.0
    private fun n(x: Double) = if (x == Math.floor(x)) x.toLong().toString() else "%.1f".format(Locale.ENGLISH, x)

    /**
     * The round trip's charges per unit for [qty] bought at [entry] (a buy and a sell of the same size), on the engine's
     * F&O schedule ([SandboxCosts.charge]: Rs 20 a leg, STT on the sell, exchange, SEBI, stamp, GST), the sell priced at
     * the breakeven it pays for. With no quantity, [FALLBACK_COST_PCT] of [entry].
     */
    fun roundTripPerUnit(entry: Double, qty: Int): Double {
        if (!(entry > 0)) return 0.0
        if (qty <= 0) return entry * FALLBACK_COST_PCT / 100
        val buy = SandboxCosts.charge("BUY", BigDecimal(entry), qty).toDouble()
        val first = (buy + SandboxCosts.charge("SELL", BigDecimal(entry), qty).toDouble()) / qty
        return (buy + SandboxCosts.charge("SELL", BigDecimal(entry + first), qty).toDouble()) / qty
    }

    /**
     * The trail's stop for a buy at [entry] whose best price so far is [peak] ([costPerUnit]: [roundTripPerUnit]), or
     * null below its first rung. The breakeven rung counts only while the best is above it.
     */
    fun trailLevel(entry: Double, peak: Double, trail: Trail, costPerUnit: Double): Double? {
        if (!(entry > 0) || !(peak > entry)) return null
        val gain = (peak - entry) / entry * 100
        val levels = ArrayList<Double>()
        val be = entry + costPerUnit.coerceAtLeast(0.0)
        if (trail.breakevenPct > 0 && gain >= trail.breakevenPct - EPS && be < peak) levels += be
        for (s in trail.steps) if (s.startPct > 0 && gain >= s.startPct - EPS) levels += entry + s.keepPct / 100 * (peak - entry)
        return levels.maxOrNull()
    }

    /**
     * The Pine lock's stop: the higher of the target ladder's ([ref], from [pineReference]; null for none) and the
     * trail's ([trail]; null for none), or null while neither has a rung reached.
     */
    fun lockLevel(entry: Double, ref: Double?, trail: Trail?, costPerUnit: Double, peak: Double): Double? =
        listOfNotNull(ref?.let { level(entry, it, peak, costPerUnit) }, trail?.let { trailLevel(entry, peak, it, costPerUnit) }).maxOrNull()

    /** True when [ltp] is at or below [lockLevel] earned by the best price seen BEFORE it ([peakBefore]), as [exits]. */
    fun lockExits(entry: Double, ref: Double?, trail: Trail?, costPerUnit: Double, peakBefore: Double, ltp: Double): Boolean =
        lockLevel(entry, ref, trail, costPerUnit, peakBefore)?.let { ltp <= it + EPS } == true
}
