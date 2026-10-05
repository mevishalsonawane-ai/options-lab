package com.optionslab.ira

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReminderTest {
    private val now = LocalDateTime.of(2026, 10, 5, 10, 0)

    @Test fun reminders() {
        val r = assertNotNull(Reminder.parse("remind me at 3 pm to check nifty", now))
        assertEquals("check nifty", r.rest)
        assertEquals(LocalDateTime.of(2026, 10, 5, 15, 0), r.at)
        assertEquals("call the broker", Reminder.parse("Jarvis, remind me in 20 minutes to call the broker", now)!!.rest)
        assertEquals(LocalDateTime.of(2026, 10, 6, 9, 0), Reminder.parse("set a reminder for tomorrow at 9 am to log in", now)!!.at)
        // No time: not set (asked again); not a reminder at all: null.
        assertNull(Reminder.parse("remind me to check nifty", now))
        assertTrue(Reminder.asked("remind me to check nifty"))
        assertNull(Reminder.parse("how is nifty at 3 pm", now))
    }

    @Test fun clockAndTomorrow() {
        assertEquals("It's 10:00 AM, Boss.", Reminder.clock("what time is it?", now))
        assertEquals("Today is Monday, 5 October 2026, Boss.", Reminder.clock("Jarvis, what's the date", now))
        assertNull(Reminder.clock("what time does the market open", now))
        assertTrue(Reminder.tomorrow("what's the plan for tomorrow"))
        assertTrue(Reminder.tomorrow("how does tomorrow look like"))
        assertTrue(!Reminder.tomorrow("stop all strategies tomorrow at 9"))
    }
}

class HinglishMoreTest {
    private val now = LocalDateTime.of(2026, 10, 5, 10, 0)

    @Test fun moneyWithoutMyIsTheAccount() {
        for (s in listOf("how much did I lose today", "what did i lose on today", "aaj kitna kamaya", "kitne trade kiye aaj", "how many trades today"))
            assertTrue(Topic.ACCOUNT in Ask.parse(s).topics, s)
        // The market still is the market.
        assertTrue(Topic.ACCOUNT !in Ask.parse("how is nifty doing today").topics)
    }

    @Test fun hindiReminderClockPlanHush() {
        val r = assertNotNull(Reminder.parse("mujhe 3 baje nifty check karna yaad dilana", now))
        assertEquals(LocalDateTime.of(2026, 10, 5, 15, 0), r.at)
        assertEquals("nifty check karna", r.rest)
        assertTrue(Reminder.asked("mujhe kal 9 baje login karna yaad dilana"))
        assertEquals(LocalDateTime.of(2026, 10, 6, 9, 0), Reminder.parse("mujhe kal 9 baje login karna yaad dilana", now)!!.at)
        assertNotNull(Reminder.clock("time kya hua", now))
        assertTrue(Reminder.tomorrow("kal ka plan kya hai"))
        assertTrue(Wake.hush("jarvis chup ho jao"))
        assertEquals("should i trade now", Hinglish.normalize("kya karna chahiye"))
    }
}

class HindiTimesReviewTest {
    private val now = LocalDateTime.of(2026, 10, 5, 10, 0)

    @Test fun hindiTimesOfDay() {
        val r = assertNotNull(Reminder.parse("mujhe shaam 9 baje nifty dekhna yaad dilana", now))
        assertEquals(LocalDateTime.of(2026, 10, 5, 21, 0), r.at)
        assertEquals("nifty dekhna", r.rest)
        assertEquals(LocalDateTime.of(2026, 10, 6, 9, 0), Reminder.parse("mujhe kal subah 9 baje login yaad dilana", now)!!.at)
        assertEquals(LocalDateTime.of(2026, 10, 5, 10, 20), Reminder.parse("mujhe 20 minute mein chai yaad dilana", now)!!.at)
        assertTrue(!Reminder.asked("kal kya hua tha yaad dilao"))
    }

    @Test fun narrowerAccount() {
        assertTrue(Topic.ACCOUNT in Ask.parse("how many trades did i take today").topics)
        assertTrue(Topic.ACCOUNT in Ask.parse("how much did i make today").topics)
    }
}

