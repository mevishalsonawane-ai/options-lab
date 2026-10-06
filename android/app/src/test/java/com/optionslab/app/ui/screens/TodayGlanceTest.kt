package com.optionslab.app.ui.screens

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.data.GlanceSource
import com.optionslab.app.testing.DeviceConfig
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.ScreenTest
import com.optionslab.app.ui.Load
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.ira.BigMoveRisk
import com.optionslab.ira.Events
import com.optionslab.ira.ForwardCheck
import com.optionslab.ira.Market as IraMarket
import com.optionslab.ira.TodayGlance
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicInteger

/** The "Today at a glance" card's fixtures: one Tuesday before the open, in the session and a closed Saturday. */
internal object GlanceFixtures {
    private val day: LocalDate = LocalDate.of(2026, 10, 6)
    private val base = TodayGlance.Facts(
        now = day.atTime(8, 50), tradingToday = true, expiriesToday = listOf("NIFTY"),
        holidays = listOf(LocalDate.of(2026, 10, 9) to "Test holiday"),
        gift = "GIFT Nifty points to a gap-up of ~100 pts (+0.40%), read at 07:45 today.",
        fii = "FIIs are net short index futures, 30% long (NSE participant OI, 5 Oct).",
        risks = listOf(
            BigMoveRisk.Read(IraMarket.NIFTY, BigMoveRisk.Level.HIGH, 2.5, listOf("x"), asOf = day.atTime(11, 4)),
            BigMoveRisk.Read(IraMarket.BANKNIFTY, BigMoveRisk.Level.LOW, 0.4, emptyList(), asOf = day.atTime(11, 4)),
        ),
        liquidity = TodayGlance.Liquidity(true, 2, 1, 1_240.0, TodayGlance.Open("BANKNIFTY26OCT52000PE", 210.5, 180.0)),
        solo = TodayGlance.Solo(true, null, 12), hero = TodayGlance.Hero(true, true), jarvisOpen = 1,
        forward = listOf(ForwardCheck.check(ForwardCheck.LIQUIDITY, emptyList()), ForwardCheck.check(ForwardCheck.SOLO, emptyList())),
        events = listOf(Events.Event(day, "NIFTY expiry"), Events.Event(day.plusDays(1), "US Fed decision overnight (FOMC)")),
    )
    val preOpen: TodayGlance.Card = TodayGlance.card(base)
    val inSession: TodayGlance.Card = TodayGlance.card(base.copy(now = day.atTime(11, 5)))
    val closed: TodayGlance.Card = TodayGlance.card(base.copy(now = LocalDateTime.of(2026, 10, 10, 11, 0), tradingToday = false,
        nextSession = LocalDate.of(2026, 10, 12), events = emptyList()))
}

