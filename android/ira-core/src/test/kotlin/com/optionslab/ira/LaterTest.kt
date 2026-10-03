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
