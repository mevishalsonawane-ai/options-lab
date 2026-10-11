package com.optionslab.engine.orb

import com.optionslab.engine.Olx
import com.optionslab.engine.Right
import com.optionslab.engine.Series
import com.optionslab.engine.Session
import com.optionslab.engine.Upstox
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The arms' parameters (S1 / S3): every default IS the arm's constant, the rules given their defaults decide exactly as
 * before, and a parameter given another value moves only what it names.
 */
class ArmParamsTest {
    private val assets = System.getProperty("olx.assets") ?: "../app/src/main/assets"
    private val day = LocalDate.of(2026, 9, 1)

    @Test fun theDefaultsAreTheArmsConstants() {
        val orb = OrbParams.of(OrbRules.ORB)
        assertEquals(OrbParams(LocalTime.of(10, 0), LocalTime.of(14, 0), 0.0, 40.0, 40.0, 0.10), orb)
        assertEquals(orb, OrbParams.of(OrbRules.ORB_FRESH))
        assertEquals(OrbParams(LocalTime.of(10, 0), LocalTime.of(14, 30), 0.0, 40.0, 80.0, 0.10), OrbParams.of(SweepRules.ARM))
        assertEquals(OrbParams(LocalTime.of(10, 0), LocalTime.of(14, 0), 0.0, 40.0, 40.0, 0.10), OrbParams.of(RangeFadeRules.ARM))
        assertEquals(ArmsBacktest.stopOf(SweepRules.ARM), OrbParams.of(SweepRules.ARM).stopPoints)
        assertEquals(ArmsBacktest.targetOf(SweepRules.ARM), OrbParams.of(SweepRules.ARM).targetPoints)
        assertEquals(ArmsBacktest.stopOf(OrbRules.ORB), OrbParams.of(OrbRules.ORB).stopPoints)
        assertEquals(LiquidityParams(0.15, 20L, 0.05, true, 0.0), LiquidityParams.DEFAULT)
        assertNull(LiquidityParams.DEFAULT.lockTarget(200.0))
        assertEquals(120.0, LiquidityParams(lockPct = 0.6).lockTarget(200.0)!!, 1e-9)
        assertEquals(VixDivParams(0.002, 0.15, 20), VixDivParams.DEFAULT)
    }

    @Test fun theBundledMonthIsTheSameWithExplicitDefaults() {
        val sessions = File(assets, "bars_banknifty.olx").inputStream().use { Olx.read(it) }
        var n = 0
        for (s in sessions) {
            val plain = ArmsBacktest.day(s, 0.0, 40.0) ?: continue
            assertEquals(plain, ArmsBacktest.day(s, 0.0, 40.0) { OrbParams.of(it) })
            for (arm in ArmsBacktest.ARMS) assertEquals(plain.filter { it.arm == arm.source }, ArmsBacktest.arm(s, arm, slip = 0.0, charges = 40.0))
            n += plain.size
        }
        assertTrue(n > 50, "the month traded ($n)")
        // Another stop, target, window or range moves the trades; a range that never ends gives none.
        val all = sessions.mapNotNull { s -> ArmsBacktest.arm(s, OrbRules.ORB, OrbParams.ORB.copy(stopPoints = 20.0, targetPoints = 80.0), 0.0, 40.0) }.flatten()
        val base = sessions.mapNotNull { s -> ArmsBacktest.arm(s, OrbRules.ORB, slip = 0.0, charges = 40.0) }.flatten()
        assertTrue(all != base)
        assertTrue(sessions.all { s -> ArmsBacktest.arm(s, OrbRules.ORB, OrbParams.ORB.copy(orEnd = LocalTime.of(16, 0)))?.isEmpty() != false })
        val early = sessions.mapNotNull { s -> ArmsBacktest.arm(s, OrbRules.ORB, OrbParams.ORB.copy(lastEntry = LocalTime.of(11, 0)), 0.0, 40.0) }.flatten()
        assertTrue(early.all { it.signalBar < "11:00" })
        assertNull(ArmsBacktest.arm(Session(day, 30, emptyList()), OrbRules.ORB))
    }

    private fun bar(h: Int, m: Int, close: Double, hi: Double = close, lo: Double = close) = Bar(day.atTime(h, m), close, hi, lo, close)

