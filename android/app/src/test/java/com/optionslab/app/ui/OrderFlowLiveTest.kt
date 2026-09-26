package com.optionslab.app.ui

import android.app.Application
import android.os.Looper
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.Kite
import com.optionslab.engine.Right
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.ConscryptMode
import java.time.LocalDate

/**
 * The manual live order, end to end through the real AppModel and Broker against the fake Kite:
 * review (reads only - nothing is sent), then send (exactly one order, the reviewed one).
 * [AppModel.sendPlan] is what the review dialog calls after hold-to-send and the PIN/fingerprint.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class OrderFlowLiveTest : RobolectricTest() {
    private lateinit var kite: FakeKite
    private val expiry = LocalDate.now().plusDays(9)
    private val sym = "NIFTY26OCT24500CE"

    @Before fun up() {
        kite = FakeKite()
        kite.login()
        // No entry cut-off, so the review is the same at any hour the tests run.
        SecurePrefs.put("g.cutoff", -1)
        kite.instruments += FakeKite.Ins(12_345_678, sym, "NIFTY", expiry, 24_500.0, "CE", 75)
        kite.quote("NFO:$sym", 100.0, 99.95, 100.05)
    }

    @After fun down() {
        kite.close()
        assertFalse("Kite REST calls must all go to the fake", "api.kite.trade" in NetworkGuard.blocked)
    }

    private fun <T> await(what: String, timeoutMs: Long = 15_000, get: () -> T?): T {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            get()?.let { return it }
            Thread.sleep(20)
        }
        throw AssertionError("timed out waiting for $what")
    }

    @Test fun reviewSendsNothingThenSendPlacesExactlyTheReviewedOrder() {
        val model = AppModel(context as Application)
        model.planManual("NIFTY", expiry, 24_500.0, Right.CE, Kite.Side.BUY, 1, "NRML", null)
        val plan = await("the plan") { (model.plan.value as? Load.Done)?.value ?: (model.plan.value as? Load.Failed)?.let { throw AssertionError(it.why) } }
        assertEquals(sym, plan.legs.single().tradingSymbol)
        assertEquals("a buy is priced at the offer", 100.05, plan.legs.single().price!!, 1e-9)
        assertEquals(40_000.0, plan.margin!!.required, 0.0)
        assertTrue("the review must not send anything: ${kite.writes}", kite.writes.isEmpty())
        assertTrue("refusals: ${plan.refusals}", plan.sendable)

        model.sendPlan()
        val fills = await("the send") {
            (model.sending.value as? Load.Done)?.value ?: (model.sending.value as? Load.Failed)?.let { throw AssertionError(it.why) }
        }
        assertEquals("COMPLETE", fills.single().status)
        val sent = kite.placed.single()
        assertEquals(mapOf("tradingsymbol" to sym, "exchange" to "NFO", "transaction_type" to "BUY", "order_type" to "LIMIT",
            "quantity" to "75", "product" to "NRML", "price" to "100.05", "validity" to "DAY", "tag" to "iraalgo"), sent.form)
    }

    @Test fun paperModeNeverSends() {
        SecurePrefs.put("k.mode", "sandbox"); SecurePrefs.put("k.allow", false)
        val model = AppModel(context as Application)
        model.planManual("NIFTY", expiry, 24_500.0, Right.CE, Kite.Side.BUY, 1, "NRML", 100.0)
        await("the plan") { model.plan.value as? Load.Done }
        model.sendPlan()
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(300)
        assertTrue(kite.placed.isEmpty())
        assertEquals(Load.Idle, model.sending.value)
    }

    @Test fun aRejectedOrderIsShownAndNotRetried() {
        kite.nextPlace(outcome = FakeKite.Outcome.REJECT, message = "RMS:Margin Exceeds, Required:1,20,000.00, Available:40,000.00")
        val model = AppModel(context as Application)
        model.planManual("NIFTY", expiry, 24_500.0, Right.CE, Kite.Side.BUY, 1, "NRML", 100.05)
        await("the plan") { model.plan.value as? Load.Done }
        model.sendPlan()
        val why = await("the refusal") { (model.sending.value as? Load.Failed)?.why }
        assertTrue(why, why.startsWith("Leg 1 rejected") && why.contains("RMS:Margin Exceeds"))
        assertEquals(1, kite.placed.size)
    }
}
