package com.optionslab.app.ui.screens

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.ira.IraHub
import com.optionslab.app.ira.JarvisVoice
import com.optionslab.app.testing.AreaE
import com.optionslab.app.testing.AreaEWatchdog
import com.optionslab.app.testing.has
import com.optionslab.app.testing.until
import com.optionslab.app.testing.waitForText
import com.optionslab.app.ui.theme.IraAlgoTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Jarvis's icon row (Boss, 7 Oct): "Don't listen" (an ear), the mic (hold to talk), mute and the chat as symbols only, in
 * one row. The voice service under the mic is a fake here ([talkPathForTest]): what the mic asks of it is recorded, nothing
 * listens.
 */
@org.robolectric.annotation.Config(qualifiers = "w411dp-h800dp")
@RunWith(AndroidJUnit4::class)
class JarvisControlsTest {
    @get:Rule val watchdog = AreaEWatchdog()
    @get:Rule val compose = createComposeRule()

    private class FakeTalk : TalkPath {
        val calls = ArrayList<String>()
        override fun start(ctx: Context, askMic: () -> Unit, note: (String) -> Unit): Boolean { calls += "start"; return true }
        override fun end(send: Boolean) { calls += if (send) "send" else "drop" }
    }
    private val fake = FakeTalk()

    @Before fun up() {
        AreaE.resetGlobals()
        JarvisVoice.resetDeafForTest()
        JarvisVoice.muted = false
        talkPathForTest = fake
    }

