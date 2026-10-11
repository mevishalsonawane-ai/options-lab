package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Jarvis's lessons from the lab cite their counts, and his answers about the lab are honest and routed as asked. */
class LabLessonsTest {
    private val day = LocalDate.of(2026, 10, 11)

    private val forge = StrategyForge.Book(tried = 120, passed = 9, retired = 23, proposed = 1, rejected = 111,
        blocks = mapOf(
            StrategyForge.key(ShadowSim.Entry.VWAP_RECLAIM) to StrategyForge.BlockStat(tried = 40, btPassed = 24, fwdPassed = 1, fwdFailed = 23),
            StrategyForge.key(ShadowSim.Entry.GAP_FADE) to StrategyForge.BlockStat(tried = 41, btPassed = 0),
            StrategyForge.key(ShadowSim.Entry.ORB_BREAK) to StrategyForge.BlockStat(tried = 12, btPassed = 3)),
        filterHelp = mapOf(StrategyForge.key(ShadowSim.Filter.EXPIRY_ONLY) to (6 to 8)))

    private val variants: VariantLab.Book by lazy {
        val s = VariantLab.strategy("liquidity")!!
        val base = s.defaults
        val wider = (1..4).map { i ->
            VariantLab.Variant("V$i", "liquidity", base + ("stopPct" to 0.2), base, day, 1.0, VariantLab.Status.RETIRED, List(25) { -300.0 },
                why = "worse than the current rules")
        }
        VariantLab.Book(variants = wider, tried = 4, retiredCount = 4)
    }

    @Test fun lessonsCiteTheirCounts() {
        val ls = LabLessons.lessons(forge, variants)
        val text = ls.joinToString("\n") { it.text }
        assertTrue("Ideas using VWAP reclaim failed 23 of 24 forward tests." in text, text)
        assertTrue("Ideas using gap fade failed the backtest gates in all 41 tries." in text, text)
        assertTrue("Ideas using ORB break passed the backtest gates in 3 of 12 tries (9 failed)." in text, text)
        assertTrue("The expiry days only filter helped in 6 of 8 survivors." in text, text)
        assertTrue("Liquidity 15+5's best stop so far is 15%; 4 variants with a wider stop lost." in text, text)
        assertTrue("Of 120 new ideas tried, 9 passed the backtest gates, 23 retired after a forward test and 1 reached you" in text, text)
        val vwap = ls.first { it.id == "block:" + StrategyForge.key(ShadowSim.Entry.VWAP_RECLAIM) }
        assertEquals(23, vwap.k); assertEquals(24, vwap.n)
    }

    @Test fun theForgeReadsTheSameCountsBackAsWeights() {
        val w = StrategyForge.weights(forge)
        // Gap fade failed every try: it is drawn less than an untried block, but its weight is never zero.
        val gap = StrategyForge.mean(w.getValue(StrategyForge.key(ShadowSim.Entry.GAP_FADE)))
        val untried = StrategyForge.mean(w.getValue(StrategyForge.key(ShadowSim.Entry.SQUEEZE_BREAK)))
        assertTrue(gap < untried && gap > 0.0)
        assertTrue(LabLessons.exploring(forge).isNotEmpty())
        assertTrue(LabLessons.failing(forge).first().startsWith("gap fade: failed 41 of 41"))
    }

    // ---- Jarvis ----------------------------------------------------------------------------------------------------------

