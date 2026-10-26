package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 26 Oct ("Erase everything"): every kept reading of Boss's words is known to [WordCaches] and forgotten by it. */
class WordCachesTest {
    @Test fun aNewKeptReadingIsForgottenByTheErase() {
        WordCaches.clear()
        val k = Kept<String>(4)
        assertEquals("one", k.of("how is my nifty position") { "one" })
        assertEquals(1, k.size)
        assertTrue(WordCaches.holds())
        WordCaches.clear()
        assertEquals(0, k.size)
        // Read again after the erase: read afresh, not from memory.
        var reads = 0
        k.of("how is my nifty position") { reads++; "two" }
        assertEquals(1, reads)
    }

    @Test fun asksQuestionsAreForgottenToo() {
        Ask.parse("how is my banknifty position doing today")
        assertTrue(Ask.readingsKept() > 0)
        WordCaches.clear()
        assertEquals(0, Ask.readingsKept())
        assertFalse(WordCaches.holds())
    }

    @Test fun clearingWithNothingKeptIsHarmless() {
        WordCaches.clear()
        WordCaches.clear()
        assertFalse(WordCaches.holds())
    }
}
