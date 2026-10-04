package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WriterTest {
    private val facts = listOf("BankNifty: last price 52,140.25 at 11:15", "BankNifty: previous close 52,400.00, change -259.75 (-0.50%)")
    private val draft = "BankNifty is at 52,140.25 (-0.50% on the day) as of 11:15."

    @Test fun thePromptCarriesOnlyTheQuestionFactsAndDraft() {
        val p = Writer.prompt("How is bank nifty?", facts, draft)
        assertTrue(p.startsWith("<|im_start|>system\n") && p.endsWith("<|im_start|>assistant\n"))
        assertTrue(facts.all { "- $it" in p } && "DRAFT: $draft" in p && "QUESTION: How is bank nifty?" in p)
    }

    @Test fun aFaithfulRewriteIsKept() {
        val out = "BankNifty is trading at 52,140.25 as of 11:15, down 0.50% from yesterday's close of 52,400.00.<|im_end|>"
        assertEquals("BankNifty is trading at 52,140.25 as of 11:15, down 0.50% from yesterday's close of 52,400.00.", Writer.check(out, facts, draft))
    }

    @Test fun madeUpNumbersAdviceLinksAndEmptinessAreThrownAway() {
        assertNull(Writer.check("BankNifty is at 52,150 now.", facts, draft), "a number not in the facts")
        assertNull(Writer.check("BankNifty is at 52,140.25; you should buy the dip.", facts, draft), "advice")
        assertNull(Writer.check("BankNifty at 52,140.25 is likely to rise.", facts, draft), "a forecast")
        assertNull(Writer.check("See https://example.com for 52,140.25.", facts, draft), "a link")
        assertNull(Writer.check("  <|im_end|>", facts, draft))
        assertNull(Writer.check("x".repeat(1000), facts, draft))
    }

    @Test fun longAnswersAreCutToFourSentences() {
        val out = "One. Two. Three. Four. Five."
        assertEquals("One. Two. Three. Four.", Writer.check(out, facts, draft))
    }

    @Test fun onlyFactBasedAnswersAreRewritten() {
        val q = Ask.parse("how is banknifty")
        assertTrue(Writer.worthRewriting(q, Answer(draft, facts)))
        assertFalse(Writer.worthRewriting(q, Answer("I only know...", emptyList())))
        assertFalse(Writer.worthRewriting(Ask.parse("should I buy banknifty"), Answer(draft, facts)))
        val o = Ask.parse("buy 1 lot banknifty 52000 ce")
        assertFalse(Writer.worthRewriting(o, Answer("Ready", facts, o.order)))
    }
}
