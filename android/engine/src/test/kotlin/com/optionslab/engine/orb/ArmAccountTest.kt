package com.optionslab.engine.orb

import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Line-by-line review G2 (11 Oct): one account per new entry, shared by the signal's rules, the approval notice, the approval
 * and the strategy lab ([ArmAccount]); Liquidity's own sale of an earned lock ([LiquidityRules.appSale]); the decision bars
 * a parameter set names ([OrbRules.decisionBars]).
 */
class ArmAccountTest {
    private val liq5 = LiquidityRules.BOOKS.first()
    private val orb = OrbRules.ORB

    @Test fun anEntryIsLiveOnlyInLiveForAnArmThatMayTradeOnZerodhaAndIsNotParked() {
        assertTrue(ArmAccount.entry(orb, appLive = true, parked = false))
        assertFalse(ArmAccount.entry(orb, appLive = false, parked = false), "the app in Paper")
        assertTrue(ArmAccount.entry(orb, appLive = true, parked = true), "a park is Liquidity's only")
        assertFalse(ArmAccount.entry(SweepRules.ARM, appLive = true, parked = false), "paper-only arms never")
        assertFalse(ArmAccount.entry(RangeFadeRules.ARM, appLive = true, parked = false))
        assertTrue(ArmAccount.entry(liq5, appLive = true, parked = false))
        assertFalse(ArmAccount.entry(liq5, appLive = true, parked = true), "parked by its self-review: paper")
        assertFalse(ArmAccount.entry(liq5, appLive = false, parked = true))
    }

    /** G2-1: Liquidity armed in Live, parked to paper by its review; its notice said paper - the approval never goes to Zerodha. */
    @Test fun anApprovalEntersOnTheNoticesAccountOrNotAtAll() {
        // Parked: the notice said paper, the approval (Boss in Live, with his PIN) enters on paper.
        assertEquals(false, ArmAccount.approval(liq5, appLive = true, parked = true, noticed = false))
        // A signal saved before the notice kept its account: decided again now - parked is paper even in Live.
        assertEquals(false, ArmAccount.approval(liq5, appLive = true, parked = true, noticed = null))
        assertEquals(true, ArmAccount.approval(liq5, appLive = true, parked = false, noticed = null))
        assertEquals(true, ArmAccount.approval(liq5, appLive = true, parked = false, noticed = true))
        assertEquals(false, ArmAccount.approval(liq5, appLive = false, parked = false, noticed = false))
        // The account changed since the notice: nothing (a paper notice never becomes a Zerodha order, nor the reverse).
        assertNull(ArmAccount.approval(liq5, appLive = true, parked = false, noticed = false))
        assertNull(ArmAccount.approval(liq5, appLive = true, parked = true, noticed = true))
        assertNull(ArmAccount.approval(liq5, appLive = false, parked = false, noticed = true))
    }

    /** G2-2: the lab's record is one account per strategy, whatever order its books are armed in. */
    @Test fun theLabSeesOneAccountPerKeyLiveWhenAnyOfItsArmsIsLive() {
        val key: (String) -> String = { if (it.startsWith("liquidity")) "liquidity" else it }
        val mid = LiquidityRules.BOOKS.last()
        val books = listOf(liq5, mid)
        // The BANKNIFTY book enters live, the MIDCPNIFTY book (joined on paper) does not: live, in either order.
        val liveOf: (Arm) -> Boolean = { it == liq5 }
        assertEquals(mapOf("liquidity" to true), ArmAccount.byKey(books, key, liveOf))
        assertEquals(mapOf("liquidity" to true), ArmAccount.byKey(books.reversed(), key, liveOf))
        assertEquals(mapOf("liquidity" to false, "orb" to false), ArmAccount.byKey(books + orb, key) { false })
        assertEquals(emptyMap(), ArmAccount.byKey(emptyList(), key) { true })
        // ORB armed for Live (liveOk) with the app in Paper: its entries go to paper, and so does the lab's record.
        assertEquals(mapOf("orb" to false), ArmAccount.byKey(listOf(orb), key) { ArmAccount.entry(it, appLive = false, parked = false) })
    }

    /** G2-4: an earned lock is sold by the app when it is not resting as the stop; with no lock, exactly the stop rule. */
    @Test fun liquiditysOwnSaleTakesAnEarnedLockTheStopDidNotCarry() {
        // The stop rule as before (no lock variant: locked is false).
        assertEquals("stop", LiquidityRules.appSale(stopResting = false, runThrough = false, atStop = true, locked = false, lockResting = false))
        assertNull(LiquidityRules.appSale(stopResting = true, runThrough = false, atStop = true, locked = false, lockResting = false), "the resting stop sells it")
        assertEquals("stop", LiquidityRules.appSale(stopResting = true, runThrough = true, atStop = true, locked = false, lockResting = false), "ran through its limit")
        assertNull(LiquidityRules.appSale(stopResting = false, runThrough = false, atStop = false, locked = false, lockResting = false))
        // The lock earned: no stop order (paper: rejected), or a refused modify at Zerodha - the app sells.
        assertEquals("profit_lock", LiquidityRules.appSale(stopResting = false, runThrough = false, atStop = false, locked = true, lockResting = false))
        assertEquals("profit_lock", LiquidityRules.appSale(stopResting = true, runThrough = false, atStop = false, locked = true, lockResting = false))
        // Resting at the lock: Zerodha sells it, unless the price ran through its limit.
        assertNull(LiquidityRules.appSale(stopResting = true, runThrough = false, atStop = false, locked = true, lockResting = true))
        assertEquals("profit_lock", LiquidityRules.appSale(stopResting = true, runThrough = true, atStop = false, locked = true, lockResting = true))
    }

    @Test fun theDecisionBarsFollowTheParameters() {
        assertEquals(LocalTime.of(10, 5) to LocalTime.of(13, 55), OrbRules.decisionBars(OrbParams.ORB))
        assertEquals(LocalTime.of(10, 5) to LocalTime.of(14, 25), OrbRules.decisionBars(OrbParams.SWEEP))
        assertEquals(LocalTime.of(9, 50) to LocalTime.of(14, 25),
            OrbRules.decisionBars(OrbParams.ORB.copy(orEnd = LocalTime.of(9, 45), lastEntry = LocalTime.of(14, 30))))
    }
}
