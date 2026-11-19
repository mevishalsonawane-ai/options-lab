package com.optionslab.app.data

import com.optionslab.app.testing.RobolectricTest
import com.optionslab.ira.Auction
import com.optionslab.ira.OrderFlow
import com.optionslab.ira.TradeManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 19 Nov 2026 (audit batch 2, second review): a PAPER trade whose live copy follows its paper exits (Solo here, acting on paper by
 * default) is looked at as in SHADOW while its family's live mode is not ACT: the manager's early exit is recorded as a would-be exit
 * (not acted, priced as the counterfactual), no exit is asked and nothing says it exited. With no live copy it acts as before.
 */
class ManagerLiveCopyTest : RobolectricTest() {
    private val t0 = 1_760_000_000_000L

    @Before fun up() {
        SmartWorkers.resetForTest()
        TradeManagerHost.resetForTest()
        TradeManagerHost.testNoWake = true
        TradeManagerHost.testBars = { emptyList() }
    }

    @After fun down() { TradeManagerHost.resetForTest() }

    private fun trade(id: String) = TradeManager.ManagedTrade("solo:1", id, "Solo · midday", "NIFTY", 1, "NIFTY25OCT25000CE",
        100.0, 75, 70.0, 160.0, TradeManager.Caps(t0 + 3 * 3_600_000L), TradeManager.Account.PAPER, t0, 1.0)

    private fun sellers(at: Long): TradeManager.Snapshot {
        val r = OrderFlow.Read(name = "NIFTY", atSec = at / 1000, side = OrderFlow.Side.SELLERS, strength = 70, buyers = 30, warm = true,
            mid = 24_990.0, ofi10 = 0.0, ofi60 = 0.0, ofi300 = 0.0, cvd10 = 0.0, cvd60 = -5_000.0, cvd300 = 0.0, depth = 0.0, queue = 0.0,
            ratio = Double.NaN, ce60 = 0.0, pe60 = 0.0, optionNet = Double.NaN, buildUp = null, oiChange = null, z = emptyMap())
        return TradeManager.Snapshot(at, 120.0, flow = r, futPrice = 24_990.0, vwap = Auction.Vwap(25_000.0, 20.0, null, null),
            valueHigh = 25_010.0, valueLow = 24_980.0)
    }

    /** [copy]: a live copy assumed or known ([known]: ArmLive's book read with a real copy open). */
    private fun runTo(id: String, copy: Boolean, known: Boolean = copy, from: Int = 0, to: Int = 61, attach: Boolean = true): TradeManager.Record {
        TradeManagerHost.liveCopyOf = { copy }
        TradeManagerHost.liveCopyKnownOf = { known }
        if (attach) assertEquals(TradeManager.Mode.ACT, TradeManagerHost.attach(TradeManagerHost.Registration(trade(id), premium = { 120.0 })))
        TradeManagerHost.testSnapshot = { _, at -> sellers(at) }
        for (i in from..to) TradeManagerHost.evaluate(t0 + i * 1000L)
        return TradeManagerHost.records.value.single { it.trade.tradeId == id }
    }

    @Test fun anExitHeldForAKnownLiveCopyIsRecordedAsAWouldBeExitNeverActed() {
        val r = runTo("solo:1:held", copy = true)
        assertEquals("a known copy, nothing acted yet: a SHADOW record from here", TradeManager.Mode.SHADOW, r.mode)
        val exit = r.notes.single { it.kind == TradeManager.EXIT_EARLY }
        assertFalse("not acted", exit.acted)
        assertNull("no exit asked of the strategy", TradeManagerHost.exitDue("solo:1:held"))
        assertTrue("priced as the counterfactual", r.manager != null)
    }

    /**
     * 19 Nov 2026 (audit batch 2, delta review): a copy only ASSUMED (the Live setting unknown, ArmLive's book not read) holds this look's
     * exit, noted once, but leaves the record ACT and nothing acted; once it is known there is no copy, the next look acts.
     */
    @Test fun anAssumedCopyHoldsTheLookButNeverTurnsTheRecordToShadow() {
        val held = runTo("solo:1:unknown", copy = true, known = false)
        assertEquals("still ACT: decided again on the next look", TradeManager.Mode.ACT, held.mode)
        assertNull("nothing acted", held.actedAtMs)
        assertNull(held.manager)
        assertTrue("no exit recorded", held.notes.none { it.kind == TradeManager.EXIT_EARLY })
        assertEquals("noted once while held", 1, held.notes.count { it.kind == TradeManagerHost.HELD_NOTE })
        assertFalse(held.notes.single { it.kind == TradeManagerHost.HELD_NOTE }.acted)
        assertNull(TradeManagerHost.exitDue("solo:1:unknown"))
        // Known now: no live copy - the next looks act.
        val freed = runTo("solo:1:unknown", copy = false, known = false, from = 62, to = 130, attach = false)
        assertEquals(TradeManager.Mode.ACT, freed.mode)
        assertTrue(freed.notes.single { it.kind == TradeManager.EXIT_EARLY }.acted)
        assertEquals("the trade manager: sellers took over", TradeManagerHost.exitDue("solo:1:unknown"))
    }

