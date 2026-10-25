package com.optionslab.app.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.data.AppSettings
import com.optionslab.app.data.McxPaperArms
import com.optionslab.app.ui.theme.IraAlgoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 21 Oct (Boss: "Yes, give commodities their own day lock"): Bot settings' rows for the commodities' own paper day lock and
 * daily loss limit, and the MCX group's own line on Home when its lock is reached.
 */
@RunWith(AndroidJUnit4::class)
@org.robolectric.annotation.Config(qualifiers = "w411dp-h2400dp")
class CommoditiesLimitsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    // 22 Oct: the market clock pinned to noon IST on a trading day, as in every app test (round 4's rule).
    @get:Rule val marketClock = com.optionslab.app.testing.MarketClockRule()

    private fun rupees(x: Double) = "₹%,.0f".format(java.util.Locale.ENGLISH, x)

    @Test fun theSwitchAndBothAmountsShowAndPickTheirOwnValues() {
        val picked = ArrayList<String>()
        compose.setContent {
            IraAlgoTheme("light") {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    CommoditiesLimits(AppSettings(), ::rupees, onOwn = { picked += "own $it" }, onLock = { picked += "lock $it" }, onLoss = { picked += "loss $it" })
                }
            }
        }
        compose.onNodeWithText("Commodities keep their own paper limits").assertIsDisplayed()
        compose.onNodeWithText("COMMODITIES DAY LOCK (PAPER)").assertIsDisplayed()
        compose.onNodeWithText("COMMODITIES DAILY LOSS LIMIT (PAPER)").assertIsDisplayed()
        compose.onNodeWithText("Commodities keep their own paper day lock (+Rs 8,000) and daily loss limit (-Rs 10,000)", substring = true).assertIsDisplayed()
        compose.onNodeWithText("still stop both", substring = true).assertIsDisplayed()
        compose.onAllNodesWithText("₹10,000")[0].performClick()          // the day lock's row comes first
        compose.onNodeWithText("₹2,000").performClick()
        compose.onNodeWithText("Commodities keep their own paper limits").performClick()
        compose.waitForIdle()
        assertEquals(listOf("lock 10000.0", "loss 2000.0", "own false"), picked)
    }

    @Test fun offTheAmountsAreHiddenAndTheSharedRuleIsSaid() {
        compose.setContent {
            IraAlgoTheme("light") { Column { CommoditiesLimits(AppSettings(mcxOwnLimits = false), ::rupees, {}, {}, {}) } }
        }
        compose.onNodeWithText("Off: one paper figure stops both NSE and MCX arms").assertIsDisplayed()
        compose.onNodeWithText("COMMODITIES DAY LOCK (PAPER)").assertDoesNotExist()
    }

    @Test fun theMcxGroupSaysItsOwnDayLockPlainly() {
        val line = "Commodities day lock reached +Rs 8,000 at 18:05: no new MCX entries today"
        compose.setContent {
            IraAlgoTheme("light") {
                Column { McxArmsGroup(McxPaperArms.View(emptyMap(), emptyMap(), emptyList(), emptyList(), emptyList()), stopLine = line) { _, _ -> } }
            }
        }
        compose.onNodeWithText(line).assertIsDisplayed()
    }
}
