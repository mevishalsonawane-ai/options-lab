package com.optionslab.app.ui

import android.app.Application
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.data.Broker
import com.optionslab.app.data.SquareOffExit
import com.optionslab.app.security.PinLock
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.BrokerArea
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.ui.screens.OrderReviewDialog
import com.optionslab.app.ui.theme.IraAlgoTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.ConscryptMode

/**
 * 23 Nov 2026 (B6 review, fifth round): Boss's exit from the review dialog when only part of it can go safely. The part offered as a new exit
 * to review was a dead end on the screen; now, from the swipe and the PIN, the safe part goes under that same confirmation, the dialog says
 * what went, why the rest did not, and "close it in Kite" - and the send ends where he can close the dialog or plan the exit again.
 */
@RunWith(AndroidJUnit4::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class BossPartExitScreenTest {
    @get:Rule val marketClock = com.optionslab.app.testing.MarketClockRule()
    @get:Rule val watchdog = com.optionslab.app.testing.Watchdog()
    @get:Rule val compose = createComposeRule()
    private lateinit var kite: FakeKite
    private val store = androidx.lifecycle.ViewModelStore()
    private val expiry = com.optionslab.app.data.Market.today().plusDays(9)
    private val ce = BrokerArea.symbol("NIFTY", expiry, 24_500.0, "CE")
    private val send = "Swipe to send to Zerodha  ›››"

    @Before fun up() {
        kite = FakeKite()
        PinLock.setPin(BrokerArea.PIN.toCharArray())
        kite.login()
        BrokerArea.clearAlerts()
        SecurePrefs.put("g.cutoff", -1)
        BrokerArea.listNifty(kite, listOf(expiry))
        kite.quote("NFO:$ce", 100.0, 99.95, 100.05)
        kite.position(ce, 150, 90.0)
        // A book's own exit sale of 75 on it has not settled yet (lost, or on its way): only 75 of the 150 can go safely.
        SquareOffExit.lostPendingProviders["test"] = { s, _ -> if (s == ce) 75 else 0 }
    }

    @After fun down() {
        SquareOffExit.lostPendingProviders.remove("test")
        store.clear()
        BrokerArea.settle(1_500)
        SquareOffExit.resetForTest(gap = 0L)
        kite.close()
        assertFalse("api.kite.trade" in NetworkGuard.blocked)
        NetworkGuard.reset()
    }

    private fun until(what: String, timeoutMs: Long = 40_000, cond: () -> Boolean) = try {
        compose.waitUntil(timeoutMs) {
            shadowOf(Looper.getMainLooper()).idle()
            if (!compose.mainClock.autoAdvance) compose.mainClock.advanceTimeByFrame()
            cond()
        }
    } catch (e: Throwable) { throw AssertionError("timed out waiting for $what", e) }

    private fun frames(n: Int = 12) = repeat(n) { compose.mainClock.advanceTimeByFrame() }

    private fun shown(text: String, substring: Boolean = false) =
        compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    private fun hold(): SemanticsNodeInteraction =
        compose.onNodeWithText(send).performScrollTo().assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick).also { frames() }

    private fun enterPin(p: String) {
        compose.onNode(hasSetTextAction() and hasText("PIN")).performTextInput(p)
        compose.onNodeWithText("Confirm").performClick()
    }

    private fun position() = runBlocking { Broker.positions() }.first { it.symbol == ce }

    private fun sells() = kite.placed.filter { it.form["transaction_type"] == "SELL" }

    @Test fun thePartGoesFromTheSwipeIsSaidAndTheDialogCanCloseOrPlanAgain() {
        val m = BrokerArea.model(androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>(), store)
        m.loadAccount(quiet = true)
        BrokerArea.await("the account") { m.account.value as? Load.Done }
        m.planSquareOff(position())
        BrokerArea.await("the exit plan") { (m.plan.value as? Load.Done)?.value }
        compose.setContent { IraAlgoTheme("light") { OrderReviewDialog(m) } }
        until("the review") { shown(send) }
        assertTrue("the review sends nothing", kite.placed.isEmpty())

        hold()
        until("the PIN prompt") { shown("Confirm it is you") }
        enterPin(BrokerArea.PIN)
        until("the part sent") { m.sending.value is Load.Failed }
        val why = (m.sending.value as Load.Failed).why
        assertTrue(why, why.startsWith("Sent 75 of 150 on $ce; the other 75 may still be selling by "))
        assertTrue(why, why.endsWith(". If it must go now, close it in Kite."))
        until("said on the screen") { shown("Sent 75 of 150 on $ce", substring = true) }
        assertEquals("the safe part only, once", listOf("75"), sells().map { it.form["quantity"] })
        assertEquals("what may still be selling is left", 75, kite.positions.getValue("NFO:$ce:NRML").first)
        assertEquals("the review shows what went", listOf(75), (m.plan.value as Load.Done).value.legs.map { it.quantity })
        assertFalse("no swipe to send it again", shown(send))
        assertTrue("the dialog can be closed", shown("Close"))

        // Planning the exit again starts afresh: the swipe is back, for what is held now.
        m.planSquareOff(position())
        until("the new review") { shown(send) }
        assertEquals(Load.Idle, m.sending.value)
        assertEquals(listOf(75), (m.plan.value as Load.Done).value.legs.map { it.quantity })
        assertEquals("nothing more sent", 1, sells().size)

        // And Close closes it.
        compose.onNodeWithText("Close").performScrollTo().performClick()
        until("closed") { m.plan.value == Load.Idle }
        assertEquals(1, sells().size)
    }
}
