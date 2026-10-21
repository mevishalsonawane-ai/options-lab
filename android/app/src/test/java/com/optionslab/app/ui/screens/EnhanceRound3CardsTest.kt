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

/** 21 Oct, enhancement round 3 (idea 1): the positions-lag guard's switch on Bot settings' "One exit at a time" card. */
@org.robolectric.annotation.Config(qualifiers = "w411dp-h2400dp")
@RunWith(AndroidJUnit4::class)
class EnhanceRound3CardsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun shows(t: String, sub: Boolean = false) = compose.onAllNodesWithText(t, substring = sub).fetchSemanticsNodes().isNotEmpty()

    @Test fun theLagGuardIsInShadowByDefaultAndItsOwnTapSwitchesOnlyIt() {
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        val lag = ArrayList<Boolean>()
        val coordinator = ArrayList<Boolean>()
        compose.setContent { IraAlgoTheme("light") { Column(Modifier.verticalScroll(rememberScrollState())) {
            ExitCoordinatorCardContent(ExitCoordinator.View(on = false, lagOn = false,
                lagLine = "Positions-lag guard: shadow (logged only); a positions read trailing an exit's fill 2"), null,
                onLagSwitch = { lag += it }) { coordinator += it }
        } } }
        compose.waitForIdle()
        assertTrue(shows("Never sell a filled exit twice"))
        assertTrue(shows("it only ever sends less", sub = true))
        assertTrue(shows("trailing an exit's fill 2", sub = true))
        compose.onNodeWithText("Never sell a filled exit twice").performClick()
        compose.waitForIdle()
        assertEquals(listOf(true), lag)
        assertTrue("the coordinator's own switch untouched", coordinator.isEmpty())
    }

    @Test fun onItSaysWhatItDoes() {
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        compose.setContent { IraAlgoTheme("light") { Column(Modifier.verticalScroll(rememberScrollState())) {
            ExitCoordinatorCardContent(ExitCoordinator.View(on = true, lagOn = true), null) {}
        } } }
        compose.waitForIdle()
        assertTrue(shows("On: a positions read behind an exit's fill is read again"))
    }
}
