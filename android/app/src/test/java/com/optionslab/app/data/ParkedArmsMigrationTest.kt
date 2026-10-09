package com.optionslab.app.data

import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.testing.TradeFixtures
import com.optionslab.engine.orb.LiquidityRules
import com.optionslab.engine.orb.ParkedArms
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode

/**
 * The 9 Oct parking (Boss's OK): on the update, once, Liquidity 15+5's FINNIFTY books and ORB Sweep are switched off on
 * paper terms - the other Liquidity books stay on with their Live clearance untouched, an open FINNIFTY position is still
 * managed, Liquidity's switch coming on again leaves FINNIFTY off, and Boss's "Switch FINNIFTY back on" brings it back on
 * paper. It never runs twice, and a new book has nothing to park.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class ParkedArmsMigrationTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()

    @Before fun up() {
        TradeFixtures.paperSettings()
        AutomationSupport.clearAlerts()
        OrbArms.testNow = AutomationSupport.earlierToday()
    }

    @After fun down() {
        AutomationSupport.realClocks()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private val books = LiquidityRules.BOOKS.map { it.source }
    private fun row() = runBlocking { OrbArms.view() }.arms.single { it.arm.source == "liquidity" }
    private fun arm(src: String) = runBlocking { OrbArms.view() }.arms.single { it.arm.source == src }
    private fun armed() = runBlocking { OrbArms.liquidityDay(OrbArms.testNow!!.toLocalDate()) }.first.associate { it.book to it.armed }

    /** A book saved on 8 Oct: every Liquidity book and ORB Sweep on, Liquidity cleared for Zerodha (Live, with the PIN). */
    private fun savedOn8Oct() {
        fun flags(v: Boolean) = JSONObject().apply { books.forEach { put(it, v) }; put("orb_sweep", v) }
        AutomationSupport.orbState(context, JSONObject()
            .put("armed", flags(true)).put("auto", flags(true)).put("liveOk", JSONObject().apply { books.forEach { put(it, true) } })
            .put("liqLots", 1)
            .put("migrated", JSONArray().put(OrbArms.OFF_LOSERS).put(com.optionslab.engine.orb.RetiredArms.MIGRATION)
                .put(com.optionslab.engine.orb.RetiredArms.UNRETIRE).put(LiquidityRules.MIDCP_JOIN).put(com.optionslab.engine.orb.LiquidityLots.ONE_LOT_MIGRATION))
            .put("positions", JSONArray()))
    }

    @Test fun theUpdateParksFinniftyAndOrbSweepOnceAndLeavesTheRestAsTheyWere() {
        savedOn8Oct()
        val a = armed()
        assertFalse(a.getValue("liquidity30_fin")); assertFalse(a.getValue("liquidity5_fin"))
        for (b in listOf("liquidity15", "liquidity5", "liquidity15_mid", "liquidity5_mid")) assertTrue(b, a.getValue(b))
        val r = row()
        assertTrue("Liquidity's switch stays on", r.armed)
        assertTrue("the other books' Live clearance is untouched", r.liveOk)
        assertEquals(listOf("FINNIFTY"), r.parked)
        assertTrue(r.status, r.status.contains("FINNIFTY 30-min: ${ParkedArms.PARKED}") && r.status.contains("FINNIFTY 5-min: ${ParkedArms.PARKED}"))
        val sweep = arm("orb_sweep")
        assertFalse(sweep.armed); assertFalse(sweep.liveOk)
        assertEquals(ParkedArms.PARKED, sweep.status)
        assertTrue(Diag.lines().toString(), Diag.lines().any { it.contains(ParkedArms.NOTICE) })
        // Once: Boss switches ORB Sweep back on; a restart leaves it on.
        runBlocking { OrbArms.setArmed("orb_sweep", true, automatic = true) }
        AutomationSupport.reloadFromDisk(OrbArms)
        assertTrue(arm("orb_sweep").armed)
        assertEquals(1, Diag.lines().count { it.contains(ParkedArms.NOTICE) })
    }

    @Test fun liquiditysSwitchLeavesFinniftyParkedUntilBossSwitchesItBackOn() {
        savedOn8Oct()
        runBlocking { OrbArms.setArmed("liquidity", false, automatic = true) }
        val on = runBlocking { OrbArms.setArmed("liquidity", true, automatic = true) }
        assertTrue(on, on.contains("FINNIFTY stays parked"))
        assertFalse(armed().getValue("liquidity30_fin"))
        assertTrue(armed().getValue("liquidity15"))
        // Boss's tap under the row: FINNIFTY's books on again, on paper, with the switch's automatic choice.
        val back = runBlocking { OrbArms.unparkLiquidity("FINNIFTY") }
        assertEquals("Liquidity 15+5 FINNIFTY is back on, on paper.", back)
        assertTrue(armed().getValue("liquidity30_fin")); assertTrue(armed().getValue("liquidity5_fin"))
        assertTrue(row().parked.isEmpty())
        assertEquals("Liquidity 15+5 FINNIFTY is not parked.", runBlocking { OrbArms.unparkLiquidity("FINNIFTY") })
        // A restart keeps it as Boss left it.
        AutomationSupport.reloadFromDisk(OrbArms)
        assertTrue(armed().getValue("liquidity5_fin"))
    }

    @Test fun unparkedWithTheSwitchOffItComesOnWithTheSwitch() {
        savedOn8Oct()
        runBlocking { OrbArms.setArmed("liquidity", false, automatic = true) }
        assertEquals("Liquidity 15+5 FINNIFTY is no longer parked: it comes on with Liquidity's switch.", runBlocking { OrbArms.unparkLiquidity("FINNIFTY") })
        assertFalse(armed().getValue("liquidity30_fin"))
        runBlocking { OrbArms.setArmed("liquidity", true, automatic = true) }
        assertTrue(armed().values.all { it })
    }

    @Test fun aNewBookHasNothingToPark() {
        runBlocking { OrbArms.setArmed("liquidity", true, automatic = true) }
        assertTrue(armed().values.all { it })
        assertTrue(row().parked.isEmpty())
        assertFalse(Diag.lines().any { it.contains(ParkedArms.NOTICE) })
    }
}