    @Test fun theQuestions() {
        val want = mapOf(
            "how are my strategies improving?" to LabTalk.Kind.IMPROVING, "are my strategies getting better" to LabTalk.Kind.IMPROVING,
            "how is the strategy lab doing" to LabTalk.Kind.IMPROVING,
            "did you find any new strategy?" to LabTalk.Kind.FOUND, "have you found a new strategy yet" to LabTalk.Kind.FOUND,
            "any new strategies" to LabTalk.Kind.FOUND,
            "what is candidate F12?" to LabTalk.Kind.CANDIDATE, "what is candidate 12" to LabTalk.Kind.CANDIDATE, "tell me about f7" to LabTalk.Kind.CANDIDATE,
            "how many ideas have you tried?" to LabTalk.Kind.TRIED, "show me the graveyard" to LabTalk.Kind.TRIED,
            "what have you learned from the new strategies?" to LabTalk.Kind.LEARNED, "what did you learn from the strategy lab" to LabTalk.Kind.LEARNED,
            "which ideas keep failing?" to LabTalk.Kind.FAILING, "what keeps failing" to LabTalk.Kind.FAILING,
            "what are you exploring next?" to LabTalk.Kind.EXPLORING, "what will you try next" to LabTalk.Kind.EXPLORING,
        )
        for ((q, k) in want) assertEquals(k, LabTalk.asked(q)?.kind, q)
        assertEquals("F12", LabTalk.asked("what is candidate F12?")?.id)
        assertEquals("F7", LabTalk.asked("tell me about f7")?.id)
        // Not these: Jarvis's own lessons, the bots' trades, the market.
        for (q in listOf("what have you learned", "what have you learned from the agents", "how are my bots doing", "what's new", "how is nifty",
            "what did you change today", "how is autopilot doing", "buy nifty", "what is the vwap"))
            assertNull(LabTalk.asked(q), q)
    }

    @Test fun theAnswersAreHonestAndCiteCounts() {
        val f = forge.copy(records = listOf(StrategyForge.Record(StrategyForge.Candidate(12, day, "BANKNIFTY",
            ShadowSim.Rules(ShadowSim.Entry.ORB_BREAK, stopPct = 0.2, targetPct = 0.5), StrategyForge.ExitBlock.FIXED), StrategyForge.Stage.FORWARD,
            StrategyForge.Backtest(55, 300, 12_000.0, 48.0, listOf(5_000.0, 4_000.0, 3_000.0), 0.01, -40.0, 6_000.0, false, listOf("x" to true)),
            fwd = listOf(500.0, -200.0), fwdDays = 4, why = "passed every gate")))
        val found = LabTalk.answer(LabTalk.Ask(LabTalk.Kind.FOUND), f, variants)
        assertTrue("none has passed its forward test" in found && "F12" in found, found)
        val cand = LabTalk.answer(LabTalk.Ask(LabTalk.Kind.CANDIDATE, "F12"), f, variants)
        for (w in listOf("BANKNIFTY, 1 lot, buys", "Backtest: 55 trades on 300 stored days", "+Rs 12,000", "random twin p 0.010", "Forward: 2 trades over 4 days"))
            assertTrue(w in cand, "$w in $cand")
        assertTrue("no candidate called F99" in LabTalk.answer(LabTalk.Ask(LabTalk.Kind.CANDIDATE, "F99"), f, variants))
        val tried = LabTalk.answer(LabTalk.Ask(LabTalk.Kind.TRIED), f, variants)
        assertTrue("120 new ideas tried" in tried && "111 failed the backtest gates" in tried && "4 variants" in tried, tried)
        assertTrue("23 of 24 forward tests" in LabTalk.answer(LabTalk.Ask(LabTalk.Kind.LEARNED), f, variants))
        assertTrue("1 pick in 10 stays random" in LabTalk.answer(LabTalk.Ask(LabTalk.Kind.FAILING), f, variants))
        assertTrue("1 in 10 is chosen at random" in LabTalk.answer(LabTalk.Ask(LabTalk.Kind.EXPLORING), f, variants))
        assertTrue("No strategy is running" in LabTalk.answer(LabTalk.Ask(LabTalk.Kind.IMPROVING), f, VariantLab.Book()))
        val running = VariantLab.Book(runs = mapOf("orb" to VariantLab.Run.PAPER), tried = 3, retiredCount = 1)
        assertTrue("ORB (paper): 0 variants shadowing" in LabTalk.answer(LabTalk.Ask(LabTalk.Kind.IMPROVING), f, running))
        // Never a word of acting.
        for (k in LabTalk.Kind.entries) assertTrue(!Regex("(?i)\\b(placed|bought|sold an?|armed)\\b").containsMatchIn(
            LabTalk.answer(LabTalk.Ask(k, "F12"), f, variants)), k.name)
    }
}
