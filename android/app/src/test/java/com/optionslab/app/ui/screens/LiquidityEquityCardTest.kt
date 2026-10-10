package com.optionslab.app.ui.screens

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.data.ForwardRecords
import com.optionslab.app.data.OrbArms
import com.optionslab.app.testing.DeviceConfig
import com.optionslab.app.testing.ScreenTest
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.engine.orb.LiquidityRules
import com.optionslab.ira.ForwardCheck
import com.optionslab.ira.LiquidityEquity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

/** Liquidity 15+5's paper records: none, one trade (waiting), five (too few to judge, a losing streak), 24 in line. */
internal object EquityFixtures {
    private val d0 = LocalDate.of(2026, 10, 6)
    private fun t(i: Int, x: Double, index: String = if (i % 3 == 2) "FINNIFTY" else "BANKNIFTY") = LiquidityEquity.Trade(d0.plusDays(i.toLong()), x, index)
    val none: LiquidityEquity.Equity = LiquidityEquity.of(emptyList())
    val one: LiquidityEquity.Equity = LiquidityEquity.of(listOf(t(0, -420.0)))
    val five: LiquidityEquity.Equity = LiquidityEquity.of(listOf(1_000.0, -400.0, -700.0, 1_500.0, -200.0).mapIndexed { i, x -> t(i, x) })
    val inLine: LiquidityEquity.Equity = LiquidityEquity.of((0 until 24).map { t(it, if (it % 2 == 0) 1_400.0 else -1_000.0) })
}

