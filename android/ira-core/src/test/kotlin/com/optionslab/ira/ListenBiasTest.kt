package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Voice, round 27: the words Jarvis's ears are leaned towards - trading words, bounded, steady, never a backdoor. */
class ListenBiasTest {
    @Test fun tradingWordsAreThere() {
        val w = ListenBias.words().map { it.lowercase() }
        for (x in listOf("jarvis", "nifty", "bank nifty", "fin nifty", "sensex", "p&l", "theta", "square off", "vix", "mtm"))
            assertTrue(x in w, x)
    }

    @Test fun boundedDistinctAndSteady() {
        val w = ListenBias.words()
        assertTrue(w.isNotEmpty() && w.size <= ListenBias.MAX)
        assertEquals(w.size, w.map { it.lowercase() }.distinct().size)
        assertEquals(w, ListenBias.words())
        assertTrue(w.all { it.isNotBlank() && it == it.trim() })
    }

    /** No backdoor: nothing that guards the app is made likelier to be heard. */
    @Test fun neverASensitiveWord() {
        for (phrase in ListenBias.words()) {
            val p = phrase.lowercase()
            val parts = p.split(' ', '-').toSet()
            for (s in ListenBias.SENSITIVE) assertFalse(p == s || s in parts || (' ' in s && s in p), phrase)
        }
    }

    /** Asked about, each word gives no order. */
    @Test fun noWordPlacesAnOrder() {
        for (phrase in ListenBias.words()) assertNull(Ask.parse("what is $phrase").order, phrase)
    }
}
