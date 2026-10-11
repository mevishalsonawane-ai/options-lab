package com.optionslab.app.ui.screens

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.DeviceConfig
import com.optionslab.app.testing.FakeChartSource
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.ScreenTest
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import com.optionslab.engine.mcx.McxContract
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/** Picker fixtures, far from expiry so no clock is needed: NIFTY options, MCX futures and an option, an expired MCX future. */
internal object PickerFixtures {
    val EXP: LocalDate = LocalDate.of(2099, 10, 1)
    val NIFTY_CE = Upstox.Contract("NIFTY", EXP, 24800.0, Right.CE, 75, "NSE_FO|P1", "NIFTY99OCT24800CE")
    val NIFTY_PE = Upstox.Contract("NIFTY", EXP, 24800.0, Right.PE, 75, "NSE_FO|P2", "NIFTY99OCT24800PE")
    val NIFTY_CE2 = Upstox.Contract("NIFTY", EXP, 24900.0, Right.CE, 75, "NSE_FO|P3", "NIFTY99OCT24900CE")
    val BN_CE = Upstox.Contract("BANKNIFTY", EXP, 52000.0, Right.CE, 30, "NSE_FO|P4", "BANKNIFTY99OCT52000CE")
    val CRUDE_FUT = McxContract(1001, 501, "CRUDEOIL99OCTFUT", "CRUDEOIL", LocalDate.of(2099, 10, 19), 0.0, null, 1.0, 100)
    val CRUDE_NEXT = McxContract(1005, 505, "CRUDEOIL99NOVFUT", "CRUDEOIL", LocalDate.of(2099, 11, 19), 0.0, null, 1.0, 100)
    val CRUDE_CE = McxContract(1002, 502, "CRUDEOIL99OCT8550CE", "CRUDEOIL", LocalDate.of(2099, 10, 15), 8550.0, Right.CE, 0.1, 100)
    val GOLDM_FUT = McxContract(1004, 504, "GOLDM99NOVFUT", "GOLDM", LocalDate.of(2099, 11, 5), 0.0, null, 1.0, 10)
    val CRUDE_OLD = McxContract(1003, 503, "CRUDEOIL20SEPFUT", "CRUDEOIL", LocalDate.of(2020, 9, 18), 0.0, null, 1.0, 100)
    val NFO = listOf(NIFTY_CE, NIFTY_PE, NIFTY_CE2, BN_CE)
    val MCX = listOf(CRUDE_FUT, CRUDE_NEXT, CRUDE_CE, GOLDM_FUT, CRUDE_OLD)
    fun source() = FakeChartSource(contracts = NFO, mcx = MCX)
}

/** [ChartSymbols]: what the picker lists, search, recent (pure; no Android). */
class ChartSymbolsTest {
    private val today = LocalDate.of(2026, 10, 11)
    private val f = PickerFixtures

    @Test fun indicesAreTheSixTheFeedServes() {
        val i = ChartSymbols.indices()
        assertEquals(listOf("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX", "INDIAVIX"), i.map { it.symbol })
        assertTrue("every index has candles", i.all { it.why == null })
        assertEquals("BSE", i.first { it.symbol == "SENSEX" }.exchange)
        assertEquals("INDIA VIX", i.last().title)
    }

    @Test fun commoditiesAreEveryMcxNameAsItsNearFutureAndTheMissingSayWhy() {
        val c = ChartSymbols.commodities(f.MCX, null, today)
        assertEquals(com.optionslab.engine.mcx.Mcx.NAMES, c.map { it.title })
        assertEquals("CRUDEOIL99OCTFUT", c.first { it.title == "CRUDEOIL" }.symbol)
        assertEquals("MCX", c.first { it.title == "CRUDEOIL" }.exchange)
        assertEquals("No ZINC future in today's MCX list", c.first { it.title == "ZINC" }.why)
        val loading = ChartSymbols.commodities(null, null, today)
        assertTrue(loading.all { it.why == "Reading MCX's contract list…" })
        assertTrue(ChartSymbols.commodities(null, "MCX's contract list could not be read", today).all { it.why == "MCX's contract list could not be read" })
    }

