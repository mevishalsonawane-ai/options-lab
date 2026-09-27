package com.optionslab.app.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.ui.components.StrikeDropdown
import com.optionslab.app.ui.theme.IraAlgoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Trade tab's small parts: who placed an order ([orderSource]) and the strike picker ([StrikeDropdown]). */
@RunWith(AndroidJUnit4::class)
class TradeComponentsTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val watchdog = com.optionslab.app.testing.TradeWatchdog()

    // ---- order source ---------------------------------------------------------------------

    @Test fun anOrderThisPhonesStrategySentIsNamed() {
        assertEquals("Strategy: ORB · leg 1 entry" to true, orderSource(mapOf("paper:7" to "ORB · leg 1 entry"), "paper:7"))
        assertEquals("Strategy: ORB · leg 1 entry" to true, orderSource(mapOf("kite:7" to "ORB · leg 1 entry"), "kite:7", "iraalgo"))
    }

    @Test fun anOldStrategyOrderIsKnownByItsTag() {
        assertEquals("Strategy order" to true, orderSource(emptyMap(), "kite:9", "iraalgostrat"))
    }

    @Test fun everythingElseIsManual() {
        assertEquals("Manual" to false, orderSource(emptyMap(), "kite:9", "iraalgo"))
        assertEquals("Manual" to false, orderSource(mapOf("paper:1" to "ORB"), "kite:1"))     // same id, other venue
        assertEquals("Manual" to false, orderSource(emptyMap(), "paper:1"))
    }

    @Test fun thePillShowsTheSource() {
        compose.setContent { IraAlgoTheme("light") { OrderSourcePill(mapOf("paper:3" to "Straddle"), "paper:3") } }
        compose.onNodeWithText("Strategy: Straddle").assertExists()
    }

    // ---- strike dropdown ---------------------------------------------------------------------

    private val strikes = listOf(24_300.0, 24_400.0, 24_500.0, 24_600.0, 24_700.0)
    private fun exists(t: String) = compose.onAllNodesWithText(t).fetchSemanticsNodes().isNotEmpty()

    @Test fun beforeTheStrikesLoadItSaysSoAndDoesNotOpen() {
        compose.setContent { IraAlgoTheme("light") { StrikeDropdown(emptyList(), null, "", "NIFTY") {} } }
        compose.onNodeWithText("Loading strikes…").assertExists().performClick()
        compose.waitForIdle()
        assertTrue(compose.onAllNodes(isPopup()).fetchSemanticsNodes().isEmpty())
        assertTrue("no index level without a spot", exists("Strike"))
    }

    @Test fun itOpensOnTheListedStrikesMarksAtmAndPicks() {
        var picked by mutableStateOf("")
        compose.setContent { IraAlgoTheme("dark") { StrikeDropdown(strikes, 24_512.0, picked, "NIFTY") { picked = it } } }
        compose.onNodeWithText("Choose a strike").assertExists()
        compose.onNodeWithText("Strike · NIFTY 24,512").assertExists()
        assertTrue(exists("▼"))
        compose.onNodeWithText("Choose a strike").performClick()
        compose.waitUntil(5_000) { exists("24500   ATM") }
        assertTrue(exists("▲"))
        strikes.filter { it != 24_500.0 }.forEach { k -> compose.onNode(hasText("${k.toInt()}") and hasAnyAncestor(isPopup())).assertExists() }
        compose.onNode(hasText("24300") and hasAnyAncestor(isPopup())).performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(isPopup()).fetchSemanticsNodes().isEmpty() }
        assertEquals("24300", picked)
        compose.onNodeWithText("24300").assertExists()
        // Reopening shows the choice; the ATM mark stays on the strike nearest the index.
        compose.onNodeWithText("24300").performClick()
        compose.waitUntil(5_000) { exists("24500   ATM") }
    }

    @Test fun withoutASpotThereIsNoAtmMark() {
        compose.setContent { IraAlgoTheme("light") { StrikeDropdown(strikes, null, "24400", "BANKNIFTY") {} } }
        compose.onNodeWithText("24400").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(isPopup()).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(compose.onAllNodesWithText("ATM", substring = true).fetchSemanticsNodes().isEmpty())
    }
}
