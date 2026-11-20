package com.optionslab.app.data

import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.FakeKite.Reply
import com.optionslab.app.testing.MarketClockRule
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.work.Alerts
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode

/**
 * 20 Nov 2026 (audit: a square-off cancelled the stops before it knew its sale would go): the square-offs ([SquareOffExit], used by
 * the 15:05 expiry square-off and the flat-by) work out whether the sale can go first, and mark every stop they take out as the app's
 * own cancel - so a sale Zerodha then refuses leaves the position with its stop put back by its owner, never read as Boss's cancel.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class SquareOffStopBackTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var kite: FakeKite
    private val ce = "NIFTY26SEP24500CE"
    private lateinit var today: java.time.LocalDate

    @Before fun up() {
        kite = FakeKite()
        kite.login()
        today = Market.today()
        kite.instruments += FakeKite.Ins(1_001, ce, "NIFTY", today, 24_500.0, "CE", 75)
        kite.quote("NFO:$ce", 10.0, 9.95, 10.05)
        runBlocking { Broker.instruments() }
        kite.position(ce, 75, 10.0)
        AutomationSupport.resetExpiryTold()
        com.optionslab.app.testing.Background.clearAlerts()
        Alerts.forgetPosted()
        DayOco.resetForTest()
        ExpirySquareOff.testNow = today.atTime(15, 6).atZone(com.optionslab.engine.IST)
    }

    @After fun down() {
        SquareOffExit.resetForTest(gap = 0L)
        AutomationSupport.realClocks()
        Market.testClock = null
        kite.close()
        assertEquals(emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun protect(): Protections.Item = runBlocking {
        Protections.protectLive(ce, "NFO", "NRML", 75, 10.0, 8.0, null, 15.0)
        Protections.active().single()
    }

    private fun pass() = runBlocking { ExpirySquareOff.maybeRun(context, AppSettings.load()) }
    private fun stops() = kite.orders.values.filter { it.type == "SL" && it.working }

    @Test fun aSaleZerodhaRefusesGetsTheCancelledStopPutBackByItsOwner() {
        val p = protect()
        kite.nextPlace(reply = Reply.INPUT_EXCEPTION, message = "Instrument is blocked for trading.")
        pass()
        // The square-off took the stop and target out before its sale, which Zerodha refused.
        assertFalse(kite.order(p.stopOrderId!!).working)
        assertTrue(stops().isEmpty())
        // The protections' next look: the app's own cancel, the position still held - its stop rests at Zerodha again.
        Market.testClock = MarketClockRule.clockAt(12, 5)
        runBlocking { Protections.tick() }
        assertEquals("its stop is back", 1, stops().size)
        assertEquals(8.0, stops().single().trigger, 1e-9)
        assertTrue(runBlocking { Protections.active() }.single().stopOrderId != p.stopOrderId)
        assertFalse("never read as Boss's own cancel", Alerts.posted.any { it.text.contains("cancelled by you") })
    }

    /** Review: the sale filled but Zerodha's positions still show the lots it sold - the stop it took out is never put back on them. */
    @Test fun aFilledSaleWhosePositionsTrailItGetsNoStopBack() {
        val p = protect()
        kite.positionsTrailReads = 6                                        // the positions trail the sale's fill
        pass()
        assertEquals(1, kite.placed.count { it.form["tag"] == "iraalgoexpiry" })
        assertFalse(kite.order(p.stopOrderId!!).working)
        Market.testClock = MarketClockRule.clockAt(12, 5)
        repeat(3) { runBlocking { Protections.tick() } }
        assertTrue("no stop rests on what the sale sold", stops().isEmpty())
    }

    /** Deep review: a sale Zerodha accepted and then rejected gets the stop it took out back at once, not after the 120 s wait. */
    @Test fun aSaleRejectedAfterItWasPlacedGetsTheStopBackAtOnce() {
        Market.testClock = MarketClockRule.clockAt(12, 5)
        val p = protect()
        kite.nextPlace(outcome = FakeKite.Outcome.OPEN)
        pass()
        val sale = kite.placed.single { it.form["tag"] == "iraalgoexpiry" }
        assertFalse(kite.order(p.stopOrderId!!).working)
        val id = kite.orders.values.single { it.side == "SELL" && it.type == "MARKET" }.id
        kite.order(id).status = "REJECTED"                                  // the exchange refused it after Zerodha took it
        assertEquals("SELL", sale.form["transaction_type"])
        // 90 s on: past the protection's first minute, still inside the 120 s a sale that went is waited for.
        com.optionslab.app.testing.SecondRead.clockOn(90)
        runBlocking { Protections.tick() }
        assertEquals("its stop is back", 1, stops().size)
    }

    /** Deep review ("WHO CANCELLED A STOP"): a stop already cancelled when the square-off's own cancel went is not the app's. */
    @Test fun aStopAlreadyCancelledBeforeTheSquareOffsCancelIsNotMarkedTheApps() {
        val p = protect()
        kite.afterNext("/portfolio/positions") { kite.order(p.stopOrderId!!).status = "CANCELLED" }   // Boss, in Kite, meanwhile
        pass()
        assertFalse(Protections.takenOverForTest(p.stopOrderId!!))
        assertTrue("the target the square-off took out stays the app's", Protections.takenOverForTest(p.targetOrderId!!))
    }

    /** Deep review: a malformed row in Zerodha's positions reply never stops the square-off of what was read. */
    @Test fun aMalformedRowNeverStopsTheSquareOff() {
        // (Final review: a row with no symbol too - it never blocks an exit.)
        kite.extraNetRows = listOf(org.json.JSONObject().put("tradingsymbol", "NIFTY26SEP24600CE").put("exchange", "NFO").put("product", "NRML"), org.json.JSONObject().put("exchange", "NFO").put("product", "NRML").put("quantity", 75))
        protect()
        pass()
        assertEquals(listOf("SELL"), kite.placed.filter { it.form["tag"] == "iraalgoexpiry" }.map { it.form["transaction_type"] })
        assertEquals(0, kite.positions["NFO:$ce:NRML"]?.first)
    }

    @Test fun aSaleThatGoesLeavesNoStopBehind() {
        val p = protect()
        pass()
        assertEquals(listOf("SELL"), kite.placed.filter { it.form["tag"] == "iraalgoexpiry" }.map { it.form["transaction_type"] })
        assertFalse(kite.order(p.stopOrderId!!).working)
        // The sale filled: the position is flat, so nothing is put back (two agreeing reads end the protection).
        Market.testClock = MarketClockRule.clockAt(12, 5)
        runBlocking { Protections.tick() }
        Market.testClock = MarketClockRule.clockAt(12, 6)
        runBlocking { Protections.tick() }
        assertTrue(stops().isEmpty())
        assertTrue(runBlocking { Protections.active() }.isEmpty())
    }
}
