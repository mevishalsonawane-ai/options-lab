package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MissedTest {
    @Test fun keptOnceEachAndCapped() {
        var l = emptyList<String>()
        l = Missed.add(l, "what s going on"); l = Missed.add(l, "What s going on"); l = Missed.add(l, "  ")
        assertEquals(listOf("What s going on"), l)
        repeat(30) { l = Missed.add(l, "thing $it") }
        assertEquals(Missed.KEEP, l.size)
        assertTrue(Missed.say(l)!!.startsWith("I didn't understand 20 things today"))
        assertNull(Missed.say(emptyList()))
    }
}
