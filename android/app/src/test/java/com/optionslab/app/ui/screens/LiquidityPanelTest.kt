package com.optionslab.app.ui.screens

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.DeviceConfig
import com.optionslab.app.testing.FakeChartSource
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.ScreenTest
import com.optionslab.app.testing.testBars
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.engine.Upstox
import com.optionslab.engine.orb.Bar
import com.optionslab.engine.orb.LiquidityOverlay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Six sessions of BANKNIFTY-like 5-minute candles (the series the engine's liquidity tests use), and the arm's trades on the last. */
internal object LiquidityFixtures {
    val days: List<LocalDate> = listOf(28, 29, 30).map { LocalDate.of(2026, 9, it) } + listOf(1, 2, 5).map { LocalDate.of(2026, 10, it) }
    val last: LocalDate get() = days.last()
    /** After the close of the last session: every bar has closed. */
    val now: LocalDateTime get() = last.atTime(15, 40)

    val bars: List<Bar> by lazy {
        val n = days.size * 75
        val c = (0 until n).map { 1000 + 40 * sin(it / 9.0) + 25 * sin(it / 3.7) + 10 * sin(it / 1.3) }
        val o = (0 until n).map { if (it == 0) c[0] else c[it - 1] }
        val h = (0 until n).map { maxOf(o[it], c[it]) + 3 + 8 * abs(sin(it / 2.3)) }
        val l = (0 until n).map { minOf(o[it], c[it]) - 3 - 8 * abs(cos(it / 2.9)) }
        (0 until n).map { Bar(days[it / 75].atTime(9, 15).plusMinutes(5L * (it % 75)), o[it], h[it], l[it], c[it]) }
    }

    /** The same candles as the chart feed gives them. */
    val feed: List<Upstox.Bar> by lazy {
        bars.map { Upstox.Bar(it.start.atZone(com.optionslab.engine.IST).toEpochSecond(), it.open, it.high, it.low, it.close, 0L, 0L) }
    }

    /** A 15-minute trade closed at the next liquidity level, and a 5-minute one still open. */
    val closed = LiquidityOverlay.Trade("liquidity15", 15, 1, last.atTime(11, 0), last.atTime(11, 15), 120.5, 30, "52000 CE",
        exit = 140.0, exitTime = last.atTime(11, 47), why = "next_liquidity", level = 1050.0, target = 1077.25, pnl = 520.0,
        near = false, volSkip = true, strong = null)
    val open = LiquidityOverlay.Trade("liquidity5", 5, -1, last.atTime(10, 0), last.atTime(10, 5), 98.0, 30, "52200 PE",
        level = 1012.0, target = 990.5, near = true, volSkip = false, strong = true)

    class Source(var trades: List<LiquidityOverlay.Trade> = listOf(closed, open)) : LiquiditySource {
        val asked = java.util.concurrent.CopyOnWriteArrayList<String>()
        override suspend fun trades(underlying: String): List<LiquidityOverlay.Trade> { asked += underlying; return if (underlying == "BANKNIFTY") trades else emptyList() }
        override fun now(): LocalDateTime = LiquidityFixtures.now
    }

    fun chartSource() = FakeChartSource(bars = { s, iv -> if (s == "BANKNIFTY" && iv == "5m") feed else testBars(60) })
}

/** The chart's Liquidity 15+5 layer: the toggle, the levels and markers, a marker's card, and the candles it reuses. */
@org.robolectric.annotation.Config(qualifiers = "w411dp-h2400dp")
@RunWith(AndroidJUnit4::class)
class LiquidityPanelTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule(order = 100) val dump = com.optionslab.app.testing.DumpOnFailure(compose)
    @get:Rule val watchdog = com.optionslab.app.testing.ResearchWatchdog()

    @Before fun fresh() { SecurePrefs.put("chart.liq", true) }
    @After fun noNetwork() { assertEquals("no host may be reached", emptyList<String>(), NetworkGuard.blocked.toList()) }

    private fun shows(t: String, sub: Boolean = false) = compose.onAllNodesWithText(t, substring = sub).fetchSemanticsNodes().isNotEmpty()
    private fun idle() { shadowOf(Looper.getMainLooper()).idle(); compose.waitForIdle() }
    private fun until(what: () -> Boolean) = compose.waitUntil(5_000) { idle(); what() }

    private fun pane(symbol: String, chart: FakeChartSource, liq: LiquiditySource?) {
        compose.setContent {
            IraAlgoTheme("light") {
                ChartPane(symbol, "NSE", true, 0, false, chart, { _, _, _, _ -> }, { _, _ -> }, { _, _, _ -> }, liquidity = liq)
            }
        }
        idle()
    }

    @Test fun onByDefaultOnBankNiftyAndTheToggleHidesAndShowsIt() {
        val chart = LiquidityFixtures.chartSource()
        val liq = LiquidityFixtures.Source()
        pane("BANKNIFTY", chart, liq)
        until { shows("▲ L15 11:00") }
        assertTrue(shows("✓ Liquidity levels"))
        assertTrue(shows("Liquidity 15+5 · BANKNIFTY"))
        // The 15-minute chart first: the closed trade's entry and exit, the open one's entry, the skipped break.
        listOf("■ 11:45 next liquidity", "▼ L5 10:00", "△ skipped 10:15").forEach { assertTrue(it, shows(it)) }
        assertTrue("the candles were read once, as 5-minute bars", chart.asked.count { it == "BANKNIFTY 5m" } >= 1)
        assertTrue(liq.asked.contains("BANKNIFTY"))

        compose.onNodeWithText("✓ Liquidity levels").performClick()
        until { !shows("Liquidity 15+5 · BANKNIFTY") }
        assertTrue(shows("Liquidity levels"))
        assertFalse(shows("▲ L15 11:00"))
        compose.onNodeWithText("Liquidity levels").performClick()
        until { shows("▲ L15 11:00") }
        assertTrue(shows("✓ Liquidity levels"))

        // The 5-minute book's chart: the same trades on the 5-minute bars (the 15-minute 11:00 bar closes with 11:10).
        compose.onNodeWithText("5m").performClick()
        until { shows("▲ L15 11:10") }
        assertTrue(shows("▼ L5 10:00"))
        assertTrue(shows("■ 11:45 next liquidity"))
    }

    @Test fun theOwnersChoiceIsRemembered() {
        SecurePrefs.put("chart.liq", false)
        pane("BANKNIFTY", LiquidityFixtures.chartSource(), LiquidityFixtures.Source())
        assertTrue(shows("Liquidity levels"))
        assertFalse(shows("Liquidity 15+5 · BANKNIFTY"))
    }

    @Test fun onlyOnTheIndicesTheArmTrades() {
        pane("NIFTY", LiquidityFixtures.chartSource(), LiquidityFixtures.Source())
        assertFalse(shows("Liquidity levels", sub = true))
        assertFalse(shows("Liquidity 15+5", sub = true))
    }

    @Test fun noLayerWithoutASource() {
        pane("BANKNIFTY", LiquidityFixtures.chartSource(), null)
        assertFalse(shows("Liquidity levels", sub = true))
    }

    @Test fun finNiftyShowsItsThirtyAndFiveMinuteCharts() {
        pane("FINNIFTY", LiquidityFixtures.chartSource(), LiquidityFixtures.Source())
        assertTrue(shows("Liquidity 15+5 · FINNIFTY"))
        assertTrue(shows("30m")); assertTrue(shows("5m")); assertFalse(shows("15m"))
    }

    private fun chart(model: LiquidityOverlay.Model) = compose.setContent {
        IraAlgoTheme("light") {
            var tf by remember { mutableIntStateOf(15) }
            LiquidityChart("BANKNIFTY", listOf(15, 5), tf, { tf = it }, model, null)
        }
    }

    private fun model(tf: Int = 15) = LiquidityOverlay.build(LiquidityFixtures.bars, tf, "BANKNIFTY", LiquidityFixtures.now,
        listOf(LiquidityFixtures.closed, LiquidityFixtures.open))

    @Test fun aMarkerTapOpensItsCard() {
        chart(model())
        idle()
        compose.onNodeWithText("▲ L15 11:00").performScrollTo().performClick()
        until { shows("Liquidity trade") }
        listOf("Liquidity 15m · L15", "52000 CE · qty 30", "Entry ₹120.50 at 11:15", "Exit ₹140.00 at 11:47 · next liquidity",
            "P&L +₹520.00", "Shadow: room: ok · vol skip (c): would skip").forEach { assertTrue(it, shows(it)) }
        compose.onNodeWithText("Close").performClick()
        until { !shows("Liquidity trade") }
        // The open trade: its target, and every shadow flag.
        compose.onNodeWithText("▼ L5 10:00").performScrollTo().performClick()
        until { shows("Open · target 990.50") }
        assertTrue(shows("Shadow: room: too little · vol skip (c): would take · strong close (f): yes"))
        compose.onNodeWithText("Close").performClick()
        until { !shows("Liquidity trade") }
        // A break the room filter skipped.
        compose.onNodeWithText("△ skipped 10:15").performScrollTo().performClick()
        until { shows("Skipped: too little room") }
        assertTrue(shows("Next level", sub = true))
        compose.onNodeWithText("Close").performClick()
        until { !shows("Skipped break") }
    }

    @Test fun aTapOnTheChartAtAMarkerOpensItsCard() {
        val m = model()
        val entry = m.markers.first { it.kind == LiquidityOverlay.MarkerKind.ENTRY && it.trade == LiquidityFixtures.closed }
        chart(m)
        idle()
        compose.onNodeWithContentDescription("Liquidity levels chart").performTouchInput {
            // As the canvas lays the bars out: at least 3 dp each, beside a 52 dp label column, the newest at the right.
            val plotW = max(1f, width - 52.dp.toPx())
            val w = max(3.dp.toPx(), plotW / m.bars.size)
            val start = max(0, m.bars.size - max(1, (plotW / w).toInt()))
            click(Offset((entry.bar - start + 0.5f) * w, height / 2f))
        }
        until { shows("Entry ₹120.50 at 11:15") }
    }

    @Test fun loadingAndEmptyStates() {
        val empty = LiquidityOverlay.build(emptyList(), 30, "FINNIFTY", LiquidityFixtures.now, emptyList())
        var step by mutableIntStateOf(0)
        compose.setContent {
            IraAlgoTheme("dark") {
                when (step) {
                    0 -> LiquidityChart("FINNIFTY", listOf(30, 5), 30, {}, null, null)
                    1 -> LiquidityChart("FINNIFTY", listOf(30, 5), 30, {}, null, "Could not load the FINNIFTY candles: HTTP 500")
                    else -> LiquidityChart("FINNIFTY", listOf(30, 5), 30, {}, empty, null)
                }
            }
        }
        idle()
        assertTrue(shows("Loading the liquidity levels…"))
        step = 1; idle()
        assertTrue(shows("Could not load the FINNIFTY candles: HTTP 500"))
        step = 2; idle()
        assertTrue(shows("No closed 30-minute bars yet."))
    }

    @Test fun theCacheReusesTheChartsCandlesUntilABarIsMissing() {
        val c = LiquidityCache()
        val day = LiquidityFixtures.last
        val feed = LiquidityFixtures.feed
        fun at(h: Int, m: Int) = day.atTime(h, m).atZone(com.optionslab.engine.IST).toEpochSecond()
        val upTo1100 = feed.filter { it.epochSecond < at(11, 0) }
        // Nothing until the first full read; the page's own batches alone are not the arm's ten days.
        c.offer("BANKNIFTY", upTo1100.takeLast(10), day.atTime(11, 3))
        assertNull(c.fresh("BANKNIFTY", day.atTime(11, 3)))
        c.offer("banknifty", upTo1100, day.atTime(11, 3), history = true)
        // At 11:03 the 10:55 bar is the last closed one, and it is held; at 11:06 the 11:00 bar is missing.
        assertEquals(upTo1100, c.fresh("BANKNIFTY", day.atTime(11, 3)))
        assertNull(c.fresh("BANKNIFTY", day.atTime(11, 6)))
        // The chart page fetches its 5-minute candles: merged in, so no read is needed.
        c.offer("BANKNIFTY", feed.filter { it.epochSecond == at(11, 0) }, day.atTime(11, 5, 10))
        assertEquals(upTo1100.size + 1, c.fresh("BANKNIFTY", day.atTime(11, 6))?.size)
        // Outside the session the candles held serve.
        assertEquals(c.fresh("BANKNIFTY", day.atTime(11, 6)), c.fresh("BANKNIFTY", day.atTime(9, 0)))
        assertNotNull(c.fresh("BANKNIFTY", day.atTime(9, 0)))
        // Another symbol starts over.
        assertNull(c.fresh("FINNIFTY", day.atTime(11, 6)))
        c.offer("FINNIFTY", emptyList(), day.atTime(11, 6))
        assertNull(c.fresh("BANKNIFTY", day.atTime(11, 6)))
        assertNull(LiquidityCache.lastClosedStart(day.atTime(9, 19)))
        assertEquals(day.atTime(9, 15), LiquidityCache.lastClosedStart(day.atTime(9, 20)))
        assertEquals(day.atTime(15, 25), LiquidityCache.lastClosedStart(day.atTime(15, 30)))
        // The feed's candles as the arm's bars: IST, session minutes only.
        val pre = Upstox.Bar(day.atTime(9, 10).atZone(com.optionslab.engine.IST).toEpochSecond(), 1.0, 1.0, 1.0, 1.0, 0, 0)
        assertEquals(LiquidityFixtures.bars.takeLast(3), LiquidityCache.toBars(listOf(pre) + feed.takeLast(3)))
    }

    @Test fun aFormingBarIsNeverHeldAsClosed() {
        val c = LiquidityCache()
        val day = LiquidityFixtures.last
        val feed = LiquidityFixtures.feed
        fun at(h: Int, m: Int) = day.atTime(h, m).atZone(com.optionslab.engine.IST).toEpochSecond()
        // Read at 11:03 with the 11:00 bar still forming: that bar is left out.
        val withForming = feed.filter { it.epochSecond <= at(11, 0) }
        c.offer("BANKNIFTY", withForming, day.atTime(11, 3), history = true)
        assertEquals(at(10, 55), c.fresh("BANKNIFTY", day.atTime(11, 3))!!.last().epochSecond)
        // At 11:06 the 11:00 bar has closed, but the one held was not: read again (not the forming copy).
        assertNull(c.fresh("BANKNIFTY", day.atTime(11, 6)))
        // The page's batch at 11:05:30 carries the closed 11:00 bar (and the forming 11:05 one, left out).
        c.offer("BANKNIFTY", feed.filter { it.epochSecond in at(11, 0)..at(11, 5) }, day.atTime(11, 5, 30))
        val held = c.fresh("BANKNIFTY", day.atTime(11, 6))!!
        assertEquals(at(11, 0), held.last().epochSecond)
        // After the close: the 15:25 bar must be held, received after 15:30.
        c.offer("BANKNIFTY", feed.filter { it.epochSecond in at(11, 5)..at(15, 25) }, day.atTime(15, 29))
        assertNull(c.fresh("BANKNIFTY", day.atTime(15, 45)))
        c.offer("BANKNIFTY", feed.filter { it.epochSecond == at(15, 25) }, day.atTime(15, 30, 5))
        assertEquals(at(15, 25), c.fresh("BANKNIFTY", day.atTime(15, 45))!!.last().epochSecond)
        // A day with no session (the next day is a holiday here): what is held serves.
        assertNotNull(c.fresh("BANKNIFTY", day.plusDays(1).atTime(16, 0)))
        // The layer reads closed bars only.
        val bars = LiquidityCache.toBars(withForming)
        assertEquals(bars.dropLast(1), LiquidityCache.closed(bars, day.atTime(11, 3)))
        assertEquals(bars, LiquidityCache.closed(bars, day.atTime(11, 5)))
    }

    @Test fun theLayerSaysWhenItsCandlesAreBehind() {
        val day = LiquidityFixtures.last
        val bars = LiquidityFixtures.bars.filter { !it.start.plusMinutes(5).isAfter(day.atTime(11, 0)) }
        // At 11:03 (the 10:55 bar held) or 11:06 (one bar behind, a read in flight): fine.
        assertNull(behindBy(bars, day.atTime(11, 3)))
        assertNull(behindBy(bars, day.atTime(11, 6)))
        // Two bars behind: said.
        assertEquals("Candles behind: the last closed 11:00, the 11:10 close is missing", behindBy(bars, day.atTime(11, 12)))
        // Kept as bar times: the same at 11:12 and 11:14 (no redraw each poll); words only when shown.
        assertEquals(behindAt(bars, day.atTime(11, 12)), behindAt(bars, day.atTime(11, 14)))
        // Outside a session, or the day's first bar, nothing is said.
        assertNull(behindBy(bars, day.atTime(16, 0)))
        assertNull(behindBy(bars, day.plusDays(1).atTime(9, 21)))
        assertEquals("No candles yet", behindBy(emptyList(), day.atTime(11, 3)))
        // On the layer: the chip, and the time of the last bar drawn.
        compose.setContent {
            IraAlgoTheme("light") {
                LiquidityChart("BANKNIFTY", listOf(15, 5), 15, {}, model(), null, behind = "Candles behind: the last closed 11:00, the 11:10 close is missing")
            }
        }
        idle()
        assertTrue(shows("Candles behind: the last closed 11:00, the 11:10 close is missing"))
        assertTrue(shows("as of 15:30"))
    }

    private fun webViews(): List<android.webkit.WebView> {
        val out = ArrayList<android.webkit.WebView>()
        fun walk(v: android.view.View) {
            if (v is android.webkit.WebView) out += v
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(compose.activity.window.decorView)
        return out
    }

    @Test fun theChartIsNotRebuiltWhenTheKeyboardOpensOrTheLayerIsToggled() {
        var height by mutableStateOf(2400.dp)
        compose.setContent {
            IraAlgoTheme("light") {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.height(height)) {
                    ChartPane("BANKNIFTY", "NSE", true, 0, false, LiquidityFixtures.chartSource(), { _, _, _, _ -> }, { _, _ -> }, { _, _, _ -> },
                        liquidity = LiquidityFixtures.Source())
                }
            }
        }
        idle()
        until { shows("▲ L15 11:00") }
        val web = webViews().single()
        // The keyboard takes most of the height: the space left is wider than high (it used to switch to a Row).
        height = 300.dp; idle()
        assertTrue(shows("Liquidity 15+5 · BANKNIFTY"))
        assertSame(web, webViews().single())
        height = 2400.dp; idle()
        assertSame(web, webViews().single())
        // The layer off and on again: the same chart.
        compose.onNodeWithText("✓ Liquidity levels").performClick()
        until { !shows("Liquidity 15+5 · BANKNIFTY") }
        assertSame(web, webViews().single())
        compose.onNodeWithText("Liquidity levels").performClick()
        until { shows("Liquidity 15+5 · BANKNIFTY") }
        assertSame(web, webViews().single())
    }

    @Test fun theArmsPositionsBecomeTheChartsTrades() {
        val p = com.optionslab.app.data.OrbArms.Position("liquidity15", "BANKNIFTY26OCT52000CE", "CE", 30, 120.5, LiquidityFixtures.last.atTime(11, 15),
            LiquidityFixtures.last.atTime(11, 0), null, null, 102.4, exit = 140.0, exitTime = LiquidityFixtures.last.atTime(11, 47), why = "next_liquidity",
            charges = 65.0, level = 1050.0, target = 1077.25, near = false, volSkip = true)
        val t = ArmLiquiditySource.tradeOf(p, "BANKNIFTY")
        assertNotNull(t)
        t!!
        assertEquals(15, t.minutes); assertEquals(1, t.side); assertEquals(30, t.qty)
        assertEquals(19.5 * 30 - 65.0, t.pnl!!, 1e-9)
        assertEquals(1050.0, t.level); assertEquals(false, t.near); assertEquals(true, t.volSkip); assertNull(t.strong)
        assertNull(ArmLiquiditySource.tradeOf(p, "FINNIFTY"))
        assertNull(ArmLiquiditySource.tradeOf(p.copy(arm = "orb"), "BANKNIFTY"))
        assertEquals(-1, ArmLiquiditySource.tradeOf(p.copy(arm = "liquidity5_fin", right = "PE"), "FINNIFTY")?.side)
    }
}

