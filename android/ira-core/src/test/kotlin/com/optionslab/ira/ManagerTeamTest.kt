package com.optionslab.ira

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The trade manager's team ([ManagerTeam]): the default committee decides exactly as the single manager did on every one of
 * TradeManagerTest's scenarios (and 300 random trades), each specialist's own reading, the board, the Chair's quorum, hard
 * vetoes, supermajority and trail, muting, and the invariants under any weights.
 */
class ManagerTeamTest {
    private val t0 = 1_760_000_000_000L
    private val M = TradeManager
    private val T = ManagerTeam

    private fun trade(side: Int = 1, entry: Double = 100.0, stop: Double? = 70.0, target: Double? = 160.0, squareOff: Long = t0 + 3 * 3_600_000L,
                      account: TradeManager.Account = TradeManager.Account.PAPER, charges: Double = 1.0, qty: Int = 75) =
        TradeManager.ManagedTrade("pine:4", "pine:4:x", "Pine · EMA", "NIFTY", side, "NIFTY25OCT25000CE", entry, qty, stop, target,
            TradeManager.Caps(squareOff), account, t0, charges)

    private fun read(at: Long, side: OrderFlow.Side = OrderFlow.Side.BALANCED, strength: Int = 50, cvd60: Double = 0.0, mid: Double = 25_000.0,
                     flags: Set<TrapGuard.Trap> = emptySet(), huntDir: Int = 0, absorbAt: Double = Double.NaN, absorbBy: Int = 0,
                     buildUp: OrderFlow.BuildUp? = null, oi: Long? = null, warm: Boolean = true): OrderFlow.Read {
        val buyers = when (side) { OrderFlow.Side.BUYERS -> strength; OrderFlow.Side.SELLERS -> 100 - strength; else -> 50 }
        return OrderFlow.Read(name = "NIFTY", atSec = at / 1000, side = side, strength = strength, buyers = buyers, warm = warm, mid = mid,
            ofi10 = 0.0, ofi60 = 0.0, ofi300 = 0.0, cvd10 = 0.0, cvd60 = cvd60, cvd300 = 0.0, depth = 0.0, queue = 0.0, ratio = Double.NaN,
            ce60 = 0.0, pe60 = 0.0, optionNet = Double.NaN, buildUp = buildUp, oiChange = oi, z = emptyMap(), flags = flags, huntDir = huntDir,
            absorbAt = absorbAt, absorbBy = absorbBy)
    }

    private val vw = Auction.Vwap(25_000.0, 20.0, null, null)

    private fun snap(at: Long, premium: Double? = 120.0, flow: OrderFlow.Read? = null, fut: Double? = 25_000.0, vwap: Auction.Vwap? = vw,
                     vah: Double? = 25_010.0, val_: Double? = 24_980.0, div: Auction.Divergence? = null, brain: MarketBrain.Context? = null,
                     findings: List<Findings.Finding> = emptyList(), high: Double? = null, atr: Double? = null, vix: Double? = null) =
        TradeManager.Snapshot(at, premium, high, atr, null, flow, fut, vwap, vah, val_, div, vix, brain, findings)

    private fun room(now: Long, px: Double = 156.0, high: Double? = null) = snap(now, premium = px, high = high, atr = 4.0,
        flow = read(now, OrderFlow.Side.BUYERS, 75, cvd60 = 8_000.0, mid = 25_030.0, buildUp = OrderFlow.BuildUp.LONG_BUILDUP, oi = 12_000),
        fut = 25_030.0)

    // ---- the default committee is the single manager ---------------------------------------------------------------------

    /**
     * Runs the single manager and the default committee side by side on the same looks: the same decision every second, the
     * same target, lock, extensions and exit. Returns the decisions that were not HOLD.
     */
    private fun same(t: TradeManager.ManagedTrade, secs: Int, from: Long = t0, st0: TradeManager.State = M.start(t),
                     snapAt: (Long) -> TradeManager.Snapshot): List<TradeManager.Decision> {
        var a = st0
        var b = T.resume(st0)
        val out = ArrayList<TradeManager.Decision>()
        for (i in 0..secs) {
            val now = from + i * 1000L
            val s = snapAt(now)
            val x = M.decide(t, s, a)
            val y = T.decide(t, s, b)
            assertEquals(x.decision, y.decision, "at ${i}s")
            assertEquals(x.state.target, y.state.core.target, "target at ${i}s")
            assertEquals(x.state.lock, y.state.core.lock, "lock at ${i}s")
            assertEquals(x.state.extensions, y.state.core.extensions, "extensions at ${i}s")
            assertEquals(x.state.exited, y.state.core.exited, "exited at ${i}s")
            a = x.state; b = y.state
            if (x.decision !is TradeManager.Decision.Hold) out += x.decision
            if (a.exited) break
        }
        return out
    }

