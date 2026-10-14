package com.optionslab.engine.mcx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 14 Oct (research R42, Boss's OK): the SILVERM morning call (M4) switched off once on paper; nothing else is touched. */
class McxMorningParkTest {
    @Test fun anArmedMorningCallIsSwitchedOffOnce() {
        val armed = mapOf(McxMorningRules.SOURCE to true, McxEveRules.SOURCE to true, McxUsSilverRules.SOURCE to true)
        assertEquals(true, McxMorningPark.switchOff(armed, done = false))
        // Once only.
        assertNull(McxMorningPark.switchOff(armed, done = true))
        assertNull(McxMorningPark.switchOff(emptyMap(), done = true))
        assertEquals("park_mcx_silverm_am_2026_10_14", McxMorningPark.MIGRATION)
        assertEquals(McxMorningRules.SOURCE, McxMorningPark.SOURCE)
    }

    @Test fun nothingToSwitchOffWhenItIsOff() {
        assertEquals(false, McxMorningPark.switchOff(emptyMap(), done = false))
        assertEquals(false, McxMorningPark.switchOff(mapOf(McxMorningRules.SOURCE to false), done = false))
        // Only the morning call counts: the other MCX arms on change nothing.
        assertFalse(McxMorningPark.switchOff(mapOf(McxEveRules.SOURCE to true, McxTrendRules.SOURCE to true), done = false)!!)
    }

    @Test fun theWordsGiveR42sNumbersWithTheirSampleSizes() {
        for (w in listOf("R42", "+Rs 336/trade over 53 trades", "p 0.079", "Rs 44.6k over 129 trades", "+Rs 871/trade over 95 trades"))
            assertTrue(w in McxMorningPark.REASON, w)
        for (w in listOf("R42", "53 trades", "p 0.079", "129 trades", "its own exit", "Change log", "Paper only"))
            assertTrue(w in McxMorningPark.NOTICE, w)
        for (w in listOf("R42", "53 trades", "129 trades")) assertTrue(w in McxMorningPark.ROW, w)
        assertTrue(McxMorningPark.PARKED.contains("R42"))
        // The constants agree with the words.
        assertEquals(53, McxMorningPark.HOLDOUT_TRADES); assertEquals(336, McxMorningPark.HOLDOUT_PER_TRADE)
        assertTrue(McxMorningPark.TWIN_P >= 0.05, "not significant: that is why it is off")
        assertEquals(129, McxMorningPark.WALK_FORWARD_TRADES); assertTrue(McxMorningPark.WALK_FORWARD_RS < 0)
        assertEquals(95, McxMorningPark.DESIGN_TRADES); assertEquals(871, McxMorningPark.DESIGN_PER_TRADE)
    }
}
