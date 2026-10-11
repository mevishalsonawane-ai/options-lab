package com.optionslab.ira

import java.util.Locale
import kotlin.math.abs

/**
 * The trade manager as a team (Boss, 10 Oct: "turn the trade manager into a team of specialists"). Sixteen specialists each
 * watch one family of readings and vote at every look ([Specialist.look]: HOLD, EXIT, EXTEND or TRAIL, with a strength
 * 0-100, a reason in plain words and how long it must last); the Chair ([decide]) turns the votes into the one decision.
 *
 *  - They work together through shared memory, the [Board]: every specialist's latest vote is on it, so a later one reads
 *    what the others already see (Momentum reads that VWAP is stretched and Volume sees a climax). Notable votes go on the
 *    findings bus too ([Step.posts], posted by the app with no direction, so no strategy's consensus counts them twice).
 *  - The Chair: EXIT when the weighted strength of the EXIT votes that have lasted their hold reaches the [ManagerConfig.quorum],
 *    or at once on a hard veto from News, Traps or DataHealth (their own original rules); EXTEND only when every gate
 *    specialist agrees, a supermajority of the weighted votes agrees, and no EXIT vote at or above the floor stands (nor
 *    DataHealth's block); TRAIL to the highest lock proposed, never down.
 *  - Unchanged from [TradeManager]: the hysteresis (each rule's own hold, a gap of over 20 s starts every hold again), the
 *    60 s cooldown, at most 2 extensions, none near the square-off, and the lock: [TradeManager.levels] sets it on every
 *    extension (breakeven plus charges or better, under the price), [TradeManager.trail] trails it, it never falls, a stop is
 *    never widened and nothing is ever added. Money at risk never rises.
 *  - The defaults reproduce the single manager: the seven original rules are seven specialists at weight 1 voting 100, so
 *    any one of them alone reaches the quorum after its own hold, in the original order; the extension's nine conditions
 *    are the gates. The new specialists start at a quarter weight (4 of them together to reach the quorum) and their
 *    votes are kept under the floor where they would otherwise hold an extension back - see [DEFAULT_SPECS] and
 *    ManagerTeamTest, which replays the original scenarios through both.
 *  - A muted specialist (Boss's or the self-tuning's, [ManagerConfig.counts] false) still looks - its record goes on - but its
 *    EXIT and EXTEND no longer count; its objection to an extension and its hard veto still stand. Muting only ever moves
 *    the manager back toward the strategy's original rules.
 *
 * Pure: no clock, no Android, no storage. A look is memory only and cheap (ManagerTeamSpeedTest: under 1 ms).
 */
object ManagerTeam {
    enum class Kind { HOLD, EXIT, EXTEND, TRAIL }

    /**
     * One specialist's vote at one look. [strength] 0-100; [needsHoldSec]: how long the same vote must stand before it
     * counts (the Chair keeps the time); [proposedLock] / [proposedTarget]: levels it would set (the lock only ever rises);
     * [veto]: a hard veto (News, Traps, DataHealth on their own rules); [blocksExtend]: no extension now (DataHealth);
     * [tag]: a word the others read on the board ("stretched", "climax", "drying", "fading", ...); [rule]: the original rule
     * it stands for, when it is one.
     */
    data class Vote(
        val specialist: String,
        val kind: Kind,
        val strength: Int,
        val reason: String,
        val proposedLock: Double? = null,
        val proposedTarget: Double? = null,
        val needsHoldSec: Int = 0,
        val veto: Boolean = false,
        val blocksExtend: Boolean = false,
        val tag: String? = null,
        val rule: TradeManager.Rule? = null,
        val evidence: Map<String, Double> = emptyMap(),
        /**
         * [proposedLock] is a lock rule of its own (the research's VC and BE, [R12Rules]): it raises the lock from the next look
         * whether or not the target was ever extended - only ever up, and never under the stop already there.
         */
        val ownLock: Boolean = false,
    )

    /** One specialist: pure and side-effect free; reads only [MarketView] and the [Board]. */
    interface Specialist {
        val id: String
        val name: String
        fun look(t: TradeManager.ManagedTrade, m: MarketView, board: Board): Vote
    }

    /** One premium and future price kept for the momentum reading (about every 10 s, the last 3 minutes). */
    data class Sample(val atMs: Long, val premium: Double, val fut: Double?)

    /**
     * Everything one look sees, worked out once for all the specialists: the readings [s], the manager's state [st], the
     * premium [px], the fresh order-flow read [flow] and the future's price [fut], the original rules that hold now ([hits],
     * [TradeManager.against]) and the extension's conditions ([room], [TradeManager.room]), the recent [tape], the
     * strategy's [cfg] and the trade's [side] (+1 a call, −1 a put).
     */
    class MarketView(
        val s: TradeManager.Snapshot, val st: TradeManager.State, val px: Double?, val flow: OrderFlow.Read?, val fut: Double?,
        val hits: Map<TradeManager.Rule, Pair<String, Map<String, Double>>>, val room: TradeManager.Room, val tape: List<Sample>,
        val cfg: ManagerConfig, val side: Int,
        /** The index's (the future's) minutes since the trade was first looked at: close, VWAP and its SD (the research's VW, MP). */
        val minutes: List<R12Rules.IndexMinute> = emptyList(),
    ) {
        val now: Long get() = s.atMs
        fun th(id: String, key: String, d: Double): Double = cfg.th(id, key, d)
        /** [id] runs the research's rules (a member of a research committee, [ManagerConfig.members]). */
        fun research(id: String): Boolean = cfg.isResearch && (id in cfg.members || id in cfg.extendMembers)
        fun p(id: String, key: String): Double? = cfg.param(id, key)
    }

    /**
     * The shared memory of one trade's look: each specialist's latest vote (this look's for those that have looked, the last
     * look's for the rest). Specialists read it; only the Chair writes it.
     */
    class Board internal constructor(private val votes: Array<Vote?>) {
        fun vote(id: String): Vote? = INDEX[id]?.let { votes[it] }
        fun tag(id: String): String? = vote(id)?.tag
        /** The specialists whose latest vote is [kind]. */
        fun voting(kind: Kind): List<String> = votes.filterNotNull().filter { it.kind == kind }.map { it.specialist }
        fun all(): List<Vote> = votes.filterNotNull()
        internal fun put(i: Int, v: Vote) { votes[i] = v }
    }

    // ---- the specialists ------------------------------------------------------------------------------------------------

    private fun hold(id: String, reason: String, tag: String? = null, blocksExtend: Boolean = false, lock: Double? = null) =
        Vote(id, if (lock != null) Kind.TRAIL else Kind.HOLD, 0, reason, proposedLock = lock, tag = tag, blocksExtend = blocksExtend)