    @Test fun theDefaultCommitteeDecidesAsTheSingleManagerOnEveryOriginalScenario() {
        val t = trade()
        // The early exits, one by one, and the cases where nothing happens.
        val sellers = { now: Long -> snap(now, flow = read(now, OrderFlow.Side.SELLERS, 70, cvd60 = -5_000.0, mid = 24_990.0), fut = 24_990.0) }
        assertEquals(TradeManager.Rule.OPPOSITE_FLOW, (same(t, 120, snapAt = sellers).single() as TradeManager.Decision.ExitEarly).rule)
        assertTrue(same(t, 120) { now -> snap(now, flow = read(now, OrderFlow.Side.SELLERS, 70, cvd60 = 3_000.0, mid = 24_990.0), fut = 24_990.0) }.isEmpty())
        assertTrue(same(t, 120) { now -> snap(now, flow = read(now, OrderFlow.Side.SELLERS, 70, cvd60 = -3_000.0, mid = 25_005.0), fut = 25_005.0) }.isEmpty())
        assertTrue(same(t, 120) { now -> snap(now, flow = read(now, OrderFlow.Side.SELLERS, 55, cvd60 = -3_000.0, mid = 24_990.0), fut = 24_990.0) }.isEmpty())
        same(trade(side = -1), 120) { now -> snap(now, flow = read(now, OrderFlow.Side.BUYERS, 70, cvd60 = 5_000.0, mid = 25_010.0), fut = 25_010.0) }
        same(t, 200) { now -> if ((now - t0) / 1000 == 50L) snap(now, flow = read(now)) else sellers(now) }
        // A gap of more than 20 s starts every hold again.
        run {
            var a = M.start(t); var b = T.resume(a)
            for (at in listOf(t0, t0 + 65_000L, t0 + 70_000L)) {
                val x = M.decide(t, sellers(at), a); val y = T.decide(t, sellers(at), b)
                assertEquals(x.decision, y.decision); a = x.state; b = y.state
            }
        }
        same(t, 90) { now -> snap(now, flow = read(now), div = Auction.Divergence.BEARISH) }
        same(t, 90) { now -> snap(now, flow = read(now, OrderFlow.Side.BUYERS, 60, cvd60 = 100.0), div = Auction.Divergence.BEARISH) }
        same(t, 90) { now -> snap(now, flow = read(now), div = Auction.Divergence.BULLISH) }
        same(t, 60) { now -> snap(now, flow = read(now, absorbAt = 25_002.0, absorbBy = -1)) }
        same(t, 60) { now -> snap(now, flow = read(now, absorbAt = 25_200.0, absorbBy = -1)) }
        same(t, 60) { now -> snap(now, flow = read(now, absorbAt = 25_002.0, absorbBy = 1)) }
        same(t, 30) { now -> snap(now, flow = read(now, flags = setOf(TrapGuard.Trap.FAILED_BREAK), huntDir = 1)) }
        same(t, 30) { now -> snap(now, flow = read(now, flags = setOf(TrapGuard.Trap.STOP_HUNT), huntDir = -1)) }
        val w = MarketBrain.Window("RBI policy", t0 + 150_000L, t0 + 750_000L)
        same(t, 60) { now -> snap(now, brain = MarketBrain.Context(windows = listOf(w))) }
        same(t, 60) { now -> snap(now, brain = MarketBrain.Context(windows = listOf(w.copy(scope = MarketBrain.EIA_SCOPE)))) }
        val f = { who: String, dir: Int, s: Int -> Findings.Finding(who, Findings.Kind.BREAK, "NIFTY", dir, null, s, t0) }
        val against = listOf(f("ORB", -1, 70), f("Liquidity 15+5", -1, 80), f("order flow", -1, 65))
        same(t, 60) { now -> snap(now, findings = against) }
        for (extra in listOf(f("ORB2", -1, 40), f("Pine #4", -1, 90), f(TradeManager.WHO, -1, 90), f("trade manager · VWAP", -1, 90)))
            assertTrue(same(t, 60) { now -> snap(now, findings = against.take(2) + extra) }.isEmpty(), extra.who)
        val bad = { now: Long, px: Double -> snap(now, premium = px, flow = read(now, flags = setOf(TrapGuard.Trap.UNRELIABLE), warm = false)) }
        same(t, 30) { now -> bad(now, 95.0) }
        same(t, 30) { now -> bad(now, 78.0) }
        val solo = t.copy(strategyId = "solo", originalStop = null, originalTarget = null, stopUnderlying = 24_900.0, entryUnderlying = 25_000.0)
        same(solo, 30) { now -> bad(now, 90.0).copy(underlyingPrice = 24_920.0) }
        same(solo, 30) { now -> bad(now, 90.0).copy(underlyingPrice = 24_990.0) }
        same(t, 90) { now -> snap(now, flow = read(now - 10_000L, OrderFlow.Side.SELLERS, 70, cvd60 = -5_000.0, mid = 24_990.0), fut = 24_990.0) }
        same(t, 90) { now -> snap(now, flow = read(now, OrderFlow.Side.SELLERS, 70, cvd60 = -5_000.0, mid = 24_990.0, warm = false), fut = 24_990.0) }
    }

