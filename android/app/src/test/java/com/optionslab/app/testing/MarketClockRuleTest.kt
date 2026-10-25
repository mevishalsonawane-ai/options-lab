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

    @Test fun afterATestTheOwnClockIsClearedAndTheDefaultIsTheProcessClockNotTheWallClock() {
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
        // 25 Oct: between tests the market clock is the process one (noon on a trading day), never null (the wall clock).
        assertTrue(Market.testDefaultClock === MarketClockRule.processDefault)
        assertEquals(java.time.ZonedDateTime.now(MarketClockRule.processDefault).withZoneSameInstant(IST).toLocalDate(), Market.today())
        // This test's own rule set noon before the inner one ran: noon again for the rest of it.
        Market.testDefaultClock = MarketClockRule.clockAt(12, 0)
    }

    /**
     * 25 Oct (the Sunday CI run): a test class's fields are built before any rule runs. Ten tests failed only at the weekend
     * because a field such as `val base = Market.today().atTime(10, 0)` read the wall clock's Sunday while the test body read
     * the rule's Friday. The process clock is pinned when this rule's class loads, so a field reads the same trading day.
     */
    @Test fun aTestClassesFieldsReadTheTradingDayNotTheWallClock() {
        val processDay = java.time.ZonedDateTime.now(MarketClockRule.processDefault).withZoneSameInstant(IST).toLocalDate()
        // Pinned when this class loaded (a suite may run past IST midnight): a trading day, never ahead of today's IST date.
        assertFalse("never ahead of today's IST date", processDay.isAfter(LocalDate.now(IST)))
        assertFalse(processDay.dayOfWeek == DayOfWeek.SATURDAY || processDay.dayOfWeek == DayOfWeek.SUNDAY)
        assertFalse(Holidays.BUILT_IN.containsKey(processDay))
        // As between two tests: no rule running, the default is the process clock, and a new test class's field reads its day.
        val was = Market.testDefaultClock
        Market.testDefaultClock = null
        try {
            MarketClockRule(9, 30).apply(object : Statement() { override fun evaluate() {} }, Description.EMPTY).evaluate()
            class ATest { val base: LocalDate = Market.today() }
            assertEquals(processDay, ATest().base)
            // TestApp pins it again before each Robolectric test instance is built; nothing changes when it is already set.
            Market.testDefaultClock = null
            MarketClockRule.pinProcessDefault()
            assertTrue(Market.testDefaultClock === MarketClockRule.processDefault)
            MarketClockRule.pinProcessDefault()
            assertTrue(Market.testDefaultClock === MarketClockRule.processDefault)
        } finally { Market.testDefaultClock = was }
    }
}
