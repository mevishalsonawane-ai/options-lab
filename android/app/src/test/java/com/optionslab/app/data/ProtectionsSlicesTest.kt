package com.optionslab.app.data

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
 * 15 Nov (Boss's diagnostics: a large manual position got no stop - its one stop order was above the exchange's freeze quantity,
 * which the exchange refuses): above the freeze quantity (1,800 NIFTY) a protection's stop and target rest in slices, one protection
 * per slice. The slices never cover more than is held together (a part sold shrinks or ends them), one slice's orders never count
 * as "another exit" for the next, a refused slice keeps the slices already resting and says the rest loudly, and a lost reply never
 * adopts an earlier slice's order. At or under the freeze quantity: one order, as before.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class ProtectionsSlicesTest : RobolectricTest() {
    private lateinit var kite: FakeKite
    private val sym = "NIFTY26OCT24500CE"

    @Before fun up() {
        kite = FakeKite()
        kite.instruments += FakeKite.Ins(12_345_678, sym, "NIFTY", Market.today().plusDays(20), 24_500.0, "CE", 75)
        kite.quote("NFO:$sym", 100.0, 99.95, 100.05)
        Alerts.forgetPosted()
        com.optionslab.app.testing.Background.clearAlerts()
        DayOco.resetForTest()
    }

    @After fun down() { Market.testClock = null; kite.close(); assertEquals(emptyList<String>(), NetworkGuard.blocked.toList()) }

    private fun protect(qty: Int, stop: Double? = 90.0, target: Double? = 130.0): String = runBlocking {
        Protections.protectLive(sym, "NFO", "NRML", qty, 100.0, stop, null, target)
    }

    private fun active() = runBlocking { Protections.active() }
    private fun tick() = runBlocking { Protections.tick() }

    /** Past every slice's first minute (the positions read trails a fresh fill then), the same day. */
    private fun minutesLater() { Market.testClock = MarketClockRule.clockAt(12, 5) }

    /** The clock at [h]:[m] IST on the next trading day (still ticking). */
    private fun nextSession(h: Int, m: Int) {
        var d = MarketClockRule.pinnedDay().plusDays(1)
        while (!Market.isTradingDay(d)) d = d.plusDays(1)
        val at = d.atTime(h, m).atZone(com.optionslab.engine.IST).toInstant()
        Market.testClock = java.time.Clock.offset(java.time.Clock.system(com.optionslab.engine.IST), java.time.Duration.between(java.time.Instant.now(), at))
    }

    @Test fun exactlyAtTheFreezeQuantityItIsOneStopAndOneTarget() {
        kite.position(sym, 1800, 100.0)
        kite.login()
        val msg = protect(1800)
        assertTrue(msg, msg.startsWith("Protected $sym at Zerodha: "))
        assertEquals(listOf("1800", "1800"), kite.placed.map { it.form["quantity"] })
        assertEquals(1800, active().single().qty)
    }

    @Test fun aQuantityAboveTheFreezeRestsInSlicesEachItsOwnProtection() {
        kite.position(sym, 2250, 100.0)
        kite.login()
        val msg = protect(2250)
        assertTrue(msg, msg.startsWith("Protected $sym at Zerodha in 2 slices of at most 1800"))
        assertEquals(listOf("SL", "LIMIT", "SL", "LIMIT"), kite.placed.map { it.form["order_type"] })
        assertEquals(listOf("1800", "1800", "450", "450"), kite.placed.map { it.form["quantity"] })
        assertTrue(kite.placed.all { it.form["transaction_type"] == "SELL" && it.form["tag"] == "iraprotect" })
        val items = active()
        assertEquals(listOf(1800, 450), items.map { it.qty })
        assertEquals("each slice its own id", 2, items.map { it.id }.distinct().size)
        assertEquals("four distinct orders", 4, items.flatMap { listOfNotNull(it.stopOrderId, it.targetOrderId) }.distinct().size)
        assertTrue(items.all { it.stop == 90.0 && it.target == 130.0 })
    }

    @Test fun justOverTheFreezeQuantityTheLastSliceIsOneLot() {
        kite.position(sym, 1875, 100.0)
        kite.login()
        protect(1875, target = null)
        assertEquals(listOf("1800", "75"), kite.placed.map { it.form["quantity"] })
        assertEquals(listOf(1800, 75), active().map { it.qty })
    }

    @Test fun aShortAboveTheFreezeIsCoveredInBuySlices() {
        kite.position(sym, -2250, 100.0)
        kite.login()
        protect(-2250, stop = 110.0, target = 80.0)
        assertTrue(kite.placed.all { it.form["transaction_type"] == "BUY" })
        assertEquals(listOf(-1800, -450), active().map { it.qty })
    }

    /** A refused slice: the slices already resting stay, and the part left with no stop is said loudly. */
    @Test fun aRejectedSliceKeepsTheSlicesAlreadyRestingAndSaysTheRestLoudly() {
        kite.position(sym, 2250, 100.0)
        kite.login()
        kite.nextPlace(); kite.nextPlace()                                   // the first slice's stop and target
        kite.nextPlace(reply = Reply.INPUT_EXCEPTION, message = "RMS: blocked")
        val msg = protect(2250)
        assertTrue(msg, msg.startsWith("Partly protected $sym"))
        assertTrue(msg, msg.contains("for 1800 of 2250 only") && msg.contains("the other 450 is NOT protected"))
        val item = active().single()
        assertEquals(1800, item.qty)
        assertEquals("TRIGGER PENDING", kite.order(item.stopOrderId!!).status)
        assertTrue(Alerts.posted.any { it.kind == Alerts.Kind.ERROR && it.text.contains("only 1800 of 2250 is protected") && it.text.contains("NO stop") })
    }

    @Test fun theFirstSliceRefusedIsNotProtectedAtAllAsBefore() {
        kite.position(sym, 2250, 100.0)
        kite.login()
        kite.nextPlace(reply = Reply.INPUT_EXCEPTION, message = "RMS: blocked")
        val msg = protect(2250)
        assertTrue(msg, msg.startsWith("Not protected:") && msg.contains("RMS: blocked"))
        assertTrue(active().isEmpty())
    }

    /** A target refused on a slice keeps that slice's stop (as for one order); the next slices still go. */
    @Test fun aRefusedTargetOnOneSliceKeepsItsStop() {
        kite.position(sym, 2250, 100.0)
        kite.login()
        kite.nextPlace()
        kite.nextPlace(reply = Reply.INPUT_EXCEPTION, message = "Price exceeds the circuit limit.")
        val msg = protect(2250)
        assertTrue(msg, msg.startsWith("Protected") && msg.contains("The target was not placed on every slice"))
        val items = active()
        assertEquals(listOf(1800, 450), items.map { it.qty })
        assertEquals(null, items[0].targetOrderId); assertEquals(null, items[0].target)
        assertTrue(items[1].targetOrderId != null)
    }

    /** Two slices of the same size: a lost reply on the second is found in the book, never taken for the first's order. */
    @Test fun aLostReplyOnALaterSliceNeverAdoptsAnEarlierSlicesOrder() {
        kite.position(sym, 3600, 100.0)
        kite.login()
        kite.nextPlace(); kite.nextPlace()
        kite.nextPlace(reply = Reply.DROP_AFTER_ACCEPT)
        val msg = protect(3600)
        assertTrue(msg, msg.startsWith("Protected"))
        assertEquals("two stops and two targets, none twice", 4, kite.orders.size)
        val ids = active().flatMap { listOfNotNull(it.stopOrderId, it.targetOrderId) }
        assertEquals(4, ids.distinct().size)
        assertEquals(kite.orders.keys.toSet(), ids.toSet())
    }

    /** One slice's stop filled: only its own target is cancelled, the other slice keeps watching, and no false alarm is raised. */
    @Test fun oneSlicesStopFilledCancelsOnlyItsOwnTarget() {
        kite.position(sym, 2250, 100.0)
        kite.login()
        protect(2250)
        val (a, b) = active()
        kite.fill(a.stopOrderId!!, 89.0)
        tick()
        assertEquals("CANCELLED", kite.order(a.targetOrderId!!).status)
        assertEquals("TRIGGER PENDING", kite.order(b.stopOrderId!!).status)
        assertEquals("OPEN", kite.order(b.targetOrderId!!).status)
        assertEquals(listOf(b.id), active().map { it.id })
        assertFalse("the other slice still covers what is left", Alerts.posted.any { it.text.contains("no longer protected") })
    }

    /** Part sold by hand: the slices come down to what is held together (never two stops for the same units). */
    @Test fun aPartSoldByHandShrinksTheSlicesToWhatIsHeld() {
        kite.position(sym, 2250, 100.0)
        kite.login()
        protect(2250)
        val (a, b) = active()
        kite.position(sym, 1050, 100.0)
        minutesLater()
        tick()
        assertEquals(600, kite.order(a.stopOrderId!!).qty); assertEquals(600, kite.order(a.targetOrderId!!).qty)
        assertEquals(450, kite.order(b.stopOrderId!!).qty); assertEquals(450, kite.order(b.targetOrderId!!).qty)
        assertEquals(listOf(600, 450), active().map { it.qty })
        // Less again: the first slice has nothing left to cover and ends (its orders cancelled); the other covers all that is held.
        kite.position(sym, 300, 100.0)
        tick()
        assertEquals("CANCELLED", kite.order(a.stopOrderId!!).status); assertEquals("CANCELLED", kite.order(a.targetOrderId!!).status)
        assertEquals(300, kite.order(b.stopOrderId!!).qty)
        assertEquals(listOf(b.id), active().map { it.id })
        val ended = runBlocking { Protections.endedSince(0L) }.single { it.id == a.id }
        assertEquals(com.optionslab.ira.ManualCoverRule.SLICE_DONE, ended.note)
        // Closed: the last slice ends too, its orders cancelled.
        kite.flat()
        tick()
        assertTrue(active().isEmpty())
        assertEquals("CANCELLED", kite.order(b.stopOrderId!!).status)
    }

    /** A carried sliced position: placed again at the open in slices; one slice's new orders never count as "another exit". */
    @Test fun aCarriedSlicedPositionIsPlacedAgainInSlicesAtTheOpen() {
        kite.position(sym, 2250, 100.0)
        kite.login()
        protect(2250)
        kite.orders.clear()                                                 // yesterday's DAY orders are gone
        nextSession(9, 20)
        kite.requests.clear()
        tick()
        assertEquals(listOf("SL", "LIMIT", "SL", "LIMIT"), kite.placed.map { it.form["order_type"] })
        assertEquals(listOf("1800", "1800", "450", "450"), kite.placed.map { it.form["quantity"] })
        assertEquals(listOf(1800, 450), active().map { it.qty })
        // The next look finds them working: nothing more sent.
        kite.requests.clear()
        tick()
        assertTrue(kite.placed.isEmpty())
    }

    /** Held less overnight: placed again at the open for what is held in total, never more. */
    @Test fun aCarriedSlicedPositionPartSoldIsPlacedAgainForWhatIsHeldOnly() {
        kite.position(sym, 2250, 100.0)
        kite.login()
        protect(2250)
        kite.orders.clear()
        kite.position(sym, 1200, 100.0)
        nextSession(9, 20)
        kite.requests.clear()
        tick()
        assertEquals("never more than is held", 1200, kite.placed.filter { it.form["order_type"] == "SL" }.sumOf { it.form.getValue("quantity").toInt() })
        assertEquals(1200, active().sumOf { it.qty })
    }

    /** A rejected slice's stop at the exchange later: said loudly for that slice's units; the other slice still rests. */
    @Test fun aSlicesStopRejectedAtTheExchangeIsSaidForItsUnits() {
        kite.position(sym, 2250, 100.0)
        kite.login()
        protect(2250)
        val (_, b) = active()
        kite.reject(b.stopOrderId!!)
        minutesLater()
        tick()
        assertTrue(Alerts.posted.any { it.text.contains("450 of the position (one slice of its stops) is not protected by a stop") })
        assertEquals(2, active().size)
    }

    /** Removing the position's protection from Trade (Boss's square-off) removes every slice. */
    @Test fun removingTheSymbolRemovesEverySlice() {
        kite.position(sym, 2250, 100.0)
        kite.login()
        protect(2250)
        runBlocking { Protections.removeSymbol(true, "NFO", sym) }
        assertTrue(active().isEmpty())
        assertTrue(kite.orders.values.none { it.working })
    }

    /** A new protection replaces every slice of the old one (their orders confirmed gone first). */
    @Test fun aNewProtectionReplacesEverySlice() {
        kite.position(sym, 2250, 100.0)
        kite.login()
        protect(2250)
        val old = active()
        protect(2250, stop = 95.0, target = 125.0)
        old.flatMap { listOfNotNull(it.stopOrderId, it.targetOrderId) }.forEach { assertEquals("CANCELLED", kite.order(it).status) }
        assertEquals(listOf(95.0, 95.0), active().map { it.stop })
    }
}
