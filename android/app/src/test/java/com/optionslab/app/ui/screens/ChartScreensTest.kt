package com.optionslab.app.ui.screens

import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.click
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.data.PriceAlarm
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.DeviceConfig
import com.optionslab.app.testing.FakeChartSource
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.ResearchFixtures
import com.optionslab.app.testing.ScreenTest
import com.optionslab.app.testing.TEST_OPTION
import com.optionslab.app.testing.testBars
import com.optionslab.app.ui.Load
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/** A Bank Nifty option listed alongside [TEST_OPTION], for the chart's option-chain picks. */
internal val BN_OPTION = Upstox.Contract("BANKNIFTY", LocalDate.of(2026, 10, 1), 52000.0, Right.PE, 30, "NSE_FO|TEST2", "BANKNIFTY26OCT52000PE")

/**
 * The Chart tab (its Compose parts: the web chart cannot render under Robolectric, so the page's side of the
 * IraBridge is played by the test calling the bridge directly), the basic chart, the option page, and the
 * alert and chain dialogs. Candles come from [FakeChartSource]; nothing reaches the network.
 */
@RunWith(AndroidJUnit4::class)
class ChartScreensTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val watchdog = com.optionslab.app.testing.ResearchWatchdog()

    private val source = FakeChartSource(contracts = listOf(TEST_OPTION, BN_OPTION))

    @Before fun fresh() { clearAlerts() }
    @After fun noNetwork() { assertEquals("no host may be reached", emptyList<String>(), NetworkGuard.blocked.toList()) }

    private fun set(content: @Composable () -> Unit) = compose.setContent { IraAlgoTheme("light") { content() } }
    private fun text(t: String, sub: Boolean = false) = compose.onNodeWithText(t, substring = sub)
    private fun shows(t: String, sub: Boolean = true) = compose.onAllNodesWithText(t, substring = sub).fetchSemanticsNodes().isNotEmpty()
    private fun idle() { shadowOf(Looper.getMainLooper()).idle(); compose.waitForIdle() }
    private fun until(what: () -> Boolean) {
        if (compose.mainClock.autoAdvance) return compose.waitUntil(5_000) { idle(); what() }
        // A dialog holding a text field never reports idle while the clock runs by itself: frames by hand.
        val end = System.currentTimeMillis() + 5_000
        while (true) {
            idle(); frames(2)
            if (runCatching(what).getOrDefault(false)) return
            if (System.currentTimeMillis() > end) throw AssertionError("condition not met in 5 s")
            Thread.sleep(20)
        }
    }
    private fun frames(n: Int = 12) = repeat(n) { compose.mainClock.advanceTimeByFrame() }

    // ---- the web chart's host -----------------------------------------------------------------------

    private fun webViews(): List<WebView> {
        val out = ArrayList<WebView>()
        fun walk(v: View) { if (v is WebView) out += v; if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i)) }
        walk(compose.activity.window.decorView)
        return out
    }
    private fun web(): WebView = webViews().single()
    private fun bridge(): Bridge = shadowOf(web()).getJavascriptInterface("IraBridge") as Bridge
    private fun lastJs(): String? = shadowOf(web()).lastEvaluatedJavascript

    /** What the order sheet slot was opened with. */
    private data class Sheet(val pick: ChainPick, val buy: Boolean, val limit: Double?)

    private class Pane {
        var symbol by mutableStateOf("BANKNIFTY")
        var exchange by mutableStateOf("NSE")
        var ask by mutableIntStateOf(0)
        var visible by mutableStateOf(true)
        val sheets = ArrayList<Sheet>()
        val alerts = ArrayList<String>()
        val chains = ArrayList<String>()
        var chainPick: ChainPick? = null
    }

    private fun pane(symbol: String = "BANKNIFTY", exchange: String = "NSE"): Pane {
        val s = Pane().also { it.symbol = symbol; it.exchange = exchange }
        set {
            ChartPane(s.symbol, s.exchange, s.visible, s.ask, live = false, source = source,
                orderSheet = { pick, buy, limit, close ->
                    androidx.compose.runtime.LaunchedEffect(pick, buy, limit) { s.sheets += Sheet(pick, buy, limit) }
                    Column { Text("sheet ${if (buy) "BUY" else "SELL"} ${pick.underlying} ${pick.strike} ${pick.right} ltp=${pick.ltp} limit=$limit"); TextButton(close) { Text("Close sheet") } }
                },
                alertDialog = { sym, close ->
                    androidx.compose.runtime.LaunchedEffect(sym) { s.alerts += sym }
                    TextButton(close) { Text("Close alert on $sym") }
                },
                chainDialog = { u, close, pick ->
                    androidx.compose.runtime.LaunchedEffect(u) { s.chains += u }
                    Column {
                        TextButton(close) { Text("Close chain") }
                        TextButton({ s.chainPick?.let(pick) }) { Text("Pick from chain") }
                    }
                })
        }
        idle()
        return s
    }

    /** The page's first candles arrive: the cover lifts. */
    private fun loadCandles(symbol: String = "BANKNIFTY") {
        bridge().bars("1", symbol, "NSE", "5m", "0", "${System.currentTimeMillis() / 1000}")
        until { !shows("Loading chart…") }
    }

    @Test fun loadsOnlyTheBundledTerminalAndCoversItUntilTheFirstCandles() {
        pane()
        assertEquals("https://appassets.androidplatform.net/terminal.html?symbol=BANKNIFTY&exchange=NSE&theme=light", shadowOf(web()).lastLoadedUrl)
        text("Loading chart…").assertIsDisplayed()
        listOf("BANKNIFTY", "OPT", "ADV", "ALERT", "BUY", "SELL").forEach { text(it).assertIsDisplayed() }
        loadCandles()
        assertTrue(source.asked.contains("BANKNIFTY 5m"))
        assertFalse(shows("Showing the basic chart"))
        // Once the chart has candles it is asked to draw the owner's Pine scripts.
        until { lastJs() == "window.__iraPine && window.__iraPine()" }
    }

    @Test fun searchAnswersFromTheSourceAndNeverFails() {
        pane()
        bridge().search("7", "24800")
        until { lastJs()?.contains("NIFTY26O0124800CE") == true }
        assertTrue(lastJs()!!.startsWith("window.__iraReply(\"7\", true"))
    }

    @Test fun candlesThatFailBeforeTheChartIsReadyBringInTheBasicChart() {
        source.failure = "HTTP 503"
        pane()
        bridge().bars("2", "BANKNIFTY", "NSE", "5m", "0", "0")
        until { shows("Showing the basic chart") }
        text("Could not load BANKNIFTY: HTTP 503 Showing the basic chart. Tap ADV / BASIC above to try the advanced one again.").assertIsDisplayed()
        text("BASIC").assertIsDisplayed()
        assertTrue(lastJs()!!.startsWith("window.__iraReply(\"2\", false"))
    }

    @Test fun aPageErrorBeforeTheFirstCandlesIsFatal() {
        pane()
        bridge().fail("TypeError: widget is undefined")
        until { shows("Showing the basic chart") }
        text("TypeError: widget is undefined Showing the basic chart. Tap ADV / BASIC above to try the advanced one again.").assertIsDisplayed()
        // The basic chart draws the same symbol from the same source.
        until { source.asked.contains("BANKNIFTY 5m") }
        // ADV tries the web chart again: a fresh page.
        val first = web()
        text("BASIC").performClick()
        idle()
        assertNotSame(first, web())
        text("ADV").assertIsDisplayed()
        text("Loading chart…").assertIsDisplayed()
    }

    @Test fun aPageErrorAfterTheFirstCandlesIsNotFatal() {
        pane()
        loadCandles()
        bridge().fail("ResizeObserver loop limit exceeded")
        idle()
        assertFalse(shows("Showing the basic chart"))
        assertFalse(shows("ResizeObserver"))
        text("ADV").assertIsDisplayed()
    }

    @Test fun symbolSwitchSetsTheSymbolEvenWhenTheWebChartFailed() {
        val s = pane()
        bridge().fail("SyntaxError")
        until { shows("Showing the basic chart") }
        s.symbol = TEST_OPTION.tradingSymbol; s.exchange = "NFO"; s.ask++
        idle()
        text(TEST_OPTION.tradingSymbol).assertIsDisplayed()
        until { source.asked.contains("${TEST_OPTION.tradingSymbol} 5m") }
        assertEquals("window.__iraSetSymbol && window.__iraSetSymbol(\"${TEST_OPTION.tradingSymbol}\", \"NFO\")", lastJs())
        // The option's own index chain is one tap away.
        text("OPT").assertIsDisplayed()
    }

    @Test fun thePageReportsSymbolChangesAndTheChainFollows() {
        pane()
        loadCandles()
        bridge().symbol("FINNIFTY", "NSE")
        idle()
        text("FINNIFTY").assertIsDisplayed()
        assertFalse("FINNIFTY has no option chain here", shows("OPT", sub = false))
        bridge().symbol("NIFTY", "NSE")
        idle()
        text("OPT").performClick()
        text("Close chain").performClick()
        assertFalse(shows("Close chain"))
    }

    @Test fun indicesCannotBeTradedFromTheChart() {
        val s = pane()
        text("BUY").performClick()
        until { shows("Indices cannot be traded.") }
        text("Indices cannot be traded. Search an option in the chart (e.g. NIFTY 24800 CE) to buy or sell it.").assertIsDisplayed()
        assertTrue(s.sheets.isEmpty())
    }

    @Test fun buyAndSellOpenTheSheetAtTheLastTradedPrice() {
        val s = pane(TEST_OPTION.tradingSymbol, "NFO")
        val last = testBars(60).last().close
        text("BUY").performClick()
        until { s.sheets.isNotEmpty() }
        assertEquals(Sheet(ChainPick("NIFTY", TEST_OPTION.expiry, 24800.0, Right.CE, last, null, null, 75), true, null), s.sheets.single())
        text("Close sheet").performClick()
        assertFalse(shows("sheet BUY", sub = true))
        text("SELL").performClick()
        until { s.sheets.size == 2 }
        assertEquals(false, s.sheets[1].buy)
        assertNull(s.sheets[1].limit)
        assertTrue(source.asked.all { it.endsWith(" 1m") })
    }

    @Test fun aLongPressLimitKeepsTheFingerPriceAsTheLimitNotTheLtp() {
        val s = pane(TEST_OPTION.tradingSymbol, "NFO")
        loadCandles(TEST_OPTION.tradingSymbol)
        bridge().order("""{"side":"SELL","type":"LIMIT","price":142.35,"symbol":"${TEST_OPTION.tradingSymbol}"}""")
        until { s.sheets.isNotEmpty() }
        val sheet = s.sheets.single()
        assertEquals(false, sheet.buy)
        assertEquals(142.35, sheet.limit!!, 0.0)
        assertEquals("the sheet's LTP is the last trade, not the level under the finger", testBars(60).last().close, sheet.pick.ltp!!, 0.0)
        text("sheet SELL NIFTY 24800.0 CE ltp=130.0 limit=142.35").assertIsDisplayed()
    }

    @Test fun stopEntriesFromTheChartAreRefusedWithAHint() {
        val s = pane(TEST_OPTION.tradingSymbol, "NFO")
        loadCandles(TEST_OPTION.tradingSymbol)
        for (type in listOf("SL", "SL-M", "STOP")) {
            bridge().order("""{"side":"BUY","type":"$type","price":150}""")
            idle()
        }
        text("Stop entries cannot be placed from the chart. Use BUY / SELL for a market or limit order.").assertIsDisplayed()
        assertTrue(s.sheets.isEmpty())
        // A limit with no price is dropped, not turned into a market order; so is a malformed message.
        bridge().order("""{"side":"BUY","type":"LIMIT"}""")
        bridge().order("not json")
        idle()
        assertTrue(s.sheets.isEmpty())
        // A market order ignores any price the page sent.
        bridge().order("""{"side":"BUY","type":"MARKET","price":999}""")
        until { s.sheets.isNotEmpty() }
        assertNull(s.sheets.single().limit)
        assertTrue(s.sheets.single().buy)
    }

    @Test fun basicOrAdvancedIsTheOwnersChoiceAndRemembered() {
        pane()
        text("ADV").performClick()
        text("BASIC").assertIsDisplayed()
        assertTrue(SecurePrefs.getBoolean("chart.basic", false))
        until { shows("5m", sub = false) }
        text("5m").assertIsSelected()
        assertFalse("no fallback notice when chosen", shows("Showing the basic chart"))
        text("BASIC").performClick()
        text("ADV").assertIsDisplayed()
        assertFalse(SecurePrefs.getBoolean("chart.basic", true))
    }

    @Test fun basicChosenEarlierOpensBasic() {
        SecurePrefs.put("chart.basic", true)
        pane()
        text("BASIC").assertIsDisplayed()
        until { shows("5m", sub = false) }
        assertFalse(shows("Loading chart…", sub = false) && !shows("C 130.00"))
    }

    @Test fun anOptionPickedOffTheChainIsChartedAndBackReturnsToTheIndex() {
        val s = pane()
        s.chainPick = ChainPick("BANKNIFTY", BN_OPTION.expiry, 52000.0, Right.PE, 210.0, -0.4, 15.0, 30)
        text("OPT").performClick()
        assertEquals(listOf("BANKNIFTY"), s.chains)
        text("Pick from chain").performClick()
        until { shows(BN_OPTION.tradingSymbol, sub = false) }
        assertFalse(shows("Close chain"))
        text("‹ BANKNIFTY").assertIsDisplayed()
        assertEquals("window.__iraSetSymbol && window.__iraSetSymbol(\"${BN_OPTION.tradingSymbol}\", \"NFO\")", lastJs())
        text("‹ BANKNIFTY").performClick()
        text("BANKNIFTY").assertIsDisplayed()
        assertFalse(shows("‹ BANKNIFTY"))
        // The phone's back does the same.
        text("OPT").performClick()
        text("Pick from chain").performClick()
        until { shows("‹ BANKNIFTY") }
        compose.activity.onBackPressedDispatcher.onBackPressed()
        idle()
        text("BANKNIFTY").assertIsDisplayed()
        assertFalse(shows("‹ BANKNIFTY"))
    }

    @Test fun aChainPickNotInTodaysListSaysSo() {
        val s = pane()
        s.chainPick = ChainPick("BANKNIFTY", BN_OPTION.expiry, 99000.0, Right.CE, 1.0, null, null, 30)
        text("OPT").performClick()
        text("Pick from chain").performClick()
        until { shows("That option is not in today's contract list; try again in a moment.") }
        text("BANKNIFTY").assertIsDisplayed()
    }

    @Test fun alertOpensForTheChartedSymbol() {
        val s = pane(TEST_OPTION.tradingSymbol, "NFO")
        text("ALERT").performClick()
        text("Close alert on ${TEST_OPTION.tradingSymbol}").performClick()
        assertEquals(listOf(TEST_OPTION.tradingSymbol), s.alerts)
        assertFalse(shows("Close alert"))
    }

    @Test fun aChartWithNoCandlesIsRebuiltOnceThenFallsBack() {
        pane()
        val first = web()
        compose.mainClock.advanceTimeBy(12_100)
        idle()
        val second = web()
        assertNotSame("rebuilt after 12 s without candles", first, second)
        assertTrue(shadowOf(first).wasDestroyCalled())
        text("Loading chart…").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(12_100)
        idle()
        until { shows("Showing the basic chart") }
        text("The advanced chart could not load. Showing the basic chart. Tap ADV / BASIC above to try the advanced one again.").assertIsDisplayed()
        assertTrue(alertTexts().contains("The chart could not load. Check the connection and tap Retry."))
    }

    @Test fun candlesThatNeverPaintSwitchToBasicAfter8s() {
        pane()
        loadCandles()
        bridge().painted(411, 0, 120)        // "drawn 411×0": the page drew nothing visible
        idle()
        compose.mainClock.advanceTimeBy(8_100)
        until { shows("The advanced chart could not draw on this phone.", sub = true) }
    }

    @Test fun aChartThatPaintedStaysAdvanced() {
        pane()
        loadCandles()
        bridge().painted(411, 640, 120)
        idle()
        compose.mainClock.advanceTimeBy(8_100)
        idle()
        assertFalse(shows("Showing the basic chart"))
        text("ADV").assertIsDisplayed()
    }

    @Test fun aHiddenChartIsPausedAndGone() {
        val s = pane()
        val w = web()
        s.visible = false
        idle()
        assertEquals(View.GONE, w.visibility)
        assertTrue(shadowOf(w).wasOnPauseCalled())
        assertEquals("window.__iraPause && window.__iraPause(true)", shadowOf(w).lastEvaluatedJavascript)
        s.visible = true
        idle()
        assertEquals(View.VISIBLE, w.visibility)
        assertTrue(shadowOf(w).wasOnResumeCalled())
        assertEquals("window.__iraPause && window.__iraPause(false)", shadowOf(w).lastEvaluatedJavascript)
    }

    @Test fun onlyTheBundledChartFilesAreServed() {
        pane()
        val w = web()
        val client = shadowOf(w).webViewClient
        fun status(url: String) = client.shouldInterceptRequest(w, request(url))!!.statusCode
        assertEquals(200, status("https://appassets.androidplatform.net/terminal.html"))
        assertEquals(200, status("https://appassets.androidplatform.net/terminal.mjs"))
        assertEquals(403, status("https://appassets.androidplatform.net/missing.js"))
        assertEquals(403, status("https://appassets.androidplatform.net/../../shared_prefs/x.xml"))
        assertEquals(403, status("https://appassets.androidplatform.net/sub/dir.js"))
        assertEquals(403, status("http://appassets.androidplatform.net/terminal.html"))
        assertEquals(403, status("https://example.com/terminal.html"))
        assertEquals(403, status("file:///android_asset/chart/terminal.html"))
        assertTrue("no navigation away", client.shouldOverrideUrlLoading(w, request("https://example.com/")))
        assertTrue(NetworkGuard.blocked.isEmpty())
    }

    @Test fun aKilledRendererRebuildsTheChart() {
        pane()
        val w = web()
        assertTrue(shadowOf(w).webViewClient.onRenderProcessGone(w, gone()))
        idle()
        assertNotSame(w, web())
        text("Loading chart…").assertIsDisplayed()
    }

    /**
     * After the web chart is rebuilt (renderer killed, the 12 s retry, ADV after a failure) the new page loads
     * the symbol the tab was first opened with, and its first report resets the header to it: the symbol the
     * owner was looking at (searched in the chart, or picked off the chain) is lost.
     */
    @org.junit.Ignore("UI BUG: Chart tab: open BANKNIFTY, pick an option in the chart's search (or off the OPT chain), then the web chart is rebuilt " +
        "(renderer killed, the 12 s retry, or ADV after a failure); expected the rebuilt page to load the option on screen; actual it loads " +
        "terminal.html?symbol=BANKNIFTY (the symbol the tab was opened with) and its first IraBridge.symbol report resets the header to BANKNIFTY")
    @Test fun aRebuiltChartKeepsTheSymbolOnScreen() {
        pane()
        loadCandles()
        bridge().symbol(TEST_OPTION.tradingSymbol, "NFO")      // the owner searched for an option in the chart
        idle()
        text(TEST_OPTION.tradingSymbol).assertIsDisplayed()
        val w = web()
        shadowOf(w).webViewClient.onRenderProcessGone(w, gone())
        idle()
        assertTrue(shadowOf(web()).lastLoadedUrl, shadowOf(web()).lastLoadedUrl.contains("symbol=${TEST_OPTION.tradingSymbol}&exchange=NFO"))
    }

    private fun request(url: String) = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame() = true
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }

    private fun gone() = object : RenderProcessGoneDetail() {
        override fun didCrash() = false
        override fun rendererPriorityAtExit() = 0
    }

    // ---- the basic chart ----------------------------------------------------------------------------

    @Test fun basicChartPollsOnlyWhileVisible() {
        var visible by mutableStateOf(false)
        val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
        set { NativeChart("NIFTY", visible = visible) { s, iv -> calls += "$s $iv"; testBars(60) } }
        idle()
        assertTrue("hidden: nothing fetched", calls.isEmpty())
        text("Loading chart…").assertIsDisplayed()
        visible = true
        until { calls.isNotEmpty() && shows(" O ") }
        assertEquals(listOf("NIFTY 5m"), calls.toList())
        visible = false
        idle()
        compose.mainClock.advanceTimeBy(600_000)
        idle()
        assertEquals("hidden again: no more polls", 1, calls.size)
    }

    @Test fun basicChartIntervalsRefetchAndTapPicksACandle() {
        val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
        set { NativeChart("NIFTY") { s, iv -> calls += "$s $iv"; testBars(60) } }
        until { shows("C 130.00") }
        assertTrue(shows("O 129.50"))
        text("15m").performClick()
        until { calls.contains("NIFTY 15m") }
        text("15m").assertIsSelected()
        until { shows("C 130.00") }
        // A tap on the first visible candle shows its OHLC instead of the last one's.
        compose.onRoot().performTouchInput { click(Offset(3f, height / 2f)) }
        idle()
        assertFalse(shows("O 129.50"))
        // Dragging right scrolls back in time; a tap then picks an earlier candle.
        val before = compose.onAllNodesWithText(" O ", substring = true).fetchSemanticsNodes().first().config[SemanticsProperties.Text].first().text
        compose.onRoot().performTouchInput { swipeRight(startX = width * 0.2f, endX = width * 0.8f) }
        compose.onRoot().performTouchInput { click(Offset(3f, height / 2f)) }
        idle()
        val after = compose.onAllNodesWithText(" O ", substring = true).fetchSemanticsNodes().first().config[SemanticsProperties.Text].first().text
        assertTrue("$before -> $after", openOf(after) < openOf(before))
        assertTrue(shows("drag to scroll, tap a candle"))
    }

    private fun openOf(head: String) = Regex(" O ([0-9.,]+)").find(head)!!.groupValues[1].replace(",", "").toDouble()

    @Test fun basicChartSaysWhenThereAreNoCandlesOrTheLoadFailed() {
        var fail by mutableStateOf(false)
        set { NativeChart(if (fail) "BANKNIFTY" else "NIFTY") { s, _ -> if (s == "BANKNIFTY") throw java.io.IOException("HTTP 500") else emptyList() } }
        until { shows("No candles for NIFTY 5m yet.") }
        fail = true
        until { shows("Could not load BANKNIFTY: HTTP 500") }
    }

    // ---- the option page ------------------------------------------------------------------------------

    private val pick = ChainPick("NIFTY", LocalDate.of(2026, 10, 1), 24800.0, Right.CE, 88.5, 0.45, 14.2, 75)

    private class OptPage { val full = ArrayList<String>(); var closed = 0; val sheets = ArrayList<Pair<ChainPick, Boolean>>() }

    private fun option(intraday: suspend () -> List<Upstox.Bar>, symbol: String? = TEST_OPTION.tradingSymbol): OptPage {
        val pg = OptPage()
        set {
            OptionChartContent(pick, intraday, { symbol }, onFullChart = { pg.full += it }, onClose = { pg.closed++ }) { pk, buy, close ->
                androidx.compose.runtime.LaunchedEffect(pk, buy) { pg.sheets += pk to buy }
                TextButton(close) { Text("Close sheet") }
            }
        }
        idle()
        return pg
    }

    @Test fun optionPageLoadingShowsTheContractAndItsLastPrice() {
        val never = CompletableDeferred<List<Upstox.Bar>>()
        option({ never.await() })
        text("NIFTY 1 OCT 24800 CE").assertIsDisplayed()
        text("Lot 75 · IV 14.2% · Δ 0.45").assertIsDisplayed()
        text("Loading today's prices…").assertIsDisplayed()
        text("₹88.50").assertIsDisplayed()
    }

    @Test fun optionPageErrorAndNoTrades() {
        option({ throw java.io.IOException("HTTP 429") })
        until { shows("Could not load the chart: HTTP 429") }
        text("₹88.50").assertIsDisplayed()
    }

    @Test fun optionPageWithNoTradesYet() {
        option({ emptyList() })
        until { shows("No trades in this option yet today.") }
    }

    @Test fun optionPageShowsTheSessionAndTradesAtTheLastPrice() {
        val bars = testBars(60)
        val pg = option({ bars })
        until { shows("₹130.00") }
        text("+30.00 (30.00%)").assertIsDisplayed()
        text("Change since today's open").assertIsDisplayed()
        text("100.00").assertIsDisplayed()                              // open
        text("%.2f".format(bars.maxOf { it.high })).assertIsDisplayed()
        text("%.2f".format(bars.minOf { it.low })).assertIsDisplayed()
        text("%,d".format(bars.sumOf { it.volume })).assertIsDisplayed()
        text("%,d".format(bars.last().oi)).assertIsDisplayed()
        text("BUY").performClick()
        until { pg.sheets.isNotEmpty() }
        assertEquals(pick.copy(ltp = 130.0) to true, pg.sheets.single())
        text("Close sheet").performClick()
        text("SELL").performClick()
        until { pg.sheets.size == 2 }
        assertEquals(false, pg.sheets[1].second)
    }

    @Test fun optionPageFullChartAndClose() {
        val pg = option({ testBars(5) })
        text("Full chart ›").performClick()
        until { pg.full.isNotEmpty() }
        assertEquals(listOf(TEST_OPTION.tradingSymbol), pg.full)
        text("‹").performClick()
        assertEquals(1, pg.closed)
    }

    @Test fun optionPageFullChartDoesNothingForAnUnlistedOption() {
        val pg = option({ testBars(5) }, symbol = null)
        text("Full chart ›").performClick()
        idle()
        assertTrue(pg.full.isEmpty())
    }

    // ---- the alert dialog -------------------------------------------------------------------------------

    private fun alertDialog(): Pair<ArrayList<Pair<PriceAlarm, String>>, () -> Int> {
        val saved = ArrayList<Pair<PriceAlarm, String>>(); var closed = 0
        compose.mainClock.autoAdvance = false        // the dialog's text field: frames by hand
        set { ChartAlertDialog(TEST_OPTION.tradingSymbol, source, onSave = { a, m -> saved += a to m }, onClose = { closed++ }) }
        return saved to { closed }
    }

    private fun level(v: String) {
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNode(hasSetTextAction()).performTextInput(v)
        frames()
    }

    @Test fun alertAboveTheLastPriceFiresOnARise() {
        val (saved, closed) = alertDialog()
        until { shows("Now 130.00") }
        text("Alert on ${TEST_OPTION.tradingSymbol}").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assert(hasText("130.00"))
        level("140")
        text("Fires when it rises to 140").assertIsDisplayed()
        text("Set alert").performClick()
        until { saved.isNotEmpty() }
        val (a, said) = saved.single()
        assertEquals(PriceAlarm.CHART + TEST_OPTION.tradingSymbol, a.symbol)
        assertTrue(a.above); assertEquals(140.0, a.level, 0.0)
        assertEquals("Alert set: ${TEST_OPTION.tradingSymbol} at 140", said)
        assertEquals(1, closed())
    }

    @Test fun alertBelowFiresOnAFallAndZeroIsRefused() {
        val (saved, closed) = alertDialog()
        until { shows("Now 130.00") }
        level("120.5")
        text("Fires when it falls to 120.5").assertIsDisplayed()
        level("0")
        text("Set alert").performClick()
        assertEquals(listOf("Enter a price above zero."), alertTexts())
        assertTrue(saved.isEmpty())
        level("120.5")
        text("Set alert").performClick()
        until { saved.isNotEmpty() }
        assertFalse(saved.single().first.above)
        assertEquals(1, closed())
    }

    @Test fun alertWithoutAPriceCannotBeSetAndCancelCloses() {
        source.failure = "HTTP 503"
        val (saved, closed) = alertDialog()
        idle()
        text("Reading the price…").assertIsDisplayed()
        level("140")
        text("Set alert").performClick()
        assertEquals(listOf("Enter a price above zero."), alertTexts())
        text("Cancel").performClick()
        assertEquals(1, closed())
        assertTrue(saved.isEmpty())
    }

    // ---- the chain dialog --------------------------------------------------------------------------------

    @Test fun chainDialogStatesAndAPick() {
        var snap by mutableStateOf<Load<com.optionslab.engine.options.ChainSnapshot>>(Load.Busy("Pricing the NIFTY chain"))
        val picks = ArrayList<ChainPick>(); var retries = 0; var closed = 0
        set { ChartChainContent("NIFTY", snap, "Upstox · 11:00", onRetry = { retries++ }, onClose = { closed++ }) { picks += it } }
        text("NIFTY options").assertIsDisplayed()
        text("Upstox · 11:00").assertIsDisplayed()
        text("Pricing the NIFTY chain").assertIsDisplayed()
        snap = Load.Failed("could not price the chain")
        idle()
        text("could not price the chain").assertIsDisplayed()
        text("Try again").performClick()
        assertEquals(1, retries)
        val chain = ResearchFixtures.chain
        snap = Load.Done(chain)
        idle()
        text("Expiry ${chain.expiry}", sub = true).assertIsDisplayed()
        // Tap the first call price in the table.
        val prices = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().withIndex().filter { (_, n) ->
            n.config.getOrNull(SemanticsActions.OnClick) != null && n.config.getOrNull(SemanticsProperties.Disabled) == null &&
                n.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text?.replace(",", "")?.toDoubleOrNull() != null
        }
        assertTrue("the chain has prices to tap", prices.isNotEmpty())
        compose.onAllNodes(hasClickAction())[prices.first().index].performClick()
        assertEquals(1, picks.size)
        assertEquals("NIFTY", picks.single().underlying)
        assertEquals(chain.expiry, picks.single().expiry)
        text("Close").performClick()
        assertEquals(1, closed)
    }

    @Test fun chainDialogWaitsForTheRightUnderlying() {
        set { ChartChainContent("BANKNIFTY", Load.Done(ResearchFixtures.chain), "", {}, {}, {}) }
        text("Pricing the BANKNIFTY chain").assertIsDisplayed()
        assertFalse(shows("Expiry "))
    }
}

