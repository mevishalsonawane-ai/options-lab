package com.optionslab.app.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.data.McxPaperArms
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.engine.mcx.McxArmRules
import com.optionslab.engine.mcx.McxEveRules
import com.optionslab.engine.mcx.McxMorningRules
import com.optionslab.engine.mcx.McxTrendRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Commodities page's MCX paper bots (9 Oct): each one labelled "Not proven — paper only" with its research numbers above
 * its rules, off by default; a switch asks to arm it (on paper - there is no other way to turn it on).
 */
@RunWith(AndroidJUnit4::class)
@org.robolectric.annotation.Config(qualifiers = "w411dp-h2400dp")
class McxArmsCardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val off = McxPaperArms.View(emptyMap(), emptyMap(), emptyList(), emptyList(), emptyList())

    @Test fun eachBotIsLabelledNotProvenWithItsResearchAndStartsOff() {
        val asked = ArrayList<Pair<String, Boolean>>()
        compose.setContent { IraAlgoTheme("light") { McxArmsContent(off) { a, on -> asked += a to on } } }
        compose.waitForIdle()
        compose.onNodeWithText("MCX paper bots").assertIsDisplayed()
        assertEquals(3, compose.onAllNodesWithText(McxArmRules.NOT_PROVEN).fetchSemanticsNodes().size)
        for (label in listOf(McxEveRules.LABEL, McxMorningRules.LABEL, McxTrendRules.LABEL)) compose.onNodeWithText(label).assertExists()
        // The research numbers, each above its rules.
        for (arm in McxPaperArms.ARMS) {
            val rec = compose.onNodeWithText(keepNumbersWhole(McxPaperArms.record(arm))).fetchSemanticsNode().boundsInRoot.top
            val rules = compose.onNodeWithText(keepNumbersWhole(McxPaperArms.rules(arm))).fetchSemanticsNode().boundsInRoot.top
            assertTrue(arm, rec < rules)
        }
        assertTrue(McxPaperArms.record(McxTrendRules.SOURCE).contains("needs ₹8–10 lakh; at ₹1 lakh often ruined"))
        assertTrue(McxPaperArms.record(McxEveRules.SOURCE).contains("+Rs 41/day") && McxPaperArms.record(McxEveRules.SOURCE).contains("+Rs 83/day"))
        assertTrue(McxPaperArms.record(McxMorningRules.SOURCE).contains("+Rs 454/trade over 51 trades"))
        assertEquals(3, compose.onAllNodesWithText("OFF").fetchSemanticsNodes().size)
        compose.onNodeWithContentDescription("Paper only: switch ${McxEveRules.LABEL}").assertIsOff().performClick()
        compose.waitForIdle()
        assertEquals(listOf(McxEveRules.SOURCE to true), asked)
    }

    @Test fun anArmedBotSaysPaperOnlyAndItsStatus() {
        val v = off.copy(armed = mapOf(McxMorningRules.SOURCE to true), status = mapOf(McxMorningRules.SOURCE to "am_dte 9 (needs 11-20)"))
        compose.setContent { IraAlgoTheme("light") { McxArmsContent(v) { _, _ -> } } }
        compose.waitForIdle()
        compose.onNodeWithText("ON · PAPER ONLY").assertIsDisplayed()
        compose.onNodeWithContentDescription("Paper only: switch ${McxMorningRules.LABEL}").assertIsOn()
        compose.onNodeWithText(keepNumbersWhole("am_dte 9 (needs 11-20)")).assertExists()
    }
}
