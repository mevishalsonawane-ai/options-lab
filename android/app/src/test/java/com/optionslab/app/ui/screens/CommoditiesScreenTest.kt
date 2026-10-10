package com.optionslab.app.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.data.McxMarket
import com.optionslab.app.ui.Load
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.engine.mcx.McxContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** The Commodities page (9 Oct): each commodity's near and next future, a tap opens it; a failed read says why. */
@RunWith(AndroidJUnit4::class)
@org.robolectric.annotation.Config(qualifiers = "w411dp-h2400dp")
class CommoditiesScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun fut(sym: String, name: String, expiry: String, mult: Int) =
        McxContract(1L, 2L, sym, name, LocalDate.parse(expiry), 0.0, null, 1.0, mult)

    private val near = McxMarket.Quote(fut("CRUDEOIL26OCTFUT", "CRUDEOIL", "2026-10-19", 100), 8_600.0, 8_500.0, null, null, false)
    private val next = McxMarket.Quote(fut("CRUDEOIL26NOVFUT", "CRUDEOIL", "2026-11-19", 100), 8_650.0, 8_700.0, null, null, false)
    private val gas = McxMarket.Quote(fut("NATURALGAS26OCTFUT", "NATURALGAS", "2026-10-27", 1250), 310.0, 300.0, null, null, true)

    @Test fun eachCommodityShowsItsFuturesAndATapPicksOne() {
        val picked = ArrayList<String>()
        compose.setContent {
            IraAlgoTheme("light") {
                CommoditiesContent(Load.Done(listOf(next, gas, near)), "MCX is open now", onRefresh = {}, onPick = { picked += it.contract.tradingSymbol })
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Crude oil").assertIsDisplayed()
        compose.onNodeWithText("Natural gas").assertIsDisplayed()
        compose.onNodeWithText("MCX is open now").assertIsDisplayed()
        // The last session's close is said as such (MCX shut, or no candle yet today).
        compose.onNodeWithText("last close").assertIsDisplayed()
        compose.onNodeWithText(near.contract.label).performClick()
        compose.waitForIdle()
        assertEquals(listOf("CRUDEOIL26OCTFUT"), picked)
        // Near month above next month.
        val top = { t: String -> compose.onNodeWithText(t).fetchSemanticsNode().boundsInRoot.top }
        assertTrue(top(near.contract.label) < top(next.contract.label))
    }

    @Test fun aFailedReadSaysWhy() {
        compose.setContent {
            IraAlgoTheme("light") { CommoditiesContent(Load.Failed("LIVE mode: log in to Zerodha for today to read MCX prices."), "MCX is closed now", {}, {}) }
        }
        compose.waitForIdle()
        assertEquals(1, compose.onAllNodesWithText("LIVE mode: log in to Zerodha for today to read MCX prices.").fetchSemanticsNodes().size)
    }
}
