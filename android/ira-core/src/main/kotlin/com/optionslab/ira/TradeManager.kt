package com.optionslab.ira

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The trade manager (Boss, 10 Oct: "make Solo and Pine smart trade managers"): once a strategy's order has filled, the
 * manager keeps watching the position with every live reading - the order flow and its trap flags, executed volume, VWAP and
 * its bands, the value area, delta and divergence, absorption, OI build-up, VIX, the market brain's windows and the findings
 * bus - and may do two things only:
 *
 *  - EXIT EARLY when the picture turns against the trade ([Rule]s, each one pre-registered below with its own persistence):
 *    that only ever lowers risk, and goes through the strategy's own exit path;
 *  - EXTEND THE TARGET when there is more room - but ONLY together with the ratcheting lock: on every extension the lock
 *    (the stop) rises to at least breakeven-plus-charges, half the open profit and most of the old target's profit
 *    ([levels]); between extensions it trails new option highs at half the open profit ([trail]); it never comes down.
 *    So an extension never puts more money at risk than the original plan (the property tests check it). Never adds lots,
 *    never widens a stop, never moves a strategy's square-off time.
 *
 * Generic: any strategy, now or later, hands over a [ManagedTrade] ([FAMILIES]). Modes per strategy and account: OFF, SHADOW
 * (what it would have done is recorded beside what the original rules did; nothing changes) or ACT. Solo and Pine act on
 * PAPER by default so their paper trades show its real effect, and are SHADOW on LIVE; every other strategy is SHADOW until
 * Boss switches it on, and LIVE to ACT always takes the PIN ([needsPin]). The record per strategy ([tally]) says whether
 * it is ready to be turned on ([READY_TRADES]) - a hint only, nothing switches by itself.
 *
 * Every decision is recorded with its evidence, and the original rules are followed on as a counterfactual on the option's
 * 1-minute wicks ([Path]) so the record compares the manager with the original rules honestly ([Record], [tally]).
 * Pure: no clock, no Android. [decide] is cheap (memory only; timed by its tests).
 */
object TradeManager {
    enum class Mode { OFF, SHADOW, ACT }
    enum class Account { PAPER, LIVE }

    // ---- the pre-registered thresholds (set before any trade was managed; changing one is a new experiment) -------------

    /** Opposite flow: the read's side against the trade at this strength or more (0-100). */
    const val OPP_STRENGTH = 60
    /** Absorption "at a level": the defended price within this share of the price (0.1%). */
    const val ABSORB_NEAR = 0.001
    /** A news window counts from this long before it starts (it is "starting"). */
    const val NEWS_LEAD_MS = 2 * 60_000L
    /** Consensus against: this many standing findings against the trade, each this strong or more, in the last 10 minutes. */
    const val CONSENSUS_MIN = 3
    const val CONSENSUS_STRENGTH = 60
    const val CONSENSUS_MS = 10 * 60_000L
    /** "Near the stop": this share of the original risk distance or less left before the stop. */
    const val NEAR_STOP = 0.35
    /** A gap between two looks longer than this starts every rule's persistence again (the app away, the stream down). */
    const val MAX_GAP_MS = 20_000L

    /** Extension: the agreeing flow's strength at least this. */
    const val EXT_FLOW = 65
    /** Extension: the price at least this many VWAP standard deviations beyond the VWAP the trade's way. */
    const val EXT_VWAP_SD = 0.5
    /** Extension: only once the premium has gone this share of the way to the current target. */
    const val EXT_NEAR = 0.9
    /** Extension: at most this many per trade. */
    const val MAX_EXTENSIONS = 2
    /** Extension: no new one within this long of the last one, or of any rule against the trade being seen. */
    const val COOLDOWN_MS = 60_000L
    /** Extension: none with less than this left before the strategy's square-off. */
    const val EXT_MIN_LEFT_MS = 15 * 60_000L
    /** Extension step: at most this share of the original target distance per step ... */
    const val EXT_CAP = 0.5
    /** ... sized by the option's recent 1-minute range times this ... */
    const val EXT_ATR_MULT = 3.0
    /** ... or this share of the original distance when the range is not known. */
    const val EXT_FALLBACK = 0.25
    /** Extension: VIX up this much (%) over the last 5 minutes blocks it. */
    const val VIX_SPIKE_PCT = 3.0

    /** The lock keeps at least this share of the open profit (on each extension, and trailing new highs). */
    const val LOCK_SHARE = 0.5
    /** On an extension the lock rises to the old target less this share of the entry-to-old-target gap. */
    const val LOCK_BUFFER = 0.25
    /** The lock stays at least this far under the price now (and at least a tick): it must be a stop, not a sale. */
    const val MIN_GAP_PCT = 0.01

    /**
     * A strategy family the manager is hooked to: its [key] (the part of a strategy id before ':'), its [name], whether it
     * only ever trades on paper, and whether its own exit path takes the manager's word in ACT ([canAct]; false: it records
     * only, whatever its mode says).
     */
    data class Family(val key: String, val name: String, val paperOnly: Boolean, val canAct: Boolean)

    /** Every hooked strategy (the record's order). Solo and Pine act on paper by default; every other one is SHADOW. */
    val FAMILIES: List<Family> = listOf(
        Family("solo", "Solo", paperOnly = true, canAct = true),
        Family("pine", "Pine", paperOnly = false, canAct = true),
        Family("orb", "ORB arms", paperOnly = false, canAct = false),
        Family("liquidity", "Liquidity 15+5", paperOnly = false, canAct = false),
        Family("hero", "Expiry Hero", paperOnly = false, canAct = false),
        Family("night", "Night (R3)", paperOnly = true, canAct = true),
        Family("vixdiv", "VIX divergence", paperOnly = true, canAct = true),
        Family("mcx-eve", "MCX evening", paperOnly = true, canAct = true),
        Family("mcx-morning", "MCX morning", paperOnly = true, canAct = true),
        Family("mcx-trend", "MCX trend", paperOnly = true, canAct = true),
        Family("silver-night", "US-night silver", paperOnly = true, canAct = true),
    )

    /** The families that act on paper by default (their effect shows on paper): Solo and Pine. Every other one is SHADOW. */
    val ACT_ON_PAPER: Set<String> = setOf("solo", "pine")

