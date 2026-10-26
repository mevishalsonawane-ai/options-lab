package com.optionslab.engine.orb

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 26 Oct (R105 lead 6): the expiry weekdays with the holiday shift - a holiday's expiry moves to the PREVIOUS trading day. */
class ExpiryRuleTest {
    private val holidays = setOf(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 20), LocalDate.of(2026, 11, 10), LocalDate.of(2026, 12, 29),
        LocalDate.of(2026, 12, 28))
    private val trading: (LocalDate) -> Boolean = { d -> d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY && d !in holidays }
    private fun d(m: Int, day: Int) = LocalDate.of(2026, m, day)

    @Test fun niftysTuesdayHolidayMovesTheExpiryToMondayNeverWednesday() {
        assertTrue(ExpiryRule.isExpiry("NIFTY", d(10, 19), trading), "Mon 19 Oct: Dussehra moved it here")
        assertFalse(ExpiryRule.isExpiry("NIFTY", d(10, 20), trading), "the holiday itself")
        assertFalse(ExpiryRule.isExpiry("NIFTY", d(10, 21), trading), "Wed 21 Oct is NOT NIFTY's expiry day")
        assertTrue(ExpiryRule.isExpiry("NIFTY", d(10, 27), trading))
        assertTrue(ExpiryRule.isExpiry("NIFTY", d(10, 13), trading))
        assertFalse(ExpiryRule.isExpiry("NIFTY", d(10, 26), trading), "today, Monday 26 Oct")
        assertFalse(ExpiryRule.isExpiry("NIFTY", d(10, 25), trading), "a Sunday is never one")
        assertEquals(d(10, 19), ExpiryRule.shifted(d(10, 20), trading))
    }

    @Test fun twoHolidaysInARowMoveItBackOverBoth() {
        // Mon 28 and Tue 29 Dec closed (a made-up closure): back over the weekend to Friday 25 Dec (open in this calendar).
        assertTrue(ExpiryRule.isExpiry("NIFTY", d(12, 25), trading))
        assertFalse(ExpiryRule.isExpiry("NIFTY", d(12, 28), trading))
        assertFalse(ExpiryRule.isExpiry("NIFTY", d(12, 29), trading))
        // The month's last Tuesday too, for the monthly indices.
        assertTrue(ExpiryRule.isExpiry("BANKNIFTY", d(12, 25), trading))
        // A calendar where nothing trades: never an endless walk, never an expiry.
        assertNull(ExpiryRule.shifted(d(12, 29)) { false })
        assertFalse(ExpiryRule.isExpiry("NIFTY", d(12, 29)) { false })
    }

    @Test fun senseThursdayAndTheMonthliesLastTuesday() {
        assertTrue(ExpiryRule.isExpiry("SENSEX", d(10, 22), trading))
        assertFalse(ExpiryRule.isExpiry("SENSEX", d(10, 20), trading))
        assertFalse(ExpiryRule.isExpiry("SENSEX", d(10, 21), trading))
        for (u in listOf("BANKNIFTY", "FINNIFTY", "MIDCPNIFTY")) {
            assertTrue(ExpiryRule.isExpiry(u, d(10, 27), trading), u)
            assertFalse(ExpiryRule.isExpiry(u, d(10, 19), trading), "$u: monthly only, the weekly shift is NIFTY's")
            assertTrue(ExpiryRule.isExpiry(u, d(11, 24), trading), u)
        }
        assertEquals(d(11, 24), ExpiryRule.nominalOnOrAfter("BANKNIFTY", d(10, 28)))
        assertNull(ExpiryRule.nominalOnOrAfter("CRUDEOIL", d(10, 28)))
        assertFalse(ExpiryRule.isExpiry("CRUDEOIL", d(10, 27), trading))
        // A calendar that throws counts the day as trading (as the app's own "unknown is open"): the weekday rule.
        assertTrue(ExpiryRule.isExpiry("NIFTY", d(10, 27)) { throw IllegalStateException("no calendar") })
    }

    /** The arms themselves read the exchange's listed expiries: a list moved to Monday 19 Oct makes Wednesday no expiry day. */
    @Test fun theListedExpiriesCarryTheShift() {
        val listed = listOf(d(10, 19), d(10, 27), d(11, 3))
        assertTrue(HeroRules.isExpiryDay(d(10, 19), listed))
        assertFalse(HeroRules.isExpiryDay(d(10, 21), listed))
        assertFalse(HeroRules.isExpiryDay(d(10, 26), listed))
        assertTrue(HeroRules.isExpiryDay(d(10, 27), listed))
        assertFalse(HeroRules.isExpiryDay(d(10, 21), emptyList()))
    }
}
