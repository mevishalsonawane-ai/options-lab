package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MoversTest {
    @Test fun moversAreEveryIndex() {
        for (s in listOf("what's moving", "top gainers", "biggest movers today", "any big moves today", "which index is strongest")) {
            val q = Ask.parse(s)
            assertEquals(setOf(Topic.OVERVIEW), q.topics, s)
            assertTrue(Market.BANKNIFTY in q.markets && Market.NIFTY in q.markets, s)
        }
    }

    @Test fun recommendationIsTheSuggestion() {
        for (s in listOf("what's your recommendation", "what do you recommend", "any suggestions today"))
            assertEquals(setOf(Topic.SUGGEST), Ask.parse(s).topics, s)
        // Still never an order.
        assertEquals(null, Ask.parse("what do you recommend").order)
    }
}
