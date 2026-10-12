package com.optionslab.ira

import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The strategies improve themselves through SHADOW VARIANTS (Boss, 11 Oct: "make the strategies self-improve"). For each running
 * strategy the lab knows its tunable parameters and their SAFE bounds ([STRATEGIES]: only what the arm's own rules have - the
 * last entry, the opening range, a breakout buffer, the stop, the target, the time stop, the edge - see [ArmParams]).
 *
 *  - The lab replays the REAL arms (S3): a variant is the arm's own rule code with other values ([ArmLab]), followed on the
 *    same day's data as VIRTUAL trades (no order, not even paper), settled on the option's 1-minute bars with the paper costs.
 *    Before one starts it must not be worse than the current rules on the stored days (the pre-check).
 *  - Each is compared with the CURRENT rules day by day (the variant's rupees less the current rules' on the same day).
 *  - Promotion needs all of: at least [MIN_N] compared days, the mSPRT's always-valid p ([DiscoveryStats.alwaysValidP]), a
 *    Benjamini-Hochberg pass across EVERY variant ever tried, and the trial-count penalty ([DiscoveryStats.penalizedAlpha])
 *    with every variant ever tried counted.
 *  - The arms trade what the lab promotes (S1), PAPER FIRST and never mid-day: a PAPER strategy's winner is applied to the
 *    arm's paper entries by itself from the next session ([Tune]; logged, undoable: [Change], AUTO) and posted as "variant
 *    ready"; a LIVE strategy's becomes a PROPOSAL to Boss, applied only with his PIN ([accept]), also from the next session.
 *    Undoing a live change needs the PIN unless it puts the defaults back (that only lowers risk) ([undoLive]).
 *  - The safety net ([safety]): an arm under a variant that loses more than [SAFETY_LOSS_X] x its backtest's average losing
 *    day in one day, or [SAFETY_STREAK] losing days in a row below the variant's forward expectation, goes back to its
 *    defaults by itself (lower risk) from the next session, and it is said.
 *  - A variant that is worse (the same test the other way) or runs [MAX_N] days without winning is retired, and a new
 *    neighbour is spawned with half the step (a hill-climb that shrinks).
 *  - Liquidity 15+5, the one proven edge (S9): its variants move its EXITS only (its entries are frozen in code), at most
 *    [LIQUIDITY_MAX] alive, and its promotion bar is stricter (alpha halved).
 *
 * Never: a stop wider than the strategy's researched maximum, more lots (lots are not a parameter at all), an entry outside
 * the F&O hours (the bounds). Pure: the app hands in the days, the runs, the clock's date and the arms' results.
 */
object VariantLab {
    const val MAX_PER_STRATEGY = 6
    const val MAX_TOTAL = 24
    /** Compared days before a variant may be promoted, and the most it runs without winning. */
    const val MIN_N = 20
    const val MAX_N = 60
    const val Q = 0.10
    const val ALPHA = 0.05
    /** The smallest step the hill-climb shrinks to (a share of each parameter's own step). */
    const val MIN_SCALE = 0.25
    /** Retired variants kept in full (older ones are counted, not kept). */
    const val KEEP_RETIRED = 120
    /** The mSPRT's floor on the spread of daily rupees (a near-constant difference never looks infinitely sure). */
    const val MIN_SD = 50.0
    /** Liquidity 15+5 (S9): its variants alive at most, and its promotion alpha (half the others'). */
    const val LIQUIDITY_MAX = 3
    const val LIQUIDITY_ALPHA = ALPHA / 2
    /** Liquidity 15+5's widest stop a variant may try: its own default (15%), never wider (review R1). */
    const val LIQUIDITY_STOP_MAX = com.optionslab.engine.orb.LiquidityRules.PREMIUM_STOP
    /** The safety net: a day's loss beyond this many times the backtest's average losing day reverts the arm to its defaults... */
    const val SAFETY_LOSS_X = 3.0
    /** ...as do this many losing days in a row below the variant's forward expectation. */
    const val SAFETY_STREAK = 3
    /** The smallest "average losing day" the safety net measures against (a backtest with no losing day is not a free pass). */
    const val MIN_AVG_LOSS = 300.0
    /** The arms' parameter changes kept per strategy and account (older ones are history). */
    const val KEEP_TUNES = 12

    enum class Run(val words: String) { PAPER("paper"), LIVE("live"), OFF("not running") }

    /**
     * One tunable: its key ([ArmParams]), plain words, the SAFE bounds, its step and its [grid] (values snap to it: whole minutes,
     * on / off). A stop's [max] is the strategy's researched maximum.
     */
    data class Param(val key: String, val words: String, val min: Double, val max: Double, val step: Double, val unit: Unit, val grid: Double = 0.0) {
        fun clamp(x: Double): Double {
            if (x.isNaN()) return min
            val c = x.coerceIn(min, max)
            return if (grid > 0) ((c / grid).roundToInt() * grid).coerceIn(min, max) else c
        }
        fun say(x: Double): String = when (unit) {
            Unit.PCT -> "%.1f%%".format(Locale.ENGLISH, x * 100).replace(".0%", "%")
            Unit.BPS -> "%.3f%%".format(Locale.ENGLISH, x * 100)
            Unit.PTS -> "${x.roundToInt()} pts"
            Unit.MIN -> "${x.roundToInt()} min"
            Unit.TIME -> ShadowSim.hhmm(x.roundToInt())
            Unit.ONOFF -> if (x >= 0.5) "on" else "off"
            Unit.LOCK -> if (x <= 0) "none" else "%.0f%% of the premium".format(Locale.ENGLISH, x * 100)
            Unit.CAP -> if (x < 0.5) "no cap" else "${x.roundToInt()} a side a day"
        }
    }

    enum class Unit { PCT, BPS, PTS, MIN, TIME, ONOFF, LOCK, CAP }

    /**
     * A strategy the lab can tune: its key (the arm's source), name, indices, its tunables, how many variants may be alive
     * ([maxAlive]) and its promotion alpha. Its rules now are the arm's own constants ([defaults], [ArmParams.defaults]).
     */
    data class Strategy(val key: String, val label: String, val underlyings: List<String>, val params: List<Param>,
                        val maxAlive: Int = MAX_PER_STRATEGY, val alpha: Double = ALPHA) {
        val defaults: Map<String, Double> get() = ArmParams.defaults(key).filterKeys { k -> params.any { it.key == k } }
        fun param(key: String): Param? = params.firstOrNull { it.key == key }
        /** [v] inside the bounds, every tunable present (missing: its default), any other key dropped. */
        fun values(v: Map<String, Double>): Map<String, Double> = defaults + v.filterKeys { param(it) != null }.mapValues { (k, x) -> param(k)!!.clamp(x) }
        /** Null when [v] is a set of values this strategy may trade, else why not (a key it does not have, a value out of bounds). */
        fun invalid(v: Map<String, Double>): String? {
            v.keys.firstOrNull { param(it) == null }?.let { return "$it is not a parameter of $label" }
            for ((k, x) in v) { val p = param(k)!!; if (x.isNaN() || x < p.min - 1e-12 || x > p.max + 1e-12) return "${p.words} outside its bounds" }
            return null
        }
    }

    private val orbUntil = Param(ArmParams.UNTIL, "last entry", 13 * 60.0, 14 * 60 + 30.0, 15.0, Unit.TIME, grid = 5.0)
    private val orbStop = Param(ArmParams.STOP_PTS, "stop", 20.0, 40.0, 5.0, Unit.PTS)
    /** ORB / ORB Fresh's re-entry cap (R14): 0 = none (the default), 1-3 entries a side a day - any cap means fewer entries. */
    private val orbCap = Param(ArmParams.SIDE_CAP, "entries a side a day", 0.0, 3.0, 1.0, Unit.CAP, grid = 1.0)

