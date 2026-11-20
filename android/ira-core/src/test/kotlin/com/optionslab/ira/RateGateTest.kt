package com.optionslab.ira

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** One budget for Zerodha's REST API ([RateGate]): at most 8 in any second, exits never queued behind analysis. */
class RateGateTest {
    @Test fun eightInASecondThenTheNinthWaits() {
        var now = 1_000_000L
        val g = RateGate(perSecond = 8) { now }
        repeat(8) { assertEquals(0L, g.tryTake(RateGate.Priority.SAFETY), "request $it"); now += 10 }
        // The first left at 1_000_000: the ninth fits at 1_001_000, 920 ms from now.
        assertEquals(920L, g.tryTake(RateGate.Priority.SAFETY))
        now += 920
        assertEquals(0L, g.tryTake(RateGate.Priority.SAFETY))
        assertEquals(9L, g.taken)
    }

    @Test fun overAnyRollingSecondNoMoreThanTheBudget() {
        var now = 0L
        val g = RateGate(perSecond = 8) { now }
        val sent = ArrayList<Long>()
        // Ten seconds of callers asking every 7 ms.
        while (now < 10_000) { if (g.tryTake(RateGate.Priority.SAFETY) == 0L) sent += now; now += 7 }
        for (t in sent) {
            val n = sent.count { it in t until t + 1_000 }
            assertTrue(n <= 8, "at $t: $n in a second")
        }
        assertTrue(sent.size in 75..80, "${sent.size}")
    }

    @Test fun theLowerLanesLeaveRoomForExits() {
        var now = 0L
        val g = RateGate(perSecond = 8) { now }
        repeat(5) { assertEquals(0L, g.tryTake(RateGate.Priority.ANALYSIS)) }
        assertTrue(g.tryTake(RateGate.Priority.ANALYSIS) > 0, "analysis leaves three")
        repeat(2) { assertEquals(0L, g.tryTake(RateGate.Priority.ENTRY)) }
        assertTrue(g.tryTake(RateGate.Priority.ENTRY) > 0, "entries leave one")
        assertEquals(0L, g.tryTake(RateGate.Priority.SAFETY), "an exit takes the last")
        assertTrue(g.tryTake(RateGate.Priority.SAFETY) > 0)
        // A clock set back by more than the window starts it again (never stuck for the size of the jump).
        now = -60_000
        assertEquals(0L, g.tryTake(RateGate.Priority.SAFETY))
        // A tiny budget still lets each lane send one a second.
        val tiny = RateGate(perSecond = 2) { now }
        assertEquals(0L, tiny.tryTake(RateGate.Priority.ANALYSIS))
        assertTrue(tiny.tryTake(RateGate.Priority.ANALYSIS) > 0)
    }

    @Test fun workersWaitBySuspendingAndNeverPassTheBudget() = runBlocking {
        val g = RateGate(perSecond = 8)
        val at = java.util.Collections.synchronizedList(ArrayList<Long>())
        val t0 = System.nanoTime() / 1_000_000
        (1..4).map {
            async(Dispatchers.IO) { repeat(5) { g.acquire(RateGate.Priority.ENTRY); at += System.nanoTime() / 1_000_000 } }
        }.awaitAll()
        val took = System.nanoTime() / 1_000_000 - t0
        assertEquals(20, at.size)
        // 7 a second for entries: 20 requests take at least two full seconds.
        assertTrue(took >= 2_000, "$took ms")
        assertTrue(g.waited >= 1)
        assertEquals(20L, g.taken)
    }

