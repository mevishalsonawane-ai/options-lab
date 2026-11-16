package com.optionslab.engine.orb

import com.optionslab.engine.sandbox.SandboxCosts
import java.math.BigDecimal
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Boss's exit rule on three arms' trades - Liquidity 15+5 (every book and index), R51 Donchian 20 and R72 Breadth Donchian - on paper
 * and live. (The object keeps its first name, StepTrail, so every caller, the persisted flag and the switch carry over.)
 *
 * 15 Nov 2026, third version (Boss, after the whole R176 - R179 table: "Switch to + picker + breakeven at +10% (V4)"), replacing the
 * ₹500 step trail of the morning (and the ₹1,500 stop a lot + breakeven at +5% that never left its branch):
 *  - no rupee stop at the entry: each arm keeps its own stop (Liquidity and R51 / R72's -15%) - [stopLevel] is always null;
 *  - once the option has traded 10% above the fill, the stop moves to breakeven: the fill plus the round trip's charges
 *    ([ProfitLock.roundTripPerUnit] for one lot), so a sale there nets about ₹0. It only ever moves up. No fixed target.
 *  - R72 / R51's R151 picker (CLAUDE.md's fourth exception) stays as it is: V4 is the picker plus this breakeven.
 * Per unit the distance depends only on the fill and the lot size, so the level is the same whatever the lots. Levels are rounded UP to
 * the tick (the safe side). Every exit the arm already has stays (its -15% stop, ladders and locks, L25, time stops, index exits,
 * square-offs): whichever comes first wins. A stop order fills at the market, so a fast fall can sell under breakeven. It only lowers risk
 * (a higher stop, an earlier exit), so it acts on live through every existing live gate (CLAUDE.md's LIVE rule).
 *
 * FAILED GATES, Boss's exception (CLAUDE.md, the fifth): R179's V4 replayed it on one year ([RECORD]) and it failed the gates (its twin
 * q 1.00; R72's final win rate 53.5%). Labelled [STATUS] wherever it is shown, logged in the Autopilot change timeline, one tap off.
 *
 * 16 Nov 2026, fourth version (Boss, after R186: "For r51/r72 use Step lock (+₹500 at +₹1,000, and so on) And for liquidity use Breakeven
 * at +₹1,000"), ON TOP of V4 for the trades that enter with it ([Lock]; a trade keeps the version it entered with):
 *  - R51 / R72 ([Lock.L500], R186's L500): once the best open P&L reaches +₹1,000 a lot net after the round trip's charges, the stop rises to
 *    the premium whose sale nets +₹500 a lot; then it stays ₹500 a lot under each further ₹500-a-lot step of the best (+1,500 locks +1,000,
 *    +2,000 locks +1,500, ...).
 *  - Liquidity 15+5 ([Lock.BE1000], R186's L-BE1000): once the best open P&L reaches +₹1,000 a lot net, the stop moves to breakeven - R186's:
 *    the stop premium whose stop sale nets ₹0 on the whole quantity after charges and the stop's slippage ([stopPrice]), a little above
 *    V4's breakeven ([breakevenLevel]: the fill plus one lot's charges) - so breakeven at +10% or +₹1,000 a lot, whichever comes first;
 *    once both are reached the higher of the two applies. Nothing more.
 *  The levels are r186engine.lock_levels' ([Ladder]): "net" is (premium - fill) x the entry quantity - the round trip's charges on that
 *  whole quantity (the engine's F&O schedule, [SandboxCosts]), with the rupees x lots (lots = entry quantity / lot size); a step is
 *  reached when the best price is at or above the premium that nets it (on the tick, rounded up), and the lock is the stop premium whose
 *  stop sale nets the kept rupees after the research's stop slippage ([stopPrice]: Liquidity's ₹0 on the whole quantity; V4's +10%
 *  breakeven is unchanged). A stop order fills at the market, so a fast fall can still sell under it. Only ever raises the stop; the
 *  higher of the breakeven and the lock wins. Failed R186's gates ([LOCK_RECORD]).
 * Signal source: the arms' entries stay on the index; only the stop is on the premium (CLAUDE.md allows stops and locks on the option
 * premium). Pure: no I/O, no clock.
 */
object StepTrail {
    /** The gain above the fill, percent, at which the stop moves to breakeven. */
    const val BREAKEVEN_PCT = 10.0

    const val STATUS = "failed gates, Boss's exception"
    /** How the arms' rows and the trades name it. */
    const val LABEL = "breakeven at +10 percent (failed gates, Boss's exception)"
    /** Bot settings' switch. */
    const val SWITCH = "Breakeven at +10 percent (Liquidity, R51, R72) — failed gates, Boss's exception"
    /** The diagnostics' name: no rupee sign and no grouped figure (the uploaded diagnostics hide every rupee figure). */
    const val DIAG = "breakeven at 10 percent (failed gates, Boss's exception)"
    /** An exit by the breakeven stop, as the trade record and the diagnostics say it. */
    const val WHY_TRAIL = "breakeven lock"
    /**
     * The entry's rupee stop: this version has none (each arm's own stop applies), so nothing new is booked with it; the morning's first
     * version (₹500 a lot) wrote it, and its older trade records still carry it.
     */
    const val WHY_STOP = "₹500 stop"
    /** The first version's trail word (15 Nov morning): older trade records still carry it. */
    const val OLD_WHY_TRAIL = "step trail"
    /** The rule in one line. */
    const val RULE = "each trade of Liquidity 15+5, R51 Donchian 20 and R72 Breadth Donchian keeps its arm's own stop; once the option is 10% " +
        "above the fill the stop moves to breakeven (the fill plus the charges), never down; R72 / R51 keep the R151 picker; the arm's own " +
        "exits stay and the first one wins; paper and live"
    /** The honest record. */
    const val RECORD = "R179's V4 (the R151 picker plus a breakeven at +10%) on one year (20 Oct 2025 - 19 Oct 2026, 1 lot): R72 won 56.6% and " +
        "made ₹3.25 L (the picker alone 51.9%, ₹3.72 L), R51 55.5% and ₹2.50 L (50.8%, ₹2.93 L), Liquidity 41.3% and ₹1.00 L (its own rules " +
        "39.2%, ₹1.18 L); it failed the gates (twin q 1.00; R72's final win rate 53.5%)"

    /**
     * 16 Nov, fourth version: the rupee lock a trade entered with ON TOP of the breakeven at +10% (none on a trade: V4 alone, as it
     * entered). [L500]: R51 / R72's ₹500 step lock from +₹1,000 a lot; [BE1000]: Liquidity's breakeven at +₹1,000 a lot.
     */
    enum class Lock { L500, BE1000 }

    /** The best open P&L a lot, net after charges, at which the lock starts (both locks). */
    const val LOCK_FROM = 1000.0
    /** [Lock.L500]'s step of the best open P&L a lot, and the gap the lock keeps under the last step reached. */
    const val LOCK_STEP = 500.0
    const val LOCK_GAP = 500.0

    /**
     * The stop sale's slippage the lock's premium is padded for, as R186 (r106engine.stop_price with r51 engine's SLIP: the sale fills at
     * 0.999 of the stop less twice the half-spread). R186 took the half-spread from the option's 5-minute volume at the fill (1, 2 or 4
     * ticks); the app has no volume at the fill on every path, so it takes [SLIP_TICKS] (1 tick: the liquid index options these arms buy).
     */
    const val SLIP_FILL = 0.999
    const val SLIP_TICKS = 1.0

    /** An exit by [Lock.L500]'s step lock, as the trade record and the diagnostics say it ([Lock.BE1000]'s is [WHY_TRAIL]: a breakeven). */
    const val WHY_LOCK = "step lock"

    /** The fourth version's labels, by lock (how the arms' rows and the trades name it). */
    const val LABEL_L500 = "breakeven at +10 percent and ₹500 step lock from +₹1,000 a lot (failed gates, Boss's exception)"
    const val LABEL_BE1000 = "breakeven at +10 percent or +₹1,000 a lot (failed gates, Boss's exception)"
    /** Bot settings' second switch: the fourth version on top of the breakeven at +10% (off: V4 alone for new trades). */
    const val SWITCH_LOCK = "Step lock (R51, R72) and breakeven at +₹1,000 (Liquidity) — failed gates, Boss's exception"
    /** The diagnostics' name of the fourth version: no rupee sign and no grouped figure (the uploaded diagnostics hide every rupee figure). */
    const val DIAG_LOCK = "step lock on R51 and R72, breakeven at the first step on Liquidity (failed gates, Boss's exception)"
    /** The fourth version in one line. */
    const val LOCK_RULE = "on top of the breakeven at +10%: R51 Donchian 20 and R72 Breadth Donchian lock +₹500 a lot (net after charges) once " +
        "the best open P&L is +₹1,000 a lot, then stay ₹500 a lot under each further ₹500 step (+1,500 locks +1,000, +2,000 locks +1,500, ...); " +
        "Liquidity 15+5 moves its stop to breakeven at +₹1,000 a lot, if that comes before +10%; the stop only rises, the first exit wins; paper and live"
    /** R186's honest record of the fourth version. */
    const val LOCK_RECORD = "R186 on one year (20 Oct 2025 - 19 Oct 2026, 1 lot): R72 with the step lock 1,097 trades, 64.4% won, ₹2.13 L " +
        "(worst month -₹13,690) vs V4's 56.6% and ₹3.25 L; R51 1,430 trades, 62.7%, ₹1.63 L (worst month -₹29,871) vs 55.5% and ₹2.50 L; " +
        "Liquidity with breakeven at +₹1,000 688 trades, 44.5%, ₹98,239 (worst month -₹32,670) vs 41.3% and ₹1.00 L; it failed the gates " +
        "(less money than V4; twin BH q 1.00)"

    private const val EPS = 1e-9
    /** Steps a lock looks through at most (₹500 a lot each: far beyond any option's move). */
    private const val MAX_STEPS = 400

    /** The NSE options' tick. */
    const val TICK = 0.05

    /** The arm sources it covers: R51, R72 and every Liquidity 15+5 book (and its one switch's source). Nothing else (MCX never). */
    val SOURCES: Set<String> = setOf(DonchianArm.SOURCE, BreadthDonchianArm.SOURCE, LiquidityRules.ARM.source) + LiquidityRules.BOOKS.map { it.source }

    /** Whether the arm [source] is one of the three. */
    fun covers(source: String): Boolean = source in SOURCES

    /** 16 Nov: the fourth version's lock for the arm [source]: [Lock.L500] for R51 / R72, [Lock.BE1000] for every Liquidity book; null for any other arm. */
    fun lockFor(source: String): Lock? = when {
        source == DonchianArm.SOURCE || source == BreadthDonchianArm.SOURCE -> Lock.L500
        covers(source) -> Lock.BE1000
        else -> null
    }

    /** [lock]'s label (null: V4's [LABEL]). */
    fun labelOf(lock: Lock?): String = when (lock) { Lock.L500 -> LABEL_L500; Lock.BE1000 -> LABEL_BE1000; null -> LABEL }

    /** The round trip's charges in rupees for [qty] bought at [entry] and sold at [exit] (the engine's F&O schedule, [SandboxCosts]). */
    fun roundTrip(entry: Double, exit: Double, qty: Int): Double =
        SandboxCosts.charge("BUY", BigDecimal(entry), qty).toDouble() + SandboxCosts.charge("SELL", BigDecimal(exit.coerceAtLeast(0.0)), qty).toDouble()

    /** The P&L of [qty] bought at [entry] if sold at [exit], net after the round trip's charges. */
    fun netAt(entry: Double, exit: Double, qty: Int): Double = (exit - entry) * qty - roundTrip(entry, exit, qty)

    /**
     * r106engine.target_price: the premium on the [tick] (rounded up) whose sale nets [net] rupees for [qty] bought at [entry] after the
     * round trip's charges on the whole quantity - its 6 fixed-point passes, then up to the tick. Null: nothing can be told.
     */
    fun netPrice(entry: Double, qty: Int, net: Double, tick: Double = TICK): Double? {
        if (!entry.isFinite() || entry <= 0 || qty <= 0 || !net.isFinite() || !tick.isFinite() || tick <= 0) return null
        var x = entry + net / qty
        repeat(6) { x = entry + (net + roundTrip(entry, x, qty)) / qty }
        return ceilTick(x, tick)
    }

    /**
     * r106engine.stop_price with a kept [keep] (its loss -keep): the stop premium on the [tick] (rounded up) whose stop sale - at [SLIP_FILL]
     * of the stop less twice [slipTicks] ticks - nets [keep] rupees for [qty] bought at [entry] after the round trip's charges. Null: the
     * whole premium is worth less, or nothing can be told.
     */
    fun stopPrice(entry: Double, qty: Int, keep: Double, tick: Double = TICK, slipTicks: Double = SLIP_TICKS): Double? {
        if (!entry.isFinite() || entry <= 0 || qty <= 0 || !keep.isFinite() || !tick.isFinite() || tick <= 0 || !(slipTicks >= 0)) return null
        var rx = entry + keep / qty
        repeat(6) { rx = entry + (roundTrip(entry, maxOf(rx, 0.0), qty) + keep) / qty }
        if (rx <= 0) return null
        return ceilTick((rx + 2.0 * slipTicks * tick) / SLIP_FILL, tick)
    }

    /** [qty] in lots of [lot] (per-lot rupees scale by it); 0 when either is unusable. */
    private fun lotsOf(lot: Int, qty: Int): Double = if (lot > 0 && qty > 0) qty.toDouble() / lot else 0.0

    /**
     * 16 Nov: one trade's lock levels, as r186engine.lock_levels builds them from the trade's fill [entry], its contract's [lot] and its
     * ENTRY quantity [qty] (lots = [qty] / [lot]; a part sold later never moves them). Step k's trigger is the premium whose sale nets
     * (+₹1,000 + ₹500 k) a lot ([netPrice]); [Lock.L500]'s lock there is the stop premium whose stop sale nets (+₹500 + ₹500 k) a lot
     * ([stopPrice]); [Lock.BE1000] has one step, its lock the stop premium whose stop sale nets ₹0 on the whole quantity. Built once per
     * trade by its host and kept: each level is worked out once, when first asked (thread-safe). Pure: no I/O.
     */
    class Ladder(val entry: Double, val lot: Int, val qty: Int, val lock: Lock, val tick: Double = TICK, val slipTicks: Double = SLIP_TICKS) {
        val lots: Double = lotsOf(lot, qty)
        private val ok = usable(entry, lot, tick) && qty > 0 && lots > 0
        private val triggers = HashMap<Int, Double?>()
        private val keeps = HashMap<Int, Double?>()

        /** Whether this ladder was built for these inputs (a host rebuilds it when the trade's fill or entry quantity is not these). */
        fun isFor(entry: Double, lot: Int, qty: Int, lock: Lock, tick: Double): Boolean =
            this.entry == entry && this.lot == lot && this.qty == qty && this.lock == lock && this.tick == tick

        /** Step [k]'s trigger premium (null: none). */
        fun trigger(k: Int): Double? = if (!ok || k < 0 || k > MAX_STEPS) null
            else synchronized(triggers) { triggers.getOrPut(k) { netPrice(entry, qty, (LOCK_FROM + LOCK_STEP * k) * lots, tick) } }

        /** Step [k]'s lock premium (null: none). */
        fun keep(k: Int): Double? = if (!ok || k < 0 || k > MAX_STEPS || (lock == Lock.BE1000 && k > 0)) null
            else synchronized(keeps) { keeps.getOrPut(k) {
                stopPrice(entry, qty, if (lock == Lock.BE1000) 0.0 else (LOCK_FROM - LOCK_GAP + LOCK_STEP * k) * lots, tick, slipTicks) } }

        private fun reached(k: Int, best: Double): Boolean = trigger(k)?.let { best >= it - EPS } == true

        /** The highest step the best price [best] has reached (0: +₹1,000 a lot; null: none yet, or not a price). */
        fun step(best: Double?): Int? {
            if (!ok || best == null || !best.isFinite() || !reached(0, best)) return null
            if (lock == Lock.BE1000) return 0
            // From the step the net at the best points to, settled on the exact one (the triggers are on the tick).
            var k = floor((netAt(entry, best, qty) / lots - LOCK_FROM) / LOCK_STEP).toInt().coerceIn(0, MAX_STEPS)
            while (k > 0 && !reached(k, best)) k--
            while (k < MAX_STEPS && reached(k + 1, best)) k++
            return k
        }

        /** The lock now for the best price [best] (null: no step reached, or a level that would not sit under the best). */
        fun level(best: Double?): Double? {
            val k = step(best) ?: return null
            return keep(k)?.takeIf { it < best!! - EPS }
        }

        /**
         * What a stop resting at [trigger] is under this lock, for a trade whose best price so far is [best]: [WHY_LOCK] at an L500 lock,
         * [WHY_TRAIL] at BE1000's, within half a tick - only a step the best has REACHED (review: a level of the arm's own lock that
         * happens to sit near a step not reached is never named the step lock; a null [best]: not known, any step). Null: none.
         */
        fun why(trigger: Double?, best: Double?): String? {
            if (!ok || trigger == null || !trigger.isFinite()) return null
            // (A null best: not known - a restart that lost the minutes - so any step may be named, as before.)
            val reachedTo = if (best == null) MAX_STEPS else step(best) ?: return null
            if (lock == Lock.BE1000) return keep(0)?.takeIf { abs(trigger - it) < tick / 2 }?.let { WHY_TRAIL }
            val k = Math.round((netAt(entry, trigger, qty) / lots - (LOCK_FROM - LOCK_GAP)) / LOCK_STEP).toInt()
            for (j in maxOf(0, k - 2)..minOf(reachedTo, k + 1)) keep(j)?.let { if (abs(trigger - it) < tick / 2) return WHY_LOCK }
            return null
        }
    }

    private data class LadderKey(val entry: Double, val lot: Int, val qty: Int, val lock: Lock, val tick: Double, val slipTicks: Double)
    /** Every host's trade ladders ([ladder]), memory only; bounded ([LADDERS_KEPT]: cleared when full, each rebuilt on its next ask). */
    private val ladders = java.util.concurrent.ConcurrentHashMap<LadderKey, Ladder>()
    private const val LADDERS_KEPT = 512

    /**
     * 16 Nov (review): the one memoising Ladder factory the hosts on the full-cadence paths use (the arms' paper and live books, the passed
     * arms' walk): one per trade - its fill [entry], contract [lot], ENTRY quantity [qty], [lock] and [tick] - each level worked out once.
     * One-off readers (the trade explanations, a popup's words) build their own [Ladder] and never fill this.
     */
    fun ladder(entry: Double, lot: Int, qty: Int, lock: Lock, tick: Double = TICK, slipTicks: Double = SLIP_TICKS): Ladder {
        val key = LadderKey(entry, lot, qty, lock, tick, slipTicks)
        ladders[key]?.let { return it }
        if (ladders.size >= LADDERS_KEPT) ladders.clear()
        return ladders.getOrPut(key) { Ladder(entry, lot, qty, lock, tick, slipTicks) }
    }

    /** Forgets every kept ladder (erase, the tests' restart): each is rebuilt on its next ask. */
    fun clearLadders() = ladders.clear()

    /** How many ladders are kept (the tests). */
    internal fun laddersKept(): Int = ladders.size

    /** Step [k]'s trigger for [qty] bought at [entry] on a contract of [lot] (a one-off ladder, never kept: the hosts use [ladder]). */
    fun lockTrigger(entry: Double, lot: Int, qty: Int, k: Int, tick: Double = TICK): Double? = Ladder(entry, lot, qty, Lock.L500, tick).trigger(k)

    /** [lock]'s lock at step [k] (a one-off ladder, never kept). */
    fun lockKeep(entry: Double, lot: Int, qty: Int, k: Int, lock: Lock, tick: Double = TICK): Double? = Ladder(entry, lot, qty, lock, tick).keep(k)

    /**
     * 16 Nov (review): where a stop sale at [px] fills as R186 books it (r51 engine's stop fill: 0.999 of the price, to the paisa, less
     * twice [slipTicks] half-spread ticks; never under 0) - the step lock's paper record is booked there, never at the padded stop itself.
     */
    fun stopFill(px: Double, tick: Double = TICK, slipTicks: Double = SLIP_TICKS): Double =
        maxOf(Math.rint(px * SLIP_FILL * 100) / 100 - 2.0 * slipTicks * tick, 0.0)


    /** The round trip's charges per unit for one lot of [lot] units bought at [entry] (the engine's F&O schedule). */
    fun chargesPerUnit(entry: Double, lot: Int): Double = ProfitLock.roundTripPerUnit(entry, lot).takeIf { it.isFinite() && it >= 0 } ?: 0.0

    /**
     * The rule's own stop at the entry for a long position filled at [entry]: none in this version (each arm keeps its own stop), so always
     * null. Kept so the callers (and [tighterStop]) carry over unchanged: with null, the arm's own stop is the one that applies.
     */
    @Suppress("UNUSED_PARAMETER")
    fun stopLevel(entry: Double, lot: Int, tick: Double = TICK): Double? = null

    /** The price at which the stop moves to breakeven: [BREAKEVEN_PCT] above the fill. */
    fun breakevenTrigger(entry: Double): Double = entry * (1 + BREAKEVEN_PCT / 100)

    /** The breakeven stop: the fill plus the round trip's charges a unit, rounded UP to the [tick] (null: nothing can be told). */
    fun breakevenLevel(entry: Double, lot: Int, tick: Double = TICK): Double? =
        if (!usable(entry, lot, tick)) null else up(entry + chargesPerUnit(entry, lot), tick)

    /**
     * The premium the breakeven holds for a position filled at [entry] whose best price so far is [best]: null until the best has reached
     * [breakevenTrigger] (or when the breakeven would not sit under the best, which would sell at once). 16 Nov (fourth version): with the
     * trade's [ladder], the higher of that and the lock ([Ladder.level]), each once earned.
     */
    fun trailLevel(entry: Double, lot: Int, best: Double?, tick: Double = TICK, ladder: Ladder? = null): Double? {
        val rupee = ladder?.level(best)
        if (!usable(entry, lot, tick) || best == null || !best.isFinite() || best < breakevenTrigger(entry) - EPS) return rupee
        return listOfNotNull(breakevenLevel(entry, lot, tick)?.takeIf { it < best - EPS }, rupee).maxOrNull()
    }

    /** The rule's stop now: the breakeven (or, 16 Nov, the [ladder]'s lock) once earned (null: not yet; the rule sets no entry stop, [stopLevel]). */
    fun levelOf(entry: Double, lot: Int, best: Double?, tick: Double = TICK, ladder: Ladder? = null): Double? =
        listOfNotNull(stopLevel(entry, lot, tick), trailLevel(entry, lot, best, tick, ladder)).maxOrNull()

    /** The stop that applies: the higher (tighter, for a long) of the arm's [own] and the rule's [rule]; never a looser one. */
    fun tighterStop(own: Double?, rule: Double?): Double? = when {
        rule == null -> own
        own == null -> rule
        else -> maxOf(own, rule)
    }

    /** Whether the rule's stop [rule] is strictly tighter than the arm's [own] (none counts as looser). */
    fun isTighter(own: Double?, rule: Double?): Boolean = rule != null && (own == null || rule > own + EPS)

    /** Whether the price now [ltp] (null or not a price: never) is at or under [stop] (null: none). */
    fun stopHit(ltp: Double?, stop: Double?): Boolean = stop != null && ltp != null && ltp.isFinite() && ltp > 0 && ltp <= stop + EPS

    /**
     * What a stop resting at [trigger] is, for a position filled at [entry]: [WHY_TRAIL] at the breakeven, within half a [tick]; 16 Nov:
     * the [ladder]'s ([Ladder.why]); null: not the rule's (the arm's own stop or lock).
     */
    fun whyAt(trigger: Double?, entry: Double, lot: Int, tick: Double = TICK, ladder: Ladder? = null, best: Double? = null): String? {
        if (trigger == null || !usable(entry, lot, tick)) return null
        breakevenLevel(entry, lot, tick)?.let { if (abs(trigger - it) < tick / 2) return WHY_TRAIL }
        return ladder?.why(trigger, best)
    }

    /** Whether [why] is one of the rule's exit words, any version's (older records too). */
    fun isRuleWhy(why: String?): Boolean = why == WHY_STOP || why == WHY_TRAIL || why == OLD_WHY_TRAIL || why == WHY_LOCK

    /** Whether [why] is the rule's entry stop (the first version's ₹500, on older records: a stop hit, for the findings). */
    fun isRuleStop(why: String?): Boolean = why == WHY_STOP

    /** An exit reason as a fill card or a line shows it: the rule's own words (this version's or the first's) with [STATUS] after them; any other as it is. */
    fun words(why: String): String = if (isRuleWhy(why)) "$why ($STATUS)" else why

    private fun usable(entry: Double, lot: Int, tick: Double): Boolean = entry.isFinite() && entry > 0 && lot > 0 && tick.isFinite() && tick > 0

    /**
     * r106engine._ceil_tick: [x] up to the [tick], the ticks rounded half-even to 6 decimals first (Python's round), so the lock's levels
     * are the research's to the tick (V4's breakeven keeps [up]).
     */
    private fun ceilTick(x: Double, tick: Double): Double {
        val n = ceil(Math.rint(x / tick * 1e6) / 1e6)
        return java.math.BigDecimal.valueOf(tick).multiply(java.math.BigDecimal.valueOf(n.toLong())).toDouble()
    }

    /** [x] up to the next [tick] (a value already on a tick stays), kept to the tick's decimals. */
    private fun up(x: Double, tick: Double): Double {
        val n = ceil(x / tick - 1e-6)
        return java.math.BigDecimal.valueOf(tick).multiply(java.math.BigDecimal.valueOf(n.toLong())).toDouble()
    }
}