    /**
     * The strategies the lab tunes, with bounds from their research: the ORB family's stop never wider than its -40 points,
     * entries never after 14:30; Liquidity's stop never wider than its 15% default (review R1, Boss's rule: an automatic change
     * never widens a stop; its research tried up to 25%) and its next-level target always on (never skips an exit), its EXITS only;
     * VIX divergence's stop never wider than its 15%. Night holds overnight, Hero and the MCX arms trade data the lab does not
     * keep: they are not tuned here ([NOT_TUNED]).
     */
    val STRATEGIES: List<Strategy> = listOf(
        Strategy("orb", "ORB", listOf("BANKNIFTY"),
            listOf(orbUntil, Param(ArmParams.OR, "opening range", 35.0, 65.0, 5.0, Unit.MIN, grid = 5.0),
                Param(ArmParams.BUFFER, "breakout buffer", 0.0, 0.001, 0.00025, Unit.BPS), orbStop,
                Param(ArmParams.TARGET_PTS, "target", 30.0, 80.0, 10.0, Unit.PTS), orbCap)),
        Strategy("orb_fresh", "ORB Fresh", listOf("BANKNIFTY"),
            listOf(orbUntil, Param(ArmParams.OR, "opening range", 35.0, 65.0, 5.0, Unit.MIN, grid = 5.0),
                Param(ArmParams.BUFFER, "breakout buffer", 0.0, 0.001, 0.00025, Unit.BPS), orbStop,
                Param(ArmParams.TARGET_PTS, "target", 30.0, 80.0, 10.0, Unit.PTS), orbCap)),
        Strategy("orb_sweep", "ORB Sweep", listOf("BANKNIFTY"),
            listOf(orbUntil, orbStop, Param(ArmParams.TARGET_PTS, "target", 40.0, 100.0, 10.0, Unit.PTS))),
        Strategy("range_fade", "Range Fade", listOf("BANKNIFTY"),
            listOf(orbUntil, Param(ArmParams.EDGE, "edge of the range", 0.05, 0.20, 0.025, Unit.PCT), orbStop,
                Param(ArmParams.TARGET_PTS, "target", 20.0, 60.0, 5.0, Unit.PTS))),
        // S9: exits only (stop, time stop, target rule, lock); its entries are frozen in LiquidityRules. Fewer alive, a stricter bar.
        Strategy("liquidity", "Liquidity 15+5", listOf("BANKNIFTY", "FINNIFTY", "MIDCPNIFTY"),
            listOf(Param(ArmParams.STOP_PCT, "stop", 0.10, LIQUIDITY_STOP_MAX, 0.025, Unit.PCT),
                Param(ArmParams.TS, "time-stop minutes", 10.0, 30.0, 5.0, Unit.MIN, grid = 1.0),
                Param(ArmParams.TSG, "time-stop gain", 0.03, 0.08, 0.01, Unit.PCT),
                Param(ArmParams.TGT, "next-level target", 1.0, 1.0, 1.0, Unit.ONOFF, grid = 1.0),
                Param(ArmParams.LOCK, "profit lock", 0.0, 1.0, 0.15, Unit.LOCK)),
            maxAlive = LIQUIDITY_MAX, alpha = LIQUIDITY_ALPHA),
        Strategy("vix_div", "VIX divergence", listOf("NIFTY", "BANKNIFTY"),
            // The index move steps by 0.025%: shown to 3 decimals (BPS), or 0.175% and 0.2% would both read "0.2%" (G7-7).
            listOf(Param(ArmParams.MOVE, "index move", 0.0015, 0.003, 0.00025, Unit.BPS),
                Param(ArmParams.STOP_PCT, "stop (NIFTY)", 0.10, 0.15, 0.0125, Unit.PCT),
                Param(ArmParams.TS, "time-stop minutes (NIFTY)", 10.0, 30.0, 5.0, Unit.MIN, grid = 1.0))),
    )

    /** Running strategies the lab does not tune, and why (said on the card and by Jarvis). */
    val NOT_TUNED: Map<String, String> = mapOf(
        "night_r3" to "Night holds overnight: the lab follows one session at a time",
        "hero" to "Hero trades NIFTY's expiry-day straddle legs, which the lab does not keep",
        "mcx" to "the MCX arms trade futures the lab does not keep",
    )

    fun strategy(key: String): Strategy? = STRATEGIES.firstOrNull { it.key == key }

    // ---- the book --------------------------------------------------------------------------------------------------------

    enum class Status(val words: String) {
        ALIVE("shadowing"), PROMOTED("promoted"), PROPOSED("proposed to you"), RETIRED("retired"), PRECHECK_FAILED("worse on the stored days"),
    }

    /**
     * One variant: [values] its parameters, [base] the current rules' when it was born (wider or tighter is judged against
     * them), [diffs] its rupees less the current rules' per compared day, [vSum] / [cSum] both totals, [pre] the pre-check
     * (variant, current) rupees on the stored days.
     */
    data class Variant(
        val id: String, val strategy: String, val values: Map<String, Double>, val base: Map<String, Double>, val born: LocalDate,
        val scale: Double, val status: Status, val diffs: List<Double> = emptyList(), val vSum: Double = 0.0, val cSum: Double = 0.0,
        val pBetter: Double = 1.0, val pWorse: Double = 1.0, val lastDay: LocalDate? = null, val why: String = "",
        val pre: Pair<Double, Double>? = null,
    ) {
        val n: Int get() = diffs.size
        val alive: Boolean get() = status == Status.ALIVE || status == Status.PROPOSED
        /** What moved, against the current rules then: "stop 15% → 12.5%". */
        fun moved(): String {
            val s = strategy(strategy) ?: return ""
            return s.params.filter { p -> values[p.key]?.let { v -> base[p.key]?.let { abs(it - v) > 1e-12 } } == true }
                .joinToString(", ") { p -> "${p.words} ${p.say(base.getValue(p.key))} → ${p.say(values.getValue(p.key))}" }.ifEmpty { "no change" }
        }
    }

    enum class ChangeKind(val words: String) {
        AUTO_PAPER("applied on paper by itself"), LIVE_PROPOSAL("proposed for live (your PIN)"),
        SAFETY_REVERT("put back to the defaults by the safety net"),
        /** A research finding applied on PAPER by itself (R14's re-entry cap): undoable like a paper promotion, never the safety net's. */
        RESEARCH("applied on paper from the research"),
    }
    enum class ChangeStatus(val words: String) { APPLIED("applied"), OFFERED("waiting for you"), ACCEPTED("applied with your PIN"), DECLINED("declined"), UNDONE("undone") }

    /**
     * One change of a strategy's rules: from, to, why (the numbers), its kind and status; [effective] the session the arm
     * trades it from (never the day it was decided); [avgLoss] the variant's backtest average losing day and [expect] its
     * forward rupees a day (the safety net's yardsticks).
     */
    data class Change(val id: String, val strategy: String, val variant: String, val kind: ChangeKind, val status: ChangeStatus,
                      val day: LocalDate, val from: Map<String, Double>, val to: Map<String, Double>, val why: String,
                      val effective: LocalDate? = null, val avgLoss: Double = 0.0, val expect: Double = 0.0)

    /**
     * What an ARM trades (S1): from session [from] on, strategy [strategy]'s entries on the [live] account (false: paper) use
     * [values]; put there by change [change]. The arm reads the newest one that has started ([effective]).
     */
    data class Tune(val strategy: String, val live: Boolean, val from: LocalDate, val values: Map<String, Double>, val change: String)

    /**
     * Everything the lab keeps: per strategy its current rules' values ([current]; absent: its defaults), its account
     * ([runs]) and the hill-climb's step ([scales]); the variants; the changes; what the arms trade ([tunes]) and the safety
     * net's losing streaks ([streaks]); [tried] every variant ever made (the penalty's count); [retiredCount] every one
     * retired or failed (kept or not).
     */
    data class Book(
        val current: Map<String, Map<String, Double>> = emptyMap(), val runs: Map<String, Run> = emptyMap(),
        val scales: Map<String, Double> = emptyMap(), val variants: List<Variant> = emptyList(), val changes: List<Change> = emptyList(),
        val tried: Int = 0, val retiredCount: Int = 0, val nextId: Int = 1, val lastDay: LocalDate? = null,
        val tunes: List<Tune> = emptyList(), val streaks: Map<String, Int> = emptyMap(),
    ) {
        fun currentOf(key: String): Map<String, Double> = strategy(key)?.let { s -> s.defaults + (current[key] ?: emptyMap()) } ?: emptyMap()
        fun alive(key: String? = null): List<Variant> = variants.filter { it.alive && (key == null || it.strategy == key) }
        fun runOf(key: String): Run = runs[key] ?: Run.OFF
        fun change(id: String): Change? = changes.firstOrNull { it.id == id }
    }

