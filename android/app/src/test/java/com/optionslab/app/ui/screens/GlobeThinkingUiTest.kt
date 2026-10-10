package com.optionslab.app.ui.screens

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.ira.IraHub
import com.optionslab.app.ira.JarvisVoice
import com.optionslab.app.testing.AreaE
import com.optionslab.app.testing.AreaEWatchdog
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.ira.GlobeThinking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The globe's word (Boss, 7 Oct: THINKING at 09:05, market closed, nothing asked): thinking is Boss's own question only -
 * a market read in the background shows nothing - and a voice stuck thinking rests after [GlobeThinking.MAX_MS].
 */
@org.robolectric.annotation.Config(qualifiers = "w411dp-h800dp")
@RunWith(AndroidJUnit4::class)
class GlobeThinkingUiTest {
    @get:Rule val watchdog = AreaEWatchdog()
    @get:Rule val compose = createComposeRule()

    @Before fun up() {
        AreaE.resetGlobals()
        // Robolectric shares static state between tests: "Don't listen" left on would read "MIC OFF" at rest.
        JarvisVoice.resetDeafForTest()
        JarvisVoice.stateForTest(JarvisVoice.VoiceState())
        IraHub.loadingForTest(false)
    }

    @After fun down() {
        JarvisVoice.stateForTest(JarvisVoice.VoiceState())
        IraHub.loadingForTest(false)
        AreaE.resetGlobals()
    }

    /**
     * One frame at a time, the paused main looper run each time: a state write is applied to Compose by a main-thread
     * post (the global snapshot's apply), which the compose clock alone never runs.
     */
    private fun step(n: Int = 8) = repeat(n) {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        compose.mainClock.advanceTimeByFrame()
    }

    private fun globe() {
        compose.setContent { IraAlgoTheme("dark") { GlobeLabel(rememberGlobeMode(typed = 0, drafting = false, askedWork = true)) } }
        compose.mainClock.autoAdvance = false
        step()
    }

    private fun noThinking() = compose.onAllNodesWithText("THINKING").assertCountEquals(0)

    @Test fun aBackgroundMarketReadDoesNotShowThinking() {
        globe()
        noThinking()
        // The minute's market read under way (a slow network read): the globe stays at rest.
        IraHub.loadingForTest(true); step()
        noThinking()
        compose.onNodeWithText("VOICE OFF").assertIsDisplayed()
        IraHub.loadingForTest(false); step()
        noThinking()
    }

    @Test fun bossQuestionShowsThinkingAndAStuckThinkingClearsAfterTheTimeout() {
        globe()
        JarvisVoice.stateForTest(JarvisVoice.VoiceState(JarvisVoice.Mode.THINKING)); step()
        compose.onNodeWithText("THINKING").assertIsDisplayed()
        // Still working on it short of the limit.
        compose.mainClock.advanceTimeBy(GlobeThinking.MAX_MS - 5_000); step()
        compose.onNodeWithText("THINKING").assertIsDisplayed()
        // Past it: the globe rests, though the voice never left thinking.
        compose.mainClock.advanceTimeBy(6_000); step()
        noThinking()
        compose.onNodeWithText("IDLE").assertIsDisplayed()
        // Thinking again later is a new question: shown again.
        JarvisVoice.stateForTest(JarvisVoice.VoiceState(JarvisVoice.Mode.SPEAKING)); step()
        compose.onNodeWithText("ANSWERING").assertIsDisplayed()
        JarvisVoice.stateForTest(JarvisVoice.VoiceState(JarvisVoice.Mode.THINKING)); step()
        compose.onNodeWithText("THINKING").assertIsDisplayed()
    }
}
