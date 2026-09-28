package com.optionslab.app.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.ui.theme.IraAlgoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The compromised-device screen, in both themes. */
@RunWith(AndroidJUnit4::class)
class RefusedScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun listsTheFindingsAndCloses() {
        var theme by mutableStateOf("light")
        var quit = 0
        compose.setContent {
            IraAlgoTheme(theme) { RefusedScreen(listOf("su binary present", "test-keys build"), onQuit = { quit++ }) }
        }
        for (t in listOf("light", "dark")) {
            theme = t
            compose.waitForIdle()
            compose.onNodeWithText("IraAlgo will not open on this device").assertIsDisplayed()
            compose.onNodeWithText("• su binary present").assertIsDisplayed()
            compose.onNodeWithText("• test-keys build").assertIsDisplayed()
            compose.onNodeWithText("Close").performClick()
        }
        assertEquals(2, quit)
    }
}
