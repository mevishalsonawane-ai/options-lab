package com.optionslab.app.data

import com.optionslab.app.ira.Autopilot
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.security.Vault
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.FakeUpstox
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.IST
import com.optionslab.engine.mcx.McxEveRules
import com.optionslab.engine.mcx.McxMorningPark
import com.optionslab.engine.mcx.McxMorningRules
import com.optionslab.ira.AutoChange
import com.optionslab.ira.ChangeTimeline
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode
import java.io.File
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime

/**
 * 14 Oct (research R42, Boss's OK; [McxMorningPark]): the SILVERM morning call (M4), an MCX PAPER arm, is switched off once on
 * this update. Off means no NEW entry; a call it already holds is still watched and sold by its own 4-hour exit. The change
 * is in the Autopilot change log once (Boss's, with R42's reason), and one tap on it switches the arm back on, paper only.
 * It never runs again, a new install has nothing to switch off, and the other MCX arms are untouched. Not one order reaches
 * Zerodha, with Live, real orders and "AI trades go live" all on.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class McxMorningParkMigrationTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var upstox: FakeUpstox
    private lateinit var kite: FakeKite
    private val day = LocalDate.of(2026, 10, 8)             // a Thursday: MCX 09:00-23:30
    private val callExpiry = LocalDate.of(2026, 10, 30)      // 16 MCX trading days after 8 Oct: inside the call's 11-20
    private val futKey = "MCX_FO|900700"
    private val callKey = "MCX_FO|900804"                     // the 152,000 call: 2 strikes above the 150,000 ATM
    private val armsFile get() = File(context.filesDir, "mcx_arms.vault")
    private val m4 = McxMorningRules.SOURCE

    private fun at(t: LocalTime, seconds: Long = 30) {
        Market.testClock = Clock.fixed(day.atTime(t).plusSeconds(seconds).atZone(IST).toInstant(), IST)
    }

    /** Today's MCX list: SILVERM's November future and its October calls 148,000 to 156,000 (1 lot = 5 kg). */
    private fun mcxList() {
        val a = JSONArray()
        fun row(tok: Long, xt: Long, sym: String, exp: LocalDate, strike: Double, right: String, tick: Double) =
            a.put(JSONArray().put(tok).put(xt).put(sym).put("SILVERM").put(exp.toString()).put(strike).put(right).put(tick).put(5))
        row(1, 900700, "SILVERM26NOVFUT", LocalDate.of(2026, 11, 30), 0.0, "", 1.0)
        for ((i, k) in (148_000..156_000 step 1_000).withIndex())
            row(10L + i, 900800L + i, "SILVERM26OCT${k}CE", callExpiry, k.toDouble(), "CE", 0.5)
        File(context.filesDir, "mcx_contracts.json").writeText(JSONObject().put("day", day.toString()).put("c", a).toString())
        McxMarket.wipe()
    }

    /** The future (150,000) and the 152,000 call ([call] each minute) from 09:00 to [until] (exclusive). */
    private fun minutes(until: LocalTime, call: (LocalTime) -> Double = { 2_000.0 }) {
        val fut = ArrayList<FakeUpstox.Candle>()
        val opt = ArrayList<FakeUpstox.Candle>()
        var t = LocalTime.of(9, 0)
        while (t.isBefore(until)) {
            fut += FakeUpstox.Candle(t, 150_000.0, 150_050.0, 149_950.0, 150_000.0)
            val p = call(t)
            opt += FakeUpstox.Candle(t, p, p * 1.01, p * 0.99, p)
            t = t.plusMinutes(1)
        }
        upstox.minutes[futKey] = fut
        upstox.minutes[callKey] = opt
    }

    private fun tick(t: LocalTime) { at(t); runBlocking { McxPaperArms.tick() } }

    private fun view() = runBlocking { McxPaperArms.refresh() }

    /**
     * The arms' book as it was saved before this update: what this test saved so far (or [base]), with the switch-off's mark
     * taken out, then read again from disk as after the update's restart.
     */
    private fun bookFromBeforeTheUpdate(base: JSONObject? = null) {
        val o = base ?: JSONObject(String(Vault.readFileSteady(armsFile)!!, Charsets.UTF_8))
        o.optJSONObject("marks")?.remove(McxMorningPark.MIGRATION)
        Vault.writeFile(armsFile, o.toString().toByteArray(Charsets.UTF_8))
        AutomationSupport.reloadFromDisk(McxPaperArms)
    }

    /** A book saved on 13 Oct: the morning call and the evening break on, nothing held. */
    private fun savedOn13Oct() = bookFromBeforeTheUpdate(JSONObject()
        .put("armed", JSONObject().put(m4, true).put(McxEveRules.SOURCE, true))
        .put("positions", JSONArray()).put("marks", JSONObject()))

    private fun noticeCount() = Diag.lines().count { it.contains(McxMorningPark.NOTICE) }

    private fun assertNothingAtZerodha() {
        assertEquals("no order at Zerodha", emptyList<FakeKite.Req>(), kite.placed)
        assertEquals("nothing written at Zerodha", emptyList<FakeKite.Req>(), kite.writes)
    }

    @Before fun up() {
        upstox = FakeUpstox()                                // (clears Market.testClock: set it after)
        kite = FakeKite()
        kite.login(live = true)
        AutomationSupport.liveSettings()
        AutomationSupport.security(compromised = false)
        SecurePrefs.put("jarvis.trades.paper.v3", false)
        if (Autopilot.enabled()) { Autopilot.resetForTest(); Autopilot.init(context); Autopilot.testLive = false }
        at(LocalTime.of(9, 15))
        mcxList()
        kite.requests.clear()
    }

    @After fun down() {
        Market.testClock = null
        if (Autopilot.enabled()) Autopilot.resetForTest()
        kite.close(); upstox.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    @Test fun onTheUpdateTheMorningCallIsSwitchedOffAndBuysNothing() {
        savedOn13Oct()
        val v = view()
        assertFalse("switched off", v.isArmed(m4))
        assertTrue("the evening break is untouched", v.isArmed(McxEveRules.SOURCE))
        assertEquals(McxMorningPark.PARKED, v.status[m4])
        assertEquals("its row says why", setOf(m4), v.parked)
        assertTrue(v.log.toString(), v.log.any { it.contains("${McxMorningRules.LABEL}: ${McxMorningPark.PARKED}") })
        assertEquals(1, noticeCount())
        // 09:15, its first grid time, with a fine call on the chain: nothing is bought.
        minutes(LocalTime.of(9, 15))
        val orders = Paper.state.orders.size
        tick(LocalTime.of(9, 15))
        assertEquals(orders, Paper.state.orders.size)
        assertTrue(McxPaperArms.view.value.open.isEmpty())
        assertNothingAtZerodha()
        // A restart: it never runs again and is not told twice.
        AutomationSupport.reloadFromDisk(McxPaperArms)
        assertFalse(view().isArmed(m4))
        assertEquals(1, noticeCount())
    }

    @Test fun aCallItAlreadyHoldsIsStillWatchedAndSoldByItsOwnFourHourExit() {
        // Before the update: armed, it buys its 09:15 call on paper.
        assertTrue(runBlocking { McxPaperArms.setArmed(m4, true) }.contains("paper only"))
        minutes(LocalTime.of(9, 15))
        tick(LocalTime.of(9, 15))
        val pos = McxPaperArms.view.value.open.single()
        assertEquals(m4, pos.arm)
        assertEquals(555, pos.entryMinute)
        assertTrue("held in the paper book", Paper.state.positions.any { it.symbol == pos.symbol && it.quantity > 0 })
        // The update (restart): switched off, and the call stays in the book, still watched.
        bookFromBeforeTheUpdate()
        val v = view()
        assertFalse(v.isArmed(m4))
        assertEquals(pos.symbol, v.open.single().symbol)
        assertTrue(v.log.toString(), v.log.any { it.contains("${McxMorningPark.PARKED}; what it holds still closes by its own exit") })
        at(LocalTime.of(11, 0))
        assertTrue("the watch still runs for what it holds", McxPaperArms.wantsWatch())
        // Before its 4 hours: held.
        minutes(LocalTime.of(11, 0)) { 2_100.0 }
        tick(LocalTime.of(11, 0))
        assertEquals(1, McxPaperArms.view.value.open.size)
        // 13:15, 4 hours after 09:15: sold on paper by its own exit.
        minutes(LocalTime.of(13, 15)) { 2_200.0 }
        tick(LocalTime.of(13, 15))
        assertTrue(McxPaperArms.view.value.open.isEmpty())
        val done = McxPaperArms.view.value.closed.single()
        assertEquals("four_hours", done.why)
        assertEquals("still parked after its exit", setOf(m4), McxPaperArms.view.value.parked)
        assertEquals(0, Paper.state.positions.filter { it.symbol == pos.symbol }.sumOf { it.quantity })
        // Then no NEW entry: 13:30 is a grid time after its exit, and it is off.
        val orders = Paper.state.orders.size
        minutes(LocalTime.of(13, 30)) { 2_200.0 }
        tick(LocalTime.of(13, 30))
        assertEquals(orders, Paper.state.orders.size)
        assertTrue(McxPaperArms.view.value.open.isEmpty())
        assertNothingAtZerodha()
    }

    @Test fun itIsInTheChangeLogOnceAndOneTapSwitchesItBackOnOnPaper() {
        org.junit.Assume.assumeTrue("the change log is in IraAlgo with Jarvis only", Autopilot.enabled())
        savedOn13Oct()
        view()
        val deadline = System.currentTimeMillis() + 10_000
        fun parks() = Autopilot.book.value.entries.filter { it.change.kind == AutoChange.Kind.ARM_PARK }
        while (parks().isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val park = parks().single()
        assertEquals("mcx:$m4", park.change.target)
        assertEquals(AutoChange.Status.KEPT, park.status)
        assertEquals("your choice", park.by)
        assertEquals(McxMorningPark.REASON, park.change.reason)
        val item = ChangeTimeline.fromAutopilot(Autopilot.book.value, park.day).single { it.key == park.id }
        assertEquals("${McxMorningRules.LABEL}'s switch: on → parked", item.what)
        assertTrue(item.own)
        assertEquals(ChangeTimeline.UndoState.UNDOABLE, item.undo)
        assertFalse("no PIN: paper only", Autopilot.undoNeedsPin(park.id))
        // One tap: back on, paper only.
        val said = Autopilot.undo(park.id)
        assertTrue(said, said.startsWith("Undone"))
        val v = view()
        assertTrue(v.isArmed(m4))
        assertTrue("its row no longer says it was switched off", v.status[m4] != McxMorningPark.PARKED)
        // A restart: it stays on, the change never runs again and is logged once.
        AutomationSupport.reloadFromDisk(McxPaperArms)
        assertTrue(view().isArmed(m4))
        Thread.sleep(200)
        assertEquals(1, parks().size)
        assertEquals(AutoChange.Status.UNDONE, parks().single().status)
        assertNothingAtZerodha()
    }

    @Test fun switchingItBackOnFromItsRowClosesTheChangeLogsEntry() {
        org.junit.Assume.assumeTrue("the change log is in IraAlgo with Jarvis only", Autopilot.enabled())
        savedOn13Oct()
        view()
        fun park() = Autopilot.book.value.entries.firstOrNull { it.change.kind == AutoChange.Kind.ARM_PARK && it.change.target == "mcx:$m4" }
        var deadline = System.currentTimeMillis() + 10_000
        while (park() == null && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(AutoChange.Status.KEPT, park()!!.status)
        runBlocking { McxPaperArms.setArmed(m4, true) }
        deadline = System.currentTimeMillis() + 10_000
        while (park()!!.status != AutoChange.Status.UNDONE && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals("no Undo left that could switch it on later", AutoChange.Status.UNDONE, park()!!.status)
    }

    @Test fun anArmBossSwitchesBackOnFromItsRowStaysOn() {
        savedOn13Oct()
        assertFalse(view().isArmed(m4))
        assertTrue(runBlocking { McxPaperArms.setArmed(m4, true) }.contains("paper only"))
        assertTrue("no longer parked", McxPaperArms.view.value.parked.isEmpty())
        assertTrue(McxPaperArms.view.value.status[m4] != McxMorningPark.PARKED)
        AutomationSupport.reloadFromDisk(McxPaperArms)
        assertTrue(view().isArmed(m4))
        assertTrue(view().parked.isEmpty())
        // Switched off again by hand: an ordinary off, not a park, and never switched on by the app.
        runBlocking { McxPaperArms.setArmed(m4, false) }
        AutomationSupport.reloadFromDisk(McxPaperArms)
        assertFalse(view().isArmed(m4)); assertTrue(view().parked.isEmpty())
        assertEquals(1, noticeCount())
    }

    /** The change log's Undo, through Autopilot's own setter (in every build: CI runs the app tests without Jarvis). */
    @Test fun theUndosSetterSwitchesOnlyTheMorningCallBackOnOnPaper() {
        savedOn13Oct()
        assertFalse(view().isArmed(m4))
        val park = AutoChange.mcxArmPark(m4, McxMorningRules.LABEL, McxMorningPark.REASON, McxMorningPark.WALK_FORWARD_TRADES,
            McxMorningPark.WALK_FORWARD_RS.toDouble())
        // Never another arm, and never a park by itself (only Boss's undo puts it back).
        assertEquals(null, Autopilot.apply(AutoChange.inverse(AutoChange.mcxArmPark(McxEveRules.SOURCE, McxEveRules.LABEL, "x", 0, 0.0), "undone by you"), false))
        assertEquals(null, Autopilot.apply(park, false))
        val said = Autopilot.apply(AutoChange.inverse(park, "undone by you"), pinConfirmed = false)
        assertNotNull(said); assertTrue(said!!, said.contains("on, paper only"))
        val v = view()
        assertTrue(v.isArmed(m4)); assertTrue(v.parked.isEmpty())
        assertNothingAtZerodha()
    }

    @Test fun aBookWithTheMorningCallOffHasNothingToSwitchOff() {
        bookFromBeforeTheUpdate(JSONObject().put("armed", JSONObject().put(McxEveRules.SOURCE, true)).put("positions", JSONArray()))
        val v = view()
        assertFalse(v.isArmed(m4)); assertTrue(v.isArmed(McxEveRules.SOURCE))
        assertTrue(v.status[m4] == null)
        assertEquals(0, noticeCount())
        // Marked done: Boss arming it later is never undone by the app.
        runBlocking { McxPaperArms.setArmed(m4, true) }
        AutomationSupport.reloadFromDisk(McxPaperArms)
        assertTrue(view().isArmed(m4))
        assertEquals(0, noticeCount())
    }

    @Test fun aNewInstallHasNothingToSwitchOffAndArmingItWorks() {
        assertFalse("off by default", view().isArmed(m4))
        assertTrue(runBlocking { McxPaperArms.setArmed(m4, true) }.contains("paper only"))
        AutomationSupport.reloadFromDisk(McxPaperArms)
        assertTrue("never switched off on a new install", view().isArmed(m4))
        assertEquals(0, noticeCount())
        assertNotNull(Vault.readFileSteady(armsFile))
    }
}
