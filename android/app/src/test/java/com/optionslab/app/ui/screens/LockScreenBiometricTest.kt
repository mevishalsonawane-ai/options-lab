package com.optionslab.app.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.security.PinLock
import com.optionslab.app.ui.theme.IraAlgoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * The fingerprint is asked for by itself once each time the app comes to the front: not only the first time the
 * lock screen appears (the app seals while in the background, where a prompt is cancelled by Android), and never
 * twice in one visit (cancelling it must not bring it straight back).
 */
@RunWith(AndroidJUnit4::class)
class LockScreenBiometricTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val asked = AtomicInteger()

    private fun show(label: String?) = compose.setContent {
        IraAlgoTheme("light") {
            LockScreen(setup = false, biometricLabel = label, onBiometric = { asked.incrementAndGet() },
                onPin = { PinLock.Result.Ok }, onCreate = { null }, notice = null, calm = true)
        }
    }

    private fun settle() { compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle() }

    @Test fun askedOnceWhenTheLockScreenAppears() {
        show("Use fingerprint")
        settle(); settle()
        assertEquals(1, asked.get())
    }

    @Test fun askedAgainEachTimeTheAppReturnsToTheFront() {
        show("Use fingerprint")
        settle()
        assertEquals(1, asked.get())
        // Left the app and came back: asked again.
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        settle()
        assertEquals(2, asked.get())
    }

    @Test fun aPauseWithoutLeavingDoesNotAskAgain() {
        show("Use fingerprint")
        settle()
        // The prompt's own dialog pauses the screen; dismissing it resumes it. That is not a new visit.
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        settle()
        assertEquals(1, asked.get())
    }

    @Test fun notAskedWithoutAFingerprint() {
        show(null)
        settle()
        assertEquals(0, asked.get())
    }
}