    fun familyOf(key: String): Family? = FAMILIES.firstOrNull { it.key == key }

    /** The plain name of a strategy family. */
    fun familyName(family: String): String = familyOf(family)?.name ?: family.replaceFirstChar { it.uppercase(Locale.ENGLISH) }

    // ---- modes ----------------------------------------------------------------------------------------------------------

    /**
     * The default: Solo and Pine act on PAPER (their real effect shows there) and are SHADOW on LIVE until proven; every other
     * strategy is SHADOW (records only; its own exits stay authoritative) - ACT only when Boss switches it on.
     */
    fun defaultMode(family: String, account: Account): Mode =
        if (family in ACT_ON_PAPER && account == Account.PAPER) Mode.ACT else Mode.SHADOW

    fun key(family: String, account: Account): String = "$family.${account.name.lowercase(Locale.ENGLISH)}"

    /**
     * Boss's choices per strategy family and account ("pine.live" -> ACT); the rest at their defaults. ACT on a family
     * whose exits do not take the manager's word ([Family.canAct] false) reads as SHADOW: it never acts there.
     */
    data class Policy(val modes: Map<String, Mode> = emptyMap()) {
        fun mode(family: String, account: Account): Mode {
            val m = modes[key(family, account)] ?: defaultMode(family, account)
            return if (m == Mode.ACT && familyOf(family)?.canAct != true) Mode.SHADOW else m
        }
        fun with(family: String, account: Account, m: Mode): Policy {
            val k = key(family, account)
            return Policy(if (m == defaultMode(family, account)) modes - k else modes + (k to m))
        }
    }

    /** "pine.live=ACT;solo.paper=SHADOW" (bad parts left out). */
    fun encode(p: Policy): String = p.modes.toSortedMap().entries.joinToString(";") { "${it.key}=${it.value.name}" }

    fun decode(s: String?): Policy {
        if (s.isNullOrBlank()) return Policy()
        val m = HashMap<String, Mode>()
        for (part in s.split(';')) {
            val k = part.substringBefore('=').trim()
            if (!Regex("^[a-z0-9_-]+\\.(paper|live)$").matches(k)) continue
            m[k] = runCatching { Mode.valueOf(part.substringAfter('=').trim()) }.getOrNull() ?: continue
        }
        return Policy(m)
    }

    /** Turning a LIVE account to ACT takes the PIN or fingerprint. */
    fun needsPin(to: Mode, account: Account): Boolean = to == Mode.ACT && account == Account.LIVE

    const val UNPROVEN = "Unproven: the trade manager may cut winners early."

    // ---- the trade ------------------------------------------------------------------------------------------------------

    /** The strategy's limits: its last exit time (Solo 14:30, Pine 15:15, F&O close, expiry), the extensions, the tick. */
    data class Caps(val squareOffMs: Long, val maxExtensions: Int = MAX_EXTENSIONS, val tick: Double = 0.05)

    /**
     * One open trade, as any strategy hands it over. Always a BOUGHT option (premium long): [side] is the direction it bets
     * on the underlying (+1 a call, −1 a put). [originalStop] / [originalTarget]: premium levels (null: none on the premium -
     * Solo's stop is on the index, [stopUnderlying] with [entryUnderlying]; Solo has no target, so it is never extended).
     * [chargesPerUnit]: the round trip's charges per unit (breakeven-plus-charges).
     */
    data class ManagedTrade(
        val strategyId: String, val tradeId: String, val label: String, val underlying: String, val side: Int,
        val instrument: String, val entry: Double, val qty: Int,
        val originalStop: Double?, val originalTarget: Double?,
        val caps: Caps, val account: Account, val entryMs: Long,
        val chargesPerUnit: Double = 0.0,
        val stopUnderlying: Double? = null, val entryUnderlying: Double? = null,
    ) {
        val family: String get() = strategyId.substringBefore(':')
    }

    /** Everything read at one look (memory only). Null parts are not known and decide nothing. */
    data class Snapshot(
        val atMs: Long,
        /** The held option's price now. */
        val premium: Double?,
        /** The option's best 1-minute wick high since the entry (the trail's high-water mark). */
        val premiumHigh: Double? = null,
        /** The option's average 1-minute range lately (the extension's size). */
        val premiumAtr: Double? = null,
        /** The underlying's price (an index stop's distance). */
        val underlyingPrice: Double? = null,
        /** The underlying future's order-flow read, and its price. */
        val flow: OrderFlow.Read? = null,
        val futPrice: Double? = null,
        val vwap: Auction.Vwap? = null,
        /** Today's value area (the future's). */
        val valueHigh: Double? = null, val valueLow: Double? = null,
        val divergence: Auction.Divergence? = null,
        /** VIX's change over the last 5 minutes, %. */
        val vixChangePct: Double? = null,
        val brain: MarketBrain.Context? = null,
        val findings: List<Findings.Finding> = emptyList(),
        // ---- the specialists' extra readings ([ManagerTeam]; each optional: null or empty decides nothing) ----
        /** Today's point of control and high- / low-volume nodes (the future's profile). */
        val poc: Double? = null, val hvns: List<Double> = emptyList(), val lvns: List<Double> = emptyList(),
        /** The future's volume per minute lately (minute start, epoch s, to volume), oldest first. */
        val minuteVolumes: List<Pair<Long, Long>> = emptyList(),
        /** The held option's implied volatility change over the last 5 minutes, % (null: not known). */
        val ivChangePct: Double? = null,
        /** The gamma regime under Boss's convention: the net GEX (+ choppy, − trending) and the zero-gamma level. */
        val gammaNet: Double? = null, val zeroGamma: Double? = null,
        /** How old the held option's price is, ms (null: not known). */
        val premiumAgeMs: Long? = null,
    )

    // ---- the rules ------------------------------------------------------------------------------------------------------

