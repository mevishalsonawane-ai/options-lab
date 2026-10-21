package com.optionslab.app.testing

import com.optionslab.app.data.Holidays
import com.optionslab.app.data.Market
import com.optionslab.engine.IST
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * 21 Oct, enhancement round 4 (idea 5): every app test starts with the market's clock ([Market.now]) at noon IST on a normal trading
 * day (the latest weekday on or before today's IST date that is not an NSE holiday on the built-in list), still ticking. So a new
 * test can no longer pass at one hour of the day and fail at another by accident (CI's runs after 15:15 IST, around 06:00 IST, on
 * expiry days and at weekends each failed tests in rounds 1 to 3).
 *
 * It sets [Market.testDefaultClock], the clock [Market.now] falls back to while [Market.testClock] is null: a test that needs another
 * time sets [Market.testClock] itself as before (and setting it back to null returns to noon, never to the wall clock). Both are
 * cleared after the test. In [RobolectricTest] for every test that extends it; other test classes declare it as a rule field.
 * Only the market's clock: the system clock ([System.currentTimeMillis], Zerodha's token expiry, the stores' timestamps) is real.
 */
class MarketClockRule(private val hour: Int = 12, private val minute: Int = 0) : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            Market.testClock = null
            Market.testDefaultClock = clockAt(hour, minute)
            try { base.evaluate() } finally {
                Market.testDefaultClock = null
                Market.testClock = null
            }
        }
    }

    companion object {
        /** The latest normal trading day on or before [today]: a weekday that is not on NSE's built-in holiday list. */
        fun tradingDayOnOrBefore(today: LocalDate): LocalDate {
            var d = today
            while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY || Holidays.BUILT_IN.containsKey(d)) d = d.minusDays(1)
            return d
        }

        /** A ticking clock that reads [hour]:[minute] IST on [tradingDayOnOrBefore] today now. */
        fun clockAt(hour: Int, minute: Int): Clock {
            val realNow = ZonedDateTime.now(IST)
            val at = tradingDayOnOrBefore(realNow.toLocalDate()).atTime(hour, minute).atZone(IST)
            return Clock.offset(Clock.system(IST), Duration.between(realNow, at))
        }
    }
}