    @Test fun optionRootsExpiriesAndStrikes() {
        val roots = ChartSymbols.optionRoots(f.NFO, null, f.MCX, today)
        assertNull(roots.first { it.name == "NIFTY" }.why)
        assertEquals("No FINNIFTY options in the option list on the phone", roots.first { it.name == "FINNIFTY" }.why)
        assertTrue(roots.first { it.name == "CRUDEOIL" }.mcx)
        assertTrue("SENSEX options trade on BSE" in roots.first { it.name == "SENSEX" }.why!!)
        val nifty = roots.first { it.name == "NIFTY" }
        assertEquals(listOf(f.EXP), ChartSymbols.expiries(nifty, f.NFO, f.MCX, today))
        assertEquals(listOf(StrikeRow(24800.0, "NIFTY99OCT24800CE", "NIFTY99OCT24800PE"), StrikeRow(24900.0, "NIFTY99OCT24900CE", null)),
            ChartSymbols.strikes(nifty, f.EXP, f.NFO, f.MCX))
        val crude = roots.first { it.name == "CRUDEOIL" }
        assertEquals(listOf(LocalDate.of(2099, 10, 15)), ChartSymbols.expiries(crude, f.NFO, f.MCX, today))
        assertEquals("CRUDEOIL99OCTFUT", ChartSymbols.spotSymbol(crude, LocalDate.of(2099, 10, 15), f.MCX))
        assertEquals("NIFTY", ChartSymbols.spotSymbol(nifty, f.EXP, f.MCX))
        val rows = (1..40).map { StrikeRow(it * 100.0, "c$it", "p$it") }
        assertEquals((10..30).map { it * 100.0 }, ChartSymbols.near(rows, 2000.0).map { it.strike })
        assertEquals(21, ChartSymbols.near(rows, null).size)
    }

    @Test fun searchResolvesIndicesCommoditiesAndOptionsThroughTheLists() {
        val feed: (String) -> List<com.optionslab.app.data.ChartFeed.Match> = { t ->
            f.NFO.filter { it.tradingSymbol.contains(t.replace(" ", ""), true) }.map { com.optionslab.app.data.ChartFeed.Match(it.tradingSymbol, "NFO", "lot ${it.lotSize}") }
        }
        assertEquals("BANKNIFTY", ChartSymbols.resolve("bank nifty", f.NFO, f.MCX, today, feed).first().symbol)
        assertEquals("INDIAVIX", ChartSymbols.resolve("vix", f.NFO, f.MCX, today, feed).first().symbol)
        assertEquals(listOf("CRUDEOIL99OCTFUT", "CRUDEOIL99NOVFUT"), ChartSymbols.resolve("crude", f.NFO, f.MCX, today, feed).map { it.symbol })
        val opt = ChartSymbols.resolve("nifty99oct24800ce", f.NFO, f.MCX, today, feed).first { it.exchange == "NFO" }
        assertEquals("NIFTY99OCT24800CE", opt.symbol)
        val old = ChartSymbols.resolve("CRUDEOIL20SEPFUT", f.NFO, f.MCX, today, feed).single()
        assertEquals("Expired on 2020-09-18; the candle feed keeps no expired contracts", old.why)
        assertTrue(ChartSymbols.resolve("   ", f.NFO, f.MCX, today, feed).isEmpty())
        assertTrue(ChartSymbols.resolve("zzz", f.NFO, f.MCX, today, feed).isEmpty())
    }

    @Test fun recentKeepsSixNewestFirstOnce() {
        var r = emptyList<Pair<String, String>>()
        for (s in listOf("A", "B", "C", "D", "E", "F", "G", "B")) r = ChartSymbols.pushRecent(r, s to "NSE")
        assertEquals(listOf("B", "G", "F", "E", "D", "C"), r.map { it.first })
        assertEquals(r, ChartSymbols.decode(ChartSymbols.encode(r)))
        assertTrue(ChartSymbols.decode(null).isEmpty())
        assertEquals(listOf("X" to "MCX"), ChartSymbols.decode("X|MCX;broken;|NSE"))
    }

    @Test fun theOptChainFollowsEveryNseIndex() {
        assertEquals("FINNIFTY", nseChainOf("FINNIFTY"))
        assertEquals("MIDCPNIFTY", nseChainOf("MIDCPNIFTY99OCT12000CE"))
        assertEquals("BANKNIFTY", nseChainOf("BANKNIFTY99OCT52000CE"))
        assertEquals("NIFTY", nseChainOf("NIFTY99OCT24800CE"))
        assertNull(nseChainOf("SENSEX"))
        assertNull(nseChainOf("CRUDEOIL99OCTFUT"))
    }
}

