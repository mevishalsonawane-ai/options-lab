package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
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

    @Test fun speechEndedNeverPutsTheCloseOff() {
        // A close already due sooner (the words stood still): kept.
        assertEquals(300L, Turn.closeIn(now = 10_600, dueAt = 10_900))
        assertEquals(1L, Turn.closeIn(10_899, 10_900))
        // Due later than the usual wait after speech ended (only the name so far), or none: the usual wait.
        assertEquals(Turn.END_SPEECH_MS, Turn.closeIn(10_000, 11_800))
        assertEquals(Turn.END_SPEECH_MS, Turn.closeIn(10_000, 10_000 + Turn.END_SPEECH_MS))
        assertEquals(Turn.END_SPEECH_MS, Turn.closeIn(10_000, 0))
        // Already past (it ran, or was cancelled with its turn): not taken.
        assertEquals(Turn.END_SPEECH_MS, Turn.closeIn(10_000, 9_500))
        assertEquals(Turn.END_SPEECH_MS, Turn.closeIn(10_000, 10_000))
    }

    @Test fun wordsStoppingMidSentenceAreNeverAnsweredAndTheTurnWaitsForTheRest() {
        // Voice, round 29: before, each of these still partials was answered as it stood (the market's overview or its
        // "why") while Boss was taking a breath before "P&L", "lose money today", "BankNifty"...
        for (w in listOf("Jarvis how is my", "Jarvis how is the", "Jarvis why did I", "Jarvis what happened to",
                "Jarvis what is the trend of", "Jarvis what is the support for", "Jarvis how is nifty and",
                "Jarvis how is nifty doing compared to", "Jarvis how is nifty today versus", "Jarvis what is the news about my",
                "Jarvis why is nifty falling and what should", "Jarvis what is nifty's support and", "Jarvis how volatile is",
                "Jarvis what is nifty's", "Jarvis nifty ka", "Jarvis mera", "Jarvis how is nifty aur")) {
            assertTrue(Turn.unfinished(w), w)
            assertNull(Turn.early(w, false, 5_000), w)
            assertNull(Turn.ahead(w, false), w)
            assertEquals(Turn.UNFINISHED_MS, Turn.endAfter(w, 600), w)
        }
        // Awake (no name): the same.
        assertNull(Turn.early("how is my", awake = true, stableForMs = 5_000))
        // Whole questions are as quick as before - also those ending in a word that may close a question.
        for (w in listOf("Jarvis how is nifty", "Jarvis any news", "Jarvis what are the levels on banknifty", "Jarvis is the kill switch on",
                "Jarvis am I logged in", "Jarvis what do I do", "Jarvis how are you", "Jarvis what is that", "Jarvis nifty kaisa hai")) {
            assertFalse(Turn.unfinished(w), w)
            assertEquals(600L, Turn.endAfter(w, 600), w)
        }
        assertEquals("Jarvis how is nifty", Turn.early("Jarvis how is nifty", false, 700))
        // Review, 6 Oct: "at" and "we" also end whole questions; the commands and a yes or a no never wait.
        for (w in listOf("Jarvis what is nifty trading at", "what is banknifty at", "Jarvis where are we", "how are we doing today so where are we",
                "Jarvis stop", "kill switch on", "exit all", "sab band kar do", "Jarvis kill switch on", "Jarvis exit all")) {
            assertFalse(Turn.unfinished(w), w)
            assertEquals(600L, Turn.endAfter(w, 600), w)
        }
        // The name alone keeps its own wait (decided before this); a yes or a no is never unfinished, read exactly as before.
        for (w in listOf("Jarvis", "yes", "no", "haan", "nahi", "ya", "yes do it", "no don't", "")) assertFalse(Turn.unfinished(w), w)
        assertFalse(Turn.unfinished(null))
        // A slower learned pace is kept.
        assertEquals(2_400L, Turn.endAfter("Jarvis how is my", 2_400))
        // "Speech ended" over unfinished words keeps the later close; otherwise as before.
        assertEquals(1_100L, Turn.closeIn(now = 10_000, dueAt = 11_100, unfinished = true))
        assertEquals(Turn.END_SPEECH_MS, Turn.closeIn(10_000, 11_100, unfinished = false))
        assertEquals(Turn.END_SPEECH_MS, Turn.closeIn(10_000, 0, unfinished = true))
        assertEquals(Turn.END_SPEECH_MS, Turn.closeIn(10_000, 9_500, unfinished = true))
        assertEquals(300L, Turn.closeIn(10_600, 10_900, unfinished = true))
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

    @Test fun aPlainQuestionIsWorkedOutAheadBeforeItsWordsStandStill() {
        // The question only (the name taken off), at once - no stillness needed: only the answer's words are made.
        assertEquals("how is nifty", Turn.ahead("Jarvis how is nifty", awake = false))
        assertEquals("what are the levels on banknifty", Turn.ahead("Jarvis what are the levels on banknifty", false))
        assertEquals("how is sensex", Turn.ahead("how is sensex", awake = true))
        // Never for anything that acts, the account, the model's words, a breath, or words not said to Jarvis.
        assertNull(Turn.ahead("Jarvis stop all strategies", false))
        assertNull(Turn.ahead("Jarvis buy 2 lots nifty 24500 ce", false))
        assertNull(Turn.ahead("Jarvis what is my pnl today", false))
        assertNull(Turn.ahead("Jarvis backtest the hammer on nifty", false))
        assertNull(Turn.ahead("Jarvis what should I buy", false))
        assertNull(Turn.ahead("Jarvis tell me a joke please", false))
        assertNull(Turn.ahead("Jarvis how", false))
        assertNull(Turn.ahead("Jarvis", false))
        assertNull(Turn.ahead("how is nifty", awake = false))
        assertNull(Turn.ahead(null, true))
        // The same rule as answering early: what is worked out ahead is what may be answered from a still partial.
        for (w in listOf("Jarvis how is nifty", "Jarvis mute", "Jarvis any news", "Jarvis what is my pnl today"))
            assertEquals(Turn.early(w, false, 5_000) != null, Turn.ahead(w, false) != null, w)
    }

    @Test fun theAnswerMadeAheadIsTakenOnlyForTheSameQuestionAndOnlyOnce() {
        val ahead = Ahead<Question, String>()
        // The final words read as the same question (case, a question mark): the answer made ahead is used, once.
        fun k(s: String) = Turn.key(Ask.parse(s))
        ahead.put(k("how is nifty"), "Boss, Nifty is at 24,512.")
        assertEquals("Boss, Nifty is at 24,512.", ahead.take(k("How is Nifty?")))
        assertNull(ahead.take(k("how is nifty")))
        // The words changed before the final reading: dropped, never answered from (and not kept for later).
        ahead.put(k("how is nifty"), "Boss, Nifty is at 24,512.")
        assertNull(ahead.take(k("how is banknifty")))
        assertNull(ahead.take(k("how is nifty")))
        ahead.put(k("how is nifty"), "Boss, Nifty is at 24,512.")
        assertNull(ahead.take(k("how is nifty trending")))
        // A plain question that became a command: never the plain answer.
        ahead.put(k("how is nifty"), "Boss, Nifty is at 24,512.")
        assertNull(ahead.take(k("stop all strategies")))
    }

    @Test fun aQuestionIsReadOnceAndAnOrderOrCommandAfreshEachTime() {
        val q = Ask.parse("what are the levels on finnifty")
        assertSame(q, Ask.parse("what are the levels on finnifty"))
        assertEquals(q, Ask.parse(String(StringBuilder("what are the levels on finnifty"))))
        assertNotSame(Ask.parse("stop all strategies"), Ask.parse("stop all strategies"))
        assertEquals(Ask.parse("stop all strategies"), Ask.parse("stop all strategies"))
        // Many questions later the oldest are read again, the same.
        repeat(Ask.READ_KEPT + 5) { Ask.parse("how is nifty $it") }
        assertEquals(q, Ask.parse("what are the levels on finnifty"))
    }

    @Test fun theSameKeyGivesTheSameAnswer() {
        val now = java.time.LocalDateTime.of(2026, 10, 5, 10, 30)
        fun said(s: String) = Ira(PatternBook()).answer(s, emptyMap(), emptyList(), voice = true, now = now).text
        for ((partial, final) in listOf("how is nifty" to "How is Nifty?", "can you hear me" to "Can you hear me?", "any news" to "Any news.",
                "good morning" to "Good morning!", "what are the levels on banknifty" to "What are the levels on BankNifty?")) {
            assertEquals(Turn.key(Ask.parse(partial)), Turn.key(Ask.parse(final)), partial)
            assertEquals(said(partial), said(final), partial)
        }
    }
}
