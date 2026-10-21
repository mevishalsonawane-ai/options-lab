package com.optionslab.app.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.data.ExposureCapHost
import com.optionslab.app.ui.theme.IraAlgoTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 19 Oct, Settings → Bot settings → Same-direction cap: the card shows the paper switch (on), the number (2) and the live switch
 * (off: live only logs); picking 3 sets the cap to 3, the paper switch turns it off, the live switch turns live refusals on.
 */
@RunWith(AndroidJUnit4::class)
@org.robolectric.annotation.Config(qualifiers = "w411dp-h2400dp")
class SameDirectionCapCardTest {
    // 21 Oct, enhancement round 4: the market clock at noon IST on a trading day unless the test sets its own.
    @get:org.junit.Rule val marketClock = com.optionslab.app.testing.MarketClockRule()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @After fun down() { ExposureCapHost.resetForTest(paperOn = false) }

    @Test fun theCardShowsTheCapAndBossCanChangeItsNumberAndSwitches() {
        ExposureCapHost.resetForTest(paperOn = true)
        compose.setContent { IraAlgoTheme("light") { Column(Modifier.verticalScroll(rememberScrollState())) { SameDirectionCapCard() } } }
        compose.waitForIdle()
        compose.onNodeWithText("Same-direction cap", ignoreCase = true).assertExists()
        compose.onNodeWithText("Cap automatic paper entries").assertExists()
        compose.onNodeWithText("Also refuse the bots' live entries").assertExists()
        compose.onNodeWithText("never capped", substring = true).assertExists()
        assertEquals(2, ExposureCapHost.paperMax())
        assertFalse(ExposureCapHost.view.value.liveOn)
        // The number: 3.
        compose.onNodeWithText("3").performClick()
        compose.waitUntil(5_000) { ExposureCapHost.paperMax() == 3 }
        // The paper switch: off.
        compose.onNodeWithText("Cap automatic paper entries").performClick()
        compose.waitUntil(5_000) { ExposureCapHost.paperMax() == null }
        assertNull(ExposureCapHost.paperMax())
        // The live switch: on (only ever refuses the bots' live entries).
        compose.onNodeWithText("Also refuse the bots' live entries").performClick()
        compose.waitUntil(5_000) { ExposureCapHost.view.value.liveOn }
        assertTrue(ExposureCapHost.view.value.liveOn)
    }
}