@RunWith(AndroidJUnit4::class)
@org.robolectric.annotation.Config(qualifiers = "w411dp-h2400dp")   // the whole open card on one screen
class TodayGlanceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule(order = 100) val dump = com.optionslab.app.testing.DumpOnFailure(compose)
    @get:Rule val watchdog = com.optionslab.app.testing.ResearchWatchdog()

    @Before fun fresh() { GlanceSource.forget() }
    @After fun noNetwork() { assertEquals("no host may be reached", emptyList<String>(), NetworkGuard.blocked.toList()) }

    private fun set(content: @Composable () -> Unit) = compose.setContent { IraAlgoTheme("light") { content() } }
    private fun text(t: String, sub: Boolean = false) = compose.onNodeWithText(t, substring = sub)
    private fun shows(t: String, sub: Boolean = true) = compose.onAllNodesWithText(t, substring = sub).fetchSemanticsNodes().isNotEmpty()

    private fun card(c: TodayGlance.Card?, expanded: Boolean = true): ArrayList<String> {
        val went = ArrayList<String>()
        set { TodayGlanceContent(c, expanded, onToggle = {}, onGo = { went += it }) }
        return went
    }

    @Test fun preOpenShowsTheCuesNotTheRisk() {
        card(GlanceFixtures.preOpen)
        text("Today at a glance").assertIsDisplayed()
        text("Pre-open · opens at 09:15 (in 25m)").assertIsDisplayed()
        text("Expiry today: NIFTY").assertIsDisplayed()
        text("Holiday Fri 9 Oct: Test holiday - market shut").assertIsDisplayed()
        text("Before the open").assertIsDisplayed()
        text(GlanceFixtures.preOpen.cues[0]).assertIsDisplayed()
        text(GlanceFixtures.preOpen.cues[1]).assertIsDisplayed()
        assertFalse(shows("Big-move risk"))
        text("Decides at 12:00").assertIsDisplayed()
        text("Today: NIFTY expiry").assertIsDisplayed()
        text("Tomorrow: US Fed decision overnight (FOMC)").assertIsDisplayed()
        text("Updated 08:50").assertIsDisplayed()
    }

    @Test fun inSessionShowsTheRiskBadgesAndTheirNoteOnTap() {
        card(GlanceFixtures.inSession)
        text("Market open · closes at 15:30 (4h 25m left)").assertIsDisplayed()
        assertFalse(shows("Before the open"))
        text("Nifty: high · 2.5x").assertIsDisplayed()
        text("BankNifty: low · 0.4x").assertIsDisplayed()
        assertFalse(shows("direction can't be told"))
        text("Big-move risk now ⓘ").performClick()
        compose.waitForIdle()
        text(TodayGlance.RISK_NOTE).assertIsDisplayed()
        text("Big-move risk now ⓘ").performClick()
        compose.waitForIdle()
        assertFalse(shows("direction can't be told"))
        // The strategies, as worded.
        text("armed · 2 lots").assertIsDisplayed()
        text("Today: 1 trade, +₹1,240").assertIsDisplayed()
        text("Open BANKNIFTY26OCT52000PE · bought 210.50 · stop 180.00").assertIsDisplayed()
        text("on · 12 of 60").assertIsDisplayed()
        text("No trade today").assertIsDisplayed()
        text("NIFTY expiry today · may trade 13:30-14:45").assertIsDisplayed()
        text("1 open").assertIsDisplayed()
        text("Liquidity 15+5: too few trades (<20) · 0 so far").assertIsDisplayed()
        text("Solo: too few trades (<20) · 0 so far").assertIsDisplayed()
    }

    @Test fun closedDayShowsTheNextSessionOnly() {
        card(GlanceFixtures.closed)
        text("Market closed · next session Mon 12 Oct, 09:15").assertIsDisplayed()
        assertFalse(shows("Before the open"))
        assertFalse(shows("Big-move risk"))
        assertFalse(shows("Expiry today"))
        text(TodayGlance.NO_EVENTS).assertIsDisplayed()
    }

    @Test fun eachRowOpensItsOwnPage() {
        val went = card(GlanceFixtures.inSession)
        text("Liquidity 15+5 ›").performClick()
        text("Solo (midday) ›").performClick()
        text("Hero (expiry) ›").performClick()
        text("Jarvis's own trades ›").performClick()
        text("Live vs backtest").performClick()
        assertEquals(listOf("strategy", "jarvis", "strategy", "ira", "pnl"), went)
    }

    @Test fun foldedShowsOnlyTheMarketLine() {
        card(GlanceFixtures.inSession, expanded = false)
        text("Today at a glance").assertIsDisplayed()
        text("Market open · closes at 15:30 (4h 25m left)").assertIsDisplayed()
        text("Show ▾").assertIsDisplayed()
        assertFalse(shows("Liquidity 15+5"))
        assertFalse(shows("Nifty: high"))
    }

    @Test fun notReadYetSaysSo() {
        card(null)
        text("Reading today…").assertIsDisplayed()
    }

    /** The card folds and opens from its heading; a read that comes back the same writes nothing. */
    @Test fun headingFoldsAndOpensTheCard() {
        set { TodayGlanceCard(onGo = {}, load = { GlanceFixtures.inSession }) }
        compose.waitUntil(10_000) { shows("Nifty: high · 2.5x") }   // read on Dispatchers.IO, outside Compose's idling
        compose.waitForIdle()
        text("Nifty: high · 2.5x").assertIsDisplayed()
        text("Hide ▴").performClick()
        compose.waitForIdle()
        assertFalse(shows("Nifty: high"))
        assertFalse(GlanceSource.expanded())
        text("Show ▾").performClick()
        compose.waitForIdle()
        text("Nifty: high · 2.5x").assertIsDisplayed()
        assertTrue(GlanceSource.expanded())
    }

    /**
     * The CI lesson (a settings card that wrote Compose state forever kept Robolectric from ever idling): Home with the
     * card shown reaches idle within the default timeout, its reads run off the main thread, and the 60 s poll does not
     * spin - however often the loader is called, an equal card writes no state.
     */
    @Test fun homeWithTheCardReachesIdle() {
        val reads = AtomicInteger()
        val offMain = AtomicInteger()
        set {
            AlmanacContent(false, false, emptyMap(), null, emptyList(), Load.Idle, Load.Done(HomeFixtures.paper), {}, {},
                glance = {
                    TodayGlanceCard(onGo = {}, load = {
                        reads.incrementAndGet()
                        if (Looper.myLooper() != Looper.getMainLooper()) offMain.incrementAndGet()
                        GlanceFixtures.inSession.copy()   // a new, equal card every read
                    })
                }) { Text("strategies card") }
        }
        compose.waitForIdle()
        text("Today at a glance").assertIsDisplayed()
        compose.waitUntil(10_000) { shows("Nifty: high · 2.5x") }   // read on Dispatchers.IO, outside Compose's idling
        compose.waitForIdle()
        text("Nifty: high · 2.5x").assertIsDisplayed()
        text("strategies card").assertIsDisplayed()
        compose.waitForIdle()
        val n = reads.get()
        assertTrue("read at least once, and not in a loop: $n", n in 1..3)
        assertEquals("every read off the main thread", n, offMain.get())
    }

    /** The same with the app's own sources (empty records on a fresh phone): idle, nothing reached over the network. */
    @Test fun homeWithTheRealSourcesReachesIdle() {
        set {
            AlmanacContent(false, false, emptyMap(), null, emptyList(), Load.Idle, Load.Done(HomeFixtures.paper), {}, {},
                glance = { TodayGlanceCard(onGo = {}) }) { Text("strategies card") }
        }
        compose.waitForIdle()
        text("Today at a glance").assertIsDisplayed()
        compose.waitUntil(10_000) { GlanceSource.last != null }
        compose.waitForIdle()
        assertTrue(shows("Market ") || shows("Pre-open"))
    }
}