    enum class Rule(val key: String, val persistMs: Long, val words: String) {
        OPPOSITE_FLOW("flow", 60_000L, "strong opposite flow confirmed by executed volume for 60 s, with the price back through VWAP or the value area"),
        DIVERGENCE("divergence", 60_000L, "delta divergence against the trade for 60 s, the flow not behind it"),
        ABSORPTION("absorption", 30_000L, "absorption against the trade at a level for 30 s"),
        TRAP("trap", 15_000L, "a stop hunt or failed break against the trade for 15 s"),
        NEWS("news", 0L, "a news window starting within 2 minutes"),
        CONSENSUS("consensus", 30_000L, "3 or more strong findings against the trade for 30 s"),
        DATA("data", 10_000L, "data unreliable for 10 s with the price near the stop"),
        LOCK("lock", 0L, "the manager's profit lock was hit"),
        /** The specialists' weighted quorum ([ManagerTeam]) led by a specialist with no rule of its own above. */
        TEAM("team", 0L, "the specialists' weighted quorum agreed"),
        EXTEND("extend", 30_000L, "strong agreeing flow, price accepted beyond VWAP and value, rising agreeing OI, nothing against, for 30 s"),
    }

    /** The rules that exit early, in the order they are asked. */
    val EXIT_RULES: List<Rule> = listOf(Rule.DATA, Rule.NEWS, Rule.TRAP, Rule.OPPOSITE_FLOW, Rule.ABSORPTION, Rule.DIVERGENCE, Rule.CONSENSUS)

    /** A rule's reason in a few words, for the trade's side ("sellers took over"). */
    fun short(r: Rule, side: Int): String {
        val them = if (side > 0) "sellers" else "buyers"
        return when (r) {
            Rule.OPPOSITE_FLOW -> "$them took over"
            Rule.DIVERGENCE -> "delta divergence against it"
            Rule.ABSORPTION -> "$them absorbing at a level"
            Rule.TRAP -> if (side > 0) "failed break up" else "failed break down"
            Rule.NEWS -> "news window starting"
            Rule.CONSENSUS -> "the bots turned against it"
            Rule.DATA -> "data unreliable near the stop"
            Rule.LOCK -> "profit lock hit"
            Rule.TEAM -> "the specialists agreed"
            Rule.EXTEND -> "more room"
        }
    }

    // ---- state and decisions --------------------------------------------------------------------------------------------

    /**
     * The manager's memory of one trade: when each rule was first seen (continuously), the extensions, the current target
     * and ratcheting [lock] (null: none yet - the original rules' stop holds), the best premium seen.
     */
    data class State(
        val since: Map<Rule, Long> = emptyMap(),
        val lastEvalMs: Long = 0L,
        val extensions: Int = 0,
        val lastExtendMs: Long = 0L,
        val lastAgainstMs: Long = 0L,
        val target: Double? = null,
        val lock: Double? = null,
        val peak: Double = 0.0,
        val exited: Boolean = false,
    )

    fun start(t: ManagedTrade): State = State(target = t.originalTarget, peak = t.entry)

    sealed class Decision {
        object Hold : Decision()
        data class ExitEarly(val rule: Rule, val reason: String, val evidence: Map<String, Double>) : Decision()
        data class Extend(val newTarget: Double, val newStop: Double, val reason: String, val evidence: Map<String, Double>) : Decision()
        /** The lock trailed a new high (between extensions). */
        data class Trail(val newStop: Double) : Decision()
    }

    data class Step(val decision: Decision, val state: State)

    private fun ceilTick(x: Double, tick: Double): Double = if (tick <= 0) x else Math.round(ceil(x / tick - 1e-9) * tick * 1e6) / 1e6
    private fun floorTick(x: Double, tick: Double): Double = if (tick <= 0) x else Math.round(floor(x / tick + 1e-9) * tick * 1e6) / 1e6

    /** How far under the price now a lock must stay. */
    fun gap(px: Double, tick: Double): Double = maxOf(tick, MIN_GAP_PCT * abs(px))

    /** The share of the original risk distance left before the stop (1 at the entry, 0 at the stop); null: not known. */
    fun riskLeft(t: ManagedTrade, s: Snapshot): Double? {
        val px = s.premium
        val st = t.originalStop
        if (px != null && st != null && t.entry - st > 1e-9) return (px - st) / (t.entry - st)
        val su = t.stopUnderlying; val eu = t.entryUnderlying; val u = s.underlyingPrice
        if (su != null && eu != null && u != null && abs(eu - su) > 1e-9) return (u - su) / (eu - su)
        return null
    }

    private fun freshFlow(s: Snapshot): OrderFlow.Read? =
        s.flow?.takeIf { it.warm && s.atMs / 1000 - it.atSec <= OrderFlow.STALE_SEC }

    /** The data is unreliable at this look: the flow's flag, the brain's trap flags, or self-healing's finding on the underlying. */
    fun unreliable(t: ManagedTrade, s: Snapshot): Boolean = s.flow?.flags?.contains(TrapGuard.Trap.UNRELIABLE) == true ||
        s.brain?.traps?.get(t.underlying.uppercase(Locale.ENGLISH))?.contains("UNRELIABLE") == true ||
        s.findings.any { it.kind == Findings.Kind.DATA_UNRELIABLE && it.alive(s.atMs) && it.on(t.underlying) }

