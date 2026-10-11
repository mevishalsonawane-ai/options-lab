package com.optionslab.app.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performScrollToNode
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
import com.optionslab.ira.AutoChange
import com.optionslab.ira.Experiments
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * Home's Autopilot card and its change log: today's changes, on probation and reverted; each change with its reason, scope,
 * risk and status; Undo (with "PIN" where it raises live risk); the title and Close never overlap upright, on its side or at
 * large font; and the setting's three levels.
 */
@Config(qualifiers = "w411dp-h2400dp")
@RunWith(AndroidJUnit4::class)
class AutopilotCardTest {
    @get:Rule val watchdog = AreaEWatchdog()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val day = LocalDate.of(2026, 10, 12)

    private fun change(scope: AutoChange.Scope, target: String, what: String) = AutoChange.Change(AutoChange.Kind.SPECIALIST_MUTE, target,
        AutoChange.COUNTS, AutoChange.MUTED, "it hurt -Rs 4,000 over 40 decisions", AutoChange.Evidence(40, -4_000.0, AgentLessons.Confidence.STRONG, -1),
        AutoChange.Risk.LOWER, scope, expect = -1, effect = -1, what = what)

    private val book: AutoChange.Book by lazy {
        var b = AutoChange.Book()
        b = AutoChange.record(b, change(AutoChange.Scope.PAPER, "spec:pine:vwap", "the VWAP specialist on Pine (paper)"), AutoChange.Status.PROBATION, 1, day).first
        b = AutoChange.record(b, change(AutoChange.Scope.LIVE, "spec:pine:oi", "the OI specialist on Pine (live)"), AutoChange.Status.PROBATION, 2, day).first
        b = AutoChange.record(b, change(AutoChange.Scope.PAPER, "spec:night:oi", "the OI specialist on Night (paper)"), AutoChange.Status.PROBATION, 0, day.minusDays(9)).first
        AutoChange.review(b, day) { e -> if (e.change.target == "spec:night:oi") List(15) { 500.0 + it } else null }.first
    }

    /** Smarter learning: a probation with its q, its sequential test and its pre-flight; a skipped change; a paper experiment; 9 trials. */
    private val smart: AutoChange.Book by lazy {
        var b = AutoChange.Book(trials = 7)
        b = AutoChange.record(b, change(AutoChange.Scope.PAPER, "spec:pine:vwap", "the VWAP specialist on Pine (paper)"), AutoChange.Status.PROBATION, 1, day,
            q = 0.004, preflight = "pre-flight passed: 12 cases replayed, +Rs 900 with the change against -Rs 300 without").first
        b = AutoChange.review(b, day) { List(6) { i -> if (i % 2 == 0) -300.0 else 250.0 } }.first
        b = AutoChange.record(b, change(AutoChange.Scope.PAPER, "spec:solo:oi", "the OI specialist on Solo (paper)"), AutoChange.Status.SKIPPED, 2, day,
            note = "pre-flight failed: 20 cases replayed, -Rs 2,000 with the change against +Rs 400 without").first
        Experiments.start(b, change(AutoChange.Scope.PAPER, "spec:night:oi", "the OI specialist on Night (paper)"), day, 3)
    }

    @Before fun up() { AreaE.resetGlobals() }
    @After fun down() { AreaE.resetGlobals(); RuntimeEnvironment.setFontScale(1f) }

    private fun set(content: @Composable () -> Unit) { compose.setContent { IraAlgoTheme("light") { content() } }; compose.frames() }
    private fun shows(t: String) = compose.onAllNodesWithText(t, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun tap(t: String) { compose.onNodeWithText(t, substring = true).performSemanticsAction(SemanticsActions.OnClick); compose.frames(); compose.waitForIdle() }
    private fun noOverlap(a: DpRect, b: DpRect) = a.right <= b.left || b.right <= a.left || a.bottom <= b.top || b.bottom <= a.top
    private fun titleAndCloseApart() = assertTrue(noOverlap(compose.onNodeWithTag("autopilot-log-title").getBoundsInRoot(),
        compose.onNodeWithTag("autopilot-log-close").getBoundsInRoot()))

    @Test fun theCardSaysTodayProbationAndRevertedAndOpensTheLog() {
        var opened = false
        set { AutopilotCardContent(book, AutoChange.Mode.SAFE, day, onOpen = { opened = true }) }
        assertTrue(shows(AUTOPILOT_TITLE))
        assertTrue(shows("Autopilot learning: safe"))
        assertTrue(shows("Today: 2 changes (1 paper, 0 shadow, 1 live risk-lowering)"))
        assertTrue(shows("the OI specialist on Pine (live): counts → muted"))
        assertTrue(shows("On probation 2 · kept 0 · reverted 1"))
        tap("Change log")
        assertTrue(opened)
    }

    @Test fun theLogShowsEachChangeWithItsReasonAndUndo() {
        val asked = ArrayList<Pair<LogAction, String>>()
        var closed = false
        set { AutopilotLogSheet(book, null, onAction = { a, id -> asked += a to id }, onClose = { closed = true }) }
        assertTrue(shows(AUTOPILOT_LOG_TITLE))
        assertTrue(shows("Why: it hurt -Rs 4,000 over 40 decisions"))
        assertTrue(shows("live · risk-lowering · on probation"))
        assertTrue(shows("reverted"))
        // Undoing the live one adds live risk: its button says PIN; the paper one does not.
        assertTrue(shows("Undo (PIN)"))
        val live = book.entries.first { it.change.target == "spec:pine:oi" }
        tap("Undo (PIN)")
        assertEquals(listOf(LogAction.UNDO to live.id), asked)
        assertTrue(AutoChange.undoNeedsPin(live.change))
        titleAndCloseApart()
        tap("Close")
        assertTrue(closed)
    }

    @Config(qualifiers = "w891dp-h411dp-land")
    @Test fun onItsSideTheTitleAndCloseKeepApart() {
        set { AutopilotLogSheet(book, null, onAction = { _, _ -> }, onClose = {}) }
        titleAndCloseApart()
        // Landscape is short: the list scrolls to the paper change (a lazy list composes only what is on screen).
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("the VWAP specialist on Pine (paper): counts → muted", substring = true))
        assertTrue(shows("the VWAP specialist on Pine (paper): counts → muted"))
    }

