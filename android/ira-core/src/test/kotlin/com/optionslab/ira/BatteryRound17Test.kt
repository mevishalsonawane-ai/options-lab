package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OffHoursWarmPaceTest {
    /** The rule before round 17: market hours every pass, else every 10th pass (screen on) or 60th (screen off). */
    private fun before(open: Boolean, screenOn: Boolean, pass: Int): Boolean = open || pass % (if (screenOn) 10 else 60) == 0

    /** Counts the reads ahead of a stretch of 30 s listening passes, the screen given per pass. */
    private fun count(passes: Int, open: (Int) -> Boolean, screen: (Int) -> Boolean, rule: (Boolean, Boolean, Int, Boolean?) -> Boolean): Int {
        var reads = 0
        var last: Boolean? = null
        for (n in 0 until passes) {
            val on = screen(n)
            if (rule(open(n), on, n, last)) reads++
            last = on
        }
        return reads
    }

    @Test fun aWeekendDayWithTheScreenOffReadsNothing() {
        val passes = 24 * 60 * 2                                     // a day of 30 s passes
        val old = count(passes, { false }, { false }) { o, s, n, _ -> before(o, s, n) }
        val new = count(passes, { false }, { false }, OffHoursWarmPace::due)
        assertEquals(48, old, "every 30 minutes all day before")
        assertEquals(0, new)
    }

    @Test fun aWeeknightFromCloseToOpenReadsNothingWithTheScreenOff() {
        val passes = (17 * 60 + 45) * 2                              // 15:30 to 09:15
        assertEquals(36, count(passes, { false }, { false }) { o, s, n, _ -> before(o, s, n) })
        assertEquals(0, count(passes, { false }, { false }, OffHoursWarmPace::due))
    }

    @Test fun marketHoursAreUnchanged() {
        val passes = 375 * 2
        for (screenOn in listOf(true, false)) {
            assertEquals(passes, count(passes, { true }, { screenOn }, OffHoursWarmPace::due), "every pass, screen $screenOn")
            assertEquals(passes, count(passes, { true }, { screenOn }) { o, s, n, _ -> before(o, s, n) })
        }
    }

    @Test fun screenOnOffHoursKeepsItsFiveMinutePace() {
        val passes = 120
        val old = count(passes, { false }, { true }) { o, s, n, _ -> before(o, s, n) }
        val new = count(passes, { false }, { true }, OffHoursWarmPace::due)
        assertEquals(old, new)
        for (n in 0 until passes) assertEquals(before(false, true, n), OffHoursWarmPace.due(false, true, n, true))
    }

    @Test fun theScreenComingOnReadsAtOnce() {
        assertTrue(OffHoursWarmPace.due(open = false, screenOn = true, pass = 7, wasOn = false))
        assertFalse(OffHoursWarmPace.due(open = false, screenOn = true, pass = 7, wasOn = true))
        assertFalse(OffHoursWarmPace.due(open = false, screenOn = true, pass = 7, wasOn = null))
        assertTrue(OffHoursWarmPace.due(open = false, screenOn = true, pass = 0, wasOn = null), "start-up with the screen on")
        assertFalse(OffHoursWarmPace.due(open = false, screenOn = false, pass = 0, wasOn = null))
        assertFalse(OffHoursWarmPace.due(open = false, screenOn = false, pass = 60, wasOn = true))
    }

    @Test fun aNightWithAGlanceAtThePhoneReadsOnlyForTheGlance() {
        // 02:00 to 02:10 the screen on (passes 240..259) in an otherwise dark night: a read at once, then the 5-minute pace.
        val passes = 12 * 60 * 2
        val glance = 240 until 260
        val reads = count(passes, { false }, { it in glance }, OffHoursWarmPace::due)
        assertEquals(2, reads, "on waking (pass 240) and at 250 - not at 260 (dark again)")
        val old = count(passes, { false }, { it in glance }) { o, s, n, _ -> before(o, s, n) }
        assertEquals(25, old, "every 30 minutes in the dark, and the glance's two")
    }
}
