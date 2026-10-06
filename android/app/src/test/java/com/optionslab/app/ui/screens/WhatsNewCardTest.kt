package com.optionslab.app.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.data.WhatsNewStore
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.DeviceConfig
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.ScreenTest
import com.optionslab.app.testing.reveal
import com.optionslab.app.ui.Load
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.ira.WhatsNew
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/** The "What's new" fixtures: five changes over two days, some with a page to open and a question to try. */
internal object NewsFixtures {
    private val d5 = LocalDate.of(2026, 10, 5)
    private val d6 = LocalDate.of(2026, 10, 6)
    val five = listOf(
        WhatsNew.Entry("t-chart", d6, "Levels on the chart", "The chart shows 15+5's levels. Tap one for its card.", "Chart tab → Liquidity levels",
            ask = "where are the liquidity levels", to = "chart"),
        WhatsNew.Entry("t-lots", d6, "Size: 1 to 3 lots", "Choose 1, 2 or 3 lots (2 at first).", "Home → Dashboard → Strategies card"),
        WhatsNew.Entry("t-pnl", d6, "Live vs backtest", "Paper trades against the research.", "P&L tab → Live vs backtest", to = "pnl"),
        WhatsNew.Entry("t-pine", d5, "Pine stop and target", "Every script has a 30 stop and a 60 target.", "Research tab → Pine scripts", to = "pine"),
        WhatsNew.Entry("t-glance", d5, "Today at a glance", "A card at the top of Home.", "Home → Dashboard, at the top"),
    )
}

