package com.optionslab.app.ui.screens

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.data.NtfyArm
import com.optionslab.app.data.NtfyReader
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.engine.signals.NtfyParser
import com.optionslab.engine.signals.NtfyRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.ConscryptMode
import java.time.LocalDateTime

/**
 * Settings → Automation → ntfy alerts (25 Oct): the security advice, the topic masked (never shown), the weak-topic warning, the
 * On and Trade live switches off by default, the limits and strike rule, Test, and the Alerts list with what was decided and why.
 */
@RunWith(AndroidJUnit4::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class NtfyPageTest {
    @get:Rule val compose = createComposeRule()

    private val calls = ArrayList<String>()
    private val at = LocalDateTime.of(2026, 10, 26, 9, 28, 4)

    private fun show(v: NtfyArm.View, cfg: NtfyReader.Config?, liveOn: Boolean = false, said: String? = null) {
        compose.setContent {
            IraAlgoTheme("light") {
                NtfyContent(v, NtfyReader.Status(said = "connected"), cfg, liveOn, said,
                    onTopic = { calls += "topic:${it.length}" }, onServer = { calls += "server:$it" }, onToken = { calls += "token:${it.length}" },
                    onPrefix = { calls += "prefix:$it" }, onOn = { calls += "on:$it" }, onSettings = { calls += "settings:${it.lots}/${it.maxPerDay}/${it.staleMin}/${it.strikeRule.key}" },
                    onLive = { calls += "live:$it" }, onTest = { calls += "test" })
            }
        }
        compose.waitForIdle()
    }

    private fun exists(t: String) = compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test fun theSetupSaysTheSecurityAndNeverShowsTheTopic() {
        show(NtfyArm.View(), NtfyReader.Config("alerts1", NtfyRules.DEFAULT_SERVER, null, ""))
        assertTrue(exists("works like a password"))
        assertTrue(exists("Topic: topic set (****)"))
        assertTrue("a short topic is warned about", exists("short or plain"))
        assertTrue(exists("An external message - not a researched or proven strategy"))
        compose.onNodeWithTag("ntfy-on").performScrollTo().assertIsOff()
        compose.onNodeWithTag("ntfy-live").performScrollTo().assertIsOff()
        compose.onNodeWithTag("ntfy-topic").performScrollTo().performTextInput("k3J9-q2Lm_8xZpV4rT7wYb")
        compose.onNodeWithTag("ntfy-topic-save").performScrollTo().performClick()
        compose.onNodeWithTag("ntfy-test").performScrollTo().performClick()
        compose.onNodeWithTag("ntfy-on").performScrollTo().performClick()
        compose.onNodeWithTag("ntfy-live").performScrollTo().performClick()
        compose.onNodeWithTag("ntfy-lots-plus").performScrollTo().performClick()
        compose.onNodeWithTag("ntfy-strike-itm1").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf("topic:22", "test", "on:true", "live:true", "settings:2/3/5/atm", "settings:1/3/5/itm1"), calls)
        // The typed topic is never shown as text.
        assertTrue(!exists("k3J9-q2Lm_8xZpV4rT7wYb"))
    }

    @Test fun noTopicMeansTestIsOffAndNoWarning() {
        show(NtfyArm.View(), NtfyReader.Config(null, NtfyRules.DEFAULT_SERVER, null, ""), said = "Type your topic first.")
        assertTrue(exists("Topic: no topic set"))
        assertTrue(!exists("short or plain"))
        compose.onNodeWithTag("ntfy-test").performScrollTo().performClick()
        assertTrue(calls.isEmpty())
        assertTrue(exists("Type your topic first."))
    }

    @Test fun theAlertsListShowsWhatWasDecidedAndWhy() {
        val alerts = listOf(
            NtfyArm.Alert("m2", at.plusMinutes(2), "NIFTY CE PE", NtfyArm.NOT_TRADED, "both CE and PE (call and put) in one message"),
            NtfyArm.Alert("m1", at, "BUY NIFTY 25000 CE", NtfyArm.TRADED, "bought NIFTY 25000CE 65 @ 121 · paper · stop 100, target 140"))
        val open = NtfyArm.Pos("ntfy", "m1", "NIFTY", "NIFTY26N0225000CE", "CE", 1, 65, 121.0, at, "o1", 100.0, false, "s1", "t1", 140.0, 140.0, false)
        show(NtfyArm.View(on = true, open = listOf(open), alerts = alerts, settings = NtfyRules.Settings(strikeRule = NtfyParser.StrikeRule.OTM1)),
            NtfyReader.Config("k3J9-q2Lm_8xZpV4rT7wYb", NtfyRules.DEFAULT_SERVER, "t", ""), liveOn = false)
        compose.onNodeWithTag("ntfy-on").performScrollTo().assertIsOn()
        assertTrue(exists("09:30 2026-10-26 · not traded"))
        assertTrue(exists("both CE and PE"))
        assertTrue(exists("09:28 2026-10-26 · traded"))
        assertTrue(exists("NIFTY26N0225000CE 65 @ 121 · stop 100 · target 140"))
        assertTrue(exists("● 1 OTM"))
        assertTrue(exists("Access token: set (****)"))
        assertTrue(exists("Reader: connected"))
    }

    /**
     * 26 Oct (IraThinker review): a strangle is shown as information (a neutral note, not a refusal), and an open IraThinker mom60
     * trade shows its source rule's exit time, its label and "a paper signal".
     */
    @Test fun informationIsANeutralNoteAndASourceTradeShowsItsRule() {
        val alerts = listOf(
            NtfyArm.Alert("st", at.plusMinutes(1), "signal {\"strategy\":\"STRANGLE\"}", NtfyArm.INFO,
                "information, not traded: IraAlgo only buys options (SELL_STRANGLE: a multi-leg trade)"),
            NtfyArm.Alert("x", at, "NIFTY CE PE", NtfyArm.NOT_TRADED, "both CE and PE (call and put) in one message"))
        val open = NtfyArm.Pos("ntfy", "m1", "NIFTY", "NIFTY26N0225000CE", "CE", 1, 65, 121.0, at, "o1", 96.8, true, "s1", "t1", 169.45, 169.45, false,
            exitAt = at.plusMinutes(60), strategy = "IraThinker mom60", paperOnly = true)
        show(NtfyArm.View(on = true, open = listOf(open), alerts = alerts), NtfyReader.Config("k3J9-q2Lm_8xZpV4rT7wYb", NtfyRules.DEFAULT_SERVER, null, ""))
        assertTrue(exists("NIFTY26N0225000CE 65 @ 121 · stop 96.8 · target 169.45 · out by 10:28 · IraThinker mom60 (a paper signal)"))
        compose.onNodeWithTag("ntfy-alert-info-0").performScrollTo()
        assertTrue(exists("09:29 2026-10-26 · ⓘ information"))
        assertTrue(!exists("09:29 2026-10-26 · not traded"))
        assertTrue(exists("information, not traded: IraAlgo only buys options"))
        assertEquals(1, compose.onAllNodesWithContentDescription("Alert information").fetchSemanticsNodes().size)
        // A refusal keeps its own header, and no info tag.
        assertTrue(exists("09:28 2026-10-26 · not traded"))
        assertTrue(compose.onAllNodesWithTag("ntfy-alert-info-1").fetchSemanticsNodes().isEmpty())
        // The note says how a signal's own exits are used.
        assertTrue(exists("never a wider stop"))
    }

    /** 26 Oct (Boss: "I think ntfy messages are getting read late"): each message's seconds to read and decide, and today's line. */
    @Test fun eachMessageShowsItsTimingAndTodaysLine() {
        val sent = at.atZone(com.optionslab.engine.IST).toInstant().toEpochMilli()
        com.optionslab.app.data.Market.testClock = java.time.Clock.fixed(at.plusMinutes(5).atZone(com.optionslab.engine.IST).toInstant(), com.optionslab.engine.IST)
        try {
            val alerts = listOf(
                NtfyArm.Alert("m2", at.plusMinutes(2), "exit nifty", NtfyArm.CLOSED, "closed", readMs = sent + 120_000 + 800, actedMs = sent + 120_000 + 1_500),
                NtfyArm.Alert("m1", at, "BUY NIFTY 25000 CE", NtfyArm.TRADED, "bought", readMs = sent + 1_200, actedMs = sent + 2_500),
                // Kept before 26 Oct: no timing, no line of its own and not counted.
                NtfyArm.Alert("m0", at.minusMinutes(1), "old", NtfyArm.NOT_TRADED, "stale"))
            show(NtfyArm.View(on = true, alerts = alerts), NtfyReader.Config("k3J9-q2Lm_8xZpV4rT7wYb", NtfyRules.DEFAULT_SERVER, null, ""))
            compose.onNodeWithTag("ntfy-timing").performScrollTo()
            assertTrue(exists("ntfy: 2 messages, read in median 1.0s / max 1.2s, acted in median 2.0s / max 2.5s"))
            compose.onNodeWithTag("ntfy-alert-timing-0").performScrollTo()
            assertTrue(exists("read 0.8s · decided 1.5s after it was sent"))
            assertTrue(exists("read 1.2s · decided 2.5s after it was sent"))
            assertTrue(compose.onAllNodesWithTag("ntfy-alert-timing-2").fetchSemanticsNodes().isEmpty())
        } finally { com.optionslab.app.data.Market.testClock = null }
    }
}
