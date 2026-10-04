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
