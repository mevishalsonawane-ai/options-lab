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
    private val tue20 = LocalDate.of(2026, 10, 20)       // a Tuesday, not the month's last - and Dussehra, an NSE holiday
    private val mon19 = LocalDate.of(2026, 10, 19)       // NIFTY's expiry, moved back from the holiday
    private val wed21 = LocalDate.of(2026, 10, 21)
    private val tue13 = LocalDate.of(2026, 10, 13)
    private val tue27 = LocalDate.of(2026, 10, 27)       // October 2026's last Tuesday
    private val thu22 = LocalDate.of(2026, 10, 22)

    @Test fun niftyIsWeeklyOnTuesday() {
        assertTrue(MarketContext.ruleExpiry("NIFTY", tue13))
        assertTrue(MarketContext.ruleExpiry("NIFTY", tue27))
        assertFalse(MarketContext.ruleExpiry("NIFTY", thu22))
    }

    @Test fun sensexIsWeeklyOnThursdayNeverTuesday() {
        assertTrue(MarketContext.ruleExpiry("SENSEX", thu22))
        assertFalse(MarketContext.ruleExpiry("SENSEX", tue13))
        assertFalse(MarketContext.ruleExpiry("SENSEX", tue27))
    }

    @Test fun theMonthlyIndicesOnlyOnTheMonthsLastTuesday() {
        for (u in listOf("BANKNIFTY", "FINNIFTY", "MIDCPNIFTY")) {
            assertTrue(u, MarketContext.ruleExpiry(u, tue27))
            assertFalse(u, MarketContext.ruleExpiry(u, tue13))
            assertFalse(u, MarketContext.ruleExpiry(u, thu22))
        }
        // A month whose last Tuesday is the 24th (November 2026 ends on a Monday the 30th) - and the 24th is Guru Nanak Jayanti, an
        // NSE holiday: the expiry moves back to Monday the 23rd (26 Oct, R105 lead 6).
        assertTrue(Holidays.isHoliday(LocalDate.of(2026, 11, 24)))
        assertFalse(MarketContext.ruleExpiry("BANKNIFTY", LocalDate.of(2026, 11, 24)))
        assertTrue(MarketContext.ruleExpiry("BANKNIFTY", LocalDate.of(2026, 11, 23)))
        assertFalse(MarketContext.ruleExpiry("BANKNIFTY", LocalDate.of(2026, 11, 17)))
    }

    /**
     * 26 Oct (R105 lead 6): Tue 20 Oct 2026 was Dussehra (in [Holidays]' table): NIFTY's weekly expiry moved to the PREVIOUS trading
     * day, Mon 19 Oct - never to Wed 21 Oct - and the holiday itself is no expiry day. A weekend is never one either.
     */
    @Test fun aHolidayMovesTheExpiryToThePreviousTradingDay() {
        assertTrue(Holidays.isHoliday(tue20))
        assertTrue(MarketContext.ruleExpiry("NIFTY", mon19))
        assertFalse(MarketContext.ruleExpiry("NIFTY", tue20))
        assertFalse(MarketContext.ruleExpiry("NIFTY", wed21))
        assertFalse(MarketContext.ruleExpiry("NIFTY", LocalDate.of(2026, 10, 25)))
        // The monthly indices and SENSEX are not moved by NIFTY's weekly holiday.
        assertFalse(MarketContext.ruleExpiry("BANKNIFTY", mon19))
        assertFalse(MarketContext.ruleExpiry("SENSEX", mon19))
        assertTrue(MarketContext.ruleExpiry("SENSEX", thu22))
    }
}
