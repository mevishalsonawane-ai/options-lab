package com.optionslab.app.ui.screens

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.data.AppSettings
import com.optionslab.app.data.PineAuto
import com.optionslab.app.data.PineScripts
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.DeviceConfig
import com.optionslab.app.testing.ScreenTest
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.app.work.Alerts
import com.optionslab.engine.Upstox
import com.optionslab.engine.pine.Pine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** Pine page fixtures: scripts, candles, the page's environment (no AppModel, no network). */
internal object PineFakes {
    val LEVEL = """
        //@version=5
        indicator("Level signals", overlay = true)
        level = input.float(52000, "Level")
        buy = close > level
        sell = close < level
        plotshape(buy, "Buy", shape.labelup, location.belowbar, color.green)
        plotshape(sell, "Sell", shape.labeldown, location.abovebar, color.red)
    """.trimIndent()

    const val BROKEN = "//@version=5\nindicator(\"Broken\")\nx = close +\nplot(x)"

    /** 5-minute candles swinging around 52,000 so the level script trades both ways. */
    fun candles(n: Int = 400): List<Upstox.Bar> {
        val start = System.currentTimeMillis() / 1000 - n * 300L
        return (0 until n).map { i ->
            val c = 52_000.0 + 80.0 * Math.sin(i / 6.0)
            Upstox.Bar(start + i * 300L, c - 5, c + 10, c - 10, c, 1_000, 0)
        }
    }

    /** Forget the page's draft (it lives for the whole process, like the app's). */
    fun resetSession() {
        val c = Class.forName("com.optionslab.app.ui.screens.PineSession")
        val inst = c.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
        c.declaredMethods.first { it.name == "setOpen" }.apply { isAccessible = true }.invoke(inst, null)
    }

    val reauth: @Composable (String, () -> Unit, () -> Unit) -> Unit = { why, ok, cancel ->
        AlertDialog(onDismissRequest = cancel, confirmButton = { TextButton(ok) { Text("PIN OK") } },
            dismissButton = { TextButton(cancel) { Text("PIN cancel") } }, text = { Text(why) })
    }

    fun env(scope: CoroutineScope, live: Boolean = false, bars: suspend (String, String, Long) -> List<Upstox.Bar> = { _, _, _ -> candles() }) =
        PineEnv(scope, MutableStateFlow(if (live) AppSettings(mode = "live", allowRealOrders = true) else AppSettings()), reauth, bars)
}

