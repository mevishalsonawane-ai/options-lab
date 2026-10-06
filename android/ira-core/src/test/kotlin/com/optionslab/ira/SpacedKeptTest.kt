package com.optionslab.ira

import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Speed round 11: the question's spaced words are made once per words and shared ([Spaced]) - some sixty readers each
 * lowercased and spaced the same question again. Work is counted by the forms made ([Spaced.madeCount]), never by the
 * clock; each form is checked to be exactly what every reader's own spacing gave.
 */
class SpacedKeptTest {
    @BeforeTest @AfterTest fun forget() = Spaced.forget()

    /** Fifty questions as Boss says them (English, Hinglish, a follow-up, curly and straight apostrophes, a figure). */
    private val questions = listOf(
        "how is nifty doing today", "what are my positions", "what's my p&l today", "why are my charges so high",
        "nifty ka kya haal hai", "should I trade now?", "what is a hammer", "how was yesterday for bank nifty",
        "umm and bank nifty?", "what’s the vix doing", "where is sensex trading", "any requests waiting",
        "what did I ask you yesterday", "how many trades did I take today", "how am I doing this month",
        "what's the gap today", "did nifty fill the gap", "what time did nifty make its high", "is bank nifty leading",
        "how far is nifty from 25000", "what is my streak", "how much did I give back today", "what's the max pain",
        "what's the pcr", "how did the first hour go", "what happened in the last hour", "is today an inside day",
        "what were the big candles today", "how often does nifty close near its high", "what's the week's range",
        "how did nifty do after a gap up", "what about after a loss day", "why did you say that", "you got that wrong",
        "speak shorter", "what do you know about me", "forget what I said about sensex", "what's the plan for tomorrow",
        "how's the bot doing", "is the relay working", "how's my battery", "kal ka kya plan hai", "aaj market kaisa hai",
        "can you tell me the levels for nifty", "jarvis what's the trend", "what's moving", "explain theta",
        "don't trade today?", "what-if nifty falls 1%", "show my journal"
    )

    /** Every way a reader spaced the words before (each reader's own replaces, in its own order). */
    private fun oldWords(s: String): List<String> = listOf(
        " " + spacedWords(s.lowercase()) + " ",
        " " + spacedWords(s.lowercase(Locale.ENGLISH)) + " ",
        " " + spacedWords(s.lowercase(Locale.ENGLISH).replace("’", "'")) + " ",
        " " + spacedWords(s.lowercase(Locale.ENGLISH).replace("’", "'").replace("'", " ")) + " ",
        " " + spacedWords(s.lowercase(Locale.ENGLISH).replace("'", " ").replace("’", " ").replace("-", " ")) + " ",
        " " + spacedWords(s.lowercase().replace("'", " ")) + " "
    )

    private fun oldJoined(s: String): List<String> = listOf(
        " " + spacedWords(s.lowercase().replace("'", "").replace("’", "")) + " ",
        " " + spacedWords(s.lowercase(Locale.ENGLISH).replace("’", "").replace("'", "")) + " ",
        " " + spacedWords(s.lowercase(Locale.ENGLISH).replace("'", "").replace("’", "")) + " ",
        " " + spacedWords(s.lowercase(Locale.ENGLISH).replace("’", "'").replace("'", "")) + " ",
        " " + spacedWords(s.lowercase(Locale.ENGLISH).replace("'", "").replace("’", "").replace("-", " ")) + " "
    )

    @Test fun eachFormIsExactlyWhatEveryReaderMadeBefore() {
        val odd = listOf("", "  ", "Don't", "DON’T-STOP", "İstanbul’s P&L", "a'’b", "naïve café", "x--y", "’", "'quoted'", "ŞAHİN", "Ä 50%")
        for (s in questions + odd) {
            for (old in oldWords(s)) assertEquals(old, Spaced.words(s), s)
            for (old in oldJoined(s)) assertEquals(old, Spaced.joined(s), s)
        }
    }

    @Test fun manyReadersOfOneQuestionMakeEachFormOnce() {
        val q = "what's my p&l today and how's nifty"
        val readers: List<(String) -> Any?> = listOf(
            { Airtime.asked(it) }, { Breadth.asked(it) }, { Clarity.asked(it) }, { AskedAgain.asked(it) },
            { HonestStars.asked(it) }, { Weekdays.asked(it) }, { LeadIndex.asked(it) }, { LeadPart.asked(it) }, { MoreAfter.asked(it) },
            { MorningSense.asked(it) }, { NextAsk.asked(it) }, { Nicknames.asked(it) }, { UsualIndex.asked(it) }, { WordFit.asked(it) },
            { SmallTrades.asked(it) }, { InsideDays.asked(it) }, { MarketStory.asked(it) }, { MonthReview.asked(it) },
            { ExpiryDay.asked(it) }, { PriorDay.asked(it) }, { OutlookCheck.asked(it) }
        )
        val first = readers.map { it(q) }
        val made = Spaced.madeCount
        // Twenty readers, a handful of forms (the words as said and as read, each spaced two ways), not twenty or more.
        assertTrue(made in 1..6, "made $made")
        // Asked again (the hub asks most readers more than once per question): nothing made again, the same answers.
        assertEquals(first, readers.map { it(q) })
        assertEquals(made, Spaced.madeCount)
    }

    @Test fun theHubsWholeChainSpacesEachQuestionAFewTimesNotNinetyAndAskedAgainNotAtAll() {
        val c = CoverageTest()
        val per = questions.map { q -> val before = Spaced.madeCount; c.feature(q); Spaced.madeCount - before }
        // Before the forms were shared, the chain spaced each of these questions some eighty to ninety times (over 4,000 in all).
        assertTrue(per.all { it <= 12 } && per.sum() <= 150, "per question: $per")
        val all = Spaced.madeCount
        for (q in questions.takeLast(5)) c.feature(q)
        assertEquals(all, Spaced.madeCount)
    }

    @Test fun keptFormsAreTheSameObjectsAndTheResetHookForgets() {
        val q = "don't trade today?"
        val w = Spaced.words(q)
        val j = Spaced.joined(q)
        assertSame(w, Spaced.words(q))
        assertSame(j, Spaced.joined(q))
        assertEquals(2, Spaced.madeCount)
        Spaced.forget()
        assertEquals(0, Spaced.madeCount)
        assertEquals(w, Spaced.words(q))
        assertEquals(1, Spaced.madeCount)
    }
}