    /** The exit rules whose conditions hold at this look, each with its words and numbers (no persistence here). */
    fun against(t: ManagedTrade, s: Snapshot): Map<Rule, Pair<String, Map<String, Double>>> {
        val out = LinkedHashMap<Rule, Pair<String, Map<String, Double>>>()
        val side = if (t.side >= 0) 1 else -1
        val now = s.atMs
        val r = freshFlow(s)
        val fut = s.futPrice ?: r?.mid
        val flags = r?.flags.orEmpty()
        // Opposite flow, executed, with the price back through VWAP or out of value against the trade.
        if (r != null && fut != null && r.side == (if (side > 0) OrderFlow.Side.SELLERS else OrderFlow.Side.BUYERS) &&
            r.strength >= OPP_STRENGTH && r.cvd60 * side < 0 && TrapGuard.Trap.NO_EXECUTED !in flags) {
            val vw = s.vwap
            val vwapAgainst = vw != null && (fut - vw.vwap) * side < 0
            val vaAgainst = if (side > 0) s.valueLow?.let { fut < it } == true else s.valueHigh?.let { fut > it } == true
            if (vwapAgainst || vaAgainst) out[Rule.OPPOSITE_FLOW] = "${if (side > 0) "sellers" else "buyers"} ${r.strength} with executed volume " +
                "${r.cvd60.toLong()} over 60 s, price ${num(fut)} " + (if (vwapAgainst) "through VWAP ${num(vw!!.vwap)}" else "outside value") to
                mapOf("strength" to r.strength.toDouble(), "cvd60" to r.cvd60, "price" to fut) + (vw?.let { mapOf("vwap" to it.vwap) } ?: emptyMap())
        }
        // Delta divergence against, the flow not behind the trade.
        val divAgainst = if (side > 0) Auction.Divergence.BEARISH else Auction.Divergence.BULLISH
        if (s.divergence == divAgainst && (r == null || r.side != (if (side > 0) OrderFlow.Side.BUYERS else OrderFlow.Side.SELLERS)))
            out[Rule.DIVERGENCE] = divAgainst.words to mapOf("divergence" to -side.toDouble())
        // Absorption against at a level near the price.
        if (r != null && fut != null && r.absorbBy == -side && !r.absorbAt.isNaN() && abs(fut - r.absorbAt) <= ABSORB_NEAR * fut)
            out[Rule.ABSORPTION] = "${if (side > 0) "sellers" else "buyers"} absorbing at ${num(r.absorbAt)}" to mapOf("absorbAt" to r.absorbAt, "price" to fut)
        // A stop hunt or failed break the trade's way (the move that came back was its own).
        if (r != null && r.huntDir == side && (TrapGuard.Trap.STOP_HUNT in flags || TrapGuard.Trap.FAILED_BREAK in flags))
            out[Rule.TRAP] = (if (TrapGuard.Trap.FAILED_BREAK in flags) "failed break" else "stop hunt") + " " + (if (side > 0) "up" else "down") to
                mapOf("huntDir" to side.toDouble())
        // A news window starting (or on) for the underlying.
        s.brain?.windows?.firstOrNull { it.kind == MarketBrain.Kind.NEWS && it.concerns(t.underlying) && now >= it.startMs - NEWS_LEAD_MS && now <= it.endMs }
            ?.let { out[Rule.NEWS] = "${it.name} window" to mapOf("startsInSec" to ((it.startMs - now) / 1000).toDouble()) }
        // Strong findings against it (not its own, not the manager's).
        val opp = s.findings.filter {
            it.alive(now) && now - it.atMs <= CONSENSUS_MS && it.direction == -side && it.strength >= CONSENSUS_STRENGTH &&
                it.instrument.equals(t.underlying, ignoreCase = true) && !it.who.startsWith(familyName(t.family)) && !it.who.startsWith(WHO)
        }
        if (opp.size >= CONSENSUS_MIN) out[Rule.CONSENSUS] = "${opp.size} strong ${if (side > 0) "bearish" else "bullish"} findings (" +
            opp.take(3).joinToString(", ") { it.who } + ")" to mapOf("against" to opp.size.toDouble())
        // Unreliable data near the stop.
        val unreliable = unreliable(t, s)
        val left = riskLeft(t, s)
        if (unreliable && left != null && left <= NEAR_STOP)
            out[Rule.DATA] = "data unreliable with ${pct(left)} of the risk left before the stop" to mapOf("riskLeft" to left)
        return out
    }

    /**
     * The extension's conditions one by one at this look (the specialists each read their own part, [ManagerTeam]); [scope]
     * needs every one. [target]: a target to move with extensions left; [time]: far enough from the square-off; [near]: the
     * premium most of the way to the target; [flow]: fresh agreeing flow, strong, executed, with no flag against it; [traps]:
     * no pull against it and no stop hunt or failed break its way; [vwap]: accepted beyond VWAP; [value]: beyond the value
     * area; [oi]: agreeing build-up with OI rising; [vix]: VIX not spiking.
     */
    data class Room(val target: Boolean, val time: Boolean, val near: Boolean, val flow: Boolean, val traps: Boolean, val vwap: Boolean,
                    val value: Boolean, val oi: Boolean, val vix: Boolean) {
        val all: Boolean get() = target && time && near && flow && traps && vwap && value && oi && vix
    }

    /** The flags that spoil an agreeing flow for an extension (the pulls are the trap guard's part, [Room.traps]). */
    private val BAD_FLOW_LONG = setOf(TrapGuard.Trap.NO_EXECUTED, TrapGuard.Trap.PRICE_AGAINST, TrapGuard.Trap.CONTRADICTION,
        TrapGuard.Trap.UNRELIABLE, TrapGuard.Trap.BOOK_OFF, TrapGuard.Trap.ABSORPTION_BUY)
    private val BAD_FLOW_SHORT = setOf(TrapGuard.Trap.NO_EXECUTED, TrapGuard.Trap.PRICE_AGAINST, TrapGuard.Trap.CONTRADICTION,
        TrapGuard.Trap.UNRELIABLE, TrapGuard.Trap.BOOK_OFF, TrapGuard.Trap.ABSORPTION_SELL)

    /** [Room] at this look (no persistence or cooldown here). */
    fun room(t: ManagedTrade, s: Snapshot, st: State): Room {
        val side = if (t.side >= 0) 1 else -1
        val target = st.target
        val hasTarget = target != null && t.originalTarget != null && st.extensions < minOf(t.caps.maxExtensions, MAX_EXTENSIONS)
        val time = s.atMs <= t.caps.squareOffMs - EXT_MIN_LEFT_MS
        val px = s.premium
        val near = px != null && target != null && target - t.entry > 0 && (px - t.entry) / (target - t.entry) >= EXT_NEAR
        val r = freshFlow(s)
        val bad = if (side > 0) BAD_FLOW_LONG else BAD_FLOW_SHORT
        val flow = r != null && r.side == (if (side > 0) OrderFlow.Side.BUYERS else OrderFlow.Side.SELLERS) && r.strength >= EXT_FLOW &&
            r.cvd60 * side > 0 && r.flags.none { it in bad }
        val pull = if (side > 0) TrapGuard.Trap.PULL_BID else TrapGuard.Trap.PULL_ASK
        val traps = r != null && r.huntDir != side && pull !in r.flags
        val fut = s.futPrice ?: r?.mid
        val vw = s.vwap
        val vwap = fut != null && vw != null && vw.sdUnits(fut).let { sdU -> if (sdU == null) (fut - vw.vwap) * side > 0 else sdU * side >= EXT_VWAP_SD }
        val value = fut != null && (if (side > 0) s.valueHigh?.let { fut > it } == true else s.valueLow?.let { fut < it } == true)
        val agreeing = if (side > 0) OrderFlow.BuildUp.LONG_BUILDUP else OrderFlow.BuildUp.SHORT_BUILDUP
        val oi = r != null && r.buildUp == agreeing && (r.oiChange ?: 0L) > 0L
        val vix = (s.vixChangePct ?: 0.0) < VIX_SPIKE_PCT
        return Room(hasTarget, time, near, flow, traps, vwap, value, oi, vix)
    }

