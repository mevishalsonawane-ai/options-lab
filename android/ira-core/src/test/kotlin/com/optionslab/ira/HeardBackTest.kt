package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeardBackTest {
    @Test fun keyWordsAreSaidOneWay() {
        assertEquals(setOf("banknifty", "today"), HeardBack.keys("What is Bank Nifty doing today?"))
        assertEquals(setOf("nifty", "24500", "call"), HeardBack.keys("Nifty 24,500 CE price"))
        assertEquals(HeardBack.keys("nifty 50 calls"), HeardBack.keys("Nifty call"))
        assertEquals(setOf("vix"), HeardBack.keys("india vix level"))
    }

    @Test fun aLowScoreQuestionIsReadBack() {
        assertEquals(HeardBack.Why.LOW_SCORE, HeardBack.why("what is bank nifty doing", listOf("what is bank nifty doing"), 0.3f, true))
        assertNull(HeardBack.why("what is bank nifty doing", listOf("what is bank nifty doing"), 0.7f, true), "sure enough")
        assertNull(HeardBack.why("what is bank nifty doing", listOf("what is bank nifty doing"), null, true), "no score, one reading")
    }

    @Test fun readingsThatDifferInWhatMattersAreReadBack() {
        val r = listOf("what is nifty doing", "what is bank nifty doing")
        assertEquals(HeardBack.Why.READINGS_DIFFER, HeardBack.why(r[0], r, null, true))
        assertEquals(HeardBack.Why.READINGS_DIFFER, HeardBack.why(r[0], r, 0.6f, true))
        assertNull(HeardBack.why(r[0], r, 0.9f, true), "the recognizer was sure of the best reading")
        // Readings differing only in small words change nothing.
        assertNull(HeardBack.why("what's nifty doing", listOf("what's nifty doing", "whats nifty doing now"), null, true))
        assertEquals(HeardBack.Why.READINGS_DIFFER, HeardBack.why("nifty 24500 put", listOf("nifty 24500 put", "nifty 24000 put"), null, true))
    }

    @Test fun ordersCommandsLongAndLockedAccountAreNeverReadBack() {
        assertNull(HeardBack.why("exit bank nifty", listOf("exit bank nifty"), 0.2f, isQuestion = false))
        assertNull(HeardBack.why("what's my p and l today", listOf("what's my p and l today"), 0.2f, true, lockedAccount = true))
        val long = (1..15).joinToString(" ") { "word" }
        assertNull(HeardBack.why(long, listOf(long), 0.2f, true))
        assertNull(HeardBack.why("  ", listOf(""), 0.2f, true))
    }

    @Test fun theLineIsOneSentenceSecretsHidden() {
        assertEquals("Boss, I took that as \"what is bank nifty doing\".", HeardBack.line("what is bank nifty doing?"))
        assertTrue(HeardBack.line("my pin is 4321 right").contains(Secrets.HIDDEN))
        assertEquals(1, BargeIn.sentences(HeardBack.line("is it up? or down. really!")).size)
        assertEquals("बॉस, मैंने सुना: \"nifty kya hai\"।", HeardBack.line("nifty kya hai?", hindi = true))
    }

    @Test fun bossIsNamedOnceAndGoOnStillLinesUp() {
        val line = HeardBack.line("what is bank nifty doing")
        val spoken = Aloud.say("Bank Nifty is up 0.4% today, Boss. It led the market.")
        assertEquals("Boss, I took that as \"what is bank nifty doing\". Bank Nifty is up 0.4 percent today. It led the market.",
            HeardBack.lead(line, spoken))
        assertEquals("Same.", HeardBack.lead(null, "Same."))
        // Cut while the answer's first sentence was being said: "go on" says the answer from its first sentence.
        val full = HeardBack.full(line, "Bank Nifty is up 0.4% today. It led the market.")
        val said = HeardBack.lead(line, spoken)
        val c = BargeIn.cutOff(full, said, said.indexOf("up 0.4"), false, 0L)!!
        assertEquals(BargeIn.Rest.Say("Bank Nifty is up 0.4% today. It led the market."), BargeIn.rest(c, 1L, false))
    }
}