    /** The original rule [r] as a vote of [id]: EXIT 100 with the rule's words and numbers, after the rule's own hold. */
    private fun ruleVote(id: String, r: TradeManager.Rule, m: MarketView, veto: Boolean = false): Vote? = m.hits[r]?.let { h ->
        Vote(id, Kind.EXIT, 100, h.first, needsHoldSec = (r.persistMs / 1000).toInt(), veto = veto, rule = r, evidence = h.second)
    }

    private fun them(side: Int) = if (side > 0) "sellers" else "buyers"
    private fun us(side: Int) = if (side > 0) "buyers" else "sellers"
    private fun f1(x: Double) = String.format(Locale.ENGLISH, "%.1f", x)
    private fun num(x: Double) = TradeManager.num(x)

    /** 1. Order flow: who leads and how strongly, with the real trades' lean (executed volume). */
    object FlowSpec : Specialist {
        override val id = "flow"; override val name = "Order flow"
        override fun look(t: TradeManager.ManagedTrade, m: MarketView, board: Board): Vote {
            ruleVote(id, TradeManager.Rule.OPPOSITE_FLOW, m)?.let { return it }
            val r = m.flow ?: return hold(id, "no fresh order-flow read")
            if (m.room.flow) return Vote(id, Kind.EXTEND, r.strength, "${us(m.side)} ${r.strength} with executed volume ${r.cvd60.toLong()} behind the trade")
            val lean = if (r.cvd60 * m.side > 0) "the real trades lean its way" else if (r.cvd60 * m.side < 0) "the real trades lean against it" else "no lean in the real trades"
            return hold(id, "${r.side.name.lowercase(Locale.ENGLISH)} ${r.strength}; $lean")
        }
    }

    /** 2. Delta: cumulative delta and its divergence. */
    object DeltaSpec : Specialist {
        override val id = "delta"; override val name = "Delta"
        override fun look(t: TradeManager.ManagedTrade, m: MarketView, board: Board): Vote {
            // The research's delta PROXY (R12 DP; the extension's xDP): the 10-minute signed option flow as a z-score.
            if (m.research(id)) {
                val z = m.s.flowZ
                val z0 = m.p(id, "dpZ0")
                if (id in m.cfg.members && z0 != null && R12Rules.dp(z, m.side, z0))
                    return Vote(id, Kind.EXIT, 100, "signed option flow ${f1(z ?: 0.0)} SD against it over 10 minutes (the research's delta proxy)",
                        tag = "r12-dp", evidence = mapOf("flowZ" to (z ?: Double.NaN)))
                val x = m.p(id, "xdp")
                if (id in m.cfg.extendMembers && x != null && R12Rules.xdp(z, m.side, x))
                    return Vote(id, Kind.EXTEND, 60, "signed option flow ${f1(z ?: 0.0)} SD its way")
                return hold(id, z?.let { "signed option flow ${f1(it)} SD" } ?: "no signed option-flow reading yet (the research's delta proxy waits for it)")
            }
            ruleVote(id, TradeManager.Rule.DIVERGENCE, m)?.let { return it }
            val favour = if (m.side > 0) Auction.Divergence.BULLISH else Auction.Divergence.BEARISH
            if (m.s.divergence == favour) return Vote(id, Kind.EXTEND, 55, "delta divergence in its favour")
            val cvd = m.flow?.cvd300 ?: return hold(id, "no delta read")
            return hold(id, "5-minute delta ${cvd.toLong()}" + if (cvd * m.side < 0) ", against it" else "")
        }
    }

    /** 3. Absorption: a defended level near the price. */
    object AbsorptionSpec : Specialist {
        override val id = "absorption"; override val name = "Absorption"
        override fun look(t: TradeManager.ManagedTrade, m: MarketView, board: Board): Vote {
            ruleVote(id, TradeManager.Rule.ABSORPTION, m)?.let { return it }
            val r = m.flow; val fut = m.fut
            if (r != null && fut != null && r.absorbBy == m.side && !r.absorbAt.isNaN() && abs(fut - r.absorbAt) <= TradeManager.ABSORB_NEAR * fut)
                return Vote(id, Kind.EXTEND, 55, "${us(m.side)} absorbing at ${num(r.absorbAt)}: the level holds its way")
            return hold(id, "no absorption at the price")
        }
    }

    /** 4. Traps: the trap guard's spoofs and pulls, stop hunts and failed breaks. A hard veto on its own rule. */
    object TrapsSpec : Specialist {
        override val id = "traps"; override val name = "Traps"
        override fun look(t: TradeManager.ManagedTrade, m: MarketView, board: Board): Vote {
            ruleVote(id, TradeManager.Rule.TRAP, m, veto = true)?.let { return it }
            val r = m.flow ?: return hold(id, "no fresh order-flow read")
            val pull = if (m.side > 0) TrapGuard.Trap.PULL_BID else TrapGuard.Trap.PULL_ASK
            if (pull in r.flags) return Vote(id, Kind.EXIT, 40, "orders pulled on its side (${pull.words})", needsHoldSec = 10, tag = "pull")
            if (m.room.traps) return Vote(id, Kind.EXTEND, 60, "no trap against it")
            return hold(id, "a stop hunt or failed break its way: no extension", tag = "hunt")
        }
    }

    /** 5. VWAP: the distance in standard deviations, and a recross against the trade. */
    object VwapSpec : Specialist {
        override val id = "vwap"; override val name = "VWAP"
        override fun look(t: TradeManager.ManagedTrade, m: MarketView, board: Board): Vote {
            // The research's VW (R12): z SD on the wrong side of VWAP for p minute closes in a row; and the extension's xVW.
            if (m.research(id)) {
                val z = m.p(id, "vwZ"); val p = m.p(id, "vwP")
                if (id in m.cfg.members && z != null && p != null) {
                    val at = R12Rules.vw(m.minutes, m.side, t.entryMs, m.now, z, p.toInt())
                    if (at != null) return Vote(id, Kind.EXIT, 100, "the index ${p.toInt()} minute${if (p.toInt() == 1) "" else "s"} on the wrong side of VWAP " +
                        "by over ${f1(z)} SD (the research's VWAP rule)", tag = "r12-vw", evidence = mapOf("z" to z, "minutes" to p))
                }
                val x = m.p(id, "xvw")
                val last = m.minutes.lastOrNull { it.startMs + R12Rules.MINUTE_MS <= m.now }
                if (id in m.cfg.extendMembers && x != null && R12Rules.xvw(last?.close, last?.vwap, last?.sd, m.side, x))
                    return Vote(id, Kind.EXTEND, 60, "at least ${f1(x)} SD beyond VWAP its way")
                return hold(id, "VWAP: the research's rule does not hold")
            }
            val vw = m.s.vwap ?: return hold(id, "no VWAP yet")
            val fut = m.fut ?: return hold(id, "no future price")
            val sd = vw.sdUnits(fut)?.let { it * m.side }
            if ((fut - vw.vwap) * m.side < 0)
                return Vote(id, Kind.EXIT, 45, "price ${num(fut)} back through VWAP ${num(vw.vwap)} against it", needsHoldSec = 60, tag = "recross",
                    evidence = mapOf("vwap" to vw.vwap, "price" to fut))
            val stretched = sd != null && sd >= m.th(id, "stretchSd", 2.0)
            if (m.room.vwap) return Vote(id, Kind.EXTEND, if (stretched) 50 else 70,
                (sd?.let { "${f1(it)} SD beyond VWAP its way" } ?: "beyond VWAP its way") + if (stretched) " (stretched)" else "",
                tag = if (stretched) "stretched" else null)
            return hold(id, sd?.let { "${f1(it)} SD from VWAP" } ?: "at VWAP")
        }
    }

