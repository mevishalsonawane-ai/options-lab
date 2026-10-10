package com.optionslab.ira

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The trade manager ([TradeManager]): each early-exit rule, the extension with its ratcheting lock, hysteresis, caps, the record. */
class TradeManagerTest {
    private val t0 = 1_760_000_000_000L
    private val M = TradeManager

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

    /** Runs [snapAt] once a second from t0 for [secs] seconds; the first non-HOLD decision and the state. */
    private fun run(t: TradeManager.ManagedTrade, secs: Int, st0: TradeManager.State = M.start(t), from: Long = t0,
                    snapAt: (Long) -> TradeManager.Snapshot): Pair<TradeManager.Decision, Long>? {
        var st = st0
        for (i in 0..secs) {
            val now = from + i * 1000L
            val s = M.decide(t, snapAt(now), st)
            st = s.state
            if (s.decision !is TradeManager.Decision.Hold && s.decision !is TradeManager.Decision.Trail) return s.decision to now
        }
        return null
    }

    // ---- the early exits, one by one -----------------------------------------------------------------------------------

    @Test fun oppositeFlowWithExecutedVolumeAndPriceThroughVwapExitsAfter60s() {
        val t = trade()
        val sellers = { now: Long -> snap(now, flow = read(now, OrderFlow.Side.SELLERS, 70, cvd60 = -5_000.0, mid = 24_990.0), fut = 24_990.0) }
        val d = run(t, 120, snapAt = sellers)
        assertNotNull(d)
        val e = assertIs<TradeManager.Decision.ExitEarly>(d.first)
        assertEquals(TradeManager.Rule.OPPOSITE_FLOW, e.rule)
        assertEquals(t0 + 60_000L, d.second, "held for 60 s, not sooner")
        assertEquals("sellers took over", M.short(e.rule, 1))
        // Not executed (no volume behind it), or price still above VWAP and in value: nothing.
        assertNull(run(t, 120) { now -> snap(now, flow = read(now, OrderFlow.Side.SELLERS, 70, cvd60 = 3_000.0, mid = 24_990.0), fut = 24_990.0) })
        assertNull(run(t, 120) { now -> snap(now, flow = read(now, OrderFlow.Side.SELLERS, 70, cvd60 = -3_000.0, mid = 25_005.0), fut = 25_005.0) })
        assertNull(run(t, 120) { now -> snap(now, flow = read(now, OrderFlow.Side.SELLERS, 55, cvd60 = -3_000.0, mid = 24_990.0), fut = 24_990.0) }, "too weak")
        // A put's trade: buyers against it.
        val put = trade(side = -1)
        val e2 = run(put, 120) { now -> snap(now, flow = read(now, OrderFlow.Side.BUYERS, 70, cvd60 = 5_000.0, mid = 25_010.0), fut = 25_010.0) }
        assertEquals(TradeManager.Rule.OPPOSITE_FLOW, (e2!!.first as TradeManager.Decision.ExitEarly).rule)
    }

    @Test fun aRuleThatLapsesStartsItsPersistenceAgain() {
        val t = trade()
        // Sellers for 50 s, a balanced second, sellers again: the 60 s count from the second run.
        val d = run(t, 200) { now ->
            val i = (now - t0) / 1000
            if (i == 50L) snap(now, flow = read(now)) else snap(now, flow = read(now, OrderFlow.Side.SELLERS, 70, cvd60 = -5_000.0, mid = 24_990.0), fut = 24_990.0)
        }
        assertEquals(t0 + 111_000L, d!!.second)
        // A gap of more than 20 s between looks starts it again too.
        var st = M.start(t)
        st = M.decide(t, snap(t0, flow = read(t0, OrderFlow.Side.SELLERS, 70, cvd60 = -5_000.0, mid = 24_990.0), fut = 24_990.0), st).state
        val later = t0 + 65_000L
        val s = M.decide(t, snap(later, flow = read(later, OrderFlow.Side.SELLERS, 70, cvd60 = -5_000.0, mid = 24_990.0), fut = 24_990.0), st)
        assertIs<TradeManager.Decision.Hold>(s.decision)
    }

