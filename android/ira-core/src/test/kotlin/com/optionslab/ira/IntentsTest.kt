package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IntentsTest {
    @Test fun onlyALineOfTheListIsTaken() {
        assertEquals("stop strategy 2", Intents.pick("Stop strategy 2."))
        assertEquals("set an alarm on banknifty below 51000", Intents.pick("set an alarm on banknifty below 51000\nextra"))
        assertEquals("nifty pcr and max pain", Intents.pick("\"Nifty PCR and max pain\""))
        assertNull(Intents.pick("NONE"))
        assertNull(Intents.pick("buy 1 lot nifty atm ce"))                 // never an order
        assertNull(Intents.pick("switch to live mode"))                    // never Live
        assertNull(Intents.pick("stop strategy two please"))
        assertNull(Intents.pick("set an alarm on reliance above 2500"))
        assertTrue(Intents.prompt("halt the second bot").contains("REQUEST: halt the second bot"))
    }

    @Test fun everyLineIsUnderstoodByTheParser() {
        for (l in Intents.LINES) {
            val filled = l.replace("<n>", "2").replace("<market>", "nifty").replace("<level>", "25000")
            assertEquals(filled.lowercase(), Intents.pick(filled))
            val q = Ask.parse(filled)
            assertTrue(Topic.OFF_TOPIC !in q.topics && Topic.ORDER !in q.topics, "$filled -> ${q.topics}")
        }
    }
}
