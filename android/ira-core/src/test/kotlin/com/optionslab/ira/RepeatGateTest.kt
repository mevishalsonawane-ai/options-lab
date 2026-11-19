package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 20 Nov 2026 (minor fixes, item 2): a stuck exit's alert once per episode, then a reminder at most every 15 minutes. */
class RepeatGateTest {
    private val min = 60_000L

    @Test fun oncePerEpisodeThenAtMostEveryFifteenMinutes() {
        val g = RepeatGate(15 * min)
        assertTrue(g.tell("ORB|leg|1", 0L), "the first is told")
        for (m in 1L..14L) assertFalse(g.tell("ORB|leg|1", m * min), "minute $m: not again")
        assertTrue(g.tell("ORB|leg|1", 15 * min), "the reminder at 15 minutes")
        assertFalse(g.tell("ORB|leg|1", 16 * min))
        assertTrue(g.tell("ORB|leg|2", 16 * min), "another leg is its own episode")
        assertTrue(g.tell("Iron|leg|1", 16 * min), "another run too")
    }

    @Test fun anEndedRunStartsAFreshEpisodeAndAClockGoneBackTells() {
        val g = RepeatGate(15 * min)
        g.tell("ORB|leg|1", 0L); g.tell("ORB|stop", 0L); g.tell("ORBX|leg|1", 0L)
        g.end("ORB|")
        assertEquals(1, g.size, "only that run's episodes are over")
        assertTrue(g.tell("ORB|leg|1", min), "a new episode is told at once")
        assertFalse(g.tell("ORBX|leg|1", min))
        assertTrue(g.tell("ORBX|leg|1", -min), "a clock set back never hides it")
    }
}
