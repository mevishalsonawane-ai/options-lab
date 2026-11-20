package com.optionslab.app.ui.screens

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.testing.AreaE
import com.optionslab.app.testing.AreaEWatchdog
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.OfflineModel
import com.optionslab.app.testing.frames
import com.optionslab.app.testing.until
import com.optionslab.app.testing.waitForText
import com.optionslab.app.ui.theme.IraAlgoTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * 20 Nov 2026 (Boss: "a button where i could share u all the setting details"): "Share full settings and state" at the top of
 * Settings and beside Copy diagnostics; a tap builds the file off the screen's thread and opens the share sheet on ONE .txt file.
 */
@org.robolectric.annotation.Config(qualifiers = "w411dp-h2400dp")
@RunWith(AndroidJUnit4::class)
class ShareStateButtonTest {
    @get:Rule val marketClock = com.optionslab.app.testing.MarketClockRule()
    @get:Rule val watchdog = AreaEWatchdog()
    @get:Rule val compose = createComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private var offline: OfflineModel? = null

    @Before fun up() {
        assumeFalse("IraGoldAlgo has no such button", com.optionslab.app.BuildConfig.GOLD)
        AreaE.resetGlobals()
        com.optionslab.app.testing.forgetFileProviderPaths()
        com.optionslab.app.data.SettingsShare.builtOn = null
    }

    @After fun down() {
        offline?.close()
        AreaE.resetGlobals()
        assertEquals("no test may reach the network", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    /** Taps the button and waits for the share sheet: one plain-text file, built off the screen's thread. */
    private fun tapAndShare() {
        compose.onNodeWithTag("share-state").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.until(60_000, "the share sheet") { shadowOf(app).peekNextStartedActivity() != null }
        val chooser = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION") val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertNotNull(send)
        assertEquals("text/plain", send!!.type)
        @Suppress("DEPRECATION") val uri = send.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
        assertTrue("$uri", uri.toString().endsWith(".txt"))
        val built = com.optionslab.app.data.SettingsShare.builtOn
        assertNotNull(built)
        assertNotSame("built off the screen's thread", android.os.Looper.getMainLooper().thread, built)
        compose.waitForText("Settings and state ready to share", substring = true)
    }

    @Test fun theButtonIsAtTheTopOfSettingsAndShares() {
        val model = OfflineModel(app).also { offline = it }.model
        var page by mutableStateOf<String?>(null)
        compose.setContent { IraAlgoTheme("light") { CabinetScreen(model, page) { page = it } } }
        compose.frames()
        compose.waitForText(SHARE_STATE_LABEL)
        tapAndShare()
    }

    @Test fun theButtonIsBesideCopyDiagnosticsAndShares() {
        compose.setContent { IraAlgoTheme("light") { DiagnosticsPage() } }
        compose.frames()
        compose.waitForText("Copy diagnostics")
        compose.waitForText(SHARE_STATE_LABEL)
        tapAndShare()
    }
}
