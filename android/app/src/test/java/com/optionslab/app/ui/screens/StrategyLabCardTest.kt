package com.optionslab.app.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
import com.optionslab.ira.ShadowSim
import com.optionslab.ira.StrategyForge
import com.optionslab.ira.VariantLab
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
import java.time.LocalDate

/**
 * Home's Strategy lab card and its sheet: each strategy's variants with the best against the current rules, the ideas in
 * forward test, the graveyard, what waits for Boss; each idea's rules, backtest table, forward record and why it was retired;
 * Undo on paper, Apply (PIN) on live, Start on paper for a forge survivor; the title and Close never overlap upright, on its
 * side or at large font, and the card's lines keep apart at large font. Lazy lists are scrolled before asserting.
 */
@Config(qualifiers = "w411dp-h2400dp")
@RunWith(AndroidJUnit4::class)
class StrategyLabCardTest {
    // 21 Oct, enhancement round 4: the market clock at noon IST on a trading day unless the test sets its own.
    @get:org.junit.Rule val marketClock = com.optionslab.app.testing.MarketClockRule()
    @get:Rule val watchdog = AreaEWatchdog()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val day = LocalDate.of(2026, 10, 12)

    private val variants: VariantLab.Book by lazy {
        val s = VariantLab.strategy("orb")!!
        val base = s.defaults
        val alive = VariantLab.Variant("V3", "orb", base + ("stopPts" to 35.0), base, day.minusDays(14), 1.0, VariantLab.Status.ALIVE,
            List(14) { 120.0 }, 14_000.0, 12_320.0, 0.21, 1.0)
        val dead = VariantLab.Variant("V2", "orb", base + ("targetPts" to 60.0), base, day.minusDays(30), 1.0, VariantLab.Status.RETIRED,
            List(20) { -200.0 }, why = "worse than the current rules: -Rs 4,000 over 20 days")
        val applied = VariantLab.Change("C5", "orb", "V1", VariantLab.ChangeKind.AUTO_PAPER, VariantLab.ChangeStatus.APPLIED, day, base,
            base + ("until" to 810.0), "over 25 days it made +Rs 9,000 against +Rs 4,000")
        val live = VariantLab.Change("C6", "liquidity", "V9", VariantLab.ChangeKind.LIVE_PROPOSAL, VariantLab.ChangeStatus.OFFERED, day,
            VariantLab.strategy("liquidity")!!.defaults, VariantLab.strategy("liquidity")!!.defaults + ("stopPct" to 0.125), "it beat the current rules")
        VariantLab.Book(runs = mapOf("orb" to VariantLab.Run.PAPER, "liquidity" to VariantLab.Run.LIVE), variants = listOf(alive, dead),
            changes = listOf(applied, live), tried = 9, retiredCount = 4)
    }

    private fun idea(n: Int, stage: StrategyForge.Stage, why: String) = StrategyForge.Record(
        StrategyForge.Candidate(n, day, "BANKNIFTY", ShadowSim.Rules(ShadowSim.Entry.VWAP_RECLAIM, stopPct = 0.2, targetPct = 0.5, noProgressMin = 20),
            StrategyForge.ExitBlock.FIXED), stage,
        StrategyForge.Backtest(52, 100, 6_000.0, 44.0, listOf(2_000.0, -500.0, 4_500.0), 0.012, -30.0, 5_000.0, false,
            listOf("at least 40 trades" to true, "beats its random twin (p < 0.05)" to true)),
        fwd = List(12) { if (it % 3 == 0) -400.0 else 150.0 }, fwdDays = 9, why = why)

    private val forge: StrategyForge.Book by lazy {
        StrategyForge.Book(tried = 61, passed = 4, retired = 2, rejected = 57, proposed = 1, records = listOf(
            idea(12, StrategyForge.Stage.FORWARD, "passed every gate"), idea(7, StrategyForge.Stage.RETIRED, "retired: it lost on live days (p 0.030)"),
            idea(15, StrategyForge.Stage.PROPOSED, "kept passing its forward test")))
    }

