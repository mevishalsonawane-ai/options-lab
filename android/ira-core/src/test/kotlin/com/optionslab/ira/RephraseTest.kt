package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RephraseTest {
    @Test fun aMissedQuestionIsLearnedFromTheNextWording() {
        assertTrue(Corrections.missed("how s the street looking"))
        val l = assertNotNull(Corrections.rephrase("how s the street looking", "how is nifty doing"))
        assertEquals("how is nifty doing", Corrections.apply("how's the street looking", listOf(l)))
    }

    @Test fun neverSmallTalkCommandsOrOrders() {
        // Not a miss: small talk, about Jarvis, numbers, commands.
        for (s in listOf("how are you", "how was your day", "buy 2 lots at 25000", "stop all strategies")) assertTrue(!Corrections.missed(s), s)
        // The next words must ask about the markets or the account: never a command, an order or small talk.
        assertNull(Corrections.rephrase("how s the street looking", "stop all strategies"))
        assertNull(Corrections.rephrase("how s the street looking", "buy 1 lot nifty 25000 ce"))
        assertNull(Corrections.rephrase("how s the street looking", "how are you"))
        assertNull(Corrections.rephrase("how s the street looking", "what can you do"))
    }

    @Test fun aLearnedWordingNeverActs() {
        val l = Corrections.Learned("how s the street looking", "stop all strategies")
        assertNull(Corrections.apply("how s the street looking", listOf(l)))
    }
}
