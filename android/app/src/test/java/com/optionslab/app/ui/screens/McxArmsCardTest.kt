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
                    mcxRows = { McxArmsGroup(v, onArm) })
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
        assertEquals(4, compose.onAllNodesWithText(McxArmRules.NOT_PROVEN).fetchSemanticsNodes().size)
        // The trend's warning, on its row.
        assertTrue(McxPaperArms.record(McxTrendRules.SOURCE).contains("needs ₹8–10 lakh; at ₹1 lakh often ruined"))
        compose.onNodeWithText(keepNumbersWhole(McxPaperArms.record(McxTrendRules.SOURCE))).assertExists()
    }

    @Test fun eachBotIsLabelledNotProvenWithItsResearchAndStartsOff() {
        val asked = ArrayList<Pair<String, Boolean>>()
        home(off) { a, on -> asked += a to on }
        compose.waitForIdle()
        compose.onNodeWithText(MCX_GROUP).assertIsDisplayed()
        assertEquals(4, compose.onAllNodesWithText(McxArmRules.NOT_PROVEN).fetchSemanticsNodes().size)
        for (label in labels) compose.onNodeWithText(label).assertExists()
        // The research numbers, each above its rules.
        for (arm in McxPaperArms.ARMS) {
            val rec = compose.onNodeWithText(keepNumbersWhole(McxPaperArms.record(arm))).fetchSemanticsNode().boundsInRoot.top
            val rules = compose.onNodeWithText(keepNumbersWhole(McxPaperArms.rules(arm))).fetchSemanticsNode().boundsInRoot.top
            assertTrue(arm, rec < rules)
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
        val v = off.copy(nets = mapOf(McxUsSilverRules.SOURCE to listOf(1_200.0, -400.0, 900.0)))
        home(v)
        compose.waitForIdle()
        val group = compose.onNodeWithText(MCX_GROUP).fetchSemanticsNode().boundsInRoot.top
        assertTrue(compose.onNodeWithText(McxUsSilverRules.LABEL).fetchSemanticsNode().boundsInRoot.top > group)
        val record = McxPaperArms.record(McxUsSilverRules.SOURCE)
        for (w in listOf("holdout 166 trades", "+₹1,189/trade (+₹1,115/day)", "won 56%", "₹29.6k", "10 of 13 months green", "tripling year",
            "older check lost", "futures (sells too)", "margin ~₹25–35k"))
            assertTrue(w, w in record)
        compose.onNodeWithText(keepNumbersWhole(record)).assertExists()
        assertTrue(McxPaperArms.rules(McxUsSilverRules.SOURCE).contains("not part of the research"))
        val test = com.optionslab.ira.Vetting.judge(McxUsSilverRules.LABEL, listOf(1_200.0, -400.0, 900.0)).text()
        assertTrue(test, test.startsWith("${McxUsSilverRules.LABEL} is still on its paper test: 3 of 15 trades"))
        compose.onNodeWithText(keepNumbersWhole(test)).assertExists()
        compose.onNodeWithContentDescription("Paper only: switch ${McxUsSilverRules.LABEL}").assertIsOff()
    }

    @Test fun anArmedBotSaysPaperOnlyAndItsStatus() {
        val v = off.copy(armed = mapOf(McxMorningRules.SOURCE to true), status = mapOf(McxMorningRules.SOURCE to "am_dte 9 (needs 11-20)"))
        home(v)
        compose.waitForIdle()
        compose.onNodeWithText("ON · PAPER ONLY").assertIsDisplayed()
        compose.onNodeWithContentDescription("Paper only: switch ${McxMorningRules.LABEL}").assertIsOn()
        compose.onNodeWithText(keepNumbersWhole("am_dte 9 (needs 11-20)")).assertExists()
    }

    @Test fun theCommoditiesPageSaysTheBotsAreOnHome() {
        compose.setContent {
            IraAlgoTheme("light") { CommoditiesContent(Load.Done(emptyList<McxMarket.Quote>()), "MCX is open now", {}, {}) }
        }
        compose.waitForIdle()
        compose.onNodeWithText(MCX_ARMS_WHERE).assertExists()
        assertTrue("Home → Strategies" in MCX_ARMS_WHERE)
        // No switch here: the bots' one place is Home → Strategies.
        assertEquals(0, compose.onAllNodes(hasContentDescription("Paper only: switch", substring = true)).fetchSemanticsNodes().size)
    }
}