    /** The volume of the minutes closed by [nowMs] (oldest first). */
    private fun closedVolumes(m: MarketView): List<Long> {
        val nowSec = m.now / 1000
        val v = m.s.minuteVolumes
        val out = ArrayList<Long>(v.size)
        for (p in v) if (p.first + 60 <= nowSec) out += p.second
        return out
    }

    /** 6. Volume: a climax or exhaustion, and volume drying up. */
    object VolumeSpec : Specialist {
        override val id = "volume"; override val name = "Volume"
        override fun look(t: TradeManager.ManagedTrade, m: MarketView, board: Board): Vote {
            val r = m.flow
            val gaveWay = if (m.side > 0) TrapGuard.Trap.EXHAUSTION_SELL else TrapGuard.Trap.EXHAUSTION_BUY
            if (r != null && gaveWay in r.flags) return Vote(id, Kind.EXIT, 45, "exhaustion against it (${gaveWay.words})", needsHoldSec = 30, tag = "exhaustion")
            val vols = closedVolumes(m)
            if (vols.size < m.th(id, "minMinutes", 10.0).toInt()) return hold(id, "volume: not enough minutes yet")
            val last = vols.last().toDouble()
            val before = vols.subList(0, vols.size - 1).takeLast(20)
            val avg = before.average()
            if (!(avg > 0)) return hold(id, "volume: no normal yet")
            val px = m.px
            val inProfit = px != null && px > t.entry
            if (last >= m.th(id, "climaxX", 3.0) * avg && inProfit)
                return Vote(id, Kind.EXIT, 45, "a volume climax (${f1(last / avg)}x normal) with the trade in profit", needsHoldSec = 30, tag = "climax",
                    evidence = mapOf("volumeX" to last / avg))
            val recent = vols.takeLast(3).average()
            if (recent <= m.th(id, "dryX", 0.5) * avg)
                return Vote(id, Kind.EXIT, 50, "volume drying up (${f1(recent / avg)}x normal): no fuel to go further", needsHoldSec = 60, tag = "drying",
                    evidence = mapOf("volumeX" to recent / avg))
            if (recent >= 1.5 * avg && r != null && r.cvd60 * m.side > 0) return Vote(id, Kind.EXTEND, 60, "volume ${f1(recent / avg)}x normal its way")
            return hold(id, "volume ${f1(recent / avg)}x normal")
        }
    }

    /** 7. Profile: the value area, the POC and the high- / low-volume nodes. */
    object ProfileSpec : Specialist {
        override val id = "profile"; override val name = "Profile"
        override fun look(t: TradeManager.ManagedTrade, m: MarketView, board: Board): Vote {
            // The research's MP (R12): outside the prior day's value area the trade's way, then back inside for p minutes.
            if (m.research(id)) {
                val p = m.p(id, "mpP") ?: return hold(id, "the research's value-area rule has no setting")
                val at = R12Rules.mp(m.minutes, m.side, t.entryMs, m.now, m.s.priorValueHigh, m.s.priorValueLow, p.toInt())
                if (at != null) return Vote(id, Kind.EXIT, 100, "the index went outside the prior day's value area its way and has been back " +
                    "inside ${p.toInt()} minute${if (p.toInt() == 1) "" else "s"} (the research's value-area rule)", tag = "r12-mp",
                    evidence = mapOf("minutes" to p))
                return hold(id, if (m.s.priorValueHigh == null) "no prior day's value area" else "the prior day's value area: nothing against it")
            }
            val fut = m.fut ?: return hold(id, "no future price")
            if (m.room.value) {
                val hvn = m.s.hvns.firstOrNull { (it - fut) * m.side > 0 && abs(it - fut) <= m.th(id, "hvnNearPct", 0.0015) * fut }
                return Vote(id, Kind.EXTEND, if (hvn != null) 45 else 65, "accepted beyond value" + (hvn?.let { " (a high-volume node just ahead at ${num(it)})" } ?: ""),
                    tag = if (hvn != null) "hvn-ahead" else null)
            }
            val poc = m.s.poc
            if (poc != null && (fut - poc) * m.side < 0)
                return Vote(id, Kind.EXIT, 40, "price ${num(fut)} back ${if (m.side > 0) "below" else "above"} the POC ${num(poc)}", needsHoldSec = 60, tag = "poc",
                    evidence = mapOf("poc" to poc, "price" to fut))
            return hold(id, if (m.s.valueHigh != null) "inside value" else "no profile yet")
        }
    }

    /** 8. OI: build-up or unwinding, its way or against it. */
    object OiSpec : Specialist {
        override val id = "oi"; override val name = "OI"
        override fun look(t: TradeManager.ManagedTrade, m: MarketView, board: Board): Vote {
            // The research's OI (R12): ATM±2 put-minus-call OI change over 15 minutes against the trade at −z0 SD or worse.
            if (m.research(id)) {
                val z0 = m.p(id, "oiZ0") ?: return hold(id, "the research's OI rule has no setting")
                val z = m.s.oiZ
                if (R12Rules.oi(z, m.side, z0)) return Vote(id, Kind.EXIT, 100, "put-minus-call OI change ${f1(z ?: 0.0)} SD against it " +
                    "over 15 minutes (the research's OI rule)", tag = "r12-oi", evidence = mapOf("oiZ" to (z ?: Double.NaN)))
                return hold(id, z?.let { "OI change ${f1(it)} SD" } ?: "no OI z-reading yet (the research's OI rule waits for it)")
            }
            val r = m.flow ?: return hold(id, "no fresh OI read")
            val bu = r.buildUp ?: return hold(id, "no OI change yet")
            if (m.room.oi) return Vote(id, Kind.EXTEND, 65, "${bu.words} its way (OI +${r.oiChange})")
            val against = if (m.side > 0) OrderFlow.BuildUp.SHORT_BUILDUP else OrderFlow.BuildUp.LONG_BUILDUP
            val unwinding = if (m.side > 0) OrderFlow.BuildUp.LONG_UNWINDING else OrderFlow.BuildUp.SHORT_COVERING
            if (bu == against && (r.oiChange ?: 0L) > 0L) return Vote(id, Kind.EXIT, 45, "${bu.words} against it (OI +${r.oiChange})", needsHoldSec = 60, tag = "against")
            if (bu == unwinding) return Vote(id, Kind.EXIT, 40, "${bu.words}: its own side leaving", needsHoldSec = 60, tag = "unwinding")
            return hold(id, bu.words)
        }
    }

