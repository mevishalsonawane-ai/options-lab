package com.optionslab.app.data

import com.optionslab.app.ira.Automations
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.KiteTicks
import com.optionslab.ira.GammaMorning
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode
import java.time.LocalDate
import java.time.ZoneId

/**
 * Jarvis's morning gamma line ([GammaMorningHost], 14 Oct 2026): read from the chain already in memory (the stream's
 * ticks), never fetched; a stale chain or spot, or an index whose chain is not on the phone, gives no reading; the answer
 * says the reading's time and that it is information only; its switch is its own under Market alerts.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class GammaMorningHostTest : RobolectricTest() {
    private lateinit var kite: FakeKite
    private val zone = ZoneId.of("Asia/Kolkata")
    private val day = LocalDate.of(2026, 10, 13)
    private val expiry = LocalDate.of(2026, 10, 20)
    private val bnToken = 260_105L
    private val strikes = (-12..12).map { 55_000.0 + it * 100 }

    private fun tokenOf(strike: Double, right: String) = 24_000_000L + (strike / 100).toLong() * 2 + if (right == "CE") 0 else 1

    @Before fun up() {
        Market.testClock = java.time.Clock.fixed(day.atTime(10, 0, 30).atZone(zone).toInstant(), zone)
        kite = FakeKite()
        kite.keepAlive = true
        kite.login()
        for (k in strikes) for (r in listOf("CE", "PE"))
            kite.instruments += FakeKite.Ins(tokenOf(k, r), "BANKNIFTY26OCT${k.toInt()}$r", "BANKNIFTY", expiry, k, r, 35)
        runBlocking { Broker.instruments() }
        KiteStream.resetForTest()
        GammaMorningHost.resetForTest()
    }

    @After fun down() {
        GammaMorningHost.resetForTest()
        Market.testClock = null
        KiteStream.stop()
        KiteStream.resetForTest()
        kite.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    /** BANKNIFTY at [spot] and its chain priced at 14% IV, puts' OI [putOi] against calls' 1 lakh, as ticks reaching the phone at [atMs]. */
    private fun feed(atMs: Long, spot: Double = 55_020.0, putOi: Long = 300_000, optionsAtMs: Long = atMs) {
        val t = GammaMorning.years(day.atTime(10, 0), expiry)!!
        KiteStream.feedForTest(listOf(KiteTicks.Tick(bnToken, spot)), atMs)
        KiteStream.feedForTest(strikes.flatMap { k ->
            listOf(KiteTicks.Tick(tokenOf(k, "CE"), GammaMorning.price(spot, k, t, 0.14, true), oi = 100_000, volume = 50_000),
                KiteTicks.Tick(tokenOf(k, "PE"), GammaMorning.price(spot, k, t, 0.14, false), oi = putOi, volume = 50_000))
        }, optionsAtMs)
    }

    @Test fun readsTheChainInMemoryAtTen() {
        val now = System.currentTimeMillis()
        feed(now)
        val r = GammaMorningHost.readOne("BANKNIFTY", day.atTime(10, 0), now)
        assertNotNull(r)
        r!!
        assertTrue("puts heavier: negative", r.negative)
        assertEquals(42, r.contracts)
        assertNotNull(r.callWall); assertNotNull(r.putWall)
        // Only the indices whose chain is on the phone.
        assertEquals(listOf("BANKNIFTY"), GammaMorningHost.readNow(day.atTime(10, 0), now).map { it.index })
        assertNull(GammaMorningHost.readOne("FINNIFTY", day.atTime(10, 0), now))
        // The memory chain carries each option's OI and the day's volume, and the stream's tokens.
        val snap = MemoryChain.read("BANKNIFTY", now)
        assertEquals(50, snap.legs.size)
        assertTrue(snap.legs.all { it.oi != null && it.cumVol == 50_000L && it.token != null })
        assertEquals(35, snap.lot)
        assertEquals(expiry, snap.expiry)
    }

    @Test fun aStaleChainOrSpotGivesNoReading() {
        val now = System.currentTimeMillis()
        // Options last traded 31 minutes ago: no price is fresh enough for an IV.
        feed(now, optionsAtMs = now - 31 * 60_000L)
        assertNull(GammaMorningHost.readOne("BANKNIFTY", day.atTime(10, 0), now))
        // Spot 6 minutes old: not today's 10:00 price.
        KiteStream.resetForTest()
        feed(now - 6 * 60_000L, optionsAtMs = now)
        assertNull(GammaMorningHost.readOne("BANKNIFTY", day.atTime(10, 0), now))
        // Nothing on the phone at all.
        KiteStream.resetForTest()
        assertNull(GammaMorningHost.readOne("BANKNIFTY", day.atTime(10, 0), now))
        assertTrue(GammaMorningHost.readNow(day.atTime(10, 0), now).isEmpty())
    }

    @Test fun theAnswerIsLabelledInformationOnly() {
        feed(System.currentTimeMillis())
        val a = GammaMorningHost.answer("BANKNIFTY")
        assertTrue(a, a.startsWith("From the chain on the phone at 10:00: Dealer gamma is negative this morning on BankNifty."))
        assertTrue(a, a.endsWith(GammaMorning.INFO))
        assertFalse(a, Regex("Rs|₹|crore").containsMatchIn(a))
        val none = GammaMorningHost.answer("NIFTY")
        assertTrue(none, "no gamma reading for Nifty" in none)
        // Before the 10:00 line is made, the diagnostics say there is none.
        assertEquals("gamma 10:00: no reading", GammaMorningHost.diagLine(day))
    }

    @Test fun itsSwitchIsItsOwnUnderMarketAlertsAndOnByDefault() {
        assertTrue(Automations.isSub(Automations.Auto.GAMMA))
        assertEquals(Automations.Group.MARKET, Automations.groupOf(Automations.Auto.GAMMA))
        assertTrue(Automations.Auto.GAMMA.byDefault)
        assertTrue("information only" in Automations.Auto.GAMMA.what && "never a trade signal" in Automations.Auto.GAMMA.what)
        assertTrue(Automations.isSub(Automations.Auto.OIWALL) && Automations.Auto.OIWALL.byDefault)
        assertTrue("Never an order" in Automations.Auto.OIWALL.what)
    }
}