    @Test fun divergenceAgainstWithTheFlowNotBehindIt() {
        val t = trade()
        val d = run(t, 90) { now -> snap(now, flow = read(now), div = Auction.Divergence.BEARISH) }
        assertEquals(TradeManager.Rule.DIVERGENCE, (d!!.first as TradeManager.Decision.ExitEarly).rule)
        assertEquals(t0 + 60_000L, d.second)
        // The flow still behind the long: no exit.
        assertNull(run(t, 90) { now -> snap(now, flow = read(now, OrderFlow.Side.BUYERS, 60, cvd60 = 100.0), div = Auction.Divergence.BEARISH) })
        // A bullish divergence is not against a long.
        assertNull(run(t, 90) { now -> snap(now, flow = read(now), div = Auction.Divergence.BULLISH) })
    }

    @Test fun absorptionAgainstAtALevel() {
        val t = trade()
        val d = run(t, 60) { now -> snap(now, flow = read(now, absorbAt = 25_002.0, absorbBy = -1)) }
        assertEquals(TradeManager.Rule.ABSORPTION, (d!!.first as TradeManager.Decision.ExitEarly).rule)
        assertEquals(t0 + 30_000L, d.second)
        // Far from the price, or buyers absorbing (for the long): nothing.
        assertNull(run(t, 60) { now -> snap(now, flow = read(now, absorbAt = 25_200.0, absorbBy = -1)) })
        assertNull(run(t, 60) { now -> snap(now, flow = read(now, absorbAt = 25_002.0, absorbBy = 1)) })
    }

    @Test fun stopHuntOrFailedBreakAgainst() {
        val t = trade()
        val d = run(t, 30) { now -> snap(now, flow = read(now, flags = setOf(TrapGuard.Trap.FAILED_BREAK), huntDir = 1)) }
        assertEquals(TradeManager.Rule.TRAP, (d!!.first as TradeManager.Decision.ExitEarly).rule)
        assertEquals(t0 + 15_000L, d.second)
        assertNull(run(t, 30) { now -> snap(now, flow = read(now, flags = setOf(TrapGuard.Trap.STOP_HUNT), huntDir = -1)) }, "a hunt down helps a long")
    }

    @Test fun aNewsWindowStartingExitsAtOnce() {
        val t = trade()
        val w = MarketBrain.Window("RBI policy", t0 + 150_000L, t0 + 750_000L)
        val ctx = MarketBrain.Context(windows = listOf(w))
        assertNull(run(t, 20) { now -> snap(now, brain = ctx) }, "3 minutes ahead: not yet")
        val d = run(t, 40, from = t0 + 20_000L) { now -> snap(now, brain = ctx) }
        assertEquals(TradeManager.Rule.NEWS, (d!!.first as TradeManager.Decision.ExitEarly).rule)
        assertEquals(t0 + 30_000L, d.second, "2 minutes before it starts")
        // A window for crude only: not NIFTY's.
        val crude = MarketBrain.Context(windows = listOf(w.copy(scope = MarketBrain.EIA_SCOPE)))
        assertNull(run(t, 60, from = t0 + 30_000L) { now -> snap(now, brain = crude) })
    }

    @Test fun threeStrongFindingsAgainstExitAfter30s() {
        val t = trade()
        val f = { who: String, dir: Int, s: Int -> Findings.Finding(who, Findings.Kind.BREAK, "NIFTY", dir, null, s, t0) }
        val against = listOf(f("ORB", -1, 70), f("Liquidity 15+5", -1, 80), f("order flow", -1, 65))
        val d = run(t, 60) { now -> snap(now, findings = against) }
        assertEquals(TradeManager.Rule.CONSENSUS, (d!!.first as TradeManager.Decision.ExitEarly).rule)
        assertEquals(t0 + 30_000L, d.second)
        // Two only, weak ones, the strategy's own, or the manager's: nothing.
        assertNull(run(t, 60) { now -> snap(now, findings = against.take(2)) })
        assertNull(run(t, 60) { now -> snap(now, findings = against.take(2) + f("ORB2", -1, 40)) })
        assertNull(run(t, 60) { now -> snap(now, findings = against.take(2) + f("Pine #4", -1, 90)) })
        assertNull(run(t, 60) { now -> snap(now, findings = against.take(2) + f(TradeManager.WHO, -1, 90)) })
    }

