package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertTrue

class WrapUpAskTest {
    @Test fun askedAnyHour() {
        for (s in listOf("wrap up my day", "Jarvis, give me my wrap up", "day summary", "how did my day go", "aaj ka summary", "mera din kaisa raha", "wrapup so far?"))
            assertTrue(DaySummary.asked(s), s)
        for (s in listOf("how did nifty do today", "wrap up the orb strategy", "stop all strategies")) assertTrue(!DaySummary.asked(s), s)
    }

    /** Usefulness round 28: "summarise the day" was missed - the wrap-up opens with the market's day, then Boss's own. */
    @Test fun summariseTheDayIsTheWrapUp() {
        for (s in listOf("summarise the day", "Jarvis, summarize the day", "summarise today", "can you summarise the day for me", "summary of the day",
            "give me a summary of my day", "end of day report", "eod summary"))
            assertTrue(DaySummary.asked(s), s)
        // The market's own recap stays the market's; a summary of the market or of a strategy is not the wrap-up.
        for (s in listOf("recap the day", "summarize the market", "summarise the orb strategy", "market summary please", "summarise the day and stop orb"))
            assertTrue(!DaySummary.asked(s), s)
    }
}
