package com.optionslab.app.work

import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.ira.TicketRisk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** 20 Oct: the paper ticket's risk notices are said once per ticket, even after a restart (19 Oct: twice each). */
class TicketNoticesTest : RobolectricTest() {
    private val day = LocalDate.of(2026, 10, 19)

    @After fun down() { Tasks.TicketNotices.resetForTest() }

    @Test fun theSaidKeysSurviveARestart() {
        Tasks.TicketNotices.resetForTest()
        assertTrue(Tasks.TicketNotices.said().isEmpty())
        val first = TicketRisk.due("NIFTY", "SELL", "PE", 25_200.0, 25_000.0, day, day, day, 25_050.0, 0.0025, Tasks.TicketNotices.said())
        assertEquals(2, first.size)
        first.forEach { Tasks.TicketNotices.add(it.key) }
        assertTrue(SecurePrefs.flush())
        // A restart: memory gone, the vault read again - nothing is said twice.
        Tasks.TicketNotices.resetForTest()
        assertEquals(first.map { it.key }.toSet(), Tasks.TicketNotices.said())
        assertTrue(TicketRisk.due("NIFTY", "SELL", "PE", 25_200.0, 25_000.0, day, day, day, 25_020.0, 0.0025, Tasks.TicketNotices.said()).isEmpty())
        // Below the breakeven later: that step only, once.
        assertEquals(listOf("risk.below|2026-10-19"),
            TicketRisk.due("NIFTY", "SELL", "PE", 25_200.0, 25_000.0, day, day, day, 24_950.0, 0.0025, Tasks.TicketNotices.said()).map { it.key })
    }
}