    @Test fun theDefaultCommitteeExtendsAndLocksAsTheSingleManager() {
        val t = trade()
        val e = same(t, 60) { now -> room(now) }.first()
        assertIs<TradeManager.Decision.Extend>(e)
        assertEquals(172.0, e.newTarget, 1e-9); assertEquals(145.0, e.newStop, 1e-9)
        for (f in listOf<(Long) -> TradeManager.Snapshot>(
            { now -> room(now, px = 140.0) }, { now -> room(now).copy(vwap = null) }, { now -> room(now).copy(valueHigh = 25_040.0) },
            { now -> room(now).copy(flow = read(now, OrderFlow.Side.BUYERS, 75, cvd60 = 8_000.0, mid = 25_030.0, buildUp = OrderFlow.BuildUp.SHORT_COVERING, oi = -500)) },
            { now -> room(now).copy(flow = read(now, OrderFlow.Side.BUYERS, 75, cvd60 = 8_000.0, mid = 25_030.0, flags = setOf(TrapGuard.Trap.NO_EXECUTED), buildUp = OrderFlow.BuildUp.LONG_BUILDUP, oi = 1)) },
            { now -> room(now).copy(flow = read(now, OrderFlow.Side.BUYERS, 75, cvd60 = 8_000.0, mid = 25_030.0, flags = setOf(TrapGuard.Trap.PULL_BID), buildUp = OrderFlow.BuildUp.LONG_BUILDUP, oi = 1)) },
            { now -> room(now).copy(vixChangePct = 4.0) }))
            assertTrue(same(t, 60, snapAt = f).isEmpty())
        assertTrue(same(t.copy(originalTarget = null), 60) { now -> room(now) }.isEmpty())
        assertTrue(same(t, 60, from = t.caps.squareOffMs - 10 * 60_000L) { now -> room(now) }.isEmpty())
        // Two extensions with the cooldown, the premium following the newest target.
        var px = 156.0
        var a = M.start(t); var b = T.resume(a)
        var ext = 0
        for (i in 0..600) {
            val now = t0 + i * 1000L
            a.target?.let { tg -> px = maxOf(px, t.entry + 0.95 * (tg - t.entry)) }
            val x = M.decide(t, room(now, px), a); val y = T.decide(t, room(now, px), b)
            assertEquals(x.decision, y.decision, "at $i"); a = x.state; b = y.state
            if (x.decision is TradeManager.Decision.Extend) ext++
        }
        assertEquals(2, ext)
        // A rule seen within the last minute holds an extension back.
        val s0 = M.start(t)
        val x0 = M.decide(t, room(t0).copy(divergence = Auction.Divergence.BEARISH, flow = read(t0)), s0)
        val y0 = T.decide(t, room(t0).copy(divergence = Auction.Divergence.BEARISH, flow = read(t0)), T.resume(s0))
        assertEquals(x0.decision, y0.decision)
        assertEquals(x0.state.lastAgainstMs, y0.state.core.lastAgainstMs)
        // The trail and the lock exit.
        var c = M.start(t); var d = T.resume(c)
        for (i in 0..40) { c = M.decide(t, room(t0 + i * 1000L), c).state; d = T.decide(t, room(t0 + i * 1000L), d).state }
        for ((i, s) in listOf(snap(t0 + 41_000L, premium = 165.0, high = 170.0), snap(t0 + 42_000L, premium = 190.0, high = 200.0),
            snap(t0 + 43_000L, premium = 180.0, high = 185.0), snap(t0 + 44_000L, premium = 149.0), snap(t0 + 45_000L, premium = 100.0)).withIndex()) {
            val x = M.decide(t, s, c); val y = T.decide(t, s, d)
            assertEquals(x.decision, y.decision, "trail step $i"); assertEquals(x.state.lock, y.state.core.lock)
            c = x.state; d = y.state
        }
        assertTrue(d.core.exited)
    }

