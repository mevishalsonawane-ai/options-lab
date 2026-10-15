package com.optionslab.app.data

import com.optionslab.app.ira.Autopilot
import com.optionslab.app.security.Vault
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.testing.TradeFixtures
import com.optionslab.engine.orb.ArmsCleanup
import com.optionslab.engine.orb.LiquidityRules
import com.optionslab.engine.orb.ParkedArms
import com.optionslab.ira.AutoChange
import com.optionslab.ira.ChangeTimeline
import com.optionslab.ira.StartOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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

/**
 * Boss's 15 Oct arms cleanup on the phone ([HiddenArms]): nothing is read before the start's positions step; an arm holding a
 * position keeps its switch and its row (its own exits keep working, the watch keeps it as held) and is switched off only once
 * flat; every other one off and hidden at once; each switch-off and restore in Autopilot's change timeline; one tap restores
 * (on paper only); a hidden arm's own switch refuses; the state survives a restart; Liquidity keeps FINNIFTY and MIDCPNIFTY
 * on paper once, BANKNIFTY untouched.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class HiddenArmsTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()

    /** A stand-in arm: its switch, whether it holds anything, how often it was read, and what was done to it. */
    private class Fake(val key: String, var armed: Boolean = false, var open: Boolean = false, val members: List<String> = emptyList()) {
        var reads = 0
        val calls = ArrayList<String>()
        fun arm() = HiddenArms.Arm(key,
            now = { reads++; ArmsCleanup.Now(key, armed, open) },
            off = { calls += "off"; armed = false; members },
            on = { ids -> calls += "on $ids"; armed = true; "back on, paper only" })
    }

    private lateinit var fakes: Map<String, Fake>
    private fun fake(key: String) = fakes.getValue(key)

    @Before fun up() {
        TradeFixtures.paperSettings()
        OrbArms.testNow = AutomationSupport.earlierToday()
        HiddenArms.resetForTest(context)
        fakes = ArmsCleanup.HIDDEN.associate { it.key to Fake(it.key) }
        HiddenArms.arms = fakes.values.map { it.arm() }
        if (Autopilot.enabled()) { Autopilot.resetForTest(); Autopilot.init(context); Autopilot.testLive = false }
    }

    @After fun down() {
        AutomationSupport.realClocks()
        PositionsFirst.resetForTest(started = false)
        HiddenArms.resetForTest(context)
        if (Autopilot.enabled()) Autopilot.resetForTest()
        com.optionslab.app.security.SecurePrefs.put(Backup.DISARM, null)
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun pass() = runBlocking { HiddenArms.passNow() }

    private fun hides() = if (Autopilot.enabled()) Autopilot.book.value.entries.filter { it.change.kind == AutoChange.Kind.ARM_HIDE } else emptyList()
    private fun waitFor(what: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!cond() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(what, cond())
    }

    // ---- open positions first -------------------------------------------------------------------------------------------------

    @Test fun nothingIsReadOrSwitchedBeforeTheStartsPositionsStep() {
        fake(ArmsCleanup.NIGHT).armed = true
        PositionsFirst.resetForTest(started = true)
        runBlocking {
            val job = async(Dispatchers.Default) { HiddenArms.awaitPositionsStep(); HiddenArms.passNow() }
            delay(300)
            assertFalse("waits for the positions step", job.isCompleted)
            assertEquals("no arm read before it", 0, fakes.values.sumOf { it.reads })
            assertTrue(fake(ArmsCleanup.NIGHT).armed)
            PositionsFirst.order.done(StartOrder.Step.POSITIONS, 1L)
            job.await()
        }
        assertTrue(fakes.values.all { it.reads == 1 })
        assertEquals(listOf("off"), fake(ArmsCleanup.NIGHT).calls)
    }

    @Test fun anArmHoldingAPositionKeepsItsSwitchAndItsRowUntilItsOwnExitThenGoesOff() {
        fake(ArmsCleanup.ORB).apply { armed = true; open = true }
        fake(ArmsCleanup.VIX_DIV).armed = true
        val steps = pass()
        // Flat and on: off at once. Holding: left on and on screen, said to wait for its exit; nothing done to it.
        assertEquals(listOf("off"), fake(ArmsCleanup.VIX_DIV).calls)
        assertTrue(fake(ArmsCleanup.ORB).calls.isEmpty())
        assertTrue(fake(ArmsCleanup.ORB).armed)
        assertTrue(HiddenArms.visible(ArmsCleanup.ORB))
        assertFalse(HiddenArms.blocks(ArmsCleanup.ORB))
        assertTrue(steps.any { it.key == ArmsCleanup.ORB && it.action == ArmsCleanup.Action.WAIT })
        assertFalse(HiddenArms.visible(ArmsCleanup.VIX_DIV))
        assertTrue(Diag.lines().any { it.contains("ORB: ${ArmsCleanup.WAITING}") })
        // Still holding: nothing more.
        pass()
        assertTrue(fake(ArmsCleanup.ORB).calls.isEmpty())
        // Its own stop sold it: switched off on the next pass, hidden, listed.
        fake(ArmsCleanup.ORB).open = false
        pass()
        assertEquals(listOf("off"), fake(ArmsCleanup.ORB).calls)
        assertFalse(HiddenArms.visible(ArmsCleanup.ORB))
        assertTrue(ArmsCleanup.listed(HiddenArms.state.value).any { it.key == ArmsCleanup.ORB })
        assertTrue(HiddenArms.diagLine(), HiddenArms.diagLine().startsWith("Hidden arms: 12 hidden"))
    }

    @Test fun aReadThatFailsCountsAsHoldingSoNothingIsSwitchedOffOnIt() {
        fake(ArmsCleanup.PINE).armed = true
        HiddenArms.arms = fakes.values.map { f -> if (f.key == ArmsCleanup.PINE) HiddenArms.Arm(f.key, { error("unreadable") }, { f.calls += "off"; emptyList() }, { "" }) else f.arm() }
        pass()
        assertTrue(fake(ArmsCleanup.PINE).calls.isEmpty())
        assertTrue(HiddenArms.visible(ArmsCleanup.PINE))
    }

    // ---- the timeline, the restore, a restart ---------------------------------------------------------------------------------

    @Test fun oneTapRestoresAnArmOnPaperWithTheMembersItHadAndBothAreInTheTimeline() {
        fakes = ArmsCleanup.HIDDEN.associate { it.key to Fake(it.key, members = if (it.key == ArmsCleanup.PINE) listOf("7", "9") else emptyList()) }
        HiddenArms.arms = fakes.values.map { it.arm() }
        fake(ArmsCleanup.PINE).armed = true
        pass()
        assertFalse(HiddenArms.mayArm(ArmsCleanup.PINE))
        val said = runBlocking { HiddenArms.restore(ArmsCleanup.PINE) }
        assertTrue(said, said.startsWith("Pine auto-trade scripts is back on its row") && said.contains("paper only"))
        assertEquals(listOf("off", "on [7, 9]"), fake(ArmsCleanup.PINE).calls)
        assertTrue(HiddenArms.visible(ArmsCleanup.PINE) && HiddenArms.mayArm(ArmsCleanup.PINE))
        // An arm that was off comes back off.
        val off = runBlocking { HiddenArms.restore(ArmsCleanup.NIGHT) }
        assertTrue(off, off.contains("it was off"))
        assertTrue(fake(ArmsCleanup.NIGHT).calls.isEmpty())
        assertEquals("Night (R3) is not hidden.", runBlocking { HiddenArms.restore(ArmsCleanup.NIGHT) })
        assertEquals("There is no hidden arm called liquidity.", runBlocking { HiddenArms.restore("liquidity") })
        // Never hidden again by a later pass.
        pass()
        assertTrue(HiddenArms.visible(ArmsCleanup.PINE))
        if (!Autopilot.enabled()) return
        waitFor("each switch-off logged once, and the restore") { hides().count { it.change.target == "hide:pine_auto" } == 2 && hides().size == 13 }
        val pine = hides().filter { it.change.target == "hide:pine_auto" }
        assertEquals(listOf(AutoChange.HIDDEN, AutoChange.RESTORED), pine.map { it.change.to })
        assertEquals(AutoChange.ON, pine[0].change.from)
        assertEquals(AutoChange.Status.UNDONE, pine[0].status)
        assertEquals(AutoChange.Status.KEPT, pine[1].status)
        assertTrue(hides().all { it.by == "your choice" })
        val items = ChangeTimeline.fromAutopilot(Autopilot.book.value, Market.today())
        assertTrue(items.any { it.own && it.what == "Pine auto-trade scripts (hidden arms): hidden → restored" })
        assertTrue(items.any { it.own && it.what == "Night (R3) (hidden arms): off → hidden" })
    }

    @Test fun theTimelinesUndoRestoresAndAnUndoOfTheRestoreHidesItAgain() {
        fake(ArmsCleanup.VIX_DIV).armed = true
        pass()
        val hide = AutoChange.armHidden(ArmsCleanup.VIX_DIV, "VIX divergence", wasOn = true)
        val back = Autopilot.apply(AutoChange.inverse(hide, "undone by you"), pinConfirmed = false)
        assertNotNull(back)
        assertTrue(HiddenArms.visible(ArmsCleanup.VIX_DIV))
        assertEquals(listOf("off", "on []"), fake(ArmsCleanup.VIX_DIV).calls)
        // Undo of a restore: hidden again, and switched off once it holds nothing.
        assertNotNull(Autopilot.apply(AutoChange.inverse(AutoChange.armRestored(ArmsCleanup.VIX_DIV, "VIX divergence"), "undone by you"), pinConfirmed = false))
        pass()
        assertFalse(HiddenArms.visible(ArmsCleanup.VIX_DIV))
        assertEquals(listOf("off", "on []", "off"), fake(ArmsCleanup.VIX_DIV).calls)
        // Never another target.
        assertEquals(null, Autopilot.apply(hide.copy(target = "hide:liquidity", to = AutoChange.ON), false))
    }

    @Test fun theStateSurvivesARestartAndTheCleanupNeverRunsTwice() {
        fake(ArmsCleanup.ORB).armed = true
        pass()
        val before = HiddenArms.state.value
        HiddenArms.reloadForTest()
        assertFalse(HiddenArms.state.value.done)
        pass()
        assertEquals(before, HiddenArms.state.value)
        assertEquals("switched off once", listOf("off"), fake(ArmsCleanup.ORB).calls)
        runBlocking { HiddenArms.restore(ArmsCleanup.ORB) }
        HiddenArms.reloadForTest(); pass()
        assertTrue("a restored arm stays restored after a restart", HiddenArms.visible(ArmsCleanup.ORB))
    }

    // ---- the real arms ---------------------------------------------------------------------------------------------------------

    /** ORB armed for Zerodha with an open call, ORB Fresh armed and flat: the book as saved before the update. */
    private fun orbBook(orbOpen: Boolean) {
        val t = OrbArms.testNow!!.toLocalDateTime()
        val pos = JSONObject().put("arm", "orb").put("symbol", "BANKNIFTY-ORB-52000CE").put("right", "CE").put("qty", 30).put("entry", 280.0)
            .put("entryTime", t.minusMinutes(5).toString()).put("signalBar", t.minusMinutes(10).toString())
        if (!orbOpen) pos.put("exit", 300.0).put("exitTime", t.minusMinutes(1).toString()).put("why", "target")
        AutomationSupport.orbState(context, JSONObject()
            .put("armed", JSONObject().put("orb", true).put("orb_fresh", true).put("liquidity15", true).put("liquidity5", true))
            .put("auto", JSONObject().put("orb", true).put("orb_fresh", false))
            .put("liveOk", JSONObject().put("orb", true))
            .put("positions", JSONArray().put(pos)))
    }

    private fun orbArm(src: String) = runBlocking { OrbArms.view() }.arms.single { it.arm.source == src }

    @Test fun theRealOrbArmHoldingACallIsStillWatchedAndExitsByItsOwnRulesBeforeItGoesOff() {
        orbBook(orbOpen = true)
        val orbFamily = setOf(ArmsCleanup.ORB, ArmsCleanup.ORB_FRESH, ArmsCleanup.ORB_SWEEP, ArmsCleanup.RANGE_FADE, ArmsCleanup.HERO)
        HiddenArms.arms = HiddenArms.defaultArmsForTest().filter { it.key in orbFamily }
        // A restart restores the open position first (the watch is started on it).
        assertTrue(runBlocking { PositionsFirst.restore() })
        pass()
        // ORB keeps its switch, its Zerodha clearance and its open call: the watch keeps it as held (full cadence), its stop rests.
        val orb = orbArm("orb")
        assertTrue(orb.armed); assertTrue(orb.liveOk); assertNotNull(orb.open)
        assertTrue(OrbArms.holdingHint)
        assertTrue(HiddenArms.visible("orb"))
        // ORB Fresh was flat: off now, its approve choice kept for a restore.
        assertFalse(orbArm("orb_fresh").armed)
        assertFalse(runBlocking { OrbArms.autoOf("orb_fresh") })
        // Liquidity is never touched.
        assertTrue(orbArm("liquidity").armed)
        // The call is sold by its own target: on the next pass ORB goes off (paper and Live clearance alike) and is hidden.
        orbBook(orbOpen = false)
        pass()
        assertFalse(orbArm("orb").armed); assertFalse(orbArm("orb").liveOk)
        assertFalse(HiddenArms.visible("orb"))
        // Its own switch now refuses (a restore comes first)...
        val refused = runBlocking { OrbArms.setArmed("orb", true, automatic = true) }
        assertEquals(ArmsCleanup.refusal("ORB"), refused)
        assertFalse(orbArm("orb").armed)
        // ...and a restore puts it back ON PAPER only: in Live the arm asks for the PIN on its row, so nothing goes live.
        AutomationSupport.liveSettings()
        val said = runBlocking { HiddenArms.restore("orb") }
        assertTrue(said, said.contains("PIN"))
        assertFalse("never live without the PIN", orbArm("orb").armed)
        TradeFixtures.paperSettings()
        runBlocking { HiddenArms.restore("orb_fresh") }
        assertTrue(orbArm("orb_fresh").armed)
        assertFalse("approve each entry, as it was", orbArm("orb_fresh").automatic)
        assertFalse(orbArm("orb_fresh").liveOk)
    }

    @Test fun theOtherHiddenArmsOwnSwitchesRefuseWhileHidden() {
        HiddenArms.arms = emptyList()
        pass()
        assertEquals(ArmsCleanup.refusal(com.optionslab.engine.orb.VixDivRules.LABEL), runBlocking { VixDivArm.setArmed(true) })
        assertEquals(ArmsCleanup.refusal("Shadows re-armed on paper"), runBlocking { ShadowArms.promote(com.optionslab.engine.orb.ShadowRules.ALL.first().id) })
        // Switching off is always allowed (it only lowers risk).
        assertFalse(runBlocking { VixDivArm.setArmed(false) }.contains("hidden"))
        // The gated arms: no new entry or idea.
        assertTrue(HiddenArms.blocks(ArmsCleanup.FORGE) && HiddenArms.blocks(ArmsCleanup.JARVIS))
        // Liquidity and the kept arms: never.
        for (k in listOf("liquidity", "liquidity15", "hero_ticket")) assertTrue(k, HiddenArms.mayArm(k) && !HiddenArms.blocks(k))
    }

    // ---- Liquidity on its indices ------------------------------------------------------------------------------------------

    private fun liqArmed() = runBlocking { OrbArms.liquidityDay(OrbArms.testNow!!.toLocalDate()) }.first.associate { it.book to it.armed }

    private fun savedBook(): JSONObject = JSONObject(String(Vault.readFile(File(context.filesDir, "orb.vault"))!!, Charsets.UTF_8))

    @Test fun liquidityKeepsFinniftyAndMidcpniftyOnPaperOnceAndBankniftyIsUntouched() {
        // Liquidity's BANKNIFTY books on and cleared for Zerodha; FINNIFTY parked on 9 Oct; MIDCPNIFTY's 5-minute book off.
        val bn = listOf("liquidity15", "liquidity5")
        AutomationSupport.orbState(context, JSONObject()
            .put("armed", JSONObject().put("liquidity15", true).put("liquidity5", true).put("liquidity15_mid", true))
            .put("auto", JSONObject().put("liquidity15", true).put("liquidity5", true).put("liquidity15_mid", true))
            .put("liveOk", JSONObject().put("liquidity15", true).put("liquidity5", true))
            .put("liqParked", JSONArray().put("FINNIFTY")).put("positions", JSONArray()))
        assertEquals(listOf("FINNIFTY", "MIDCPNIFTY"), runBlocking { OrbArms.keepLiquidityFour() })
        assertEquals(emptyList<String>(), orbArm("liquidity").parked)
        assertTrue(LiquidityRules.BOOKS.all { liqArmed().getValue(it.source) })
        // On paper only: the new books are never cleared for Zerodha; BANKNIFTY's clearance is as it was.
        val live = savedBook().getJSONObject("liveOk")
        for (b in listOf("liquidity30_fin", "liquidity5_fin", "liquidity5_mid")) assertFalse(b, live.optBoolean(b, false))
        for (b in bn) assertTrue(b, live.getBoolean(b))
        assertTrue(OrbArms.liquidityBnLiveHint)
        assertEquals(ArmsCleanup.LIQ_ON, savedBook().getJSONObject("status").getString("liquidity30_fin"))
        // Once: never again (Boss may park it again from the timeline's Undo).
        assertEquals(emptyList<String>(), runBlocking { OrbArms.keepLiquidityFour() })
        assertEquals("Liquidity 15+5 FINNIFTY is parked again.", runBlocking { OrbArms.parkLiquidity("FINNIFTY") })
        assertEquals(listOf("FINNIFTY"), orbArm("liquidity").parked)
        assertFalse(liqArmed().getValue("liquidity30_fin"))
        assertEquals(emptyList<String>(), runBlocking { OrbArms.keepLiquidityFour() })
        assertTrue("BANKNIFTY never parked here", runBlocking { OrbArms.parkLiquidity("BANKNIFTY") }.startsWith("Only FINNIFTY or MIDCPNIFTY"))
        // NIFTY has no Liquidity book: it failed research (H4), so it stays a note.
        assertEquals(listOf("BANKNIFTY", "FINNIFTY", "MIDCPNIFTY"), LiquidityRules.UNDERLYINGS)
        assertTrue(ArmsCleanup.NIFTY_NOTE.contains("no book"))
        if (Autopilot.enabled()) {
            fun liq() = Autopilot.book.value.entries.filter { it.change.kind == AutoChange.Kind.LIQ_INDEX_ON }
            waitFor("both indices logged") { liq().size == 2 }
            assertEquals(listOf("liqindex:FINNIFTY", "liqindex:MIDCPNIFTY"), liq().map { it.change.target })
            assertEquals(listOf(AutoChange.PARKED, AutoChange.SWITCHED_OFF), liq().map { it.change.from })
            assertTrue(liq().all { it.by == "your choice" && it.status == AutoChange.Status.KEPT })
        }
    }

    @Test fun withLiquidityOffOnlyTheParkGoesAndARestoreNotYetDisarmedSwitchesNothingOn() {
        AutomationSupport.orbState(context, JSONObject().put("liqParked", JSONArray().put("FINNIFTY")).put("positions", JSONArray()))
        assertEquals(listOf("FINNIFTY"), runBlocking { OrbArms.keepLiquidityFour() })
        assertEquals(emptyList<String>(), orbArm("liquidity").parked)
        assertTrue(LiquidityRules.BOOKS.none { liqArmed().getValue(it.source) })
        com.optionslab.app.security.SecurePrefs.put(Backup.DISARM, true)
        AutomationSupport.orbState(context, JSONObject().put("armed", JSONObject().put("liquidity15", true)).put("positions", JSONArray()))
        runBlocking { OrbArms.keepLiquidityFour() }
        assertTrue(ParkedArms.books("FINNIFTY").none { liqArmed().getValue(it.source) })
    }
}
