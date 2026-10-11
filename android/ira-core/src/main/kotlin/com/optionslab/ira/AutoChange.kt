package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Autopilot learning (Boss, 11 Oct: "I want Jarvis and the agents we created to learn and auto-heal: learn by themselves
 * and make changes accordingly"). From what Jarvis learned ([AgentLessons]) and the agents' own records (the trade manager
 * and its specialists, the self-review, the order flow's shadow log), [candidates] finds the changes the evidence points to;
 * [classify] sorts each into:
 *
 *  - AUTO (done without asking, logged, undoable with one tap):
 *    (a) anything confined to SHADOW - the order flow's CONFIRM threshold while that strategy is in SHADOW, the trap guard's
 *        shadow thresholds, the market brain's regime thresholds (shadow), the specialists' weights in shadow, the workers'
 *        safety-net schedule, self-healing's backoff, the findings' consensus weights;
 *    (b) on PAPER, any of: a specialist's weight within its bounds, a mute, the trade manager ACT <-> SHADOW, pausing or
 *        resuming a paper strategy's new entries;
 *    (c) on LIVE, only what strictly lowers risk: mute a specialist with a clearly negative record, the trade manager ACT ->
 *        SHADOW when its live record is negative (the original exits are always safe), park a strategy whose self-review
 *        says it is broken (no new live entries), and the self-review's thresholds made stricter. Never an open order.
 *  - ASK (put to Boss through the app's requests; on LIVE with his PIN): anything that RAISES live risk - un-mute, resume
 *    live entries, the manager to ACT on live, a weight up on a strategy the manager acts on live.
 *  - NEVER: a safety setting (PIN, lock, Live, AI-live, the guard, Google speech, the day lock, this very setting), size or
 *    lots, a wider stop, a new strategy going live, real-order permissions ([never], [DENY]).
 *
 * Guards ([plan]): live AUTO only on a STRONG lesson, paper and shadow AUTO on BUILDING or above; at most [MAX_AUTO_DAY] auto
 * changes a day of which [MAX_LIVE_DAY] live; nothing on the same target within [FLIP_DAYS] days of its last change; a target
 * Boss undid or said no to rests [REST_DAYS] days; a blacklisted target ([BLACKLIST_DAYS]) is left alone.
 *
 * Every auto change is on probation ([review]), judged by a sequential test (11 Oct, "smarter learning"): each night the
 * outcomes since it was made (a muted specialist's would-be votes, the manager against the original rules, the stream that
 * moved a threshold), each signed by what the change expected, go through Wald's SPRT ([LearnStats.Sprt]: "it hurts" at
 * -0.2 sd a case against "it helps" at +0.2 sd; alpha 0.05 bounds keeping a change that hurts, beta 0.20 reverting one that
 * helps). It keeps or reverts as soon as the evidence is clear (never before [MIN_JUDGE] cases); the old limit -
 * [PROBATION_N] settled cases or [PROBATION_DAYS] trading days - is the cap, where an undecided test falls back to the old
 * rule (reverted only if worse beyond noise, [Z_NOISE] standard errors). A revert that would itself raise live risk is asked
 * instead. Two reverts on one target within [REVERT_WINDOW_DAYS] days blacklist it for [BLACKLIST_DAYS] days. Each entry
 * keeps the test's state in words ([Entry.test]: "deciding: 62% of the way"), the lesson's q ([Entry.q]) and its pre-flight
 * replay ([Entry.preflight], [Preflight]); a change skipped by its pre-flight or held by a regime condition is logged as
 * [Status.SKIPPED]. [Book.trials] counts every configuration Autopilot has tried (it raises the evidence bar, [AutoGate]);
 * paper experiments live in [Book.experiments] ([Experiments]).
 *
 * Pure: no clock, no storage. The app ([com.optionslab.app]'s Autopilot) applies each change through the agents' own setters.
 */
object AutoChange {
    enum class Risk(val words: String) { LOWER("risk-lowering"), NEUTRAL_SHADOW("shadow only"), RAISE("adds risk") }
    enum class Scope(val words: String) { SHADOW("shadow"), PAPER("paper"), LIVE("live") }
    enum class Decision { AUTO, ASK, NEVER, HOLD }

    /** Boss's setting: OFF (nothing by itself), SAFE (the default: the rules above), SHADOW_ONLY (only shadow changes by itself). */
    enum class Mode(val words: String) {
        OFF("off"), SAFE("safe"), SHADOW_ONLY("shadow only");
        /** How much it may do by itself (OFF < SHADOW_ONLY < SAFE): a voice may only ever lower it. */
        val level: Int get() = when (this) { OFF -> 0; SHADOW_ONLY -> 1; SAFE -> 2 }
    }

    enum class Status(val words: String) {
        PROBATION("on probation"), KEPT("kept"), REVERTED("reverted"), REVERT_ASKED("revert asked"), UNDONE("undone"),
        OFFERED("asked"), DECLINED("declined"),
        /** Not applied: its pre-flight replay was worse (or over its time budget), or its lesson holds under one regime only. */
        SKIPPED("skipped"),
    }

    /** What changes, by which agent family. NEVER kinds are listed so the denylist is a test, not a hope. */
    enum class Kind(val agent: String, val words: String, val never: Boolean = false) {
        SPECIALIST_WEIGHT("trade manager specialists", "a specialist's weight"),
        SPECIALIST_MUTE("trade manager specialists", "a specialist's mute"),
        MANAGER_MODE("trade manager", "the trade manager's mode"),
        PAPER_ENTRIES("strategies", "new paper entries"),
        LIVE_ENTRIES("self-review", "new live entries"),
        FLOW_THRESHOLD("order flow", "the order flow's CONFIRM threshold (shadow)"),
        FLOW_MODE("order flow", "the order flow's mode"),
        TRAP_THRESHOLD("trap guard", "a trap guard threshold (shadow)"),
        WAKE_SCHEDULE("worker wake", "the safety-net schedule"),
        HEAL_BACKOFF("self-healing", "a retry backoff"),
        REGIME_THRESHOLD("market brain", "a regime threshold (shadow)"),
        DRIFT_THRESHOLD("self-review", "a park threshold"),
        FINDINGS_WEIGHT("findings", "a consensus weight (shadow)"),
        SAFETY_SETTING("settings", "a safety setting", never = true),
        SIZE("sizing", "size or lots", never = true),
        STOP_WIDER("stops", "a wider stop", never = true),
        STRATEGY_LIVE("strategies", "a strategy going live", never = true),
        REAL_ORDERS("orders", "real-order permissions", never = true),
    }

    /** The evidence behind a change: how many, the rupees (sum), the mean a case, which way, and how sure. */
    data class Evidence(val n: Int, val rs: Double, val confidence: AgentLessons.Confidence, val sign: Int = 0) {
        val mean: Double get() = if (n == 0) 0.0 else rs / n
        fun words(): String = "$n case${if (n == 1) "" else "s"}, ${AgentLessons.rs(rs)}, ${confidence.word}"
    }

    data class Change(
        val kind: Kind,
        /** What it changes: "spec:solo:oi", "mgr:pine:live", "paper:night_r3", "live:liquidity", "flow:orb", "knob:trap.lockout_sec". */
        val target: String,
        val from: String, val to: String,
        /** Plain words, with the numbers. */
        val reason: String,
        val evidence: Evidence,
        val risk: Risk, val scope: Scope,
        /** The lesson it came from (Jarvis learns from his own changes: [AgentLessons.autoStatus]). */
        val lessonId: String? = null,
        /** The sign of the outcome stream that justified it (probation sets what follows against it). */
        val expect: Int = 0,
        /** +1: its effect is the stream itself (the manager acting); -1: what it avoided (a mute, a pause); 0: shadow, no money. */
        val effect: Int = 0,
        /** A numeric change's allowed range (a weight's bounds, a knob's): outside it is NEVER. */
        val lo: Double? = null, val hi: Double? = null,
        /** The plain name ("the OI specialist on Solo (live)"). */
        val what: String = target,
        /**
         * This change puts an earlier one back ([inverse]: an undo or a revert). Only such a change may turn the order flow
         * back to CONFIRM (where an earlier change took it from); never kept in the log (worked out when needed).
         */
        val revert: Boolean = false,
    ) {
        val key: String get() = "${kind.name}:$target"
    }

    // ---- the numbers, each documented ----------------------------------------------------------------------------------------

    const val MAX_AUTO_DAY = 5
    const val MAX_LIVE_DAY = 2
    const val MAX_ASK_DAY = 2
    const val FLIP_DAYS = 3L
    const val REST_DAYS = 30L
    const val REOFFER_DAYS = 7L
    const val PROBATION_N = 15
    const val PROBATION_DAYS = 5
    const val MIN_JUDGE = 5
    const val Z_NOISE = 2.0
    const val REVERT_WINDOW_DAYS = 90L
    const val BLACKLIST_DAYS = 30L
    const val MAX_ENTRIES = 400

    /**
     * One agent's learnable number and its bounds (Boss: "document each bound"). [stricter]: +1 a higher value lowers risk,
     * -1 a lower value does, 0 neither (shadow only). [stricterOnly]: it may only ever move the stricter way by itself.
     */
    data class Knob(val key: String, val kind: Kind, val words: String, val default: Double, val min: Double, val max: Double, val step: Double,
                    val scope: Scope, val stricter: Int = 0, val stricterOnly: Boolean = false)

    val KNOBS: List<Knob> = listOf(
        // Trap guard (shadow candidates; the live guard keeps its researched constants, [TrapGuard]): the stop-hunt lockout
        // 60-300 s (default 120), the pull share 0.30-0.70 (0.50), the absorption multiple 2-5x (3x).
        Knob("trap.lockout_sec", Kind.TRAP_THRESHOLD, "the trap guard's stop-hunt lockout (s, shadow)", TrapGuard.LOCKOUT_SEC.toDouble(), 60.0, 300.0, 30.0, Scope.SHADOW, stricter = 1),
        Knob("trap.pull_drop", Kind.TRAP_THRESHOLD, "the trap guard's pull share (shadow)", TrapGuard.PULL_DROP, 0.30, 0.70, 0.05, Scope.SHADOW, stricter = -1),
        Knob("trap.absorb_x", Kind.TRAP_THRESHOLD, "the trap guard's absorption multiple (shadow)", TrapGuard.ABSORB_X, 2.0, 5.0, 0.5, Scope.SHADOW, stricter = -1),
        // WorkerWake: the safety-net pass with nothing held, within its own 15-60 s ([WorkerWake.NET_MIN_SEC]..[WorkerWake.NET_MAX_SEC]).
        Knob("wake.net_flat_sec", Kind.WAKE_SCHEDULE, "the safety-net pass with nothing held (s)", WorkerWake.NET_FLAT_DEFAULT_SEC.toDouble(),
            WorkerWake.NET_MIN_SEC.toDouble(), WorkerWake.NET_MAX_SEC.toDouble(), 5.0, Scope.SHADOW),
        // SelfHeal: the relay's and the candle feed's breaker pause, 5-60 s (default 10 s). Zerodha's own requests are never tuned.
        Knob("heal.relay_open_ms", Kind.HEAL_BACKOFF, "the relay's retry pause (ms)", 10_000.0, 5_000.0, 60_000.0, 5_000.0, Scope.SHADOW),
        Knob("heal.candles_open_ms", Kind.HEAL_BACKOFF, "the candle feed's retry pause (ms)", 10_000.0, 5_000.0, 60_000.0, 5_000.0, Scope.SHADOW),
        // MarketBrain (shadow only: what the learned thresholds would read, beside the real regime): busy at 1.5-3.0 z (2.0).
        Knob("brain.busy_z", Kind.REGIME_THRESHOLD, "the market brain's busy threshold (z, shadow)", MarketBrain.BUSY_Z, 1.5, 3.0, 0.25, Scope.SHADOW),
        // DriftReview: the park thresholds may only get stricter by themselves - z 3.3 down to 2.6, the drawdown multiple 2.5 down to 2.0.
        Knob("drift.z_crit", Kind.DRIFT_THRESHOLD, "the self-review's drift threshold (z)", DriftReview.Z_CRIT, 2.6, DriftReview.Z_CRIT, 0.1, Scope.LIVE, stricter = -1, stricterOnly = true),
        Knob("drift.dd_x", Kind.DRIFT_THRESHOLD, "the self-review's drawdown multiple", DriftReview.DD_X, 2.0, DriftReview.DD_X, 0.1, Scope.LIVE, stricter = -1, stricterOnly = true),
    )
    // The other families' learnable parts and their bounds, set where they live:
    //  - the trade manager's specialists: weights within SpecialistBook.MIN_X..MAX_X of the default, 10% a night, and mutes
    //    ([fromTuning] logs the specialists' own nightly tuning; [candidates] mutes on a negative record, un-mutes on a positive one);
    //  - the trade manager: ACT <-> SHADOW per strategy and account (never OFF by itself; LIVE to ACT only asked);
    //  - the order flow: the CONFIRM threshold in SHADOW within OrderFlow.MIN_THRESHOLD..MAX_THRESHOLD, [FLOW_STEP] at a time;
    //    its mode only toward SHADOW by itself (CONFIRM -> SHADOW on paper; on a live-capable strategy asked);
    //  - the paper arms (MCX, VIX, Night: [PAPER_ARMS]): new paper entries paused or resumed;
    //  - the self-review: a broken live strategy parked (DriftReview's own calibration), its thresholds only stricter;
    //  - the findings' consensus weights: classified ([Kind.FINDINGS_WEIGHT], shadow only) - the bus has no weights to learn yet.
    private val KNOB_BY = KNOBS.associateBy { it.key }
    fun knob(key: String): Knob? = KNOB_BY[key]

    /** The order flow's CONFIRM threshold may move in shadow within [OrderFlow.MIN_THRESHOLD]..[OrderFlow.MAX_THRESHOLD], 5 at a time. */
    const val FLOW_STEP = 5

    /**
     * The paper arms Autopilot may pause or resume by their record (MCX, VIX, Night; paper only), each with the words its
     * bot's name is matched by in the lessons (strat:<owner>).
     */
    val PAPER_ARMS: List<Pair<String, List<String>>> = listOf(
        "mcx_us_silver" to listOf("us night silver", "us-night silver"),
        "mcx_silverm_am" to listOf("silver morning"),
        "mcx_ng_eve" to listOf("natural gas", "mcx evening"),
        "mcx_trend12" to listOf("12-month trend", "12 month trend", "mcx trend"),
        "vix_div" to listOf("vix"),
        "night_r3" to listOf("night"),
    )

    fun paperArmOf(owner: String): String? {
        val o = owner.lowercase(Locale.ENGLISH)
        return PAPER_ARMS.firstOrNull { (_, ws) -> ws.any { it in o } }?.first
    }

    // ---- never ---------------------------------------------------------------------------------------------------------------

    /** A target or value naming a safety setting is never changed by Autopilot, whatever its kind says. */
    val DENY = Regex("(?i)(\\bpin\\b|app.?lock|\\bai.?live|ai trades|\\bguard\\b|kill.?switch|google|speech|day.?lock|\\blots?\\b|max.?lots|lot.?size|" +
        "\\bsize\\b|real.?orders?|zerodha|allow.?real|\\bk\\.mode|live.?mode|autolearn|autopilot|setting:|\\b(jarvis|g|k|pin|lock|sec|kite)\\.)")

    /** The safety settings by name (a change to any of them is NEVER, tested). */
    val SAFETY = listOf("pin", "app lock", "live mode", "ai-live", "ai trades go live", "guard", "kill switch", "google speech", "day lock",
        "autopilot learning", "lot size", "real orders")

    fun never(c: Change): Boolean = c.kind.never || DENY.containsMatchIn(c.target) || DENY.containsMatchIn(c.from) || DENY.containsMatchIn(c.to) ||
        // The order flow to CONFIRM: never by itself, except putting back a CONFIRM an earlier change took away (it only skips).
        (c.kind == Kind.FLOW_MODE && c.to == OrderFlow.Mode.CONFIRM.name && !(c.revert && c.from == OrderFlow.Mode.SHADOW.name)) ||
        (c.lo != null && c.to.toDoubleOrNull()?.let { it < c.lo - 1e-9 } == true) || (c.hi != null && c.to.toDoubleOrNull()?.let { it > c.hi + 1e-9 } == true) ||
        knobOutOfBounds(c)

    private fun knobOutOfBounds(c: Change): Boolean {
        if (!c.target.startsWith("knob:")) return false
        val k = knob(c.target.removePrefix("knob:")) ?: return true
        val to = c.to.toDoubleOrNull() ?: return true
        val from = c.from.toDoubleOrNull() ?: return true
        if (to < k.min - 1e-9 || to > k.max + 1e-9) return true
        // A stricter-only knob may never loosen by itself - but putting back a value it had ([inverse]: Boss's undo or a revert,
        // a live raise and so only with his PIN) is not loosening by itself: within its bounds it goes (review G5-3).
        return !c.revert && k.stricterOnly && k.stricter * (to - from) < -1e-9
    }

    /** The risk a change really carries, whatever it claims (a LOWER claim is never trusted: what it does decides). */
    fun riskOf(c: Change): Risk {
        val derived = when (c.kind) {
            Kind.SPECIALIST_MUTE -> if (c.to == MUTED) Risk.LOWER else Risk.RAISE
            Kind.MANAGER_MODE -> if (c.to == TradeManager.Mode.ACT.name) Risk.RAISE else Risk.LOWER
            Kind.PAPER_ENTRIES -> if (c.to == PAUSED) Risk.LOWER else Risk.RAISE
            Kind.LIVE_ENTRIES -> if (c.to == PARKED) Risk.LOWER else Risk.RAISE
            // CONFIRM only ever skips entries (lower risk); away from it takes the skips away (adds risk).
            Kind.FLOW_MODE -> if (c.scope == Scope.SHADOW) Risk.NEUTRAL_SHADOW else if (c.to == OrderFlow.Mode.CONFIRM.name) Risk.LOWER else Risk.RAISE
            Kind.SPECIALIST_WEIGHT -> if (c.scope == Scope.SHADOW) Risk.NEUTRAL_SHADOW else {
                val a = c.from.toDoubleOrNull(); val b = c.to.toDoubleOrNull()
                if (a != null && b != null && b < a) Risk.LOWER else Risk.RAISE
            }
            Kind.DRIFT_THRESHOLD -> {
                val k = knob(c.target.removePrefix("knob:"))
                val a = c.from.toDoubleOrNull(); val b = c.to.toDoubleOrNull()
                if (k != null && a != null && b != null && k.stricter * (b - a) > 0) Risk.LOWER else Risk.RAISE
            }
            Kind.FLOW_THRESHOLD, Kind.TRAP_THRESHOLD, Kind.WAKE_SCHEDULE, Kind.HEAL_BACKOFF, Kind.REGIME_THRESHOLD, Kind.FINDINGS_WEIGHT ->
                if (c.scope == Scope.SHADOW) Risk.NEUTRAL_SHADOW else Risk.RAISE
            else -> Risk.RAISE
        }
        // A RAISE claim always stands; any other claim gives way to what the change really does.
        return if (c.risk == Risk.RAISE) Risk.RAISE else derived
    }

    const val MUTED = "muted"
    const val COUNTS = "counts"
    const val PAUSED = "paused"
    const val ON = "on"
    const val PARKED = "parked"
    const val LIVE = "live"

    /** What may be done by itself on PAPER (b). */
    private val PAPER_AUTO = setOf(Kind.SPECIALIST_WEIGHT, Kind.SPECIALIST_MUTE, Kind.MANAGER_MODE, Kind.PAPER_ENTRIES, Kind.FLOW_MODE, Kind.DRIFT_THRESHOLD)
    /** What may be done by itself on LIVE, and only when it lowers risk (c). */
    private val LIVE_LOWER_AUTO = setOf(Kind.SPECIALIST_MUTE, Kind.MANAGER_MODE, Kind.LIVE_ENTRIES, Kind.DRIFT_THRESHOLD)

    /**
     * AUTO, ASK, NEVER or HOLD (not enough evidence yet, or Autopilot off) for [c] under [mode]. Live AUTO needs a STRONG
     * lesson, paper and shadow AUTO a BUILDING one.
     */
    fun classify(c: Change, mode: Mode = Mode.SAFE): Decision {
        if (never(c)) return Decision.NEVER
        if (mode == Mode.OFF) return Decision.HOLD
        val risk = riskOf(c)
        val conf = c.evidence.confidence
        val building = conf.ordinal >= AgentLessons.Confidence.BUILDING.ordinal
        return when (c.scope) {
            Scope.SHADOW -> if (risk == Risk.RAISE) Decision.ASK else if (building) Decision.AUTO else Decision.HOLD
            Scope.PAPER -> when {
                mode == Mode.SHADOW_ONLY -> if (building) Decision.ASK else Decision.HOLD
                c.kind in PAPER_AUTO -> if (building) Decision.AUTO else Decision.HOLD
                else -> if (building) Decision.ASK else Decision.HOLD
            }
            Scope.LIVE -> when {
                risk == Risk.RAISE -> if (building) Decision.ASK else Decision.HOLD
                mode == Mode.SHADOW_ONLY -> if (building) Decision.ASK else Decision.HOLD
                risk == Risk.LOWER && c.kind in LIVE_LOWER_AUTO && conf == AgentLessons.Confidence.STRONG -> Decision.AUTO
                building -> Decision.ASK
                else -> Decision.HOLD
            }
        }
    }

    /** The change that undoes [c] (from and to swapped; its risk worked out afresh). */
    fun inverse(c: Change, reason: String): Change {
        val back = c.copy(from = c.to, to = c.from, reason = reason, risk = Risk.NEUTRAL_SHADOW, revert = true)
        return back.copy(risk = riskOf(back))
    }

    /** Does undoing [c] raise live risk (and so need Boss's PIN)? */
    fun undoNeedsPin(c: Change): Boolean = c.scope == Scope.LIVE && riskOf(inverse(c, "")) == Risk.RAISE

    // ---- evidence from records -------------------------------------------------------------------------------------------------

    /** A stream of outcomes (oldest first) judged as [AgentLessons] judges a lesson: strong at 30+ with 2 of 3 time splits agreeing. */
    fun evidenceOf(values: List<Double>): Evidence {
        val n = values.size
        if (n == 0) return Evidence(0, 0.0, AgentLessons.Confidence.WEAK, 0)
        val sum = values.sum()
        val sign = sgn(sum)
        val k = AgentLessons.SPLITS
        val splits = if (n < k) emptyList() else (0 until k).map { i -> sgn(values.subList(i * n / k, (i + 1) * n / k).sum()) }
        val agree = splits.count { it == sign && it != 0 }
        val conf = when {
            n >= AgentLessons.STRONG_N && sign != 0 && agree >= AgentLessons.AGREE -> AgentLessons.Confidence.STRONG
            n >= AgentLessons.BUILDING_N -> AgentLessons.Confidence.BUILDING
            else -> AgentLessons.Confidence.WEAK
        }
        return Evidence(n, sum, conf, sign)
    }

    fun evidenceOf(l: AgentLessons.Lesson): Evidence = AgentLessons.evidence(l).let { Evidence(it.n, it.rs, l.confidence, l.sign) }

    private fun sgn(x: Double) = if (x > 1e-9) 1 else if (x < -1e-9) -1 else 0

    // ---- what the evidence points to ------------------------------------------------------------------------------------------

    /** What the agents kept, as the app reads it. */
    data class Inputs(
        val day: LocalDate,
        val lessons: List<AgentLessons.Lesson> = emptyList(),
        /** The trade manager's records (paper and live). */
        val records: List<TradeManager.Record> = emptyList(),
        val policy: TradeManager.Policy = TradeManager.Policy(),
        /** Per family, the specialists muted in the self-tuned book (shadow and paper) and in the frozen one (live), id to why. */
        val mutedPaper: Map<String, Map<String, String>> = emptyMap(),
        val mutedLive: Map<String, Map<String, String>> = emptyMap(),
        val drift: List<DriftReview.Review> = emptyList(),
        val parked: Set<String> = emptySet(),
        val paused: Set<String> = emptySet(),
        /** The order flow's settings that are not the default (key to mode and threshold). */
        val flow: Map<String, FlowShadow.Setting> = emptyMap(),
        /** The learned knob values now (absent: the default). */
        val knobs: Map<String, Double> = emptyMap(),
        /** Targets Autopilot itself paused, parked or muted (only those it may resume by itself on paper). */
        val ownPaused: Set<String> = emptySet(),
    )

    private fun live(l: AgentLessons.Lesson) = l.status != AgentLessons.Status.RETIRED && l.confidence != AgentLessons.Confidence.WEAK

    /** Every change the evidence points to now (not yet classified or capped), in a fixed order. */
    fun candidates(i: Inputs): List<Change> {
        val out = ArrayList<Change>()
        val byId = i.lessons.filter { live(it) }.associateBy { it.id }
        // 1. The specialists: mute on a negative record (live where the manager acts on live trades), un-mute on a positive one.
        for (l in byId.values.sortedBy { it.id }) {
            if (!l.id.startsWith("spec:")) continue
            val parts = l.id.split(':'); if (parts.size != 3) continue
            val fam = parts[1]; val id = parts[2]
            if (id !in ManagerTeam.IDS || TradeManager.familyOf(fam) == null) continue
            val ev = evidenceOf(l)
            val liveActs = i.policy.mode(fam, TradeManager.Account.LIVE) == TradeManager.Mode.ACT
            val paperActs = i.policy.mode(fam, TradeManager.Account.PAPER) == TradeManager.Mode.ACT
            val name = "the ${ManagerTeam.nameOf(id)} specialist on ${TradeManager.familyName(fam)}"
            val target = "spec:$fam:$id"
            if (l.sign < 0) {
                val scope = if (liveActs) Scope.LIVE else if (paperActs) Scope.PAPER else Scope.SHADOW
                val already = if (liveActs) i.mutedLive[fam]?.containsKey(id) == true else i.mutedPaper[fam]?.containsKey(id) == true
                if (!already) out += Change(Kind.SPECIALIST_MUTE, target, COUNTS, MUTED,
                    "$name hurt its exits against the original rules: ${ev.words()}", ev, Risk.LOWER, scope, l.id, expect = -1, effect = -1,
                    what = "$name (${scope.words})")
            } else if (l.sign > 0) {
                val m = if (liveActs) i.mutedLive[fam] else i.mutedPaper[fam]
                val why = m?.get(id)
                // Only a mute that was not Boss's own (his hand stays his).
                if (why != null && !why.startsWith("muted by you")) {
                    val scope = if (liveActs) Scope.LIVE else if (paperActs) Scope.PAPER else Scope.SHADOW
                    out += Change(Kind.SPECIALIST_MUTE, target, MUTED, COUNTS, "$name now helps its exits: ${ev.words()}", ev,
                        Risk.RAISE, scope, l.id, expect = 1, effect = 1, what = "$name (${scope.words})")
                }
            }
        }
        // 2. The trade manager per strategy, paper and live apart, from its own record against the original rules.
        for (f in TradeManager.FAMILIES) {
            for (acct in TradeManager.Account.entries) {
                val xs = i.records.filter { it.trade.family == f.key && it.trade.account == acct }
                    .mapNotNull { r -> r.vsOriginal?.let { v -> (r.actual?.atMs ?: r.trade.entryMs) to v } }.sortedBy { it.first }.map { it.second }
                if (xs.isEmpty()) continue
                val ev = evidenceOf(xs)
                val mode = i.policy.mode(f.key, acct)
                val isLive = acct == TradeManager.Account.LIVE
                val scope = if (isLive) Scope.LIVE else Scope.PAPER
                val target = "mgr:${f.key}:${acct.name.lowercase(Locale.ENGLISH)}"
                val who = "the trade manager on ${f.name} (${scope.words})"
                if (mode == TradeManager.Mode.ACT && ev.sign < 0)
                    out += Change(Kind.MANAGER_MODE, target, TradeManager.Mode.ACT.name, TradeManager.Mode.SHADOW.name,
                        "$who did worse than the original rules: ${ev.words()}; back to recording only (the original exits are always safe)",
                        ev, Risk.LOWER, scope, "mgr:${f.key}", expect = -1, effect = -1, what = who)
                else if (mode == TradeManager.Mode.SHADOW && ev.sign > 0 && f.canAct && (!isLive || (f.canActLive && !f.paperOnly)))
                    out += Change(Kind.MANAGER_MODE, target, TradeManager.Mode.SHADOW.name, TradeManager.Mode.ACT.name,
                        "$who would have done better than the original rules: ${ev.words()}", ev, Risk.RAISE, scope, "mgr:${f.key}",
                        expect = 1, effect = 1, what = who)
            }
        }
        // 3. The self-review: a broken live strategy parks (its review is calibrated: drifting counts as strong); a broken paper one pauses.
        for (r in i.drift) {
            if (r.verdict != DriftReview.Verdict.DRIFTING) continue
            val ev = Evidence(r.trades, r.windows.lastOrNull()?.let { it.mean * it.m } ?: 0.0, AgentLessons.Confidence.STRONG, -1)
            if (r.live && r.key !in i.parked)
                out += Change(Kind.LIVE_ENTRIES, "live:${r.key}", LIVE, PARKED, "${r.name} is drifting from its backtest: ${r.reasons.joinToString("; ")}",
                    ev, Risk.LOWER, Scope.LIVE, "drift:${r.key}:live", expect = -1, effect = -1, what = "${r.name}'s new live entries")
            if (!r.live && r.key !in i.paused)
                out += Change(Kind.PAPER_ENTRIES, "paper:${r.key}", ON, PAUSED, "${r.name} is drifting from its backtest on paper: ${r.reasons.joinToString("; ")}",
                    ev, Risk.LOWER, Scope.PAPER, "drift:${r.key}:paper", expect = -1, effect = -1, what = "${r.name}'s new paper entries")
        }
        // 4. The paper arms (MCX, VIX, Night) by their own trades: pause a losing one, resume one Autopilot paused when it turns.
        for (l in byId.values.sortedBy { it.id }) {
            if (!l.id.startsWith("strat:")) continue
            val key = paperArmOf(l.name) ?: continue
            val ev = evidenceOf(l)
            val target = "paper:$key"
            if (l.sign < 0 && key !in i.paused)
                out += Change(Kind.PAPER_ENTRIES, target, ON, PAUSED, "${l.name} loses money on paper: ${ev.words()}", ev, Risk.LOWER, Scope.PAPER,
                    l.id, expect = -1, effect = -1, what = "${l.name}'s new paper entries")
            else if (l.sign > 0 && key in i.paused && target in i.ownPaused)
                out += Change(Kind.PAPER_ENTRIES, target, PAUSED, ON, "${l.name} makes money on paper again: ${ev.words()}", ev, Risk.RAISE, Scope.PAPER,
                    l.id, expect = 1, effect = 1, what = "${l.name}'s new paper entries")
        }
        // 5. The order flow: in SHADOW its CONFIRM threshold moves 5 toward what the shadow log says (stricter when trades against
        // the flow lose, looser when they make money). A paper-only strategy under CONFIRM that skips winners goes back to SHADOW.
        for (s in FlowShadow.STRATEGIES) {
            val setting = i.flow[s.key] ?: FlowShadow.Setting(FlowShadow.DEFAULT)
            val l = byId["flow:${AgentLessons.norm(s.key)}:against"] ?: continue
            val ev = evidenceOf(l)
            when (setting.mode) {
                OrderFlow.Mode.SHADOW -> {
                    val next = (setting.threshold + (if (l.sign < 0) FLOW_STEP else -FLOW_STEP)).coerceIn(OrderFlow.MIN_THRESHOLD, OrderFlow.MAX_THRESHOLD)
                    if (l.sign != 0 && next != setting.threshold)
                        out += Change(Kind.FLOW_THRESHOLD, "flow:${s.key}", setting.threshold.toString(), next.toString(),
                            "${s.label} trades taken against the order flow ${if (l.sign < 0) "lose" else "make"} money: ${ev.words()}",
                            ev, Risk.NEUTRAL_SHADOW, Scope.SHADOW, l.id, expect = l.sign, effect = 0,
                            lo = OrderFlow.MIN_THRESHOLD.toDouble(), hi = OrderFlow.MAX_THRESHOLD.toDouble(), what = "${s.label}'s order flow threshold (shadow)")
                }
                OrderFlow.Mode.CONFIRM -> if (l.sign > 0)
                    out += Change(Kind.FLOW_MODE, "flow:${s.key}", OrderFlow.Mode.CONFIRM.name, OrderFlow.Mode.SHADOW.name,
                        "${s.label}: the entries the order flow would skip make money: ${ev.words()}", ev, Risk.RAISE,
                        if (s.liveCapable) Scope.LIVE else Scope.PAPER, l.id, expect = 1, effect = 1, what = "${s.label}'s order flow")
                OrderFlow.Mode.OFF -> {}
            }
        }
        // 6. The knobs (trap guard, wake, self-healing, brain, self-review), each from its own lessons.
        fun knobStep(k: Knob, dir: Int, ev: Evidence, lessonId: String?, why: String) {
            if (dir == 0) return
            val cur = i.knobs[k.key] ?: k.default
            val next = round4((cur + dir * k.step).coerceIn(k.min, k.max))
            if (abs(next - cur) < 1e-9) return
            if (k.stricterOnly && k.stricter * (next - cur) < 0) return
            val risk = if (k.scope == Scope.SHADOW) Risk.NEUTRAL_SHADOW else if (k.stricter * (next - cur) > 0) Risk.LOWER else Risk.RAISE
            out += Change(k.kind, "knob:${k.key}", fmt(cur), fmt(next), "$why: ${ev.words()}", ev, risk, k.scope, lessonId,
                expect = ev.sign, effect = 0, lo = k.min, hi = k.max, what = k.words)
        }
        fun knobStepL(k: Knob, dir: Int, l: AgentLessons.Lesson, why: String) = knobStep(k, dir, evidenceOf(l), l.id, why)
        val trapKnob = mapOf("stop_hunt" to "trap.lockout_sec", "failed_break" to "trap.lockout_sec", "pull_bid" to "trap.pull_drop", "pull_ask" to "trap.pull_drop",
            "absorption_buy" to "trap.absorb_x", "absorption_sell" to "trap.absorb_x", "exhaustion_buy" to "trap.absorb_x", "exhaustion_sell" to "trap.absorb_x")
        val trapsSeen = HashSet<String>()
        for (l in byId.values.sortedByDescending { AgentLessons.evidence(it).n }) {
            if (!l.id.startsWith("trap:")) continue
            val kk = trapKnob[l.id.removePrefix("trap:")] ?: continue
            if (!trapsSeen.add(kk)) continue
            val k = knob(kk) ?: continue
            // Trades taken with the flag up lose: the flag is right - stricter; they make money: looser.
            knobStepL(k, if (l.sign < 0) k.stricter else -k.stricter, l, "${l.name} ${if (l.sign < 0) "lose" else "make"} money")
        }
        byId["health:stream"]?.let { l -> knob("wake.net_flat_sec")?.let { k ->
            val cur = i.knobs[k.key] ?: k.default
            // Feed trouble often: the safety net checks more often; reliable: back toward its default.
            val dir = if (l.sign < 0) -1 else if (cur < k.default) 1 else 0
            knobStepL(k, dir, l, "the live price stream ${if (l.sign < 0) "has had trouble often" else "has been reliable"}")
        } }
        for ((dep, kk) in listOf("relay" to "heal.relay_open_ms", "candles" to "heal.candles_open_ms")) {
            val l = byId["health:$dep"] ?: continue
            val k = knob(kk) ?: continue
            val cur = i.knobs[k.key] ?: k.default
            val dir = if (l.sign < 0) 1 else if (cur > k.default) -1 else 0
            knobStepL(k, dir, l, "${l.name} ${if (l.sign < 0) "has had trouble often: retries wait longer" else "has been reliable"}")
        }
        val pauseL = byId.values.filter { it.id.startsWith("pause:") }.maxByOrNull { AgentLessons.evidence(it).n }
        if (pauseL != null) knob("brain.busy_z")?.let { k ->
            // Entries during a would-be pause lose: the brain should call "busy" sooner (lower z); they make money: later.
            knobStepL(k, if (pauseL.sign < 0) -1 else 1, pauseL, "${pauseL.name} ${if (pauseL.sign < 0) "lose" else "make"} money")
        }
        // The self-review made stricter when a strategy it calls on track has lost money a trade over 30 or more trades.
        for (r in i.drift) {
            if (r.verdict != DriftReview.Verdict.ON_TRACK || r.expectation.lottery) continue
            val w = r.windows.lastOrNull() ?: continue
            if (w.m < AgentLessons.STRONG_N || w.mean >= 0 || r.expectation.mean <= 0) continue
            val ev = Evidence(w.m, w.mean * w.m, AgentLessons.Confidence.STRONG, -1)
            knob("drift.z_crit")?.let { k -> knobStep(k, -1, ev, "drift:${r.key}:${r.venue}",
                "${r.name} (${r.venue}) lost ${AgentLessons.rs(w.mean)} a trade over ${w.m} trades while its review said on track") }
            break
        }
        return out
    }

    private fun round4(x: Double) = Math.round(x * 10_000) / 10_000.0
    fun fmt(x: Double): String = if (abs(x - Math.rint(x)) < 1e-9) Math.rint(x).toLong().toString() else String.format(Locale.ENGLISH, "%.2f", x).trimEnd('0').trimEnd('.')

    /** The self-tuning's own weight changes for [family] ([SpecialistBook.tune]), to be logged as Autopilot's (applied already). */
    fun fromTuning(family: String, before: SpecialistBook.Tuning?, after: SpecialistBook.Tuning, base: ManagerConfig, scope: Scope): List<Change> {
        val out = ArrayList<Change>()
        for (id in ManagerTeam.IDS) {
            val def = base.spec(id).weight
            val a = before?.weights?.get(id) ?: def
            val b = after.weights[id] ?: def
            val name = "the ${ManagerTeam.nameOf(id)} specialist on ${TradeManager.familyName(family)}"
            if (abs(a - b) > 1e-9) out += Change(Kind.SPECIALIST_WEIGHT, "weight:$family:$id", fmt(a), fmt(b),
                "$name's record over ${SpecialistBook.MIN_SETTLED}+ settled decisions (the specialists' own nightly tuning)",
                Evidence(SpecialistBook.MIN_SETTLED, 0.0, AgentLessons.Confidence.BUILDING), Risk.NEUTRAL_SHADOW, scope, "spec:$family:$id",
                expect = if (b > a) 1 else -1, lo = SpecialistBook.MIN_X * def, hi = SpecialistBook.MAX_X * def, what = "$name's weight")
            val ma = before?.muted?.containsKey(id) == true
            val mb = after.muted.containsKey(id)
            if (!ma && mb) out += Change(Kind.SPECIALIST_MUTE, "spec:$family:$id", COUNTS, MUTED, after.muted[id] ?: "", Evidence(SpecialistBook.MIN_SETTLED, 0.0,
                AgentLessons.Confidence.BUILDING, -1), Risk.LOWER, scope, "spec:$family:$id", expect = -1, effect = -1, what = name)
        }
        return out
    }

    // ---- the book ------------------------------------------------------------------------------------------------------------

    data class Entry(
        val id: String, val atMs: Long, val day: LocalDate, val change: Change, val status: Status,
        /** Who did it: "autopilot", "specialists' tuning", "self-review". */
        val by: String = "autopilot",
        /** The day its status last moved, and why (a revert's reason). */
        val closedOn: LocalDate? = null, val note: String = "",
        /** Since it was made: the cases settled and the rupee effect against what it avoided or did (kept at the last review). */
        val afterN: Int = 0, val effect: Double = 0.0,
        /** The lesson's q-value when it was made (null: no lesson behind it, or not tested). */
        val q: Double? = null,
        /** The probation's sequential test in words ("deciding: 62% of the way (leaning keep), 9 cases"); empty: not run yet. */
        val test: String = "",
        /** Its pre-flight replay in words ([Preflight.Result.words]); empty: none (shadow, asked, or logged by an agent). */
        val preflight: String = "",
    ) {
        val applied: Boolean get() = status == Status.PROBATION || status == Status.KEPT || status == Status.REVERT_ASKED
    }

    data class Book(
        val entries: List<Entry> = emptyList(),
        val blacklist: Map<String, LocalDate> = emptyMap(),
        val rest: Map<String, LocalDate> = emptyMap(),
        val lastRun: LocalDate? = null,
        val lastMidday: LocalDate? = null,
        /** Every configuration Autopilot has ever tried (applied by itself, applied on Boss's yes, or started as an experiment). */
        val trials: Int = 0,
        /** The paper experiments, running and ended ([Experiments]). */
        val experiments: List<Experiments.Experiment> = emptyList(),
    )

    /** Who counts as a trial when recorded on probation: Autopilot itself and a graduated experiment (not the specialists' own tuning). */
    private val TRIERS = setOf("autopilot", Experiments.BY)

    /**
     * Can [kind] take a regime-conditional value (one setting per market regime)? None can yet: the specialists' weights,
     * mutes and the manager's mode are per strategy only ([SpecialistBook], [TradeManager.Policy]). A change whose lesson holds
     * under one regime only is therefore recorded ([Status.SKIPPED]) and not applied everywhere ([AutoGate]).
     */
    fun condCapable(kind: Kind): Boolean = false

    /** What [plan] decided for each candidate. */
    data class Plan(val auto: List<Change>, val ask: List<Change>, val held: List<Pair<Change, String>>, val never: List<Change>)

    private fun scopeRank(c: Change) = when (c.scope) { Scope.LIVE -> 0; Scope.PAPER -> 1; Scope.SHADOW -> 2 }

    /** Is [key] resting today: blacklisted, rested after Boss's undo or no, or changed within [FLIP_DAYS]? The reason, or null. */
    fun blocked(book: Book, key: String, day: LocalDate): String? {
        book.blacklist[key]?.let { if (!day.isAfter(it)) return "blacklisted after two reverts, until ${AgentLessons.day(it)}" }
        book.rest[key]?.let { if (!day.isAfter(it)) return "resting after your undo or no, until ${AgentLessons.day(it)}" }
        val last = book.entries.lastOrNull { it.change.key == key && it.status != Status.OFFERED && it.status != Status.DECLINED }
        if (last != null) {
            val since = listOfNotNull(last.day, last.closedOn).max()
            // A skipped change (pre-flight failed, learning frozen, regime-only) changed nothing: it waits the same days before
            // it is tried again (SmarterLearningTest), said as what it is - never "changed on" (review G5-8).
            if (day.isBefore(since.plusDays(FLIP_DAYS))) return if (last.status == Status.SKIPPED)
                "tried on ${AgentLessons.day(since)} and skipped (nothing changed): tried again after $FLIP_DAYS days"
                else "changed on ${AgentLessons.day(since)}: no flip-flop within $FLIP_DAYS days"
        }
        val asked = book.entries.lastOrNull { it.change.key == key && it.status == Status.OFFERED }
        if (asked != null && day.isBefore(asked.day.plusDays(REOFFER_DAYS))) return "asked on ${AgentLessons.day(asked.day)}"
        return null
    }

    /**
     * Today's plan from [candidates]: classified, blocked targets held, the daily caps kept (counting what [book] already did
     * today); [healthOnly] (the 12:30 pass) keeps only risk-lowering changes. Deterministic: the same inputs, the same plan.
     */
    fun plan(book: Book, candidates: List<Change>, day: LocalDate, mode: Mode, healthOnly: Boolean = false): Plan {
        val auto = ArrayList<Change>(); val ask = ArrayList<Change>(); val held = ArrayList<Pair<Change, String>>(); val never = ArrayList<Change>()
        // A graduated experiment's change counts toward the day's caps like Autopilot's own.
        val todays = book.entries.filter { it.day == day && it.by in TRIERS }
        var autos = todays.count { it.status != Status.OFFERED && it.status != Status.DECLINED && it.status != Status.SKIPPED }
        var lives = todays.count { it.status != Status.OFFERED && it.status != Status.DECLINED && it.status != Status.SKIPPED && it.change.scope == Scope.LIVE }
        var asks = todays.count { it.status == Status.OFFERED || it.status == Status.DECLINED }
        val seen = HashSet<String>()
        val ordered = candidates.sortedWith(compareBy<Change>({ if (riskOf(it) == Risk.LOWER) 0 else 1 }, { scopeRank(it) },
            { -it.evidence.confidence.ordinal }, { -it.evidence.n }, { it.key }, { it.to }))
        for (c in ordered) {
            if (!seen.add(c.key)) { held += c to "another change to the same target today"; continue }
            val d = classify(c, mode)
            if (d == Decision.NEVER) { never += c; continue }
            if (d == Decision.HOLD) { held += c to if (mode == Mode.OFF) "Autopilot learning is off" else "not enough evidence yet"; continue }
            if (healthOnly && riskOf(c) != Risk.LOWER) { held += c to "the mid-day pass only lowers risk"; continue }
            val why = blocked(book, c.key, day)
            if (why != null) { held += c to why; continue }
            if (d == Decision.AUTO) {
                when {
                    autos >= MAX_AUTO_DAY -> held += c to "today's $MAX_AUTO_DAY changes are made"
                    c.scope == Scope.LIVE && lives >= MAX_LIVE_DAY -> held += c to "today's $MAX_LIVE_DAY live changes are made"
                    else -> { auto += c; autos++; if (c.scope == Scope.LIVE) lives++ }
                }
            } else {
                if (asks >= MAX_ASK_DAY) held += c to "today's questions are asked" else { ask += c; asks++ }
            }
        }
        return Plan(auto, ask, held, never)
    }

    private fun nextId(book: Book, day: LocalDate, k: Int = 0): String = "$day#${book.entries.size + k + 1}"

    /** [c] kept in the book with [status] (an AUTO change applied: PROBATION; one asked: OFFERED). The book and the entry. */
    fun record(book: Book, c: Change, status: Status, atMs: Long, day: LocalDate, by: String = "autopilot", note: String = "",
               q: Double? = null, preflight: String = ""): Pair<Book, Entry> {
        var id = nextId(book, day)
        var k = 1
        while (book.entries.any { it.id == id }) id = nextId(book, day, k++)
        val e = Entry(id, atMs, day, c, status, by, note = note, q = q, preflight = preflight)
        val tried = if (status == Status.PROBATION && by in TRIERS && c.kind !in UNTRIED) 1 else 0
        return capped(book.copy(entries = book.entries + e, trials = book.trials + tried)) to e
    }

    /**
     * Kinds whose change no agent acts on (the trap guard's learned thresholds are a shadow record only - the live guard keeps
     * its researched constants): not a configuration tried, so they never raise tonight's bar for real changes (review G5-6).
     */
    private val UNTRIED = setOf(Kind.TRAP_THRESHOLD)

    private fun capped(b: Book): Book {
        if (b.entries.size <= MAX_ENTRIES) return b
        // The oldest closed ones go first; what is on probation or asked stays.
        val open = setOf(Status.PROBATION, Status.OFFERED, Status.REVERT_ASKED)
        val drop = b.entries.filter { it.status !in open }.take(b.entries.size - MAX_ENTRIES).map { it.id }.toSet()
        return b.copy(entries = b.entries.filter { it.id !in drop })
    }

    private fun update(book: Book, id: String, f: (Entry) -> Entry): Book = book.copy(entries = book.entries.map { if (it.id == id) f(it) else it })

    fun entry(book: Book, id: String): Entry? = book.entries.firstOrNull { it.id == id }

    /** Boss said yes to an asked change: done, on probation from now. */
    fun accepted(book: Book, id: String, atMs: Long, day: LocalDate): Book {
        if (entry(book, id)?.status != Status.OFFERED) return book
        return update(book, id) { it.copy(status = Status.PROBATION, atMs = atMs, day = day, note = "on your yes") }.let { it.copy(trials = it.trials + 1) }
    }

    /** Boss said no: nothing done, and that target rests [REST_DAYS] days. */
    fun declined(book: Book, id: String, day: LocalDate): Book {
        val e = entry(book, id) ?: return book
        if (e.status != Status.OFFERED && e.status != Status.REVERT_ASKED) return book
        return if (e.status == Status.OFFERED)
            update(book, id) { it.copy(status = Status.DECLINED, closedOn = day) }.copy(rest = book.rest + (e.change.key to day.plusDays(REST_DAYS)))
        else update(book, id) { it.copy(status = Status.KEPT, closedOn = day, note = "you kept it") }
    }

    /** The undo of [id]: its inverse, and whether that needs Boss's PIN (it raises live risk); null when it cannot be undone. */
    data class Undo(val entry: Entry, val inverse: Change, val needsPin: Boolean)

    fun undo(book: Book, id: String): Undo? {
        val e = entry(book, id) ?: return null
        if (!e.applied) return null
        val inv = inverse(e.change, "undone by you")
        return Undo(e, inv, undoNeedsPin(e.change))
    }

    /** The newest change that can be undone ("undo the last change"). */
    fun lastUndoable(book: Book): Entry? = book.entries.lastOrNull { it.applied }

    /** [id] undone by Boss: its target rests [REST_DAYS] days (Autopilot does not redo what he undid). */
    fun undone(book: Book, id: String, day: LocalDate): Book {
        val e = entry(book, id) ?: return book
        return update(book, id) { it.copy(status = Status.UNDONE, closedOn = day, note = "undone by you") }.copy(rest = book.rest + (e.change.key to day.plusDays(REST_DAYS)))
    }

    // ---- probation: the auto-heal of learning itself -------------------------------------------------------------------------

    /** Weekdays after [from] up to and including [to] (the trading days, holidays aside). */
    fun tradingDays(from: LocalDate, to: LocalDate): Int {
        var n = 0
        var d = from.plusDays(1)
        while (!d.isAfter(to)) { if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) n++; d = d.plusDays(1) }
        return n
    }

    enum class Verdict { WAIT, KEEP, REVERT, ASK_REVERT }

    /** One probation judged: what to do, the change that reverts it, and the reason in words. */
    data class Judged(val entry: Entry, val verdict: Verdict, val inverse: Change?, val why: String, val afterN: Int, val effect: Double)

    /** Is [after] (the outcomes since, oldest first) worse than [expect] beyond noise? */
    fun worse(after: List<Double>, expect: Int): Boolean {
        if (expect == 0 || after.size < MIN_JUDGE) return false
        val n = after.size
        val mean = after.average()
        if (expect * mean >= 0) return false
        val sd = sqrt(after.sumOf { (it - mean) * (it - mean) } / (n - 1))
        val se = sd / sqrt(n.toDouble())
        return se <= 1e-9 || abs(mean) > Z_NOISE * se
    }

    /** The probation's sequential test over [xs] (each signed by what [c] expected; nothing to test when it expected nothing). */
    fun sequential(c: Change, xs: List<Double>): LearnStats.Sprt.State? =
        if (c.expect == 0) null else LearnStats.Sprt.run(xs.map { c.expect * it }, minN = MIN_JUDGE)

    /**
     * Every change on probation judged on [day] from [after] (its outcomes since it was made; null: no stream for it). The
     * sequential test ([sequential]) keeps or reverts it as soon as it is clear; otherwise, once over the cap ([PROBATION_N]
     * cases or [PROBATION_DAYS] trading days): kept, or - worse beyond noise - reverted by itself (or asked when the revert
     * would raise live risk). Two reverts on one target blacklist it. The book and what to do.
     */
    fun review(book: Book, day: LocalDate, after: (Entry) -> List<Double>?): Pair<Book, List<Judged>> {
        var b = book
        val out = ArrayList<Judged>()
        for (e in book.entries) {
            if (e.status != Status.PROBATION) continue
            val xs = after(e).orEmpty()
            val effect = e.change.effect * xs.sum()
            val seq = sequential(e.change, xs)
            val test = seq?.words().orEmpty()
            val over = xs.size >= PROBATION_N || tradingDays(e.day, day) >= PROBATION_DAYS
            val call = seq?.call
            val n0 = seq?.n ?: xs.size
            if (call == null && !over) {
                b = update(b, e.id) { it.copy(afterN = xs.size, effect = effect, test = test) }
                out += Judged(e, Verdict.WAIT, null, "on probation", xs.size, effect); continue
            }
            if (call == LearnStats.Sprt.Call.KEEP || (call == null && !worse(xs, e.change.expect))) {
                val why = when {
                    call != null -> "kept: the sequential test decided early, after $n0 cases (${AgentLessons.rs(xs.take(n0).sum())}), as it expected"
                    xs.size < MIN_JUDGE -> "kept: too few cases since (${xs.size}) to judge it worse"
                    else -> "kept: ${xs.size} cases since, ${AgentLessons.rs(xs.sum())}, not worse than it expected"
                }
                b = update(b, e.id) { it.copy(status = Status.KEPT, closedOn = day, note = why, afterN = xs.size, effect = effect, test = test) }
                out += Judged(e, Verdict.KEEP, null, why, xs.size, effect)
                continue
            }
            val why = if (call == LearnStats.Sprt.Call.REVERT)
                "reverted: the sequential test decided early, after $n0 cases (${AgentLessons.rs(xs.take(n0).sum())}, " +
                    "${AgentLessons.rs(xs.take(n0).average())} each), against what it expected"
                else "reverted: since it was made ${xs.size} cases came to ${AgentLessons.rs(xs.sum())} (${AgentLessons.rs(xs.average())} each), " +
                "against what it expected, beyond noise"
            val inv = inverse(e.change, why)
            val asks = inv.scope == Scope.LIVE && riskOf(inv) == Risk.RAISE
            b = update(b, e.id) { it.copy(status = if (asks) Status.REVERT_ASKED else Status.REVERTED, closedOn = day, note = why, afterN = xs.size, effect = effect, test = test) }
            if (!asks) b = blacklistIfRepeated(b, e.change.key, day)
            out += Judged(e, if (asks) Verdict.ASK_REVERT else Verdict.REVERT, inv, why, xs.size, effect)
        }
        return b to out
    }

    /**
     * A revert [review] decided could not be made by itself (its agent refused: it needs Boss's PIN there, or nothing could be
     * changed): not recorded as reverted - asked of Boss instead ([Status.REVERT_ASKED], his PIN in the change log).
     */
    fun revertNotApplied(book: Book, id: String, why: String): Book {
        val e = entry(book, id) ?: return book
        if (e.status != Status.REVERTED) return book
        return update(book, id) { it.copy(status = Status.REVERT_ASKED, note = "$why - the revert could not be made by itself: yours, with your PIN") }
    }

    /**
     * Why a won experiment's paper change may not be made by itself on [day] under [mode] (null: it may) - the same gates as
     * any other automatic change: SAFE only, never a NEVER, paper only, a resting or blacklisted target left alone, and the
     * day's cap of automatic changes.
     */
    fun graduateHeld(book: Book, c: Change, day: LocalDate, mode: Mode): String? {
        if (never(c)) return "never changed by itself"
        if (c.scope != Scope.PAPER) return "experiments graduate on paper only"
        if (mode != Mode.SAFE) return if (mode == Mode.OFF) "Autopilot learning is off" else "Autopilot learning is on for shadow changes only"
        blocked(book, c.key, day)?.let { return it }
        val made = book.entries.count { it.day == day && it.by in TRIERS && it.status != Status.OFFERED && it.status != Status.DECLINED && it.status != Status.SKIPPED }
        if (made >= MAX_AUTO_DAY) return "today's $MAX_AUTO_DAY changes are made"
        return null
    }

    /** Boss said yes to an asked revert: reverted, and counted toward the blacklist. */
    fun revertAccepted(book: Book, id: String, day: LocalDate): Book {
        val e = entry(book, id) ?: return book
        if (e.status != Status.REVERT_ASKED) return book
        return blacklistIfRepeated(update(book, id) { it.copy(status = Status.REVERTED, closedOn = day) }, e.change.key, day)
    }

    private fun blacklistIfRepeated(b: Book, key: String, day: LocalDate): Book {
        val n = b.entries.count { it.change.key == key && it.status == Status.REVERTED && it.closedOn != null && !it.closedOn.isBefore(day.minusDays(REVERT_WINDOW_DAYS)) }
        return if (n >= 2) b.copy(blacklist = b.blacklist + (key to day.plusDays(BLACKLIST_DAYS))) else b
    }

    // ---- words ---------------------------------------------------------------------------------------------------------------

    fun line(e: Entry): String {
        val c = e.change
        return "${AgentLessons.day(e.day)} · ${c.kind.agent} · ${c.what}: ${c.from} → ${c.to} (${c.scope.words}, ${riskOf(c).words}) · ${e.status.words}" +
            (if (e.note.isNotEmpty()) " · ${e.note}" else "")
    }

    data class Summary(val today: Int, val todayPaper: Int, val todayLive: Int, val todayShadow: Int, val probation: Int, val kept: Int,
                       val reverted: Int, val asked: Int, val effect: Double)

    fun summary(book: Book, day: LocalDate): Summary {
        val t = book.entries.filter { it.day == day && it.applied }
        return Summary(t.size, t.count { it.change.scope == Scope.PAPER }, t.count { it.change.scope == Scope.LIVE }, t.count { it.change.scope == Scope.SHADOW },
            book.entries.count { it.status == Status.PROBATION }, book.entries.count { it.status == Status.KEPT },
            book.entries.count { it.status == Status.REVERTED }, book.entries.count { it.status == Status.OFFERED && it.day == day },
            book.entries.filter { it.applied || it.status == Status.REVERTED }.sumOf { it.effect })
    }

    /** The day's notification: "Autopilot made 3 changes (2 paper, 1 live risk-lowering). Tap to review."; null: none today. */
    fun notice(s: Summary): String? {
        if (s.today == 0) return null
        val parts = listOfNotNull(if (s.todayPaper > 0) "${s.todayPaper} paper" else null, if (s.todayShadow > 0) "${s.todayShadow} shadow" else null,
            if (s.todayLive > 0) "${s.todayLive} live risk-lowering" else null)
        return "Autopilot made ${s.today} change${if (s.today == 1) "" else "s"} (${parts.joinToString(", ")}). Tap to review."
    }

    /** "What did you change today?" */
    fun sayToday(book: Book, day: LocalDate): String {
        val t = book.entries.filter { it.day == day && (it.applied || it.status == Status.OFFERED) }
        val reverted = book.entries.filter { it.closedOn == day && it.status == Status.REVERTED }
        if (t.isEmpty() && reverted.isEmpty()) return "I changed nothing by myself today, Boss."
        val lines = t.map { "• " + it.change.what + ": " + it.change.from + " → " + it.change.to + " (" + it.change.scope.words + ", " +
            (if (it.status == Status.OFFERED) "asked you" else riskOf(it.change).words) + ") - " + it.change.reason } +
            reverted.map { "• Reverted ${it.change.what}: ${it.note}" }
        return "Today, Boss:\n" + lines.joinToString("\n") + "\nEach is on probation and undoable in Home → Automation card → Autopilot → Change log."
    }

    /** "Why did you mute X?": the newest change whose target or name holds [subject]. */
    fun sayWhy(book: Book, subject: String?): String {
        val s = subject?.lowercase(Locale.ENGLISH)?.trim()?.removePrefix("the ")?.removeSuffix(" specialist")?.trim().orEmpty()
        val e = book.entries.lastOrNull { e -> e.status != Status.DECLINED && (s.isEmpty() || s in e.change.what.lowercase(Locale.ENGLISH) || s in e.change.target.lowercase(Locale.ENGLISH)) }
            ?: return if (s.isEmpty()) "I have not changed anything by myself yet, Boss." else "I have not changed anything about $subject by myself, Boss."
        return "${e.change.what}: ${e.change.from} → ${e.change.to} on ${AgentLessons.day(e.day)}, because ${e.change.reason}. " +
            "It is ${e.status.words}" + (if (e.note.isNotEmpty() && e.status != Status.PROBATION) " (${e.note})" else "") + "."
    }

    /** "How is autopilot doing?": kept against reverted, and the rupees against the counterfactual. */
    fun sayHow(book: Book, mode: Mode): String {
        if (book.entries.none { it.by == "autopilot" || it.applied }) return "Autopilot learning is ${mode.words} and has made no change yet, Boss."
        val kept = book.entries.count { it.status == Status.KEPT }
        val rev = book.entries.count { it.status == Status.REVERTED }
        val prob = book.entries.count { it.status == Status.PROBATION }
        val undone = book.entries.count { it.status == Status.UNDONE }
        val eff = book.entries.filter { it.applied || it.status == Status.REVERTED }.sumOf { it.effect }
        return "Autopilot learning is ${mode.words}: $kept kept, $rev reverted by its own check, $prob on probation" +
            (if (undone > 0) ", $undone undone by you" else "") +
            ". Against what would have happened without them: ${AgentLessons.rs(eff)} (shadow changes carry no rupees)." +
            (if (book.blacklist.isNotEmpty()) " ${book.blacklist.size} target${if (book.blacklist.size == 1) " is" else "s are"} left alone after two reverts." else "")
    }

    // ---- what Boss asks ------------------------------------------------------------------------------------------------------

    /**
     * SURE: "how sure are you about X?" ([subject]: X); EXPERIMENTS: "what experiments are running?"; SHADOW: "set autopilot to
     * shadow only" - a lowering from SAFE, a raise from OFF ([voiceSet] decides; review R5 #10).
     */
    enum class AskKind { TODAY, WHY, UNDO, OFF, RAISE, HOW, SURE, EXPERIMENTS, SHADOW }

    data class Ask(val kind: AskKind, val subject: String? = null)

    private const val LEAD = "^ (hey |ok |okay )?(jarvis )?(so )?(please )?(tell me |can you tell me |can you )?"
    private const val TAIL = "( please| boss| jarvis)* $"
    private const val AP = "(the )?(autopilot|auto pilot)( learning)?"
    private val TODAY = Regex(LEAD + "(what (did|have) you changed? (by yourself |yourself )?today|what did you change by yourself( today)?|" +
        "what changes did you make( today)?|what did $AP change( today)?|what has $AP changed( today)?|what (did|have) you changed? on your own( today)?|" +
        "aaj (tumne |aapne )?kya badla)" + TAIL)
    /** Its one capturing group is what the change was about. */
    private val WHY = Regex("^ (?:hey |ok |okay )?(?:jarvis )?(?:so )?(?:please )?(?:tell me |can you tell me )?why did (?:you|(?:the )?(?:autopilot|auto pilot)(?: learning)?) " +
        "(?:mute|pause|park|demote|unmute|un mute|resume) (.+?)(?: please| boss| jarvis)* $")
    // (A bare "undo the last change" is the settings' own undo command, as before: never taken here.)
    private val UNDO = Regex(LEAD + "(undo your last change|undo (the|your) last (autopilot|auto pilot) change|undo the last thing (you|autopilot) changed|" +
        "revert (the|your) last (autopilot|auto pilot) change|undo what $AP (did|changed)|undo $AP s last change|undo $AP last change)" + TAIL)
    private val OFF = Regex(LEAD + "((turn|switch) off $AP learning|(stop|disable) $AP learning|$AP learning off|(turn|switch) $AP learning off)" + TAIL)
    private val RAISE = Regex(LEAD + "((turn|switch) on $AP learning|(start|enable) $AP learning|$AP learning on|(turn|switch) $AP learning on|" +
        "set $AP to safe|(put|set) $AP learning (back )?(to|on) safe)" + TAIL)
    private val SHADOW = Regex(LEAD + "(set $AP to (shadow only|shadow)|(put|set) $AP learning (back )?(to|on) (shadow only|shadow))" + TAIL)
    private val HOW = Regex(LEAD + "(how is $AP doing|how s $AP doing|hows $AP doing|how is $AP|is $AP (helping|working)|$AP report|" +
        "how is (your|the) autopilot learning (doing|going)|how are your (own )?changes doing)" + TAIL)

    /** Its one capturing group is what Boss asks about ("how sure are you about the OI specialist on Pine?"). */
    private val SURE = Regex("^ (?:hey |ok |okay )?(?:jarvis )?(?:so )?(?:please )?(?:tell me )?(?:how (?:sure|confident|certain) (?:are you|is (?:the )?(?:autopilot|auto pilot)|r u)" +
        "|how much do you trust (?:your lesson |the lesson )?) (?:about|of|on|in) (.+?)(?: please| boss| jarvis)* $")
    private val EXPERIMENTS = Regex(LEAD + "((what|which) (paper )?experiments? (are|is) (running|going on|on)( now| right now)?|(any|are there any) (paper )?experiments( running)?|" +
        "(show|list)( me)? (the |your |autopilots )?(paper )?experiments|(what|which) experiments (are you|is autopilot|is the autopilot) running|" +
        "what are you testing on paper|what is autopilot testing|your experiments|autopilot experiments)" + TAIL)

    /** Words "how sure are you about X?" may name: one of Jarvis's lessons or one of Autopilot's own targets (never the market). */
    private val SURE_SUBJECT = Regex(" (specialists?|manager|autopilot|experiments?|lessons?|mute|muting|pause|pausing|park|parking|threshold|weight|" +
        "night|pine|solo|hero|orb|liquidity|vix divergence|mcx|order flow|trap guard|traps?|mornings?|middays?|afternoons?|busy days|quiet days|change|changes) ")

    private val askedKept = Kept<Ask?>(64)

    /** What Boss asked of Autopilot, or null. */
    fun asked(text: String): Ask? = askedKept.of(text) { askedFresh(text) }

    private fun askedFresh(text: String): Ask? {
        val t = Spaced.joined(text)
        if (t.contains(" sure ") || t.contains(" confident ") || t.contains(" certain ") || t.contains(" trust ")) {
            SURE.find(t)?.let { m ->
                val s = m.groupValues[1].trim()
                if (s.isNotEmpty() && SURE_SUBJECT.containsMatchIn(" $s ")) return Ask(AskKind.SURE, s)
            }
        }
        if (t.contains(" experiment") || t.contains(" testing")) EXPERIMENTS.find(t)?.let { return Ask(AskKind.EXPERIMENTS) }
        if (!t.contains(" auto") && !t.contains(" change") && !t.contains(" mute") && !t.contains(" un mute") && !t.contains(" unmute") &&
            !t.contains(" pause") && !t.contains(" park") && !t.contains(" demote") && !t.contains(" badla") && !t.contains(" resume") &&
            !t.contains(" stop")) return null
        if (OFF.containsMatchIn(t)) return Ask(AskKind.OFF)
        if (RAISE.containsMatchIn(t)) return Ask(AskKind.RAISE)
        if (SHADOW.containsMatchIn(t)) return Ask(AskKind.SHADOW)
        if (UNDO.containsMatchIn(t)) return Ask(AskKind.UNDO)
        if (TODAY.containsMatchIn(t)) return Ask(AskKind.TODAY)
        if (HOW.containsMatchIn(t)) return Ask(AskKind.HOW)
        WHY.find(t)?.let { m -> return Ask(AskKind.WHY, m.groupValues[1].trim()) }
        return null
    }

    /** A voice may lower Autopilot's level, never raise it: the level a spoken [want] leaves it at from [now]. */
    fun voiceSet(now: Mode, want: Mode): Mode = if (want.level <= now.level) want else now

    /** The words for a raise asked by voice (refused). */
    const val VOICE_RAISE = "I can't turn Autopilot learning up by voice, Boss: switch it in Settings → Bot settings → Autopilot learning " +
        "(SAFE asks for your PIN when the live account is on). Turning it off by voice always works."

    // ---- the file ------------------------------------------------------------------------------------------------------------

    private fun clean(s: String) = s.replace('|', '/').replace('\n', ' ').replace('\r', ' ').take(400)

    fun encode(b: Book): String {
        val sb = StringBuilder("V|1\n")
        sb.append("R|").append(b.lastRun ?: "").append('|').append(b.lastMidday ?: "").append('\n')
        for ((k, d) in b.blacklist.toSortedMap()) sb.append("B|").append(clean(k)).append('|').append(d).append('\n')
        for ((k, d) in b.rest.toSortedMap()) sb.append("S|").append(clean(k)).append('|').append(d).append('\n')
        if (b.trials > 0) sb.append("T|").append(b.trials).append('\n')
        for (e in b.entries) {
            sb.append("E|").append(clean(e.id)).append('|').append(e.atMs).append('|').append(e.day).append('|').append(e.status.name)
                .append('|').append(clean(e.by)).append('|').append(e.closedOn ?: "").append('|').append(clean(e.note))
                .append('|').append(e.afterN).append('|').append(e.effect)
            appendChange(sb, e.change)
            sb.append('|').append(e.q ?: "").append('|').append(clean(e.test)).append('|').append(clean(e.preflight)).append('\n')
        }
        for (x in b.experiments) sb.append(Experiments.encode(x)).append('\n')
        return sb.toString()
    }

    /** A change's 17 fields, each after a '|' (the log's and the experiments' lines share them). */
    internal fun appendChange(sb: StringBuilder, c: Change) {
        sb.append('|').append(c.kind.name).append('|').append(clean(c.target)).append('|').append(clean(c.from)).append('|').append(clean(c.to))
            .append('|').append(clean(c.reason)).append('|').append(c.evidence.n).append('|').append(c.evidence.rs).append('|').append(c.evidence.confidence.name)
            .append('|').append(c.evidence.sign).append('|').append(c.risk.name).append('|').append(c.scope.name).append('|').append(clean(c.lessonId.orEmpty()))
            .append('|').append(c.expect).append('|').append(c.effect).append('|').append(c.lo ?: "").append('|').append(c.hi ?: "").append('|').append(clean(c.what))
    }

    /** The change whose 17 fields start at [f]'s index [i] (throws on garbage: the caller skips the line). */
    internal fun changeAt(f: List<String>, i: Int): Change = Change(Kind.valueOf(f[i]), f[i + 1], f[i + 2], f[i + 3], f[i + 4],
        Evidence(f[i + 5].toInt(), f[i + 6].toDouble(), AgentLessons.Confidence.valueOf(f[i + 7]), f[i + 8].toInt()),
        Risk.valueOf(f[i + 9]), Scope.valueOf(f[i + 10]), f[i + 11].ifEmpty { null }, f[i + 12].toInt(), f[i + 13].toInt(),
        f[i + 14].toDoubleOrNull(), f[i + 15].toDoubleOrNull(), f[i + 16])

    fun decode(text: String?): Book {
        if (text.isNullOrBlank()) return Book()
        val entries = ArrayList<Entry>()
        val black = HashMap<String, LocalDate>(); val rest = HashMap<String, LocalDate>()
        var last: LocalDate? = null; var mid: LocalDate? = null
        var trials = 0
        val exps = ArrayList<Experiments.Experiment>()
        fun date(s: String?): LocalDate? = s?.takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        for (raw in text.lineSequence()) {
            val f = raw.split('|')
            runCatching {
                when (f[0]) {
                    "R" -> { last = date(f.getOrNull(1)); mid = date(f.getOrNull(2)) }
                    "B" -> black[f[1]] = LocalDate.parse(f[2])
                    "S" -> rest[f[1]] = LocalDate.parse(f[2])
                    "T" -> trials = f[1].toInt().coerceAtLeast(0)
                    "X" -> Experiments.decode(f)?.let { exps += it }
                    "E" -> if (f.size >= 27) {
                        val c = changeAt(f, 10)
                        entries += Entry(f[1], f[2].toLong(), LocalDate.parse(f[3]), c, Status.valueOf(f[4]), f[5], date(f[6]), f[7], f[8].toInt(), f[9].toDouble(),
                            q = f.getOrNull(27)?.toDoubleOrNull()?.takeIf { it in 0.0..1.0 }, test = f.getOrNull(28).orEmpty(), preflight = f.getOrNull(29).orEmpty())
                    }
                }
            }
        }
        return Book(entries.distinctBy { it.id }, black, rest, last, mid, trials, exps.distinctBy { it.id })
    }
}
