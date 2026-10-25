package com.optionslab.engine.mcx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 25 Oct: the NG evening breakout switched off once on paper (research R53 and R83: it loses after costs). */
class McxEveParkTest {
    @Test fun anArmedEveningBreakoutIsSwitchedOffOnce() {
        val armed = mapOf(McxEveRules.SOURCE to true, McxUsSilverRules.SOURCE to true)
        assertEquals(true, McxEvePark.switchOff(armed, done = false))
        assertNull(McxEvePark.switchOff(armed, done = true), "once only: switched back on by Boss, it stays on")
        assertNull(McxEvePark.switchOff(emptyMap(), done = true))
        assertEquals(McxEveRules.SOURCE, McxEvePark.SOURCE)
        assertTrue(McxEvePark.MIGRATION != McxMorningPark.MIGRATION, "its own mark: the 14 Oct change is not re-run")
    }

    @Test fun nothingToSwitchOffWhenItIsOffOrOnlyOtherArmsAreOn() {
        assertEquals(false, McxEvePark.switchOff(emptyMap(), done = false))
        assertEquals(false, McxEvePark.switchOff(mapOf(McxEveRules.SOURCE to false), done = false))
        assertFalse(McxEvePark.switchOff(mapOf(McxUsSilverRules.SOURCE to true, McxMorningRules.SOURCE to true), done = false)!!)
    }

    @Test fun theWordsGiveBothStudiesWithTheirSampleSizes() {
        for (w in listOf("R53", "R83", "115 trades", "881 trades", "-Rs 3,508", "-Rs 51,428")) assertTrue(w in McxEvePark.REASON, w)
        for (w in listOf("R53", "R83", "its own exit", "Change log", "Paper only")) assertTrue(w in McxEvePark.NOTICE, w)
        assertTrue("R53" in McxEvePark.PARKED && "R83" in McxEvePark.PARKED)
        for (w in listOf("R53", "R83", "115 trades", "881 trades")) assertTrue(w in McxEvePark.ROW, w)
        assertEquals(115, McxEvePark.R53_TRADES); assertEquals(-3_508, McxEvePark.R53_RS)
        assertEquals(881, McxEvePark.R83_TRADES); assertEquals(-51_428, McxEvePark.R83_RS)
    }
}