/**
 * The Chart tab's native symbol picker on [ChartPane] (basic chart, so the candles asked for are the app's own and the
 * page's side needs no browser): sections, switching to an index, an MCX future and an option, search, greyed entries.
 */
@org.robolectric.annotation.Config(qualifiers = "w411dp-h2400dp")
@RunWith(AndroidJUnit4::class)
class ChartSymbolPickerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule(order = 100) val dump = com.optionslab.app.testing.DumpOnFailure(compose)
    @get:Rule val watchdog = com.optionslab.app.testing.ResearchWatchdog()

    private val source = PickerFixtures.source()
    private val chains = ArrayList<String>()

    @Before fun basic() { clearAlerts(); SecurePrefs.put("chart.basic", true) }
    @After fun noNetwork() { assertEquals("no host may be reached", emptyList<String>(), NetworkGuard.blocked.toList()) }

    private fun idle() { shadowOf(Looper.getMainLooper()).idle(); compose.waitForIdle() }
    private fun shows(t: String, sub: Boolean = true) = compose.onAllNodesWithText(t, substring = sub).fetchSemanticsNodes().isNotEmpty()
    private fun has(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun until(what: () -> Boolean) = compose.waitUntil(5_000) { idle(); what() }
    private fun tap(tag: String) { until { has(tag) }; compose.onNodeWithTag(tag).performScrollTo().performClick(); idle() }

    private fun webViews(): List<WebView> {
        val out = ArrayList<WebView>()
        fun walk(v: View) { if (v is WebView) out += v; if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i)) }
        walk(compose.activity.window.decorView)
        return out
    }
    private fun lastJs(): String? = shadowOf(webViews().single()).lastEvaluatedJavascript

    private fun pane() {
        compose.setContent {
            IraAlgoTheme("light") {
                ChartPane("BANKNIFTY", "NSE", true, 0, live = false, source = source,
                    orderSheet = { pick, buy, _, close -> Column { Text("sheet ${if (buy) "BUY" else "SELL"} ${pick.underlying} ${pick.strike} ${pick.right}"); TextButton(close) { Text("Close sheet") } } },
                    alertDialog = { _, close -> TextButton(close) { Text("Close alert") } },
                    chainDialog = { u, close, _ ->
                        androidx.compose.runtime.LaunchedEffect(u) { chains += u }
                        TextButton(close) { Text("Close chain") }
                    })
            }
        }
        idle()
    }

    /** The symbol in the toolbar ([charted]) is the picker's button. */
    private fun openPicker(charted: String = "BANKNIFTY") {
        compose.onNodeWithText(charted).performClick()
        until { shows("Chart a symbol") }
    }

    @Test fun thePickerListsTheSectionsAndGreyedEntriesSayWhy() {
        pane()
        openPicker()
        for (h in listOf("Recent", "Indices", "Commodities (MCX)", "Options")) assertTrue(h, shows(h, sub = false))
        for (s in listOf("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX", "INDIAVIX")) assertTrue(s, has("pick:index:$s"))
        until { has("pick:root:CRUDEOIL") }
        compose.onNodeWithTag("pick:mcx:CRUDEOIL").performScrollTo().assertIsEnabled()
        // A commodity with no future in the list: greyed, with why, and not tappable.
        compose.onNodeWithTag("pick:mcx:ZINC").performScrollTo().assertIsNotEnabled()
        assertTrue(shows("No ZINC future in today's MCX list", sub = false))
        compose.onNodeWithTag("pick:root:SENSEX").performScrollTo().assertIsNotEnabled()
        assertTrue(shows("SENSEX options trade on BSE; the app's option list is NSE's", sub = false))
        compose.onNodeWithTag("pick:root:FINNIFTY").performScrollTo().assertIsNotEnabled()
        // Close leaves the chart as it was.
        compose.onNodeWithText("Close").performClick()
        idle()
        assertFalse(shows("Chart a symbol"))
        compose.onNodeWithText("BANKNIFTY").assertIsDisplayed()
    }

    @Test fun pickingNiftySwitchesTheChartAndItsCandles() {
        pane()
        openPicker()
        tap("pick:index:NIFTY")
        until { !shows("Chart a symbol") }
        compose.onNodeWithText("NIFTY").assertIsDisplayed()
        until { source.asked.contains("NIFTY 5m") }
        assertEquals("window.__iraSetSymbol && window.__iraSetSymbol(\"NIFTY\", \"NSE\")", lastJs())
        // Kept for the next start, and first in Recent.
        until { ChartSymbols.lastAsk() == ("NIFTY" to "NSE") }
        openPicker("NIFTY")
        assertEquals(listOf("NIFTY", "BANKNIFTY"), ChartSymbols.decode(SecurePrefs.getString(ChartSymbols.RECENT_KEY)).map { it.first })
        assertTrue(has("pick:recent:BANKNIFTY"))
        tap("pick:recent:BANKNIFTY")
        until { source.asked.contains("BANKNIFTY 5m") && !shows("Chart a symbol") }
        compose.onNodeWithText("BANKNIFTY").assertIsDisplayed()
    }

    @Test fun pickingAnMcxCommodityChartsItsNearFutureAndItsChainIsUnderOpt() {
        pane()
        openPicker()
        tap("pick:mcx:CRUDEOIL")
        until { !shows("Chart a symbol") }
        compose.onNodeWithText("CRUDEOIL99OCTFUT").assertIsDisplayed()
        until { source.asked.contains("CRUDEOIL99OCTFUT 5m") }
        assertEquals("window.__iraSetSymbol && window.__iraSetSymbol(\"CRUDEOIL99OCTFUT\", \"MCX\")", lastJs())
        // Crude lists options: its chain is one tap away.
        until { shows("OPT", sub = false) }
        compose.onNodeWithText("OPT").performClick()
        until { chains.isNotEmpty() }
        assertEquals(listOf("CRUDEOIL"), chains)
        compose.onNodeWithText("Close chain").performClick()
        // An MCX future is ordered from the Commodities page: the chart says so instead of calling it an index.
        compose.onNodeWithText("BUY").performClick()
        until { shows("MCX futures are bought and sold from Options → Commodities") }
    }

    @Test fun pickingAnOptionGoesUnderlyingExpiryStrikeThenCeOrPe() {
        pane()
        openPicker()
        tap("pick:root:NIFTY")
        tap("pick:expiry:${PickerFixtures.EXP}")
        until { has("pick:CE:24800") && has("pick:PE:24800") }
        assertFalse("a strike with no put shows none", has("pick:PE:24900"))
        tap("pick:PE:24800")
        until { !shows("Chart a symbol") }
        compose.onNodeWithText("NIFTY99OCT24800PE").assertIsDisplayed()
        until { source.asked.contains("NIFTY99OCT24800PE 5m") }
        assertEquals("window.__iraSetSymbol && window.__iraSetSymbol(\"NIFTY99OCT24800PE\", \"NFO\")", lastJs())
        // Buy opens the app's order sheet for that option (paper or the review: the existing rules).
        compose.onNodeWithText("BUY").performClick()
        until { shows("sheet BUY NIFTY 24800.0 PE") }
    }

    @Test fun anMcxOptionCanBePickedToo() {
        pane()
        openPicker()
        tap("pick:root:CRUDEOIL")
        tap("pick:expiry:2099-10-15")
        tap("pick:CE:8550")
        until { !shows("Chart a symbol") }
        until { source.asked.contains("CRUDEOIL99OCT8550CE 5m") }
        assertEquals("window.__iraSetSymbol && window.__iraSetSymbol(\"CRUDEOIL99OCT8550CE\", \"MCX\")", lastJs())
    }

    @Test fun thePricedChainIsTheChartsOwnOptPopup() {
        pane()
        openPicker()
        tap("pick:root:BANKNIFTY")
        tap("pick:chain:BANKNIFTY")
        until { chains.isNotEmpty() }
        assertEquals(listOf("BANKNIFTY"), chains)
        assertFalse(shows("Chart a symbol"))
    }

    @Test fun searchResolvesThroughTheInstrumentLists() {
        pane()
        openPicker()
        fun type(t: String) {
            compose.onNodeWithTag("pick:search").performTextClearance()
            compose.onNodeWithTag("pick:search").performTextInput(t)
            idle()
        }
        type("bank nifty")
        until { has("pick:result:BANKNIFTY") }
        type("CRUDEOIL20SEPFUT")
        until { has("pick:result:CRUDEOIL20SEPFUT") }
        compose.onNodeWithTag("pick:result:CRUDEOIL20SEPFUT").assertIsNotEnabled()
        assertTrue(shows("Expired on 2020-09-18; the candle feed keeps no expired contracts", sub = false))
        type("qqqq")
        until { shows("No index, MCX contract or option matches") }
        type("goldm")
        tap("pick:result:GOLDM99NOVFUT")
        until { !shows("Chart a symbol") }
        until { source.asked.contains("GOLDM99NOVFUT 5m") }
        compose.onNodeWithText("GOLDM99NOVFUT").assertIsDisplayed()
    }

    @Test fun aHiddenChartClosesItsPicker() {
        var visible by androidx.compose.runtime.mutableStateOf(true)
        compose.setContent {
            IraAlgoTheme("light") {
                ChartPane("BANKNIFTY", "NSE", visible, 0, live = false, source = source,
                    orderSheet = { _, _, _, _ -> }, alertDialog = { _, _ -> }, chainDialog = { _, _, _ -> })
            }
        }
        idle()
        openPicker()
        visible = false
        idle()
        assertFalse(shows("Chart a symbol"))
    }
}