    /** The fresh, warm order-flow read at this look (null: none; a stale or cold read decides nothing). */
    fun flowNow(s: Snapshot): OrderFlow.Read? = freshFlow(s)

    /** Whether there is more room now (no persistence or cooldown here): its words and numbers, else null. Every [Room] part. */
    fun scope(t: ManagedTrade, s: Snapshot, st: State): Pair<String, Map<String, Double>>? {
        if (!room(t, s, st).all) return null
        return scopeWords(t, s)
    }

    /** The extension's words and numbers (asked only once every [Room] part holds). */
    fun scopeWords(t: ManagedTrade, s: Snapshot): Pair<String, Map<String, Double>>? {
        val side = if (t.side >= 0) 1 else -1
        val r = freshFlow(s) ?: return null
        val fut = s.futPrice ?: r.mid
        val vw = s.vwap ?: return null
        val agreeing = if (side > 0) OrderFlow.BuildUp.LONG_BUILDUP else OrderFlow.BuildUp.SHORT_BUILDUP
        return "${if (side > 0) "buyers" else "sellers"} ${r.strength} with executed volume ${r.cvd60.toLong()}, price ${num(fut)} beyond VWAP " +
            "${num(vw.vwap)} and value, ${agreeing.words} (OI +${r.oiChange})" to
            mapOf("strength" to r.strength.toDouble(), "cvd60" to r.cvd60, "price" to fut, "vwap" to vw.vwap, "oiChange" to (r.oiChange ?: 0L).toDouble())
    }

    /**
     * An extension's new target and lock from [st] at premium [px]: the target moves out by the option's recent 1-minute range
     * x [EXT_ATR_MULT] (else [EXT_FALLBACK] of the original distance), at most [EXT_CAP] of the original distance; the lock
     * rises to the highest of: where it is, the original stop, breakeven plus charges, entry + half the open profit, and the
     * old target less [LOCK_BUFFER] of its gap - kept under the price by [gap]. Null when no such lock fits under the price
     * (then no extension: never one without the lock at breakeven-plus-charges or better).
     */
    fun levels(t: ManagedTrade, st: State, px: Double, premiumAtr: Double?): Pair<Double, Double>? {
        val target = st.target ?: return null
        val orig = t.originalTarget ?: return null
        val tick = t.caps.tick
        val dist = orig - t.entry
        if (dist <= 0 || px <= t.entry) return null
        val raw = premiumAtr?.takeIf { it > 0 && it.isFinite() }?.let { EXT_ATR_MULT * it } ?: (EXT_FALLBACK * dist)
        val step = minOf(EXT_CAP * dist, raw)
        val newTarget = floorTick(target + step, tick)
        if (newTarget <= target + 1e-9) return null
        val be = t.entry + t.chargesPerUnit
        val keep = maxOf(st.lock ?: Double.NEGATIVE_INFINITY, t.originalStop ?: Double.NEGATIVE_INFINITY, be, t.entry + LOCK_SHARE * (px - t.entry))
        val room = floorTick(px - gap(px, tick), tick)
        val wanted = ceilTick(maxOf(keep, target - LOCK_BUFFER * (target - t.entry)), tick)
        val lock = if (wanted <= room) wanted else maxOf(ceilTick(keep, tick), room)
        if (lock > room + 1e-9 || lock < be - 1e-9 || lock < (st.lock ?: Double.NEGATIVE_INFINITY) - 1e-9) return null
        return newTarget to lock
    }

    /** The trail between extensions: entry + half the open profit at the best price [peak] (rounded up to the tick). */
    fun trail(t: ManagedTrade, peak: Double): Double = ceilTick(t.entry + LOCK_SHARE * (peak - t.entry), t.caps.tick)

    /**
     * One look at [t] with [s]: HOLD, EXIT_EARLY (a rule held for its persistence, or the lock hit), EXTEND (the target out
     * with the lock up) or TRAIL (the lock up to a new high's half). Hysteresis: each rule must hold for its own time; an
     * extension waits [COOLDOWN_MS] after the last one and after any rule against was seen; at most [MAX_EXTENSIONS]. The
     * lock only ever rises. After an exit it only holds.
     */
    fun decide(t: ManagedTrade, s: Snapshot, st0: State): Step {
        if (st0.exited) return Step(Decision.Hold, st0)
        val now = s.atMs
        val since0 = if (st0.lastEvalMs > 0 && now - st0.lastEvalMs > MAX_GAP_MS) emptyMap() else st0.since
        val px = s.premium?.takeIf { it != 0.0 && it.isFinite() }
        val peak = listOfNotNull(st0.peak, px, s.premiumHigh?.takeIf { it.isFinite() }).max()
        var st = st0.copy(lastEvalMs = now, peak = peak)
        // The trail: once a lock stands (after the first extension), it follows new highs at half the open profit. Up only.
        var trailed: Double? = null
        st.lock?.let { lk -> val tr = trail(t, peak); if (tr > lk + 1e-9) { st = st.copy(lock = tr); trailed = tr } }
        // The lock hit: out at once, a normal profit-lock exit.
        val lk = st.lock
        if (px != null && lk != null && px <= lk + 1e-9)
            return Step(Decision.ExitEarly(Rule.LOCK, "profit lock ${num(lk)} hit at ${num(px)}", mapOf("lock" to lk, "premium" to px)),
                st.copy(exited = true, since = since0))
        val hits = against(t, s)
        val since = HashMap<Rule, Long>()
        for (r in hits.keys) since[r] = since0[r] ?: now
        if (hits.isNotEmpty()) st = st.copy(lastAgainstMs = now)
        val due = EXIT_RULES.firstOrNull { r -> hits[r] != null && now - since.getValue(r) >= r.persistMs }
        if (due != null) {
            val h = hits.getValue(due)
            return Step(Decision.ExitEarly(due, h.first, h.second + mapOf("premium" to (px ?: Double.NaN), "heldSec" to ((now - since.getValue(due)) / 1000).toDouble())),
                st.copy(since = since, exited = true))
        }
        val room = scope(t, s, st)
        if (room != null && px != null) {
            since[Rule.EXTEND] = since0[Rule.EXTEND] ?: now
            val cool = (st.lastExtendMs <= 0L || now - st.lastExtendMs >= COOLDOWN_MS) && (st.lastAgainstMs <= 0L || now - st.lastAgainstMs >= COOLDOWN_MS)
            if (cool && now - since.getValue(Rule.EXTEND) >= Rule.EXTEND.persistMs) {
                val lv = levels(t, st, px, s.premiumAtr)
                if (lv != null) {
                    val (nt, ns) = lv
                    since.remove(Rule.EXTEND)
                    return Step(Decision.Extend(nt, ns, room.first, room.second + mapOf("premium" to px, "oldTarget" to (st.target ?: 0.0))),
                        st.copy(since = since, target = nt, lock = ns, extensions = st.extensions + 1, lastExtendMs = now))
                }
            }
        }
        st = st.copy(since = since)
        return Step(trailed?.let { Decision.Trail(it) } ?: Decision.Hold, st)
    }