    /** 9. VIX / IV: a VIX spike (no extension) and an IV crush (the option bleeds even if the index holds). */
    object VixSpec : Specialist {
        override val id = "vix"; override val name = "VIX / IV"
        override fun look(t: TradeManager.ManagedTrade, m: MarketView, board: Board): Vote {
            val vix = m.s.vixChangePct
            if (!m.room.vix) return hold(id, "VIX up ${f1(vix ?: 0.0)}% in 5 minutes: no extension", tag = "spike")
            val iv = m.s.ivChangePct
            if (iv != null && iv <= -m.th(id, "crushPct", 8.0))
                return Vote(id, Kind.EXIT, 45, "IV crush (${f1(iv)}% in 5 minutes)", needsHoldSec = 30, tag = "crush", evidence = mapOf("ivChangePct" to iv))
            return Vote(id, Kind.EXTEND, 55, if (vix == null) "VIX not spiking" else "VIX calm (${f1(vix)}% in 5 minutes)")
        }
    }

    /** 10. Momentum: the option premium's and the index's rate of change fading; reads VWAP and Volume on the board. */
    object MomentumSpec : Specialist {
        override val id = "momentum"; override val name = "Momentum"
        override fun look(t: TradeManager.ManagedTrade, m: MarketView, board: Board): Vote {
            val px = m.px ?: return hold(id, "no premium")
            val back = m.th(id, "lookbackSec", 60.0).toLong() * 1000
            var old: Sample? = null
            for (i in m.tape.indices.reversed()) { val x = m.tape[i]; if (m.now - x.atMs >= back) { old = x; break } }
            if (old == null || old.premium <= 0) return hold(id, "momentum: not enough history yet")
            val roc = (px / old.premium - 1) * 100
            val idx = if (m.fut != null && old.fut != null && old.fut > 0) (m.fut - old.fut) / old.fut * 1e4 * m.side else 0.0
            val tired = board.tag("vwap") == "stretched" && board.tag("volume") in TIRED
            if (roc <= -m.th(id, "fadePct", 3.0) && idx <= 0) {
                val boost = if (tired) m.th(id, "boost", 20.0).toInt() else 0
                return Vote(id, Kind.EXIT, (40 + boost).coerceAtMost(100),
                    "premium ${f1(roc)}% and the index ${f1(idx)} bp its way in ${back / 1000} s: fading" + if (tired) "; VWAP and Volume flag exhaustion too" else "",
                    needsHoldSec = 30, tag = "fading", evidence = mapOf("premiumRocPct" to roc, "indexRocBp" to idx))
            }
            if (roc >= m.th(id, "fadePct", 3.0) && idx > 0 && !tired)
                return Vote(id, Kind.EXTEND, 60, "premium +${f1(roc)}% with the index its way: momentum behind it")
            return hold(id, "premium ${f1(roc)}% in ${back / 1000} s" + if (tired) "; VWAP and Volume flag exhaustion" else "", tag = if (tired) "tired" else null)
        }
        private val TIRED = setOf("climax", "drying", "exhaustion")
    }

    /** 11. Price action / MFE: the way to the target, no progress in time, giving back the best (MFE); proposes the trail. */
    object PriceSpec : Specialist {
        override val id = "price"; override val name = "Price action"
        override fun look(t: TradeManager.ManagedTrade, m: MarketView, board: Board): Vote {
            if (m.research(id)) return research(t, m)
            val px = m.px ?: return hold(id, "no premium")
            val lk = m.st.lock
            val tr = lk?.let { TradeManager.trail(t, m.st.peak).takeIf { x -> x > it + 1e-9 } }
            // Near the target with room to extend: the original extension's condition comes first (its lock keeps half the open profit).
            if (m.room.target && m.room.near) {
                val tg = m.st.target ?: 0.0
                return Vote(id, Kind.EXTEND, 70, "${((px - t.entry) / (tg - t.entry) * 100).toInt()}% of the way to the target ${num(tg)}", proposedLock = tr)
            }
            val mfe = m.st.peak - t.entry
            if (mfe >= m.th(id, "minMfePct", 0.15) * t.entry && px <= t.entry + (1 - m.th(id, "giveBack", 0.6)) * mfe)
                return Vote(id, Kind.EXIT, 45, "gave back ${((m.st.peak - px) / mfe * 100).toInt()}% of its best (${num(m.st.peak)})", proposedLock = tr,
                    needsHoldSec = 30, tag = "giveback", evidence = mapOf("peak" to m.st.peak, "premium" to px))
            val ageMin = (m.now - t.entryMs) / 60_000.0
            if (ageMin >= m.th(id, "noProgressMin", 30.0) && m.st.peak < t.entry * (1 + m.th(id, "progressPct", 0.05)))
                return Vote(id, Kind.EXIT, 40, "no progress in ${ageMin.toInt()} minutes", proposedLock = tr, needsHoldSec = 60, tag = "stalled")
            if (tr != null) return hold(id, "a new best ${num(m.st.peak)}: the lock trails to ${num(tr)}", lock = tr)
            return hold(id, if (m.st.target != null) "on its way to the target" else "no target (never extended)")
        }

        /**
         * The research's price rules (R12, on the option's 1-minute bars, decided on minute closes): PA "no progress", PB the
         * adverse close, and the locks VC (best high less k option ATRs, once a ATRs up) and BE (breakeven plus charges once
         * b up). Each runs only with its parameters set.
         */
        private fun research(t: TradeManager.ManagedTrade, m: MarketView): Vote {
            val bars = m.s.minuteBars
            val vcK = m.p(id, "vcK"); val vcA = m.p(id, "vcArm"); val beB = m.p(id, "beB")
            val vc = if (vcK != null && vcA != null) R12Rules.vc(bars, t.entry, t.entryMs, m.now, vcK, vcA, t.caps.tick) else null
            val be = if (beB != null) R12Rules.be(bars, t.entry, t.entryMs, m.now, beB, t.entry + t.chargesPerUnit, t.caps.tick) else null
            val lock = listOfNotNull(vc, be).maxOrNull()
            val paT = m.p(id, "paT"); val paM = m.p(id, "paM")
            if (paT != null && paM != null && R12Rules.pa(bars, t.entry, t.entryMs, m.now, paT.toInt(), paM) != null)
                return Vote(id, Kind.EXIT, 100, "no progress: ${paT.toInt()} minutes in, never ${pc(paM)} above the entry ${num(t.entry)} " +
                    "(the research's rule)", proposedLock = lock, ownLock = lock != null, tag = "r12-pa", evidence = mapOf("minutes" to paT, "progress" to paM))
            val pbA = m.p(id, "pbA")
            if (pbA != null && R12Rules.pb(bars, t.entry, t.entryMs, m.now, pbA) != null)
                return Vote(id, Kind.EXIT, 100, "a minute closed ${pc(pbA)} or more under the entry ${num(t.entry)} (the research's rule)",
                    proposedLock = lock, ownLock = lock != null, tag = "r12-pb", evidence = mapOf("adverse" to pbA))
            if (lock != null) return Vote(id, Kind.TRAIL, 0, "the research's lock at ${num(lock)} (" +
                (if (lock == vc && vcK != null) "the best high less ${f1(vcK)} option ATRs" else "breakeven plus charges") + ")",
                proposedLock = lock, ownLock = true, tag = "r12-lock", evidence = mapOf("lock" to lock))
            return hold(id, if (bars.isEmpty()) "the option's minutes not loaded yet" else "the research's price rules: hold")
        }

