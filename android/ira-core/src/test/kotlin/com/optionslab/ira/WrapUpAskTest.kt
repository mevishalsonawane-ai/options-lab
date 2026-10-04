package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertTrue

class WrapUpAskTest {
    @Test fun askedAnyHour() {
        for (s in listOf("wrap up my day", "Jarvis, give me my wrap up", "day summary", "how did my day go", "aaj ka summary", "mera din kaisa raha", "wrapup so far?"))
            assertTrue(DaySummary.asked(s), s)
        for (s in listOf("how did nifty do today", "wrap up the orb strategy", "stop all strategies")) assertTrue(!DaySummary.asked(s), s)
    }
}
