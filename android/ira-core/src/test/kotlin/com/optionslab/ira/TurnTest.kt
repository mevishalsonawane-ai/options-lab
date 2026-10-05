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

    @Test fun theWaitCountsFromBossLastWord() {
        // The earlier of the words' last change and "speech ended".
        assertEquals(10_000L, Turn.spokeEnd(wordsAt = 10_000, endAt = 10_600, now = 11_500))
        assertEquals(10_400L, Turn.spokeEnd(wordsAt = 10_900, endAt = 10_400, now = 11_500))
        // Only one seen, or neither (the turn's end, as before).
        assertEquals(10_900L, Turn.spokeEnd(10_900, 0, 11_500))
        assertEquals(10_400L, Turn.spokeEnd(0, 10_400, 11_500))
        assertEquals(11_500L, Turn.spokeEnd(0, 0, 11_500))
        // Another turn's stale time, or one from the future, is not taken.
        assertEquals(40_000L, Turn.spokeEnd(10_000, 0, 40_000))
        assertEquals(11_500L, Turn.spokeEnd(12_000, 0, 11_500))
    }

    @Test fun theFirstSentenceIsSpokenWhileTheRestIsMade() {
        val a = "Boss, Nifty is at 24,512.35, up 0.4 percent today. It holds above the opening range high. Support is 24,400."
        assertEquals(listOf("Boss, Nifty is at 24,512.35, up 0.4 percent today.", "It holds above the opening range high. Support is 24,400."), Wake.pieces(a))
        assertEquals(a, Wake.pieces(a).joinToString(" "))
        // Short answers stay whole; a very short first sentence goes with the next.
        assertEquals(listOf("Boss, Nifty is up. Bank Nifty is down."), Wake.pieces("Boss, Nifty is up. Bank Nifty is down."))
        assertEquals(listOf("Boss, yes. Nifty is at 24,512 and holding the opening range.", "Support is near 24,400 and resistance 24,600."),
            Wake.pieces("Boss, yes. Nifty is at 24,512 and holding the opening range. Support is near 24,400 and resistance 24,600."))
        // One long sentence: one piece.
        val one = "Boss, Nifty is at 24,512 and holding above the opening range high with volume picking up"
        assertEquals(listOf(one), Wake.pieces(one))
    }
}