    /** The money at risk with stop [stop] (null: the whole premium), charges included; never below 0. */
    fun moneyAtRisk(t: ManagedTrade, stop: Double?): Double = maxOf(0.0, (t.entry - (stop ?: 0.0) + t.chargesPerUnit) * t.qty)

    // ---- the counterfactual ---------------------------------------------------------------------------------------------

    /** One minute of the option: its start (epoch ms) and its prices. */
    data class Bar(val startMs: Long, val open: Double, val high: Double, val low: Double, val close: Double)

    /** Where a way of managing ended: when, at what price, and why ("STOP", "TARGET", "LOCK", "TIME", "EARLY: ..." or the strategy's words). */
    data class Exit(val atMs: Long, val price: Double, val why: String)

    /**
     * A way of managing the trade followed on the option's 1-minute wicks: its [stop], [target], profit lock ([lockAt]: the
     * lock the best price earns; the strategy's own, e.g. Pine's), the manager's trail ([trailing]: half the open profit at
     * the best price), and its [timeMs] square-off. A bar touching both the stop and the target counts as the stop
     * (conservative); a bar opening through a level fills at its open. Exits before [fromMs] are not counted (the trade was
     * still held then, as it really was); the best price counts from the entry.
     */
    data class Path(val stop: Double?, val target: Double?, val timeMs: Long, val peak: Double, val trailing: Boolean = false,
                    val exit: Exit? = null)

    fun walk(t: ManagedTrade, p0: Path, bars: List<Bar>, fromMs: Long, lockAt: ((Double) -> Double?)? = null): Path {
        var p = p0
        if (p.exit != null) return p
        for (b in bars.sortedBy { it.startMs }) {
            if (b.startMs < t.entryMs - 60_000L) continue
            if (b.startMs >= fromMs) {
                if (b.startMs >= p.timeMs) return p.copy(exit = Exit(b.startMs, b.open, "TIME"))
                val lockNow = lockAt?.invoke(p.peak)
                val trailNow = if (p.trailing) trail(t, p.peak) else null
                val stops = listOfNotNull(p.stop, lockNow, trailNow)
                val stop = stops.maxOrNull()
                if (stop != null && b.low <= stop) {
                    // A stop the manager's lock set (trailing) or the strategy's own profit lock raised is a lock exit.
                    val why = if (p.trailing || (lockNow != null && lockNow > (p.stop ?: Double.NEGATIVE_INFINITY))) "LOCK" else "STOP"
                    return p.copy(exit = Exit(b.startMs, minOf(stop, b.open), why))
                }
                val tg = p.target
                if (tg != null && b.high >= tg) return p.copy(exit = Exit(b.startMs, maxOf(tg, b.open), "TARGET"))
            }
            p = p.copy(peak = maxOf(p.peak, b.high))
        }
        return p
    }

    /** The original rules ended at a decided minute (Solo's index rules): priced at that minute's close (TIME: the next open). */
    fun priced(bars: List<Bar>, atMs: Long, why: String): Exit? {
        val s = bars.sortedBy { it.startMs }
        if (why == "TIME") {
            s.firstOrNull { it.startMs >= atMs }?.let { return Exit(atMs, it.open, why) }
            return s.lastOrNull { it.startMs < atMs }?.let { Exit(atMs, it.close, why) }
        }
        return s.lastOrNull { it.startMs <= atMs }?.let { Exit(atMs, it.close, why) }
    }

    // ---- the record -----------------------------------------------------------------------------------------------------

    /** One manager decision with its evidence; [acted] false in SHADOW (what it would have done). */
    data class Note(val atMs: Long, val kind: String, val rule: String, val words: String, val premium: Double?, val acted: Boolean,
                    val evidence: Map<String, Double> = emptyMap(), val target: Double? = null, val lock: Double? = null,
                    /** The specialist that led the decision ([ManagerTeam]; null: none - the lock, or an older record). */
                    val lead: String? = null,
                    /** Every specialist that voted for it (its report card is credited or debited when the trade settles). */
                    val voters: List<String> = emptyList())

    const val EXIT_EARLY = "EXIT_EARLY"
    const val EXTEND_NOTE = "EXTEND"
    const val LOCK_NOTE = "LOCK"
    const val TRAIL_NOTE = "TRAIL"

    /** Who the manager is on the findings bus. */
    const val WHO = "trade manager"

