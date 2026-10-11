package com.optionslab.app.work

import com.optionslab.app.testing.RobolectricTest
import com.optionslab.ira.BudgetRule
import org.junit.After
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
}
