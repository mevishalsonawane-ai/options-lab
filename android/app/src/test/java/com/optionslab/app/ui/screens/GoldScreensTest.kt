package com.optionslab.app.ui.screens

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.optionslab.app.data.GoldPaper
import com.optionslab.app.testing.AreaE
import com.optionslab.app.testing.AreaEWatchdog
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.OfflineModel
import com.optionslab.app.testing.frames
import com.optionslab.app.testing.has
import com.optionslab.app.testing.reveal
import com.optionslab.app.testing.switchFor
import com.optionslab.app.testing.until
import com.optionslab.app.testing.waitForNoText
import com.optionslab.app.testing.waitForText
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.engine.orb.Bar
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * IraGoldAlgo's screens, every feature, on a simulated phone (the gold palette, light and dark): Home (price, paper
 * account, the strategy switch, an open trade), Trades (totals, closed trades), Settings (lot size, start again with
 * its confirm, theme, security page without IraAlgo's Zerodha parts, diagnostics). Prices come from a test feed: no
 * network. Screenshots go to build/outputs/roborazzi/gold-*.png.
 */
@org.robolectric.annotation.Config(qualifiers = "w411dp-h2000dp")
@RunWith(AndroidJUnit4::class)
class GoldScreensTest {
    @get:Rule val watchdog = AreaEWatchdog()
    @get:Rule val compose = createComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var offline: OfflineModel
    private val model: AppModel get() = offline.model
    private val monday = LocalDate.of(2026, 9, 28)
    private val now = monday.atTime(12, 0, 30)

    @Before fun up() {
        AreaE.resetGlobals()
        offline = OfflineModel(app)
        GoldPaper.testNow = now
        // A flat morning at 2400.00: the price the screens show.
        GoldPaper.testMinutes = { t -> (0 until 300).map { Bar(monday.atTime(7, 0).plusMinutes(it.toLong()), 2400.0, 2400.0, 2400.0, 2400.0) }
            .filter { !it.start.plusMinutes(1).isAfter(t) } }
        runBlocking { GoldPaper.replaceForTest(GoldPaper.Book()) }
        // The chart's candles: a flat 2400 hour from a test feed, never the network.
        com.optionslab.app.data.GoldChart.resetForTest()
        com.optionslab.app.data.GoldChart.testFetch = { _ ->
            val q = org.json.JSONObject().apply { listOf("open", "high", "low", "close").forEach { put(it, org.json.JSONArray().put(2400.0).put(2401.0)) } }
            org.json.JSONObject().put("chart", org.json.JSONObject().put("result", org.json.JSONArray().put(org.json.JSONObject()
                .put("timestamp", org.json.JSONArray().put(1_790_000_000L).put(1_790_000_300L))
                .put("indicators", org.json.JSONObject().put("quote", org.json.JSONArray().put(q))))))
        }
    }

    @After fun down() {
        GoldPaper.testMinutes = null; GoldPaper.testNow = null; com.optionslab.app.data.GoldChart.testFetch = null
        offline.close()
        AreaE.resetGlobals()
        assertEquals("no test may reach the network", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun show(dark: Boolean = false) {
        compose.setContent { IraAlgoTheme(if (dark) "dark" else "light", gold = true) { GoldMain(model) } }
        compose.frames()
    }

    private fun tap(text: String) { compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.OnClick); compose.frames() }
    private fun shot(name: String) { compose.waitForIdle(); compose.onRoot().captureRoboImage("build/outputs/roborazzi/gold-$name.png") }

    @Test fun homeShowsThePriceTheAccountAndArmsTheStrategy() {
        show()
        compose.waitForText("$2,400.00")
        compose.waitForText("Paper account")
        assertTrue(compose.has("$1,000.00"))
        assertTrue(compose.has("0.01 lot (1 oz)"))
        assertTrue(compose.has("Not armed"))
        assertTrue(compose.has("Gold (COMEX futures)"))
        assertTrue(compose.has("24x5: Monday 05:30 IST to Saturday 02:30 IST", substring = true))
        assertTrue(compose.has("Last signal")); assertTrue(compose.has("none yet"))
        shot("home-light")
        compose.switchFor("Armed").performSemanticsAction(SemanticsActions.OnClick); compose.frames()
        compose.until(10_000, "armed") { GoldPaper.book.value.armed }
        // Armed: the next decision time shows; the status is a decision's (no break on the last candle, or the next one).
        compose.waitForText("Next decision")
        compose.until(10_000, "an armed status") { GoldPaper.book.value.status != "Not armed" }
        compose.switchFor("Armed").performSemanticsAction(SemanticsActions.OnClick); compose.frames()
        compose.until(10_000, "disarmed") { !GoldPaper.book.value.armed }
        compose.waitForText("Not armed")
    }

    @Test fun anOpenTradeShowsItsLevelsAndLiveProfit() {
        runBlocking {
            GoldPaper.replaceForTest(GoldPaper.Book(armed = true, status = "Bought at 2390.15",
                position = GoldPaper.Position(2390.15, monday.atTime(11, 0), monday.atTime(10, 0), 2388.0, 2410.0)))
        }
        show(dark = true)
        compose.waitForText("Broken level")
        assertTrue(compose.has("2388.00"))
        assertTrue(compose.has("2410.00"))
        // Open: (2399.85 bid - 2390.15) x 1 oz - $0.07.
        compose.waitForText("+$9.63")
        shot("home-open-trade-dark")
    }

    @Test fun tradesTabTotalsTodayTheMonthAndEverything() {
        val today = monday
        val trades = listOf(
            GoldPaper.Trade(2400.0, 2410.0, today.atTime(9, 0), today.atTime(10, 0), 0.01, "next_liquidity", 9.93),
            GoldPaper.Trade(2400.0, 2395.0, today.minusDays(3).atTime(9, 0), today.minusDays(3).atTime(11, 0), 0.01, "failed_break", -5.07),
            GoldPaper.Trade(2300.0, 2320.0, LocalDateTime.of(2026, 8, 3, 9, 0), LocalDateTime.of(2026, 8, 3, 15, 0), 0.01, "cut_off", 19.93),
        )
        runBlocking { GoldPaper.replaceForTest(GoldPaper.Book(trades = trades)) }
        show()
        tap("Trades")
        compose.waitForText("Closed trades")
        assertTrue(compose.has("Today · 1 trades · 100% won"))
        assertTrue(compose.has("This month · 2 trades · 50% won"))
        assertTrue(compose.has("All · 3 trades · 67% won"))
        assertTrue(compose.has("+$24.79"))
        assertTrue(compose.has("reached the next liquidity level", substring = true))
        assertTrue(compose.has("the break failed", substring = true))
        assertTrue(compose.has("cut-off before the weekend", substring = true))
        // Three trades: the balance curve, best, worst and the deepest drawdown (+19.93, -5.07, +9.93 in time order).
        assertTrue(compose.has("Best trade")); assertTrue(compose.has("+$19.93"))
        assertTrue(compose.has("Worst trade")); assertTrue(compose.has("-$5.07"))
        assertTrue(compose.has("Deepest drawdown"))
        shot("trades-light")
        // Home counts the closed trades into the balance.
        tap("Home")
        compose.waitForText("$1,024.79")
    }

    @Test fun settingsChangeTheLotStartAgainAndTheTheme() {
        runBlocking { GoldPaper.replaceForTest(GoldPaper.Book(trades = listOf(
            GoldPaper.Trade(2400.0, 2410.0, monday.atTime(9, 0), monday.atTime(10, 0), 0.01, "next_liquidity", 9.93)))) }
        show()
        tap("Settings")
        compose.waitForText("Lot size".uppercase())
        shot("settings-light")
        tap("0.05")
        compose.until(10_000, "the lot") { GoldPaper.book.value.lots == 0.05 }
        // Start again asks first; Cancel keeps everything.
        tap("$5,000")
        compose.waitForText("Reset the paper account to $5,000? Its trades and any open trade go.")
        tap("Cancel")
        compose.waitForNoText("Reset the paper account to $5,000? Its trades and any open trade go.")
        assertEquals(1, GoldPaper.book.value.trades.size)
        tap("$5,000"); tap("Reset")
        compose.until(10_000, "the reset") { GoldPaper.book.value.start == 5_000.0 && GoldPaper.book.value.trades.isEmpty() }
        assertEquals("the lot size is kept", 0.05, GoldPaper.book.value.lots, 1e-9)
        // Theme.
        compose.reveal("Dark")
        tap("Dark")
        compose.until(10_000, "the theme") { model.settings.value.theme == "dark" }
        // Diagnostics show the build.
        compose.reveal("Copy diagnostics")
        assertTrue(compose.has("This app: build", substring = true))
    }

    @Test fun securityOpensWithoutIraAlgosZerodhaPartsAndComesBack() {
        show()
        tap("Settings")
        compose.reveal("Open security")
        tap("Open security")
        compose.waitForText("Security")
        assertTrue(compose.has("‹ Settings"))
        shot("security-light")
        tap("‹ Settings")
        compose.waitForText("Lot size".uppercase())
        assertFalse(compose.has("‹ Settings"))
    }

    @Test fun theChartTabIsIraAlgosChartOnGoldWithNothingToOrder() {
        show()
        tap("Chart")
        compose.waitForText("XAUUSD")
        assertTrue(compose.has("ADV") || compose.has("BASIC"))
        listOf("BUY", "SELL", "ALERT", "OPT").forEach { assertFalse("$it on the gold chart", compose.has(it)) }
        shot("chart-light")
    }

    @Test fun thePnlTabIsIraAlgosCalendarInDollars() {
        val trades = listOf(
            GoldPaper.Trade(2400.0, 2410.0, monday.atTime(9, 0), monday.atTime(10, 0), 0.01, "next_liquidity", 9.93),
            // Closed at 20:00 UTC on Friday the 25th: Saturday 01:30 in India, so it counts on the 26th.
            GoldPaper.Trade(2400.0, 2395.0, monday.minusDays(4).atTime(9, 0), monday.minusDays(3).atTime(20, 0), 0.01, "cut_off", -5.07),
        )
        runBlocking { GoldPaper.replaceForTest(GoldPaper.Book(trades = trades)) }
        val days = goldDays(trades)
        assertEquals(setOf(monday, LocalDate.of(2026, 9, 26)), days.keys)
        show()
        tap("P&L")
        compose.waitForText("+$4.86")                          // September's net: 9.93 - 5.07
        assertFalse(compose.has("₹", substring = true))
        // The win rate is by trade (one won, one lost), not by day (both days count as one each way, never "100%").
        compose.reveal("1W · 1L trades")
        assertTrue(compose.has("50%"))
        shot("pnl-light")
    }

    @Test fun noTradesShowADashNotZeroPercent() {
        show()
        tap("Trades")
        compose.waitForText("Today · 0 trades · —")
        assertFalse(compose.has("0% won", substring = true))
    }

    @Test fun aDelayedFeedIsSaidOnHome() {
        // The last price is from 11:30, it is 12:00:30 and gold is trading: more than 10 minutes old.
        GoldPaper.testMinutes = { _ -> listOf(Bar(monday.atTime(11, 30), 2400.0, 2400.0, 2400.0, 2400.0)) }
        show()
        compose.waitForText("Price feed")
        assertTrue(compose.has("delayed: last price 11:30 UTC (17:00 IST)"))
    }

    @Test fun nothingHereCanPlaceARealOrder() {
        show()
        // No live / Zerodha / order controls anywhere on the three tabs.
        listOf("Home", "Trades", "Settings").forEach { t ->
            tap(t); compose.frames()
            listOf("Zerodha", "LIVE", "Place order", "Send", "Kite").forEach { w -> assertFalse("$w on $t", compose.has(w, substring = true)) }
        }
        assertNull(GoldPaper.book.value.position)
    }
}