    @Test fun theOrbRulesTakeTheirParameters() {
        val bars = (0..9).map { Bar(day.atTime(9, 15).plusMinutes(5L * it), 100.0, 101.0, 99.0, 100.0) }
        assertEquals(101.0 to 99.0, OrbRules.openingRange(bars))
        assertEquals(101.0 to 99.0, OrbRules.openingRange(bars.take(7), LocalTime.of(9, 45)))
        assertNull(OrbRules.openingRange(bars.take(6), LocalTime.of(9, 45)))
        // A buffer: 101.05 clears 101 but not 101 x 1.001.
        assertEquals(1, OrbRules.breakDirection(bar(10, 5, 101.05), 101.0, 99.0))
        assertEquals(0, OrbRules.breakDirection(bar(10, 5, 101.05), 101.0, 99.0, 0.001))
        assertEquals(-1, OrbRules.breakDirection(bar(10, 5, 98.0), 101.0, 99.0, 0.001))
        val seen = bars + bar(10, 5, 101.05)
        assertEquals(1, OrbRules.entrySignal(seen, 101.0 to 99.0, OrbRules.ORB, null).first)
        assertEquals("inside_range", OrbRules.entrySignal(seen, 101.0 to 99.0, OrbRules.ORB, null, p = OrbParams.ORB.copy(buffer = 0.001)).second)
        assertEquals("no_decision_bar", OrbRules.entrySignal(seen, 101.0 to 99.0, OrbRules.ORB, null, p = OrbParams.ORB.copy(lastEntry = LocalTime.of(10, 5))).second)
        assertTrue(OrbRules.mayDecide(bar(9, 50, 1.0), orEnd = LocalTime.of(9, 45)))
        assertFalse(OrbRules.mayDecide(bar(9, 50, 1.0)))
        // Stop and target.
        val t = day.atTime(11, 0)
        assertEquals("target", OrbRules.exitReason(200.0, 240.0, t))
        assertNull(OrbRules.exitReason(200.0, 240.0, t, 40.0, 60.0))
        assertEquals("stop", OrbRules.exitReason(200.0, 175.0, t, 25.0, 60.0))
        assertNull(OrbRules.exitReasonFor(OrbRules.ORB, 200.0, 239.0, t))
        assertEquals("target", OrbRules.exitReasonFor(OrbRules.ORB, 200.0, 230.0, t, OrbParams.ORB.copy(targetPoints = 30.0)))
        assertEquals("stop", OrbRules.exitReasonFor(SweepRules.ARM, 200.0, 170.0, t, OrbParams.SWEEP.copy(stopPoints = 30.0)))
        assertNull(OrbRules.exitReasonFor(SweepRules.ARM, 200.0, 279.0, t))
        assertEquals("target", OrbRules.exitReasonFor(SweepRules.ARM, 200.0, 250.0, t, OrbParams.SWEEP.copy(targetPoints = 50.0)))
        assertEquals(160.0, OrbRules.stopTrigger(200.0)!!, 1e-9)
        assertEquals(175.0, OrbRules.stopTrigger(200.0, 25.0)!!, 1e-9)
        assertNull(OrbRules.stopTrigger(30.0, 30.0))
    }

    @Test fun sweepAndFadeTakeTheirWindowAndEdge() {
        val rng = 101.0 to 99.0
        val sweep = listOf(bar(14, 10, 100.5, hi = 101.5))
        assertEquals(-1, SweepRules.entrySignal(sweep, rng, null, 0).first)
        assertEquals("no_decision_bar", SweepRules.entrySignal(sweep, rng, null, 0, until = LocalTime.of(14, 0)).second)
        // Range Fade: a high 0.15 of the range under the top is not at a 10% edge, but is at a 20% one.
        val fade = listOf(bar(11, 0, 100.0, hi = 100.7))
        assertEquals("not_at_the_edge", RangeFadeRules.entrySignal(fade, rng, null, 0).second)
        assertEquals(-1, RangeFadeRules.entrySignal(fade, rng, null, 0, edge = 0.2).first)
        assertEquals("no_decision_bar", RangeFadeRules.entrySignal(fade, rng, null, 0, edge = 0.2, lastBar = LocalTime.of(11, 0)).second)
        assertEquals("stop", SweepRules.exitReason(200.0, 175.0, day.atTime(11, 0), 25.0, 80.0))
    }

