package com.optionslab.app.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.optionslab.app.IraAlgoApp
import com.optionslab.app.data.Ledger
import com.optionslab.app.data.Market
import com.optionslab.app.security.PinLock
import com.optionslab.app.security.Vault
import com.optionslab.app.testing.AreaE
import com.optionslab.app.testing.DeviceConfig
import com.optionslab.app.testing.OfflineModel
import com.optionslab.app.testing.ScreenTest
import com.optionslab.app.ui.CrashReport
import com.optionslab.app.ui.Masthead
import com.optionslab.app.ui.Tab
import com.optionslab.app.ui.TabBar
import com.optionslab.app.ui.VaultUnreadable
import com.optionslab.app.ui.components.AlertBanner
import com.optionslab.app.ui.components.SwipeToConfirm
import com.optionslab.app.work.Alerts
import com.optionslab.engine.Live
import com.optionslab.engine.options.ChainRow
import com.optionslab.engine.options.ChainSnapshot
import com.optionslab.engine.options.OptLeg
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.round

/**
 * The frame (masthead, tab bar, crash report, sealed vault), every More control page and its
 * dialogs, the first-use guide, the battery gate, the Expiry Put page, the chain cards, the P&L
 * calendar, the lock-out state and the banner / slide control, on every device set-up:
 * a screenshot each, [com.optionslab.app.testing.LayoutLint], and click smoke where it is safe
 * (pages whose buttons fetch - Refresh from NSE, Harvest now, Price the live chain - are not smoked).
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ControlScreensLayoutTest(device: DeviceConfig) : ScreenTest(device) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun configs(): List<Array<Any>> = DeviceConfig.matrix()

        /** Dialogs and other secondary states run on these six set-ups only (the main pages on all 24), to keep CI short. */
        val SIX = setOf("small-font1.0-light", "small-font2.0-dark", "phone-font1.3-light", "landscape-font1.0-dark",
            "landscape-font2.0-light", "tablet-font1.3-dark")

        /** The lock screen's own alert is drawn over the top on purpose. */
        val LOCK_OVERLAYS = com.optionslab.app.testing.LayoutLint.Options(overlays = setOf("Too many attempts. Try again in 30 s."))
    }

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private var offline: OfflineModel? = null
    private fun model() = (offline ?: OfflineModel(app).also { offline = it }).model

    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AreaEWatchdog()

    @Before fun clean() = AreaE.resetGlobals()
    @After fun closeModel() { offline?.close(); AreaE.resetGlobals() }

    /** False (and the test passes at once) outside [SIX], for secondary states. */
    private fun onSix() = device.name in SIX

    /** The click smoke is about behaviour, not layout: one set-up is enough. */
    private fun smokeOnce() { if (device.name == "phone-font1.0-light") smokeEveryAction() }

    private fun tap(text: String) = compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.OnClick)
    private fun reveal(text: String) {
        compose.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToNode(hasText(text))
        frames()
        compose.waitForIdle()
    }

    private fun frames(n: Int = 12) { if (!compose.mainClock.autoAdvance) repeat(n) { compose.mainClock.advanceTimeByFrame() } }

    private fun frame(live: Boolean, linked: Boolean) = @Composable {
        Column(Modifier.fillMaxSize()) {
            Masthead(live = live, calm = true, linked = linked, onMode = {})
            Box(Modifier.weight(1f))
            TabBar(Tab.ALMANAC, Tab.entries) {}
        }
    }

    // ---- The frame ----------------------------------------------------------------------------

    @Test fun framePaper() {
        checkScreen("frame-paper", content = frame(live = false, linked = true))
        smokeOnce()
    }

    @Test fun frameLive() {
        if (!onSix()) return
        checkScreen("frame-live", content = frame(live = true, linked = true))
    }

    @Test fun frameGoLiveDialog() {
        if (!onSix()) return
        show(frame(live = false, linked = true))
        tap("PAPER TRADING")
        compose.waitForIdle()
        capture("frame-go-live"); lint("frame-go-live")
    }

    @Test fun frameLinkZerodhaDialog() {
        if (!onSix()) return
        show(frame(live = false, linked = false))
        tap("PAPER TRADING")
        compose.waitForIdle()
        capture("frame-link-zerodha"); lint("frame-link-zerodha")
    }

    @Test fun crashReport() {
        if (!onSix()) return
        Vault.writeFile(File(app.filesDir, IraAlgoApp.CRASH_FILE),
            IraAlgoApp.crashReport(Thread.currentThread(), IllegalStateException("not shown", RuntimeException("not shown"))).toByteArray())
        checkScreen("crash-report") { CrashReport(app.filesDir) }
    }

    @Test fun vaultUnreadable() {
        checkScreen("vault-unreadable") { VaultUnreadable(onRetry = {}, onErase = {}) }
        smokeOnce()
    }

    // ---- More ------------------------------------------------------------------------------------

    @Test fun moreList() = checkScreen("more-list") { CabinetScreen(model(), null) {} }

    @Test fun securityPage() = checkScreen("security") { SecurityPage(model()) }

    @Test fun securityChangePin() {
        if (!onSix()) return
        compose.mainClock.autoAdvance = false   // a dialog with a text field never idles on a running clock
        show { SecurityPage(model()) }
        frames()
        reveal("Change PIN"); tap("Change PIN")
        frames()
        capture("security-change-pin"); lint("security-change-pin")
    }

    @Test fun securityBackupSeal() {
        if (!onSix()) return
        PinLock.setPin("246813".toCharArray())
        compose.mainClock.autoAdvance = false   // a dialog with a text field never idles on a running clock
        show { SecurityPage(model()) }
        frames()
        reveal("Back up now"); tap("Back up now")
        frames()
        compose.onNodeWithText("Backup passphrase").performTextReplacement("correct horse")
        compose.onNodeWithText("Passphrase again").performTextReplacement("correct horsx")
        frames()
        capture("security-backup-seal"); lint("security-backup-seal")
    }

    @Test fun securityErase() {
        if (!onSix()) return
        show { SecurityPage(model()) }
        reveal("Erase everything personal"); tap("Erase everything personal")
        compose.waitForIdle()
        capture("security-erase"); lint("security-erase")
    }

    @Test fun botSettings() = checkScreen("bot-settings") { RiskPage(model()) }

    @Test fun botKillSwitchDialog() {
        if (!onSix()) return
        show { RiskPage(model()) }
        compose.waitForIdle()
        val sw = compose.onAllNodes(androidx.compose.ui.test.isToggleable()).onFirst()
        sw.performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        capture("bot-kill-switch"); lint("bot-kill-switch")
    }

    @Test fun dataPage() = checkScreen("data") { DataPage(model()) }

    @Test fun alarmsPage() = checkScreen("alarms") { AlarmsPage(model()) }

    @Test fun schedulePage() = checkScreen("schedule") { SchedulePage(model()) }

    // ---- Other screens ------------------------------------------------------------------------------

    @Test fun gettingStarted() {
        checkScreen("getting-started") { GettingStarted {} }
        smokeOnce()
    }

    @Test fun batteryGate() = checkScreen("battery") { BatteryScreen {} }

    @Test fun expiryPut() {
        val day = Market.today()
        Ledger.record(Live.Ticket(day, "NIFTY", day, "SELL", "PE", 24_000.0, 75, 1, 75, 20.0, 24_300.0, 23_980.0, 90_000.0, null, null, null), null, null)
        val m = model()
        m.refreshLedger()
        checkScreen("expiry-put") { ToolsScreen(m, "expiryput", {}) { _, _ -> } }
    }

    @Test fun chainCards() {
        val spot = 24_512.0
        val expiry = LocalDate.of(2026, 10, 27)
        fun r2(x: Double) = round(x * 100) / 100
        val rows = (0..20).map { i ->
            val k = 24_000.0 + i * 50
            val tv = 90 * exp(-((k - spot) / 450).let { it * it }) + 3
            ChainRow(k, OptLeg("CE$i", r2(max(0.0, spot - k) + tv), oi = 100_000L + i * 7_000L, prevOi = 90_000L, lotSize = 75),
                OptLeg("PE$i", r2(max(0.0, k - spot) + tv), oi = 240_000L - i * 6_000L, prevOi = 250_000L, lotSize = 75))
        }
        val c = ChainSnapshot.of("NIFTY", expiry, spot, 75, rows, ZonedDateTime.of(expiry.minusDays(4), LocalTime.of(11, 0), com.optionslab.engine.IST))
        checkScreen("chain-cards") {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Header(c, "Upstox public candles (test)") {}
                ChainCard(c) {}
                OiCard(c); IvCard(c); GexCard(c); MoveCard(c)
            }
        }
    }

    @Test fun pnlCalendar() = checkScreen("pnl-calendar") { PnlCalendarScreen(model()) }

    @Test fun lockedOut() {
        if (!onSix()) return
        PinLock.setPin("246813".toCharArray())
        repeat(5) { PinLock.verify("000000".toCharArray(), false) }
        checkScreen("lock-locked-out", options = LOCK_OVERLAYS) {
            LockScreen(setup = false, biometricLabel = "Use fingerprint", onBiometric = {}, onPin = { PinLock.Result.Ok }, onCreate = { null }, notice = null, calm = true)
        }
    }

    @Test fun bannerAndSlideToConfirm() {
        if (!onSix()) return
        Alerts.success("Paper BUY 75 NIFTY26OCT24500PE filled @ 120.00 (test)")
        checkScreen("banner-slide", options = com.optionslab.app.testing.LayoutLint.Options(
            overlays = setOf("Paper BUY 75 NIFTY26OCT24500PE filled @ 120.00 (test)"))) {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) { Box(Modifier.weight(1f)); SwipeToConfirm("Slide to close", Color(0xFF8B2E2E)) {} }
                AlertBanner()
            }
        }
        smokeOnce()
    }
}
