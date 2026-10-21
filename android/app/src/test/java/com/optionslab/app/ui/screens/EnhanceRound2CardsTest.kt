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
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.data.ExitCoordinator
import com.optionslab.app.ui.theme.IraAlgoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 20 Oct, enhancement round 2 (idea 1): Bot settings' "One exit at a time" card (the exit coordinator's switch). */
@org.robolectric.annotation.Config(qualifiers = "w411dp-h2400dp")
@RunWith(AndroidJUnit4::class)
class EnhanceRound2CardsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun shows(t: String, sub: Boolean = false) = compose.onAllNodesWithText(t, substring = sub).fetchSemanticsNodes().isNotEmpty()

    @Test fun theExitCoordinatorCardIsInShadowByDefaultAndSwitchesOnInOneTap() {
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        val set = ArrayList<Boolean>()
        compose.setContent { IraAlgoTheme("light") { Column(Modifier.verticalScroll(rememberScrollState())) {
            ExitCoordinatorCardContent(ExitCoordinator.View(on = false, line = "Exit coordinator: shadow (logged only); exits sent today 3"), null) { set += it }
        } } }
        compose.waitForIdle()
        assertTrue(shows("One exit at a time"))
        assertTrue(shows("Off: only logs what it would do"))
        assertTrue(shows("Your own exits never wait", sub = true))
        assertTrue(shows("exits sent today 3", sub = true))
        compose.onNodeWithText("Make exits take turns").performClick()
        compose.waitForIdle()
        assertEquals(listOf(true), set)
    }

    @Test fun onItSaysSoAndWhatWasSaidBack() {
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        compose.setContent { IraAlgoTheme("light") { Column(Modifier.verticalScroll(rememberScrollState())) {
            ExitCoordinatorCardContent(ExitCoordinator.View(on = true, line = ""), "Exit coordinator on: said back") {}
        } } }
        compose.waitForIdle()
        assertTrue(shows("On: one exit at a time per contract"))
        assertTrue(shows("Exit coordinator on: said back"))
    }
}