    // ---- what the arms trade (S1) ------------------------------------------------------------------------------------------

    /** The tune [key]'s arm trades on [day] on the [live] account: the newest that has started by then (null: none ever). */
    fun effective(b: Book, key: String, live: Boolean, day: LocalDate): Tune? {
        var best: Tune? = null
        for (t in b.tunes) if (t.strategy == key && t.live == live && !t.from.isAfter(day) && (best == null || !t.from.isBefore(best.from))) best = t
        return best
    }

    /** [key]'s parameters on [day] for the [live] account when they are not its defaults; null: the arm trades its defaults. */
    fun valuesFor(b: Book, key: String, live: Boolean, day: LocalDate): Map<String, Double>? {
        val s = strategy(key) ?: return null
        val t = effective(b, key, live, day) ?: return null
        val v = s.values(t.values)
        return if (same(v, s.defaults)) null else v
    }

    /** What the arm of [key] runs on [day] on the [live] account, in words for the sheet: "default" or "variant V3 since 14 Oct". */
    data class Running(val variant: String?, val since: LocalDate?, val change: Change?, val next: Tune?) {
        val isDefault: Boolean get() = variant == null
    }

    fun running(b: Book, key: String, live: Boolean, day: LocalDate): Running {
        val now = effective(b, key, live, day)
        val nowValues = valuesFor(b, key, live, day)
        val change = now?.let { b.change(it.change) }
        // A tune waiting for a later session (the next one's): said beside what runs now.
        val next = b.tunes.filter { it.strategy == key && it.live == live && it.from.isAfter(day) }.maxByOrNull { it.from }
        return if (nowValues == null) Running(null, null, null, next) else Running(change?.variant ?: "?", now?.from, change, next)
    }

    fun runningWords(b: Book, key: String, live: Boolean, day: LocalDate): String {
        val r = running(b, key, live, day)
        val now = if (r.isDefault) "running: default" else "running: variant ${r.variant} since ${DiscoveryStats.day(r.since!!)}"
        val next = r.next?.let { t ->
            val s = strategy(key)
            val toDefault = s != null && same(s.values(t.values), s.defaults)
            val v = b.change(t.change)?.variant
            " · from ${DiscoveryStats.day(t.from)}: " + if (toDefault) "default" else "variant ${v ?: "?"}"
        } ?: ""
        return now + next
    }

    private fun tune(b: Book, key: String, live: Boolean, from: LocalDate, values: Map<String, Double>, change: String): Book {
        val mine = b.tunes.filter { it.strategy == key && it.live == live }
        val drop = (mine.size + 1 - KEEP_TUNES).coerceAtLeast(0)
        val dropped = mine.take(drop).toSet()
        return b.copy(tunes = b.tunes.filter { it !in dropped } + Tune(key, live, from, values, change))
    }

    // ---- spawning --------------------------------------------------------------------------------------------------------

    /**
     * Up to [n] neighbours of [center] for [s]: one or two parameters moved by +-[scale] x their step (x1 or x2), clamped to
     * the SAFE bounds, never equal to [center] or to one of [taken], and always valid. Seeded by [rng].
     */
    fun neighbours(s: Strategy, center: Map<String, Double>, scale: Double, rng: DiscoveryStats.Rng, n: Int,
                   taken: Collection<Map<String, Double>> = emptyList()): List<Map<String, Double>> {
        val out = ArrayList<Map<String, Double>>()
        var tries = 0
        val base = s.values(center)
        while (out.size < n && tries < n * 30) {
            tries++
            val v = HashMap(base)
            val moves = 1 + rng.nextInt(2)
            repeat(moves) {
                val p = s.params[rng.nextInt(s.params.size)]
                val dir = if (rng.nextInt(2) == 0) -1 else 1
                val mult = 1 + rng.nextInt(2)
                v[p.key] = p.clamp((v[p.key] ?: p.min) + dir * mult * scale * p.step)
            }
            if (same(v, base) || (out + taken).any { same(it, v) }) continue
            if (s.invalid(v) != null) continue
            out += v
        }
        return out
    }

    private fun same(a: Map<String, Double>, b: Map<String, Double>) = a.keys.union(b.keys).all { abs((a[it] ?: 0.0) - (b[it] ?: 0.0)) < 1e-12 }

    /** [s]'s rupees on [d] with [values] (its arms' real rules, [ArmLab]); [history] the earlier days the replay may read. */
    fun netOn(s: Strategy, values: Map<String, Double>, d: ShadowSim.Day, history: List<ShadowSim.Day>): Double? {
        if (d.underlying !in s.underlyings) return null
        return ArmLab.trades(s.key, values, d, history).sumOf { it.net }
    }

    /** The pre-check: (variant rupees, current rupees) on [history] (the stored days); passes when the variant is not worse. */
    fun precheck(s: Strategy, values: Map<String, Double>, current: Map<String, Double>, history: List<ShadowSim.Day>): Pair<Double, Double> {
        var v = 0.0; var c = 0.0
        for (d in history) if (d.underlying in s.underlyings) { v += netOn(s, values, d, history) ?: 0.0; c += netOn(s, current, d, history) ?: 0.0 }
        return v to c
    }

    /** [values]' rupees per stored day (all [s]'s indices together), oldest first: the backtest the safety net measures against. */
    fun daily(s: Strategy, values: Map<String, Double>, history: List<ShadowSim.Day>): List<Double> =
        history.filter { it.underlying in s.underlyings }.groupBy { it.date }.toSortedMap().values.map { ds -> ds.sumOf { netOn(s, values, it, history) ?: 0.0 } }

    /** The average losing day of [daily] (a positive number; [MIN_AVG_LOSS] at least). */
    fun avgLoss(daily: List<Double>): Double = daily.filter { it < 0 }.let { if (it.isEmpty()) 0.0 else -it.average() }.coerceAtLeast(MIN_AVG_LOSS)

    /**
     * Fills [key]'s variants up to its [Strategy.maxAlive] (and the lab to [MAX_TOTAL]): neighbours of its current rules, each
     * counted as tried, each pre-checked on [history]; a worse one is kept as failed (counted in the graveyard).
     */
    fun spawn(book: Book, key: String, day: LocalDate, history: List<ShadowSim.Day>, rng: DiscoveryStats.Rng): Book {
        val s = strategy(key) ?: return book
        var b = book
        val want = minOf(s.maxAlive - b.alive(key).size, MAX_TOTAL - b.alive().size)
        if (want <= 0) return b
        val cur = b.currentOf(key)
        val scale = b.scales[key] ?: 1.0
        // Every variant of this strategy ever kept is taken (retired, failed, promoted too): a failed neighbour is never re-tried
        // night after night (each try tightens the penalty), and a reverted one is never spawned again (G7-10).
        val fresh = neighbours(s, cur, scale, rng, want, b.variants.filter { it.strategy == key }.map { it.values })
        for (v in fresh) {
            val pre = precheck(s, v, cur, history)
            val ok = pre.first >= pre.second
            val id = "V${b.nextId}"
            val varnt = Variant(id, key, v, cur, day, scale, if (ok) Status.ALIVE else Status.PRECHECK_FAILED, pre = pre,
                why = if (ok) "" else "worse than the current rules on ${history.count { it.underlying in s.underlyings }} stored days " +
                    "(${DiscoveryStats.rs(pre.first)} against ${DiscoveryStats.rs(pre.second)})")
            b = b.copy(variants = b.variants + varnt, tried = b.tried + 1, nextId = b.nextId + 1,
                retiredCount = b.retiredCount + if (ok) 0 else 1)
        }
        return trim(b)
    }