    @Test fun liquiditysExitNowIsTheArmsOrderWithItsParameters() {
        val sig = day.atTime(10, 0)
        val entryT = day.atTime(10, 5)
        val bars = (0..30).map { Bar(day.atTime(9, 15).plusMinutes(5L * it), 100.0, 101.0, 99.0, 100.5) }
        val calm = listOf(Bar(entryT, 100.0, 101.0, 99.0, 100.5))
        // Index stop first.
        val hit = listOf(Bar(entryT, 100.0, 101.0, 60.0, 100.5))
        assertEquals(LiquidityRules.Look("index_stop", false),
            LiquidityRules.exitNow(1, 100.0, null, sig, 200.0, entryT, entryT.plusMinutes(1), bars, hit, "BANKNIFTY", null, false) { emptyList() })
        // The time stop: due at 20 minutes, decided once on a fresh price; short of +5% sells, else it is held and marked.
        assertEquals(LiquidityRules.Look("time_stop", false),
            LiquidityRules.exitNow(1, 100.0, null, sig, 200.0, entryT, entryT.plusMinutes(20), bars, calm, "BANKNIFTY", 205.0, false) { emptyList() })
        assertEquals(LiquidityRules.Look(null, true),
            LiquidityRules.exitNow(1, 100.0, null, sig, 200.0, entryT, entryT.plusMinutes(20), bars, calm, "BANKNIFTY", 211.0, false) { emptyList() })
        assertEquals(LiquidityRules.Look(null, false),
            LiquidityRules.exitNow(1, 100.0, null, sig, 200.0, entryT, entryT.plusMinutes(20), bars, calm, "BANKNIFTY", null, false) { emptyList() })
        assertEquals(LiquidityRules.Look(null, true),
            LiquidityRules.exitNow(1, 100.0, null, sig, 200.0, entryT, entryT.plusMinutes(20), bars, calm, "BANKNIFTY", 150.0, true) { emptyList() })
        // Other minutes and gain.
        val p = LiquidityParams(timeStopMinutes = 10, timeStopGain = 0.10)
        assertEquals(LiquidityRules.Look("time_stop", false),
            LiquidityRules.exitNow(1, 100.0, null, sig, 200.0, entryT, entryT.plusMinutes(10), bars, calm, "BANKNIFTY", 215.0, false, p) { emptyList() })
        // The next level: a target touched sells, unless the variant has no level target.
        assertEquals("next_liquidity", LiquidityRules.exitNow(1, 100.0, 100.8, sig, 200.0, entryT, entryT.plusMinutes(1), bars, calm,
            "BANKNIFTY", null, false) { emptyList() }.why)
        assertNull(LiquidityRules.exitNow(1, 100.0, 100.8, sig, 200.0, entryT, entryT.plusMinutes(1), bars, calm, "BANKNIFTY", null, false,
            LiquidityParams(nextLevelTarget = false)) { emptyList() }.why)
        // The default levels are the bars' own zones.
        assertEquals(LiquidityRules.exitReason(1, 100.0, null, sig, bars, LiquidityRules.zones(bars), calm),
            LiquidityRules.exitNow(1, 100.0, null, sig, 200.0, entryT, entryT.plusMinutes(1), bars, calm, "BANKNIFTY", null, false).why)
        assertEquals(0.85, LiquidityRules.stopTrigger(1.0)!!, 0.0)
        assertEquals(0.75, LiquidityRules.stopTrigger(1.0, 0.25)!!, 1e-9)
        assertFalse(LiquidityRules.timeStopDue(entryT, entryT.plusMinutes(9), 10))
        assertTrue(LiquidityRules.timeStopFails(200.0, 215.0, 0.08))
    }

    private fun ones(at: Int, close: (Int) -> Double): List<Upstox.Bar> = (9 * 60 + 15..at).map { m ->
        val e = day.atTime(m / 60, m % 60).atZone(com.optionslab.engine.IST).toEpochSecond()
        Upstox.Bar(e, close(m), close(m), close(m), close(m), 0, 0)
    }

    @Test fun vixDivergenceTakesItsMoveStopAndMinutes() {
        val index = ones(10 * 60 + 40) { m -> if (m < 600) 100.0 else 100.25 }
        val vix = ones(10 * 60 + 40) { m -> if (m < 600) 10.0 else 10.3 }
        assertEquals(-1, VixDivRules.firstSignal(index, vix, 10 * 60 + 30)!!.side)
        assertNull(VixDivRules.firstSignal(index, vix, 10 * 60 + 30, 0.003))
        assertEquals(0, VixDivRules.side(0.0025, 0.03, 0.003))
        assertEquals(1, VixDivRules.side(-0.0025, -0.03))
        assertEquals(85.0, VixDivRules.stop(100.0), 1e-9)
        assertEquals(90.0, VixDivRules.stop(100.0, 0.10), 1e-9)
        val h = VixDivRules.Held("NIFTY", 100.0, 600)
        val flat = (600..640).map { VixDivRules.Minute(it, 100.0, 100.0) }
        assertEquals("not_up_5_in_20", VixDivRules.exit(h, flat, 620))
        assertNull(VixDivRules.exit(h, flat, 615))
        assertEquals("not_up_5_in_20", VixDivRules.exit(h, flat, 611, VixDivParams(minutes = 10)))
        val dip = listOf(VixDivRules.Minute(601, 88.0, 99.0))
        assertNull(VixDivRules.exit(h, dip, 603))
        assertEquals("stop_15", VixDivRules.exit(h, dip, 603, VixDivParams(stopPct = 0.10)))
    }
}
