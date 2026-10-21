package com.optionslab.app.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.ira.JarvisVoice
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.ResearchFixtures
import com.optionslab.app.ui.Load
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.ira.ListenWhen
import com.optionslab.ira.LoopMeter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Battery, 14 Oct: the Health page's "Background work today" card shows the day's loop minutes, and Jarvis's
 * "Listen for 'Jarvis'" setting offers its three choices, Always by default, kept across a restart.
 */
@org.robolectric.annotation.Config(qualifiers = "w411dp-h2400dp")
@RunWith(AndroidJUnit4::class)
class BatteryRestScreensTest {
    // 21 Oct, enhancement round 4: the market clock at noon IST on a trading day unless the test sets its own.
    @get:org.junit.Rule val marketClock = com.optionslab.app.testing.MarketClockRule()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @After fun noNetwork() {
        JarvisVoice.resetListenWhenForTest()
        assertEquals("no host may be reached", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    @Test fun theHealthPageShowsTodaysBackgroundWork() {
        val meter = LoopMeter(dayOf = { java.time.Instant.ofEpochMilli(it).atZone(com.optionslab.engine.IST).toLocalDate() })
        meter.add(LoopMeter.WATCH_EVENING, 42 * 60_000L)
        repeat(3) { meter.count(LoopMeter.MCX_SLOW) }
        val line = LoopMeter.line(meter)
        compose.setContent {
            IraAlgoTheme("light") { HealthContent(ResearchFixtures.settings, Load.Idle, "backtest", {}, {}, {}, {}, battery = line) }
        }
        compose.onNodeWithText("Background work today").assertIsDisplayed()
        compose.onNodeWithText("watch after 15:40 42 min", substring = true).assertIsDisplayed()
        compose.onNodeWithText("3 at 60 s", substring = true).assertIsDisplayed()
    }

    @Test fun withoutTheLineTheCardIsNotShown() {
        compose.setContent { IraAlgoTheme("light") { HealthContent(ResearchFixtures.settings, Load.Idle, "backtest", {}, {}, {}, {}) } }
        assertTrue(compose.onAllNodesWithText("Background work today").fetchSemanticsNodes().isEmpty())
    }

    @Test fun listenForJarvisOffersThreeChoicesAlwaysByDefaultAndKeepsThePick() {
        assertEquals("nothing changes unless Boss picks another", ListenWhen.Mode.ALWAYS, JarvisVoice.listenWhen)
        JarvisVoice.resetListenWhenForTest()
        assertEquals("not read yet: the turns go by the default", ListenWhen.Mode.ALWAYS, JarvisVoice.listenWhenQuick())
        val picked = ArrayList<ListenWhen.Mode>()
        compose.setContent {
            var mode by remember { mutableStateOf(JarvisVoice.listenWhen) }
            IraAlgoTheme("light") { ListenWhenPicker(mode) { m -> mode = m; picked += m; JarvisVoice.listenWhen = m } }
        }
        compose.onNodeWithText("Always").assertIsSelected()
        compose.onNodeWithText("Market hours only").assertIsNotSelected()
        compose.onNodeWithText("Only when unlocked or charging").assertIsNotSelected()
        compose.onNodeWithText("Market hours only").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Market hours only").assertIsSelected()
        compose.onNodeWithText("Always").assertIsNotSelected()
        assertEquals(listOf(ListenWhen.Mode.MARKET_HOURS), picked)
        // Kept: read back from the settings as a restart would.
        JarvisVoice.resetListenWhenForTest()
        assertEquals(ListenWhen.Mode.MARKET_HOURS, JarvisVoice.listenWhen)
        compose.onNodeWithText("Only when unlocked or charging").performClick()
        compose.waitForIdle()
        JarvisVoice.resetListenWhenForTest()
        assertEquals(ListenWhen.Mode.UNLOCKED_OR_CHARGING, JarvisVoice.listenWhen)
    }
}