    @Test fun unreliableDataExitsOnlyNearTheStop() {
        val t = trade()   // entry 100, stop 70: near = premium 80.5 or less
        val bad = { now: Long, px: Double -> snap(now, premium = px, flow = read(now, flags = setOf(TrapGuard.Trap.UNRELIABLE), warm = false)) }
        assertNull(run(t, 30) { now -> bad(now, 95.0) }, "far from the stop: the feed alone is no reason")
        val d = run(t, 30) { now -> bad(now, 78.0) }
        assertEquals(TradeManager.Rule.DATA, (d!!.first as TradeManager.Decision.ExitEarly).rule)
        assertEquals(t0 + 10_000L, d.second)
        // Solo's stop is on the index: the distance comes from the index.
        val solo = t.copy(strategyId = "solo", originalStop = null, originalTarget = null, stopUnderlying = 24_900.0, entryUnderlying = 25_000.0)
        val d2 = run(solo, 30) { now -> bad(now, 90.0).copy(underlyingPrice = 24_920.0) }
        assertEquals(TradeManager.Rule.DATA, (d2!!.first as TradeManager.Decision.ExitEarly).rule)
        assertNull(run(solo, 30) { now -> bad(now, 90.0).copy(underlyingPrice = 24_990.0) })
    }

    @Test fun aStaleOrColdReadDecidesNothing() {
        val t = trade()
        assertNull(run(t, 90) { now -> snap(now, flow = read(now - 10_000L, OrderFlow.Side.SELLERS, 70, cvd60 = -5_000.0, mid = 24_990.0), fut = 24_990.0) })
        assertNull(run(t, 90) { now -> snap(now, flow = read(now, OrderFlow.Side.SELLERS, 70, cvd60 = -5_000.0, mid = 24_990.0, warm = false), fut = 24_990.0) })
    }

    // ---- the extension and the ratcheting lock -------------------------------------------------------------------------

    private fun room(now: Long, px: Double = 156.0, high: Double? = null) = snap(now, premium = px, high = high, atr = 4.0,
        flow = read(now, OrderFlow.Side.BUYERS, 75, cvd60 = 8_000.0, mid = 25_030.0, buildUp = OrderFlow.BuildUp.LONG_BUILDUP, oi = 12_000),
        fut = 25_030.0)

    @Test fun extensionNeedsEveryConditionForThirtySecondsAndRaisesTheLock() {
        val t = trade()   // entry 100, stop 70, target 160 (60 away), charges 1
        val d = run(t, 60) { now -> room(now) }
        val e = assertIs<TradeManager.Decision.Extend>(d!!.first)
        assertEquals(t0 + 30_000L, d.second)
        // The step: 3 x the 1-minute range (12), under the cap (30).
        assertEquals(172.0, e.newTarget, 1e-9)
        // The lock: max(breakeven+charges 101, half the open profit 128, old target less a quarter of its gap 145) = 145.
        assertEquals(145.0, e.newStop, 1e-9)
        assertTrue(e.newStop < 156.0)
        // A missing piece: no extension.
        assertNull(run(t, 60) { now -> room(now, px = 140.0) }, "not near the target yet")
        assertNull(run(t, 60) { now -> room(now).copy(vwap = null) })
        assertNull(run(t, 60) { now -> room(now).copy(valueHigh = 25_040.0) }, "not beyond value")
        assertNull(run(t, 60) { now -> room(now).copy(flow = read(now, OrderFlow.Side.BUYERS, 75, cvd60 = 8_000.0, mid = 25_030.0, buildUp = OrderFlow.BuildUp.SHORT_COVERING, oi = -500)) }, "OI not rising")
        assertNull(run(t, 60) { now -> room(now).copy(flow = read(now, OrderFlow.Side.BUYERS, 75, cvd60 = 8_000.0, mid = 25_030.0, flags = setOf(TrapGuard.Trap.NO_EXECUTED), buildUp = OrderFlow.BuildUp.LONG_BUILDUP, oi = 1)) })
        assertNull(run(t, 60) { now -> room(now).copy(vixChangePct = 4.0) }, "VIX spiking")
        assertNull(run(t.copy(originalTarget = null), 60) { now -> room(now) }, "no target (Solo): never extended")
        assertNull(run(t, 60, from = t.caps.squareOffMs - 10 * 60_000L) { now -> room(now) }, "too close to the square-off")
    }

