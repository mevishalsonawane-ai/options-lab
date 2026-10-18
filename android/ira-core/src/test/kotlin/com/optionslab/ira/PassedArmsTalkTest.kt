package com.optionslab.ira

import com.optionslab.engine.orb.BreadthDonchianArm
import com.optionslab.engine.orb.DonchianArm
import com.optionslab.engine.orb.OovArm
import com.optionslab.engine.orb.Solo2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Jarvis on the passed-research paper arms and the shadow Watchlist ([PassedArmsTalk], 15 Oct): which questions are his, that
 * a sentence with something to do never is, and that every answer carries its label ("Passed research ... paper only";
 * "shadow, not traded; information only, not a trade signal"; C4 NON-DEFAULT), the index reported apart and the bar. The
 * change log's entries for them ([AutoChange.passedArmOn] / [AutoChange.passedArmOff]): paper, Boss's, no PIN.
 */
class PassedArmsTalkTest {
    @Test fun whichQuestionsAreHis() {
        assertEquals(PassedArmsTalk.Topic.DONCHIAN, PassedArmsTalk.asked("how is the donchian arm doing"))
        assertEquals(PassedArmsTalk.Topic.DONCHIAN, PassedArmsTalk.asked("R51 donchian kaisa chal raha hai"))
        assertEquals(PassedArmsTalk.Topic.OOV, PassedArmsTalk.asked("what did open outside value do today"))
        assertEquals(PassedArmsTalk.Topic.OOV, PassedArmsTalk.asked("how is the R52 arm doing"))
        assertEquals(PassedArmsTalk.Topic.SOLO2, PassedArmsTalk.asked("how is Solo 2.0 doing"))
        assertEquals(PassedArmsTalk.Topic.SOLO2, PassedArmsTalk.asked("solo two ne aaj kya kiya"))
        assertEquals(PassedArmsTalk.Topic.WATCHLIST, PassedArmsTalk.asked("what's on the watchlist"))
        assertEquals(PassedArmsTalk.Topic.WATCHLIST, PassedArmsTalk.asked("how are the near misses doing"))
        assertEquals(PassedArmsTalk.Topic.ALL, PassedArmsTalk.asked("how are the passed arms doing"))
        // 16 Oct (R72): the breadth arm is its own topic, never plain Donchian's.
        assertEquals(PassedArmsTalk.Topic.BREADTH, PassedArmsTalk.asked("how is breadth donchian doing"))
        assertEquals(PassedArmsTalk.Topic.BREADTH, PassedArmsTalk.asked("how is the breadth donchian arm doing today"))
        assertEquals(PassedArmsTalk.Topic.BREADTH, PassedArmsTalk.asked("R72 breadth kaisa chal raha hai"))
        assertEquals(PassedArmsTalk.Topic.BREADTH, PassedArmsTalk.asked("how is the r72 arm doing"))
        assertNull(PassedArmsTalk.asked("switch off breadth donchian"))
        assertNull(PassedArmsTalk.asked("what is market breadth"))
        // Not his: plain Solo, the Donchian channel itself, anything with something to do.
        for (q in listOf("what did solo do today", "how is solo doing", "what is a donchian channel", "explain the donchian indicator",
            "switch off the donchian arm", "turn off solo 2.0", "add nifty to my watchlist", "remove the watchlist", "stop open outside value",
            "how is nifty doing", "donchian", "what is in my watchlist"))
            assertNull(PassedArmsTalk.asked(q), q)
    }

    private val arm = PassedArmsTalk.Arm(DonchianArm.SOURCE, true, listOf("NIFTY26OCT25150CE 1 lot @ 120.00 → 150.00 (target), +₹1,800"),
        3, 2_500.0, 2, 1, 1_200.0, "Forward test: 3 of 40 paper trades, net ₹2,500 after charges, at twice the spread ₹2,100")

