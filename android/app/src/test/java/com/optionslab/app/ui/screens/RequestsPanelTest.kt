package com.optionslab.app.ui.screens

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.ira.IraHub
import com.optionslab.app.testing.AreaE
import com.optionslab.app.testing.AreaEWatchdog
import com.optionslab.app.testing.frames
import com.optionslab.app.testing.has
import com.optionslab.app.testing.until
import com.optionslab.app.testing.waitForNoText
import com.optionslab.app.testing.waitForText
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
 * The Requests panel: only what waits for Boss's yes, each with what it does, Paper or Zerodha, when asked and when it
 * lapses; No is the hub's decline, Yes the hub's own confirm; answered ones move to Recent; none waiting, it says so.
 */
@org.robolectric.annotation.Config(qualifiers = "w411dp-h891dp")
@RunWith(AndroidJUnit4::class)
class RequestsPanelTest {
    @get:Rule val watchdog = AreaEWatchdog()
    @get:Rule val compose = createComposeRule()

    @Before fun up() { AreaE.resetGlobals() }
    @After fun down() {
        runBlocking { IraHub.forgetAll() }
        com.optionslab.app.data.AppSettings.save(com.optionslab.app.data.AppSettings.load().copy(guardKill = false))
        AreaE.resetGlobals()
    }

