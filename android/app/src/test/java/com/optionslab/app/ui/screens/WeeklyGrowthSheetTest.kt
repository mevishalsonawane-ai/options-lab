package com.optionslab.app.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.DpRect
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.testing.AreaE
import com.optionslab.app.testing.AreaEWatchdog
import com.optionslab.app.testing.frames
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.ira.AgentLessons
import com.optionslab.ira.GrowthReport
import com.optionslab.ira.Insights
import com.optionslab.ira.LearningGoals
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * Jarvis's weekly report sheet and the learning goals in "What Jarvis has learned": every part of the week from the records,
 * the title and Close never overlapping upright, on its side or at large font, and the lazy lists scrolled to each item
 * before it is checked.
 */
@Config(qualifiers = "w411dp-h891dp")
@RunWith(AndroidJUnit4::class)
class WeeklyGrowthSheetTest {
    @get:Rule val watchdog = AreaEWatchdog()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val mon = LocalDate.of(2026, 10, 12)
    private val week = GrowthReport.Week(mon, learned = 3, retired = 1, strongNow = 4, buildingNow = 6, newest = listOf("Liquidity 15+5 makes money"),
        made = 2, kept = 1, reverted = 1, effectRs = 1_200.0, effectN = 21, muted = listOf("the OI specialist on Pine (paper)"),
        stalls = 5, anrs = 1, fastestDay = mon.plusDays(1), fastestMs = 180, slowestDay = mon.plusDays(3), slowestMs = 420,
        goals = listOf("Need 12 more Liquidity 15+5 trades to judge the OI specialist" to 6), met = 1,
        next = listOf("Need 12 more Liquidity 15+5 trades to judge the OI specialist"))

    @Before fun up() { AreaE.resetGlobals() }
    @After fun down() { AreaE.resetGlobals(); RuntimeEnvironment.setFontScale(1f) }

    private fun set(content: @Composable () -> Unit) { compose.setContent { IraAlgoTheme("light") { content() } }; compose.frames() }
    private fun shows(t: String) = compose.onAllNodesWithText(t, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun scrollTo(t: String) { compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(t, substring = true)); compose.frames() }
    private fun tap(t: String) { compose.onNodeWithText(t, substring = true).performSemanticsAction(SemanticsActions.OnClick); compose.frames(); compose.waitForIdle() }
    private fun noOverlap(a: DpRect, b: DpRect) = a.right <= b.left || b.right <= a.left || a.bottom <= b.top || b.bottom <= a.top
    private fun titleAndCloseApart() = assertTrue(noOverlap(compose.onNodeWithTag("weekly-title").getBoundsInRoot(), compose.onNodeWithTag("weekly-close").getBoundsInRoot()))

    private fun everyPart() {
        assertTrue(shows("Week of 12–16 Oct"))
        for ((item, line) in listOf(
            "Lessons" to "3 lessons learned or confirmed, 1 retired. Now 4 strong and 6 building.",
            "Autopilot" to "2 changes made, 1 kept and 1 reverted this week.",
            "Specialists" to "Muted: the OI specialist on Pine (paper).",
            "App health" to "5 stalls of 0.7 s or more, 1 ANR, no crash.",
            "App health" to "fastest 180 ms (Tue 13 Oct), slowest 420 ms (Thu 15 Oct)",
            "Learning goals" to "Need 12 more Liquidity 15+5 trades to judge the OI specialist: +6 since it was set.",
            "Next week" to "I'll try to learn: Need 12 more Liquidity 15+5 trades to judge the OI specialist.",
        )) {
            scrollTo(line)
            assertTrue("$item: $line", shows(line))
        }
        scrollTo("Their effect against what would have happened without them: +Rs 1,200 over 21 settled cases.")
        assertTrue(shows("+Rs 1,200 over 21 settled cases"))
    }

    @Test fun uprightEveryPartFromTheRecordsAndClose() {
        var closed = false
        set { WeeklyGrowthSheet(week, onClose = { closed = true }) }
        assertTrue(shows(WEEKLY_REPORT_TITLE))
        titleAndCloseApart()
        everyPart()
        tap("Close")
        assertTrue(closed)
    }

    @Config(qualifiers = "w891dp-h411dp-land")
    @Test fun onItsSideTheTitleAndCloseKeepApartAndThePartsScroll() {
        set { WeeklyGrowthSheet(week, onClose = {}) }
        titleAndCloseApart()
        everyPart()
    }

    @Test fun atLargeFontTheTitleAndCloseKeepApart() {
        RuntimeEnvironment.setFontScale(2f)
        set { WeeklyGrowthSheet(week, onClose = {}) }
        titleAndCloseApart()
        everyPart()
    }

    @Test fun noReportYetSaysSo() {
        set { WeeklyGrowthSheet(null, onClose = null) }
        assertTrue(shows("No weekly report yet"))
        assertTrue(!shows("Close"))
    }

    @Test fun whatJarvisHasLearnedShowsTheGoals() {
        val d0 = LocalDate.of(2026, 8, 1)
        val trips = (0 until 40).map { k -> val d = d0.plusDays(k.toLong()); Insights.Trip("X", d.atTime(9, 0), d.atTime(9, 20), 250.0, "Liquidity 15+5") }
        val book = AgentLessons.pass(AgentLessons.Book(), AgentLessons.Inputs(d0.plusDays(40), trips = trips)).book
        val oi = AgentLessons.Lesson("spec:liquidity:oi", AgentLessons.Topic.SPECIALIST, AgentLessons.Kind.RUPEES, "The OI specialist on Liquidity 15+5",
            emptyList(), setOf("the specialists"), (0 until 12).map { AgentLessons.Bucket(d0.plusDays(it.toLong()), 1, -500.0, 0) }, -1,
            AgentLessons.Status.NEW, AgentLessons.Confidence.BUILDING, d0, d0.plusDays(12), d0.plusDays(12))
        val goals = LearningGoals.next(LearningGoals.Book(), listOf(oi), emptyMap(), d0.plusDays(40)).goals
        set { JarvisLearnedSheet(book, onClose = {}, goals = goals) }
        scrollTo(GOALS_TITLE)
        assertTrue(shows(GOALS_TITLE))
        assertTrue(shows("Need 18 more Liquidity 15+5 trades to judge the OI specialist (12 of 30 so far"))
        assertTrue(shows("never a trade"))
    }
}
