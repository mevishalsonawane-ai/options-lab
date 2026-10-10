package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GlobeThinkingTest {
    private val G = GlobeThinking

    @Test fun anAnswerBeingWorkedOutKeepsThinkingUntilTheTimeout() {
        assertFalse(G.stale(now = 10_000, since = 1_000, working = true, speaking = false))
        assertFalse(G.stale(now = 1_000 + G.MAX_MS - 1, since = 1_000, working = true, speaking = false))
        assertTrue(G.stale(now = 1_000 + G.MAX_MS, since = 1_000, working = true, speaking = false))
    }

    @Test fun thinkingWithNothingLeftToWaitForEnds() {
        // The answer's work ended (delivered, failed, cancelled, or a yes-or-no question left to someone else).
        assertTrue(G.stale(now = 1_000 + G.GRACE_MS, since = 1_000, working = false, speaking = false))
        // Just begun: a moment's grace.
        assertFalse(G.stale(now = 1_500, since = 1_000, working = false, speaking = false))
        // When it began is not known: only the work decides.
        assertTrue(G.stale(now = 5_000, since = 0, working = false, speaking = false))
        assertFalse(G.stale(now = 5_000, since = 0, working = true, speaking = false))
        // A clock that went backwards is no reason to stay stuck.
        assertTrue(G.stale(now = 500, since = 1_000, working = true, speaking = false))
    }

    @Test fun speakingIsNeverStaleThinking() {
        assertFalse(G.stale(now = 1_000_000, since = 1_000, working = false, speaking = true))
    }

    @Test fun backgroundWorkNeverShowsAsThinking() {
        // Nothing asked, voice at rest (or off): at rest, whatever the phone is doing in the background.
        assertEquals(G.REST, G.globe(typed = 0, voice = G.REST))
        // Boss's own question: thinking.
        assertEquals(G.THINKING, G.globe(typed = 0, voice = G.THINKING))
        assertEquals(G.THINKING, G.globe(typed = G.THINKING, voice = G.REST))
        // His own backtest he waits on: thinking too.
        assertEquals(G.THINKING, G.globe(typed = 0, voice = G.REST, askedWork = true))
    }

    @Test fun staleVoiceThinkingRestsOnTheGlobe() {
        assertEquals(G.REST, G.globe(typed = 0, voice = G.THINKING, voiceStale = true))
        assertEquals(G.LISTENING, G.globe(typed = 0, voice = G.THINKING, voiceStale = true, drafting = true))
        // Staleness is about thinking only.
        assertEquals(G.ANSWERING, G.globe(typed = 0, voice = G.ANSWERING, voiceStale = true))
    }

    @Test fun theTypedExchangeAndTheVoiceComeFirst() {
        assertEquals(G.ANSWERING, G.globe(typed = G.ANSWERING, voice = G.THINKING))
        assertEquals(G.ANSWERING, G.globe(typed = 0, voice = G.ANSWERING, askedWork = true))
        assertEquals(G.LISTENING, G.globe(typed = 0, voice = G.LISTENING, askedWork = true))
        assertEquals(G.LISTENING, G.globe(typed = 0, voice = G.REST, drafting = true, askedWork = true))
        // Out of range values are kept in range.
        assertEquals(G.REST, G.globe(typed = 9, voice = -1))
    }
}
