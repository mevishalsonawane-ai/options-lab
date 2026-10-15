package com.optionslab.engine.orb

import com.optionslab.engine.orb.ArmsCleanup.Action
import com.optionslab.engine.orb.ArmsCleanup.Now
import com.optionslab.engine.orb.ArmsCleanup.State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Boss's 15 Oct cleanup: the other arms off and hidden, never while one holds a position; Liquidity on its indices. */
class ArmsCleanupTest {
    private val keys = ArmsCleanup.HIDDEN.map { it.key }
    private fun flat(armed: Set<String> = emptySet()) = keys.map { Now(it, it in armed, open = false) }

    @Test fun theFirstPassSwitchesOffTheOnesThatAreOnAndHidesTheRest() {
        val (s, steps) = ArmsCleanup.pass(State(), flat(setOf(ArmsCleanup.ORB, ArmsCleanup.PINE)))
        assertTrue(s.done)
        assertEquals(keys.toSet(), s.hidden)
        assertEquals(emptySet(), s.waiting)
        assertEquals(setOf(ArmsCleanup.ORB, ArmsCleanup.PINE), s.wasOn)
        assertEquals(keys.toSet(), s.logged)
        assertEquals(listOf(ArmsCleanup.ORB, ArmsCleanup.PINE), steps.filter { it.action == Action.OFF }.map { it.key })
        assertEquals(keys.size - 2, steps.count { it.action == Action.HIDE })
        assertTrue(steps.all { it.first })
        // Nothing hidden is visible, nothing may be armed, and all of them are listed.
        for (k in keys) { assertFalse(ArmsCleanup.visible(s, k), k); assertFalse(ArmsCleanup.mayArm(s, k), k) }
        assertEquals(ArmsCleanup.HIDDEN, ArmsCleanup.listed(s))
        // A second pass with nothing changed does nothing.
        val (s2, steps2) = ArmsCleanup.pass(s, flat())
        assertEquals(s, s2)
        assertEquals(emptyList(), steps2)
    }

    @Test fun anArmHoldingAPositionKeepsTradingItAndIsSwitchedOffOnlyOnceFlat() {
        val held = keys.map { Now(it, it == ArmsCleanup.NIGHT || it == ArmsCleanup.HERO, open = it == ArmsCleanup.NIGHT) }
        val (s, steps) = ArmsCleanup.pass(State(), held)
        // Night holds a position: it stays on and visible, waiting for its own exit; Hero (flat) goes off at once.
        assertEquals(setOf(ArmsCleanup.NIGHT), s.waiting)
        assertTrue(ArmsCleanup.visible(s, ArmsCleanup.NIGHT))
        assertEquals(Action.WAIT, steps.first { it.key == ArmsCleanup.NIGHT }.action)
        assertEquals(Action.OFF, steps.first { it.key == ArmsCleanup.HERO }.action)
        assertFalse(ArmsCleanup.NIGHT in s.wasOn)
        assertFalse(ArmsCleanup.listed(s).any { it.key == ArmsCleanup.NIGHT })
        // Still holding on the next pass (Hero now off): nothing new is said or done.
        val (s2, steps2) = ArmsCleanup.pass(s, held.map { if (it.key == ArmsCleanup.HERO) it.copy(armed = false) else it })
        assertEquals(emptyList(), steps2)
        assertEquals(s, s2)
        // Flat now (its own 09:16 sale): switched off on this pass, logged once, hidden, and listed.
        val (s3, steps3) = ArmsCleanup.pass(s2, flat(setOf(ArmsCleanup.NIGHT)))
        assertEquals(listOf(ArmsCleanup.Step(ArmsCleanup.NIGHT, Action.OFF, true)), steps3)
        assertEquals(emptySet(), s3.waiting)
        assertTrue(ArmsCleanup.NIGHT in s3.wasOn)
        assertFalse(ArmsCleanup.visible(s3, ArmsCleanup.NIGHT))
        assertTrue(ArmsCleanup.listed(s3).any { it.key == ArmsCleanup.NIGHT })
    }