    private fun trim(b: Book): Book {
        val dead = b.variants.filter { !it.alive && it.status != Status.PROMOTED }
        if (dead.size <= KEEP_RETIRED) return b
        val drop = dead.take(dead.size - KEEP_RETIRED).map { it.id }.toSet()
        return b.copy(variants = b.variants.filter { it.id !in drop })
    }

    // ---- the day -----------------------------------------------------------------------------------------------------------

    /** What a day's pass did, for the app to act on and tell. */
    sealed class Event {
        data class Applied(val change: Change) : Event()
        data class Proposed(val change: Change) : Event()
        data class Retired(val variant: Variant) : Event()
        data class Waiting(val variant: Variant, val why: String) : Event()
        /**
         * The safety net put a strategy back to its defaults ([Change] of kind SAFETY_REVERT). [liveStopKept]: the live arm kept
         * its tighter stop (the defaults' wider one would raise live risk: Boss's PIN, through the accepted change's Undo).
         */
        data class Reverted(val change: Change, val liveStopKept: Boolean = false) : Event()
    }

    data class Pass(val book: Book, val events: List<Event>,
                    /** What became of the directed variants asked for this day ([Seed], the order review's): one per seed. */
                    val seeded: List<Seeded> = emptyList())

    // ---- directed variants (the order review, 12 Oct) ---------------------------------------------------------------------

    /**
     * A directed variant asked of the lab (the order review's recurring flaw): [strategy]'s [param] moved ONE step in [dir]
     * (+1 up, -1 down) from its current rules. It goes through everything a spawned neighbour does - the SAFE bounds, the
     * pre-check on the stored days, counted as tried in the trial penalty and the Benjamini-Hochberg family, then the same
     * minimum days and tests before any promotion (paper first; live only with Boss's PIN). Nothing is applied by asking.
     */
    data class Seed(val id: String, val strategy: String, val param: String, val dir: Int, val why: String)

    /** What became of [Seed] [id]: [variant] made (shadowing or failed its pre-check), or [final] false while it waits for room. */
    data class Seeded(val id: String, val variant: String?, val status: String, val final: Boolean)

    /** [seed] in [book] on [day]: a new variant (pre-checked on [history]), or why not. Pure. */
    fun seed(book: Book, seed: Seed, day: LocalDate, history: List<ShadowSim.Day>): Pair<Book, Seeded> {
        val s = strategy(seed.strategy) ?: return book to Seeded(seed.id, null, "the lab does not tune ${seed.strategy}", true)
        val p = s.param(seed.param) ?: return book to Seeded(seed.id, null, "${s.label} has no ${seed.param} the lab may move", true)
        if (book.runOf(s.key) == Run.OFF && book.alive(s.key).isEmpty()) return book to Seeded(seed.id, null, "waiting: ${s.label} is not running", false)
        if (book.alive(s.key).size >= s.maxAlive || book.alive().size >= MAX_TOTAL) return book to Seeded(seed.id, null, "waiting: the lab is full for ${s.label}", false)
        val cur = book.currentOf(s.key)
        val from = cur[p.key] ?: p.min
        val v = HashMap(cur).apply { put(p.key, p.clamp(from + (if (seed.dir >= 0) 1 else -1) * p.step)) }
        if (same(v, cur)) return book to Seeded(seed.id, null, "${p.words} is already at its safe bound (${p.say(from)}): a proposal only", true)
        if (s.invalid(v) != null) return book to Seeded(seed.id, null, "outside the safe bounds: a proposal only", true)
        book.variants.firstOrNull { it.strategy == s.key && same(it.values, v) }?.let {
            return book to Seeded(seed.id, it.id, "already tried: ${it.status.words}", true)
        }
        val pre = precheck(s, v, cur, history)
        val ok = pre.first >= pre.second
        val id = "V${book.nextId}"
        val varnt = Variant(id, s.key, v, cur, day, book.scales[s.key] ?: 1.0, if (ok) Status.ALIVE else Status.PRECHECK_FAILED, pre = pre,
            why = if (ok) seed.why.take(160) else "worse than the current rules on ${history.count { it.underlying in s.underlyings }} stored days " +
                "(${DiscoveryStats.rs(pre.first)} against ${DiscoveryStats.rs(pre.second)})")
        val b = book.copy(variants = book.variants + varnt, tried = book.tried + 1, nextId = book.nextId + 1,
            retiredCount = book.retiredCount + if (ok) 0 else 1)
        return trim(b) to Seeded(seed.id, id, if (ok) "shadowing: ${p.words} ${p.say(from)} → ${p.say(v.getValue(p.key))}" else varnt.why, true)
    }

    private fun seedAll(book: Book, seeds: List<Seed>, day: LocalDate, history: List<ShadowSim.Day>): Pair<Book, List<Seeded>> {
        var b = book
        val out = ArrayList<Seeded>()
        for (sd in seeds) { val (nb, r) = seed(b, sd, day, history); b = nb; out += r }
        return b to out
    }

    /**
     * One trading day: [runs] (how each strategy was armed that day), [days] the day's data per index, [history] the stored
     * days (the pre-check of new neighbours, and Liquidity's earlier sessions), [from] the next session (a change decided now
     * is traded from then: never mid-day), [actual] each strategy's real result that day on the account it ran (rupees a lot;
     * absent: the lab's own replay of what it ran). The safety net first; then each living variant against the current rules;
     * then the judgement; then refill. [evidence] false (an event day, [LearningFilter.include]): the safety net still runs
     * (it only lowers risk), but no variant is compared or judged on that day, so it never counts toward a promotion.
     */
    fun day(book: Book, date: LocalDate, runs: Map<String, Run>, days: Map<String, ShadowSim.Day>, history: List<ShadowSim.Day>,
            rng: DiscoveryStats.Rng, from: LocalDate = date.plusDays(1), actual: Map<String, Double> = emptyMap(),
            evidence: Boolean = true, seeds: List<Seed> = emptyList()): Pass {
        if (book.lastDay != null && !date.isAfter(book.lastDay)) return Pass(book, emptyList())
        var b = book.copy(runs = book.runs + runs, lastDay = date)
        val events = ArrayList<Event>()
        // The day's rupees per strategy and values, worked out once (every variant is compared with the same current rules).
        val memo = HashMap<Pair<String, Map<String, Double>>, Pair<Double, Boolean>>()
        fun result(s: Strategy, values: Map<String, Double>): Pair<Double, Boolean> = memo.getOrPut(s.key to values) {
            var net = 0.0; var traded = false
            for (u in s.underlyings) {
                val d = days[u] ?: continue
                val t = ArmLab.trades(s.key, values, d, history)
                if (t.isNotEmpty()) traded = true
                net += t.sumOf { it.net }
            }
            net to traded
        }
        // 0. The safety net, on what each arm traded that day.
        // (No result known and no stored day of its indices: not judged.)
        val sn = safety(b, date, runs, from) { key, values ->
            actual[key] ?: strategy(key)?.takeIf { s -> s.underlyings.any { it in days } }?.let { result(it, values).first }
        }
        b = sn.book; events += sn.events
        // An event day is not evidence (G9-6): nothing compared, nothing judged; the refill still runs.
        if (!evidence) {
            // Asked variants first (before the refill takes the room), born today and first compared on the next session.
            val (sb, seeded) = seedAll(b, seeds, date, history); b = sb
            for (s in STRATEGIES) if (b.runOf(s.key) != Run.OFF || b.alive(s.key).isNotEmpty()) b = spawn(b, s.key, date, history, rng)
            return Pass(trim(b), events, seeded)
        }
        // 1. Compare.
        b = b.copy(variants = b.variants.map { v ->
            if (!v.alive) return@map v
            val s = strategy(v.strategy) ?: return@map v
            val (vn, vt) = result(s, s.values(v.values))
            val (cn, ct) = result(s, b.currentOf(v.strategy))
            if (!vt && !ct) v.copy(lastDay = date) else {
                val diffs = v.diffs + (vn - cn)
                v.copy(diffs = diffs, vSum = v.vSum + vn, cSum = v.cSum + cn, lastDay = date,
                    pBetter = DiscoveryStats.alwaysValidP(diffs, 1, MIN_SD), pWorse = DiscoveryStats.alwaysValidP(diffs, -1, MIN_SD))
            }
        })
        // 2. Judge (a change is traded from the next session), with the safety net's yardsticks on each change.
        val j = judge(b, date, from)
        b = j.book
        for (e in j.events) events += when (e) {
            is Event.Applied -> Event.Applied(yardsticks(e.change, history).also { c -> b = b.copy(changes = b.changes.map { if (it.id == c.id) c else it }) })
            is Event.Proposed -> Event.Proposed(yardsticks(e.change, history).also { c -> b = b.copy(changes = b.changes.map { if (it.id == c.id) c else it }) })
            else -> e
        }
        // 3. The asked variants (the order review's), then refill every running strategy. An asked one is born today and first
        // compared on the next session, so the day that showed the flaw is never its own evidence.
        val (sb, seeded) = seedAll(b, seeds, date, history); b = sb
        for (s in STRATEGIES) if (b.runOf(s.key) != Run.OFF || b.alive(s.key).isNotEmpty()) b = spawn(b, s.key, date, history, rng)
        return Pass(trim(b), events, seeded)
    }