    @Test fun theDefaultCommitteeMatchesTheSingleManagerOnRandomTrades() {
        val rnd = Random(20261010)
        var decisions = 0
        repeat(300) {
            val entry = 20.0 + rnd.nextDouble() * 400
            val dist = entry * (0.1 + rnd.nextDouble())
            val t = trade(side = if (rnd.nextBoolean()) 1 else -1, entry = entry, stop = if (rnd.nextInt(5) == 0) null else entry * (0.5 + rnd.nextDouble() * 0.45),
                target = entry + dist, charges = rnd.nextDouble() * 3, qty = 25 * (1 + rnd.nextInt(4)))
            var px = entry
            val blocks = BooleanArray(21) { rnd.nextInt(3) != 0 }
            val snaps = (0..900).map { i ->
                val now = t0 + i * 1000L
                val good = blocks[i / 45]
                px = (if (good) px * (1 + rnd.nextDouble() * 0.01) else px * (1 + (rnd.nextDouble() - 0.5) * 0.03)).coerceAtLeast(0.05)
                val dir = t.side
                val flow = if (good) read(now, if (dir > 0) OrderFlow.Side.BUYERS else OrderFlow.Side.SELLERS, 60 + rnd.nextInt(40), cvd60 = dir * 5_000.0,
                    mid = 25_000.0 + dir * 40, buildUp = if (dir > 0) OrderFlow.BuildUp.LONG_BUILDUP else OrderFlow.BuildUp.SHORT_BUILDUP, oi = 1_000)
                    else read(now, OrderFlow.Side.entries[rnd.nextInt(3)], rnd.nextInt(100), cvd60 = rnd.nextDouble() * 10_000 - 5_000, mid = 25_000.0 + rnd.nextDouble() * 80 - 40)
                snap(now, premium = px, high = px * (1 + rnd.nextDouble() * 0.02), atr = if (rnd.nextBoolean()) entry * rnd.nextDouble() * 0.1 else null,
                    flow = flow, fut = flow.mid, vah = 25_020.0, val_ = 24_980.0)
            }
            decisions += same(t, 900) { now -> snaps[((now - t0) / 1000).toInt()] }.size
        }
        assertTrue(decisions >= 50, "the random trades decided $decisions times")
    }

    // ---- the specialists, each reading its own family ---------------------------------------------------------------------

    private fun votes(t: TradeManager.ManagedTrade, s: TradeManager.Snapshot, st: ManagerTeam.TeamState = T.start(t), cfg: ManagerConfig = ManagerConfig("pine")) =
        T.decide(t, s, st, cfg).votes.associateBy { it.specialist }