        private fun pc(x: Double) = String.format(Locale.ENGLISH, "%.0f%%", x * 100)
    }

    /** 12. Time and theta: the square-off, a time stop, and theta in the expiry hour. */
    object TimeSpec : Specialist {
        override val id = "time"; override val name = "Time and theta"
        override fun look(t: TradeManager.ManagedTrade, m: MarketView, board: Board): Vote {
            val px = m.px
            val ageMin = (m.now - t.entryMs) / 60_000.0
            if (px != null && px < t.entry && ageMin >= m.th(id, "maxMin", 120.0))
                return Vote(id, Kind.EXIT, 40, "open ${ageMin.toInt()} minutes and still under its entry", needsHoldSec = 60, tag = "time-stop")
            val expiryHour = m.s.brain?.windows?.any { it.kind == MarketBrain.Kind.EXPIRY_HOUR && it.active(m.now) && it.concerns(t.underlying) } == true
            if (expiryHour && px != null && px <= t.entry)
                return Vote(id, Kind.EXIT, 45, "the expiry hour: theta eats an option that is not in profit", needsHoldSec = 60, tag = "theta")
            val left = (t.caps.squareOffMs - m.now) / 60_000
            if (m.room.time) return Vote(id, Kind.EXTEND, 50, "$left minutes to the square-off")
            return hold(id, "$left minutes to the square-off: too close to extend", tag = "late")
        }
    }

    /** 13. Gamma: the GEX regime (negative: moves run; positive: moves get sold) and the zero-gamma level. */
    object GammaSpec : Specialist {
        override val id = "gamma"; override val name = "Gamma"
        override fun look(t: TradeManager.ManagedTrade, m: MarketView, board: Board): Vote {
            val net = m.s.gammaNet ?: return hold(id, "no gamma reading")
            val zg = m.s.zeroGamma; val fut = m.fut
            if (net < 0 && zg != null && fut != null && (fut - zg) * m.side < 0)
                return Vote(id, Kind.EXIT, 40, "${if (m.side > 0) "below" else "above"} zero gamma ${num(zg)} in a trending regime", needsHoldSec = 60, tag = "flip")
            if (net < 0) return Vote(id, Kind.EXTEND, 55, "negative gamma: moves tend to run")
            return hold(id, "positive gamma: moves tend to be sold", tag = "pinning")
        }
    }

    /** 14. News: a news window starting. A hard veto on its own rule. */
    object NewsSpec : Specialist {
        override val id = "news"; override val name = "News"
        override fun look(t: TradeManager.ManagedTrade, m: MarketView, board: Board): Vote {
            ruleVote(id, TradeManager.Rule.NEWS, m, veto = true)?.let { return it }
            val next = m.s.brain?.windows?.firstOrNull { it.kind == MarketBrain.Kind.NEWS && it.concerns(t.underlying) && it.startMs > m.now }
            return hold(id, next?.let { "${it.name} in ${(it.startMs - m.now) / 60_000} minutes" } ?: "no news window ahead")
        }
    }

    /** 15. Bots: the other strategies' findings, for and against. */
    object BotsSpec : Specialist {
        override val id = "bots"; override val name = "Bots"
        override fun look(t: TradeManager.ManagedTrade, m: MarketView, board: Board): Vote {
            ruleVote(id, TradeManager.Rule.CONSENSUS, m)?.let { return it }
            var forIt = 0; var against = 0
            val own = TradeManager.familyName(t.family)
            for (f in m.s.findings) {
                if (f.direction == 0 || !f.alive(m.now) || m.now - f.atMs > TradeManager.CONSENSUS_MS || f.strength < TradeManager.CONSENSUS_STRENGTH) continue
                if (!f.instrument.equals(t.underlying, ignoreCase = true) || f.who.startsWith(own) || f.who.startsWith(TradeManager.WHO)) continue
                if (f.direction == m.side) forIt++ else against++
            }
            if (forIt >= TradeManager.CONSENSUS_MIN && against == 0) return Vote(id, Kind.EXTEND, 55, "$forIt strong findings its way")
            return hold(id, "$forIt strong findings its way, $against against")
        }
    }

    /** 16. Data health: the feed's freshness. A hard veto on its own rule; a stale feed holds every extension back. */
    object DataSpec : Specialist {
        override val id = "data"; override val name = "Data health"
        override fun look(t: TradeManager.ManagedTrade, m: MarketView, board: Board): Vote {
            ruleVote(id, TradeManager.Rule.DATA, m, veto = true)?.let { return it }
            if (TradeManager.unreliable(t, m.s)) return hold(id, "data unreliable: no extension", tag = "unreliable", blocksExtend = true)
            val age = m.s.premiumAgeMs
            if (age != null && age > m.th(id, "staleMs", 15_000.0)) return hold(id, "the option's price is ${age / 1000} s old: no extension", tag = "stale", blocksExtend = true)
            val r = m.s.flow
            if (r != null && m.flow == null && r.warm) return hold(id, "the order-flow read is ${m.now / 1000 - r.atSec} s old: no extension", tag = "stale", blocksExtend = true)
            return hold(id, "feed fresh")
        }
    }

    /** The committee in the order it looks (VWAP and Volume before Momentum, so it reads them on the board this look). */
    val SPECIALISTS: List<Specialist> = listOf(DataSpec, NewsSpec, TrapsSpec, FlowSpec, AbsorptionSpec, DeltaSpec, BotsSpec,
        VwapSpec, VolumeSpec, ProfileSpec, OiSpec, VixSpec, MomentumSpec, PriceSpec, TimeSpec, GammaSpec)

    val IDS: List<String> = SPECIALISTS.map { it.id }
    private val INDEX: Map<String, Int> = IDS.withIndex().associate { it.value to it.index }

