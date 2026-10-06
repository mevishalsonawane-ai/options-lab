package com.optionslab.ira

import com.optionslab.engine.orb.SoloMidday
import com.optionslab.ira.ForwardWatch.Category
import com.optionslab.ira.ForwardWatch.Told
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ForwardWatchTest {
    private val start: LocalDate = LocalDate.of(2026, 10, 6)

    private fun trades(nets: List<Double>) = nets.mapIndexed { i, n -> ForwardCheck.Trade(start.plusDays(i.toLong()), n) }
    private fun check(e: ForwardCheck.Expectation, nets: List<Double>) = ForwardCheck.check(e, trades(nets))
    private fun liq(vararg runs: Pair<Int, Double>) = check(ForwardCheck.LIQUIDITY, runs.flatMap { (n, x) -> List(n) { x } })
    private fun solo(vararg runs: Pair<Int, Double>) = check(ForwardCheck.SOLO, runs.flatMap { (n, x) -> List(n) { x } })

    // ---- the category -------------------------------------------------------------------------------------------

    @Test fun theCategoryFollowsTheVerdictWithTheCusumFirst() {
        assertEquals(Category.TOO_FEW, ForwardWatch.category(liq(5 to 300.0)))
        assertEquals(Category.TOO_FEW, ForwardWatch.category(liq()))
        assertEquals(Category.IN_LINE, ForwardWatch.category(liq(20 to 300.0)))
        assertEquals(Category.ABOVE, ForwardWatch.category(liq(20 to 1_500.0)))
        // −₹600 a trade over 30: under the band's −₹526, and too mild for the CUSUM (z −0.4, under its 0.5 allowance).
        val below = liq(30 to -600.0)
        assertTrue(!below.alarm)
        assertEquals(Category.BELOW, ForwardWatch.category(below))
        // −₹3,000 a trade: the CUSUM alarms by the 5th trade - before 20, and before the band.
        val run = liq(5 to -3_000.0)
        assertEquals(5, run.alarmAt)
        assertEquals(Category.RUN_BELOW, ForwardWatch.category(run))
        // Hero (a lottery): a drawdown deeper than its research's worst, never the CUSUM.
        val hero = check(ForwardCheck.HERO, List(9) { -6_000.0 })
        assertEquals(Category.DEEPER, ForwardWatch.category(hero))
        assertEquals(Category.TOO_FEW, ForwardWatch.category(check(ForwardCheck.HERO, List(9) { -1_000.0 })))
        assertEquals(Category.IN_LINE, ForwardWatch.category(check(ForwardCheck.HERO, List(20) { -1_000.0 })))
    }

    @Test fun milestonesAreTwentyForEveryArmAndFortyAndSixtyForSolo() {
        assertEquals(0, ForwardWatch.milestone(ForwardCheck.LIQUIDITY, 19))
        assertEquals(20, ForwardWatch.milestone(ForwardCheck.LIQUIDITY, 20))
        assertEquals(20, ForwardWatch.milestone(ForwardCheck.LIQUIDITY, 75))
        assertEquals(20, ForwardWatch.milestone(ForwardCheck.HERO, 45))
        assertEquals(0, ForwardWatch.milestone(ForwardCheck.SOLO, 0))
        assertEquals(20, ForwardWatch.milestone(ForwardCheck.SOLO, 39))
        assertEquals(40, ForwardWatch.milestone(ForwardCheck.SOLO, 40))
        assertEquals(60, ForwardWatch.milestone(ForwardCheck.SOLO, 60))
        assertEquals(60, ForwardWatch.milestone(ForwardCheck.SOLO, 90))
        assertEquals(listOf(20, 40, SoloMidday.FORWARD_TRADES), ForwardWatch.SOLO_MILESTONES)
    }

    // ---- what is kept ---------------------------------------------------------------------------------------------

    @Test fun toldIsKeptAsTextAndReadBack() {
        assertEquals("BELOW|20", Told(Category.BELOW, 20).encode())
        for (c in Category.entries) for (m in listOf(0, 20, 40, 60)) assertEquals(Told(c, m), Told.decode(Told(c, m).encode()))
        assertEquals(Told(Category.IN_LINE, 0), Told.decode("IN_LINE"))
        assertEquals(Told(Category.IN_LINE, 0), Told.decode("IN_LINE|x"))
        assertEquals(Told(Category.IN_LINE, 0), Told.decode("IN_LINE|-4"))
        assertNull(Told.decode(null))
        assertNull(Told.decode(""))
        assertNull(Told.decode("SIDEWAYS|20"))
        assertEquals("jarvis.forward.told.liquidity", ForwardWatch.key(ForwardCheck.LIQUIDITY))
        assertEquals("jarvis.forward.told.solo", ForwardWatch.key(ForwardCheck.SOLO))
        assertEquals("jarvis.forward.told.hero", ForwardWatch.key(ForwardCheck.HERO))
        // Not a secret and nothing that trades: it travels in a backup with the records it was read from.
        assertTrue(Upkeep.carried(ForwardWatch.key(ForwardCheck.SOLO)))
    }

    // ---- when a word is said --------------------------------------------------------------------------------------

    @Test fun aFirstLookWithTooFewIsKeptSilently() {
        val d = assertNotNull(ForwardWatch.decide(liq(3 to 100.0), null))
        assertEquals(Told(Category.TOO_FEW, 0), d.told)
        assertNull(d.text)
        // Then the same again: nothing new.
        assertNull(ForwardWatch.decide(liq(4 to 100.0), d.told))
    }

    @Test fun twentyTradesInLineIsTheFirstNote() {
        val d = assertNotNull(ForwardWatch.decide(liq(20 to 300.0), Told(Category.TOO_FEW)))
        assertEquals(Told(Category.IN_LINE, 20), d.told)
        val t = assertNotNull(d.text)
        assertTrue(t.startsWith("Forward-test watch - Liquidity 15+5: now in line with the backtest (was too few trades to judge)."), t)
        assertTrue("20 trades: enough to judge against the backtest." in t, t)
        assertTrue("20 trades, ₹300 a trade per lot vs the backtest's ₹228 (its band for 20 trades: −₹696 to ₹1,151)." in t, t)
        assertTrue(t.endsWith("Within what the backtest allows for 20 trades - nothing to do; nothing has been changed."), t)
        // The next trades in line: nothing more.
        assertNull(ForwardWatch.decide(liq(25 to 300.0), d.told))
    }

    @Test fun inLineToBelowIsToldAndBackToInLineToo() {
        val d = assertNotNull(ForwardWatch.decide(liq(30 to -600.0), Told(Category.IN_LINE, 20)))
        assertEquals(Told(Category.BELOW, 20), d.told)
        val t = assertNotNull(d.text)
        assertTrue(t.startsWith("Forward-test watch - Liquidity 15+5: now below expectation (was in line with the backtest)."), t)
        assertTrue("30 trades, −₹600 a trade per lot vs the backtest's ₹228" in t, t)
        assertTrue("Below what the backtest allows for 30 trades - worth watching; nothing has been changed." in t, t)
        assertFalse("enough to judge" in t, t)
        // Back in line.
        val back = assertNotNull(ForwardWatch.decide(liq(30 to -600.0, 30 to 800.0), d.told))
        assertEquals(Told(Category.IN_LINE, 20), back.told)
        assertTrue(assertNotNull(back.text).contains("now in line with the backtest (was below expectation)"))
        assertTrue(back.text!!.contains("Back within what the backtest allows for 60 trades - the research describes it again; nothing has been changed."))
    }

    @Test fun theCusumAlarmIsToldBeforeTwentyTrades() {
        val d = assertNotNull(ForwardWatch.decide(liq(5 to -3_000.0), Told(Category.TOO_FEW)))
        assertEquals(Told(Category.RUN_BELOW, 0), d.told)
        val t = assertNotNull(d.text)
        assertTrue(t.startsWith("Forward-test watch - Liquidity 15+5: now a sustained run below the backtest (was too few trades to judge)."), t)
        assertTrue("since trade 5" in t && "worth watching; nothing has been changed." in t, t)
        // A first look that already alarmed is told too.
        assertNotNull(ForwardWatch.decide(liq(5 to -3_000.0), null)?.text)
        // Still alarmed, 20 trades on: the milestone is told ("still").
        val m = assertNotNull(ForwardWatch.decide(liq(5 to -3_000.0, 15 to 200.0), d.told))
        assertEquals(Told(Category.RUN_BELOW, 20), m.told)
        assertTrue(assertNotNull(m.text).startsWith("Forward-test watch - Liquidity 15+5: still a sustained run below the backtest."), m.text)
    }

    @Test fun neverTellsADropIntoTooFewAndKeepsTheHighestMilestone() {
        // The record shrank (a book trimmed): kept, never said, the milestone kept.
        val d = assertNotNull(ForwardWatch.decide(liq(5 to 300.0), Told(Category.IN_LINE, 20)))
        assertEquals(Told(Category.TOO_FEW, 20), d.told)
        assertNull(d.text)
        // Back to 20 in line: a change, but the milestone was told before.
        val again = assertNotNull(ForwardWatch.decide(liq(20 to 300.0), d.told))
        assertEquals(Told(Category.IN_LINE, 20), again.told)
        assertFalse(assertNotNull(again.text).contains("enough to judge"))
    }

    @Test fun soloIsToldAtTwentyFortyAndSixtyWithItsOwnLine() {
        val bar = ForwardWatch.SoloBar(-8_200.0)
        val at20 = assertNotNull(ForwardWatch.decide(solo(20 to 100.0), Told(Category.TOO_FEW), bar))
        assertTrue(assertNotNull(at20.text).startsWith("Forward-test watch - Solo (midday): now in line with the backtest"), at20.text)
        assertTrue(at20.text!!.endsWith("Solo's own switch-off line: ₹8,200 below its best now, ₹16,800 from the −₹25,000 line where Solo switches itself off (its own rule, untouched)."), at20.text)
        assertNull(ForwardWatch.decide(solo(39 to 100.0), at20.told, bar))
        val at40 = assertNotNull(ForwardWatch.decide(solo(40 to 100.0), at20.told, bar))
        assertEquals(Told(Category.IN_LINE, 40), at40.told)
        assertTrue(assertNotNull(at40.text).contains("Solo (midday): still in line with the backtest. 40 of Solo's 60 forward-test trades."), at40.text)
        val at60 = assertNotNull(ForwardWatch.decide(solo(60 to 100.0), at40.told, ForwardWatch.SoloBar(0.0)))
        assertEquals(Told(Category.IN_LINE, 60), at60.told)
        assertTrue(assertNotNull(at60.text).contains("60 trades: Solo's forward test is complete"), at60.text)
        assertTrue(at60.text!!.contains("at its best now, ₹25,000 from the −₹25,000"), at60.text)
        // Another arm's note never carries Solo's line.
        assertFalse(assertNotNull(ForwardWatch.decide(liq(20 to 300.0), null, bar)?.text).contains("switch-off"))
        // Hero and Liquidity have no 40 or 60.
        assertNull(ForwardWatch.decide(liq(40 to 300.0), Told(Category.IN_LINE, 20)))
    }

    @Test fun heroIsJudgedByItsDrawdown() {
        val d = assertNotNull(ForwardWatch.decide(check(ForwardCheck.HERO, List(9) { -6_000.0 }), Told(Category.TOO_FEW)))
        val t = assertNotNull(d.text)
        assertTrue(t.startsWith("Forward-test watch - Hero (expiry): now a drawdown deeper than the backtest's worst (was too few trades to judge)."), t)
        assertTrue("9 trades, −₹6,000 a trade per ₹5,000 ticket vs the backtest's ₹2,578; drawdown −₹54,000 vs the backtest's worst −₹53,035 (a lottery: judged by drawdown, not mean)." in t, t)
        assertTrue(t.endsWith("the research no longer describes it; worth watching; nothing has been changed."), t)
        val ok = assertNotNull(ForwardWatch.decide(check(ForwardCheck.HERO, List(20) { -1_000.0 }), Told(Category.TOO_FEW)))
        assertTrue(assertNotNull(ok.text).contains("Its drawdown is within the backtest's worst - nothing to do"), ok.text)
    }

    @Test fun aboveIsToldPlainly() {
        val t = assertNotNull(ForwardWatch.decide(liq(20 to 1_500.0), Told(Category.TOO_FEW))?.text)
        assertTrue("now above expectation" in t && "better than researched so far (it may not last); nothing has been changed." in t, t)
    }

    // ---- Solo's own line ------------------------------------------------------------------------------------------

    @Test fun solosLineIsCountedFromItsBaselineNow() {
        assertEquals(0.0, ForwardWatch.soloBar(emptyList()).drawdown)
        assertEquals(25_000.0, ForwardWatch.soloBar(emptyList()).room)
        // Up 5,000, down 8,000, up 1,000: 7,000 below its best now (the worst was 8,000).
        val b = ForwardWatch.soloBar(listOf(5_000.0, -8_000.0, 1_000.0))
        assertEquals(-7_000.0, b.drawdown)
        assertEquals(18_000.0, b.room)
        // From a baseline set after a switch-on (2 trades in, at −3,000): the best counts from there (up to −2,000, then down to −4,000).
        val since = ForwardWatch.soloBar(listOf(5_000.0, -8_000.0, 1_000.0, -2_000.0), SoloMidday.Baseline(2, -3_000.0))
        assertEquals(-2_000.0, since.drawdown)
        assertEquals("Solo's own switch-off line: ₹26,000 below its best - at or past the −₹25,000 line; Solo's own switch-off acts on that, not this note.",
            ForwardWatch.soloWords(ForwardWatch.SoloBar(-26_000.0)))
    }

    // ---- asked ----------------------------------------------------------------------------------------------------

    @Test fun theSummaryIsOneLineAnArm() {
        val s = ForwardWatch.summary(listOf(ForwardCheck.LIQUIDITY to liq(20 to 300.0), ForwardCheck.SOLO to solo(), ForwardCheck.HERO to null),
            ForwardWatch.SoloBar(-1_000.0))
        val lines = s.lines()
        assertEquals(3, lines.size)
        assertEquals("Liquidity 15+5: in line (₹300/trade vs expected ₹228 ± ₹923).", lines[0])
        assertEquals("Solo (midday): no forward trades yet (expected ₹25/trade).", lines[1])
        assertEquals("Hero (expiry): its record could not be read just now.", lines[2])
        val withSolo = ForwardWatch.summary(listOf(ForwardCheck.SOLO to solo(5 to 100.0)), ForwardWatch.SoloBar(-1_000.0))
        assertEquals("Solo (midday): too few trades (<20) (₹100/trade vs expected ₹25 ± ₹2,861); ₹24,000 from its own −₹25,000 switch-off line.", withSolo)
        val alarmed = ForwardWatch.summary(listOf(ForwardCheck.LIQUIDITY to liq(5 to -3_000.0)))
        assertTrue(alarmed.contains("below expectation (5 trades)") && alarmed.contains("a sustained run below the backtest since trade 5"), alarmed)
        assertEquals(listOf("liquidity", "solo", "hero"), ForwardWatch.ARMS.map { it.key })
    }

    @Test fun askedForEveryArmAtOnce() {
        for (s in listOf("is anything drifting", "is anything drifting?", "Jarvis, is anything drifting", "is any arm drifting", "are my arms drifting",
            "are my bots drifting", "is any of my arms drifting", "anything drifting from the backtest", "any drift", "drift check",
            "how are my arms vs backtest", "how are my arms doing vs the backtest", "how are my arms doing against the backtest", "arms vs backtest",
            "my arms vs the backtest", "how are my strategies vs backtest", "are my arms in line with the backtest", "are my arms on track with the research",
            "live vs backtest", "live vs backtest status", "show live vs backtest", "paper vs backtest",
            "forward test status", "forward-test status", "what's the forward test status", "status of my forward tests", "forward test update",
            "how is the forward test going", "how are my forward tests doing", "forward test watch", "forward test ka haal",
            "kya koi arm drift kar raha hai", "koi bot drift ho raha hai kya", "mere arms backtest ke hisaab se kaise hain"))
            assertTrue(ForwardWatch.asked(s), s)
        // An arm or a shadow named, another day, a backtest to run, an act, the bots' plain health: never this.
        for (s in listOf("liquidity live vs backtest", "solo forward test", "hero forward test", "is liquidity on track", "how are my shadows vs backtest",
            "how are my arms doing", "how are my bots doing", "backtest orb", "backtest my strategy", "run a backtest", "forward test", "how did my arms do last week vs backtest",
            "is nifty drifting", "switch off the arm that is drifting", "stop the drifting arm", "what is drift", "is the market drifting"))
            assertFalse(ForwardWatch.asked(s), s)
    }
}
