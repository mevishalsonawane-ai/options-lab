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
        Protections.resetFollowUpForTest()
        Protections.cancelWaitMsForTest = null
        Protections.trackedSinceForTest = null
        Broker.serverOffsetMsForTest = null
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
        assertEquals("told once", 1, Alerts.posted.count { it.text.contains("your own exit in Kite") && it.text.contains("Only your order protects it now") })
        // Never placed again beside his (his order works; and he chose to handle it today).
        val n = kite.placed.size
        later(25); look(); later(25); look()
        kite.order(own).status = "CANCELLED"
        later(65); look(); later(25); look()
        assertEquals("respected for the day", n, kite.placed.size)
        tick()
        assertEquals("told once", 1, Alerts.posted.count { it.text.contains("your own exit in Kite") })
    }

    /**
     * Round 2, review (per side: his target sells in a rise, never in a fall - a stop never comes out unless its sale will go): his own target
     * for part of it - the app's stop stays for all held, and the app's target is dropped (his is the target).
     */
    @Test fun bossesOwnTargetForPartReplacesTheAppsTargetOnly() {
        val p = autoProtected()
        kite.restsInKite(sym, "SELL", 65, 140.0, product = "NRML")
        later(65)
        tick()
        assertEquals("the app's stop stays", "TRIGGER PENDING", kite.order(p.stopOrderId!!).status)
        assertEquals("for all 130 held", 130, kite.order(p.stopOrderId!!).qty)
        assertEquals("the app's target dropped: his is the target", "CANCELLED", kite.order(p.targetOrderId!!).status)
        val now = runBlocking { Protections.active() }.single()
        assertEquals(130, now.qty); assertEquals(null, now.target)
        assertTrue(said("is the target now, so the app's target was dropped; the app's stop stays for 130 of the 130 held"))
        val n = writes().size
        tick(); tick()
        assertEquals("nothing more", n, writes().size)
    }

    /** Round 2: his own stop for part of it - the app's stop and target both come down to the rest, said once. */
    @Test fun bossesOwnStopForPartShrinksTheAppsStopAndTarget() {
        val p = autoProtected()
        kite.restsInKite(sym, "SELL", 65, 108.0, type = "SL", product = "NRML")
        later(65)
        tick()
        assertEquals(65, kite.order(p.stopOrderId!!).qty); assertEquals("TRIGGER PENDING", kite.order(p.stopOrderId!!).status)
        assertEquals(65, kite.order(p.targetOrderId!!).qty); assertEquals("OPEN", kite.order(p.targetOrderId!!).status)
        assertEquals(65, runBlocking { Protections.active() }.single().qty)
        tick()
        assertEquals("said once", 1, Alerts.posted.count { it.text.contains("covers 65 of the 130 held") })
    }

    /** Intraday: the app's stop rests and its target is watched by the app; Boss's own target in Kite replaces the app's target, its stop stays. */
    @Test fun intradayBossesOwnTargetReplacesTheAppsWatchedTarget() {
        val id = kite.boughtInKite(sym, 130, 125.35)
        update(id, "COMPLETE", 130)
        Background.await("covered") { kite.placed.isNotEmpty() }
        settled()
        val p = runBlocking { Protections.active() }.single()
        val own = kite.restsInKite(sym, "SELL", 130, 145.0)
        later(65)
        tick()
        assertEquals("the app's stop stays", "TRIGGER PENDING", kite.order(p.stopOrderId!!).status)
        assertEquals("OPEN", kite.order(own).status)
        val now = runBlocking { Protections.active() }.single()
        assertEquals(null, now.target); assertFalse(now.appTarget)
        assertTrue(said("is the target now, so the app's target was dropped"))
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
        val own = kite.restingInKite(sym, "SELL", 130, 108.0, type = "SL", trigger = 108.0, product = "NRML")
        tick()
        assertTrue("the app's GTT deleted", kite.gtts.isEmpty() || kite.gtts.values.none { it.optString("status") == "active" })
        assertEquals("TRIGGER PENDING", kite.order(own).status)
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
        assertFalse(said("your own exit in Kite"))
    }

    // ---- round 2 (23 Nov 2026): the time the two looks used to give Boss ------------------------------------------------------

    /** Boss's own GTT in Kite (an OCO: a stop and a target), active on the contract. */
    private fun bossGtt(id: Long, qty: Int = 130, stop: Double = 110.0, target: Double = 140.0, product: String = "NRML") {
        fun leg(px: Double) = org.json.JSONObject().put("exchange", "NFO").put("tradingsymbol", sym).put("transaction_type", "SELL")
            .put("quantity", qty).put("product", product).put("order_type", "LIMIT").put("price", px)
        kite.gtts[id] = org.json.JSONObject().put("id", id).put("type", "two-leg").put("status", "active").put("created_at", "2026-11-23 10:00:00")
            .put("condition", org.json.JSONObject().put("exchange", "NFO").put("tradingsymbol", sym)
                .put("trigger_values", org.json.JSONArray().put(stop).put(target)).put("last_price", 126.0))
            .put("orders", org.json.JSONArray().put(leg(stop - 1)).put(leg(target)))
    }

    /** Item 1: Boss sets his own GTT OCO after the cover - the app's stop and target come out (confirmed): never two exits. */
    @Test fun bossesOwnGttSetAfterTheCoverTakesTheAppsOut() {
        val p = autoProtected()
        later(15)
        bossGtt(6_001L)
        later(65)
        tick()
        assertEquals("CANCELLED", kite.order(p.stopOrderId!!).status)
        assertEquals("CANCELLED", kite.order(p.targetOrderId!!).status)
        assertEquals("his GTT untouched", "active", kite.gtts.getValue(6_001L).getString("status"))
        assertTrue(runBlocking { Protections.active() }.isEmpty())
        assertEquals(Protections.BOSS_EXIT, runBlocking { Protections.endedSince(0) }.single { it.id == p.id }.note)
        assertTrue(said("a GTT at 110.00 / 140.00 for 130"))
    }

    /** Item 2: his own stop placed in Kite beside the app's - the stream's update asks for the protections' look at once (no regular pass). */
    @Test fun bossesOwnStopReportedOnTheStreamTakesTheAppsOutAtOnce() {
        val p = autoProtected()
        later(65)
        val own = kite.restsInKite(sym, "SELL", 130, 108.0, type = "SL", product = "NRML")
        update(own, "TRIGGER PENDING", 0, product = "NRML", side = "SELL")
        Background.await("the app's stop out at once") { kite.order(p.stopOrderId!!).status == "CANCELLED" }
        Background.await("its protection ended") { runBlocking { Protections.active() }.isEmpty() }
        assertEquals("TRIGGER PENDING", kite.order(own).status)
    }

    /** Item 2: the grace - his stop set right after his buy wins, the app places nothing. */
    @Test fun aStopBossSetsRightAfterHisBuyWinsTheGrace() {
        ManualCover.fillGraceMsForTest = 1_500L
        val id = kite.boughtInKite(sym, 130, 125.35)
        update(id, "COMPLETE", 130)
        kite.restsInKite(sym, "SELL", 130, 110.0, type = "SL")
        settled()
        assertTrue("his stop won: nothing placed, yet ${kite.placed.map { it.form }}", kite.placed.isEmpty())
        assertTrue(ManualCover.view.value.last, ManualCover.view.value.last.startsWith("Left alone: $sym"))
    }

    /** Item 3a: Boss covers the long wing, then sells the short leg - the wing's protection comes off at once, no two looks. */
    @Test fun aShortLegSoldAfterTheWingTakesTheWingsProtectionOffAtOnce() {
        val id = kite.boughtInKite(sym, 65, 125.35)
        update(id, "COMPLETE", 65, qty = 65)
        Background.await("the wing covered") { kite.placed.isNotEmpty() }
        settled()
        val wing = runBlocking { Protections.active() }.single()
        val short = "NIFTY26N0925200CE"
        val sold = kite.soldInKite(short, 65, 90.0)
        update(sold, "COMPLETE", 65, qty = 65, side = "SELL", symbol = short)
        Background.await("the wing's protection off at once") { runBlocking { Protections.active() }.isEmpty() }
        assertEquals("CANCELLED", kite.order(wing.stopOrderId!!).status)
        // (Round 3: said just after the removal returns, in the look's own job - waited for, never read the same moment.)
        Background.await("said") { Alerts.posted.any { it.text.contains("removed because it is now part of a spread/hedge") } }
    }

    /** Item 3b: Boss adds lots seconds after the cover - the lots added are covered too, at the same stop; never more than held. */
    @Test fun lotsAddedAfterTheCoverAreCoveredToo() {
        val id = kite.boughtInKite(sym, 130, 125.35)
        update(id, "COMPLETE", 130)
        Background.await("covered") { kite.placed.isNotEmpty() }
        settled()
        val more = kite.boughtInKite(sym, 65, 125.35)
        update(more, "COMPLETE", 65, qty = 65)
        Background.await("the lots added covered") { kite.placed.size == 2 }
        settled()
        val sl = kite.placed[1]
        assertEquals("SL", sl.form["order_type"]); assertEquals("65", sl.form["quantity"])
        assertEquals("the same stop", 115.35, sl.form.getValue("trigger_price").toDouble(), 1e-9)
        val active = runBlocking { Protections.active() }
        assertEquals(195, active.sumOf { it.qty }); assertTrue(active.all { it.auto })
        assertTrue(ManualCover.view.value.last, ManualCover.view.value.last.contains("195 of 195 held, 65 added"))
        // Later looks add nothing more.
        later(25); look(); later(25); look()
        assertEquals(2, kite.placed.size)
    }

    /** Item 7: a quick scalp - Boss sells all of it in Kite seconds after the cover: the app's stop comes out at once (two agreeing reads). */
    @Test fun aQuickScalpTakesTheAppsStopOutAtOnce() {
        val id = kite.boughtInKite(sym, 130, 125.35)
        update(id, "COMPLETE", 130)
        Background.await("covered") { kite.placed.isNotEmpty() }
        settled()
        val p = runBlocking { Protections.active() }.single()
        val sold = kite.soldInKite(sym, 130, 127.0)
        update(sold, "COMPLETE", 130, side = "SELL")
        Background.await("the app's stop out on a flat account") { kite.order(p.stopOrderId!!).status == "CANCELLED" }
        Background.await("its protection ended") { runBlocking { Protections.active() }.isEmpty() }
        assertEquals("position closed", runBlocking { Protections.endedSince(0) }.single { it.id == p.id }.note)
    }

    /** Item 8: the app's own move of its stop (Boss's move from the app) while the book still shows the old level is never taken for his. */
    @Test fun anInAppMoveIsNeverTakenForBosssWhileTheBookTrailsIt() {
        val p = autoProtected()
        later(65)
        assertEquals(null, runBlocking { Protections.moveStop(p.id, 118.0) })
        assertEquals(118.0, kite.order(p.stopOrderId!!).trigger, 0.0)
        kite.order(p.stopOrderId!!).trigger = 115.35                    // the order book still shows the old level a moment
        tick()
        assertEquals("the app's own level kept", 118.0, runBlocking { Protections.active() }.single().stop!!, 1e-9)
        assertFalse(said("you moved its automatic stop"))
    }

    /** Item 5: Zerodha refuses his own sale beside the app's stop - he is told once how to exit or set his own stop. */
    @Test fun bossesRefusedSaleIsToldHowToExitOnce() {
        autoProtected()
        val own = kite.restsInKite(sym, "SELL", 130, 140.0, product = "NRML")
        kite.order(own).status = "REJECTED"
        update(own, "REJECTED", 0, product = "NRML", side = "SELL")
        update(own, "REJECTED", 0, product = "NRML", side = "SELL")
        Background.await("told") { said(com.optionslab.ira.ManualCoverRule.OWN_EXIT_HELP) }
        Thread.sleep(300)
        assertEquals("once", 1, Alerts.posted.count { it.text.contains("Zerodha refused your own sell order") })
        assertTrue(said("modify the app's SL in Kite (to MARKET to exit, or to your level) or cancel it"))
    }

    /** Item 11: another app's order (a third-party tag) is Boss's: covered at once, and his own stop with it beside the app's comes first. */
    @Test fun aThirdPartyTagIsBosss() {
        val id = kite.boughtInKite(sym, 130, 125.35, tag = "sensibull")
        update(id, "COMPLETE", 130, tag = "sensibull")
        Background.await("covered at once") { kite.placed.isNotEmpty() }
        settled()
        val p = runBlocking { Protections.active() }.single()
        kite.restsInKite(sym, "SELL", 130, 108.0, type = "SL", tag = "streak")
        later(65)
        tick()
        assertEquals("CANCELLED", kite.order(p.stopOrderId!!).status)
        assertTrue(runBlocking { Protections.active() }.isEmpty())
    }

    /** Item 5: the protected alert says how to exit while the app's SL rests. */
    @Test fun theProtectedAlertSaysHowToExitOrSetHisOwnStop() {
        autoProtected()
        assertTrue(Alerts.posted.any { it.kind == Alerts.Kind.SUCCESS && it.text.contains("you bought 130 yourself") &&
            it.text.contains(com.optionslab.ira.ManualCoverRule.OWN_EXIT_HELP) })
    }

    // ---- round 2, the review's cases ----------------------------------------------------------------------------------------

    /** His own stop and target on the same 65 units count once (an OCO), never twice: the app's stop comes down to the other 65, not out. */
    @Test fun bossesOwnStopAndTargetOnTheSameUnitsCountOnce() {
        val p = autoProtected()
        kite.restsInKite(sym, "SELL", 65, 108.0, type = "SL", product = "NRML")
        kite.restsInKite(sym, "SELL", 65, 140.0, product = "NRML")
        later(65)
        tick()
        assertEquals("the app's stop stays, for the other 65", "TRIGGER PENDING", kite.order(p.stopOrderId!!).status)
        assertEquals(65, kite.order(p.stopOrderId!!).qty)
        assertEquals("his target is the target", "CANCELLED", kite.order(p.targetOrderId!!).status)
        assertEquals(65, runBlocking { Protections.active() }.single().qty)
    }

    /** He cancels his own stop after the app's came down beside it: the app's stop is raised back to all that is held. */
    @Test fun theAppsStopIsRaisedAgainWhenBossCancelsHisOwn() {
        val p = autoProtected()
        val own = kite.restsInKite(sym, "SELL", 65, 108.0, type = "SL", product = "NRML")
        later(65)
        tick()
        assertEquals(65, kite.order(p.stopOrderId!!).qty)
        kite.order(own).status = "CANCELLED"
        tick()
        assertEquals("raised back to the 130 held", 130, kite.order(p.stopOrderId!!).qty)
        assertEquals(130, kite.order(p.targetOrderId!!).qty)
        assertEquals(130, runBlocking { Protections.active() }.single().qty)
        assertTrue(said("covers less now (0), so the app's automatic stop covers 130 again"))
    }

    /** Zerodha refuses bringing the app's stop down: said, kept, and tried again on the next look. */
    @Test fun aShrinkZerodhaRefusesIsTriedAgain() {
        val p = autoProtected()
        kite.restsInKite(sym, "SELL", 65, 108.0, type = "SL", product = "NRML")
        later(65)
        kite.modifyRefusals = 1
        tick()
        assertTrue(said("could not be brought down to the other 65 yet; retrying"))
        tick()
        assertEquals(65, kite.order(p.stopOrderId!!).qty)
        assertEquals(65, runBlocking { Protections.active() }.single().qty)
    }

    /** One resting overnight as a GTT, with his own sell for part of it: the GTT's legs come down to the rest, never taken out whole. */
    @Test fun bossesOwnSellForPartBesideTheOvernightGttBringsTheGttDown() {
        val p = autoProtected()
        at(15, 45)
        listOfNotNull(p.stopOrderId, p.targetOrderId).forEach { kite.order(it).status = "CANCELLED" }
        tick()
        val g = kite.gtts.keys.single()
        kite.restingInKite(sym, "SELL", 65, 108.0, type = "SL", trigger = 108.0, product = "NRML")
        tick()
        val legs = kite.gtts.getValue(g).getJSONArray("orders")
        assertEquals("the GTT kept", "active", kite.gtts.getValue(g).getString("status"))
        assertEquals(65, legs.getJSONObject(0).getInt("quantity"))
        assertEquals(65, runBlocking { Protections.active() }.single().qty)
    }

    /** Lots added while the price is at the app's stop already: no stop is added for them, and he is told. */
    @Test fun lotsAddedAtTheStopAreSaidNotCovered() {
        val id = kite.boughtInKite(sym, 130, 125.35)
        update(id, "COMPLETE", 130)
        Background.await("covered") { kite.placed.isNotEmpty() }
        settled()
        kite.quote("NFO:$sym", 115.5, 115.45, 115.55)
        val more = kite.boughtInKite(sym, 65, 115.5)
        update(more, "COMPLETE", 65, qty = 65)
        Background.await("told") { said("so no stop was added for them") }
        settled()
        assertEquals(1, kite.placed.size)
    }

    /** Review: never raised again while an exit of the app's or his own from the app is on its way (hands off): only once it is done. */
    @Test fun theAppsStopIsNeverRaisedBesideAnExitOnItsWay() {
        val p = autoProtected()
        val own = kite.restsInKite(sym, "SELL", 65, 108.0, type = "SL", product = "NRML")
        later(65)
        tick()
        assertEquals(65, kite.order(p.stopOrderId!!).qty)
        kite.order(own).status = "CANCELLED"
        ArmLive.handsOff("NFO", sym)
        try {
            tick()
            assertEquals("not raised while his exit from the app is going", 65, kite.order(p.stopOrderId!!).qty)
        } finally { ArmLive.handsBack("NFO", sym) }
        tick()
        assertEquals("raised once it is done", 130, kite.order(p.stopOrderId!!).qty)
    }

    /** Review: one resting as a GTT, brought down beside his stop, is raised again when he cancels his. */
    @Test fun theAppsGttIsRaisedAgainWhenBossCancelsHisOwn() {
        val p = autoProtected()
        at(15, 45)
        listOfNotNull(p.stopOrderId, p.targetOrderId).forEach { kite.order(it).status = "CANCELLED" }
        tick()
        val g = kite.gtts.keys.single()
        val own = kite.restingInKite(sym, "SELL", 65, 108.0, type = "SL", trigger = 108.0, product = "NRML")
        tick()
        assertEquals(65, kite.gtts.getValue(g).getJSONArray("orders").getJSONObject(0).getInt("quantity"))
        kite.order(own).status = "CANCELLED"
        tick()
        assertEquals("raised back to the 130 held", 130, kite.gtts.getValue(g).getJSONArray("orders").getJSONObject(0).getInt("quantity"))
        assertEquals(130, runBlocking { Protections.active() }.single().qty)
    }


    // ---- round 3, the review's cases ----------------------------------------------------------------------------------------

    /** Item 1: his stop seen while still on its way at Zerodha, then refused - never counted: the app's stop and target stay as they are. */
    @Test fun bossesStopStillOnItsWayThenRefusedLeavesTheAppsStop() {
        val p = autoProtected()
        val own = kite.restsInKite(sym, "SELL", 130, 108.0, type = "SL", product = "NRML")
        later(65)
        for (st in listOf("PUT ORDER REQ RECEIVED", "VALIDATION PENDING", "OPEN PENDING", "REJECTED")) {
            kite.order(own).status = st
            tick()
            assertEquals("$st: the app's stop stays", "TRIGGER PENDING", kite.order(p.stopOrderId!!).status)
            assertEquals("$st: and its target", "OPEN", kite.order(p.targetOrderId!!).status)
        }
        assertEquals(130, kite.order(p.stopOrderId!!).qty)
        val now = runBlocking { Protections.active() }.single()
        assertEquals(130, now.qty); assertTrue(now.note != "removing")
        assertFalse(said("your own exit in Kite"))
    }

    /** Item 1: his stop resting when the look marked the app's to come out, refused before the look that cancels it: the app's stays. */
    @Test fun bossesStopRefusedRightAfterTheMarkLeavesTheAppsStop() {
        val p = autoProtected()
        val own = kite.restsInKite(sym, "SELL", 130, 108.0, type = "SL", product = "NRML")
        later(65)
        // Zerodha refuses it just after the look that saw it resting reads the order book.
        kite.afterNext("/orders") { kite.order(own).status = "REJECTED" }
        tick()
        assertEquals("TRIGGER PENDING", kite.order(p.stopOrderId!!).status)
        assertEquals("OPEN", kite.order(p.targetOrderId!!).status)
        val now = runBlocking { Protections.active() }.single()
        assertTrue("its mark dropped: ${now.note}", now.note != "removing")
        assertTrue(said("so the app keeps its own stop."))
        assertFalse(said("Only your order protects it now"))
        // Nothing more happens on later looks.
        val n = writes().size
        tick()
        assertEquals(n, writes().size)
    }

    /** Item 1, review: his stop fills between the look that marked the app's to come out and the look that cancels it - it still comes out. */
    @Test fun bossesStopThatFilledAfterTheMarkStillTakesTheAppsOut() {
        val p = autoProtected()
        val own = kite.restsInKite(sym, "SELL", 130, 108.0, type = "SL", product = "NRML")
        later(65)
        kite.afterNext("/orders") { kite.fill(own, 108.0) }               // his stop sells all, just after the marking look read the book
        tick()
        assertEquals("the app's stop out: his sale went", "CANCELLED", kite.order(p.stopOrderId!!).status)
        assertEquals("CANCELLED", kite.order(p.targetOrderId!!).status)
        assertTrue(runBlocking { Protections.active() }.isEmpty())
        assertTrue(said("your own stop in Kite sold 130"))
        assertFalse(said("so the app keeps its own stop"))
    }

    /** Item 1, review: his stop sells part after the mark and the rest of it is cancelled - the app's stop stays for what is still held. */
    @Test fun bossesStopThatSoldPartThenEndedLeavesTheAppsStopForTheRest() {
        val p = autoProtected()
        val own = kite.restsInKite(sym, "SELL", 130, 108.0, type = "SL", product = "NRML")
        later(65)
        kite.afterNext("/orders") { kite.fillPart(own, 65, 108.0); kite.order(own).status = "CANCELLED" }
        tick()
        assertEquals("the app's stop kept", "TRIGGER PENDING", kite.order(p.stopOrderId!!).status)
        assertTrue(said("sold 65 and does not cover the rest held, so the app keeps its own stop."))
        tick()
        assertEquals("and brought down to the 65 still held", 65, kite.order(p.stopOrderId!!).qty)
        assertTrue(runBlocking { Protections.active() }.single().active)
    }

    /** Item 1, review: his stop just marked, then seen still on its way at Zerodha (an order being placed) - nothing comes out until it settles. */
    @Test fun bossesStopOnItsWayAfterTheMarkWaitsForItToSettle() {
        val p = autoProtected()
        val own = kite.restsInKite(sym, "SELL", 130, 108.0, type = "SL", product = "NRML")
        later(65)
        kite.afterNext("/orders") { kite.order(own).status = "OPEN PENDING" }
        tick()
        assertEquals("nothing cancelled while his is on its way", "TRIGGER PENDING", kite.order(p.stopOrderId!!).status)
        assertEquals("still marked", "removing", runBlocking { Protections.active() }.single().note)
        assertFalse(said("so the app keeps its own stop"))
        kite.order(own).status = "OPEN"
        tick()
        assertEquals("settled and resting: the app's out", "CANCELLED", kite.order(p.stopOrderId!!).status)
        assertTrue(runBlocking { Protections.active() }.isEmpty())
    }

    /** Item 1, review: the GTT list not read on the look after the mark - the mark dropped (its own checks go on), taken out once both read. */
    @Test fun aGttListNotReadAfterTheMarkKeepsTheAppsStopUntilBothAreRead() {
        val p = autoProtected()
        kite.restsInKite(sym, "SELL", 130, 108.0, type = "SL", product = "NRML")
        later(65)
        kite.afterNext("/gtt/triggers") { kite.gttReadFailures = 20 }
        tick()
        assertEquals("TRIGGER PENDING", kite.order(p.stopOrderId!!).status)
        assertTrue("the mark dropped", runBlocking { Protections.active() }.single().note != "removing")
        kite.gttReadFailures = 0
        tick()
        assertEquals("both read: the app's out", "CANCELLED", kite.order(p.stopOrderId!!).status)
        assertTrue(runBlocking { Protections.active() }.isEmpty())
    }

    /** Item 1: the app's stop already cancelled for his stop (its target's cancel not through), then his stop refused: the stop goes back. */
    @Test fun theAppsStopAlreadyCancelledForBosssRefusedStopIsPutBack() {
        Protections.cancelWaitMsForTest = 0L                                       // the target's cancel never reached Zerodha: not waited for
        val p = autoProtected()
        val own = kite.restsInKite(sym, "SELL", 130, 108.0, type = "SL", product = "NRML")
        later(65)
        kite.drop += "/orders/regular/${p.targetOrderId}"             // the target's cancel never reaches Zerodha
        tick()
        assertEquals("CANCELLED", kite.order(p.stopOrderId!!).status)
        assertEquals("OPEN", kite.order(p.targetOrderId!!).status)
        kite.drop.clear()
        kite.order(own).status = "REJECTED"
        tick(); tick()
        val now = runBlocking { Protections.active() }.single()
        val sl = kite.order(now.stopOrderId!!)
        assertEquals("a stop rests again", "TRIGGER PENDING", sl.status)
        assertEquals("for all held", 130, sl.qty)
        assertEquals("at its level", 115.35, sl.trigger, 1e-9)
        assertTrue(said("puts back what it had already cancelled"))
        assertEquals("the old target taken out first: one target only", "CANCELLED", kite.order(p.targetOrderId!!).status)
        assertTrue(now.targetOrderId != p.targetOrderId && kite.order(now.targetOrderId!!).status == "OPEN")
    }

    /** Item 2: lots added while the app's stop is raised back beside his cancelled stop - the slices never cover more than is held. */
    @Test fun lotsAddedNeverCoverMoreThanHeldWhenTheAppsStopIsRaisedMeanwhile() {
        ManualCover.fillGraceMsForTest = 200L
        val p = autoProtected()
        val own = kite.restsInKite(sym, "SELL", 65, 108.0, type = "SL", product = "NRML")
        later(65)
        tick()
        assertEquals("brought down beside his", 65, kite.order(p.stopOrderId!!).qty)
        kite.order(own).status = "CANCELLED"                           // he cancels his own stop ...
        val more = kite.boughtInKite(sym, 65, 125.35, product = "NRML")  // ... and buys 65 more at once
        // The app's stop raised back at Zerodha (the protections' look beside his cancel) between the switch's reads and its slices.
        kite.afterNext("/quote") { kite.order(p.stopOrderId!!).qty = 130 }
        val placed = kite.placed.size
        update(more, "COMPLETE", 65, qty = 65, product = "NRML")
        Background.await("the lots added covered") { kite.placed.size > placed }
        settled()
        val added = kite.placed.drop(placed).filter { it.form["order_type"] == "SL" }
        assertEquals("only what the raised stop leaves: 195 held, 130 + 65", listOf("65"), added.map { it.form["quantity"] })
        assertTrue(ManualCover.view.value.last, ManualCover.view.value.last.contains("195 of 195 held, 65 added"))
    }

    /** Item 3: a burst of Boss's buy updates while a look runs - two looks at most, never one per update. */
    @Test fun aBurstOfUpdatesCostsTwoLooksAtMost() {
        ManualCover.fillGraceMsForTest = 0L
        kite.slowMs["/portfolio/positions"] = 300L
        val ran = ManualCover.lookNowRanForTest()
        val id = kite.boughtInKite(sym, 130, 125.35)
        update(id, "COMPLETE", 130)
        Background.await("its look started") { ManualCover.lookNowRanForTest() > ran }
        repeat(19) { update(id, "COMPLETE", 130) }
        settled()
        kite.slowMs.clear()
        val n = ManualCover.lookNowRanForTest() - ran
        assertTrue("at most two looks for 20 updates, made $n", n in 1..2)
        assertEquals("covered once", 1, kite.placed.count { it.form["order_type"] == "SL" })
    }

    /** Item 3: a burst of Boss's sale updates beside the app's stop while the protections look - two of their looks at most. */
    @Test fun aBurstOfBossesSalesCostsTwoProtectionLooksAtMost() {
        autoProtected()
        later(65)
        val own = kite.restsInKite(sym, "SELL", 65, 140.0, product = "NRML")
        kite.slowMs["/orders"] = 300L
        val ran = Protections.lookSoonRanForTest()
        update(own, "OPEN", 0, qty = 65, product = "NRML", side = "SELL")
        Background.await("its look started") { Protections.lookSoonRanForTest() > ran }
        repeat(19) { update(own, "OPEN", 0, qty = 65, product = "NRML", side = "SELL") }
        Background.await("the looks done") { !Protections.lookSoonPendingForTest() }
        kite.slowMs.clear()
        val n = Protections.lookSoonRanForTest() - ran
        assertTrue("at most two looks for 20 updates, made $n", n in 1..2)
    }

    /** Item 4: his target beside the app's stop fills in its first minute, the stream's report missed: the app's stop comes out (two reads). */
    @Test fun bossesTargetThatFilledUnreportedTakesTheAppsStopOutInItsFirstMinute() {
        val p = autoProtected()
        val own = kite.restsInKite(sym, "SELL", 130, 140.0, product = "NRML")
        tick()
        assertEquals("his target is the target", "CANCELLED", kite.order(p.targetOrderId!!).status)
        assertEquals("TRIGGER PENDING", kite.order(p.stopOrderId!!).status)
        kite.fill(own, 140.0)                                       // filled at Zerodha; no update on the stream
        tick()
        assertEquals("the app's stop out on the flat account", "CANCELLED", kite.order(p.stopOrderId!!).status)
        assertEquals("position closed", runBlocking { Protections.endedSince(0) }.single { it.id == p.id }.note)
        assertTrue("still its first minute", Market.now().toInstant().toEpochMilli() - p.id < 60_000L)
    }

    /** Item 5: Boss trails his own stop (a change being checked at Zerodha): it still covers - the app's stop is never raised beside it. */
    @Test fun bossTrailingHisOwnStopNeverRaisesTheAppsStop() {
        val p = autoProtected()
        val own = kite.restsInKite(sym, "SELL", 65, 108.0, type = "SL", product = "NRML")
        later(65)
        tick()
        assertEquals(65, kite.order(p.stopOrderId!!).qty)
        kite.order(own).status = "MODIFY VALIDATION PENDING"
        tick()
        assertEquals("not raised beside his", 65, kite.order(p.stopOrderId!!).qty)
        assertFalse(said("covers less now"))
        kite.order(own).status = "TRIGGER PENDING"
        tick()
        assertEquals(65, kite.order(p.stopOrderId!!).qty)
    }

    /** Item 6: an order a GTT of the app's own placed when it fired (no tag of the app's) is never taken for Boss's target. */
    @Test fun anOrderTheAppsOwnGttPlacedIsNeverTakenForBosss() {
        val p = autoProtected()
        later(65)
        kite.gtts[7_001L] = org.json.JSONObject().put("id", 7_001L).put("type", "single").put("status", "active").put("created_at", "2026-11-23 10:00:00")
            .put("condition", org.json.JSONObject().put("exchange", "NFO").put("tradingsymbol", sym)
                .put("trigger_values", org.json.JSONArray().put(139.0)).put("last_price", 126.0))
            .put("orders", org.json.JSONArray().put(org.json.JSONObject().put("exchange", "NFO").put("tradingsymbol", sym).put("transaction_type", "SELL")
                .put("quantity", 65).put("product", "NRML").put("order_type", "LIMIT").put("price", 140.0)))
        Protections.noteAppGttForTest(7_001L)                          // another of the app's own (a slice, an arm's copy, a strategy's leg)
        val fired = kite.triggerGtt(7_001L)
        assertEquals("its sale works", "OPEN", kite.order(fired).status)
        tick()
        assertEquals("the app's target kept", "OPEN", kite.order(p.targetOrderId!!).status)
        assertNotNull(runBlocking { Protections.active() }.single().target)
        assertFalse(said("is the target now"))
    }

    /** Item 6: a sale on the other leg that a buy of his there explains (the positions trailing it) never takes the wing's protection at once. */
    @Test fun aSaleAPositionsLagCouldExplainNeverTakesTheWingOffAtOnce() {
        val id = kite.boughtInKite(sym, 65, 125.35)
        update(id, "COMPLETE", 65, qty = 65)
        Background.await("the wing covered") { kite.placed.isNotEmpty() }
        settled()
        val wing = runBlocking { Protections.active() }.single()
        val other = "NIFTY26N0925200CE"
        kite.positionsTrailReads = 50
        kite.boughtInKite(other, 65, 90.0)                             // his buy there, the positions trailing it
        val sell = kite.restsInKite(other, "SELL", 65, 100.0)           // and his target on it
        update(sell, "OPEN", 0, qty = 65, side = "SELL", symbol = other)
        settled()
        kite.positionsCatchUp()
        assertEquals("the wing keeps its protection", "TRIGGER PENDING", kite.order(wing.stopOrderId!!).status)
        assertTrue(runBlocking { Protections.active() }.any { it.id == wing.id })
        assertFalse(Alerts.posted.any { it.text.contains("removed because it is now part of a spread/hedge") })
    }


    // ---- round 4, the final gate's cases ---------------------------------------------------------------------------------------

    /**
     * Round 4 major 1, as round 5 has it: NRML, 150 held. His own GTT stop for 75 brings the app's stop down to 75; it fires - with its order
     * not in the book yet the app's stop is never raised beside it; once its LIMIT rests unfilled under a falling price (a sale that may not
     * go) the app's stop covers all held again, and he is told once, loudly, that both could sell.
     */
    @Test fun bossesFiredGttStopOnItsWayHoldsTheAppsStopAndItsRestingLimitIsSaidOnce() {
        val s75 = "NIFTY26N0925100CE"
        kite.instruments += FakeKite.Ins(12_345_680, s75, "NIFTY", Market.today().plusDays(2), 25_100.0, "CE", 75)
        kite.quote("NFO:$s75", 126.0, 125.95, 126.05)
        kite.position(s75, 150, 125.35)
        look(); later(25); look()
        val p = runBlocking { Protections.active() }.single { it.symbol == s75 }
        assertEquals(150, kite.order(p.stopOrderId!!).qty)
        val gid = 6_101L
        kite.gtts[gid] = org.json.JSONObject().put("id", gid).put("type", "single").put("status", "active").put("created_at", "2026-11-23 10:00:00")
            .put("condition", org.json.JSONObject().put("exchange", "NFO").put("tradingsymbol", s75)
                .put("trigger_values", org.json.JSONArray().put(120.0)).put("last_price", 126.0))
            .put("orders", org.json.JSONArray().put(org.json.JSONObject().put("exchange", "NFO").put("tradingsymbol", s75).put("transaction_type", "SELL")
                .put("quantity", 75).put("product", "NRML").put("order_type", "LIMIT").put("price", 119.0)))
        later(65)
        tick()
        assertEquals("brought down beside his GTT stop", 75, kite.order(p.stopOrderId!!).qty)
        // It fires; its order not in the book yet.
        kite.gtts.getValue(gid).put("status", "triggered")
            .put("updated_at", Market.now().toLocalDateTime().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
        tick()
        assertEquals("never raised while his fired stop's order is on its way", 75, kite.order(p.stopOrderId!!).qty)
        // Its LIMIT rests unfilled: the price fell under it.
        kite.quote("NFO:$s75", 118.0, 117.95, 118.05)
        val fired = kite.triggerGtt(gid)
        assertEquals("OPEN", kite.order(fired).status)
        tick()
        assertEquals("its LIMIT may not sell: the app's stop covers all held again", 150, kite.order(p.stopOrderId!!).qty)
        val told = Alerts.posted.count { it.text.contains("part of it may not sell") }
        assertTrue("told", told > 0)
        tick(); tick()
        assertEquals(150, kite.order(p.stopOrderId!!).qty)
        assertEquals("told once", told, Alerts.posted.count { it.text.contains("part of it may not sell") })
    }

    /**
     * Round 5 major 1 (the final gate's scenario): long 150; Boss's own GTT stop for 75 fires before the app saw it, and its LIMIT rests above
     * the market after a gap. The app's stop is never cut to 75 beside it (the other 75 would have no stop): it stays at 150, said once.
     */
    @Test fun bossesFiredGttLimitLeftByAGapNeverCutsTheAppsStop() {
        Protections.trackedSinceForTest = 0L                                       // this run began before his GTT was made
        val s75 = "NIFTY26N0925100CE"
        kite.instruments += FakeKite.Ins(12_345_680, s75, "NIFTY", Market.today().plusDays(2), 25_100.0, "CE", 75)
        kite.quote("NFO:$s75", 126.0, 125.95, 126.05)
        kite.position(s75, 150, 125.35)
        look(); later(25); look()
        val p = runBlocking { Protections.active() }.single { it.symbol == s75 }
        val gid = 6_201L
        val made = java.time.LocalDateTime.now(com.optionslab.engine.IST).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        kite.gtts[gid] = org.json.JSONObject().put("id", gid).put("type", "single").put("status", "active").put("created_at", made)
            .put("condition", org.json.JSONObject().put("exchange", "NFO").put("tradingsymbol", s75)
                .put("trigger_values", org.json.JSONArray().put(120.0)).put("last_price", 126.0))
            .put("orders", org.json.JSONArray().put(org.json.JSONObject().put("exchange", "NFO").put("tradingsymbol", s75).put("transaction_type", "SELL")
                .put("quantity", 75).put("product", "NRML").put("order_type", "LIMIT").put("price", 119.0)))
        kite.quote("NFO:$s75", 116.0, 115.95, 116.05)                              // a gap through his trigger and his limit
        val fired = kite.triggerGtt(gid)
        assertEquals("his LIMIT rests above the market", "OPEN", kite.order(fired).status)
        later(65)
        tick()
        assertEquals("the app's stop never cut beside it", 150, kite.order(p.stopOrderId!!).qty)
        val told = Alerts.posted.count { it.text.contains("part of it may not sell") }
        assertTrue("told", told > 0)
        tick(); tick()
        assertEquals(150, kite.order(p.stopOrderId!!).qty)
        assertEquals("told once", told, Alerts.posted.count { it.text.contains("part of it may not sell") })
    }

    /** Round 5 (2): a cancel of the app's own sent while taking its stop out, landing only after the mark was dropped - never read as Boss's. */
    @Test fun aCancelOfItsOwnLandingLateIsNeverReadAsBosss() {
        Protections.cancelWaitMsForTest = 0L
        val id = kite.boughtInKite(sym, 130, 125.35)
        update(id, "COMPLETE", 130)
        Background.await("covered") { kite.placed.isNotEmpty() }
        settled()
        val p = runBlocking { Protections.active() }.single()
        val own = kite.restsInKite(sym, "SELL", 130, 108.0, type = "SL")
        later(65)
        kite.drop += "/orders/regular/${p.stopOrderId}"                            // the app's cancel of its stop does not get through yet
        tick()
        assertEquals("TRIGGER PENDING", kite.order(p.stopOrderId!!).status)
        kite.drop.clear()
        kite.order(own).status = "REJECTED"
        tick()
        assertTrue(said("so the app keeps its own stop"))
        kite.order(p.stopOrderId!!).status = "CANCELLED"                           // the app's own cancel lands late
        val stops = kite.placed.count { it.form["order_type"] == "SL" }
        tick()
        assertEquals("put back: the app's own cancel", stops + 1, kite.placed.count { it.form["order_type"] == "SL" })
        assertFalse(said("cancelled in Kite (by you)"))
    }

    /** Round 5 (7): the app's stop cancelled under another of its own paths' marks while being taken out - never said as Boss's cancel. */
    @Test fun aStopCancelledUnderAnotherPathsMarkIsNeverSaidAsBosss() {
        Protections.cancelWaitMsForTest = 0L
        val p = autoProtected()
        val own = kite.restsInKite(sym, "SELL", 130, 108.0, type = "SL", product = "NRML")
        later(65)
        kite.afterNext("/orders") {                                                 // a square-off's own cancel, marked as the app's
            Protections.noteTakenOver(listOf(p.stopOrderId!!)); kite.order(p.stopOrderId!!).status = "CANCELLED"
        }
        kite.drop += "/orders/regular/${p.targetOrderId}"
        tick()
        kite.drop.clear()
        kite.order(own).status = "REJECTED"
        tick()
        assertFalse(said("cancelled in Kite (by you)"))
    }

    /** Major 2: Boss cancels the app's SL in Kite himself while it was being taken out, then his own SL is refused - his cancel is respected. */
    @Test fun aStopBossCancelledHimselfIsNeverPutBackWhenHisOwnIsRefused() {
        Protections.cancelWaitMsForTest = 0L                                       // the target's cancel never reached Zerodha: not waited for
        val p = autoProtected()
        val own = kite.restsInKite(sym, "SELL", 130, 108.0, type = "SL", product = "NRML")
        later(65)
        kite.afterNext("/orders") { kite.order(p.stopOrderId!!).status = "CANCELLED" }   // he cancels the app's stop in Kite just then
        kite.drop += "/orders/regular/${p.targetOrderId}"                               // and the app's cancel of its target does not get through
        tick()
        assertEquals("OPEN", kite.order(p.targetOrderId!!).status)
        kite.drop.clear()
        kite.order(own).status = "REJECTED"
        val stops = kite.placed.count { it.form["order_type"] == "SL" }
        tick(); tick()
        assertEquals("his cancel respected: no stop put back", stops, kite.placed.count { it.form["order_type"] == "SL" })
        assertEquals("CANCELLED", kite.order(p.stopOrderId!!).status)
        assertTrue(said("cancelled in Kite (by you), so it is not put back"))
    }

    /**
     * Round 4 minor 1, as round 5 has it: his after-market stop for part, waiting for the open (Zerodha may yet refuse it), never cuts the app's
     * overnight GTT: it stays whole, and he is told once that both could sell.
     */
    @Test fun bossesAfterMarketStopForPartKeepsTheOvernightGttAndIsSaid() {
        val p = autoProtected()
        at(15, 45)
        listOfNotNull(p.stopOrderId, p.targetOrderId).forEach { kite.order(it).status = "CANCELLED" }
        tick()
        val g = kite.gtts.keys.single()
        val amo = kite.restingInKite(sym, "SELL", 65, 108.0, type = "SL", trigger = 108.0, product = "NRML")
        kite.order(amo).status = "AMO REQ RECEIVED"
        tick()
        assertEquals("the GTT kept", "active", kite.gtts.getValue(g).getString("status"))
        assertEquals("whole", 130, kite.gtts.getValue(g).getJSONArray("orders").getJSONObject(0).getInt("quantity"))
        assertTrue(said("part of it may not sell"))
    }

    /** Review: his after-market stop for all of it, waiting for the open - it may be refused then: the app's GTT stays, and he is told loudly. */
    @Test fun anAfterMarketStopForAllNeverTakesTheAppsGttOut() {
        val p = autoProtected()
        at(15, 45)
        listOfNotNull(p.stopOrderId, p.targetOrderId).forEach { kite.order(it).status = "CANCELLED" }
        tick()
        val g = kite.gtts.keys.single()
        val amo = kite.restingInKite(sym, "SELL", 130, 108.0, type = "SL", trigger = 108.0, product = "NRML")
        kite.order(amo).status = "AMO REQ RECEIVED"
        tick()
        val told = Alerts.posted.count { it.text.contains("part of it may not sell") }
        assertTrue("told", told > 0)
        tick(); tick()
        assertEquals("the GTT kept whole", "active", kite.gtts.getValue(g).getString("status"))
        assertEquals(130, kite.gtts.getValue(g).getJSONArray("orders").getJSONObject(0).getInt("quantity"))
        assertTrue(runBlocking { Protections.active() }.single().note != "removing")
        assertEquals("told once", told, Alerts.posted.count { it.text.contains("part of it may not sell") })
    }

    /** Minor 2: the app's stop fills while his own target still works on the closed position - said loudly, never "nothing else rested". */
    @Test fun theAppsStopFillingBesideBosssWorkingTargetIsSaidLoudly() {
        val p = autoProtected()
        val own = kite.restsInKite(sym, "SELL", 130, 140.0, product = "NRML")
        later(65)
        tick()
        assertEquals("his target is the target", "CANCELLED", kite.order(p.targetOrderId!!).status)
        kite.fill(p.stopOrderId!!, 115.35)
        tick()
        assertEquals("OPEN", kite.order(own).status)
        assertTrue(said("your own sell order for 130 still works in Kite. If the position is closed now, cancel it in Kite"))
        assertFalse(said("nothing else rested on it"))
    }

    /** Minor 3: its overnight GTT already deleted for his stop, then his stop refused - the old id cleared, and a new GTT rests. */
    @Test fun theOvernightGttDeletedForBosssRefusedStopRestsAgain() {
        val p = autoProtected()
        at(15, 45)
        listOfNotNull(p.stopOrderId, p.targetOrderId).forEach { kite.order(it).status = "CANCELLED" }
        tick()
        val g = kite.gtts.keys.single()
        val own = kite.restingInKite(sym, "SELL", 130, 108.0, type = "SL", trigger = 108.0, product = "NRML")
        kite.afterNext("/gtt/triggers/$g") { kite.gttReadFailures = 5 }      // deleted, but its delete not confirmed this look
        tick()
        assertFalse("deleted", kite.gtts[g]?.optString("status") == "active")
        kite.gttReadFailures = 0
        kite.order(own).status = "REJECTED"
        tick(); tick()
        val now = runBlocking { Protections.active() }.single()
        val g2 = now.gttId
        assertNotNull("a new overnight GTT rests", g2)
        assertTrue(g2 != g && kite.gtts.getValue(g2!!).getString("status") == "active")
        assertTrue(said("rests its overnight GTT at Zerodha again"))
    }

    /**
     * Round 5 (6): a GTT that fired is read on Zerodha's clock - the phone's offset to it when known (a 2-minute window), else a 3-minute one -
     * so a phone clock ahead never makes one that fired a moment ago read as long fired.
     */
    @Test fun aFiredGttIsReadOnZerodhasClock() {
        val fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        fun fired(agoMs: Long) = Broker.GttRow(6_301L, "single", "triggered", "NFO", sym, listOf(120.0), 126.0, "", "2026-11-23 09:15:00",
            firedAt = Market.now().toLocalDateTime().minusNanos(agoMs * 1_000_000L).format(fmt))
        // The phone 5 minutes ahead of Zerodha: fired 30 s ago by Zerodha's clock.
        Broker.serverOffsetMsForTest = 300_000L
        assertTrue("on Zerodha's clock: a moment ago", Protections.firedLatelyForTest(fired(330_000L)))
        Broker.serverOffsetMsForTest = null
        assertFalse("the offset not known: over the wide window", Protections.firedLatelyForTest(fired(330_000L)))
        assertTrue("the offset not known: 2.5 minutes is within the wide window", Protections.firedLatelyForTest(fired(150_000L)))
        assertFalse(Protections.firedLatelyForTest(fired(200_000L)))
        Broker.serverOffsetMsForTest = 0L
        assertFalse("the offset known: the 2-minute window", Protections.firedLatelyForTest(fired(150_000L)))
        assertTrue(Protections.firedLatelyForTest(fired(60_000L)))
    }

    /**
     * Round 5 (5): a GTT that fired is known as Boss's only when it was seen active as his in this run, or made after this run began - after
     * a restart, one of the app's own that fired is never taken for his.
     */
    @Test fun aFiredGttIsHissOnlyWhenSeenOrMadeSinceThisRunBegan() {
        val fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val madeAt = java.time.LocalDateTime.now(com.optionslab.engine.IST).withNano(0)
        val madeMs = madeAt.atZone(com.optionslab.engine.IST).toInstant().toEpochMilli()
        fun g(id: Long) = Broker.GttRow(id, "single", "triggered", "NFO", sym, listOf(120.0), 126.0, "", madeAt.format(fmt))
        Broker.serverOffsetMsForTest = 0L
        Protections.trackedSinceForTest = madeMs
        assertFalse("made as this run began: not known", Protections.hisGttKnownForTest(g(6_401L)))
        Protections.seeHisGttsForTest(listOf(6_401L))
        assertTrue("seen active as his", Protections.hisGttKnownForTest(g(6_401L)))
        Protections.trackedSinceForTest = madeMs - 10 * 60_000L
        assertTrue("made well after this run began", Protections.hisGttKnownForTest(g(6_402L)))
        assertFalse("its time not told: not known", Protections.hisGttKnownForTest(g(6_403L).copy(created = "")))
    }

    // ---- 24 Nov 2026 (B6-I round 6, final integration review) ------------------------------------------------------------------------

    /**
     * Item 2: whose a fired GTT's order is, for another book's cancel ([Protections.gttOrdersNotBosss]) - the app's own GTTs' and any not known
     * to be his are never his; one known to be his is his. Only this contract's.
     */
    @Test fun aFiredGttsOrderIsBosssOnlyWhenItsGttIsKnownToBeHis() {
        val fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val madeAt = java.time.LocalDateTime.now(com.optionslab.engine.IST).withNano(0)
        val madeMs = madeAt.atZone(com.optionslab.engine.IST).toInstant().toEpochMilli()
        fun g(id: Long, order: String) = Broker.GttRow(id, "single", "triggered", "NFO", sym, listOf(120.0), 126.0, "", madeAt.format(fmt), orderIds = listOf(order))
        Broker.serverOffsetMsForTest = 0L
        Protections.trackedSinceForTest = madeMs - 10 * 60_000L              // made well after this run began: known to be his
        Protections.noteAppGttForTest(7_101L)                                // one of the app's own
        val gtts = listOf(g(7_101L, "a1"), g(7_102L, "b1"), g(7_103L, "c1").copy(created = ""), g(7_104L, "d1").copy(symbol = "NIFTY26N0925200CE"))
        assertEquals("the app's own and the one not known to be his", setOf("a1", "c1"), Protections.gttOrdersNotBosss("NFO", sym, gtts))
        Protections.trackedSinceForTest = madeMs                             // made as this run began: not known to be his
        assertEquals(setOf("a1", "b1", "c1"), Protections.gttOrdersNotBosss("NFO", sym, gtts))
        Protections.seeHisGttsForTest(listOf(7_102L))                        // seen active as his
        assertEquals(setOf("a1", "c1"), Protections.gttOrdersNotBosss("NFO", sym, gtts))
    }

    /**
     * MAJOR: Boss's sale fills while the app's stop is going in - after the last check read his position held, before the protection exists -
     * and the stream says so then. Noted all the same, and counted from when the cover's look began: in its first minute the protections read
     * what is held at once, and the stop comes out of the flat row.
     */
    @Test fun bossesSaleFilledWhileTheCoverGoesInTakesItsStopOutInItsFirstMinute() {
        val id = kite.boughtInKite(sym, 130, 125.35)
        kite.afterNext("/orders/regular") {
            val sold = kite.soldInKite(sym, 130, 127.0)
            update(sold, "COMPLETE", 130, side = "SELL")
        }
        update(id, "COMPLETE", 130)
        Background.await("covered") { runBlocking { Protections.active() }.isNotEmpty() }
        settled()
        val p = runBlocking { Protections.active() }.single()
        assertEquals("the app's stop rests", "TRIGGER PENDING", kite.order(p.stopOrderId!!).status)
        tick()                                                              // its first minute
        Background.await("the app's stop out of the flat row") { kite.order(p.stopOrderId!!).status == "CANCELLED" }
        Background.await("its protection ended") { runBlocking { Protections.active() }.isEmpty() }
        assertEquals("position closed", runBlocking { Protections.endedSince(0) }.single { it.id == p.id }.note)
    }

    /**
     * MAJOR (lots added): Boss adds 65, then sells 65 and it fills between the slices' check outside the lock and their placing - nothing is
     * added beside the app's stop, which already covers all he holds.
     */
    @Test fun lotsAddedThenSoldBeforeTheyAreCoveredAreNeverCovered() {
        val id = kite.boughtInKite(sym, 130, 125.35)
        update(id, "COMPLETE", 130)
        Background.await("covered") { kite.placed.isNotEmpty() }
        settled()
        val more = kite.boughtInKite(sym, 65, 125.35)
        // (The look's own check reads the orders, then the slices' check before the lock; the sale fills just before the last check's read.)
        kite.afterNext("/quote") { kite.afterNext("/orders") { kite.afterNext("/orders") { kite.beforeNext("/orders") { kite.soldInKite(sym, 65, 126.0) } } } }
        update(more, "COMPLETE", 65, qty = 65)
        Background.await("looked at") { Diag.lines().any { it.contains("the 65 added not covered") } }
        settled()
        assertTrue(Diag.lines().joinToString("\n"), Diag.lines().any { it.contains("the 65 added not covered") && it.contains("a sale on it filled a moment ago") })
        assertEquals("nothing added: ${kite.placed.map { it.form }}", 1, kite.placed.size)
        assertEquals(130, runBlocking { Protections.active() }.sumOf { it.qty })
        assertEquals("never left holding the row", null, SquareOffExit.exitClaimedBy("NFO", sym, "MIS"))
    }
}
