package com.optionslab.app.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.data.McxMarket
import com.optionslab.app.data.McxPaperArms
import com.optionslab.app.ui.Load
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.engine.mcx.McxArmRules
import com.optionslab.engine.mcx.McxEveRules
import com.optionslab.engine.mcx.McxMorningRules
import com.optionslab.engine.mcx.McxTrendRules
import com.optionslab.engine.mcx.McxUsSilverRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The MCX paper bots (9 Oct) on Home's Strategies card (Boss: "why commodities strategies are in options tab, it should be
 * on home screen"): their own "MCX (commodities)" group, each bot labelled "Not proven — paper only" with its research
 * numbers above its rules, off by default; a switch asks to arm it (on paper - there is no other way to turn it on). The
 * Commodities page only says where they are.
 */
@RunWith(AndroidJUnit4::class)
@org.robolectric.annotation.Config(qualifiers = "w411dp-h2400dp")
class McxArmsCardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val off = McxPaperArms.View(emptyMap(), emptyMap(), emptyList(), emptyList(), emptyList())
    private val labels = listOf(McxEveRules.LABEL, McxMorningRules.LABEL, McxTrendRules.LABEL, McxUsSilverRules.LABEL)

    /** Home's Strategies card as the app builds it, with the NSE arms stood in by a line and the MCX group from [v]. */
    private fun home(v: McxPaperArms.View, onArm: (String, Boolean) -> Unit = { _, _ -> }) = compose.setContent {
        IraAlgoTheme("light") {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                StrategyArmContent(false, false, emptyList(), emptyMap(), emptyMap(), false, RecordingStrategyActions(), {},
                    orbRows = { Text("ORB rows") }, reauth = StrategyFakes.reauth, nightRow = { Text("Night row") },
                    mcxRows = { McxArmsGroup(v, onArm = onArm) })
            }
        }
    }

    @Test fun homeStrategiesHasTheMcxGroupWithFourSwitchesOffByDefault() {
        // The store's own first view (nothing armed yet): what Home shows before Boss touches a switch.
        home(McxPaperArms.view.value)
        compose.waitForIdle()
        compose.onNodeWithText("Strategies").assertExists()
        compose.onNodeWithText(MCX_GROUP).assertExists()
        // Under the NSE arms, in its own group.
        val night = compose.onNodeWithText("Night row").fetchSemanticsNode().boundsInRoot.top
        val group = compose.onNodeWithText(MCX_GROUP).fetchSemanticsNode().boundsInRoot.top
        assertTrue(night < group)
        for (label in labels) {
            compose.onNodeWithText(label).assertExists()
            assertTrue(label, compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot.top > group)
            compose.onNodeWithContentDescription("Paper only: switch $label").assertIsOff()
        }
        assertEquals(4, compose.onAllNodes(isToggleable() and hasContentDescription("Paper only: switch", substring = true)).fetchSemanticsNodes().size)
        // COMPACT (15 Oct): the label and the research are in each name's dialog, not on the row.
        assertEquals(0, compose.onAllNodesWithText(McxArmRules.NOT_PROVEN, substring = true).fetchSemanticsNodes().size)
        compose.onNodeWithText(MCX_NOTE).assertDoesNotExist()
        // The trend's warning, in its name's dialog.
        assertTrue(McxPaperArms.record(McxTrendRules.SOURCE).contains("needs ₹8–10 lakh; at ₹1 lakh often ruined"))
        compose.onNodeWithContentDescription("About ${McxTrendRules.LABEL}").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithText(McxArmRules.NOT_PROVEN + ".").assertExists()
        compose.onNodeWithText(keepNumbersWhole(McxPaperArms.record(McxTrendRules.SOURCE))).assertExists()
        compose.onNodeWithText("OK").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithText(keepNumbersWhole(McxPaperArms.record(McxTrendRules.SOURCE))).assertDoesNotExist()
        // The group's note, in the group name's dialog.
        compose.onNodeWithContentDescription("About $MCX_GROUP").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithText(MCX_NOTE).assertExists()
        compose.onNodeWithText("OK").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
    }

    @Test fun eachBotIsLabelledNotProvenWithItsResearchAndStartsOff() {
        val asked = ArrayList<Pair<String, Boolean>>()
        home(off) { a, on -> asked += a to on }
        compose.waitForIdle()
        compose.onNodeWithText(MCX_GROUP).assertIsDisplayed()
        for (label in labels) compose.onNodeWithText(label).assertExists()
        assertEquals(4, compose.onAllNodesWithText(keepNumbersWhole("Paper: 0 trades, +₹0")).fetchSemanticsNodes().size)
        // The research numbers, each above its rules, labelled not proven - in each name's dialog; OK closes it.
        for (arm in McxPaperArms.ARMS) {
            compose.onNodeWithContentDescription("About ${McxPaperArms.label(arm)}").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
            compose.waitForIdle()
            compose.onNodeWithText(McxArmRules.NOT_PROVEN + ".").assertExists()
            val rec = compose.onNodeWithText(keepNumbersWhole(McxPaperArms.record(arm))).fetchSemanticsNode().boundsInRoot.top
            val rules = compose.onNodeWithText(keepNumbersWhole(McxPaperArms.rules(arm))).fetchSemanticsNode().boundsInRoot.top
            assertTrue(arm, rec < rules)
            compose.onNodeWithText("OK").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
            compose.waitForIdle()
        }
        assertTrue(McxPaperArms.record(McxTrendRules.SOURCE).contains("needs ₹8–10 lakh; at ₹1 lakh often ruined"))
        assertTrue(McxPaperArms.record(McxEveRules.SOURCE).contains("+Rs 41/day") && McxPaperArms.record(McxEveRules.SOURCE).contains("+Rs 83/day"))
        assertTrue(McxPaperArms.record(McxMorningRules.SOURCE).contains("+Rs 454/trade over 51 trades"))
        assertEquals(4, compose.onAllNodesWithText("OFF").fetchSemanticsNodes().size)
        compose.onNodeWithContentDescription("Paper only: switch ${McxEveRules.LABEL}").assertIsOff().performClick()
        compose.waitForIdle()
        assertEquals(listOf(McxEveRules.SOURCE to true), asked)
    }

    /** 9 Oct (research R2): the US-night silver futures rule in the same group, its research and its warnings first, and its paper test. */
    @Test fun usNightSilverIsInTheMcxGroupWithItsResearchAndItsPaperTest() {
        val d = java.time.LocalDate.of(2026, 10, 14)
        val dated = listOf(1_200.0, -400.0, 900.0).map { com.optionslab.ira.Vetting.Trade(d, it) }
        val v = off.copy(nets = mapOf(McxUsSilverRules.SOURCE to listOf(1_200.0, -400.0, 900.0)), trades = mapOf(McxUsSilverRules.SOURCE to dated))
        home(v)
        compose.waitForIdle()
        val group = compose.onNodeWithText(MCX_GROUP).fetchSemanticsNode().boundsInRoot.top
        assertTrue(compose.onNodeWithText(McxUsSilverRules.LABEL).fetchSemanticsNode().boundsInRoot.top > group)
        val record = McxPaperArms.record(McxUsSilverRules.SOURCE)
        for (w in listOf("holdout 166 trades", "+₹1,189/trade (+₹1,115/day)", "won 56%", "₹29.6k", "10 of 13 months green", "tripling year",
            "older check lost", "futures (sells too)", "margin ~₹25–35k"))
            assertTrue(w, w in record)
        // Its key numbers on the row; its research and paper test in its name's dialog.
        compose.onNodeWithText(keepNumbersWhole("Paper: 3 trades, +₹1,700")).assertExists()
        compose.onNodeWithContentDescription("About ${McxUsSilverRules.LABEL}").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithText(keepNumbersWhole(record)).assertExists()
        assertTrue(McxPaperArms.rules(McxUsSilverRules.SOURCE).contains("not part of the research"))
        // The row counts the days to the real today (as the card does); only the trade count is asserted from it.
        val test = com.optionslab.ira.Vetting.judge(McxUsSilverRules.LABEL, dated, runCatching { com.optionslab.app.data.Market.today() }.getOrNull()).text()
        assertTrue(test, test.startsWith("${McxUsSilverRules.LABEL}'s paper test: too early (3/60, "))
        compose.onNodeWithText(keepNumbersWhole(test)).assertExists()
        compose.onNodeWithText("OK").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Paper only: switch ${McxUsSilverRules.LABEL}").assertIsOff()
    }

    @Test fun anArmedBotSaysPaperOnlyAndItsStatus() {
        val v = off.copy(armed = mapOf(McxMorningRules.SOURCE to true), status = mapOf(McxMorningRules.SOURCE to "am_dte 9 (needs 11-20)"))
        home(v)
        compose.waitForIdle()
        compose.onNodeWithText("ON · PAPER ONLY").assertIsDisplayed()
        compose.onNodeWithContentDescription("Paper only: switch ${McxMorningRules.LABEL}").assertIsOn()
        // Its last decision is in its name's dialog.
        compose.onNodeWithContentDescription("About ${McxMorningRules.LABEL}").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithText(keepNumbersWhole("am_dte 9 (needs 11-20)")).assertExists()
    }

    /** 14 Oct (research R42, Boss's OK): the SILVERM morning call switched off once: its row says why; its switch turns it back on. */
    @Test fun theMorningCallSwitchedOffByR42SaysWhyOnItsRow() {
        val asked = ArrayList<Pair<String, Boolean>>()
        home(off.copy(parked = setOf(McxMorningRules.SOURCE))) { a, on -> asked += a to on }
        compose.waitForIdle()
        val row = keepNumbersWhole(com.optionslab.engine.mcx.McxMorningPark.ROW)
        // The row says it short; why, in full, in its name's dialog (once).
        compose.onNodeWithText("OFF · switched off on your OK (14 Oct)").assertExists()
        compose.onNodeWithContentDescription("About ${McxMorningRules.LABEL}").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(1, compose.onAllNodesWithText(row).fetchSemanticsNodes().size)
        compose.onNodeWithText("OK").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Paper only: switch ${McxMorningRules.LABEL}").assertIsOff().performClick()
        compose.waitForIdle()
        assertEquals(listOf(McxMorningRules.SOURCE to true), asked)
    }

    @Test fun theSwitchedOffLineIsNotShownWhileTheMorningCallIsOn() {
        home(off.copy(armed = mapOf(McxMorningRules.SOURCE to true), parked = setOf(McxMorningRules.SOURCE)))
        compose.waitForIdle()
        compose.onNodeWithContentDescription("About ${McxMorningRules.LABEL}").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithText(keepNumbersWhole(com.optionslab.engine.mcx.McxMorningPark.ROW)).fetchSemanticsNodes().size)
    }

    @Test fun theCommoditiesPageSaysTheBotsAreOnHome() {
        compose.setContent {
            IraAlgoTheme("light") { CommoditiesContent(Load.Done(emptyList<McxMarket.Quote>()), "MCX is open now", {}, {}) }
        }
        compose.waitForIdle()
        compose.onNodeWithText(MCX_ARMS_WHERE).assertExists()
        assertTrue("Home → MCX card" in MCX_ARMS_WHERE)
        // No switch here: the bots' one place is Home → Strategies.
        assertEquals(0, compose.onAllNodes(hasContentDescription("Paper only: switch", substring = true)).fetchSemanticsNodes().size)
    }

    // ---- 21 Oct: NR7 crude (record only) and the two hidden MCX arms -------------------------------------------------------

    @Test fun theNr7RecordRowSaysRecordOnlyAndItsSwitchCallsBack() {
        var asked: Boolean? = null
        val t = com.optionslab.app.data.McxNr7Record.Trade("CRUDEOIL", "CRUDEOIL26NOV5950CE", 1, 5950.0, 100,
            java.time.LocalDate.of(2026, 11, 16), "k", "f", 100.0, java.time.LocalDateTime.of(2026, 10, 21, 10, 2),
            java.time.LocalDate.of(2026, 10, 20), 5995.0, 102.0, exit = 180.0, exitTime = java.time.LocalDateTime.of(2026, 10, 21, 10, 4),
            why = "edge", cost = 500.0)
        val v = com.optionslab.app.data.McxNr7Record.View(on = false, closed = listOf(t))
        compose.setContent { IraAlgoTheme("light") { Column { McxNr7Content(v) { asked = it } } } }
        compose.onNodeWithText(com.optionslab.engine.mcx.McxNr7Rules.LABEL).assertExists()
        compose.onNodeWithText(nr7State(v)).assertExists()
        assertEquals("OFF · RECORD ONLY (NO ORDERS)", nr7State(v))
        assertTrue(nr7Numbers(v).contains("1 trade") && nr7Numbers(v).contains("+₹7,500") && nr7Numbers(v).contains("never traded"))
        val sw = compose.onNodeWithContentDescription("Record only, no orders: switch ${com.optionslab.engine.mcx.McxNr7Rules.LABEL}")
        sw.assertIsOff()
        sw.performClick()
        assertEquals(true, asked)
        assertEquals("RECORDING · NO ORDERS", nr7State(v.copy(on = true)))
    }

    @Test fun theHiddenMcxArmsLeaveTheGroup() {
        com.optionslab.app.data.HiddenArms.resetForTest(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        com.optionslab.app.data.HiddenArms.arms = emptyList()
        kotlinx.coroutines.runBlocking { com.optionslab.app.data.HiddenArms.passNow() }
        // A position-less read counts as holding (never switched off on a missing read): stand in "flat" for both MCX arms.
        com.optionslab.app.data.HiddenArms.arms = listOf(McxMorningRules.SOURCE, McxTrendRules.SOURCE).map { k ->
            com.optionslab.app.data.HiddenArms.Arm(k, now = { com.optionslab.engine.orb.ArmsCleanup.Now(k, false, false) },
                off = { emptyList() }, on = { "" })
        }
        kotlinx.coroutines.runBlocking { com.optionslab.app.data.HiddenArms.passNow() }
        home(off)
        compose.waitForIdle()
        compose.onNodeWithText(McxEveRules.LABEL).assertExists()
        compose.onNodeWithText(McxUsSilverRules.LABEL).assertExists()
        compose.onNodeWithText(McxMorningRules.LABEL).assertDoesNotExist()
        compose.onNodeWithText(McxTrendRules.LABEL).assertDoesNotExist()
        assertEquals(2, compose.onAllNodes(isToggleable() and hasContentDescription("Paper only: switch", substring = true)).fetchSemanticsNodes().size)
        com.optionslab.app.data.HiddenArms.resetForTest(androidx.test.core.app.ApplicationProvider.getApplicationContext())
    }
}
