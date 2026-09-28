package com.optionslab.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.optionslab.app.data.Broker
import com.optionslab.app.security.PinLock
import com.optionslab.app.testing.DeviceConfig
import com.optionslab.app.testing.LayoutLint
import com.optionslab.app.testing.ScreenTest
import com.optionslab.app.ui.Load
import com.optionslab.app.ui.OrderPlan
import com.optionslab.engine.Kite
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Every screen state the functional tests cover, on every device set-up (size x font scale x theme):
 * a screenshot each (build/outputs/roborazzi), [LayoutLint], and a click-everything smoke test.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreensLayoutTest(device: DeviceConfig) : ScreenTest(device) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun configs(): List<Array<Any>> = DeviceConfig.matrix()

        /** Real layout bugs found by these tests, skipped with this text until fixed (none open now:
         *  the lock and refused screens scroll since the landscape / large-font findings). */
        val LOCK_BUGS = emptyMap<String, String>()
        val REFUSED_BUGS = emptyMap<String, String>()

        /** The alert banner is drawn over the top of the lock screen on purpose. */
        val LOCK_OVERLAYS = LayoutLint.Options(overlays = setOf("Not the right PIN. 3 before a pause."))
    }

    private fun lock(setup: Boolean, bio: String? = null, onPin: (CharArray) -> PinLock.Result = { PinLock.Result.Ok }) =
        @androidx.compose.runtime.Composable {
            LockScreen(setup = setup, biometricLabel = bio, onBiometric = {}, onPin = onPin, onCreate = { null }, notice = null, calm = true)
        }

    @Test fun lockSetup() {
        checkScreen("lock-setup", knownBugs = LOCK_BUGS, content = lock(setup = true))
        assertTrue(smokeEveryAction().containsAll(listOf("1", "0")))
    }

    @Test fun lockUnlockWithFingerprint() {
        PinLock.setPin("246813".toCharArray())
        checkScreen("lock-unlock", knownBugs = LOCK_BUGS, content = lock(setup = false, bio = "Use fingerprint"))
    }

    @Test fun lockWrongPin() {
        // Every key must be reachable on every device (in landscape the lock screen scrolls to it).
        show(lock(setup = false) { PinLock.Result.Wrong(3) })
        "2468".forEach { compose.onNodeWithText(it.toString()).performScrollTo().performClick() }
        compose.onNodeWithText("✓").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Not the right PIN. 3 before a pause.")).fetchSemanticsNodes().isNotEmpty() }
        capture("lock-wrong-pin")
        lint("lock-wrong-pin", knownBugs = LOCK_BUGS, options = LOCK_OVERLAYS)
    }

    private val leg = Kite.Order("NIFTY26OCT24500PE", Kite.Side.BUY, 75, 75, "NRML", "LIMIT", 120.0)
    private fun plan(vararg legs: Kite.Order, margin: Broker.Margin? = null) = OrderPlan("Test order", null, legs.toList(),
        mapOf("NFO:${leg.tradingSymbol}" to Broker.Quote(119.5, 119.4, 119.6, 110.0)), legs.map { emptyList<String>() }, false, margin = margin)

    /** As in the app: the card inside the dialog's scrolling column. */
    private fun review(p: OrderPlan, allowed: Boolean = true) = @androidx.compose.runtime.Composable {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            PlanCard(p, allowed, Load.Idle, onPrice = { _, _ -> }, onSend = {}, onClose = {})
        }
    }

    @Test fun orderReviewTwoLegs() = checkScreen("order-review",
        content = review(plan(leg.copy(side = Kite.Side.BUY, tradingSymbol = "NIFTY26OCT24000PE", price = 20.0), leg.copy(side = Kite.Side.SELL),
            margin = Broker.Margin(90_000.0, 95_000.0, 400_000.0, 40.0))))

    @Test fun orderReviewPaperMode() = checkScreen("order-review-paper", content = review(plan(leg), allowed = false))

    @Test fun orderReviewShortOfMargin() = checkScreen("order-review-margin",
        content = review(plan(leg, margin = Broker.Margin(500_000.0, 500_000.0, 40_000.0, 20.0))))

    @Test fun refused() = checkScreen("refused", knownBugs = REFUSED_BUGS) {
        RefusedScreen(listOf("su binaries, Magisk or test-keys present", "an instrumentation framework is loaded or listening"), onQuit = {})
    }
}
