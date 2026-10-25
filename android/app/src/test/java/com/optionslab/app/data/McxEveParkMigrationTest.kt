package com.optionslab.app.data

import com.optionslab.app.ira.Autopilot
import com.optionslab.app.security.Vault
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.IST
import com.optionslab.engine.mcx.McxEvePark
import com.optionslab.engine.mcx.McxEveRules
import com.optionslab.engine.mcx.McxMorningPark
import com.optionslab.engine.mcx.McxUsSilverRules
import com.optionslab.ira.AutoChange
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
import java.io.File
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime

/**
 * 25 Oct (Boss's decision on the arms audit; research R53 and R83, [McxEvePark]): the NG evening breakout, an MCX PAPER arm, is
 * switched off once on this update - no NEW entry; what it holds still closes by its own exits. Said once (the arm log, the
 * diagnostics); one tap on the change log's entry switches it back on, paper only. It never runs again, a new install has nothing
 * to switch off, and the other MCX arms are untouched.
 */
class McxEveParkMigrationTest : RobolectricTest() {
    private val day = LocalDate.of(2026, 10, 26)
    private val armsFile get() = File(context.filesDir, "mcx_arms.vault")
    private val eve = McxEveRules.SOURCE

    @Before fun up() {
        Market.testClock = Clock.fixed(day.atTime(LocalTime.of(10, 0)).atZone(IST).toInstant(), IST)
        if (Autopilot.enabled()) { Autopilot.resetForTest(); Autopilot.init(context); Autopilot.testLive = false }
    }

    @After fun down() {
        Market.testClock = null
        if (Autopilot.enabled()) Autopilot.resetForTest()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun view() = runBlocking { McxPaperArms.refresh() }

    /** A book saved before this update (24 Oct): the evening break and US-night silver on, the 14 Oct change done, nothing held. */
    private fun savedOn24Oct(armedEve: Boolean = true) {
        val o = JSONObject().put("armed", JSONObject().put(eve, armedEve).put(McxUsSilverRules.SOURCE, true))
            .put("positions", JSONArray()).put("marks", JSONObject().put(McxMorningPark.MIGRATION, "2026-10-14"))
        Vault.writeFile(armsFile, o.toString().toByteArray(Charsets.UTF_8))
        AutomationSupport.reloadFromDisk(McxPaperArms)
    }

    /** The diary's own line (the notification's banner is kept too, under "[info]"). */
    private fun noticeCount() = Diag.lines().count { it.contains("[mcx-arms] " + McxEvePark.NOTICE) }

    @Test fun onTheUpdateTheEveningBreakoutIsSwitchedOffOnceAndTheOthersAreUntouched() {
        savedOn24Oct()
        val v = view()
        assertFalse("switched off", v.isArmed(eve))
        assertTrue("US-night silver is untouched", v.isArmed(McxUsSilverRules.SOURCE))
        assertEquals(McxEvePark.PARKED, v.status[eve])
        assertEquals("its row says why", setOf(eve), v.parked)
        assertTrue(v.log.toString(), v.log.any { it.contains(McxEvePark.PARKED) })
        assertEquals(1, noticeCount())
        // Read again (a restart): never a second time.
        AutomationSupport.reloadFromDisk(McxPaperArms)
        assertFalse(view().isArmed(eve))
        assertEquals(1, noticeCount())
        // Boss switches it back on from its row: it stays on, its row no longer says why; a reload does not switch it off again.
        assertTrue(runBlocking { McxPaperArms.setArmed(eve, true) }.contains("paper only"))
        AutomationSupport.reloadFromDisk(McxPaperArms)
        val w = view()
        assertTrue(w.isArmed(eve)); assertTrue(w.parked.isEmpty())
    }

    @Test fun offAlreadyNothingChangesAndANewInstallHasNothingToSwitchOff() {
        savedOn24Oct(armedEve = false)
        assertFalse(view().isArmed(eve))
        assertTrue(view().parked.isEmpty())
        assertEquals(0, noticeCount())
        // A new install: no book at all.
        armsFile.delete(); AutomationSupport.reloadFromDisk(McxPaperArms)
        assertTrue(view().parked.isEmpty())
        assertEquals(0, noticeCount())
    }

    /** The change log's Undo, through Autopilot's own setter (in every build): the arm back on, paper only. */
    @Test fun theUndosSetterSwitchesItBackOnOnPaper() {
        savedOn24Oct()
        assertFalse(view().isArmed(eve))
        val park = AutoChange.mcxArmPark(eve, McxEveRules.LABEL, McxEvePark.REASON, McxEvePark.R83_TRADES, McxEvePark.R83_RS.toDouble())
        val said = Autopilot.apply(AutoChange.inverse(park, "undone by you"), pinConfirmed = false)
        assertNotNull(said); assertTrue(said!!, said.contains("on, paper only"))
        assertTrue(view().isArmed(eve))
    }
}
