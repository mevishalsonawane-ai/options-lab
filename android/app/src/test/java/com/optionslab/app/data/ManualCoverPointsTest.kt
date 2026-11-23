package com.optionslab.app.data

import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.Background
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.work.Alerts
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode

/**
 * Boss, 23 Nov 2026 ("I want you to check live orders placed manually in kite immediately, and set a target of 10 points and stoploss
 * of 10points, a button, where i could stop/start the functionality"), against the fake Kite:
 *  - a buy placed in Kite that Zerodha reports COMPLETE on the stream is covered at once (one look in the background, no 20 s wait): an
 *    SL 10 points under the average, a target 10 points over it (intraday: watched by the app);
 *  - a part fill waits for its COMPLETE (or for the rest cancelled: what is held is covered); a fill Zerodha's positions do not show yet
 *    is looked at again in 2 s;
 *  - nothing is placed when something follows it, Boss's own sell is working, or a GTT on it is active;
 *  - Boss's points are saved on this phone only, widening the stop in Live takes the PIN, and a protection already placed keeps its levels;
 *  - Boss's own stop comes first: a stop of the app's he moved in Kite is adopted and never moved back; his own sell placed beside the
 *    app's takes the app's out (marked first, confirmed out), never two exits.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class ManualCoverPointsTest : RobolectricTest() {
    private lateinit var kite: FakeKite
    private val sym = "NIFTY26N0925150CE"

    @Before fun up() {
        assumeFalse("IraGoldAlgo places no stop on manual positions", com.optionslab.app.BuildConfig.GOLD)
        kite = FakeKite()
        kite.instruments += FakeKite.Ins(12_345_679, sym, "NIFTY", Market.today().plusDays(2), 25_150.0, "CE", 65)
        kite.quote("NFO:$sym", 126.0, 125.95, 126.05)
        kite.login()
        AutomationSupport.liveSettings()
        Alerts.forgetPosted()
        Background.clearAlerts()
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

    // ---- helpers ------------------------------------------------------------------------------------------------------------

    private fun look() = runBlocking { ManualCover.run(AppSettings.load()) }
    private fun tick() = runBlocking { Protections.tick() }

    private fun later(sec: Long) {
        val at = Market.now().toInstant().plusSeconds(sec)
        Market.testClock = java.time.Clock.offset(java.time.Clock.system(com.optionslab.engine.IST), java.time.Duration.between(java.time.Instant.now(), at))
    }

    private fun at(h: Int, m: Int) {
        val t = Market.today().atTime(h, m).atZone(com.optionslab.engine.IST).toInstant()
        Market.testClock = java.time.Clock.offset(java.time.Clock.system(com.optionslab.engine.IST), java.time.Duration.between(java.time.Instant.now(), t))
    }

    private fun writes() = kite.requests.filter { it.method != "GET" && !it.path.startsWith("/margins") && !it.path.startsWith("/charges") }
    private fun said(t: String) = Alerts.posted.any { it.text.contains(t) }

    /** Zerodha's order update on the stream, as Kite Connect pushes it (no tag: placed in Kite). */
    private fun update(id: String, status: String, filled: Int, qty: Int = 130, product: String = "MIS", side: String = "BUY", tag: String? = null,
                       symbol: String = sym) {
        KiteStream.textForTest("""{"type":"order","data":{"order_id":"$id","status":"$status","status_message":null,"tradingsymbol":"$symbol",""" +
            """"exchange":"NFO","product":"$product","transaction_type":"$side","quantity":$qty,"filled_quantity":$filled,""" +
            """"pending_quantity":${qty - filled},"average_price":125.35,"tag":${tag?.let { "\"$it\"" } ?: "null"}}}""")
    }

    /** The look an update asked for (and any it asked for after) done. */
    private fun settled() = Background.await("the look the update asked for") { !ManualCover.lookNowPendingForTest() }

    /** Pine's live holdings as [ZerodhaSync.beliefs] reads them (memory). */
    @Suppress("UNCHECKED_CAST")
    private fun pineHeld(m: Map<Long, PineAuto.Held>) {
        val f = PineAuto::class.java.getDeclaredField("_held").apply { isAccessible = true }
        (f.get(null) as MutableStateFlow<Map<Long, PineAuto.Held>>).value = m
    }

    /** A carried-style (NRML) manual position covered by the watch's two looks: the app's SL and LIMIT target resting. */
    private fun autoProtected(qty: Int = 130): Protections.Item {
        kite.position(sym, qty, 125.35)
        look(); later(25); look()
        return runBlocking { Protections.active() }.single { it.symbol == sym }.also { assertTrue("set by this switch", it.auto) }
    }

    // ---- the levels -------------------------------------------------------------------------------------------------------------

    @Test fun theDefaultIsTenPointsEachWayOnThePremium() {
        kite.position(sym, 130, 120.0)
        kite.quote("NFO:$sym", 121.0, 120.95, 121.05)
        look(); later(25); look()
        val placed = kite.placed
        assertEquals(listOf("SL", "LIMIT"), placed.map { it.form["order_type"] })
        assertEquals("bought at 120: stop 110", 110.0, placed[0].form.getValue("trigger_price").toDouble(), 1e-9)
        assertEquals("target 130", 130.0, placed[1].form.getValue("price").toDouble(), 1e-9)
        assertTrue(said("a stop 10 points under your average and a target sell 10 points over it"))
        assertTrue(ManualCover.view.value.last, ManualCover.view.value.last.startsWith("Protected: $sym (130), stop 110.00 · target 130.00"))
    }

    @Test fun bossesOwnPointsAreUsedAndAProtectionAlreadyPlacedKeepsItsLevels() {
        assertTrue(ManualCover.setPoints(15.0, 25.0, pinConfirmed = true).startsWith("Saved"))
        val p = autoProtected()
        assertEquals("125.35 - 15", 110.35, p.stop!!, 1e-9)
        assertEquals("125.35 + 25", 150.35, p.target!!, 1e-9)
        assertEquals(110.35, kite.placed[0].form.getValue("trigger_price").toDouble(), 1e-9)
        // New points later: the protection in place keeps its levels, nothing at Zerodha is changed.
        val n = writes().size
        assertTrue(ManualCover.setPoints(5.0, 5.0).startsWith("Saved"))
        tick(); later(25); look()
        assertEquals("nothing modified or placed", n, writes().size)
        assertEquals(110.35, runBlocking { Protections.active() }.single().stop!!, 1e-9)
    }

    /** An option too cheap for a 10-point stop keeps the 5% stop, and the card says so. */
    @Test fun aCheapOptionGetsTheFivePercentStopAndSaysSo() {
        kite.position(sym, 130, 8.0)
        kite.quote("NFO:$sym", 8.0, 7.95, 8.05)
        look(); later(25); look()
        assertEquals("8.00 x 0.95", 7.6, kite.placed[0].form.getValue("trigger_price").toDouble(), 1e-9)
        assertEquals("8 + 10", 18.0, kite.placed[1].form.getValue("price").toDouble(), 1e-9)
        assertTrue(said("too cheap for a 10-point stop"))
        assertTrue(ManualCover.view.value.last, ManualCover.view.value.last.contains("5% stop: too cheap for a 10-point stop"))
    }

    // ---- the settings -----------------------------------------------------------------------------------------------------------

    @Test fun thePointsAreSavedOnThisPhoneOnlyAndWideningTheStopInLiveTakesThePin() {
        assertFalse("never written to or taken from a backup", com.optionslab.ira.Upkeep.carried("g.manualCover.stopPts"))
        assertFalse(com.optionslab.ira.Upkeep.carried("g.manualCover.targetPts"))
        assertEquals(10.0, ManualCover.stopPoints(), 0.0); assertEquals(10.0, ManualCover.targetPoints(), 0.0)
        // Live: a wider stop needs the PIN; nothing changes without it.
        assertTrue(ManualCover.setPoints(20.0, 10.0).contains("PIN"))
        assertEquals(10.0, ManualCover.stopPoints(), 0.0)
        // Narrower and any target change: one tap.
        assertTrue(ManualCover.setPoints(8.0, 30.0).startsWith("Saved"))
        assertEquals(8.0, ManualCover.stopPoints(), 0.0); assertEquals(30.0, ManualCover.targetPoints(), 0.0)
        assertTrue(ManualCover.setPoints(20.0, 30.0, pinConfirmed = true).startsWith("Saved"))
        assertEquals(20.0, ManualCover.stopPoints(), 0.0)
        assertTrue(Diag.lines().any { it.contains("stop 20 points, target 30 points (by you)") })
        // Out of bounds: brought inside 1-500.
        assertTrue(ManualCover.setPoints(0.0, 9_999.0).contains("kept between 1 and 500"))
        assertEquals(1.0, ManualCover.stopPoints(), 0.0); assertEquals(500.0, ManualCover.targetPoints(), 0.0)
        // Read back from the settings after a restart.
        com.optionslab.app.security.SecurePrefs.flush()
        ManualCover.resetForTest(on = true, loaded = false)
        ManualCover.load()
        assertEquals(1.0, ManualCover.stopPoints(), 0.0); assertEquals(500.0, ManualCover.targetPoints(), 0.0)
        assertEquals(1.0, ManualCover.view.value.stopPoints, 0.0)
        // Paper: a wider stop is one tap (the app places nothing in Paper).
        AutomationSupport.liveSettings(live = false)
        assertTrue(ManualCover.setPoints(12.0, 10.0).startsWith("Saved"))
        assertEquals(12.0, ManualCover.stopPoints(), 0.0)
        // A bad saved value never makes a stop at or over the price.
        com.optionslab.app.security.SecurePrefs.put("g.manualCover.stopPts", -7.0)
        com.optionslab.app.security.SecurePrefs.flush()
        ManualCover.resetForTest(on = true, loaded = false)
        ManualCover.load()
        assertEquals(1.0, ManualCover.stopPoints(), 0.0)
        // Erase everything: back to 10 and 10.
        ManualCover.wipe()
        assertEquals(10.0, ManualCover.stopPoints(), 0.0); assertEquals(10.0, ManualCover.targetPoints(), 0.0)
        assertFalse(ManualCover.holdsForErase())
    }

    // ---- at once, from Zerodha's order update -----------------------------------------------------------------------------------

    @Test fun aKiteBuyReportedCompleteIsCoveredAtOnceWithoutTheTwentySecondWait() {
        val id = kite.boughtInKite(sym, 130, 125.35)
        // The watch's own look alone: one sighting, nothing placed (the two looks).
        look()
        assertTrue(writes().isEmpty())
        // Zerodha says it filled: covered in one background look, the clock never moved on.
        update(id, "COMPLETE", 130)
        Background.await("covered at once") { kite.placed.isNotEmpty() }
        settled()
        val sl = kite.placed.single()
        assertEquals("SL", sl.form["order_type"]); assertEquals("SELL", sl.form["transaction_type"]); assertEquals("130", sl.form["quantity"])
        assertEquals("MIS", sl.form["product"])
        assertEquals(115.35, sl.form.getValue("trigger_price").toDouble(), 1e-9)
        val p = runBlocking { Protections.active() }.single()
        assertTrue(p.auto); assertTrue("intraday: the target is the app's", p.appTarget); assertEquals(135.35, p.target!!, 1e-9)
        assertTrue(ManualCover.view.value.last, ManualCover.view.value.last.contains("target 135.35 (watched by the app)"))
        // Later looks place nothing more.
        later(25); look(); later(25); look()
        assertEquals(1, kite.placed.size)
    }

    /** The update's handler only queues: no switch, nothing queued; the app's own orders and sales never queue a look. */
    @Test fun onlyAnUntaggedBuyDoneFillingQueuesALook() {
        val id = kite.boughtInKite(sym, 130, 125.35)
        update(id, "COMPLETE", 130, tag = "iraarm")
        update(id, "COMPLETE", 130, side = "SELL")
        update(id, "COMPLETE", 130, tag = "iraalgo")
        assertFalse("nothing queued", ManualCover.lookNowPendingForTest())
        ManualCover.resetForTest(on = false)
        update(id, "COMPLETE", 130)
        assertFalse("switched off: nothing queued", ManualCover.lookNowPendingForTest())
        assertTrue(writes().isEmpty())
    }

    @Test fun aPartFillWaitsForItsCompleteThenAllOfItIsCovered() {
        val id = kite.restsInKite(sym, "BUY", 130, 125.35)
        kite.fillPart(id, 65, 125.35)
        update(id, "OPEN", 65)
        assertFalse("a part fill queues nothing", ManualCover.lookNowPendingForTest())
        look(); later(25); look()
        assertTrue("its buy still working: left alone", writes().isEmpty())
        kite.fillPart(id, 65, 125.35)
        update(id, "COMPLETE", 130)
        Background.await("covered once complete") { kite.placed.isNotEmpty() }
        settled()
        assertEquals("130", kite.placed.single().form["quantity"])
    }

    @Test fun aPartFillWhoseRestWasCancelledIsCoveredForWhatIsHeld() {
        val id = kite.restsInKite(sym, "BUY", 130, 125.35)
        kite.fillPart(id, 65, 125.35)
        kite.order(id).status = "CANCELLED"
        update(id, "CANCELLED", 65)
        Background.await("covered for what is held") { kite.placed.isNotEmpty() }
        settled()
        assertEquals("65", kite.placed.single().form["quantity"])
    }

    /** Zerodha's positions trail the fill: looked at again in 2 s, covered once they show it (never on a read without it). */
    @Test fun aFillThePositionsDoNotShowYetIsLookedAtAgainSoon() {
        kite.positionsTrailReads = 3
        val id = kite.boughtInKite(sym, 130, 125.35)
        update(id, "COMPLETE", 130)
        Background.await("covered once the positions show it", timeoutMs = 20_000) { kite.placed.isNotEmpty() }
        settled()
        assertEquals("130", kite.placed.single().form["quantity"])
    }

    /** Review: Zerodha's update comes before its order book shows the buy done: never taken for Boss's working order, covered at once. */
    @Test fun aBuyTheOrderBookStillShowsWorkingIsCoveredOnceTheStreamSaysItFilled() {
        val id = kite.restsInKite(sym, "BUY", 130, 125.35)
        kite.position(sym, 130, 125.35, product = "MIS")
        update(id, "COMPLETE", 130)
        Background.await("covered at once") { kite.placed.isNotEmpty() }
        settled()
        assertEquals("130", kite.placed.single().form["quantity"])
        assertFalse("never said left alone for its own buy", ManualCover.view.value.last.startsWith("Left alone"))
    }

    /** Review: an erase while a retry waits leaves nothing waiting, and the next update is looked at at once. */
    @Test fun anEraseWhileARetryWaitsNeverBlocksTheNextUpdate() {
        kite.positionsTrailReads = 1
        val id = kite.boughtInKite(sym, 130, 125.35)
        val asked = ManualCover.lookNowAskedForTest()
        update(id, "COMPLETE", 130)
        Background.await("the retry asked for") { ManualCover.lookNowAskedForTest() >= asked + 2 }
        ManualCover.resetForTest(on = true)
        Background.await("nothing left waiting") { !ManualCover.lookNowPendingForTest() }
        assertTrue(writes().isEmpty())
        update(id, "COMPLETE", 130)
        Background.await("covered at once") { kite.placed.isNotEmpty() }
        settled()
    }

    @Test fun nothingIsPlacedWhenSomethingFollowsItOrBossHasHisOwnSellOrAGtt() {
        // Pine follows it.
        pineHeld(mapOf(7L to PineAuto.Held(sym, "CE", 130, 65, 125.35, Market.today().toString(), live = true, kite = sym)))
        val id = kite.boughtInKite(sym, 130, 125.35)
        update(id, "COMPLETE", 130)
        settled()
        assertTrue("followed by Pine", writes().isEmpty())
        pineHeld(emptyMap())
        // Boss's own stop working in Kite.
        val own = kite.restsInKite(sym, "SELL", 130, 110.0, type = "SL")
        update(id, "COMPLETE", 130)
        settled()
        assertTrue("Boss's own sell working", writes().isEmpty())
        kite.order(own).status = "CANCELLED"
        // An active GTT on it.
        kite.gtts[5_001L] = org.json.JSONObject().put("id", 5_001L).put("type", "single").put("status", "active")
            .put("created_at", "2026-11-23 10:00:00")
            .put("condition", org.json.JSONObject().put("exchange", "NFO").put("tradingsymbol", sym)
                .put("trigger_values", org.json.JSONArray().put(110.0)).put("last_price", 126.0))
            .put("orders", org.json.JSONArray())
        update(id, "COMPLETE", 130)
        settled()
        assertTrue("a GTT active on it", writes().isEmpty())
    }

    // ---- Boss's own stop comes first ------------------------------------------------------------------------------------------

    /** Boss moves the app's stop and target in Kite: adopted, said once, never moved back (the overnight GTT rests at his levels). */
    @Test fun aStopBossMovedInKiteIsAdoptedAndNeverMovedBack() {
        val p = autoProtected()
        kite.order(p.stopOrderId!!).apply { trigger = 112.0; price = 111.5 }
        kite.order(p.targetOrderId!!).price = 140.0
        later(65)
        val n = writes().size
        tick(); tick(); tick()
        val now = runBlocking { Protections.active() }.single()
        assertEquals(112.0, now.stop!!, 1e-9); assertEquals(140.0, now.target!!, 1e-9)
        assertEquals("nothing modified back", n, writes().size)
        assertEquals("said once", 1, Alerts.posted.count { it.text.contains("you moved its automatic stop to 112.00 and target to 140.00 in Kite") })
        assertEquals(112.0, kite.order(p.stopOrderId!!).trigger, 0.0)
        // The close: Zerodha ends the DAY orders; the GTT that holds it overnight is at Boss's levels, never the app's first ones.
        at(15, 45)
        listOfNotNull(p.stopOrderId, p.targetOrderId).forEach { kite.order(it).status = "CANCELLED" }
        tick()
        val g = kite.gtts.values.single()
        val trig = g.getJSONObject("condition").getJSONArray("trigger_values")
        assertEquals(listOf(112.0, 140.0), (0 until trig.length()).map { trig.getDouble(it) })
    }

    /** Boss places his own stop after the app's: the app's comes out (marked as its own first, confirmed out), only his remains. */
    @Test fun bossesOwnStopPlacedBesideTheAppsTakesTheAppsOut() {
        val p = autoProtected()
        val own = kite.restsInKite(sym, "SELL", 130, 108.0, type = "SL", product = "NRML")
        var noteWhenCancelled: String? = null
        kite.afterNext("/orders/regular/${p.stopOrderId}") { noteWhenCancelled = runBlocking { Protections.active() }.singleOrNull()?.note }
        later(65)
        tick()
        assertEquals("marked as the app's own cancel before it went", "removing", noteWhenCancelled)
        assertEquals("CANCELLED", kite.order(p.stopOrderId!!).status)
        assertEquals("CANCELLED", kite.order(p.targetOrderId!!).status)
        assertEquals("Boss's own stop untouched", "TRIGGER PENDING", kite.order(own).status)
        assertTrue(runBlocking { Protections.active() }.isEmpty())
        assertEquals(Protections.BOSS_EXIT, runBlocking { Protections.endedSince(0) }.single { it.id == p.id }.note)
        assertEquals("told once", 1, Alerts.posted.count { it.text.contains("your own sell order in Kite") && it.text.contains("Only your order protects it now") })
        // Never placed again beside his (his order works; and he chose to handle it today).
        val n = kite.placed.size
        later(25); look(); later(25); look()
        kite.order(own).status = "CANCELLED"
        later(65); look(); later(25); look()
        assertEquals("respected for the day", n, kite.placed.size)
        tick()
        assertEquals("told once", 1, Alerts.posted.count { it.text.contains("your own sell order in Kite") })
    }

    /** His own sell for part of it: the app's still comes out (never two exits), and the part with no stop is said loudly. */
    @Test fun bossesOwnSellForPartIsSaidLoudly() {
        val p = autoProtected()
        kite.restsInKite(sym, "SELL", 65, 140.0, product = "NRML")
        later(65)
        tick()
        assertEquals("CANCELLED", kite.order(p.stopOrderId!!).status)
        assertTrue(Alerts.posted.any { it.kind == Alerts.Kind.ERROR && it.text.contains("Your order covers 65 of the 130 held") })
    }

    /** Intraday: the app's stop rests and its target is watched by the app; Boss's own target in Kite takes the app's stop out. */
    @Test fun intradayBossesOwnTargetTakesTheAppsStopOut() {
        val id = kite.boughtInKite(sym, 130, 125.35)
        update(id, "COMPLETE", 130)
        Background.await("covered") { kite.placed.isNotEmpty() }
        settled()
        val p = runBlocking { Protections.active() }.single()
        val own = kite.restsInKite(sym, "SELL", 130, 145.0)
        later(65)
        tick()
        assertEquals("CANCELLED", kite.order(p.stopOrderId!!).status)
        assertEquals("OPEN", kite.order(own).status)
        assertTrue(runBlocking { Protections.active() }.isEmpty())
        assertNotNull(runBlocking { Protections.endedSince(0) }.singleOrNull { it.note == Protections.BOSS_EXIT })
        // (Review: his order is a target alone - nothing stops a fall now: said loudly.)
        assertTrue(Alerts.posted.any { it.kind == Alerts.Kind.ERROR && it.text.contains("Your order is not a stop: the position has no stop now") })
    }

    /** Review: the app's stop fills between the look that marked it and its cancel: said loudly as that, never "took its own out". */
    @Test fun aStopThatFillsAsItIsTakenOutIsSaidAsFilledWithBosssSellStillWorking() {
        val p = autoProtected()
        kite.restsInKite(sym, "SELL", 130, 108.0, type = "SL", product = "NRML")
        later(65)
        // The stop fills just after the look read the order book (it still shows it resting).
        kite.afterNext("/orders") { kite.fill(p.stopOrderId!!, 115.0) }
        tick()
        val ended = runBlocking { Protections.endedSince(0) }.single { it.id == p.id }
        assertEquals("stop filled", ended.note)
        assertEquals("its target out", "CANCELLED", kite.order(p.targetOrderId!!).status)
        assertTrue(Alerts.posted.any { it.kind == Alerts.Kind.ERROR && it.text.contains("filled at Zerodha (130) just as it was being taken out") })
        assertFalse(said("so the app took its own out"))
    }

    /** Review: one resting overnight as a GTT, with Boss's own sell placed beside it: the GTT comes out (confirmed), his order stays. */
    @Test fun bossesOwnSellBesideTheOvernightGttTakesTheGttOut() {
        val p = autoProtected()
        at(15, 45)
        listOfNotNull(p.stopOrderId, p.targetOrderId).forEach { kite.order(it).status = "CANCELLED" }
        tick()
        val g = kite.gtts.keys.single()
        assertEquals(g, runBlocking { Protections.active() }.single().gttId)
        val own = kite.restingInKite(sym, "SELL", 130, 140.0, product = "NRML")
        tick()
        assertTrue("the app's GTT deleted", kite.gtts.isEmpty() || kite.gtts.values.none { it.optString("status") == "active" })
        assertEquals("OPEN", kite.order(own).status)
        assertTrue(runBlocking { Protections.active() }.isEmpty())
        assertEquals(Protections.BOSS_EXIT, runBlocking { Protections.endedSince(0) }.single { it.id == p.id }.note)
    }

    /** Review: "one GTT all day" on - the same: Boss's own stop beside the app's GTT takes the GTT out, never two exits. */
    @Test fun bossesOwnStopBesideTheAppsDayGttTakesTheGttOut() {
        try {
            assertTrue(DayOco.setOn(true, pinConfirmed = true).startsWith("On:"))
            val p = autoProtected()
            assertNotNull("rests as one GTT", p.gttId)
            val own = kite.restsInKite(sym, "SELL", 130, 108.0, type = "SL", product = "NRML")
            later(65)
            tick()
            assertTrue(kite.gtts.isEmpty() || kite.gtts.values.none { it.optString("status") == "active" })
            assertEquals("TRIGGER PENDING", kite.order(own).status)
            assertEquals(Protections.BOSS_EXIT, runBlocking { Protections.endedSince(0) }.single { it.id == p.id }.note)
        } finally { DayOco.resetForTest() }
    }

    /** The app's own exits are never taken for Boss's: an untagged-looking app order is still not his when it carries the app's tag. */
    @Test fun theAppsOwnOrdersOnTheContractAreNeverTakenForBosss() {
        val p = autoProtected()
        kite.restingInKite(sym, "SELL", 130, 140.0, tag = "iraorb", product = "NRML")
        later(65)
        tick()
        assertEquals("TRIGGER PENDING", kite.order(p.stopOrderId!!).status)
        assertEquals(1, runBlocking { Protections.active() }.size)
        assertFalse(said("your own sell order in Kite"))
    }
}