    /** The specialists whose hard veto stands whatever their weight or mute. */
    val VETOES: Set<String> = setOf("news", "traps", "data")

    fun nameOf(id: String): String = SPECIALISTS.firstOrNull { it.id == id }?.name ?: id

    /**
     * The defaults: the seven original rules' specialists (Data, News, Traps, Order flow, Absorption, Delta, Bots) at weight
     * 1, so any one of them reaches the quorum of 1 on its own, as before; the nine new readings at a quarter weight
     * (unproven: at least four must agree to reach the quorum). The gates are the original extension's conditions: Order
     * flow, Traps, VWAP, Profile (beyond value), OI, VIX, Price action (near the target) and Time (far from the square-off).
     * Each specialist's own thresholds are listed here so the research can see and change them.
     */
    val DEFAULT_SPECS: Map<String, SpecConfig> = linkedMapOf(
        "data" to SpecConfig(1.0, thresholds = mapOf("staleMs" to 15_000.0)),
        "news" to SpecConfig(1.0),
        "traps" to SpecConfig(1.0, gate = true),
        "flow" to SpecConfig(1.0, gate = true),
        "absorption" to SpecConfig(1.0),
        "delta" to SpecConfig(1.0),
        "bots" to SpecConfig(1.0),
        "vwap" to SpecConfig(0.25, gate = true, thresholds = mapOf("stretchSd" to 2.0)),
        "volume" to SpecConfig(0.25, thresholds = mapOf("climaxX" to 3.0, "dryX" to 0.5, "minMinutes" to 10.0)),
        "profile" to SpecConfig(0.25, gate = true, thresholds = mapOf("hvnNearPct" to 0.0015)),
        "oi" to SpecConfig(0.25, gate = true),
        "vix" to SpecConfig(0.25, gate = true, thresholds = mapOf("crushPct" to 8.0)),
        "momentum" to SpecConfig(0.25, thresholds = mapOf("fadePct" to 3.0, "lookbackSec" to 60.0, "boost" to 20.0)),
        "price" to SpecConfig(0.25, gate = true, thresholds = mapOf("giveBack" to 0.6, "minMfePct" to 0.15, "noProgressMin" to 30.0, "progressPct" to 0.05)),
        "time" to SpecConfig(0.25, gate = true, thresholds = mapOf("maxMin" to 120.0)),
        "gamma" to SpecConfig(0.25),
    )

    // ---- the state and the Chair ----------------------------------------------------------------------------------------

    /**
     * One trade's team memory: the manager's [core] state (target, lock, extensions, peak, the cooldown's times), since when
     * each specialist's EXIT vote has stood ([since]), since when the extension's agreement has stood ([extendSince], 0:
     * not now), the last look's [votes] (the board's memory) and the momentum [tape].
     */
    data class TeamState(
        val core: TradeManager.State,
        val since: Map<String, Long> = emptyMap(),
        val extendSince: Long = 0L,
        val votes: List<Vote> = emptyList(),
        val tape: List<Sample> = emptyList(),
        /** The index's minutes (a research committee's VW and MP read them; kept only for one). */
        val minutes: List<R12Rules.IndexMinute> = emptyList(),
    )

    fun start(t: TradeManager.ManagedTrade): TeamState = TeamState(TradeManager.start(t))

    /** A trade picked up again (after a restart) with its kept [core] state. */
    fun resume(core: TradeManager.State): TeamState = TeamState(core)

    /**
     * One look's outcome: the [decision] (the manager's own types), the next [state], every specialist's [votes] in the
     * committee's order, the [lead] (the vote the decision follows; null on HOLD), the [top] three reasons, the weighted EXIT
     * [score] against the quorum, and the votes worth posting on the findings bus ([posts]: new and strong).
     */
    data class Step(
        val decision: TradeManager.Decision, val state: TeamState, val votes: List<Vote>, val lead: Vote?, val top: List<Vote>,
        val score: Double, val posts: List<Vote>,
    ) {
        /** The specialists that voted for the decision (each one's report card is settled with the trade). */
        val voters: List<String> get() = when (decision) {
            is TradeManager.Decision.ExitEarly -> votes.filter { it.kind == Kind.EXIT }.map { it.specialist }
            is TradeManager.Decision.Extend -> votes.filter { it.kind == Kind.EXTEND }.map { it.specialist }
            is TradeManager.Decision.Trail -> votes.filter { it.proposedLock != null }.map { it.specialist }
            else -> emptyList()
        }
    }

    private const val TAPE_EVERY_MS = 10_000L
    private const val TAPE_KEEP_MS = 180_000L

    private fun taped(tape: List<Sample>, now: Long, px: Double?, fut: Double?): List<Sample> {
        if (px == null) return tape
        val last = tape.lastOrNull()
        if (last != null && now - last.atMs < TAPE_EVERY_MS && now >= last.atMs) return tape
        val out = ArrayList<Sample>(tape.size + 1)
        for (x in tape) if (now - x.atMs <= TAPE_KEEP_MS && x.atMs <= now) out += x
        out += Sample(now, px, fut)
        return out
    }

    private const val MINUTES_KEEP = 420

    /** The index minute [now] falls in updated with the latest price and VWAP (its close is the last one seen in it). */
    private fun minuted(ms: List<R12Rules.IndexMinute>, now: Long, fut: Double?, vw: Auction.Vwap?): List<R12Rules.IndexMinute> {
        if (fut == null || !(fut > 0)) return ms
        val start = R12Rules.col(now) * R12Rules.MINUTE_MS
        val x = R12Rules.IndexMinute(start, fut, vw?.vwap, vw?.sd)
        val last = ms.lastOrNull()
        if (last != null && last.startMs > start) return ms
        val out = ArrayList<R12Rules.IndexMinute>(minOf(ms.size + 1, MINUTES_KEEP))
        val keep = if (last != null && last.startMs == start) ms.size - 1 else ms.size
        for (i in maxOf(0, keep - MINUTES_KEEP + 1) until keep) out += ms[i]
        out += x
        return out
    }

    /** A vote's hold is kept per specialist and signal: a different signal from the same specialist starts its own hold. */
    private fun holdKey(v: Vote): String = v.specialist + ":" + (v.rule?.key ?: v.tag ?: "")

    /** The order the original rules were asked in (a tie between equal votes goes to the earlier one), then the rest. */
    private val PRIORITY: Map<String, Int> = IDS.withIndex().associate { it.value to it.index }

