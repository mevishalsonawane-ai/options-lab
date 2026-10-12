package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The battery and heat budget ([BudgetRule]) with made-up phone states. */
class BudgetRuleTest {
    private val ok = BudgetRule.Phone(80, charging = false, tempC = 32.0, powerSave = false)
    private val job = BudgetRule.Job.FORGE

    @Test fun lowBatteryHeatSaverAndSpentCpuEachHoldTheJob() {
        assertTrue(BudgetRule.decide(job, ok, 0).allow)
        assertFalse(BudgetRule.decide(job, ok.copy(batteryPct = 29), 0).allow)
        assertTrue(BudgetRule.decide(job, ok.copy(batteryPct = 29, charging = true), 0).allow, "charging: a low battery may run")
        assertTrue(BudgetRule.decide(job, ok.copy(batteryPct = 30), 0).allow)
        assertFalse(BudgetRule.decide(job, ok.copy(tempC = 40.5), 0).allow)
        assertTrue(BudgetRule.decide(job, ok.copy(tempC = 40.0), 0).allow)
        assertFalse(BudgetRule.decide(job, ok.copy(tempC = 41.0, charging = true), 0).allow, "heat holds it even when charging")
        assertFalse(BudgetRule.decide(job, ok.copy(powerSave = true), 0).allow)
        val spent = BudgetRule.decide(job, ok, BudgetRule.NIGHT_CPU_MS)
        assertFalse(spent.allow); assertTrue(spent.why.contains("spent"))
        assertTrue(BudgetRule.decide(job, BudgetRule.Phone(null, false, null, false), 0).allow, "unknown readings do not hold it")
    }

    @Test fun theNightAndTheReadingsAreRead() {
        assertEquals(LocalDate.of(2026, 10, 12), BudgetRule.nightOf(LocalDateTime.of(2026, 10, 13, 2, 0)))
        assertEquals(LocalDate.of(2026, 10, 13), BudgetRule.nightOf(LocalDateTime.of(2026, 10, 13, 21, 0)))
        assertEquals(41.5, BudgetRule.tempOf(415))
        assertNull(BudgetRule.tempOf(0))
        assertEquals(45, BudgetRule.pctOf(45, 100))
        assertNull(BudgetRule.pctOf(-1, 100))
    }

    /** 12 Oct: the history vault waited for tomorrow at 16:24 with 24 of 62 pieces kept; the end of each budget is its share. */
    @Test fun theLastMinutesOfTheBudgetAreTheHistoryVaults() {
        val share = BudgetRule.NIGHT_CPU_MS - BudgetRule.HISTORY_RESERVE_MS
        for (other in BudgetRule.Job.entries.filter { it != BudgetRule.Job.HISTORY }) {
            assertTrue(BudgetRule.decide(other, ok, share - 1).allow)
            val held = BudgetRule.decide(other, ok, share)
            assertFalse(held.allow, other.name); assertTrue(held.why.contains("history vault's share"), held.why)
        }
        assertTrue(BudgetRule.decide(BudgetRule.Job.HISTORY, ok, share).allow)
        assertTrue(BudgetRule.decide(BudgetRule.Job.HISTORY, ok, BudgetRule.NIGHT_CPU_MS - 1).allow)
        assertFalse(BudgetRule.decide(BudgetRule.Job.HISTORY, ok, BudgetRule.NIGHT_CPU_MS).allow)
    }
}
