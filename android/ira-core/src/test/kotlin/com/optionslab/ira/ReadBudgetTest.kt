package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 20 Nov 2026 (B5-D): the paper arms' fresh exit reads share [ReadBudget]. */
class ReadBudgetTest {
    @Test fun readsTogetherNeverPassTheBudgetInAWindow() {
        var now = 1_000L
        val b = ReadBudget(windowMs = 15_000L, budgetMs = 4_000L) { now }
        val a = b.reserve(3_000L)!!
        assertEquals(3_000L, a.ms)
        b.settle(a, 3_000L)
        val c = b.reserve(3_000L)!!
        assertEquals(1_000L, c.ms, "only what is left")
        b.settle(c, 1_200L)
        assertNull(b.reserve(3_000L), "none left: the stream's last price")
        now += 15_000L
        assertEquals(3_000L, b.reserve(3_000L)!!.ms, "a new window")
    }

    @Test fun whatAReadDidNotUseIsGivenBack() {
        val b = ReadBudget(windowMs = 15_000L, budgetMs = 4_000L) { 0L }
        val a = b.reserve(3_000L)!!
        assertEquals(1_000L, b.left(), "held up front")
        b.settle(a, 200L)
        assertEquals(3_800L, b.left())
        b.settle(b.reserve(500L)!!, -50L)
        assertEquals(3_800L, b.left(), "a negative time uses nothing")
    }

    /** Merge review: a scrap of budget too small for a useful read is no read at all (the stream's last price). */
    @Test fun aScrapUnderHalfASecondIsNoRead() {
        val b = ReadBudget(windowMs = 15_000L, budgetMs = 4_000L) { 0L }
        b.settle(b.reserve(3_700L)!!, 3_700L)
        assertNull(b.reserve(3_000L), "300 ms left: none")
        assertEquals(200L, b.reserve(200L)!!.ms, "a read that asks for less than the floor still gets it")
    }

    /** B5-D: reads side by side reserve before they run, so together they can never pass the budget. */
    @Test fun readsSideBySideCannotOverspend() {
        val b = ReadBudget(windowMs = 15_000L, budgetMs = 4_000L) { 0L }
        val held = java.util.Collections.synchronizedList(ArrayList<Long>())
        (1..16).map { Thread { b.reserve(1_000L)?.let { held += it.ms } } }.onEach { it.start() }.forEach { it.join() }
        assertEquals(4_000L, held.sum())
        assertEquals(4, held.size)
    }

    @Test fun aClockSetBackStartsAWindowAndALateSettleChangesNothing() {
        var now = 100_000L
        val b = ReadBudget(windowMs = 15_000L, budgetMs = 4_000L) { now }
        val old = b.reserve(4_000L)!!
        assertNull(b.reserve(1_000L))
        now = 50_000L
        assertEquals(1_000L, b.reserve(1_000L)!!.ms)
        b.settle(old, 0L)
        assertTrue(b.left() == 3_000L, "the old window's give-back is not counted in this one: ${b.left()}")
    }
}
