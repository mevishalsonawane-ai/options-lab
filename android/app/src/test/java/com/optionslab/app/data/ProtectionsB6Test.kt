package com.optionslab.app.data

import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.MarketClockRule
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.work.Alerts
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode

/**
 * 22 Nov 2026 (B6 review of the exits, the protections - [Protections]), against the fake Kite:
 *  - M5: Zerodha takes no second full sale beside a resting stop (a new short, refused for funds), so an intraday target was never placed,
 *    saved as none, and nothing watched it. Now an intraday stop and target rest only the stop at Zerodha; the target is the app's own
 *    (saved, shown): reached, the app takes the stop out first (marked its own), confirms it out (a part filled: only the rest is sold),
 *    sells, and puts the stop back if the sale is refused; a stream tick only marks it. A carried target refused the same way is the app's
 *    too; any other refusal is said, never dropped unsaid (the re-placing at the open included).
 *  - M6: the marks of the stops the app cancels itself (a square-off, Boss's exit from the app, the target's sale) are kept on disk, so a
 *    restart never reads such a cancel as Boss's; they expire, and one whose cancel never took ends.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class ProtectionsB6Test : RobolectricTest() {
    private lateinit var kite: FakeKite
    private val sym = "NIFTY26OCT24500CE"

    @Before fun up() {
        kite = FakeKite()
        kite.instruments += FakeKite.Ins(12_345_678L, sym, "NIFTY", Market.today().plusDays(20), 24_500.0, "CE", 75)
        kite.quote("NFO:$sym", 100.0, 99.95, 100.05)
        kite.position(sym, 75, 100.0, product = "MIS")
        kite.login()
        Alerts.forgetPosted()
        com.optionslab.app.testing.Background.clearAlerts()
        DayOco.resetForTest()
        com.optionslab.app.testing.AutomationSupport.liveSettings()
        runBlocking { Broker.instruments() }
    }

    @After fun down() {
        Protections.resetFollowUpForTest()
        KiteStream.resetForTest()
        SquareOffExit.resetForTest(gap = 0L)
        Broker.lostSearchMsForTest = null; Broker.lostSearchInlineForTest = false; Broker.lostLookGapMsForTest = null
        Market.testClock = null; kite.close(); assertEquals(emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun at(h: Int, m: Int) { Market.testClock = MarketClockRule.clockAt(h, m) }
    private fun tick() = runBlocking { Protections.tick() }
    private fun active() = runBlocking { Protections.active() }
    private fun protect(product: String = "MIS", stop: Double? = 90.0, target: Double? = 130.0, qty: Int = 75): String = runBlocking {
        Protections.protectLive(sym, "NFO", product, qty, 100.0, stop, null, target, recheck = true)
    }
    private fun held(product: String = "MIS") = kite.positions["NFO:$sym:$product"]?.first ?: 0
    private fun sales() = kite.placed.filter { it.form["order_type"] == "MARKET" && it.form["transaction_type"] == "SELL" }

    // ---- M5: the intraday target is the app's own -------------------------------------------------------------------------------

    @Test fun anIntradayStopAndTargetRestOnlyTheStopAndTheTargetIsKeptAndShown() {
        val msg = protect()
        assertTrue(msg, msg.startsWith("Protected") && msg.contains(Protections.TARGET_BY_APP))
        assertEquals("only the stop at Zerodha", listOf("SL"), kite.placed.map { it.form["order_type"] })
        val p = active().single()
        assertEquals("the target kept", 130.0, p.target!!, 1e-9)
        assertTrue(p.appTarget); assertNull(p.targetOrderId)
        assertEquals("stop 90.00 · target 130.00 (watched by the app)", p.describe())
        // Saved: a restart keeps it.
        Protections.restartForTest()
        val again = active().single()
        assertTrue(again.appTarget); assertEquals(130.0, again.target!!, 1e-9)
        // A target alone (no stop beside it) still rests at Zerodha as before.
        runBlocking { Protections.remove(again.id) }
        kite.requests.clear()
        protect(stop = null)
        assertEquals(listOf("LIMIT"), kite.placed.map { it.form["order_type"] })
        assertFalse(active().single().appTarget)
    }

    /** Reached: the stop is marked the app's own and taken out first, confirmed, then the sale goes - one exit; then it ends. */
    @Test fun theTargetReachedTakesTheStopOutThenSells() {
        SquareOffExit.resetForTest(gap = SquareOffExit.GAP_MS)               // (the app's gap: the sale counted through it for the square-offs)
        protect()
        val p = active().single()
        at(12, 5)
        tick()
        assertTrue("below the target: nothing", sales().isEmpty())
        kite.quote("NFO:$sym", 131.0, 130.95, 131.05)
        kite.requests.clear()
        tick()
        val reqs = kite.requests.toList()
        val cancel = reqs.indexOfFirst { it.method == "DELETE" && it.path.endsWith(p.stopOrderId!!) }
        val sale = reqs.indexOfFirst { it.method == "POST" && it.path == "/orders/regular" && it.form["order_type"] == "MARKET" }
        assertTrue("the stop out before the sale: $reqs", cancel in 0 until sale)
        assertEquals("CANCELLED", kite.order(p.stopOrderId!!).status)
        assertEquals(listOf("75"), sales().map { it.form["quantity"] })
        assertEquals("iraprotect", sales().single().form["tag"])
        assertEquals("flat, never short", 0, held())
        assertTrue("its cancel marked the app's own", Protections.takenOverForTest(p.stopOrderId!!))
        assertEquals("the square-offs count it as going for their gap", 75, SquareOffExit.lostPending(sym, "MIS"))
        Protections.ageTargetSalesForTest(SquareOffExit.GAP_MS)
        assertEquals(0, Protections.targetSalesGoing(sym, "MIS"))
        com.optionslab.app.testing.SecondRead.clockOn(); tick()
        assertTrue("ended", active().isEmpty())
        assertEquals("never a stop put back on a flat position", 0, kite.placed.count { it.form["order_type"] == "SL" })
        assertTrue(Alerts.posted.any { it.text.contains("its target 130.00 was reached") })
    }

    /** One seller at a time on a row: while a square-off holds the row's claim, the target takes nothing out; then it sells. */
    @Test fun theTargetWaitsWhileASquareOffHoldsTheRowsClaim() {
        protect()
        val p = active().single()
        at(12, 5)
        kite.quote("NFO:$sym", 131.0, 130.95, 131.05)
        val claim = SquareOffExit.claimExit("NFO", sym, "MIS", "iraalgoflat")!!
        try {
            tick()
            assertTrue("its stop left resting", kite.order(p.stopOrderId!!).working)
            assertTrue(sales().isEmpty())
        } finally { claim.release() }
        tick()
        assertEquals("CANCELLED", kite.order(p.stopOrderId!!).status)
        assertEquals(1, sales().size)
        assertEquals(null, SquareOffExit.exitClaimedBy("NFO", sym, "MIS"))
    }

    /** The sale refused outright: the stop is put back at once, the target still the app's. */
    @Test fun aRefusedTargetSalePutsTheStopBackAtOnce() {
        protect()
        val p = active().single()
        at(12, 5)
        kite.quote("NFO:$sym", 131.0, 130.95, 131.05)
        kite.nextPlace(FakeKite.Reply.INPUT_EXCEPTION, message = "MARKET orders are blocked for this contract.")
        tick()
        assertEquals("CANCELLED", kite.order(p.stopOrderId!!).status)
        assertEquals(75, held())
        val now = active().single()
        assertNotNull(now.stopOrderId)
        assertTrue("a new stop rests", now.stopOrderId != p.stopOrderId && kite.order(now.stopOrderId!!).working)
        assertEquals("75", kite.placed.last().form["quantity"]); assertEquals("SL", kite.placed.last().form["order_type"])
        assertTrue("still the app's target", now.appTarget); assertEquals(130.0, now.target!!, 1e-9)
        assertTrue(Alerts.posted.any { it.text.contains("refused the sale") })
        assertFalse("its mark ended with the put-back", Protections.takenOverForTest(p.stopOrderId!!))
    }

    /** The stop filled in part as it came out: only the rest is sold. */
    @Test fun aStopFilledInPartAsItCameOutSellsOnlyTheRest() {
        kite.position(sym, 150, 100.0, product = "MIS")
        protect(qty = 150)
        val p = active().single()
        at(12, 5)
        kite.quote("NFO:$sym", 131.0, 130.95, 131.05)
        // Its cancel finds 75 of it filled meanwhile (it triggered as it came out).
        kite.afterNext("/orders/regular/${p.stopOrderId}") {
            val o = kite.order(p.stopOrderId!!)
            o.filled = 75; o.avg = 90.0
            kite.positions["NFO:$sym:MIS"] = 75 to 100.0
        }
        tick()
        assertEquals("only the rest", listOf("75"), sales().map { it.form["quantity"] })
        assertEquals("flat, never short", 0, held())
    }

    /** A stop still working after its cancel (not confirmed out): nothing is sold beside it; the next look tries again. */
    @Test fun aStopNotConfirmedOutSellsNothing() {
        protect()
        val p = active().single()
        at(12, 5)
        kite.quote("NFO:$sym", 131.0, 130.95, 131.05)
        kite.cancelsLost = 1
        tick()
        assertTrue("its stop still rests", kite.order(p.stopOrderId!!).working)
        assertTrue("nothing beside it", sales().isEmpty())
        tick()
        assertEquals("CANCELLED", kite.order(p.stopOrderId!!).status)
        assertEquals(1, sales().size)
        assertEquals(0, held())
    }

    /**
     * A stream tick (the live lane) only marks the target reached and asks a look apart - no Zerodha write from the tick's look; the look
     * apart sells the target the tick reached even if the price has slipped back a moment later.
     */
    @Test fun aStreamTickOnlyMarksTheTargetAndALookApartSells() {
        protect()
        val p = active().single()
        at(12, 5)
        tick()
        // The look apart held back (as when one is already waiting), so this sees what the stream look itself does.
        val apart = Protections::class.java.getDeclaredField("followApart").apply { isAccessible = true }.get(Protections) as java.util.concurrent.atomic.AtomicBoolean
        apart.set(true)
        try {
            kite.requests.clear()
            KiteStream.feedForTest(listOf(com.optionslab.engine.KiteTicks.Tick(12_345_678L, 131.0)))
            runBlocking { Protections.tick(fast = true, stream = true) }
            assertTrue("no Zerodha write from the tick's look: ${kite.requests}", kite.requests.none { it.method != "GET" })
            assertTrue("nothing sold yet", sales().isEmpty())
        } finally { apart.set(false) }
        KiteStream.feedForTest(listOf(com.optionslab.engine.KiteTicks.Tick(12_345_678L, 129.5)))
        tick()
        assertEquals("CANCELLED", kite.order(p.stopOrderId!!).status)
        assertEquals("the target the tick reached is sold", 1, sales().size)
        assertEquals(0, held())
    }

    /** The target's sale whose reply is lost and not found: no stop goes back beside it until the book shows it or it is let go. */
    @Test fun aLostTargetSaleKeepsTheStopOutUntilItIsKnown() {
        protect()
        val p = active().single()
        at(12, 5)
        kite.quote("NFO:$sym", 131.0, 130.95, 131.05)
        kite.trailReads = 1_000
        kite.positionsTrailReads = 1_000
        kite.nextPlace(reply = FakeKite.Reply.DROP_AFTER_ACCEPT)
        Broker.lostLookGapMsForTest = 0; Broker.lostSearchInlineForTest = true
        tick()
        assertEquals(1, sales().size)
        assertTrue(Alerts.posted.any { it.text.contains("answer to the sale was lost") })
        at(12, 6); tick(); tick()
        assertEquals("no stop beside a sale that may be selling", 1, kite.placed.count { it.form["order_type"] == "SL" })
        kite.showInBook(); kite.positionsCatchUp()
        at(12, 7); tick()
        com.optionslab.app.testing.SecondRead.clockOn(); tick()
        assertTrue("found, flat: ended", active().isEmpty())
        assertEquals(1, kite.placed.count { it.form["order_type"] == "SL" })
        assertEquals(0, held())
    }

    /**
     * Review: after a restart a lost target sale's search (memory only) is gone, but its mark is kept: no stop goes back beside it for two
     * minutes from the sale; after that the lag-guarded reads decide - the sale shown filled, nothing is put back.
     */
    @Test fun aLostTargetSaleStillKeepsTheStopOutAfterARestart() {
        protect()
        val p = active().single()
        at(12, 5)
        kite.quote("NFO:$sym", 131.0, 130.95, 131.05)
        kite.trailReads = 1_000
        kite.positionsTrailReads = 1_000
        kite.nextPlace(reply = FakeKite.Reply.DROP_AFTER_ACCEPT)
        tick()
        assertEquals(1, sales().size)
        Protections.restartForTest(); Broker.forgetLostForTest()              // the phone restarted: the search is gone
        at(12, 6); tick()
        assertEquals("no stop beside the lost sale", 1, kite.placed.count { it.form["order_type"] == "SL" })
        assertEquals(1, active().size)
        Protections.ageTakenOverForTest("prottarget:${p.id}", Broker.LOST_SEARCH_MS)
        kite.showInBook()                                                     // the book shows it by now
        at(12, 8); tick()
        assertEquals("the sale shown filled: nothing put back", 1, kite.placed.count { it.form["order_type"] == "SL" })
        kite.positionsCatchUp()
        at(12, 9); tick()
        assertTrue(active().isEmpty())
        assertEquals(0, held())
    }

    /** A carried target refused for funds beside its stop is the app's; any other refusal is said - the re-placing at the open included. */
    @Test fun aCarriedTargetRefusedForFundsIsTheAppsAndOtherRefusalsAreSaid() {
        kite.position(sym, 0, 0.0, product = "MIS"); kite.position(sym, 75, 100.0)
        kite.afterNext("/orders/regular") { kite.nextPlace(FakeKite.Reply.INPUT_EXCEPTION, message = "Insufficient funds. Required margin is 52000.00 but available margin is 1200.00.") }
        val msg = protect(product = "NRML")
        assertTrue(msg, msg.contains(Protections.TARGET_BY_APP))
        val p = active().single()
        assertTrue(p.appTarget); assertEquals(130.0, p.target!!, 1e-9)
        // The open's re-placing: its stop and target ended with yesterday's session; the target refused otherwise is said, not dropped.
        runBlocking { Protections.remove(p.id) }
        kite.requests.clear()
        kite.afterNext("/orders/regular") { kite.nextPlace(FakeKite.Reply.INPUT_EXCEPTION, message = "Price exceeds the circuit limit.") }
        val second = protect(product = "NRML")
        assertTrue(second, second.contains("The target was not placed (Price exceeds the circuit limit.)"))
        assertFalse(active().single().appTarget)
    }

    /** Before: restAgainInTurn dropped a refused target unsaid. Now the carried protection's open re-placing says it. */
    @Test fun aTargetRefusedAtTheOpenIsSaid() {
        kite.position(sym, 0, 0.0, product = "MIS"); kite.position(sym, 75, 100.0)
        protect(product = "NRML")
        val p = active().single()
        assertNotNull(p.targetOrderId)
        // Boss's exit takes both out, then does not go: the stop and target are put back - the target refused.
        Protections.noteTakenOver(listOfNotNull(p.stopOrderId, p.targetOrderId))
        runBlocking { listOfNotNull(p.stopOrderId, p.targetOrderId).forEach { Broker.cancel(it) } }
        at(12, 5)
        kite.afterNext("/orders/regular") { kite.nextPlace(FakeKite.Reply.INPUT_EXCEPTION, message = "Price exceeds the circuit limit.") }
        tick()
        val now = active().single()
        assertNotNull("the stop back", now.stopOrderId)
        assertTrue("the refusal said", Alerts.posted.any { it.text.contains("its target 130.00 could not be placed again") })
    }

    // ---- M6: the taken-over marks kept across a restart ---------------------------------------------------------------------

    /** A square-off's or Boss's own exit from the app took the stop out, then the phone restarted: still the app's cancel - put back. */
    @Test fun aTakenOverMarkSurvivesARestartAndTheStopIsPutBack() {
        kite.position(sym, 0, 0.0, product = "MIS"); kite.position(sym, 75, 100.0)
        protect(product = "NRML", target = null)
        val p = active().single()
        Protections.noteTakenOver(listOf(p.stopOrderId!!))
        runBlocking { Broker.cancel(p.stopOrderId!!) }
        Protections.restartForTest()                                         // the phone restarted before the put-back
        at(12, 5)
        tick()
        val now = active().single()
        assertTrue("put back, never read as Boss's", now.stopOrderId != null && now.stopOrderId != p.stopOrderId)
        assertTrue(kite.order(now.stopOrderId!!).working)
        assertFalse(Alerts.posted.any { it.text.contains("cancelled by you") })
        assertFalse("the mark ended with the put-back", Protections.takenOverForTest(p.stopOrderId!!))
        Protections.restartForTest()
        active()
        assertFalse("and on disk too", Protections.takenOverForTest(p.stopOrderId!!))
    }

    /** Without a mark (Boss cancelled it in Kite), the same cancel is his: never put back - as before. */
    @Test fun withoutAMarkACancelledStopIsBossesAfterARestart() {
        kite.position(sym, 0, 0.0, product = "MIS"); kite.position(sym, 75, 100.0)
        protect(product = "NRML", target = null)
        val p = active().single()
        runBlocking { Broker.cancel(p.stopOrderId!!) }
        Protections.restartForTest()
        at(12, 5)
        tick()
        assertEquals("never put back", 1, kite.placed.count { it.form["order_type"] == "SL" })
        assertTrue(active().isEmpty())
    }

    /** A mark older than an hour is not taken back after a restart; one whose stop still works two minutes on ends (its cancel never took). */
    @Test fun takenOverMarksExpire() {
        kite.position(sym, 0, 0.0, product = "MIS"); kite.position(sym, 75, 100.0)
        protect(product = "NRML", target = null)
        val p = active().single()
        val id = p.stopOrderId!!
        Protections.noteTakenOver(listOf(id))
        at(12, 5)
        tick()
        assertTrue("kept while its cancel may still land", Protections.takenOverForTest(id))
        Protections.ageTakenOverForTest(id, Protections.TAKEN_SETTLE_MS + 1_000)
        tick()
        assertFalse("still working two minutes on: ended", Protections.takenOverForTest(id))
        // Boss now cancels it in Kite: his - never put back.
        runBlocking { Broker.cancel(id) }
        tick()
        assertEquals(1, kite.placed.count { it.form["order_type"] == "SL" })
        // An hour-old mark kept on disk is not read back after a restart; a fresh one is.
        Protections.drainMarksForTest()
        val now = System.currentTimeMillis()
        com.optionslab.app.security.Vault.writeFile(java.io.File(context.noBackupFilesDir, "protections_marks.vault"),
            org.json.JSONObject().put("old-1", now - 61 * 60_000L).put("fresh-1", now - 60_000L).toString().toByteArray(Charsets.UTF_8))
        Protections.restartForTest()
        active()
        assertFalse(Protections.takenOverForTest("old-1"))
        assertTrue(Protections.takenOverForTest("fresh-1"))
    }

    /** "Erase everything": the marks' file goes with the rest. */
    @Test fun theMarksFileIsErased() {
        Protections.noteTakenOver(listOf("x1"))
        Protections.drainMarksForTest()
        val f = java.io.File(context.noBackupFilesDir, "protections_marks.vault")
        assertTrue(f.exists())
        assertTrue(Protections.filesForErase().contains(f))
        Protections.wipe()
        assertFalse(f.exists())
        Protections.restartForTest()
        active()
        assertFalse(Protections.takenOverForTest("x1"))
    }
}