    @Test fun sixteenSpecialistsEachWithItsOwnFamily() {
        assertEquals(16, T.SPECIALISTS.size)
        assertEquals(listOf("data", "news", "traps", "flow", "absorption", "delta", "bots", "vwap", "volume", "profile", "oi", "vix", "momentum", "price", "time", "gamma"), T.IDS)
        val t = trade()
        // An extension's look: every gate says EXTEND, with its reason.
        val v = votes(t, room(t0))
        for (g in listOf("flow", "traps", "vwap", "profile", "oi", "vix", "price", "time")) assertEquals(ManagerTeam.Kind.EXTEND, v.getValue(g).kind, g)
        assertTrue("buyers 75" in v.getValue("flow").reason, v.getValue("flow").reason)
        assertTrue("SD beyond VWAP" in v.getValue("vwap").reason)
        // VWAP recross; Profile under the POC; OI against; VIX spike and IV crush; Gamma flip; Time in the expiry hour.
        val down = snap(t0, premium = 98.0, flow = read(t0, buildUp = OrderFlow.BuildUp.SHORT_BUILDUP, oi = 500), fut = 24_990.0)
            .copy(poc = 25_000.0, gammaNet = -1e9, zeroGamma = 24_995.0, ivChangePct = -12.0,
                brain = MarketBrain.Context(windows = listOf(MarketBrain.Window("NIFTY expiry hour", t0 - 60_000L, t0 + 3_000_000L, MarketBrain.Kind.EXPIRY_HOUR, setOf("NIFTY")))))
        val d = votes(t, down)
        for (id in listOf("vwap", "profile", "oi", "vix", "gamma", "time")) assertEquals(ManagerTeam.Kind.EXIT, d.getValue(id).kind, "$id: ${d.getValue(id)}")
        assertTrue("short build-up" in d.getValue("oi").reason)
        assertTrue("IV crush" in d.getValue("vix").reason)
        assertEquals(ManagerTeam.Kind.HOLD, votes(t, room(t0).copy(vixChangePct = 5.0)).getValue("vix").kind)
        // Positive gamma: no help to extend.
        assertEquals("pinning", votes(t, room(t0).copy(gammaNet = 5e8)).getValue("gamma").tag)
        // Traps: a pull on the long's side is an exit vote, not a veto; the original trap rule is a hard veto.
        assertEquals(40, votes(t, snap(t0, flow = read(t0, flags = setOf(TrapGuard.Trap.PULL_BID)))).getValue("traps").strength)
        val trap = votes(t, snap(t0, flow = read(t0, flags = setOf(TrapGuard.Trap.FAILED_BREAK), huntDir = 1))).getValue("traps")
        assertTrue(trap.veto && trap.rule == TradeManager.Rule.TRAP && trap.needsHoldSec == 15)
        // Data health: a stale option price holds every extension back.
        assertTrue(votes(t, room(t0).copy(premiumAgeMs = 30_000L)).getValue("data").blocksExtend)
        // Bots: three strong findings its way.
        val f = { who: String -> Findings.Finding(who, Findings.Kind.BREAK, "NIFTY", 1, null, 70, t0) }
        assertEquals(ManagerTeam.Kind.EXTEND, votes(t, snap(t0, findings = listOf(f("ORB"), f("Liquidity 15+5"), f("order flow")))).getValue("bots").kind)
        // Price action: giving back most of the best, and no progress in time.
        val st = T.start(t).copy(core = M.start(t).copy(peak = 150.0))
        assertEquals("giveback", votes(t, snap(t0, premium = 115.0), st).getValue("price").tag)
        assertEquals("stalled", votes(t, snap(t0 + 40 * 60_000L, premium = 101.0)).getValue("price").tag)
    }

    @Test fun volumeReadsAClimaxAndDryingUp() {
        val t = trade()
        val nowSec = t0 / 1000
        fun mins(last: List<Long>) = ((0 until 15).map { (nowSec / 60 - 20 + it) * 60 to 1_000L } + last.mapIndexed { i, v -> (nowSec / 60 - 5 + i) * 60 to v })
        val climax = votes(t, snap(t0, premium = 130.0, flow = read(t0)).copy(minuteVolumes = mins(listOf(1_000, 1_000, 1_000, 1_000, 5_000))))
        assertEquals("climax", climax.getValue("volume").tag, climax.getValue("volume").reason)
        val dry = votes(t, snap(t0, flow = read(t0)).copy(minuteVolumes = mins(listOf(1_000, 1_000, 300, 300, 300))))
        assertEquals("drying", dry.getValue("volume").tag)
        assertEquals(50, dry.getValue("volume").strength, "at the floor: it holds an extension back")
        assertEquals(ManagerTeam.Kind.HOLD, votes(t, snap(t0).copy(minuteVolumes = mins(listOf(1_000)).take(5))).getValue("volume").kind, "too few minutes")
    }

