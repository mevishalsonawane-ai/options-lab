package com.optionslab.app.data

import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.Kite
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
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 22 Nov 2026 (B6 review): the square-offs ([SquareOffExit]: the 15:05 expiry square-off and the flat-by) against an exit sale of
 * Liquidity 15+5 / the ORB family whose reply was lost ([OrbArms]). While that sale is unresolved the square-off takes no stop out on its
 * contract and sells only what that sale leaves - never the whole position beside it (a short if it filled too); once it is found and
 * booked, the square-off sells the rest. The arms' own exits wait while a square-off is selling the contract. Against the fake Kite, the
 * clocks fixed; the contract expires today.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class OrbSquareOffLostSaleTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var kite: FakeKite
    private val sym = "BANKNIFTY26OCT52000CE"
    private val paperCe = "BANKNIFTY-TEST-52000CE"
    private val paperPe = "BANKNIFTY-TEST-52000PE"
    private lateinit var today: LocalDate
    private lateinit var now: LocalDateTime
    private lateinit var flatOwner: suspend (String) -> String?
    private lateinit var flatReady: () -> Boolean

    @Before fun up() {
        kite = FakeKite()
        kite.login()
        AutomationSupport.liveSettings()
        AutomationSupport.security(compromised = false)
        com.optionslab.app.testing.Background.clearAlerts()            // (and the banner's 20 s repeat filter)
        AutomationSupport.resetExpiryTold()
        today = Market.today()
        kite.instruments += FakeKite.Ins(22_000_001, sym, "BANKNIFTY", today, 52_000.0, "CE", 30)
        kite.instruments += FakeKite.Ins(22_000_002, "BANKNIFTY26OCT52000PE", "BANKNIFTY", today, 52_000.0, "PE", 30)
        kite.quote("NFO:$sym", 200.0, 199.95, 200.05)
        runBlocking { Broker.instruments() }
        kite.requests.clear()
        val t = AutomationSupport.earlierToday()
        OrbArms.testNow = t
        // Liquidity's index exits read the index candles: a test feed with none (its time stop decides on the option's price alone).
        OrbArms.testIndexBars = { emptyList() }
        now = t.toLocalDateTime().withNano(0)
        flatOwner = FlatBySquareOff.liveOwner
        flatReady = FlatBySquareOff.liveBooksReady
    }

    @After fun down() {
        SquareOffExit.lostPendingProviders.remove("test")
        FlatBySquareOff.liveOwner = flatOwner
        FlatBySquareOff.liveBooksReady = flatReady
        SquareOffExit.resetForTest(gap = 0L)
        AutomationSupport.realClocks()
        kite.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun contract(symbol: String, right: String) =
        JSONArray().put(symbol).put("BANKNIFTY").put(today.toString()).put(52_000.0).put(right).put(30).put("NSE_FO|TEST$right")

    /** Minutes before [now], but never yesterday. */
    private fun ago(minutes: Long) = maxOf(now.minusMinutes(minutes), now.toLocalDate().atStartOfDay())

    private fun position(arm: String, qty: Int, entry: Double, entryId: String?, stopId: String? = null, stop: Double? = null) =
        JSONObject().put("arm", arm).put("symbol", paperCe).put("right", "CE").put("qty", qty).put("entry", entry)
            .put("entryTime", ago(30).toString()).put("signalBar", ago(35).toString())
            .put("entryOrderId", entryId ?: "").put("stopOrderId", stopId ?: "").apply { stop?.let { put("stopTrigger", it) } }
            .put("exitTime", "").put("why", "").put("charges", 0.0).put("live", true).put("kite", sym).put("unconfirmed", false)

    /** A live Liquidity 15+5 trade (the 5-minute BANKNIFTY book) that broke 52,050, one lot of 30. */
    private fun liquidity(qty: Int, entry: Double, stopId: String?, stop: Double?) =
        position("liquidity5", qty, entry, "E1", stopId, stop).put("level", 52_050.0).put("lot", 30)

    private fun state(positions: List<JSONObject>) {
        AutomationSupport.orbState(context, JSONObject()
            .put("armed", JSONObject().put("orb", true)).put("auto", JSONObject().put("orb", true)).put("liveOk", JSONObject().put("orb", true))
            .put("legs", JSONObject().put("day", now.toLocalDate().toString()).put("strike", 52_000).put("expiry", today.toString())
                .put("ce", contract(paperCe, "CE")).put("pe", contract(paperPe, "PE")))
            .put("positions", JSONArray(positions)))
    }

    private fun today(source: String) = runBlocking { OrbArms.view() }.arms.single { it.arm.source == source }.today
    private fun pass() = runBlocking { OrbArms.priceCheckOnly() }
    private fun stopOrder(qty: Int, trigger: Double, limit: Double) = runBlocking {
        Broker.placeOrder(Kite.Order(sym, Kite.Side.SELL, qty, 30, "MIS", "SL", limit, 0.05, "NFO", "iraorb", triggerPrice = trigger), exit = true)
    }
    private fun marketSells() = kite.placed.filter { it.form["transaction_type"] == "SELL" && it.form["order_type"] == "MARKET" }
    private fun squareOffSells(tag: String) = marketSells().filter { it.form["tag"] == tag }.map { it.form["quantity"] }
    private fun held() = kite.positions.getValue("NFO:$sym:MIS").first
    private fun at(h: Int, m: Int) { ExpirySquareOff.testNow = today.atTime(h, m).atZone(com.optionslab.engine.IST) }
    private fun expiryPass() = runBlocking { ExpirySquareOff.maybeRun(context, AppSettings.load()) }
    private fun flatPass(h: Int, m: Int) {
        FlatBySquareOff.testNow = today.atTime(h, m).atZone(com.optionslab.engine.IST)
        runBlocking { FlatBySquareOff.maybeRun(context, AppSettings.load()) }
    }
    private fun told() = com.optionslab.app.work.Alerts.queue.value.any { "exit sale on it is not settled yet" in it.text }

    /** Waits (wall clock, at most 10 s) until no background search of a lost exit on [sym] runs. */
    private fun searchDone() {
        val end = System.currentTimeMillis() + 10_000
        while (OrbArms.lostSearchingForTest(sym) && System.currentTimeMillis() < end) Thread.sleep(10)
        assertTrue("the background search ended", !OrbArms.lostSearchingForTest(sym))
    }

    /**
     * Book A (the 5-minute Liquidity book, its time stop due) and book B (the 15-minute book, entered 3 minutes ago) on the contract, one lot
     * each, Zerodha holding 60. A's exit sale reached Zerodha and filled, but its reply was lost and neither the order book nor the positions
     * show it yet. [bStop]: B's own stop rests at Zerodha (else Boss cancelled it in Kite: none rests). B's stop's id, if any.
     */
    private fun lostSaleOnABesideB(bStop: Boolean): String? {
        val s1 = stopOrder(30, 170.0, 161.5)
        val s2 = if (bStop) stopOrder(30, 170.0, 161.5) else null
        val b = position("liquidity15", 30, 200.0, "E2", s2, 170.0).put("level", 52_050.0).put("lot", 30)
            .put("entryTime", ago(3).toString()).put("signalBar", ago(15).toString()).apply { if (!bStop) put("stopByYou", true) }
        state(listOf(liquidity(30, 200.0, s1, 170.0), b))
        kite.position(sym, 60, 200.0, product = "MIS")
        kite.positionsTrailReads = 1_000
        kite.trailReads = 1_000
        kite.nextPlace(reply = FakeKite.Reply.DROP_AFTER_ACCEPT)
        pass()
        assertTrue("A's sale lost and held", OrbArms.lostExitForTest(sym))
        assertEquals("A's stop came out before its sale", "CANCELLED", kite.order(s1).status)
        assertEquals("the square-offs see A's lost sale", 30, OrbArms.unsettledSales(sym))
        return s2
    }

    /** A's lost sale shows in the order book and the positions; its own looks find it and book it as A's exit. */
    private fun lostSaleFoundAndBooked() {
        kite.showInBook()
        kite.positionsCatchUp()
        OrbArms.testNow = OrbArms.testNow!!.plusSeconds(OrbArms.LOST_EXIT_HOLD_MS / 1000 + 1)
        pass(); searchDone(); pass()
        assertFalse(OrbArms.lostExitForTest(sym))
        assertEquals(0, OrbArms.unsettledSales(sym))
        assertEquals("booked as A's exit", "time_stop", today("liquidity").single { it.arm == "liquidity5" }.why)
    }

    // ---- the expiry square-off at 15:05 --------------------------------------------------------------------------------------------

    /**
     * The finding: A's exit sale lost its reply moments before 15:05. The expiry square-off took B's stop out and sold all 60 Zerodha's
     * positions still showed - with A's sale filled too, the account went short 30. Now it leaves B's stop resting and sends nothing while
     * A's sale is unresolved; once it is found and booked, it takes B's stop out and sells the 30 left - flat, never short.
     */
    @Test fun theExpirySquareOffTakesNoStopOutAndSellsNothingBesideALostLiquiditySale() {
        val s2 = lostSaleOnABesideB(bStop = true)!!
        at(15, 5); expiryPass()
        assertTrue("nothing sold beside the lost sale: ${squareOffSells("iraalgoexpiry")}", squareOffSells("iraalgoexpiry").isEmpty())
        assertEquals("B's stop still rests", "TRIGGER PENDING", kite.order(s2).status)
        assertTrue("Boss told why", told())
        at(15, 6); expiryPass()
        assertTrue("still nothing while it is unresolved", squareOffSells("iraalgoexpiry").isEmpty())
        lostSaleFoundAndBooked()
        at(15, 7); expiryPass()
        assertEquals("B's stop out first", "CANCELLED", kite.order(s2).status)
        assertEquals("then the 30 left sold", listOf("30"), squareOffSells("iraalgoexpiry"))
        assertEquals("flat, never short", 0, held())
    }

    /** With nothing resting on the contract, the expiry square-off sells only what A's lost sale leaves (it counts as already going). */
    @Test fun withNothingRestingTheExpirySquareOffSellsOnlyWhatALostSaleLeaves() {
        assertNull(lostSaleOnABesideB(bStop = false))
        at(15, 5); expiryPass()
        assertEquals("B's lot only", listOf("30"), squareOffSells("iraalgoexpiry"))
        kite.positionsCatchUp()
        assertEquals("flat, never short - A's lost sale had filled too", 0, held())
    }

    // ---- the flat-by ---------------------------------------------------------------------------------------------------------------

    /** The flat-by (15:25 for an index option) keeps the same rule: no stop out, nothing sold beside the lost sale; the rest after it. */
    @Test fun theFlatByTakesNoStopOutAndSellsNothingBesideALostSale() {
        val s2 = lostSaleOnABesideB(bStop = true)!!
        FlatBySquareOff.liveOwner = { if (it == sym) "Liquidity 15+5" else null }
        FlatBySquareOff.liveBooksReady = { true }
        flatPass(15, 25)
        assertTrue("nothing sold beside the lost sale", squareOffSells("iraalgoflat").isEmpty())
        assertEquals("B's stop still rests", "TRIGGER PENDING", kite.order(s2).status)
        lostSaleFoundAndBooked()
        flatPass(15, 26)
        assertEquals("B's stop out first", "CANCELLED", kite.order(s2).status)
        assertEquals("then the 30 left sold", listOf("30"), squareOffSells("iraalgoflat"))
        assertEquals("flat, never short", 0, held())
    }

    // ---- the providers ([SquareOffExit.lostPendingProviders]) ---------------------------------------------------------------------

    @Test fun theArmsBookIsAProviderAndItsLostSalesAreKnownOnlyOnceItIsRead() {
        assertTrue(SquareOffExit.lostPendingProviders.containsKey("orb"))
        state(emptyList())                                                 // (an erase: nothing read in this process yet)
        assertFalse("not read yet: the square-offs wait for it", OrbArms.bookReadHint)
        runBlocking { OrbArms.view() }
        assertTrue(OrbArms.bookReadHint)
        assertEquals(0, OrbArms.unsettledSales(sym))
        assertEquals(0, SquareOffExit.lostPending(sym, "MIS"))
    }

    /**
     * The arm's own exit sale on its way (sent, not yet booked) counts too: a square-off that read the books just before it went (with the
     * exit coordinator in shadow nothing else tells it) never sells beside it.
     */
    @Test fun anArmsExitSaleOnItsWayCountsUntilItIsBooked() {
        val s1 = stopOrder(30, 170.0, 161.5)
        state(listOf(liquidity(30, 200.0, s1, 170.0)))                     // its time stop is due
        kite.position(sym, 30, 200.0, product = "MIS")
        val seen = java.util.concurrent.atomic.AtomicInteger(-1)
        kite.afterNext("/orders/regular") { seen.set(SquareOffExit.lostPending(sym, "MIS") ?: -2) }
        pass()
        assertEquals("while its sale was on its way", 30, seen.get())
        assertEquals("time_stop", today("liquidity").single().why)
        assertEquals("booked: none", 0, OrbArms.unsettledSales(sym))
    }

    @Test fun providersAddUpAndOneThatCannotTellMakesTheSizeUnknown() {
        SquareOffExit.lostPendingProviders["test"] = { s, _ -> if (s == sym) 30 else 0 }
        assertEquals(30, SquareOffExit.lostPending(sym, "MIS"))
        assertEquals(0, SquareOffExit.lostPending("NIFTY26OCT24500CE", "MIS"))
        SquareOffExit.lostPendingProviders["test"] = { _, _ -> -1 }
        assertNull("a negative answer: unknown", SquareOffExit.lostPending(sym, "MIS"))
        SquareOffExit.lostPendingProviders["test"] = { _, _ -> error("its book is busy") }
        assertNull("a throw: unknown", SquareOffExit.lostPending(sym, "MIS"))
        // The arms' own book answers for its intraday (MIS) row only.
        SquareOffExit.lostPendingProviders.remove("test")
        lostSaleOnABesideB(bStop = true)
        assertEquals(30, SquareOffExit.lostPending(sym, "MIS"))
        assertEquals("never another product's row", 0, SquareOffExit.lostPending(sym, "NRML"))
    }

    /** A lost sale of unknown size: nothing is sold on the contract, even with nothing resting there; once known as none, it is sold. */
    @Test fun aLostSaleOfUnknownSizeHoldsTheSquareOffBack() {
        state(emptyList())
        kite.position(sym, 30, 200.0, product = "MIS")
        SquareOffExit.lostPendingProviders["test"] = { s, _ -> if (s == sym) -1 else 0 }
        at(15, 5); expiryPass()
        assertTrue(squareOffSells("iraalgoexpiry").isEmpty())
        SquareOffExit.lostPendingProviders["test"] = { _, _ -> 0 }
        at(15, 6); expiryPass()
        assertEquals(listOf("30"), squareOffSells("iraalgoexpiry"))
    }

    /**
     * A lost sale lowers a SALE only: a short position is bought back in full (a lost sale on it cannot cover a buy), but a stop resting
     * on it is still never taken out while that sale is unresolved.
     */
    @Test fun aShortIsBoughtBackInFullButItsStopStaysWhileALostSaleIsUnresolved() {
        state(emptyList())
        kite.position(sym, -30, 200.0, product = "MIS")
        SquareOffExit.lostPendingProviders["test"] = { s, _ -> if (s == sym) 30 else 0 }
        val stop = kite.restsInKite(sym, "BUY", 30, 260.0, type = "SL")
        at(15, 5); expiryPass()
        assertTrue("nothing bought beside a stop it would have to take out", kite.placed.none { it.form["tag"] == "iraalgoexpiry" })
        assertEquals("TRIGGER PENDING", kite.order(stop).status)
        kite.orders.remove(stop)
        at(15, 6); expiryPass()
        assertEquals(listOf("30"), kite.placed.filter { it.form["tag"] == "iraalgoexpiry" && it.form["transaction_type"] == "BUY" }.map { it.form["quantity"] })
    }

    /** Boss's own NRML lot on the contract is its own row: an arm's lost MIS sale never holds it back nor lowers its sale. */
    @Test fun anotherProductsRowIsNeverHeldBackByAnArmsLostSale() {
        kite.position(sym, 90, 210.0, product = "NRML")                    // (before the positions start trailing A's sale)
        lostSaleOnABesideB(bStop = true)
        at(15, 5); expiryPass()
        assertEquals("Boss's NRML lots sold in full", listOf("90"), kite.placed.filter { it.form["tag"] == "iraalgoexpiry" && it.form["product"] == "NRML" }
            .map { it.form["quantity"] })
        assertTrue("nothing on the MIS row", kite.placed.none { it.form["tag"] == "iraalgoexpiry" && it.form["product"] == "MIS" })
    }

    /**
     * An arm's sale that comes up after the square-off's first look - while it takes the stops out - and before its own sale goes: the
     * square-off starts its gap, looks once more and sends nothing (the arm, which counts its sale before it looks at the gap, sells);
     * the gap ends again, and the next pass sells what is left.
     */
    @Test fun aSaleThatComesUpJustBeforeTheSquareOffsSaleGoesIsNeverDoubled() {
        SquareOffExit.resetForTest()                                       // the gap on, as it ships
        state(emptyList())
        kite.position(sym, 30, 200.0, product = "MIS")
        val looks = java.util.concurrent.atomic.AtomicInteger()
        SquareOffExit.lostPendingProviders["test"] = { s, _ -> if (s == sym && looks.incrementAndGet() >= 2) 30 else 0 }
        at(15, 5); expiryPass()
        assertTrue("nothing sold beside it", squareOffSells("iraalgoexpiry").isEmpty())
        assertFalse("the gap ended again: nothing of the square-off's went", SquareOffExit.inGap("NFO", sym, "MIS"))
        SquareOffExit.lostPendingProviders["test"] = { _, _ -> 0 }
        at(15, 6); expiryPass()
        assertEquals(listOf("30"), squareOffSells("iraalgoexpiry"))
        assertTrue("its gap on once its sale went", SquareOffExit.inGap("NFO", sym, "MIS"))
    }

    /**
     * One arm sale ending and another starting between the square-off's two looks leaves the total the same: any sale started in between
     * holds the square-off's sale back all the same; the next pass sells what is left.
     */
    @Test fun aSaleStartedBetweenTheLooksHoldsTheSaleBackEvenWithTheSameTotal() {
        SquareOffExit.resetForTest()
        state(emptyList())
        kite.position(sym, 60, 200.0, product = "MIS")
        val looks = java.util.concurrent.atomic.AtomicInteger()
        SquareOffExit.lostPendingProviders["test"] = { s, _ ->
            if (s == sym && looks.incrementAndGet() == 1) SquareOffExit.saleStarting(sym)     // another sale starts after the first look
            if (s == sym) 30 else 0
        }
        at(15, 5); expiryPass()
        assertTrue("nothing sold beside it", squareOffSells("iraalgoexpiry").isEmpty())
        assertFalse(SquareOffExit.inGap("NFO", sym, "MIS"))
        at(15, 6); expiryPass()
        assertEquals("what the unsettled 30 leaves", listOf("30"), squareOffSells("iraalgoexpiry"))
    }

    /** A book that could not be read (set aside): a lost sale it held is not known, so the square-offs wait that sale's window. */
    @Test fun anUnreadableBookHoldsTheSquareOffsBackForALostSalesWindow() {
        var wall = 2_000_000_000L
        OrbArms.testWallMs = { wall }
        state(emptyList())
        OrbArms.testRead = { throw com.optionslab.app.security.Vault.Tampered() }
        try { runBlocking { OrbArms.view() } } finally { OrbArms.testRead = null }
        assertFalse("not known yet", OrbArms.bookReadHint)
        wall += OrbArms.LOST_LET_GO_MS
        assertTrue("past the window", OrbArms.bookReadHint)
    }

    // ---- the arms' own exits beside a square-off -----------------------------------------------------------------------------------

    /**
     * A square-off's sale went on the contract a moment ago (not in the order book yet): the arm's own exit waits, its stop resting - with
     * the exit coordinator in shadow nothing else kept the two senders apart; past the square-off's gap it goes as usual.
     */
    @Test fun anArmsExitWaitsWhileASquareOffIsSellingTheContract() {
        SquareOffExit.resetForTest()                                       // the square-offs' gap on, as it ships
        var clock = 1_000_000L
        SquareOffExit.clock = { clock }
        val s1 = stopOrder(30, 170.0, 161.5)
        state(listOf(liquidity(30, 200.0, s1, 170.0)))                     // its time stop is due
        kite.position(sym, 30, 200.0, product = "MIS")
        SquareOffExit.notePlaced("NFO", sym, "MIS")
        pass()
        assertEquals("its stop rests", "TRIGGER PENDING", kite.order(s1).status)
        assertTrue("nothing sold beside the square-off's sale", marketSells().isEmpty())
        clock += SquareOffExit.GAP_MS
        pass()
        assertEquals("its stop out first", "CANCELLED", kite.order(s1).status)
        assertEquals(listOf("30"), marketSells().map { it.form["quantity"] })
        assertEquals("time_stop", today("liquidity").single().why)
    }

    /**
     * The square-off's sale goes while the arm's exit is taking its stop out: checked again just before the arm's own sale - nothing sold
     * beside it, no stop put back beside the square-off's sale (said; the app watches the level).
     */
    @Test fun aSquareOffSaleSentWhileTheArmTakesItsStopOutIsNeverDoubled() {
        SquareOffExit.resetForTest()
        val s1 = stopOrder(30, 170.0, 161.5)
        state(listOf(liquidity(30, 200.0, s1, 170.0)))
        kite.position(sym, 30, 200.0, product = "MIS")
        kite.afterNext("/orders/regular/$s1") { SquareOffExit.notePlaced("NFO", sym, "MIS") }
        kite.requests.clear()
        pass()
        assertEquals("its stop came out", "CANCELLED", kite.order(s1).status)
        assertTrue("nothing sold beside the square-off's sale", marketSells().isEmpty())
        assertTrue("no stop put back beside it", kite.placed.none { it.form["order_type"] == "SL" })
        val row = today("liquidity").single()
        assertTrue(row.open); assertNull(row.stopOrderId)
        assertTrue(AutomationSupport.alerts().joinToString(), AutomationSupport.alerts().any { "a square-off is selling it" in it })
    }
}
