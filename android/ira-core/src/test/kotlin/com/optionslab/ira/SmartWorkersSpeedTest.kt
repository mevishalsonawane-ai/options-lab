package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The hot paths the smart workers touch (10 Oct: "no performance regression"): a finding posted, the board read, the entry
 * brake's whole decision, the scheduler's answer and a health sample - each timed on the JVM with a generous bound, so a big
 * regression fails here. Printed for the record (per call, after warm-up).
 */
class SmartWorkersSpeedTest {
    private fun perCallNs(n: Int, f: () -> Unit): Double {
        repeat(n / 5) { f() }
        val t0 = System.nanoTime()
        repeat(n) { f() }
        return (System.nanoTime() - t0).toDouble() / n
    }

    private fun report(what: String, ns: Double, boundNs: Double) {
        println("speed: $what %.0f ns a call (bound %.0f ns)".format(ns, boundNs))
        assertTrue(ns < boundNs, "$what took %.0f ns a call".format(ns))
    }

    private val t0 = 1_760_000_000_000L

    @Test fun postingAFindingIsNanosecondsAndReadingTheFullBoardMicroseconds() {
        val bus = Findings.Bus()
        var i = 0L
        val post = perCallNs(200_000) {
            bus.post(Findings.Finding("ORB", Findings.Kind.BREAK, if (i % 2 == 0L) "NIFTY" else "BANKNIFTY", 1, null, 60, t0 + i++))
            if (i % 1_000 == 0L) bus.drainLines()
        }
        report("finding posted (ring of 512, a subscriber-free bus)", post, 5_000.0)
        val read = perCallNs(20_000) { bus.recent(t0 + i, "BANKNIFTY") }
        report("board read (512 standing, one index)", read, 200_000.0)
    }

    @Test fun theEntryBrakesWholeDecisionIsMicroseconds() {
        val day = java.time.LocalDate.of(2026, 12, 4)
        val ctx = MarketBrain.Context(windows = MarketBrain.windows(day, listOf("RBI policy", "US CPI"), listOf("NIFTY")),
            traps = mapOf("BANKNIFTY" to setOf("PULL_BID")))
        val findings = List(100) { Findings.Finding("w${it % 7}", Findings.Kind.BREAK, "BANKNIFTY", if (it % 3 == 0) 1 else -1, null, 70, t0 + it) }
        val ns = perCallNs(50_000) {
            EntryBrake.decide(ctx, MarketBrain.Policy(), findings, Findings.Policy(), "orb", "BANKNIFTY", 1, t0 + 200)
        }
        report("entry brake (brain pause + reactions + consensus, 100 findings)", ns, 200_000.0)
    }

    @Test fun theSchedulersAnswerAndAHealthSampleAreNanoseconds() {
        var now = t0
        val s = WorkerWake.Scheduler { now }
        listOf("ORB arms", "Night (R3)", "VIX divergence", "Pine scripts", "MCX paper arms", "strategies").forEach {
            s.register(WorkerWake.Spec(it, setOf(WorkerWake.Cause.CANDLE_1M, WorkerWake.Cause.CANDLE_5M, WorkerWake.Cause.ORDER_UPDATE), 300_000L, 10_000L))
        }
        val ns = perCallNs(500_000) { now += 1_000; if (s.decide("ORB arms").run) s.ran("ORB arms") }
        report("scheduler decision", ns, 5_000.0)
        val m = SelfHeal.Monitor { now }
        val h = perCallNs(500_000) { m.rest(ok = true, latencyMs = 120) }
        report("health sample (one Zerodha answer)", h, 5_000.0)
    }
}
