package com.optionslab.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.testing.DeviceConfig
import com.optionslab.app.testing.ScreenTest
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.app.ui.theme.Light
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The drawn charts (PriceChart, LinePlot, ButterflyBars, SignedBars). They have no semantics, so the tests
 * read the pixels: which colours were inked says what was drawn (a rising day in green, a falling one in
 * red, calls up and puts down, a gap where the data has none).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChartsTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val watchdog = com.optionslab.app.testing.ResearchWatchdog()

    private val up = Light.verdigris
    private val down = Light.oxblood
    private val a = Color(0xFF1E40AF)      // test line colours, used nowhere in the palette
    private val b = Color(0xFFD97706)

    /** Draw [content] alone on a white card and return every colour inked. */
    private var shown by mutableStateOf<Pair<Int, @Composable () -> Unit>>(0 to {})

    private fun inked(content: @Composable () -> Unit): Set<Int> {
        // setContent once per test; each chart after that replaces the last one (a fresh composition each time).
        if (shown.first == 0) compose.setContent {
            IraAlgoTheme("light") {
                Box(Modifier.testTag("chart").width(360.dp).background(Color.White).padding(4.dp)) { key(shown.first) { shown.second() } }
            }
        }
        shown = (shown.first + 1) to content
        compose.waitForIdle()
        val px = compose.onNodeWithTag("chart").captureToImage().toPixelMap()
        val out = HashSet<Int>()
        for (y in 0 until px.height) for (x in 0 until px.width) out += px[x, y].toArgb()
        return out
    }
    private operator fun Set<Int>.contains(c: Color) = contains(c.toArgb())

    // ---- PriceChart ------------------------------------------------------------------------------------------

    @Test fun aRisingDayIsInkedGreen() {
        val c = inked { PriceChart(listOf(100.0, 101.0, 103.0, 102.5, 104.0), 99.0, 375, listOf(0 to "09:15", 374 to "15:30")) }
        assertTrue(up in c); assertFalse(down in c)
    }

    @Test fun aFallingDayIsInkedRed() {
        val c = inked { PriceChart(listOf(104.0, 103.0, 101.0, 100.5), null, 375, emptyList()) }
        assertTrue(down in c); assertFalse(up in c)
    }

    @Test fun theReferenceDecidesTheColourNotTheFirstPoint() {
        // Up on the day's first print, but still below the previous close: red.
        val c = inked { PriceChart(listOf(100.0, 101.0), 105.0, 375, emptyList()) }
        assertTrue(down in c); assertFalse(up in c)
    }

    @Test fun fewerThanTwoPointsDrawNoLine() {
        for (values in listOf(emptyList(), listOf(100.0))) {
            val c = inked { PriceChart(values, 99.0, 375, listOf(0 to "09:15")) }
            assertFalse(up in c); assertFalse(down in c)
        }
    }

    @Test fun aFlatLineAndOddLabelsStillDraw() {
        // Equal prices (no range), a label beyond the last slot, a one-slot axis: drawn, no crash.
        val c = inked { PriceChart(List(10) { 0.05 }, 0.05, 10, listOf(-5 to "early", 999 to "late")) }
        assertTrue(up in c)
        inked { PriceChart(listOf(1.0, 2.0), null, 1, listOf(0 to "only")) }
        val big = inked { PriceChart(listOf(52_000.0, 52_500.0, 51_200.0, 53_900.0), 51_000.0, 4, listOf(0 to "1 Sep", 3 to "26 Sep")) }
        assertTrue(up in big)
    }

    // ---- LinePlot ------------------------------------------------------------------------------------------------

    @Test fun linePlotInksEachLine() {
        val xs = (0..20).map { it.toDouble() }
        val c = inked {
            LinePlot(xs, listOf(PlotLine(xs.map { it * 2 }, a, width = 6f), PlotLine(xs.map { 40 - it }, b, dashed = true, width = 6f)), markX = 10.0)
        }
        assertTrue(a in c); assertTrue(b in c)
    }

    @Test fun linePlotWithNoDataDrawsNothing() {
        val xs = (0..5).map { it.toDouble() }
        val none = inked { LinePlot(xs, listOf(PlotLine(xs.map { null }, a, width = 6f), PlotLine(xs.map { Double.NaN }, b, width = 6f))) }
        assertFalse(a in none); assertFalse(b in none)
        val short = inked { LinePlot(listOf(1.0), listOf(PlotLine(listOf(1.0), a, width = 6f))) }
        assertFalse(a in short)
    }

    @Test fun linePlotGapsAndSignFill() {
        val xs = (0..30).map { it.toDouble() }
        // A payoff that crosses zero, with a gap in the middle: drawn, the gap left blank, no crash.
        val ys = xs.map { x -> if (x in 12.0..16.0) null else (x - 15) * 100 }
        val c = inked { LinePlot(xs, listOf(PlotLine(ys, a, width = 6f), PlotLine(ys.map { it?.times(0.5) }, b, dashed = true, width = 6f)), fillSign = true, markX = 15.0) }
        assertTrue(a in c); assertTrue(b in c)
    }

    // ---- ButterflyBars / SignedBars -----------------------------------------------------------------------------

    @Test fun butterflyBarsPutCallsUpAndPutsDown() {
        val xs = listOf(24_000.0, 24_100.0, 24_200.0, 24_300.0)
        val c = inked { ButterflyBars(xs, listOf(10.0, 40.0, 90.0, 20.0), listOf(80.0, 30.0, 10.0, 0.0), up, down, markX = 24_150.0) }
        assertTrue(up in c); assertTrue(down in c)
        val zero = inked { ButterflyBars(xs, List(4) { 0.0 }, List(4) { 0.0 }, up, down) }
        assertFalse(up in zero); assertFalse(down in zero)
        val empty = inked { ButterflyBars(emptyList(), emptyList(), emptyList(), up, down) }
        assertFalse(up in empty)
    }

    @Test fun signedBarsAreGreenAboveAndRedBelow() {
        val xs = listOf(1.0, 2.0, 3.0)
        val both = inked { SignedBars(xs, listOf(5.0, -3.0, 1.0), markX = 2.0) }
        assertTrue(up in both); assertTrue(down in both)
        val pos = inked { SignedBars(xs, listOf(5.0, 3.0, 1.0)) }
        assertTrue(up in pos); assertFalse(down in pos)
        val nothing = inked { SignedBars(emptyList(), emptyList()) }
        assertFalse(up in nothing); assertFalse(down in nothing)
    }
}

