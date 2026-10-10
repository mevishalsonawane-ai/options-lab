package com.optionslab.ira

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The hot paths the watch's workers added (Boss, 10 Oct: "existing performance must not decrease"): each timed on the JVM
 * with a generous bound, so a big regression fails here. The numbers are printed for the record (per call, after warm-up).
 */
class WorkersSpeedTest {
    private fun perCallNs(n: Int, f: () -> Unit): Double {
        repeat(n / 5) { f() }                     // warm-up (JIT)
        val t0 = System.nanoTime()
        repeat(n) { f() }
        return (System.nanoTime() - t0).toDouble() / n
    }

    private fun report(what: String, ns: Double, boundNs: Double) {
        println("speed: $what %.0f ns a call (bound %.0f ns)".format(ns, boundNs))
        assertTrue(ns < boundNs, "$what took %.0f ns a call".format(ns))
    }

    @Test fun theEntryDoorDecisionIsMicroseconds() = runBlocking {
        val gate = EntryGate()
        var sent = 0
        val ns = run {
            repeat(20_000) { gate.enter("NFO:NIFTY", { null }) { sent++ } }
            val t0 = System.nanoTime()
            repeat(100_000) { gate.enter("NFO:NIFTY", { null }) { sent++ } }
            (System.nanoTime() - t0).toDouble() / 100_000
        }
        report("entry door (uncontended, recheck included)", ns, 50_000.0)
    }

    @Test fun theOneIndexClaimIsMicroseconds() {
        val holds = EntryHolds()
        val held = listOf("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX").map {
            AutoSide.Held.option("ORB", "$it-CE", it, "CE", long = true)
        }
        val booked = mapOf("ORB" to held, "PINE" to emptyList<AutoSide.Held>())
        var i = 0L
        val ns = perCallNs(100_000) { holds.checkAndHold("PINE", "Pine", "BANKEX", if (i++ % 2 == 0L) 1 else -1, held, booked, i) }
        report("one-index check and claim (5 held)", ns, 50_000.0)
    }

    @Test fun theRequestBudgetIsNanoseconds() {
        var now = 0L
        val g = RateGate(perSecond = 8) { now }
        val ns = perCallNs(1_000_000) { now += 200; g.tryTake(RateGate.Priority.ENTRY) }
        report("request budget decision", ns, 5_000.0)
    }

    @Test fun aSharedReadAddsMicrosecondsNotMilliseconds() = runBlocking {
        val f = SingleFlight<Int>()
        repeat(20_000) { f.run(it.toLong(), 2_000) { 1 } }
        val t0 = System.nanoTime()
        repeat(100_000) { f.run(it.toLong(), 2_000) { 1 } }
        val ns = (System.nanoTime() - t0).toDouble() / 100_000
        // A Zerodha read is tens to hundreds of milliseconds; the first caller reads in its own coroutine (no hand-over).
        report("shared read overhead (first caller)", ns, 20_000.0)
    }

    @Test fun timingAStepIsNanoseconds() {
        val s = StepStats()
        var i = 0L
        val ns = perCallNs(1_000_000) { s.record(if (i % 3 == 0L) "ORB arms" else "Pine scripts", i++ % 900, "2026-10-10") }
        report("step timing record", ns, 5_000.0)
    }
}
