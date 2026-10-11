package com.optionslab.app

import android.Manifest
import android.os.Build
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.optionslab.app.data.Paper
import com.optionslab.app.security.PinLock
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.security.SessionLock
import com.optionslab.engine.Right
import com.optionslab.engine.sandbox.Quote
import com.optionslab.engine.sandbox.SandboxEvent
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * Emulator smoke flows (S16), run by the emulator job (.github/workflows/gold-emulator.yml) on the IraAlgo debug build:
 * the app starts past its gates, Home's dashboard shows its cards, the Chart tab and its symbol picker switch to NIFTY, a
 * paper order is placed and closed on the paper account with a fixed price (no feed), and Settings → Bot settings opens.
 * Flavour-aware: IraGoldAlgo (GOLD) has its own three screens, so only the start is checked there.
 *
 * The phone is a throwaway emulator: a test PIN is set, the app is let run unrestricted, and a made-up Zerodha link (no
 * token, so nothing can reach Zerodha) opens the main screen. Paper only: nothing here places a real order.
 */
@RunWith(AndroidJUnit4::class)
class SmokeTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val notify: GrantPermissionRule = if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
        else GrantPermissionRule.grant()

    private var scenario: ActivityScenario<MainActivity>? = null
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = inst.targetContext

    private fun shell(cmd: String) {
        runCatching { inst.uiAutomation.executeShellCommand(cmd).close() }
    }

    @Before fun up() {
        // Unrestricted battery (the app stays on its battery page otherwise) and no first-use guide, no permission prompt.
        shell("dumpsys deviceidle whitelist +${ctx.packageName}")
        if (!PinLock.isSet) PinLock.setPin("135792".toCharArray())
        SecurePrefs.putAll(mapOf(com.optionslab.app.ui.screens.GETTING_STARTED to true, "asked.notify" to true))
        // A made-up link: keys and a user id, no session token (nothing is ever sent to Zerodha without a login).
        if (!com.optionslab.app.data.Broker.linked) SecurePrefs.putAll(mapOf("kite.apiKey" to "smoketestkey", "kite.apiSecret" to "smoketestsecret", "kite.userId" to "ZZ0000"))
        Paper.testSkipFeedChecks = true
        SessionLock.unlock()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        // The splash, then the main screen.
        compose.waitUntil(30_000) { compose.onAllNodesWithText(settingsTab(), substring = BuildConfig.GOLD).fetchSemanticsNodes().isNotEmpty() }
    }

    @After fun down() {
        scenario?.close()
        Paper.testSkipFeedChecks = false
    }

    private fun tap(text: String) = compose.onAllNodesWithText(text).onFirst().performClick()

    /** The Settings tab's label: "Settings" with Jarvis and in IraGoldAlgo, "More" otherwise. */
    private fun settingsTab(): String = if (BuildConfig.JARVIS || BuildConfig.GOLD) "Settings" else "More"

    private fun waitText(text: String, ms: Long = 10_000) =
        compose.waitUntil(ms) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    @Test fun theAppStarts() {
        // Past the PIN, the battery page and the Zerodha gate: the tab bar is there.
        assertTrue(compose.onAllNodesWithText(settingsTab(), substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun homeShowsItsCards() {
        assumeFalse("IraGoldAlgo has its own Home", BuildConfig.GOLD)
        tap("Home")
        if (BuildConfig.JARVIS) tap("Dashboard")
        // A lazy list: each card is scrolled to before it is asserted. Trading first (11 Oct): the live orders under the money,
        // then the strategies and MCX; the trade manager, Autopilot, the lab, the order flow and the Brain are one Automation card.
        for (card in listOf("Live orders", "Strategies", "MCX (commodities)", "Automation")) {
            compose.waitUntil(10_000) { compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(card))
            assertTrue(card, compose.onAllNodesWithText(card).fetchSemanticsNodes().isNotEmpty())
        }
        // Each section is one tap away: its line opens a sheet with the section's own buttons, and Close shuts it.
        val sections = buildList {
            add("manager" to "Trade manager record"); add("orderflow" to "Order flow per strategy"); add("brain" to "Findings and pauses")
            if (BuildConfig.JARVIS) { add("autopilot" to "Change log"); add("lab" to "Details") }
        }
        for ((key, button) in sections) {
            val sheet = hasAnyAncestor(hasTestTag("automation-sheet-$key"))
            compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasTestTag("automation-line-$key"))
            compose.onNodeWithTag("automation-line-$key").performClick()
            compose.waitUntil(10_000) { compose.onAllNodes(hasScrollAction() and sheet).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodes(hasScrollAction() and sheet).onFirst().performScrollToNode(hasText(button))
            assertTrue("$key: $button", compose.onAllNodes(hasText(button) and sheet).fetchSemanticsNodes().isNotEmpty())
            compose.onAllNodes(hasText("Close") and sheet).onFirst().performClick()
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("automation-sheet-$key")).fetchSemanticsNodes().isEmpty() }
        }
    }

    @Test fun theChartOpensAndItsPickerSwitchesToNifty() {
        assumeFalse("IraGoldAlgo has its own chart", BuildConfig.GOLD)
        tap("Chart")
        val changeSymbol = SemanticsMatcher("opens the symbol picker") { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Change symbol" }
        compose.waitUntil(15_000) { compose.onAllNodes(changeSymbol).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(changeSymbol).onFirst().performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("pick:index:NIFTY")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("pick:index:NIFTY").performClick()
        waitText("NIFTY")
        assertTrue(compose.onAllNodesWithText("NIFTY").fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun aPaperOrderIsPlacedAndClosedWithAFixedPrice() {
        assumeFalse("IraGoldAlgo's paper book is its own", BuildConfig.GOLD)
        Paper.wipe()
        val expiry = LocalDate.now().plusDays(7)
        val c = Paper.Contract("NIFTYSMOKE25000CE", "NIFTY", expiry, 25_000.0, Right.CE, 75, "NSE_FO|SMOKE")
        val buy = runBlocking { Paper.place(c, "BUY", 1, "MARKET", "NRML", null, null, Quote(100.0)) }
        assertTrue(buy.message, buy.ok && buy.events.any { it is SandboxEvent.Fill })
        assertEquals(75, Paper.state.positions.filter { it.symbol == c.symbol }.sumOf { it.quantity })
        val sell = runBlocking { Paper.place(c, "SELL", 1, "MARKET", "NRML", null, null, Quote(105.0)) }
        assertTrue(sell.message, sell.ok && sell.events.any { it is SandboxEvent.Fill })
        assertEquals(0, Paper.state.positions.filter { it.symbol == c.symbol }.sumOf { it.quantity })
        // The Trade tab shows the paper book without trouble.
        tap("Trade")
        Paper.wipe()
    }

    @Test fun settingsBotSettingsOpens() {
        assumeFalse("IraGoldAlgo's Settings are its own", BuildConfig.GOLD)
        tap(settingsTab())
        compose.waitUntil(10_000) { compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Bot settings"))
        tap("Bot settings")
        waitText("Account guard")
    }
}
