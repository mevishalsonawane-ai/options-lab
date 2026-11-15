package com.optionslab.app.data

import com.optionslab.app.ira.Autopilot
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.testing.TradeFixtures
import com.optionslab.ira.AutoChange
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode

/**
 * 15 Nov (Boss: "Liquidity should be always working if i have swtich on it on dashboard, no extra pin based approval needed"): the
 * update's one-time clearance of the Liquidity books that are on is in the change timeline, once, as Boss's; its Undo parks Liquidity's
 * live entries to paper (no PIN: it lowers risk), and putting them back takes the PIN.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class LiquiditySwitchTimelineTest : RobolectricTest() {
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
    }

    private fun entries() = Autopilot.book.value.entries.filter { it.change.kind == AutoChange.Kind.LIVE_ENTRIES && it.change.target == "live:liquidity" }

    @Test fun theClearanceIsLoggedOnceItsUndoParksAndPuttingItBackTakesThePin() {
        org.junit.Assume.assumeTrue("the change timeline is in IraAlgo with Jarvis only", Autopilot.enabled())
        val books = listOf("liquidity15", "liquidity5")
        fun flags(v: Boolean) = JSONObject().apply { books.forEach { put(it, v) } }
        AutomationSupport.orbState(context, JSONObject().put("armed", flags(true)).put("auto", flags(true)).put("liveOk", flags(false))
            .put("migrated", JSONArray().put(OrbArms.OFF_LOSERS).put(com.optionslab.engine.orb.RetiredArms.MIGRATION).put(com.optionslab.engine.orb.RetiredArms.UNRETIRE)
                .put(com.optionslab.engine.orb.ParkedArms.MIGRATION).put(com.optionslab.engine.orb.FamilyPark.MIGRATION))
            .put("positions", JSONArray()))
        assertTrue(runBlocking { OrbArms.liquidityLiveCleared() }.let { it.getValue("liquidity15") && it.getValue("liquidity5") })
        val deadline = System.currentTimeMillis() + 10_000
        while (entries().isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val e = entries().single()
        assertEquals("your choice", e.by)
        assertEquals(AutoChange.Status.KEPT, e.status)
        assertTrue(e.change.reason, e.change.reason.contains("BANKNIFTY 15-min") && e.change.reason.contains("BANKNIFTY 5-min"))
        // Read again: never logged twice.
        AutomationSupport.reloadFromDisk(OrbArms)
        runBlocking { OrbArms.liquidityLiveCleared() }
        Thread.sleep(200)
        assertEquals(1, entries().size)
        // Its Undo: Liquidity's live entries parked to paper, at once, no PIN (it lowers risk).
        val said = Autopilot.undo(e.id)
        assertTrue(said, said.startsWith("Undone"))
        assertTrue("parked to paper", SmartWorkers.reviewParked("liquidity"))
        // Putting them back raises live risk: never without the PIN.
        assertNull(Autopilot.apply(e.change, pinConfirmed = false, byBoss = true))
        assertTrue(SmartWorkers.reviewParked("liquidity"))
        assertTrue(Autopilot.apply(e.change, pinConfirmed = true, byBoss = true) != null)
        assertFalse(SmartWorkers.reviewParked("liquidity"))
    }
}
