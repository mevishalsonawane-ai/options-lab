package com.optionslab.app.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.data.ForwardRecords
import com.optionslab.app.data.OrbArms
import com.optionslab.app.data.ShadowArms
import com.optionslab.app.testing.DeviceConfig
import com.optionslab.app.testing.ScreenTest
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.engine.orb.HeroRules
import com.optionslab.engine.orb.LiquidityRules
import com.optionslab.engine.orb.ShadowRules
import com.optionslab.ira.ForwardCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.LocalDateTime

/** Three cards: Liquidity in line, Solo below its backtest (the early flag), Hero with too few trades; and an O08 shadow. */
internal object ForwardFixtures {
    private val d0 = LocalDate.of(2026, 10, 6)
    private fun t(i: Int, x: Double) = ForwardCheck.Trade(d0.plusDays(i.toLong()), x)
    val inLine = ForwardRecords.Row(ForwardCheck.check(ForwardCheck.LIQUIDITY, (0 until 24).map { t(it, if (it % 2 == 0) 1_400.0 else -1_000.0) }))
    val below = ForwardRecords.Row(ForwardCheck.check(ForwardCheck.SOLO, (0 until 12).map { t(it, -3_000.0) }))
    val tooFew = ForwardRecords.Row(ForwardCheck.check(ForwardCheck.HERO, (0 until 3).map { t(it, -1_500.0) }))
    val shadow = ForwardRecords.Row(ForwardCheck.check(ForwardCheck.SHADOWS.getValue("momo_o08"), emptyList()), shadow = true,
        title = "Midday momentum (NIFTY) · shadow O08")
    val all = listOf(inLine, below, tooFew, shadow)
}

