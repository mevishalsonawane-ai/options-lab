package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The day's stop and "start all" (Boss, 6 Oct): who stopped it is said; the loss limit's stop holds; "start all" only resumes. */
class DayStopTest {
    private val arms = listOf(DayStop.Arm("ORB", on = true), DayStop.Arm("ORB Fresh", on = true, needsPin = true),
        DayStop.Arm("ORB Sweep", on = false), DayStop.Arm("Range Fade", on = false), DayStop.Arm("Hero (expiry)", on = false))

    @Test fun theReasonIsKeptAndSaid() {
        assertEquals(DayStop.Why.BOSS, DayStop.Why.of(null))
        assertEquals(DayStop.Why.LOSS, DayStop.Why.of("loss"))
        assertEquals(DayStop.Why.TILE, DayStop.Why.of(DayStop.Why.TILE.wire))
        assertTrue(DayStop.line(DayStop.Why.BOSS).startsWith("Stopped for today by you"))
        assertTrue(DayStop.line(DayStop.Why.BOSS).contains("\"Start all\""))
        assertTrue(DayStop.line(DayStop.Why.LOSS).startsWith("Stopped for today by the daily loss limit"))
        assertFalse(DayStop.line(DayStop.Why.LOSS).contains("by you"))
        assertTrue(DayStop.bar(DayStop.Why.TILE).contains("from the quick-settings tile"))
    }

    @Test fun theLossLimitsStopIsNeverLiftedToday() {
        assertFalse(DayStop.mayLift(DayStop.Why.LOSS))
        assertTrue(DayStop.mayLift(DayStop.Why.BOSS) && DayStop.mayLift(DayStop.Why.TILE))
        val st = DayStop.startAll(DayStop.Why.LOSS, arms)
        assertFalse(st.lift)
        assertEquals(DayStop.LOSS_HOLDS, st.say)
    }

    @Test fun startAllResumesWhatWasOnAndNeverSwitchesOnWhatIsOff() {
        val st = DayStop.startAll(DayStop.Why.BOSS, arms)
        assertTrue(st.lift)
        assertTrue(st.say.startsWith("lift today's stop (made by you): ORB and ORB Fresh trade again"), st.say)
        assertTrue(st.say.contains("Off, and left off: ORB Sweep, Range Fade and Hero (expiry)"), st.say)
        // Live: what is not cleared for Zerodha needs Boss's fingerprint in the app - never by voice.
        assertTrue(st.say.contains("In Live, ORB Fresh needs your fingerprint or PIN in the app"), st.say)
    }

    @Test fun nothingStoppedMeansNothingIsStarted() {
        val st = DayStop.startAll(null, arms)
        assertFalse(st.lift)
        assertTrue(st.say.startsWith("Nothing is stopped for today, Boss: ORB and ORB Fresh are already on."), st.say)
        assertFalse(DayStop.startAll(null, emptyList()).lift)
    }
}
