package com.optionslab.app.ui.screens

import android.app.Application
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipe
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.data.Ledger
import com.optionslab.app.data.Market
import com.optionslab.app.testing.AreaE
import com.optionslab.app.testing.FakePicker
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.OfflineModel
import com.optionslab.app.testing.has
import com.optionslab.app.testing.AreaEWatchdog
import com.optionslab.app.testing.frames
import com.optionslab.app.testing.pause
import com.optionslab.app.testing.until
import com.optionslab.app.testing.reveal
import com.optionslab.app.testing.waitForNoText
import com.optionslab.app.testing.waitForText
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.components.AlertBanner
import com.optionslab.app.ui.components.AlertDialog
import com.optionslab.app.ui.components.AlertOn
import com.optionslab.app.ui.components.SwipeToConfirm
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.app.work.Alerts
import com.optionslab.engine.Live
import com.optionslab.engine.Right
import com.optionslab.engine.options.ChainRow
import com.optionslab.engine.options.ChainSnapshot
import com.optionslab.engine.options.OptLeg
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowBuild
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.round

/**
 * The smaller screens: the first-use guide, the battery gate, the Expiry Put page (formerly the
 * Ticket tab), the option-chain cards, the P&L calendar, the alert banner and the slide-to-confirm
 * control. Model-bound pages run on a real offline [AppModel]; nothing is fetched.
 */
@RunWith(AndroidJUnit4::class)
class MiscScreensTest {
    @get:Rule val watchdog = AreaEWatchdog()
    @get:Rule val compose = createComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private var offline: OfflineModel? = null
    private val model: AppModel get() = (offline ?: OfflineModel(app).also { offline = it }).model
    private lateinit var picker: FakePicker

