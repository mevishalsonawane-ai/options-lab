package com.optionslab.ira

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LaterTest {
    private val now = LocalDateTime.of(2026, 10, 3, 19, 50)   // a Saturday evening

    @Test fun aCommandForLaterIsReadOffItsTime() {
        val w = Later.split("start all the arms by tomorrow morning 9am", now)!!
        assertEquals(LocalDateTime.of(2026, 10, 4, 9, 0), w.at)
        assertEquals("start all the arms", w.rest)
        assertEquals(Command.Kind.START_ALL, Ask.parse(w.rest).command?.kind)
        assertEquals("tomorrow (Sun 4 Oct) at 09:00", Later.say(w.at, now))

        assertEquals(LocalDateTime.of(2026, 10, 4, 15, 15), Later.split("stop strategy 2 tomorrow at 3:15 pm", now)!!.at)
        assertEquals("stop strategy 2", Later.split("stop strategy 2 tomorrow at 3:15 pm", now)!!.rest)
        assertEquals(LocalDateTime.of(2026, 10, 3, 20, 20), Later.split("in 30 minutes stop all strategies", now)!!.at)
        // No day and the time has passed today: tomorrow. "at 2" is 2 pm in market terms.
        assertEquals(LocalDateTime.of(2026, 10, 4, 9, 20), Later.split("start all arms at 9:20", now)!!.at)
        assertEquals(LocalDateTime.of(2026, 10, 4, 14, 0), Later.split("stop all strategies at 2", now)!!.at)
        assertEquals(LocalDateTime.of(2026, 10, 4, 9, 15), Later.split("start all arms tomorrow morning", now)!!.at)
    }

    @Test fun noTimeNoLater() {
        assertNull(Later.split("start all the arms", now))
        assertNull(Later.split("stop strategy 2", now))                  // a bare number is not a time
        assertNull(Later.split("start all arms tomorrow", now))          // a day with no time
        assertNull(Later.split("start all arms today at 9am", now))      // already past
        assertNull(Later.split("start all arms in 900 hours", now))      // too far ahead
        assertNull(Later.split("start all arms at 13pm", now))
    }

    @Test fun onlySafeCommandsMayWait() {
        assertTrue(Command.Kind.START_ALL in Later.ALLOWED && Command.Kind.STOP_ONE in Later.ALLOWED)
        for (k in listOf(Command.Kind.MODE_LIVE, Command.Kind.KILL_OFF, Command.Kind.AUTOPILOT_ON, Command.Kind.JTRADES_LIVE, Command.Kind.CLOSE_ALL))
            assertTrue(k !in Later.ALLOWED, k.name)
    }
}

class DailyStopLossTest {
    @Test fun theDailyStopLossIsTheDailyLossLimit() {
        for (q in listOf("whats my daily stop loss", "what is my daily SL", "stop loss for the day?", "what's my max loss per day")) {
            val p = Ask.parse(q)
            assertNull(p.command, "$q is a question, not a command: ${p.command}")
            assertNull(p.order, q)
            val s = AppAnswers.sections(q)
            assertTrue(Section.RISK in s && Section.PROTECTIONS !in s && Section.PNL !in s, "$q -> $s")
        }
        // The stops on positions are still the stops.
        assertTrue(Section.PROTECTIONS in AppAnswers.sections("what are my stop losses on open positions"))
        // A change in those words sets the daily loss limit.
        val c = Ask.parse("set my daily stop loss to 5000").command
        assertEquals(Command.Kind.SET_LIMIT, c?.kind, "$c")
        assertEquals(SettingsTalk.Key.DAILY_LOSS.name, c?.target)
    }
}
