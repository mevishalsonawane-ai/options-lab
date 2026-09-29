package com.optionslab.engine.orb

import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Straddle Sell: sell the ATM pair once between 09:25 and 10:30, out at 15:10 or when half the credit is lost. */
class StraddleRulesTest {
    private val day = LocalDate.of(2026, 9, 30)
    private fun at(h: Int, m: Int) = day.atTime(h, m)

    @Test fun theArmIsPaperOnlyAndSellsBothSides() {
        assertTrue(StraddleRules.ARM.straddle)
        assertTrue(StraddleRules.ARM.paperOnly)
        assertEquals("straddle_sell", StraddleRules.ARM.source)
    }

    @Test fun sellsOnceInTheMorningWindow() {
        assertEquals(false to "waiting_for_0920_strike", StraddleRules.entryDecision(at(9, 24), soldToday = false))
        assertEquals(true to "sell", StraddleRules.entryDecision(at(9, 25), soldToday = false))
        assertEquals(true to "sell", StraddleRules.entryDecision(at(10, 29), soldToday = false))
        assertEquals(false to "too_late_to_sell_today", StraddleRules.entryDecision(at(10, 30), soldToday = false))
        assertFalse(StraddleRules.entryDecision(at(9, 40), soldToday = true).first)
        assertEquals("done_for_today", StraddleRules.entryDecision(at(9, 40), soldToday = true).second)
    }

    @Test fun stopsWhenHalfTheCreditIsLost() {
        // sold for 1000 in all: buying back for 1499 is a 499 loss, 1500 is the 50% stop
        assertNull(StraddleRules.exitReason(1000.0, 1499.0, at(11, 0)))
        assertEquals("stop", StraddleRules.exitReason(1000.0, 1500.0, at(11, 0)))
        assertEquals("stop", StraddleRules.exitReason(1000.0, 1800.0, at(11, 0)))
        // a profit is simply held
        assertNull(StraddleRules.exitReason(1000.0, 700.0, at(14, 0)))
        assertEquals(-300.0, StraddleRules.loss(1000.0, 700.0), 1e-9)
    }

    @Test fun alwaysOutAt1510() {
        assertEquals("session_end", StraddleRules.exitReason(1000.0, 700.0, day.atTime(LocalTime.of(15, 10))))
        assertEquals("session_end", StraddleRules.exitReason(1000.0, 1600.0, at(15, 20)))
        assertNull(StraddleRules.exitReason(0.0, 50.0, at(11, 0)))
    }
}