    @Test fun anArmOffButStillHoldingIsHiddenOnlyAfterItsExit() {
        val now = keys.map { Now(it, false, open = it == ArmsCleanup.ORB) }
        val (s, _) = ArmsCleanup.pass(State(), now)
        assertTrue(ArmsCleanup.visible(s, ArmsCleanup.ORB))
        val (s2, steps2) = ArmsCleanup.pass(s, flat())
        assertEquals(listOf(ArmsCleanup.Step(ArmsCleanup.ORB, Action.HIDE, true)), steps2)
        assertFalse(ArmsCleanup.visible(s2, ArmsCleanup.ORB))
        assertFalse(ArmsCleanup.ORB in s2.wasOn)
    }

    @Test fun aMissingReadCountsAsOpenSoNothingIsSwitchedOffOnIt() {
        val (s, steps) = ArmsCleanup.pass(State(), emptyList())
        assertEquals(keys.toSet(), s.waiting)
        assertTrue(steps.all { it.action == Action.WAIT })
        assertTrue(keys.all { ArmsCleanup.visible(s, it) })
    }

    @Test fun anArmSwitchedOnAgainWhileHiddenIsSwitchedOffAgainWithoutASecondLog() {
        val (s, _) = ArmsCleanup.pass(State(), flat())
        val (s2, steps) = ArmsCleanup.pass(s, flat(setOf(ArmsCleanup.VIX_DIV)))
        assertEquals(listOf(ArmsCleanup.Step(ArmsCleanup.VIX_DIV, Action.OFF, false)), steps)
        assertTrue(ArmsCleanup.VIX_DIV in s2.wasOn)
    }

    @Test fun aRestoreUnhidesInOneTapAndSaysWhetherItWasOn() {
        val (s, _) = ArmsCleanup.pass(State(), flat(setOf(ArmsCleanup.PINE)))
        val r = ArmsCleanup.restore(s, ArmsCleanup.PINE)
        assertTrue(r.on)
        assertTrue(ArmsCleanup.visible(r.state, ArmsCleanup.PINE))
        assertTrue(ArmsCleanup.mayArm(r.state, ArmsCleanup.PINE))
        assertFalse(ArmsCleanup.listed(r.state).any { it.key == ArmsCleanup.PINE })
        val r2 = ArmsCleanup.restore(s, ArmsCleanup.ORB)
        assertFalse(r2.on)
        // A restored arm is never hidden again by a later pass (the cleanup ran once).
        val (s3, steps3) = ArmsCleanup.pass(r.state, flat(setOf(ArmsCleanup.PINE)))
        assertTrue(ArmsCleanup.visible(s3, ArmsCleanup.PINE))
        assertEquals(emptyList(), steps3)
        // Its Undo hides it again, through the same pass (switched off, logged again).
        val again = ArmsCleanup.hideAgain(s3, ArmsCleanup.PINE)
        val (s4, steps4) = ArmsCleanup.pass(again, flat(setOf(ArmsCleanup.PINE)))
        assertEquals(listOf(ArmsCleanup.Step(ArmsCleanup.PINE, Action.OFF, true)), steps4)
        assertFalse(ArmsCleanup.visible(s4, ArmsCleanup.PINE))
        // An unknown key is never hidden.
        assertEquals(s3, ArmsCleanup.hideAgain(s3, "liquidity"))
    }

