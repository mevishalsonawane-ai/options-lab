package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpellingTest {
    @Test fun nearMissesAreReadAsJarvisWords() {
        assertEquals("i dlnr see any strategies on", Spelling.fix("i dlnr see any statergirs on"))
        assertEquals("show my position", Spelling.fix("show my postions"))
        assertEquals("stop strategy 1", Spelling.fix("stop stratgy 1"))
        assertEquals("cancel all orders", Spelling.fix("cancle all ordrs"))
        assertEquals("how is banknifty", Spelling.fix("how is bnknifty"))
        // Known words and ordinary English are left alone.
        for (s in listOf("buy 1 lot nifty atm ce", "the stock market today", "start strategy 2", "what is a hammer", "Should I trade now?"))
            assertEquals(s, Spelling.fix(s))
    }

    @Test fun theOwnersQuestionIsUnderstood() {
        assertEquals(Topic.ACCOUNT, Ask.parse("i dlnr see any statergirs on").topics.first())
        assertTrue(Section.STRATEGIES in AppAnswers.sections(Spelling.fix("i dlnr see any statergirs on")))
        assertEquals(Command.Kind.START_ALL, Commands.parse("start all statergies")?.kind)
    }
}