    @After fun down() {
        talkPathForTest = null
        JarvisVoice.listenAgain(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        JarvisVoice.resetDeafForTest()
        JarvisVoice.muted = false
        runBlocking { IraHub.forgetAll() }
        AreaE.resetGlobals()
    }

    /**
     * One frame at a time, the paused main looper run each time: a tap's state write is applied to Compose by a
     * main-thread post (the global snapshot's apply), which the compose clock alone never runs.
     */
    private fun step(n: Int = 8) = repeat(n) {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        compose.mainClock.advanceTimeByFrame()
    }

    private val listening = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Listening")
    private val button = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    private fun row(chatOpen: Boolean = false, onChat: () -> Unit = {}) {
        compose.setContent { IraAlgoTheme("dark") { JarvisControlRow(chatOpen = chatOpen, onChat = onChat) } }
        compose.mainClock.autoAdvance = false
        step()
    }

    /** The press lasts past a quick tap: the hold begins. */
    private fun held() {
        compose.mainClock.advanceTimeBy(JarvisControls.QUICK_TAP_MS + 100)
        step()
    }

    private val ctx: Context get() = androidx.test.core.app.ApplicationProvider.getApplicationContext()

    /** The old worded "Don't listen" button's words: none anywhere. */
    private fun noListeningWords() = listOf("Not listening", "Listen again", "Don't listen").forEach { w ->
        compose.onAllNodesWithText(w, substring = true, ignoreCase = true).assertCountEquals(0)
    }

    @Test fun fourIconButtonsInOneRowAndNoWords() {
        row()
        val ear = compose.onNodeWithContentDescription(JarvisControls.STOP_LISTEN).assert(button).fetchSemanticsNode().boundsInRoot
        val mic = compose.onNodeWithContentDescription(JarvisControls.MIC).assert(button).fetchSemanticsNode().boundsInRoot
        val mute = compose.onNodeWithContentDescription(JarvisControls.MUTE).assert(button).fetchSemanticsNode().boundsInRoot
        val chat = compose.onNodeWithContentDescription(JarvisControls.OPEN_CHAT).assert(button).fetchSemanticsNode().boundsInRoot
        // One row: the same top, left to right - the ear, the mic, mute, the chat.
        listOf(mic, mute, chat).forEach { b -> assertEquals(ear.top, b.top, 0.5f) }
        assertTrue(ear.right <= mic.left + 0.5f && mic.right <= mute.left + 0.5f && mute.right <= chat.left + 0.5f)
        // Small, but each a full 48dp touch target.
        val density = compose.density.density
        listOf(ear, mic, mute, chat).forEach { b ->
            assertEquals(48f, b.width / density, 0.5f); assertEquals(48f, b.height / density, 0.5f)
        }
        // None of the old worded buttons.
        listOf("Talk", "Open chat", "Muted", "unmute", "Back to Jarvis", "Requests").forEach { w ->
            compose.onAllNodesWithText(w, substring = true, ignoreCase = true).assertCountEquals(0)
        }
        noListeningWords()
    }

    /** The ear is "Don't listen" (the Settings switch): a tap switches the microphone off, a tap on the crossed-out ear back on. */
    @Test fun theEarSwitchesListeningOffAndBackOn() {
        row()
        compose.onNodeWithContentDescription(JarvisControls.STOP_LISTEN)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Microphone on"))
            .performSemanticsAction(SemanticsActions.OnClick); step()
        assertTrue(JarvisVoice.deaf)
        compose.onNodeWithContentDescription(JarvisControls.LISTEN_AGAIN).assert(button)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Microphone off"))
        // Still one row of symbols: the mic stays (greyed), and no words appear.
        compose.onNodeWithContentDescription(JarvisControls.MIC).assert(button)
        noListeningWords()
        compose.onNodeWithContentDescription(JarvisControls.LISTEN_AGAIN).performSemanticsAction(SemanticsActions.OnClick); step()
        assertFalse(JarvisVoice.deaf)
        compose.onNodeWithContentDescription(JarvisControls.STOP_LISTEN).assert(button)
        noListeningWords()
    }

    /** "Don't listen" is the microphone off altogether: the mic stays in the row but a press only says so, briefly. */
    @Test fun whileNotListeningTheMicOnlySaysListeningIsOff() {
        JarvisVoice.dontListen(ctx)
        row()
        val mic = compose.onNodeWithContentDescription(JarvisControls.MIC).assert(button)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, JarvisControls.LISTENING_OFF))
        mic.performTouchInput { down(center); advanceEventTime(100); up() }
        step()
        assertEquals(emptyList<String>(), fake.calls)
        compose.onNodeWithText(JarvisControls.LISTENING_OFF).assertIsDisplayed()
        compose.mainClock.advanceTimeBy(JarvisControls.HINT_MS + 500); step()
        assertFalse(compose.has(JarvisControls.LISTENING_OFF))
        // A hold starts nothing either; nor does TalkBack's double tap.
        mic.performTouchInput { down(center) }
        step()
        held()
        mic.performTouchInput { advanceEventTime(1_500); up() }
        step()
        mic.performSemanticsAction(SemanticsActions.OnClick); step()
        assertEquals(emptyList<String>(), fake.calls)
        assertFalse(JarvisVoice.listeningNow())
        assertTrue(JarvisVoice.deaf)
    }

    @Test fun pressAndHoldListensAndLettingGoSends() {
        row()
        val mic = compose.onNodeWithContentDescription(JarvisControls.MIC)
        mic.performTouchInput { down(center) }
        step()
        assertEquals("not yet a hold: nothing starts", emptyList<String>(), fake.calls)
        held()
        assertEquals(listOf("start"), fake.calls)
        mic.assert(listening)
        mic.performTouchInput { advanceEventTime(1_500); up() }
        step()
        assertEquals(listOf("start", "send"), fake.calls)
        assertFalse(compose.has(JarvisControls.HOLD_HINT))
        compose.onNodeWithContentDescription(JarvisControls.MIC).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
    }

    /** Held a minute: asked then (as if let go); letting go later sends nothing more. */
    @Test fun aHoldIsAskedAtSixtySecondsAndOnlyOnce() {
        row()
        val mic = compose.onNodeWithContentDescription(JarvisControls.MIC)
        mic.performTouchInput { down(center) }
        step()
        held()
        compose.mainClock.advanceTimeBy(com.optionslab.ira.HoldTalk.CAP_MS - 1_000); step()
        assertEquals("still held at 59 s", listOf("start"), fake.calls)
        compose.mainClock.advanceTimeBy(2_000); step()
        assertEquals(listOf("start", "send"), fake.calls)
        mic.performTouchInput { advanceEventTime(61_000); up() }
        step()
        assertEquals(listOf("start", "send"), fake.calls)
        assertFalse(compose.has(JarvisControls.HOLD_HINT))
    }

    @Test fun aQuickTapSaysHoldToTalkAndSendsNothing() {
        row()
        compose.onNodeWithContentDescription(JarvisControls.MIC).performTouchInput { down(center); advanceEventTime(100); up() }
        step()
        // Nothing starts or stops: Jarvis is not interrupted and his follow-up window is left alone.
        assertEquals(emptyList<String>(), fake.calls)
        compose.onNodeWithText(JarvisControls.HOLD_HINT).assertIsDisplayed()
        // Brief: gone a couple of seconds later.
        compose.mainClock.advanceTimeBy(JarvisControls.HINT_MS + 500); step()
        assertFalse(compose.has(JarvisControls.HOLD_HINT))
    }

    @Test fun slidingOffTheMicSendsNothing() {
        row()
        val mic = compose.onNodeWithContentDescription(JarvisControls.MIC)
        mic.performTouchInput { down(center) }
        step()
        held()
        mic.performTouchInput { advanceEventTime(600); moveTo(Offset(width * 6f, centerY)); advanceEventTime(600); up() }
        step()
        assertEquals(listOf("start", "drop"), fake.calls)
        assertFalse(compose.has(JarvisControls.HOLD_HINT))
    }

    /** TalkBack cannot hold: a double tap starts listening, the next one stops and sends. */
    @Test fun talkBackTapStartsAndASecondTapSends() {
        row()
        val mic = compose.onNodeWithContentDescription(JarvisControls.MIC)
        mic.performSemanticsAction(SemanticsActions.OnClick); step()
        assertEquals(listOf("start"), fake.calls)
        mic.assert(listening)
        assertEquals("Stop and send", mic.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        mic.performSemanticsAction(SemanticsActions.OnClick); step()
        assertEquals(listOf("start", "send"), fake.calls)
    }

    @Test fun muteTogglesItsSymbolAndJarvisVoice() {
        row()
        compose.onNodeWithContentDescription(JarvisControls.MUTE).performSemanticsAction(SemanticsActions.OnClick); step()
        compose.onNodeWithContentDescription(JarvisControls.UNMUTE).assert(button)
        compose.until(5_000, "muted") { JarvisVoice.muted }
        compose.onNodeWithContentDescription(JarvisControls.UNMUTE).performSemanticsAction(SemanticsActions.OnClick); step()
        compose.onNodeWithContentDescription(JarvisControls.MUTE).assert(button)
        compose.until(5_000, "unmuted") { !JarvisVoice.muted }
    }

    @Test fun theChatIconOpensAndClosesTheChat() {
        compose.setContent {
            IraAlgoTheme("dark") {
                var open by remember { mutableStateOf(false) }
                JarvisControlRow(chatOpen = open, onChat = { open = !open })
            }
        }
        compose.mainClock.autoAdvance = false
        step()
        compose.onNodeWithContentDescription(JarvisControls.OPEN_CHAT).performSemanticsAction(SemanticsActions.OnClick); step()
        compose.onNodeWithContentDescription(JarvisControls.CLOSE_CHAT).performSemanticsAction(SemanticsActions.OnClick); step()
        compose.onNodeWithContentDescription(JarvisControls.OPEN_CHAT).assert(button)
    }

    /** With Jarvis, "Don't listen" on: the globe shows the crossed-out ear and the mic in the row - no listening words. */
    @Test fun onTheGlobeNotListeningIsASymbolOnly() {
        org.junit.Assume.assumeTrue(com.optionslab.app.BuildConfig.JARVIS)
        JarvisVoice.dontListen(ctx)
        compose.setContent { IraAlgoTheme("dark") { IraPage() } }
        step()
        compose.onNodeWithContentDescription(JarvisControls.LISTEN_AGAIN).assert(button)
        compose.onNodeWithContentDescription(JarvisControls.MIC).assert(button)
        noListeningWords()
        compose.onNodeWithContentDescription(JarvisControls.LISTEN_AGAIN).performSemanticsAction(SemanticsActions.OnClick); step()
        assertFalse(JarvisVoice.deaf)
        compose.onNodeWithContentDescription(JarvisControls.STOP_LISTEN).assert(button)
        noListeningWords()
    }

    /** With Jarvis: the globe's chat icon opens the chat; the globe shows no Requests and no worded buttons. */
    @Test fun onTheGlobeTheChatIconOpensTheChat() {
        org.junit.Assume.assumeTrue(com.optionslab.app.BuildConfig.JARVIS)
        compose.setContent { IraAlgoTheme("dark") { IraPage() } }
        step()
        compose.onNodeWithContentDescription(JarvisControls.MUTE).assert(button)
        listOf("Open chat", "Talk", "Requests").forEach { w -> compose.onAllNodesWithText(w, substring = true).assertCountEquals(0) }
        compose.onNodeWithContentDescription(JarvisControls.STOP_LISTEN).assert(button)
        noListeningWords()
        compose.onNodeWithContentDescription(JarvisControls.OPEN_CHAT).performSemanticsAction(SemanticsActions.OnClick); step()
        compose.waitForText("Ask Ira about the market")
        compose.onNodeWithContentDescription(JarvisControls.CLOSE_CHAT).assert(button)
    }

    /** The chat on an ordinary phone: the header's icon row leaves the question box and Ask on screen. */
    @Test fun theQuestionBoxStaysOnScreenInTheChat() {
        compose.setContent { IraAlgoTheme("light") { IraPage(startInChat = true) } }
        step()
        compose.waitForText("Ask Ira about the market")
        val rootBottom = compose.onRoot().fetchSemanticsNode().boundsInRoot.bottom
        listOf("Ask Ira about the market", "Ask").forEach { t ->
            compose.onNodeWithText(t).assertIsDisplayed()
            val b = compose.onNodeWithText(t).fetchSemanticsNode().boundsInRoot
            assertTrue("$t on screen: ${b.bottom} of $rootBottom", b.bottom <= rootBottom + 0.5f)
        }
        if (com.optionslab.app.BuildConfig.JARVIS) {
            compose.onNodeWithContentDescription(JarvisControls.MIC).assert(button)
            compose.onNodeWithContentDescription(JarvisControls.STOP_LISTEN).assert(button)
            compose.onNodeWithContentDescription(JarvisControls.CLOSE_CHAT).assert(button)

            compose.onAllNodesWithText("Back to Jarvis", substring = true).assertCountEquals(0)
        }
    }
}
