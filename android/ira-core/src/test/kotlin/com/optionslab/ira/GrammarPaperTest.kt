package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The paper arm for approved forge ideas (S2): hosting, the grammar's live entry, the refusals, the exits and the auto-stop. */
class GrammarPaperTest {
    private val day = LocalDate.of(2026, 10, 13)        // a Tuesday, after 3 Aug 2026: F&O closes 15:40

    private val orb = ShadowSim.Rules(ShadowSim.Entry.ORB_BREAK, barMin = 5, orMin = 30, fromMin = 10 * 60, untilMin = 14 * 60,
        stopPct = 0.2, targetPct = 0.5, noProgressMin = 20, timeStopMin = 60, indexStopPct = 0.003)

    private fun rec(n: Int, stage: StrategyForge.Stage, rules: ShadowSim.Rules = orb, fwd: List<Double> = List(30) { 400.0 }) =
        StrategyForge.Record(StrategyForge.Candidate(n, day.minusDays(40), "BANKNIFTY", rules, StrategyForge.ExitBlock.FIXED), stage,
            StrategyForge.Backtest(60, 100, 30_000.0, 50.0, listOf(1.0, 1.0, 1.0), 0.01, 0.0, 5_000.0, false, listOf("x" to true)), fwd, 25)

    /** BANKNIFTY's finished minutes to [until] (exclusive): flat near 50,000 to 10:30, then 50,300. */
    private fun index(until: Int): ShadowSim.Day {
        val mins = (ShadowSim.OPEN until until).toList()
        fun px(m: Int) = if (m >= 10 * 60 + 30) 50_300.0 else 50_000.0 + (m % 3) * 5
        val c = DoubleArray(mins.size) { px(mins[it]) }
        val s = ShadowSim.Series(mins.toIntArray(), c.copyOf(), DoubleArray(c.size) { c[it] + 5 }, DoubleArray(c.size) { c[it] - 5 }, c)
        return ShadowSim.Day(day, "BANKNIFTY", 30, false, s)
    }

    @Test fun onlyApprovedIdeasAreHostedAtMostThreeAndNeverOneStopped() {
        assertNull(GrammarPaper.hostRefusal(rec(1, StrategyForge.Stage.PAPER), emptyList()))
        assertTrue(GrammarPaper.hostRefusal(rec(1, StrategyForge.Stage.PROPOSED), emptyList())!!.startsWith("only an idea you approved"))
        assertTrue(GrammarPaper.hostRefusal(rec(1, StrategyForge.Stage.FORWARD), emptyList())!!.startsWith("only an idea you approved"))
        assertTrue(GrammarPaper.hostRefusal(null, emptyList())!!.contains("no such"))
        assertTrue(GrammarPaper.hostRefusal(rec(4, StrategyForge.Stage.PAPER), listOf("F1", "F2", "F3"))!!.contains("3 ideas are already on paper"))
        assertTrue(GrammarPaper.hostRefusal(rec(1, StrategyForge.Stage.PAPER), listOf("F1"))!!.contains("already on paper"))
        assertTrue(GrammarPaper.hostRefusal(rec(1, StrategyForge.Stage.PAPER), emptyList(), listOf("F1"))!!.contains("stopped"))
        assertTrue(GrammarPaper.hostRefusal(rec(1, StrategyForge.Stage.PAPER, orb.copy(stopPct = null)), emptyList())!!.contains("not valid"))
        assertEquals(400.0 to 30, GrammarPaper.expectation(rec(1, StrategyForge.Stage.PAPER)))
        assertEquals(500.0, GrammarPaper.expectation(rec(1, StrategyForge.Stage.PAPER, fwd = emptyList())).first, 1e-9)
    }

    @Test fun theGrammarsOwnEntryFiresAtTheBarThatJustClosedOnly() {
        // 10:35: the 10:30-10:34 bar closed above the first 30 minutes' range: the call, decided at 10:35, index 50,300.
        val s = GrammarPaper.signal(orb, index(10 * 60 + 35), 10 * 60 + 35, 0, -1)
        assertNotNull(s)
        assertEquals(1, s.dir); assertEquals(10 * 60 + 35, s.decisionMin); assertEquals(50_300.0, s.indexClose, 1e-9)
        // The same decision is not acted on twice, nor a second one beyond the rules' trades a day.
        assertNull(GrammarPaper.signal(orb, index(10 * 60 + 36), 10 * 60 + 36, 0, s.decisionMin))
        assertNull(GrammarPaper.signal(orb, index(10 * 60 + 36), 10 * 60 + 36, 1, -1))
        // A watch two minutes late still takes it; four minutes late lets it go.
        assertNotNull(GrammarPaper.signal(orb, index(10 * 60 + 37), 10 * 60 + 37, 0, -1))
        assertNull(GrammarPaper.signal(orb, index(10 * 60 + 39), 10 * 60 + 39, 0, -1))
        // Before the break: nothing.
        assertNull(GrammarPaper.signal(orb, index(10 * 60 + 30), 10 * 60 + 30, 0, -1))
        // The strike: at the money, or one in the money as the grammar says.
        assertEquals(50_300.0, GrammarPaper.strike(50_300.0, 100, 1, 0), 1e-9)
        assertEquals(50_200.0, GrammarPaper.strike(50_300.0, 100, 1, 1), 1e-9)
        assertEquals(50_400.0, GrammarPaper.strike(50_300.0, 100, -1, 1), 1e-9)
    }

