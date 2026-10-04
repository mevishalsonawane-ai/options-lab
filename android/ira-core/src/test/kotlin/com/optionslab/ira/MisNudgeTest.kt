package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MisNudgeTest {
    @Test fun nudge() {
        assertNull(MisNudge.say(emptyList()))
        assertTrue(MisNudge.say(listOf("NIFTY25O0725000CE" to 75))!!.contains("1 intraday (MIS) position is still open"))
        assertTrue(!MisNudge.say(listOf("X" to 1), named = false)!!.contains("X"))
        assertTrue(MisNudge.due(15 * 60 + 12) && !MisNudge.due(15 * 60 + 25))
    }
}
