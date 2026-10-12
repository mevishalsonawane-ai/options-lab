package com.optionslab.app.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.data.ManagerSpecialists
import com.optionslab.app.testing.AreaE
import com.optionslab.app.testing.AreaEWatchdog
import com.optionslab.app.testing.frames
import com.optionslab.app.ui.components.expandTag
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.ira.ManagerTeam
import com.optionslab.ira.SpecialistBook
import com.optionslab.ira.TradeManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Boss (12 Oct, a screenshot of a wall of text): the Specialists sheet folded. Collapsed by default: one intro line, each
 * trade's title with the Chair's call and its first reason, and the roster's one line (how many muted, the hard vetoes);
 * "Show details" opens the top three and every vote, "Show all 16 specialists" each one with its Mute button; a second tap
 * folds it again. What was said (a save's answer) is never folded.
 */
@Config(qualifiers = "w411dp-h2400dp")
@RunWith(AndroidJUnit4::class)
class SpecialistsSheetTest {
    @get:Rule val watchdog = AreaEWatchdog()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val family = TradeManager.FAMILIES.first().key

    private val view = ManagerSpecialists.View("t1", family, "SILVERM morning call", 0L,
        listOf(ManagerTeam.Vote("data", ManagerTeam.Kind.HOLD, 0, "feed fresh"),
            ManagerTeam.Vote("traps", ManagerTeam.Kind.EXIT, 40, "orders pulled", needsHoldSec = 10)),
        "Chair: EXIT (Traps) - top reasons: Traps: orders pulled; Data health: feed fresh",
        listOf("Traps: orders pulled", "Data health: feed fresh"), frozen = false)

    private val book = ManagerSpecialists.Book(tuned = mapOf(family to SpecialistBook.Tuning(muted = mapOf("traps" to "it hurt"))))

    @Before fun up() { AreaE.resetGlobals() }
    @After fun down() { AreaE.resetGlobals(); RuntimeEnvironment.setFontScale(1f) }

    private val muted = ArrayList<Pair<String, String>>()
    private val unmuted = ArrayList<Pair<String, String>>()

    private fun set(content: @Composable () -> Unit) { compose.setContent { IraAlgoTheme("light") { content() } }; compose.frames(); compose.waitForIdle() }
    private fun sheet(said: String? = null) = set {
        SpecialistsContent(book, mapOf(view.tradeId to view), emptyList(), TradeManager.Policy(), said,
            onMute = { f, id -> muted += f to id }, onUnmute = { f, id -> unmuted += f to id }, onAccept = {}, onClose = {})
    }
    private fun shows(t: String) = compose.onAllNodesWithText(t, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun toggle(key: String) {
        compose.onNodeWithTag(expandTag(key)).performSemanticsAction(SemanticsActions.OnClick); compose.frames(); compose.waitForIdle()
    }
    private fun state(key: String) = compose.onNodeWithTag(expandTag(key)).fetchSemanticsNode().config[SemanticsProperties.StateDescription]

    @Test fun collapsedByDefaultATradeIsItsTitleAndTheChairsFirstReason() {
        sheet(said = "Could not save that.")
        // The intro: one line; the rest folded.
        assertTrue(shows("Sixteen specialists vote; the chair weighs them. Muting needs no PIN."))
        assertFalse(shows("still order an exit alone"))
        // The trade: its title and one Chair line; no numbered list, no vote lines.
        assertTrue(shows("SILVERM morning call"))
        assertTrue(shows("Chair: EXIT (Traps) — top reason: Traps: orders pulled"))
        assertFalse(shows("1. Traps: orders pulled"))
        assertFalse(shows("HOLD - feed fresh"))
        assertEquals("Collapsed", state("spec-trade:t1"))
        // The roster: one line with the muted one and the hard vetoes in sight; no rows, no report cards.
        assertTrue(shows("16 specialists · 1 muted (Traps) · 3 hard vetoes"))
        assertFalse(shows("No decision voted yet."))
        // What was said is never folded.
        assertTrue(shows("Could not save that."))
    }

    @Test fun aTapOpensTheTopThreeAndEveryVoteAndASecondTapFoldsThem() {
        sheet()
        toggle("spec-trade:t1")
        assertEquals("Expanded", state("spec-trade:t1"))
        assertTrue(shows("1. Traps: orders pulled"))
        assertTrue(shows("2. Data health: feed fresh"))
        assertTrue(shows("Data health: HOLD - feed fresh"))
        assertTrue(shows("Traps: EXIT 40 - orders pulled (needs 10 s)"))
        assertTrue(shows("Hide details ▴"))
        toggle("spec-trade:t1")
        assertFalse(shows("1. Traps: orders pulled"))
        assertFalse(shows("HOLD - feed fresh"))
        assertTrue(shows("Chair: EXIT (Traps) — top reason: Traps: orders pulled"))
        // The intro opens and folds the same way.
        toggle("spec-intro")
        assertTrue(shows("still order an exit alone"))
        toggle("spec-intro")
        assertFalse(shows("still order an exit alone"))
    }

    @Test fun theRosterOpensWithEveryMuteButtonReachable() {
        sheet()
        toggle("spec-roster:$family")
        assertTrue(shows("Hide specialists ▴"))
        assertEquals(ManagerTeam.SPECIALISTS.size, compose.onAllNodesWithText("Weight ", substring = true, useUnmergedTree = true).fetchSemanticsNodes().size)
        assertTrue(shows("No decision voted yet."))
        // 15 Mute buttons and one Un-mute (Traps): nothing about muting changed, only where it sits.
        val mutes = compose.onAllNodesWithText("Mute")
        assertEquals(ManagerTeam.SPECIALISTS.size - 1, mutes.fetchSemanticsNodes().size)
        mutes[0].performSemanticsAction(SemanticsActions.OnClick); compose.frames(); compose.waitForIdle()
        assertEquals(listOf(family to ManagerTeam.SPECIALISTS.first().id), muted)
        compose.onAllNodesWithText("Un-mute")[0].performSemanticsAction(SemanticsActions.OnClick); compose.frames(); compose.waitForIdle()
        assertEquals(listOf(family to "traps"), unmuted)
        // Folded again: the rows go, the one line with the muted count stays.
        toggle("spec-roster:$family")
        assertFalse(shows("No decision voted yet."))
        assertTrue(shows("1 muted (Traps)"))
    }

    @Config(qualifiers = "w320dp-h640dp")
    @Test fun atLargeFontOnASmallPhoneTheTogglesStayFullSizeTargets() {
        RuntimeEnvironment.setFontScale(2f)
        sheet()
        for (key in listOf("spec-intro", "spec-trade:t1", "spec-roster:$family")) {
            // Below the fold at font 2.0: scrolled into view first (a clipped node measures 0 high).
            // (the intro's sits above the scrolling part, so only the others scroll)
            val node = compose.onNodeWithTag(expandTag(key))
            runCatching { node.performScrollTo() }
            val b = node.getBoundsInRoot()
            assertTrue("$key is ${b.bottom - b.top} high", b.bottom - b.top >= 47.5.dp)
            assertTrue("$key is cut on the right: $b", b.right <= 320.dp)
        }
        toggle("spec-trade:t1")
        assertTrue(shows("1. Traps: orders pulled"))
    }
}
