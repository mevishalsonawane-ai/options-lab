package com.optionslab.app.data

import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.Kite
import com.optionslab.ira.AutoSide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The watch's workers in parallel (10 Oct): the one entry door in [Broker.placeOrder] (the kill switch and the bots' day
 * guards read again at the last moment; exits never stopped) and the one-index claims in [AutoExposure.check] (two arms
 * signalling in the same second on one index: only one goes).
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class EntryDoorTest : RobolectricTest() {
    private lateinit var kite: FakeKite
    private fun order(tag: String = "iraorb", sym: String = "NIFTY26OCT24500CE") =
        Kite.Order(sym, Kite.Side.BUY, 75, 75, "MIS", "MARKET", null, 0.05, "NFO", tag)

    @Before fun up() { kite = FakeKite(); kite.login() }
    @After fun down() { kite.close(); assertEquals(emptyList<String>(), NetworkGuard.blocked.toList()) }

    @Test fun theKillSwitchFlippedBeforeTheDoorStopsAnEntryButNeverAnExit() = runBlocking {
        // The arm checked its gates a moment ago; Boss flips the kill switch before its order reaches the door.
        AppSettings.save(AppSettings.load().copy(guardKill = true))
        try { Broker.placeOrder(order()); fail("an entry went through the kill switch") } catch (e: Broker.KiteError) {
            assertEquals("entry_gate", e.type)
            assertTrue(e.message, e.message!!.contains("kill switch"))
            assertTrue("a refusal at the door is definite: nothing was sent", Broker.definite(e))
        }
        assertTrue("nothing sent", kite.placed.isEmpty())
        // Boss's own order (another tag) is stopped too: the kill switch stops every new position.
        try { Broker.placeOrder(order(tag = "iraalgo")); fail() } catch (e: Broker.KiteError) { assertEquals("entry_gate", e.type) }
        // An exit always goes.
        Broker.placeOrder(order().copy(side = Kite.Side.SELL), exit = true)
        assertEquals(1, kite.placed.size)
        assertTrue(Broker.sharingLine(), Broker.sharingLine().contains("entry door:"))
    }

    @Test fun theDailyLossLimitAndTheDayLockStopTheBotsEntriesAtTheDoorNotBosss() = runBlocking {
        SecurePrefs.put("breaker.day", Market.today().toString())
        try { Broker.placeOrder(order(tag = "irapine")); fail() } catch (e: Broker.KiteError) {
            assertTrue(e.message, e.message!!.contains("daily loss limit"))
        }
        assertTrue(kite.placed.isEmpty())
        // Boss's own order is his call (the loss limit stops the bots).
        Broker.placeOrder(order(tag = "iraalgo"))
        assertEquals(1, kite.placed.size)
    }

    @Test fun entriesFromTwoWorkersAtOnceGoOneAtATimeAndAllArrive() = runBlocking {
        val r = (1..4).map { i -> async(Dispatchers.IO) { Broker.placeOrder(order(sym = "NIFTY26OCT2450${i}CE")) } }.awaitAll()
        assertEquals(4, r.distinct().size)
        assertEquals(4, kite.placed.size)
    }

    // ---- the one-index claims ----

    @Test fun twoArmsSignallingInTheSameSecondOnOneIndexOnlyOneGoes() {
        val ok = AtomicInteger()
        val why = java.util.concurrent.CopyOnWriteArrayList<String>()
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        // ORB's Liquidity-less arm and a Pine script both see a BANKNIFTY call signal in the same second: neither has booked.
        for (src in listOf(AutoExposure.Source.ORB, AutoExposure.Source.PINE)) pool.execute {
            start.await()
            val r = AutoExposure.check(src, "BANKNIFTY", AutoSide.direction("CE", true), emptyList())
            if (r == null) ok.incrementAndGet() else why += r
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        assertEquals(why.toString(), 1, ok.get())
        assertTrue(why.single(), why.single().startsWith(AutoSide.SAME_SIDE) && why.single().contains("a BANKNIFTY entry being placed"))
        // The other way on the same index is refused as well; another index is free; the claim holder itself is not refused.
        val other = if (why.single().contains("ORB arms")) AutoExposure.Source.PINE else AutoExposure.Source.ORB
        val holder = if (other == AutoExposure.Source.PINE) AutoExposure.Source.ORB else AutoExposure.Source.PINE
        assertTrue(AutoExposure.check(other, "BANKNIFTY", -1, emptyList())!!.startsWith(AutoSide.OPPOSITE))
        assertNull(AutoExposure.check(other, "NIFTY", 1, emptyList()))
        assertNull(AutoExposure.check(holder, "BANKNIFTY", 1, emptyList()))
        // Its entry refused later: released, the index is free for the other at once.
        AutoExposure.release(holder.name, "BANKNIFTY")
        assertNull(AutoExposure.check(other, "BANKNIFTY", 1, emptyList()))
        assertTrue(AutoExposure.claims().isNotEmpty())
    }

    @Test fun aReArmedShadowAndTheOrbArmsClaimApartThoughTheyShareOrbsRefusals() {
        assertNull(AutoExposure.check(AutoExposure.Source.ORB, "NIFTY", 1, emptyList()))
        val r = AutoExposure.check(AutoExposure.Source.ORB, "NIFTY", 1, emptyList(), trader = AutoExposure.SHADOW)
        assertTrue("$r", r != null && r.contains("ORB arms holds a NIFTY entry being placed"))
    }

    @Test fun theDayLockStillComesFirst() {
        SecurePrefs.put("breaker.daylock.paper", com.optionslab.engine.risk.DayLock.encode(
            com.optionslab.engine.risk.DayLock.Reached(Market.today(), java.time.LocalTime.of(11, 0), 9_000.0, 8_000.0)))
        AppSettings.save(AppSettings.load().copy(dayLock = 8_000.0))
        val r = AutoExposure.check(AutoExposure.Source.PINE, "NIFTY", 1, emptyList(), account = false)
        assertTrue("$r", r != null && !r.startsWith(AutoSide.SAME_SIDE))
        assertTrue("a refused entry claims nothing", AutoExposure.claims().isEmpty())
    }
}
