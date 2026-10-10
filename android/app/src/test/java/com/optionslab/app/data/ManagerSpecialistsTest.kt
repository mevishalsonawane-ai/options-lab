package com.optionslab.app.data

import com.optionslab.app.testing.RobolectricTest
import com.optionslab.ira.Auction
import com.optionslab.ira.ManagerTeam
import com.optionslab.ira.OrderFlow
import com.optionslab.ira.TradeManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The trade manager's specialists on the phone ([ManagerSpecialists] with [TradeManagerHost]): each look's committee from
 * memory, its decision recorded with the specialist that led it and every one that voted, the latest look shown per trade,
 * a mute with no PIN (by voice too), an un-mute where the manager acts on live trades only with the PIN, and the nightly
 * review's tuning.
 */
class ManagerSpecialistsTest : RobolectricTest() {
    private val t0 = 1_760_000_000_000L

    @Before fun up() {
        SmartWorkers.resetForTest()
        TradeManagerHost.resetForTest()
        TradeManagerHost.testNoWake = true
        TradeManagerHost.testBars = { emptyList() }
    }

    @After fun down() {
        TradeManagerHost.resetForTest()
    }

    private fun trade(id: String = "pine:1:x") = TradeManager.ManagedTrade("pine:1", id, "Pine · Level", "NIFTY", 1, "NIFTY25OCT25000CE",
        100.0, 75, 70.0, 160.0, TradeManager.Caps(t0 + 3 * 3_600_000L), TradeManager.Account.PAPER, t0, 1.0)

    private fun sellers(at: Long): TradeManager.Snapshot {
        val r = OrderFlow.Read(name = "NIFTY", atSec = at / 1000, side = OrderFlow.Side.SELLERS, strength = 70, buyers = 30, warm = true,
            mid = 24_990.0, ofi10 = 0.0, ofi60 = 0.0, ofi300 = 0.0, cvd10 = 0.0, cvd60 = -5_000.0, cvd300 = 0.0, depth = 0.0, queue = 0.0,
            ratio = Double.NaN, ce60 = 0.0, pe60 = 0.0, optionNet = Double.NaN, buildUp = null, oiChange = null, z = emptyMap())
        return TradeManager.Snapshot(at, 120.0, flow = r, futPrice = 24_990.0, vwap = Auction.Vwap(25_000.0, 20.0, null, null),
            valueHigh = 25_010.0, valueLow = 24_980.0)
    }

    @Test fun theCommitteeDecidesAndTheRecordNamesWhoLedIt() {
        assertEquals(TradeManager.Mode.ACT, TradeManagerHost.attach(TradeManagerHost.Registration(trade(), premium = { 120.0 })))
        TradeManagerHost.testSnapshot = { _, at -> sellers(at) }
        for (i in 0..61) TradeManagerHost.evaluate(t0 + i * 1000L)
        val r = TradeManagerHost.records.value.single()
        val exit = r.notes.single { it.kind == TradeManager.EXIT_EARLY }
        assertEquals("flow", exit.lead)
        assertTrue(exit.voters.toString(), "flow" in exit.voters && "vwap" in exit.voters)
        assertEquals("the trade manager: sellers took over", TradeManagerHost.exitDue("pine:1:x"))
        val view = ManagerSpecialists.views.value.getValue("pine:1:x")
        assertEquals(16, view.votes.size)
        assertTrue(view.chair, view.chair.startsWith("Chair: EXIT (Order flow)"))
        // Jarvis: which specialist made Pine exit.
        val which = TradeManagerHost.answer(TradeManager.asked("which specialist made Pine exit?")!!)
        assertTrue(which, "the Order flow specialist led it" in which && "VWAP" in which)
        // The specialists' notable votes went on the findings bus with no direction (no consensus counts them).
        val mine = SmartWorkers.bus.recent(System.currentTimeMillis()).filter { it.who.startsWith(TradeManager.WHO + " · ") }
        assertTrue(mine.isNotEmpty())
        assertTrue(mine.all { it.direction == 0 })
    }