    @Test fun donchiansSupertrendFilterIsSaidWhenOnAndNotWhenOff() {
        // 16 Oct (R73 review): Jarvis says R51's rules with the Supertrend filter and its record when it is on; the plain rules when off.
        val on = PassedArmsTalk.answer(PassedArmsTalk.Topic.DONCHIAN, PassedArmsTalk.Facts(listOf(arm.copy(stOn = true)), emptyList(), true))
        assertTrue(DonchianArm.ST_RULE in on && DonchianArm.ST_UNJUDGED_RULE in on && DonchianArm.ST_RECORD in on, on)
        val off = PassedArmsTalk.answer(PassedArmsTalk.Topic.DONCHIAN, PassedArmsTalk.Facts(listOf(arm), emptyList(), true))
        assertFalse("Supertrend" in off, off)
        assertTrue(DonchianArm.RULES in off, off)
        // The short answer (all arms) still names it; the breaks it could not judge today are said (one, then several).
        val all = PassedArmsTalk.answer(PassedArmsTalk.Topic.ALL, PassedArmsTalk.Facts(listOf(arm.copy(stOn = true, stUnjudged = 1)), emptyList(), true))
        assertTrue("Its Supertrend (10, 3) filter is on for new breaks (paper only)." in all, all)
        assertTrue("Today Supertrend could not judge 1 break (no earlier session in), so it was not taken." in all, all)
        val two = PassedArmsTalk.armWords(arm.copy(stOn = true, stUnjudged = 2))
        assertTrue("could not judge 2 breaks (no earlier session in), so they were not taken." in two, two)
        // Never on another arm, nor with the filter off.
        assertFalse("Supertrend" in PassedArmsTalk.armWords(arm.copy(source = BreadthDonchianArm.SOURCE, stOn = true, stUnjudged = 3)))
        assertFalse("Supertrend" in PassedArmsTalk.armWords(arm.copy(stUnjudged = 3), full = false))
    }

    @Test fun eachArmIsSaidWithItsLabelRulesRecordAndBar() {
        val w = PassedArmsTalk.answer(PassedArmsTalk.Topic.DONCHIAN, PassedArmsTalk.Facts(listOf(arm), emptyList(), true))
        assertTrue(w.startsWith("R51 Donchian 20: on (paper book). Passed research (R51/R52/R57/R72) — paper book; its live copy follows its own Trade live and Shadow switches. "), w)
        for (x in listOf(DonchianArm.RULES, "Signal: the index's own bars", "Today: NIFTY26OCT25150CE", "3 trades, 2 won, +₹2,500 after charges",
            "FINNIFTY apart: 1 trade, +₹1,200", "Forward test: 3 of 40", "40 paper trades", DonchianArm.RECORD)) assertTrue(x in w, x)
        val oov = arm.copy(source = OovArm.SOURCE, on = false, todayLines = emptyList(), closed = 0, pausedWhy = "net −₹500 at 40 trades")
        val o = PassedArmsTalk.answer(PassedArmsTalk.Topic.OOV, PassedArmsTalk.Facts(listOf(oov), emptyList(), true))
        assertTrue(o.startsWith("R52 Open outside value: off (it switched itself off: net −₹500 at 40 trades)."), o)
        for (x in listOf(OovArm.STOP_ADDED, "No trade today.", "no closed trade yet", "MIDCPNIFTY apart")) assertTrue(x in o, x)
        val s = arm.copy(source = Solo2.SOURCE, on = null, scores = listOf("S3 MIDCPNIFTY call 0.5123: bought"))
        val so = PassedArmsTalk.answer(PassedArmsTalk.Topic.SOLO2, PassedArmsTalk.Facts(listOf(s), emptyList(), true, modelLoaded = false))
        for (x in listOf("Solo 2.0: its switch could not be read just now", "Today's candidates: S3 MIDCPNIFTY call 0.5123: bought", "60 trades",
            Solo2.RETRAIN_TODO, "Its model is not loaded yet")) assertTrue(x in so, x)
        // A book that could not be read is said so.
        assertTrue(PassedArmsTalk.answer(PassedArmsTalk.Topic.SOLO2, PassedArmsTalk.Facts(emptyList(), emptyList(), null)).startsWith("I could not read Solo 2.0"))
        assertTrue(PassedArmsTalk.answer(PassedArmsTalk.Topic.OOV, PassedArmsTalk.Facts(emptyList(), emptyList(), null)).contains("could not read"))
        assertTrue(PassedArmsTalk.answer(PassedArmsTalk.Topic.DONCHIAN, PassedArmsTalk.Facts(emptyList(), emptyList(), null)).contains("could not read"))
    }

