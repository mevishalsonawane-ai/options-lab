package com.optionslab.app.data

import android.app.Application
import android.os.Looper
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.ui.Account
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.Load
import com.optionslab.ira.PnlCharges
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.ConscryptMode

/**
 * Zerodha's exact charges (its virtual contract note, POST /charges/orders) on the account's small line, through the
 * real AppModel and Broker against the fake Kite: "Charges ₹X" when Zerodha answers, the estimate from the day's trades
 * ("Charges ≈ ₹X (estimate)") when it does not. Asked about the COMPLETE orders only, and not again for the same ones.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class ExactChargesLiveTest : RobolectricTest() {
    private lateinit var kite: FakeKite
    private val sym = "NIFTY26OCT24500CE"

    @Before fun up() {
        // The GOLD build has no Zerodha.
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        kite = FakeKite()
        kite.login()
        kite.tradesFromFills = true
        // Two orders done today, one still open with nothing filled (not asked about).
        kite.orders["251005000000001"] = FakeKite.Order("251005000000001", sym, "NFO", "BUY", 150, "NRML", "LIMIT", 100.0, 0.0, "iraalgo",
            "COMPLETE", filled = 150, avg = 100.05)
        kite.orders["251005000000002"] = FakeKite.Order("251005000000002", sym, "NFO", "SELL", 150, "NRML", "MARKET", 0.0, 0.0, "iraalgo",
            "COMPLETE", filled = 150, avg = 120.4)
        kite.orders["251005000000003"] = FakeKite.Order("251005000000003", sym, "NFO", "BUY", 75, "NRML", "LIMIT", 90.0, 0.0, "iraalgo", "OPEN")
    }

    private val store = androidx.lifecycle.ViewModelStore()

    private fun model(): AppModel =
        androidx.lifecycle.ViewModelProvider(store, androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory(context as Application))[AppModel::class.java]

    @After fun down() {
        if (!::kite.isInitialized) return
        store.clear()
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

    private fun account(m: AppModel): Account? = (m.account.value as? Load.Done)?.value

    @Test fun zerodhasExactChargesAreShownWhenItAnswers() {
        val m = model()
        m.loadAccount(quiet = true)
        val a = await("the exact charges") { account(m)?.takeIf { it.exactCharges != null } }
        assertEquals("two complete orders at 31.25 each", 62.5, a.charges, 1e-9)
        assertFalse(a.chargesEstimate)
        assertEquals("Charges ₹63", PnlCharges.line(a.charges, a.chargesEstimate))

        // Asked once, about the COMPLETE orders only: what each executed, at its average price.
        val sent = JSONArray(kite.contractNotes.single().body)
        assertEquals(2, sent.length())
        val first = sent.getJSONObject(0)
        assertEquals("251005000000001", first.getString("order_id"))
        assertEquals("BUY", first.getString("transaction_type"))
        assertEquals("regular", first.getString("variety"))
        assertEquals(150, first.getInt("quantity"))
        assertEquals(100.05, first.getDouble("average_price"), 1e-9)
        assertEquals("251005000000002", sent.getJSONObject(1).getString("order_id"))
        assertTrue("an authenticated read", kite.contractNotes.single().auth != null)
        assertTrue("the contract note changes nothing at Zerodha", kite.writes.isEmpty())

        // Read again with the same orders done: the kept figure, Zerodha not asked again.
        m.loadAccount(quiet = true)
        com.optionslab.app.testing.BrokerArea.settle(800)
        assertEquals(62.5, account(m)!!.charges, 1e-9)
        assertFalse(account(m)!!.chargesEstimate)
        assertEquals(1, kite.contractNotes.size)
    }

    @Test fun theEstimateStaysWhenZerodhaDoesNotAnswer() {
        kite.down += "/charges/orders"
        val m = model()
        m.loadAccount(quiet = true)
        await("the contract note asked") { kite.contractNotes.takeIf { it.isNotEmpty() } }
        com.optionslab.app.testing.BrokerArea.settle(800)
        val a = await("the account") { account(m) }
        assertNull(a.exactCharges)
        assertTrue(a.chargesEstimate)
        assertEquals("the estimate from the day's trades", TradeBook.liveCharges(a.trades), a.charges, 1e-9)
        assertTrue("trades were read", a.trades.isNotEmpty())
        assertEquals("Charges ≈ ${PnlCharges.inr(a.charges)} (estimate)", PnlCharges.line(a.charges, a.chargesEstimate))

        // A failed ask is not repeated on every refresh (the same orders: not for a while).
        kite.down -= "/charges/orders"
        m.loadAccount(quiet = true)
        com.optionslab.app.testing.BrokerArea.settle(800)
        assertEquals(1, kite.contractNotes.size)
        assertTrue(account(m)!!.chargesEstimate)
    }
}
