package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DistanceTest {
    @Test fun distance() {
        assertEquals(Distance.Asked(Market.NIFTY, 25000.0), Distance.asked("how far is nifty from 25000"))
        assertEquals(Distance.Asked(Market.BANKNIFTY, 52000.0), Distance.asked("banknifty 52,000 se kitna door hai"))
        assertNull(Distance.asked("how far is nifty 25000 ce"))
        assertEquals("Nifty is at 24,770.00: 230.00 points (0.93%) below 25,000.00, Boss.", Distance.say(Distance.Asked(Market.NIFTY, 25000.0), 24770.0))
    }
}
