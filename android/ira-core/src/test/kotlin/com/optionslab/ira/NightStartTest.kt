package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 22 Oct (the overnight kills): the rules that keep a start in the background light ([NightStart]). */
class NightStartTest {
    private val boot = 1_760_000_000_000L
    private val now = boot + 3_600_000L
    private val at = now + 8 * 3_600_000L
    private val mark = NightStart.AlarmMark(at, 42, boot)

    @Test fun anAlarmArmedForTheSameTimeInTheSameBootAndVersionIsLeftAsItIs() {
        assertFalse(NightStart.needsArm(mark, mark, pendingThere = true, nowMs = now))
        // The boot estimate wobbles by a second or two (the wall clock read a moment later): still the same boot.
        assertFalse(NightStart.needsArm(mark, mark.copy(bootAtMs = boot + 2_000), pendingThere = true, nowMs = now))
    }

    @Test fun anythingChangedOrUnknownArmsTheAlarmAgain() {
        assertTrue(NightStart.needsArm(null, mark, true, now), "nothing kept")
        assertTrue(NightStart.needsArm(mark, mark.copy(atMs = at + 86_400_000L), true, now), "a new time (a day passed, a holiday)")
        assertTrue(NightStart.needsArm(mark, mark.copy(version = 43), true, now), "an app update drops alarms")
        assertTrue(NightStart.needsArm(mark, mark.copy(bootAtMs = boot + 60_000), true, now), "a reboot drops alarms")
        assertTrue(NightStart.needsArm(mark, mark, pendingThere = false, nowMs = now), "Android no longer holds it (force stop)")
        assertTrue(NightStart.needsArm(mark, mark, true, nowMs = at), "its time has come: armed again")
        assertTrue(NightStart.needsArm(mark, mark.copy(atMs = NightStart.OFF), true, now), "turned off: cancelled")
        assertTrue(NightStart.needsArm(mark.copy(atMs = NightStart.OFF), mark, true, now), "turned on: armed")
    }

    @Test fun anAlarmOffBothTimesNeedsNothing() {
        val off = mark.copy(atMs = NightStart.OFF)
        assertFalse(NightStart.needsArm(off, off, pendingThere = false, nowMs = now))
    }

    @Test fun marksReadBackAndBadWordsAreNoMark() {
        assertEquals(mark, NightStart.AlarmMark.decode(mark.encode()))
        assertEquals(NightStart.AlarmMark(NightStart.OFF, 1, 2), NightStart.AlarmMark.decode("-1|1|2"))
        assertNull(NightStart.AlarmMark.decode(null))
        assertNull(NightStart.AlarmMark.decode(""))
        assertNull(NightStart.AlarmMark.decode("1|2"))
        assertNull(NightStart.AlarmMark.decode("a|2|3"))
        assertNull(NightStart.AlarmMark.decode("1|b|3"))
        assertNull(NightStart.AlarmMark.decode("1|2|c"))
    }

    /** A kill mid-run cannot hand the night a fresh 10 minutes: what was spent comes back for the same budget only. */
    @Test fun theSpentCpuComesBackForTheSameBudgetOnly() {
        val night = "2026-10-21 night"
        val kept = NightStart.carryEncode(night, 312_000)
        assertEquals(312_000, NightStart.carried(night, kept))
        assertEquals(0, NightStart.carried("2026-10-22 day", kept), "the next budget starts afresh")
        assertEquals(0, NightStart.carried("2026-10-22 night", kept))
        assertEquals(0, NightStart.carried(night, null))
        assertEquals(0, NightStart.carried(night, "garbage"))
        assertEquals(0, NightStart.carried(night, "|5"))
        assertEquals(0, NightStart.carried(night, "$night|x"))
        assertEquals(0, NightStart.carried(night, NightStart.carryEncode(night, -5)), "never negative")
    }

    @Test fun theSpentCpuIsWrittenEveryFewSeconds() {
        assertFalse(NightStart.saveDue(10_000, 14_999))
        assertTrue(NightStart.saveDue(10_000, 15_000))
        assertFalse(NightStart.saveDue(10_000, 10_000))
        assertTrue(NightStart.saveDue(0, 1, stepMs = 1))
    }

    @Test fun aKeptDayIsReadBack() {
        assertEquals(LocalDate.of(2026, 10, 22), NightStart.dayOf("2026-10-22"))
        assertNull(NightStart.dayOf(null))
        assertNull(NightStart.dayOf("22 Oct"))
    }
}
