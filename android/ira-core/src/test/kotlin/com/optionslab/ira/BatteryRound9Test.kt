package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CheckWarmPaceTest {
    @Test fun anythingThatCanEnterKeepsItFast() {
        assertTrue(CheckWarmPace.fast(soloCanEnter = true, actsAlone = false, held = false), "Solo can reach its gate")
        assertTrue(CheckWarmPace.fast(soloCanEnter = false, actsAlone = true, held = false), "Jarvis takes paper trades alone")
        assertTrue(CheckWarmPace.fast(soloCanEnter = false, actsAlone = false, held = true), "something held or waiting to fill")
        assertFalse(CheckWarmPace.fast(soloCanEnter = false, actsAlone = false, held = false))
    }

    @Test fun fastIsEveryPass() {
        assertTrue(CheckWarmPace.due(30_000L, fast = true))
        assertTrue(CheckWarmPace.due(1_000L, fast = true))
    }

    @Test fun slowIsEveryTwoMinutes() {
        assertFalse(CheckWarmPace.due(30_000L, fast = false))
        assertFalse(CheckWarmPace.due(119_999L, fast = false))
        assertTrue(CheckWarmPace.due(CheckWarmPace.SLOW_MS, fast = false))
        assertTrue(CheckWarmPace.due(600_000L, fast = false))
    }

    @Test fun noneYetOrAClockOddityChecks() {
        assertTrue(CheckWarmPace.due(null, fast = false))
        assertTrue(CheckWarmPace.due(-5L, fast = false))
    }
}
