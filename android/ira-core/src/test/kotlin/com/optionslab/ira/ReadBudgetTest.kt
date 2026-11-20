package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals

/** 20 Nov 2026 (B5-D): the paper arms' fresh exit reads share [ReadBudget]. */
class ReadBudgetTest {
    @Test fun readsTogetherNeverPassTheBudgetInAWindow() {
        var now = 1_000L
        val b = ReadBudget(windowMs = 15_000L, budgetMs = 4_000L) { now }
        assertEquals(3_000L, b.take(3_000L))
        b.spend(3_000L)
        assertEquals(1_000L, b.take(3_000L), "only what is left")
        b.spend(1_200L)
        assertEquals(0L, b.take(3_000L), "none left: the stream's last price")
        now += 15_000L
        assertEquals(3_000L, b.take(3_000L), "a new window")
    }

    @Test fun aClockSetBackStartsAWindowAndANegativeSpendCountsNothing() {
        var now = 100_000L
        val b = ReadBudget(windowMs = 15_000L, budgetMs = 4_000L) { now }
        b.spend(4_000L)
        assertEquals(0L, b.take(1_000L))
        now = 50_000L
        assertEquals(1_000L, b.take(1_000L))
        b.spend(-500L)
        assertEquals(4_000L, b.take(5_000L))
    }
}
