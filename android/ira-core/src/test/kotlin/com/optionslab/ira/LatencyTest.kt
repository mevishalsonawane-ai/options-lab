package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNull

class LatencyTest {
    @Test fun summary() {
        var l = emptyList<Long>()
        for (ms in listOf(1200L, 3400L, 800L, 0L, 90_000L)) l = Latency.add(l, ms)
        assertEquals(listOf(1200L, 3400L, 800L), l)
        assertEquals("Spoken answers: 3, typical wait 1.2 s, slowest 3.4 s, last 0.8 s.", Latency.say(l))
        assertNull(Latency.say(emptyList()))
    }
}

class SlowSuggestTest {
    @Test fun onlyAfterThreeSlowAnswersAndNotOnTheFastest() {
        assertNull(Latency.suggestFaster(listOf(6000L, 7000L), onFastest = false))
        assertNull(Latency.suggestFaster(listOf(6000L, 4000L, 7000L), onFastest = false))
        assertNull(Latency.suggestFaster(listOf(6000L, 6500L, 7000L), onFastest = true))
        kotlin.test.assertTrue(Latency.suggestFaster(listOf(1000L, 6000L, 6500L, 7000L), onFastest = false)!!.contains("Qwen2.5 0.5B"))
    }

    @Test fun askedAloud() {
        assertTrue(Latency.asked("Jarvis, how fast are you?"))
        assertTrue(Latency.asked("how long do you take to answer"))
        assertTrue(Latency.asked("your response time"))
        assertTrue(Latency.asked("jawab mein kitna time lagta hai"))
        assertTrue(Latency.asked("jawab mein kitna time"))
        assertTrue(!Latency.asked("kitna time lagta hai"))
        assertTrue(Reminder.heardAsked("hey jarvis what did you hear"))
        assertTrue(Reminder.heardAsked("Ok Jarvis, what did you hear?"))
        assertTrue(Latency.spoken(listOf(7_000, 8_000, 9_000), "FAST", onFastest = false).endsWith("Your choice, Boss."))
        assertTrue(!Latency.spoken(listOf(7_000, 8_000, 9_000), "FASTEST", onFastest = true).contains("quicker"))
        assertEquals("Over my last spoken answer, Boss, you waited about 1.2 seconds, 1.2 at the slowest. I'm on FAST.", Latency.spoken(listOf(1_200), "FAST"))
        assertTrue(!Latency.asked("how fast is nifty moving"))
        assertEquals("I haven't timed a spoken answer yet, Boss. Ask me something by voice first.", Latency.spoken(emptyList(), "FASTEST"))
        assertEquals("Over my last 3 spoken answers, Boss, you waited about 2.0 seconds, 6.5 at the slowest. I'm on FASTEST.",
            Latency.spoken(listOf(1_200, 6_500, 2_000), "FASTEST"))
    }
}
