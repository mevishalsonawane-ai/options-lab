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
