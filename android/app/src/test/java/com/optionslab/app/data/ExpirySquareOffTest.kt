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
    private lateinit var kite: FakeKite
    private val ce = "NIFTY26SEP24500CE"
    private val pe = "NIFTY26SEP24500PE"
    private val later = "NIFTY26OCT24500CE"

    @Before fun up() {
        kite = FakeKite()
        kite.login()
        val today = com.optionslab.app.data.Market.today()
        kite.instruments += FakeKite.Ins(1_001, ce, "NIFTY", today, 24_500.0, "CE", 75)
        kite.instruments += FakeKite.Ins(1_002, pe, "NIFTY", today, 24_500.0, "PE", 75)
        kite.instruments += FakeKite.Ins(1_003, later, "NIFTY", today.plusDays(7), 24_500.0, "CE", 75)
        listOf(ce, pe, later).forEach { kite.quote("NFO:$it", 10.0, 9.95, 10.05) }
        runBlocking { Broker.instruments() }                       // the day's contract list, as the watch loads it
        kite.requests.clear()
        AutomationSupport.resetExpiryTold()
        AutomationSupport.clearAlerts()
        at(15, 6)
    }

    @After fun down() {
        AutomationSupport.realClocks()
        kite.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun at(h: Int, m: Int) { ExpirySquareOff.testNow = AutomationSupport.todayAt(h, m) }
    private fun pass() = runBlocking { ExpirySquareOff.maybeRun(context, AppSettings.load()) }
    private fun done() = SecurePrefs.getString("sq.expiry.done") == com.optionslab.app.data.Market.today().toString()
    private fun sent() = kite.placed.map { Triple(it.form["tradingsymbol"], it.form["transaction_type"], it.form["quantity"]) }

    @Test fun nothingBefore1505OrAfterTheClose() {
        kite.position(ce, 75, 20.0)
        at(15, 4); pass()
        at(15, 30); pass()
        assertTrue("no request at all outside 15:05-15:30", kite.requests.isEmpty())
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
        val said = AutomationSupport.alerts().filter { it.contains("Zerodha refused the expiry exit of $ce") }
        assertEquals("told once, not every pass", 1, said.size)
        assertTrue(said.single(), said.single().contains("Instrument is blocked for trading."))
        pass()
        assertEquals("tried every pass until it went", 3, kite.placed.size)
        assertEquals(0, kite.positions.getValue("NFO:$ce:NRML").first)
    }

    @Test fun anExitTheAppWillNotSendIsSaidOnce() {
        kite.position(ce, 75, 20.0, product = "CO")                   // a cover order position: not a product the app sends
        pass(); pass()
        assertTrue("nothing sent", kite.placed.isEmpty())
        val said = AutomationSupport.alerts().filter { it.contains("$ce expires today and its exit was not sent") }
        assertEquals(1, said.size)
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

    @Test fun anotherRestingExitIsCancelledFirstAndTheMarketExitFollowsNextPass() {
        kite.position(ce, 75, 20.0)
        val stop = runBlocking { Broker.placeOrder(Kite.Order(ce, Kite.Side.SELL, 75, 75, "NRML", "SL", 4.5, 0.05, "NFO", "iraprotect", triggerPrice = 5.0), exit = true) }
        kite.requests.clear()
        pass()
        assertTrue(kite.requests.any { it.method == "DELETE" && it.path == "/orders/regular/$stop" })
        assertTrue("no market exit while the stop may still fill", kite.placed.isEmpty())
        pass()
        assertEquals(listOf(Triple(ce, "SELL", "75")), sent())
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
        pass()
        assertTrue(kite.placed.isEmpty())
        val said = AutomationSupport.alerts().single { it.contains("SENSEX26SEP80000CE") }
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