internal fun ComposeTestRule.pineShown(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
internal fun ComposeTestRule.pineWaitFor(text: String, ms: Long = 20_000) = waitUntil(ms) { pineShown(text) }
internal fun ComposeTestRule.pineTap(text: String, exact: Boolean = true) {
    val n = onAllNodesWithText(text, substring = !exact).onFirst()
    runCatching { n.performScrollTo() }
    n.areaCClick(); waitForIdle()
}

@RunWith(AndroidJUnit4::class)
class PineScreenTest {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var onPage by mutableStateOf(true)
    private var charts = 0

    @Before fun up() {
        AutomationSupport.freshPine(ApplicationProvider.getApplicationContext())
        PineFakes.resetSession()
        AutomationSupport.clearAlerts()
    }

    @After fun down() { scope.cancel(); PineFakes.resetSession() }

    private fun page(env: PineEnv = PineFakes.env(scope)) = compose.setContent {
        IraAlgoTheme("light") { if (onPage) PineContent(env) { charts++ } else Text("Another tab") }
    }

    private fun saved(code: String = PineFakes.LEVEL, name: String = "Level", auto: PineScripts.Auto? = null): Long {
        val id = PineScripts.put(PineScripts.Item(0, name, code)).id
        auto?.let { PineScripts.setAuto(id, it) }
        return id
    }

    private fun open(name: String) { compose.pineWaitFor(name); compose.pineTap(name); compose.pineWaitFor("Compiles") }
    private fun nameField() = compose.onAllNodes(hasText("Name")).onFirst()

    // ---- list and editor ------------------------------------------------------------------------

    @Test fun aNewScriptStartsFromASampleAndIsSaved() {
        page()
        assertTrue(compose.pineShown("No scripts yet."))
        compose.pineTap("New script")
        compose.pineTap("Cancel")
        compose.pineTap("New script")
        compose.pineTap("Blank indicator")
        compose.pineWaitFor("✓ Compiles · indicator")
        compose.pineTap("Save")
        compose.waitUntil(5_000) { PineScripts.items.value.size == 1 }
        assertEquals("Blank indicator", PineScripts.items.value.single().name)
        compose.pineWaitFor("Saved")
        compose.pineTap("‹ Scripts")
        compose.pineWaitFor("Blank indicator")
        assertTrue("saved and unchanged: back without asking", !compose.pineShown("Discard your changes?"))
    }

    @Test fun aDraftSurvivesATabSwitch() {
        saved()
        page()
        open("Level")
        nameField().performTextReplacement("Level (edited)")
        compose.pineTap("Backtest")
        onPage = false; compose.waitForIdle()
        assertTrue(compose.pineShown("Another tab"))
        onPage = true; compose.waitForIdle()
        compose.pineWaitFor("Run backtest")
        compose.pineTap("Code")
        compose.pineWaitFor("Level (edited)")
        compose.onAllNodesWithText("Save").onFirst().assertIsEnabled()
        assertEquals("nothing is written until Save", "Level", PineScripts.items.value.single().name)
    }

    @Test fun unsavedChangesAreNeverDroppedWithoutAsking() {
        saved()
        page()
        open("Level")
        nameField().performTextReplacement("Renamed")
        compose.pineTap("‹ Scripts")
        assertTrue(compose.pineShown("Discard your changes?"))
        compose.pineTap("Keep editing")
        assertTrue("still editing", compose.pineShown("Renamed"))
        compose.pineTap("‹ Scripts")
        compose.pineTap("Discard")
        compose.pineWaitFor("New script")
        assertEquals("Level", PineScripts.items.value.single().name)
    }

    @Test fun compileErrorsAreShownAndBlockTheBacktest() {
        saved(PineFakes.BROKEN, "Broken")
        page()
        compose.pineWaitFor("Broken"); compose.pineTap("Broken")
        compose.pineWaitFor("error")
        assertTrue(compose.pineShown("✗ "))
        assertTrue("each error names its line", compose.pineShown("Line "))
        compose.pineTap("Backtest")
        assertTrue(compose.pineShown("The script has errors: fix them in Code first."))
        compose.pineTap("Auto-trade")
        compose.pineWaitFor("The saved script has errors")
    }

    @Test fun deletingAsksFirst() {
        saved()
        page()
        open("Level")
        compose.pineTap("Delete")
        assertTrue(compose.pineShown("Delete Level?"))
        compose.pineTap("Keep")
        assertEquals(1, PineScripts.items.value.size)
        compose.pineTap("Delete")
        compose.inDialog("Delete").areaCClick()
        compose.waitUntil(5_000) { PineScripts.items.value.isEmpty() }
        compose.pineWaitFor("New script")
    }

    // ---- backtest -------------------------------------------------------------------------------

    @Test fun aBacktestRunsAndShowsItsResult() {
        saved()
        page()
        open("Level")
        compose.pineTap("Backtest")
        compose.pineTap("Run backtest")
        compose.pineWaitFor("NET P&L")
        assertTrue(compose.pineShown("BANKNIFTY · 5m · 400 candles"))
        assertTrue(compose.pineShown("Trades ("))
        compose.onAllNodesWithText("Export CSV").onFirst().assertIsEnabled()
        // The result is kept in the draft: back on the page it is still there.
        onPage = false; compose.waitForIdle(); onPage = true; compose.waitForIdle()
        compose.pineWaitFor("NET P&L")
        compose.pineTap("Show on chart")
        compose.waitUntil(5_000) { charts == 1 }
        assertTrue(PineScripts.items.value.single().onChart)
    }

    @Test fun aFailedCandleReadIsReported() {
        saved()
        page(PineFakes.env(scope, bars = { _, _, _ -> throw java.io.IOException("No connection to the market data service") }))
        open("Level")
        compose.pineTap("Backtest")
        compose.pineTap("Run backtest")
        compose.pineWaitFor("Backtest failed: No connection to the market data service")
    }

    @Test fun noCandlesIsReported() {
        saved()
        page(PineFakes.env(scope, bars = { _, _, _ -> emptyList() }))
        open("Level")
        compose.pineTap("Backtest"); compose.pineTap("Run backtest")
        compose.pineWaitFor("No BANKNIFTY 5m candles came back for that period")
    }

    @Test fun theOptimiserRanksInputValues() {
        saved()
        page()
        open("Level")
        compose.pineTap("Backtest")
        compose.pineTap("Set up")
        compose.pineWaitFor("combination")
        compose.pineTap("Run optimiser")
        compose.pineWaitFor("Tap a row to use its values")
    }

    @Test fun csvExportQuotesAndDefusesScriptText() {
        val t = Pine.Trade("=HYPERLINK(\"x\")", "Sell \"now\"", true, 1.0, 0, 1_790_000_000, 100.0, 5, 1_790_001_500, 110.0, 10.0, 10.0, 0.0, false)
        val rep = Pine.Report(100_000.0, 10.0, 0.01, 10.0, 0.0, null, 1, 1, 0, 100.0, 10.0, 10.0, 0.0, 10.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 5.0,
            listOf(t), DoubleArray(0))
        val csv = tradesCsv(rep).lines()
        assertEquals("entry_time,exit_time,side,entry,exit,qty,entry_price,exit_price,pnl,pnl_pct,charges,status", csv[0])
        assertTrue(csv[1], csv[1].contains(",LONG,\"'=HYPERLINK(\"\"x\"\")\",\"Sell \"\"now\"\"\",1.00,100.00,110.00,10.00,10.00,0.00,closed"))
    }

    // ---- auto-trade -----------------------------------------------------------------------------

    @Test fun arming_isBlockedWhileTheDraftHasUnsavedEdits() {
        saved(auto = PineScripts.Auto(buy = "Buy", sell = "Sell"))
        page()
        open("Level")
        nameField().performTextReplacement("Level 2")
        compose.pineTap("Auto-trade")
        compose.pineWaitFor("Save the script first (Code tab)")
        compose.onAllNodes(isToggleable()).onFirst().areaCClick(); compose.waitForIdle()
        compose.waitUntil(3_000) { Alerts.queue.value.any { it.text.startsWith("Save the script first") } }
        assertFalse("not switched on", PineScripts.items.value.single().auto.on)
    }

    @Test fun anArmedScriptIsNeverChangedSilently() {
        saved(auto = PineScripts.Auto(on = true, buy = "Buy", sell = "Sell"))
        page()
        open("Level")
        nameField().performTextReplacement("Level 2")
        compose.onAllNodesWithText("Save").onFirst().assertIsNotEnabled()
        assertTrue(compose.pineShown("Auto-trade is on: switch it off (Auto-trade tab) to save changes."))
        compose.pineTap("Auto-trade")
        compose.pineWaitFor("Switch it off to change these.")
        compose.pineTap("Alerts only")
        compose.pineTap("NIFTY")
        compose.waitForIdle(); Thread.sleep(300); compose.waitForIdle()
        val a = PineScripts.items.value.single()
        assertEquals("trade", a.auto.mode); assertEquals("BANKNIFTY", a.auto.symbol)
        assertEquals("the saved code is what it trades", "Level", a.name)
        compose.pineTap("‹ Scripts")
        assertTrue(compose.pineShown("Auto-trade is on, so they cannot be saved until it is switched off."))
    }

    @Test fun alertsOnlyIsSwitchedOnWithoutThePinEvenInLive() {
        AutomationSupport.liveSettings()
        saved(auto = PineScripts.Auto(buy = "Buy", sell = "Sell"))
        page(PineFakes.env(scope, live = true))
        open("Level")
        compose.pineTap("Auto-trade")
        compose.pineWaitFor("Follows the app switch: LIVE")
        compose.pineTap("Alerts only")
        compose.waitUntil(5_000) { PineScripts.items.value.single().auto.mode == "alert" }
        compose.pineWaitFor("Alerts only: a notification on each signal, no orders")
        compose.onAllNodes(isToggleable()).onFirst().areaCClick()
        compose.waitUntil(5_000) { PineScripts.items.value.single().auto.on }
        assertTrue("no PIN asked", !compose.pineShown("PIN OK"))
        compose.waitUntil(5_000) { PineAuto.log.value.any { it.text.startsWith("Switched on (alerts only)") } }
    }

    @Test fun liveTradingIsSwitchedOnOnlyWithThePin() {
        AutomationSupport.liveSettings()
        saved(auto = PineScripts.Auto(buy = "Buy", sell = "Sell"))
        page(PineFakes.env(scope, live = true))
        open("Level")
        compose.pineTap("Auto-trade")
        compose.onAllNodes(isToggleable()).onFirst().areaCClick(); compose.waitForIdle()
        assertTrue(compose.pineShown("Enter your app PIN to let this Pine script trade on Zerodha"))
        compose.pineTap("PIN cancel")
        assertFalse(PineScripts.items.value.single().auto.on)
        compose.onAllNodes(isToggleable()).onFirst().areaCClick(); compose.waitForIdle()
        compose.pineTap("PIN OK")
        compose.waitUntil(5_000) { PineScripts.items.value.single().auto.on }
        compose.waitUntil(5_000) { PineAuto.log.value.any { it.text.startsWith("Switched on (Live)") } }
        compose.pineWaitFor("Auto-trading")
    }

    @Test fun protectionSettingsTypedAreSaved() {
        saved(auto = PineScripts.Auto(buy = "Buy", sell = "Sell"))
        page()
        open("Level")
        compose.pineTap("Auto-trade")
        compose.onAllNodes(hasText("Stop-loss (pts)")).onFirst().performTextReplacement("25")
        compose.mainClock.advanceTimeBy(700)                        // written 0.6 s after the last keystroke
        compose.waitUntil(5_000) { PineScripts.items.value.single().auto.stopPts == 25.0 }
        compose.pineTap("Just exit")
        compose.waitUntil(5_000) { PineScripts.items.value.single().auto.shortWith == "exit" }
        compose.pineTap("3")
        compose.waitUntil(5_000) { PineScripts.items.value.single().auto.lots == 3 }
    }
}

/** The Pine page's states on every device set-up. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PineScreensLayoutTest(private val config: DeviceConfig) : ScreenTest(config) {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun configs(): List<Array<Any>> = DeviceConfig.matrix()

        /** Real layout bugs found here (skipped with this text until fixed). */
        val EDITOR_BUGS = mapOf("*" to "Pine editor: two clickables with no label (the 'Show on the chart' switch of ToggleRow and one more), unreadable by TalkBack")
        /** Fixed: the choice tokens were 29 dp high and the auto-trade switch had no label. */
        val TOKEN_BUGS = emptyMap<String, String>()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before fun up() {
        AutomationSupport.freshPine(ApplicationProvider.getApplicationContext())
        PineFakes.resetSession()
    }

    @After fun down() { scope.cancel(); PineFakes.resetSession() }

    private fun openLevel() {
        PineScripts.put(PineScripts.Item(0, "Level", PineFakes.LEVEL))
        show { PineContent(PineFakes.env(scope)) }
        compose.pineWaitFor("Level"); compose.pineTap("Level"); compose.pineWaitFor("Compiles")
    }

    @Test fun list() {
        PineScripts.put(PineScripts.Item(0, "Level", PineFakes.LEVEL))
        PineScripts.put(PineScripts.Item(0, "Broken", PineFakes.BROKEN))
        show { PineContent(PineFakes.env(scope)) }
        compose.pineWaitFor("ERRORS"); compose.pineWaitFor("INDICATOR")
        capture("pine-list"); lint("pine-list")
    }

    @Test fun editor() {
        openLevel()
        capture("pine-editor"); lint("pine-editor", knownBugs = EDITOR_BUGS)
    }


    @Test fun autoTrade() {
        openLevel()
        compose.pineTap("Auto-trade"); compose.pineWaitFor("Follows the app switch")
        capture("pine-auto"); lint("pine-auto", knownBugs = TOKEN_BUGS)
    }
}

