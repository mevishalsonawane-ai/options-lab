package com.optionslab.ira

import com.optionslab.engine.orb.ArmPriority
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The one door for new positions ([EntryGate]) and the one-index claims ([EntryHolds]) under workers running at once. */
class EntryGateTest {
    /** A pretend account: what the entries opened, and the guard "one position an index" read from it. */
    private class Account {
        val open = CopyOnWriteArrayList<String>()
        fun guard(index: String): String? = if (open.any { it.startsWith(index) }) "same_side_already_held: $index" else null
    }

    @Test fun twoWorkersEnteringAtOnceOnlyOnePassesWhenTheGuardAllowsOne() = runBlocking {
        val gate = EntryGate()
        val acct = Account()
        val bothChecked = CountDownLatch(2)
        suspend fun entry(who: String) = gate.enter("NIFTY", recheck = { acct.guard("NIFTY") }) {
            delay(50)                      // the order's round trip: the other worker is at the door meanwhile
            acct.open += "NIFTY $who"
            who
        }
        val r = listOf("ORB arms", "Pine scripts").map { w ->
            async(Dispatchers.IO) { bothChecked.countDown(); bothChecked.await(); entry(w) }
        }.awaitAll()
        assertEquals(1, r.count { it is EntryGate.Outcome.Went }, "$r")
        assertEquals(1, acct.open.size)
        val refused = r.filterIsInstance<EntryGate.Outcome.Refused>().single()
        assertEquals("same_side_already_held: NIFTY", refused.why)
        assertEquals(1L, gate.went)
        assertEquals(1L, gate.refused)
        assertEquals("same_side_already_held: NIFTY", gate.lastRefusal)
    }

    @Test fun theKillSwitchFlippedMidFlightStopsEveryEntryStillOnItsWay() = runBlocking {
        val gate = EntryGate()
        val kill = AtomicBoolean(false)
        val sent = AtomicInteger()
        val first = CompletableDeferred<Unit>()
        val firstIn = CompletableDeferred<Unit>()
        val recheck: suspend () -> String? = { if (kill.get()) "refused: the kill switch is on" else null }
        // One entry is inside the door (checked, its order on its way); three more queue behind it.
        val a = async(Dispatchers.IO) { gate.enter("NIFTY", recheck) { firstIn.complete(Unit); first.await(); "sent before the switch" } }
        firstIn.await()
        val queued = (1..3).map { i -> async(Dispatchers.IO) { gate.enter("BANKNIFTY $i", recheck) { sent.incrementAndGet() } } }
        delay(50)
        assertTrue(gate.busy)
        kill.set(true)                     // Boss flips the kill switch while they wait
        first.complete(Unit)
        assertTrue(a.await() is EntryGate.Outcome.Went)
        val r = queued.awaitAll()
        assertTrue(r.all { it == EntryGate.Outcome.Refused("refused: the kill switch is on") }, "$r")
        assertEquals(0, sent.get(), "none passed after the switch")
        // And a switch on before anything: none pass at all.
        val none = (1..4).map { async(Dispatchers.IO) { gate.enter("x", recheck) { sent.incrementAndGet() } } }.awaitAll()
        assertTrue(none.all { it is EntryGate.Outcome.Refused })
        assertEquals(0, sent.get())
    }

    @Test fun aDoorShutTooLongIsARefusalAndAnUnreadableCheckRefuses() = runBlocking {
        val gate = EntryGate(maxWaitMs = 50)
        val hold = CompletableDeferred<Unit>()
        val inside = CompletableDeferred<Unit>()
        val a = async(Dispatchers.IO) { gate.enter("a", { null }) { inside.complete(Unit); hold.await() } }
        inside.await()
        assertEquals(EntryGate.Outcome.Refused(EntryGate.BUSY), gate.enter("b", { null }) { error("never") })
        hold.complete(Unit)
        a.await()
        val r = gate.enter("c", { throw IllegalStateException("vault") }) { error("never") }
        assertEquals(EntryGate.Outcome.Refused("${EntryGate.UNCHECKED} (IllegalStateException)"), r)
        // The door is free again after a failure inside.
        runCatching { gate.enter("d", { null }) { throw java.io.IOException() } }
        assertTrue(gate.enter("e", { null }) { 1 } is EntryGate.Outcome.Went)
    }

    // ---- the one-index claims ----

    private fun held(who: String, u: String, right: String, rank: ArmPriority.Rank = ArmPriority.Rank.OTHER) =
        AutoSide.Held.option(who, "$u-$right", u, right, long = true, rank = rank)