    /** [c] with the safety net's yardsticks: the variant's backtest average losing day and its forward rupees a day. */
    private fun yardsticks(c: Change, history: List<ShadowSim.Day>): Change {
        val s = strategy(c.strategy) ?: return c
        return c.copy(avgLoss = avgLoss(daily(s, s.values(c.to), history)))
    }

    /** Does [v] win: enough days, better on average, the sequential p under the trial penalty AND a BH pass across [allP]? */
    fun wins(v: Variant, allP: Collection<Double>, tried: Int): Boolean {
        val alpha = strategy(v.strategy)?.alpha ?: ALPHA
        return v.n >= MIN_N && v.diffs.average() > 0 && v.pBetter <= DiscoveryStats.penalizedAlpha(alpha, tried) && DiscoveryStats.bhPass(v.pBetter, allP, Q)
    }

    /** Is [v] done losing: worse by the same test the other way, or out of days? */
    fun loses(v: Variant): Boolean = (v.n >= MIN_N / 2 && v.pWorse <= ALPHA) || v.n >= MAX_N

    /**
     * Every p the false-discovery check runs across: each variant ever judged (the retired at their last p), the forgotten
     * and those that failed their pre-check as 1 (they were tried: leaving them out shrank the family - R3 finding 10).
     */
    fun allP(b: Book): List<Double> = b.variants.map { if (it.status == Status.PRECHECK_FAILED) 1.0 else it.pBetter } +
        List((b.tried - b.variants.size).coerceAtLeast(0)) { 1.0 }

    /**
     * The judgement of the living variants on [date]: promotions (paper by itself from session [from], live proposed) and
     * retirements.
     */
    fun judge(book: Book, date: LocalDate, from: LocalDate = date.plusDays(1)): Pass {
        var b = book
        val events = ArrayList<Event>()
        val allP = allP(b)
        val promotedFor = HashSet<String>()
        for (v in b.variants.filter { it.status == Status.ALIVE }.sortedBy { it.pBetter }) {
            if (v.strategy in promotedFor) continue
            val wins = wins(v, allP, b.tried)
            val loses = loses(v)
            when {
                wins -> {
                    val penalty = DiscoveryStats.penalizedAlpha(strategy(v.strategy)?.alpha ?: ALPHA, b.tried)
                    val why = "over ${v.n} days it made ${DiscoveryStats.rs(v.vSum)} against ${DiscoveryStats.rs(v.cSum)} " +
                        "(${DiscoveryStats.rs(v.diffs.average())} a day better; sequential p ${fmtP(v.pBetter)}, passes the false-discovery " +
                        "check across ${b.tried} variants tried and the penalty ${fmtP(penalty)})"
                    val expect = if (v.n > 0) v.vSum / v.n else 0.0
                    when (b.runOf(v.strategy)) {
                        Run.PAPER -> {
                            val c = Change("C${b.nextId}", v.strategy, v.id, ChangeKind.AUTO_PAPER, ChangeStatus.APPLIED, date, b.currentOf(v.strategy),
                                v.values, why, effective = from, expect = expect)
                            b = promote(b, v, c, Status.PROMOTED, applied = true)
                            b = tune(b, v.strategy, false, from, v.values, c.id)
                            events += Event.Applied(c); promotedFor += v.strategy
                        }
                        Run.LIVE -> if (b.changes.none { it.strategy == v.strategy && it.status == ChangeStatus.OFFERED }) {
                            val c = Change("C${b.nextId}", v.strategy, v.id, ChangeKind.LIVE_PROPOSAL, ChangeStatus.OFFERED, date, b.currentOf(v.strategy),
                                v.values, why, expect = expect)
                            b = promote(b, v, c, Status.PROPOSED, applied = false)
                            events += Event.Proposed(c); promotedFor += v.strategy
                        }
                        Run.OFF -> events += Event.Waiting(v, "the strategy is not running: nothing to change")
                    }
                }
                loses -> {
                    val why = if (v.n >= MAX_N && v.pWorse > ALPHA) "no better after ${v.n} days (${DiscoveryStats.rs(v.vSum - v.cSum)} in all)"
                        else "worse than the current rules: ${DiscoveryStats.rs(v.vSum)} against ${DiscoveryStats.rs(v.cSum)} over ${v.n} days (p ${fmtP(v.pWorse)})"
                    val r = v.copy(status = Status.RETIRED, why = why)
                    b = b.copy(variants = b.variants.map { if (it.id == v.id) r else it }, retiredCount = b.retiredCount + 1,
                        scales = b.scales + (v.strategy to maxOf(MIN_SCALE, (b.scales[v.strategy] ?: 1.0) * 0.5)))
                    events += Event.Retired(r)
                }
            }
        }
        return Pass(b, events)
    }

    // ---- research findings (R14) -------------------------------------------------------------------------------------------

    /** What change [c] moves, in words ("entries a side a day no cap → 1 a side a day"), from its own from / to. */
    fun moved(c: Change): String {
        val s = strategy(c.strategy) ?: return ""
        return s.params.filter { p -> c.to[p.key]?.let { v -> c.from[p.key]?.let { abs(it - v) > 1e-12 } } == true }
            .joinToString(", ") { p -> "${p.words} ${p.say(c.from.getValue(p.key))} → ${p.say(c.to.getValue(p.key))}" }.ifEmpty { "no change" }
    }

    /** The change ids of R14's re-entry cap: on paper ([researchPaperId]) and proposed for live ([researchLiveId]). */
    fun researchPaperId(key: String) = "R14-$key"
    fun researchLiveId(key: String) = "R14L-$key"