    /** The log scrolled to the row holding [t] (a lazy list composes only what is on screen), then [t] shown. */
    private fun scrolledShows(t: String): Boolean {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(t, substring = true))
        compose.frames()
        return shows(t)
    }

    @Test fun theLogShowsTrialsQTheTestThePreflightAndExperiments() {
        set { AutopilotLogSheet(smart, null, onAction = { _, _ -> }, onClose = {}) }
        // The header: every configuration tried so far, tonight's bar, the experiments running.
        assertTrue(shows("Trials so far: 9 · tonight's bar q ≤ 0.07 · 1 experiment running"))
        // The experiment, marked as one, paper only, with its pairs and its test.
        assertTrue(scrolledShows("the OI specialist on Night (paper): counts → muted"))
        assertTrue(scrolledShows("experiment · paper only · running"))
        assertTrue(scrolledShows("0 pairs on alternate paper trades"))
        // The probation: its lesson's q, the sequential test's state, the pre-flight it passed.
        assertTrue(scrolledShows("Lesson: q 0.004"))
        assertTrue(scrolledShows("% of the way (leaning keep), 6 cases"))
        assertTrue(scrolledShows("Pre-flight passed: 12 cases replayed"))
        // The skipped one: why, and no Undo (nothing was changed).
        assertTrue(scrolledShows("pre-flight failed: 20 cases replayed"))
        val skipped = smart.entries.first { it.status == AutoChange.Status.SKIPPED }
        assertTrue(compose.onAllNodesWithTag("undo-" + skipped.id).fetchSemanticsNodes().isEmpty())
        val kept = smart.entries.first { it.status == AutoChange.Status.PROBATION }
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("undo-" + kept.id))
        assertTrue(compose.onAllNodesWithTag("undo-" + kept.id).fetchSemanticsNodes().isNotEmpty())
    }

    @Config(qualifiers = "w891dp-h411dp-land")
    @Test fun onItsSideTheExperimentAndTheTestAreReachedByScrolling() {
        set { AutopilotLogSheet(smart, null, onAction = { _, _ -> }, onClose = {}) }
        titleAndCloseApart()
        assertTrue(shows("Trials so far: 9"))
        assertTrue(scrolledShows("experiment · paper only · running"))
        assertTrue(scrolledShows("Pre-flight passed: 12 cases replayed"))
        assertTrue(scrolledShows("pre-flight failed: 20 cases replayed"))
    }

    @Test fun atLargeFontTheTitleAndCloseKeepApart() {
        RuntimeEnvironment.setFontScale(2f)
        set { AutopilotLogSheet(book, "Undone.", onAction = { _, _ -> }, onClose = {}) }
        titleAndCloseApart()
    }

    @Test fun atLargeFontTheCardsLinesKeepApart() {
        RuntimeEnvironment.setFontScale(2f)
        set { AutopilotCardContent(book, AutoChange.Mode.SHADOW_ONLY, day, onOpen = {}) }
        assertTrue(noOverlap(compose.onNodeWithTag("autopilot-today").getBoundsInRoot(), compose.onNodeWithTag("autopilot-counts").getBoundsInRoot()))
        assertTrue(shows("Change log"))
    }

    @Test fun anEmptyLogSaysSo() {
        set { AutopilotLogSheet(AutoChange.Book(), null, onAction = { _, _ -> }, onClose = null) }
        assertTrue(shows("No changes yet."))
    }

    @Test fun anEmptyCardSaysSo() {
        set { AutopilotCardContent(AutoChange.Book(), AutoChange.Mode.OFF, day, onOpen = {}) }
        assertTrue(shows("No changes today.")); assertTrue(shows("nothing changes by itself"))
    }

    @Test fun theSettingsThreeLevels() {
        val picked = ArrayList<AutoChange.Mode>()
        set { androidx.compose.foundation.layout.Column { AutopilotModeContent(AutoChange.Mode.SAFE, null) { picked += it } } }
        assertTrue(shows("AUTOPILOT LEARNING"))
        for (t in listOf("OFF", "SHADOW-ONLY")) {
            compose.onNodeWithText(t).performSemanticsAction(SemanticsActions.OnClick); compose.frames()
        }
        assertEquals(listOf(AutoChange.Mode.OFF, AutoChange.Mode.SHADOW_ONLY), picked)
    }
}