    @Test fun theBoardLetsMomentumReadVwapAndVolume() {
        val t = trade()
        // A stretched move with a volume climax, then the premium fading: Momentum's exit vote is stronger and says why.
        val nowSec = t0 / 1000
        val mins = (0 until 15).map { (nowSec / 60 - 20 + it) * 60 to 1_000L } + listOf((nowSec / 60 - 2) * 60 to 6_000L)
        val stretched = Auction.Vwap(25_000.0, 10.0, null, null)
        var st = T.start(t)
        var last: ManagerTeam.Step? = null
        for (i in 0..70) {
            val now = t0 + i * 1000L
            val px = if (i < 60) 150.0 else 140.0
            val fut = if (i < 60) 25_040.0 else 25_030.0
            val s = snap(now, premium = px, flow = read(now), fut = fut, vwap = stretched).copy(minuteVolumes = mins.map { (it.first + i) to it.second })
            last = T.decide(t, s, st); st = last.state
        }
        val mom = last!!.votes.first { it.specialist == "momentum" }
        assertEquals(ManagerTeam.Kind.EXIT, mom.kind, mom.reason)
        assertEquals(60, mom.strength)
        assertTrue("VWAP and Volume flag exhaustion too" in mom.reason, mom.reason)
        // Without the others' flags: the plain 40.
        var st2 = T.start(t)
        var l2: ManagerTeam.Step? = null
        for (i in 0..70) {
            val now = t0 + i * 1000L
            l2 = T.decide(t, snap(now, premium = if (i < 60) 150.0 else 140.0, flow = read(now), fut = if (i < 60) 25_005.0 else 25_003.0), st2); st2 = l2.state
        }
        assertEquals(40, l2!!.votes.first { it.specialist == "momentum" }.strength)
    }

    // ---- the Chair --------------------------------------------------------------------------------------------------------

    @Test fun theNewSpecialistsAloneNeedFourToReachTheQuorum() {
        val t = trade()
        // VWAP recross, Profile under the POC, OI unwinding, Gamma flip: 4 x 0.25 x ~40 = 0.4 < 1 - no exit by default.
        val s = { now: Long -> snap(now, premium = 105.0, flow = read(now, buildUp = OrderFlow.BuildUp.LONG_UNWINDING, oi = -100), fut = 24_990.0)
            .copy(poc = 25_000.0, gammaNet = -1.0, zeroGamma = 24_995.0) }
        var st = T.start(t)
        for (i in 0..120) { val x = T.decide(t, s(t0 + i * 1000L), st); assertIs<TradeManager.Decision.Hold>(x.decision, "at $i"); st = x.state }
        // With the research's weights (each at 1.0 and a quorum of 1.5): out once every hold has passed, led by the strongest.
        var cfg = ManagerConfig("pine", quorum = 1.5)
        for (id in listOf("vwap", "profile", "oi", "gamma")) cfg = cfg.with(id) { it.copy(weight = 1.0) }
        var st2 = T.start(t)
        var out: Pair<Int, ManagerTeam.Step>? = null
        for (i in 0..120) { val x = T.decide(t, s(t0 + i * 1000L), st2, cfg); st2 = x.state; if (x.decision is TradeManager.Decision.ExitEarly) { out = i to x; break } }
        assertNotNull(out)
        assertEquals(60, out.first, "every one of them holds for 60 s")
        val e = out.second.decision as TradeManager.Decision.ExitEarly
        assertEquals(TradeManager.Rule.TEAM, e.rule)
        assertTrue(e.reason.startsWith("VWAP: price"), e.reason)
        assertEquals("vwap", out.second.lead!!.specialist)
        assertTrue(out.second.voters.containsAll(listOf("vwap", "profile", "oi", "gamma")))
        assertEquals(3, out.second.top.size)
        assertTrue(T.chairLine(e, out.second.lead, out.second.top).startsWith("Chair: EXIT (VWAP) - top reasons: VWAP:"))
    }

