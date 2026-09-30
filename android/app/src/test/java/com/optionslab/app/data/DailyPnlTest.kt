package com.optionslab.app.data

import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.IST
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime

/** The calendar's day figures: a reading belongs to the account's trading day, not the phone's date. */
class DailyPnlTest : RobolectricTest() {
    private val tue = LocalDate.of(2026, 9, 29)
    private val wed = tue.plusDays(1)

    private fun at(t: LocalDateTime) { Market.testClock = Clock.fixed(t.atZone(IST).toInstant(), IST) }

    @After fun down() { Market.testClock = null }

    @Test fun paperReadingAfterMidnightStaysOnTheDayBefore() {
        at(tue.atTime(15, 20)); DailyPnl.record(false, 589.0, 2)
        at(wed.atTime(0, 40)); DailyPnl.record(false, 589.0, 2)
        at(wed.atTime(2, 59)); DailyPnl.record(false, 589.0, 2)
        val days = DailyPnl.all(false)
        assertEquals(589.0, days.getValue(tue).pnl, 0.0)
        assertNull("no copy of Tuesday on Wednesday", days[wed])
        at(wed.atTime(3, 0)); assertEquals(wed, DailyPnl.sessionDay(false))
    }

    @Test fun zerodhaNothingRecordedBeforeNine() {
        at(wed.atTime(8, 59)); DailyPnl.record(true, 1200.0, -1)
        assertNull(DailyPnl.all(true)[wed])
        at(wed.atTime(9, 30)); DailyPnl.record(true, -150.0, -1)
        assertEquals(-150.0, DailyPnl.all(true).getValue(wed).pnl, 0.0)
    }

    @Test fun olderBuildsCopyOfYesterdayIsHidden() {
        SecurePrefs.put("pnl.days.paper", """{"$tue":[589.0,2],"$wed":[589.0,2]}""")
        at(wed.atTime(9, 42))
        val days = DailyPnl.all(false)
        assertEquals(589.0, days.getValue(tue).pnl, 0.0)
        assertNull(days[wed])
    }
}
