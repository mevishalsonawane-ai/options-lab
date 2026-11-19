package com.optionslab.app.ui.screens

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.data.ArmLive
import com.optionslab.app.testing.AreaE
import com.optionslab.app.testing.AreaEWatchdog
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.OfflineModel
import com.optionslab.app.testing.frames
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.engine.orb.DonchianArm
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime

/**
 * 19 Nov 2026 (audit batch 2: "Reset paper" said "Nothing about Zerodha is touched" while every mirrored arm then sold its live copy):
 * the reset dialogs say an arm's live copy open at Zerodha stays open with its own stop (and the reset never sells it: [ArmLive.detachAll]);
 * with none open they say nothing more.
 */
@org.robolectric.annotation.Config(qualifiers = "w411dp-h2400dp")
@RunWith(AndroidJUnit4::class)
class PaperResetLiveCopyTest {
    @get:Rule val marketClock = com.optionslab.app.testing.MarketClockRule()
    @get:Rule val watchdog = AreaEWatchdog()
    @get:Rule val compose = createComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var offline: OfflineModel
    private val model: AppModel get() = offline.model

    @Before fun up() { AreaE.resetGlobals(); offline = OfflineModel(app) }

    @After fun down() {
        ArmLive.viewForTest(ArmLive.View())
        offline.close()
        AreaE.resetGlobals()
        assertEquals("no test may reach the network", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun shows(t: String) = compose.onAllNodesWithText(t, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun liveCopy() = ArmLive.Pos(DonchianArm.SOURCE, "passed:r51:1", "NIFTY26NOV25050CE", "NFO", "MIS", 1, 65, 65, 65, 0.05, 1, 100.0,
        LocalDateTime.now().withNano(0), 100.0, 65, null, "1001", stopOrderId = "1002", stopTrigger = 85.0, paperSymbol = "NIFTY-PA-25050CE")

    @Test fun theResetDialogSaysAnOpenLiveCopyStaysOpenWithItsOwnStop() {
        ArmLive.viewForTest(ArmLive.View(open = listOf(liveCopy()), loaded = true))
        compose.setContent { IraAlgoTheme("light") { PaperDefaultDialog(model) { } } }
        compose.frames()
        assertTrue(shows("Paper only - nothing at Zerodha changes."))
        assertTrue(shows("A live copy is open at Zerodha"))
        assertTrue(shows("it stays open with its own stop and exits. Nothing at Zerodha is sold"))
    }

    @Test fun theAmountDialogSaysItTooAndNothingWhenNoneIsOpen() {
        ArmLive.viewForTest(ArmLive.View(open = listOf(liveCopy()), loaded = true))
        compose.setContent { IraAlgoTheme("light") { PaperResetDialog(model) { } } }
        compose.frames()
        assertTrue(shows("A live copy is open at Zerodha"))
        ArmLive.viewForTest(ArmLive.View(open = listOf(liveCopy().copy(detached = true)), loaded = true))
        assertTrue("a detached copy no longer follows paper", ArmLive.liveCopiesOpen().isEmpty())
        ArmLive.viewForTest(ArmLive.View(loaded = true))
        assertFalse(ArmLive.liveCopyWords(ArmLive.liveCopiesOpen()) != null)
    }
}