    @Test fun aHardVetoStandsWhateverTheWeightsAndAMuteNeverRemovesIt() {
        val t = trade()
        val w = MarketBrain.Window("RBI policy", t0 + 60_000L, t0 + 660_000L)
        var cfg = ManagerConfig("pine", quorum = 10.0).with("news") { it.copy(weight = 0.0, enabled = false) }
        val x = T.decide(t, snap(t0, brain = MarketBrain.Context(windows = listOf(w))), T.start(t), cfg)
        val e = assertIs<TradeManager.Decision.ExitEarly>(x.decision)
        assertEquals(TradeManager.Rule.NEWS, e.rule)
        // A muted non-veto specialist: its exit no longer counts (the original rules hold the trade instead).
        cfg = ManagerConfig("pine").with("flow") { it.copy(enabled = false) }
        var st = T.start(t)
        for (i in 0..120) {
            val now = t0 + i * 1000L
            val y = T.decide(t, snap(now, flow = read(now, OrderFlow.Side.SELLERS, 70, cvd60 = -5_000.0, mid = 24_990.0), fut = 24_990.0), st, cfg)
            assertFalse(y.decision is TradeManager.Decision.ExitEarly, "at $i: ${y.decision}"); st = y.state
        }
    }

    @Test fun aMutedGateHoldsExtensionsBackAndAnObjectionToo() {
        val t = trade()
        fun extends(cfg: ManagerConfig, f: (Long) -> TradeManager.Snapshot = { room(it) }): Boolean {
            var st = T.start(t)
            for (i in 0..60) { val x = T.decide(t, f(t0 + i * 1000L), st, cfg); st = x.state; if (x.decision is TradeManager.Decision.Extend) return true }
            return false
        }
        assertTrue(extends(ManagerConfig("pine")))
        assertFalse(extends(ManagerConfig("pine").with("oi") { it.copy(enabled = false) }), "a muted gate: no extension")
        assertFalse(extends(ManagerConfig("pine")) { room(it).copy(premiumAgeMs = 60_000L) }, "a stale price: DataHealth blocks it")
        // The supermajority: an exit vote under the floor from a specialist that is not a gate (Gamma below zero gamma).
        val flip = { now: Long -> room(now).copy(gammaNet = -1.0, zeroGamma = 25_040.0) }
        assertTrue(extends(ManagerConfig("pine"), flip), "75%: the agreeing votes far outweigh it")
        val strict = ManagerConfig("pine", superMajority = 1.0)
        assertTrue(extends(strict))
        assertFalse(extends(strict, flip), "100%: one exit vote is enough to hold it back")
        // Not a gate any more (the research's choice): OI's agreement is not needed.
        assertTrue(extends(ManagerConfig("pine").with("oi") { it.copy(gate = false) }) { now ->
            room(now).copy(flow = read(now, OrderFlow.Side.BUYERS, 75, cvd60 = 8_000.0, mid = 25_030.0, buildUp = OrderFlow.BuildUp.NEUTRAL, oi = 0)) })
    }

    @Test fun aSpecialistsLockCanOnlyRaiseTheTrailNeverLowerIt() {
        val t = trade()
        var st = T.start(t)
        for (i in 0..40) st = T.decide(t, room(t0 + i * 1000L), st).state
        assertEquals(145.0, st.core.lock!!, 1e-9)
        val x = T.decide(t, snap(t0 + 41_000L, premium = 190.0, high = 200.0), st)
        assertIs<TradeManager.Decision.Trail>(x.decision)
        assertEquals(150.0, x.state.core.lock!!, 1e-9)
        assertEquals("price", x.lead?.specialist)
        assertEquals(listOf("price"), x.voters)
    }

    @Test fun findingsArePostedOnceWhenAVoteTurns() {
        val t = trade()
        var st = T.start(t)
        val posted = ArrayList<String>()
        for (i in 0..30) {
            val now = t0 + i * 1000L
            val x = T.decide(t, snap(now, flow = read(now, flags = setOf(TrapGuard.Trap.FAILED_BREAK), huntDir = 1)), st)
            posted += x.posts.map { it.specialist }; st = x.state
        }
        assertEquals(listOf("traps"), posted)
    }

