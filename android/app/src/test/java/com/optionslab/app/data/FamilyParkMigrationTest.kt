package com.optionslab.app.data

import com.optionslab.app.ira.Autopilot
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.testing.TradeFixtures
import com.optionslab.engine.orb.FamilyPark
import com.optionslab.engine.orb.LiquidityRules
import com.optionslab.engine.orb.ParkedArms
import com.optionslab.ira.AutoChange
import com.optionslab.ira.ChangeTimeline
import com.optionslab.ira.PolicyCheck
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

/**
 * The 13 Oct park (Boss's choice: "Switch off ORB, ORB Fresh, Sweep and Range Fade"): on the update, once, each of the four
 * that is on is switched off - on paper and in Live - and all four are marked parked; Liquidity 15+5 and the Hero arm are
 * untouched. It never runs twice: an arm Boss switches back on stays on. The morning check's policy line and Problems found no
 * longer see them armed, the change is in the Autopilot change timeline (its Undo switches the arm back on, on paper), and a
 * restore of a backup from before the park leaves them parked.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class FamilyParkMigrationTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()

    @Before fun up() {
        TradeFixtures.paperSettings()
        AutomationSupport.clearAlerts()
        OrbArms.testNow = AutomationSupport.earlierToday()
        if (Autopilot.enabled()) { Autopilot.resetForTest(); Autopilot.init(context); Autopilot.testLive = false }
    }

    @After fun down() {
        AutomationSupport.realClocks()
        if (Autopilot.enabled()) Autopilot.resetForTest()
        com.optionslab.app.security.SecurePrefs.put(Backup.DISARM, null)
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private val four = FamilyPark.ARMS.map { it.source }
    private val liqBooks = LiquidityRules.BOOKS.map { it.source }
    private fun arm(src: String) = runBlocking { OrbArms.view() }.arms.single { it.arm.source == src }
    private fun liqArmed() = runBlocking { OrbArms.liquidityDay(OrbArms.testNow!!.toLocalDate()) }.first.associate { it.book to it.armed }
    private fun noticeCount() = Diag.lines().count { it.contains(FamilyPark.NOTICE) }

    /**
     * A book saved on 12 Oct (every earlier one-time change done): the four and every Liquidity book on, ORB cleared for
     * Zerodha (Live, with the PIN), ORB Fresh approving each entry, the Hero arm on.
     */
    private fun savedOn12Oct(extra: (JSONObject) -> Unit = {}) {
        val on = four + liqBooks + "hero"
        val o = JSONObject()
            .put("armed", JSONObject().apply { on.forEach { put(it, true) } })
            .put("auto", JSONObject().apply { on.forEach { put(it, it != "orb_fresh") } })
            .put("liveOk", JSONObject().put("orb", true).apply { liqBooks.forEach { put(it, true) } })
            .put("liqLots", 1)
            .put("migrated", JSONArray().put(OrbArms.OFF_LOSERS).put(com.optionslab.engine.orb.RetiredArms.MIGRATION)
                .put(com.optionslab.engine.orb.RetiredArms.UNRETIRE).put(LiquidityRules.MIDCP_JOIN)
                .put(com.optionslab.engine.orb.LiquidityLots.ONE_LOT_MIGRATION).put(ParkedArms.MIGRATION))
            .put("positions", JSONArray())
        extra(o)
        AutomationSupport.orbState(context, o)
    }

    @Test fun theFirstRunParksAllFourAndLeavesLiquidityAndHeroAsTheyWere() {
        savedOn12Oct()
        for (src in four) {
            val a = arm(src)
            assertFalse("$src off", a.armed)
            assertFalse("$src no longer cleared for Zerodha", a.liveOk)
            assertTrue("$src parked", a.bossParked)
            assertEquals(FamilyPark.PARKED, a.status)
        }
        // Liquidity 15+5: its switch, its books (FINNIFTY stays parked from 9 Oct) and their Live clearance as they were.
        val liq = arm("liquidity")
        assertTrue(liq.armed); assertTrue(liq.liveOk); assertFalse(liq.bossParked)
        assertEquals(listOf("FINNIFTY"), liq.parked)
        for (b in listOf("liquidity15", "liquidity5", "liquidity15_mid", "liquidity5_mid")) assertTrue(b, liqArmed().getValue(b))
        // The Hero arm too.
        assertTrue(arm("hero").armed); assertFalse(arm("hero").bossParked)
        // Told once, in the arm log, for each arm that was on, and as one notice.
        val log = Diag.lines()
        for (label in listOf("ORB", "ORB Fresh", "ORB Sweep", "Range Fade"))
            assertTrue(log.toString(), log.any { it.contains("$label: ${FamilyPark.PARKED}") })
        assertTrue(log.toString(), log.any { it.contains("ORB: ${FamilyPark.PARKED} (it was armed for Zerodha)") })
        assertEquals(1, noticeCount())
    }

    @Test fun aSecondRunDoesNothing() {
        savedOn12Oct()
        assertTrue(four.none { arm(it).armed })
        fun parkLines() = Diag.lines().count { it.contains("13 Oct") }
        val logged = parkLines()
        AutomationSupport.reloadFromDisk(OrbArms)
        assertTrue(four.all { arm(it).bossParked && !arm(it).armed })
        assertEquals(1, noticeCount())
        assertEquals("nothing about the park logged again", logged, parkLines())
    }

    @Test fun anArmBossSwitchesBackOnStaysOnAfterARestart() {
        savedOn12Oct()
        val said = runBlocking { OrbArms.setArmed("orb", true, automatic = true) }
        assertTrue(said, said.startsWith("ORB armed on paper"))
        assertFalse("no longer parked", arm("orb").bossParked)
        assertTrue(Diag.lines().any { it.contains("ORB: switched back on by you after the 13 Oct park, on paper") })
        AutomationSupport.reloadFromDisk(OrbArms)
        assertTrue("left on", arm("orb").armed)
        assertFalse(arm("orb").bossParked)
        // The others stay parked; switching one off and on again later is Boss's alone, never undone by the app.
        for (src in four - "orb") assertTrue(src, arm(src).bossParked && !arm(src).armed)
        runBlocking { OrbArms.setArmed("orb", false, automatic = true) }
        AutomationSupport.reloadFromDisk(OrbArms)
        assertFalse(arm("orb").armed); assertFalse("a disarm is not a park", arm("orb").bossParked)
        assertEquals(1, noticeCount())
    }

    @Test fun anOpenPositionIsKeptAndTheArmLogSaysItIsStillManaged() {
        val t = OrbArms.testNow!!.toLocalDateTime()
        savedOn12Oct { o -> o.put("positions", JSONArray().put(JSONObject().put("arm", "orb_fresh")
            .put("symbol", "BANKNIFTY-ORB-52000CE").put("right", "CE").put("qty", 30).put("entry", 280.0)
            .put("entryTime", t.minusMinutes(5).toString()).put("signalBar", t.minusMinutes(10).toString()))) }
        val a = arm("orb_fresh")
        assertFalse(a.armed)
        assertNotNull("the open call is kept, to be managed to its exit", a.open)
        assertTrue(Diag.lines().any { it.contains("ORB Fresh: ${FamilyPark.PARKED}; its open position is still managed to its exit") })
    }

    @Test fun theMorningCheckAndProblemsFoundNoLongerWarnAboutThem() {
        savedOn12Oct()
        runBlocking { OrbArms.view() }
        val v = PolicyCheck.check(ProblemsHost.armedNow(live = false))
        for (label in listOf("ORB", "ORB Fresh", "ORB Sweep", "Range Fade"))
            assertTrue(v.toString(), v.none { it.key == "policy:$label:research" })
        // Nothing parked is armed, so Jarvis's "Shall I switch ORB off?" has nothing to ask about.
        assertTrue(OrbArms.armedLabelsHint.toString(), OrbArms.armedLabelsHint.none { it in setOf("ORB", "ORB Fresh", "ORB Sweep", "Range Fade") })
        val off = runBlocking { com.optionslab.app.ira.IraBots.switchOff(com.optionslab.ira.SwitchOff.Q("orb")) }
        assertTrue(off.second.toString(), off.second == null || off.second!!.name !in setOf("ORB", "ORB Fresh", "ORB Sweep", "Range Fade"))
        assertTrue(off.first.text, off.first.text.startsWith(FamilyPark.history("ORB")))
    }

    @Test fun itIsInTheChangeTimelineAndItsUndoSwitchesTheArmBackOnOnPaper() {
        org.junit.Assume.assumeTrue("the change timeline is in IraAlgo with Jarvis only", Autopilot.enabled())
        savedOn12Oct()
        runBlocking { OrbArms.view() }
        val deadline = System.currentTimeMillis() + 10_000
        while (Autopilot.book.value.entries.count { it.change.kind == AutoChange.Kind.ARM_PARK } < 4 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val parks = Autopilot.book.value.entries.filter { it.change.kind == AutoChange.Kind.ARM_PARK }
        assertEquals(four.map { "arm:$it" }.toSet(), parks.map { it.change.target }.toSet())
        assertTrue(parks.all { it.status == AutoChange.Status.KEPT && it.by == "your choice" })
        val day = parks.first().day
        val items = ChangeTimeline.fromAutopilot(Autopilot.book.value, day).filter { it.what.endsWith("'s switch: on → parked") || it.what.contains("on, approve each entry → parked") }
        assertEquals(4, items.size)
        assertTrue(items.all { it.agent == "You (logged by Autopilot)" && it.undo == ChangeTimeline.UndoState.UNDOABLE })
        // ORB Fresh approved each entry: its Undo puts it back so, on paper.
        val fresh = parks.single { it.change.target == "arm:orb_fresh" }
        assertEquals(AutoChange.ON_APPROVE, fresh.change.from)
        val said = Autopilot.undo(fresh.id)
        assertTrue(said, said.startsWith("Undone"))
        val a = arm("orb_fresh")
        assertTrue(a.armed); assertFalse("approve each entry, as before", a.automatic); assertFalse("paper only", a.liveOk); assertFalse(a.bossParked)
        // A restart: the park never runs again, nor is logged twice.
        AutomationSupport.reloadFromDisk(OrbArms)
        assertTrue(arm("orb_fresh").armed)
        assertEquals(4, Autopilot.book.value.entries.count { it.change.kind == AutoChange.Kind.ARM_PARK })
    }

    @Test fun aRestoreOfABackupFromBeforeTheParkLeavesThemParked() {
        // The backup's book (12 Oct) restored, the restore not yet disarmed: everything restored acts as disarmed.
        savedOn12Oct()
        com.optionslab.app.security.SecurePrefs.put(Backup.DISARM, true)
        assertTrue(four.all { !arm(it).armed && arm(it).bossParked })
        assertEquals("nothing was on to switch off: no notice", 0, noticeCount())
        com.optionslab.app.security.SecurePrefs.put(Backup.DISARM, null)
        AutomationSupport.reloadFromDisk(OrbArms)
        assertTrue("still parked after the restore", four.all { !arm(it).armed && arm(it).bossParked })
        // Boss switches Range Fade back on after the restore: it stays on.
        runBlocking { OrbArms.setArmed("range_fade", true, automatic = true) }
        AutomationSupport.reloadFromDisk(OrbArms)
        assertTrue(arm("range_fade").armed)
    }

    @Test fun aNewBookHasNothingToPark() {
        runBlocking { OrbArms.setArmed("orb", true, automatic = true) }
        assertTrue(arm("orb").armed)
        assertTrue(four.none { arm(it).bossParked })
        assertEquals(0, noticeCount())
    }
}