    private fun tap(text: String) { compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.OnClick); compose.frames() }

    @Test fun listsWhatWaitsAndAnswersItThroughTheHub() {
        // IraGoldAlgo only talks: nothing waits there.
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        compose.setContent { IraAlgoTheme("light") { RequestsPanel(onClose = {}) } }
        compose.frames()
        compose.waitForText("No requests waiting, Boss.")
        IraHub.ask("hit the panic button")
        compose.until(60_000, "the request") { IraHub.state.value.pending.isNotEmpty() }
        val id = IraHub.state.value.pending.single()
        // The chat says only that there is one; the panel has it.
        assertTrue(IraHub.state.value.messages.last().text, IraHub.state.value.messages.last().text.startsWith("New request: "))
        compose.waitForText("Requests 1")
        compose.waitForText("Lapses in", substring = true)
        // "Hit the panic button" is the kill switch: it stops new entries and sends no order, so it is labelled "No order".
        // (Exits, closes and cancels are labelled by what is open: RequestsTest and IraActions.venueOf.)
        val view = IraHub.requestsOf(IraHub.state.value).single()
        assertEquals(com.optionslab.ira.Requests.Venue.NONE, view.venue)
        // No fingerprint is asked for it, so the card never says so.
        assertFalse(view.fingerprint)
        assertFalse(compose.has(com.optionslab.ira.Requests.FINGERPRINT_MARK))
        compose.waitForText("No order")
        // The chat line carries the full what, never only the short title.
        assertTrue(IraHub.state.value.messages.last().text, IraHub.state.value.messages.last().text.contains(view.what.trim().trimEnd('.')))
        compose.waitForText("Yes, approve")
        assertFalse("nothing before Yes", com.optionslab.app.data.AppSettings.load().guardKill)
        tap("No, reject")
        compose.until(10_000, "declined") { id !in IraHub.state.value.pending }
        assertFalse("declined: nothing done", com.optionslab.app.data.AppSettings.load().guardKill)
        compose.waitForText("No requests waiting, Boss.")
        compose.waitForText("Recent")
        compose.waitForText("Declined")
        assertEquals(com.optionslab.ira.Requests.Outcome.DECLINED, IraHub.recentRequests().value.first().outcome)

        IraHub.ask("hit the panic button")
        compose.until(60_000, "the second request") { IraHub.state.value.pending.isNotEmpty() }
        compose.waitForText("Yes, approve")
        tap("Yes, approve")
        compose.until(10_000, "approved") { IraHub.state.value.pending.isEmpty() }
        compose.until(10_000, "the kill switch on") { com.optionslab.app.data.AppSettings.load().guardKill }
        compose.waitForText("Approved")
        assertFalse("answered: no button left", compose.has("Yes, approve"))
    }

    /** The answered banner is a record only: no Yes / No, no Later, nothing that approves or rejects. */
    private fun assertNoAnswerButtons() {
        for (b in listOf("Yes, approve", "No, reject", "Yes", "No", "Later", "Confirm", "Cancel"))
            assertFalse("no '$b' on an answered request's banner", compose.has(b))
    }

    @Test fun anAnsweredRequestOpensTheSameBannerWithItsRecordOnly() {
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        compose.setContent { IraAlgoTheme("light") { RequestsPanel(onClose = {}) } }
        compose.frames()
        compose.waitForText("No requests waiting, Boss.")

        // Rejected: tapped in Recent, the banner says what it was, where, the fingerprint, when asked and when and how
        // it was rejected, and what came of it - with no Yes or No.
        IraHub.ask("hit the panic button")
        compose.until(60_000, "the request") { IraHub.state.value.pending.isNotEmpty() }
        compose.waitForText("No, reject")
        tap("No, reject")
        compose.until(10_000, "declined") { IraHub.recentRequests().value.isNotEmpty() }
        compose.waitForText("Declined")
        val rejected = IraHub.recentRequests().value.first()
        assertEquals(com.optionslab.ira.Requests.By.TAP, rejected.by)
        assertFalse("not open before the tap", compose.has(com.optionslab.ira.Requests.answeredTitle(rejected)))
        tap("Declined")
        compose.waitForText(com.optionslab.ira.Requests.answeredTitle(rejected))
        assertTrue(com.optionslab.ira.Requests.answeredTitle(rejected).startsWith("Rejected: "))
        compose.onNodeWithTag("notice-banner", useUnmergedTree = true).assertExists()
        for (line in listOf("Venue: No order", "Fingerprint: not needed", "Asked: ", "(by tap)", "Result: Cancelled; nothing was done.",
                com.optionslab.ira.Requests.ANSWERED_NOTE))
            assertTrue("banner has '$line'", compose.has(line, substring = true))
        assertNoAnswerButtons()
        assertFalse("nothing re-run", com.optionslab.app.data.AppSettings.load().guardKill)
        tap("OK")
        compose.waitForNoText(com.optionslab.ira.Requests.answeredTitle(rejected))
        assertFalse(compose.has("Rejected: ", substring = true))

        // A pending request is as before: its card with Yes and No, and no banner of its own.
        IraHub.ask("hit the panic button")
        compose.until(60_000, "the second request") { IraHub.state.value.pending.isNotEmpty() }
        compose.waitForText("Yes, approve")
        assertTrue(compose.has("No, reject"))
        assertTrue(compose.has("Lapses in", substring = true))
        assertTrue("no banner for a pending one", compose.onAllNodesWithTag("notice-banner", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertFalse("still waiting: nothing done", com.optionslab.app.data.AppSettings.load().guardKill)

        // Approved: the same banner, "Approved", how and the result; still no Yes or No, and nothing done again.
        tap("Yes, approve")
        compose.until(10_000, "approved") { IraHub.state.value.pending.isEmpty() }
        compose.until(10_000, "the kill switch on") { com.optionslab.app.data.AppSettings.load().guardKill }
        compose.until(10_000, "approved in Recent") { IraHub.recentRequests().value.firstOrNull()?.outcome == com.optionslab.ira.Requests.Outcome.APPROVED }
        compose.waitForText("Approved")
        val approved = IraHub.recentRequests().value.first()
        assertEquals(com.optionslab.ira.Requests.By.TAP, approved.by)
        assertTrue(approved.result.orEmpty().isNotBlank())
        tap("Approved")
        compose.waitForText(com.optionslab.ira.Requests.answeredTitle(approved))
        assertTrue(com.optionslab.ira.Requests.answeredTitle(approved).startsWith("Approved: "))
        for (line in listOf("Venue: No order", "Fingerprint: not needed", "Asked: ", "(by tap)", "Result: ",
                com.optionslab.ira.Requests.ANSWERED_NOTE))
            assertTrue("banner has '$line'", compose.has(line, substring = true))
        assertNoAnswerButtons()
        tap("OK")
        compose.waitForNoText(com.optionslab.ira.Requests.answeredTitle(approved))
        assertEquals(com.optionslab.ira.Requests.Outcome.APPROVED, IraHub.recentRequests().value.first().outcome)
    }
}
