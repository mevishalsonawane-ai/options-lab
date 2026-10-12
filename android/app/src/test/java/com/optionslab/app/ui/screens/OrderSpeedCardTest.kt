package com.optionslab.app.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.optionslab.app.ui.components.expandTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.data.OrderTiming
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.ira.OrderSpeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The order-speed card (Settings → Zerodha, 9 Oct) from sample figures: each step, the warnings, the relay's advice. */
@RunWith(AndroidJUnit4::class)
@org.robolectric.annotation.Config(qualifiers = "w411dp-h2400dp")
class OrderSpeedCardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun sample(): OrderTiming.Card {
        val b = OrderSpeed.Book("2026-10-09")
        listOf(40L, 60L, 50L).forEach { b.add(OrderSpeed.Step.SENT_ACK, true, it) }
        listOf(15L, 30L).forEach { b.add(OrderSpeed.Step.SIGNAL_DECISION, true, it) }
        b.add(OrderSpeed.Step.SENT_ACK, false, 3)
        listOf(180L, 200L, 170L).forEach { b.add(OrderSpeed.Step.RELAY_PING, true, it) }
        b.add(OrderSpeed.Step.DIRECT_PING, true, 25)
        b.add(OrderSpeed.Step.PRICE_AGE, true, 2_600)
        val relay = OrderSpeed.median(b.all(OrderSpeed.Step.RELAY_PING))
        return OrderTiming.Card(OrderSpeed.lines(b, fillsOnStream = 2, fillsKnown = 3), OrderSpeed.warnings(b), relay)
    }

    private fun show(card: OrderTiming.Card?, relayOn: Boolean = true, region: String? = null, sync: String? = null, steps: List<String> = emptyList()) =
        compose.setContent {
            IraAlgoTheme("light") {
                Column(Modifier.verticalScroll(rememberScrollState())) { OrderSpeedContent(card, relayOn, region, stepLines = steps, syncLine = sync) }
            }
        }

    /** Tap "Show details" (Boss, 12 Oct: the step timings are folded by default). */
    private fun openDetails() {
        compose.onNodeWithTag(expandTag(ORDER_SPEED_DETAILS)).performClick()
        compose.waitForIdle()
    }

    private fun shown(text: String, substring: Boolean = false) =
        compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    @Test fun theCardShowsEachStepTheWarningsAndTheMumbaiAdvice() {
        show(sample())
        assertTrue(shown(ORDER_SPEED_TITLE))
        // Folded: the warnings and the advice in sight, the steps not.
        assertTrue(shown("⚠ Relay is slow: typical 180 ms", substring = true))
        assertTrue(!shown("Live · Signal → decision", substring = true))
        openDetails()
        assertTrue(shown("Live · Order sent → Zerodha's answer: p50 50 ms, p95 60 ms, 3 times"))
        assertTrue(shown("Live · Signal → decision: p50 15 ms, p95 30 ms, 2 times"))
        assertTrue(shown("Paper · Order sent → Zerodha's answer: p50 3 ms, p95 3 ms, 1 time"))
        assertTrue(shown("Live fills first seen on Zerodha's stream: 2 of 3"))
        assertTrue(shown("Direct round trip (no relay): p50 25 ms, p95 25 ms, 1 time"))
        assertTrue(shown("⚠ Relay is slow: typical 180 ms", substring = true))
        assertTrue(shown("⚠ Prices were up to 2.6 s old", substring = true))
        assertTrue(shown("Move the relay to a Mumbai region (e.g. Oracle ap-mumbai-1 / AWS ap-south-1)", substring = true))
        assertTrue(shown("nothing here changes it", substring = true))
    }

    @Test fun noRelayNoAdviceAndAnEmptyDaySaysSo() {
        val empty = OrderSpeed.Book("2026-10-09")
        show(OrderTiming.Card(OrderSpeed.lines(empty), OrderSpeed.warnings(empty), null), relayOn = false)
        openDetails()
        assertTrue(shown("No timings yet today", substring = true))
        assertTrue(!shown("Mumbai", substring = true))
        assertTrue(!shown("⚠", substring = true))
    }

    @Test fun beforeTheFirstReadItSaysReading() {
        show(null)
        assertEquals(1, compose.onAllNodesWithText("Reading…").fetchSemanticsNodes().size)
    }

    @Test fun theDetailsFoldOpenAndShutAndTheSyncMismatchStaysInSight() {
        show(sample(), sync = "Zerodha sync (10:26): 1 mismatch - NIFTY 25000 CE: app 75, Zerodha 0", steps = listOf("Watch pass: p50 12 ms"))
        // Collapsed by default: the step lines hidden; the warnings, the sync mismatch and the relay's word in sight.
        assertTrue(shown("Show details ▾"))
        assertTrue(!shown("Watch pass: p50 12 ms"))
        assertTrue(shown("1 mismatch", substring = true))
        assertTrue(shown("⚠ Prices were up to 2.6 s old", substring = true))
        assertTrue(shown("nothing here changes it", substring = true))
        // A tap opens it ...
        openDetails()
        assertTrue(shown("Watch pass: p50 12 ms"))
        assertTrue(shown("Hide details ▴"))
        assertTrue(shown("1 mismatch", substring = true))
        // ... and a second tap folds it again.
        openDetails()
        assertTrue(!shown("Watch pass: p50 12 ms"))
        assertTrue(shown("Show details ▾"))
        assertTrue(shown("⚠ Relay is slow: typical 180 ms", substring = true))
    }
}