/** The layer on the six set-ups that span the device matrix (landscape and font 2.0 among them): a screenshot each and [com.optionslab.app.testing.LayoutLint]. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LiquidityPanelLayoutTest(device: DeviceConfig) : ScreenTest(device) {
    @get:Rule val watchdog = com.optionslab.app.testing.ResearchWatchdog()
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun configs(): List<Array<Any>> = com.optionslab.app.testing.ResearchMatrix.six()
        val BUGS get() = ChartScreensLayoutTest.BUGS
    }

    @Before fun on() { SecurePrefs.put("chart.liq", true) }
    @After fun noNetwork() { assertEquals(emptyList<String>(), NetworkGuard.blocked.toList()) }

    private fun pane() = @Composable {
        ChartPane("BANKNIFTY", "NSE", true, 0, false, LiquidityFixtures.chartSource(), { _, _, _, _ -> }, { _, _ -> }, { _, _, _ -> },
            liquidity = LiquidityFixtures.Source())
    }

    private fun waitFor(text: String) {
        val end = System.currentTimeMillis() + 5_000
        while (compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()) {
            check(System.currentTimeMillis() < end) { "'$text' never showed" }
            shadowOf(Looper.getMainLooper()).idle(); compose.waitForIdle(); Thread.sleep(20)
        }
    }

    @Test fun chartWithLevels() {
        show(pane())
        waitFor("▲ L15 11:00")
        capture("chart-liquidity")
        lint("chart-liquidity", BUGS)
    }

    @Test fun markerCard() {
        show(pane())
        waitFor("▲ L15 11:00")
        compose.onNodeWithText("▲ L15 11:00").performScrollTo().performClick()
        waitFor("Entry ₹120.50 at 11:15")
        capture("chart-liquidity-card")
        lint("chart-liquidity-card", BUGS)
    }

    @Test fun levelsOff() {
        SecurePrefs.put("chart.liq", false)
        checkScreen("chart-liquidity-off", BUGS, content = pane())
    }
}
