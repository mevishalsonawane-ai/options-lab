package com.optionslab.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 9 Nov (Boss: "application is showing wrong funds details"): Home's Capital card with Zerodha's used margin below zero. */
class HomeMoneyTest {
    @Test fun aPremiumReceivedIsNothingInUseAndCapitalIsWhatKiteShowsAvailable() {
        // Boss's account, 9 Nov 09:39: available 56,428.40, used -19,500 (a PE sold that morning credited its premium).
        val m = Money(1_034.0, 56_428.40, -19_500.0)
        assertEquals(0.0, m.usedShown!!, 0.0)
        assertEquals("Capital is Kite's available, not 36,928", 56_428.40, m.capital!!, 1e-9)
    }

    @Test fun moneyInUseStillAddsUp() {
        val m = Money(null, 40_000.0, 15_000.0)
        assertEquals(15_000.0, m.usedShown!!, 0.0)
        assertEquals(55_000.0, m.capital!!, 1e-9)
        assertEquals(0.0, Money(null, 10_000.0, 0.0).usedShown!!, 0.0)
    }

    @Test fun anUnreadFigureLeavesCapitalUnknown() {
        assertNull(Money(null, 10_000.0, null).capital)
        assertNull(Money(null, null, -5_000.0).capital)
        assertNull(Money(null, 10_000.0, null).usedShown)
    }
}
