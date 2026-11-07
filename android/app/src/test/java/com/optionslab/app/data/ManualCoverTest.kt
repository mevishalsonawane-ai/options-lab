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
 * app follows gets protectLive's SL at 95% of its average and a LIMIT sell at 110%, after two looks 20 s apart; an arm's or Pine's
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
        // 7 Nov, review: comfortably over the 5% stop (119.05) and its room (1% of the average), under the 10% target (137.90).
        kite.quote("NFO:$sym", 126.0, 125.95, 126.05)
        kite.login()
        AutomationSupport.liveSettings()
        Alerts.forgetPosted()
        // The banners' 20-second repeat filter is process-wide: an earlier test's identical words would hide this test's banner.
        com.optionslab.app.testing.Background.clearAlerts()
        DayOco.resetForTest()
        ManualCover.resetForTest(on = true)
    }

    @After fun down() {
        ManualCover.resetForTest(on = false)
        ManualCover.attachForTest(null)
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
        // 125.35 x 0.95 = 119.08, down to the tick; x 1.1 = 137.89, up to the tick.
        assertEquals(119.05, placed[0].form.getValue("trigger_price").toDouble(), 1e-9)
        assertEquals(137.9, placed[1].form.getValue("price").toDouble(), 1e-9)
        val p = runBlocking { Protections.active() }.single()
        assertTrue(p.live); assertEquals(sym, p.symbol); assertEquals(130, p.qty)
        assertEquals(119.05, p.stop!!, 1e-9); assertEquals(137.9, p.target!!, 1e-9)
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

    @Test fun alreadyDown5PercentNothingIsPlacedAndBossIsToldOnce() {
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

    /** 7 Nov, review: the start's read not landed yet: the pass reads the switch itself, and follows what is saved. */
    @Test fun notReadYetThePassReadsTheSwitchAndFollowsIt() {
        com.optionslab.app.security.SecurePrefs.put("g.manualCover.on", false)
        com.optionslab.app.security.SecurePrefs.flush()
        ManualCover.resetForTest(on = true, loaded = false)
        twoLooks()
        assertTrue("saved off: nothing", writes().isEmpty())
        assertFalse(ManualCover.isOn())
        assertTrue(ManualCover.view.value.loaded)
        com.optionslab.app.security.SecurePrefs.put("g.manualCover.on", true)
        com.optionslab.app.security.SecurePrefs.flush()
        ManualCover.resetForTest(on = false, loaded = false)
        later(25); look(); later(25); look()
        assertEquals("saved on (the default): covered", listOf("SL", "LIMIT"), kite.placed.map { it.form["order_type"] })
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
        assertEquals("a minute between attempts at least", 1, kite.placed.size)
        later(40); look()
        later(65); look()
        assertEquals(3, kite.placed.size)
        assertTrue(Alerts.posted.any { it.kind == Alerts.Kind.ERROR && it.text.contains("is NOT protected") && it.text.contains("tried 3 times") })
        assertEquals(1, ManualCover.tallyForTest().failed)
        later(65); look(); later(65); look()
        assertEquals("it stops trying for the day", 3, kite.placed.size)
        assertTrue(runBlocking { Protections.active() }.isEmpty())
    }

    @Test fun theGiveUpIsABannerAsWellAsANotificationWhenTheAppRuns() {
        ManualCover.attachForTest(context)
        repeat(3) { kite.nextPlace(FakeKite.Reply.INPUT_EXCEPTION) }
        twoLooks(); later(65); look(); later(65); look()
        assertEquals(3, kite.placed.size)
        assertEquals("one banner (the notification's own is not shown twice)", 1,
            Alerts.posted.count { it.kind == Alerts.Kind.ERROR && it.text.contains("is NOT protected") })
    }

    @Test fun aFailedPriceReadIsNoAttemptAndNoRefusal() {
        kite.down += "/quote"
        twoLooks(); later(65); look(); later(65); look(); later(65); look()
        assertTrue("no price: nothing placed", writes().isEmpty())
        assertFalse(said("is NOT protected"))
        kite.down -= "/quote"
        later(65); look()
        assertEquals(listOf("SL", "LIMIT"), kite.placed.map { it.form["order_type"] })
    }

    @Test fun justOverTheStopIsTooCloseNothingIsPlaced() {
        kite.quote("NFO:$sym", 120.0, 119.95, 120.05)                 // 0.95 over the 119.05 stop: under 1% of the average
        twoLooks(); later(25); look()
        assertTrue(writes().isEmpty())
        assertEquals(1, Alerts.posted.count { it.text.contains("no stop was placed") })
        kite.quote("NFO:$sym", 126.0, 125.95, 126.05)                 // back up: covered then
        later(25); look()
        assertEquals(listOf("SL", "LIMIT"), kite.placed.map { it.form["order_type"] })
    }

    // ---- 7 Nov, review: spreads, two products, a carried position, Boss's exit in between --------------------------------------

    @Test fun aWingIsLeftAloneWhileTheAccountIsShortAnOptionOnTheSameIndex() {
        kite.position("NIFTY26N1625000PE", -65, 80.0)                 // the short leg of a spread (any expiry)
        twoLooks(); later(25); look(); later(25); look()
        assertTrue("covering the wing alone could leave the short naked", writes().isEmpty())
        assertEquals("said once", 1, Alerts.posted.count { it.text.contains("left alone (part of a spread)") })
    }

    @Test fun aContractHeldAsBothMisAndNrmlIsLeftAloneAndSaidOnce() {
        kite.position(sym, 65, 125.0, product = "MIS")
        twoLooks(); later(25); look(); later(25); look()
        assertTrue(writes().isEmpty())
        assertEquals(1, Alerts.posted.count { it.text.contains("held as both MIS and NRML: set the stops yourself") })
    }

    @Test fun aCarriedPositionsProtectionRemovedYesterdayIsNotAddedAgain() {
        kite.flat()
        kite.overnight(sym, 130, 125.35)                              // NRML, held since yesterday
        val now = Market.now()
        val yesterday = now.toLocalDate().minusDays(1).atTime(12, 0).atZone(com.optionslab.engine.IST).toInstant()
        Market.testClock = java.time.Clock.offset(java.time.Clock.system(com.optionslab.engine.IST), java.time.Duration.between(java.time.Instant.now(), yesterday))
        val said = runBlocking { Protections.protectLive(sym, "NFO", "NRML", 130, 126.0, 119.0, null, 138.0) }
        assertTrue(said, said.startsWith("Protected"))
        val p = runBlocking { Protections.active() }.single()
        assertTrue(runBlocking { Protections.remove(p.id) }.startsWith("Protection removed"))   // Boss removed it yesterday
        Market.testClock = java.time.Clock.offset(java.time.Clock.system(com.optionslab.engine.IST), java.time.Duration.between(java.time.Instant.now(), now.toInstant()))
        val n = kite.placed.size
        twoLooks(); later(25); look()
        assertEquals("the same carried position: Boss's removal still holds", n, kite.placed.size)
        assertTrue("said as removed earlier, not today", Diag.lines().any { it.contains("its protection was removed earlier") })
    }

    @Test fun anIntradayBuyTodayIsCoveredAfterAnEarlierDaysRemoval() {
        kite.flat()
        kite.position(sym, 130, 125.35, product = "MIS")
        val now = Market.now()
        val yesterday = now.toLocalDate().minusDays(1).atTime(12, 0).atZone(com.optionslab.engine.IST).toInstant()
        Market.testClock = java.time.Clock.offset(java.time.Clock.system(com.optionslab.engine.IST), java.time.Duration.between(java.time.Instant.now(), yesterday))
        runBlocking { Protections.protectLive(sym, "NFO", "MIS", 130, 126.0, 119.0, null, 138.0) }
        runBlocking { Protections.remove(Protections.active().single().id) }
        Market.testClock = java.time.Clock.offset(java.time.Clock.system(com.optionslab.engine.IST), java.time.Duration.between(java.time.Instant.now(), now.toInstant()))
        val n = kite.placed.size
        twoLooks()
        assertEquals("a new day's intraday buy is covered", listOf("SL", "LIMIT"), kite.placed.drop(n).map { it.form["order_type"] })
    }

    @Test fun bossesOwnExitSentJustBeforePlacingIsRespected() {
        look(); later(25)
        // Boss's own stop arrives between the look's read and the placing (here: as the price is read fresh).
        kite.afterNext("/quote") {
            kite.orders["9101"] = FakeKite.Order("9101", sym, "NFO", "SELL", 130, "NRML", "SL", 110.0, 111.0, "", "TRIGGER PENDING")
        }
        look()
        assertTrue("re-read in the exit turn: nothing placed beside Boss's stop", writes().isEmpty())
    }

    // ---- 7 Nov, second review: hedges, the check in the lock, untracked exits, endedAt ----------------------------------------

    private fun autoProtected(): Protections.Item {
        twoLooks()
        return runBlocking { Protections.active() }.single { it.symbol == sym }.also { assertTrue("set by this switch", it.auto) }
    }

    @Test fun aWingBoughtFirstLosesItsAutomaticStopWhenTheShortFills() {
        ManualCover.attachForTest(context)
        val p = autoProtected()
        // Boss now sells the spread's short leg: the bought PE was its wing.
        kite.position("NIFTY26N1625000PE", -65, 80.0)
        later(25); look()
        assertEquals("one look is never enough", 1, runBlocking { Protections.active() }.size)
        later(25); look()
        assertTrue("taken off after two looks 20 s apart", runBlocking { Protections.active() }.isEmpty())
        assertEquals(com.optionslab.ira.ManualCoverRule.UNHEDGED, runBlocking { Protections.endedSince(0) }.last().note)
        assertEquals("CANCELLED", kite.order(p.stopOrderId!!).status)
        assertEquals("CANCELLED", kite.order(p.targetOrderId!!).status)
        assertEquals("said once, loudly", 1, Alerts.posted.count { it.kind == Alerts.Kind.ERROR && it.text.contains("removed because it is now part of a spread/hedge") })
        later(25); look()
        assertEquals(1, Alerts.posted.count { it.text.contains("removed because it is now part of a spread/hedge") })
    }

    @Test fun aWorkingOptionSellMakesItASpreadAndTakesOffOnlyAutomaticProtections() {
        // Boss's own protection on another NIFTY long, set by hand: never touched.
        val own = "NIFTY26N0925200CE"
        kite.instruments += FakeKite.Ins(12_345_680, own, "NIFTY", Market.today().plusDays(2), 25_200.0, "CE", 65)
        kite.position(own, 65, 100.0); kite.quote("NFO:$own", 101.0, 100.95, 101.05)
        assertTrue(runBlocking { Protections.protectLive(own, "NFO", "NRML", 65, 101.0, 90.0, null, null) }.startsWith("Protected"))
        val p = autoProtected()
        assertTrue(p.symbol == sym)
        // The short leg's sell is still working (it would open a short).
        kite.orders["9201"] = FakeKite.Order("9201", "NIFTY26N1625000PE", "NFO", "SELL", 65, "NRML", "LIMIT", 85.0, 0.0, "", "OPEN")
        later(25); look(); later(25); look()
        assertEquals("only Boss's own is left", listOf(own), runBlocking { Protections.active() }.map { it.symbol })
    }

    // 7 Nov, third review: leftover exits are never a short; none of these may take the automatic protection off.

    private fun neverUnhedged(what: String) {
        later(25); look(); later(25); look(); later(25); look()
        assertFalse(what, Alerts.posted.any { it.text.contains("removed because it is now part of a spread/hedge") })
        assertFalse(what, runBlocking { Protections.endedSince(0) }.any { it.note == com.optionslab.ira.ManualCoverRule.UNHEDGED })
    }

    @Test fun anOcoTargetStillWorkingAfterTheStopFilledIsNoShort() {
        val p = autoProtected()
        kite.fill(p.stopOrderId!!, 119.0)                            // (a) the stop filled; the target sells on until the next tick
        assertTrue(kite.order(p.targetOrderId!!).working)
        neverUnhedged("the OCO's other half")
    }

    @Test fun bossesLeftoverStopAfterHisTargetFilledIsNoShort() {
        autoProtected()
        // (b) Boss's own contract: held this morning, his target filled, his stop still resting in Kite.
        val b = "NIFTY26N0924900PE"
        kite.position(b, 0, 100.0); kite.dayBought["NFO:$b:NRML"] = 65; kite.daySold["NFO:$b:NRML"] = 65
        kite.orders["9401"] = FakeKite.Order("9401", b, "NFO", "SELL", 65, "NRML", "SL", 90.0, 91.0, "", "TRIGGER PENDING")
        neverUnhedged("Boss's leftover stop")
        assertEquals(1, runBlocking { Protections.active() }.size)
    }

    @Test fun aPartSoldByHandUnderTheAutomaticStopIsNoShort() {
        autoProtected()
        kite.position(sym, 65, 125.35)                               // (c) Boss sold 65 of 130; the app's stop and target are still 130
        neverUnhedged("the app's own stop over a part sold")
    }

    @Test fun switchedOffItStillTakesItsOwnOffWhenTheyBecomeASpread() {
        autoProtected()
        assertTrue(ManualCover.setOn(false).startsWith("Off:"))
        kite.position("NIFTY26N1625000PE", -65, 80.0)
        val n = kite.placed.size
        later(25); look(); later(25); look()
        assertTrue("taken off while switched off", runBlocking { Protections.active() }.isEmpty())
        assertEquals("never placed anything", n, kite.placed.size)
    }

    @Test fun switchedOffMidLookPlacesNothing() {
        look(); later(25)
        kite.afterNext("/quote") { ManualCover.setOn(false) }
        look()
        assertTrue(kite.placed.isEmpty())
        assertTrue(Diag.lines().any { it.contains("checked again before placing: switched off") })
    }

    @Test fun aHangingReadInsideTheLockNeverHoldsTheStopsLong() {
        look(); later(25)
        // The look's own orders read goes through; the next one (inside the protections' lock) never answers.
        kite.afterNext("/quote") { kite.afterNext("/orders") { kite.hang += "/orders" } }
        val t = Thread { look() }.apply { start() }
        com.optionslab.app.testing.Background.await("the hang") { "/orders" in kite.hang }
        Thread.sleep(300)
        val t0 = System.nanoTime()
        runBlocking { Protections.tick() }
        val ms = (System.nanoTime() - t0) / 1_000_000
        t.join(30_000)
        kite.hang.clear()
        assertTrue("the stops' watch waited $ms ms", ms < 3_500)
        assertTrue(kite.placed.isEmpty())
        assertTrue(Diag.lines().any { it.contains("checked again before placing: Zerodha's orders not read") })
    }

    // ---- 7 Nov, fourth review -------------------------------------------------------------------------------------------------

    @Test fun switchedOffWithNoneOfItsOwnItReadsNothingMore() {
        ManualCover.resetForTest(on = false)
        kite.requests.clear()
        twoLooks(); later(25); look()
        assertTrue("no orders read", kite.requests.none { it.path == "/orders" })
        assertTrue("no holdings read", kite.requests.none { it.path == "/portfolio/holdings" })
        assertTrue(writes().isEmpty())
    }

    @Test fun theSpreadDecisionReadsThePositionsAfresh() {
        kite.position("NIFTY26N1625000PE", -65, 80.0)
        kite.requests.clear()
        look()
        // The pass's shared read (outside a pass: one read), then a fresh read after the orders for the spread decision.
        val reads = kite.requests.map { it.path }.filter { it == "/orders" || it == "/portfolio/positions" }
        assertEquals(listOf("/portfolio/positions", "/orders", "/portfolio/positions"), reads)
        assertEquals(1, Alerts.posted.count { it.text.contains("left alone (part of a spread)") })
    }

    @Test fun aLookCancelledInsideTheLockGivesTheTurnBack() {
        look(); later(25)
        kite.afterNext("/quote") { kite.afterNext("/orders") { kite.hang += "/orders" } }
        runBlocking { kotlinx.coroutines.withTimeoutOrNull(1_500) { ManualCover.run(AppSettings.load()) } }
        kite.hang.clear()
        assertTrue("the turn was given back: ${ExitCoordinator.busyForTest()}", ExitCoordinator.busyForTest().isEmpty())
        assertTrue(kite.placed.isEmpty())
    }

    @Test fun aSpreadsWorkingShortSellLeavesANewWingAlone() {
        kite.orders["9202"] = FakeKite.Order("9202", "NIFTY26N1625000PE", "NFO", "SELL", 65, "NRML", "LIMIT", 85.0, 0.0, "", "OPEN")
        twoLooks(); later(25); look()
        assertTrue(kite.placed.isEmpty())
        assertEquals(1, Alerts.posted.count { it.text.contains("left alone (part of a spread)") })
    }

    @Test fun anIndexFutureMakesTheOptionAHedge() {
        kite.position("NIFTY26NOVFUT", -75, 25_100.0)
        twoLooks(); later(25); look()
        assertTrue(writes().isEmpty())
        assertEquals("said once", 1, Alerts.posted.count { it.text.contains("left alone (a hedge") })
    }

    @Test fun aStockOptionIsAHedgeWhileTheStockIsHeld() {
        kite.flat()
        val opt = "RELIANCE26NOV1400CE"
        kite.instruments += FakeKite.Ins(12_345_681, opt, "RELIANCE", Market.today().plusDays(20), 1_400.0, "CE", 500)
        kite.position(opt, 500, 20.0); kite.quote("NFO:$opt", 21.0, 20.95, 21.05)
        kite.holdings += FakeKite.Holding("RELIANCE", 10, 1_400.0, 1_401.0)
        twoLooks(); later(25); look()
        assertTrue(writes().isEmpty())
        assertEquals(1, Alerts.posted.count { it.text.contains("left alone (a hedge") })
        kite.holdings.clear()
        // Not a hedge any more: covered after its two looks.
        later(25); look(); later(25); look()
        assertEquals("no stock: covered", listOf("SL", "LIMIT"), kite.placed.map { it.form["order_type"] })
    }

    @Test fun aGttBossSetJustBeforePlacingIsSeenInsideTheLock() {
        look(); later(25)
        kite.afterNext("/quote") {
            kite.gtts[5_101L] = org.json.JSONObject().put("id", 5_101L).put("type", "single").put("status", "active")
                .put("created_at", "2026-11-07 10:00:00")
                .put("condition", org.json.JSONObject().put("exchange", "NFO").put("tradingsymbol", sym)
                    .put("trigger_values", org.json.JSONArray().put(110.0)).put("last_price", 126.0))
                .put("orders", org.json.JSONArray())
        }
        look()
        assertTrue("checked again in the turn: nothing placed beside Boss's GTT", writes().isEmpty())
        assertTrue(Diag.lines().any { it.contains("checked again before placing: a GTT on it is active") })
        assertFalse("never a refusal", said("is NOT protected"))
    }

    @Test fun lessHeldWhenCheckedAgainPlacesNothingAndSaysSo() {
        look(); later(25)
        // Boss sold half by hand between the fresh read and the check in the lock.
        kite.afterNext("/quote") { kite.position(sym, 65, 125.35) }
        look()
        assertTrue(kite.placed.isEmpty())
        assertTrue(Diag.lines().any { it.contains("Zerodha holds less than seen (65 of 130") })
        // The next looks start over on 65 and cover that.
        later(25); look(); later(65); look()
        assertEquals(listOf("65", "65"), kite.placed.map { it.form["quantity"] })
    }

    @Test fun anUntrackedAppStopIsSaidLoudlyAndNotTakenForBosss() {
        kite.orders["9301"] = FakeKite.Order("9301", sym, "NFO", "SELL", 130, "NRML", "SL", 118.0, 118.5, "iraprotect", "TRIGGER PENDING")
        twoLooks(); later(25); look()
        assertTrue(writes().isEmpty())
        assertEquals("once", 1, Alerts.posted.count { it.kind == Alerts.Kind.ERROR && it.text.contains("untracked") && it.text.contains("Check Kite's order book") })
        assertTrue(Diag.lines().any { it.contains("may be resting at Zerodha untracked") })
    }

    @Test fun theWatchKicksItInItsOwnJob() {
        assertTrue(ManualCover.kick(AppSettings.load()))
        com.optionslab.app.testing.Background.await("the first look") { !ManualCover.kickRunningForTest() }
        later(25)
        assertTrue(ManualCover.kick(AppSettings.load()))
        com.optionslab.app.testing.Background.await("the second look") { !ManualCover.kickRunningForTest() }
        assertEquals(listOf("SL", "LIMIT"), kite.placed.map { it.form["order_type"] })
        // Switched off, it still looks while one of its own protections is active (only ever to take it off) ...
        ManualCover.resetForTest(on = false)
        assertTrue("switched off with its own protection: started", ManualCover.kick(AppSettings.load()))
        com.optionslab.app.testing.Background.await("the look") { !ManualCover.kickRunningForTest() }
        assertEquals("placed nothing more", 2, kite.placed.size)
        // ... and not at all without one.
        runBlocking { Protections.remove(Protections.active().single().id) }
        assertFalse("switched off, none of its own: not started", ManualCover.kick(AppSettings.load()))
    }

    @Test fun theLoudNoticesUseTheAlwaysShownChannelWithOneBanner() {
        com.optionslab.app.testing.Background.grantNotifications(context)
        com.optionslab.app.security.SecurePrefs.put("n.other", false)
        ManualCover.attachForTest(context)
        repeat(3) { kite.nextPlace(FakeKite.Reply.INPUT_EXCEPTION) }
        twoLooks(); later(65); look(); later(65); look()
        val shown = com.optionslab.app.testing.Background.notifications(context).allNotifications
            .filter { com.optionslab.app.testing.Background.text(it)?.contains("is NOT protected") == true }
        assertEquals("shown even with other alerts off", 1, shown.size)
        assertEquals(1, Alerts.posted.count { it.text.contains("is NOT protected") })
    }

    @Test fun anEndedProtectionKeepsWhenItEndedAndItsOriginAcrossARestart() {
        val p = autoProtected()
        val t0 = Market.now().toInstant().toEpochMilli()
        runBlocking { Protections.remove(p.id) }
        Protections.forgetMemoryForTest()
        val ended = runBlocking { Protections.endedSince(t0 - 1_000) }.single()
        assertEquals("removed", ended.note)
        assertTrue("auto kept", ended.auto)
        assertTrue("endedAt stamped when it ended", ended.endedAt!! >= t0)
    }

    @Test fun aRecentRemovalIsKeptBeyondTheFiftyFinished() {
        assertTrue(runBlocking { Protections.protectLive(sym, "NFO", "NRML", 130, 126.0, 119.0, null, null) }.startsWith("Protected"))
        runBlocking { Protections.remove(Protections.active().single().id) }
        // 55 more, each replacing the last (a natural end): the removal is older than the last 50 finished.
        repeat(55) { runBlocking { Protections.protectLive(sym, "NFO", "NRML", 130, 126.0, 119.0, null, null) } }
        Protections.forgetMemoryForTest()
        assertTrue("Boss's removal of the last week is kept", runBlocking { Protections.endedSince(0) }.any { it.note == "removed" })
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