    @Test fun atMostTwoExtensionsWithACooldownAndTheLockRisesEachTime() {
        val t = trade()
        var st = M.start(t)
        val ext = ArrayList<Pair<Long, TradeManager.Decision.Extend>>()
        var px = 156.0
        for (i in 0..600) {
            val now = t0 + i * 1000L
            // The premium keeps near the newest target.
            st.target?.let { tg -> px = maxOf(px, t.entry + 0.95 * (tg - t.entry)) }
            val s = M.decide(t, room(now, px), st)
            (s.decision as? TradeManager.Decision.Extend)?.let { ext += now to it }
            assertFalse(s.decision is TradeManager.Decision.ExitEarly, "at $i: ${s.decision}")
            st = s.state
        }
        assertEquals(2, ext.size)
        assertEquals(2, st.extensions)
        assertTrue(ext[1].first - ext[0].first >= TradeManager.COOLDOWN_MS)
        assertTrue(ext[1].second.newStop > ext[0].second.newStop, "the lock rises on each extension")
        assertTrue(ext[1].second.newTarget > ext[0].second.newTarget)
        // An exit rule seen within the last minute holds an extension back.
        var s2 = M.start(t)
        s2 = M.decide(t, room(t0).copy(divergence = Auction.Divergence.BEARISH, flow = read(t0)), s2).state
        assertNull(run(t, 50, s2, from = t0 + 1000L) { now -> room(now) })
    }

    @Test fun theLockTrailsNewHighsOnlyUpAndExitsAsALock() {
        val t = trade()
        var st = M.start(t)
        for (i in 0..40) st = M.decide(t, room(t0 + i * 1000L), st).state
        assertEquals(145.0, st.lock!!, 1e-9)
        // A new wick high of 170: half the open profit there is 135, below 145 - the lock stays.
        var s = M.decide(t, snap(t0 + 41_000L, premium = 165.0, high = 170.0), st)
        assertEquals(145.0, s.state.lock!!, 1e-9)
        // A wick high of 200: half is 150 - the lock rises to 150.
        s = M.decide(t, snap(t0 + 42_000L, premium = 190.0, high = 200.0), s.state)
        assertIs<TradeManager.Decision.Trail>(s.decision)
        assertEquals(150.0, s.state.lock!!, 1e-9)
        // Lower highs never bring it down.
        s = M.decide(t, snap(t0 + 43_000L, premium = 180.0, high = 185.0), s.state)
        assertEquals(150.0, s.state.lock!!, 1e-9)
        // Through the lock: a normal profit-lock exit.
        s = M.decide(t, snap(t0 + 44_000L, premium = 149.0), s.state)
        val e = assertIs<TradeManager.Decision.ExitEarly>(s.decision)
        assertEquals(TradeManager.Rule.LOCK, e.rule)
        assertTrue(s.state.exited)
        assertIs<TradeManager.Decision.Hold>(M.decide(t, snap(t0 + 45_000L, premium = 100.0), s.state).decision)
    }

    @Test fun theLockFitsUnderThePriceOrThereIsNoExtension() {
        val t = trade()
        val st = M.start(t)
        // Premium 156: the wanted lock 145 fits.
        assertEquals(145.0, M.levels(t, st, 156.0, null)!!.second, 1e-9)
        // Premium 146: 145 is within the 1% gap - the lock goes as high as it may under the price (144.5).
        val lv = M.levels(t, st, 146.0, null)!!
        assertTrue(lv.second <= 146.0 - M.gap(146.0, 0.05) + 1e-9 && lv.second >= 101.0, "$lv")
        // Premium 101.5 with charges 1: breakeven-plus-charges does not fit under it - no extension.
        assertNull(M.levels(t, st, 101.5, null))
        // The step is capped at half the original distance.
        assertEquals(190.0, M.levels(t, st, 156.0, 50.0)!!.first, 1e-9)
    }