    /**
     * R14's re-entry cap (ORB / ORB Fresh at most [com.optionslab.engine.orb.ReentryCap.PAPER_CAP] entry a side a day: confirmed
     * on train and holdout with real costs) put through the lab's own path, once per strategy:
     *  - PAPER: applied by itself from session [from] ([ChangeKind.RESEARCH], undoable on the card; fewer paper entries only);
     *    the current rules move to it and the living variants, measured against the old rules, are retired as superseded;
     *  - LIVE: only while the strategy runs live ([Book.runs]) and its live arm has no cap, PROPOSED to Boss (applied only with
     *    his PIN, [accept]) - never applied to live by itself.
     * A strategy whose research change exists (any status: Boss's undo or no is kept) is never seeded again.
     */
    fun research(book: Book, date: LocalDate, from: LocalDate): Pass {
        var b = book
        val events = ArrayList<Event>()
        val cap = com.optionslab.engine.orb.ReentryCap.PAPER_CAP.toDouble()
        val why = com.optionslab.engine.orb.ReentryCap.EVIDENCE
        for (key in com.optionslab.engine.orb.ReentryCap.ARMS.sorted()) {
            val s = strategy(key) ?: continue
            if (b.change(researchPaperId(key)) == null) {
                val cur = b.currentOf(key)
                if ((cur[ArmParams.SIDE_CAP] ?: 0.0) < 0.5) {
                    val to = s.values(cur + (ArmParams.SIDE_CAP to cap))
                    val c = Change(researchPaperId(key), key, "R14", ChangeKind.RESEARCH, ChangeStatus.APPLIED, date, cur, to, why, effective = from)
                    var retired = 0
                    val vs = b.variants.map { if (it.strategy == key && it.status == Status.ALIVE) { retired++; it.copy(status = Status.RETIRED, why = "superseded by R14's re-entry cap") } else it }
                    b = b.copy(changes = b.changes + c, current = b.current + (key to to), variants = vs, retiredCount = b.retiredCount + retired,
                        scales = b.scales + (key to 1.0))
                    b = tune(b, key, false, from, to, c.id)
                    events += Event.Applied(c)
                }
            }
            val liveNow = newest(b, s, true)
            if (b.runOf(key) == Run.LIVE && b.change(researchLiveId(key)) == null && (liveNow[ArmParams.SIDE_CAP] ?: 0.0) < 0.5 &&
                b.changes.none { it.strategy == key && it.status == ChangeStatus.OFFERED }) {
                val c = Change(researchLiveId(key), key, "R14", ChangeKind.LIVE_PROPOSAL, ChangeStatus.OFFERED, date, liveNow,
                    s.values(liveNow + (ArmParams.SIDE_CAP to cap)), why)
                b = b.copy(changes = b.changes + c)
                events += Event.Proposed(c)
            }
        }
        return Pass(b, events)
    }

    /** [v] won: the change recorded; on paper the current rules move to it and its siblings are retired as superseded. */
    private fun promote(b: Book, v: Variant, c: Change, status: Status, applied: Boolean): Book {
        var vs = b.variants.map { if (it.id == v.id) it.copy(status = status, why = c.why) else it }
        var retired = 0
        if (applied) vs = vs.map {
            if (it.strategy == v.strategy && it.status == Status.ALIVE) { retired++; it.copy(status = Status.RETIRED, why = "superseded by ${v.id}") } else it
        }
        return b.copy(variants = vs, changes = b.changes + c, nextId = b.nextId + 1, retiredCount = b.retiredCount + retired,
            current = if (applied) b.current + (v.strategy to v.values) else b.current, scales = if (applied) b.scales + (v.strategy to 1.0) else b.scales)
    }

    /**
     * Boss's Undo of an applied PAPER change (no PIN: paper only): the paper rules back to before it, from session [from] (never
     * mid-day). Null when it cannot be undone here (a live change goes through [undoLive]).
     */
    fun undo(b: Book, id: String, from: LocalDate? = null): Book? {
        val c = b.change(id) ?: return null
        if (c.status != ChangeStatus.APPLIED || (c.kind != ChangeKind.AUTO_PAPER && c.kind != ChangeKind.RESEARCH)) return null
        // Only the newest change in force: undoing one a later change replaced would bring back stale rules (G7-5).
        if (superseded(b, id)) return null
        var out = b.copy(current = b.current + (c.strategy to c.from), changes = b.changes.map { if (it.id == id) it.copy(status = ChangeStatus.UNDONE) else it })
        if (from != null) out = tune(out, c.strategy, false, from, c.from, id)
        return out
    }

    /**
     * A paper promotion [id] withdrawn before it was ever traded (learning frozen): the paper rules back to before it and its
     * tune dropped - the arm never trades it - the change marked undone. Null when it is not an applied paper promotion.
     */
    fun withhold(b: Book, id: String): Book? {
        val c = b.change(id) ?: return null
        val out = undo(b, id) ?: return null
        // The promotion never happened: the winner and the siblings it retired as superseded shadow on (G7-9).
        val superseded = "superseded by ${c.variant}"
        var back = 0
        val vs = out.variants.map {
            when {
                it.id == c.variant && it.status == Status.PROMOTED -> it.copy(status = Status.ALIVE, why = "")
                it.strategy == c.strategy && it.status == Status.RETIRED && it.why == superseded -> { back++; it.copy(status = Status.ALIVE, why = "") }
                else -> it
            }
        }
        return out.copy(tunes = out.tunes.filter { !(it.change == id && !it.live) }, variants = vs,
            retiredCount = (out.retiredCount - back).coerceAtLeast(0),
            changes = out.changes.map { if (it.id == id) it.copy(why = it.why + " - withheld: learning is frozen, never traded") else it })
    }

    /** Whether a later change of [id]'s strategy is in force (applied or accepted): [id] is then no longer the one to undo. */
    fun superseded(b: Book, id: String): Boolean {
        val i = b.changes.indexOfFirst { it.id == id }
        if (i < 0) return false
        val c = b.changes[i]
        return b.changes.drop(i + 1).any { it.strategy == c.strategy && (it.status == ChangeStatus.APPLIED || it.status == ChangeStatus.ACCEPTED) }
    }

    /** Whether the card may offer Undo for paper change [id]: applied, by itself, and still the newest in force. */
    fun undoable(b: Book, id: String): Boolean =
        b.change(id)?.let { (it.kind == ChangeKind.AUTO_PAPER || it.kind == ChangeKind.RESEARCH) && it.status == ChangeStatus.APPLIED && !superseded(b, id) } == true

    // ---- which way a parameter moves risk ------------------------------------------------------------------------------------

    /**
     * The parameters whose higher value is a wider stop - more rupees at risk on every live trade. Lots are not a parameter
     * at all, so the stop is what bounds a trade's loss; the other parameters choose which trades to take and when to exit.
     */
    val WIDER_IS_RISKIER: Set<String> = setOf(ArmParams.STOP_PTS, ArmParams.STOP_PCT)

    /**
     * Every exit parameter whose move can delay or skip an exit (G7-4): the stops, the time stop (more minutes is later), its
     * gain (less is laxer) and the profit lock (none, or a later rung, is less protective); and the ORB's re-entry cap (R14:
     * none, or a higher one, lets more trades - and their charges - in), so lifting it is a risk raise like a wider stop.
     */
    val EXIT_KEYS: Set<String> = WIDER_IS_RISKIER + setOf(ArmParams.TS, ArmParams.TSG, ArmParams.LOCK, ArmParams.SIDE_CAP)

    /** How loose exit [k] is at [x]: higher is riskier (a later or laxer exit). */
    fun looseness(k: String, x: Double): Double = when (k) {
        ArmParams.TSG -> -x
        ArmParams.LOCK -> if (x <= 0) Double.POSITIVE_INFINITY else x
        ArmParams.SIDE_CAP -> if (x < 0.5) Double.POSITIVE_INFINITY else x
        else -> x
    }

    /** Does moving [s] from [from] to [to] widen a stop or loosen an exit (raise the risk of a trade)? */
    fun widensStop(s: Strategy, from: Map<String, Double>, to: Map<String, Double>): Boolean {
        val a = s.values(from); val c = s.values(to)
        return EXIT_KEYS.any { k -> s.param(k) != null && looseness(k, c[k] ?: 0.0) > looseness(k, a[k] ?: 0.0) + 1e-12 }
    }

    /** [to] with each exit no looser than in [cap] (the defaults put back, but never a wider live stop or later exit than the one trading). */
    fun capStops(s: Strategy, to: Map<String, Double>, cap: Map<String, Double>): Map<String, Double> {
        val t = s.values(to); val c = s.values(cap)
        return t.mapValues { (k, v) -> val cv = c[k]; if (k in EXIT_KEYS && cv != null && looseness(k, v) > looseness(k, cv)) cv else v }
    }

    /** The values [s]'s arm trades on the [live] account from its newest tune on (its defaults when none). */
    private fun newest(b: Book, s: Strategy, live: Boolean): Map<String, Double> =
        b.tunes.filter { it.strategy == s.key && it.live == live }.maxByOrNull { it.from }?.let { s.values(it.values) } ?: s.defaults

