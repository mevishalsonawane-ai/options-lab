package com.optionslab.app.ui.screens

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.ira.IraHub
import com.optionslab.app.testing.AreaE
import com.optionslab.app.testing.AreaEWatchdog
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.frames
import com.optionslab.app.testing.until
import com.optionslab.app.testing.waitForText
import com.optionslab.app.ui.theme.IraAlgoTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Ira page on the bundled record: it reads the data, answers an example question, and sends nothing anywhere. */
@org.robolectric.annotation.Config(qualifiers = "w411dp-h900dp")
@RunWith(AndroidJUnit4::class)
class IraPageTest {
    @get:Rule val watchdog = AreaEWatchdog()
    @get:Rule val compose = createComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    // After the close on a fixed clock: in session hours (CI at 03:45 UTC is 09:15 IST) an empty live read is "no live
    // price", never the last close, so the example's "BankNifty is at" only held when CI ran outside market hours.
    @Before fun up() {
        AreaE.resetGlobals()
        com.optionslab.app.data.Market.testClock = java.time.Clock.fixed(
            java.time.LocalDate.now(com.optionslab.engine.IST).atTime(16, 0).atZone(com.optionslab.engine.IST).toInstant(), com.optionslab.engine.IST)
    }

    @After fun down() {
        com.optionslab.app.data.Market.testClock = null
        runBlocking { IraHub.forgetAll() }
        AreaE.resetGlobals()
        assertEquals("no test may reach the network", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    @Test fun homeOpensOnIraWithTheDashboardOneTapAway() {
        compose.setContent { IraAlgoTheme("light") { IraHome { androidx.compose.material3.Text("The usual dashboard") } } }
        compose.frames()
        compose.waitForText("Ask Ira about the market")
        compose.onNodeWithText("Dashboard").performSemanticsAction(SemanticsActions.OnClick); compose.frames()
        compose.waitForText("The usual dashboard")
        compose.onAllNodesWithText("Ira").onFirst().performSemanticsAction(SemanticsActions.OnClick); compose.frames()
        compose.waitForText("Ask Ira about the market")
    }

    @Test fun asksAnExampleAndGetsAnAnswer() {
        compose.setContent { IraAlgoTheme("dark") { IraPage() } }
        compose.frames()
        compose.until(20_000, "the data read") { !IraHub.state.value.loading && IraHub.state.value.snaps.isNotEmpty() }
        compose.waitForText("pattern outcomes learned", substring = true)
        compose.waitForText("How Ira is doing")
        compose.waitForText("Last session", substring = true)
        compose.onNodeWithText("What is BankNifty doing today?").performSemanticsAction(SemanticsActions.OnClick); compose.frames()
        compose.until(10_000, "an answer") { IraHub.state.value.messages.size == 2 }
        compose.waitForText("BankNifty is at", substring = true)
        compose.waitForText("Facts used", substring = true)
        compose.onNodeWithText("Ask Ira about the market").performTextInput("who won the match")
        compose.frames()
        compose.onNodeWithText("Ask").performSemanticsAction(SemanticsActions.OnClick); compose.frames()
        compose.until(10_000, "a second answer") { IraHub.state.value.messages.size == 4 }
        assertTrue(IraHub.state.value.messages.last().text.startsWith("I only know"))
        compose.waitForText("Forget this conversation")
        compose.onNodeWithText("Forget this conversation").performSemanticsAction(SemanticsActions.OnClick); compose.frames()
        compose.until(5_000, "forgotten") { IraHub.state.value.messages.isEmpty() }
    }

    /** "buy …" on Paper: Review shows the contract, and only Confirm places it; on Live it opens the order review instead. */
    @Test fun anOrderGoesOnlyThroughConfirmOrTheReview() {
        val placed = ArrayList<com.optionslab.app.ira.IraOrders.Ticket>(); val reviewed = ArrayList<com.optionslab.app.ira.IraOrders.Ticket>()
        var live = false
        val paths = IraOrderPaths({ live }, { 2 }, { placed += it }, { reviewed += it })
        com.optionslab.app.ira.IraOrders.testListed = { _, _, _ ->
            listOf(com.optionslab.app.ira.IraOrders.Listed(java.time.LocalDate.now().plusDays(3), 24_000.0, 75))
        }
        try {
            compose.setContent { IraAlgoTheme("light") { IraPage(paths) } }
            compose.frames()
            IraHub.ask("buy 1 lot nifty 24000 ce"); compose.frames()
            compose.waitForText("Ready for review", substring = true)
            compose.onNodeWithText("Review (paper)").performSemanticsAction(SemanticsActions.OnClick); compose.frames()
            compose.waitForText("Confirm (paper)")
            assertTrue("nothing is placed before Confirm", placed.isEmpty())
            compose.onNodeWithText("Confirm (paper)").performSemanticsAction(SemanticsActions.OnClick); compose.frames()
            assertEquals(listOf(24_000.0 to com.optionslab.engine.Right.CE), placed.map { it.strike to it.right })
            compose.waitForText("Sent to the paper account", substring = true)
            live = true
            IraHub.ask("sell 1 lot nifty 24000 pe"); compose.frames()
            compose.waitForText("Review (Live, Zerodha)")
            compose.onNodeWithText("Review (Live, Zerodha)").performSemanticsAction(SemanticsActions.OnClick); compose.frames()
            compose.until(5_000, "the review opened") { reviewed.isNotEmpty() }
            assertEquals(1, placed.size); assertEquals(false, reviewed.single().buy)
            compose.waitForText("Only the swipe and your PIN send it", substring = true)
        } finally { com.optionslab.app.ira.IraOrders.testListed = null }
    }
}
