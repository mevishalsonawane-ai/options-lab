package com.optionslab.app.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.testing.AreaE
import com.optionslab.app.testing.AreaEWatchdog
import com.optionslab.app.testing.waitForText
import com.optionslab.app.ui.theme.IraAlgoTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 18 Oct, review: Settings -> Jarvis's Solo card read Solo's trades (a vault file, decrypted on a first read) twice during
 * composition, on the main thread. Both are now read off it, and the record line still shows once read.
 */
@org.robolectric.annotation.Config(qualifiers = "w411dp-h900dp")
@RunWith(AndroidJUnit4::class)
class SoloCardTest {
    // 21 Oct, enhancement round 4: the market clock at noon IST on a trading day unless the test sets its own.
    @get:org.junit.Rule val marketClock = com.optionslab.app.testing.MarketClockRule()
    @get:Rule val watchdog = AreaEWatchdog()
    @get:Rule val compose = createComposeRule()

    @Before fun up() { AreaE.resetGlobals() }
    @After fun down() { AreaE.resetGlobals() }

    @Test fun theRecordLineShowsOnceReadOffTheMainThread() {
        compose.setContent { IraAlgoTheme("light") { SoloCard() } }
        // No Solo trades on a fresh phone: its record line says so, read off the main thread and shown when read.
        compose.waitForText("Solo (midday) has no closed trades yet.", substring = true)
        compose.waitForText("Solo (midday) is", substring = true)
    }
}