    /**
     * One managed trade: its [mode] at the start, every [notes], the manager's [target] and [lock] now, and three ends:
     * [actual] (what really happened), [manager] (where the manager's way ended: the actual in ACT, what it would have done in
     * SHADOW), [original] (where the original rules ended: the counterfactual in ACT, the actual in SHADOW). With no manager
     * action the two ways are the same and both are the actual.
     */
    data class Record(
        val trade: ManagedTrade, val mode: Mode, val notes: List<Note> = emptyList(),
        val target: Double? = trade.originalTarget, val lock: Double? = null, val extensions: Int = 0,
        val actual: Exit? = null, val manager: Exit? = null, val original: Exit? = null,
        val actedAtMs: Long? = null,
    ) {
        val closed: Boolean get() = actual != null
        val settled: Boolean get() = manager != null && original != null
        val earlyExit: Boolean get() = notes.any { it.kind == EXIT_EARLY }
        val lockExit: Boolean get() = notes.any { it.kind == LOCK_NOTE }
        /** The manager against the original rules, in rupees (before charges, which are the same round trip); null: not settled. */
        val vsOriginal: Double? get() = if (manager != null && original != null) (manager.price - original.price) * trade.qty else null
    }

    /** [r] after the trade really closed at [exit]. */
    fun closed(r: Record, exit: Exit): Record {
        if (r.actual != null) return r
        var x = r.copy(actual = exit)
        if (r.mode == Mode.ACT) {
            x = x.copy(manager = exit)
            if (r.actedAtMs == null) x = x.copy(original = exit)
        } else {
            x = x.copy(original = exit)
            if (r.actedAtMs == null) x = x.copy(manager = exit)
        }
        return x
    }

    /** A strategy with this many settled trades and the manager ahead of its original rules gets the "ready to turn on?" hint. */
    const val READY_TRADES = 30

    /**
     * One strategy family's record on one account: its trades; the early exits that helped (the manager ended above the
     * original rules) or hurt; the trades extended that helped or hurt (no early exit in them); the lock exits; the settled
     * trades and the manager against the original rules over them ([net], rupees); those still followed.
     */
    data class Tally(val family: String, val account: Account, val trades: Int, val earlyExits: Int, val extensions: Int, val lockExits: Int,
                     val settled: Int, val net: Double, val open: Int, val earlyHelped: Int = 0, val earlyHurt: Int = 0,
                     val extHelped: Int = 0, val extHurt: Int = 0) {
        /** A hint only: enough settled trades and the manager ahead. Nothing switches by itself. */
        val ready: Boolean get() = settled >= READY_TRADES && net > 0
    }

    private fun order(family: String): Int = FAMILIES.indexOfFirst { it.key == family }.let { if (it < 0) Int.MAX_VALUE else it }

    fun tally(records: List<Record>): List<Tally> = records.groupBy { it.trade.family to it.trade.account }.map { (k, rs) ->
        val st = rs.filter { it.settled }
        val early = st.filter { it.earlyExit || it.lockExit }
        val ext = st.filter { it.extensions > 0 && !it.earlyExit }
        Tally(k.first, k.second, rs.size, rs.count { it.earlyExit }, rs.sumOf { it.extensions }, rs.count { it.lockExit },
            st.size, st.sumOf { it.vsOriginal ?: 0.0 }, rs.count { !it.settled },
            early.count { (it.vsOriginal ?: 0.0) > 0 }, early.count { (it.vsOriginal ?: 0.0) < 0 },
            ext.count { (it.vsOriginal ?: 0.0) > 0 }, ext.count { (it.vsOriginal ?: 0.0) < 0 })
    }.sortedWith(compareBy<Tally>({ order(it.family) }, { it.family }, { it.account.ordinal }))

    // ---- words ----------------------------------------------------------------------------------------------------------

    fun num(x: Double): String = if (x.isNaN()) "—" else String.format(Locale.ENGLISH, "%,.2f", x).removeSuffix(".00")
    /** A level in words: always the market's (positive) price (a short future's are kept negated inside the manager). */
    fun lv(x: Double): String = num(abs(x))
    private fun pct(x: Double) = String.format(Locale.ENGLISH, "%.0f%%", x * 100)
    fun rs(x: Double): String = (if (x < 0) "−₹" else "+₹") + String.format(Locale.ENGLISH, "%,.0f", abs(x))

    fun modeWords(m: Mode): String = when (m) { Mode.OFF -> "off"; Mode.SHADOW -> "shadow (records only)"; Mode.ACT -> "acting" }

    /** The trade card's line: "Trade manager (acting): target 132 · lock 110.5 · extended once". */
    fun cardLine(r: Record): String {
        val parts = ArrayList<String>()
        r.target?.let { parts += "target ${lv(it)}" }
        parts += "lock " + (r.lock?.let { lv(it) } ?: "none yet")
        if (r.extensions > 0) parts += "extended ${if (r.extensions == 1) "once" else "${r.extensions} times"}"
        r.notes.lastOrNull { it.kind == EXIT_EARLY || it.kind == LOCK_NOTE }?.let {
            parts += (if (it.acted) "exited early: " else "would have exited: ") + it.words.take(80)
        }
        r.vsOriginal?.let { parts += "vs original rules ${rs(it)}" }
        return "Trade manager (${modeWords(r.mode)}): " + parts.joinToString(" · ")
    }

    private fun n(x: Int, one: String, many: String = one + "s") = "$x ${if (x == 1) one else many}"

    /** One strategy's record line ([t]); [mode] its mode now (the "ready to turn on?" hint is said only while it does not act). */
    fun tallyLine(t: Tally, mode: Mode? = null): String =
        "${familyName(t.family)} ${t.account.name.lowercase(Locale.ENGLISH)}: ${n(t.trades, "trade")}, " +
            "${n(t.earlyExits, "early exit")} (${t.earlyHelped} helped, ${t.earlyHurt} hurt), " +
            "${n(t.extensions, "extension")} (${t.extHelped} helped, ${t.extHurt} hurt), ${n(t.lockExits, "lock exit")}; " +
            (if (t.settled == 0) "nothing settled yet" else "manager vs original rules ${rs(t.net)} on ${t.settled} settled") +
            (if (t.open > 0) " (${t.open} still followed)" else "") +
            (if (t.ready && mode != Mode.ACT) " - ready to turn on? (${t.settled} settled, ahead by ${rs(t.net)}; a hint only, nothing switches by itself)" else "")

    /** The "Trade manager record" lines: per strategy, paper and live apart. */
    fun recordLines(records: List<Record>, policy: Policy? = null): List<String> {
        if (records.isEmpty()) return listOf("No trade managed yet.")
        return tally(records).map { t -> tallyLine(t, policy?.mode(t.family, t.account)) }
    }

