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

    /**
     * This test's own entry: the LIVE_ENTRIES clearance naming exactly its two books. (Matched by its words, so an entry another test's
     * one-time change still had on its way - [Autopilot]'s log waits for the timeline's start - is never counted here.)
     */
    private fun entries() = Autopilot.book.value.entries.filter { it.change.kind == AutoChange.Kind.LIVE_ENTRIES && it.change.target == "live:liquidity" &&
        it.change.reason.endsWith("cleared now: BANKNIFTY 15-min, BANKNIFTY 5-min") }

    @Test fun theClearanceIsLoggedOnceItsUndoParksAndPuttingItBackTakesThePin() {
        org.junit.Assume.assumeTrue("the change timeline is in IraAlgo with Jarvis only", Autopilot.enabled())
        val books = listOf("liquidity15", "liquidity5")
        fun flags(v: Boolean) = JSONObject().apply { books.forEach { put(it, v) } }
        AutomationSupport.orbState(context, JSONObject().put("armed", flags(true)).put("auto", flags(true)).put("liveOk", flags(false))
            .put("migrated", JSONArray().put(OrbArms.OFF_LOSERS).put(com.optionslab.engine.orb.RetiredArms.MIGRATION).put(com.optionslab.engine.orb.RetiredArms.UNRETIRE)
                .put(com.optionslab.engine.orb.ParkedArms.MIGRATION).put(com.optionslab.engine.orb.FamilyPark.MIGRATION)
                // 20 Nov 2026 (minor fixes, item 7: this test failed on main): the MIDCPNIFTY books' 7 Oct join marked done too, so it never
                // switches them on beside this test's two books - the clearance then names exactly BANKNIFTY 15-min and 5-min.
                .put(com.optionslab.engine.orb.LiquidityRules.MIDCP_JOIN))
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

    /** 15 Nov (review): Boss lifting Liquidity's pause himself (his PIN) closes Autopilot's kept pause entry in the timeline. */
    @Test fun bossLiftingThePauseClosesAutopilotsPauseEntry() {
        org.junit.Assume.assumeTrue("the change timeline is in IraAlgo with Jarvis only", Autopilot.enabled())
        val day = Market.today()
        val pause = AutoChange.Change(AutoChange.Kind.PAPER_ENTRIES, "paper:liquidity", "on", AutoChange.PAUSED, "test: its record loses",
            AutoChange.Evidence(0, 0.0, com.optionslab.ira.AgentLessons.Confidence.STRONG), AutoChange.Risk.LOWER, AutoChange.Scope.PAPER)
        val (book, e) = AutoChange.record(AutoChange.Book(), pause, AutoChange.Status.KEPT, System.currentTimeMillis(), day)
        Autopilot.setForTest(book)
        assertTrue(SmartWorkers.pausePaperByAutopilot("liquidity") != null)
        assertTrue(OrbArms.liftLiquidityPark(true).startsWith("Liquidity's park to paper and pause are lifted"))
        assertFalse(SmartWorkers.reviewPaused("liquidity"))
        fun status() = Autopilot.book.value.entries.first { it.id == e.id }.status
        val deadline = System.currentTimeMillis() + 10_000
        while (status() != AutoChange.Status.UNDONE && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(AutoChange.Status.UNDONE, status())
        // Lifted by Boss himself: no 30-day rest - Autopilot may pause it again if it keeps drifting.
        assertEquals(AutoChange.LIFTED_BY_OWNER, Autopilot.book.value.entries.first { it.id == e.id }.note)
        assertFalse(Autopilot.book.value.rest.containsKey(pause.key))
    }
}