    /** Property: over random trades and looks, money at risk never rises, the lock never falls, and every extension's lock is >= breakeven + charges. */
    @Test fun moneyAtRiskNeverIncreasesOverRandomScenarios() {
        val rnd = Random(20261010)
        var seen = 0
        repeat(400) { n ->
            val entry = 20.0 + rnd.nextDouble() * 400
            val dist = entry * (0.1 + rnd.nextDouble())
            val t = trade(side = if (rnd.nextBoolean()) 1 else -1, entry = entry, stop = if (rnd.nextInt(5) == 0) null else entry * (0.5 + rnd.nextDouble() * 0.45),
                target = entry + dist, charges = rnd.nextDouble() * 3, qty = 25 * (1 + rnd.nextInt(4)))
            val original = M.moneyAtRisk(t, t.originalStop)
            var st = M.start(t)
            var px = entry
            var lastLock: Double? = null
            var extended = false
            val blocks = BooleanArray(21) { rnd.nextInt(3) != 0 }
            for (i in 0..900) {
                val now = t0 + i * 1000L
                // Stretches of 45 s: a good one (the premium climbs, the flow agrees) or a random one.
                val good = blocks[i / 45]
                px = (if (good) px * (1 + rnd.nextDouble() * 0.01) else px * (1 + (rnd.nextDouble() - 0.5) * 0.03)).coerceAtLeast(0.05)
                val dir = t.side
                val flow = if (good) read(now, if (dir > 0) OrderFlow.Side.BUYERS else OrderFlow.Side.SELLERS, 60 + rnd.nextInt(40), cvd60 = dir * 5_000.0,
                    mid = 25_000.0 + dir * 40, buildUp = if (dir > 0) OrderFlow.BuildUp.LONG_BUILDUP else OrderFlow.BuildUp.SHORT_BUILDUP, oi = 1_000)
                    else read(now, OrderFlow.Side.entries[rnd.nextInt(3)], rnd.nextInt(100), cvd60 = rnd.nextDouble() * 10_000 - 5_000, mid = 25_000.0 + rnd.nextDouble() * 80 - 40)
                val s = snap(now, premium = px, high = px * (1 + rnd.nextDouble() * 0.02), atr = if (rnd.nextBoolean()) entry * rnd.nextDouble() * 0.1 else null,
                    flow = flow, fut = flow.mid, vah = 25_020.0, val_ = 24_980.0)
                val step = M.decide(t, s, st)
                val d = step.decision
                if (d is TradeManager.Decision.Extend) {
                    extended = true; seen++
                    assertTrue(d.newStop >= t.entry + t.chargesPerUnit - 1e-9, "#$n: the lock is at breakeven plus charges or better")
                    assertTrue(d.newStop >= (st.lock ?: Double.NEGATIVE_INFINITY) - 1e-9, "#$n: never down")
                    assertTrue(d.newStop >= (t.originalStop ?: Double.NEGATIVE_INFINITY) - 1e-9, "#$n: never wider than the original stop")
                    assertTrue(d.newStop < px, "#$n: a stop, under the price")
                    assertTrue(d.newTarget - (st.target ?: 0.0) <= M.EXT_CAP * dist + 1e-9, "#$n: the step is capped")
                    assertTrue(M.moneyAtRisk(t, d.newStop) <= original + 1e-9, "#$n: money at risk never above the plan")
                    assertEquals(0.0, M.moneyAtRisk(t, d.newStop), 1e-6)
                }
                step.state.lock?.let { l ->
                    lastLock?.let { assertTrue(l >= it - 1e-9, "#$n: the lock fell $it -> $l") }
                    assertTrue(l >= t.entry + t.chargesPerUnit - 1e-9, "#$n: after the first extension the lock is never under breakeven plus charges")
                    lastLock = l
                }
                if (lastLock != null) assertTrue(extended)
                assertTrue(step.state.extensions <= M.MAX_EXTENSIONS)
                st = step.state
                if (st.exited) break
            }
        }
        assertTrue(seen >= 20, "the scenarios extended $seen times")
    }

