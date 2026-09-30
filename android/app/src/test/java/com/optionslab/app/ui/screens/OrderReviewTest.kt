package com.optionslab.app.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.data.Broker
import com.optionslab.app.ui.Load
import com.optionslab.app.ui.OrderPlan
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.app.work.Alerts
import com.optionslab.engine.Kite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real-order review card ([PlanCard]) with a hand-made plan: no AppModel, no Zerodha.
 * The send control must be off whenever a limit price box does not hold the price that would go.
 */
@RunWith(AndroidJUnit4::class)
class OrderReviewTest {
    @get:Rule val compose = createComposeRule()

    private val send = "Swipe to send to Zerodha  ›››"
    private val mismatchNote = "A limit price box does not hold a valid price; fix it before sending."
    private val leg = Kite.Order("NIFTY26OCT24500PE", Kite.Side.BUY, 75, 75, "NRML", "LIMIT", 120.0)

    private fun planOf(vararg legs: Kite.Order, margin: Broker.Margin? = null) = OrderPlan(
        title = "Test order", session = null, legs = legs.toList(), quotes = emptyMap(),
        refusals = legs.map { emptyList<String>() }, holdToSettlement = false, margin = margin)

    private var sent = 0

    /** Renders the card; price edits go back into the plan as AppModel.setLegPrice does. */
    private fun show(start: OrderPlan, allowed: Boolean = true): () -> OrderPlan {
        var plan by mutableStateOf(start)
        compose.setContent {
            IraAlgoTheme("light") {
                PlanCard(plan, allowed, Load.Idle,
                    onPrice = { i, x -> plan = plan.copy(legs = plan.legs.mapIndexed { j, o -> if (j == i) o.copy(price = x) else o }) },
                    onSend = { sent++ }, onClose = {})
            }
        }
        return { plan }
    }

    private fun gone(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()

    @Test fun aValidLimitPriceCanBeSent() {
        show(planOf(leg))
        compose.onNodeWithText("120.00").assertExists()
        assertTrue(gone(mismatchNote))
        compose.onNodeWithText(send).assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, sent)
    }

    @Test fun anEmptyPriceBoxDisablesSendingUntilFixed() {
        val plan = show(planOf(leg))
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNodeWithText(mismatchNote).assertExists()
        compose.onNodeWithText(send).assertIsNotEnabled()

        compose.onNode(hasSetTextAction()).performTextInput("121.5")
        compose.waitForIdle()
        assertEquals(121.5, plan().legs[0].price!!, 0.0)
        assertTrue(gone(mismatchNote))
        compose.onNodeWithText(send).assertIsEnabled()
    }

    @Test fun aZeroPriceNeverReachesThePlan() {
        val plan = show(planOf(leg))
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNode(hasSetTextAction()).performTextInput("0")
        compose.waitForIdle()
        assertEquals("the old price stays in the plan", 120.0, plan().legs[0].price!!, 0.0)
        compose.onNodeWithText(mismatchNote).assertExists()
        compose.onNodeWithText(send).assertIsNotEnabled()
    }

    @Test fun marketOrdersHaveNoPriceToMismatch() {
        show(planOf(leg.copy(orderType = "MARKET", price = null)))
        assertTrue(gone(mismatchNote))
        compose.onNodeWithText(send).assertIsEnabled()
    }

    @Test fun paperModeExplainsAndKeepsSendOff() {
        show(planOf(leg), allowed = false)
        compose.onNodeWithText("This is Paper mode. To send real orders, tap the PAPER TRADING badge at the top and switch to Live.").assertExists()
        compose.onNodeWithText(send).assertIsNotEnabled()
    }

    @Test fun shortOfMarginIsNotSendable() {
        show(planOf(leg, margin = Broker.Margin(required = 50_000.0, initial = 50_000.0, available = 49_000.0, charges = 0.0)))
        compose.onNodeWithText(send).assertIsNotEnabled()
        compose.waitUntil(5_000) { (Alerts.queue.value + Alerts.posted).any { it.text == "Short of margin by Rs 1,000: not sendable." } }
    }
}
