package com.optionslab.app.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.security.PinLock
import com.optionslab.app.ui.theme.IraAlgoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/** The PIN pad: first-run setup (enter twice) and unlock. The PIN checks are fakes here. */
@RunWith(AndroidJUnit4::class)
class LockScreenTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule(order = 100) val dump = com.optionslab.app.testing.DumpOnFailure(compose)

    private fun type(digits: String) {
        digits.forEach { compose.onNodeWithText(it.toString()).performClick() }
        compose.waitForIdle()
    }

    private fun waitForText(text: String) =
        compose.waitUntil(20_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }   // the pad checks off the main thread: a slow CI runner

    private fun show(setup: Boolean, onPin: (CharArray) -> PinLock.Result = { PinLock.Result.Ok }, onCreate: (CharArray) -> String? = { null }) =
        compose.setContent {
            IraAlgoTheme("light") {
                LockScreen(setup = setup, biometricLabel = null, onBiometric = {}, onPin = onPin, onCreate = onCreate, notice = null, calm = true)
            }
        }

    @Test fun setupAsksTwiceThenCreatesThePin() {
        val created = Collections.synchronizedList(mutableListOf<String>())
        show(setup = true, onCreate = { created += String(it); null })
        compose.onNodeWithText("Choose a 6-digit PIN").assertIsDisplayed()
        type("135790")
        waitForText("Enter it again to confirm")
        type("135790")
        compose.waitUntil(5_000) { created.isNotEmpty() }
        assertEquals(listOf("135790"), created)
    }

    @Test fun setupStartsAgainWhenTheTwoDoNotMatch() {
        val created = Collections.synchronizedList(mutableListOf<String>())
        show(setup = true, onCreate = { created += String(it); null })
        type("135790")
        waitForText("Enter it again to confirm")
        type("135791")
        waitForText("Those did not match. Start again.")
        waitForText("Choose a 6-digit PIN")
        assertEquals(emptyList<String>(), created)
    }

    @Test fun setupShowsWhyAPinWasRefused() {
        show(setup = true, onCreate = { "a PIN of one repeated digit is too easy to guess" })
        type("111111")
        waitForText("Enter it again to confirm")
        type("111111")
        waitForText("a PIN of one repeated digit is too easy to guess")
    }

    @Test fun unlockSubmitsWithTheTickAndReportsAWrongPin() {
        // No PIN length is stored on this fresh "phone", so the pad shows the tick key.
        val tried = Collections.synchronizedList(mutableListOf<String>())
        show(setup = false, onPin = { tried += String(it); PinLock.Result.Wrong(3) })
        compose.onNodeWithText("Enter your PIN").assertIsDisplayed()
        type("2468")
        compose.onNodeWithText("⌫").performClick()
        type("913")
        compose.onNodeWithText("✓").performClick()
        waitForText("Not the right PIN. 3 before a pause.")
        assertEquals(listOf("246913"), tried)
    }

    @Test fun aKnownLengthUnlocksOnTheLastDigit() {
        PinLock.setPin("246813".toCharArray())      // stores the length (6) in the test vault
        val tried = Collections.synchronizedList(mutableListOf<String>())
        show(setup = false, onPin = { tried += String(it); PinLock.Result.LockedOut(30) })
        type("246813")
        // The banner shows the live countdown while locked out, the refusal once it ends.
        compose.waitUntil(5_000) {
            listOf("Too many attempts. Try again in 30 s.", "Too many attempts. Wait 30 s.")
                .any { compose.onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty() }
        }
        assertEquals(listOf("246813"), tried)
        assertNull("no tick key when the length is known", compose.onAllNodesWithText("✓").fetchSemanticsNodes().firstOrNull())
    }
}