    @Test fun decideIsCheap() {
        val t = trade()
        val findings = (0 until 40).map { Findings.Finding("w$it", Findings.Kind.BREAK, "NIFTY", if (it % 2 == 0) 1 else -1, null, 70, t0) }
        val ctx = MarketBrain.Context(windows = listOf(MarketBrain.Window("RBI policy", t0 + 3_600_000L, t0 + 4_000_000L)))
        var st = M.start(t)
        val snaps = (0 until 2_000).map { i -> room(t0 + i * 1000L, 150.0 + (i % 7)).copy(findings = findings, brain = ctx) }
        repeat(2) { for (s in snaps) st = M.decide(t, s, M.start(t)).state }      // warm up
        val n0 = System.nanoTime()
        for (s in snaps) st = M.decide(t, s, st).state.copy(exited = false)
        val perUs = (System.nanoTime() - n0) / 1_000.0 / snaps.size
        assertTrue(perUs < 200.0, "one decision took $perUs µs on average")
    }

    // ---- the counterfactual and the record ------------------------------------------------------------------------------

    private fun bar(i: Int, o: Double, h: Double, l: Double, c: Double) = TradeManager.Bar(t0 + i * 60_000L, o, h, l, c)

    @Test fun theOriginalRulesAreFollowedOnTheWicks() {
        val t = trade()
        val bars = listOf(bar(0, 100.0, 110.0, 98.0, 108.0), bar(1, 108.0, 125.0, 105.0, 120.0), bar(2, 120.0, 165.0, 118.0, 150.0))
        val p = M.walk(t, TradeManager.Path(70.0, 160.0, t.caps.squareOffMs, t.entry), bars, fromMs = t0)
        assertEquals(TradeManager.Exit(t0 + 120_000L, 160.0, "TARGET"), p.exit)
        // From a later action only: an exit before it is not counted (the trade was really held then).
        val q = M.walk(t, TradeManager.Path(70.0, 115.0, t.caps.squareOffMs, t.entry), bars, fromMs = t0 + 120_000L)
        assertEquals("TARGET", q.exit!!.why); assertEquals(t0 + 120_000L, q.exit!!.atMs); assertEquals(120.0, q.exit!!.price, 1e-9, "opened through it")
        // Both touched in one bar: the stop (conservative).
        val both = M.walk(t, TradeManager.Path(100.0, 160.0, t.caps.squareOffMs, t.entry), listOf(bar(0, 120.0, 170.0, 95.0, 110.0)), t0)
        assertEquals("STOP", both.exit!!.why)
        // A profit lock earned by the best price: a LOCK exit.
        val lk = M.walk(t, TradeManager.Path(70.0, 300.0, t.caps.squareOffMs, t.entry), bars + bar(3, 150.0, 151.0, 120.0, 125.0), t0) { peak -> if (peak >= 150.0) 130.0 else null }
        assertEquals(TradeManager.Exit(t0 + 180_000L, 130.0, "LOCK"), lk.exit)
        // The manager's trail: half the open profit at the best price.
        val tr = M.walk(t, TradeManager.Path(145.0, 300.0, t.caps.squareOffMs, t.entry, trailing = true), bars + bar(3, 160.0, 200.0, 159.0, 190.0) + bar(4, 190.0, 191.0, 140.0, 141.0), t0 + 180_000L)
        assertEquals("LOCK", tr.exit!!.why); assertEquals(150.0, tr.exit!!.price, 1e-9)
        // Time.
        val tm = M.walk(t, TradeManager.Path(10.0, 900.0, t0 + 120_000L, t.entry), bars, t0)
        assertEquals(TradeManager.Exit(t0 + 120_000L, 120.0, "TIME"), tm.exit)
        assertEquals(TradeManager.Exit(t0 + 60_000L, 120.0, "index_stop"), M.priced(bars, t0 + 60_000L, "index_stop"))
    }

