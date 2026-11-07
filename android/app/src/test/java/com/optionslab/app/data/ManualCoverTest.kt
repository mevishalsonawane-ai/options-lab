package com.optionslab.app.data

import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.work.Alerts
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode

/**
 * 7 Nov (Boss: "if any manual order is placed, then stoploss and target (profit lock) order should be placed by the application"):
 * "Stop and target on my manual positions" ([ManualCover]) against the fake Kite. A live option bought by hand that nothing in the
 * app follows gets protectLive's SL at 80% of its average and a LIMIT sell at 140%, after two looks 20 s apart; an arm's or Pine's
 * position, one with Boss's own stop working, one whose protection Boss removed today are never touched; off or in Paper nothing
 * happens; three refusals are said loudly and it stops trying.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class ManualCoverTest : RobolectricTest() {
    private lateinit var kite: FakeKite
    private val sym = "NIFTY26N0925150CE"

    @Before fun up() {
        assumeFalse("IraGoldAlgo places no stop on manual positions", com.optionslab.app.BuildConfig.GOLD)
        kite = FakeKite()
        kite.instruments += FakeKite.Ins(12_345_679, sym, "NIFTY", Market.today().plusDays(2), 25_150.0, "CE", 65)
        kite.position(sym, 130, 125.35)
        kite.quote("NFO:$sym", 120.0, 119.95, 120.05)
        kite.login()
        AutomationSupport.liveSettings()
        Alerts.forgetPosted()
        DayOco.resetForTest()
        ManualCover.resetForTest(on = true)
    }

    @After fun down() {
        ManualCover.resetForTest(on = false)
        pineHeld(emptyMap())
        Market.testClock = null
        if (::kite.isInitialized) kite.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    /** One look, as the watch's pass makes it. */
    private fun look() = runBlocking { ManualCover.run(AppSettings.load()) }

    /** The market's clock [sec] seconds on (still ticking). */
    private fun later(sec: Long) {
        val at = Market.now().toInstant().plusSeconds(sec)
        Market.testClock = java.time.Clock.offset(java.time.Clock.system(com.optionslab.engine.IST), java.time.Duration.between(java.time.Instant.now(), at))
    }

    /** The market's clock at [h]:[m] IST today (still ticking). */
    private fun at(h: Int, m: Int) {
        val t = Market.today().atTime(h, m).atZone(com.optionslab.engine.IST).toInstant()
        Market.testClock = java.time.Clock.offset(java.time.Clock.system(com.optionslab.engine.IST), java.time.Duration.between(java.time.Instant.now(), t))
    }

    /** Two looks 25 s apart: what acts. */
    private fun twoLooks() { look(); later(25); look() }

    private fun writes() = kite.requests.filter { it.method != "GET" && !it.path.startsWith("/margins") && !it.path.startsWith("/charges") }
    private fun said(t: String) = Alerts.posted.any { it.text.contains(t) }

    /** Pine's live holdings as [ZerodhaSync.beliefs] reads them (memory). */
    @Suppress("UNCHECKED_CAST")
    private fun pineHeld(m: Map<Long, PineAuto.Held>) {
        val f = PineAuto::class.java.getDeclaredField("_held").apply { isAccessible = true }
        (f.get(null) as MutableStateFlow<Map<Long, PineAuto.Held>>).value = m
    }

    // ---- a manual position ---------------------------------------------------------------------------------------------------

    @Test fun aManualPositionGetsAStopAt80PercentAndATargetAt140AfterTwoLooks() {
        look()
        assertTrue("one look is never enough", writes().isEmpty())
        later(5); look()
        assertTrue("two looks under 20 s apart are not enough", writes().isEmpty())
        later(20); look()
        val placed = kite.placed
        assertEquals(listOf("SL", "LIMIT"), placed.map { it.form["order_type"] })
        assertEquals(listOf("SELL", "SELL"), placed.map { it.form["transaction_type"] })
        assertEquals(listOf("130", "130"), placed.map { it.form["quantity"] })
        assertEquals(listOf("NRML", "NRML"), placed.map { it.form["product"] })
        // 125.35 x 0.8 = 100.28, down to the tick; x 1.4 = 175.49, up to the tick.
        assertEquals(100.25, placed[0].form.getValue("trigger_price").toDouble(), 1e-9)
        assertEquals(175.5, placed[1].form.getValue("price").toDouble(), 1e-9)
        val p = runBlocking { Protections.active() }.single()
        assertTrue(p.live); assertEquals(sym, p.symbol); assertEquals(130, p.qty)
        assertEquals(100.25, p.stop!!, 1e-9); assertEquals(175.5, p.target!!, 1e-9)
        assertTrue(Alerts.posted.any { it.kind == Alerts.Kind.SUCCESS && it.text.contains("you bought 130 yourself") })
        assertTrue(Diag.lines().any { it.contains("[protection]") && it.contains("you bought 130 yourself") })
        assertEquals(1, ManualCover.tallyForTest().protectedN)
        assertTrue(ManualCover.line(), ManualCover.line().contains("on; today protected 1"))
        // Protected now: later looks place nothing more.
        val n = writes().size
        later(25); look(); later(25); look()
        assertEquals(n, writes().size)
    }

    @Test fun aQuantityChangedBetweenTheLooksStartsThemAgain() {
        look()
        kite.position(sym, 195, 124.0)                     // more bought by hand meanwhile
        later(25); look()
        assertTrue(writes().isEmpty())
        later(25); look()
        assertEquals(listOf("195", "195"), kite.placed.map { it.form["quantity"] })
    }

    @Test fun alreadyAtTheTargetOnlyTheStopIsPlacedAndSaid() {
        kite.quote("NFO:$sym", 180.0, 179.95, 180.05)
        twoLooks()
        assertEquals(listOf("SL"), kite.placed.map { it.form["order_type"] })
        assertTrue(said("only the stop was placed"))
    }

    @Test fun alreadyDown20PercentNothingIsPlacedAndBossIsToldOnce() {
        kite.quote("NFO:$sym", 95.0, 94.95, 95.05)
        twoLooks(); later(25); look(); later(25); look()
        assertTrue(writes().isEmpty())
        assertEquals(1, Alerts.posted.count { it.text.contains("no stop was placed") })
        assertEquals(1, ManualCover.tallyForTest().leftAlone)
    }

    // ---- never touched ----------------------------------------------------------------------------------------------------------

    @Test fun pinesOrAnArmsPositionIsNeverTouched() {
        pineHeld(mapOf(7L to PineAuto.Held(sym, "CE", 130, 65, 125.35, Market.today().toString(), live = true, kite = sym)))
        twoLooks(); later(25); look()
        assertTrue("followed by Pine: nothing placed", writes().isEmpty())
    }

    @Test fun aPositionTheAppsOwnArmOrderBoughtIsNeverTouched() {
        // Today's fill came from an arm's live order (its Zerodha tag), not yet in the arm's book.
        kite.orders["9002"] = FakeKite.Order("9002", sym, "NFO", "BUY", 130, "NRML", "LIMIT", 125.35, 0.0, "iraarm", "COMPLETE",
            filled = 130, avg = 125.35)
        twoLooks(); later(25); look()
        assertTrue(writes().isEmpty())
    }

    @Test fun aManualOrderFromTheAppsOwnScreenIsCovered() {
        // "iraalgo" is the app's hand-order tag: Boss's own buy.
        kite.orders["9003"] = FakeKite.Order("9003", sym, "NFO", "BUY", 130, "NRML", "LIMIT", 125.35, 0.0, "iraalgo", "COMPLETE",
            filled = 130, avg = 125.35)
        twoLooks()
        assertEquals(listOf("SL", "LIMIT"), kite.placed.map { it.form["order_type"] })
    }

    @Test fun bossesOwnWorkingStopOrGttMeansHandsOff() {
        kite.orders["9001"] = FakeKite.Order("9001", sym, "NFO", "SELL", 130, "NRML", "SL", 99.0, 100.0, "", "TRIGGER PENDING")
        twoLooks(); later(25); look()
        assertTrue("Boss's stop is working: nothing placed", writes().isEmpty())
        assertEquals(1, ManualCover.tallyForTest().leftAlone)
        kite.orders.remove("9001")
        kite.gtts[5_001L] = org.json.JSONObject().put("id", 5_001L).put("type", "single").put("status", "active")
            .put("created_at", "2026-11-07 10:00:00")
            .put("condition", org.json.JSONObject().put("exchange", "NFO").put("tradingsymbol", sym)
                .put("trigger_values", org.json.JSONArray().put(100.0)).put("last_price", 120.0))
            .put("orders", org.json.JSONArray())
        later(25); look(); later(25); look()
        assertTrue("an active GTT: nothing placed", writes().isEmpty())
    }

    @Test fun shortsFuturesAndCncAreNeverTouched() {
        kite.flat()
        kite.position(sym, -65, 125.35)
        kite.position("NIFTY26NOVFUT", 75, 25_100.0)
        kite.position("RELIANCE", 10, 1_400.0, product = "CNC", exchange = "NSE")
        twoLooks(); later(25); look()
        assertTrue(writes().isEmpty())
    }

    @Test fun aProtectionBossRemovedTodayIsNotAddedAgain() {
        twoLooks()
        val p = runBlocking { Protections.active() }.single()
        assertTrue(runBlocking { Protections.remove(p.id) }.startsWith("Protection removed"))
        val n = kite.placed.size
        later(25); look(); later(25); look(); later(25); look()
        assertEquals("removed by Boss today: respected", n, kite.placed.size)
        assertTrue(ManualCover.line(), ManualCover.line().contains("left alone 1"))
    }

    // ---- off, Paper, hours ------------------------------------------------------------------------------------------------------

    @Test fun switchedOffNothingHappens() {
        ManualCover.resetForTest(on = false)
        twoLooks(); later(25); look()
        assertTrue(writes().isEmpty())
    }

    @Test fun notReadYetNothingHappens() {
        ManualCover.resetForTest(on = true, loaded = false)
        twoLooks()
        assertTrue("a restart never acts before the switch is read", writes().isEmpty())
    }

    @Test fun inPaperModeNothingHappens() {
        AutomationSupport.liveSettings(live = false)
        twoLooks(); later(25); look()
        assertTrue(writes().isEmpty())
    }

    @Test fun outsideTheSessionNothingHappens() {
        at(15, 25); look(); later(25); look()
        at(9, 10); look(); later(25); look()
        assertTrue(writes().isEmpty())
        at(9, 16); look(); later(25); look()
        assertEquals("in the session", listOf("SL", "LIMIT"), kite.placed.map { it.form["order_type"] })
    }

    @Test fun onItsExpiryDayItIsLeftToTheExpirySquareOffFrom1500() {
        kite.instruments.clear()
        kite.instruments += FakeKite.Ins(12_345_679, sym, "NIFTY", Market.today(), 25_150.0, "CE", 65)
        runBlocking { Broker.instruments() }
        at(15, 1); look(); later(25); look(); later(25); look()
        assertTrue("the square-off closes it at 15:05: no stop and target a moment before", writes().isEmpty())
        at(14, 30); look(); later(25); look()
        assertEquals("earlier that day it is covered", listOf("SL", "LIMIT"), kite.placed.map { it.form["order_type"] })
    }

    // ---- refusals ----------------------------------------------------------------------------------------------------------------

    @Test fun threeRefusalsAreSaidLoudlyAndItStopsTrying() {
        repeat(3) { kite.nextPlace(FakeKite.Reply.INPUT_EXCEPTION) }
        twoLooks()
        assertEquals(1, kite.placed.size)
        assertFalse("not yet given up", said("is NOT protected"))
        later(25); look()
        later(25); look()
        assertEquals(3, kite.placed.size)
        assertTrue(Alerts.posted.any { it.kind == Alerts.Kind.ERROR && it.text.contains("is NOT protected") && it.text.contains("tried 3 times") })
        assertEquals(1, ManualCover.tallyForTest().failed)
        later(25); look(); later(25); look()
        assertEquals("it stops trying for the day", 3, kite.placed.size)
        assertTrue(runBlocking { Protections.active() }.isEmpty())
    }

    // ---- the switch -------------------------------------------------------------------------------------------------------------

    @Test fun theSwitchIsOnByDefaultOffNeedsNoPinOnTakesItAndIsSaved() {
        ManualCover.resetForTest(on = false, loaded = false)
        ManualCover.load()
        assertTrue("on by default", ManualCover.isOn())
        assertTrue(ManualCover.setOn(false).startsWith("Off:"))
        assertFalse(ManualCover.isOn())
        assertTrue(Diag.lines().any { it.contains("Stop and target on my manual positions: off (by you)") })
        assertTrue(ManualCover.setOn(true).contains("PIN"))
        assertFalse("never on again without the PIN", ManualCover.isOn())
        com.optionslab.app.security.SecurePrefs.flush()
        ManualCover.resetForTest(on = true, loaded = false)
        ManualCover.load()
        assertFalse("read back from the settings", ManualCover.isOn())
        assertTrue(ManualCover.setOn(true, pinConfirmed = true).startsWith("On:"))
        assertTrue(ManualCover.isOn())
    }

    @Test fun eraseReturnsTheSwitchToItsDefaultAndForgetsTheDay() {
        twoLooks()
        assertTrue(ManualCover.holdsForErase())
        ManualCover.wipe()
        assertFalse(ManualCover.holdsForErase())
        assertTrue(ManualCover.isOn())
    }
}
