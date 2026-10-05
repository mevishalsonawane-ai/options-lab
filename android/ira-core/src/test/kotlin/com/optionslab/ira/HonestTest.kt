package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the phone cannot answer truthfully - other markets, results, VWAP, targets, lots with no budget - said so (5 Oct). */
class HonestTest {
    @Test fun marketsThePhoneHasNoDataForAreSaidSo() {
        for ((s, what) in listOf("what's crude doing" to "crude or gas", "crude kitne pe hai" to "crude or gas", "how is the dow" to "US market",
            "how did us markets close" to "US market", "how is nasdaq" to "US market", "how is asia" to "Asian market", "hang seng kaisa hai" to "Asian market",
            "what's the dollar rupee" to "currency", "usd inr rate" to "currency", "dollar kitne ka hai" to "currency", "is the rupee falling" to "currency",
            "gift nifty" to "GIFT Nifty", "what's sgx nifty" to "GIFT Nifty", "how is silver" to "silver", "bitcoin price" to "crypto"))
            assertEquals(Honest.Asked.Elsewhere(what), Honest.asked(s), s)
        val said = Honest.say(Honest.Asked.Elsewhere("crude or gas"))
        assertEquals("I don't have crude or gas data on the phone, Boss - I follow Nifty, BankNifty, FinNifty, Sensex, VIX and gold. I won't guess at it.", said)
        assertTrue(Honest.say(Honest.Asked.Elsewhere("US market"), listOf(Market.GOLD)).contains("I follow gold."), "the gold-only app names only gold")
        assertEquals(Honest.Asked.Results, Honest.asked("any results today"))
        assertEquals(Honest.Asked.Results, Honest.asked("kiske results aaj hain"))
    }

    @Test fun vwapTargetsAndLotsWithNoBudget() {
        assertEquals(Honest.Asked.Vwap(null), Honest.asked("where is vwap"))
        assertEquals(Honest.Asked.Vwap(Market.NIFTY), Honest.asked("is nifty above vwap"))
        assertTrue(Honest.say(Honest.Asked.Vwap(Market.NIFTY)).contains("index candles carry no volume"))
        assertEquals(Honest.Asked.Target(Market.NIFTY), Honest.asked("what's the target for nifty today"))
        assertEquals(Honest.Asked.Target(Market.BANKNIFTY), Honest.asked("banknifty ka target kya hai"))
        assertEquals(Honest.Asked.Target(null), Honest.asked("where is the bottom"))
        assertEquals(Honest.Asked.Target(Market.NIFTY), Honest.asked("nifty kitna aur girega"))
        assertTrue(Honest.say(Honest.Asked.Target(Market.NIFTY)).startsWith("I don't give targets or predictions, Boss"))
        assertEquals(Honest.Asked.LotsNoBudget, Honest.asked("kitne lot le sakta hoon"))
        assertEquals(Honest.Asked.LotsNoBudget, Honest.asked("how many lots can i take"))
        assertTrue(Honest.say(Honest.Asked.LotsNoBudget).contains("Boss"))
    }

    @Test fun whatTheAppAnswersItselfIsNotReadHere() {
        for (s in listOf("how is nifty", "what is vwap", "vwap kya hota hai", "what does vwap mean", "what's my target", "mera target kya hai",
            "what is my day target", "my targets", "set target 25200 on nifty", "alert me when crude goes above 6000", "set an alarm on nifty above 25000",
            "any news on crude", "crude news", "how many lots am i holding", "kitne lots khule hain", "how many lots can i buy with 20000",
            "50000 mein kitne lot", "what is gold in dollar rate", "why is nifty falling, is it crude", "backtest results", "where is the top gainer",
            "how is gold", "what are global cues", "give us an update", "how is the census today", "buy 2 lots nifty 25000 ce"))
            assertNull(Honest.asked(s), s)
    }

    @Test fun anythingThatActsIsNeverAnsweredHere() {
        // The hub reads commands and orders first; even so, no line that acts is read as one of these.
        for (s in listOf("stop all strategies", "close all positions", "cancel all orders", "set my day target to 5000", "trail my stop loss",
            "remind me at 3 pm to check crude", "sell 1 lot nifty 24500 pe")) {
            assertNull(Honest.asked(s), s)
            assertFalse(Sizing.needsBudget(s), s)
        }
    }
}