@RunWith(AndroidJUnit4::class)
@org.robolectric.annotation.Config(qualifiers = "w411dp-h2400dp")   // the open card and Home's first cards on one screen
class WhatsNewCardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule(order = 100) val dump = com.optionslab.app.testing.DumpOnFailure(compose)
    @get:Rule val watchdog = com.optionslab.app.testing.ResearchWatchdog()

    @Before fun nothingSeen() { SecurePrefs.put(WhatsNewStore.KEY, null); WhatsNewStore.forget() }
    @After fun noNetwork() {
        SecurePrefs.put(WhatsNewStore.KEY, null); WhatsNewStore.forget()
        assertEquals("no host may be reached", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun set(content: @Composable () -> Unit) = compose.setContent { IraAlgoTheme("light") { content() } }
    private fun text(t: String) = compose.onNodeWithText(keepNumbersWhole(t))
    private fun shows(t: String) = compose.onAllNodesWithText(keepNumbersWhole(t)).fetchSemanticsNodes().isNotEmpty()

    @Test fun threeNewestFirstThenShowAll() {
        set { WhatsNewCardContent(NewsFixtures.five, onGotIt = {}, onGo = {}) }
        text("What's new").assertIsDisplayed()
        text("5 changes since you last looked").assertIsDisplayed()
        text("Levels on the chart ›").assertIsDisplayed()
        text("The chart shows 15+5's levels. Tap one for its card.").assertIsDisplayed()
        text("Where: Chart tab → Liquidity levels").assertIsDisplayed()
        text("Ask Jarvis: \"where are the liquidity levels\"").assertIsDisplayed()
        text("Size: 1 to 3 lots").assertIsDisplayed()
        text("Where: Home → Dashboard → Strategies card").assertIsDisplayed()
        text("Live vs backtest ›").assertIsDisplayed()
        assertFalse(shows("Pine stop and target ›"))
        assertFalse(shows("Today at a glance"))
        // The newest first: the 6 Oct changes above the 5 Oct ones.
        val top = { t: String -> compose.onNodeWithText(keepNumbersWhole(t)).fetchSemanticsNode().boundsInRoot.top }
        assertTrue(top("Levels on the chart ›") < top("Size: 1 to 3 lots"))
        text("Show all (2 more) ▾").performClick()
        compose.waitForIdle()
        text("Pine stop and target ›").assertIsDisplayed()
        text("Today at a glance").assertIsDisplayed()
        assertEquals(2, compose.onAllNodesWithText("5 Oct").fetchSemanticsNodes().size)
        assertEquals(3, compose.onAllNodesWithText("6 Oct").fetchSemanticsNodes().size)
        assertFalse(shows("Show all (2 more) ▾"))
        assertTrue(top("Live vs backtest ›") < top("Pine stop and target ›"))
    }

    @Test fun aTapOpensThePageAndGotItCallsBack() {
        val went = ArrayList<String>()
        var got = 0
        set { WhatsNewCardContent(NewsFixtures.five, onGotIt = { got++ }, onGo = { went += it }) }
        text("Levels on the chart ›").performClick()
        text("Live vs backtest ›").performClick()
        // A change with no page to open is no button.
        text("Size: 1 to 3 lots").performClick()
        compose.waitForIdle()
        assertEquals(listOf("chart", "pnl"), went)
        text("Got it").performClick()
        compose.waitForIdle()
        assertEquals(1, got)
    }

    @Test fun oneChangeIsSaidAsOneAndNeedsNoShowAll() {
        set { WhatsNewCardContent(NewsFixtures.five.take(1), onGotIt = {}, onGo = {}) }
        text("1 change since you last looked").assertIsDisplayed()
        assertFalse(compose.onAllNodesWithText("Show all", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    /** Home as the app builds it: the card from the settings' seen ids, above "Today at a glance"; "Got it" hides it for good. */
    @Composable private fun HomeWithNews(went: MutableList<String>) {
        val news by remember { WhatsNewStore.shown() }.collectAsState()
        AlmanacContent(false, false, emptyMap(), null, emptyList(), Load.Idle, Load.Done(HomeFixtures.paper), {}, {},
            glance = { TodayGlanceContent(GlanceFixtures.inSession, false, {}, {}) },
            whatsNew = if (news.isNotEmpty()) {
                { WhatsNewCardContent(news, onGotIt = { WhatsNewStore.markAllSeen() }, onGo = { went += it }) }
            } else null) { Text("strategies card") }
    }

    @Test fun homeShowsTheUnseenAboveTheGlanceUntilGotIt() {
        val entries = WhatsNewStore.entries()
        org.junit.Assume.assumeTrue("IraGoldAlgo shows none of today's changes", entries.isNotEmpty())
        var mounted by mutableStateOf(true)
        val went = ArrayList<String>()
        set { if (mounted) HomeWithNews(went) }
        compose.waitForIdle()
        text("What's new").assertIsDisplayed()
        text("${entries.size} changes since you last looked").assertIsDisplayed()
        text(entries[0].title + if (entries[0].to != null) " ›" else "").assertIsDisplayed()
        val top = { t: String -> compose.onNodeWithText(t).fetchSemanticsNode().boundsInRoot.top }
        assertTrue("the card is above Today at a glance", top("What's new") < top("Today at a glance"))
        text("Got it").performClick()
        compose.waitForIdle()
        assertFalse(shows("What's new"))
        text("Today at a glance").assertIsDisplayed()
        // Kept: every change this build shows is seen, and Home opened again shows no card.
        assertTrue(WhatsNewStore.unseen().isEmpty())
        assertEquals(entries.map { it.id }.toSet(), WhatsNew.decode(SecurePrefs.getString(WhatsNewStore.KEY)))
        compose.runOnIdle { mounted = false }
        compose.waitForIdle()
        compose.runOnIdle { mounted = true }
        compose.waitForIdle()
        text("Today at a glance").assertIsDisplayed()
        assertFalse(shows("What's new"))
        assertTrue(went.isEmpty())
    }

    @Test fun aNewChangeAfterGotItIsShownAlone() {
        val entries = WhatsNewStore.entries()
        org.junit.Assume.assumeTrue(entries.size >= 2)
        // Seen all but the newest (as after an update that brought one more change).
        SecurePrefs.put(WhatsNewStore.KEY, WhatsNew.encode(entries.drop(1).map { it.id }.toSet()))
        WhatsNewStore.forget()
        assertEquals(listOf(entries[0].id), WhatsNewStore.unseen().map { it.id })
        set { HomeWithNews(ArrayList()) }
        compose.waitForIdle()
        text("1 change since you last looked").assertIsDisplayed()
        assertFalse(compose.onAllNodesWithText("Show all", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    /** The Ira page (Home opens on it in Jarvis): the same card above it while changes are unseen; "Got it" hides it. */
    @Test fun theIraPageShowsTheCardAboveItUntilGotIt() {
        val entries = WhatsNewStore.entries()
        org.junit.Assume.assumeTrue("IraGoldAlgo shows none of today's changes", entries.isNotEmpty())
        set { WhatsNewOverPage(onGo = null) { Text("the ira page") } }
        compose.waitForIdle()
        text("What's new").assertIsDisplayed()
        text("${entries.size} changes since you last looked").assertIsDisplayed()
        text("the ira page").assertIsDisplayed()
        val top = { t: String -> compose.onNodeWithText(t).fetchSemanticsNode().boundsInRoot.top }
        assertTrue("the card is above the page", top("What's new") < top("the ira page"))
        // With no page to open from here, a change is no button (nothing navigates).
        text(entries[0].title).assertIsDisplayed()
        text("Got it").performClick()
        compose.waitForIdle()
        assertFalse(shows("What's new"))
        text("the ira page").assertIsDisplayed()
        assertTrue(WhatsNewStore.unseen().isEmpty())
        assertTrue(WhatsNewStore.shown().value.isEmpty())
    }

    @Test fun nothingUnseenLeavesTheIraPageAsItWas() {
        SecurePrefs.put(WhatsNewStore.KEY, WhatsNew.encode(WhatsNew.ENTRIES.map { it.id }.toSet()))
        WhatsNewStore.forget()
        set { WhatsNewOverPage(onGo = null) { Text("the ira page") } }
        compose.waitForIdle()
        assertFalse(shows("What's new"))
        assertEquals(0f, compose.onNodeWithText("the ira page").fetchSemanticsNode().boundsInRoot.top, 0.5f)
    }

    /** One list for both places: "Got it" on the Ira page hides the Dashboard's card too. */
    @Test fun gotItInOnePlaceHidesBoth() {
        org.junit.Assume.assumeTrue(WhatsNewStore.entries().isNotEmpty())
        set {
            Column {
                Box(Modifier.height(700.dp)) { WhatsNewOverPage(onGo = null) { Text("the ira page") } }
                Box(Modifier.height(1500.dp)) { HomeWithNews(ArrayList()) }
            }
        }
        compose.waitForIdle()
        assertEquals(2, compose.onAllNodesWithText("What's new").fetchSemanticsNodes().size)
        compose.onAllNodesWithText("Got it").fetchSemanticsNodes().let { assertEquals(2, it.size) }
        compose.onAllNodesWithText("Got it")[0].performClick()
        compose.waitForIdle()
        assertFalse(shows("What's new"))
        text("the ira page").assertIsDisplayed()
        text("Today at a glance").assertIsDisplayed()
    }

    @Test fun theSettingsPageListsEveryChange() {
        val all = WhatsNew.forBuild(gold = false, jarvis = true)
        set { WhatsNewPage(all) }
        compose.waitForIdle()
        text("What changed in recent updates, and where to find it").assertIsDisplayed()
        text(all.first().title).assertIsDisplayed()
        compose.reveal(keepNumbersWhole(all.last().title))
        text(all.last().title).assertIsDisplayed()
        compose.reveal(keepNumbersWhole(WhatsNew.whereLine(all.last())))
        // The page lists; it opens nothing and marks nothing seen.
        assertEquals(null, SecurePrefs.getString(WhatsNewStore.KEY))
    }
}

/** The card and the Settings page on the six research set-ups (a small phone at font 2.0, landscape, a tablet): layout lint. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WhatsNewLayoutTest(device: DeviceConfig) : ScreenTest(device) {
    @get:Rule val watchdog = com.optionslab.app.testing.ResearchWatchdog()
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun configs(): List<Array<Any>> = com.optionslab.app.testing.ResearchMatrix.six()

        val BUGS = emptyMap<String, String>()
    }

    @After fun noNetwork() { assertEquals(emptyList<String>(), NetworkGuard.blocked.toList()) }

    @Composable private fun home() {
        AlmanacContent(false, false, mapOf("BANKNIFTY" to HomeFixtures.quote), null, HomeFixtures.daily, Load.Idle, Load.Done(HomeFixtures.paper), {}, {},
            glance = { TodayGlanceContent(GlanceFixtures.inSession, false, {}, {}) },
            whatsNew = { WhatsNewCardContent(NewsFixtures.five, {}, {}) }) {}
    }

    @Test fun homeWhatsNew() {
        checkScreen("home-whatsnew", BUGS) { home() }
        if (device.name == "phone-font1.3-light") smokeEveryAction()
    }

    @Test fun whatsNewPage() = checkScreen("whatsnew-page", BUGS) { WhatsNewPage(WhatsNew.forBuild(gold = false, jarvis = true)) }
}
