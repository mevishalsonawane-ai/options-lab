package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 14 Oct (Boss): a Pine script reads its signal on the index chart, then buys the option; any other chart is refused. */
class PineUnderlyingTest {
    @Test fun theFiveIndicesAreKept() {
        for (r in listOf("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")) assertEquals(r, PineUnderlying.rootOf(r))
        assertEquals(PineUnderlying.ROOTS, listOf("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"))
    }

    @Test fun lowerCaseAndTheUsualNamesBecomeTheRoot() {
        assertEquals("NIFTY", PineUnderlying.rootOf("nifty"))
        assertEquals("NIFTY", PineUnderlying.rootOf(" Nifty 50 "))
        assertEquals("NIFTY", PineUnderlying.rootOf("NSE:NIFTY"))
        assertEquals("BANKNIFTY", PineUnderlying.rootOf("Bank Nifty"))
        assertEquals("BANKNIFTY", PineUnderlying.rootOf("NSE:NIFTY BANK"))
        assertEquals("BANKNIFTY", PineUnderlying.rootOf("bank_nifty"))
        assertEquals("FINNIFTY", PineUnderlying.rootOf("Nifty Fin Service"))
        assertEquals("MIDCPNIFTY", PineUnderlying.rootOf("midcap-nifty"))
        assertEquals("SENSEX", PineUnderlying.rootOf("BSE:SENSEX"))
    }

    @Test fun anOptionAnMcxContractAndAnythingElseAreRefused() {
        for (s in listOf("BANKNIFTY26OCT52000CE", "NIFTY2610624500PE", "NIFTY 24500 CE", "nifty26oct24500ce",
                "CRUDEOIL26NOVFUT", "GOLDM26DEC72000CE", "CRUDEOIL", "RELIANCE", "INDIAVIX", "GOLD", "BTCUSD", "NIFTYNXT50"))
            assertNull(PineUnderlying.rootOf(s), s)
    }

    @Test fun emptyAndGarbageAreRefusedNeverThrown() {
        for (s in listOf(null, "", "   ", "::", "NSE:", "?!", "NIFTY!", "\u0000", "x".repeat(10_000))) assertNull(PineUnderlying.rootOf(s), "$s".take(20))
    }

    @Test fun theRefusalSaysWhyInPlainWords() {
        assertEquals("Pine scripts run on the index chart and then buy the option: BANKNIFTY26OCT52000CE is not an index",
            PineUnderlying.refusal("BANKNIFTY26OCT52000CE"))
        assertTrue(PineUnderlying.refusal("").endsWith("(no symbol) is not an index"))
        assertTrue(PineUnderlying.refusal(null).endsWith("(no symbol) is not an index"))
        assertTrue(PineUnderlying.refusal("y".repeat(500)).length < 150, "a long symbol is cut")
    }
}
