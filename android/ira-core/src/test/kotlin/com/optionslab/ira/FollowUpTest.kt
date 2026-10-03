package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FollowUpTest {
    @Test fun marketsCarryOver() {
        assertEquals("How is BankNifty doing?", FollowUp.resolve("How is Nifty doing?", "and BankNifty?"))
        assertEquals("What are the levels on FinNifty", FollowUp.resolve("What are the levels on Nifty", "what about finnifty"))
        assertEquals("why is Nifty moving today", FollowUp.resolve("How is Nifty doing?", "why?"))
    }

    @Test fun standAloneAndActionsDoNotCarry() {
        assertNull(FollowUp.resolve("How is Nifty doing?", "what is the market regime in banknifty this week"))
        assertNull(FollowUp.resolve("stop all strategies", "and banknifty?"), "a command is never repeated")
        assertNull(FollowUp.resolve("buy 1 lot nifty 24000 call", "and banknifty?"), "an order is never repeated")
        assertNull(FollowUp.resolve(null, "and banknifty?"))
        assertNull(FollowUp.resolve("How is Nifty doing?", "and tomorrow?"))
    }

    @Test fun whatIsSaidNowNeverActsThroughAFollowUp() {
        assertNull(FollowUp.resolve("what are the events", "now stop banknifty arm"))
        assertNull(FollowUp.resolve("how is nifty", "now sell 2 lots banknifty"))
        assertNull(FollowUp.resolve("how is nifty", "now buy banknifty"))
        assertNull(FollowUp.resolve("how is nifty", "and close banknifty"))
    }

    @Test fun marketsAsSpokenAreSwapped() {
        assertEquals("how is Nifty", FollowUp.resolve("how is bank nifty", "and nifty?"))
        assertEquals("how is BankNifty", FollowUp.resolve("how is nifty 50", "and banknifty?"))
    }
}
