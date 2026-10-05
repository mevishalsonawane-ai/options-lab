package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WrongThingTest {
    private val today = LocalDate.of(2026, 10, 5)
    private val now: LocalDateTime = today.atTime(15, 0)
    private val t0: LocalDateTime = today.atTime(10, 0)
    private val cancelled = "why was my last order canceled"

    /** [said] in order, each [gapS] seconds after the one before, from [start]. */
    private fun run(said: List<String>, gapS: Long, start: LocalDateTime = t0, log: WrongThing.Log = WrongThing.Log()): WrongThing.Step =
        said.foldIndexed(WrongThing.Step(log, null, null)) { i, s, q -> WrongThing.heard(s.log, s.last, q, start.plusSeconds(gapS * i)) }

    @Test fun aQuestionIsItsKindAndTheWayItWasTaken() {
        assertEquals("ORDER_WHY", WrongThing.kind(cancelled))
        assertEquals("account:ORDERS", WrongThing.took(cancelled))
        assertEquals("APP", WrongThing.kind("open youtube"))
        assertEquals("missed", WrongThing.took("open youtube"))
        assertEquals("words I could not place", WrongThing.tookPhrase("missed"))
        assertEquals("your account (Orders)", WrongThing.tookPhrase("account:ORDERS"))
        assertEquals("the app's facts (Option chain)", WrongThing.tookPhrase("account:CHAIN"))
        // Open positions and open interest are not opening an app.
        assertFalse(WrongThing.kind("show my open positions") == "APP")
        assertFalse(WrongThing.kind("open interest on nifty") == "APP")
        // A command or an order is never taken as a question here.
        assertNull(WrongThing.took("stop all strategies"))
        assertNull(WrongThing.took("buy 1 lot nifty 25000 ce"))
    }

    @Test fun theSameQuestionAgainWithinTwoMinutesIsNoted() {
        val s = run(listOf(cancelled, "why did my last order get cancelled"), 40)
        val e = assertNotNull(s.noted)
        assertEquals("ORDER_WHY", e.kind)
        assertEquals("account:ORDERS", e.took)
        assertEquals(WrongThing.Why.REPEAT, e.why)
        assertEquals(1, s.log.events.size)
        // Once a question: a third ask adds nothing more.
        assertEquals(1, run(listOf(cancelled, cancelled, cancelled), 30).log.events.size)
        // "Open youtube", not placed, asked again.
        assertEquals("APP", assertNotNull(run(listOf("open youtube", "open youtube app"), 20).noted).kind)
    }

    @Test fun aFreshQuestionLaterElsewhereOrWantedFreshIsNot() {
        assertNull(run(listOf(cancelled, cancelled), WrongThing.MAX_GAP_S + 1).noted)
        // The same words heard twice in a breath.
        assertNull(run(listOf(cancelled, cancelled), 3).noted)
        // Another question between closes it.
        assertNull(run(listOf(cancelled, "show my positions", cancelled), 30).noted)
        // A command between leaves it open.
        assertNotNull(run(listOf(cancelled, "stop all strategies", cancelled), 30).noted)
        // A P&L or a price asked again is wanting it fresh; a market read asked again is AskedAgain's.
        assertNull(run(listOf("what is my pnl", "what is my pnl"), 60).noted)
        assertNull(run(listOf("how is nifty", "how is nifty"), 60).noted)
        assertNull(run(listOf("what are the levels on nifty", "what are the levels on nifty"), 60).noted)
        // Asking what he got wrong twice is not a miss.
        assertNull(run(listOf("what did you get wrong today", "what did you get wrong today"), 30).noted)
    }

    @Test fun bossSayingItWasWrongIsNotedOnce() {
        assertTrue(WrongThing.objected("galat jawab"))
        assertTrue(WrongThing.objected("Jarvis, ye nahi poocha"))
        assertTrue(WrongThing.objected("that's not what I meant"))
        assertTrue(WrongThing.objected("you answered the wrong thing"))
        assertFalse(WrongThing.objected("why was my order wrong"))
        assertFalse(WrongThing.objected("what is my p&l"))
        val asked = run(listOf("open youtube"), 0)
        val s = WrongThing.said(asked.log, asked.last, t0.plusSeconds(15))
        assertEquals(WrongThing.Why.SAID, assertNotNull(s.noted).why)
        // Said twice, or after the repeat was noted: once.
        assertNull(WrongThing.said(s.log, s.last, t0.plusSeconds(20)).noted)
        // Too long after the question: not about it.
        assertNull(WrongThing.said(asked.log, asked.last, t0.plusSeconds(WrongThing.SAID_MS / 1000 + 1)).noted)
        // The objection itself is never a question: the question stays last.
        val after = WrongThing.heard(asked.log, asked.last, "galat jawab", t0.plusSeconds(10))
        assertEquals(asked.last, after.last)
    }

    @Test fun noWordsAreKept() {
        val s = run(listOf(cancelled, cancelled), 30)
        val e = assertNotNull(s.noted)
        assertFalse(e.toString().contains("canceled"))
        assertFalse(s.log.toString().contains("why was"))
    }

    @Test fun theRecordAndTheLedger() {
        var log = WrongThing.Log()
        for (d in 0..2L) log = run(listOf(cancelled, cancelled), 30, start = now.minusDays(d).minusHours(1), log = log).log
        val single = run(listOf("open youtube", "open youtube"), 20, start = now.minusHours(2), log = log).log
        val recs = WrongThing.records(single, now)
        assertEquals("ORDER_WHY", recs.first().kind)
        assertEquals(3, recs.first().repeats)
        // Shown at two, or at once when Boss said so; once asked again is chance.
        assertEquals(listOf("ORDER_WHY"), WrongThing.shown(single, now).map { it.kind })
        val items = Learnings.items(Learnings.Inputs(wrong = single), now).filter { it.area == Learnings.Area.WRONG_THING }
        assertEquals(1, items.size)
        assertNull(items[0].undo)
        assertTrue(items[0].what.contains("why an order was cancelled or rejected, answered as your account (Orders)"), items[0].what)
        // A record: left out of "what changed in how you work".
        assertFalse(Learnings.say(items, Learnings.Ask.CHANGED, today, locked = false).contains("cancelled"))
        // Older than the window: gone.
        assertTrue(WrongThing.records(single, now.plusDays(WrongThing.WINDOW_DAYS + 1)).isEmpty())
    }

    @Test fun askedAndSaid() {
        assertEquals(WrongThing.Request.TODAY, WrongThing.asked("What did you get wrong today?"))
        assertEquals(WrongThing.Request.ALL, WrongThing.asked("which questions did you answer with the wrong thing"))
        assertEquals(WrongThing.Request.ALL, WrongThing.asked("what did you get wrong this week"))
        assertEquals(WrongThing.Request.TODAY, WrongThing.asked("aaj kya galat jawab diya"))
        assertEquals(WrongThing.Request.ALL, WrongThing.asked("where did you misunderstand me"))
        // Plain "what did you get wrong?" stays the account's marked mistakes.
        assertNull(WrongThing.asked("what did you get wrong"))
        assertNull(WrongThing.asked("what did I get wrong today"))

        assertTrue(WrongThing.say(WrongThing.Log(), WrongThing.Request.TODAY, now).startsWith("Nothing I noticed today, Boss"))
        val log = run(listOf(cancelled, cancelled), 30).log
        val s = WrongThing.say(WrongThing.said(log, null, now).log, WrongThing.Request.TODAY, now)
        assertTrue(s.startsWith("Boss, one answer missed what you asked today: why an order was cancelled or rejected, answered as your account (Orders) (you asked again once)"), s)
        assertTrue(s.contains("nothing I learn re-routes a question or acts by itself"), s)
        assertFalse(s.contains("canceled"))
        // Yesterday's are not today's.
        assertTrue(WrongThing.say(log, WrongThing.Request.TODAY, now.plusDays(1)).startsWith("Nothing I noticed today"))
        assertTrue(WrongThing.say(log, WrongThing.Request.ALL, now.plusDays(1)).startsWith("Boss, one answer missed what you asked in the last 30 days"))
    }
}
