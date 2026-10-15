package com.optionslab.engine.orb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The 13 Oct park (Boss's choice): ORB, ORB Fresh, ORB Sweep and Range Fade off once, Liquidity and Hero never touched. */
class FamilyParkTest {
    @Test fun theChangeSwitchesOffTheArmedOnesAndParksAllFour() {
        val armed = mapOf("orb" to true, "orb_fresh" to false, "orb_sweep" to true, "range_fade" to true,
            "liquidity15" to true, "hero" to true)
        val p = FamilyPark.plan(armed, done = false)!!
        assertEquals(listOf("orb", "orb_sweep", "range_fade"), p.off)
        assertEquals(setOf("orb", "orb_fresh", "orb_sweep", "range_fade"), p.parked)
        // Nothing on: still all four parked, nothing to switch off.
        assertEquals(FamilyPark.Plan(emptyList(), p.parked), FamilyPark.plan(emptyMap(), done = false))
        // Once only.
        assertNull(FamilyPark.plan(armed, done = true))
        assertEquals("park_orb_family_2026_10_13", FamilyPark.MIGRATION)
    }

    @Test fun onlyTheFourAreCovered() {
        for (s in listOf("orb", "orb_fresh", "orb_sweep", "range_fade")) assertTrue(FamilyPark.covers(s), s)
        for (s in listOf("liquidity", "liquidity15", "liquidity5", "liquidity30_fin", "hero", "")) assertFalse(FamilyPark.covers(s), s)
        assertEquals(FamilyPark.ARMS.map { it.source }.toSet(), FamilyPark.TESTED.keys)
        // Every tested year lost, for each of the four.
        assertTrue(FamilyPark.TESTED.values.all { it.first < 0 && it.second < 0 })
    }

    @Test fun bossSwitchingOneBackOnEndsItsParkOnly() {
        val all = FamilyPark.plan(emptyMap(), done = false)!!.parked
        assertEquals(all - "orb", FamilyPark.unpark(all, "orb"))
        assertEquals(all, FamilyPark.unpark(all, "liquidity"))
        assertEquals(emptySet(), FamilyPark.unpark(emptySet(), "orb"))
    }

    @Test fun theWordsSayWhatBossChose() {
        assertEquals("parked on your choice (13 Oct)", FamilyPark.ROW)
        assertEquals("parked on your choice (13 Oct): lost in every tested year (−₹77,013 and −₹69,754 a lot)", FamilyPark.rowLine("orb"))
        assertEquals("−₹16,838 and −₹6,361 a lot", FamilyPark.record("orb_fresh"))
        assertEquals("−₹24,046 and −₹76,025 a lot", FamilyPark.record("orb_sweep"))
        assertEquals("−₹47,158 and −₹72,340 a lot", FamilyPark.record("range_fade"))
        assertEquals("", FamilyPark.record("liquidity"))
        assertTrue(FamilyPark.PARKED.startsWith(FamilyPark.ROW))
        assertTrue("still managed to their exits" in FamilyPark.NOTICE)
        // Paused for research (Boss, 13 Oct).
        assertTrue("Paused for research" in FamilyPark.NOTICE)
        assertTrue("paused for research" in FamilyPark.history("ORB"))
        assertTrue(FamilyPark.history("ORB").startsWith("ORB is parked on your choice (13 Oct), Boss"))
    }
}
