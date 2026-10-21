package com.optionslab.app.testing

import com.optionslab.app.data.Holidays
import com.optionslab.app.data.Market
import com.optionslab.engine.IST
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.time.DayOfWeek
import java.time.LocalDate

/** 21 Oct, enhancement round 4 (idea 5): the app tests' market clock rule ([MarketClockRule]). */
class MarketClockRuleTest {
    @get:Rule val marketClock = MarketClockRule()

    @Test fun eachTestStartsAtNoonISTOnATradingDayStillTicking() {
        val now = Market.now()
        assertEquals(12, now.hour)
        assertTrue("within the first minutes of noon: ${now.toLocalTime()}", now.minute < 5)
        val d = now.toLocalDate()
        assertFalse(d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY)
        assertFalse(Holidays.BUILT_IN.containsKey(d))
        assertFalse("never ahead of today's IST date", d.isAfter(LocalDate.now(IST)))
        val a = Market.now()
        Thread.sleep(20)
        assertTrue("it ticks", Market.now().isAfter(a))
    }

    @Test fun aTestsOwnClockWinsAndClearingItReturnsToNoonNotTheWallClock() {
        val day = Market.today()
        Market.testClock = java.time.Clock.fixed(day.atTime(15, 45).atZone(IST).toInstant(), IST)
        assertEquals(15, Market.now().hour); assertEquals(45, Market.now().minute)
        Market.testClock = null
        assertEquals(12, Market.now().hour)
    }

    @Test fun theLatestTradingDayOnOrBeforeSkipsWeekendsAndHolidays() {
        // Sat 24 Oct 2026 and Sun 25 Oct: back to Fri 23 Oct.
        assertEquals(LocalDate.of(2026, 10, 23), MarketClockRule.tradingDayOnOrBefore(LocalDate.of(2026, 10, 25)))
        assertEquals(LocalDate.of(2026, 10, 23), MarketClockRule.tradingDayOnOrBefore(LocalDate.of(2026, 10, 24)))
        assertEquals(LocalDate.of(2026, 10, 21), MarketClockRule.tradingDayOnOrBefore(LocalDate.of(2026, 10, 21)))
        // A built-in NSE holiday on a weekday: the weekday before it (or earlier).
        val hol = Holidays.BUILT_IN.keys.first { it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY }
        val d = MarketClockRule.tradingDayOnOrBefore(hol)
        assertTrue(d.isBefore(hol))
        assertFalse(Holidays.BUILT_IN.containsKey(d))
    }

    @Test fun afterATestBothClocksAreCleared() {
        val inner = MarketClockRule(9, 30)
        var seen = -1
        inner.apply(object : Statement() {
            override fun evaluate() {
                seen = Market.now().hour * 60 + Market.now().minute
                Market.testClock = java.time.Clock.systemUTC()
            }
        }, Description.EMPTY).evaluate()
        assertEquals(9 * 60 + 30, seen)
        assertNull(Market.testClock)
        assertNull(Market.testDefaultClock)
        // The wall clock again (this test's own rule is outside the inner one, and set it before): restored for the rest of it.
        Market.testDefaultClock = MarketClockRule.clockAt(12, 0)
    }
}
