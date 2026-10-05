package com.optionslab.app.ui.screens

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.ira.IraHub
import com.optionslab.app.testing.AreaE
import com.optionslab.app.testing.AreaEWatchdog
import com.optionslab.app.testing.frames
import com.optionslab.app.testing.has
import com.optionslab.app.testing.until
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
        // The emergency exit can send orders: labelled by what is open (nothing on Zerodha here: Paper), never "No order".
        compose.waitForText("Paper")
        val view = IraHub.requestsOf(IraHub.state.value).single()
        assertEquals(com.optionslab.ira.Requests.Venue.PAPER, view.venue)
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
}
