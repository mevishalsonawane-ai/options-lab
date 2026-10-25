package com.optionslab.app.ui.screens

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.testing.AreaE
import com.optionslab.app.testing.AreaEWatchdog
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.OfflineModel
import com.optionslab.app.testing.frames
import com.optionslab.app.testing.until
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.theme.IraAlgoTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 25 Oct (Boss: "Daily loss was off" - the Zerodha limit on Bot settings was off, but the paper bots' own limit, ₹6,000 then, was
 * on no screen and stopped every paper bot on 23 Oct; then "Make it 10k"): Bot settings shows the paper bots' daily loss limit
 * beside the Zerodha one, at ₹10,000 by default, and a pick there changes it (paper only: no PIN asked).
 */
@org.robolectric.annotation.Config(qualifiers = "w411dp-h2400dp")
@RunWith(AndroidJUnit4::class)
class PaperLossLimitRowTest {
    @get:Rule val marketClock = com.optionslab.app.testing.MarketClockRule()
    @get:Rule val watchdog = AreaEWatchdog()
    @get:Rule val compose = createComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var offline: OfflineModel
    private val model: AppModel get() = offline.model

    @Before fun up() { AreaE.resetGlobals(); offline = OfflineModel(app) }

    @After fun down() {
        offline.close()
        AreaE.resetGlobals()
        assertEquals("no test may reach the network", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun shows(t: String) = compose.onAllNodesWithText(t, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test fun botSettingsShowsThePaperBotsDailyLossLimitAt10000() {
        assertEquals(10_000.0, model.settings.value.guardPaperDailyLoss, 0.0)
        compose.setContent { IraAlgoTheme("light") { CabinetScreen(model, "risk") { } } }
        compose.frames()
        compose.until(10_000, "the paper limit's row") { shows("PAPER BOTS' DAILY LOSS LIMIT") }
        // Both limits are on the one screen: the Zerodha account guard's and the paper bots'.
        assertEquals(true, shows("DAILY LOSS LIMIT"))
        model.update { it.copy(guardPaperDailyLoss = 20_000.0) }
        compose.frames(); compose.waitForIdle()
        assertEquals(20_000.0, model.settings.value.guardPaperDailyLoss, 0.0)
        assertEquals(true, shows("PAPER BOTS' DAILY LOSS LIMIT"))
    }
}
