package com.optionslab.app.data

import com.optionslab.app.security.Vault
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.FakeUpstox
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.Kite
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
import java.io.File
import java.time.LocalDateTime

/**
 * 22 Nov 2026 (B6 review): the ORB family's and Liquidity 15+5's live stops at Zerodha ([OrbArms]) - WHO CANCELLED A STOP across a restart
 * (the app's own cancel is kept in the book before it goes; one that never took ends; one outside market hours is Zerodha's; Stop for today
 * no longer makes every cancel the app's), a found lost stop taken out after part of it filled (that part booked), a closed trade's lost
 * stop still being cancelled across a restart, a malformed positions row never read as "nothing held", a stop put back for what is held
 * lag-guarded, and the exits' labels never waiting on the Strategy Module's lock. Against the fake Kite, the clocks fixed.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class OrbStopCancelsTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var kite: FakeKite
    private val sym = "BANKNIFTY26OCT52000CE"
    private val paperCe = "BANKNIFTY-TEST-52000CE"
    private val paperPe = "BANKNIFTY-TEST-52000PE"
    private val expiry = AutomationSupport.expiryAfter(7)
    private lateinit var now: LocalDateTime
    private val file get() = File(context.filesDir, "orb.vault")

    @Before fun up() {
        kite = FakeKite()
        kite.login()
        AutomationSupport.liveSettings()
        AutomationSupport.security(compromised = false)
        com.optionslab.app.testing.Background.clearAlerts()
        kite.instruments += FakeKite.Ins(22_000_001, sym, "BANKNIFTY", expiry, 52_000.0, "CE", 30)
        kite.instruments += FakeKite.Ins(22_000_002, "BANKNIFTY26OCT52000PE", "BANKNIFTY", expiry, 52_000.0, "PE", 30)
        kite.quote("NFO:$sym", 200.0, 199.95, 200.05)
        runBlocking { Broker.instruments() }
        kite.requests.clear()
        val t = AutomationSupport.earlierToday()
        OrbArms.testNow = t
        OrbArms.testIndexBars = { emptyList() }
        now = t.toLocalDateTime().withNano(0)
    }

    @After fun down() {
        Market.testOrdersAnyTime = true
        Broker.lostLookGapMsForTest = null; Broker.lostSearchInlineForTest = false; Broker.forgetLostForTest()
        OrbArms.lostSweepAgeMsForTest = null
        AutomationSupport.realClocks()
        kite.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun contract(symbol: String, right: String) =
        JSONArray().put(symbol).put("BANKNIFTY").put(expiry.toString()).put(52_000.0).put(right).put(30).put("NSE_FO|TEST$right")

    /** Minutes before [now], but never yesterday. */
    private fun ago(minutes: Long) = maxOf(now.minusMinutes(minutes), now.toLocalDate().atStartOfDay())

    private fun position(arm: String, qty: Int, entry: Double, entryId: String?, stopId: String? = null, stop: Double? = null) =
        JSONObject().put("arm", arm).put("symbol", paperCe).put("right", "CE").put("qty", qty).put("entry", entry)
            .put("entryTime", ago(30).toString()).put("signalBar", ago(35).toString())
            .put("entryOrderId", entryId ?: "").put("stopOrderId", stopId ?: "").apply { stop?.let { put("stopTrigger", it) } }
            .put("exitTime", "").put("why", "").put("charges", 0.0).put("live", true).put("kite", sym).put("unconfirmed", false)

    /** A live Liquidity 15+5 trade (the 5-minute BANKNIFTY book) that broke 52,050, one lot of 30, entered 30 minutes ago. */
    private fun liquidity(qty: Int, entry: Double, stopId: String?, stop: Double?) =
        position("liquidity5", qty, entry, "E1", stopId, stop).put("level", 52_050.0).put("lot", 30)

    private fun state(positions: List<JSONObject>) {
        AutomationSupport.orbState(context, JSONObject()
            .put("armed", JSONObject().put("orb", true)).put("auto", JSONObject().put("orb", true)).put("liveOk", JSONObject().put("orb", true))
            .put("legs", JSONObject().put("day", now.toLocalDate().toString()).put("strike", 52_000).put("expiry", expiry.toString())
                .put("ce", contract(paperCe, "CE")).put("pe", contract(paperPe, "PE")))
            .put("positions", JSONArray(positions)))
    }

    private fun arm(source: String) = runBlocking { OrbArms.view() }.arms.single { it.arm.source == source }
    private fun today(source: String) = arm(source).today
    private fun pass() = runBlocking { OrbArms.priceCheckOnly() }
    private fun stopOrder(qty: Int, trigger: Double, limit: Double) = runBlocking {
        Broker.placeOrder(Kite.Order(sym, Kite.Side.SELL, qty, 30, "MIS", "SL", limit, 0.05, "NFO", "iraorb", triggerPrice = trigger), exit = true)
    }
    private fun marketSells() = kite.placed.filter { it.form["transaction_type"] == "SELL" && it.form["order_type"] == "MARKET" }
    private fun stopsPlaced() = kite.placed.filter { it.form["order_type"] == "SL" }
    private fun saidYours() = AutomationSupport.alerts().any { "looks cancelled by you" in it }
    private fun <T> withUpstox(body: () -> T): T {
        val u = FakeUpstox()
        try { u.price("NSE_FO|TESTCE", 1.0); return body() } finally { u.close() }
    }

    // ---- item 2: the app's own cancels across a restart (WHO CANCELLED A STOP) ------------------------------------------------------

    /**
     * The finding: the exit's cancel of its stop was marked in memory only, and the book saved after the pass - the phone dying in
     * between came back to a stop CANCELLED with no words, read as Boss's (never put back, and Boss told he had cancelled it). The mark
     * is now in the book before the cancel goes: after the restart the cancel is still the app's.
     */
    @Test fun aRestartBetweenTheAppsOwnCancelAndTheSaveKeepsTheCancelTheApps() {
        val s1 = stopOrder(30, 170.0, 161.5)
        state(listOf(liquidity(30, 200.0, s1, 170.0)))                     // its time stop is due
        kite.position(sym, 30, 200.0, product = "MIS")
        val atCancel = java.util.concurrent.atomic.AtomicReference<ByteArray?>()
        // The book on disk right as the cancel is confirmed (the writer given its chance, as a crash right after would leave it).
        kite.afterNext("/orders/regular/$s1") { OrbArms.awaitWrites(); atCancel.set(Vault.readFile(file)) }
        pass()
        assertEquals("CANCELLED", kite.order(s1).status)
        assertEquals("the exit sold", 1, marketSells().size)
        val snap = atCancel.get()!!
        assertTrue("the mark is in the book written before the cancel went", JSONObject(String(snap, Charsets.UTF_8)).optJSONObject("ownCancels")?.has(s1) == true)
        // The phone "dies" with the book as it was at the cancel; the app starts again.
        Vault.writeFile(file, snap)
        OrbArms.forgetMemoryForTest()
        com.optionslab.app.testing.Background.clearAlerts()
        pass()
        assertTrue("the cancel is still the app's own", OrbArms.ownCancelForTest(s1))
        assertFalse("never read as Boss's", today("liquidity").single().stopByYou)
        assertFalse("never told he cancelled it: ${AutomationSupport.alerts()}", saidYours())
    }

    /**
     * A cancel of the app's own that never took (its stop still working two minutes after): the mark ends, so Boss's own later cancel of
     * that stop in Kite is his - not put back, and said so.
     */
    @Test fun anOwnCancelThatNeverTookEndsSoBossesLaterCancelIsHis() {
        var wall = 1_000_000_000L
        OrbArms.testWallMs = { wall }
        val s1 = stopOrder(30, 160.0, 152.0)
        state(listOf(position("orb", 30, 200.0, "E1", s1, 160.0)))
        kite.position(sym, 30, 200.0, product = "MIS")
        kite.quote("NFO:$sym", 245.0, 244.95, 245.05)                       // the ORB's +40 target
        kite.cancelsLost = 1                                                // the app's cancel is answered, but never lands
        kite.requests.clear()
        pass()
        assertTrue("its cancel did not take", kite.order(s1).working)
        assertTrue("marked the app's own meanwhile", OrbArms.ownCancelForTest(s1))
        assertTrue("nothing sold beside a stop still working", marketSells().isEmpty())
        kite.quote("NFO:$sym", 220.0, 219.95, 220.05)                       // back under the target: no exit due
        wall += OrbArms.EXIT_MARK_SETTLE_MS - 1_000
        pass()
        assertTrue("still marked inside two minutes", OrbArms.ownCancelForTest(s1))
        wall += 2_000
        pass()
        assertFalse("seen working past two minutes: the mark ended", OrbArms.ownCancelForTest(s1))
        kite.reject(s1, message = "", status = "CANCELLED")                 // Boss cancels it in Kite
        pass()
        val row = arm("orb").open!!
        assertTrue("his own cancel", row.stopByYou)
        assertNull(row.stopOrderId)
        assertTrue(saidYours())
        assertTrue("never put back", stopsPlaced().isEmpty())
    }

    /**
     * The app's cancel never reached Zerodha (its mark already in the book) and the phone restarts: the mark taken back from the book
     * ends the first time its stop is seen working, so Boss's own cancel of it in Kite right after is his - never put back.
     */
    @Test fun aMarkTakenBackAfterARestartEndsOnceItsStopIsSeenWorking() {
        val s1 = stopOrder(30, 160.0, 152.0)
        state(listOf(position("orb", 30, 200.0, "E1", s1, 160.0)))
        kite.position(sym, 30, 200.0, product = "MIS")
        kite.quote("NFO:$sym", 245.0, 244.95, 245.05)                       // the ORB's +40 target
        kite.down += "/orders/regular/$s1"                                  // the app's cancel never reaches Zerodha
        pass()
        assertTrue(kite.order(s1).working)
        assertTrue(OrbArms.ownCancelForTest(s1))
        kite.down.clear()
        kite.quote("NFO:$sym", 220.0, 219.95, 220.05)                       // no exit due any more
        assertTrue(OrbArms.awaitWrites())
        OrbArms.forgetMemoryForTest()                                       // a restart: the mark is read back from the book
        kite.requests.clear()
        pass()
        assertFalse("seen working: the mark ended", OrbArms.ownCancelForTest(s1))
        kite.reject(s1, message = "", status = "CANCELLED")                 // Boss cancels it in Kite
        pass()
        assertTrue("his own cancel", arm("orb").open!!.stopByYou)
        assertTrue(saidYours())
        assertTrue("never put back", stopsPlaced().isEmpty())
    }

    /** A cancel with no words and no mark of the app's, made outside market hours (the day's end), is Zerodha's: the stop goes back. */
    @Test fun aBlankCancelOutsideMarketHoursIsZerodhasAndTheStopGoesBack() {
        Market.testOrdersAnyTime = false
        val s1 = stopOrder(30, 160.0, 152.0)
        state(listOf(position("orb", 30, 200.0, "E1", s1, 160.0)))
        kite.position(sym, 30, 200.0, product = "MIS")
        kite.reject(s1, message = "", status = "CANCELLED")
        kite.order(s1).updated = now.toLocalDate().atTime(15, 45)           // Zerodha's own time of it: after F&O's close
        kite.requests.clear()
        pass()
        val row = arm("orb").open!!
        assertFalse("Zerodha's, not Boss's", row.stopByYou)
        assertFalse(saidYours())
        assertEquals("put back at Zerodha", 1, stopsPlaced().size)
        assertEquals(kite.orders.values.last { it.type == "SL" }.id, row.stopOrderId)
    }

    /** The same blank cancel while the market takes orders (Zerodha's time of it in hours) is Boss's: respected, never put back. */
    @Test fun aBlankCancelInMarketHoursIsStillBosses() {
        Market.testOrdersAnyTime = false
        val s1 = stopOrder(30, 160.0, 152.0)
        state(listOf(position("orb", 30, 200.0, "E1", s1, 160.0)))
        kite.position(sym, 30, 200.0, product = "MIS")
        kite.reject(s1, message = "", status = "CANCELLED")
        kite.order(s1).updated = now.toLocalDate().atTime(11, 0)
        kite.requests.clear()
        pass()
        assertTrue(arm("orb").open!!.stopByYou)
        assertTrue(saidYours())
        assertTrue(stopsPlaced().isEmpty())
    }

    /**
     * Item 6c: with Stop for today on, a cancel the app never marked follows the normal rule - Boss's own in Kite stays his (before, any
     * cancel then was taken as the app's). Stop for today still sells the lot.
     */
    @Test fun withStopForTodayAnUnmarkedCancelIsStillBosses() {
        val s1 = stopOrder(30, 160.0, 152.0)
        state(listOf(position("orb", 30, 200.0, "E1", s1, 160.0)))
        kite.position(sym, 30, 200.0, product = "MIS")
        kite.reject(s1, message = "", status = "CANCELLED")                 // Boss cancels it in Kite
        runBlocking { Strategies.stopForToday(stopRunning = false, compromised = false) }
        kite.requests.clear()
        pass()
        assertTrue("said as his", saidYours())
        assertTrue(today("orb").single().stopByYou)
        assertEquals("Stop for today still sold it", 1, marketSells().size)
    }

    // ---- item 3: a found lost stop taken out after part of it filled --------------------------------------------------------------------

    /**
     * A stop sent for 90 lost its answer; the trade sold 30 since (now 60) and the stop has filled 30 when it is found. It cannot be cut to
     * what is held (Zerodha refuses the change), so it is taken out - and the 30 it sold are booked closed at the stop (before: "rests
     * again", that fill never booked). The rest, with none, rests its own stop on the next look.
     */
    @Test fun aFoundLostStopTakenOutAfterAPartFilledBooksThatPartClosed() = withUpstox {
        state(listOf(position("orb", 60, 200.0, "E1", stop = 160.0)))
        kite.position(sym, 60, 200.0, product = "MIS")
        Broker.lostLookGapMsForTest = 0; Broker.lostSearchInlineForTest = true
        val label = arm("orb").arm.label
        val lostId = kite.restingInKite(sym, "SELL", 90, 152.0, type = "SL", trigger = 160.0, tag = "iraorb", product = "MIS")
        Broker.noteLost("orbstop:$label:$sym:200.0:90",
            Kite.Order(sym, Kite.Side.SELL, 90, 30, "MIS", "SL", 152.0, 0.05, "NFO", "iraorb", triggerPrice = 160.0), emptyList(), "test", liveOnly = true)
        kite.fillPart(lostId, 30, 158.0)                                    // it triggered and sold 30: Zerodha holds 30
        kite.modifyRefusals = 1                                            // it cannot be cut to what is held
        kite.requests.clear()
        pass()
        assertEquals("taken out", "CANCELLED", kite.order(lostId).status)
        val rows = today("orb")
        val closed = rows.single { !it.open }
        assertEquals("the part it sold booked closed", 30, closed.qty)
        assertEquals(158.0, closed.exit!!, 1e-9)
        assertEquals("stop", closed.why)
        val rest = rows.single { it.open }
        assertEquals(30, rest.qty)
        assertNull("the rest has none yet", rest.stopOrderId)
        assertTrue("no stop rested beside it in the same look", stopsPlaced().isEmpty())
        pass()
        assertEquals("the rest's own stop, for what is held", listOf("30"), stopsPlaced().map { it.form["quantity"] })
        assertEquals("every lot booked once", 60, today("orb").sumOf { it.qty })
    }

    // ---- item 4: a closed trade's lost stop still being cancelled across a restart ------------------------------------------------------

    @Test fun aClosedTradesLostStopIsStillCancelledAfterARestartMidSweep() = withUpstox {
        state(emptyList())
        Broker.lostLookGapMsForTest = 0; Broker.lostSearchInlineForTest = true
        val label = arm("orb").arm.label
        val lostId = kite.restingInKite(sym, "SELL", 30, 147.0, type = "SL", trigger = 150.0, tag = "iraorb", product = "MIS")
        Broker.noteLost("orbstop:$label:$sym:190.0:30",                   // a trade at 190 that has closed
            Kite.Order(sym, Kite.Side.SELL, 30, 30, "MIS", "SL", 147.0, 0.05, "NFO", "iraorb", triggerPrice = 150.0), emptyList(), "test", liveOnly = true)
        kite.down += "/orders/regular/$lostId"                            // its cancel fails this time
        pass()
        assertTrue("found and its cancel tried", kite.requests.any { it.method == "DELETE" && it.path.endsWith(lostId) })
        assertTrue("still resting", kite.order(lostId).working)
        assertTrue("kept in the book", OrbArms.awaitWrites() &&
            JSONObject(String(Vault.readFile(file)!!, Charsets.UTF_8)).optJSONObject("sweepCancels")?.has(lostId) == true)
        // The phone restarts: memory gone (the lost reply's key too), the book read back.
        OrbArms.forgetMemoryForTest()
        Broker.forgetLostForTest()
        kite.down.clear()
        pass()
        assertEquals("cancelled after the restart", "CANCELLED", kite.order(lostId).status)
        pass()
        assertFalse("confirmed out: no longer kept", JSONObject(String(Vault.readFile(file)!!, Charsets.UTF_8)).optJSONObject("sweepCancels")?.has(lostId) == true)
    }

    // ---- item 5: a malformed positions row is not "nothing held" ----------------------------------------------------------------------

    /**
     * An entry whose order id was lost, searched for: Zerodha's positions read left the contract's row out as malformed - that tells
     * nothing of what is held, so the entry stays unconfirmed (never called "never bought" on it). Once the row reads 0 on two agreeing
     * reads it is called never bought, as before.
     */
    @Test fun aMalformedRowIsNeverReadAsNothingHeldForALostEntry() {
        state(listOf(liquidity(30, 200.0, null, 170.0).put("entryOrderId", "").put("unconfirmed", true)))
        kite.extraNetRows = listOf(JSONObject().put("tradingsymbol", sym).put("exchange", "NFO").put("product", "MIS"))   // no quantity
        repeat(3) {
            pass()
            OrbArms.testNow = OrbArms.testNow!!.plusSeconds(11)
        }
        val p = arm("liquidity").open
        assertTrue("still kept, unconfirmed: $p", p != null && p.unconfirmed)
        assertFalse(AutomationSupport.alerts().any { "no buy of $sym reached Zerodha" in it })
        kite.extraNetRows = emptyList()
        kite.position(sym, 0, 0.0, product = "MIS")
        repeat(3) {
            pass()
            OrbArms.testNow = OrbArms.testNow!!.plusSeconds(11)
        }
        assertNull("a row read at 0 on two reads: never bought", arm("liquidity").open)
        assertTrue(AutomationSupport.alerts().any { "no buy of $sym reached Zerodha" in it })
    }

    // ---- item 6a: a stop put back is sized from what is held, lag-guarded --------------------------------------------------------------

    /**
     * Two trades on the contract, A's stop resting, B's missing; Boss sold 30 himself in Kite a moment ago and Zerodha's positions still
     * show 60. B's stop is put back only for what is really held less A's stop - nothing - never a second 30 that could sell into a short.
     */
    @Test fun aStopPutBackIsSizedFromWhatIsHeldLagGuarded() {
        val sA = stopOrder(30, 160.0, 152.0)
        state(listOf(position("orb", 30, 200.0, "E1", sA, 160.0), position("orb", 30, 201.0, "E2", null, 161.0)))
        kite.position(sym, 60, 200.0, product = "MIS")
        kite.positionsTrailReads = 1_000                                   // the positions trail the next fill
        kite.soldInKite(sym, 30, 205.0)                                    // Boss's own sale: COMPLETE in the order book
        kite.requests.clear()
        pass()
        assertTrue("no stop for lots already sold: ${stopsPlaced().map { it.form }}", stopsPlaced().isEmpty())
        assertEquals("A's stop untouched", "TRIGGER PENDING", kite.order(sA).status)
    }

    // ---- item 6b: the exits' labels never wait on the Strategy Module's lock -----------------------------------------------------------

    /**
     * The Strategy Module's lock is held (a strategy placing orders) from the moment the arm's exit sale goes: the exit goes on to wait for
     * its fill at once - its label kept in memory - and the label lands once the lock is free.
     */
    @Test fun anExitSalesLabelNeverWaitsOnTheStrategyModulesLock() {
        val s1 = stopOrder(30, 170.0, 161.5)
        state(listOf(liquidity(30, 200.0, s1, 170.0)))                     // its time stop is due
        kite.position(sym, 30, 200.0, product = "MIS")
        val release = CompletableDeferred<Unit>()
        val holder = CoroutineScope(Dispatchers.IO)
        kite.afterNext("/orders/regular") {
            holder.launch { Strategies.holdLockForTest(release) }
            val end = System.currentTimeMillis() + 5_000
            while (!Strategies.lockHeldForTest() && System.currentTimeMillis() < end) Thread.sleep(5)
        }
        kite.requests.clear()
        val look = Thread { pass() }.apply { start() }
        // (Its fill read: an order's own history other than its stop's, read after the sale went.)
        fun fillRead() = kite.requests.any { r -> r.method == "GET" && r.path.startsWith("/orders/") && r.path.count { it == '/' } == 2 && r.path != "/orders/$s1" }
        try {
            val end = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < end && !fillRead()) Thread.sleep(10)
            assertTrue("its fill read at once", fillRead())
            assertTrue("while the Strategy Module's lock is still held", Strategies.lockHeldForTest())
        } finally { release.complete(Unit) }
        look.join(30_000)
        assertFalse(look.isAlive)
        val id = kite.orders.values.last { it.side == "SELL" && it.type == "MARKET" }.id
        assertTrue("labelled once the lock is free", runBlocking { Strategies.owners() }["kite:$id"]?.endsWith("time_stop") == true)
    }
}