    /** 16 Oct (R72, Boss: "Okay add it"): its label, rules, risk line, record and the comparison with Donchian on the same days. */
    @Test fun breadthDonchianIsSaidWithItsRiskAndTheComparison() {
        val b = arm.copy(source = BreadthDonchianArm.SOURCE, todayLines = emptyList(), closed = 0)
        val cmp = BreadthDonchianArm.comparison(java.time.LocalDate.of(2026, 10, 16), listOf(900.0), listOf(-300.0, 500.0), listOf(-300.0))
        val w = PassedArmsTalk.answer(PassedArmsTalk.Topic.BREADTH, PassedArmsTalk.Facts(listOf(arm, b), emptyList(), true, comparison = cmp))
        assertTrue(w.startsWith("R72 Breadth Donchian: on (paper book). Passed research (R51/R52/R57/R72) — paper book; its live copy follows its own Trade live and Shadow switches. "), w)
        for (x in listOf(BreadthDonchianArm.RULES, BreadthDonchianArm.RISK, BreadthDonchianArm.RECORD, "Signal: the index's own bars", "FINNIFTY apart",
            "Forward test", "since 16 Oct: R72 Breadth Donchian 1 trade, +₹900; R51 Donchian 20 2 trades, +₹200",
            "Donchian's trades breadth did not confirm: 1 trade, −₹300")) assertTrue(x in w, x)
        assertTrue(w.endsWith("$cmp."), w)
        assertTrue(PassedArmsTalk.answer(PassedArmsTalk.Topic.BREADTH, PassedArmsTalk.Facts(listOf(arm), emptyList(), true)).contains("could not read"))
        // The round-up carries the comparison once; plain Donchian's answer never.
        val all = PassedArmsTalk.answer(PassedArmsTalk.Topic.ALL, PassedArmsTalk.Facts(listOf(arm, b), emptyList(), true, comparison = cmp))
        assertTrue("R72 Breadth Donchian: on (paper book)" in all && cmp in all, all)
        assertFalse(cmp in PassedArmsTalk.answer(PassedArmsTalk.Topic.DONCHIAN, PassedArmsTalk.Facts(listOf(arm, b), emptyList(), true, comparison = cmp)))
        // Its change-log entry says Boss's words, paper only, no PIN.
        val on = AutoChange.passedArmOn(BreadthDonchianArm.SOURCE, BreadthDonchianArm.LABEL, BreadthDonchianArm.RESEARCH_ID, "your \"Okay add it\" (16 Oct, after R72)")
        assertEquals("paperarm:r72_breadth_donchian", on.target)
        assertTrue(on.reason.startsWith("your \"Okay add it\" (16 Oct, after R72): R72 Breadth Donchian passed every research gate"), on.reason)
        assertEquals(AutoChange.Scope.PAPER, on.scope); assertFalse(AutoChange.undoNeedsPin(on)); assertTrue(AutoChange.bossOwn(on.kind))
    }

    /** 16 Oct (R72 review): Boss switching R72 back on from its row says his "Okay add it", never the 15 Oct "Both". */
    @Test fun eachArmsSwitchOnNamesItsOwnChoice() {
        val r72 = AutoChange.passedArmOn(BreadthDonchianArm.SOURCE, BreadthDonchianArm.LABEL, BreadthDonchianArm.RESEARCH_ID)
        assertTrue(r72.reason.startsWith("your \"Okay add it\" (16 Oct, after R72): "), r72.reason)
        assertFalse("Both" in r72.reason, r72.reason)
        assertEquals(AutoChange.CHOICE_BREADTH, AutoChange.passedArmChoice(BreadthDonchianArm.SOURCE))
        for (s in listOf(DonchianArm.SOURCE, OovArm.SOURCE, Solo2.SOURCE)) {
            assertEquals(AutoChange.CHOICE_BOTH, AutoChange.passedArmChoice(s))
            assertTrue(AutoChange.passedArmOn(s, "x", "R").reason.startsWith("your \"Both\" (15 Oct): "))
        }
    }

