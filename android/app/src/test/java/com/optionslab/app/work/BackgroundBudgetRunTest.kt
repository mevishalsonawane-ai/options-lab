package com.optionslab.app.work

import com.optionslab.app.testing.RobolectricTest
import com.optionslab.ira.BudgetRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime

/**
 * The night's CPU budget counts a whole run (review R4 #5): a job that asks once and then works for longer than the gap
 * between two asks is still counted when it ends ([BackgroundBudget.done]).
 */
class BackgroundBudgetRunTest : RobolectricTest() {
    private var cpu = 0L
    private var wall = 1_000_000L

    @Before fun up() {
        BackgroundBudget.resetForTest()
        BackgroundBudget.testNow = LocalDateTime.of(2026, 10, 12, 21, 0)
        BackgroundBudget.testPhone = BudgetRule.Phone(80, true, 33.0, false)
        BackgroundBudget.testCpuMs = { cpu }
        BackgroundBudget.testWallMs = { wall }
    }

    @After fun down() { BackgroundBudget.resetForTest() }

    /**
     * 22 Oct (the overnight kills, "excessive binder traffic during cached"): the forge's stop check asks the budget (the battery's
     * broadcast and the power manager) at most every 5 s, not on every backtest twin; the first call always asks, and a "no" is
     * seen within the 5 s.
     */
    @Test fun aCheckAskedOftenAsksTheBudgetAtMostEveryFiveSeconds() {
        var now = 0L
        var asks = 0
        var answer = true
        val t = BackgroundBudget.Throttled(5_000L, { now }) { asks++; answer }
        assertTrue(t())
        assertEquals(1, asks)
        repeat(4_000) { assertTrue(t()); now += 1 }      // 4,000 twins in 4 s: still the one ask
        assertEquals(1, asks)
        answer = false
        assertTrue("answered from the last ask until 5 s pass", t())
        now = 5_000L
        assertFalse("asked again at 5 s", t())
        assertEquals(2, asks)
        // Through the budget itself: tonight's phone too hot says no, asked once for many checks.
        BackgroundBudget.testPhone = BudgetRule.Phone(80, true, 46.0, false)
        var clock = 0L
        val f = BackgroundBudget.allowEvery(BudgetRule.Job.FORGE, clock = { clock })
        assertFalse(f()); clock += 100; assertFalse(f())
        BackgroundBudget.testPhone = BudgetRule.Phone(80, true, 33.0, false)
        clock += 100; assertFalse("still the last answer", f())
        clock += BackgroundBudget.CHECK_EVERY_MS; assertTrue(f())
    }

    @Test fun aLongRunThatAsksOnlyAtItsStartIsCountedWhenItEnds() {
        assertTrue(BackgroundBudget.allow(BudgetRule.Job.HISTORY))
        // Four minutes of work with no ask in between: more than the gap that separates two runs.
        cpu += BudgetRule.NIGHT_CPU_MS + 1
        wall += 4 * 60_000L
        BackgroundBudget.done(BudgetRule.Job.HISTORY)
        wall += 30 * 60_000L
        assertFalse("the run's CPU is spent from tonight's budget", BackgroundBudget.allow(BudgetRule.Job.HISTORY))
    }

    @Test fun withoutDoneTheGapBetweenRunsIsNotCounted() {
        assertTrue(BackgroundBudget.allow(BudgetRule.Job.HISTORY))
        BackgroundBudget.done(BudgetRule.Job.HISTORY)
        // The rest of the app's CPU between two runs is not the job's.
        cpu += BudgetRule.NIGHT_CPU_MS + 1
        wall += 30 * 60_000L
        assertTrue(BackgroundBudget.allow(BudgetRule.Job.HISTORY))
    }

    /** Review G8-10: the afternoon's work spends the day's budget, not the night's that follows. */
    @Test fun theAfternoonsWorkLeavesTheNightsBudgetWhole() {
        BackgroundBudget.testNow = LocalDateTime.of(2026, 10, 12, 15, 50)
        assertTrue(BackgroundBudget.allow(BudgetRule.Job.HISTORY))
        cpu += BudgetRule.NIGHT_CPU_MS + 1
        BackgroundBudget.done(BudgetRule.Job.HISTORY)
        wall += 30 * 60_000L
        assertFalse("the day's own budget is spent", BackgroundBudget.allow(BudgetRule.Job.HISTORY))
        BackgroundBudget.testNow = LocalDateTime.of(2026, 10, 12, 16, 30)
        assertTrue("tonight's 10 minutes are untouched", BackgroundBudget.allow(BudgetRule.Job.LEARNING))
    }

    /** Review G8-10: two jobs running at once count the process's CPU once. */
    @Test fun twoJobsAtOnceCountTheCpuOnce() {
        assertTrue(BackgroundBudget.allow(BudgetRule.Job.FORGE))
        assertTrue(BackgroundBudget.allow(BudgetRule.Job.LEARNING))
        // Six minutes of the process's CPU while both run: six minutes spent, not twelve.
        cpu += 6 * 60_000L
        wall += 60_000L
        assertTrue(BackgroundBudget.allow(BudgetRule.Job.FORGE))
        assertTrue("6 of the 10 minutes, counted once", BackgroundBudget.allow(BudgetRule.Job.LEARNING))
    }

    /** 12 Oct ("the history vault waits for tomorrow" at 16:24): the other jobs stop short of the vault's share of the night. */
    @Test fun theHistoryVaultKeepsItsShareOfTheNight() {
        assertTrue(BackgroundBudget.allow(BudgetRule.Job.FORGE))
        cpu += BudgetRule.NIGHT_CPU_MS - BudgetRule.HISTORY_RESERVE_MS
        wall += 60_000L
        assertFalse("the forge stops at the vault's share", BackgroundBudget.allow(BudgetRule.Job.FORGE))
        assertFalse(BackgroundBudget.allow(BudgetRule.Job.ORDER_REVIEW))
        assertTrue("the vault still runs", BackgroundBudget.allow(BudgetRule.Job.HISTORY))
    }
}
