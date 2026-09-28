package com.optionslab.app.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.ui.theme.IraAlgoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The logo screen shown as the app opens fresh: the emblem and the name, in both themes. */
@RunWith(AndroidJUnit4::class)
class SplashScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun shows(theme: String) {
        compose.setContent { IraAlgoTheme(theme) { SplashScreen() } }
        compose.onNodeWithContentDescription("IraAlgo").assertExists()
        compose.onNodeWithText("IraAlgo").assertExists()
        compose.onNodeWithText("Intelligent algorithmic trading").assertExists()
    }

    @Test fun showsTheEmblemAndNameInTheLightTheme() = shows("light")
    @Test fun showsTheEmblemAndNameInTheDarkTheme() = shows("dark")
}
