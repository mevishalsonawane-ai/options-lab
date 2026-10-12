package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 12 Oct: Jarvis switched on paper auto-trading for a SENSEX and a FinNifty strategy; those are alerts only. */
class PineVenueTest {
    @Test fun bseAndFinniftyStrategiesAreAlertsOnly() {
        assertTrue(PineVenue.alertsOnlyWhy("SENSEX")!!.contains("BSE"))
        assertNotNull(PineVenue.alertsOnlyWhy("sensex"))
        assertNotNull(PineVenue.alertsOnlyWhy("BANKEX"))
        assertTrue(PineVenue.alertsOnlyWhy("FINNIFTY")!!.contains("too thin"))
        assertNotNull(PineVenue.alertsOnlyWhy("Fin Nifty"))
        assertNull(PineVenue.alertsOnlyWhy("BANKNIFTY"))
        assertNull(PineVenue.alertsOnlyWhy("NIFTY"))
        assertNull(PineVenue.alertsOnlyWhy("MIDCPNIFTY"))
    }
}