    @Test fun aMuteNeedsNoPinAndAnUnmuteWhereTheManagerActsLiveNeedsIt() {
        // By voice: muted at once, nothing else changes.
        val said = TradeManagerHost.answer(TradeManager.asked("mute the OI specialist for Pine")!!)
        assertTrue(said, said.startsWith("OI specialist muted for Pine") && "No PIN needed" in said)
        assertFalse(ManagerSpecialists.configFor("pine", frozen = false).counts("oi"))
        assertFalse(ManagerSpecialists.configFor("pine", frozen = true).counts("oi"))
        assertEquals("muting changes no mode", TradeManager.Mode.SHADOW, TradeManagerHost.policy.value.mode("pine", TradeManager.Account.LIVE))
        // Pine acting on live trades: an un-mute by voice is refused; with the PIN (the sheet) it counts again.
        TradeManagerHost.setMode("pine", TradeManager.Account.LIVE, TradeManager.Mode.ACT, pinConfirmed = true)
        assertEquals(ManagerSpecialists.PIN_UNMUTE, TradeManagerHost.answer(TradeManager.asked("unmute the OI specialist for Pine")!!))
        assertFalse(ManagerSpecialists.configFor("pine", frozen = true).counts("oi"))
        assertTrue(ManagerSpecialists.unmute("pine", "oi", pinConfirmed = true).startsWith("OI specialist counts again"))
        assertTrue(ManagerSpecialists.configFor("pine", frozen = true).counts("oi"))
        // A hard veto stays even muted.
        assertTrue("hard veto still stands" in ManagerSpecialists.mute("pine", "news"))
        // Solo is not acting on live: its un-mute needs no PIN.
        ManagerSpecialists.mute("solo", "vwap")
        assertTrue(ManagerSpecialists.unmute("solo", "vwap").startsWith("VWAP specialist counts again"))
        // The live weights change only on the PIN.
        assertEquals(ManagerSpecialists.PIN_ACCEPT, ManagerSpecialists.acceptSuggestion("pine", pinConfirmed = false))
    }

    @Test fun theNightlyReviewTunesPaperAndShadowOnlyAndSaysOneLine() {
        val notes = { lead: String -> listOf(TradeManager.Note(t0, TradeManager.EXIT_EARLY, "team", "out", 110.0, true, lead = lead, voters = listOf(lead))) }
        // 34 settled Pine trades where the OI specialist's exits hurt.
        val recs = (1..34).map { i ->
            TradeManager.Record(trade("t$i"), TradeManager.Mode.ACT, notes("oi"), actual = TradeManager.Exit(t0, 110.0, "x"),
                manager = TradeManager.Exit(t0, 110.0, "x"), original = TradeManager.Exit(t0, 110.0 + 0.1, "TARGET"), actedAtMs = t0)
        }
        TradeManagerHost.recordsForTest(recs)
        ManagerSpecialists.review("2026-10-12", t0)
        val b = ManagerSpecialists.book.value
        assertNotNull(b.tuned["pine"]?.muted?.get("oi"))
        assertTrue("the frozen live weights never move by themselves", b.frozen.isEmpty())
        assertTrue(b.lines.single(), b.lines.single().startsWith("Pine: OI hurt"))
        assertTrue(b.lines.single().endsWith(", muted"))
        assertFalse(ManagerSpecialists.configFor("pine", frozen = false).counts("oi"))
        assertTrue(ManagerSpecialists.configFor("pine", frozen = true).counts("oi"))
        assertEquals(listOf("OI weight 0.25 → 0.23", "OI muted"), ManagerSpecialists.suggestion("pine"))
        val how = TradeManagerHost.answer(TradeManager.asked("how are the manager's specialists doing?")!!)
        assertTrue(how, "Pine: OI hurt" in how && "Last night's review" in how)
        assertTrue(ManagerTeam.IDS.size == 16)
    }
}
