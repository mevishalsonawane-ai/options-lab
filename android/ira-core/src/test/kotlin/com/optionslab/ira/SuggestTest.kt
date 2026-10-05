package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SuggestTest {
    @Test fun closestKnownLine() {
        assertEquals("show my positions", Suggest.closest("positions show karo"))
        assertEquals("banknifty pcr and max pain", Suggest.closest("bank nifty max pain batao"))
        assertEquals("I didn't catch that, Boss. Did you mean \"cancel all orders\"?", Suggest.line("orders cancel kar do yaar"))
    }

    @Test fun nothingCloseOrRiskAdding() {
        assertNull(Suggest.closest("good evening friend"))
        assertNull(Suggest.closest("strategies"))
        // Starting strategies adds risk: never offered.
        assertNull(Suggest.closest("strategies start again"))
    }
}