    /**
     * A Jarvis question about the manager: [family] null for "how is it doing", else "why did <family> exit early"; [team]:
     * about its specialists ([ManagerTeam]) - which one made a strategy exit, how they are doing, mute or un-mute one.
     */
    data class Ask(val why: Boolean, val family: String?, val team: TeamAsk? = null)

    enum class TeamKind { WHICH, HOW, MUTE, UNMUTE }

    /** [specialist]: the [ManagerTeam] id named (null: none named). */
    data class TeamAsk(val kind: TeamKind, val specialist: String? = null)

    private val FAMILY_WORDS = listOf("solo" to Regex(" solo "), "pine" to Regex(" (pine|script|scripts) "),
        "liquidity" to Regex(" liquidity "), "hero" to Regex(" hero "), "orb" to Regex(" orb "), "silver-night" to Regex(" silver "),
        "night" to Regex(" night "), "vixdiv" to Regex(" vix "), "mcx-eve" to Regex(" evening "), "mcx-morning" to Regex(" morning "),
        "mcx-trend" to Regex(" trend "))

    private val SPECIALIST_WORD = Regex(" specialists? ")
    private val UNMUTE_WORDS = Regex(" (un ?mute|unmuted|bring back|switch on|turn on|enable|restore) ")
    private val MUTE_WORDS = Regex(" (mute|muted|silence|switch off|turn off|disable|ignore) ")
    private val WHICH_WORDS = Regex(" (which|who|what) ")
    private val EXIT_WORDS = Regex(" (exit|exited|exits|exiting|out|sell|sold|close|closed|cut|extend|extended) ")

    fun asked(text: String): Ask? {
        val t = " " + text.lowercase(Locale.ENGLISH).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim() + " "
        // The manager's specialists: "which specialist made Solo exit?", "how are the manager's specialists doing?",
        // "mute the OI specialist for Pine" (the specialist's own words taken out before the strategy is looked for).
        if (SPECIALIST_WORD.containsMatchIn(t)) {
            val spec = ManagerTeam.specialistIn(t)
            val rest = spec?.let { t.replaceFirst(it.second, " ") } ?: t
            val fam = FAMILY_WORDS.firstOrNull { it.second.containsMatchIn(rest) }?.first
            // Only the manager's: it, a strategy or one of its specialists named ("ask a tax specialist" is not this).
            if (spec == null && fam == null && !Regex(" (manager|managers|manager s) ").containsMatchIn(t)) return null
            val kind = when {
                UNMUTE_WORDS.containsMatchIn(t) -> TeamKind.UNMUTE
                MUTE_WORDS.containsMatchIn(t) -> TeamKind.MUTE
                WHICH_WORDS.containsMatchIn(t) && EXIT_WORDS.containsMatchIn(t) -> TeamKind.WHICH
                else -> TeamKind.HOW
            }
            return Ask(false, fam, TeamAsk(kind, spec?.first))
        }
        val early =Regex(" (exit|exited|exits|exiting|sell|sold|close|closed|cut|get out|got out) early | early exit ").containsMatchIn(t)
        if (early && Regex(" why ").containsMatchIn(t)) return Ask(true, FAMILY_WORDS.firstOrNull { it.second.containsMatchIn(t) }?.first)
        if (Regex(" trade manager| trade managers ").containsMatchIn(t)) return Ask(Regex(" why ").containsMatchIn(t), FAMILY_WORDS.firstOrNull { it.second.containsMatchIn(t) }?.first)
        return null
    }

    /** "How is the trade manager doing?": the modes, then the record per strategy, paper and live apart. */
    fun answerStatus(records: List<Record>, policy: Policy): String {
        val modes = FAMILIES.joinToString("; ") { f ->
            "${f.name}: paper ${modeWords(policy.mode(f.key, Account.PAPER))}" + if (f.paperOnly) "" else
                ", live ${modeWords(policy.mode(f.key, Account.LIVE))}"
        }
        val open = records.filter { !it.closed }.map { "${it.trade.label}: ${cardLine(it).removePrefix("Trade manager ")}" }
        return "The trade manager, Boss - $modes. Solo and Pine act on paper; every other strategy records only until you switch it on.\n" +
            recordLines(records, policy).joinToString("\n") { "• $it" } +
            (if (open.isNotEmpty()) "\nNow: " + open.joinToString("; ") else "") +
            "\nIt only lowers risk: early exits, and a target moved out only with the lock raised to breakeven plus charges or better."
    }

    /** "Why did Solo exit early?": the newest early exit (or would-be one) of [family] with its evidence. */
    fun answerWhy(family: String?, records: List<Record>, hhmm: (Long) -> String): String {
        val mine = records.filter { family == null || it.trade.family == family }
        val r = mine.lastOrNull { it.notes.any { n -> n.kind == EXIT_EARLY || n.kind == LOCK_NOTE } }
        val who = family?.let { familyName(it) } ?: "No strategy"
        if (r == null) return if (family == null) "No managed trade has exited early yet, Boss." else "$who has not exited early under the trade manager yet, Boss."
        val n = r.notes.last { it.kind == EXIT_EARLY || it.kind == LOCK_NOTE }
        val ev = n.evidence.entries.filter { !it.value.isNaN() }.take(6).joinToString(", ") { "${it.key} ${num(it.value)}" }
        val head = if (n.acted) "${r.trade.label} exited early at ${hhmm(n.atMs)}" else "${r.trade.label} would have exited early at ${hhmm(n.atMs)} (shadow: nothing was done)"
        val vs = r.vsOriginal?.let { " Against the original rules: ${rs(it)}" + (r.original?.let { o -> " (they ended ${o.why.lowercase(Locale.ENGLISH)} at ${lv(o.price)})" } ?: "") + "." }
            ?: " The original rules are still being followed to compare."
        return "$head: ${n.words}" + (n.premium?.let { " (premium ${lv(it)})" } ?: "") + ". Evidence: ${ev.ifEmpty { "none kept" }}." +
            " Target ${r.target?.let { lv(it) } ?: "none"}, lock ${r.lock?.let { lv(it) } ?: "none"}." + vs
    }
}
