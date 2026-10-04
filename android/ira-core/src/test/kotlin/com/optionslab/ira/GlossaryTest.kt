package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GlossaryTest {
    @Test fun wordsAreExplained() {
        assertTrue(Glossary.explain("what is theta")!!.startsWith("Theta"))
        assertTrue(Glossary.explain("Jarvis, explain max pain")!!.startsWith("Max pain"))
        assertTrue(Glossary.explain("what does short covering mean")!!.startsWith("Short covering"))
        assertTrue(Glossary.explain("what is an iron condor")!!.startsWith("An iron condor"))
        assertNotNull(Glossary.explain("what is the meaning of PCR"))
    }

    @Test fun figuresAndOwnThingsAreNotWords() {
        assertNull(Glossary.explain("what is the nifty pcr"), "the number is wanted")
        assertNull(Glossary.explain("what is max pain"), "a figure Jarvis can read")
        assertNull(Glossary.explain("what is my margin"), "the owner's own")
        assertNull(Glossary.explain("how is nifty"))
        assertNull(Glossary.explain("buy a straddle"))
    }
}

class UnderstandingTest {
    @Test fun everydayQuestionsGoWhereTheyShould() {
        assertTrue(Topic.WHY in Ask.parse("what moved the market today").topics)
        assertTrue(Topic.WHY in Ask.parse("market kyun gira aaj").topics)
        assertTrue(Topic.TREND in Ask.parse("bank nifty upar jayega ya neeche").topics)
        kotlin.test.assertEquals(setOf(Topic.OVERVIEW), Ask.parse("where is banknifty trading").topics)
        kotlin.test.assertEquals(setOf(Topic.TRADE_CHECK), Ask.parse("is it a good day to sell options").topics)
        kotlin.test.assertEquals(setOf(Topic.ACCOUNT), Ask.parse("what's my win rate").topics)
        kotlin.test.assertEquals(setOf(Topic.ACCOUNT), Ask.parse("when is the next expiry").topics)
    }
}

class HearingTest {
    @Test fun marketsAsTheRecognizerWritesThem() {
        kotlin.test.assertEquals(listOf(Market.BANKNIFTY), Market.mentioned("how is bang nifty"))
        kotlin.test.assertEquals(listOf(Market.BANKNIFTY), Market.mentioned("how is nifty bank"))
        kotlin.test.assertEquals(listOf(Market.NIFTY), Market.mentioned("how is the nifti"))
        kotlin.test.assertEquals(listOf(Market.SENSEX), Market.mentioned("how is sense x"))
        kotlin.test.assertEquals(listOf(Market.FINNIFTY), Market.mentioned("how is finn nifty"))
        kotlin.test.assertEquals(setOf(Market.NIFTY, Market.BANKNIFTY), Market.mentioned("nifty and bank nifty").toSet())
    }
}
