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