    /**
     * Whether undoing live change [id] only puts the arm's defaults back AND never widens the live stop (against the change
     * itself and against what the live arm trades now): a lower-or-equal-risk action, no PIN needed. Defaults whose stop is
     * wider than the variant's raise live risk: the PIN.
     */
    fun restoresDefault(b: Book, id: String): Boolean {
        val c = b.change(id) ?: return false
        val s = strategy(c.strategy) ?: return false
        return same(s.values(c.from), s.defaults) && !widensStop(s, c.to, c.from) && !widensStop(s, newest(b, s, true), c.from)
    }

    /**
     * Boss's Undo of a live change he applied with his PIN: both accounts back to before it from session [from]. Without
     * [pinConfirmed] only when that puts the defaults back ([restoresDefault]); otherwise null (it needs the PIN).
     */
    fun undoLive(b: Book, id: String, pinConfirmed: Boolean, from: LocalDate): Book? {
        val c = b.change(id) ?: return null
        if (c.status != ChangeStatus.ACCEPTED || c.kind != ChangeKind.LIVE_PROPOSAL) return null
        if (!pinConfirmed && !restoresDefault(b, id)) return null
        var out = b.copy(current = b.current + (c.strategy to c.from), changes = b.changes.map { if (it.id == id) it.copy(status = ChangeStatus.UNDONE) else it })
        out = tune(out, c.strategy, true, from, c.from, id)
        return tune(out, c.strategy, false, from, c.from, id)
    }

    /**
     * Boss's yes to a live proposal: ONLY with [pinConfirmed], traded from session [from] (never mid-day) on both accounts.
     * Null when refused or not waiting.
     */
    fun accept(b: Book, id: String, pinConfirmed: Boolean, from: LocalDate? = null): Book? {
        if (!pinConfirmed) return null
        val c = b.changes.firstOrNull { it.id == id && it.status == ChangeStatus.OFFERED } ?: return null
        // The siblings still alive were measured against the rules this replaces: retired as superseded (R3 finding 11), as
        // a paper promotion retires them, so none can win later on a lead over rules no longer running.
        var superseded = 0
        var out = b.copy(current = b.current + (c.strategy to c.to),
            changes = b.changes.map { if (it.id == id) it.copy(status = ChangeStatus.ACCEPTED, effective = from) else it },
            variants = b.variants.map {
                when {
                    it.id == c.variant -> it.copy(status = Status.PROMOTED)
                    it.strategy == c.strategy && it.status == Status.ALIVE -> { superseded++; it.copy(status = Status.RETIRED, why = "superseded by ${c.variant}") }
                    else -> it
                }
            })
        out = out.copy(retiredCount = out.retiredCount + superseded)
        if (from != null) { out = tune(out, c.strategy, true, from, c.to, id); out = tune(out, c.strategy, false, from, c.to, id) }
        return out
    }

    fun decline(b: Book, id: String): Book {
        val c = b.changes.firstOrNull { it.id == id && it.status == ChangeStatus.OFFERED } ?: return b
        return b.copy(changes = b.changes.map { if (it.id == id) it.copy(status = ChangeStatus.DECLINED) else it },
            variants = b.variants.map { if (it.id == c.variant) it.copy(status = Status.RETIRED, why = "you declined it") else it },
            retiredCount = b.retiredCount + 1)
    }

    // ---- the safety net ----------------------------------------------------------------------------------------------------

    /**
     * The safety net on [date]: each strategy that ran ([runs]) under a variant on that account ([effective] not its
     * defaults) is judged on its day's rupees ([net] of (key, the values it ran); null: not known, not judged). A loss beyond
     * [SAFETY_LOSS_X] x the variant's backtest average losing day, or the [SAFETY_STREAK]th losing day in a row below its
     * forward expectation, puts the strategy back to its defaults on BOTH accounts from session [from] (lower risk: no PIN),
     * recorded as a SAFETY_REVERT change.
     */
    fun safety(book: Book, date: LocalDate, runs: Map<String, Run>, from: LocalDate, net: (String, Map<String, Double>) -> Double?): Pass {
        var b = book
        val events = ArrayList<Event>()
        for (s in STRATEGIES) {
            val run = runs[s.key] ?: Run.OFF
            if (run == Run.OFF) continue
            val live = run == Run.LIVE
            val values = valuesFor(b, s.key, live, date)
            if (values == null) { if ((b.streaks[s.key] ?: 0) != 0) b = b.copy(streaks = b.streaks - s.key); continue }
            val t = effective(b, s.key, live, date)!!
            // Already put back by the safety net (the live arm's defaults with its tighter stop kept): nothing lower to go to.
            if (b.change(t.change)?.kind == ChangeKind.SAFETY_REVERT) continue
            // A research change (R14's re-entry cap) is judged on years of data, not a forward week, and its defaults would let
            // more trades - and charges - in: the safety net leaves it; Boss's Undo takes it back.
            if (b.change(t.change)?.kind == ChangeKind.RESEARCH) { if ((b.streaks[s.key] ?: 0) != 0) b = b.copy(streaks = b.streaks - s.key); continue }
            val made = net(s.key, values) ?: continue
            val c = b.change(t.change)
            val avg = (c?.avgLoss ?: 0.0).coerceAtLeast(MIN_AVG_LOSS)
            val expect = c?.expect ?: 0.0
            val big = made < -SAFETY_LOSS_X * avg
            val losing = made < 0 && made < expect
            val streak = if (losing) (b.streaks[s.key] ?: 0) + 1 else 0
            if (!big && streak < SAFETY_STREAK) { b = b.copy(streaks = b.streaks + (s.key to streak)); continue }
            val why = if (big) "lost ${DiscoveryStats.rs(made)} in one day on ${run.words} under ${c?.variant ?: "a variant"}: more than " +
                    "${SAFETY_LOSS_X.roundToInt()} x its backtest's average losing day (${DiscoveryStats.rs(-avg)})"
                else "$SAFETY_STREAK losing days in a row on ${run.words} under ${c?.variant ?: "a variant"}, each below its forward " +
                    "expectation (${DiscoveryStats.rs(expect)} a day; the last ${DiscoveryStats.rs(made)})"
            // The live arm never gets a wider stop by itself: where the defaults' stop is wider than the one it trades, it goes back
            // to the defaults but keeps its own stop (lower risk); the wider default stop waits for Boss's PIN.
            val livePending = b.tunes.filter { it.strategy == s.key && it.live }.maxByOrNull { it.from }
            val liveTo = livePending?.let { capStops(s, s.defaults, it.values) } ?: s.defaults
            val kept = !same(liveTo, s.defaults)
            val keptWords = if (!kept) "" else "; the live arm keeps its tighter exits (" +
                s.params.filter { it.key in EXIT_KEYS && abs((liveTo[it.key] ?: 0.0) - (s.defaults[it.key] ?: 0.0)) > 1e-12 }
                    .joinToString(", ") { p -> "${p.words} ${p.say(liveTo.getValue(p.key))}" } +
                "): the defaults' wider stop or later exit needs your PIN"
            val rc = Change("C${b.nextId}", s.key, c?.variant ?: "", ChangeKind.SAFETY_REVERT, ChangeStatus.APPLIED, date, values, s.defaults,
                "$why - back to the defaults from ${DiscoveryStats.day(from)} (lower risk)$keptWords", effective = from)
            // The living siblings were measured against the rules just thrown out: retired as superseded, as a promotion and an
            // accepted proposal retire them, so none can win later on a lead over rules no longer running (G7-3).
            var superseded = 0
            val vs = b.variants.map {
                if (it.strategy == s.key && it.status == Status.ALIVE) { superseded++; it.copy(status = Status.RETIRED, why = "superseded by the safety net") } else it
            }
            b = b.copy(changes = b.changes + rc, nextId = b.nextId + 1, current = b.current + (s.key to s.defaults), streaks = b.streaks - s.key,
                variants = vs, retiredCount = b.retiredCount + superseded, scales = b.scales + (s.key to 1.0))
            // Both accounts back to the defaults wherever a variant is in force or waiting (live: never a wider stop).
            for (acct in listOf(true, false)) {
                val pending = b.tunes.filter { it.strategy == s.key && it.live == acct }.maxByOrNull { it.from }
                val to = if (acct) liveTo else s.defaults
                if (pending != null && !same(s.values(pending.values), s.defaults)) b = tune(b, s.key, acct, from, to, rc.id)
            }
            events += Event.Reverted(rc, liveStopKept = kept)
        }
        return Pass(b, events)
    }

