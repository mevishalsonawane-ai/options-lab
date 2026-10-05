package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Voice, round 26: Boss's diagnostics, 5 Oct (Pixel 9, en-US recognizer). */
class MisHeardTest {
    @Test fun bossesFragmentsAreMisHeard() {
        for (s in listOf("bus", "pause", "calculation", "peppermine", "office schedule", "what s the piano", "what's the piano", "why you", "Bus.", "piano"))
            assertTrue(MisHeard.fragment(s, voice = true), s)
    }

    @Test fun realShortCommandsAreNeverFragments() {
        for (s in listOf("mute", "unmute", "stop", "yes", "no", "cancel", "go on", "repeat", "Mute.", "Stop!", "Yes", "no thanks", "repeat that",
                "more", "tell me more", "shorter", "stop talking", "be quiet"))
            assertFalse(MisHeard.fragment(s, voice = true), s)
    }

    @Test fun realShortQuestionsAreNeverFragments() {
        for (s in listOf("nifty?", "p&l?", "positions", "Nifty", "bank nifty", "sensex?", "vix", "funds", "margin", "orders", "my p&l", "status",
                "theta", "max pain", "news", "24500?", "why", "why?", "what", "how are you", "who are you", "help", "hello", "good evening",
                "good morning", "thanks", "thank you", "ok", "okay", "hmm", "acha", "theek hai", "kya hal hai", "nifty kaisa hai", "and banknifty",
                "what time is it", "gold", "mtm", "pnl"))
            assertFalse(MisHeard.fragment(s, voice = true), s)
    }

    @Test fun longerWordsAreNotFragments() {
        assertFalse(MisHeard.fragment("why did we not face the face the laws today", voice = true))
        assertFalse(MisHeard.fragment("tell me about the office schedule", voice = true))
        assertFalse(MisHeard.fragment("", voice = true))
        assertFalse(MisHeard.fragment("   ", voice = true))
    }

    @Test fun typedKeepsTodaysBehaviourButASingleNonWord() {
        for (s in listOf("bus", "pause", "office schedule", "what s the piano", "why you", "calculation"))
            assertFalse(MisHeard.fragment(s, voice = false), s)
        for (s in listOf("sdfg", "zzzz", "aaaa", "xkcd"))
            assertTrue(MisHeard.fragment(s, voice = false), s)
        for (s in listOf("hmm", "pnl", "mtm", "nifty", "p&l", "mute", "stop", "no", "ltp", "sdfg qwrt", "24500"))
            assertFalse(MisHeard.fragment(s, voice = false), s)
    }

    @Test fun nearMissesAreRescuedAsQuestions() {
        assertEquals("why did we not face the laws today", MisHeard.withoutRepeats("why did we not face the face the laws today"))
        assertEquals("how is nifty", MisHeard.withoutRepeats("how is is nifty"))
        assertEquals("what 50 50", MisHeard.withoutRepeats("what 50 50"))
        assertEquals("what is the vix", MisHeard.rescue("what is the the vix"))
        assertNotNull(MisHeard.rescue("sensei"))
        for (s in listOf("bus", "pause", "calculation", "peppermine", "office schedule", "what s the piano", "why you"))
            assertNull(MisHeard.rescue(s), s)
    }

    @Test fun aRescueNeverActs() {
        for (s in listOf("stop stop", "stop all all strategies", "cancel cancel", "buy buy nifty", "mute mute", "yes yes", "no no",
                "kill kill switch", "go live live", "start start arms", "close close all positions", "sell sell banknifty 52000 ce")) {
            val r = MisHeard.rescue(s)
            if (r != null) {
                val q = Ask.parse(r)
                assertNull(q.command, s); assertNull(q.order, s)
                assertFalse(Topic.COMMAND in q.topics || Topic.ORDER in q.topics, s)
            }
            assertNull(r, s)
        }
    }

    @Test fun wakeSlipsAtTheStartOnly() {
        assertEquals(Wake.Heard.Ask("good evening"), Wake.heard("darius good evening", false))
        assertEquals(Wake.Heard.Ask("good evening"), Wake.heard("Darius, good evening", true))
        assertEquals(Wake.Heard.Ask("how is nifty"), Wake.heard("travis how is nifty", false))
        assertEquals(Wake.Heard.Ask("what is the vix"), Wake.heard("harvest what is the vix", false))
        assertEquals(Wake.Heard.Ask("good evening"), Wake.heard("Jarvis's good evening", false))
        assertEquals(Wake.Heard.Awake, Wake.heard("jarvis's", false))
        assertEquals(Wake.Heard.Ask("how is nifty"), Wake.heard("service how is nifty", false))
        // Alone, later in the sentence, or before other words: never the name.
        assertEquals(Wake.Heard.Ignore, Wake.heard("darius", false))
        assertEquals(Wake.Heard.Ignore, Wake.heard("travis scott is playing", false))
        assertEquals(Wake.Heard.Ignore, Wake.heard("harvest season is here", false))
        assertEquals(Wake.Heard.Ignore, Wake.heard("my friend darius good evening", false))
        assertEquals(Wake.Heard.Ignore, Wake.heard("I met travis how is he", false))
        // Never "named": acting still needs the name itself.
        assertFalse(Wake.named("darius good evening"))
        assertFalse(Wake.named("travis stop all strategies"))
        assertTrue(Wake.named("jarvis's good evening"))
        // A slip never makes a command named or a stop of listening.
        assertEquals(Wake.Heard.Ignore, Wake.heard("darius stop all strategies", false))
    }

    @Test fun hinglishNeverBecomesTheName() {
        for (s in listOf("jaruri hai kya", "jarur batao", "jaise hi nifty", "dar hai kya", "haar gaye kya", "jaa raha hai nifty", "aaj kya hua"))
            assertEquals(Wake.Heard.Ignore, Wake.heard(s, false), s)
    }

    @Test fun usageCountsMisHeardApart() {
        val d = Usage.Day(heard = 31, misunderstood = 3, misheard = 17)
        assertEquals(14, d.questions)
        val line = Usage.line(d)!!
        assertTrue(line.startsWith("I heard 14 questions today, did not understand 3"), line)
        assertTrue("mis-heard 17" in line, line)
        assertEquals("I heard 2 questions today.", Usage.line(Usage.Day(heard = 2)))
        assertEquals(0, Usage.Day(heard = 1, misheard = 5).questions)
    }

    @Test fun weekUnderstandingIsOfRealQuestions() {
        val start = LocalDate.of(2026, 10, 5)
        val w = Improve.week(start, listOf(mapOf(Improve.HEARD to 31, Improve.MISUNDERSTOOD to 3, Improve.MISHEARD to 17)))
        assertEquals(14, w.heard)
        assertEquals(17, w.misheard)
        assertEquals((14 - 3) * 100 / 14, w.understoodShare)
        // No mis-heard count kept (older days): as before.
        assertEquals(31, Improve.week(start, listOf(mapOf(Improve.HEARD to 31))).heard)
    }
}