    /**
     * One look at [t] with [s] by the committee under [cfg]: every specialist votes (on the board, in order), then the Chair
     * decides - in the single manager's order: the trail, the lock hit, an exit (the quorum or a hard veto, each vote after its
     * hold), an extension (gates, supermajority, no objection, its hold, the cooldown, the lock that fits), else TRAIL or HOLD.
     */
    fun decide(t: TradeManager.ManagedTrade, s: TradeManager.Snapshot, st0: TeamState, cfg: ManagerConfig = ManagerConfig(t.family)): Step {
        val c0 = st0.core
        if (c0.exited) return Step(TradeManager.Decision.Hold, st0, st0.votes, null, emptyList(), 0.0, emptyList())
        val now = s.atMs
        val gapped = c0.lastEvalMs > 0 && now - c0.lastEvalMs > TradeManager.MAX_GAP_MS
        val since0 = if (gapped) emptyMap() else st0.since
        val extSince0 = if (gapped) 0L else st0.extendSince
        val px = s.premium?.takeIf { it != 0.0 && it.isFinite() }
        var peak = c0.peak
        if (px != null && px > peak) peak = px
        s.premiumHigh?.takeIf { it.isFinite() && it > peak }?.let { peak = it }
        var core = c0.copy(lastEvalMs = now, peak = peak)
        val flow = TradeManager.flowNow(s)
        val fut = s.futPrice ?: flow?.mid
        val side = if (t.side >= 0) 1 else -1
        val tape = taped(st0.tape, now, px, fut)
        val minutes = if (cfg.isResearch) minuted(st0.minutes, now, fut, s.vwap) else st0.minutes
        val hits = TradeManager.against(t, s)
        val view = MarketView(s, core, px, flow, fut, hits, TradeManager.room(t, s, core), tape, cfg, side, minutes)

        // Every specialist looks, in order, each one's vote on the board as it comes.
        val arr = arrayOfNulls<Vote>(SPECIALISTS.size)
        for (v in st0.votes) INDEX[v.specialist]?.let { arr[it] = v }
        val board = Board(arr)
        val votes = ArrayList<Vote>(SPECIALISTS.size)
        for ((i, sp) in SPECIALISTS.withIndex()) {
            val v = runCatching { sp.look(t, view, board) }.getOrElse { hold(sp.id, "could not read") }
            board.put(i, v); votes += v
        }
        val posts = votes.filter { v ->
            (v.kind == Kind.EXIT || v.kind == Kind.EXTEND) && v.strength >= cfg.postAt && st0.votes.firstOrNull { it.specialist == v.specialist }?.kind != v.kind
        }

        // The trail: once a lock stands (after the first extension) it follows new highs - the manager's own, or higher where a
        // specialist proposes it (never under breakeven plus charges). Up only.
        var trailed: Double? = null
        var trailLead: Vote? = null
        // (Only after an extension: a lock that stands for any other reason - a research lock - is not trailed at half the profit.)
        core.lock?.takeIf { core.extensions > 0 }?.let { lk ->
            var best = TradeManager.trail(t, peak)
            for (v in votes) {
                val p = v.proposedLock ?: continue
                if (!cfg.counts(v.specialist) || !p.isFinite() || p < t.entry + t.chargesPerUnit - 1e-9) continue
                if (p > best + 1e-9) { best = p; trailLead = v } else if (p >= best - 1e-9 && trailLead == null) trailLead = v
            }
            if (best > lk + 1e-9) { core = core.copy(lock = best); trailed = best }
        }
        // A research committee's own locks (VC, BE): from any counting member, raised only, never under the stop already there.
        if (cfg.isResearch) for (v in votes) {
            val p = v.proposedLock ?: continue
            if (!v.ownLock || !p.isFinite() || !cfg.counts(v.specialist)) continue
            val floor = maxOf(core.lock ?: Double.NEGATIVE_INFINITY, t.originalStop ?: Double.NEGATIVE_INFINITY)
            if (p > floor + 1e-9) { core = core.copy(lock = p); trailed = p; trailLead = v }
        }
        val lk = core.lock
        if (px != null && lk != null && px <= lk + 1e-9)
            return Step(TradeManager.Decision.ExitEarly(TradeManager.Rule.LOCK, "profit lock ${num(lk)} hit at ${num(px)}", mapOf("lock" to lk, "premium" to px)),
                TeamState(core.copy(exited = true), since0, 0L, votes, tape, minutes), votes, trailLead, top(votes, null), 0.0, posts)

        // The exits: each EXIT vote's hold; the weighted quorum of those that have lasted, or a hard veto.
        val since = HashMap<String, Long>()
        var against = false
        var score = 0.0
        var lead: Vote? = null
        var leadKey = Double.NEGATIVE_INFINITY
        var vetoLead: Vote? = null
        for (v in votes) {
            if (v.kind != Kind.EXIT) continue
            val key = holdKey(v)
            val from = since0[key] ?: now
            since[key] = from
            // (A research committee: only its members decide; the others - their hard vetoes too - are recorded only.)
            val decides = !cfg.isResearch || v.specialist in cfg.members
            if (v.strength >= cfg.exitFloor && decides) against = true
            if (now - from < v.needsHoldSec * 1000L) continue
            if (v.veto) { if (vetoLead == null && decides) vetoLead = v; continue }
            val w = cfg.weight(v.specialist)
            if (w <= 0) continue
            val x = w * v.strength / 100.0
            score += x
            if (x > leadKey + 1e-9) { leadKey = x; lead = v }
        }
        if (against) core = core.copy(lastAgainstMs = now)
        val exitBy = vetoLead ?: lead?.takeIf { score >= cfg.quorum - 1e-9 }
        if (exitBy != null) {
            val held = ((now - since.getValue(holdKey(exitBy))) / 1000).toDouble()
            val d = if (exitBy.rule != null) TradeManager.Decision.ExitEarly(exitBy.rule, exitBy.reason,
                    exitBy.evidence + mapOf("premium" to (px ?: Double.NaN), "heldSec" to held))
                else TradeManager.Decision.ExitEarly(TradeManager.Rule.TEAM, "${nameOf(exitBy.specialist)}: ${exitBy.reason}" +
                    (if (score > 0) " (the specialists' weight ${f1(score)} against the quorum ${f1(cfg.quorum)})" else ""),
                    exitBy.evidence + mapOf("premium" to (px ?: Double.NaN), "heldSec" to held, "score" to score))
            return Step(d, TeamState(core.copy(exited = true), since, 0L, votes, tape, minutes), votes, exitBy, top(votes, exitBy), score, posts)
        }

        // The extension: every gate agrees, a supermajority of the weighted votes, no objection; then its hold and the cooldown.
        // A research committee: only if its block allows one, every extension member agreeing and the premium 90% of the way.
        var extSince = 0L
        val agreed = cfg.extend && if (cfg.isResearch) researchExtendAgreed(votes, cfg) && view.room.near else extendAgreed(votes, cfg)
        // (The caps are the Chair's own, whatever the gates: a target with extensions left, and not near the square-off.)
        if (view.room.target && view.room.time && agreed && px != null) {
            extSince = if (extSince0 > 0) extSince0 else now
            val cool = (core.lastExtendMs <= 0L || now - core.lastExtendMs >= TradeManager.COOLDOWN_MS) &&
                (core.lastAgainstMs <= 0L || now - core.lastAgainstMs >= TradeManager.COOLDOWN_MS)
            if (cool && now - extSince >= cfg.extendHoldSec * 1000L) {
                val lv = TradeManager.levels(t, core, px, s.premiumAtr)
                val words = TradeManager.scopeWords(t, s) ?: if (cfg.isResearch) "the research's extension votes agree" to emptyMap<String, Double>() else null
                if (lv != null && words != null) {
                    val (nt, ns) = lv
                    val d = TradeManager.Decision.Extend(nt, ns, words.first, words.second + mapOf("premium" to px, "oldTarget" to (core.target ?: 0.0)))
                    val leadX = votes.filter { it.kind == Kind.EXTEND && (cfg.counts(it.specialist) || it.specialist in cfg.extendMembers) }
                        .maxByOrNull { maxOf(cfg.weight(it.specialist), if (it.specialist in cfg.extendMembers) 1.0 else 0.0) * it.strength }
                    return Step(d, TeamState(core.copy(target = nt, lock = ns, extensions = core.extensions + 1, lastExtendMs = now), since, 0L, votes, tape, minutes),
                        votes, leadX, top(votes, leadX), score, posts)
                }
            }
        }
        val decision = trailed?.let { TradeManager.Decision.Trail(it) } ?: TradeManager.Decision.Hold
        return Step(decision, TeamState(core, since, extSince, votes, tape, minutes), votes, if (trailed != null) trailLead else null,
            top(votes, if (trailed != null) trailLead else null), score, posts)
    }

