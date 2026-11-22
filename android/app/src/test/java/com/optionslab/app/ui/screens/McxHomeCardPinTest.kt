package com.optionslab.app.ui.screens

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.data.Strategies
import com.optionslab.app.security.PinLock
import com.optionslab.app.testing.AreaE
import com.optionslab.app.testing.AreaEWatchdog
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.OfflineModel
import com.optionslab.app.testing.frames
import com.optionslab.app.testing.has
import com.optionslab.app.testing.until
import com.optionslab.app.testing.waitForNoText
import com.optionslab.app.testing.waitForText
import com.optionslab.app.ui.AppModel
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

/**
 * 22 Nov 2026 (B6 review): the MCX card on Home as the app wires it ([McxHomeCard] on a real, offline [AppModel]): in Live with an app PIN
 * set, "Start again" asks the PIN first (Cancel starts nothing; the PIN starts the commodity side); on Paper it starts at once.
 */
@org.robolectric.annotation.Config(qualifiers = "w411dp-h2400dp")
@RunWith(AndroidJUnit4::class)
class McxHomeCardPinTest {
    @get:Rule val marketClock = com.optionslab.app.testing.MarketClockRule()
    @get:Rule val watchdog = AreaEWatchdog()
    @get:Rule val compose = createComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var offline: OfflineModel
    private val model: AppModel get() = offline.model
    private val pin = "246813"

    @Before fun up() { AreaE.resetGlobals(); offline = OfflineModel(app) }

    @After fun down() {
        offline.close()
        AreaE.resetGlobals()
        assertEquals("no test may reach the network", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun mcxStopped() = runBlocking { Strategies.mcxStoppedKept() } != null
    private fun tap(text: String) { compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.OnClick); compose.frames() }

    /** The card shown with the commodity side stopped by Boss, and its "Start again" dialog's Start tapped. */
    private fun startFromTheCard() {
        compose.until(15_000, "the card reads the stop") { model.mcxStop.value.stopped }
        compose.onAllNodesWithText(START_MCX).onFirst().performSemanticsAction(SemanticsActions.OnClick); compose.frames()
        compose.waitForText("Start the commodity bots again?")
        tap("Start")
    }

    private fun show() {
        runBlocking { Strategies.stopCommodities(sell = false) }
        assertTrue(mcxStopped())
        compose.setContent { IraAlgoTheme("light") { McxHomeCard(model) } }
        compose.frames()
    }

    @Test fun inLiveWithAPinTheCardsStartAgainTakesThePin() {
        PinLock.setPin(pin.toCharArray())
        model.update { it.copy(mode = "live", allowRealOrders = true) }
        compose.until(30_000, "the settings") { model.settings.value.live }
        show()
        startFromTheCard()
        compose.waitForText("Confirm it is you")
        compose.onNodeWithText(Strategies.MCX_LIFT_WHY).assertExists()
        tap("Cancel")
        compose.waitForNoText("Confirm it is you")
        assertTrue("Cancel starts nothing", mcxStopped())
        startFromTheCard()
        compose.waitForText("Confirm it is you")
        compose.onNodeWithText("PIN").performTextReplacement(pin); compose.frames()
        tap("Confirm")
        compose.until(30_000, "the commodity side started") { !mcxStopped() }
    }

    @Test fun onPaperTheCardsStartAgainGoesAtOnce() {
        PinLock.setPin(pin.toCharArray())
        assertFalse(model.settings.value.live)
        show()
        startFromTheCard()
        compose.until(30_000, "the commodity side started") { !mcxStopped() }
        assertFalse("no PIN step on Paper", compose.has("Confirm it is you"))
    }
}
