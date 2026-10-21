package com.optionslab.app.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.data.GrammarArm
import com.optionslab.app.data.NightArm
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.engine.orb.ArmsCleanup
import com.optionslab.engine.orb.NightRules
import com.optionslab.ira.AutoChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Boss's 15 Oct UI rule and cleanup on Home's Strategies card: after the cleanup no ORB, ORB Fresh, ORB Sweep, Range Fade or
 * expiry Hero row is shown; each remaining row is its name, a badge, one status line and its switch; a tap on a NAME opens a
 * dialog with ONLY that arm's text (never another arm's) and OK closes it; the Hidden arms list restores one in one tap; the
 * other compact cards (Night, the hosted lab ideas, the Strategy lab, Autopilot, the shared knowledge, a settings row) open
 * their own explanation the same way.
 */
@RunWith(AndroidJUnit4::class)
@org.robolectric.annotation.Config(qualifiers = "w411dp-h2400dp")
class CompactRowsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun shown(t: String) = compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun about(name: String) { compose.onNodeWithContentDescription("About $name").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick); compose.waitForIdle() }
    private fun ok() { compose.onNodeWithText("OK").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick); compose.waitForIdle() }

    /** The state after the cleanup's first pass, nothing holding a position. */
    private val cleaned = ArmsCleanup.pass(ArmsCleanup.State(), ArmsCleanup.HIDDEN.map { ArmsCleanup.Now(it.key, armed = it.key == ArmsCleanup.ORB, open = false) }).first

    @Test fun afterTheCleanupTheDashboardShowsNoOrbFamilyOrExpiryHeroRow() {
        val view = visibleOrbView(StrategyFakes.orbView(armed = true, fourArmed = true), cleaned)
        compose.setContent { IraAlgoTheme("light") { Column(Modifier.verticalScroll(rememberScrollState())) { OrbRowsContent(view, false, RecordingStrategyActions(), StrategyFakes.reauthWhy) } } }
        compose.waitForIdle()
        for (label in listOf("ORB", "ORB Fresh", "ORB Sweep", "Range Fade", "Hero (expiry)"))
            assertEquals(label, 0, compose.onAllNodes(hasContentDescription("Arm $label")).fetchSemanticsNodes().size)
        assertEquals("only Liquidity 15+5's switch", 1, compose.onAllNodes(isToggleable()).fetchSemanticsNodes().size)
        assertTrue(shown("Liquidity 15+5"))
        assertFalse("no shadows section", shown("Shadows · no orders"))
        // The row: its name, its badge and ONE status line - no rules, books, sizes, what-ifs, charges or paper test on it.
        assertTrue(shown("ARMED · PAPER · AUTO"))
        for (gone in listOf("liquidity pool taken", "BANKNIFTY 15-min:", "Since 06 Oct, per lot", "Size:", "Lots:", "Show charges", "parked on your OK",
            "Frozen for its paper test", "Live stop rule", "PAPER RECORD"))
            assertFalse(gone, shown(gone))
    }

    @Test fun anArmHoldingAPositionStaysOnScreenUntilItsExitAndSaysSo() {
        val holding = ArmsCleanup.pass(ArmsCleanup.State(), ArmsCleanup.HIDDEN.map { ArmsCleanup.Now(it.key, true, open = it.key == ArmsCleanup.ORB) }).first
        val view = visibleOrbView(StrategyFakes.orbView(orbOpen = true, fourArmed = true), holding)
        compose.setContent { IraAlgoTheme("light") { Column(Modifier.verticalScroll(rememberScrollState())) {
            OrbRowsContent(view, false, RecordingStrategyActions(), StrategyFakes.reauthWhy, waiting = { it in holding.waiting })
        } } }
        compose.waitForIdle()
        assertEquals(1, compose.onAllNodes(hasContentDescription("Arm ORB")).fetchSemanticsNodes().size)
        assertTrue(shown("IN TRADE · PAPER"))
        assertTrue(shown("SWITCHING OFF AFTER ITS EXIT"))
        assertEquals(0, compose.onAllNodes(hasContentDescription("Arm ORB Fresh")).fetchSemanticsNodes().size)
    }

    @Test fun eachArmsDialogShowsOnlyItsOwnText() {
        compose.setContent { IraAlgoTheme("light") { Column(Modifier.verticalScroll(rememberScrollState())) {
            OrbRowsContent(StrategyFakes.orbView(armed = true, lots = 1), false, RecordingStrategyActions(), StrategyFakes.reauthWhy)
        } } }
        compose.waitForIdle()
        about("Liquidity 15+5")
        assertTrue(shown(keepNumbersWhole("BANKNIFTY (15 + 5-min)")))
        assertTrue(shown("Lots:"))
        assertTrue(shown(keepNumbersWhole(ArmsCleanup.NIFTY_NOTE)))
        // Never another arm's.
        assertFalse(shown("opening-range break"))
        assertFalse(shown(com.optionslab.engine.orb.HeroRules.SIGNAL_SOURCE))
        ok()
        assertFalse(shown("Lots:"))
        about("Hero (expiry)")
        assertTrue(shown(com.optionslab.engine.orb.HeroRules.SIGNAL_SOURCE))
        assertFalse(shown(keepNumbersWhole("BANKNIFTY (15 + 5-min)")))
        assertFalse(shown("Lots:"))
        ok()
        about("ORB Sweep")
        assertTrue(shown("failed break of the opening range"))
        assertFalse(shown("touch of the range edge"))
        ok()
    }

    @Test fun theHiddenArmsListRestoresOneInOneTap() {
        val restored = ArrayList<String>()
        compose.setContent { IraAlgoTheme("light") { Column { HiddenArmsContent(cleaned, said = null) { restored += it } } } }
        compose.waitForIdle()
        assertTrue(shown("$HIDDEN_ARMS (12)"))
        compose.onNodeWithContentDescription("$HIDDEN_ARMS: 12").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick); compose.waitForIdle()
        assertTrue(shown(HIDDEN_NOTE))
        assertTrue(shown("was on · back on paper"))
        assertTrue(shown(keepNumbersWhole(ArmsCleanup.NIFTY_NOTE)))
        compose.onNodeWithContentDescription("Restore ORB").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick); compose.waitForIdle()
        assertEquals(listOf(ArmsCleanup.ORB), restored)
        ok()
        assertFalse(shown(HIDDEN_NOTE))
    }

    @Test fun beforeTheCleanupRanThereIsNoList() {
        compose.setContent { IraAlgoTheme("light") { Column { HiddenArmsContent(ArmsCleanup.State()) {} } } }
        compose.waitForIdle()
        assertFalse(shown(HIDDEN_ARMS))
    }

    @Test fun anArmWaitingForItsExitIsNotInTheListButSaidThere() {
        val holding = ArmsCleanup.pass(ArmsCleanup.State(), ArmsCleanup.HIDDEN.map { ArmsCleanup.Now(it.key, true, open = it.key == ArmsCleanup.NIGHT) }).first
        compose.setContent { IraAlgoTheme("light") { Column { HiddenArmsContent(holding) {} } } }
        compose.waitForIdle()
        assertTrue(shown("$HIDDEN_ARMS (11)"))
        assertTrue(shown("1 switching off after its exit"))
        compose.onNodeWithContentDescription("$HIDDEN_ARMS: 11").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick); compose.waitForIdle()
        assertTrue(shown(keepNumbersWhole("Night (R3): " + ArmsCleanup.WAITING)))
        assertEquals(0, compose.onAllNodes(hasContentDescription("Restore Night (R3)")).fetchSemanticsNodes().size)
    }

    @Test fun theOtherCompactCardsOpenOnlyTheirOwnExplanation() {
        compose.setContent { IraAlgoTheme("light") { Column(Modifier.verticalScroll(rememberScrollState())) {
            NightRowContent(NightArm.View(true, mapOf("NIFTY" to "waits for 15:20"), emptyList(), emptyList(), emptyList())) {}
            HostedIdeasContent(GrammarArm.View()) { "" }
            StrategyLabCardContent(com.optionslab.ira.VariantLab.Book(), com.optionslab.ira.StrategyForge.Book()) {}
            AutopilotCardContent(AutoChange.Book(), AutoChange.Mode.SAFE, java.time.LocalDate.of(2026, 10, 15)) {}
            SharedKnowledgeContent(false) {}
            ToggleRow("Calm motion", "Fewer animations everywhere.", false) {}
        } } }
        compose.waitForIdle()
        val pairs = listOf(NightRules.LABEL to keepNumbersWhole(NightRules.RULES), "On paper now (0 of ${com.optionslab.ira.GrammarPaper.MAX_HOSTED})" to HOSTED_NOTE,
            LAB_TITLE to LAB_NOTE, AUTOPILOT_TITLE to AUTOPILOT_NOTE, SHARED_CARD_TITLE to SHARED_NOTE, "Calm motion" to "Fewer animations everywhere.")
        for ((name, text) in pairs) assertFalse(name, shown(text))
        for ((name, text) in pairs) {
            about(name)
            assertTrue(name, shown(text))
            for ((other, otherText) in pairs) if (other != name && otherText != text) assertFalse("$name shows $other's text", shown(otherText))
            ok()
            assertFalse(name, shown(text))
        }
        assertTrue("Night's row keeps a short state", shown("No position · decides at 15:20"))
    }
}
