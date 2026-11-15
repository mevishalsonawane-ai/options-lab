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
        assertTrue(Alerts.posted.any { it.text.contains("450 of the position (one slice of its stops) is not protected by a stop") &&
            it.text.contains("set a stop for these 450 yourself") })
        assertEquals(2, active().size)
    }

    // ---- 15 Nov, review: a sibling counts only for what it really has resting -----------------------------------------------------

    /** H1: the second slice's stop fills first: the first slice keeps its whole stop, and nothing is said bare. */
    @Test fun aSiblingsStopFilledFirstNeverMakesTheOtherGiveUpItsStop() {
        kite.position(sym, 3600, 100.0)
        kite.login()
        protect(3600)
        val (a, b) = active()
        kite.fill(b.stopOrderId!!, 89.0)                                    // 1,800 held now, covered by A alone
        minutesLater()
        tick()
        assertEquals("TRIGGER PENDING", kite.order(a.stopOrderId!!).status)
        assertEquals(1800, kite.order(a.stopOrderId!!).qty)
        assertEquals("OPEN", kite.order(a.targetOrderId!!).status)
        assertEquals("CANCELLED", kite.order(b.targetOrderId!!).status)
        assertEquals(listOf(a.id), active().map { it.id })
        assertEquals(1800, active().single().qty)
        assertFalse(Alerts.posted.any { it.text.contains("no longer protected") })
        // And it stays so on later looks.
        tick()
        assertEquals("TRIGGER PENDING", kite.order(a.stopOrderId!!).status)
        assertEquals(1800, kite.order(a.stopOrderId!!).qty)
    }

    /** H1, part filled: a sibling's stop half filled counts nothing; the slices come to what is held together, never less. */
    @Test fun aSiblingsStopPartFilledNeverShrinksTheOther() {
        kite.position(sym, 3600, 100.0)
        kite.login()
        protect(3600)
        val (a, b) = active()
        kite.fillPart(b.stopOrderId!!, 900, 89.0)                           // 2,700 held
        minutesLater()
        tick()
        assertEquals(1800, kite.order(a.stopOrderId!!).qty)
        assertEquals("TRIGGER PENDING", kite.order(a.stopOrderId!!).status)
        assertEquals("its target down to what it still covers", 900, kite.order(b.targetOrderId!!).qty)
        assertEquals(2, active().size)
    }

    /** H1, 2,250: the small slice's target fills: the big slice keeps all 1,800 (nothing left bare). */
    @Test fun aSiblingsTargetFilledNeverShrinksTheOther() {
        kite.position(sym, 2250, 100.0)
        kite.login()
        protect(2250)
        val (a, b) = active()
        kite.fill(b.targetOrderId!!, 130.0)                                 // 1,800 held
        minutesLater()
        tick()
        assertEquals(1800, kite.order(a.stopOrderId!!).qty)
        assertEquals(1800, kite.order(a.targetOrderId!!).qty)
        assertEquals("CANCELLED", kite.order(b.stopOrderId!!).status)
        assertEquals(listOf(a.id), active().map { it.id })
        tick()
        assertEquals(1800, kite.order(a.stopOrderId!!).qty)
    }

    /** M2: a sibling left with a target only covers nothing: the slice with the stop keeps it, and the target-only one ends. */
    @Test fun aSiblingWithOnlyATargetNeverTakesTheStopsPlace() {
        kite.position(sym, 2250, 100.0)
        kite.login()
        protect(2250)
        val (a, b) = active()
        kite.reject(b.stopOrderId!!)
        minutesLater()
        tick()                                                               // B: its stop gone, its target kept
        kite.position(sym, 1800, 100.0)
        tick()
        assertEquals("A keeps its whole stop", 1800, kite.order(a.stopOrderId!!).qty)
        kite.position(sym, 450, 100.0)
        tick()
        assertEquals("A comes down to what is held", 450, kite.order(a.stopOrderId!!).qty)
        assertEquals("TRIGGER PENDING", kite.order(a.stopOrderId!!).status)
        assertTrue(a.id in active().map { it.id })
        // B (a target only) has nothing left to cover beside A's stop: taken out, never A.
        tick()
        assertEquals(listOf(a.id), active().map { it.id })
        assertEquals("CANCELLED", kite.order(b.targetOrderId!!).status)
    }

    /** L3: a reduce Zerodha refuses leaves the protection with the quantity its orders really have; it is tried again. */
    @Test fun aRefusedReduceKeepsTheQuantityTheOrdersHave() {
        kite.position(sym, 150, 100.0)
        kite.login()
        protect(150)
        val p = active().single()
        kite.position(sym, 75, 100.0)
        kite.down += "/orders/regular/${p.stopOrderId}"
        kite.down += "/orders/regular/${p.targetOrderId}"
        minutesLater()
        tick()
        assertEquals(150, active().single().qty)
        assertEquals(150, kite.order(p.stopOrderId!!).qty)
        kite.down.clear()
        tick()
        assertEquals(75, kite.order(p.stopOrderId!!).qty)
        assertEquals(75, active().single().qty)
    }

    /** L4: an automatic protection in slices whose one stop the exchange refused: no promise that the app puts it back. */
    @Test fun anAutomaticSlicesRefusedStopIsSaidForBossToSet() {
        kite.position(sym, 2250, 100.0)
        kite.login()
        runBlocking { Protections.protectLive(sym, "NFO", "NRML", 2250, 100.0, 90.0, null, 130.0, auto = true) }
        val (_, b) = active()
        kite.reject(b.stopOrderId!!)
        minutesLater()
        tick()
        assertFalse(Alerts.posted.any { it.text.contains("places its stop and target again") })
        assertTrue(Alerts.posted.any { it.kind == Alerts.Kind.ERROR && it.text.contains("set a stop for these 450 yourself") })
        assertEquals("its target kept (the others keep the position covered)", "OPEN", kite.order(b.targetOrderId!!).status)
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

    /**
     * N3: at the open the first slice rests its stop again, and the order book read for the second does not show it yet: the new
     * stop is counted whole (it was set seconds ago), so the second never covers the same units again.
     */
    @Test fun aSiblingsStopJustPlacedButNotYetInTheBookIsCounted() {
        kite.position(sym, 3600, 100.0)
        kite.login()
        protect(3600, target = null)
        kite.orders.clear()                                                 // yesterday's DAY orders are gone
        kite.position(sym, 1800, 100.0)                                     // half sold overnight
        nextSession(9, 20)
        kite.requests.clear()
        kite.trailReads = 10                                                // the next order placed trails the order book's reads
        tick()
        assertEquals("never more than is held", 1800, kite.placed.filter { it.form["order_type"] == "SL" }.sumOf { it.form.getValue("quantity").toInt() })
        // Later looks, the book catching up: still never more than is held, and the second slice ends as not needed.
        repeat(8) { tick() }
        assertEquals("never more than is held", 1800, kite.placed.filter { it.form["order_type"] == "SL" }.sumOf { it.form.getValue("quantity").toInt() })
        assertEquals(1800, active().sumOf { it.qty })
        assertEquals(1, active().size)
    }

    /**
     * R3: the first slice's new stop is refused within a second, while the order book does not show it yet: the second slice never
     * ends on its account, and covers the position once the book shows the refusal.
     */
    @Test fun aSiblingRefusedBeforeTheBookShowsItNeverEndsTheOther() {
        kite.position(sym, 3600, 100.0)
        kite.login()
        protect(3600, target = null)
        kite.orders.clear()
        kite.position(sym, 1800, 100.0)
        nextSession(9, 20)
        kite.requests.clear()
        kite.trailReads = 4
        tick()
        val first = kite.placed.single()
        assertEquals("1800", first.form["quantity"])
        assertEquals("the second waits a look; never ended", 2, active().size)
        val aStop = kite.orders.keys.last()
        kite.reject(aStop)                                                  // refused at the exchange a moment later
        repeat(8) { tick() }
        val sls = kite.placed.filter { it.form["order_type"] == "SL" }
        assertEquals("the second slice covers it once the refusal shows", 2, sls.size)
        assertEquals("1800", sls.last().form["quantity"])
        assertTrue(kite.orders.values.any { it.working && it.qty == 1800 && it.type == "SL" })
    }

    /**
     * N4: a sibling not counted as resting (its target part filled) is counted as busy by the larger of its stop and target, never
     * their sum: the slice put back after Boss's exit gets what is left to cover, not nothing.
     */
    @Test fun aNonRestingSiblingIsBusyByItsLargerExitNotTheSum() {
        kite.position(sym, 3600, 100.0)
        kite.login()
        protect(3600)
        val (a, b) = active()
        kite.fillPart(b.targetOrderId!!, 600, 130.0)                        // 3,000 held; B's stop 1,800 and target 1,200 still work
        val ids = listOfNotNull(a.stopOrderId, a.targetOrderId)
        Protections.noteTakenOver(ids)
        runBlocking { ids.forEach { Broker.cancel(it) } }                   // A's taken over for an exit that did not go
        minutesLater()
        kite.requests.clear()
        tick()
        assertEquals("1,800 of B's stop plus 1,200 again: all 3,000 held", listOf("1200", "1200"), kite.placed.map { it.form["quantity"] })
        val now = active().first { it.id != b.id }
        assertEquals(1200, now.qty)
        assertEquals("TRIGGER PENDING", kite.order(now.stopOrderId!!).status)
    }

    /** R2: an id from a clock once ahead is neither carried on to new protections nor "its first minute" for ever. */
    @Test fun aFutureIdIsNotCarriedOnAndNeverBlocksTheWatch() {
        kite.position(sym, 75, 100.0)
        kite.login()
        val ahead = MarketClockRule.pinnedDay().plusDays(1).atTime(12, 0).atZone(com.optionslab.engine.IST).toInstant()
        Market.testClock = java.time.Clock.offset(java.time.Clock.system(com.optionslab.engine.IST), java.time.Duration.between(java.time.Instant.now(), ahead))
        protect(75)                                                          // set while the phone's clock was a day ahead
        val future = active().single().id
        Market.testClock = MarketClockRule.clockAt(12, 5)                   // the clock right again; the app restarted
        Protections.forgetMemoryForTest()
        // Its position closed: the watch reads it at once (never "its first minute" for a day) and ends it.
        kite.flat()
        tick()
        assertTrue(active().isEmpty())
        // A new protection's id is today's, not a day ahead.
        kite.position(sym, 75, 100.0)
        protect(75)
        val id = active().single().id
        assertTrue("not carried on from the future id", id < future)
        assertTrue(id <= Market.now().toInstant().toEpochMilli() + 5_000)
    }
}
