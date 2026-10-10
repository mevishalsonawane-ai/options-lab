package com.optionslab.app.ui

import android.Manifest
import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.IraAlgoApp
import com.optionslab.app.MainActivity
import com.optionslab.app.data.Alarms
import com.optionslab.app.data.Ledger
import com.optionslab.app.data.Paper
import com.optionslab.app.data.PriceAlarm
import com.optionslab.app.security.Capture
import com.optionslab.app.security.PinLock
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.security.SessionLock
import com.optionslab.app.security.Vault
import com.optionslab.app.testing.AreaE
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.has
import com.optionslab.app.testing.waitForNoText
import com.optionslab.app.testing.waitForText
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.engine.Live
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The app's frame (ui/Root.kt): every tab, Back (including a More page left open behind another
 * tab), Home shortcuts, the first-use guide, deep links from the app's own notifications (and only
 * those: the per-install nonce), the PAPER / LIVE badge and its confirmation, the crash report,
 * the sealed-vault screen and erasing everything. The navigation rules are [NavState]; the
 * composables are driven with plain state, never a full [AppModel].
 */
@RunWith(AndroidJUnit4::class)
class RootNavigationTest {
    @get:Rule val watchdog = com.optionslab.app.testing.AreaEWatchdog()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before fun up() = AreaE.resetGlobals()
    @After fun down() {
        AreaE.resetGlobals()
        assertEquals("no test may reach the network", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    // ---- NavState: the rules -------------------------------------------------------------------

    @Test fun everyTabIsReachedAndBackFromItGoesHome() {
        val home = NavState()
        assertEquals(Tab.ALMANAC, home.tab)
        assertFalse("Back at Home is the system's (the app closes)", home.backEnabled)
        for (t in Tab.entries) {
            val at = home.pick(t)
            assertEquals(t, at.tab)
            if (t == Tab.ALMANAC) continue
            assertTrue(at.backEnabled)
            assertEquals(Tab.ALMANAC, at.back().tab)
            assertFalse(at.back().backEnabled)
        }
        assertEquals(listOf("Home", "Chart", "Trade", "P&L", "Options", "Research", "More"), Tab.entries.map { it.label })
    }

    @Test fun backFromAMorePageGoesToTheMoreListThenHome() {
        val page = NavState().home("security")
        assertEquals(Tab.CABINET, page.tab); assertEquals("security", page.cabinetPage)
        val list = page.back()
        assertEquals(Tab.CABINET, list.tab); assertNull(list.cabinetPage)
        assertEquals(NavState(), list.back())
    }

    @Test fun aMorePageLeftOpenBehindAnotherTabIsNotWhatBackCloses() {
        val page = NavState().home("risk")
        // Switch tab with the page still open: More remembers it...
        val trade = page.pick(Tab.TRADE)
        assertEquals(Tab.TRADE, trade.tab)
        assertEquals("risk", trade.cabinetPage)
        assertEquals("risk", trade.pick(Tab.CABINET).cabinetPage)
        // ...but Back from the other tab goes Home and forgets it, so More opens on its list again.
        val back = trade.back()
        assertEquals(Tab.ALMANAC, back.tab)
        assertNull(back.cabinetPage)
        assertNull(back.pick(Tab.CABINET).cabinetPage)
    }

    @Test fun tappingMoreWhileOnMoreReturnsToItsList() {
        val page = NavState().home("data")
        assertNull(page.pick(Tab.CABINET).cabinetPage)
        assertEquals(Tab.CABINET, page.pick(Tab.CABINET).tab)
        // Any other tab tapped twice keeps its own page.
        val lab = NavState().request("pine")
        assertEquals("pine", lab.pick(Tab.LAB).labPage)
    }

    @Test fun deepLinksOpenTheirPage() {
        val expect = mapOf(
            "almanac" to NavState(tab = Tab.ALMANAC),
            "ticket" to NavState(tab = Tab.TOOLS, toolsView = "expiryput"),
            "chart" to NavState(tab = Tab.CHART),
            "trade" to NavState(tab = Tab.TRADE),
            // The saved strategies are a Research page (moved from Trade, 9 Oct).
            "strategy" to NavState(tab = Tab.LAB, labPage = "strategies"),
            "health" to NavState(tab = Tab.LAB, labPage = "health"),
            "trials" to NavState(tab = Tab.LAB, labPage = "trials"),
            "pine" to NavState(tab = Tab.LAB, labPage = "pine"),
            "tools" to NavState(tab = Tab.TOOLS),
            "pnl" to NavState(tab = Tab.PNL),
            "cabinet" to NavState(tab = Tab.CABINET, cabinetPage = "data"),
            "alarms" to NavState(tab = Tab.CABINET, cabinetPage = "alarms"),
            "broker" to NavState(tab = Tab.CABINET, cabinetPage = "broker"),
        )
        for ((dest, want) in expect) assertEquals(dest, want, NavState().request(dest))
        // From elsewhere a link replaces only what it names.
        val from = NavState(tab = Tab.TRADE, labPage = "pine")
        assertEquals(from.copy(tab = Tab.LAB, labPage = "health"), from.request("health"))
        // Unknown, blank or absent: nothing moves.
        for (junk in listOf(null, "", "settings", "../../etc", "ALMANAC")) assertEquals(from, from.request(junk))
    }

    @Test fun homeShortcutsAndTheFirstUseGuide() {
        val h = NavState()
        assertEquals(NavState(tab = Tab.LAB, labPage = "trials"), h.home("trials"))
        assertEquals(NavState(tab = Tab.TRADE), h.home("trade"))
        assertEquals(NavState(tab = Tab.LAB, labPage = "strategies"), h.home("strategy"))
        assertEquals(NavState(tab = Tab.TOOLS, toolsView = "expiryput"), h.home("ticket"))
        assertEquals(NavState(tab = Tab.CHART), h.home("chart"))
        assertEquals(NavState(tab = Tab.LAB, labPage = "health"), h.home("health"))
        // "Today at a glance": its live-vs-backtest lines open the P&L tab; Solo opens Jarvis settings, Jarvis's trades the Ira page.
        assertEquals(NavState(tab = Tab.PNL), h.home("pnl"))
        // "What's new": the Pine change opens the Pine scripts page; its full list is a Settings page.
        assertEquals(NavState(tab = Tab.LAB, labPage = "pine"), h.home("pine"))
        assertEquals(NavState(tab = Tab.CABINET, cabinetPage = "whatsnew"), h.home("whatsnew"))
        for (page in listOf("alarms", "broker", "risk", "security", "schedule", "data", "jarvis", "ira")) assertEquals(NavState(tab = Tab.CABINET, cabinetPage = page), h.home(page))
        val away = NavState(tab = Tab.PNL)
        assertEquals(Tab.ALMANAC, away.tour("orb").tab)
        assertEquals(Tab.CHART, away.tour("chart").tab)
        assertEquals(NavState(tab = Tab.TRADE), away.tour("trade"))
        assertEquals("Skip closes the guide where it is", away, away.tour(""))
    }

    // ---- The tab bar and the system Back button --------------------------------------------------

    @Test fun theTabBarAndSystemBackMoveBetweenTabsAndMorePages() {
        val nav = mutableStateOf(NavState())
        compose.setContent {
            IraAlgoTheme("light") {
                Column {
                    Text("at ${nav.value.tab.name} ${nav.value.cabinetPage ?: "-"}")
                    NavBack(nav.value) { nav.value = it }
                    TabBar(nav.value.tab, Tab.entries) { nav.value = nav.value.pick(it) }
                }
            }
        }
        fun back() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Home").assertIsSelected()
        for (t in Tab.entries.drop(1)) {
            compose.onNodeWithText(t.label).performClick()
            compose.onNodeWithText("at ${t.name} -").assertExists()
            compose.onNodeWithText(t.label).assertIsSelected()
            compose.onNodeWithText("Home").assertIsNotSelected()
        }
        // Every tab carries the Tab role, for TalkBack.
        compose.onNodeWithText("P&L").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
        // A More page (opened from its list), then Back: the list; Back again: Home.
        compose.runOnUiThread { nav.value = nav.value.home("security") }
        compose.onNodeWithText("at CABINET security").assertExists()
        back(); compose.onNodeWithText("at CABINET -").assertExists()
        back(); compose.onNodeWithText("at ALMANAC -").assertExists()
        // A More page, then another tab, then Back: Home, and More opens on its list.
        compose.runOnUiThread { nav.value = nav.value.home("risk") }
        compose.onNodeWithText("Trade").performClick()
        compose.onNodeWithText("at TRADE risk").assertExists()
        back(); compose.onNodeWithText("at ALMANAC -").assertExists()
        compose.onNodeWithText("More").performClick()
        compose.onNodeWithText("at CABINET -").assertExists()
        // Tapping More on More closes the page it shows.
        compose.runOnUiThread { nav.value = nav.value.home("data") }
        compose.onNodeWithText("More").performClick()
        compose.onNodeWithText("at CABINET -").assertExists()
        // At Home the app does not take Back: it is left to the system (which leaves the app).
        assertTrue(compose.activity.onBackPressedDispatcher.hasEnabledCallbacks())
        back(); back()
        compose.onNodeWithText("at ALMANAC -").assertExists()
        compose.waitForIdle()
        assertFalse("Back at Home must not be swallowed", compose.activity.onBackPressedDispatcher.hasEnabledCallbacks())
    }

    // ---- The PAPER / LIVE badge ------------------------------------------------------------------

    @Test fun goingLiveAsksFirstAndCanBeDeclined() {
        val calls = mutableListOf<Boolean>()
        var linkAsked = 0
        compose.setContent { IraAlgoTheme("light") { Masthead(live = false, calm = true, linked = true, onMode = { calls += it }, onLink = { linkAsked++ }) } }
        compose.onNodeWithText("PAPER TRADING").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
        assertTrue(compose.has("Market open") || compose.has("Market closed"))
        compose.onNodeWithText("PAPER TRADING").performClick()
        compose.waitForText("Switch to live trading?")
        compose.onNodeWithText("Orders you send will use real money", substring = true).assertExists()
        compose.onNodeWithText("Stay on paper").performClick()
        compose.waitForNoText("Switch to live trading?")
        assertEquals(emptyList<Boolean>(), calls)
        compose.onNodeWithText("PAPER TRADING").performClick()
        compose.onNodeWithText("Go live").performClick()
        compose.waitForNoText("Switch to live trading?")
        assertEquals(listOf(true), calls)
        assertEquals(0, linkAsked)
    }

    @Test fun theBadgeFollowsTheModeAndLeavingLiveNeedsNoConfirmation() {
        val live = mutableStateOf(true)
        val calls = mutableListOf<Boolean>()
        compose.setContent { IraAlgoTheme("dark") { Masthead(live = live.value, calm = false, linked = true, onMode = { calls += it; live.value = it }) } }
        compose.onNodeWithText("LIVE TRADING").assertIsSelected()
        compose.onNodeWithText("LIVE TRADING").performClick()
        assertEquals(listOf(false), calls)
        compose.onNodeWithText("PAPER TRADING").assertIsNotSelected()
        assertFalse(compose.has("Switch to live trading?"))
        compose.onNodeWithText("PAPER TRADING").performClick()
        compose.onNodeWithText("Go live").performClick()
        compose.onNodeWithText("LIVE TRADING").assertExists()
        assertEquals(listOf(false, true), calls)
    }

    @Test fun liveNeedsZerodhaLinkedFirst() {
        val calls = mutableListOf<Boolean>()
        var linkAsked = 0
        compose.setContent { IraAlgoTheme("light") { Masthead(live = false, calm = true, linked = false, onMode = { calls += it }, onLink = { linkAsked++ }) } }
        compose.onNodeWithText("PAPER TRADING").performClick()
        compose.waitForText("Link Zerodha for live trading")
        compose.onNodeWithText("Stay on paper").performClick()
        compose.waitForNoText("Link Zerodha for live trading")
        assertEquals(0, linkAsked)
        compose.onNodeWithText("PAPER TRADING").performClick()
        compose.onNodeWithText("Link Zerodha").performClick()
        assertEquals(1, linkAsked)
        assertEquals("never live without a linked account", emptyList<Boolean>(), calls)
    }

    // ---- Screenshots policy (every sensitive dialog follows it) ----------------------------------

    @Test fun capturePolicyFollowsTheOwnersSwitch() {
        assertFalse("blocked until the owner allows it", Capture.allowed)
        assertEquals(androidx.compose.ui.window.SecureFlagPolicy.SecureOn, Capture.policy)
        val activity = compose.activity
        compose.runOnUiThread { Capture.apply(activity) }
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        compose.runOnUiThread { Capture.set(activity, true) }
        assertTrue(Capture.allowed)
        assertEquals(androidx.compose.ui.window.SecureFlagPolicy.SecureOff, Capture.policy)
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0)
        compose.runOnUiThread { Capture.set(activity, false) }
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
    }

    // ---- The crash report -------------------------------------------------------------------------

    private fun crashFile() = File(app.filesDir, IraAlgoApp.CRASH_FILE)

    @Test fun aSealedCrashReportIsShownCopiedAndDismissedOnce() {
        val report = IraAlgoApp.crashReport(Thread.currentThread(),
            IllegalStateException("kite-token-not-real", IllegalArgumentException("secret detail not real")))
        assertFalse("messages are never kept", "kite-token-not-real" in report || "secret detail" in report)
        Vault.writeFile(crashFile(), report.toByteArray())
        assertFalse("sealed on disk", String(crashFile().readBytes(), Charsets.ISO_8859_1).contains("IllegalStateException"))
        compose.setContent { IraAlgoTheme("light") { CrashReport(app.filesDir) } }
        compose.waitForText("IraAlgo closed unexpectedly last time")
        compose.onNodeWithText("java.lang.IllegalStateException", substring = true).assertExists()
        compose.onNodeWithText("Caused by: java.lang.IllegalArgumentException", substring = true).assertExists()
        compose.onNodeWithText("Copy").performClick()
        val clip = (app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        assertEquals(report, clip?.getItemAt(0)?.text?.toString())
        compose.onNodeWithText("Dismiss").performClick()
        compose.waitForNoText("IraAlgo closed unexpectedly last time")
        assertFalse("shown once: the report is deleted", crashFile().exists())
    }

    @Test fun noReportOrAnUnsealedOneShowsNothing() {
        crashFile().writeText("java.lang.RuntimeException planted in the clear")
        compose.setContent { IraAlgoTheme("light") { CrashReport(app.filesDir) } }
        compose.waitForIdle()
        assertFalse(compose.has("IraAlgo closed unexpectedly last time"))
        assertFalse(compose.has("planted", substring = true))
    }

    // ---- The sealed vault ------------------------------------------------------------------------

    @Test fun anUnreadableVaultOffersRetryAndAnEraseThatNeedsTwoTaps() {
        var retries = 0
        var erases = 0
        compose.setContent { IraAlgoTheme("light") { VaultUnreadable(onRetry = { retries++ }, onErase = { erases++ }) } }
        compose.onNodeWithText("THE VAULT IS SEALED SHUT").assertExists()
        compose.onNodeWithText("Try again").performClick()
        assertEquals(1, retries)
        compose.onNodeWithText("Erase everything and start again").performClick()
        assertEquals("one tap never erases", 0, erases)
        compose.onNodeWithText("Tap again: erase ALL IraAlgo data").performClick()
        assertEquals(1, erases)
    }

    // ---- Erase everything --------------------------------------------------------------------------

    private val ticket = Live.Ticket(LocalDate.of(2026, 1, 6), "NIFTY", LocalDate.of(2026, 1, 6), "SELL", "PE", 24_000.0, 75, 1, 75,
        20.0, 24_300.0, 23_980.0, 90_000.0, null, null, null)

    private fun seedPersonalData() {
        PinLock.setPin("246813".toCharArray())
        SecurePrefs.putAll(mapOf("s.capital" to 5.0, "kite.apiKey" to "test-key-not-real"))
        Ledger.record(ticket, null, null)
        Alarms.upsert(PriceAlarm(1, "NIFTY", above = false, level = 24_000.0))
        Paper.reset(BigDecimal("123456"))
        SessionLock.unlock()
    }

    private fun assertErased(before: Int) {
        assertFalse(PinLock.isSet)
        assertTrue(SessionLock.locked.value)
        assertEquals(before + 1, wipes.value)
        assertTrue(SecurePrefs.snapshot().isEmpty())
        assertTrue(Ledger.all().isEmpty())
        assertTrue(Alarms.all().isEmpty())
        assertFalse(File(app.noBackupFilesDir, "ledger.vault").exists())
        assertFalse(File(app.noBackupFilesDir, "alarms.vault").exists())
        assertFalse(File(app.filesDir, "paper.vault").exists())
        assertTrue("a fresh paper account", Paper.capital.compareTo(BigDecimal("123456")) != 0)
    }

    @Test fun eraseEverythingDestroysTheVaultAndSealsTheApp() {
        seedPersonalData()
        val before = wipes.value
        eraseEverything()
        shadowOf(Looper.getMainLooper()).idle()
        assertErased(before)
    }

    @Test fun eraseEverythingFromABackgroundThread() {
        seedPersonalData()
        val before = wipes.value
        var failure: Throwable? = null
        val t = Thread { try { eraseEverything() } catch (e: Throwable) { failure = e } }
        t.start(); t.join(20_000)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(failure)
        assertErased(before)
    }

    // ---- Deep links: only the app's own intents navigate ------------------------------------------

    private fun activityWith(i: Intent) = Robolectric.buildActivity(MainActivity::class.java, i)

    @Test fun onlyIntentsCarryingThisInstallsNonceNavigate() {
        shadowOf(app).grantPermissions(Manifest.permission.HIDE_OVERLAY_WINDOWS)
        val nonce = MainActivity.nonce()
        assertEquals("stable for this install", nonce, MainActivity.nonce())
        assertTrue(nonce.length >= 32)
        fun intent(n: String?) = Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, "pnl")
            .putExtra(MainActivity.EXTRA_CLOSE, "NIFTY26OCT24500PE").apply { if (n != null) putExtra(MainActivity.EXTRA_NONCE, n) }

        // Another app's launch intent (no nonce, or a guessed one) is ignored.
        for (bad in listOf(null, "", "00000000-0000-0000-0000-000000000000")) {
            val c = activityWith(intent(bad)).create()
            assertNull("nonce '$bad'", MainActivity.tabRequests.value)
            assertNull(MainActivity.closeRequests.value)
            c.destroy()
        }
        // The app's own notification carries it.
        val c = activityWith(intent(nonce)).create()
        assertEquals("pnl", MainActivity.tabRequests.value)
        assertEquals("NIFTY26OCT24500PE", MainActivity.closeRequests.value)
        MainActivity.tabRequests.value = null; MainActivity.closeRequests.value = null
        // A later intent to the running app: the same rule.
        c.newIntent(Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, "alarms"))
        assertNull(MainActivity.tabRequests.value)
        c.newIntent(Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, "alarms").putExtra(MainActivity.EXTRA_NONCE, nonce))
        assertEquals("alarms", MainActivity.tabRequests.value)
        assertNull("no close asked", MainActivity.closeRequests.value)
        c.destroy()
        // Where each link lands.
        assertEquals(NavState(tab = Tab.CABINET, cabinetPage = "alarms"), NavState().request(MainActivity.tabRequests.value))
    }
}