    @Before fun up() { AreaE.resetGlobals() }
    @After fun down() { AreaE.resetGlobals(); RuntimeEnvironment.setFontScale(1f) }

    private fun set(content: @Composable () -> Unit) { compose.setContent { IraAlgoTheme("light") { content() } }; compose.frames() }
    private fun shows(t: String) = compose.onAllNodesWithText(t, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun tap(t: String) { compose.onNodeWithText(t, substring = true).performSemanticsAction(SemanticsActions.OnClick); compose.frames(); compose.waitForIdle() }
    private fun noOverlap(a: DpRect, b: DpRect) = a.right <= b.left || b.right <= a.left || a.bottom <= b.top || b.bottom <= a.top
    private fun titleAndCloseApart() = assertTrue(noOverlap(compose.onNodeWithTag("lab-sheet-title").getBoundsInRoot(),
        compose.onNodeWithTag("lab-sheet-close").getBoundsInRoot()))
    private fun scrollTo(t: String) { compose.onNode(hasScrollAction()).performScrollToNode(hasText(t, substring = true)); compose.frames() }
    private fun scrollToTag(t: String) { compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag(t)); compose.frames() }
    /** Scroll to a fold's toggle ([com.optionslab.app.ui.components.Expandable], key [key]) and tap it (Boss, 12 Oct: folded by default). */
    private fun expand(key: String) {
        val tag = com.optionslab.app.ui.components.expandTag(key)
        scrollToTag(tag)
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick); compose.frames(); compose.waitForIdle()
    }

    @Test fun theCardSaysVariantsIdeasTheGraveyardAndWhatWaits() {
        var opened: String? = "none"
        set { StrategyLabCardContent(variants, forge, onOpen = { opened = it }) }
        assertTrue(shows(LAB_TITLE))
        assertTrue(shows("ORB (paper): 1 variant · best V3, stop 40 pts → 35 pts: 14 days, +Rs 120 a day vs current, p 0.210"))
        assertTrue(shows("Liquidity 15+5 (live): 0 variants"))
        assertTrue(shows("In forward test: 1"))
        assertTrue(shows("F12 VWAP reclaim Banknifty 5m: 9 days, 12 trades"))
        assertTrue(shows("Ideas tried 61 · passed 4 · retired 2 · rejected 57"))
        assertTrue(shows("Waiting for you: 1 live change (PIN) · 1 to start on paper"))
        tap("Details")
        assertEquals(null, opened)
    }

    @Test fun anEmptyCardSaysSo() {
        set { StrategyLabCardContent(VariantLab.Book(), StrategyForge.Book(), onOpen = {}) }
        assertTrue(shows("No strategy is running for the lab to tune yet."))
        assertTrue(shows("Ideas tried 0 · passed 0 · retired 0 · rejected 0"))
        assertTrue(shows("Nothing waiting for you."))
    }

    @Test fun theSheetShowsChangesVariantsAndEachIdeasDetails() {
        val asked = ArrayList<Pair<LabAction, String>>()
        var closed = false
        set { StrategyLabSheet(variants, forge, null, null, onAction = { a, id -> asked += a to id }, onClose = { closed = true }) }
        assertTrue(shows(LAB_SHEET_TITLE))
        titleAndCloseApart()
        scrollToTag("lab-undo-C5")
        // The intro also says "with Undo": the button is tapped by its tag.
        compose.onNodeWithTag("lab-undo-C5").performSemanticsAction(SemanticsActions.OnClick); compose.frames(); compose.waitForIdle()
        assertEquals(LabAction.UNDO to "C5", asked.last())
        scrollToTag("lab-apply-C6")
        tap("Apply (PIN)")
        assertEquals(LabAction.APPLY_LIVE to "C6", asked.last())
        scrollTo("ORB (paper)")
        assertFalse(shows("V2 · target 40 pts → 60 pts · retired"))
        expand("lab-variants:orb")
        assertTrue(shows("V2 · target 40 pts → 60 pts · retired"))
        // Each idea: plain rules, the backtest table, the forward record, why it was retired; a survivor's Start on paper.
        scrollToTag("lab-start-F15")
        tap("Start on paper")
        assertEquals(LabAction.START_PAPER to "F15", asked.last())
        scrollTo("Why retired: retired: it lost on live days")
        assertTrue(shows("Why retired: retired: it lost on live days (p 0.030)"))
        assertTrue(shows("Rules: BANKNIFTY, 1 lot, buys the at-the-money call or put"))
        assertTrue(shows("Forward: 12 trades over 9 days"))
        expand("lab-bt:F7")
        assertTrue(shows("Random twin p"))
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("lab-sheet-graveyard"))
        tap("Close")
        assertTrue(closed)
    }

    @Test fun eachArmSaysWhatItRunsWithItsUndo() {
        // S1: a paper variant in force since today; live on the defaults.
        val s = VariantLab.strategy("orb_sweep")!!
        val to = s.defaults + ("stopPts" to 30.0)
        val c = VariantLab.Change("C8", "orb_sweep", "V3", VariantLab.ChangeKind.AUTO_PAPER, VariantLab.ChangeStatus.APPLIED, day.minusDays(1),
            s.defaults, to, "it made more", effective = day)
        val b = VariantLab.Book(runs = mapOf("orb_sweep" to VariantLab.Run.PAPER), changes = listOf(c),
            tunes = listOf(VariantLab.Tune("orb_sweep", false, day, to, "C8")))
        val asked = ArrayList<Pair<LabAction, String>>()
        set { StrategyLabSheet(b, StrategyForge.Book(), null, null, onAction = { a, id -> asked += a to id }, onClose = {}, today = day) }
        scrollToTag("lab-running-orb_sweep-paper")
        assertTrue(shows("Paper · running: variant V3 since 12 Oct"))
        assertTrue(shows("Live · running: default"))
        scrollToTag("lab-undo-running-orb_sweep-paper")
        compose.onNodeWithTag("lab-undo-running-orb_sweep-paper").performSemanticsAction(SemanticsActions.OnClick); compose.frames()
        assertEquals(LabAction.UNDO to "C8", asked.last())
    }

    @Test fun aSelectedIdeaComesFirst() {
        set { StrategyLabSheet(variants, forge, "F7", null, onAction = { _, _ -> }, onClose = {}) }
        scrollToTag("lab-idea-F7")
        assertTrue(shows("F7 VWAP reclaim Banknifty 5m · retired after its forward test"))
    }

    @Config(qualifiers = "w891dp-h411dp-land")
    @Test fun onItsSideTheTitleAndCloseKeepApart() {
        set { StrategyLabSheet(variants, forge, null, null, onAction = { _, _ -> }, onClose = {}) }
        titleAndCloseApart()
        // Landscape is short: the list scrolls to the last idea (a lazy list composes only what is on screen).
        scrollToTag("lab-idea-F7")
        assertTrue(shows("F7 VWAP reclaim"))
    }

    @Test fun atLargeFontTheTitleAndCloseKeepApart() {
        RuntimeEnvironment.setFontScale(2f)
        set { StrategyLabSheet(variants, forge, null, "Undone.", onAction = { _, _ -> }, onClose = {}) }
        titleAndCloseApart()
    }

    @Test fun atLargeFontTheCardsLinesKeepApart() {
        RuntimeEnvironment.setFontScale(2f)
        set { StrategyLabCardContent(variants, forge, onOpen = {}) }
        val tags = listOf("lab-strategy-orb", "lab-strategy-liquidity", "lab-forward", "lab-graveyard", "lab-waiting", "lab-details")
        val rects = tags.map { compose.onNodeWithTag(it, useUnmergedTree = true).getBoundsInRoot() }
        for (i in rects.indices) for (j in i + 1 until rects.size) assertTrue("${tags[i]} / ${tags[j]}", noOverlap(rects[i], rects[j]))
    }
}
