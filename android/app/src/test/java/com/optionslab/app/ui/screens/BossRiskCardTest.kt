package com.optionslab.app.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.data.BossRiskHost
import com.optionslab.app.data.BossRiskHost.Rule
import com.optionslab.app.ui.theme.IraAlgoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule as JRule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 25 Oct (Boss's decisions on the app audit): Bot settings' card for the new risk rules - each paper rule on by default, each live
 * switch off (shadow); its lines, and the paper account stop's restart (PIN) only once it is reached.
 */
@org.robolectric.annotation.Config(qualifiers = "w411dp-h3200dp")
@RunWith(AndroidJUnit4::class)
class BossRiskCardTest {
    @get:JRule val marketClock = com.optionslab.app.testing.MarketClockRule()
    @get:JRule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun shows(t: String, sub: Boolean = true) = compose.onAllNodesWithText(t, substring = sub).fetchSemanticsNodes().isNotEmpty()

    private fun view(paper: Boolean = true, reached: Boolean = false) = BossRiskHost.View(
        BossRiskHost.PAPER_RULES.associateWith { paper }, BossRiskHost.LIVE_RULES.associateWith { false }, 20_000.0,
        listOf("Account stop rule on paper (on; counted from 2026-10-26): no paper day recorded yet.", "IV at entry (record only): no entry today."),
        accountReached = reached)

    @Test fun eachPaperRuleIsOnEachLiveSwitchIsShadowAndATapFlipsIt() {
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        val paper = ArrayList<Pair<Rule, Boolean>>()
        val live = ArrayList<Pair<Rule, Boolean>>()
        compose.setContent { IraAlgoTheme("light") { Column(Modifier.verticalScroll(rememberScrollState())) {
            BossRiskCardContent(view(), null, onPaper = { r, on -> paper += r to on }, onLive = { r, on -> live += r to on }, onRestart = {})
        } } }
        compose.waitForIdle()
        assertTrue(shows(BOSS_RISK_TITLE))
        for (r in BossRiskHost.PAPER_RULES) assertTrue(bossPaperTitle(r), shows(bossPaperTitle(r)))
        for (r in BossRiskHost.LIVE_RULES) assertTrue(bossLiveTitle(r), shows(bossLiveTitle(r)))
        assertTrue(shows("Off: shadow, logged only"))
        assertTrue(shows("IV at entry (record only)"))
        assertFalse("no restart until it is reached", shows(BOSS_RISK_RESTART))
        compose.onNodeWithText(bossPaperTitle(Rule.WEEKLY)).performScrollTo().performClick()
        compose.onNodeWithText(bossLiveTitle(Rule.RISK)).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf(Rule.WEEKLY to false), paper)
        assertEquals(listOf(Rule.RISK to true), live)
    }

    @Test fun reachedTheRestartIsOfferedAndAnOffLimitSaysItsPin() {
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        var restarts = 0
        val paper = ArrayList<Pair<Rule, Boolean>>()
        compose.setContent { IraAlgoTheme("light") { Column(Modifier.verticalScroll(rememberScrollState())) {
            BossRiskCardContent(view(paper = false, reached = true), "Weekly loss stop on paper: off.", onPaper = { r, on -> paper += r to on }, onLive = { _, _ -> },
                onRestart = { restarts++ })
        } } }
        compose.waitForIdle()
        assertTrue(shows("Off (switching it off asks your PIN)"))
        assertTrue(shows("Weekly loss stop on paper: off."))
        compose.onNodeWithText(BOSS_RISK_RESTART).performScrollTo().performClick()
        compose.onNodeWithText(bossPaperTitle(Rule.ACCOUNT)).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, restarts)
        assertEquals("switching on never waits", listOf(Rule.ACCOUNT to true), paper)
    }
}
