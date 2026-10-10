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
}
