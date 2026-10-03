package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryTest {
    @Test fun wordsAreKeptAndRead() {
        assertEquals("I want to book profits at 25,000", Memory.toKeep("Jarvis, remember that I want to book profits at 25,000."))
        assertEquals("no trades on RBI day", Memory.toKeep("keep in mind no trades on RBI day"))
        assertNull(Memory.toKeep("remember my PIN is 1234"), "nothing secret")
        assertNull(Memory.toKeep("remember me"))
        assertNull(Memory.toKeep("how is nifty"))
        assertTrue(Memory.recallAsked("Jarvis, what did I tell you?"))
        assertTrue(Memory.forgetAsked("forget what I told you"))
        assertTrue(Memory.lines(listOf(Memory.Item(LocalDate.of(2026, 10, 3), "no trades on RBI day"))).contains("no trades on RBI day (2026-10-03)"))
    }
}
