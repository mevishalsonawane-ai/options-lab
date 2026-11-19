package com.optionslab.app.data

import com.optionslab.app.security.Vault
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode
import java.io.File
import java.time.LocalDateTime

/**
 * 19 Nov 2026 (audit batch 2), against the fake Kite (Live):
 *  - the arms' book: a read that fails for a moment is read again (never set aside, never an empty book); one that keeps failing, or
 *    fails authentication, is set aside - and the live trades are then taken back from Zerodha's positions and the arms' own orders, each
 *    with its stop, and Boss told; one position row (or part) that cannot be read never drops the rest;
 *  - L1: a stop of these arms left resting on a flat contract with no trade is cancelled once the contract is confirmed flat (never while
 *    it is held);
 *  - L2: a stray buy kept after a lost entry's take-back is no longer followed once its day has passed or the order book stops listing it.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class OrbArmsBookReadTest : RobolectricTest() {
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
        AutomationSupport.clearAlerts()
        kite.instruments += FakeKite.Ins(22_000_001, sym, "BANKNIFTY", expiry, 52_000.0, "CE", 30)
        kite.instruments += FakeKite.Ins(22_000_002, "BANKNIFTY26OCT52000PE", "BANKNIFTY", expiry, 52_000.0, "PE", 30)
        kite.quote("NFO:$sym", 200.0, 199.95, 200.05)
        runBlocking { Broker.instruments() }
        kite.requests.clear()
        val t = AutomationSupport.earlierToday()
        OrbArms.testNow = t
        now = t.toLocalDateTime().withNano(0)
    }

    @After fun down() {
        OrbArms.testRead = null
        AutomationSupport.realClocks()
        kite.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun contract(symbol: String, right: String) =
        JSONArray().put(symbol).put("BANKNIFTY").put(expiry.toString()).put(52_000.0).put(right).put(30).put("NSE_FO|TEST$right")

    private fun ago(minutes: Long) = maxOf(now.minusMinutes(minutes), now.toLocalDate().atStartOfDay())

    private fun position(qty: Int, entry: Double, entryId: String, stopId: String? = null, stop: Double? = null) =
        JSONObject().put("arm", "orb").put("symbol", paperCe).put("right", "CE").put("qty", qty).put("entry", entry)
            .put("entryTime", ago(30).toString()).put("signalBar", ago(35).toString())
            .put("entryOrderId", entryId).put("stopOrderId", stopId ?: "").apply { stop?.let { put("stopTrigger", it) } }
            .put("exitTime", "").put("why", "").put("charges", 0.0).put("live", true).put("kite", sym).put("unconfirmed", false)

    private fun state(armed: Boolean = true, pending: Boolean = false, positions: List<JSONObject> = emptyList(), armedJson: JSONObject? = null) {
        val on = JSONObject().put("orb", armed)
        val o = JSONObject()
            .put("armed", armedJson ?: on).put("auto", JSONObject().put("orb", true)).put("liveOk", JSONObject(on.toString()))
            .put("legs", JSONObject().put("day", now.toLocalDate().toString()).put("strike", 52_000).put("expiry", expiry.toString())
                .put("ce", contract(paperCe, "CE")).put("pe", contract(paperPe, "PE")))
            .put("positions", JSONArray(positions))
        if (pending) o.put("pending", JSONObject().put("orb", JSONObject().put("right", "CE").put("bar", ago(1).toString()).put("expires", now.plusMinutes(9).toString())))
        AutomationSupport.orbState(context, o)
    }

    private fun arm() = runBlocking { OrbArms.view() }.arms.single { it.arm.source == "orb" }
    private fun pass() = runBlocking { OrbArms.priceCheckOnly() }
    private fun restart() { OrbArms.forgetMemoryForTest(); runBlocking { ArmLive.refresh() } }
    private fun asides() = context.filesDir.listFiles { f -> f.name.startsWith("orb.vault.unreadable.") }.orEmpty().toList()
    private fun alerts() = AutomationSupport.alerts()

    /** [block] must throw the book's "could not be read now" ([java.io.IOException]). */
    private fun readFailsNow(block: () -> Unit) {
        try { block(); fail("the book was read") } catch (e: java.io.IOException) {
            assertTrue(e.message, e.message!!.contains("could not be read now"))
        }
    }

    // ---- item 2: the book's read ----------------------------------------------------------------------------------------------

    @Test fun aReadThatFailsForAMomentIsReadAgainNeverSetAsideNorEmptied() {
        state(positions = listOf(position(30, 200.0, "E1", stopId = "S1", stop = 160.0)))
        restart()
        var calls = 0
        OrbArms.testRead = { if (calls++ == 0) throw java.security.KeyStoreException("a Keystore hiccup") else Vault.readFileSteady(file) }
        readFailsNow { runBlocking { OrbArms.holding() } }
        assertTrue("read again: its open live trade is there", runBlocking { OrbArms.holding() })
        assertEquals("E1", arm().open!!.entryOrderId)
        assertTrue("never set aside", asides().isEmpty())
        assertTrue(file.exists())
        assertTrue(alerts().toString(), alerts().none { "set aside" in it })
    }

    @Test fun aReadThatKeepsFailingIsSetAsideOnlyAfterFiveInARowOverHalfAMinute() {
        state(positions = listOf(position(30, 200.0, "E1", stopId = "S1", stop = 160.0)))
        restart()
        OrbArms.testRead = { throw java.security.KeyStoreException("the Keystore is down") }
        // Many in a row within a second or two (the stream's looks): still read again, never set aside.
        repeat(OrbArms.READ_FAILS_BEFORE_ASIDE + 2) { readFailsNow { runBlocking { OrbArms.holding() } } }
        assertTrue("not yet", asides().isEmpty())
        OrbArms.testNow = OrbArms.testNow!!.plusSeconds(OrbArms.READ_FAIL_ASIDE_MS / 1000 + 1)
        assertFalse("still failing half a minute on: set aside, a new book", runBlocking { OrbArms.holding() })
        assertEquals(1, asides().size)
        assertTrue(alerts().toString(), alerts().any { "could not be read and was set aside" in it && "taken back from Zerodha" in it })
    }

    /** The paper feed's candles (a laddered trade's best price without a stream), served by a fake so no test reaches the internet. */
    private fun <T> withUpstox(body: () -> T): T {
        val u = com.optionslab.app.testing.FakeUpstox()
        try { u.price("NSE_FO|TESTCE", 1.0); return body() } finally { u.close() }
    }

    @Test fun aSetAsideBookTakesItsLiveTradeBackFromZerodhaWithItsOwnStop() = withUpstox {
        state(pending = true)
        assertEquals("Entered at Zerodha (live).", runBlocking { OrbArms.approve("orb", pinConfirmed = true) })
        val held = arm().open!!
        val stopId = held.stopOrderId!!
        val buyId = held.entryOrderId!!
        runBlocking { ArmLive.refresh() }
        pass()                                                              // this process's start-up sweep has run
        assertTrue(OrbArms.awaitWrites())
        // The book can no longer be read (it fails authentication): set aside at the next read.
        OrbArms.testRead = { throw Vault.Tampered() }
        AutomationSupport.reloadFromDisk(OrbArms)
        AutomationSupport.clearAlerts()
        kite.requests.clear()
        pass()
        OrbArms.testRead = null
        val p = arm().open
        assertNotNull("taken back, never forgotten", p)
        assertTrue(p!!.live)
        assertEquals(buyId, p.entryOrderId); assertEquals(30, p.qty); assertEquals(sym, p.kite)
        assertEquals("its own stop, already resting, taken - never a second", stopId, p.stopOrderId)
        assertEquals("exactly one stop at Zerodha", 1, kite.orders.values.count { it.side == "SELL" && it.working })
        assertTrue("no new buy", kite.placed.none { it.form["transaction_type"] == "BUY" })
        assertTrue(alerts().toString(), alerts().any { "could not be read and was set aside" in it })
        assertTrue(alerts().toString(), alerts().any { "found at Zerodha with no trade here" in it })
        assertEquals(1, asides().size)
    }

    @Test fun oneRowThatCannotBeReadNeverDropsTheOthers() {
        val bad = position(30, 210.0, "E2").put("entryTime", "not a time")
        state(positions = listOf(position(30, 200.0, "E1", stopId = "S1", stop = 160.0), bad))
        restart()
        val a = arm()
        assertEquals("the good row kept", "E1", a.open!!.entryOrderId)
        assertEquals(1, a.today.size)
        assertTrue("the switches kept: only a row was lost", a.armed)
        assertTrue(alerts().toString(), alerts().any { "position 2 ($paperCe, live)" in it && "taken back from Zerodha" in it })
        assertEquals("the file as it was, kept beside it", 1, asides().size)
        assertTrue(file.exists())
    }

    @Test fun aSwitchPartThatCannotBeReadTurnsEveryArmOffAndKeepsThePositions() {
        state(positions = listOf(position(30, 200.0, "E1", stopId = "S1", stop = 160.0)), armedJson = JSONObject().put("orb", "maybe"))
        restart()
        val a = arm()
        assertFalse("off until Boss switches it on", a.armed)
        assertEquals("E1", a.open!!.entryOrderId)
        assertTrue(alerts().toString(), alerts().any { "could not be read (armed)" in it && "every arm is off" in it })
    }

    // ---- paper-side ends never sell a live trade (coordinator, 19 Nov 2026) ----------------------------------------------------

    @Test fun aPaperRowClosedOrResetNeverSellsOrDropsTheLiveTrade() {
        val stop = kite.restingInKite(sym, "SELL", 30, 152.0, type = "SL", trigger = 160.0, tag = "iraorb")
        // The same arm's paper row on the same option, which the paper book does not hold (closed there by hand, or its contract unknown).
        val paper = position(30, 200.0, "P1").put("live", false).put("kite", "").put("entryOrderId", "P1")
        state(armed = false, positions = listOf(position(30, 200.0, "E1", stopId = stop, stop = 160.0), paper))
        kite.position(sym, 30, 200.0, product = "MIS")
        restart()
        pass()
        val live = { runBlocking { OrbArms.liveOpen() }.map { it.second } }
        assertEquals("the live trade is untouched by its paper side", listOf("E1"), live().map { it.entryOrderId })
        runBlocking { OrbArms.resetPaper() }                                // Boss resets the paper account
        pass()
        val p = live().single()
        assertTrue(p.open); assertEquals(stop, p.stopOrderId); assertEquals(30, p.qty)
        assertTrue("its own stop still resting at Zerodha", kite.order(stop).working)
        assertTrue("never a sale because of the paper side", kite.placed.none { it.form["transaction_type"] == "SELL" })
    }

    // ---- L1: an unclaimed stop on a flat contract ----------------------------------------------------------------------------

    @Test fun anUnclaimedStopOnAFlatContractIsCancelledOnceItIsConfirmedFlat() {
        state(armed = false)
        kite.position(sym, 0, 200.0, product = "MIS")                      // traded today, nothing held now
        val orphan = kite.restingInKite(sym, "SELL", 30, 152.0, type = "SL", trigger = 160.0, tag = "iraorb")
        restart()
        pass()
        assertEquals("followed", setOf(orphan), OrbArms.flatOrphansForTest())
        assertTrue("not on one read", kite.order(orphan).working)
        OrbArms.testNow = OrbArms.testNow!!.plusSeconds(60)
        pass()
        assertTrue("no sale on it today: five minutes of reads first", kite.order(orphan).working)
        OrbArms.testNow = OrbArms.testNow!!.plusMinutes(5)
        pass()
        assertEquals("cancelled as the app's own", "CANCELLED", kite.order(orphan).status)
        assertTrue(OrbArms.flatOrphansForTest().isEmpty())
        assertTrue(alerts().toString(), alerts().any { "resting on a flat contract" in it && orphan in it })
    }

    @Test fun aTradeBossClosedInKiteLeavesNoStrayStopOfTheseArmsBehind() {
        val own = kite.restingInKite(sym, "SELL", 30, 152.0, type = "SL", trigger = 160.0, tag = "iraorb")
        val stray = kite.restingInKite(sym, "SELL", 30, 150.0, type = "SL", trigger = 158.0, tag = "iraorb")   // a lost reply's second stop
        state(armed = false, positions = listOf(position(30, 200.0, "E1", stopId = own, stop = 160.0)))
        kite.position(sym, 30, 200.0, product = "MIS")
        restart()
        pass()
        assertTrue(arm().open != null)
        kite.soldInKite(sym, 30, 205.0)                                    // Boss's own target filled in Kite: flat now
        pass()
        assertNull("booked closed by Boss's sale", arm().open)
        assertEquals("its own stop taken out", "CANCELLED", kite.order(own).status)
        pass()
        assertEquals("the stray stop too, at once: the contract was confirmed flat by the same rules", "CANCELLED", kite.order(stray).status)
        assertTrue(alerts().toString(), alerts().any { "resting on a flat contract" in it && stray in it })
    }

    @Test fun anUnclaimedStopOnAHeldContractIsNeverCancelled() {
        state(armed = false)
        kite.position(sym, 30, 200.0, product = "MIS")                     // held (Boss's own lot, say)
        val orphan = kite.restingInKite(sym, "SELL", 30, 152.0, type = "SL", trigger = 160.0, tag = "iraorb")
        restart()
        pass()
        OrbArms.testNow = OrbArms.testNow!!.plusMinutes(10)
        pass()
        assertTrue("left resting: it may cover that lot", kite.order(orphan).working)
        assertTrue(alerts().toString(), alerts().none { "resting on a flat contract" in it })
    }

    @Test fun aStopOnAContractWhereATradeIsOpenIsLeftToThatTrade() {
        state(armed = false, positions = listOf(position(30, 200.0, "E1", stopId = null, stop = 160.0)))
        kite.position(sym, 30, 200.0, product = "MIS")
        val other = kite.restingInKite(sym, "SELL", 30, 152.0, type = "SL", trigger = 160.0, tag = "iraorb")
        restart()
        pass()
        assertNull("never followed while a trade of this book is open on it", OrbArms.flatOrphansForTest().firstOrNull { it == other })
    }

    // ---- L2: stray buys are not followed for ever ----------------------------------------------------------------------------

    @Test fun strayBuysFromAnEarlierDayOrLongGoneAreNoLongerFollowed() {
        state(armed = false)
        restart()
        pass()                                                              // the start-up sweep (nothing to take back)
        val today = now.toLocalDate()
        OrbArms.strayLeftAddForTest("OLD", sym, 0, today.minusDays(1))
        OrbArms.strayLeftAddForTest("GONE", sym, 0, today)
        pass()
        assertEquals("an earlier day's dropped at once", setOf("GONE"), OrbArms.strayLeftForTest())
        repeat(OrbArms.STRAY_MISSES - 1) { pass() }
        assertTrue("not listed for ${OrbArms.STRAY_MISSES} reads: dropped", OrbArms.strayLeftForTest().isEmpty())
        kite.requests.clear()
        pass()
        assertTrue("no order read on every look any more", kite.requests.none { it.method == "GET" && it.path == "/orders" })
    }
}
