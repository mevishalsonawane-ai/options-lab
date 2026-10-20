package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 20 Oct, review: the 15-second stop check's card waits ([BatterySaver.nextWait]) never run past the window, whatever the
 * battery. Before, a 6 s step (20% battery) waited 18 s and a 12 s step (10%) 24 s before the stops after it ran.
 */
class StopWindowTest {
    private val window = 15_000L

    @Test fun theWaitsSumToTheWindowAtEveryBatteryLevel() {
        for ((pct, charging) in listOf(50 to false, 15 to false, 5 to false, 5 to true, null to false)) {
            val step = BatterySaver.gap(3_000L, pct, charging)
            val w = BatterySaver.waits(window, step)
            assertEquals(window, w.sum(), "battery $pct charging $charging step $step: $w")
            assertTrue(w.all { it in 1..step }, "$w")
        }
    }

    @Test fun lowBatteryStepsAreCutAtTheWindowsEnd() {
        assertEquals(listOf(3_000L, 3_000L, 3_000L, 3_000L, 3_000L), BatterySaver.waits(window, 3_000L))
        assertEquals(listOf(6_000L, 6_000L, 3_000L), BatterySaver.waits(window, 6_000L))
        assertEquals(listOf(12_000L, 3_000L), BatterySaver.waits(window, 12_000L))
    }

    @Test fun edgeCasesNeverWaitNegativeOrForever() {
        assertEquals(0L, BatterySaver.nextWait(-500L, 3_000L), "the window already over: no wait")
        assertEquals(0L, BatterySaver.nextWait(0L, 12_000L))
        assertEquals(1L, BatterySaver.nextWait(15_000L, 0L), "a zero step still moves on")
        assertTrue(BatterySaver.waits(0L, 3_000L).isEmpty())
    }
}
