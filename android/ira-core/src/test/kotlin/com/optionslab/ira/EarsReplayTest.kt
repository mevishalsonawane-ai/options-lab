package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Boss's phone, 4 Oct, replayed: the name read mid-turn, then the final answer "no match". */
class EarsReplayTest {
    @Test fun theNameReadMidTurnWakesJarvis() {
        assertEquals("Jarvis", Wake.lostTurn(7, "Jarvis"))                     // error 7 (no match), partial "Jarvis"
        assertEquals("jarvish", Wake.lostTurn(6, "jarvish"))                   // silence after an Indian-English reading
        assertEquals("Jarvis what is nifty", Wake.lostTurn(7, "Jarvis what is nifty"))
        assertTrue(Wake.heard(Wake.lostTurn(7, "Jarvis")!!, false) is Wake.Heard.Awake)
    }

    @Test fun nothingElseWakesHim() {
        assertNull(Wake.lostTurn(7, null))                                     // nothing read
        assertNull(Wake.lostTurn(7, "what is the time"))                       // words, no name
        assertNull(Wake.lostTurn(5, "Jarvis"))                                 // a real failure, not a lost turn
        assertNull(Wake.lostTurn(11, "Jarvis"))
    }
}
