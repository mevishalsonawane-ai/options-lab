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
