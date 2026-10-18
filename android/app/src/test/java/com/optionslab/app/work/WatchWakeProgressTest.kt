package com.optionslab.app.work

import com.optionslab.app.testing.RobolectricTest
import com.optionslab.ira.WatchHealth
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * 18 Oct, review (battery): the watch's pulse renews the processor's hold only while the loop makes progress - a pass's beat
 * or a step begun or ended in the last 5 minutes - so a pass hung on one call cannot keep the phone awake for ever.
 */
class WatchWakeProgressTest : RobolectricTest() {
    private fun now() = android.os.SystemClock.elapsedRealtime()

    @Test fun aBeatOrAStepKeepsItAndAHungPassLetsItGo() {
        val start = now()
        Heartbeat.beat(context)
        assertTrue(Heartbeat.keepsAwake(start))
        // A slow pass: one step after another, each a few minutes apart, still keeps it.
        repeat(3) {
            ShadowSystemClock.advanceBy(Duration.ofMinutes(4))
            Heartbeat.stepBegin("Zerodha positions")
            assertTrue(Heartbeat.keepsAwake(start))
            ShadowSystemClock.advanceBy(Duration.ofMinutes(4))
            Heartbeat.stepEnd()
            assertTrue(Heartbeat.keepsAwake(start))
        }
        // Then hung inside one step: let go once nothing moved for the limit.
        Heartbeat.stepBegin("index quotes")
        ShadowSystemClock.advanceBy(Duration.ofMillis(WatchHealth.AWAKE_PROGRESS_MS - 1_000))
        assertTrue(Heartbeat.keepsAwake(start))
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
        assertFalse("a hung pass no longer renews the hold", Heartbeat.keepsAwake(start))
        // The next beat brings it back.
        Heartbeat.beat(context)
        assertTrue(Heartbeat.keepsAwake(start))
        Heartbeat.stepEnd()
    }

    @Test fun aNewRunIsKeptUntilItsFirstPass() {
        Heartbeat.progressAt = 0L
        val start = now()
        assertTrue(Heartbeat.keepsAwake(start))
        ShadowSystemClock.advanceBy(Duration.ofMillis(WatchHealth.AWAKE_PROGRESS_MS))
        assertFalse(Heartbeat.keepsAwake(start))
    }
}