/** Every chart component on every device set-up (size x font scale x theme): screenshot and layout lint. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChartsLayoutTest(device: DeviceConfig) : ScreenTest(device) {
    @get:Rule val watchdog = com.optionslab.app.testing.ResearchWatchdog()
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun configs(): List<Array<Any>> = DeviceConfig.matrix()
    }

    @Test fun gallery() = checkScreen("charts-gallery") {
        val xs = (0..30).map { it.toDouble() }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(14.dp)) {
            Text("PriceChart")
            PriceChart(List(200) { 52_000.0 + 40 * kotlin.math.sin(it / 9.0) + it }, 52_010.0, 375, listOf(0 to "09:15", 105 to "11:00", 225 to "13:00", 374 to "15:30"))
            Text("LinePlot")
            LinePlot(xs, listOf(PlotLine(xs.map { (it - 15) * 100 }, Light.ink), PlotLine(xs.map { (it - 15) * 60 }, Light.brass, dashed = true)), markX = 15.0, fillSign = true)
            Text("ButterflyBars")
            ButterflyBars(xs, xs.map { it * 3 }, xs.map { 90 - it * 3 }, Light.verdigris, Light.oxblood, markX = 15.0)
            Text("SignedBars")
            SignedBars(xs, xs.map { it - 15 }, markX = 15.0)
        }
    }
}
