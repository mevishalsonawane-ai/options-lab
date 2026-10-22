package com.optionslab.app.data

import com.optionslab.app.testing.RobolectricTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 22 Oct (arms audit): the "days like today" context's expiry days by the exchanges' rule - NIFTY weekly on Tuesday, SENSEX
 * weekly on Thursday, BANKNIFTY / FINNIFTY / MIDCPNIFTY monthly only (the month's last Tuesday). Every index was Tuesday before.
 * Context only: no order reads it.
 */
class MarketContextExpiryRuleTest : RobolectricTest() {
    private val tue20 = LocalDate.of(2026, 10, 20)       // a Tuesday, not the month's last
    private val tue27 = LocalDate.of(2026, 10, 27)       // October 2026's last Tuesday
    private val thu22 = LocalDate.of(2026, 10, 22)

    @Test fun niftyIsWeeklyOnTuesday() {
        assertTrue(MarketContext.ruleExpiry("NIFTY", tue20))
        assertTrue(MarketContext.ruleExpiry("NIFTY", tue27))
        assertFalse(MarketContext.ruleExpiry("NIFTY", thu22))
    }

    @Test fun sensexIsWeeklyOnThursdayNeverTuesday() {
        assertTrue(MarketContext.ruleExpiry("SENSEX", thu22))
        assertFalse(MarketContext.ruleExpiry("SENSEX", tue20))
        assertFalse(MarketContext.ruleExpiry("SENSEX", tue27))
    }

    @Test fun theMonthlyIndicesOnlyOnTheMonthsLastTuesday() {
        for (u in listOf("BANKNIFTY", "FINNIFTY", "MIDCPNIFTY")) {
            assertTrue(u, MarketContext.ruleExpiry(u, tue27))
            assertFalse(u, MarketContext.ruleExpiry(u, tue20))
            assertFalse(u, MarketContext.ruleExpiry(u, thu22))
        }
        // A month whose last Tuesday is the 24th (November 2026 ends on a Monday the 30th).
        assertTrue(MarketContext.ruleExpiry("BANKNIFTY", LocalDate.of(2026, 11, 24)))
        assertFalse(MarketContext.ruleExpiry("BANKNIFTY", LocalDate.of(2026, 11, 17)))
    }
}
