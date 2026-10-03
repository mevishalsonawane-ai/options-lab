package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The review of the reasoning batch: each confirmed finding, fixed. */
class ReviewMTest {
    @Test fun theGlossaryLeavesFiguresAlone() {
        for (q in listOf("what is the next expiry", "what are fiis doing today", "what is the iv today", "what is the support today", "explain the gap down today",
                "tell me about banknifty support and resistance", "what is the premium on 25000 ce", "what is the margin available"))
            assertNull(Glossary.explain(q), q)
        assertTrue(Glossary.explain("what does implied volatility mean") != null)
        assertTrue(Glossary.explain("what is theta") != null)
    }

    @Test fun whereIsMyPositionIsTheAccount() {
        assertEquals(setOf(Topic.ACCOUNT), Ask.parse("where is my nifty position").topics)
        assertEquals(setOf(Topic.OVERVIEW), Ask.parse("where is banknifty trading").topics)
    }

    @Test fun patternRecordsAreNotTheReview() {
        assertFalse(Topic.ACCOUNT in Ask.parse("engulfing win rate on banknifty").topics)
        assertEquals(setOf(Topic.ACCOUNT), Ask.parse("what's my win rate").topics)
    }

    @Test fun oneIsNeverWidenedToAllAndQuestionsNeverAct() {
        for (q in listOf("exit the trade", "close my trade", "pause the strategy", "kill the algo", "stop my strategy", "close holdings", "will you close all positions?", "would you close all positions?", "can you close all positions?"))
            assertNull(Intents.quick(q), q)
        assertEquals("close all positions", Intents.quick("close all my positions"))
        assertEquals("what is my p&l today", Intents.quick("am I up today?"), "a question stays a question")
    }

    @Test fun todaysRangeIsTheActualRange() {
        assertFalse(ExpectedRange.asked("what was the day's range"))
        assertFalse(ExpectedRange.asked("today's range of banknifty"))
        assertTrue(ExpectedRange.asked("what's the expected range today"))
    }

    @Test fun nicheIsEnglish() {
        assertEquals("my niche strategy", Hinglish.normalize("my niche strategy"))
    }
}