    @Test fun theRecordComparesTheManagerWithTheOriginalRules() {
        val paper = trade()
        val act = TradeManager.Record(paper, TradeManager.Mode.ACT)
        // No action: both ways are the actual.
        val plain = M.closed(act, TradeManager.Exit(t0 + 1, 150.0, "target"))
        assertEquals(0.0, plain.vsOriginal!!, 1e-9)
        // An early exit in ACT at 110; the original rules later stopped at 70.
        val early = M.closed(act.copy(actedAtMs = t0, notes = listOf(TradeManager.Note(t0, TradeManager.EXIT_EARLY, "flow", "sellers took over", 110.0, true))),
            TradeManager.Exit(t0, 110.0, "trade manager"))
        assertNull(early.original); assertNull(early.vsOriginal)
        val settled = early.copy(original = TradeManager.Exit(t0 + 600_000L, 70.0, "STOP"))
        assertEquals((110.0 - 70.0) * 75, settled.vsOriginal!!, 1e-9)
        // SHADOW on live: the actual is the original's; the manager's would-be exit is recorded apart.
        val live = trade(account = TradeManager.Account.LIVE)
        val sh = M.closed(TradeManager.Record(live, TradeManager.Mode.SHADOW, actedAtMs = t0, manager = TradeManager.Exit(t0, 120.0, "EARLY: flow")),
            TradeManager.Exit(t0 + 1, 160.0, "target"))
        assertEquals(TradeManager.Exit(t0 + 1, 160.0, "target"), sh.original)
        assertEquals((120.0 - 160.0) * 75, sh.vsOriginal!!, 1e-9)
        val tally = M.tally(listOf(plain, settled, sh))
        assertEquals(listOf(TradeManager.Account.PAPER, TradeManager.Account.LIVE), tally.map { it.account })
        assertEquals(2, tally[0].trades); assertEquals(1, tally[0].earlyExits); assertEquals(3_000.0, tally[0].net, 1e-9)
        assertTrue(M.recordLines(listOf(plain, settled, sh)).first().startsWith("Pine paper: 2 trades, 1 early exit (1 helped, 0 hurt)"))
        val shEarly = sh.copy(notes = listOf(TradeManager.Note(t0, TradeManager.EXIT_EARLY, "flow", "sellers took over", 120.0, false)))
        assertTrue(M.recordLines(listOf(shEarly))[0].contains("1 early exit (0 helped, 1 hurt)"), M.recordLines(listOf(shEarly))[0])
        assertFalse("ready to turn on" in M.recordLines(listOf(settled))[0])
        // 30 settled trades and the manager ahead: the hint (only a hint), not while it already acts.
        val many = (1..30).map { i -> settled.copy(trade = settled.trade.copy(tradeId = "t$i", strategyId = "night", account = TradeManager.Account.PAPER)) }
        val night = M.tally(many).single()
        assertTrue(night.ready)
        assertTrue("ready to turn on?" in M.tallyLine(night, TradeManager.Mode.SHADOW) && "nothing switches by itself" in M.tallyLine(night, TradeManager.Mode.SHADOW))
        assertFalse("ready to turn on?" in M.tallyLine(night, TradeManager.Mode.ACT))
        assertFalse(M.tally(many.take(29)).single().ready)
        // An extension that helped: counted apart from the early exits.
        val ext = settled.copy(notes = emptyList(), extensions = 1)
        assertEquals(1, M.tally(listOf(ext)).single().extHelped)
        assertTrue("lock" in M.cardLine(settled) && "target 160" in M.cardLine(settled), M.cardLine(settled))
    }

    // ---- modes and words ------------------------------------------------------------------------------------------------

