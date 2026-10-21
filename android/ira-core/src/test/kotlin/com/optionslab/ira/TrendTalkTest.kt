package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrendTalkTest {
    @Test
    fun recognisesTheQuestionsAndNotTheCommandsOrTheMarket() {
        for (q in listOf("Has the trend turned on my trades?", "did the trend change against my positions", "trend watch",
            "what does the trend watch say", "kya mere trades par trend badla", "has the supertrend flipped against my position"))
            assertTrue(TrendTalk.asked(q), q)
        for (q in listOf("what's the trend today", "is nifty in an uptrend", "switch off the trend watch", "sell my position if the trend turned",
            "turn on trend watch", "close my trades"))
            assertFalse(TrendTalk.asked(q), q)
    }

    @Test
    fun answersFromTheLog() {
        val off = TrendTalk.answer(TrendTalk.Facts(false, false, emptyList(), emptyList()))
        assertTrue(off.startsWith("The Trend watch is off"))
        val none = TrendTalk.answer(TrendTalk.Facts(true, false, emptyList(), emptyList()))
        assertTrue(none.startsWith("Nothing is open to watch") && none.contains("\"Live positions too\" is off"))
        val held = listOf(TrendTalk.Held("R51 Donchian 20", "NIFTY..CE", "T2", "trend holding", false),
            TrendTalk.Held("Your own orders", "BANKNIFTY..PE", "T2", "warming up (3 of 11 bars)", true))
        val holding = TrendTalk.answer(TrendTalk.Facts(true, true, emptyList(), held))
        assertTrue(holding.startsWith("No, Boss: the trend has not turned against any of your 2 open positions"))
        assertTrue(holding.contains("BANKNIFTY..PE (Zerodha): T2 - warming up"))
        assertFalse(holding.contains("is off"))
        val one = TrendTalk.answer(TrendTalk.Facts(true, true, emptyList(), held.take(1)))
        assertTrue(one.contains("1 open position today"))
        val turns = listOf(TrendTalk.Turn("trend turned against Hero ₹1,000 ticket NIFTY..CE at 13:05: selling on paper", "SELL", false),
            TrendTalk.Turn("trend turned against Liquidity 15+5 BANKNIFTY..CE at 11:00: would sell", "NOTE", false),
            TrendTalk.Turn("trend turned against Your own orders' pending order X at 11:00: would cancel", "PENDING", false))
        val yes = TrendTalk.answer(TrendTalk.Facts(true, false, turns, held.take(1)))
        assertTrue(yes.startsWith("Yes, Boss: the trend turned against 2 positions today - 1 Hero ticket leg was sold on paper."))
        assertTrue(yes.contains("nothing cancelled") && yes.contains(TrendTalk.RULE))
        val notesOnly = TrendTalk.answer(TrendTalk.Facts(true, false, turns.drop(1).take(1) + turns.drop(1).take(1).map { it.copy(act = "SELL") } , emptyList()))
        assertTrue(notesOnly.contains("1 Hero ticket leg was sold"))
        val two = TrendTalk.answer(TrendTalk.Facts(true, false, List(2) { turns[0] }, emptyList()))
        assertTrue(two.contains("2 Hero ticket legs were sold"))
        val single = TrendTalk.answer(TrendTalk.Facts(true, false, turns.drop(1).take(1), emptyList()))
        assertTrue(single.startsWith("Yes, Boss: the trend turned against 1 position today - notes only, nothing was sold."))
        assertEquals(TrendTalk.LOCKED, "Unlock the phone for that, Boss.")
    }
}
