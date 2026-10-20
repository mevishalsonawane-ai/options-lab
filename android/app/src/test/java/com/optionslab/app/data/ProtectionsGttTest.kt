package com.optionslab.app.data

import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode
import java.time.LocalDate

/**
 * 20 Oct, enhancement round 1 (idea 2): a carried (NRML) live protection's stop and target rest at Zerodha as a GTT while the
 * session's DAY orders are gone (after the close, overnight, before the open), so a dead phone misses nothing. The GTT is
 * deleted (confirmed) before the day's orders are placed again at the open, and whenever the protection ends: never two
 * resting exits for one position, never an exit left for a position no longer held.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class ProtectionsGttTest : RobolectricTest() {
    private lateinit var kite: FakeKite
    private val sym = "NIFTY26OCT24500CE"

    @Before fun up() {
        kite = FakeKite()
        kite.instruments += FakeKite.Ins(12_345_678, sym, "NIFTY", LocalDate.now().plusDays(20), 24_500.0, "CE", 75)
        kite.position(sym, 75, 100.0)
        kite.quote("NFO:$sym", 100.0, 99.95, 100.05)
        kite.login()
    }

    @After fun down() { Market.testClock = null; kite.close(); assertEquals(emptyList<String>(), NetworkGuard.blocked.toList()) }

    /** The clock at [h]:[m] IST on the [nth] trading day after today. */
    private fun session(h: Int, m: Int, nth: Int = 1) {
        var d = LocalDate.now(com.optionslab.engine.IST)
        repeat(nth) { d = d.plusDays(1); while (!Market.isTradingDay(d)) d = d.plusDays(1) }
        val at = d.atTime(h, m).atZone(com.optionslab.engine.IST).toInstant()
        Market.testClock = java.time.Clock.offset(java.time.Clock.system(com.optionslab.engine.IST), java.time.Duration.between(java.time.Instant.now(), at))
    }

    private fun protect(product: String = "NRML", stop: Double? = 90.0, target: Double? = 130.0) = runBlocking {
        Protections.protectLive(sym, "NFO", product, 75, 100.0, stop, null, target)
    }

    private fun one() = runBlocking { Protections.active() }.single()
    private fun tick() = runBlocking { Protections.tick() }
    private val gttPosts get() = kite.requests.filter { it.method == "POST" && it.path == "/gtt/triggers" }
    private val gttDeletes get() = kite.requests.filter { it.method == "DELETE" && it.path.startsWith("/gtt/triggers/") }

    /** The close: Zerodha cancels the DAY exits (the clock on the next trading day at 15:45, the orders still in its book). */
    private fun theClose() {
        val p = one()
        session(15, 45)
        listOfNotNull(p.stopOrderId, p.targetOrderId).forEach { kite.order(it).status = "CANCELLED" }
    }

    @Test fun atTheCloseTheStopAndTargetRestAsOneGttAndTheOpenReplacesItWithTheDayOrders() {
        protect()
        theClose()
        tick()
        val g = kite.gtts.values.single()
        assertEquals("two-leg", g.getString("type"))
        val trig = g.getJSONObject("condition").getJSONArray("trigger_values")
        assertEquals(listOf(90.0, 130.0), (0 until trig.length()).map { trig.getDouble(it) })
        val leg = g.getJSONArray("orders").getJSONObject(0)
        assertEquals("SELL", leg.getString("transaction_type")); assertEquals(75, leg.getInt("quantity")); assertEquals("NRML", leg.getString("product"))
        val item = one()
        assertEquals(g.getLong("id"), item.gttId)
        assertTrue(com.optionslab.app.work.Alerts.queue.value.any { it.text.contains("rest at Zerodha overnight as a GTT") })
        // Later looks that evening and before the open: nothing more sent.
        tick()
        kite.orders.clear()
        session(9, 0, nth = 2)
        kite.requests.clear()
        tick()
        assertTrue("nothing sent before the open", kite.requests.none { it.method != "GET" })
        // The open: the GTT out first (confirmed), then the day's stop and target - never both resting.
        session(9, 20, nth = 2)
        kite.requests.clear()
        tick()
        val writes = kite.requests.filter { it.method != "GET" }
        assertEquals("DELETE", writes.first().method)
        assertTrue(writes.first().path.endsWith("/${item.gttId}"))
        assertEquals(listOf("SL", "LIMIT"), kite.placed.map { it.form["order_type"] })
        assertTrue("the GTT is gone", kite.gtts.isEmpty())
        val now = one()
        assertNull(now.gttId)
        assertEquals(90.0, now.stop!!, 0.0)
    }

    @Test fun anIntradayProtectionNeverGetsAGtt() {
        kite.position(sym, 0, 0.0); kite.position(sym, 75, 100.0, product = "MIS")
        protect(product = "MIS")
        theClose()
        tick()
        assertTrue(gttPosts.isEmpty())
        assertTrue(kite.gtts.isEmpty())
    }

    @Test fun aStopOnlyProtectionRestsASingleGtt() {
        protect(target = null)
        theClose()
        tick()
        val g = kite.gtts.values.single()
        assertEquals("single", g.getString("type"))
        assertEquals(90.0, g.getJSONObject("condition").getJSONArray("trigger_values").getDouble(0), 0.0)
    }

    /** The GTT fired overnight (the price opened through the stop) and its sell filled: the protection ends, nothing more is placed. */
    @Test fun aGttThatFiredAndFilledEndsTheProtectionWithNothingPlacedAgain() {
        protect()
        theClose()
        tick()
        val id = one().gttId!!
        kite.orders.clear()
        kite.quote("NFO:$sym", 86.0, 85.95, 86.05)        // opened through the stop, at or over the GTT's limit (90 less 5%)
        session(9, 16, nth = 2)
        val oid = kite.triggerGtt(id)
        assertEquals("COMPLETE", kite.order(oid).status)
        kite.flat()
        kite.requests.clear()
        tick()
        assertTrue("no stop placed for a position no longer held", kite.placed.isEmpty())
        assertTrue(runBlocking { Protections.active() }.isEmpty())
    }

    /** Fired at the open but not filled yet (its limit resting): that sell is counted, so the day's stop is not doubled. */
    @Test fun aGttFiredButNotFilledIsCountedAndNeverDoubled() {
        protect()
        theClose()
        tick()
        val id = one().gttId!!
        kite.orders.clear()
        kite.quote("NFO:$sym", 60.0, 59.95, 60.05)        // under the GTT's limit (90 less 5%): its sell rests unfilled
        session(9, 16, nth = 2)
        val oid = kite.triggerGtt(id)
        assertEquals("OPEN", kite.order(oid).status)
        kite.requests.clear()
        tick()
        assertTrue("the fired GTT's sell already covers the position", kite.placed.isEmpty())
        // Said plainly: the sell rests under the market.
        assertTrue(com.optionslab.app.work.Alerts.queue.value.any { it.text.contains("its overnight GTT fired") })
    }

    /** Closed by hand overnight (Boss sold it after hours): the GTT is deleted before it can sell what is no longer held. */
    @Test fun aPositionClosedOvernightDeletesItsGtt() {
        protect()
        theClose()
        tick()
        val id = one().gttId!!
        kite.orders.clear()
        kite.flat()
        session(8, 30, nth = 2)
        tick()
        assertTrue(gttDeletes.any { it.path.endsWith("/$id") })
        assertTrue(kite.gtts.isEmpty())
        assertTrue(runBlocking { Protections.active() }.isEmpty())
    }

    @Test fun removingTheProtectionDeletesItsGtt() {
        protect()
        theClose()
        tick()
        val p = one()
        assertEquals("Protection removed from $sym.", runBlocking { Protections.remove(p.id) })
        assertTrue(kite.gtts.isEmpty())
    }

    /** A delete Zerodha does not confirm (the GTT still listed): the day's orders wait, the protection stays, and it is asked again. */
    @Test fun aDeleteNotConfirmedHoldsTheDayOrdersBack() {
        protect()
        theClose()
        tick()
        val id = one().gttId!!
        val saved = JSONObject(kite.gtts.getValue(id).toString())
        kite.orders.clear()
        session(9, 20, nth = 2)
        kite.afterNext("/gtt/triggers/$id") { kite.gtts[id] = saved }       // the delete "went" but the GTT is still active
        kite.requests.clear()
        tick()
        assertTrue("no day orders beside an active GTT", kite.placed.isEmpty())
        assertEquals(id, one().gttId)
        // The next look: deleted and confirmed, then the day's orders.
        kite.requests.clear()
        tick()
        assertTrue(kite.gtts.isEmpty())
        assertEquals(listOf("SL", "LIMIT"), kite.placed.map { it.form["order_type"] })
    }

    /** Another exit already covers it overnight (an arm's own GTT on the same contract): no second GTT. */
    @Test fun anotherGttCoveringThePositionMeansNoSecondOne() {
        protect()
        runBlocking {
            Broker.placeGtt(requireNotNull(com.optionslab.engine.Kite.protect(com.optionslab.engine.Kite.Spec("NFO", sym, 0L, 75, 0.05),
                "NRML", 75, 100.0, 80.0, null).first))
        }
        theClose()
        kite.requests.clear()
        tick()
        assertTrue(gttPosts.isEmpty())
        assertNull(one().gttId)
    }

    /** At the close the price is already under the stop: Zerodha would refuse the GTT, so it is said plainly (and not retried forever). */
    @Test fun aStopAlreadyThroughThePriceIsSaidPlainly() {
        protect(target = null)
        theClose()
        kite.quote("NFO:$sym", 85.0, 84.95, 85.05)
        repeat(5) { tick() }
        assertTrue(kite.gtts.isEmpty())
        val said = com.optionslab.app.work.Alerts.queue.value.map { it.text }
        assertTrue(said.toString(), said.any { it.contains("could not rest at Zerodha overnight as a GTT") })
        assertNotNull("the protection is kept for the open", runBlocking { Protections.active() }.singleOrNull())
    }

    /** Saved and read back (a restart overnight keeps the GTT's id, so the open still deletes it first). */
    @Test fun theGttIdSurvivesARestart() {
        protect()
        theClose()
        tick()
        val id = one().gttId!!
        Protections.forgetMemoryForTest()          // as after a restart: the file read again
        assertEquals(id, one().gttId)
    }

    /** The watch's evening look runs only while a carried live protection still waits for its overnight GTT. */
    @Test fun theEveningLookHintFollowsTheGtt() {
        protect()
        assertTrue("carried, no GTT yet", Protections.overnightHint)
        theClose()
        tick()
        assertTrue(one().gttId != null)
        assertTrue("its GTT rests: nothing more to wait for", !Protections.overnightHint)
    }

    @Test fun anIntradayProtectionNeverKeepsTheEveningLook() {
        kite.position(sym, 0, 0.0); kite.position(sym, 75, 100.0, product = "MIS")
        protect(product = "MIS")
        assertTrue(!Protections.overnightHint)
    }

    /** Refused three times (the price through the stop): the evening look for it ends (bounded), the protection stays for the open. */
    @Test fun repeatedRefusalsEndTheEveningLook() {
        protect(target = null)
        theClose()
        kite.quote("NFO:$sym", 85.0, 84.95, 85.05)
        repeat(3) { tick() }
        assertTrue(!Protections.overnightHint)
        assertNotNull(runBlocking { Protections.active() }.singleOrNull())
    }
}
