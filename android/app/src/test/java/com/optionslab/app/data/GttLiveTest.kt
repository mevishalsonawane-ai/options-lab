package com.optionslab.app.data

import android.app.Application
import com.optionslab.app.testing.BrokerArea
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.Load
import com.optionslab.engine.Kite
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

/**
 * GTT protection through the real [AppModel] and [Broker] against the fake Kite: pricing a GTT only
 * reads, placing sends exactly the reviewed OCO (or single) trigger, Paper mode sends nothing, a GTT
 * Kite would refuse is never sent, and deleting sends DELETE for that one trigger.
 * (The screen that asks for the PIN first is TradeScreen's; [AppModel.placeGtt] is what it calls after.)
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class GttLiveTest : RobolectricTest() {
    private lateinit var kite: FakeKite
    private val store = androidx.lifecycle.ViewModelStore()
    private val expiry = LocalDate.now().plusDays(9)
    private val sym = BrokerArea.symbol("NIFTY", expiry, 24_500.0, "CE")

    @Before fun up() {
        kite = FakeKite()
        BrokerArea.clearAlerts()
        BrokerArea.listNifty(kite, listOf(expiry))
        kite.quote("NFO:$sym", 100.0, 99.95, 100.05)
    }

    @After fun down() {
        store.clear()
        Thread.sleep(200)
        kite.close()
        assertFalse("Kite REST calls must all go to the fake", "api.kite.trade" in NetworkGuard.blocked)
    }

    private fun model(live: Boolean = true): AppModel {
        kite.login(live)
        return BrokerArea.model(context as Application, store)
    }

    private fun plan(m: AppModel, qty: Int, stop: Double?, target: Double?): AppModel.GttPlan {
        m.planGtt("NFO", sym, "NRML", qty, stop, target)
        return BrokerArea.await("the GTT plan") {
            (m.gttPlan.value as? Load.Done)?.value ?: (m.gttPlan.value as? Load.Failed)?.let { throw AssertionError(it.why) }
        }
    }

    @Test fun pricingAGttOnlyReads() {
        val m = model()
        val p = plan(m, 75, 90.0, 130.0)
        assertEquals("Protect $sym", p.title)
        val g = p.gtt!!
        assertEquals("two-leg", g.type)
        assertEquals(listOf(90.0, 130.0), g.triggers)
        assertEquals(100.0, g.lastPrice, 0.0)
        assertTrue("a long is closed by selling", g.orders.all { it.side == Kite.Side.SELL && it.quantity == 75 })
        assertTrue("pricing sends nothing: ${kite.writes}", kite.writes.isEmpty())
    }

    @Test fun placingSendsExactlyTheReviewedTwoLegGtt() {
        val m = model()
        val g = plan(m, 75, 90.0, 130.0).gtt!!
        m.placeGtt()
        BrokerArea.await("the GTT at Zerodha") { kite.gtts.values.singleOrNull() }
        val post = kite.writes.single()
        assertEquals("POST" to "/gtt/triggers", post.method to post.path)
        assertEquals("two-leg", post.form["type"])
        val cond = JSONObject(post.form.getValue("condition"))
        assertEquals(sym, cond.getString("tradingsymbol")); assertEquals("NFO", cond.getString("exchange"))
        assertEquals(listOf(90.0, 130.0), cond.getJSONArray("trigger_values").let { a -> (0 until a.length()).map { a.getDouble(it) } })
        val orders = JSONArray(post.form.getValue("orders"))
        assertEquals(2, orders.length())
        for (i in 0 until 2) {
            val o = orders.getJSONObject(i)
            assertEquals("SELL", o.getString("transaction_type")); assertEquals(75, o.getInt("quantity"))
            assertEquals("LIMIT", o.getString("order_type")); assertEquals("NRML", o.getString("product"))
            assertEquals(g.orders[i].price!!, o.getDouble("price"), 1e-9)
        }
        // Each leg fires a limit 5% beyond its trigger: it fills in a fast market without being a market order.
        assertEquals(85.5, orders.getJSONObject(0).getDouble("price"), 1e-9)
        assertEquals(123.5, orders.getJSONObject(1).getDouble("price"), 1e-9)
        BrokerArea.await("the placed message") { BrokerArea.alerted("GTT placed (#1001): Zerodha holds it even when the phone is off.").takeIf { it } }
        BrokerArea.await("the GTT list refreshed") { m.gtts.value.singleOrNull()?.takeIf { it.id == 1001L } }
        assertEquals("the plan closes once placed", Load.Idle, m.gttPlan.value)
    }

    @Test fun aShortIsProtectedByBuyingBackAboveAndBelow() {
        val m = model()
        val g = plan(m, -150, 130.0, 70.0).gtt!!
        assertEquals(listOf(70.0, 130.0), g.triggers)
        assertTrue(g.orders.all { it.side == Kite.Side.BUY && it.quantity == 150 })
        assertEquals("the lower trigger's order first", 73.5, g.orders[0].price!!, 1e-9)
        m.placeGtt()
        BrokerArea.await("the GTT") { kite.gtts.values.singleOrNull() }
        assertTrue(kite.writes.single().form.getValue("orders").contains("\"transaction_type\":\"BUY\""))
    }

    @Test fun aStopOnlyGttIsSingle() {
        val m = model()
        val g = plan(m, 75, 80.0, null).gtt!!
        assertEquals("single", g.type)
        m.placeGtt()
        BrokerArea.await("the GTT") { kite.gtts.values.singleOrNull() }
        assertEquals("single", kite.writes.single().form["type"])
    }

    @Test fun aGttKiteWouldRefuseIsExplainedAndNeverSent() {
        val m = model()
        // A stop above the last price of a long, and a target within 0.25% of it.
        val p = plan(m, 75, 101.0, 100.2)
        assertNull(p.gtt)
        assertEquals("the stop must be below the last price 100.00", p.why.first())
        assertTrue(p.why.joinToString(), p.why.any { it.contains("within 0.25%") })
        m.placeGtt()
        BrokerArea.settle()
        assertTrue(kite.writes.isEmpty())
        // Nothing to protect, or nothing given.
        assertEquals(listOf("give a stop, a target, or both"), plan(m, 75, null, null).why)
        assertEquals(listOf("nothing is open"), plan(m, 0, 90.0, null).why)
        assertTrue(kite.writes.isEmpty())
    }

    @Test fun paperModeNeverSendsAGtt() {
        val m = model(live = false)
        plan(m, 75, 90.0, 130.0)
        m.placeGtt()
        BrokerArea.settle()
        assertTrue(kite.writes.isEmpty())
        assertTrue(BrokerArea.alerted("A GTT is a real order: switch to Live with the badge at the top first."))
    }

    @Test fun noLastPriceFailsThePlan() {
        val m = model()
        kite.quotes.clear()
        m.planGtt("NFO", sym, "NRML", 75, 90.0, null)
        val why = BrokerArea.await("the refusal") { (m.gttPlan.value as? Load.Failed)?.why }
        assertEquals("no last price for $sym", why)
        m.dismissGtt()
        assertEquals(Load.Idle, m.gttPlan.value)
        assertTrue(kite.writes.isEmpty())
    }

    @Test fun aRefusalFromZerodhaIsSaidAndThePlanStays() {
        val m = model()
        plan(m, 75, 90.0, 130.0)
        kite.down += "/gtt/triggers"
        m.placeGtt()
        BrokerArea.await("the refusal") { BrokerArea.alerted("GTT not placed: Gateway timed out").takeIf { it } }
        assertTrue("the plan stays open to try again", m.gttPlan.value is Load.Done)
        assertTrue(kite.gtts.isEmpty())
    }

    @Test fun deletingSendsDeleteForThatTriggerOnly() {
        val m = model()
        runCatchingBlocking {
            Broker.placeGtt(Kite.Gtt("single", "NFO", sym, 100.0, listOf(80.0), listOf(Kite.Order(sym, Kite.Side.SELL, 75, 75, "NRML", "LIMIT", 76.0))))
            Broker.placeGtt(Kite.Gtt("single", "NFO", sym, 100.0, listOf(85.0), listOf(Kite.Order(sym, Kite.Side.SELL, 75, 75, "NRML", "LIMIT", 80.75))))
        }
        kite.requests.clear()
        m.deleteGtt(1001L)
        BrokerArea.await("the delete") { BrokerArea.alerted("GTT #1001 deleted.").takeIf { it } }
        assertEquals(listOf("DELETE /gtt/triggers/1001"), kite.writes.map { "${it.method} ${it.path}" })
        assertEquals(setOf(1002L), kite.gtts.keys)
        BrokerArea.await("the list refreshed") { m.gtts.value.singleOrNull()?.takeIf { it.id == 1002L } }
    }

    @Test fun deletingAGttThatIsGoneSaysSo() {
        val m = model()
        m.deleteGtt(4242L)
        BrokerArea.await("the refusal") { BrokerArea.alerted("Not deleted: Invalid trigger ID.").takeIf { it } }
    }

    private fun runCatchingBlocking(block: suspend () -> Unit) = kotlinx.coroutines.runBlocking { block() }
}
