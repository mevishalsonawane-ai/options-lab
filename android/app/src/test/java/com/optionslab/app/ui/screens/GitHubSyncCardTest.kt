package com.optionslab.app.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.ira.CloudSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** Settings → Data & Harvest → GitHub sync's card (13 Oct review): the data repository, the refused token, the buttons. Plain state. */
@org.robolectric.annotation.Config(qualifiers = "w411dp-h2400dp")
@RunWith(AndroidJUnit4::class)
class GitHubSyncCardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun shows(t: String, sub: Boolean = false) = compose.onAllNodesWithText(t, substring = sub).fetchSemanticsNodes().isNotEmpty()

    private val today = LocalDate.of(2026, 10, 13)
    private val zone = java.time.ZoneOffset.ofHoursMinutes(5, 30)
    private val toDay: (Long) -> LocalDate = { java.time.Instant.ofEpochMilli(it).atOffset(zone).toLocalDate() }
    private fun ms(d: LocalDate) = d.atTime(18, 0).toInstant(zone).toEpochMilli()

    private fun status(token: Boolean = true, key: Boolean = true, last: CloudSync.Last? = null) =
        CloudSync.Status(token, key, LocalDate.of(2027, 1, 10), last, last?.takeIf { it.ok }, null, 0)

    private val asks = ArrayList<String>()
    private var uploads = 0

    private fun show(s: CloudSync.Status, workerOk: Boolean = true) {
        compose.setContent { IraAlgoTheme("light") { Column(Modifier.verticalScroll(rememberScrollState())) {
            GitHubSyncLines(s, CloudSync.card(s, toDay, today), workerOk, busy = false, ask = null,
                onAsk = { asks += it }, onUploadNow = { uploads++ })
        } } }
        compose.waitForIdle()
    }

    @Test fun nothingSetUpOffersThePasteOnly() {
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        show(status(token = false, key = false))
        assertTrue(shows("mevishalsonawane-ai/IraAlgo", sub = true))
        assertTrue(shows("never options-lab", sub = true))
        assertTrue(shows("Paste token…"))
        assertFalse(shows("Upload now"))
        assertFalse(shows("Restore from GitHub…"))
        assertFalse(shows("Remove the token"))
        compose.onNodeWithText("Paste token…").performClick()
        compose.onNodeWithText("Set passphrase…").performClick()
        assertEquals(listOf("token", "pass"), asks)
    }

    @Test fun theNoteSaysTheUploadWaitsForTheMcxClose() {
        // G4 (13 Oct, Boss: "after the commodities close"): the evening upload waits for MCX's evening close.
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        show(status())
        assertTrue(shows("Every evening after the MCX evening close", sub = true))
        assertTrue(shows("15:45 on days without an MCX session", sub = true))
        assertTrue(shows("next morning before 08:00", sub = true))
        assertFalse(shows("Every evening after 15:45", sub = true))
    }

    @Test fun theCardSaysTheLastDayOfDiagnosticsUploaded() {
        // G3 (13 Oct): each evening's upload carries that day's diagnostics; the card names the last day that went up.
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        show(status())
        assertTrue(shows("Diagnostics"))
        assertTrue(shows("last day uploaded: none yet"))
        show(status().copy(lastDiagDay = today.minusDays(1)))
        assertTrue(shows("last day uploaded: 12 Oct ✓"))
        assertFalse(shows("last day uploaded: none yet"))
    }

    @Test fun setUpOffersUploadAndRestore() {
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        show(status(last = CloudSync.Last(ms(today), true, 4_200_000, "backup")))
        assertTrue(shows("Uploads go to"))
        assertTrue(shows("13 Oct · 4.2 MB · ✓"))
        compose.onNodeWithText("Upload now").performClick()
        assertEquals(1, uploads)
        compose.onNodeWithText("Restore from GitHub…").performClick()
        compose.onNodeWithText("Remove the token").performClick()
        assertEquals(listOf("restore", "forget"), asks)
        assertFalse(shows("Unlock with PIN"))
    }

    @Test fun aRefusedTokenSaysSo() {
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        show(status(last = CloudSync.Last(ms(today), false, 0, "backup", "401: the token was refused", 401)))
        assertTrue(shows("✗ GitHub token invalid: paste a new one"))
        assertTrue(shows("New token…"))
    }

    @Test fun aLostWorkerKeyAsksForThePinOnce() {
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        show(status(), workerOk = false)
        compose.onNodeWithText("Unlock with PIN").performClick()
        assertEquals(listOf("unlock"), asks)
    }

    @Test fun theTokenFormRefusesOptionsLab() {
        val t = "github_pat_" + "TEST" + "q".repeat(40)
        val until = today.plusDays(90).toString()
        assertNull(tokenFormProblem(t, until, CloudSync.DEFAULT_REPO, today))
        assertTrue(tokenFormProblem(t, until, "mevishalsonawane-ai/options-lab", today)!!.contains("never"))
        assertTrue(tokenFormProblem(t, until, "IraAlgo", today)!!.contains("owner/name"))
        assertTrue(tokenFormProblem("ghp_old", until, CloudSync.DEFAULT_REPO, today)!!.contains("github_pat_"))
        assertTrue(tokenFormProblem(t, "2026-10-12", CloudSync.DEFAULT_REPO, today)!!.contains("future"))
        assertTrue(tokenFormProblem(t, "soon", CloudSync.DEFAULT_REPO, today)!!.contains("YYYY-MM-DD"))
    }
}
