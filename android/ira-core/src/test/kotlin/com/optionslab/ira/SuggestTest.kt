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
        // Never the opposite of what was said, never a market not named, never a loose match.
        assertNull(Suggest.closest("kill switch off kar do"))
        assertNull(Suggest.closest("live mode switch kar do"))
        assertNull(Suggest.closest("what is the lot size of reliance"))
        assertNull(Suggest.closest("which model is better for trading"))
    }
}
