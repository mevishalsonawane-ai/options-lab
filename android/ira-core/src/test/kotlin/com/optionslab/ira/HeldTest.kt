package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 20 Oct, enhancement round 1 (idea 7): one shared "is anything held?" answer. */
class HeldTest {
    @Test fun nothingHeldAnywhereIsFalseAndSaysNothing() {
        val none = Held.Sources()
        assertFalse(Held.any(none))
        assertEquals("nothing", Held.line(none))
    }

    @Test fun everySourceAloneCountsAsHeld() {
        val each = listOf(
            Held.Sources(cards = true), Held.Sources(paperBook = true), Held.Sources(protections = true), Held.Sources(orb = true),
            Held.Sources(orbLive = true), Held.Sources(pine = true), Held.Sources(strategies = true), Held.Sources(armLive = true),
            Held.Sources(armLiveRecovering = true), Held.Sources(passed = true), Held.Sources(hero = true), Held.Sources(signal = true),
            Held.Sources(night = true), Held.Sources(vixDiv = true), Held.Sources(mcxArms = true), Held.Sources(mcxLive = true),
        )
        each.forEach { assertTrue(Held.any(it), "$it") }
        // Every field of Sources is covered above (a new field must be added to the list and to Held.parts).
        assertEquals(Held.Sources::class.java.declaredFields.count { !it.isSynthetic && it.name != "Companion" }, each.size)
    }

    /** A book not loaded yet (a fresh start) counts as held: a slow start never slows a stop. */
    @Test fun unknownCountsAsHeld() {
        assertTrue(Held.any(Held.Sources(paperBook = null)))
        assertTrue(Held.any(Held.Sources(protections = null)))
        assertEquals("paper book, stops and targets", Held.line(Held.Sources(paperBook = null, protections = null)))
    }

    @Test fun theLineNamesWhatHolds() {
        assertEquals("Zerodha positions, MCX live", Held.line(Held.Sources(cards = true, mcxLive = true)))
        assertEquals("arms' live trades", Held.line(Held.Sources(armLiveRecovering = true)))
    }
}