    /** Property: under any weights, quorum and supermajority, money at risk never rises and the lock never falls. */
    @Test fun anyConfigKeepsTheInvariants() {
        val rnd = Random(7)
        var extended = 0
        repeat(150) { n ->
            val entry = 50.0 + rnd.nextDouble() * 300
            val t = trade(entry = entry, stop = entry * 0.7, target = entry * (1.2 + rnd.nextDouble()), charges = rnd.nextDouble() * 2)
            var cfg = ManagerConfig("pine", quorum = 0.25 + rnd.nextDouble() * 3, superMajority = 0.5 + rnd.nextDouble() * 0.5,
                exitFloor = 1 + rnd.nextInt(100), extendHoldSec = rnd.nextInt(100))
            for (id in T.IDS) {
                val gate = T.DEFAULT_SPECS.getValue(id).gate && rnd.nextInt(4) != 0
                cfg = cfg.with(id) { it.copy(weight = rnd.nextDouble() * 3, enabled = gate || rnd.nextInt(6) != 0, gate = gate) }
            }
            cfg = cfg.bounded()
            assertTrue(cfg.extendHoldSec >= 30)
            val original = M.moneyAtRisk(t, t.originalStop)
            var st = T.start(t)
            var px = entry
            var lastLock: Double? = null
            for (i in 0..600) {
                val now = t0 + i * 1000L
                px = (px * (1 + (rnd.nextDouble() - 0.35) * 0.02)).coerceAtLeast(0.05)
                val flow = read(now, OrderFlow.Side.BUYERS, 60 + rnd.nextInt(40), cvd60 = 5_000.0, mid = 25_040.0, buildUp = OrderFlow.BuildUp.LONG_BUILDUP, oi = 100)
                val x = T.decide(t, snap(now, premium = px, high = px * 1.01, atr = entry * 0.02, flow = flow, fut = 25_040.0, vah = 25_020.0), st, cfg)
                val d = x.decision
                if (d is TradeManager.Decision.Extend) {
                    extended++
                    assertTrue(d.newStop >= t.entry + t.chargesPerUnit - 1e-9, "#$n: the lock at breakeven plus charges or better")
                    assertTrue(d.newStop < px && M.moneyAtRisk(t, d.newStop) <= original + 1e-9, "#$n")
                }
                x.state.core.lock?.let { l -> lastLock?.let { assertTrue(l >= it - 1e-9, "#$n: the lock fell") }; lastLock = l
                    assertTrue(l >= t.entry + t.chargesPerUnit - 1e-9) }
                assertTrue(x.state.core.extensions <= TradeManager.MAX_EXTENSIONS)
                st = x.state
                if (st.core.exited) break
            }
        }
        assertTrue(extended > 5, "$extended")
    }

    // ---- Jarvis's words ---------------------------------------------------------------------------------------------------

    @Test fun theQuestionsAboutTheSpecialists() {
        val which = M.asked("which specialist made Solo exit?")!!
        assertEquals(TradeManager.TeamKind.WHICH, which.team!!.kind); assertEquals("solo", which.family)
        val how = M.asked("how are the manager's specialists doing?")!!
        assertEquals(TradeManager.TeamKind.HOW, how.team!!.kind); assertNull(how.family)
        val mute = M.asked("mute the OI specialist for Pine")!!
        assertEquals(TradeManager.TeamAsk(TradeManager.TeamKind.MUTE, "oi"), mute.team); assertEquals("pine", mute.family)
        assertEquals(TradeManager.TeamAsk(TradeManager.TeamKind.UNMUTE, "vix"), M.asked("unmute the VIX specialist for VIX divergence")!!.team)
        assertEquals("vixdiv", M.asked("unmute the VIX specialist for VIX divergence")!!.family)
        assertEquals("vwap", M.asked("mute the vwap specialist for solo")!!.team!!.specialist)
        assertNull(M.asked("should I ask a tax specialist"), "not the manager's")
        // The original questions read as before.
        assertEquals(TradeManager.Ask(false, null), M.asked("how is the trade manager doing?"))
        assertEquals(TradeManager.Ask(true, "solo"), M.asked("Why did Solo exit early?"))
        val audit = CoverageTest()
        for (q in listOf("which specialist made Solo exit?", "how are the manager's specialists doing?", "mute the OI specialist for Pine"))
            assertEquals("TradeManager", audit.feature(q), q)
    }
}
