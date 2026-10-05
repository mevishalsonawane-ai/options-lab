package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AutoStopTest {
    @Test fun bossChoosesInChat() {
        assertEquals(true, AutoStop.read("Jarvis, do it automatically"))
        assertEquals(true, AutoStop.read("don't ask me before stopping"))
        assertEquals(true, AutoStop.read("stop them automatically"))
        assertEquals(false, AutoStop.read("ask me before stopping"))
        assertEquals(false, AutoStop.read("always ask me"))
        assertEquals(false, AutoStop.read("don't do it automatically"))
        assertEquals(false, AutoStop.read("stop doing it automatically"))
        assertNull(AutoStop.read("stop all strategies"))
        assertNull(AutoStop.read("set automatic stop loss on my positions"))
        assertNull(AutoStop.read("turn on auto approve for the ORB arm"))
        assertNull(AutoStop.read("what is nifty"))
    }
}