    @Test fun modesDefaultPaperActLiveShadowOthersOffAndLiveActNeedsThePin() {
        val p = TradeManager.Policy()
        assertEquals(TradeManager.Mode.ACT, p.mode("solo", TradeManager.Account.PAPER))
        assertEquals(TradeManager.Mode.ACT, p.mode("pine", TradeManager.Account.PAPER))
        assertEquals(TradeManager.Mode.SHADOW, p.mode("pine", TradeManager.Account.LIVE))
        // Every other strategy: SHADOW by default, on paper too (records only); ACT only when Boss switches it on.
        for (f in TradeManager.FAMILIES.filter { it.key !in TradeManager.ACT_ON_PAPER })
            for (a in TradeManager.Account.entries) assertEquals(TradeManager.Mode.SHADOW, p.mode(f.key, a), f.key)
        assertEquals(TradeManager.Mode.SHADOW, p.mode("some-new-arm", TradeManager.Account.PAPER))
        assertEquals(TradeManager.Mode.ACT, p.with("night", TradeManager.Account.PAPER, TradeManager.Mode.ACT).mode("night", TradeManager.Account.PAPER))
        // An arm whose exits do not take the manager's word never acts, whatever is stored.
        assertEquals(TradeManager.Mode.SHADOW, p.with("liquidity", TradeManager.Account.PAPER, TradeManager.Mode.ACT).mode("liquidity", TradeManager.Account.PAPER))
        assertEquals(TradeManager.Mode.OFF, p.with("orb", TradeManager.Account.LIVE, TradeManager.Mode.OFF).mode("orb", TradeManager.Account.LIVE))
        val q = p.with("pine", TradeManager.Account.LIVE, TradeManager.Mode.ACT).with("solo", TradeManager.Account.PAPER, TradeManager.Mode.SHADOW)
        assertEquals(q, M.decode(M.encode(q)))
        assertEquals(p, p.with("pine", TradeManager.Account.LIVE, TradeManager.Mode.SHADOW))
        assertEquals(TradeManager.Policy(), M.decode("junk;pine.live=NOPE;=;x"))
        assertTrue(M.needsPin(TradeManager.Mode.ACT, TradeManager.Account.LIVE))
        assertFalse(M.needsPin(TradeManager.Mode.SHADOW, TradeManager.Account.LIVE))
        assertFalse(M.needsPin(TradeManager.Mode.ACT, TradeManager.Account.PAPER))
    }

    @Test fun jarvisQuestions() {
        assertEquals(TradeManager.Ask(false, null), M.asked("how is the trade manager doing?"))
        assertEquals(TradeManager.Ask(true, "solo"), M.asked("Why did Solo exit early?"))
        assertEquals(TradeManager.Ask(true, "pine"), M.asked("why did the pine script exit early"))
        assertNull(M.asked("how is Solo doing"))
        assertNull(M.asked("what did Solo do today"))
        val r = TradeManager.Record(trade().copy(strategyId = "solo", label = "Solo"), TradeManager.Mode.ACT, target = null, lock = null,
            notes = listOf(TradeManager.Note(t0, TradeManager.EXIT_EARLY, "flow", "sellers took over", 110.0, true, mapOf("strength" to 70.0))))
        val a = M.answerWhy("solo", listOf(r)) { "12:41" }
        assertTrue("Solo exited early at 12:41: sellers took over" in a && "strength 70" in a && "lock none" in a, a)
        assertTrue("has not exited early" in M.answerWhy("pine", listOf(r)) { "x" })
        val s = M.answerStatus(listOf(r), TradeManager.Policy())
        assertTrue("Pine: paper acting, live shadow" in s && "Solo: paper acting" in s && "every other strategy records only" in s && "Liquidity 15+5: paper shadow (records only), live shadow" in s && "Night (R3): paper shadow (records only);" in s, s)
    }

    @Test fun theQuestionsAreTheTradeManagersAndNeverAct() {
        val audit = CoverageTest()
        for (q in listOf("how is the trade manager doing", "how is the trade manager doing?", "why did Solo exit early", "why did Solo exit early?",
            "why did the pine script exit early", "trade manager record")) {
            assertEquals("TradeManager", audit.feature(q), q)
            val p = Ask.parse(q)
            assertNull(p.order, q); assertNull(p.command, q); assertFalse(Bundle.acts(q), q)
        }
        // Solo's own questions stay Solo's.
        assertEquals("SoloDay", audit.feature("what did Solo do today"))
    }
}