    @Test fun theKeptArmsAreNeverHidden() {
        for (k in listOf("liquidity", "liquidity15", "liquidity5", "liquidity30_fin", "liquidity5_fin", "liquidity15_mid", "liquidity5_mid",
            "hero_ticket", DonchianArm.SOURCE, OovArm.SOURCE, Solo2.SOURCE, "watchlist", "solo_midday", "mcx")) {
            assertFalse(ArmsCleanup.covers(k), k)
            val (s, _) = ArmsCleanup.pass(State(), flat())
            assertTrue(ArmsCleanup.visible(s, k), k)
            assertTrue(ArmsCleanup.mayArm(s, k), k)
        }
        assertTrue(ArmsCleanup.KEPT.any { it.label.startsWith("Liquidity 15+5") })
        assertTrue(ArmsCleanup.KEPT.any { it.label == "Hero ₹1,000 ticket" })
        assertEquals(12, ArmsCleanup.HIDDEN.size)
        assertEquals(keys.toSet().size, keys.size)
        assertEquals("ORB", ArmsCleanup.of(ArmsCleanup.ORB)?.label)
        assertNull(ArmsCleanup.of("liquidity"))
        // The ORB family and the expiry Hero arm use the arms' book's own sources.
        assertEquals(listOf(OrbRules.ORB.source, OrbRules.ORB_FRESH.source, SweepRules.ARM.source, RangeFadeRules.ARM.source, HeroRules.ARM.source),
            listOf(ArmsCleanup.ORB, ArmsCleanup.ORB_FRESH, ArmsCleanup.ORB_SWEEP, ArmsCleanup.RANGE_FADE, ArmsCleanup.HERO))
    }

    @Test fun theStateSurvivesItsFile() {
        val (s, _) = ArmsCleanup.pass(State(), keys.map { Now(it, it == ArmsCleanup.ORB, it == ArmsCleanup.JARVIS) })
        assertEquals(s, ArmsCleanup.decode(ArmsCleanup.encode(s)))
        assertEquals(State(), ArmsCleanup.decode(null))
        assertEquals(State(), ArmsCleanup.decode("garbage"))
        // Unknown keys (a later build's) are dropped; an empty list reads as empty.
        assertEquals(State(true, setOf(ArmsCleanup.ORB)), ArmsCleanup.decode("v1\ndone=true\nhidden=orb,nope\nwaiting=\nbad line\n"))
    }

    @Test fun theWordsAreShortAndSayWhatBossChose() {
        assertTrue(ArmsCleanup.refusal("ORB").startsWith("ORB is hidden"))
        assertTrue(ArmsCleanup.refusal("ORB").contains("Hidden arms"))
        assertTrue(ArmsCleanup.HIDDEN_WORDS.contains("15 Oct"))
        assertTrue(ArmsCleanup.WAITING.contains("open position"))
        assertTrue(ArmsCleanup.NIFTY_NOTE.contains("765 trades"))
        assertTrue(ArmsCleanup.LIQ_ON.contains("paper only") && ArmsCleanup.LIQ_ON.contains("Boss's choice"))
        assertEquals("arms_cleanup_2026_10_15", ArmsCleanup.MIGRATION)
        assertEquals("liquidity_four_2026_10_15", ArmsCleanup.LIQ_MIGRATION)
    }

    @Test fun liquidityKeepsItsFourIndicesBooksOnPaperAndNeverTouchesBankNifty() {
        val bnOn = mapOf("liquidity15" to true, "liquidity5" to true, "liquidity15_mid" to true)
        val p = ArmsCleanup.liquidityPlan(bnOn, setOf("FINNIFTY"), done = false, restoring = false)!!
        assertEquals(setOf("FINNIFTY"), p.unpark)
        assertEquals(listOf("liquidity30_fin", "liquidity5_fin", "liquidity5_mid"), p.on)
        assertTrue(p.on.none { it == "liquidity15" || it == "liquidity5" })
        // Liquidity off: only the park goes; the books come on with the switch.
        assertEquals(ArmsCleanup.LiqPlan(setOf("FINNIFTY"), emptyList()), ArmsCleanup.liquidityPlan(emptyMap(), setOf("FINNIFTY", "X"), false, false))
        // A restore not yet disarmed: nothing is switched on.
        assertEquals(emptyList(), ArmsCleanup.liquidityPlan(bnOn, emptySet(), false, restoring = true)!!.on)
        // Everything already on: nothing to do; once only.
        val all = LiquidityRules.BOOKS.associate { it.source to true }
        assertEquals(ArmsCleanup.LiqPlan(emptySet(), emptyList()), ArmsCleanup.liquidityPlan(all, emptySet(), false, false))
        assertNull(ArmsCleanup.liquidityPlan(bnOn, setOf("FINNIFTY"), done = true, restoring = false))
    }
}
