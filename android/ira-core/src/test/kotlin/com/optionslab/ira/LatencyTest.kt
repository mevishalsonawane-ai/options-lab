package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
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
}
