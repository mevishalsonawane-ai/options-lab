package com.optionslab.engine.orb

import com.optionslab.engine.orb.ArmPriority.Rank
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArmPriorityTest {
    private val day = LocalDate.of(2026, 10, 7)
    private fun leg(who: String, rank: Rank, side: Int, from: Int, to: Int?, live: Boolean = false, und: String = "BANKNIFTY") =
        ArmPriority.Leg(who, rank, und, side, day.atTime(from / 100, from % 100), to?.let { day.atTime(it / 100, it % 100) }, live)

    @Test fun theArmsAreRanked() {
        listOf(OrbRules.ORB, OrbRules.ORB_FRESH, SweepRules.ARM, RangeFadeRules.ARM).forEach { assertEquals(Rank.OLD_ARM, ArmPriority.rankOf(it)) }
        (LiquidityRules.BOOKS + LiquidityRules.ARM).forEach { assertEquals(Rank.LIQUIDITY, ArmPriority.rankOf(it)) }
        assertEquals(Rank.OTHER, ArmPriority.rankOf(HeroRules.ARM))
        assertEquals(Rank.OLD_ARM, ArmPriority.rankOf(ShadowRules.ORB_V43.arms))
        assertEquals(Rank.LIQUIDITY, ArmPriority.rankOf(listOf(LiquidityRules.ARM15)))
        assertEquals(Rank.OTHER, ArmPriority.rankOf(ShadowRules.MOMO_O08.arms))
        assertEquals(Rank.OTHER, ArmPriority.rankOf(listOf(OrbRules.ORB, LiquidityRules.ARM15)))
    }

    @Test fun liquidityIsNeverRefusedByAnOrbArmButLiveAgainstLiveKeepsTheGuard() {
        assertFalse(ArmPriority.counts(Rank.OLD_ARM, false, Rank.LIQUIDITY, false))
        assertFalse(ArmPriority.counts(Rank.OLD_ARM, false, Rank.LIQUIDITY, true), "a paper ORB holding never blocks a live Liquidity entry")
        assertFalse(ArmPriority.counts(Rank.OLD_ARM, true, Rank.LIQUIDITY, false), "a paper Liquidity entry beside a live ORB holding")
        assertTrue(ArmPriority.counts(Rank.OLD_ARM, true, Rank.LIQUIDITY, true), "live safety: never two live automatic positions on one index")
        assertTrue(ArmPriority.counts(Rank.LIQUIDITY, false, Rank.OLD_ARM, false), "an ORB arm still gives way to Liquidity")
        assertTrue(ArmPriority.counts(Rank.OTHER, false, Rank.LIQUIDITY, false), "Pine, Solo, Hero still count against Liquidity")
        assertTrue(ArmPriority.counts(Rank.OLD_ARM, false, Rank.OTHER, false))
        assertTrue(ArmPriority.counts(Rank.LIQUIDITY, false, Rank.LIQUIDITY, false))
        assertTrue(ArmPriority.byPriority(Rank.LIQUIDITY, Rank.OLD_ARM))
        assertFalse(ArmPriority.byPriority(Rank.OTHER, Rank.OLD_ARM)); assertFalse(ArmPriority.byPriority(Rank.LIQUIDITY, Rank.OTHER))
    }

    @Test fun theGuardReplayedTakesLiquidityWhileOrbHoldsAndRefusesOrbWhileLiquidityHolds() {
        val orb = leg("orb", Rank.OLD_ARM, 1, 1010, 1300)
        val liq = leg("liquidity15", Rank.LIQUIDITY, -1, 1100, 1140)
        val sweep = leg("orb_sweep", Rank.OLD_ARM, 1, 1105, 1200)
        val g = ArmPriority.guard(listOf(sweep, liq, orb))
        assertEquals(listOf(orb, liq), g.taken)
        assertEquals("orb_sweep refused: orb holds BANKNIFTY (one automatic position an index a side)", g.refused.single().words)
        // Liquidity holds first: ORB's later entry is refused, by priority.
        val h = ArmPriority.guard(listOf(leg("liquidity5", Rank.LIQUIDITY, 1, 1000, 1030), leg("range_fade", Rank.OLD_ARM, -1, 1015, 1100),
            leg("orb_fresh", Rank.OLD_ARM, 1, 1030, null)))
        assertEquals(listOf("liquidity5", "orb_fresh"), h.taken.map { it.who }, "the index is free again at Liquidity's exit")
        assertTrue(h.refused.single().words.endsWith("(Liquidity has priority over ORB arms)"))
        // At the same minute Liquidity goes first.
        val tie = ArmPriority.guard(listOf(leg("orb", Rank.OLD_ARM, 1, 1100, 1200), leg("liquidity15", Rank.LIQUIDITY, 1, 1100, 1200)))
        assertEquals("liquidity15", tie.taken.single().who)
        // Both live: the guard holds as before; another index, a neutral leg and a held-to-the-end leg.
        val live = ArmPriority.guard(listOf(leg("orb", Rank.OLD_ARM, 1, 1010, null, live = true), leg("liquidity15", Rank.LIQUIDITY, 1, 1100, 1140, live = true),
            leg("liquidity30_fin", Rank.LIQUIDITY, 1, 1100, 1140, live = true, und = "FINNIFTY"), leg("straddle", Rank.OTHER, 0, 1100, 1200),
            leg("pine", Rank.OTHER, 0, 1000, 1500)))
        assertEquals(listOf("liquidity15"), live.refused.map { it.leg.who })
        assertEquals(setOf("orb", "liquidity30_fin", "straddle", "pine"), live.taken.map { it.who }.toSet())
    }

    @Test fun theEveningReplayMarksTheOrbTradesLiquidityWouldHaveRefused() {
        val trades = listOf(ReplayTrade("10:05", "10:40", "CE", 300.0, 340.0, "target"), ReplayTrade("11:00", "12:00", "PE", 250.0, 210.0, "stop"))
        val liq = listOf(leg("liquidity15", Rank.LIQUIDITY, 1, 1045, 1130))
        assertEquals(mapOf(1 to "orb refused: liquidity15 holds BANKNIFTY (Liquidity has priority over ORB arms)"),
            ArmPriority.refusedInReplay(OrbRules.ORB, day, trades, liq))
        assertTrue(ArmPriority.refusedInReplay(OrbRules.ORB_FRESH, day, trades, emptyList()).isEmpty())
    }
}
