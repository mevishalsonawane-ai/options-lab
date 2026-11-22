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
        ExpirySquareOff.testAfterReads = null
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
    private fun told() = com.optionslab.app.work.Alerts.queue.value.any { "exit sale on it has not settled yet" in it.text }

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
     * An arm's sale that comes up after the square-off's first look and before anything of the square-off's goes: the square-off starts
     * its gap and looks once more BEFORE it takes anything out (second round of the review: it took the stops out first, and nothing put
     * them back at the expiry) - so the stop resting on it stays, and nothing is sent; the gap ends again, and the next pass takes the stop
     * out and sells what is left.
     */
    @Test fun aSaleThatComesUpJustBeforeTheSquareOffsSaleGoesIsNeverDoubled() {
        SquareOffExit.resetForTest()                                       // the gap on, as it ships
        state(emptyList())
        kite.position(sym, 30, 200.0, product = "MIS")
        val stop = kite.restsInKite(sym, "SELL", 30, 170.0, type = "SL")
        val looks = java.util.concurrent.atomic.AtomicInteger()
        SquareOffExit.lostPendingProviders["test"] = { s, _ -> if (s == sym && looks.incrementAndGet() >= 2) 30 else 0 }
        at(15, 5); expiryPass()
        assertTrue("nothing sold beside it", squareOffSells("iraalgoexpiry").isEmpty())
        assertEquals("nothing taken out for a sale that did not go", "TRIGGER PENDING", kite.order(stop).status)
        assertFalse("the gap ended again: nothing of the square-off's went", SquareOffExit.inGap("NFO", sym, "MIS"))
        SquareOffExit.lostPendingProviders["test"] = { _, _ -> 0 }
        at(15, 6); expiryPass()
        assertEquals("the stop out with the sale", "CANCELLED", kite.order(stop).status)
        assertEquals(listOf("30"), squareOffSells("iraalgoexpiry"))
        assertTrue("its gap on once its sale went", SquareOffExit.inGap("NFO", sym, "MIS"))
    }

    /**
     * A backstop: an arm's unsettled sale that comes up while the square-off's stops are coming out (a book selling only under the row's
     * claim cannot start one then) - what goes is cut to what is held less that sale, never sold beside it in full.
     */
    @Test fun aSaleThatComesUpWhileTheStopsComeOutCutsTheSquareOffsSale() {
        state(emptyList())
        kite.position(sym, 60, 200.0, product = "MIS")
        val stop = kite.restsInKite(sym, "SELL", 60, 170.0, type = "SL")
        val looks = java.util.concurrent.atomic.AtomicInteger()
        SquareOffExit.lostPendingProviders["test"] = { s, _ -> if (s == sym && looks.incrementAndGet() >= 3) 30 else 0 }
        at(15, 5); expiryPass()
        assertEquals("its stop out", "CANCELLED", kite.order(stop).status)
        assertEquals("only what that sale leaves", listOf("30"), squareOffSells("iraalgoexpiry"))
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
     * A square-off comes to the row while the arm's exit is taking its stop out (second round of the review: each looked at the other, and
     * both could back off with the stop already out - or both sell): the arm is held right there until the square-off has been refused
     * the row ([SquareOffExit.claimExit]: it takes nothing out and sends nothing); then the arm sells. Exactly one sale - the arm's - and
     * no stop left out with no sale; the square-off's next pass finds nothing to sell.
     */
    @Test fun aSquareOffSaleSentWhileTheArmTakesItsStopOutIsNeverDoubled() {
        SquareOffExit.resetForTest()                                       // the gap on, as it ships
        val s1 = stopOrder(30, 170.0, 161.5)
        state(listOf(liquidity(30, 200.0, s1, 170.0)))                     // its time stop is due
        kite.position(sym, 30, 200.0, product = "MIS")
        runBlocking { OrbArms.view() }                                     // (the book read: the square-off may sell its lots)
        val row = Broker.Position(sym, "MIS", 30, 200.0, 200.0, 0.0)
        val refused = java.util.concurrent.atomic.AtomicReference<Boolean?>()
        val claimedBy = java.util.concurrent.atomic.AtomicReference<String?>()
        kite.afterNext("/orders/regular/$s1") {
            // The arm is inside its exit, its stop's cancel just answered: it waits here until the square-off has tried the row.
            claimedBy.set(SquareOffExit.exitClaimedBy("NFO", sym, "MIS"))
            refused.set(!runBlocking {
                SquareOffExit.send(row, 30, 0.05, emptyList(), emptyList(), Broker.Positions(listOf(row), emptyList()), AppSettings.load().limits(),
                    "expiry square-off", "iraalgoexpiry", Origins.EXPIRY, "expires today", "expiry") { _, _ -> }
            })
        }
        kite.requests.clear()
        pass()
        assertEquals("the arm held the row as its stop came out", OrbArms.CLAIM_OWNER, claimedBy.get())
        assertEquals("the square-off was refused the row", true, refused.get())
        assertEquals("and said so (counted as a pass that left it unsold)", 1, SquareOffExit.skipsForTest("NFO", sym, "MIS"))
        assertEquals("its stop came out", "CANCELLED", kite.order(s1).status)
        assertEquals("exactly one sale: the arm's", listOf("iraorb" to "30"), marketSells().map { it.form["tag"] to it.form["quantity"] })
        assertNull("the row free again", SquareOffExit.exitClaimedBy("NFO", sym, "MIS"))
        at(15, 5); expiryPass()
        assertEquals("nothing more on the square-off's next pass", 1, marketSells().size)
        assertEquals("flat, never short", 0, held())
        val trade = today("liquidity").single()
        assertFalse("sold and booked: no stop left out with no sale", trade.open)
        assertEquals("time_stop", trade.why)
    }

    /**
     * The same with a whole expiry pass running on another thread beside the arm's exit (its reads, its owner checks, its own loop): it
     * is refused the row or finds the arm's sale in its fresh read - exactly one sale, the arm's, and flat.
     */
    @Test fun aSquareOffPassRunningBesideTheArmsExitSellsNothingMore() {
        SquareOffExit.resetForTest()
        val s1 = stopOrder(30, 170.0, 161.5)
        state(listOf(liquidity(30, 200.0, s1, 170.0)))
        kite.position(sym, 30, 200.0, product = "MIS")
        runBlocking { OrbArms.view() }
        at(15, 5)
        val squareOff = java.util.concurrent.atomic.AtomicReference<Thread?>()
        kite.afterNext("/orders/regular/$s1") { squareOff.set(Thread { expiryPass() }.apply { start() }) }
        pass()
        val t = squareOff.get()!!
        t.join(30_000)
        assertFalse("the square-off's pass ended", t.isAlive)
        assertEquals("exactly one sale: the arm's", listOf("iraorb" to "30"), marketSells().map { it.form["tag"] to it.form["quantity"] })
        assertEquals("flat, never short", 0, held())
        assertFalse(today("liquidity").single().open)
    }

    // ---- one seller at a time on a row ([SquareOffExit.claimExit]) ---------------------------------------------------------------------

    @Test fun anExitClaimIsOneOwnerAtATimeAndCountsEachOfItsOwnersHolds() {
        val a = SquareOffExit.claimExit("NFO", sym, "MIS", "orb")!!
        assertNull("another seller waits", SquareOffExit.claimExit("NFO", sym, "MIS", "iraalgoexpiry"))
        SquareOffExit.claimExit("NFO", sym, "NRML", "iraalgoexpiry")!!.release()          // (another row is free)
        val inner = SquareOffExit.claimExit("NFO", sym, "MIS", "orb")!!
        inner.release()
        assertEquals("a nested release keeps the outer claim", "orb", SquareOffExit.exitClaimedBy("NFO", sym, "MIS"))
        a.release(); a.release()
        assertNull("released (a second release changes nothing)", SquareOffExit.exitClaimedBy("NFO", sym, "MIS"))
        val b = SquareOffExit.claimExit("NFO", sym, "MIS", "iraalgoexpiry")!!
        a.release()
        assertEquals("a spent claim never frees another's", "iraalgoexpiry", SquareOffExit.exitClaimedBy("NFO", sym, "MIS"))
        // Two runs of one owner at once: the row stays theirs until both released.
        val b2 = SquareOffExit.claimExit("NFO", sym, "MIS", "iraalgoexpiry")!!
        b.release()
        assertEquals("iraalgoexpiry", SquareOffExit.exitClaimedBy("NFO", sym, "MIS"))
        assertNull("an arm still waits", SquareOffExit.claimExit("NFO", sym, "MIS", "orb"))
        b2.release()
        assertNull(SquareOffExit.exitClaimedBy("NFO", sym, "MIS"))
    }

    /** While a square-off holds the row, the arm's own exit waits with its stop resting; once it is free, the arm sells. */
    @Test fun whileASquareOffHoldsTheRowTheArmsExitWaitsWithItsStopResting() {
        val s1 = stopOrder(30, 170.0, 161.5)
        state(listOf(liquidity(30, 200.0, s1, 170.0)))                     // its time stop is due
        kite.position(sym, 30, 200.0, product = "MIS")
        val claim = SquareOffExit.claimExit("NFO", sym, "MIS", "iraalgoexpiry")!!
        try {
            pass()
            assertEquals("its stop rests", "TRIGGER PENDING", kite.order(s1).status)
            assertTrue("nothing sold", marketSells().isEmpty())
            assertTrue(today("liquidity").single().open)
        } finally { claim.release() }
        pass()
        assertEquals("CANCELLED", kite.order(s1).status)
        assertEquals(listOf("30"), marketSells().map { it.form["quantity"] })
        assertEquals("time_stop", today("liquidity").single().why)
    }

    /** While an arm holds the row, a square-off takes nothing out and sends nothing; its next pass sells. */
    @Test fun whileAnArmHoldsTheRowTheSquareOffTakesNothingOutAndSendsNothing() {
        state(emptyList())
        runBlocking { OrbArms.view() }
        kite.position(sym, 30, 200.0, product = "MIS")
        val stop = kite.restsInKite(sym, "SELL", 30, 170.0, type = "SL", tag = "iraorb")
        val claim = SquareOffExit.claimExit("NFO", sym, "MIS", OrbArms.CLAIM_OWNER)!!
        try {
            at(15, 5); expiryPass()
            assertEquals("its stop rests", "TRIGGER PENDING", kite.order(stop).status)
            assertTrue(squareOffSells("iraalgoexpiry").isEmpty())
        } finally { claim.release() }
        at(15, 6); expiryPass()
        assertEquals("CANCELLED", kite.order(stop).status)
        assertEquals(listOf("30"), squareOffSells("iraalgoexpiry"))
    }

    // ---- an arm's sale the square-off's reads cannot show ------------------------------------------------------------------------------

    /**
     * The finding (second round): an arm's exit sale that started after the expiry square-off read Zerodha's books, and was booked before
     * the pass reached its contract, showed in neither read - and the square-off sold the position a second time (short). The square-off
     * now takes the arms' sales started so far BEFORE it reads, and leaves a contract with one started since to the next pass: exactly one
     * sale, flat.
     */
    @Test fun anArmsSaleStartedAndBookedAfterTheSquareOffsReadsIsNeverSoldAgain() {
        val s1 = stopOrder(30, 170.0, 161.5)
        state(listOf(liquidity(30, 200.0, s1, 170.0)))                     // its time stop is due
        kite.position(sym, 30, 200.0, product = "MIS")
        runBlocking { OrbArms.view() }                                     // (the book read: the square-off may sell its lots)
        ExpirySquareOff.testAfterReads = { OrbArms.priceCheckOnly() }      // the arm's whole exit, after the square-off's reads
        at(15, 5); expiryPass()
        ExpirySquareOff.testAfterReads = null
        assertEquals("the arm sold its lot", listOf("30"), squareOffSells("iraorb"))
        assertEquals("time_stop", today("liquidity").single().why)
        assertEquals("booked: none on its way", 0, OrbArms.unsettledSales(sym))
        assertTrue("the square-off sold nothing on its older reads", squareOffSells("iraalgoexpiry").isEmpty())
        assertEquals("flat, never short", 0, held())
        at(15, 6); expiryPass()
        assertTrue("nor on its next pass", squareOffSells("iraalgoexpiry").isEmpty())
        assertEquals("exactly one sale", 1, marketSells().size)
    }

    /**
     * An arm's exit sale that went and was booked a moment before the square-off read the books - the order book's list not showing it
     * yet and the positions trailing its fill - still counts for the square-offs' gap after it was booked: nothing sold beside it; past
     * the gap, with the books caught up, nothing is left to sell.
     */
    @Test fun anArmsSaleBookedAMomentAgoStillCountsWhileTheBooksTrailIt() {
        SquareOffExit.resetForTest()                                       // the gap on, as it ships
        var clock = 1_000_000L
        SquareOffExit.clock = { clock }
        val s1 = stopOrder(30, 170.0, 161.5)
        state(listOf(liquidity(30, 200.0, s1, 170.0)))                     // its time stop is due
        kite.position(sym, 30, 200.0, product = "MIS")
        kite.positionsTrailReads = 1_000                                   // the positions trail its fill
        kite.trailReads = 1_000                                            // and the order book's list its sale
        pass()
        assertEquals("time_stop", today("liquidity").single().why)
        assertEquals("booked a moment ago: still counted", 30, OrbArms.unsettledSales(sym))
        at(15, 5); expiryPass()
        assertTrue("nothing sold beside it: ${squareOffSells("iraalgoexpiry")}", squareOffSells("iraalgoexpiry").isEmpty())
        clock += SquareOffExit.GAP_MS
        assertEquals("past the gap: no longer counted", 0, OrbArms.unsettledSales(sym))
        kite.showInBook(); kite.positionsCatchUp()
        at(15, 6); expiryPass()
        assertTrue(squareOffSells("iraalgoexpiry").isEmpty())
        assertEquals("flat, never short", 0, held())
        assertEquals("exactly one sale", 1, marketSells().size)
    }

    /** The notice is said once for each new unsettled sale an arm started on the row - a later lost sale there is told too. */
    @Test fun eachNewUnsettledSaleOnTheRowIsToldAgain() {
        state(emptyList())
        runBlocking { OrbArms.view() }
        kite.position(sym, 30, 200.0, product = "MIS")
        kite.restsInKite(sym, "SELL", 30, 170.0, type = "SL")
        SquareOffExit.lostPendingProviders["test"] = { s, _ -> if (s == sym) 30 else 0 }
        at(15, 5); expiryPass()
        assertTrue(told())
        com.optionslab.app.testing.Background.clearAlerts()
        at(15, 6); expiryPass()
        assertFalse("the same sale: not said again", told())
        SquareOffExit.saleStarting(sym)                                    // another of the arm's sales on it
        at(15, 7); expiryPass()
        assertTrue("a new one: said", told())
        assertTrue(squareOffSells("iraalgoexpiry").isEmpty())
    }

    // ---- third round: what is sold is worked out from a fresh read under the claim ---------------------------------------------------

    /**
     * The finding (third round): an arm's sale started before the square-off's snapshot, missed by its reads (the order book's list and the
     * positions trailing it), and booked more than the gap before the pass reached the row - the claim free, nothing started since, the
     * booked cushion gone - was sold again. Now the square-off works its sale out from a fresh read of the orders under the claim, through
     * the lag guard: that sale shows as a fill, so nothing is sold again.
     */
    @Test fun anArmsSaleMissedByTheReadsAndBookedLongBeforeTheRowIsReachedIsNeverSoldAgain() {
        SquareOffExit.resetForTest()                                       // the gap on, as it ships
        var clock = 1_000_000L
        SquareOffExit.clock = { clock }
        val s1 = stopOrder(30, 170.0, 161.5)
        state(listOf(liquidity(30, 200.0, s1, 170.0)))                     // its time stop is due
        kite.position(sym, 30, 200.0, product = "MIS")
        kite.trailReads = 1_000                                            // the order book's list trails the arm's sale
        kite.positionsTrailReads = 1_000                                   // and the positions its fill
        pass()                                                             // the arm sells and books - before the square-off's snapshot
        assertEquals("time_stop", today("liquidity").single().why)
        ExpirySquareOff.testAfterReads = {                                 // after the pass's reads, which miss it:
            clock += SquareOffExit.GAP_MS + 2_000                          // the pass spends more than the gap on earlier rows
            kite.showInBook()                                              // the order book shows the sale by now; the positions still trail
        }
        at(15, 5); expiryPass()
        assertTrue("nothing sold again: ${squareOffSells("iraalgoexpiry")}", squareOffSells("iraalgoexpiry").isEmpty())
        assertEquals("exactly one sale", 1, marketSells().size)
        assertEquals("flat, never short", 0, held())
    }

    /** ...and while even the fresh read misses that sale (the book still trailing an order sent a moment ago): nothing this pass, then flat. */
    @Test fun whileEvenTheFreshReadMissesTheArmsSaleNothingIsSold() {
        SquareOffExit.resetForTest()
        var clock = 1_000_000L
        SquareOffExit.clock = { clock }
        val s1 = stopOrder(30, 170.0, 161.5)
        state(listOf(liquidity(30, 200.0, s1, 170.0)))
        kite.position(sym, 30, 200.0, product = "MIS")
        kite.trailReads = 1_000
        kite.positionsTrailReads = 1_000
        pass()
        ExpirySquareOff.testAfterReads = { clock += SquareOffExit.GAP_MS + 2_000 }
        at(15, 5); expiryPass()
        assertTrue("nothing sold beside it", squareOffSells("iraalgoexpiry").isEmpty())
        assertEquals("a pass that left it unsold", 1, SquareOffExit.skipsForTest("NFO", sym, "MIS"))
        ExpirySquareOff.testAfterReads = null
        kite.showInBook(); kite.positionsCatchUp()
        at(15, 6); expiryPass()
        assertTrue(squareOffSells("iraalgoexpiry").isEmpty())
        assertEquals("exactly one sale", 1, marketSells().size)
        assertEquals("flat, never short", 0, held())
    }

    /**
     * A lost sale of the arm's found and booked after the square-off read the books ([OrbArms] books it from its look's own read: no
     * cushion after it) - the pass's reads missing it, the positions trailing: the fresh read under the claim shows it, so only B's lot is
     * sold - flat, never short.
     */
    @Test fun aLostSaleFoundAndBookedAfterTheReadsIsNeverSoldAgain() {
        lostSaleOnABesideB(bStop = false)                                  // B with no stop resting; Zerodha's reads still show 60
        ExpirySquareOff.testAfterReads = {
            kite.showInBook()                                              // A's lost sale shows in the order book now
            OrbArms.testNow = OrbArms.testNow!!.plusSeconds(OrbArms.LOST_EXIT_HOLD_MS / 1000 + 1)
            OrbArms.priceCheckOnly(); searchDone(); OrbArms.priceCheckOnly()   // its own looks find and book it
        }
        at(15, 5); expiryPass()
        assertFalse("found and booked", OrbArms.lostExitForTest(sym))
        assertEquals("booked as A's exit", "time_stop", today("liquidity").single { it.arm == "liquidity5" }.why)
        assertEquals("B's lot only", listOf("30"), squareOffSells("iraalgoexpiry"))
        kite.positionsCatchUp()
        assertEquals("flat, never short", 0, held())
    }

    /**
     * Another sender's exit sale on the row (no book counts it: a protection's, Boss's from the app) sent a moment ago whose reply was lost,
     * not in Zerodha's order book yet: the square-off sells nothing beside it; once the book shows it, only what it left is sold - flat.
     */
    @Test fun anotherSendersExitSaleNotInTheBookYetHoldsTheRowBack() {
        state(emptyList())
        runBlocking { OrbArms.view() }
        kite.position(sym, 60, 200.0, product = "MIS")
        kite.positionsTrailReads = 1_000                                   // the positions trail its fill
        kite.trailReads = 1_000                                            // and the order book's list the sale
        kite.nextPlace(reply = FakeKite.Reply.DROP_AFTER_ACCEPT)           // its reply lost
        runCatching { runBlocking { Broker.placeOrder(Kite.Order(sym, Kite.Side.SELL, 30, 30, "MIS", "MARKET", null, 0.05, "NFO", ""), exit = true) } }
        kite.requests.clear()
        at(15, 5); expiryPass()
        assertTrue("nothing sold beside it", squareOffSells("iraalgoexpiry").isEmpty())
        assertEquals(1, SquareOffExit.skipsForTest("NFO", sym, "MIS"))
        kite.showInBook()
        at(15, 6); expiryPass()
        assertEquals("only what it left", listOf("30"), squareOffSells("iraalgoexpiry"))
        kite.positionsCatchUp()
        assertEquals("flat, never short", 0, held())
    }

    // ---- third round: a row left unsold pass after pass is told ----------------------------------------------------------------------

    private fun unsoldTold(what: String) = com.optionslab.app.work.Alerts.queue.value.any { "has left it unsold $what" in it.text }

    /** A row an arm keeps selling (its claim held) is left unsold pass after pass: Boss is told once, at the third in a row. */
    @Test fun aRowLeftUnsoldThreePassesInARowIsToldOnce() {
        state(emptyList())
        runBlocking { OrbArms.view() }
        kite.position(sym, 30, 200.0, product = "MIS")
        val claim = SquareOffExit.claimExit("NFO", sym, "MIS", OrbArms.CLAIM_OWNER)!!
        try {
            at(15, 5); expiryPass()
            at(15, 6); expiryPass()
            assertFalse("not after two", unsoldTold("on 2 passes"))
            at(15, 7); expiryPass()
            assertTrue("told at the third", unsoldTold("on 3 passes in a row"))
            assertTrue(squareOffSells("iraalgoexpiry").isEmpty())
        } finally { claim.release() }
        at(15, 9); expiryPass()
        assertEquals("sold once free", listOf("30"), squareOffSells("iraalgoexpiry"))
        assertEquals("the count starts again", 0, SquareOffExit.skipsForTest("NFO", sym, "MIS"))
    }

    /** From the expiry square-off's backstop minute a row left unsold is told at once. */
    @Test fun fromTheBackstopMinuteARowLeftUnsoldIsToldAtOnce() {
        state(emptyList())
        runBlocking { OrbArms.view() }
        kite.position(sym, 30, 200.0, product = "MIS")
        val claim = SquareOffExit.claimExit("NFO", sym, "MIS", OrbArms.CLAIM_OWNER)!!
        try {
            at(ExpirySquareOff.BACKSTOP_MINUTE / 60, ExpirySquareOff.BACKSTOP_MINUTE % 60); expiryPass()
            assertTrue("told at once", unsoldTold("at its last minutes"))
        } finally { claim.release() }
    }

    /**
     * From the backstop minute, an arm's sale started after the pass's reads no longer puts the row off: the square-off reads again under
     * the claim (that sale a fill in it) and sells the rest in the same pass - flat, never short.
     */
    @Test fun fromTheBackstopMinuteASaleStartedAfterTheReadsIsReadAgainNotPutOff() {
        val s1 = stopOrder(30, 170.0, 161.5)
        state(listOf(liquidity(30, 200.0, s1, 170.0)))                     // the arm's lot, its time stop due
        kite.position(sym, 60, 200.0, product = "MIS")                     // and 30 more that are no arm's
        runBlocking { OrbArms.view() }
        ExpirySquareOff.testAfterReads = { OrbArms.priceCheckOnly() }      // the arm's whole exit, after the square-off's reads
        at(ExpirySquareOff.BACKSTOP_MINUTE / 60, ExpirySquareOff.BACKSTOP_MINUTE % 60); expiryPass()
        assertEquals("the arm sold its lot", listOf("30"), squareOffSells("iraorb"))
        assertEquals("the rest sold in the same pass", listOf("30"), squareOffSells("iraalgoexpiry"))
        assertEquals(0, SquareOffExit.skipsForTest("NFO", sym, "MIS"))
        assertEquals("flat, never short", 0, held())
    }
}
