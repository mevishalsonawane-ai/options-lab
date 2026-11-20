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
        repeat(8) { assertEquals(0L, g.tryTake(RateGate.Priority.EXIT), "request $it"); now += 10 }
        // The first left at 1_000_000: the ninth fits at 1_001_000, 920 ms from now.
        assertEquals(920L, g.tryTake(RateGate.Priority.EXIT))
        now += 920
        assertEquals(0L, g.tryTake(RateGate.Priority.EXIT))
        assertEquals(9L, g.taken)
    }

    @Test fun overAnyRollingSecondNoMoreThanTheBudget() {
        var now = 0L
        val g = RateGate(perSecond = 8) { now }
        val sent = ArrayList<Long>()
        // Ten seconds of callers asking every 7 ms.
        while (now < 10_000) { if (g.tryTake(RateGate.Priority.EXIT) == 0L) sent += now; now += 7 }
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
        assertEquals(0L, g.tryTake(RateGate.Priority.ENTRY))
        assertTrue(g.tryTake(RateGate.Priority.ENTRY) > 0, "entry orders and ordinary reads leave two")
        assertEquals(0L, g.tryTake(RateGate.Priority.SAFETY))
        assertTrue(g.tryTake(RateGate.Priority.SAFETY) > 0, "the stop checks' reads leave one")
        // 20 Nov 2026 (audit B5, item 3): the last request of the second is the exits' alone (exits, stops, stop moves, cancels).
        assertEquals(0L, g.tryTake(RateGate.Priority.EXIT), "an exit takes the reserved one")
        assertTrue(g.tryTake(RateGate.Priority.EXIT) > 0, "and never more than the budget")
        // A clock set back by more than the window starts it again (never stuck for the size of the jump).
        now = -60_000
        assertEquals(0L, g.tryTake(RateGate.Priority.SAFETY))
        // Never below one a second, however small the budget.
        val tiny = RateGate(perSecond = 2) { now }
        assertEquals(0L, tiny.tryTake(RateGate.Priority.ANALYSIS))
        assertTrue(tiny.tryTake(RateGate.Priority.ANALYSIS) > 0)
    }

    /**
     * 20 Nov 2026 (audit B5, item 3): a second filled by entry orders and reads still lets exits through (two above the entries, one
     * above the stop checks' reads), and a waiting exit's slot opens before any lower lane's (nothing of a lower lane is taken in between).
     */
    @Test fun aSecondFullOfReadsAndEntriesStillLetsExitsThroughFirst() {
        var now = 0L
        val g = RateGate(perSecond = 8) { now }
        repeat(6) { assertEquals(0L, g.tryTake(RateGate.Priority.ENTRY)); now += 10 }
        assertTrue(g.tryTake(RateGate.Priority.ENTRY) > 0)
        assertEquals(0L, g.tryTake(RateGate.Priority.EXIT)); assertEquals(0L, g.tryTake(RateGate.Priority.EXIT))
        // The second is full: each lane is told when its own slot opens - the exit's first.
        val exit = g.tryTake(RateGate.Priority.EXIT)
        val safety = g.tryTake(RateGate.Priority.SAFETY)
        val entry = g.tryTake(RateGate.Priority.ENTRY)
        assertTrue(exit in 1..safety - 1, "exit $exit before safety $safety")
        assertTrue(safety < entry, "safety $safety before entry $entry")
        now += exit
        assertTrue(g.tryTake(RateGate.Priority.SAFETY) > 0, "a read never takes the exit's slot")
        assertEquals(0L, g.tryTake(RateGate.Priority.EXIT))
        // Never more than the budget in any second.
        assertTrue(g.tryTake(RateGate.Priority.EXIT) > 0)
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
        // 6 a second for ordinary reads: 20 requests take at least three full seconds.
        assertTrue(took >= 3_000, "$took ms")
        assertTrue(g.waited >= 1)
        assertEquals(20L, g.taken)
    }

    @Test fun aWaitingHigherLaneIsServedBeforeALowerOneTakesASlot() = runBlocking {
        val now = java.util.concurrent.atomic.AtomicLong(0L)
        val g = RateGate(perSecond = 8) { now.get() }
        // (Since the EXIT lane: the stop checks' reads leave it one a second - 7 of them, then an exit takes the last slot.)
        repeat(7) { assertEquals(0L, g.tryTake(RateGate.Priority.SAFETY)) }
        assertEquals(0L, g.tryTake(RateGate.Priority.EXIT))
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
        repeat(6) { assertEquals(0L, g.tryTake(RateGate.Priority.ENTRY)) }
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

    /** Review, 20 Nov 2026: a read never queues without end - it gives up when its own wait runs out, nothing taken. */
    @Test fun aBoundedWaitGivesUpWithoutTakingASlot() = runBlocking {
        val g = RateGate(perSecond = 1, windowMs = 1_100L)
        assertTrue(g.acquireWithin(RateGate.Priority.ENTRY, 0L), "a free slot at once")
        val t0 = System.nanoTime()
        assertEquals(false, g.acquireWithin(RateGate.Priority.ENTRY, 200L))
        val took = (System.nanoTime() - t0) / 1_000_000
        assertTrue(took in 150..1_000, "gave up after its 200 ms: $took ms")
        assertEquals(0, g.waitingFor(RateGate.Priority.ENTRY), "no longer counted as waiting")
        assertEquals(1L, g.taken, "nothing taken")
        assertTrue(g.acquireWithin(RateGate.Priority.SAFETY, 3_000L), "a longer wait gets the next window's slot")
        assertEquals(2L, g.taken)
    }

    /** B5-D: several paper reads waiting in the entries' lane never hold a live stop's read, which asks later but goes first. */
    @Test fun aStopReadAskingAfterSeveralEntryReadsGoesFirst() = runBlocking {
        val g = RateGate(perSecond = 1, windowMs = 1_000L)
        assertTrue(g.acquireWithin(RateGate.Priority.ENTRY, 0L))
        val order = java.util.Collections.synchronizedList(ArrayList<String>())
        val papers = (1..3).map { i -> async(Dispatchers.IO) { g.acquire(RateGate.Priority.ENTRY); order += "paper$i" } }
        val t0 = System.nanoTime()
        while (g.waitingFor(RateGate.Priority.ENTRY) < 3) { assertTrue(System.nanoTime() - t0 < 5_000_000_000L); Thread.sleep(2) }
        assertEquals(false, g.waitingAbove(RateGate.Priority.ENTRY))
        val stop = async(Dispatchers.IO) { g.acquire(RateGate.Priority.SAFETY); order += "stop" }
        while (g.waitingFor(RateGate.Priority.SAFETY) == 0 && order.isEmpty()) Thread.sleep(1)
        stop.await(); papers.awaitAll()
        assertEquals("stop", order.first(), "$order")
        assertEquals(false, g.waitingAbove(RateGate.Priority.EXIT), "nothing above the top lane")
    }

    /** B5 merge review: a slot whose request never went (its quote turn ran out) is given back - exactly that one. */
    @Test fun aSlotNeverSentIsGivenBackAndOnlyThatOne() = runBlocking {
        var now = 0L
        val g = RateGate(perSecond = 2, clock = { now })
        val first = g.acquireStamp(RateGate.Priority.EXIT, 0L)!!
        now = 400L
        val second = g.acquireStamp(RateGate.Priority.EXIT, 0L)!!
        assertEquals(0L, first); assertEquals(400L, second)
        assertTrue(g.tryTake(RateGate.Priority.EXIT) > 0, "full")
        g.giveBack(first)
        assertEquals(1L, g.taken)
        assertEquals(0L, g.tryTake(RateGate.Priority.EXIT), "the given-back slot is free again")
        // The kept one (400) still counts until 1 400: never a later one's slot freed by a give-back.
        now = 1_100L
        assertTrue(g.tryTake(RateGate.Priority.EXIT) > 0)
        g.giveBack(12_345L)                                                     // not a slot: nothing changes
        assertEquals(2L, g.taken)
        assertEquals(null, g.acquireStamp(RateGate.Priority.EXIT, 0L), "full, no time to wait: nothing taken")
        assertEquals(2L, g.taken)
    }

    /**
     * B5 merge review: the lost replies' searches read in SAFETY. Under sustained ENTRY and ANALYSIS load (callers asking all the time),
     * a SAFETY read still gets its slot within a second; an ANALYSIS one could wait as long as the load lasts.
     */
    @Test fun aSafetyReadGetsItsSlotUnderSustainedEntryLoad() = runBlocking {
        val g = RateGate(perSecond = 8)
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val load = (1..6).map { async(Dispatchers.IO) { while (!stop.get()) g.acquire(if (it % 2 == 0) RateGate.Priority.ENTRY else RateGate.Priority.ANALYSIS) } }
        Thread.sleep(1_200)
        val t0 = System.nanoTime()
        val got = g.acquireStamp(RateGate.Priority.SAFETY, 3_000L)
        val took = (System.nanoTime() - t0) / 1_000_000
        stop.set(true); load.awaitAll()
        assertTrue(got != null && took < 1_500, "the search's read went in $took ms")
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