    @Test fun theWatchlistIsSaidAsShadowWithC4MarkedNonDefault() {
        val w = PassedArmsTalk.answer(PassedArmsTalk.Topic.WATCHLIST, PassedArmsTalk.Facts(emptyList(), listOf("R52 R4-8 Gann swing break: 2 shadow trades"), false))
        assertTrue(w.startsWith("The Watchlist (shadow, not traded; information only, not a trade signal), switched off: "), w)
        for (x in listOf("R47 C4 BANKNIFTY high PCR (R47 (C4), NON-DEFAULT (option-chain signal))", "R52 R4-8 Gann swing break: 2 shadow trades",
            "net > 0", "M4", "MA2")) assertTrue(x in w, x)
        assertTrue(PassedArmsTalk.answer(PassedArmsTalk.Topic.WATCHLIST, PassedArmsTalk.Facts(emptyList(), emptyList(), true)).contains("No shadow trade yet."))
        val all = PassedArmsTalk.answer(PassedArmsTalk.Topic.ALL, PassedArmsTalk.Facts(listOf(arm), emptyList(), true))
        assertTrue(all.contains("R51 Donchian 20: on (paper book)") && all.contains("The Watchlist (") && !all.contains(DonchianArm.RULES))
        assertEquals("Unlock the phone for that, Boss.", PassedArmsTalk.LOCKED)
    }

    @Test fun theExitsNewTradesEnterWithAreSaid() {
        // 16 Oct (R66 L25, Boss's choice): the lock with the +5% step; the lock alone; neither.
        val both = PassedArmsTalk.exitsWords(lockOn = true, l25On = true)
        for (x in listOf("+5% locked once +25%", "breakeven once +30% (never below the +5%)", "+30% once +60%", "+60% once +100%", "no fixed target",
            "your choice", "not proven", "paper only", "S1 keeps its index exits", "Open trades keep the exits they entered with")) assertTrue(x in both, x)
        val a4 = PassedArmsTalk.exitsWords(lockOn = true, l25On = false)
        assertTrue("breakeven locked once +30%" in a4 && "+25%" !in a4, a4)
        assertTrue("research's own stop, target and time exit" in PassedArmsTalk.exitsWords(false, false))
        assertTrue("acts only with the 30-60-100 lock" in PassedArmsTalk.exitsWords(false, true))
        assertEquals("breakeven locked once +30%, +30% once +60%, +60% once +100%", PassedArmsTalk.ladderWords(false))
        // Jarvis says them after the arm's own words, and once in the round-up.
        val w = PassedArmsTalk.answer(PassedArmsTalk.Topic.DONCHIAN, PassedArmsTalk.Facts(listOf(arm), emptyList(), true, exits = both))
        assertTrue(w.endsWith(" $both."), w)
        val all = PassedArmsTalk.answer(PassedArmsTalk.Topic.ALL, PassedArmsTalk.Facts(listOf(arm), emptyList(), true, exits = both))
        assertEquals(1, Regex(Regex.escape("+5% locked once +25%")).findAll(all).count())
        assertFalse("New trades' exits" in PassedArmsTalk.answer(PassedArmsTalk.Topic.DONCHIAN, PassedArmsTalk.Facts(listOf(arm), emptyList(), true)))
    }

    @Test fun theChangeLogEntriesArePaperBossOwnAndNeedNoPin() {
        val on = AutoChange.passedArmOn(DonchianArm.SOURCE, DonchianArm.LABEL, DonchianArm.RESEARCH_ID)
        assertEquals(AutoChange.Kind.PAPER_ARM_ON, on.kind)
        assertEquals("paperarm:r51_donchian20", on.target)
        assertEquals(AutoChange.SWITCHED_OFF to AutoChange.ON, on.from to on.to)
        assertEquals(AutoChange.Scope.PAPER, on.scope)
        assertEquals(AutoChange.Risk.RAISE, AutoChange.riskOf(on))
        assertTrue(AutoChange.bossOwn(on.kind))
        assertFalse(AutoChange.undoNeedsPin(on))
        assertTrue(on.reason.contains("passed every research gate (R51 (R44-donchian20-RT))") && on.reason.contains("never live"))
        val off = AutoChange.passedArmOff(Solo2.SOURCE, Solo2.LABEL, "a drawdown of −₹26,000")
        assertEquals(AutoChange.ON to AutoChange.SWITCHED_OFF, off.from to off.to)
        assertEquals(AutoChange.Risk.LOWER, AutoChange.riskOf(off))
        assertFalse(AutoChange.undoNeedsPin(off))
        assertTrue(off.reason.contains("switched itself off") && off.reason.contains("your choice"))
        assertEquals("Solo 2.0's switch (paper)", off.what)
        // Its undo is the arm back on: a paper raise, Boss's tap, no PIN.
        assertEquals(AutoChange.ON, AutoChange.inverse(off, "").to)
    }
}
