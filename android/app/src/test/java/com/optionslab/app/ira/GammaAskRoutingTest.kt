package com.optionslab.app.ira

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.testing.AreaE
import com.optionslab.app.testing.AreaEWatchdog
import com.optionslab.app.testing.until
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.ira.GammaMorning
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "What's gamma saying" (14 Oct, Boss approved): Jarvis answers with the morning gamma line ([com.optionslab.app.data.GammaMorningHost]),
 * labelled information only - not the glossary's meaning of the word, and not an order or a command. "Did the OI-wall
 * check fire" is the shadow log's answer, never a trade.
 */
@RunWith(AndroidJUnit4::class)
class GammaAskRoutingTest {
    @get:Rule val watchdog = AreaEWatchdog()
    @get:Rule val compose = createComposeRule()

    @Before fun up() { AreaE.resetGlobals() }
    @After fun down() {
        runBlocking { IraHub.forgetAll() }
        AreaE.resetGlobals()
    }

    private fun lastReply(): String? = IraHub.state.value.messages.lastOrNull()?.takeIf { it.fromIra }?.text

    @Test fun whatsGammaSayingIsTheGammaLineNotTheGlossary() {
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        compose.setContent { IraAlgoTheme("light") { Text("Jarvis") } }
        IraHub.ask("what's gamma saying")
        compose.until(60_000, "the gamma answer") { lastReply()?.contains(GammaMorning.INFO) == true }
        val said = lastReply()!!
        assertFalse(said, said.startsWith("Gamma is how fast"))
        assertTrue(IraHub.state.value.pending.isEmpty())
    }

    @Test fun didTheOiWallCheckFireIsTheShadowLog() {
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        compose.setContent { IraAlgoTheme("light") { Text("Jarvis") } }
        IraHub.ask("did the oi wall check fire today")
        compose.until(60_000, "the OI-wall answer") { lastReply()?.startsWith("OI-wall check (shadow, not traded; information only, not a trade signal)") == true }
        assertTrue(lastReply()!!, "nothing trades on it" in lastReply()!!)
        assertTrue(IraHub.state.value.pending.isEmpty())
    }
}