class ReminderCancelTest {
    @Test fun cancel() {
        for (s in listOf("cancel my reminders", "Jarvis, delete the reminder", "reminder hata do", "clear all reminders"))
            assertTrue(Reminder.cancelAsked(s), s)
        assertTrue(!Reminder.cancelAsked("remind me at 3 pm to cancel my order"))
        assertTrue(!Reminder.cancelAsked("cancel all orders"))
    }
}

class ModelAskedTest {
    @Test fun model() {
        for (s in listOf("which model are you using", "Jarvis, which AI model is loaded", "kaunsa model hai")) assertTrue(Reminder.modelAsked(s), s)
        assertTrue(!Reminder.modelAsked("what is the black scholes model"))
    }
}

class ModelAskedNarrowTest {
    @Test fun narrow() {
        assertTrue(Reminder.modelAsked("what's your model?"))
        assertTrue(!Reminder.modelAsked("what does your model say about nifty"))
    }
}

class MissedSinceTest {
    @Test fun since() {
        fun b(t: String) = Reminder.Said(true, false, t)
        fun r(t: String) = Reminder.Said(false, false, t)
        fun u(t: String) = Reminder.Said(false, true, t)
        val ms = listOf(b("how is nifty"), r("Reading the option chain."), u("Relay down."), r("Nifty ATM is 25000."), u("BankNifty broke its range."))
        assertEquals(listOf("Relay down.", "BankNifty broke its range."), Reminder.sinceLastAsked(ms))
        assertEquals(listOf("Morning check."), Reminder.sinceLastAsked(listOf(u("Morning check."))))
        assertTrue(Reminder.missedAsked("Jarvis, what did I miss?"))
        assertTrue(Reminder.missedAsked("maine kya miss kiya"))
        assertTrue(!Reminder.missedAsked("what did i miss on the nifty chart"))
    }
}

class SelfCheckAskedTest {
    @Test fun asked() {
        for (s in listOf("run a self check", "Jarvis, system check", "check yourself", "sab theek hai?")) assertTrue(SelfCheck.asked(s), s)
        assertTrue(!SelfCheck.asked("check the nifty chart"))
    }
}

class DailyReminderTest {
    private val now = LocalDateTime.of(2026, 10, 5, 10, 0)

    @Test fun daily() {
        assertTrue(Reminder.daily("remind me every day at 9:20 to check the gap"))
        val r = assertNotNull(Reminder.parse("remind me every day at 9:20 to check the gap", now))
        assertEquals("check the gap", r.rest)
        assertEquals(LocalDateTime.of(2026, 10, 6, 9, 20), r.at)          // 9:20 has passed today: tomorrow first
        assertTrue(Reminder.daily("mujhe roz 9 baje login karna yaad dilana"))
        assertEquals("login karna", Reminder.parse("mujhe roz 9 baje login karna yaad dilana", now)!!.rest)
        assertTrue(!Reminder.daily("remind me at 3 pm to check nifty"))
    }
}

class UsageAskedTest {
    @Test fun usage() {
        for (s in listOf("how did you do today", "Jarvis, your report card", "how many questions did I ask today")) assertTrue(Reminder.usageAsked(s), s)
        assertTrue(!Reminder.usageAsked("how did nifty do today"))
    }
}

class DailyWordInTextTest {
    @Test fun oneOffWithDailyInIt() {
        val now = LocalDateTime.of(2026, 10, 5, 10, 0)
        assertTrue(!Reminder.daily("remind me at 3 pm to check the daily pnl"))
        assertEquals("check the daily pnl", Reminder.parse("remind me at 3 pm to check the daily pnl", now)!!.rest)
        assertTrue(Reminder.daily("remind me daily at 9:15 to log in"))
    }
}

class HeardAskedTest {
    @Test fun heard() {
        for (s in listOf("what did you hear", "Jarvis, what did I just say?", "tumne kya suna")) assertTrue(Reminder.heardAsked(s), s)
        assertTrue(!Reminder.heardAsked("what did you hear about nifty"))
    }
}
