package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AboutBossTest {
    private val friday = LocalDate.of(2026, 10, 9)
    private val monday = LocalDate.of(2026, 10, 5)

    @Test fun factsAboutBossAreHeard() {
        assertEquals("I don't trade on Fridays", AboutBoss.fact("Jarvis, I don't trade on Fridays."))
        assertEquals("I get greedy after a win", AboutBoss.fact("remind me I get greedy after a win"))
        assertEquals("I get greedy after a win", AboutBoss.fact("Remind me that I get greedy after a win"))
        assertEquals("I tend to overtrade after a loss", AboutBoss.fact("I tend to overtrade after a loss"))
        assertEquals("I never hold overnight", AboutBoss.fact("I never hold overnight"))
        assertEquals("I'm impatient in the first hour", AboutBoss.fact("I'm impatient in the first hour"))
        assertEquals("My weakness is chasing breakouts", AboutBoss.fact("my weakness is chasing breakouts"))
        assertEquals("My max loss is 3000 a day", AboutBoss.fact("note that my max loss is 3000 a day"))
        assertEquals("I work till 5 on weekdays", AboutBoss.fact("just so you know, I work till 5 on weekdays"))
    }

    @Test fun notFacts() {
        assertNull(AboutBoss.fact("remind me at 3 pm to check Nifty"), "a reminder with a time stays a reminder")
        assertNull(AboutBoss.fact("remind me in 20 minutes that I get greedy"))
        assertNull(AboutBoss.fact("I don't understand options"), "not about how he trades")
        assertNull(AboutBoss.fact("I think nifty will go up"))
        assertNull(AboutBoss.fact("I want to buy nifty"))
        assertNull(AboutBoss.fact("buy 1 lot of banknifty"))
        assertNull(AboutBoss.fact("stop all strategies"))
        assertNull(AboutBoss.fact("do I trade on Fridays?"))
        assertNull(AboutBoss.fact("note that my pin is 1234"), "nothing secret")
        assertNull(AboutBoss.fact("remind me my password is hunter2"))
        assertNull(AboutBoss.fact("how is nifty"))
        assertNull(AboutBoss.fact("hello jarvis"))
    }

    @Test fun factsAreNotTakenByOtherWords() {
        for (q in listOf("I don't trade on Fridays", "I get greedy after a win", "I tend to overtrade after a loss", "I never hold overnight")) {
            val p = Ask.parse(q)
            assertNull(p.order, q); assertNull(p.command, q); assertNull(Commands.parse(q), q)
            assertNull(Chat.smallTalk(q, 0), q)
            assertNull(Goals.read(q), q)
            assertFalse(Reminder.asked(q), q)
        }
        for (q in listOf("what do you know about me", "forget that", "forget that I don't trade on Fridays", "forget my target")) {
            val p = Ask.parse(q)
            assertNull(p.order, q); assertNull(p.command, q); assertNull(Chat.smallTalk(q, 0), q)
            assertFalse(Memory.recallAsked(q) || Memory.forgetAsked(q), q)
        }
        assertNull(AboutBoss.forgetAsked("forget my preferences"))
    }

    @Test fun kinds() {
        assertEquals(AboutBoss.Kind.DAYS, AboutBoss.kind("I don't trade on Fridays"))
        assertEquals(AboutBoss.Kind.DAYS, AboutBoss.kind("I skip expiry days"))
        assertEquals(AboutBoss.Kind.TEMPER, AboutBoss.kind("I get greedy after a win"))
        assertEquals(AboutBoss.Kind.TEMPER, AboutBoss.kind("I tend to overtrade after a loss"))
        assertEquals(AboutBoss.Kind.TARGET, AboutBoss.kind("my target is 2000 a day"))
        assertEquals(AboutBoss.Kind.LOSS, AboutBoss.kind("My max loss is 3000 a day"))
        assertEquals(AboutBoss.Kind.STYLE, AboutBoss.kind("I only trade BankNifty"))
        assertEquals(AboutBoss.Kind.OTHER, AboutBoss.kind("I work till 5 on weekdays"))
        assertEquals(2000.0, AboutBoss.amount("my target is 2,000 a day"))
        assertEquals(5000.0, AboutBoss.amount("my target is 5k a day"))
        assertEquals(150000.0, AboutBoss.amount("my capital is 1.5 lakh"))
        assertNull(AboutBoss.amount("I trade 2 lots"))
    }

    private val notes = listOf("I don't trade on Fridays", "I get greedy after a win", "I tend to overtrade after a loss", "my target is 2000 a day",
        "My max loss is 3000 a day", "I'm impatient in the first hour")

    @Test fun recalledWhenItMatters() {
        val fri = AboutBoss.recall(notes, AboutBoss.Moment(friday, AboutBoss.At.MORNING))!!
        assertTrue(fri.contains("it's Friday and you told me \"I don't trade on Fridays\""), fri)
        assertTrue(fri.contains("my target is 2000 a day"), fri)
        assertFalse(fri.contains("greedy"), "not up yet")
        val mon = AboutBoss.recall(notes, AboutBoss.Moment(monday, AboutBoss.At.MORNING))!!
        assertFalse(mon.contains("Fridays"), mon)

        val up = AboutBoss.recall(notes, AboutBoss.Moment(monday, AboutBoss.At.CHECK, dayPnl = 2500.0))!!
        assertTrue(up.contains("you're up today") && up.contains("greedy after a win"), up)
        assertFalse(up.contains("overtrade after a loss"), up)
        assertTrue(up.split("; ").size <= AboutBoss.MAX_SAID)
        val target = AboutBoss.recall(listOf("my target is 2000 a day"), AboutBoss.Moment(monday, AboutBoss.At.CHECK, dayPnl = 2500.0))!!
        assertTrue(target.contains("past your target"), target)
        assertNull(AboutBoss.recall(listOf("my target is 2000 a day"), AboutBoss.Moment(monday, AboutBoss.At.CHECK, dayPnl = 500.0)))

        val down = AboutBoss.recall(notes, AboutBoss.Moment(monday, AboutBoss.At.CHECK, dayPnl = -3500.0))!!
        assertTrue(down.contains("overtrade after a loss") && !down.contains("greedy"), down)
        assertTrue(AboutBoss.recall(listOf("My max loss is 3000 a day"), AboutBoss.Moment(monday, AboutBoss.At.CHECK, dayPnl = -3500.0))!!.contains("stop at"))

        val exp = AboutBoss.recall(listOf("I skip expiry days"), AboutBoss.Moment(monday, AboutBoss.At.MORNING, expiryToday = true))!!
        assertTrue(exp.contains("an expiry day"), exp)
        assertNull(AboutBoss.recall(listOf("I skip expiry days"), AboutBoss.Moment(monday, AboutBoss.At.MORNING)))
        assertNull(AboutBoss.recall(emptyList(), AboutBoss.Moment(friday, AboutBoss.At.CHECK, dayPnl = 1.0)))
        // Words only: nothing in it is an order or a command.
        assertNull(Ask.parse(fri).order); assertNull(Commands.parse(fri))
    }

    @Test fun askedAndForgotten() {
        assertTrue(AboutBoss.knowAsked("Jarvis, what do you know about me?"))
        assertTrue(AboutBoss.knowAsked("what have you learned about me"))
        assertFalse(AboutBoss.knowAsked("what do you know about nifty"))
        assertEquals(AboutBoss.Forget(true), AboutBoss.forgetAsked("forget that"))
        assertEquals(AboutBoss.Forget(true), AboutBoss.forgetAsked("Jarvis, forget the last thing I told you"))
        assertEquals("i dont trade on fridays", AboutBoss.forgetAsked("forget that I don't trade on Fridays")?.about)
        assertEquals("my target", AboutBoss.forgetAsked("forget my target")?.about)
        assertNull(AboutBoss.forgetAsked("forget what I told you"), "all of them: Memory's")
        assertNull(AboutBoss.forgetAsked("forget my goals"))
        assertNull(AboutBoss.forgetAsked("forget what you learned"))
        assertNull(AboutBoss.forgetAsked("forget it"))

        val items = notes.mapIndexed { n, t -> Memory.Item(monday.minusDays(10L - n), t) } + Memory.Item(monday, "I don't trade BankNifty")
        assertEquals("I don't trade BankNifty", AboutBoss.pick(items, AboutBoss.Forget(true))?.text)
        assertEquals("I don't trade on Fridays", AboutBoss.pick(items, AboutBoss.forgetAsked("forget that I don't trade on Fridays")!!)?.text)
        assertEquals("my target is 2000 a day", AboutBoss.pick(items, AboutBoss.forgetAsked("forget my target")!!)?.text)
        assertEquals("I get greedy after a win", AboutBoss.pick(items, AboutBoss.forgetAsked("forget that I get greedy")!!)?.text)
        assertNull(AboutBoss.pick(items, AboutBoss.forgetAsked("forget that I trade on Mondays")!!))
        assertTrue(AboutBoss.forgot(null, AboutBoss.Forget(false, "x y")).contains("don't have anything"))

        val all = AboutBoss.lines(items)
        assertTrue(all.contains("Your days: \"I don't trade on Fridays\""), all)
        assertTrue(all.contains("What to watch in you:"), all)
        assertTrue(all.contains("only shapes what I say"), all)
        assertTrue(AboutBoss.lines(emptyList()).contains("haven't told me"))
        val noted = AboutBoss.noted("I don't trade BankNifty")
        assertTrue(noted.contains("I'll follow it"), noted)
        assertNotNull(noted)
    }

    @Test fun theMorningPlanSaysIt() {
        val items = Agenda.build(Agenda.Facts(friday, true, notes = notes))
        val a = items.first { it.kind == Agenda.Kind.ABOUT }
        assertEquals(9 * 60 + 5, a.at)
        assertTrue(Agenda.line(a)!!.contains("Fridays"))
        assertEquals(Agenda.Means.SPEAK, a.kind.means)
        assertFalse(a.kind.open, "Boss's words: never on a locked phone")
        assertTrue(Agenda.build(Agenda.Facts(friday, false, notes = notes)).none { it.kind == Agenda.Kind.ABOUT })
    }

    @Test fun hisOwnRulesAreWhatHeTold() {
        // Round 9: Boss's own stated rules read back with what he told (words only; nothing in them acts).
        for (q in listOf("what are my rules", "Jarvis, what are my trading rules?", "what rules did i tell you", "remind me of my rules",
                "what rules do i follow", "mere rules kya hain", "mere niyam kya hain")) assertTrue(AboutBoss.knowAsked(q), q)
        for (q in listOf("what are my goals", "am i going against my own rules", "what are the rules of the exchange", "explain the rules"))
            assertFalse(AboutBoss.knowAsked(q), q)
        assertNull(AboutBoss.fact("what are my rules"))
    }
}