    @Test fun aWaitingHigherLaneIsServedBeforeALowerOneTakesASlot() = runBlocking {
        val now = java.util.concurrent.atomic.AtomicLong(0L)
        val g = RateGate(perSecond = 8) { now.get() }
        repeat(8) { assertEquals(0L, g.tryTake(RateGate.Priority.SAFETY)) }
        val stop = async(Dispatchers.IO) { g.acquire(RateGate.Priority.SAFETY) }
        // The stop's read queues in the gate (the budget is full).
        val t0 = System.nanoTime()
        while (g.waitingFor(RateGate.Priority.SAFETY) == 0) {
            assertTrue(System.nanoTime() - t0 < 5_000_000_000L, "the safety read never queued")
            Thread.sleep(2)
        }
        // The second ends: a slot is free, but an entry read asking now leaves it to the waiting stop's read.
        now.set(1_000L)
        assertEquals(20L, g.tryTake(RateGate.Priority.ENTRY))
        assertEquals(20L, g.tryTake(RateGate.Priority.ANALYSIS))
        stop.await()
        assertEquals(0, g.waitingFor(RateGate.Priority.SAFETY))
        assertEquals(9L, g.taken, "the stop's read took the freed slot")
        // With nobody waiting any more, the entry read gets in.
        assertEquals(0L, g.tryTake(RateGate.Priority.ENTRY))
    }

    @Test fun aLowerLaneYieldsOnlyWhileAHigherOneWaits() {
        var now = 0L
        val g = RateGate(perSecond = 8) { now }
        // Nobody waiting: the reserve rule alone, exactly as before.
        repeat(7) { assertEquals(0L, g.tryTake(RateGate.Priority.ENTRY)) }
        assertTrue(g.tryTake(RateGate.Priority.ENTRY) > 0)
        now += 1_000
        assertEquals(0L, g.tryTake(RateGate.Priority.ANALYSIS))
        assertEquals(0, g.waitingFor(RateGate.Priority.SAFETY))
    }

    /** 20 Nov 2026 (review): Zerodha's quote limit (one a second) as its own gate, its window a little over a second. */
    @Test fun theQuoteGateLetsOneThroughPerWindowEveryLaneAlike() {
        var now = 0L
        val g = RateGate(perSecond = 1, clock = { now }, windowMs = 1_100L)
        assertEquals(0L, g.tryTake(RateGate.Priority.ANALYSIS), "the only lane asking gets the slot")
        assertEquals(1_100L, g.tryTake(RateGate.Priority.SAFETY))
        now = 1_000
        assertEquals(100L, g.tryTake(RateGate.Priority.SAFETY), "not yet: the window is 1.1 s")
        now = 1_100
        assertEquals(0L, g.tryTake(RateGate.Priority.ENTRY))
        // Over a minute of callers asking every 7 ms: never two inside 1.1 s.
        val sent = ArrayList<Long>()
        while (now < 61_100) { if (g.tryTake(RateGate.Priority.SAFETY) == 0L) sent += now; now += 7 }
        sent.zipWithNext().forEach { (a, b) -> assertTrue(b - a >= 1_100, "$a then $b") }
        assertTrue(sent.size in 50..55, "${sent.size}")
        // A clock set back by more than the window starts it again.
        now = -60_000
        assertEquals(0L, g.tryTake(RateGate.Priority.ANALYSIS))
    }

    @Test fun onTheQuoteGateAWaitingStopReadGoesBeforeAnEntryRead() = runBlocking {
        val now = java.util.concurrent.atomic.AtomicLong(0L)
        val g = RateGate(perSecond = 1, clock = { now.get() }, windowMs = 1_100L)
        assertEquals(0L, g.tryTake(RateGate.Priority.ENTRY))
        val stop = async(Dispatchers.IO) { g.acquire(RateGate.Priority.SAFETY) }
        val t0 = System.nanoTime()
        while (g.waitingFor(RateGate.Priority.SAFETY) == 0) {
            assertTrue(System.nanoTime() - t0 < 5_000_000_000L, "the stop read never queued")
            Thread.sleep(2)
        }
        now.set(1_100L)
        assertEquals(20L, g.tryTake(RateGate.Priority.ENTRY), "the entry read leaves the slot to the stop's")
        stop.await()
        assertTrue(g.tryTake(RateGate.Priority.ENTRY) > 0, "the stop read took this window's one slot")
    }
}