@org.robolectric.annotation.Config(qualifiers = "w411dp-h2400dp")
@RunWith(AndroidJUnit4::class)
class LiquidityEquityCardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun shows(t: String, sub: Boolean = false) = compose.onAllNodesWithText(t, substring = sub).fetchSemanticsNodes().isNotEmpty()
    private fun count(t: String) = compose.onAllNodesWithText(t).fetchSemanticsNodes().size
    private fun chart(desc: String) = compose.onAllNodesWithContentDescription(desc, substring = true)

    private fun card(q: LiquidityEquity.Equity) = compose.setContent {
        IraAlgoTheme("light") { Column(Modifier.verticalScroll(rememberScrollState())) { LiquidityEquityContent(q) } }
    }

    @Test fun underTwoTradesItWaitsInsteadOfAnEmptyChart() {
        card(EquityFixtures.one)
        compose.waitForIdle()
        assertTrue(shows("PAPER RECORD"))
        assertTrue(shows(keepNumbersWhole(LiquidityEquity.waiting(EquityFixtures.one))))
        assertTrue(LiquidityEquity.waiting(EquityFixtures.one).contains("1 closed paper trade since 6 Oct 2026 (−₹420 a lot)"))
        assertTrue("no chart", chart("Paper record chart").fetchSemanticsNodes().isEmpty())
        assertFalse(shows("Max drawdown"))
    }

    @Test fun noTradeYet() {
        card(EquityFixtures.none)
        compose.waitForIdle()
        assertTrue(shows(keepNumbersWhole("Waiting for trades: no closed paper trade since 6 Oct 2026 yet. The chart starts at 2.")))
        assertTrue(chart("Paper record chart").fetchSemanticsNodes().isEmpty())
    }

    @Test fun theChartItsFiguresAndTheVerdict() {
        val q = EquityFixtures.five
        card(q)
        compose.waitForIdle()
        assertTrue(chart("Paper record chart").fetchSemanticsNodes().isNotEmpty())
        assertTrue(shows(keepNumbersWhole("Liquidity 15+5 · closed paper trades since 6 Oct 2026, per lot after charges, against the backtest")))
        listOf("Trades", "Net", "Max drawdown", "Streak").forEach { assertTrue(it, shows(it)) }
        // 5 trades, +₹1,200, the deepest fall −₹1,100, the last one a loss.
        listOf("5", "+₹1,200", "−₹1,100", "1 loss").forEach { assertTrue(it, shows(keepNumbersWhole(it))) }
        assertTrue(shows(keepNumbersWhole(LiquidityEquity.verdict(q))))
        assertTrue(LiquidityEquity.verdict(q).startsWith("Live vs backtest: too few trades (<20)"))
    }

    @Test fun inLineSaysSo() {
        val q = EquityFixtures.inLine
        assertEquals(ForwardCheck.Verdict.IN_LINE, q.check.verdict)
        card(q)
        compose.waitForIdle()
        listOf("24", "+₹4,800", "1 loss").forEach { assertTrue(it, shows(keepNumbersWhole(it))) }
        assertTrue(shows(keepNumbersWhole(LiquidityEquity.verdict(q))))
        assertTrue(shows(keepNumbersWhole("Live vs backtest: in line"), sub = true))
    }

    @Test fun aTapOpensTheLargerViewWithEveryTrade() {
        val q = EquityFixtures.five
        card(q)
        compose.waitForIdle()
        chart("Paper record chart").onFirst().areaCClick(); compose.waitForIdle()
        assertTrue(chart("Paper record, larger").fetchSemanticsNodes().isNotEmpty())
        assertTrue(shows("Every trade, newest first"))
        // Every trade with its date, index and net; the last one picked (in the list and above it).
        q.points.forEach { assertTrue(it.n.toString(), shows(keepNumbersWhole(LiquidityEquity.pointLine(it)))) }
        assertEquals("10 Oct 2026 · #5 · BANKNIFTY · −₹200 · total +₹1,200", LiquidityEquity.pointLine(q.points.last()))
        assertEquals(2, count(keepNumbersWhole(LiquidityEquity.pointLine(q.points.last()))))
        assertTrue(shows(keepNumbersWhole(LiquidityEquity.expectedLine(q, 5))))
        // A trade tapped in the list: it is the one shown.
        val third = keepNumbersWhole(LiquidityEquity.pointLine(q.points[2]))
        assertEquals(1, count(third))
        compose.onAllNodesWithText(third).onFirst().areaCClick(); compose.waitForIdle()
        assertEquals(2, count(third))
        assertTrue(shows(keepNumbersWhole(LiquidityEquity.expectedLine(q, 3))))
        assertTrue(shows("A read of the closed paper trades: it places, closes and arms nothing."))
        compose.onAllNodesWithText("Close").onFirst().areaCClick(); compose.waitForIdle()
        assertTrue(chart("Paper record, larger").fetchSemanticsNodes().isEmpty())
    }

    @Test fun readOffTheMainThreadOnceAndNotInALoop() {
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)   // never in the GOLD build (below)
        val reads = AtomicInteger()
        val offMain = AtomicInteger()
        compose.setContent {
            IraAlgoTheme("light") {
                LiquidityEquityCard(0) {
                    reads.incrementAndGet()
                    if (Looper.myLooper() != Looper.getMainLooper()) offMain.incrementAndGet()
                    EquityFixtures.five.copy()
                }
            }
        }
        compose.waitUntil(10_000) { shows(keepNumbersWhole("+₹1,200")) }   // read on Dispatchers.IO, outside Compose's idling
        compose.waitForIdle()
        assertEquals(1, reads.get())
        assertEquals(1, offMain.get())
    }

    @Test fun aRecordThatCannotBeReadShowsAsNone() {
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)   // never in the GOLD build (below)
        compose.setContent { IraAlgoTheme("dark") { LiquidityEquityCard(0) { error("unreadable") } } }
        compose.waitUntil(10_000) { shows("Waiting for trades", sub = true) }
        assertTrue(shows(keepNumbersWhole(LiquidityEquity.waiting(EquityFixtures.none))))
    }

    @Test fun underTheLiquidityRowOnlyWhenGiven() {
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)   // never in the GOLD build (below)
        compose.setContent {
            IraAlgoTheme("light") {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OrbRowsContent(StrategyFakes.orbView(armed = true), false, RecordingStrategyActions(), StrategyFakes.reauthWhy,
                        paperRecord = { EquityFixtures.inLine })
                }
            }
        }
        compose.waitUntil(10_000) { shows("PAPER RECORD") }
        assertEquals("one card, under Liquidity 15+5", 1, count("PAPER RECORD"))
        assertTrue(shows(keepNumbersWhole(LiquidityEquity.verdict(EquityFixtures.inLine))))
    }

    @Test fun hiddenInTheGoldBuild() {
        org.junit.Assume.assumeTrue(com.optionslab.app.BuildConfig.GOLD)
        val reads = AtomicInteger()
        compose.setContent { IraAlgoTheme("light") { LiquidityEquityCard(0) { reads.incrementAndGet(); EquityFixtures.inLine } } }
        compose.waitForIdle()
        assertFalse(shows("PAPER RECORD"))
        assertEquals("nothing read", 0, reads.get())
    }

    @Test fun recordsPerLotWithTheirIndex() {
        val day = LocalDate.of(2026, 10, 7)
        fun pos(arm: String, net: Double, lot: Int? = null, qty: Int = 30, live: Boolean = false, open: Boolean = false, hour: Int = 11): OrbArms.Position {
            val at = day.atTime(hour, 0)
            val exit = if (open) null else 100.0 + (net + 20.0) / qty
            return OrbArms.Position(arm, "X", "CE", qty, 100.0, at, at, null, null, null, exit = exit, exitTime = if (open) null else at.plusMinutes(20),
                why = "x", charges = 20.0, live = live, lot = lot)
        }
        val closed = listOf(pos(LiquidityRules.FIN5.source, 600.0, lot = 15, qty = 30, hour = 13), pos(LiquidityRules.ARM15.source, 200.0, hour = 10),
            pos(LiquidityRules.ARM5.source, 999.0, live = true), pos(LiquidityRules.ARM5.source, 0.0, open = true), pos("orb", 5_000.0))
        val trades = ForwardRecords.liquidityEquityTrades(closed)
        assertEquals(listOf("FINNIFTY", "BANKNIFTY"), trades.map { it.index })
        assertEquals(listOf(300.0, 200.0), trades.map { Math.round(it.net * 100) / 100.0 })
        // Taken in entry order on the day: 10:00 before 13:00.
        val q = LiquidityEquity.of(trades)
        assertEquals(listOf("BANKNIFTY", "FINNIFTY"), q.points.map { it.index })
        assertEquals(500.0, q.net, 1e-6)
        // The same trades as Live vs backtest's.
        assertEquals(ForwardRecords.liquidity(closed).map { it.net }.sorted(), trades.map { it.net }.sorted())
    }
}

/** The card's states on six set-ups spanning the device matrix (font 2.0 and landscape among them): screenshot and layout lint. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LiquidityEquityLayoutTest(device: DeviceConfig) : ScreenTest(device) {
    @get:Rule val watchdog = com.optionslab.app.testing.ResearchWatchdog()
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun configs(): List<Array<Any>> = com.optionslab.app.testing.ResearchMatrix.six()
    }

    @Test fun paperRecordStates() = checkScreen("liquidity-paper-record") {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            LiquidityEquityContent(EquityFixtures.inLine)
            LiquidityEquityContent(EquityFixtures.five)
            LiquidityEquityContent(EquityFixtures.one)
        }
    }

    @Test fun paperRecordLarger() {
        show { Column(Modifier.verticalScroll(rememberScrollState())) { LiquidityEquityContent(EquityFixtures.inLine) } }
        compose.onAllNodesWithContentDescription("Paper record chart", substring = true).onFirst().areaCClick(); compose.waitForIdle()
        areaCCaptureTop(compose, "liquidity-paper-record-larger", device); lint("liquidity-paper-record-larger")
    }
}
