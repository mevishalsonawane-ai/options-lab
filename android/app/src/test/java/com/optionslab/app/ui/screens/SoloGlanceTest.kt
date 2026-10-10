package com.optionslab.app.ui.screens

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.engine.orb.SoloMidday
import com.optionslab.ira.SoloProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Solo (midday)'s forward test at a glance on its card: five trades running, one trade, switched itself off. */
@org.robolectric.annotation.Config(qualifiers = "w411dp-h2400dp")
@RunWith(AndroidJUnit4::class)
class SoloGlanceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val five = SoloGlanceRead(SoloProgress.of(listOf(1_000.0, -400.0, -700.0, 1_500.0, -200.0), on = true, paused = null),
        "Decides at 12:00 today (orders only until 12:03).")
    private val one = SoloGlanceRead(SoloProgress.of(listOf(-420.0), on = true, paused = null), "Decided: traded X - net −₹420 after charges.")
    private val off = SoloGlanceRead(SoloProgress.of(listOf(2_000.0, -15_000.0, -13_000.0), on = false, paused = "Boss, Solo switched itself off."),
        "Off: no decision today.")

    private fun shows(t: String, sub: Boolean = false) = compose.onAllNodesWithText(t, substring = sub).fetchSemanticsNodes().isNotEmpty()
    private fun described(d: String) = compose.onAllNodesWithContentDescription(d, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun card(r: SoloGlanceRead) = compose.setContent {
        IraAlgoTheme("light") { Column(Modifier.verticalScroll(rememberScrollState())) { SoloGlanceContent(r) } }
    }

    @Test fun theForwardTestAtAGlance() {
        card(five)
        compose.waitForIdle()
        assertTrue(shows("FORWARD TEST"))
        assertTrue(shows(keepNumbersWhole("5 of 60 forward-test trades")))
        assertTrue(described("Forward test: 5 of 60 forward-test trades"))
        assertTrue("sparkline from 2 trades", described("Solo's running net a trade: 5 trades"))
        assertTrue(shows(keepNumbersWhole(five.glance.netLine)))
        assertTrue(five.glance.netLine.contains("+₹1,200 total, +₹1,200 per lot") && five.glance.netLine.contains("won 2 of 5 (40%)"))
        assertTrue(shows(keepNumbersWhole(five.glance.drawdownLine)))
        assertTrue(described("Drawdown against the switch-off line"))
        assertTrue(shows(keepNumbersWhole("Decides at 12:00 today (orders only until 12:03).")))
        assertTrue(shows(keepNumbersWhole(five.glance.research)))
        assertTrue(five.glance.research.contains("about break-even"))
        assertFalse(shows("Switched itself off", sub = true))
    }

    @Test fun oneTradeHasNoSparkline() {
        card(one)
        compose.waitForIdle()
        assertTrue(shows(keepNumbersWhole("1 of 60 forward-test trades")))
        assertFalse(described("Solo's running net a trade"))
        assertTrue(shows(keepNumbersWhole("Decided: traded X - net −₹420 after charges.")))
    }

    @Test fun switchedItselfOffSaysWhoSwitchesItBackOn() {
        card(off)
        compose.waitForIdle()
        assertEquals(SoloMidday.Verdict.FAILED_DRAWDOWN, off.glance.verdict)
        assertTrue(shows(keepNumbersWhole(off.glance.offLine!!)))
        assertTrue(off.glance.offLine!!.contains("Switching it back on is Boss's switch"))
        assertTrue(shows(keepNumbersWhole(off.glance.drawdownLine)))
    }

    @Test fun readOffTheMainThreadOnceAndAgainOnTheSwitch() {
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        val reads = AtomicInteger()
        val offMain = AtomicInteger()
        var key by mutableIntStateOf(0)
        compose.setContent {
            IraAlgoTheme("light") {
                SoloForwardGlance(key) {
                    reads.incrementAndGet()
                    if (Looper.myLooper() != Looper.getMainLooper()) offMain.incrementAndGet()
                    five
                }
            }
        }
        compose.waitUntil(10_000) { shows("FORWARD TEST") }
        compose.waitForIdle()
        assertEquals(1, reads.get())
        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot { key = 1 }   // applied at once, so the effect re-keys
        compose.waitUntil(10_000) { reads.get() == 2 }
        compose.waitForIdle()
        assertEquals(2, reads.get())
        assertEquals(2, offMain.get())
    }

    @Test fun anUnreadableRecordShowsNothing() {
        compose.setContent { IraAlgoTheme("dark") { SoloForwardGlance(0) { error("unreadable") } } }
        compose.waitForIdle()
        assertFalse(shows("FORWARD TEST"))
    }

    @Test fun hiddenInTheGoldBuild() {
        org.junit.Assume.assumeTrue(com.optionslab.app.BuildConfig.GOLD)
        val reads = AtomicInteger()
        compose.setContent { IraAlgoTheme("light") { SoloForwardGlance(0) { reads.incrementAndGet(); five } } }
        compose.waitForIdle()
        assertFalse(shows("FORWARD TEST"))
        assertEquals("nothing read", 0, reads.get())
    }
}
