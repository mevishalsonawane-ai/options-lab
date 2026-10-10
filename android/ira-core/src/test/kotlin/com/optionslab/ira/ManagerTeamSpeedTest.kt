package com.optionslab.ira

import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A full 16-specialist committee look ([ManagerTeam.decide]) on a busy snapshot (40 findings, a news window ahead, 30 minutes
 * of volumes, the profile's nodes, gamma): the median under 1 ms on the JVM, and no allocation storm (a bounded few KB a look).
 */
class ManagerTeamSpeedTest {
    private val t0 = 1_760_000_000_000L

    @Test fun aFullCommitteeLookTakesUnderAMillisecond() {
        val t = TradeManager.ManagedTrade("pine:4", "pine:4:x", "Pine · EMA", "NIFTY", 1, "NIFTY25OCT25000CE", 100.0, 75, 70.0, 160.0,
            TradeManager.Caps(t0 + 3 * 3_600_000L), TradeManager.Account.PAPER, t0, 1.0)
        val findings = (0 until 40).map { Findings.Finding("w$it", Findings.Kind.BREAK, "NIFTY", if (it % 2 == 0) 1 else -1, null, 70, t0) }
        val ctx = MarketBrain.Context(windows = listOf(MarketBrain.Window("RBI policy", t0 + 3_600_000L, t0 + 4_000_000L)))
        val vols = (0 until 30).map { (t0 / 1000 / 60 - 30 + it) * 60 to (1_000L + it * 10) }
        val snaps = (0 until 3_000).map { i ->
            val now = t0 + i * 1000L
            val r = OrderFlow.Read(name = "NIFTY", atSec = now / 1000, side = OrderFlow.Side.BUYERS, strength = 75, buyers = 75, warm = true, mid = 25_030.0,
                ofi10 = 0.0, ofi60 = 0.0, ofi300 = 0.0, cvd10 = 0.0, cvd60 = 8_000.0, cvd300 = 0.0, depth = 0.0, queue = 0.0, ratio = Double.NaN,
                ce60 = 0.0, pe60 = 0.0, optionNet = Double.NaN, buildUp = OrderFlow.BuildUp.LONG_BUILDUP, oiChange = 12_000, z = emptyMap())
            TradeManager.Snapshot(now, 150.0 + (i % 7), null, 4.0, null, r, 25_030.0 + (i % 5), Auction.Vwap(25_000.0, 20.0, null, null),
                25_010.0, 24_980.0, null, 0.5, ctx, findings, poc = 25_000.0, hvns = listOf(25_060.0), lvns = listOf(24_990.0),
                minuteVolumes = vols, gammaNet = -1e9, zeroGamma = 24_900.0)
        }
        val cfg = ManagerConfig("pine")
        var st = ManagerTeam.start(t)
        repeat(3) { for (s in snaps.take(1_000)) st = ManagerTeam.decide(t, s, ManagerTeam.start(t), cfg).state }   // warm up
        st = ManagerTeam.start(t)
        val times = LongArray(snaps.size)
        val threads = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        val tid = Thread.currentThread().id
        val a0 = threads?.getThreadAllocatedBytes(tid) ?: 0L
        for ((i, s) in snaps.withIndex()) {
            val n0 = System.nanoTime()
            val step = ManagerTeam.decide(t, s, st, cfg)
            times[i] = System.nanoTime() - n0
            // Keep it looking (an exit or a used-up extension would make the rest of the looks trivial).
            st = step.state.copy(core = step.state.core.copy(exited = false, extensions = 0, lock = null, target = t.originalTarget))
        }
        val perLook = threads?.let { (it.getThreadAllocatedBytes(tid) - a0) / snaps.size }
        times.sort()
        val medianUs = times[times.size / 2] / 1_000.0
        assertTrue(medianUs <= 1_000.0, "a committee look's median took $medianUs µs")
        if (perLook != null) assertTrue(perLook < 64 * 1024, "a committee look allocated $perLook bytes")
        println("ManagerTeam: median $medianUs µs a look, ${perLook ?: "?"} bytes allocated a look")
    }
}
