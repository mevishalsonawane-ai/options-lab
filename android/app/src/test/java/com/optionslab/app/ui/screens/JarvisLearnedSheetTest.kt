package com.optionslab.app.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.DpRect
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.testing.AreaE
import com.optionslab.app.testing.AreaEWatchdog
import com.optionslab.app.testing.frames
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.ira.AgentLessons
import com.optionslab.ira.Insights
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * "What Jarvis has learned": the counts by confidence, each topic's skill, the lessons with their confidence chip, status
 * and evidence, the topic chips filtering them, and the growth timeline; the title and Close never overlap, upright or on
 * its side.
 */
@Config(qualifiers = "w411dp-h2400dp")
@RunWith(AndroidJUnit4::class)
class JarvisLearnedSheetTest {
    @get:Rule val watchdog = AreaEWatchdog()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val d0 = LocalDate.of(2026, 8, 1)

    private fun trips(owner: String, days: Int, net: Double, h: Int) = (0 until days).map { k ->
        val d = d0.plusDays(k.toLong()); Insights.Trip("X", d.atTime(h, 0), d.atTime(h, 20), net, owner)
    }

    private val book: AgentLessons.Book by lazy {
        AgentLessons.pass(AgentLessons.Book(), AgentLessons.Inputs(d0.plusDays(40), trips = trips("Liquidity 15+5", 40, 250.0, 9) + trips("Hero", 12, -90.0, 14))).book
    }

    @Before fun up() { AreaE.resetGlobals() }
    @After fun down() { AreaE.resetGlobals() }

    private fun set(content: @Composable () -> Unit) { compose.setContent { IraAlgoTheme("light") { content() } }; compose.frames() }
    private fun shows(t: String) = compose.onAllNodesWithText(t, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun tap(t: String) { compose.onNodeWithText(t, substring = true).performSemanticsAction(SemanticsActions.OnClick); compose.frames(); compose.waitForIdle() }

    private fun noOverlap(a: DpRect, b: DpRect) = a.right <= b.left || b.right <= a.left || a.bottom <= b.top || b.bottom <= a.top

    @Test fun lessonsByTopicWithTheirChipsEvidenceAndTimeline() {
        var closed = false
        set { JarvisLearnedSheet(book, onClose = { closed = true }) }
        assertTrue(shows(LEARNED_TITLE))
        assertTrue(shows("Strong · 2")); assertTrue(shows("Building · 2"))
        assertTrue(shows("Liquidity 15+5 makes money"))
        assertTrue(shows("40 trades, +Rs 10,000, 100% won"))
        assertTrue(shows("Strategies · confirmed"))
        assertTrue(shows("Skills")); assertTrue(shows("Strategies: strong (52 trades"))
        assertTrue(shows("Growth timeline")); assertTrue(shows("First strong lesson"))
        // A topic chip filters; tapped again, all.
        tap("Time of day · 2")
        assertTrue(shows("entered in the morning (before 10:30) makes money"))
        assertFalse(shows("Strategies · confirmed"))
        tap("Time of day · 2")
        assertTrue(shows("Strategies · confirmed"))
        tap("Close")
        assertTrue(closed)
        // The title and Close keep apart.
        assertTrue(noOverlap(compose.onNodeWithTag("learned-title").getBoundsInRoot(), compose.onNodeWithTag("learned-close").getBoundsInRoot()))
    }

    @Config(qualifiers = "w891dp-h411dp-land")
    @Test fun onItsSideTheTitleAndCloseStillKeepApartAndTheLessonsScroll() {
        set { JarvisLearnedSheet(book, onClose = {}) }
        assertTrue(noOverlap(compose.onNodeWithTag("learned-title").getBoundsInRoot(), compose.onNodeWithTag("learned-close").getBoundsInRoot()))
        assertTrue(shows("Liquidity 15+5 makes money"))
    }

    @Test fun nothingYetSaysSo() {
        set { JarvisLearnedSheet(AgentLessons.Book(), onClose = null) }
        assertTrue(shows("No lessons yet"))
        assertFalse(shows("Close"))
    }

    @Test fun anEmptyCardSaysNothingYet() {
        set { JarvisLearnedCard(AgentLessons.Book(), onOpen = {}) }
        assertTrue(shows("Nothing yet: each evening after the close"))
    }

    @Test fun theCardCountsAndOpens() {
        var opened = false
        set { JarvisLearnedCard(book, onOpen = { opened = true }) }
        assertTrue(shows("lessons from the agents: 2 strong, 2 building"))
        tap("Open")
        assertTrue(opened)
        assertEquals(true, opened)
    }
}
