package com.optionslab.ira

import java.util.Random
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Self-review and auto-park ([DriftReview]): drift found with few false alarms; parking only ever lowers risk. */
class DriftReviewTest {
    private val liq = ForwardCheck.LIQUIDITY
    private val solo = ForwardCheck.SOLO

    /** Normal trades with the expectation's mean and spread. */
    private fun normal(e: ForwardCheck.Expectation, n: Int, rnd: Random, shift: Double = 0.0) =
        List(n) { e.mean + shift + e.sd * rnd.nextGaussian() }

    /**
     * Skewed trades like an option buyer's: a win rate p, wins larger than losses, matched to the mean and spread (two points
     * with a little noise): win w, loss l with p·w + (1−p)·l = mean and p(1−p)(w−l)² = sd² (noise taking 10% of the variance).
     */
    private fun skewed(e: ForwardCheck.Expectation, n: Int, rnd: Random, shift: Double = 0.0): List<Double> {
        val p = e.winRate
        val core = e.sd * sqrt(0.9)
        val gap = core / sqrt(p * (1 - p))
        val l = e.mean - p * gap
        val w = l + gap
        return List(n) { (if (rnd.nextDouble() < p) w else l) + shift + e.sd * sqrt(0.1) * rnd.nextGaussian() }
    }

    /** Share of [runs] runs of [n] trades where any look (after each trade from the 15th) says drifting. */
    private fun alarmRate(e: ForwardCheck.Expectation, runs: Int, n: Int, gen: (Random) -> List<Double>): Double {
        val rnd = Random(20261010)
        var hits = 0
        repeat(runs) {
            val x = gen(rnd)
            if ((DriftReview.MIN_TRADES..n).any { k -> DriftReview.review(e, x.take(k), live = false).verdict == DriftReview.Verdict.DRIFTING }) hits++
        }
        return hits.toDouble() / runs
    }

    @Test fun atTheBacktestFalseAlarmsStayAtOrUnderFivePercentOverAHundredTrades() {
        for (e in listOf(liq, solo)) {
            val rn = alarmRate(e, 600, 100) { normal(e, 100, it) }
            val rs = alarmRate(e, 600, 100) { skewed(e, 100, it) }
            println("false alarms within 100 trades, ${e.key}: normal ${"%.3f".format(rn)}, skewed ${"%.3f".format(rs)}")
            assertTrue(rn <= 0.05, "${e.key} normal: $rn")
            assertTrue(rs <= 0.05, "${e.key} skewed: $rs")
        }
    }

    @Test fun aStrategyAFullSpreadWorseIsCaughtWithinFiftyTradesMostOfTheTime() {
        val caught = alarmRate(liq, 300, 50) { normal(liq, 50, it, shift = -liq.sd) }
        assertTrue(caught >= 0.9, "caught $caught")
        val half = alarmRate(liq, 300, 100) { skewed(liq, 100, it, shift = -liq.sd / 2) }
        assertTrue(half >= 0.5, "half a spread worse caught in $half of runs within 100 trades")
    }

    @Test fun tooFewTradesIsNeverDriftingAndSaysHowManyAreNeeded() {
        val r = DriftReview.review(liq, List(14) { -5_000.0 }, live = true)
        assertEquals(DriftReview.Verdict.TOO_FEW, r.verdict)
        assertEquals(DriftReview.Action.NONE, DriftReview.action(r))
        assertTrue("14 of the 15" in r.reasons.single())
    }

    @Test fun driftingLiveParksToPaperAndAsksPaperOnlyWarnsByDefault() {
        val bad = List(30) { -2_000.0 }
        val live = DriftReview.review(liq, bad, live = true)
        assertEquals(DriftReview.Verdict.DRIFTING, live.verdict)
        assertEquals(DriftReview.Action.PARK_TO_PAPER, DriftReview.action(live))
        val ask = DriftReview.ask(live, DriftReview.Action.PARK_TO_PAPER)
        for (w in listOf("Liquidity 15+5 (live) is drifting", "parked", "PAPER", "still managed to their exits", "your PIN", "I never will", "last 20"))
            assertTrue(w in ask, "$w in $ask")
        val paper = DriftReview.review(liq, bad, live = false)
        assertEquals(DriftReview.Action.WARN, DriftReview.action(paper))
        assertEquals(DriftReview.Action.PAUSE, DriftReview.action(paper, DriftReview.PaperPolicy.PAUSE))
        assertTrue("Nothing was changed" in DriftReview.ask(paper, DriftReview.Action.WARN))
        // Already parked: nothing new is done or asked; on track: nothing.
        assertEquals(DriftReview.Action.NONE, DriftReview.action(live, alreadyParked = true))
        val ok = DriftReview.review(liq, List(30) { liq.mean }, live = true)
        assertEquals(DriftReview.Verdict.ON_TRACK, ok.verdict)
        assertEquals(DriftReview.Action.NONE, DriftReview.action(ok))
    }

    @Test fun noVerdictEverLeadsToMoreRisk() {
        // Every action is NONE, WARN, PAUSE or PARK_TO_PAPER: there is no un-park, arm, size up or go-live action at all.
        assertEquals(setOf("NONE", "WARN", "PAUSE", "PARK_TO_PAPER"), DriftReview.Action.entries.map { it.name }.toSet())
        val rnd = Random(7)
        repeat(200) {
            val r = DriftReview.review(liq, normal(liq, 15 + rnd.nextInt(60), rnd, shift = rnd.nextGaussian() * liq.sd), rnd.nextBoolean())
            val a = DriftReview.action(r, if (rnd.nextBoolean()) DriftReview.PaperPolicy.PAUSE else DriftReview.PaperPolicy.WARN)
            if (r.live) assertTrue(a == DriftReview.Action.NONE || a == DriftReview.Action.PARK_TO_PAPER)
            else assertTrue(a != DriftReview.Action.PARK_TO_PAPER)
        }
    }

    @Test fun aLotteryIsJudgedByDrawdownOnly() {
        val hero = ForwardCheck.HERO
        val losing = List(20) { -5_000.0 }                    // -100k: past 1.5x its worst (-53k)
        val r = DriftReview.review(hero, losing, live = false)
        assertEquals(DriftReview.Verdict.DRIFTING, r.verdict)
        assertTrue(r.windows.all { it.z == null })
        assertTrue(r.reasons.single().contains("drawdown"))
        val mild = DriftReview.review(hero, List(15) { if (it % 3 == 0) 10_000.0 else -5_000.0 }, live = false)
        assertEquals(DriftReview.Verdict.ON_TRACK, mild.verdict)
    }

    @Test fun chipsLinesAndJarvis() {
        val r = DriftReview.review(liq, List(25) { liq.mean }, live = false)
        assertEquals("on track", DriftReview.chip(r, parked = false))
        assertEquals("parked", DriftReview.chip(r, parked = true))
        assertEquals("too few trades", DriftReview.chip(null, parked = false))
        assertTrue(DriftReview.asked("how are my strategies doing vs backtest?"))
        assertTrue(DriftReview.asked("are the bots drifting from the research"))
        assertFalse(DriftReview.asked("what is the backtest"))
        assertFalse(DriftReview.asked("what are the bots saying about banknifty"))
        val a = DriftReview.answer(listOf(r), emptySet(), "10 Oct")
        assertTrue(a.startsWith("Against their backtests (reviewed 10 Oct):"), a)
        assertTrue("Liquidity 15+5 (paper, 25 trades): on track" in a, a)
        assertTrue("never goes back to live by itself" in a)
        assertTrue(DriftReview.answer(emptyList(), emptySet(), null).contains("after 15:45"))
    }
}