    /** A research committee's extension: every one of its extension members votes EXTEND (R12: "quorum" 2 of its 2 votes). */
    fun researchExtendAgreed(votes: List<Vote>, cfg: ManagerConfig): Boolean =
        cfg.extendMembers.isNotEmpty() && cfg.extendMembers.all { id -> votes.any { it.specialist == id && it.kind == Kind.EXTEND } }

    /** Every gate agrees (a muted gate does not), a supermajority of the weighted votes, no EXIT at the floor, no block. */
    fun extendAgreed(votes: List<Vote>, cfg: ManagerConfig): Boolean {
        var yes = 0.0; var all = 0.0
        for (v in votes) {
            val gate = cfg.spec(v.specialist).gate
            if (gate && (v.kind != Kind.EXTEND || !cfg.counts(v.specialist))) return false
            if (v.blocksExtend || (v.kind == Kind.EXIT && v.strength >= cfg.exitFloor)) return false
            val w = cfg.weight(v.specialist) * v.strength
            if (w <= 0) continue
            if (v.kind == Kind.EXTEND) { yes += w; all += w } else if (v.kind == Kind.EXIT) all += w
        }
        return all > 0 && yes / all >= cfg.superMajority - 1e-9
    }

    /** The three reasons that matter most: the lead's, then the strongest votes that are not HOLD. */
    fun top(votes: List<Vote>, lead: Vote?): List<Vote> {
        val rest = votes.filter { it !== lead && it.kind != Kind.HOLD }
            .sortedWith(compareByDescending<Vote> { it.strength }.thenBy { PRIORITY[it.specialist] ?: Int.MAX_VALUE })
        return (listOfNotNull(lead) + rest).take(3)
    }

    // ---- words ------------------------------------------------------------------------------------------------------------

    /** A vote in one line: "VWAP: EXIT 45 - price back through VWAP (needs 60 s)". */
    fun voteLine(v: Vote, weight: Double? = null, muted: Boolean = false): String =
        "${nameOf(v.specialist)}: ${v.kind.name}" + (if (v.kind != Kind.HOLD) " ${v.strength}" else "") + " - ${v.reason}" +
            (if (v.needsHoldSec > 0 && v.kind == Kind.EXIT) " (needs ${v.needsHoldSec} s)" else "") +
            (if (v.veto) " [hard veto]" else "") + (weight?.let { " · weight ${String.format(Locale.ENGLISH, "%.2f", it)}" } ?: "") +
            (if (muted) " · muted" else "")

    /** The Chair's line: "Chair: EXIT (Order flow) - top reasons: ...; ...; ...". */
    fun chairLine(d: TradeManager.Decision, lead: Vote?, top: List<Vote>): String {
        val what = when (d) {
            is TradeManager.Decision.Hold -> "HOLD"
            is TradeManager.Decision.ExitEarly -> "EXIT"
            is TradeManager.Decision.Extend -> "EXTEND"
            is TradeManager.Decision.Trail -> "TRAIL"
        }
        return "Chair: $what" + (lead?.let { " (${nameOf(it.specialist)})" } ?: "") +
            (if (top.isEmpty()) " - nothing stands out" else " - top reasons: " + top.joinToString("; ") { "${nameOf(it.specialist)}: ${it.reason}" })
    }

    private val WORDS: List<Pair<String, Regex>> = listOf(
        "flow" to Regex(" (order flow|flow) "), "delta" to Regex(" (cumulative delta|delta|divergence|cvd) "),
        "absorption" to Regex(" (absorption|absorb|absorbing) "), "traps" to Regex(" (traps?|spoof|spoofing|stop hunt|pulls?) "),
        "vwap" to Regex(" vwap "), "volume" to Regex(" (volume|climax) "),
        "profile" to Regex(" (market profile|profile|value area|poc) "), "oi" to Regex(" (oi|open interest) "),
        "vix" to Regex(" (vix|iv|volatility) "), "momentum" to Regex(" (momentum|roc) "),
        "price" to Regex(" (price action|mfe|give ?back|price) "), "time" to Regex(" (time and theta|theta|time) "),
        "gamma" to Regex(" (gamma|gex) "), "news" to Regex(" news "), "bots" to Regex(" (bots?|consensus|findings?) "),
        "data" to Regex(" (data health|data|feed) "),
    )

    /** The specialist named in normalised words [t] (" mute the oi specialist "), with the words that named it. */
    fun specialistIn(t: String): Pair<String, String>? {
        // The words nearest before "specialist" name it ("the vix specialist for vix divergence"); else the first named.
        val at = t.indexOf(" specialist").let { if (it < 0) t.length else it }
        var best: Pair<String, MatchResult>? = null
        for ((id, rx) in WORDS) for (m in rx.findAll(t)) {
            val b = best?.second
            val better = when {
                b == null -> true
                m.range.last <= at && b.range.last <= at -> m.range.first > b.range.first
                m.range.last <= at -> true
                b.range.last <= at -> false
                else -> m.range.first < b.range.first
            }
            if (better) best = id to m
        }
        return best?.let { it.first to it.second.value.trimEnd() }
    }
}