/** The card on the six research set-ups (a small phone at font 2.0, landscape at 1.0 and 2.0, a tablet): layout lint. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TodayGlanceLayoutTest(device: DeviceConfig) : ScreenTest(device) {
    @get:Rule val watchdog = com.optionslab.app.testing.ResearchWatchdog()
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun configs(): List<Array<Any>> = com.optionslab.app.testing.ResearchMatrix.six()

        val BUGS = emptyMap<String, String>()
    }

    @After fun noNetwork() { assertEquals(emptyList<String>(), NetworkGuard.blocked.toList()) }

    @androidx.compose.runtime.Composable private fun home(card: TodayGlance.Card) {
        AlmanacContent(false, false, mapOf("BANKNIFTY" to HomeFixtures.quote), null, HomeFixtures.daily, Load.Idle, Load.Done(HomeFixtures.paper), {}, {},
            glance = { TodayGlanceContent(card, true, {}, {}) }) {}
    }

    @Test fun homeGlancePreOpen() {
        checkScreen("home-glance-preopen", BUGS) { home(GlanceFixtures.preOpen) }
        if (device.name == "phone-font1.3-light") smokeEveryAction()
    }

    @Test fun homeGlanceInSession() {
        checkScreen("home-glance-session", BUGS) { home(GlanceFixtures.inSession) }
        if (device.name == "phone-font1.3-light") smokeEveryAction()
    }

    @Test fun homeGlanceClosed() = checkScreen("home-glance-closed", BUGS) { home(GlanceFixtures.closed) }
}
