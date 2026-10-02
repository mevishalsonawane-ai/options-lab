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

    @Before fun up() { AreaE.resetGlobals() }

    @After fun down() {
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
}
