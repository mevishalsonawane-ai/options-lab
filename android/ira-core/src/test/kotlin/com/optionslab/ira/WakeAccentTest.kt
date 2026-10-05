package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertTrue

class WakeAccentTest {
    @Test fun theNameAsAnIndianEnglishRecognizerWritesIt() {
        for (n in listOf("Jarvish", "jarwis", "Jaarvis", "jarviz", "jarbis", "Jarvis"))
            assertTrue(Wake.heard("$n what is nifty", false) !is Wake.Heard.Ignore, n)
        assertTrue(Wake.hush("jarvish stop"))
    }
}

class LostQuestionTest {
    @org.junit.jupiter.api.Test fun awakeQuestionReadMidTurnCounts() {
        kotlin.test.assertEquals("how is nifty doing", Wake.lostTurn(7, "how is nifty doing", awake = true))
        kotlin.test.assertNull(Wake.lostTurn(7, "um", awake = true))
        kotlin.test.assertNull(Wake.lostTurn(7, "how is nifty doing", awake = false))
        kotlin.test.assertNull(Wake.lostTurn(5, "how is nifty doing", awake = true))
    }
}
