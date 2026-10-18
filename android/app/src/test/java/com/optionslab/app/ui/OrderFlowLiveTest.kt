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

    @Test fun jarvissOrderThatTheAppRefusedAtTheSendIsSaidAsNotSentNeverAnEarlierSendsFills() {
        // 18 Oct: a send the app refused at once (real orders not allowed, a check that failed again) left the send's state as it
        // was, and Jarvis answered from it: an earlier order's fills as "Sent to Zerodha". Nothing had gone.
        SecurePrefs.put("k.allow", false)
        val model = model()
        com.optionslab.app.ira.IraActions.attach(model)
        model.sending.value = Load.Done(listOf(com.optionslab.app.data.Broker.Fill("250926000000999", "COMPLETE", 99.0, 75, "")))
        val said = java.util.concurrent.atomic.AtomicReference<String?>()
        val t = com.optionslab.app.ira.IraOrders.Ticket("NIFTY", expiry, 24_500.0, Right.CE, 1, true, 75)
        Thread { said.set(kotlinx.coroutines.runBlocking { com.optionslab.app.ira.IraActions.trade(t, live = true) }) }.start()
        val answer = await("Jarvis's answer", 20_000) { said.get() }
        assertTrue(answer, answer.startsWith("Not sent:"))
        assertFalse(answer, answer.contains("Sent to Zerodha"))
        assertTrue("nothing went to Zerodha", kite.placed.isEmpty())
    }

    // ---- 18 Oct (Boss): exits of real Zerodha positions in Paper mode, with the PIN ---------------------------------------

    private fun paperMode() { SecurePrefs.put("k.mode", "sandbox"); SecurePrefs.put("k.allow", false) }

    private fun position(qty: Int) = kotlinx.coroutines.runBlocking { com.optionslab.app.data.Broker.positionBook() }.net.single { it.symbol == sym && it.qty == qty }

    @Test fun inPaperModeAnExitWithThePinSendsExactlyTheHeldQuantity() {
        paperMode()
        for ((held, side) in listOf(75 to "SELL", -150 to "BUY")) {
            kite.orders.clear(); kite.requests.clear()
            kite.position(sym, held, 100.0)
            val model = model()
            model.planSquareOff(position(held), "Close (Paper mode)", direct = true)
            await("the exit priced") { model.plan.value as? Load.Done }
            model.confirmDirect()                               // what the PIN's Confirm calls, after a right PIN
            val post = await("the exit at Zerodha") { kite.placed.firstOrNull() }
            assertEquals(side, post.form["transaction_type"])
            assertEquals("exactly what is held", "${kotlin.math.abs(held)}", post.form["quantity"])
            await("filled") { model.sending.value as? Load.Done }
            assertEquals("one order", 1, kite.placed.size)
            assertFalse("still Paper mode", model.settings.value.live)
            model.dismissPlan()
            store.clear()
        }
    }

    @Test fun inPaperModeAnExitWithoutThePinOrCancelledSendsNothing() {
        paperMode()
        kite.position(sym, 75, 100.0)
        val model = model()
        // Jarvis's (or any) send without the PIN: refused, and says the PIN is needed.
        model.planSquareOff(position(75), "Square off")
        await("the exit priced") { model.plan.value as? Load.Done }
        val why = model.sendPlan()
        assertEquals(com.optionslab.app.ui.PAPER_EXIT_PIN, why)
        // Cancelled at the PIN (or a wrong PIN: Confirm is never reached): closing the dialog sends nothing.
        model.planSquareOff(position(75), "Square off", direct = true)
        await("the exit priced") { model.plan.value as? Load.Done }
        model.dismissPlan()
        shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(300)
        assertTrue("nothing went to Zerodha: ${kite.placed}", kite.placed.isEmpty())
    }

    @Test fun inPaperModeAnEntryNeverGoesToZerodhaEvenWithThePin() {
        paperMode()
        val model = model()
        model.planManual("NIFTY", expiry, 24_500.0, Right.CE, Kite.Side.BUY, 1, "NRML", null, direct = true)
        model.confirmDirect()
        val failed = await("refused") { model.sending.value as? Load.Failed }
        assertTrue(failed.why, failed.why.contains("Paper mode"))
        // The review's own send with the PIN: refused the same way.
        model.planManual("NIFTY", expiry, 24_500.0, Right.CE, Kite.Side.BUY, 1, "NRML", null)
        await("the plan") { model.plan.value as? Load.Done }
        assertEquals(com.optionslab.app.ui.PAPER_NO_ENTRY, model.sendPlan(reauthed = true))
        shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(300)
        assertTrue("nothing went to Zerodha", kite.placed.isEmpty())
    }

    private fun keyguard() = shadowOf(context.getSystemService(android.app.KeyguardManager::class.java))

    /**
     * 18 Oct (Boss): Jarvis's close (or the emergency exit) of a Zerodha position in Paper mode opens the PIN prompt in the app
     * and never sends without it. Before, the app refused it at once and Jarvis answered from the send's old state (an earlier
     * order's fills as "Sent to Zerodha"): that old state is set here, and never answers.
     */
    @Test fun jarvissCloseInPaperModeOpensThePinPromptAndSendsOnlyAfterIt() {
        paperMode()
        kite.position(sym, 75, 100.0)
        val model = model()
        com.optionslab.app.ira.IraActions.attach(model)
        model.sending.value = Load.Done(listOf(com.optionslab.app.data.Broker.Fill("250926000000999", "COMPLETE", 99.0, 75, "")))
        keyguard().setKeyguardLocked(false)
        run {
            val said = java.util.concurrent.atomic.AtomicReference<String?>()
            Thread { said.set(kotlinx.coroutines.runBlocking { com.optionslab.app.ira.IraActions.closeLiveSymbol(sym) }) }.start()
            val answer = await("Jarvis's answer", 20_000) { said.get() }
            assertTrue(answer, answer.startsWith("Enter your PIN on the screen"))
            assertFalse("never an earlier send's fills: $answer", answer.contains("Sent to Zerodha"))
            assertTrue("the PIN prompt is open in the app", model.direct.value)
            await("the exit priced") { model.plan.value as? Load.Done }
            shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(300)
            assertTrue("never sent without the PIN", kite.placed.isEmpty())
            model.confirmDirect()
            val post = await("the exit at Zerodha") { kite.placed.firstOrNull() }
            assertEquals("SELL", post.form["transaction_type"]); assertEquals("75", post.form["quantity"])
        }
    }

    @Test fun jarvissCloseInPaperModeOnALockedPhoneOpensNothingAndSendsNothing() {
        paperMode()
        kite.position(sym, 75, 100.0)
        val model = model()
        com.optionslab.app.ira.IraActions.attach(model)
        keyguard().setKeyguardLocked(true)
        try {
            val said = java.util.concurrent.atomic.AtomicReference<String?>()
            Thread { said.set(kotlinx.coroutines.runBlocking { com.optionslab.app.ira.IraActions.closeLiveSymbol(sym) }) }.start()
            val answer = await("Jarvis's answer", 20_000) { said.get() }
            assertTrue(answer, answer.startsWith("Unlock the phone"))
            assertFalse("no figure on a locked phone: $answer", Regex("\\d").containsMatchIn(answer))
            assertFalse(model.direct.value)
            assertTrue(kite.placed.isEmpty())
        } finally { keyguard().setKeyguardLocked(false) }
    }

    // ---- 18 Oct (Boss): the live manual order goes straight after the PIN ------------------------------------------------

    @Test fun aLiveDirectOrderGoesAfterThePinWithEveryCheckAndNoReview() {
        val model = model()
        val t0 = System.nanoTime()
        model.planManual("NIFTY", expiry, 24_500.0, Right.CE, Kite.Side.BUY, 1, "NRML", null, direct = true)
        assertTrue("the PIN is asked at once, while the order is priced", model.direct.value)
        model.confirmDirect()                                   // the PIN typed while it was priced
        val post = await("the order at Zerodha") { kite.placed.firstOrNull() }
        println("LIVEFLOW direct live buy: tap -> POST ${(post.atNanos - t0) / 1_000_000} ms (PIN typed at once; taps: Buy, PIN, Confirm)")
        assertEquals("BUY", post.form["transaction_type"]); assertEquals("75", post.form["quantity"])
        await("filled") { model.sending.value as? Load.Done }
        assertEquals(1, kite.placed.size)
    }

    @Test fun aLiveDirectOrderTheChecksRefuseIsSaidAndNothingGoes() {
        SecurePrefs.put("g.kill", true)
        val model = model()
        model.planManual("NIFTY", expiry, 24_500.0, Right.CE, Kite.Side.BUY, 1, "NRML", null, direct = true)
        model.confirmDirect()
        val failed = await("refused") { model.sending.value as? Load.Failed }
        assertTrue(failed.why, failed.why.startsWith("Not sent:"))
        shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(300)
        assertTrue(kite.placed.isEmpty())
    }

    // ---- 18 Oct (Boss): the paper-only period blocks every new live entry; exits still go -------------------------------

    @Test fun thePaperOnlyPeriodRefusesALiveManualEntryButItsExitStillGoes() {
        val today = com.optionslab.app.data.Market.today()
        com.optionslab.app.data.PaperOnly.resetForTest(com.optionslab.ira.PaperOnlyPeriod.Period(today.plusDays(8), "10 days on paper", today.minusDays(1)))
        val model = model()
        model.planManual("NIFTY", expiry, 24_500.0, Right.CE, Kite.Side.BUY, 1, "NRML", null, direct = true)
        model.confirmDirect()
        val failed = await("refused") { model.sending.value as? Load.Failed }
        assertTrue(failed.why, failed.why.contains("paper-only period") && failed.why.contains("Exits, stops and square-offs still go"))
        assertTrue("no entry went, even in Live", kite.placed.isEmpty())
        model.dismissPlan()
        kite.position(sym, 75, 100.0)
        model.planSquareOff(position(75), "Square off", direct = true)
        await("the exit priced") { model.plan.value as? Load.Done }
        model.confirmDirect()
        val post = await("the exit at Zerodha") { kite.placed.firstOrNull() }
        assertEquals("SELL", post.form["transaction_type"]); assertEquals("75", post.form["quantity"])
    }
}

