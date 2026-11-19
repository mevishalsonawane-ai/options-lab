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

    private fun runTo(id: String, copy: Boolean): TradeManager.Record {
        TradeManagerHost.liveCopyOf = { copy }
        assertEquals(TradeManager.Mode.ACT, TradeManagerHost.attach(TradeManagerHost.Registration(trade(id), premium = { 120.0 })))
        TradeManagerHost.testSnapshot = { _, at -> sellers(at) }
        for (i in 0..61) TradeManagerHost.evaluate(t0 + i * 1000L)
        return TradeManagerHost.records.value.single { it.trade.tradeId == id }
    }

    @Test fun anExitHeldForALiveCopyIsRecordedAsAWouldBeExitNeverActed() {
        val r = runTo("solo:1:held", copy = true)
        val exit = r.notes.single { it.kind == TradeManager.EXIT_EARLY }
        assertFalse("not acted", exit.acted)
        assertNull("no exit asked of the strategy", TradeManagerHost.exitDue("solo:1:held"))
        assertTrue("priced as the counterfactual", r.manager != null)
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
        kotlinx.coroutines.runBlocking { ArmLive.refresh() }
        assertTrue(ArmLive.view.value.loaded)
        assertFalse("read, and no live trade copies it", TradeManagerHost.armLiveCopy(paperSymbol = "NIFTY25OCT25000CE"))
    }
}
