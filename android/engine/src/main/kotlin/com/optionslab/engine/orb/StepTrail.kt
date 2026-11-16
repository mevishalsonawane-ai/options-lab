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
 *  - Liquidity 15+5 ([Lock.BE1000], R186's L-BE1000): once the best open P&L reaches +₹1,000 a lot net, the stop moves to breakeven (the
 *    same level as the +10% breakeven), so breakeven at +10% or +₹1,000 a lot, whichever comes first. Nothing more.
 *  "Net" as r186engine.lock_levels: (premium - fill) x quantity - the round trip's charges on the whole quantity (the engine's F&O schedule,
 *  [SandboxCosts]), with the rupees x lots (lots = quantity / lot size); a step is reached when the best price is at or above the premium
 *  that nets it (on the tick, rounded up), and the lock is the premium whose sale nets the kept rupees (rounded up). Unlike the research,
 *  the lock has no stop-slippage pad (as V4's breakeven: the fill plus the charges): a stop order fills at the market, so a fast fall
 *  sells under it. Only ever raises the stop; the higher of the breakeven and the lock wins. Failed R186's gates ([LOCK_RECORD]).
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
     * 16 Nov, fourth version: the rupee lock a trade entered with ON TOP of the breakeven at +10% (null on a trade: V4 alone, as it
     * entered). [L500]: R51 / R72's ₹500 step lock from +₹1,000 a lot; [BE1000]: Liquidity's breakeven at +₹1,000 a lot.
     */
    enum class Lock { L500, BE1000 }

    /** The best open P&L a lot, net after charges, at which the lock starts (both locks). */
    const val LOCK_FROM = 1000.0
    /** [Lock.L500]'s step of the best open P&L a lot, and the gap the lock keeps under the last step reached. */
    const val LOCK_STEP = 500.0
    const val LOCK_GAP = 500.0

    /** An exit by [Lock.L500]'s step lock, as the trade record and the diagnostics say it ([Lock.BE1000]'s is [WHY_TRAIL]: the same breakeven). */
    const val WHY_LOCK = "step lock"

    /** The fourth version's labels, by lock (how the arms' rows and the trades name it). */
    const val LABEL_L500 = "breakeven at +10 percent and ₹500 step lock from +₹1,000 a lot (failed gates, Boss's exception)"
    const val LABEL_BE1000 = "breakeven at +10 percent or +₹1,000 a lot (failed gates, Boss's exception)"
    /** Bot settings' switch, fourth version. */
    const val SWITCH_V5 = "Step lock (R51, R72) and breakeven at +₹1,000 (Liquidity), on breakeven at +10 percent — failed gates, Boss's exception"
    /** The diagnostics' name of the fourth version: no rupee sign and no grouped figure. */
    const val DIAG_V5 = "breakeven at 10 percent plus the step lock (R51, R72) or breakeven at the first step (Liquidity) (failed gates, Boss's exception)"
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
    private const val NET_EPS = 1e-6
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
     * The premium on the [tick] (rounded up) whose sale nets at least [net] rupees for [qty] bought at [entry], after the round trip's
     * charges (r106engine.target_price's fixed point, then up to the tick; checked so it really nets it, else null). Null: nothing can be
     * told. Pure; remembered by its inputs ([netMemo]), so the full-cadence position watch never redoes the charge maths for a level.
     */
    fun netPrice(entry: Double, qty: Int, net: Double, tick: Double = TICK): Double? {
        if (!entry.isFinite() || entry <= 0 || qty <= 0 || !net.isFinite() || !tick.isFinite() || tick <= 0) return null
        val key = NetKey(entry, qty, net, tick)
        netMemo[key]?.let { return it }
        var x = entry + net / qty
        repeat(6) { x = entry + (net + roundTrip(entry, x, qty)) / qty }
        var p = up(x, tick)
        var guard = 0
        while (netAt(entry, p, qty) < net - NET_EPS && guard++ < 50) p = up(p + tick, tick)
        if (netAt(entry, p, qty) < net - NET_EPS) return null
        if (netMemo.size >= MEMO_MAX) netMemo.clear()
        netMemo[key] = p
        return p
    }

    private data class NetKey(val entry: Double, val qty: Int, val net: Double, val tick: Double)
    /** [netPrice]'s levels by their inputs (memory only, bounded: cleared once it holds [MEMO_MAX]). */
    private val netMemo = java.util.concurrent.ConcurrentHashMap<NetKey, Double>()
    private const val MEMO_MAX = 4096

    /** [qty] in lots of [lot] (per-lot rupees scale by it); 0 when either is unusable. */
    private fun lotsOf(lot: Int, qty: Int): Double = if (lot > 0 && qty > 0) qty.toDouble() / lot else 0.0

    /** The premium at which the lock's step [k] is reached: the sale there nets (+₹1,000 + ₹500 x [k]) a lot ([netPrice]). */
    fun lockTrigger(entry: Double, lot: Int, qty: Int, k: Int, tick: Double = TICK): Double? =
        lotsOf(lot, qty).takeIf { it > 0 && k >= 0 }?.let { netPrice(entry, qty, (LOCK_FROM + LOCK_STEP * k) * it, tick) }

    /**
     * The highest step of the lock the best price [best] has reached (0: +₹1,000 a lot; null: none yet), for [qty] bought at [entry] on a
     * contract of [lot]. [Lock.BE1000] has one step only (0).
     */
    fun lockStep(entry: Double, lot: Int, qty: Int, best: Double?, lock: Lock?, tick: Double = TICK): Int? {
        if (lock == null || best == null || !best.isFinite() || !usable(entry, lot, tick) || qty <= 0) return null
        fun reached(k: Int): Boolean = lockTrigger(entry, lot, qty, k, tick)?.let { best >= it - EPS } == true
        if (!reached(0)) return null
        if (lock == Lock.BE1000) return 0
        // Start from the step the net at the best points to, then settle on the exact one (the triggers are the research's: on the tick).
        val lots = lotsOf(lot, qty)
        var k = floor((netAt(entry, best, qty) / lots - LOCK_FROM) / LOCK_STEP).toInt().coerceIn(0, MAX_STEPS)
        while (k > 0 && !reached(k)) k--
        while (k < MAX_STEPS && reached(k + 1)) k++
        return k
    }

    /** The lock's stop at step [k]: [Lock.L500] the premium whose sale nets (+₹500 + ₹500 x [k]) a lot; [Lock.BE1000] the breakeven. */
    fun lockKeep(entry: Double, lot: Int, qty: Int, k: Int, lock: Lock, tick: Double = TICK): Double? = when (lock) {
        Lock.BE1000 -> breakevenLevel(entry, lot, tick)
        Lock.L500 -> lotsOf(lot, qty).takeIf { it > 0 && k >= 0 }?.let { netPrice(entry, qty, (LOCK_FROM - LOCK_GAP + LOCK_STEP * k) * it, tick) }
    }

    /**
     * The fourth version's lock for [qty] bought at [entry] on a contract of [lot] whose best price so far is [best] (null: no lock on the
     * trade, or no step reached yet; or a level that would not sit under the best).
     */
    fun lockLevel(entry: Double, lot: Int, qty: Int, best: Double?, lock: Lock?, tick: Double = TICK): Double? {
        val k = lockStep(entry, lot, qty, best, lock, tick) ?: return null
        return lockKeep(entry, lot, qty, k, lock!!, tick)?.takeIf { it < best!! - EPS }
    }

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
     * [breakevenTrigger] (or when the breakeven would not sit under the best, which would sell at once).
     */
    fun trailLevel(entry: Double, lot: Int, best: Double?, tick: Double = TICK, lock: Lock? = null, qty: Int = lot): Double? {
        // 16 Nov (fourth version): with a [lock], the higher of the breakeven at +10% and the lock ([lockLevel]), each once earned.
        val rupee = lockLevel(entry, lot, qty, best, lock, tick)
        if (!usable(entry, lot, tick) || best == null || !best.isFinite() || best < breakevenTrigger(entry) - EPS) return rupee
        return listOfNotNull(breakevenLevel(entry, lot, tick)?.takeIf { it < best - EPS }, rupee).maxOrNull()
    }

    /** The rule's stop now: the breakeven (or, 16 Nov, the [lock]) once earned (null: not yet; the rule sets no entry stop, [stopLevel]). */
    fun levelOf(entry: Double, lot: Int, best: Double?, tick: Double = TICK, lock: Lock? = null, qty: Int = lot): Double? =
        listOfNotNull(stopLevel(entry, lot, tick), trailLevel(entry, lot, best, tick, lock, qty)).maxOrNull()

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
     * What a stop resting at [trigger] is, for a position filled at [entry]: [WHY_TRAIL] at the breakeven, within half a [tick]; null: not
     * the rule's (the arm's own stop or lock).
     */
    fun whyAt(trigger: Double?, entry: Double, lot: Int, tick: Double = TICK, lock: Lock? = null, qty: Int = lot): String? {
        if (trigger == null || !usable(entry, lot, tick)) return null
        breakevenLevel(entry, lot, tick)?.let { if (abs(trigger - it) < tick / 2) return WHY_TRAIL }
        // 16 Nov: one of [Lock.L500]'s steps ([Lock.BE1000]'s is the breakeven above). The net at the trigger points to the step.
        if (lock == Lock.L500 && qty > 0 && trigger.isFinite()) {
            val k = Math.round((netAt(entry, trigger, qty) / lotsOf(lot, qty) - (LOCK_FROM - LOCK_GAP)) / LOCK_STEP).toInt()
            for (j in (k - 1)..(k + 1)) if (j in 0..MAX_STEPS) lockKeep(entry, lot, qty, j, lock, tick)?.let { if (abs(trigger - it) < tick / 2) return WHY_LOCK }
        }
        return null
    }

    /** Whether [why] is one of the rule's exit words, any version's (older records too). */
    fun isRuleWhy(why: String?): Boolean = why == WHY_STOP || why == WHY_TRAIL || why == OLD_WHY_TRAIL || why == WHY_LOCK

    /** Whether [why] is the rule's entry stop (the first version's ₹500, on older records: a stop hit, for the findings). */
    fun isRuleStop(why: String?): Boolean = why == WHY_STOP

    /** An exit reason as a fill card or a line shows it: the rule's own words (this version's or the first's) with [STATUS] after them; any other as it is. */
    fun words(why: String): String = if (isRuleWhy(why)) "$why ($STATUS)" else why

    private fun usable(entry: Double, lot: Int, tick: Double): Boolean = entry.isFinite() && entry > 0 && lot > 0 && tick.isFinite() && tick > 0

    /** [x] up to the next [tick] (a value already on a tick stays), kept to the tick's decimals. */
    private fun up(x: Double, tick: Double): Double {
        val n = ceil(x / tick - 1e-6)
        return java.math.BigDecimal.valueOf(tick).multiply(java.math.BigDecimal.valueOf(n.toLong())).toDouble()
    }
}