    @Test fun twoArmsSignallingInTheSameSecondOnOneIndexOnlyOneGoes() {
        val holds = EntryHolds()
        val ok = AtomicInteger()
        val why = CopyOnWriteArrayList<String>()
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        for (t in listOf("ORB", "PINE", "SOLO", "JARVIS")) pool.execute {
            start.await()
            val r = holds.checkAndHold(t, t, "BANKNIFTY", if (t == "SOLO") -1 else 1, emptyList(), emptyMap(), now = 1_000)
            if (r == null) ok.incrementAndGet() else why += r
        }
        start.countDown()
        pool.shutdown(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals(1, ok.get(), "$why")
        assertTrue(why.all { it.startsWith(AutoSide.SAME_SIDE) || it.startsWith(AutoSide.OPPOSITE) }, "$why")
        assertTrue(why.all { "a BANKNIFTY entry being placed" in it }, "$why")
    }

    @Test fun aClaimEndsWhenBookedWhenStaleOrReleasedAndNeverCountsAgainstItsOwnTrader() {
        val holds = EntryHolds(ttlMs = 30_000)
        assertNull(holds.checkAndHold("ORB", "ORB arms", "NIFTY", 1, emptyList(), emptyMap(), now = 0))
        // Its own claim never refuses ORB itself (its own book decides that, under its own lock).
        assertNull(holds.checkAndHold("ORB", "ORB arms", "NIFTY", 1, emptyList(), emptyMap(), now = 10))
        assertEquals("same_side_already_held: ORB arms holds a NIFTY entry being placed",
            holds.checkAndHold("PINE", "Pine", "NIFTY", 1, emptyList(), emptyMap(), now = 20))
        assertEquals("opposite_position_open: ORB arms holds a NIFTY entry being placed",
            holds.checkAndHold("PINE", "Pine", "nifty", -1, emptyList(), emptyMap(), now = 20))
        assertNull(holds.checkAndHold("PINE", "Pine", "BANKNIFTY", -1, emptyList(), emptyMap(), now = 20), "another index is free")
        holds.release("PINE")
        // Booked: ORB's published positions now show the call - the claim is gone, the real position refuses instead.
        val booked = mapOf("ORB" to listOf(held("ORB", "NIFTY", "CE")))
        assertEquals("same_side_already_held: ORB holds NIFTY-CE",
            holds.checkAndHold("PINE", "Pine", "NIFTY", 1, booked.getValue("ORB"), booked, now = 30))
        assertEquals(emptyList(), holds.standing(30, booked))
        // Stale: an entry refused later (no fill) frees the index after the time limit.
        assertNull(holds.checkAndHold("SOLO", "Solo", "FINNIFTY", 1, emptyList(), emptyMap(), now = 100))
        assertTrue(holds.checkAndHold("PINE", "Pine", "FINNIFTY", 1, emptyList(), emptyMap(), now = 30_000) != null)
        assertNull(holds.checkAndHold("PINE", "Pine", "FINNIFTY", 1, emptyList(), emptyMap(), now = 30_101))
        // Released at once by its trader.
        assertNull(holds.checkAndHold("JARVIS", "Jarvis", "SENSEX", 1, emptyList(), emptyMap(), now = 40_000))
        holds.release("JARVIS", "sensex")
        assertNull(holds.checkAndHold("ORB", "ORB arms", "SENSEX", 1, emptyList(), emptyMap(), now = 40_001))
        // A neutral entry is never refused and claims nothing.
        assertNull(holds.checkAndHold("STRATEGIES", "Strategies", "MIDCPNIFTY", 0, emptyList(), emptyMap(), now = 50_000))
        assertTrue(holds.standing(50_000).none { it.underlying == "MIDCPNIFTY" })
        holds.clear()
        assertEquals(emptyList(), holds.standing(50_000))
    }

    @Test fun liquiditysPriorityStillHoldsForClaims() {
        val holds = EntryHolds()
        // An ORB-family claim does not stop a paper Liquidity entry (it runs beside it), as a real ORB position would not.
        assertNull(holds.checkAndHold("ORB", "ORB", "BANKNIFTY", 1, emptyList(), emptyMap(), now = 0, rank = ArmPriority.Rank.OLD_ARM))
        assertNull(holds.checkAndHold("SHADOW", "Liquidity shadow", "BANKNIFTY", 1, emptyList(), emptyMap(), now = 1, rank = ArmPriority.Rank.LIQUIDITY))
        // A Liquidity claim still stops an ORB-family entry by another trader.
        val r = holds.checkAndHold("PINE", "Pine", "BANKNIFTY", 1, emptyList(), emptyMap(), now = 2, rank = ArmPriority.Rank.OLD_ARM)
        assertTrue(r != null && r.startsWith(AutoSide.SAME_SIDE), "$r")
        // A clock set back drops what it cannot age.
        assertEquals(emptyList(), holds.standing(-100_000))
    }
}
