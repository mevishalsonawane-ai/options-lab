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
 * Only the market's clock, and (25 Oct) the fake Zerodha's order times, which are stamped from it: the system clock
 * ([System.currentTimeMillis]: waits, Zerodha's token expiry, the stores' timestamps) is real.
 *
 * 25 Oct (the Sunday CI run): a test class's FIELDS are built before any rule runs, so a field like
 * `val base = Market.today().atTime(10, 0)` used to read the wall clock (a Sunday) while the test body read the rule's Friday,
 * and ten tests failed only at the weekend. Now [processDefault], the same noon-on-a-trading-day clock, is pinned the moment
 * this class is first loaded (every test class that declares the rule loads it while its own fields are built; [TestApp] pins
 * it before each Robolectric test instance is built too), and after each test the default goes back to [processDefault],
 * never to the wall clock. So no test, field or body, depends on the real calendar; only [clockAt] reads it, once.
 */
class MarketClockRule(private val hour: Int = 12, private val minute: Int = 0) : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            Market.testClock = null
            Market.testDefaultClock = clockAt(hour, minute)
            try { base.evaluate() } finally {
                Market.testDefaultClock = processDefault
                Market.testClock = null
            }
        }
    }

    companion object {
        /**
         * 25 Oct: the clock between tests and while a test instance's fields are built: noon IST on the latest trading day on or
         * before the real date, ticking, pinned once when this class is first loaded (so a suite that runs on past the hour still
         * reads the same day). Each test's own rule re-pins its hour at the start and puts this one back at the end.
         */
        val processDefault: Clock = clockAt(12, 0)

        init { pinProcessDefault() }

        /** [Market.now] reads [processDefault] while no rule and no test has set a clock (nothing when a clock is already set). */
        fun pinProcessDefault() { if (Market.testDefaultClock == null) Market.testDefaultClock = processDefault }

        /**
         * The trading day every test is pinned to, whatever clock the test has set itself meanwhile: for a test that moves its
         * clock to "the next trading day" more than once (reading [Market.today] after the first move would count from the moved
         * day, and the days would compound).
         */
        fun pinnedDay(): LocalDate = ZonedDateTime.now(processDefault).withZoneSameInstant(IST).toLocalDate()

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
