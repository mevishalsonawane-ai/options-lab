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
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.data.OrbArms
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.engine.orb.LiquidityRules
import com.optionslab.ira.LiquidityOpen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicInteger

/** Liquidity 15+5's live open-position panel: its figures, its read (off the main thread, once a refresh), its place, GOLD. */
@org.robolectric.annotation.Config(qualifiers = "w411dp-h2400dp")
@RunWith(AndroidJUnit4::class)
class LiquidityOpenPanelTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val day: LocalDate = LocalDate.of(2026, 10, 6)
    private val clock: LocalDateTime = day.atTime(10, 55, 30)
    private val pos = OrbArms.Position(LiquidityRules.ARM15.source, "BANKNIFTY26OCT52000CE", "CE", 60, 210.0, day.atTime(10, 46, 12),
        day.atTime(10, 30), "E1", "S1", LiquidityRules.stopTrigger(210.0), charges = 24.5, level = 51_970.0, target = 52_200.0,
        peak = 235.0, lot = 30, low = 198.0)
    private val fresh = OrbArms.LiquidityOpenNow(pos, 220.0, 52_050.0, day.atTime(10, 55))

    private fun shows(t: String, sub: Boolean = false) = compose.onAllNodesWithText(t, substring = sub).fetchSemanticsNodes().isNotEmpty()

    @Test fun everyFigureAndExitAsItStands() {
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        val v = liquidityOpenView(pos, 219.0, fresh, clock)
        compose.setContent { IraAlgoTheme("light") { Column(Modifier.verticalScroll(rememberScrollState())) { LiquidityOpenContent(v) } } }
        compose.waitForIdle()
        assertTrue(shows("OPEN POSITION · PAPER"))
        listOf(v.contract, v.entered, v.now, v.pnl, v.afterCharges!!, v.range, v.also, v.asOf, v.progress!!.label)
            .forEach { assertTrue(it, shows(keepNumbersWhole(it))) }
        v.exits.forEach { e ->
            assertTrue(e.name, shows(keepNumbersWhole("${e.name} · ${e.rule}")))
            assertTrue(e.away, shows(keepNumbersWhole(e.away)))
        }
        // The read's mark and index (the same position): its words exactly.
        assertTrue(shows(keepNumbersWhole("P&L +₹600 before charges · +₹300 a lot")))
        assertTrue(shows(keepNumbersWhole("Index stop · BANKNIFTY under 51,940.00 (level 51,970.00 − 30.0)")))
        assertTrue(shows(keepNumbersWhole("index 52,050.00 · 150.0 pts away")))
        assertTrue(shows(keepNumbersWhole("in 10:42 · not met now (220.00)")))
        assertTrue(compose.onAllNodesWithContentDescription("Index between the index stop and the target", substring = true)
            .fetchSemanticsNodes().isNotEmpty())
        // Read only: no button of its own.
        assertFalse(shows("Sell")); assertFalse(shows("Exit now"))
    }

    @Test fun anotherPositionsReadIsNotMixedIn() {
        val other = OrbArms.LiquidityOpenNow(pos.copy(symbol = "BANKNIFTY26OCT52100PE", right = "PE"), 99.0, 52_000.0, day.atTime(10, 55))
        val v = liquidityOpenView(pos, 219.0, other, clock)
        assertEquals("Premium now 219.00 (+4.3% on the price paid)", v.now)
        assertEquals("index not read yet", v.exits.first { it.name == "Index stop" }.away)
        assertNull(v.progress)
        // FINNIFTY's book: its 15-point index stop.
        val fin = liquidityOpenView(pos.copy(arm = LiquidityRules.FIN5.source), 219.0, null, clock)
        assertTrue(fin.exits.first { it.name == "Index stop" }.rule.endsWith("(level 51,970.00 − 15.0)"))
    }

    @Test fun readOffTheMainThreadOncePerRefresh() {
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        val reads = AtomicInteger()
        val offMain = AtomicInteger()
        compose.setContent {
            IraAlgoTheme("light") {
                LiquidityOpenPanel(pos, 219.0, read = {
                    reads.incrementAndGet()
                    if (Looper.myLooper() != Looper.getMainLooper()) offMain.incrementAndGet()
                    fresh
                }, now = { clock })
            }
        }
        compose.waitUntil(10_000) { shows(keepNumbersWhole("index 52,050.00 · 110.0 pts away")) }
        compose.waitForIdle()
        assertEquals(1, reads.get())
        assertEquals(1, offMain.get())
        assertEquals(LiquidityOpen.view(LiquidityOpen.Input("BANKNIFTY", "CE", pos.symbol, 60, 2.0, false, 210.0, pos.entryTime, 220.0,
            pos.stopTrigger, true, 51_970.0, 52_200.0, 52_050.0, day.atTime(10, 55), 235.0, 198.0, false, 24.5), clock),
            liquidityOpenView(pos, 219.0, fresh, clock))
    }

    @Test fun aReadThatFailsShowsTheRowsOwnPosition() {
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        compose.setContent { IraAlgoTheme("dark") { LiquidityOpenPanel(pos, 219.0, read = { error("unreadable") }, now = { clock }) } }
        compose.waitUntil(10_000) { shows("OPEN POSITION", sub = true) }
        assertTrue(shows(keepNumbersWhole("Premium now 219.00 (+4.3% on the price paid)")))
        assertTrue(shows("index not read yet"))
    }

    @Test fun underTheLiquidityRowOnlyWhileOpen() {
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        compose.setContent {
            IraAlgoTheme("light") {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OrbRowsContent(StrategyFakes.orbView(armed = true, open = true), false, RecordingStrategyActions(), StrategyFakes.reauthWhy)
                }
            }
        }
        compose.waitUntil(10_000) { shows("OPEN POSITION", sub = true) }
        assertEquals("one panel", 1, compose.onAllNodesWithText("OPEN POSITION", substring = true).fetchSemanticsNodes().size)
        assertTrue(shows(keepNumbersWhole("Premium stop · 170.00, 15% under 210.00 (a resting order)")))
    }

    @Test fun noPanelWhenFlat() {
        compose.setContent {
            IraAlgoTheme("light") {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OrbRowsContent(StrategyFakes.orbView(armed = true, open = false), false, RecordingStrategyActions(), StrategyFakes.reauthWhy)
                }
            }
        }
        compose.waitForIdle()
        assertFalse(shows("OPEN POSITION", sub = true))
    }

    @Test fun hiddenInTheGoldBuild() {
        org.junit.Assume.assumeTrue(com.optionslab.app.BuildConfig.GOLD)
        val reads = AtomicInteger()
        compose.setContent { IraAlgoTheme("light") { LiquidityOpenPanel(pos, 219.0, read = { reads.incrementAndGet(); fresh }, now = { clock }) } }
        compose.waitForIdle()
        assertFalse(shows("OPEN POSITION", sub = true))
        assertEquals("nothing read", 0, reads.get())
    }
}
