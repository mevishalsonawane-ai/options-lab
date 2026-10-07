package com.optionslab.app.ui.screens

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.testing.AreaE
import com.optionslab.app.testing.AreaEWatchdog
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.OfflineModel
import com.optionslab.app.testing.frames
import com.optionslab.app.testing.until
import com.optionslab.app.testing.waitForText
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.SettingFocus
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.ira.SettingsIndex
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Settings search: typing at the top of Settings lists the matching settings across every page, each with its path; a tap
 * opens that page with the row asked for (SettingFocus) - navigation only: no switch changes, no PIN or confirm is asked or
 * skipped. Clearing the box shows the pages again. IraGoldAlgo's search lists only its own settings.
 */
@org.robolectric.annotation.Config(qualifiers = "w411dp-h2400dp")
@RunWith(AndroidJUnit4::class)
class SettingsSearchTest {
    @get:Rule val watchdog = AreaEWatchdog()
    @get:Rule val compose = createComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var offline: OfflineModel
    private val model: AppModel get() = offline.model

    @Before fun up() {
        AreaE.resetGlobals()
        SettingFocus.reset()
        offline = OfflineModel(app)
    }

    @After fun down() {
        offline.close()
        SettingFocus.reset()
        AreaE.resetGlobals()
        assertEquals("no test may reach the network", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private var page by mutableStateOf<String?>(null)

    private fun show() {
        compose.setContent { IraAlgoTheme("light") { CabinetScreen(model, page) { page = it } } }
        compose.frames()
        compose.waitForText("Zerodha")
    }

    private fun search(q: String) {
        compose.onNodeWithText(SETTINGS_SEARCH_LABEL).performTextReplacement(q)
        compose.frames(); compose.waitForIdle()
    }

    private fun shows(t: String) = compose.onAllNodesWithText(keepNumbersWhole(t), substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun tap(t: String) {
        compose.onNodeWithText(keepNumbersWhole(t)).performSemanticsAction(SemanticsActions.OnClick)
        compose.frames(); compose.waitForIdle()
    }

    @Test fun typingListsMatchingSettingsWithTheirPathAndClearingShowsThePages() {
        show()
        search("backup")
        compose.waitForText("Backup and restore")
        assertTrue(shows("Settings → Security → Backup and restore"))
        // The pages give way to the results.
        assertFalse(shows("Kill switch, daily loss, drawdown, position and order limits"))
        // A synonym finds it too ("lots": the bot's lot limit and the lot sizes page).
        search("lots")
        compose.waitForText("Max lots per instrument")
        assertTrue(shows("Settings → Bot settings → Account guard → Max lots per instrument"))
        assertTrue(shows("Lot sizes"))
        // Nothing found: said so.
        search("cricket score")
        compose.waitForText("No setting matches", substring = true)
        // Cleared: the pages again, nothing opened.
        tap("Clear")
        compose.waitForText("Zerodha")
        assertEquals(null, page)
        assertEquals(null, SettingFocus.wanted.value)
    }

    @Test fun aTapOpensThePageWithTheRowAskedForAndChangesNothing() {
        show()
        val before = model.settings.value
        search("fingerprint")
        compose.waitForText("Fingerprint")
        tap("Fingerprint")
        compose.until(5_000, "the Security page") { page == "security" }
        compose.waitForText("Nothing personal leaves this phone, and nothing is logged")
        // The row was asked for (and, once laid out, brought into view and lit) - nothing was switched or asked.
        compose.until(10_000, "the row asked for") { SettingFocus.wanted.value == "security.unlock" || SettingFocus.lit.value == "security.unlock" }
        assertEquals(before, model.settings.value)
        assertFalse(shows("Enter your app PIN"))
    }

    @Test fun theKillSwitchIsFoundAndOpenedNeverTurned() {
        show()
        val killBefore = model.settings.value.guardKill
        search("kill switch")
        compose.waitForText("Kill switch")
        assertTrue(shows("Settings → Bot settings → Account guard → Kill switch"))
        tap("Kill switch")
        compose.until(5_000, "Bot settings") { page == "risk" }
        compose.waitForText("Limits on every order the bot or you place, paper and live")
        compose.until(10_000, "the row asked for") { SettingFocus.wanted.value == "risk.guards" || SettingFocus.lit.value == "risk.guards" }
        // Its own confirmation is not shown, let alone skipped: the switch is as it was.
        assertEquals(killBefore, model.settings.value.guardKill)
    }

    @Test fun aWholePageEntryOpensThePage() {
        show()
        search("research")
        compose.waitForText("Research notes")
        tap("Research notes")
        compose.until(5_000, "the research notes") { page == "notes" }
        // A page has no row to bring into view.
        assertEquals(null, SettingFocus.wanted.value)
    }

    @Test fun onlyThePagesThisBuildShowsAreSearched() {
        show()
        search("quiet")
        if (com.optionslab.app.BuildConfig.JARVIS) compose.waitForText("Quiet hours")
        else compose.waitForText("No setting matches", substring = true)
        // IraGoldAlgo's search: its own settings only.
        val gold = settingsFound("theme", gold = true, pages = null)
        assertTrue(gold.isNotEmpty() && gold.all { it.gold && it.page == SettingsIndex.GOLD_PAGE })
        assertTrue(settingsFound("kill", gold = true, pages = null).isEmpty())
    }
}