/** The Pine page's secondary states (the backtest result, the discard dialog) on six set-ups. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PineDialogsLayoutTest(private val config: DeviceConfig) : ScreenTest(config) {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun configs(): List<Array<Any>> = DeviceConfig.matrix().filter { (it[0] as DeviceConfig).name in SIX_SETUPS }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before fun up() {
        AutomationSupport.freshPine(ApplicationProvider.getApplicationContext())
        PineFakes.resetSession()
    }

    @After fun down() { scope.cancel(); PineFakes.resetSession() }

    @Test fun backtestResult() {
        PineScripts.put(PineScripts.Item(0, "Level", PineFakes.LEVEL))
        show { PineContent(PineFakes.env(scope)) }
        compose.pineWaitFor("Level"); compose.pineTap("Level"); compose.pineWaitFor("Compiles")
        compose.pineTap("Backtest"); compose.pineTap("Run backtest"); compose.pineWaitFor("NET P&L")
        capture("pine-backtest"); lint("pine-backtest", knownBugs = PineScreensLayoutTest.TOKEN_BUGS)
    }

    @Test fun discardDialog() {
        PineScripts.put(PineScripts.Item(0, "Level", PineFakes.LEVEL))
        show { PineContent(PineFakes.env(scope)) }
        compose.pineWaitFor("Level"); compose.pineTap("Level"); compose.pineWaitFor("Compiles")
        compose.onAllNodes(hasText("Name")).onFirst().performTextReplacement("Changed")
        compose.pineTap("‹ Scripts")
        areaCCaptureTop(compose, "pine-discard", config); lint("pine-discard")
    }
}
