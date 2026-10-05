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

    @Test fun shownBeforeChargesWithTheChargesBeside() {
        at(wed.atTime(10, 0))
        DailyPnl.record(false, 400.0, 2, 180.0)     // paper keeps its figure after charges
        DailyPnl.record(true, 900.0, 3, 75.5)       // Zerodha keeps its own m2m, before them
        val paper = DailyPnl.all(false).getValue(wed)
        assertEquals(580.0, paper.pnl, 0.0)
        assertEquals(180.0, paper.charges, 0.0)
        assertEquals(400.0, paper.net, 0.0)
        assertEquals(900.0, DailyPnl.all(true).getValue(wed).pnl, 0.0)
        DailyPnl.record(true, 950.0, -1)            // a reading with no trades read keeps the charges
        assertEquals(75.5, DailyPnl.all(true).getValue(wed).charges, 0.0)
        assertEquals(950.0, DailyPnl.all(true).getValue(wed).pnl, 0.0)
    }

    @Test fun anOlderEntryWithNoChargesShowsAsBefore() {
        SecurePrefs.put("pnl.days.paper", """{"$tue":[589.0,2]}""")
        at(wed.atTime(9, 42))
        val day = DailyPnl.all(false).getValue(tue)
        assertEquals(589.0, day.pnl, 0.0)
        assertEquals(0.0, day.charges, 0.0)
    }

    @Test fun resetPaperClearsPaperHistoryAndKeepsZerodha() {
        at(wed.atTime(10, 0))
        DailyPnl.record(false, 300.0, 2); DailyPnl.record(true, 900.0, -1)
        Journal.put("paper:1", "paper note", setOf("Breakout")); Journal.put("kite:9", "live note", emptySet())
        DailyPnl.resetPaper(); Journal.resetPaper()
        assertNull(DailyPnl.all(false)[wed])
        assertEquals(900.0, DailyPnl.all(true).getValue(wed).pnl, 0.0)
        assertNull(Journal.of("paper:1"))
        assertEquals("live note", Journal.of("kite:9")?.note)
    }
}