    /**
     * 19 Nov 2026 (audit batch 2, delta review): a trade the manager already acted on (a lock rested) keeps its ACT record even with a
     * known live copy - its levels and tally stay its own - and only its further exits are held, look by look.
     */
    @Test fun aTradeAlreadyActedOnKeepsItsActRecordAndOnlyItsExitsAreHeld() {
        TradeManagerHost.recordsForTest(listOf(TradeManager.Record(trade("solo:1:acted"), TradeManager.Mode.ACT, lock = 105.0, actedAtMs = t0)))
        val r = runTo("solo:1:acted", copy = true, known = true)
        assertEquals("never switched: it already acted", TradeManager.Mode.ACT, r.mode)
        assertEquals(t0, r.actedAtMs)
        assertTrue(r.notes.none { it.kind == TradeManager.EXIT_EARLY })
        assertEquals(1, r.notes.count { it.kind == TradeManagerHost.HELD_NOTE })
        assertNull("its exit held", TradeManagerHost.exitDue("solo:1:acted"))
    }

    @Test fun withNoLiveCopyTheSameExitActsAsBefore() {
        val r = runTo("solo:1:free", copy = false)
        assertTrue(r.notes.single { it.kind == TradeManager.EXIT_EARLY }.acted)
        assertEquals("the trade manager: sellers took over", TradeManagerHost.exitDue("solo:1:free"))
    }

    @Test fun anUnknownLiveSettingBeforeArmLivesBookIsReadCountsAsALiveCopy() {
        // ArmLive's book not read: with the Live setting not kept in memory, a copy is assumed (nothing sold by the back door).
        assertTrue(TradeManagerHost.liveCopyFrom(loaded = false, appLiveKept = null, held = false))
        assertTrue(TradeManagerHost.liveCopyFrom(loaded = false, appLiveKept = true, held = false))
        assertFalse("Paper: ArmLive's book may never be read there", TradeManagerHost.liveCopyFrom(loaded = false, appLiveKept = false, held = false))
        assertTrue(TradeManagerHost.liveCopyFrom(loaded = true, appLiveKept = null, held = true))
        assertFalse(TradeManagerHost.liveCopyFrom(loaded = true, appLiveKept = true, held = false))
        // A KNOWN copy is never assumed: ArmLive's book not read - none known (the record never goes SHADOW for good on a guess).
        ArmLive.wipe()
        if (!ArmLive.view.value.loaded) assertFalse(TradeManagerHost.armLiveCopyKnown("NIFTY25OCT25000CE"))
        kotlinx.coroutines.runBlocking { ArmLive.refresh() }
        assertTrue(ArmLive.view.value.loaded)
        assertFalse("read, and no live trade copies it", TradeManagerHost.armLiveCopyKnown("NIFTY25OCT25000CE"))
        assertFalse("read, and no live trade copies it", TradeManagerHost.armLiveCopy(paperSymbol = "NIFTY25OCT25000CE"))
    }

    /**
     * 19 Nov 2026 (audit batch 2): a live copy Boss detached from its paper trade (his paper reset or hand close: [ArmLive.Pos.detached]) no
     * longer follows paper exits, so it never counts as the paper trade's live copy here; a following one does.
     */
    @Test fun aDetachedLiveCopyNeverCountsAsTheLiveCopy() {
        val copy = ArmLive.Pos(com.optionslab.engine.orb.DonchianArm.SOURCE, "passed:r51:9", "NIFTY25OCT25000CE", "NFO", "MIS", 1, 75, 75, 75, 0.05, 1,
            100.0, java.time.LocalDateTime.now().withNano(0), 100.0, 75, null, "1001", paperSymbol = "NIFTY-PA-25000CE")
        try {
            ArmLive.viewForTest(ArmLive.View(open = listOf(copy), loaded = true))
            assertTrue(TradeManagerHost.armLiveCopyKnown("NIFTY-PA-25000CE"))
            assertTrue(TradeManagerHost.armLiveCopy(paperSymbol = "NIFTY-PA-25000CE"))
            assertTrue(TradeManagerHost.armLiveCopy(ref = "passed:r51:9"))
            ArmLive.viewForTest(ArmLive.View(open = listOf(copy.copy(detached = true)), loaded = true))
            assertFalse("detached: not known", TradeManagerHost.armLiveCopyKnown("NIFTY-PA-25000CE"))
            assertFalse("detached: not a live copy", TradeManagerHost.armLiveCopy(paperSymbol = "NIFTY-PA-25000CE"))
            assertFalse(TradeManagerHost.armLiveCopy(ref = "passed:r51:9"))
        } finally { ArmLive.viewForTest(ArmLive.View()) }
    }
}
