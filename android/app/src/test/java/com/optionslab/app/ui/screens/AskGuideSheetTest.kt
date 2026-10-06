package com.optionslab.app.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.ira.IraHub
import com.optionslab.app.testing.AreaE
import com.optionslab.app.testing.AreaEWatchdog
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.frames
import com.optionslab.app.testing.until
import com.optionslab.app.testing.waitForText
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.ira.AskGuide
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
 * "What can I ask?": the guide folds and opens its groups, the search box filters the examples, a tap hands over the
 * question exactly as written; on the Ira page the "?" sits beside the question box (both on screen on an ordinary phone)
 * and a tap on an example asks it in the chat as if typed, then closes the guide.
 */
@org.robolectric.annotation.Config(qualifiers = "w411dp-h2400dp")
@RunWith(AndroidJUnit4::class)
class AskGuideSheetTest {
    @get:Rule val watchdog = AreaEWatchdog()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

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

    private fun set(content: @Composable () -> Unit) { compose.setContent { IraAlgoTheme("light") { content() } }; compose.frames() }
    private fun tap(t: String) { compose.onNodeWithText(keepNumbersWhole(t)).performSemanticsAction(SemanticsActions.OnClick); compose.frames(); compose.waitForIdle() }
    private fun shows(t: String) = compose.onAllNodesWithText(keepNumbersWhole(t), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test fun groupsFoldAndOpenAndATapHandsOverTheQuestion() {
        val asked = ArrayList<String>(); var closed = 0
        val groups = AskGuide.forBuild(gold = false)
        set { AskGuideSheet(onAsk = { asked += it }, onClose = { closed++ }, groups = groups) }
        compose.onNodeWithText("What can I ask?").assertIsDisplayed()
        // Folded at first: every group's name, none of its questions.
        for (g in groups) assertTrue(g.title, shows(g.title))
        assertFalse(shows("why didn't liquidity trade today"))
        tap("Liquidity 15+5")
        for (e in groups.first { it.id == "liquidity" }.examples) assertTrue(e.q, shows(e.q))
        assertFalse(shows("what did Solo do today"))
        tap("why didn't liquidity trade today")
        assertEquals(listOf("why didn't liquidity trade today"), asked)
        // Folded again.
        tap("Liquidity 15+5")
        assertFalse(shows("why didn't liquidity trade today"))
        tap("Close")
        assertEquals(1, closed)
    }

    @Test fun theSearchBoxFiltersTheExamples() {
        val asked = ArrayList<String>()
        set { AskGuideSheet(onAsk = { asked += it }, onClose = null, groups = AskGuide.forBuild(gold = false)) }
        assertFalse("no Close in Settings", shows("Close"))
        compose.onNodeWithText("Search questions").performTextInput("kal")
        compose.frames(); compose.waitForIdle()
        // Matching groups open while searching, with only the matching questions.
        assertTrue(shows("kal expiry hai kya")); assertTrue(shows("kal ka plan batao"))
        assertFalse(shows("what's the main news"))
        assertFalse(shows("Liquidity 15+5"))
        tap("kal ka plan batao")
        assertEquals(listOf("kal ka plan batao"), asked)
        compose.onNodeWithText("kal").performTextInput(" cricket")
        compose.frames(); compose.waitForIdle()
        compose.waitForText("No question holds those words", substring = true)
    }

    @Test fun goldShowsOnlyItsGroups() {
        val gold = AskGuide.forBuild(gold = true)
        set { AskGuideSheet(onAsk = {}, onClose = {}, groups = gold) }
        for (g in gold) assertTrue(shows(g.title))
        assertFalse(shows("Liquidity 15+5")); assertFalse(shows("Hero"))
    }

    /**
     * The Ira page on an ordinary phone: the "?" beside the question box, both on screen with Ask; a tap opens the guide,
     * and a question tapped there is asked in the chat exactly as typed, the guide closing back to the chat.
     */
    @Test
    @org.robolectric.annotation.Config(qualifiers = "w411dp-h800dp")
    fun theIraPageChipAsksAsTyped() {
        set { IraPage(startInChat = true) }
        compose.until(20_000, "the data read") { !IraHub.state.value.loading }
        compose.waitForText("Ask Ira about the market")
        val rootBottom = compose.onRoot().fetchSemanticsNode().boundsInRoot.bottom
        val chip = compose.onNodeWithContentDescription("What can I ask?")
        chip.assertIsDisplayed()
        val c = chip.fetchSemanticsNode().boundsInRoot
        val density = compose.density.density
        assertTrue("a 48dp touch target: ${c.width} x ${c.height}", c.width >= 48 * density - 0.5f && c.height >= 48 * density - 0.5f)
        for (t in listOf("Ask Ira about the market", "Ask")) {
            compose.onNodeWithText(t).assertIsDisplayed()
            val b = compose.onNodeWithText(t).fetchSemanticsNode().boundsInRoot
            assertTrue("$t on screen: ${b.bottom} of $rootBottom", b.bottom <= rootBottom + 0.5f)
            assertTrue("$t beside the chip", b.left >= c.right - 0.5f)
        }
        assertTrue(c.bottom <= rootBottom + 0.5f)
        chip.performSemanticsAction(SemanticsActions.OnClick); compose.frames(); compose.waitForIdle()
        compose.waitForText("Search questions")
        tap("Market & levels")
        tap("how is nifty")
        compose.waitForText("Ask Ira about the market")
        assertFalse(shows("Search questions"))
        compose.until(20_000, "the question asked") { IraHub.state.value.messages.any { !it.fromIra && it.text == "how is nifty" } }
        compose.until(20_000, "its answer") { IraHub.state.value.messages.lastOrNull()?.fromIra == true }
    }

    /** Settings → What can I ask?: a tap asks the question as typed, then hands over to the chat. */
    @Test fun theSettingsPageAsksAndOpensTheChat() {
        val went = ArrayList<String>()
        set { AskGuidePage(onAsked = { went += it }) }
        compose.onNodeWithText("What can I ask?").assertIsDisplayed()
        tap("App")
        tap("what's new in the app")
        assertEquals(listOf("what's new in the app"), went)
        compose.until(20_000, "the question asked") { IraHub.state.value.messages.any { !it.fromIra && it.text == "what's new in the app" } }
    }
}