    @Before fun up() { AreaE.resetGlobals(); picker = FakePicker(app) }
    @After fun down() {
        offline?.close()
        AreaE.resetGlobals()
        assertEquals("no test may reach the network", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun show(content: @Composable () -> Unit) = compose.setContent {
        CompositionLocalProvider(LocalActivityResultRegistryOwner provides picker) { IraAlgoTheme("light") { content() } }
    }

    private fun tap(text: String) { compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.OnClick); compose.frames() }

    // ---- Getting started --------------------------------------------------------------------------

    @Test fun theGuideStepsForwardAndBackAndEachButtonSaysWhereToGo() {
        val went = mutableListOf<String>()
        show { GettingStarted { went += it } }
        compose.onNodeWithText("Welcome to IraAlgo").assertExists()
        assertFalse("no Back on the first page", compose.has("Back"))
        tap("Next")
        compose.waitForText("Start automated trading")
        tap("Take me to the ORB switch")
        assertEquals(listOf("orb"), went)
        tap("Back")
        compose.waitForText("Welcome to IraAlgo")
        tap("Next"); tap("Next")
        compose.waitForText("Stay in control")
        tap("Next")
        compose.waitForText("Find your way")
        assertFalse("the last page has Done instead of Next", compose.has("Next"))
        tap("Open the chart")
        tap("Done")
        tap("Skip")
        assertEquals(listOf("orb", "chart", "orb", ""), went)
    }

    // ---- The battery gate -------------------------------------------------------------------------

    private fun power() = shadowOf(app.getSystemService(PowerManager::class.java))

    @Test fun anUnrestrictedPhonePassesTheBatteryGateAtOnce() {
        power().setIgnoringBatteryOptimizations(app.packageName, true)
        var done = 0
        show { BatteryScreen { done++ } }
        compose.waitUntil(5_000) { done == 1 }
    }

    @Test fun theBatteryGateOpensTheSystemSettingsAndLetsGoOnceAllowed() {
        ShadowBuild.setManufacturer("Xiaomi")
        power().setIgnoringBatteryOptimizations(app.packageName, false)
        var done = 0
        show { BatteryScreen { done++ } }
        compose.onNodeWithText("Allow IraAlgo to run in the background").assertExists()
        compose.onNodeWithText("Xiaomi / Redmi / POCO", substring = true).assertExists()
        tap("Allow background running")
        val asked = shadowOf(app).nextStartedActivity
        assertNotNull(asked)
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, asked.action)
        assertEquals("package:${app.packageName}", asked.dataString)
        tap("Open IraAlgo's app settings")
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, shadowOf(app).nextStartedActivity.action)
        assertEquals(0, done)
        // Allowed in Settings, then back: the gate lets go by itself.
        power().setIgnoringBatteryOptimizations(app.packageName, true)
        compose.waitUntil(10_000) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_100)); done == 1 }
    }

    // ---- The Expiry Put page (Options → Expiry Put) -------------------------------------------------

    private fun ticket(day: LocalDate) = Live.Ticket(day, "NIFTY", day, "SELL", "PE", 24_000.0, 75, 1, 75,
        20.0, 24_300.0, 23_980.0, 90_000.0, null, null, null)

    @Test fun anEmptyLedgerAndTheTicketChoices() {
        val views = mutableListOf<String>()
        show { ToolsScreen(model, "expiryput", { views += it }) { _, _ -> } }
        compose.waitForText("No tickets yet", substring = true)
        tap("BANKNIFTY")
        compose.waitUntil(10_000) { model.settings.value.ticketUnderlying == "BANKNIFTY" }
        tap("3")
        compose.waitUntil(10_000) { model.settings.value.ticketLots == 3 }
        tap("naked")
        compose.waitUntil(10_000) { model.settings.value.wingPct == null }
        compose.onNodeWithText("naked").assertIsSelected()
        // The tool row switches the Options tab's view.
        tap("Chain")
        assertEquals(listOf("chain"), views)
        assertFalse("no export without tickets", compose.has("Export ledger (CSV)"))
    }

    @Test fun aTicketIsSettledStruckOutAndExported() {
        val day = Market.today()
        Ledger.record(ticket(day), null, null)
        Ledger.record(ticket(day.minusDays(7)), null, null)
        model.refreshLedger()
        compose.pause()   // the Settle dialog holds a text field
        show { TicketScreen(model) }
        compose.frames()
        compose.waitForText("$day  NIFTY 24000 PE")
        // No Zerodha account: nothing offers to send it.
        assertFalse(compose.has("Send to Zerodha…"))
        assertFalse(compose.has("Log in to Zerodha to send it"))
        // Settle, cancelled then confirmed with the exchange's figure.
        compose.reveal("Settle")
        compose.onAllNodesWithText("Settle")[0].performSemanticsAction(SemanticsActions.OnClick); compose.frames()
        compose.waitForText("Settle $day")
        tap("Cancel")
        compose.waitForNoText("Settle $day")
        compose.onAllNodesWithText("Settle")[0].performSemanticsAction(SemanticsActions.OnClick); compose.frames()
        compose.waitForText("Settle $day")
        compose.onNodeWithText("Settlement price (optional)").performTextReplacement("24100.5"); compose.frames()
        compose.onAllNodesWithText("Settle").onLast().performSemanticsAction(SemanticsActions.OnClick); compose.frames()
        compose.until(10_000) { Ledger.all().first { it.row.ticket.session == day }.row.status == "settled" }
        assertEquals(24_100.5, Ledger.all().first { it.row.ticket.session == day }.row.settlement!!, 0.0)
        compose.waitForText("WIN")
        // Strike out: Keep, then confirm.
        compose.onAllNodesWithText("Strike out")[0].performSemanticsAction(SemanticsActions.OnClick); compose.frames()
        compose.waitForText("Strike out $day?")
        tap("Keep")
        compose.waitForNoText("Strike out $day?")
        assertEquals(2, Ledger.all().size)
        compose.onAllNodesWithText("Strike out")[0].performSemanticsAction(SemanticsActions.OnClick); compose.frames()
        compose.waitForText("Strike out $day?")
        compose.onAllNodesWithText("Strike out").onLast().performSemanticsAction(SemanticsActions.OnClick); compose.frames()
        compose.until(10_000) { Ledger.all().size == 1 }
        // Export: to the file picked, the ledger as CSV.
        val out = picker.writable("ledger.csv")
        picker.answer = { out }
        compose.reveal("Export ledger (CSV)")
        tap("Export ledger (CSV)")
        assertEquals("paper_ledger.csv", picker.launched.single())
        compose.until(10_000) { picker.written(out)?.isNotEmpty() == true }
        assertTrue(String(picker.written(out)!!).startsWith("session,underlying"))
    }

    // ---- The option chain cards -----------------------------------------------------------------

    private val expiry = LocalDate.of(2026, 10, 27)

    /** A plausible NIFTY chain: intrinsic plus a bell of time value, OI heavier away from the money. */
    private fun chain(): ChainSnapshot {
        val spot = 24_512.0
        fun r2(x: Double) = round(x * 100) / 100
        val rows = (0..20).map { i ->
            val k = 24_000.0 + i * 50
            val tv = 90 * exp(-((k - spot) / 450).let { it * it }) + 3 + i / 100.0
            ChainRow(k,
                OptLeg("NIFTY26OCT${k.toInt()}CE", r2(max(0.0, spot - k) + tv), oi = 100_000L + i * 7_000L, volume = 5_000, prevOi = 90_000L + i * 5_000L, lotSize = 75),
                OptLeg("NIFTY26OCT${k.toInt()}PE", r2(max(0.0, k - spot) + tv + 0.5), oi = 240_000L - i * 6_000L, volume = 5_000, prevOi = 250_000L - i * 6_500L, lotSize = 75))
        }
        return ChainSnapshot.of("NIFTY", expiry, spot, 75, rows, ZonedDateTime.of(expiry.minusDays(4), LocalTime.of(11, 0), com.optionslab.engine.IST))
    }

    @Test fun theChainCardsRenderAndATapPicksThatOption() {
        val c = chain()
        val picks = mutableListOf<ChainPick>()
        var refreshed = 0
        show {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Header(c, "Upstox public candles (test)") { refreshed++ }
                ChainCard(c) { picks += it }
                OiCard(c); IvCard(c); GexCard(c); MoveCard(c)
            }
        }
        compose.onNodeWithText("NIFTY · $expiry").assertExists()
        compose.onNodeWithText("Upstox public candles (test)").assertExists()
        tap("Refresh")
        assertEquals(1, refreshed)
        for (title in listOf("Option chain", "IV smile", "Gamma exposure", "Expected move", "OI change today")) {
            compose.onNodeWithText(title).performScrollTo().assertExists()
        }
        val atm = c.atm!!
        val row = c.rows.first { it.strike == atm }
        val ceText = String.format(java.util.Locale.ENGLISH, "%,.2f", row.ce!!.ltp)
        compose.onAllNodesWithText(ceText)[0].performSemanticsAction(SemanticsActions.OnClick)
        val p = picks.single()
        assertEquals(atm, p.strike, 0.0)
        assertEquals(Right.CE, p.right)
        assertEquals(expiry, p.expiry)
        assertEquals(75, p.lotSize)
        val peText = String.format(java.util.Locale.ENGLISH, "%,.2f", row.pe!!.ltp)
        compose.onAllNodesWithText(peText)[0].performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(Right.PE, picks.last().right)
    }

    // ---- The P&L calendar ------------------------------------------------------------------------

    @Test fun theCalendarSwitchesAccountMonthAndViewAndExports() {
        show { PnlCalendarScreen(model) }
        compose.waitForText("Paper")
        val month = java.time.YearMonth.from(Market.today())
        fun label(m: java.time.YearMonth) = "${m.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)} ${m.year}"
        compose.onNodeWithText(label(month)).assertExists()
        compose.onNodeWithText("›").assertIsNotEnabled()
        compose.onNodeWithText("‹").assertIsEnabled().performClick()
        compose.waitForText(label(month.minusMonths(1)))
        compose.onNodeWithText("›").assertIsEnabled().performClick()
        compose.waitForText(label(month))
        tap("Zerodha")
        compose.waitForIdle()
        tap("Year")
        compose.waitForIdle()
        tap("Month")
        val out = picker.writable("pnl.csv")
        picker.answer = { out }
        tap("Export CSV")
        assertEquals("iraalgo-pnl-zerodha-${month.year}.csv", picker.launched.single())
        compose.waitUntil(10_000) { AreaE.alerted("Exported 0 days.") }
        assertEquals("date,account,strategy,pnl,trades\n", String(picker.written(out)!!))
    }

    // ---- The alert banner -------------------------------------------------------------------------

    @Test fun alertsShowAtTheTopTheLatestThreeAndSwipeAway() {
        show { AlertBanner() }
        Alerts.success("Paper BUY 75 NIFTY filled (test)")
        compose.waitForText("Paper BUY 75 NIFTY filled (test)")
        Alerts.error("Order refused (test)", title = "Kite")
        compose.waitForText("Kite")
        compose.waitForText("Order refused (test)")
        Alerts.post("third (test)"); Alerts.post("fourth (test)")
        compose.waitForText("fourth (test)")
        assertFalse("only the latest three show", compose.has("Paper BUY 75 NIFTY filled (test)"))
        compose.onNodeWithText("fourth (test)").performTouchInput { swipe(center, Offset(centerX - 600f, centerY), 300L) }
        compose.waitUntil(10_000) { Alerts.queue.value.none { it.text == "fourth (test)" } }
    }

    @Test fun wordsDecideTheColourWhenTheCallerDoesNot() {
        assertEquals(Alerts.Kind.ERROR, Alerts.classify("Order not placed: RMS rejected"))
        assertEquals(Alerts.Kind.ERROR, Alerts.classify("Could not read the account"))
        assertEquals(Alerts.Kind.SUCCESS, Alerts.classify("Paper order placed"))
    }

    @Test fun aDialogShowsTheAlertsRaisedWhileItIsOpenAndAlertOnPostsOnce() {
        show {
            AlertDialog(onDismissRequest = {}, confirmButton = {}, title = { androidx.compose.material3.Text("A dialog (test)") },
                text = { AlertOn("Level must be a number (test)") })
        }
        compose.waitForText("A dialog (test)")
        compose.waitForText("Level must be a number (test)")
        assertEquals(1, Alerts.queue.value.count { it.text == "Level must be a number (test)" })
        Alerts.post("Level must be a number (test)", throttle = true)
        assertEquals("a repeat within 20 s is throttled", 1, Alerts.queue.value.count { it.text == "Level must be a number (test)" })
    }

    // ---- Slide to confirm --------------------------------------------------------------------------

    @Test fun onlyAFullSlideConfirmsAndTalkBackHasAnAction() {
        var confirms = 0
        show { Column { SwipeToConfirm("Slide to close (test)", Color(0xFF8B2E2E)) { confirms++ } } }
        val node = compose.onNodeWithText("Slide to close (test)  ›››", substring = false, useUnmergedTree = false)
        node.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        // A tap does nothing.
        node.performTouchInput { click(Offset(30f * density, centerY)) }
        compose.waitForIdle()
        assertEquals(0, confirms)
        // Half way, let go: it springs back.
        node.performTouchInput { swipe(Offset(30f * density, centerY), Offset(width * 0.45f, centerY), 400L) }
        compose.waitForIdle()
        assertEquals(0, confirms)
        // All the way across.
        node.performTouchInput { swipe(Offset(30f * density, centerY), Offset(width - 2f, centerY), 500L) }
        compose.waitUntil(5_000) { confirms == 1 }
        // Screen readers cannot drag: the same confirmation is a click action with the label.
        assertEquals("Slide to close (test)", node.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        node.performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(2, confirms)
    }
}