/** The toolbar's symbol button and the picker on the six set-ups that span the matrix (landscape and font 2.0 included). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChartSymbolPickerLayoutTest(device: DeviceConfig) : ScreenTest(device) {
    @get:Rule val watchdog = com.optionslab.app.testing.ResearchWatchdog()
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun configs(): List<Array<Any>> = com.optionslab.app.testing.ResearchMatrix.six()
        val BUGS get() = ChartScreensLayoutTest.BUGS
    }

    @After fun noNetwork() { assertEquals(emptyList<String>(), NetworkGuard.blocked.toList()) }

    private val source = PickerFixtures.source()

    private fun waitFor(what: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!what()) {
            check(System.currentTimeMillis() < end) { "never shown on ${device.name}" }
            shadowOf(Looper.getMainLooper()).idle(); compose.waitForIdle(); Thread.sleep(20)
        }
    }

    /** An MCX future (its OPT chip on): the symbol button and every chip, nothing overlapping or cut. */
    @Test fun toolbarWithAnMcxFuture() = checkScreen("chart-symbol-toolbar", BUGS) {
        ChartPane("CRUDEOIL99OCTFUT", "MCX", true, 0, false, source, { _, _, _, _ -> }, { _, _ -> }, { _, _, _ -> })
    }

    @Test fun pickerSheet() {
        show { ChartSymbolSheet("BANKNIFTY", source, listOf("NIFTY" to "NSE", "CRUDEOIL99OCTFUT" to "MCX"), { _, _ -> }, { }, { }) }
        waitFor { compose.onAllNodesWithTag("pick:root:CRUDEOIL").fetchSemanticsNodes().isNotEmpty() }
        capture("chart-symbol-sheet")
        lint("chart-symbol-sheet", BUGS)
    }

    @Test fun pickerSheetStrikes() {
        show { ChartSymbolSheet("NIFTY99OCT24800CE", source, emptyList(), { _, _ -> }, { }, { }) }
        waitFor { compose.onAllNodesWithTag("pick:root:NIFTY").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("pick:root:NIFTY").performScrollTo().performClick()
        waitFor { compose.onAllNodesWithTag("pick:expiry:${PickerFixtures.EXP}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("pick:expiry:${PickerFixtures.EXP}").performScrollTo().performClick()
        waitFor { compose.onAllNodesWithTag("pick:CE:24800").fetchSemanticsNodes().isNotEmpty() }
        capture("chart-symbol-strikes")
        lint("chart-symbol-strikes", BUGS)
    }

    @Test fun pickerSearchResults() {
        show { ChartSymbolSheet("BANKNIFTY", source, emptyList(), { _, _ -> }, null, { }) }
        compose.onNodeWithTag("pick:search").performTextInput("crude")
        waitFor { compose.onAllNodesWithTag("pick:result:CRUDEOIL99OCTFUT").fetchSemanticsNodes().isNotEmpty() }
        capture("chart-symbol-search")
        lint("chart-symbol-search", BUGS)
    }
}