    /**
     * Whether day [d] may be settled now ([stored]: each index's stored days; [today] the clock's date): some index has [d],
     * and every index that kept a day in the week before [d] has [d] too - the harvest writes one index at a time, so a settle
     * on half the indices would judge on half the data and mark the day done for good (G7-2). From two days after [d], what
     * is there is settled (a missing index is given up).
     */
    fun readyToSettle(d: LocalDate, stored: Map<String, Collection<LocalDate>>, today: LocalDate): Boolean {
        if (stored.values.none { d in it }) return false
        if (today.isAfter(d.plusDays(1))) return true
        return stored.values.all { ds -> d in ds || ds.none { it.isBefore(d) && !it.isBefore(d.minusDays(7)) } }
    }

    fun fmtP(p: Double): String = if (p < 0.001) "<0.001" else "%.3f".format(Locale.ENGLISH, p)

    /** The test state in a few words: "12 days, +Rs 340 a day, p 0.210". */
    fun state(v: Variant): String = if (v.n == 0) "no compared day yet" else
        "${v.n} day${if (v.n == 1) "" else "s"}, ${DiscoveryStats.rs(v.diffs.average())} a day vs current, p ${fmtP(v.pBetter)}"

    /** Each strategy's best living variant (by rupees per compared day), for the card. */
    fun best(b: Book, key: String): Variant? = b.alive(key).filter { it.n > 0 }.maxByOrNull { it.diffs.average() }

    // ---- kept on the phone -----------------------------------------------------------------------------------------------

    private fun kv(m: Map<String, Double>) = m.toSortedMap().entries.joinToString(";") { "${it.key}=${it.value}" }
    private fun unkv(s: String): Map<String, Double> = s.split(';').filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=').toDouble() }
    private fun clean(s: String) = s.replace('|', '/').replace('\n', ' ')

    fun encode(b: Book): String = buildString {
        append("T|${b.tried}|${b.retiredCount}|${b.nextId}|${b.lastDay ?: ""}\n")
        for ((k, run) in b.runs.toSortedMap()) append("R|$k|${run.name}\n")
        for ((k, s) in b.scales.toSortedMap()) append("K|$k|$s\n")
        for ((k, m) in b.current.toSortedMap()) append("S|$k|${kv(m)}\n")
        for (v in b.variants) append("V|${v.id}|${v.strategy}|${v.status.name}|${v.born}|${v.scale}|${v.lastDay ?: ""}|${v.pBetter}|${v.pWorse}|" +
            "${v.vSum}|${v.cSum}|${kv(v.values)}|${kv(v.base)}|${v.diffs.joinToString(";")}|${v.pre?.let { "${it.first};${it.second}" } ?: ""}|${clean(v.why)}\n")
        for (c in b.changes) append("C|${c.id}|${c.strategy}|${c.variant}|${c.kind.name}|${c.status.name}|${c.day}|${kv(c.from)}|${kv(c.to)}|${clean(c.why)}|" +
            "${c.effective ?: ""}|${c.avgLoss}|${c.expect}\n")
        for (t in b.tunes) append("U|${t.strategy}|${if (t.live) 1 else 0}|${t.from}|${kv(t.values)}|${t.change}\n")
        for ((k, n) in b.streaks.toSortedMap()) append("Z|$k|$n\n")
    }

    /** Keys added to a strategy after its variants could be born: a variant without one takes its default (never retired for it). */
    val LATER_KEYS: Set<String> = setOf(ArmParams.SIDE_CAP)

    /** What an alive variant from before the lab replayed the real arms (S3) is retired with: its parameters are not the arm's. */
    const val OLD_RULES = "the lab now replays the real arm (S3): this variant's parameters are not the arm's"

    fun decode(text: String?): Book {
        if (text.isNullOrBlank()) return Book()
        var b = Book()
        val runs = HashMap<String, Run>(); val scales = HashMap<String, Double>(); val cur = HashMap<String, Map<String, Double>>()
        val vs = ArrayList<Variant>(); val cs = ArrayList<Change>(); val ts = ArrayList<Tune>(); val zs = HashMap<String, Int>()
        for (l in text.lines()) runCatching {
            val p = l.split('|')
            when (p[0]) {
                "T" -> b = b.copy(tried = p[1].toInt(), retiredCount = p[2].toInt(), nextId = p[3].toInt(), lastDay = p[4].takeIf { it.isNotEmpty() }?.let(LocalDate::parse))
                "R" -> runs[p[1]] = Run.valueOf(p[2])
                "K" -> scales[p[1]] = p[2].toDouble()
                "S" -> cur[p[1]] = unkv(p[2])
                "V" -> vs += Variant(p[1], p[2], unkv(p[11]), unkv(p[12]), LocalDate.parse(p[4]), p[5].toDouble(), Status.valueOf(p[3]),
                    p[13].split(';').filter { it.isNotBlank() }.map { it.toDouble() }, p[9].toDouble(), p[10].toDouble(), p[7].toDouble(), p[8].toDouble(),
                    p[6].takeIf { it.isNotEmpty() }?.let(LocalDate::parse), p.getOrElse(15) { "" },
                    p[14].split(';').takeIf { it.size == 2 }?.let { it[0].toDouble() to it[1].toDouble() })
                "C" -> cs += Change(p[1], p[2], p[3], ChangeKind.valueOf(p[4]), ChangeStatus.valueOf(p[5]), LocalDate.parse(p[6]), unkv(p[7]), unkv(p[8]), p[9],
                    p.getOrNull(10)?.takeIf { it.isNotEmpty() }?.let(LocalDate::parse), p.getOrNull(11)?.toDoubleOrNull() ?: 0.0,
                    p.getOrNull(12)?.toDoubleOrNull() ?: 0.0)
                "U" -> ts += Tune(p[1], p[2] == "1", LocalDate.parse(p[3]), unkv(p[4]), p[5])
                "Z" -> zs[p[1]] = p[2].toInt()
            }
        }
        // Bounds again on the way in: a file can never carry a value outside the SAFE bounds, or a key the arm does not have.
        val safe = cur.mapValues { (k, m) -> strategy(k)?.let { s -> m.filterKeys { s.param(it) != null }.mapValues { (pk, v) -> s.param(pk)!!.clamp(v) } } ?: emptyMap() }
        val tunes = ts.mapNotNull { t -> strategy(t.strategy)?.let { s -> t.copy(values = s.values(t.values)) } }
        return b.copy(runs = runs, scales = scales, current = safe, changes = cs, tunes = tunes, streaks = zs, variants = vs.map { v ->
            val s = strategy(v.strategy)
            when {
                s == null -> v
                // A variant from before a key was added (R14's re-entry cap) shadows on with that key at its default.
                v.alive && v.values.keys != s.params.map { it.key }.toSet() && s.params.map { it.key }.toSet().let { all ->
                    all.containsAll(v.values.keys) && LATER_KEYS.containsAll(all - v.values.keys) } ->
                    v.copy(values = (s.defaults.filterKeys { it in LATER_KEYS } + v.values).mapValues { (pk, x) -> s.param(pk)?.clamp(x) ?: x })
                // A variant born before S3 carries the old model's keys: its record is not the real arm's, so it is retired.
                v.alive && v.values.keys != s.params.map { it.key }.toSet() -> v.copy(status = Status.RETIRED, why = OLD_RULES)
                else -> v.copy(values = v.values.mapValues { (pk, x) -> s.param(pk)?.clamp(x) ?: x })
            }
        })
    }
}
