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

    private val store = androidx.lifecycle.ViewModelStore()

    /** A real AppModel, owned by a store the test clears (its coroutines end with the test). */
    private fun model(): AppModel {
        val m = androidx.lifecycle.ViewModelProvider(store, androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory(context as Application))[AppModel::class.java]
        // The review checks the order against the account (limits, exposure): read it first, as the Zerodha page does.
        m.loadAccount(quiet = true)
        await("the account") { m.account.value as? Load.Done }
        return m
    }

    @After fun down() {
        store.clear()
        // Account reads already under way when the model is cleared still finish (they swallow the cancellation):
        // let them end against this fake, running the main looper where they resume, before it closes.
        com.optionslab.app.testing.BrokerArea.settle(1_500)
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
        val model = model()
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

    @Test fun aFailureAfterEveryLegFilledSaysFilledNeverNotSent() {
        // 18 Oct, review: the ledger's write after the fills failed (here: its temp file cannot be opened) and the review said
        // "Not sent" for an order that had filled at Zerodha - Boss could send it again. Filled stays filled, with the failure said.
        val model = model()
        model.planManual("NIFTY", expiry, 24_500.0, Right.CE, Kite.Side.BUY, 1, "NRML", null)
        val plan = await("the plan") { (model.plan.value as? Load.Done)?.value ?: (model.plan.value as? Load.Failed)?.let { throw AssertionError(it.why) } }
        model.plan.value = Load.Done(plan.copy(session = LocalDate.now()))
        java.io.File(context.noBackupFilesDir, "ledger.vault.tmp").apply { mkdirs(); java.io.File(this, "busy").writeText("x") }
        model.sendPlan()
        val fills = await("the send") {
            (model.sending.value as? Load.Done)?.value ?: (model.sending.value as? Load.Failed)?.let { throw AssertionError(it.why) }
        }
        assertEquals("COMPLETE", fills.single().status)
        assertEquals("exactly one order", 1, kite.placed.size)
        val said = await("the message") { model.message.value?.takeIf { it.startsWith("Filled:") } }
        assertTrue(said, said.contains("ledger") && said.contains("failed"))
        assertTrue("still shown as filled: ${model.sending.value}", model.sending.value is Load.Done)
    }

    @Test fun aLaterLegRefusedAfterAFillSaysTheFilledLegNeverJustNotSent() {
        // 18 Oct, review: leg 1 filled, leg 2 refused by Zerodha (a definite error): the review says leg 1 is open.
        val model = model()
        model.planManual("NIFTY", expiry, 24_500.0, Right.CE, Kite.Side.BUY, 1, "NRML", null)
        val plan = await("the plan") { (model.plan.value as? Load.Done)?.value ?: (model.plan.value as? Load.Failed)?.let { throw AssertionError(it.why) } }
        val leg = plan.legs.single()
        model.plan.value = Load.Done(plan.copy(legs = listOf(leg, leg), refusals = listOf(emptyList(), emptyList())))
        kite.nextPlace(FakeKite.Scenario())
        kite.nextPlace(FakeKite.Scenario(reply = FakeKite.Reply.INPUT_EXCEPTION, message = "Invalid order"))
        model.sendPlan()
        val why = await("the refusal") {
            (model.plan.value as? Load.Done)?.value?.refusals?.flatten()?.takeIf { it.isNotEmpty() }?.let { throw AssertionError("refused before sending: $it") }
            (model.sending.value as? Load.Done)?.let { throw AssertionError("both legs went: ${it.value}") }
            (model.sending.value as? Load.Failed)?.why
        }
        assertTrue(why, why.startsWith("Not all sent:") && why.contains("1 earlier leg filled at Zerodha"))
    }

    @Test fun paperModeNeverSends() {
        SecurePrefs.put("k.mode", "sandbox"); SecurePrefs.put("k.allow", false)
        val model = model()
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
        val model = model()
        model.planManual("NIFTY", expiry, 24_500.0, Right.CE, Kite.Side.BUY, 1, "NRML", 100.05)
        await("the plan") { model.plan.value as? Load.Done }
        model.sendPlan()
        val why = await("the refusal") { (model.sending.value as? Load.Failed)?.why }
        assertTrue(why, why.startsWith("Leg 1 rejected") && why.contains("RMS:Margin Exceeds"))
        assertEquals(1, kite.placed.size)
    }

    // ---- 18 Oct (live end-to-end tests): a reply lost on the order sheet's send ---------------------------------------------

    private fun planned(model: AppModel): com.optionslab.app.ui.OrderPlan {
        model.planManual("NIFTY", expiry, 24_500.0, Right.CE, Kite.Side.BUY, 1, "NRML", null)
        val plan = await("the plan") { (model.plan.value as? Load.Done)?.value ?: (model.plan.value as? Load.Failed)?.let { throw AssertionError(it.why) } }
        assertTrue("refusals: ${plan.refusals}", plan.sendable)
        return plan
    }

    private fun sent(model: AppModel): Load<List<com.optionslab.app.data.Broker.Fill>> =
        await("the send") { model.sending.value.takeIf { it is Load.Done || it is Load.Failed } }

    @Test fun aLostReplyOnTheSheetsSendIsFoundInTheBookAndShownFilledOnce() {
        val model = model()
        planned(model)
        kite.nextPlace(reply = FakeKite.Reply.DROP_AFTER_ACCEPT)
        val t0 = System.nanoTime()
        model.sendPlan()
        val done = sent(model)
        println("E2E manual sheet, reply lost: send->fill shown ${(System.nanoTime() - t0) / 1_000_000} ms (the order book read before anything is said)")
        assertTrue("shown as filled: $done", done is Load.Done)
        assertEquals("COMPLETE", (done as Load.Done).value.single().status)
        assertEquals("the order went once", 1, kite.placed.size)
        assertEquals(kite.orders.keys.single(), done.value.single().orderId)
    }

    @Test fun aLostReplyWithNoNewOrderNeverTakesAnEarlierIdenticalOrderForIt() {
        // 18 Oct: the second of two identical hand orders was refused by Kite's gateway (nothing placed); the book was read
        // for a lost reply and the FIRST order (same contract, side, size and tag, a minute old) was taken for it: "Filled".
        SecurePrefs.putAll(mapOf("g.trades" to 50))
        val model = model()
        planned(model)
        model.sendPlan()
        val first = (sent(model) as? Load.Done)?.value?.single()?.orderId ?: throw AssertionError("the first order did not fill: ${model.sending.value}")
        model.dismissPlan()
        kite.flat()
        model.loadAccount(quiet = true)
        await("the account again") { model.account.value as? Load.Done }
        planned(model)
        kite.nextPlace(reply = FakeKite.Reply.NETWORK_EXCEPTION)
        model.sendPlan()
        val why = (sent(model) as? Load.Failed)?.why ?: throw AssertionError("an earlier order was taken for this one: ${model.sending.value}")
        assertTrue(why, why.startsWith("Not sent:") && why.contains("No matching order was found"))
        assertEquals("only the first order exists", listOf(first), kite.orders.keys.toList())
    }

    @Test fun jarvissCloseOfAZerodhaPositionThatTheAppRefusedIsSaidAsNotSent() {
        // 18 Oct: in Paper mode (Boss's paper-only days, Zerodha still logged in) the order sheet refuses the close at once, and
        // Jarvis answered from the send's old state: an earlier order's fills as "Sent to Zerodha", or after a minute "Sent;
        // Zerodha has not confirmed yet". Nothing had gone.
        SecurePrefs.put("k.mode", "sandbox"); SecurePrefs.put("k.allow", false)
        kite.position(sym, 75, 100.0)
        val model = model()
        com.optionslab.app.ira.IraActions.attach(model)
        model.sending.value = Load.Done(listOf(com.optionslab.app.data.Broker.Fill("250926000000999", "COMPLETE", 99.0, 75, "")))
        val said = java.util.concurrent.atomic.AtomicReference<String?>()
        Thread { said.set(kotlinx.coroutines.runBlocking { com.optionslab.app.ira.IraActions.closeLiveSymbol(sym) }) }.start()
        val answer = await("Jarvis's answer", 20_000) { said.get() }
        assertTrue(answer, answer.startsWith("Not sent:") && answer.contains("Paper mode"))
        assertTrue("nothing went to Zerodha", kite.placed.isEmpty())
    }
}

