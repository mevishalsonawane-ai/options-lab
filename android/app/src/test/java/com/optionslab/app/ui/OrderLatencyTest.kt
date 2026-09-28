package com.optionslab.app.ui

import android.app.Application
import android.os.Looper
import com.optionslab.app.data.Broker
import com.optionslab.app.data.Paper
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.FakeUpstox
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.testing.TradeFixtures
import com.optionslab.engine.Kite
import com.optionslab.engine.Right
import com.optionslab.engine.sandbox.SandboxEvent
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.ConscryptMode
import java.time.LocalDate

/**
 * LATENCY BUDGETS for BUY / SELL, so a change that puts waiting back on the order path fails CI.
 *
 * Measured against the local fakes, so the network is (almost) free: what is left is the app's own
 * work between the owner's confirmation and the order reaching the broker - the gates, the device
 * check, the account guard, connection handling, disk and Keystore writes. The PIN/fingerprint and the
 * hold gesture happen before [AppModel.sendPlan] and are not part of this (they are human time).
 *
 *  - Live: [AppModel.sendPlan] (what the review calls after hold + PIN) to the POST /orders/regular
 *    arriving at the fake Kite, with warm (kept-alive) connections as after the review's own reads:
 *    median of 5 under [LIVE_BUDGET_MS]. Also the fill shown (sending = Done) under [FILL_BUDGET_MS].
 *  - Paper: [Paper.place] with the contract's quote already read (as the order sheet has it) to the
 *    fill: median under [PAPER_BUDGET_MS].
 *
 * The numbers are printed, so CI logs show the trend as well as the verdict.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class OrderLatencyTest : RobolectricTest() {
    companion object {
        const val LIVE_BUDGET_MS = 150L
        const val FILL_BUDGET_MS = 400L
        const val PAPER_BUDGET_MS = 50L
    }

    private val store = androidx.lifecycle.ViewModelStore()
    private var kite: FakeKite? = null
    private var upstox: FakeUpstox? = null

    @After fun down() {
        store.clear()
        com.optionslab.app.testing.BrokerArea.settle(1_000)
        kite?.close()
        upstox?.close()
    }

    private fun <T> await(what: String, timeoutMs: Long = 15_000, get: () -> T?): T {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            get()?.let { return it }
            Thread.sleep(1)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private fun median(xs: List<Long>) = xs.sorted()[xs.size / 2]

    private fun settledAccount(m: AppModel) {
        m.loadAccount(quiet = true)
        var steady = 0
        await("the account") {
            steady = if (m.account.value is Load.Done) steady + 1 else 0
            Thread.sleep(10)
            (steady >= 30).takeIf { it }
        }
    }

    @Test fun liveConfirmToOrderAndFillStayWithinBudget() {
        val k = FakeKite().also { kite = it }
        k.keepAlive = true
        k.login()
        // No entry cut-off (any hour on CI), and room for the runs in the daily caps.
        SecurePrefs.putAll(mapOf("g.cutoff" to -1, "g.trades" to 50))
        val expiry = LocalDate.now().plusDays(9)
        val sym = "NIFTY26OCT24500CE"
        k.instruments += FakeKite.Ins(12_345_678, sym, "NIFTY", expiry, 24_500.0, "CE", 75)
        k.quote("NFO:$sym", 100.0, 99.95, 100.05)
        val m = androidx.lifecycle.ViewModelProvider(store, androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory(context as Application))[AppModel::class.java]

        val toOrder = ArrayList<Long>()
        val toFill = ArrayList<Long>()
        repeat(6) { run ->
            // Each run starts flat, so the account guard judges the same order every time.
            k.flat()
            // The account the review gates against: loaded, and no reload (the one after a fill) still under way.
            settledAccount(m)
            m.planManual("NIFTY", expiry, 24_500.0, Right.CE, Kite.Side.BUY, 1, "NRML", null)
            val plan = await("the plan") { (m.plan.value as? Load.Done)?.value ?: (m.plan.value as? Load.Failed)?.let { throw AssertionError(it.why) } }
            assertTrue("refusals: ${plan.refusals}", plan.sendable)
            val before = k.placed.size
            val t0 = System.nanoTime()
            m.sendPlan()
            val post = await("the order") { k.placed.drop(before).firstOrNull() }
            await("the fill") { (m.sending.value as? Load.Done)?.value ?: (m.sending.value as? Load.Failed)?.let { throw AssertionError(it.why) } }
            val filled = System.nanoTime()
            // Run 0 warms the JVM and the connections; it is not counted.
            if (run > 0) { toOrder += (post.atNanos - t0) / 1_000_000; toFill += (filled - t0) / 1_000_000 }
            m.dismissPlan()
        }
        println("LATENCY live confirm->POST ms: $toOrder (median ${median(toOrder)}); confirm->fill shown ms: $toFill (median ${median(toFill)})")
        assertTrue("confirm -> order at Zerodha took ${median(toOrder)} ms (budget $LIVE_BUDGET_MS): $toOrder", median(toOrder) < LIVE_BUDGET_MS)
        assertTrue("confirm -> fill shown took ${median(toFill)} ms (budget $FILL_BUDGET_MS): $toFill", median(toFill) < FILL_BUDGET_MS)
    }

    @Test fun anOrderStateIsReadAgainWithinAFewHundredMs() = runBlocking {
        // Kite answers "OPEN" twice, then COMPLETE: the fill is seen in well under a second, not after 2 x 1 s.
        val k = FakeKite().also { kite = it }
        k.keepAlive = true
        k.login()
        k.nextPlace(FakeKite.Scenario(outcome = FakeKite.Outcome.FILL_AFTER_POLLS, polls = 3))
        val id = Broker.placeOrder(Kite.Order("NIFTY26OCT24500PE", Kite.Side.BUY, 75, 75, "NRML", "LIMIT", 120.0, 0.05, "NFO", "iratest"))
        val t0 = System.nanoTime()
        val f = Broker.awaitOrder(id, 10_000)
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("LATENCY awaitOrder, filled on the 3rd read: $ms ms")
        assertEquals("COMPLETE", f.status)
        assertTrue("three reads took $ms ms", ms < 1_000)
    }

    @Test fun paperPlaceToFillStaysWithinBudget() = runBlocking {
        TradeFixtures.paperSettings()
        val u = FakeUpstox().also { upstox = it }
        TradeFixtures.seed(context, u)
        val c = Paper.contractFor("NIFTY", TradeFixtures.nearExpiry, 24_500.0, Right.PE) ?: throw AssertionError("contract not listed")
        val q = Paper.quote(c) ?: throw AssertionError("no paper quote")
        val times = ArrayList<Long>()
        repeat(6) { run ->
            val t0 = System.nanoTime()
            val r = Paper.place(c, if (run % 2 == 0) "BUY" else "SELL", 1, "MARKET", "NRML", null, null, q)
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertTrue(r.message, r.ok)
            assertTrue("filled at once: ${r.message}", r.events.any { it is SandboxEvent.Fill })
            if (run > 0) times += ms
        }
        println("LATENCY paper place->fill ms: $times (median ${median(times)})")
        assertTrue("paper place -> fill took ${median(times)} ms (budget $PAPER_BUDGET_MS): $times", median(times) < PAPER_BUDGET_MS)
    }
}
