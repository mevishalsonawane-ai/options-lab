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
        at(15, ExpirySquareOff.BACKSTOP_MINUTE % 60); pass()
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
        at(15, ExpirySquareOff.BACKSTOP_MINUTE % 60); pass()
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
        at(15, ExpirySquareOff.BACKSTOP_MINUTE % 60); pass()
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
