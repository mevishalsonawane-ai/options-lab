package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TurnTest {
    @Test fun plainQuestionsAreAnsweredFromAStillPartial() {
        assertEquals("Jarvis how is nifty", Turn.early("Jarvis how is nifty", awake = false, stableForMs = 700))
        assertEquals("Jarvis what are the levels on banknifty", Turn.early("Jarvis what are the levels on banknifty", false, 900))
        assertEquals("Jarvis any news", Turn.early("Jarvis any news", false, 600))
        // After "Yes, Boss?" (or a follow-up window): no name needed for a question.
        assertEquals("how is sensex", Turn.early("how is sensex", awake = true, stableForMs = 800))
    }

    @Test fun waitsWhileTheWordsAreStillMoving() {
        assertNull(Turn.early("Jarvis how is nifty", false, 300))
        assertNull(Turn.early("Jarvis how is nifty", false, Turn.STABLE_MS - 1))
        assertNull(Turn.early(null, false, 5_000))
        assertNull(Turn.early("  ", true, 5_000))
    }

    @Test fun neverForAnythingThatActsOrNeedsTheBestReading() {
        assertNull(Turn.early("Jarvis stop all strategies", false, 2_000))           // a command
        assertNull(Turn.early("Jarvis mute", false, 2_000))                          // a command, even a voice one
        assertNull(Turn.early("Jarvis buy 2 lots nifty 24500 ce", false, 2_000))     // an order
        assertNull(Turn.early("Jarvis switch to live mode", false, 2_000))
        assertNull(Turn.early("Jarvis what is my pnl today", false, 2_000))          // the account (voice check, lock)
        assertNull(Turn.early("Jarvis backtest the hammer on nifty", false, 2_000))  // a backtest
        assertNull(Turn.early("Jarvis what should I buy", false, 2_000))             // a suggested trade
        assertNull(Turn.early("Jarvis tell me a joke please", false, 2_000))         // the model's words
        assertNull(Turn.early("Jarvis stop", false, 2_000))                          // hush, handled by the final
        assertNull(Turn.early("Jarvis", false, 2_000))                               // the name alone ("Yes, Boss?")
        assertNull(Turn.early("Jarvis how", false, 2_000))                           // a breath inside the sentence
        assertNull(Turn.early("how is nifty", awake = false, stableForMs = 2_000))   // not said to Jarvis
    }

    @Test fun aRepeatedPartialIsNoChange() {
        assertTrue(Turn.changed(null, "Jarvis"))
        assertFalse(Turn.changed("Jarvis how is nifty", "jarvis how is Nifty "))
        assertFalse(Turn.changed("Jarvis, how is nifty?", "Jarvis how is nifty"))
        assertTrue(Turn.changed("Jarvis how is nifty", "Jarvis how is nifty today"))
    }
}
