package com.optionslab.app.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.data.ExitCoordinator
import com.optionslab.app.data.ExposureCapHost
import com.optionslab.app.data.PortfolioHost
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.ira.DayStop
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 22 Nov 2026 (B6 review): the cards' PIN steps and words -
 *  2. the MCX card's "Start again" asks the PIN first where it is asked (Live with a PIN: the MCX arms trade live too);
 *  3. a higher same-direction cap while its live switch is on, or a higher portfolio cap while the guard is on, asks the PIN; lower, or
 *     with the live rule off, goes at once;
 *  5. the positions-lag guard's switch off says what it really leaves (not "only logs").
 */
@org.robolectric.annotation.Config(qualifiers = "w411dp-h3200dp")
@RunWith(AndroidJUnit4::class)
class B6PinCardsTest {
    @get:Rule val marketClock = com.optionslab.app.testing.MarketClockRule()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun up() { reset() }
    @After fun down() { reset() }

    private fun reset() { PortfolioHost.resetForTest(); ExposureCapHost.resetForTest(paperOn = false) }

    private fun shows(t: String, sub: Boolean = false) = compose.onAllNodesWithText(t, substring = sub).fetchSemanticsNodes().isNotEmpty()

    /** The test's PIN step: says it was asked, and offers the right PIN or Cancel. */
    private val fakePin: PinStep = { ok, cancel ->
        Text("PIN asked")
        androidx.compose.material3.TextButton(onClick = ok) { Text("PIN right") }
        androidx.compose.material3.TextButton(onClick = cancel) { Text("PIN cancel") }
    }

    private fun show(content: @Composable () -> Unit) {
        compose.setContent { IraAlgoTheme("light") { Column(Modifier.verticalScroll(rememberScrollState())) { content() } } }
        compose.waitForIdle()
    }

    private fun tap(t: String) { compose.onNodeWithText(t).performScrollTo().performClick(); compose.waitForIdle() }

    // ---- 2. the MCX card's Start again ----------------------------------------------------------------------------------------------

    private val stopped = AppModel.McxStopState(kept = DayStop.Kept(DayStop.Why.BOSS, "10:42"), sells = true)

    @Test fun theMcxCardsStartAsksThePinFirstWhereItIsAsked() {
        val calls = ArrayList<Boolean>()
        show { McxStopBar(stopped, onStop = {}, onStart = { pin -> calls += pin }, startNeedsPin = { true }, pin = fakePin) }
        compose.onAllNodesWithText(START_MCX).onFirst().areaCClick(); compose.waitForIdle()
        compose.onAllNodesWithText("Start").onFirst().areaCClick(); compose.waitForIdle()
        assertTrue(shows("PIN asked"))
        assertTrue("nothing started before the PIN", calls.isEmpty())
        tap("PIN cancel")
        assertFalse(shows("PIN asked"))
        assertTrue("cancel starts nothing", calls.isEmpty())
        compose.onAllNodesWithText(START_MCX).onFirst().areaCClick(); compose.waitForIdle()
        compose.onAllNodesWithText("Start").onFirst().areaCClick(); compose.waitForIdle()
        tap("PIN right")
        assertEquals(listOf(true), calls)
    }

    @Test fun withNoPinToAskTheMcxCardStartsAtOnce() {
        val calls = ArrayList<Boolean>()
        show { McxStopBar(stopped, onStop = {}, onStart = { pin -> calls += pin }, startNeedsPin = { false }, pin = fakePin) }
        compose.onAllNodesWithText(START_MCX).onFirst().areaCClick(); compose.waitForIdle()
        compose.onAllNodesWithText("Start").onFirst().areaCClick(); compose.waitForIdle()
        assertFalse(shows("PIN asked"))
        assertEquals(listOf(false), calls)
    }

    // ---- 3. raising a live risk rule's cap -------------------------------------------------------------------------------------------

    @Test fun aHigherSameDirectionCapWhileLiveIsOnAsksThePin() {
        ExposureCapHost.setLiveOn(true)
        show { SameDirectionCapCard(pinAsks = { true }, pin = fakePin) }
        assertEquals(2, ExposureCapHost.view.value.max)
        tap("3")
        assertTrue(shows("PIN asked"))
        tap("PIN cancel")
        assertEquals("cancelled: unchanged", 2, ExposureCapHost.view.value.max)
        tap("3")
        tap("PIN right")
        compose.waitUntil(5_000) { ExposureCapHost.view.value.max == 3 }
        // Lower: at once, no PIN.
        tap("1")
        compose.waitUntil(5_000) { ExposureCapHost.view.value.max == 1 }
        assertFalse(shows("PIN asked"))
    }

    @Test fun withLiveInShadowAHigherSameDirectionCapGoesAtOnce() {
        show { SameDirectionCapCard(pinAsks = { true }, pin = fakePin) }
        assertFalse(ExposureCapHost.view.value.liveOn)
        tap("5")
        compose.waitUntil(5_000) { ExposureCapHost.view.value.max == 5 }
        assertFalse(shows("PIN asked"))
    }

    /** The card's own first read (the settings and the trades) landed, so it can never read the cap back over a tap. */
    private fun portfolioRead() = compose.waitUntil(10_000) { PortfolioHost.view.value.line.startsWith("Portfolio: too few") }

    @Test fun aHigherPortfolioCapWhileTheGuardIsOnAsksThePin() {
        PortfolioHost.testOwners = { emptyMap() }
        show { PortfolioSettingCard(pinAsks = { true }, pin = fakePin) }
        portfolioRead()
        assertEquals(2.0, PortfolioHost.view.value.capPct, 1e-9)
        tap("3%")
        assertTrue(shows("PIN asked"))
        tap("PIN cancel")
        assertEquals("cancelled: unchanged", 2.0, PortfolioHost.view.value.capPct, 1e-9)
        tap("3%")
        tap("PIN right")
        compose.waitUntil(5_000) { kotlin.math.abs(PortfolioHost.view.value.capPct - 3.0) < 1e-9 }
        // Lower: at once.
        tap("1%")
        compose.waitUntil(5_000) { kotlin.math.abs(PortfolioHost.view.value.capPct - 1.0) < 1e-9 }
        assertFalse(shows("PIN asked"))
    }

    @Test fun withTheGuardOffAHigherPortfolioCapGoesAtOnce() {
        PortfolioHost.testOwners = { emptyMap() }
        show { PortfolioSettingCard(pinAsks = { true }, pin = fakePin) }
        portfolioRead()
        PortfolioHost.setOn(false, pinConfirmed = true)
        compose.waitUntil(5_000) { !PortfolioHost.view.value.on }
        tap("5%")
        compose.waitUntil(5_000) { kotlin.math.abs(PortfolioHost.view.value.capPct - 5.0) < 1e-9 }
        assertFalse(shows("PIN asked"))
    }

    // ---- 5. the positions-lag guard's words ------------------------------------------------------------------------------------------

    @Test fun theLagGuardsOffSaysWhatStillUsesIt() {
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        show { ExitCoordinatorCardContent(ExitCoordinator.View(on = true, lagOn = false), null) {} }
        assertTrue(shows(LAG_OFF_WORDS))
        assertTrue(LAG_OFF_WORDS.contains("the square-offs, strategy exits, the arms' live exits, the MCX expiry exit"))
        assertFalse("never \"only logs\" for the whole guard", shows("Off: only logs what it would do"))
    }
}
