package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SaidAboutTest {
    private val today = LocalDate.of(2026, 10, 5) // a Monday

    private val all = listOf(
        SaidAbout.Said(SaidAbout.Source.NOTE, LocalDate.of(2026, 9, 20), "I don't trade Bank Nifty on expiry days"),
        SaidAbout.Said(SaidAbout.Source.NOTE, LocalDate.of(2026, 10, 1), "I get greedy after a win"),
        SaidAbout.Said(SaidAbout.Source.TRADE_NOTE, LocalDate.of(2026, 10, 2), "bought BNF puts on the hammer at the high",
            at = LocalDateTime.of(2026, 10, 2, 11, 5)),
        SaidAbout.Said(SaidAbout.Source.JOURNAL, LocalDate.of(2026, 10, 3), "I chased the breakout on expiry, should have waited",
            at = LocalDateTime.of(2026, 10, 3, 16, 10), question = "Your 11:05 trade came right after a loss - what were you thinking?"),
        SaidAbout.Said(SaidAbout.Source.TRADE_NOTE, LocalDate.of(2026, 10, 5), "Nifty call on the hammer candle", at = LocalDateTime.of(2026, 10, 5, 9, 40)),
    )

    @Test fun readsTheQuestion() {
        assertEquals(SaidAbout.Asked("banknifty"), SaidAbout.asked("what did I say about BankNifty?"))
        assertEquals(SaidAbout.Asked("expiry"), SaidAbout.asked("Jarvis, remind me what I said about expiry"))
        assertEquals(SaidAbout.Asked("hammer", SaidAbout.Period.LAST_WEEK), SaidAbout.asked("did I note anything about the hammer last week?"))
        assertEquals(SaidAbout.Asked("fridays"), SaidAbout.asked("find my notes on Fridays"))
        assertEquals(SaidAbout.Asked("being greedy"), SaidAbout.asked("what have I told you about being greedy"))
        assertEquals(SaidAbout.Asked("bank nifty"), SaidAbout.asked("my notes about bank nifty"))
        assertEquals(SaidAbout.Asked("expiry"), SaidAbout.asked("maine expiry ke baare mein kya kaha tha"))
        assertEquals(SaidAbout.Asked("hammer"), SaidAbout.asked("hammer ke baare mein maine kya bola tha"))
        assertEquals(SaidAbout.Asked("nifty", SaidAbout.Period.TODAY), SaidAbout.asked("what did i write about nifty today"))
    }

    @Test fun leavesOtherQuestionsAlone() {
        for (s in listOf("what did I tell you", "remind me what I said", "what do you know about me", "what did I say about it",
            "what did the RBI say about rates", "remind me at 3 pm to check nifty", "remember that I don't trade on Fridays",
            "how is nifty", "what did I do today", "what did I say about that", "note: bought on the hammer", "my notes"))
            assertNull(SaidAbout.asked(s), s)
    }

    @Test fun indexNamesAndPluralsMatchHoweverSaid() {
        val bank = SaidAbout.find(SaidAbout.Asked("bank nifty"), all, today)
        assertEquals(2, bank.size)
        // Nifty alone is not BankNifty.
        val nifty = SaidAbout.find(SaidAbout.Asked("nifty"), all, today)
        assertEquals(listOf("Nifty call on the hammer candle"), nifty.map { it.text })
        assertEquals(2, SaidAbout.find(SaidAbout.Asked("expiries"), all, today).size)
        assertEquals(1, SaidAbout.find(SaidAbout.Asked("greedy"), all, today).size)
    }

    @Test fun newestFirstAndPeriodNarrows() {
        val h = SaidAbout.find(SaidAbout.Asked("hammer"), all, today)
        assertEquals(listOf(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 2)), h.map { it.day })
        val last = SaidAbout.find(SaidAbout.Asked("hammer", SaidAbout.Period.LAST_WEEK), all, today)
        assertEquals(listOf(LocalDate.of(2026, 10, 2)), last.map { it.day })
        assertEquals(1, SaidAbout.find(SaidAbout.Asked("hammer", SaidAbout.Period.TODAY), all, today).size)
    }

    @Test fun threeWordsMayMissOne() {
        assertEquals(1, SaidAbout.find(SaidAbout.Asked("chasing breakout expiry"), all, today).size)
        assertEquals(1, SaidAbout.find(SaidAbout.Asked("chased breakout friday"), all, today).size)
        assertEquals(0, SaidAbout.find(SaidAbout.Asked("breakout friday"), all, today).size)
    }

    @Test fun saysWhereAndWhenInHisOwnWords() {
        val s = SaidAbout.say(SaidAbout.Asked("expiry"), all, today)
        assertTrue(s.startsWith("2 places you spoke of \"expiry\", Boss"), s)
        assertTrue("On 3 Oct, in your journal (I asked \"Your 11:05 trade came right after a loss - what were you thinking?\"), you said: \"I chased the breakout on expiry, should have waited\"." in s, s)
        assertTrue("On 20 Sep you asked me to remember: \"I don't trade Bank Nifty on expiry days\"." in s, s)
        assertTrue(s.endsWith("Your own words as kept, read back only."), s)
        val t = SaidAbout.say(SaidAbout.Asked("hammer"), all, today)
        assertTrue("Today at 09:40, a note with a trade: \"Nifty call on the hammer candle\"." in t, t)
        val one = SaidAbout.say(SaidAbout.Asked("greedy"), all, today)
        assertTrue(one.startsWith("One place you spoke of \"greedy\", Boss: On 1 Oct you asked me to remember"), one)
    }

    @Test fun honestWhenNothingIsFound() {
        val none = SaidAbout.say(SaidAbout.Asked("sensex"), all, today)
        assertTrue(none.startsWith("I don't find anything you said about \"sensex\", Boss."), none)
        assertTrue("(5 in all)" in none, none)
        val period = SaidAbout.say(SaidAbout.Asked("greedy", SaidAbout.Period.TODAY), all, today)
        assertTrue("ask without \"today\"" in period, period)
        val empty = SaidAbout.say(SaidAbout.Asked("expiry"), emptyList(), today)
        assertTrue(empty.startsWith("You haven't left me any words of yours yet, Boss"), empty)
    }

    @Test fun manyAreCappedWithTheCountSaid() {
        val many = (1..9).map { SaidAbout.Said(SaidAbout.Source.NOTE, today.minusDays(it.toLong()), "expiry note $it") }
        val s = SaidAbout.say(SaidAbout.Asked("expiry"), many, today)
        assertTrue(s.startsWith("9 places you spoke of \"expiry\", Boss - the 6 that match best, newest first."), s)
        assertTrue("And 3 more" in s, s)
        assertTrue("Yesterday you asked me to remember: \"expiry note 1\"." in s, s)
        assertFalse("expiry note 7" in s, s)
    }

    @Test fun neverAnOrderOrACommand() {
        for (s in listOf("what did I say about buying nifty calls", "what did I say about selling banknifty puts",
            "remind me what I said about expiry", "what did I say about the kill switch", "did I say anything about stop loss")) {
            assertNotNull(SaidAbout.asked(s), s)
            val p = Ask.parse(s)
            assertNull(p.order, s)
            assertNull(p.command, s)
            assertTrue(Topic.ACCOUNT in p.topics, s)
        }
    }
}