/**
 * The chart tab's states, the option page and the chart dialogs on every device set-up: a screenshot each,
 * [com.optionslab.app.testing.LayoutLint], and a click-everything smoke test.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChartScreensLayoutTest(device: DeviceConfig) : ScreenTest(device) {
    @get:Rule val watchdog = com.optionslab.app.testing.ResearchWatchdog()
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun configs(): List<Array<Any>> = com.optionslab.app.testing.ResearchMatrix.six()

        /** Real layout bugs found by these tests, skipped with this text until fixed. */
        val BUGS = mapOf("*" to "TRIAGE: discovery run, findings to be pinned")
    }

    private val source = FakeChartSource(contracts = listOf(TEST_OPTION, BN_OPTION))

    @After fun noNetwork() { assertEquals(emptyList<String>(), NetworkGuard.blocked.toList()) }

    private fun pane(symbol: String) = @Composable {
        ChartPane(symbol, "NSE", true, 0, false, source, { _, _, _, _ -> }, { _, _ -> }, { _, _, _ -> })
    }

    @Test fun chartLoading() = checkScreen("chart-loading", BUGS, content = pane("BANKNIFTY"))

    @Test fun chartIndexBuyHint() {
        show(pane("BANKNIFTY"))
        compose.onNodeWithText("BUY").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Indices cannot be traded.", substring = true).fetchSemanticsNodes().isNotEmpty() }
        capture("chart-index-hint")
        lint("chart-index-hint", BUGS)
    }

    @Test fun chartBasic() {
        SecurePrefs.put("chart.basic", true)
        show(pane(TEST_OPTION.tradingSymbol))
        compose.waitUntil(5_000) { compose.onAllNodesWithText("C 130.00", substring = true).fetchSemanticsNodes().isNotEmpty() }
        capture("chart-basic")
        lint("chart-basic", BUGS)
        if (device.name == "phone-font1.3-light") smokeEveryAction(skip = setOf("BUY", "SELL", "ALERT", "OPT"))
    }

    @Test fun nativeChartError() = checkScreen("chart-basic-error", BUGS) {
        NativeChart("BANKNIFTY") { _, _ -> throw java.io.IOException("HTTP 500 from the candle feed") }
    }

    @Test fun alertDialog() {
        compose.mainClock.autoAdvance = false        // a dialog holding a text field never idles on a running clock
        show { ChartAlertDialog(TEST_OPTION.tradingSymbol, source, { _, _ -> }, {}) }
        val end = System.currentTimeMillis() + 5_000
        while (compose.onAllNodesWithText("Now 130.00").fetchSemanticsNodes().isEmpty()) {
            check(System.currentTimeMillis() < end) { "the price never arrived" }
            shadowOf(Looper.getMainLooper()).idle(); repeat(2) { compose.mainClock.advanceTimeByFrame() }; Thread.sleep(20)
        }
        capture("chart-alert")
        lint("chart-alert", BUGS)
    }

    @Test fun chainDialog() {
        checkScreen("chart-chain", BUGS) { ChartChainContent("NIFTY", Load.Done(ResearchFixtures.chain), "Upstox · 11:00", {}, {}, {}) }
    }

    @Test fun optionPage() {
        show { OptionChartContent(ChainPick("NIFTY", LocalDate.of(2026, 10, 1), 24800.0, Right.CE, 88.5, 0.45, 14.2, 75), { testBars(200) }, { null }, {}, {}) { _, _, _ -> } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Open interest").fetchSemanticsNodes().isNotEmpty() }
        capture("option-page")
        lint("option-page", BUGS)
    }

    @Test fun optionPageNoTrades() = checkScreen("option-page-empty", BUGS) {
        OptionChartContent(ChainPick("BANKNIFTY", LocalDate.of(2026, 10, 1), 52000.0, Right.PE, null, null, null, 30), { emptyList() }, { null }, {}, {}) { _, _, _ -> }
    }
}

/** The page's main state on all 24 set-ups (the other states run on six, in [ChartScreensLayoutTest]). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChartMainLayoutTest(device: DeviceConfig) : ScreenTest(device) {
    @get:Rule val watchdog = com.optionslab.app.testing.ResearchWatchdog()
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun configs(): List<Array<Any>> = DeviceConfig.matrix()
        val BUGS get() = ChartScreensLayoutTest.BUGS
    }

    @Test fun chartLoading() = checkScreen("chart-loading-all", BUGS) {
        ChartPane("BANKNIFTY", "NSE", true, 0, false, FakeChartSource(), { _, _, _, _ -> }, { _, _ -> }, { _, _, _ -> })
    }
}
