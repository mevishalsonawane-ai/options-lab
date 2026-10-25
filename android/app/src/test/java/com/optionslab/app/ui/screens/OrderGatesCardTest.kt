package com.optionslab.app.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.data.Market
import com.optionslab.app.data.OrderVerdicts
import com.optionslab.app.ui.Load
import com.optionslab.app.ui.OrderPlan
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.engine.Kite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The three gates on a real order (22 Oct, [OrderGatesCard] inside [PlanCard]): the checks and the fingerprint, "yes" typed for
 * this one order, the PIN; the send stays off until "yes" is typed when Boss's setting is on; an exit never asks it.
 */
@RunWith(AndroidJUnit4::class)
class OrderGatesCardTest {
    @get:org.junit.Rule val marketClock = com.optionslab.app.testing.MarketClockRule()
    @get:Rule val compose = createComposeRule()

    private val send = "Swipe to send to Zerodha  ›››"
    private val leg = Kite.Order("NIFTY26OCT24500PE", Kite.Side.BUY, 75, 75, "MIS", "LIMIT", 120.0)

    private fun plan(vararg legs: Kite.Order, exit: Boolean = false, stamped: Boolean = true): OrderPlan {
        val p = OrderPlan("Test order", null, legs.toList(), emptyMap(), legs.map { emptyList<String>() }, false, exit = exit)
        if (!stamped) return p
        val day = Market.today()
        return p.also { q -> q.verdicts = p.legs.indices.map { OrderVerdicts.stamp(p.fieldsNow(it, day), emptyList(), "test") } }
    }

    private var sent = 0
    private fun show(start: OrderPlan, yesRequired: Boolean) {
        var pl by mutableStateOf(start)
        compose.setContent { IraAlgoTheme("light") { PlanCard(pl, true, Load.Idle, onPrice = { i, x -> pl = pl.copy(legs = pl.legs.mapIndexed { j, o -> if (j == i) o.copy(price = x) else o }) },
            onSend = { sent++ }, onClose = {}, yesRequired = yesRequired) } }
    }

    @Test fun yesMustBeTypedBeforeTheSendOpens() {
        show(plan(leg), yesRequired = true)
        compose.onNodeWithTag("order-gates").assertExists()
        compose.onNodeWithTag("order-gate-0").assertTextContains("✓ Checks passed, fingerprint matches")
        compose.onNodeWithTag("order-gate-1").assertTextContains("✗ \"yes\" typed for this order (type yes below)")
        compose.onNodeWithTag("order-gate-2").assertTextContains("✗ Your PIN or fingerprint (asked when you send)")
        compose.onNodeWithText("Three gates: 1 of 3 gates open").assertExists()
        compose.onNodeWithText(send).assertIsNotEnabled()
        compose.onNodeWithTag("order-yes").performTextInput("y")
        compose.onNodeWithText(send).assertIsNotEnabled()
        compose.onNodeWithTag("order-yes").performTextInput("es")
        compose.waitForIdle()
        compose.onNodeWithTag("order-gate-1").assertTextContains("✓ \"yes\" typed for this order", substring = true)
        compose.onNodeWithText("Three gates: 2 of 3 gates open").assertExists()
        compose.onNodeWithText(send).assertIsEnabled()
    }

    @Test fun withTheSettingOffTheGateCountsAsOpenAndThePinStays() {
        show(plan(leg), yesRequired = false)
        compose.onNodeWithTag("order-gate-1").assertTextContains("✓ \"yes\" typed for this order (off in your settings)")
        compose.onNodeWithTag("order-gate-2").assertTextContains("✗ Your PIN or fingerprint", substring = true)
        assertTrue(compose.onAllNodesWithTag("order-yes").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText(send).assertIsEnabled()
    }

    @Test fun anOrderChangedAfterItsCheckShutsTheFirstGate() {
        // A price typed after the check: the review stamps it again in the app ([AppModel.setLegPrice]); here the stale verdict stays.
        val stale = plan(leg).let { p -> p.copy(legs = listOf(leg.copy(price = 121.0))).also { it.verdicts = p.verdicts } }
        show(stale, yesRequired = false)
        compose.onNodeWithTag("order-gate-0").assertTextContains("✗ Checks passed, fingerprint matches (the order changed after its check)")
        assertEquals(0, sent)
    }

    @Test fun anExitNeverAsksForYes() {
        show(plan(leg.copy(side = Kite.Side.SELL), exit = true), yesRequired = true)
        assertTrue(compose.onAllNodesWithTag("order-gates").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText(send).assertIsEnabled()
    }
}
