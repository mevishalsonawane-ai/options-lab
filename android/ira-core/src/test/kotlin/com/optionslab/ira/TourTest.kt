package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TourTest {
    private val audit = CoverageTest()
    private val monday = LocalDate.of(2026, 10, 5)

    @Test fun asksWhatToAsk() {
        for (s in listOf("what can I ask you?", "Jarvis, what can I ask you", "what else can I ask Jarvis?", "what should I ask now",
            "what should i ask you today", "What questions can I ask?", "which questions should I ask you", "what kind of questions can I ask",
            "suggest some questions", "give me some ideas of what to ask", "any good questions to ask?", "what's worth asking right now",
            "give me a tour", "main kya pooch sakta hoon", "tumse kya puchu", "aapse kya pooch sakte hain", "kya poochna chahiye", "kuch sawal batao"))
            assertTrue(Tour.asked(s), s)
    }

    @Test fun leavesOtherQuestionsAlone() {
        for (s in listOf("what can you do", "help", "who are you", "what can I ask about BankNifty levels", "what should I buy",
            "what should I ask for as a target", "what is the pcr", "what did I ask you yesterday", "what do I usually ask",
            "kya main trade kar sakta hoon", "suggest a trade", "give me a trade idea", "buy 1 lot nifty atm ce", "what can I ask you, then exit all"))
            assertFalse(Tour.asked(s), s)
    }

    @Test fun thePartOfTheDay() {
        assertEquals(Tour.Part.PRE_OPEN, Tour.part(8 * 60 + 30, true))
        assertEquals(Tour.Part.MARKET, Tour.part(9 * 60 + 15, true))
        assertEquals(Tour.Part.MARKET, Tour.part(15 * 60 + 29, true))
        assertEquals(Tour.Part.AFTER_CLOSE, Tour.part(15 * 60 + 30, true))
        assertEquals(Tour.Part.AFTER_CLOSE, Tour.part(23 * 60, true))
        assertEquals(Tour.Part.CLOSED, Tour.part(11 * 60, false))
    }

    @Test fun fiveQuestionsTwoFixedThreeTurning() {
        for (part in Tour.Part.values()) {
            val seen = mutableSetOf<String>()
            for (d in 0L until 10L) {
                val qs = Tour.questions(part, monday.plusDays(d))
                assertEquals(Tour.NAMED, qs.size, "$part")
                assertEquals(qs.size, qs.distinct().size, "$part")
                assertEquals(Tour.QUESTIONS.getValue(part).take(2), qs.take(2))
                seen += qs
            }
            // Over a few days every question of the part gets named.
            assertEquals(Tour.QUESTIONS.getValue(part).toSet(), seen, "$part")
            assertTrue(Tour.questions(part, monday) != Tour.questions(part, monday.plusDays(1)), "$part")
        }
    }

    @Test fun everyNamedQuestionIsAnsweredAsSaidAndNoneActs() {
        val wanted = mapOf(
            "am I ready to trade" to "PreMarket", "what's the main news today" to "NewsDesk", "what does this week look like" to "WeekAhead",
            "do gap downs usually fill" to "GapRecord", "what if Nifty opens 1% down" to "Scenarios", "is this an expiry week" to "WeekAhead",
            "what have you learned this week" to "Learnings", "what matters right now" to "CoPilot", "what's the structure today" to "Structure",
            "where is the most call writing" to "ChainIntel", "how close am I to my limits" to "Headroom", "has max pain shifted since morning" to "ChainDrift",
            "is the low of the day usually in by now" to "DayClock", "help me journal today" to "DayJournal", "how has max pain moved today" to "ChainDrift",
            "what does next week look like" to "WeekAhead", "are Mondays more volatile" to "Weekdays",
            "check my positions" to "Account:HEALTH", "why did the market move today" to "Causes",
            "am I on a winning streak" to "Account:STREAKS", "what's my best weekday" to "Account:STREAKS",
        )
        for (q in Tour.QUESTIONS.values.flatten().distinct()) {
            val p = Ask.parse(q)
            assertNull(p.order, q); assertNull(p.command, q)
            assertTrue(Topic.ORDER !in p.topics && Topic.COMMAND !in p.topics, q)
            assertFalse(Bundle.acts(q), q); assertFalse(FollowUp.acts(q), q)
            assertFalse(Tour.asked(q), q)
            val got = audit.feature(q)
            assertTrue(got != "Tour" && got.isNotBlank(), "$q: $got")
            assertEquals(wanted[q], got, q)
        }
    }

    @Test fun theTourAsSaid() {
        val a = Tour.answer(Tour.Part.PRE_OPEN, monday)
        assertTrue(a.startsWith("Before the open, Boss, five good ones to ask me: \"am I ready to trade\", \"what's the main news today\", "), a)
        assertTrue(a.endsWith("or \"what can you do\" for everything."), a)
        assertEquals(5, Regex("\"[^\"]+\"").findAll(a.substringBefore(". Ask")).count())
        // All of it said aloud: two sentences, no question mark to stop the voice inside a quote, "Boss" once.
        val spoken = Aloud.say(a)
        assertFalse(spoken.contains("The rest is in the chat"), spoken)
        assertFalse(a.contains("?"), a)
        assertEquals(1, Regex("\\bBoss\\b").findAll(spoken).count(), spoken)
        // Nothing of the account in it: the same words on a locked phone.
        assertTrue(Tour.answer(Tour.Part.CLOSED, monday).startsWith("With no session today, Boss"))
    }
}
