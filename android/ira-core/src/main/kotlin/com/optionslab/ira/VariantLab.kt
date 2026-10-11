package com.optionslab.ira

import com.optionslab.ira.ShadowSim.Entry as E
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The strategies improve themselves through SHADOW VARIANTS (Boss, 11 Oct: "make the strategies self-improve"). For each running
 * strategy the lab knows its tunable parameters and their SAFE bounds ([STRATEGIES]: only what the strategy's own rules have -
 * the entry window, the opening range's minutes, a breakout buffer, the stop, the target, the time and no-progress exits).
 *
 *  - A few nearby variants per strategy ([MAX_PER_STRATEGY] alive, [MAX_TOTAL] in all) follow the same day's data as VIRTUAL
 *    trades ([ShadowSim]: no order, not even paper), settled on the option's 1-minute wicks with the paper costs. Before one
 *    starts it must not be worse than the current rules on the stored days (the pre-check).
 *  - Each is compared with the CURRENT rules day by day (the variant's rupees less the current rules' on the same day).
 *  - Promotion needs all of: at least [MIN_N] compared days, the mSPRT's always-valid p ([DiscoveryStats.alwaysValidP]), a
 *    Benjamini-Hochberg pass across EVERY variant ever tried, and the trial-count penalty ([DiscoveryStats.penalizedAlpha])
 *    with every variant ever tried counted. A PAPER strategy's winner is applied by itself to the lab's paper rules (logged,
 *    undoable: [Change], AUTO) and posted as "variant ready"; a LIVE strategy's becomes a PROPOSAL to Boss, applied only with
 *    his PIN ([accept]).
 *  - A variant that is worse (the same test the other way) or runs [MAX_N] days without winning is retired, and a new
 *    neighbour is spawned with half the step (a hill-climb that shrinks).
 *
 * Never: a stop wider than the strategy's researched maximum, more lots (lots are not a parameter at all), an entry outside
 * the F&O hours ([ShadowSim.Rules.invalid]). Pure: the app hands in the days, the runs and the clock's date.
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

    enum class Run(val words: String) { PAPER("paper"), LIVE("live"), OFF("not running") }

    /** One tunable: its key, plain words, the SAFE bounds and its step. A stop's [max] is the strategy's researched maximum. */
    data class Param(val key: String, val words: String, val min: Double, val max: Double, val step: Double, val unit: Unit) {
        fun clamp(x: Double): Double = if (x.isNaN()) min else x.coerceIn(min, max)
        fun say(x: Double): String = when (unit) {
            Unit.PCT -> "%.1f%%".format(Locale.ENGLISH, x * 100).replace(".0%", "%")
            Unit.BPS -> "%.3f%%".format(Locale.ENGLISH, x * 100)
            Unit.PTS -> "${x.roundToInt()} pts"
            Unit.MIN -> "${x.roundToInt()} min"
            Unit.TIME -> ShadowSim.hhmm(x.roundToInt())
        }
    }

    enum class Unit { PCT, BPS, PTS, MIN, TIME }

    /** A strategy the lab can tune: its key (the arm's source), name, indices, its rules now ([base]) and its tunables. */
    data class Strategy(val key: String, val label: String, val underlyings: List<String>, val base: ShadowSim.Rules, val params: List<Param>) {
        val defaults: Map<String, Double> get() = params.associate { it.key to get(base, it.key) }
        fun rules(values: Map<String, Double>): ShadowSim.Rules =
            params.fold(base) { r, p -> values[p.key]?.let { set(r, p.key, p.clamp(it)) } ?: r }
        fun param(key: String): Param? = params.firstOrNull { it.key == key }
    }

    fun get(r: ShadowSim.Rules, key: String): Double = when (key) {
        "until" -> r.untilMin.toDouble(); "from" -> r.fromMin.toDouble(); "or" -> r.orMin.toDouble(); "buffer" -> r.buffer
        "stopPct" -> r.stopPct ?: 0.0; "stopPts" -> r.stopPts ?: 0.0; "targetPct" -> r.targetPct ?: 0.0; "targetPts" -> r.targetPts ?: 0.0
        "np" -> (r.noProgressMin ?: 0).toDouble(); "npg" -> r.noProgressGain; "ts" -> (r.timeStopMin ?: 0).toDouble()
        "is" -> r.indexStopPct ?: 0.0; "param" -> r.param
        else -> throw IllegalArgumentException(key)
    }

    fun set(r: ShadowSim.Rules, key: String, v: Double): ShadowSim.Rules = when (key) {
        "until" -> r.copy(untilMin = v.roundToInt()); "from" -> r.copy(fromMin = v.roundToInt()); "or" -> r.copy(orMin = v.roundToInt())
        "buffer" -> r.copy(buffer = v); "stopPct" -> r.copy(stopPct = v); "stopPts" -> r.copy(stopPts = v)
        "targetPct" -> r.copy(targetPct = v); "targetPts" -> r.copy(targetPts = v); "np" -> r.copy(noProgressMin = v.roundToInt())
        "npg" -> r.copy(noProgressGain = v); "ts" -> r.copy(timeStopMin = v.roundToInt()); "is" -> r.copy(indexStopPct = v); "param" -> r.copy(param = v)
        else -> throw IllegalArgumentException(key)
    }

    private val until = Param("until", "last entry", 13 * 60.0, 14 * 60 + 30.0, 15.0, Unit.TIME)
    private val orStop = Param("stopPts", "stop", 20.0, 40.0, 5.0, Unit.PTS)

    /**
     * The strategies the lab tunes, with the arms' own rules as they stand and bounds from their research: the ORB family's
     * stop never wider than its -40 points, Liquidity's never wider than the 25% its research tried (15% now), VIX
     * divergence's never wider than its 15%. Night holds overnight, Hero and the MCX arms trade data the lab does not keep:
     * they are not tuned here ([NOT_TUNED]).
     */
    val STRATEGIES: List<Strategy> = listOf(
        Strategy("orb", "ORB", listOf("BANKNIFTY"),
            ShadowSim.Rules(E.ORB_BREAK, barMin = 5, orMin = 50, fromMin = 610, untilMin = 841, stopPts = 40.0, targetPts = 40.0, maxTrades = 3),
            listOf(until, Param("or", "opening range", 35.0, 65.0, 5.0, Unit.MIN), Param("buffer", "breakout buffer", 0.0, 0.001, 0.00025, Unit.BPS),
                orStop, Param("targetPts", "target", 30.0, 80.0, 10.0, Unit.PTS))),
        Strategy("orb_fresh", "ORB Fresh", listOf("BANKNIFTY"),
            ShadowSim.Rules(E.ORB_BREAK, barMin = 5, orMin = 50, fresh = true, fromMin = 610, untilMin = 841, stopPts = 40.0, targetPts = 40.0, maxTrades = 3),
            listOf(until, Param("or", "opening range", 35.0, 65.0, 5.0, Unit.MIN), Param("buffer", "breakout buffer", 0.0, 0.001, 0.00025, Unit.BPS),
                orStop, Param("targetPts", "target", 30.0, 80.0, 10.0, Unit.PTS))),
        Strategy("orb_sweep", "ORB Sweep", listOf("BANKNIFTY"),
            ShadowSim.Rules(E.ORB_SWEEP, barMin = 5, orMin = 50, fromMin = 610, untilMin = 870, stopPts = 40.0, targetPts = 80.0, maxTrades = 2),
            listOf(until, orStop, Param("targetPts", "target", 40.0, 100.0, 10.0, Unit.PTS))),
        Strategy("range_fade", "Range Fade", listOf("BANKNIFTY"),
            ShadowSim.Rules(E.RANGE_FADE, barMin = 5, orMin = 50, param = 0.10, fromMin = 635, untilMin = 841, stopPts = 40.0, targetPts = 40.0, maxTrades = 2),
            listOf(until, Param("param", "edge of the range", 0.05, 0.20, 0.025, Unit.PCT), orStop, Param("targetPts", "target", 20.0, 60.0, 5.0, Unit.PTS))),
        Strategy("liquidity", "Liquidity 15+5", listOf("BANKNIFTY", "FINNIFTY", "MIDCPNIFTY"),
            ShadowSim.Rules(E.PREV_DAY_BREAK, barMin = 5, fromMin = 565, untilMin = 841, stopPct = 0.15, noProgressMin = 20, noProgressGain = 0.05,
                indexStopPct = 0.00055, maxTrades = 2, itm = 1),
            listOf(until, Param("stopPct", "stop", 0.10, 0.25, 0.025, Unit.PCT), Param("np", "no-progress minutes", 10.0, 30.0, 5.0, Unit.MIN),
                Param("npg", "no-progress gain", 0.03, 0.08, 0.01, Unit.PCT), Param("is", "index stop", 0.0003, 0.001, 0.0001, Unit.BPS))),
        Strategy("vix_div", "VIX divergence", listOf("NIFTY", "BANKNIFTY"),
            ShadowSim.Rules(E.VIX_DIVERGENCE, barMin = 1, param = 0.002, fromMin = 630, untilMin = 811, stopPct = 0.15, noProgressMin = 20,
                noProgressGain = 0.05, itm = 1),
            listOf(Param("stopPct", "stop", 0.10, 0.15, 0.0125, Unit.PCT), Param("np", "no-progress minutes", 10.0, 30.0, 5.0, Unit.MIN),
                Param("param", "index move", 0.0015, 0.003, 0.00025, Unit.PCT))),
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

    enum class ChangeKind(val words: String) { AUTO_PAPER("applied on paper by itself"), LIVE_PROPOSAL("proposed for live (your PIN)") }
    enum class ChangeStatus(val words: String) { APPLIED("applied"), OFFERED("waiting for you"), ACCEPTED("applied with your PIN"), DECLINED("declined"), UNDONE("undone") }

    /** One change of a strategy's lab rules: from, to, why (the numbers), its kind and status. */
    data class Change(val id: String, val strategy: String, val variant: String, val kind: ChangeKind, val status: ChangeStatus,
                      val day: LocalDate, val from: Map<String, Double>, val to: Map<String, Double>, val why: String)

    /**
     * Everything the lab keeps: per strategy its current rules' values ([current]; absent: its defaults), its account
     * ([runs]) and the hill-climb's step ([scales]); the variants; the changes; [tried] every variant ever made (the
     * penalty's count); [retiredCount] every one retired or failed (kept or not).
     */
    data class Book(
        val current: Map<String, Map<String, Double>> = emptyMap(), val runs: Map<String, Run> = emptyMap(),
        val scales: Map<String, Double> = emptyMap(), val variants: List<Variant> = emptyList(), val changes: List<Change> = emptyList(),
        val tried: Int = 0, val retiredCount: Int = 0, val nextId: Int = 1, val lastDay: LocalDate? = null,
    ) {
        fun currentOf(key: String): Map<String, Double> = strategy(key)?.let { s -> s.defaults + (current[key] ?: emptyMap()) } ?: emptyMap()
        fun alive(key: String? = null): List<Variant> = variants.filter { it.alive && (key == null || it.strategy == key) }
        fun runOf(key: String): Run = runs[key] ?: Run.OFF
    }

    // ---- spawning --------------------------------------------------------------------------------------------------------

    /**
     * Up to [n] neighbours of [center] for [s]: one or two parameters moved by +-[scale] x their step (x1 or x2), clamped to
     * the SAFE bounds, never equal to [center] or to one of [taken], and always valid rules. Seeded by [rng].
     */
    fun neighbours(s: Strategy, center: Map<String, Double>, scale: Double, rng: DiscoveryStats.Rng, n: Int,
                   taken: Collection<Map<String, Double>> = emptyList()): List<Map<String, Double>> {
        val out = ArrayList<Map<String, Double>>()
        var tries = 0
        while (out.size < n && tries < n * 30) {
            tries++
            val v = HashMap(center)
            val moves = 1 + rng.nextInt(2)
            repeat(moves) {
                val p = s.params[rng.nextInt(s.params.size)]
                val dir = if (rng.nextInt(2) == 0) -1 else 1
                val mult = 1 + rng.nextInt(2)
                v[p.key] = p.clamp((v[p.key] ?: get(s.base, p.key)) + dir * mult * scale * p.step)
            }
            if (same(v, center) || (out + taken).any { same(it, v) }) continue
            if (s.rules(v).invalid() != null) continue
            out += v
        }
        return out
    }

    private fun same(a: Map<String, Double>, b: Map<String, Double>) = a.keys.union(b.keys).all { abs((a[it] ?: 0.0) - (b[it] ?: 0.0)) < 1e-12 }

    /** The pre-check: (variant rupees, current rupees) on [history] (the stored days); passes when the variant is not worse. */
    fun precheck(s: Strategy, values: Map<String, Double>, current: Map<String, Double>, history: List<ShadowSim.Day>): Pair<Double, Double> {
        val vr = s.rules(values); val cr = s.rules(current)
        var v = 0.0; var c = 0.0
        for (d in history) if (d.underlying in s.underlyings) { v += ShadowSim.run(vr, d).sumOf { it.net }; c += ShadowSim.run(cr, d).sumOf { it.net } }
        return v to c
    }

    /**
     * Fills [key]'s variants up to [MAX_PER_STRATEGY] (and the lab to [MAX_TOTAL]): neighbours of its current rules, each
     * counted as tried, each pre-checked on [history]; a worse one is kept as failed (counted in the graveyard).
     */
    fun spawn(book: Book, key: String, day: LocalDate, history: List<ShadowSim.Day>, rng: DiscoveryStats.Rng): Book {
        val s = strategy(key) ?: return book
        var b = book
        val want = minOf(MAX_PER_STRATEGY - b.alive(key).size, MAX_TOTAL - b.alive().size)
        if (want <= 0) return b
        val cur = b.currentOf(key)
        val scale = b.scales[key] ?: 1.0
        val fresh = neighbours(s, cur, scale, rng, want, b.variants.filter { it.strategy == key && it.alive }.map { it.values })
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
    }

    data class Pass(val book: Book, val events: List<Event>)

    /**
     * One trading day: [runs] (how each strategy is armed today), [days] the day's data per index, [history] the stored days
     * (for the pre-check of new neighbours). Each living variant against the current rules; then the judgement; then refill.
     */
    fun day(book: Book, date: LocalDate, runs: Map<String, Run>, days: Map<String, ShadowSim.Day>, history: List<ShadowSim.Day>,
            rng: DiscoveryStats.Rng): Pass {
        if (book.lastDay != null && !date.isAfter(book.lastDay)) return Pass(book, emptyList())
        var b = book.copy(runs = book.runs + runs, lastDay = date)
        // 1. Compare.
        b = b.copy(variants = b.variants.map { v ->
            if (!v.alive) return@map v
            val s = strategy(v.strategy) ?: return@map v
            val vr = s.rules(v.values); val cr = s.rules(b.currentOf(v.strategy))
            var vn = 0.0; var cn = 0.0; var traded = false
            for (u in s.underlyings) {
                val d = days[u] ?: continue
                val vt = ShadowSim.run(vr, d); val ct = ShadowSim.run(cr, d)
                if (vt.isNotEmpty() || ct.isNotEmpty()) traded = true
                vn += vt.sumOf { it.net }; cn += ct.sumOf { it.net }
            }
            if (!traded) v.copy(lastDay = date) else {
                val diffs = v.diffs + (vn - cn)
                v.copy(diffs = diffs, vSum = v.vSum + vn, cSum = v.cSum + cn, lastDay = date,
                    pBetter = DiscoveryStats.alwaysValidP(diffs, 1, MIN_SD), pWorse = DiscoveryStats.alwaysValidP(diffs, -1, MIN_SD))
            }
        })
        // 2. Judge; 3. refill every running strategy.
        val j = judge(b, date)
        b = j.book
        for (s in STRATEGIES) if (b.runOf(s.key) != Run.OFF || b.alive(s.key).isNotEmpty()) b = spawn(b, s.key, date, history, rng)
        return Pass(trim(b), j.events)
    }

    /** Does [v] win: enough days, better on average, the sequential p under the trial penalty AND a BH pass across [allP]? */
    fun wins(v: Variant, allP: Collection<Double>, tried: Int): Boolean =
        v.n >= MIN_N && v.diffs.average() > 0 && v.pBetter <= DiscoveryStats.penalizedAlpha(ALPHA, tried) && DiscoveryStats.bhPass(v.pBetter, allP, Q)

    /** Is [v] done losing: worse by the same test the other way, or out of days? */
    fun loses(v: Variant): Boolean = (v.n >= MIN_N / 2 && v.pWorse <= ALPHA) || v.n >= MAX_N

    /** Every p the false-discovery check runs across: each variant ever judged (the retired at their last p), the forgotten as 1. */
    fun allP(b: Book): List<Double> = b.variants.filter { it.status != Status.PRECHECK_FAILED }.map { it.pBetter } +
        List((b.tried - b.variants.size).coerceAtLeast(0)) { 1.0 }

    /** The judgement of the living variants on [date]: promotions (paper by itself, live proposed) and retirements. */
    fun judge(book: Book, date: LocalDate): Pass {
        var b = book
        val events = ArrayList<Event>()
        val allP = allP(b)
        val penalty = DiscoveryStats.penalizedAlpha(ALPHA, b.tried)
        val promotedFor = HashSet<String>()
        for (v in b.variants.filter { it.status == Status.ALIVE }.sortedBy { it.pBetter }) {
            if (v.strategy in promotedFor) continue
            val wins = wins(v, allP, b.tried)
            val loses = loses(v)
            when {
                wins -> {
                    val why = "over ${v.n} days it made ${DiscoveryStats.rs(v.vSum)} against ${DiscoveryStats.rs(v.cSum)} " +
                        "(${DiscoveryStats.rs(v.diffs.average())} a day better; sequential p ${fmtP(v.pBetter)}, passes the false-discovery " +
                        "check across ${b.tried} variants tried and the penalty ${fmtP(penalty)})"
                    when (b.runOf(v.strategy)) {
                        Run.PAPER -> {
                            val c = Change("C${b.nextId}", v.strategy, v.id, ChangeKind.AUTO_PAPER, ChangeStatus.APPLIED, date, b.currentOf(v.strategy), v.values, why)
                            b = promote(b, v, c, Status.PROMOTED, applied = true)
                            events += Event.Applied(c); promotedFor += v.strategy
                        }
                        Run.LIVE -> if (b.changes.none { it.strategy == v.strategy && it.status == ChangeStatus.OFFERED }) {
                            val c = Change("C${b.nextId}", v.strategy, v.id, ChangeKind.LIVE_PROPOSAL, ChangeStatus.OFFERED, date, b.currentOf(v.strategy), v.values, why)
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

    /** Boss's Undo of an applied paper change: the lab's paper rules back to before it. Null when it cannot be undone. */
    fun undo(b: Book, id: String): Book? {
        val c = b.changes.firstOrNull { it.id == id } ?: return null
        if (c.status != ChangeStatus.APPLIED && c.status != ChangeStatus.ACCEPTED) return null
        if (c.kind == ChangeKind.LIVE_PROPOSAL) return null   // live rules move back only through the PIN flow ([accept] of the old)
        return b.copy(current = b.current + (c.strategy to c.from), changes = b.changes.map { if (it.id == id) it.copy(status = ChangeStatus.UNDONE) else it })
    }

    /** Boss's yes to a live proposal: ONLY with [pinConfirmed]. Null when refused or not waiting. */
    fun accept(b: Book, id: String, pinConfirmed: Boolean): Book? {
        if (!pinConfirmed) return null
        val c = b.changes.firstOrNull { it.id == id && it.status == ChangeStatus.OFFERED } ?: return null
        return b.copy(current = b.current + (c.strategy to c.to),
            changes = b.changes.map { if (it.id == id) it.copy(status = ChangeStatus.ACCEPTED) else it },
            variants = b.variants.map { if (it.id == c.variant) it.copy(status = Status.PROMOTED) else it })
    }

    fun decline(b: Book, id: String): Book {
        val c = b.changes.firstOrNull { it.id == id && it.status == ChangeStatus.OFFERED } ?: return b
        return b.copy(changes = b.changes.map { if (it.id == id) it.copy(status = ChangeStatus.DECLINED) else it },
            variants = b.variants.map { if (it.id == c.variant) it.copy(status = Status.RETIRED, why = "you declined it") else it },
            retiredCount = b.retiredCount + 1)
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
        for (c in b.changes) append("C|${c.id}|${c.strategy}|${c.variant}|${c.kind.name}|${c.status.name}|${c.day}|${kv(c.from)}|${kv(c.to)}|${clean(c.why)}\n")
    }

    fun decode(text: String?): Book {
        if (text.isNullOrBlank()) return Book()
        var b = Book()
        val runs = HashMap<String, Run>(); val scales = HashMap<String, Double>(); val cur = HashMap<String, Map<String, Double>>()
        val vs = ArrayList<Variant>(); val cs = ArrayList<Change>()
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
                "C" -> cs += Change(p[1], p[2], p[3], ChangeKind.valueOf(p[4]), ChangeStatus.valueOf(p[5]), LocalDate.parse(p[6]), unkv(p[7]), unkv(p[8]), p[9])
            }
        }
        // Bounds again on the way in: a file can never carry a value outside the SAFE bounds.
        val safe = cur.mapValues { (k, m) -> strategy(k)?.let { s -> m.filterKeys { s.param(it) != null }.mapValues { (pk, v) -> s.param(pk)!!.clamp(v) } } ?: emptyMap() }
        return b.copy(runs = runs, scales = scales, current = safe, variants = vs.map { v ->
            val s = strategy(v.strategy); if (s == null) v else v.copy(values = v.values.mapValues { (pk, x) -> s.param(pk)?.clamp(x) ?: x })
        }, changes = cs)
    }
}