    @Test fun entriesRespectTheGuardsTheCutoffTheHoursAndExpiry() {
        val m = 10 * 60 + 35
        assertNull(GrammarPaper.entryRefusal(day, m, orb, 14 * 60 + 30, false, false, null, false))
        assertEquals("kill_switch", GrammarPaper.entryRefusal(day, m, orb, null, true, false, null, false))
        assertEquals("stopped_today", GrammarPaper.entryRefusal(day, m, orb, null, false, true, null, false))
        assertTrue(GrammarPaper.entryRefusal(day, m, orb, null, false, false, "locked for today", false)!!.startsWith("day_lock"))
        assertEquals("fo_closed", GrammarPaper.entryRefusal(day, 9 * 60 + 10, orb, null, false, false, null, false))
        assertEquals("fo_closed", GrammarPaper.entryRefusal(day, 15 * 60 + 45, orb, null, false, false, null, false))
        // The rules' own last entry (14:00), and the account's cut-off when it is earlier.
        assertTrue(GrammarPaper.entryRefusal(day, 14 * 60 + 1, orb, 14 * 60 + 30, false, false, null, false)!!.startsWith("after_cutoff 14:00"))
        assertTrue(GrammarPaper.entryRefusal(day, 13 * 60 + 31, orb, 13 * 60 + 30, false, false, null, false)!!.startsWith("after_cutoff 13:30"))
        // Expiry day: entries inside the rules' window go (the held option is out by 15:05); nothing after the cut-off.
        val late = orb.copy(untilMin = 14 * 60 + 30)
        assertNull(GrammarPaper.entryRefusal(day, 14 * 60 + 20, late, 16 * 60, false, false, null, true))
        assertTrue(GrammarPaper.entryRefusal(day, 14 * 60 + 31, late, 16 * 60, false, false, null, true)!!.startsWith("after_cutoff"))
    }

    @Test fun theExitsAreTheGrammarsOnTheWick() {
        val h = GrammarPaper.Held(100.0, 10 * 60 + 35, 1, 50_300.0, false)
        fun bars(vararg x: Triple<Double, Double, Double>) = x.mapIndexed { i, (hi, lo, c) -> GrammarPaper.Minute(10 * 60 + 35 + i, hi, lo, c) }
        assertNull(GrammarPaper.exit(orb, day, h, bars(Triple(104.0, 99.0, 103.0)), 50_310.0, 10 * 60 + 37))
        // The stop on a minute's low (before the target in the same minute).
        assertEquals("stop", GrammarPaper.exit(orb, day, h, bars(Triple(104.0, 99.0, 103.0), Triple(160.0, 79.0, 90.0)), 50_310.0, 10 * 60 + 38))
        assertEquals("target", GrammarPaper.exit(orb, day, h, bars(Triple(151.0, 99.0, 150.0)), 50_310.0, 10 * 60 + 37))
        // No progress: not up 5% after 20 minutes.
        val flat = (0 until 20).map { GrammarPaper.Minute(10 * 60 + 35 + it, 101.0, 99.0, 100.0) }
        assertEquals("no_progress_20", GrammarPaper.exit(orb, day, h, flat, 50_300.0, 10 * 60 + 56))
        assertNull(GrammarPaper.exit(orb, day, h, flat.take(19), 50_300.0, 10 * 60 + 55))
        // The index-level exit: 0.3% back against the entry.
        assertEquals("index_stop", GrammarPaper.exit(orb, day, h, bars(Triple(104.0, 99.0, 103.0)), 50_300.0 * (1 - 0.0031), 10 * 60 + 37))
        // The square-off (15:10), and on expiry day the expiry square-off's 15:05.
        assertEquals("square_off_1510", GrammarPaper.exit(orb, day, h, emptyList(), null, 15 * 60 + 10))
        assertNull(GrammarPaper.exit(orb, day, h, emptyList(), null, 15 * 60 + 4))
        assertEquals("expiry_1505", GrammarPaper.exit(orb, day, h.copy(expiryToday = true), emptyList(), null, 15 * 60 + 5))
        // Never past F&O's close (before 3 Aug 2026 it closed 15:30: still 15:10 here).
        assertEquals(15 * 60 + 10, GrammarPaper.squareOffMin(orb, LocalDate.of(2026, 7, 1), false))
        assertEquals(15 * 60 + 5, GrammarPaper.squareOffMin(orb, day, true))
    }

    @Test fun aRecordWorseThanItsForwardTestIsStopped() {
        assertNull(GrammarPaper.judge(List(4) { -3_000.0 }, 400.0))
        // In line with its forward test: kept.
        val ok = listOf(600.0, -200.0, 900.0, 300.0, -100.0, 700.0, 200.0)
        val v = GrammarPaper.judge(ok, 400.0)!!
        assertTrue(!v.stop, v.why)
        // Losing steadily where the forward test made Rs 400 a trade: stopped, and the words say why.
        val bad = listOf(-900.0, -1_100.0, -700.0, -1_300.0, -800.0, -1_000.0, -1_200.0, -900.0)
        val w = GrammarPaper.judge(bad, 400.0)!!
        assertTrue(w.stop, w.why)
        assertTrue(w.why.startsWith("worse than its forward test") && "+Rs 400" in w.why, w.why)
        assertTrue(w.pWorse <= GrammarPaper.STOP_ALPHA)
        // A fall beyond the capital rule stops it whatever the test says.
        val crash = listOf(5_000.0, 5_000.0, -12_000.0, -12_000.0, 3_000.0)
        assertTrue(GrammarPaper.judge(crash, -100_000.0)!!.why.contains("capital rule"))
    }
}
