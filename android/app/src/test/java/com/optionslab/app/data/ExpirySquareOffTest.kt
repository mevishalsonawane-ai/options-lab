package com.optionslab.app.data

import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.FakeKite.Outcome
import com.optionslab.app.testing.FakeKite.Reply
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.Kite
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode

/**
 * The 15:05 expiry-day square-off ([ExpirySquareOff]) against the fake Kite: what it sends (freeze-quantity
 * slices, shorts first, only what is not already going out), that it retries until nothing expiring is left,
 * and that every exit it cannot send is said out loud, once.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class ExpirySquareOffTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var kite: FakeKite
    private val ce = "NIFTY26SEP24500CE"
    private val pe = "NIFTY26SEP24500PE"
    private val later = "NIFTY26OCT24500CE"

    /** The test's day, read once: a run that crossed midnight IST (CI at 18:30 UTC) read two different "today"s. */
    private lateinit var today: java.time.LocalDate

    @Before fun up() {
        kite = FakeKite()
        kite.login()
        today = com.optionslab.app.data.Market.today()
        kite.instruments += FakeKite.Ins(1_001, ce, "NIFTY", today, 24_500.0, "CE", 75)
        kite.instruments += FakeKite.Ins(1_002, pe, "NIFTY", today, 24_500.0, "PE", 75)
        kite.instruments += FakeKite.Ins(1_003, later, "NIFTY", today.plusDays(7), 24_500.0, "CE", 75)
        listOf(ce, pe, later).forEach { kite.quote("NFO:$it", 10.0, 9.95, 10.05) }
        runBlocking { Broker.instruments() }                       // the day's contract list, as the watch loads it
        kite.requests.clear()
        AutomationSupport.resetExpiryTold()
        com.optionslab.app.testing.Background.clearAlerts()            // and the banner's 20 s repeat filter
        at(15, 6)
    }

    @After fun down() {
        ExpirySquareOff.armOwns = { ArmLive.ownerOf(it) != null }
        ExpirySquareOff.liveBooksReady = { true }
        SquareOffExit.resetForTest(gap = 0L)
        AutomationSupport.realClocks()
        kite.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun at(h: Int, m: Int) { ExpirySquareOff.testNow = today.atTime(h, m).atZone(java.time.ZoneId.of("Asia/Kolkata")) }
    private fun pass() = runBlocking { ExpirySquareOff.maybeRun(context, AppSettings.load()) }
    private fun done() = SecurePrefs.getString("sq.expiry.done") == today.toString()
    private fun sent() = kite.placed.map { Triple(it.form["tradingsymbol"], it.form["transaction_type"], it.form["quantity"]) }

    /** What the banner said, each distinct text once. */
    private fun texts() = com.optionslab.app.work.Alerts.queue.value.map { it.text }.distinct()

    /** Was two identical banners: tellOnce called Alerts.error and Notifier.post, which drops the same text in again. */
    @Test fun aRefusalIsOneBannerNotTwo() {
        kite.position(ce, 75, 20.0)
        kite.nextPlace(reply = Reply.INPUT_EXCEPTION, message = "Instrument is blocked for trading.")
        pass()
        assertEquals(1, com.optionslab.app.work.Alerts.queue.value.count { it.text.contains("Zerodha refused the expiry exit") })
    }

    @Test fun nothingBefore1505OrAfterTheClose() {
        kite.position(ce, 75, 20.0)
        at(15, 4); pass()
        // F&O's close of the day (15:40 from 3 Aug 2026, 15:30 before): an expiring option is closable to then, not after.
        val close = Market.foClose(today)
        at(close / 60, close % 60); pass()
        assertTrue("no request at all outside 15:05 to F&O's close", kite.requests.isEmpty())
    }

    @Test fun stillClosesAt1535WhileFnoTradesTo1540() {
        org.junit.Assume.assumeTrue("a day from 3 Aug 2026", Market.foClose(today) == 15 * 60 + 40)
        kite.position(ce, 75, 20.0)
        at(15, 35); pass()
        assertEquals(listOf(Triple(ce, "SELL", "75")), sent())
    }

    @Test fun switchedOffDoesNothing() {
        kite.position(ce, 75, 20.0)
        SecurePrefs.put("g.expSq", false)
        pass()
        assertTrue(kite.requests.isEmpty())
    }

    @Test fun closesWhatExpiresTodayAndLeavesTheRest() {
        kite.position(ce, 75, 20.0)
        kite.position(later, 75, 20.0)
        pass()
        assertEquals(listOf(Triple(ce, "SELL", "75")), sent())
        val o = kite.placed.single().form
        assertEquals("MARKET", o["order_type"]); assertEquals("NRML", o["product"]); assertEquals("iraalgoexpiry", o["tag"])
    }

    /**
     * 20 Oct, review: the last pass's exit was resting and filled between this pass's two reads. Positions were read first,
     * so the long still showed and no exit was working any more: a second SELL went out and left a short on expiry day.
     */
    @Test fun anExitFillingBetweenTheReadsIsNeverSentTwice() {
        kite.position(ce, 75, 20.0)
        kite.nextPlace(outcome = Outcome.OPEN)                      // a thin book: the first exit rests
        pass()
        val first = kite.orders.values.single()
        assertEquals("OPEN", first.status)
        at(15, 7)
        // It fills right after the next pass reads the positions (still +75 there).
        kite.afterNext("/portfolio/positions") { kite.fill(first.id) }
        pass()
        assertEquals("one exit only", listOf(Triple(ce, "SELL", "75")), sent())
        assertEquals("flat, never short", 0, kite.positions.getValue("NFO:$ce:NRML").first)
        at(15, 8); pass()
        assertEquals("still one exit once it is flat", 1, kite.placed.size)
        assertTrue("and the day is done", done())
    }

    /**
     * 19 Nov 2026 (second review of the clock lane, item 4: it runs this from the watch's first seconds, a restart at 15:06
     * included): the arms' live book not read yet, no live position can be told an arm's, so none is sold as Boss's own before the
     * backstop, and the day is not done. At the backstop whatever is still open is closed, as before.
     */
    @Test fun anUnreadArmsBookSellsNoLivePositionBeforeTheBackstop() {
        ExpirySquareOff.liveBooksReady = { false }
        kite.position(ce, 75, 20.0)
        pass()
        assertTrue("nothing sold while the arms' book is unread", kite.placed.isEmpty())
        assertTrue("and the day is not done", !done())
        at(ExpirySquareOff.BACKSTOP_MINUTE / 60, ExpirySquareOff.BACKSTOP_MINUTE % 60); pass()
        assertEquals(listOf(Triple(ce, "SELL", "75")), sent())
    }

    /** The other half: a fill between the reads leaves the orders read first showing it working; the positions then flat. */
    @Test fun ordersAreReadBeforeThePositions() {
        kite.position(ce, 75, 20.0)
        pass()
        val gets = kite.requests.filter { it.method == "GET" }.map { it.path }
        assertTrue(gets.toString(), gets.indexOf("/orders") in 0 until gets.indexOf("/portfolio/positions"))
    }

    /**
     * 20 Oct, review: an arm's live trade is sold by the arm's own 15:05 square-off; this one leaves it to its owner (two senders
     * at once could sell it twice) until the backstop minute, and closes it then if it is still open. Not done meanwhile.
     */
    @Test fun anArmsLiveTradeIsLeftToItsOwnerUntilTheBackstop() {
        kite.position(ce, 75, 20.0)
        kite.position(pe, 75, 20.0)
        ExpirySquareOff.armOwns = { it == ce }
        at(15, 6); pass()
        assertEquals("only the one no arm owns", listOf(Triple(pe, "SELL", "75")), sent())
        assertTrue("the arm's is still open: not done", !done())
        at(ExpirySquareOff.BACKSTOP_MINUTE / 60, ExpirySquareOff.BACKSTOP_MINUTE % 60); pass()
        assertEquals("the backstop closes what its owner did not", listOf(Triple(pe, "SELL", "75"), Triple(ce, "SELL", "75")), sent())
    }

    /** Second review: at the backstop, an arm whose own exit is still working at Zerodha is left to finish it (no cancel, no second sell). */
    @Test fun atTheBackstopAnArmsWorkingExitIsLeftAlone() {
        kite.position(ce, 75, 20.0)
        ExpirySquareOff.armOwns = { it == ce }
        kite.nextPlace(outcome = Outcome.OPEN)
        val armSell = runBlocking { Broker.placeOrder(Kite.Order(ce, Kite.Side.SELL, 75, 75, "NRML", "MARKET", null, 0.05, "NFO",
            com.optionslab.engine.orb.LiveMirror.TAG), exit = true) }
        kite.requests.clear()
        at(ExpirySquareOff.BACKSTOP_MINUTE / 60, ExpirySquareOff.BACKSTOP_MINUTE % 60); pass()
        assertTrue("nothing sent", kite.placed.isEmpty())
        assertTrue("the arm's exit not cancelled", kite.requests.none { it.method == "DELETE" })
        assertEquals("OPEN", kite.order(armSell).status)
        assertTrue("not done while it is open", !done())
    }

    /** 20 Oct, review: SENSEX's options (BFO) expiring today are closed too, on BFO, once the day's list is in. */
    @Test fun aSensexOptionExpiringTodayIsClosedOnBfo() {
        val sx = "SENSEX26OCT81000CE"
        kite.instruments += FakeKite.Ins(2_001, sx, "SENSEX", today, 81_000.0, "CE", 20, exchange = "BFO")
        kite.quote("BFO:$sx", 50.0, 49.9, 50.1)
        kite.position(sx, 40, 60.0, exchange = "BFO")
        Broker.resetBfoForTest()
        pass()                                                        // asks for the SENSEX list in the background
        val until = System.currentTimeMillis() + 5_000
        while (Broker.cachedBfoOptions() == null && System.currentTimeMillis() < until + 10_000) Thread.sleep(20)
        assertTrue("the list is in", Broker.cachedBfoOptions() != null)
        at(15, 7); pass()
        val o = kite.placed.single().form
        assertEquals(listOf(sx, "SELL", "40", "BFO"), listOf(o["tradingsymbol"], o["transaction_type"], o["quantity"], o["exchange"]))
        assertTrue("no false 'cannot tell when it expires' notice", texts().none { it.contains("cannot tell when it expires") })
    }

    /**
     * B5 review: a SENSEX list fetched before the morning's dump already lists today's expiries, so an expiring SENSEX position is
     * closed at 15:05 from it - even while today's list cannot be read again - and no "cannot tell when it expires" notice is said.
     */
    @Test fun aSensexListFromBeforeTheDumpStillClosesItsExpiryAt1505() {
        val sx = "SENSEX26OCT82000CE"
        kite.instruments += FakeKite.Ins(2_002, sx, "SENSEX", today, 82_000.0, "CE", 20, exchange = "BFO")
        kite.quote("BFO:$sx", 50.0, 49.9, 50.1)
        kite.position(sx, 40, 60.0, exchange = "BFO")
        Broker.resetBfoForTest()
        Market.testClock = java.time.Clock.fixed(today.atTime(7, 50).atZone(com.optionslab.engine.IST).toInstant(), com.optionslab.engine.IST)
        try {
            assertEquals(1, runBlocking { Broker.bfoOptionsNow() }.count { it.tradingSymbol == sx })   // read at 07:50, before the dump
            kite.drop += "/instruments/BFO"                                                          // today's cannot be read again
            Market.testClock = java.time.Clock.fixed(today.atTime(15, 5).atZone(com.optionslab.engine.IST).toInstant(), com.optionslab.engine.IST)
            assertTrue("not today's for the notice", Broker.currentBfoOptions() == null)
            at(15, 5); pass()
            val o = kite.placed.single().form
            assertEquals(listOf(sx, "SELL", "40", "BFO"), listOf(o["tradingsymbol"], o["transaction_type"], o["quantity"], o["exchange"]))
            assertTrue("no false 'cannot tell when it expires' notice", texts().none { it.contains("cannot tell when it expires") })
        } finally { Market.testClock = null; kite.drop -= "/instruments/BFO" }
    }

    @Test fun shortsAreBoughtBackBeforeLongsAreSold() {
        kite.position(ce, 75, 20.0)
        kite.position(pe, -150, 30.0)
        pass()
        assertEquals(listOf(Triple(pe, "BUY", "150"), Triple(ce, "SELL", "75")), sent())
    }

    @Test fun aPositionAboveTheFreezeQuantityGoesAsSlices() {
        kite.position(pe, -3_750, 30.0)                              // 50 lots; NIFTY freezes at 1,800
        pass()
        assertEquals(listOf("1800", "1800", "150"), kite.placed.map { it.form["quantity"] })
        assertTrue(kite.placed.all { it.form["transaction_type"] == "BUY" })
        assertEquals("closed in full", 0, kite.positions.getValue("NFO:$pe:NRML").first)
    }

    /** 18 Oct, review: no Zerodha session at 15:05 is a book not read: not done for the day, and a 15:10 login still closes it. */
    @Test fun noSessionAt1505IsNotDoneAndALaterLoginStillClosesIt() {
        kite.position(ce, 75, 20.0)
        // A linked account as on Boss's phone (the key and its secret saved, a login once; made-up values, as
        // Background.linkZerodha): the fake Kite's login saves no secret, so it would not count as linked. Then no session today.
        SecurePrefs.putAll(mapOf("kite.apiSecret" to "test-secret-not-real", "kite.accessToken" to null))
        assertTrue("no session", !Broker.loggedIn)
        assertTrue("linked", Broker.linked)
        pass()
        assertTrue("nothing can be sent without a session", kite.requests.isEmpty())
        assertTrue("the live book was not read: not done", !done())
        at(15, 7); pass()
        assertTrue("still not done while the session is missing", !done())
        kite.login()
        at(15, 10); pass()
        assertEquals(listOf(Triple(ce, "SELL", "75")), sent())
        pass()
        assertTrue("closed and read: done", done())
    }

    /** No Zerodha account linked at all (paper only): an empty paper book is done for the day, as before. */
    @Test fun noLinkedAccountAndNothingExpiringOnPaperIsDone() {
        SecurePrefs.putAll(mapOf("kite.apiSecret" to "test-secret-not-real", "kite.accessToken" to null, "kite.userId" to null))
        assertTrue("no session", !Broker.loggedIn)
        assertTrue("never logged in: not linked", !Broker.linked)
        pass()
        assertTrue(done())
        assertTrue(kite.requests.isEmpty())
    }

    @Test fun retriesEveryPassUntilNothingIsLeftThenStops() {
        kite.position(ce, 75, 20.0)
        kite.nextPlace(outcome = Outcome.REJECT, message = "RMS:Blocked for trading")
        pass()
        assertEquals(1, kite.placed.size)
        assertTrue("an exit sent is not a position closed", !done())
        pass()                                                         // sent again: this one fills
        assertEquals(2, kite.placed.size)
        assertEquals(0, kite.positions.getValue("NFO:$ce:NRML").first)
        pass()                                                         // finds nothing: done for the day
        assertTrue(done())
        val before = kite.requests.size
        pass()
        assertEquals("once done, a pass does not even read the books", before, kite.requests.size)
    }

    @Test fun aRefusedExitIsSaidOnceAndRetried() {
        kite.position(ce, 75, 20.0)
        kite.nextPlace(reply = Reply.INPUT_EXCEPTION, message = "Instrument is blocked for trading.")
        kite.nextPlace(reply = Reply.INPUT_EXCEPTION, message = "Instrument is blocked for trading.")
        pass(); pass()
        val said = texts().filter { it.contains("Zerodha refused the expiry exit of $ce") }
        assertEquals(1, said.size)
        assertEquals("told once, not every pass", 1, com.optionslab.app.work.Alerts.queue.value.count { it.text == said.single() })
        assertTrue(said.single(), said.single().contains("Instrument is blocked for trading."))
        pass()
        assertEquals("tried every pass until it went", 3, kite.placed.size)
        assertEquals(0, kite.positions.getValue("NFO:$ce:NRML").first)
    }

    /** 10 Nov (the 3 Nov refusal, for an exit): a MARKET exit Zerodha blocks (low open interest) goes once as a LIMIT at the bid. */
    @Test fun aBlockedMarketExitGoesOnceAsALimitAtTheBid() {
        kite.position(ce, 75, 20.0)
        kite.nextPlace(reply = Reply.INPUT_EXCEPTION, message = "NRML MARKET orders are blocked for this NIFTY contract due to its open interest (OI) being less than 100 lots.")
        pass()
        assertEquals(listOf("MARKET", "LIMIT"), kite.placed.map { it.form["order_type"] })
        assertEquals("at the bid", "9.95", kite.placed[1].form["price"])
        assertEquals("SELL", kite.placed[1].form["transaction_type"]); assertEquals("75", kite.placed[1].form["quantity"])
        assertEquals("closed in the same pass", 0, kite.positions.getValue("NFO:$ce:NRML").first)
        assertTrue("no refusal said", texts().none { it.contains("Zerodha refused the expiry exit of $ce") })
        pass()
        assertEquals("nothing more", 2, kite.placed.size)
    }

    /**
     * 19 Nov 2026 (third review, item 3): the blocked-MARKET exit's LIMIT whose reply is lost (accepted at Zerodha, the connection
     * dropped). It was taken as refused - said so, and no gap - although it was out. Now it is looked for as the MARKET path does:
     * found, it is the exit (filled here); never said refused; nothing more is sent.
     */
    @Test fun aBlockedMarketExitsLimitWithALostReplyIsLookedForNeverSaidRefused() {
        SquareOffExit.resetForTest()                                    // the contract's gap on, as it ships
        kite.position(ce, 75, 20.0)
        kite.nextPlace(reply = Reply.INPUT_EXCEPTION, message = "NRML MARKET orders are blocked for this NIFTY contract due to its open interest (OI) being less than 100 lots.")
        kite.nextPlace(reply = Reply.DROP_AFTER_ACCEPT)
        pass()
        assertEquals(listOf("MARKET", "LIMIT"), kite.placed.map { it.form["order_type"] })
        assertTrue("never said refused", texts().none { it.contains("Zerodha refused the expiry exit of $ce") })
        assertEquals("found and filled", 0, kite.positions.getValue("NFO:$ce:NRML").first)
        at(15, 7); pass()
        assertEquals("nothing more", 2, kite.placed.size)
    }

    /**
     * 19 Nov 2026 (third review, item 4): the contract's gap starts with the order placed (each slice), not once the whole loop has
     * ended: here the first freeze slice goes, the clock moves on 20 s before the second is refused, and a pass 10 s after the first
     * slice still finds the contract in its gap (before, the gap was dated from the loop's end and this pass sent the rest again).
     */
    @Test fun theGapStartsWithTheSlicePlacedNotTheLoopsEnd() {
        SquareOffExit.resetForTest()
        var now = 1_000_000L
        SquareOffExit.clock = { now }
        kite.position(ce, 3_600, 20.0)                                  // 48 lots; NIFTY freezes at 1,800: two slices
        kite.nextPlace()
        kite.nextPlace(reply = Reply.INPUT_EXCEPTION, message = "Order rejected by the exchange.")
        // The clock moves on as the second (refused) slice is answered, after the first slice was placed and noted.
        kite.afterNext("/orders/regular") { kite.afterNext("/orders/regular") { now += 20_000 } }
        pass()
        assertEquals(2, kite.placed.size)
        now = 1_000_000L + 10_000
        at(15, 7); pass()
        assertEquals("nothing sent inside the first slice's gap", 2, kite.placed.size)
    }

    /**
     * 23 Nov 2026 (B6 follow-ups, round 2): the blocked-MARKET exit's LIMIT answered by Kite's unsure DataException and not in the book
     * through the door's looks ([Broker.KiteUnsure]): no second search of the square-off's own, the row's gap kept. It shows in the book
     * later and rests unfilled - before, every later pass counted it as cover and the expiring row was held into the settlement. Past its
     * 10 s chase ([SquareOffExit.LIMIT_CHASE_MS]) it is cancelled, confirmed out, and the row sold again: flat.
     */
    @Test fun aLateUnsureLimitRestingUnfilledIsCancelledAndSoldAgainNeverCover() {
        SquareOffExit.resetForTest()                                    // the contract's gap on, as it ships
        var clock = 1_000_000L
        SquareOffExit.clock = { clock }
        val t0 = today.atTime(15, 6).atZone(com.optionslab.engine.IST).toInstant()
        com.optionslab.app.data.Market.testClock = java.time.Clock.fixed(t0, com.optionslab.engine.IST)
        kite.position(ce, 75, 20.0)
        kite.nextPlace(reply = Reply.INPUT_EXCEPTION, message = "NRML MARKET orders are blocked for this NIFTY contract due to its open interest (OI) being less than 100 lots.")
        kite.trailReads = 1_000                                         // the LIMIT: taken, resting, but not in the book's list yet
        kite.nextPlace(FakeKite.Scenario(reply = Reply.DATA_EXCEPTION_AFTER_ACCEPT, outcome = Outcome.OPEN))
        pass()
        assertEquals(listOf("MARKET", "LIMIT"), kite.placed.map { it.form["order_type"] })
        assertEquals("no second search of its own", 0, Broker.callerSearchesForTest.get())
        val limit = kite.orders.values.single { it.type == "LIMIT" }
        assertEquals("resting, unfilled", "OPEN", limit.status)
        assertTrue("the gap holds the row", SquareOffExit.inGap("NFO", ce, "NRML"))
        kite.showInBook()                                               // it shows in the book a moment later
        at(15, 6); pass()
        assertEquals("nothing within the gap", 2, kite.placed.size)
        // Past the gap and past its chase: not cover - cancelled, confirmed, and the row sold again.
        clock += SquareOffExit.GAP_MS
        com.optionslab.app.data.Market.testClock = java.time.Clock.fixed(t0.plusSeconds(20), com.optionslab.engine.IST)
        at(15, 7); pass()
        assertEquals("CANCELLED", limit.status)
        assertEquals(listOf("MARKET", "LIMIT", "MARKET"), kite.placed.map { it.form["order_type"] })
        assertEquals("flat", 0, kite.positions.getValue("NFO:$ce:NRML").first)
        at(15, 8); pass()
        assertEquals("nothing more", 3, kite.placed.size)
    }

    /**
     * Its own LIMIT still within its chase by both clocks (Zerodha's stamp, and this phone's since a square-off's sale on the row) stays
     * cover: never cancelled, nothing doubled.
     */
    @Test fun aSquareOffsOwnLimitWithinItsChaseStaysCover() {
        val t0 = today.atTime(15, 6).atZone(com.optionslab.engine.IST).toInstant()
        com.optionslab.app.data.Market.testClock = java.time.Clock.fixed(t0, com.optionslab.engine.IST)
        kite.position(ce, 75, 20.0)
        val lim = kite.restsInKite(ce, "SELL", 75, 9.95, product = "NRML", tag = "iraalgoexpiry")
        SquareOffExit.noteSold("NFO", ce, "NRML")                       // sent a moment ago, by this phone's clock
        com.optionslab.app.data.Market.testClock = java.time.Clock.fixed(t0.plusSeconds(5), com.optionslab.engine.IST)
        kite.requests.clear()
        pass()
        assertTrue("not cancelled", kite.requests.none { it.method == "DELETE" })
        assertEquals("OPEN", kite.order(lim).status)
        assertTrue("nothing more sent", kite.placed.isEmpty())
    }

    /**
     * The other square-off's LIMIT (the flat-by's) whose Zerodha stamp still reads young - this phone's clock behind Zerodha's, or a stamp
     * that cannot be read - is past its chase by this phone's own clock since that sale: cancelled, confirmed, and the row sold again.
     */
    @Test fun aSquareOffsLimitPastItsChaseByThePhonesClockIsSoldAgain() {
        SquareOffExit.resetForTest()
        var clock = 1_000_000L
        SquareOffExit.clock = { clock }
        val t0 = today.atTime(15, 6).atZone(com.optionslab.engine.IST).toInstant()
        com.optionslab.app.data.Market.testClock = java.time.Clock.fixed(t0, com.optionslab.engine.IST)
        kite.position(ce, 75, 20.0)
        val lim = kite.restsInKite(ce, "SELL", 75, 9.95, product = "NRML", tag = "iraalgoflat")
        SquareOffExit.noteSold("NFO", ce, "NRML")
        clock += SquareOffExit.GAP_MS                                     // 15 s by this phone; Zerodha's stamp: 0 s
        pass()
        assertEquals("CANCELLED", kite.order(lim).status)
        assertEquals(listOf("MARKET" to "75"), kite.placed.map { it.form["order_type"] to it.form["quantity"] })
        assertEquals("flat", 0, kite.positions.getValue("NFO:$ce:NRML").first)
    }

    /**
     * A square-off's LIMIT from before a restart (no sale of a square-off on the row in this process), past its chase by Zerodha's stamp
     * and part filled: cancelled, confirmed, and only what is still held sold - flat, never short.
     */
    @Test fun aPartFilledLimitFromBeforeARestartIsCancelledAndTheRestSold() {
        val t0 = today.atTime(15, 5).atZone(com.optionslab.engine.IST).toInstant()
        com.optionslab.app.data.Market.testClock = java.time.Clock.fixed(t0, com.optionslab.engine.IST)
        kite.position(ce, 150, 20.0)
        val lim = kite.restsInKite(ce, "SELL", 150, 9.95, product = "NRML", tag = "iraalgoexpiry")
        kite.fillPart(lim, 75, 9.95)
        com.optionslab.app.data.Market.testClock = java.time.Clock.fixed(t0.plusSeconds(60), com.optionslab.engine.IST)
        pass()
        assertEquals("CANCELLED", kite.order(lim).status)
        assertEquals(listOf("MARKET" to "75"), kite.placed.map { it.form["order_type"] to it.form["quantity"] })
        assertEquals("flat, never short", 0, kite.positions.getValue("NFO:$ce:NRML").first)
    }

    /**
     * 23 Nov 2026 (B6 follow-ups, round 4): a square-off's LIMIT past its chase that fills in part as its cancel lands (75 of 150): nothing
     * sold that pass (the fill came after the books were read); the next pass reads again and sells only the 75 left - flat, never short.
     */
    @Test fun aStaleLimitThatFillsAsItIsCancelledSellsNothingThatPass() {
        val t0 = today.atTime(15, 5).atZone(com.optionslab.engine.IST).toInstant()
        com.optionslab.app.data.Market.testClock = java.time.Clock.fixed(t0, com.optionslab.engine.IST)
        kite.position(ce, 150, 20.0)
        val lim = kite.restsInKite(ce, "SELL", 150, 9.95, product = "NRML", tag = "iraalgoexpiry")
        kite.fillsAsCancelled(lim, 75, 9.95)
        com.optionslab.app.data.Market.testClock = java.time.Clock.fixed(t0.plusSeconds(60), com.optionslab.engine.IST)
        pass()
        assertEquals("CANCELLED", kite.order(lim).status)
        assertEquals(75, kite.order(lim).filled)
        assertTrue("nothing sold that pass", kite.placed.isEmpty())
        at(15, 7); pass()
        assertEquals("the 75 left, once", listOf(Triple(ce, "SELL", "75")), sent())
        assertEquals("flat, never short", 0, kite.positions.getValue("NFO:$ce:NRML").first)
    }

    /**
     * An arm's LIMIT exit ([com.optionslab.engine.orb.LiveMirror.TAG]) for all [held] of [ce] (the arm's position), placed [restedS] seconds
     * before the minute [nowMinute] of today, which the clocks then read. Its id.
     */
    private fun armLimitResting(restedS: Long, nowMinute: Int, held: Int = 75): String {
        val now = today.atTime(nowMinute / 60, nowMinute % 60).atZone(com.optionslab.engine.IST).toInstant()
        com.optionslab.app.data.Market.testClock = java.time.Clock.fixed(now.minusSeconds(restedS), com.optionslab.engine.IST)
        kite.position(ce, held, 20.0)
        ExpirySquareOff.armOwns = { it == ce }
        val lim = kite.restsInKite(ce, "SELL", held, 12.0, product = "NRML", tag = com.optionslab.engine.orb.LiveMirror.TAG)
        com.optionslab.app.data.Market.testClock = java.time.Clock.fixed(now, com.optionslab.engine.IST)
        at(nowMinute / 60, nowMinute % 60)
        kite.requests.clear()
        return lim
    }

    /**
     * 23 Nov 2026 (B6 follow-ups, round 4): at the backstop, an arm's LIMIT exit resting unfilled past its chase (by Zerodha's stamp) is no
     * longer cover - cancelled, confirmed out, and the square-off sells what is held: once, flat, never carried into the settlement.
     */
    @Test fun atTheBackstopAnArmsLimitRestingPastItsChaseIsCancelledThenSoldOnce() {
        val lim = armLimitResting(restedS = 60, nowMinute = ExpirySquareOff.BACKSTOP_MINUTE)
        pass()
        assertEquals("CANCELLED", kite.order(lim).status)
        assertEquals("sold once by the square-off", listOf(Triple(ce, "SELL", "75")), sent())
        assertEquals("iraalgoexpiry", kite.placed.single().form["tag"])
        assertEquals("flat, never short", 0, kite.positions.getValue("NFO:$ce:NRML").first)
        pass()
        assertEquals("nothing more", 1, kite.placed.size)
    }

    /** ... and one that fills in part as its cancel lands: nothing sold that pass; the next sells only what is left, once. */
    @Test fun atTheBackstopAnArmsStaleLimitFillingAsItIsCancelledSellsOnlyTheRestNextPass() {
        val lim = armLimitResting(restedS = 60, nowMinute = ExpirySquareOff.BACKSTOP_MINUTE, held = 150)
        kite.fillsAsCancelled(lim, 75, 12.0)
        pass()
        assertEquals("CANCELLED", kite.order(lim).status)
        assertTrue("nothing sold that pass", kite.placed.isEmpty())
        pass()
        assertEquals("the 75 left, once", listOf(Triple(ce, "SELL", "75")), sent())
        assertEquals("flat, never short", 0, kite.positions.getValue("NFO:$ce:NRML").first)
    }

    /**
     * At the backstop, an arm's LIMIT placed 20 s ago - past a square-off's own 12 s chase, but within [SquareOffExit.ARM_LIMIT_STALE_MS]:
     * the arm may still be waiting on it (it would put its stop back when it saw it cancelled) - is left to the arm: never cancelled,
     * nothing sold beside it.
     */
    @Test fun atTheBackstopAnArmsLimitItMayStillWaitOnIsLeftAlone() {
        val lim = armLimitResting(restedS = 20, nowMinute = ExpirySquareOff.BACKSTOP_MINUTE)
        pass()
        assertTrue("not cancelled", kite.requests.none { it.method == "DELETE" })
        assertEquals("OPEN", kite.order(lim).status)
        assertTrue("nothing sold", kite.placed.isEmpty())
    }

    /** Before the backstop, an arm's LIMIT exit resting long past its chase, on a row the square-off sells, is still cover, as before. */
    @Test fun beforeTheBackstopAnArmsLimitRestingLongStaysCover() {
        val lim = armLimitResting(restedS = 60, nowMinute = 15 * 60 + 6)
        ExpirySquareOff.armOwns = { false }
        pass()
        assertTrue("not cancelled", kite.requests.none { it.method == "DELETE" })
        assertEquals("OPEN", kite.order(lim).status)
        assertTrue("nothing sold beside it", kite.placed.isEmpty())
    }

    /**
     * 19 Nov 2026 (final review, item 5): the blocked-MARKET exit's LIMIT confirmed cancelled unfilled leaves no gap - nothing of it is
     * out, so the next pass tries again at once (near 15:05 a 15 s hold is a long time).
     */
    @Test fun aLimitCancelledUnfilledLeavesNoGap() {
        SquareOffExit.resetForTest()
        kite.position(ce, 75, 20.0)
        kite.nextPlace(reply = Reply.INPUT_EXCEPTION, message = "NRML MARKET orders are blocked for this NIFTY contract due to its open interest (OI) being less than 100 lots.")
        kite.nextPlace(outcome = Outcome.OPEN)                          // the LIMIT rests and is cancelled after its 10 s
        pass()
        assertEquals(listOf("MARKET", "LIMIT"), kite.placed.map { it.form["order_type"] })
        assertEquals("CANCELLED", kite.orders.values.single { it.type == "LIMIT" }.status)
        // (22 Nov 2026, B6 review, fourth round: nothing of it went - no sale stamp left on the row to hold an arm's stop back.)
        assertNull(SquareOffExit.lastSaleAt("NFO", ce, "NRML"))
        at(15, 7); pass()
        assertEquals("tried again at once", listOf("MARKET", "LIMIT", "MARKET"), kite.placed.map { it.form["order_type"] })
    }

    /**
     * 19 Nov 2026 (final review, the Strategy Module): a strategy's own exit sale working on the contract is cover - never cancelled
     * (the strategy would send its exit again from its own book, beside this square-off's, and the account would end short).
     */
    @Test fun aStrategysWorkingExitSaleIsCoverBeforeItsBackstop() {
        kite.position(ce, 75, 20.0)
        kite.nextPlace(outcome = Outcome.OPEN)
        val id = runBlocking { Broker.placeOrder(Kite.Order(ce, Kite.Side.SELL, 75, 75, "NRML", "MARKET", null, 0.05, "NFO", "iraalgostrat"), exit = true) }
        kite.requests.clear()
        pass()
        assertTrue("not cancelled", kite.requests.none { it.method == "DELETE" })
        assertEquals("OPEN", kite.order(id).status)
        assertTrue("nothing more sent", kite.placed.isEmpty())
        assertTrue("nothing told while it is fresh", texts().none { it.contains("a strategy's exit sale for it is still resting") })
        // Resting a while (90 s): still cover (its closing in progress), told to Boss once.
        com.optionslab.app.data.Market.testClock = java.time.Clock.fixed(Market.now().toInstant().plusSeconds(90), com.optionslab.engine.IST)
        try {
            at(15, 7); pass(); pass()
            assertTrue("not cancelled yet", kite.requests.none { it.method == "DELETE" })
            assertTrue("nothing sold beside it", kite.placed.isEmpty())
            assertEquals("told once", 1, texts().count { it.contains("a strategy's exit sale for it is still resting") })
        } finally {
            com.optionslab.app.data.Market.testClock = null
        }
    }

    /**
     * 19 Nov 2026 (final check, M1): from the backstop minute a strategy's exit sale still resting is taken over - cancelled, confirmed
     * out with nothing filled, and the expiry square-off sells what is held: once, never twice; never carried into the settlement.
     */
    @Test fun atTheBackstopAStuckStrategyExitIsCancelledThenSoldOnce() {
        kite.position(ce, 75, 20.0)
        kite.nextPlace(outcome = Outcome.OPEN)
        val id = runBlocking { Broker.placeOrder(Kite.Order(ce, Kite.Side.SELL, 75, 75, "NRML", "MARKET", null, 0.05, "NFO", "iraalgostrat"), exit = true) }
        kite.requests.clear()
        at(ExpirySquareOff.BACKSTOP_MINUTE / 60, ExpirySquareOff.BACKSTOP_MINUTE % 60); pass()
        assertEquals("CANCELLED", kite.order(id).status)
        assertEquals("sold once by the square-off", listOf(Triple(ce, "SELL", "75")), sent())
        assertEquals("iraalgoexpiry", kite.placed.single().form["tag"])
        assertEquals("flat, never short", 0, kite.positions.getValue("NFO:$ce:NRML").first)
        pass()
        assertEquals("nothing more", 1, kite.placed.size)
    }

    /** M1: before the backstop, one resting past two minutes is taken over too. */
    @Test fun aStrategyExitRestingPastTwoMinutesIsTakenOver() {
        kite.position(ce, 75, 20.0)
        kite.nextPlace(outcome = Outcome.OPEN)
        val id = runBlocking { Broker.placeOrder(Kite.Order(ce, Kite.Side.SELL, 75, 75, "NRML", "MARKET", null, 0.05, "NFO", "iraalgostrat"), exit = true) }
        kite.requests.clear()
        com.optionslab.app.data.Market.testClock = java.time.Clock.fixed(Market.now().toInstant().plusSeconds(150), com.optionslab.engine.IST)
        try {
            pass()
            assertEquals("CANCELLED", kite.order(id).status)
            assertEquals(listOf(Triple(ce, "SELL", "75")), sent())
        } finally {
            com.optionslab.app.data.Market.testClock = null
        }
    }

    @Test fun anExitTheAppWillNotSendIsSaidOnce() {
        kite.position(ce, 75, 20.0, product = "CO")                   // a cover order position: not a product the app sends
        pass(); pass()
        assertTrue("nothing sent", kite.placed.isEmpty())
        val said = texts().filter { it.contains("$ce expires today and its exit was not sent") }
        assertEquals(1, said.size)
        assertEquals("told once", 1, com.optionslab.app.work.Alerts.queue.value.count { it.text == said.single() })
        assertTrue(said.single(), said.single().contains("product CO is not supported"))
        assertTrue(!done())
    }

    @Test fun aWorkingExitIsSubtractedNotSentTwice() {
        kite.position(ce, 150, 20.0)
        // An earlier pass's exit still resting (a thin book): one lot of the two is already on its way out.
        val resting = runBlocking { Broker.placeOrder(Kite.Order(ce, Kite.Side.SELL, 75, 75, "NRML", "LIMIT", 50.0, 0.05, "NFO", "iraalgoexpiry"), exit = true) }
        kite.requests.clear()
        pass()
        assertEquals("150 held, 75 already going out: 75 sent", listOf(Triple(ce, "SELL", "75")), sent())
        assertEquals("the resting exit is kept", "OPEN", kite.order(resting).status)
    }

    /** 25 Oct review: the resting stop is cancelled, confirmed out, and the market exit goes in the SAME pass (not a minute later). */
    @Test fun anotherRestingExitIsCancelledConfirmedAndTheMarketExitGoesInTheSamePass() {
        kite.position(ce, 75, 20.0)
        val stop = runBlocking { Broker.placeOrder(Kite.Order(ce, Kite.Side.SELL, 75, 75, "NRML", "SL", 4.5, 0.05, "NFO", "iraprotect", triggerPrice = 5.0), exit = true) }
        kite.requests.clear()
        pass()
        assertTrue(kite.requests.any { it.method == "DELETE" && it.path == "/orders/regular/$stop" })
        assertEquals("CANCELLED", kite.order(stop).status)
        assertEquals("the exit in the same pass, once the stop is confirmed out", listOf(Triple(ce, "SELL", "75")), sent())
        pass()
        assertEquals("nothing more", 1, kite.placed.size)
    }

    @Test fun aLostReplyIsAdoptedNotSentAgain() {
        kite.position(ce, 75, 20.0)
        kite.nextPlace(reply = Reply.DROP_AFTER_ACCEPT)
        pass()
        assertEquals("one exit at Zerodha", 1, kite.placed.size)
        pass()
        assertEquals("the next pass finds it closed", 1, kite.placed.size)
        pass()
        assertTrue(done())
    }

    @Test fun aDerivativeTheAppCannotDateIsReportedNotClosed() {
        kite.position("SENSEX26SEP80000CE", 20, 100.0, exchange = "BFO")
        Broker.resetBfoForTest()
        pass()
        assertTrue(kite.placed.isEmpty())
        // 20 Oct: SENSEX's list is asked for first; with no list by the backstop minute, it is said whatever.
        assertTrue("not before the list could be read", texts().none { it.contains("SENSEX26SEP80000CE") })
        at(ExpirySquareOff.BACKSTOP_MINUTE / 60, ExpirySquareOff.BACKSTOP_MINUTE % 60); pass()
        assertTrue(kite.placed.isEmpty())
        val said = texts().single { it.contains("SENSEX26SEP80000CE") }
        assertTrue(said, said.contains("NOT closed automatically"))
    }

    @Test fun theExpiryPutTicketLegsAreLeftAloneOnlyWhenKept() {
        // With no open ticket every expiring leg is closed, the kill switch notwithstanding.
        SecurePrefs.put("g.kill", true)
        kite.position(pe, 75, 20.0)
        pass()
        assertEquals(listOf(Triple(pe, "SELL", "75")), sent())
    }
}
