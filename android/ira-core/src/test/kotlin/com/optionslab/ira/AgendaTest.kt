package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgendaTest {
    private val day = LocalDate.of(2026, 10, 6)

    private fun lossGoal(near: Boolean) = Goals.Status(Goals.Goal(Goals.Kind.MAX_LOSS, Goals.Period.WEEK, 5000.0), "Loss limit this week: -Rs 4,000 against -Rs 5,000 - close (80% used).",
        broken = false, near = near, met = false)

    @Test fun theDayIsPlannedFromWhatHeKnows() {
        val items = Agenda.build(Agenda.Facts(day, tradingDay = true,
            events = listOf(Events.Event(day, "RBI policy", owner = true), Events.Event(day, "NIFTY expiry"), Events.Event(day.plusDays(1), "US Fed decision overnight (FOMC)")),
            openPositions = 2, goals = listOf(lossGoal(near = true)),
            notes = listOf("skip expiry days", "no new trades after 2 pm", "I like tea"),
            badHours = listOf(10), paperTests = listOf("ORB 5"),
            teach = listOf(Agenda.Teach(listOf("zorbo flim"), "what is the trend on nifty"))))
        val ids = items.map { it.id }
        assertTrue("event:rbi policy" in ids, ids.toString())
        assertFalse(ids.any { it.contains("fomc") }, "only today's events")
        assertTrue("expiry" in ids && "rule:expiry" in ids && "rule:after" in ids)
        assertTrue("goal:720" in ids && "goal:870" in ids, "a goal close is checked twice")
        assertTrue("positions" in ids && "weak:10" in ids && "paper" in ids)
        assertTrue(ids.any { it.startsWith("teach:") })
        // In the order of their time; every one only speaks, studies or works on paper.
        assertEquals(items.sortedBy { it.at }, items)
        assertTrue(items.size <= Agenda.MAX_ITEMS)
        assertTrue(items.all { it.kind.means in Agenda.Means.entries })
        assertEquals(14 * 60, items.first { it.id == "rule:after" }.at)
        // The teach question is put at the midday lull, never at the open.
        assertEquals(12 * 60 + 30, items.first { it.kind == Agenda.Kind.TEACH }.at)
    }

    @Test fun mostImportantKeptWhenTooMuch() {
        val items = Agenda.build(Agenda.Facts(day, tradingDay = true, goals = listOf(lossGoal(near = true)),
            events = (1..12).map { Events.Event(day, "Event $it") },
            teach = listOf(Agenda.Teach(listOf("abc xyz"), null))))
        assertEquals(Agenda.MAX_ITEMS, items.size)
        assertEquals(2, items.count { it.kind == Agenda.Kind.GOAL }, "goals first")
        assertTrue(items.none { it.kind == Agenda.Kind.TEACH }, "study last")
    }

    @Test fun dayOffHasOnlyStudy() {
        val y = listOf(Agenda.Item("teach:abc", Agenda.Kind.TEACH, 750, "ask you what \"abc\" means", words = listOf("abc")),
            Agenda.Item("teach:done", Agenda.Kind.TEACH, 750, "x", done = "asked", words = listOf("done")),
            Agenda.Item("teach:old", Agenda.Kind.TEACH, 750, "x", carried = Agenda.MAX_CARRY, words = listOf("old")),
            Agenda.Item("goal:720", Agenda.Kind.GOAL, 720, "check your goals"))
        val items = Agenda.build(Agenda.Facts(day, tradingDay = false, events = listOf(Events.Event(day, "RBI policy")), openPositions = 3, yesterday = y))
        assertEquals(listOf("teach:abc"), items.map { it.id })
        assertEquals(1, items.single().carried)
        assertEquals(10 * 60, items.single().at)
    }

    @Test fun workedThroughDuringTheDay() {
        var items = Agenda.build(Agenda.Facts(day, true, events = listOf(Events.Event(day, "RBI policy")), goals = listOf(lossGoal(true)), badHours = listOf(11)))
        assertEquals(listOf("event:rbi policy"), Agenda.due(items, 9 * 60 + 12).map { it.id })
        val ev = items.first { it.kind == Agenda.Kind.EVENT }
        assertTrue(Agenda.line(ev)!!.contains("RBI policy"))
        items = Agenda.done(items, ev.id, Agenda.summary(ev))
        assertTrue(Agenda.due(items, 9 * 60 + 12).isEmpty())
        assertTrue(Agenda.line(items.first { it.id == "weak:11" })!!.startsWith("From now to 12:00"))
        val said = Agenda.say(items, 11 * 60 + 30)
        assertTrue(said.contains("Done: reminded you of RBI policy"), said)
        assertTrue(said.contains("Working on now: leave 11:00-12:00 alone"), said)
        assertTrue(said.contains("Next: 12:00 check your goals"), said)
        assertTrue(said.contains("nothing real is placed, changed or closed"))
        val goal = items.first { it.id == "goal:720" }
        assertTrue(Agenda.goalLine(goal, listOf(lossGoal(true))).contains("go easy"))
        assertTrue(Agenda.positionsLine(Agenda.Item("expiry", Agenda.Kind.EXPIRY, 880, "", words = listOf("NIFTY")), 2).contains("Nothing is closed by me"))
    }

    @Test fun aWordForItsMomentIsNotSaidHoursLate() {
        val ev = Agenda.Item("event:x", Agenda.Kind.EVENT, 550, "remind you of X")
        assertFalse(Agenda.late(ev, 600))
        assertTrue(Agenda.late(ev, 700))
        assertFalse(Agenda.late(ev.copy(kind = Agenda.Kind.GOAL), 700), "a goal check is still worth doing")
        val items = Agenda.done(listOf(ev), "event:x", Agenda.MISSED)
        assertTrue(Agenda.say(items, 700).contains("Missed its time"))
        assertTrue(Agenda.wrap(items)!!.contains("0 of 1 done") && Agenda.wrap(items)!!.contains("Not reached: remind you of X"))
    }

    @Test fun wrapUpSaysWhatWasDoneAndCarried() {
        val items = listOf(
            Agenda.Item("event:x", Agenda.Kind.EVENT, 550, "remind you", done = "reminded you of X"),
            Agenda.Item("paper", Agenda.Kind.PAPER_TEST, 920, "read how ORB 5 is doing on paper"),
            Agenda.Item("teach:abc", Agenda.Kind.TEACH, 750, "ask you what \"abc\" means", words = listOf("abc")))
        val w = Agenda.wrap(items)!!
        assertTrue(w.startsWith("My own plan today: 1 of 3 done (reminded you of X)."), w)
        assertTrue(w.contains("Carrying to tomorrow: ask you what \"abc\" means."), w)
        assertTrue(w.contains("Not reached: read how ORB 5"), w)
        assertNull(Agenda.wrap(emptyList()))
    }

    @Test fun askedAboutThePlan() {
        listOf("what's your plan today?", "Jarvis, what are you working on?", "what is on your agenda today", "what's your plan", "aaj tumhara plan kya hai")
            .forEach {
                assertTrue(Agenda.asked(it), it)
                // Nothing before it in the chat takes these words: no command, no reminder, no follow-up, no wrap-up.
                assertNull(Ask.parse(it).command, it); assertNull(Commands.parse(it), it)
                assertFalse(Reminder.asked(it) || Reminder.tomorrow(it) || Reminder.missedAsked(it) || Reminder.usageAsked(it) || DaySummary.asked(it), it)
                assertNull(FollowUp.resolve("how is nifty", it), it)
            }
        listOf("what's the plan for tomorrow", "what is the plan for tomorrow", "start all strategies", "what is the trend on nifty")
            .forEach { assertFalse(Agenda.asked(it), it) }
    }

    @Test fun studyGroupsAndOnlyEverQuestions() {
        val t = Agenda.study(missed = listOf("zorbo flim", "zorbo flim batao", "stop all strategies please", "my pin is 4321"),
            wrong = listOf("blorp zing"), learned = emptyList(),
            guess = { w -> if (w.contains("zorbo")) "what is the trend on nifty" else "cancel all orders" })
        // The misheard wordings are one group, its guess a question; an order or a secret is never studied.
        val trend = t.first()
        assertEquals(2, trend.words.size)
        assertEquals("what is the trend on nifty", trend.guess)
        assertTrue(t.none { g -> g.words.any { it.contains("stop") || it.contains("4321") || it.contains("pin") } }, t.toString())
        // A guess that would act is never offered: asked another way instead.
        val other = Agenda.study(listOf("blorp zing"), emptyList(), emptyList(), guess = { "cancel all orders" })
        assertNull(other.single().guess)
        // Already learned: not studied again.
        assertTrue(Agenda.study(listOf("zorbo flim"), emptyList(), listOf(Corrections.Learned("zorbo flim", "what is the trend on nifty"))).isEmpty())
    }

    @Test fun lessonsOnlyOnAQuestion() {
        val i = Agenda.Item("teach:zorbo flim", Agenda.Kind.TEACH, 750, "", words = listOf("zorbo flim"), guess = "what is the trend on nifty")
        assertTrue(Agenda.ask(i).contains("Did you mean \"what is the trend on nifty\"?"))
        assertEquals(listOf(Corrections.Learned("zorbo flim", "what is the trend on nifty")), Agenda.lessons(i))
        assertTrue(Agenda.lessons(i.copy(guess = "stop all strategies")).isEmpty(), "nothing learned may act")
        assertTrue(Agenda.lessons(i.copy(guess = null)).isEmpty())
        assertTrue(Agenda.ask(i.copy(guess = null)).contains("another way"))
        assertEquals("learned \"zorbo flim\"", Agenda.summary(i, learned = true))
    }
}
