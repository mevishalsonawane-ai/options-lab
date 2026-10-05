package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NextAskTest {
    private val today = LocalDate.of(2026, 10, 5)
    private val now = LocalDateTime.of(2026, 10, 5, 11, 0)

    /** On each of [days] days before today at 10:00: [first], then [then] [gap] minutes later. */
    private fun log(days: Int, first: String, then: String?, gap: Long = 1, from: Int = 1): List<Routine.Seen> =
        (from until from + days).flatMap { d ->
            val at = today.minusDays(d.toLong()).atTime(10, 0)
            listOfNotNull(Routine.Seen(first, at), then?.let { Routine.Seen(it, at.plusMinutes(gap)) })
        }

    @Test fun learnedFromTheRoutineLogAlreadyKept() {
        val r = NextAsk.learned(log(4, "NIFTY|levels", "BANKNIFTY|levels"), NextAsk.Log(), today).single()
        assertEquals("NIFTY|levels", r.after); assertEquals("BANKNIFTY|levels", r.next)
        assertEquals(4, r.times); assertEquals(4, r.of); assertEquals(4, r.days); assertEquals(today.minusDays(1), r.newest)
        assertEquals("BankNifty's levels next, Boss?", NextAsk.line(r))
        assertTrue(r.say().contains("after Nifty's levels you asked about BankNifty's levels next 4 of the 4 times, on 4 days"), r.say())
        // Built the way the app builds it: Routine's own keys and log.
        var l: List<Routine.Seen> = emptyList()
        for (d in 1..4) {
            val at = today.minusDays(d.toLong()).atTime(9, 30)
            l = Routine.add(l, Routine.Seen(Routine.key("what are the levels on nifty")!!, at))
            l = Routine.add(l, Routine.Seen(Routine.key("what is my P&L today")!!, at.plusMinutes(2)))
        }
        val p = NextAsk.learned(l, NextAsk.Log(), today).single()
        assertEquals("ACCOUNT|pnl", p.next); assertEquals("Your P&L next, Boss?", NextAsk.line(p))
    }

    @Test fun tooFewTooLateOrMixedLearnsNothing() {
        assertTrue(NextAsk.learned(emptyList(), NextAsk.Log(), today).isEmpty())
        // Under the count, on too few days, more than 3 minutes later, older than the window.
        assertTrue(NextAsk.learned(log(3, "NIFTY|levels", "BANKNIFTY|levels"), NextAsk.Log(), today).isEmpty())
        assertTrue(NextAsk.learned(log(4, "NIFTY|levels", "BANKNIFTY|levels", gap = 4), NextAsk.Log(), today).isEmpty())
        assertTrue(NextAsk.learned(log(4, "NIFTY|levels", "BANKNIFTY|levels", from = 30), NextAsk.Log(), today).isEmpty())
        // Under half the times the kind was asked (4 of 9).
        val alone = log(5, "NIFTY|levels", null, from = 10)
        assertTrue(NextAsk.learned(log(4, "NIFTY|levels", "BANKNIFTY|levels") + alone, NextAsk.Log(), today).isEmpty())
        // Two follow-ups tied: no clear one.
        val other = log(4, "NIFTY|levels", "NIFTY|news", from = 10)
        assertTrue(NextAsk.learned(log(4, "NIFTY|levels", "BANKNIFTY|levels") + other, NextAsk.Log(), today).isEmpty())
        // What the first answer gives already (the levels after Nifty's overview) never counts.
        assertTrue(NextAsk.learned(log(5, "NIFTY|overview", "NIFTY|levels"), NextAsk.Log(), today).isEmpty())
        // After the account: no market answer to end with an offer.
        assertTrue(NextAsk.learned(log(5, "ACCOUNT|pnl", "NIFTY|overview"), NextAsk.Log(), today).isEmpty())
        // Since the undo only.
        assertTrue(NextAsk.learned(log(4, "NIFTY|levels", "BANKNIFTY|levels"), NextAsk.reset(today.atTime(8, 0)), today).isEmpty())
        assertEquals(1, NextAsk.learned(log(4, "NIFTY|levels", "BANKNIFTY|levels"), NextAsk.reset(today.minusDays(9).atTime(8, 0)), today).size)
    }

    private val learned = NextAsk.learned(log(4, "NIFTY|levels", "BANKNIFTY|levels"), NextAsk.Log(), today)

    @Test fun offeredOnlyAtTheEndOfItsOwnAnswer() {
        val r = assertNotNull(NextAsk.offer(learned, "what are the levels on nifty", emptyList(), now, locked = false))
        assertEquals("BANKNIFTY|levels", r.next)
        // Never on a locked phone.
        assertNull(NextAsk.offer(learned, "what are the levels on nifty", emptyList(), now, locked = true))
        // Another kind of question: nothing.
        assertNull(NextAsk.offer(learned, "what is the trend on nifty", emptyList(), now, locked = false))
        assertNull(NextAsk.offer(learned, "what is my P&L today", emptyList(), now, locked = false))
        // The answer gives it already: both indices asked.
        assertNull(NextAsk.offer(learned, "what are the levels on nifty and banknifty", emptyList(), now, locked = false))
        // Asked in the last 10 minutes: not offered; longer ago: offered.
        val recent = listOf(Routine.Seen("BANKNIFTY|levels", now.minusMinutes(4)))
        assertNull(NextAsk.offer(learned, "what are the levels on nifty", recent, now, locked = false))
        assertNotNull(NextAsk.offer(learned, "what are the levels on nifty", listOf(Routine.Seen("BANKNIFTY|levels", now.minusMinutes(40))), now, locked = false))
        // An order or a command is never answered with an offer.
        assertNull(NextAsk.offer(learned, "buy 1 lot nifty 24500 ce", emptyList(), now, locked = false))
        assertNull(NextAsk.offer(learned, "stop all strategies", emptyList(), now, locked = false))
    }

    @Test fun theOfferIsKeptInTheShortAnswer() {
        val answer = "Nifty's nearest level above is 24,700, 100 points away. The level below is 24,500, 100 points away. " +
            "The day's range is 24,450 to 24,720 so far. " + NextAsk.line(learned.single())
        val s = ShortAnswer.of("what are the levels on nifty", answer)
        assertTrue(s.line.endsWith("BankNifty's levels next, Boss?"), s.line)
        assertFalse(ShortAnswer.exempt(NextAsk.line(learned.single())))
    }

    @Test fun onlyABareYesInTimeAsksIt() {
        val at = now
        assertEquals("what are the levels on BankNifty", NextAsk.taken("BANKNIFTY|levels", at, "yes", false, false, at.plusMinutes(1)))
        assertEquals("what are the levels on BankNifty", NextAsk.taken("BANKNIFTY|levels", at, "haan batao", false, false, at))
        assertEquals("what is my P&L today", NextAsk.taken("ACCOUNT|pnl", at, "yes please", false, false, at))
        // Late, other words, something waiting for his yes, or a locked phone: never.
        assertNull(NextAsk.taken("BANKNIFTY|levels", at, "yes", false, false, at.plusMinutes(3)))
        assertNull(NextAsk.taken("BANKNIFTY|levels", at, "yes buy it", false, false, at))
        assertNull(NextAsk.taken("BANKNIFTY|levels", at, "no", false, false, at))
        assertNull(NextAsk.taken("BANKNIFTY|levels", at, "yes", true, false, at))
        assertNull(NextAsk.taken("ACCOUNT|pnl", at, "yes", false, true, at))
        // What it asks is a question: never an order or a command.
        val q = NextAsk.taken("BANKNIFTY|levels", at, "yes", false, false, at)!!
        val p = Ask.parse(q); assertNull(p.order); assertNull(p.command); assertFalse(Bundle.acts(q))
    }

    @Test fun askedAndItsUndoNeverCatchOrdinaryQuestions() {
        for (q in listOf("what do I usually ask next?", "Jarvis, what do I ask after the levels", "which follow ups do i ask",
                "what follow-ups have you learned", "why do you keep offering the next question", "what do you offer after an answer",
                "main uske baad kya puchta hoon")) assertEquals(NextAsk.Request.WHICH, NextAsk.asked(q), q)
        for (q in listOf(NextAsk.UNDO, "Stop offering what I ask next, Jarvis", "stop suggesting the next question", "don't offer follow ups",
                "do not ask me what comes next", "stop ending your answers with a question", "no more follow-up offers",
                "agla sawal mat pucho", "agla sawal offer mat karo", "quit offering the next question please")) {
            assertEquals(NextAsk.Request.RESET, NextAsk.asked(q), q)
            // Never a stop of an arm, an order, or anything that acts.
            val p = Ask.parse(q); assertNull(p.command, q); assertNull(p.order, q); assertNull(Commands.parse(q), q)
            assertFalse(Bundle.acts(q), q)
        }
        // Ordinary questions and commands keep their route.
        for (q in listOf("what next", "what's next for nifty", "what is next", "next question", "what should i ask next", "levels next",
                "banknifty levels next", "what is the next level", "next resistance on nifty", "what do i usually ask",
                "what do i usually ask in the morning", "stop orb", "stop all strategies", "stop offering trades", "don't offer my usual morning question",
                "agla level kya hai", "what comes next for banknifty", "yes", "how is nifty", "what is my p&l")) assertNull(NextAsk.asked(q), q)
        assertTrue(Commands.parse("stop orb") != null)
        // The undo is its own, not another habit's; "what do I usually ask" stays the routine's.
        assertNull(MorningAsks.asked(NextAsk.UNDO)); assertNull(LeadPart.asked(NextAsk.UNDO)); assertNull(LeadIndex.asked(NextAsk.UNDO))
        assertTrue(Routine.asked("what do i usually ask")); assertFalse(Routine.asked("what do i usually ask next"))
        // Asking about it is never counted as a question about the levels.
        assertTrue(SelfDoubt.count(emptyMap(), today, "what do i ask after the levels").isEmpty())
    }

    @Test fun saidPlainlyAndInTheLedger() {
        val r = learned.single()
        val said = NextAsk.say(learned)
        assertTrue(said.contains("after Nifty's levels you asked about BankNifty's levels next 4 of the 4 times"), said)
        assertTrue(said.contains(NextAsk.UNDO)); assertTrue(said.contains("nothing I learn acts"))
        assertTrue(NextAsk.say(emptyList()).contains("haven't learned"))
        assertTrue(NextAsk.sayReset(learned).startsWith("Done, Boss"))
        assertFalse(NextAsk.RESET_LOCKED.contains("BankNifty"))
        val i = Learnings.Inputs(routineLog = log(4, "NIFTY|levels", "BANKNIFTY|levels"))
        val item = Learnings.items(i, now).single { it.area == Learnings.Area.NEXT_ASK }
        assertEquals(NextAsk.UNDO, item.undo); assertTrue(item.personal)
        assertEquals("BankNifty's levels offered next after Nifty's levels, in one short question", item.what)
        // "Undo everything you learned this week" resets it.
        val u = Learnings.undo(i, now)
        assertEquals(listOf(r), u.nextAsk); assertFalse(u.empty)
        assertTrue(Learnings.done(u).contains("the question I offer next after an answer (BankNifty's levels after Nifty's levels) - no longer offered"))
        // A locked phone hears none of it.
        val locked = Learnings.say(Learnings.items(i, now), Learnings.Ask.ALL, today, locked = true)
        assertFalse(locked.contains("BankNifty's levels"))
    }
}