@org.robolectric.annotation.Config(qualifiers = "w411dp-h2400dp")
@RunWith(AndroidJUnit4::class)
class LiveVsBacktestTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun shows(t: String, sub: Boolean = false) = compose.onAllNodesWithText(t, substring = sub).fetchSemanticsNodes().isNotEmpty()

    @Test fun verdictsInPlainWords() {
        assertEquals(ForwardCheck.Verdict.IN_LINE, ForwardFixtures.inLine.result.verdict)
        assertEquals(ForwardCheck.Verdict.BELOW, ForwardFixtures.below.result.verdict)
        assertEquals(ForwardCheck.Verdict.TOO_FEW, ForwardFixtures.tooFew.result.verdict)
        compose.setContent { IraAlgoTheme("light") { Column(Modifier.verticalScroll(rememberScrollState())) { LiveVsBacktestCard(ForwardFixtures.all) } } }
        compose.waitForIdle()
        assertTrue(shows("LIVE VS BACKTEST"))
        listOf("Liquidity 15+5", "Solo", "Hero (expiry)", "Midday momentum (NIFTY) · shadow O08").forEach { assertTrue(it, shows(it)) }
        assertTrue(shows("In line"))
        assertTrue(shows("Below expectation (12 trades)"))
        assertTrue(shows("Too few trades (<20)"))
        // Liquidity's numbers: 24 trades, +₹4,800, ₹200 a trade, half won, against ₹228 ± ₹843.
        listOf("24", "+₹4,800", "₹200", "50%", "₹228 ± ₹843").forEach { assertTrue(it, shows(it)) }
        assertTrue(shows("Lottery: judge by drawdown, not mean"))
        assertTrue(shows("Shadow · no orders"))
        // The sentences (numbers kept whole with word joiners): the early flag and the lottery's.
        assertTrue(shows("sustained shortfall", sub = true))
        assertTrue(shows("A lottery: judged by drawdown, not mean", sub = true))
        assertTrue(shows("No forward trades yet.", sub = true))
        assertTrue(compose.onAllNodesWithText("Expected").fetchSemanticsNodes().size == 4)
    }

    @Test fun emptyCard() {
        compose.setContent { IraAlgoTheme("dark") { LiveVsBacktestCard(emptyList()) } }
        compose.waitForIdle()
        assertTrue(shows("No running strategy to compare yet."))
    }

    private fun pos(arm: String, net: Double, lot: Int? = null, qty: Int = 30, live: Boolean = false, open: Boolean = false, day: LocalDate = LocalDate.of(2026, 10, 7)): OrbArms.Position {
        val at = day.atTime(11, 0)
        // Bought at 100; sold so that gross - charges = net (charges 20).
        val exit = if (open) null else 100.0 + (net + 20.0) / qty
        return OrbArms.Position(arm, "X", "CE", qty, 100.0, at, at, null, null, null, exit = exit, exitTime = if (open) null else at.plusMinutes(20),
            why = "x", charges = 20.0, live = live, lot = lot)
    }

    @Test fun recordsPerLotAndByArm() {
        val liq = LiquidityRules.BOOKS.first().source
        val closed = listOf(pos(liq, 600.0, lot = 15, qty = 30), pos(liq, 200.0), pos(liq, 999.0, live = true), pos(liq, 0.0, open = true),
            pos("orb", 5_000.0), pos(HeroRules.ARM.source, 4_000.0))
        val l = ForwardRecords.liquidity(closed)
        // Two lots: ₹600 counts as ₹300 a lot; before the size setting (no lot): one lot. Live and open trades are not counted.
        assertEquals(listOf(300.0, 200.0), l.map { Math.round(it.net * 100) / 100.0 })
        assertEquals(listOf(4_000.0), ForwardRecords.hero(closed).map { Math.round(it.net * 100) / 100.0 })
    }

    private fun shadowRow(v: ShadowRules.Variant, vararg nets: Double): ShadowArms.Row {
        val day = LocalDate.of(2026, 10, 7)
        val trades = nets.mapIndexed { i, n ->
            val at: LocalDateTime = day.plusDays(i.toLong()).atTime(12, 0)
            ShadowArms.Trade(v.id, "S", "K", v.underlying, day, 100, "CE", 1, 100.0, at, at, exit = 100.0 + n, exitTime = at.plusMinutes(30))
        }
        return ShadowArms.Row(v, ShadowRules.summarize(nets.toList()), day, "", false, false, false, trades, "")
    }

    @Test fun bestShadowOfEachArmWithAPinnedBacktest() {
        val rows = listOf(shadowRow(ShadowRules.ORB_V43, -500.0), shadowRow(ShadowRules.ORB_P10, 200.0), shadowRow(ShadowRules.ORBF_P10, -900.0),
            shadowRow(ShadowRules.FADE_R20, 5_000.0), shadowRow(ShadowRules.FADE_P10, -100.0), shadowRow(ShadowRules.MOMO_O08, 300.0))
        val best = ForwardRecords.bestShadows(rows)
        // ORB: OP10 beats V43; ORB Fresh: V43 beats FP10; Range Fade: R20 has no pinned backtest, so RP10; O08 on its own.
        assertEquals(listOf("orb_p10", "orb_v43", "fade_p10", "momo_o08"), best.map { it.result.expectation.key })
        assertTrue(best.all { it.shadow })
        assertEquals(1, best.first().result.trades)
        assertEquals(200.0, best.first().result.net, 1e-9)
        // Each shadow against its own research, never the raw best net: V43 at −₹300 beats its research (−₹484 a trade),
        // OP10 at −₹200 is under its own (−₹100) - V43 is the ORB's best though its net is lower (shown once).
        assertEquals(listOf("orb_v43"), ForwardRecords.bestShadows(listOf(shadowRow(ShadowRules.ORB_V43, -300.0), shadowRow(ShadowRules.ORB_P10, -200.0)))
            .map { it.result.expectation.key })
    }
}

/** The card on six set-ups spanning the device matrix (font 2.0 and landscape among them): a screenshot each and the layout lint. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LiveVsBacktestLayoutTest(device: DeviceConfig) : ScreenTest(device) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun configs(): List<Array<Any>> = com.optionslab.app.testing.ResearchMatrix.six()
    }

    @Test fun liveVsBacktest() = checkScreen("live-vs-backtest") {
        Column(Modifier.verticalScroll(rememberScrollState())) { LiveVsBacktestCard(ForwardFixtures.all) }
    }
}
