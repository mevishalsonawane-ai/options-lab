package com.optionslab.engine.orb

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Range Fade: a bar at the outer tenth of the opening range that closes back inside is faded toward the middle. */
class RangeFadeRulesTest {
    private val day = LocalDate.of(2026, 9, 24)
    private fun at(h: Int, m: Int) = LocalDateTime.of(day, LocalTime.of(h, m))
    private val rng = 55200.0 to 55000.0          // 200 wide: the edges are 55180+ and 55020-
    private fun bar(h: Int, m: Int, high: Double, low: Double, close: Double) = Bar(at(h, m), close, high, low, close)

    @Test fun theArmIsPaperOnlyAndAFade() {
        assertTrue(RangeFadeRules.ARM.fade); assertTrue(RangeFadeRules.ARM.paperOnly); assertFalse(RangeFadeRules.ARM.sweep)
        assertTrue(RangeFadeRules.ARM !in OrbRules.ARMS)
    }

    @Test fun nearTheHighAndBackInsideBuysThePut() {
        assertEquals(-1 to "fade_of_high", RangeFadeRules.entrySignal(listOf(bar(10, 30, 55185.0, 55120.0, 55150.0)), rng, null, 0))
        // a sweep through the high that closes back inside is a fade too
        assertEquals(-1 to "fade_of_high", RangeFadeRules.entrySignal(listOf(bar(11, 0, 55240.0, 55120.0, 55150.0)), rng, null, 0))
    }

    @Test fun nearTheLowAndBackInsideBuysTheCall() {
        assertEquals(1 to "fade_of_low", RangeFadeRules.entrySignal(listOf(bar(12, 0, 55080.0, 55015.0, 55060.0)), rng, null, 0))
    }

    @Test fun inTheMiddleOrClosingOutsideIsNoFade() {
        assertEquals(0 to "not_at_the_edge", RangeFadeRules.entrySignal(listOf(bar(10, 30, 55150.0, 55050.0, 55100.0)), rng, null, 0))
        assertEquals(0 to "not_at_the_edge", RangeFadeRules.entrySignal(listOf(bar(10, 30, 55260.0, 55150.0, 55250.0)), rng, null, 0))
        assertEquals(0 to "no_range", RangeFadeRules.entrySignal(listOf(bar(10, 30, 55260.0, 55150.0, 55250.0)), 55000.0 to 55000.0, null, 0))
    }

    @Test fun windowLimitAndCooldown() {
        assertEquals(0 to "no_decision_bar", RangeFadeRules.entrySignal(listOf(bar(10, 25, 55185.0, 55120.0, 55150.0)), rng, null, 0))
        assertEquals(0 to "no_decision_bar", RangeFadeRules.entrySignal(listOf(bar(14, 0, 55185.0, 55120.0, 55150.0)), rng, null, 0))
        assertEquals(0 to "no_decision_bar", RangeFadeRules.entrySignal(emptyList(), rng, null, 0))
        assertEquals(0 to "day_limit_reached", RangeFadeRules.entrySignal(listOf(bar(11, 0, 55185.0, 55120.0, 55150.0)), rng, null, 2))
        assertEquals(0 to "cooling_down_after_exit", RangeFadeRules.entrySignal(listOf(bar(11, 0, 55185.0, 55120.0, 55150.0)), rng, at(11, 3), 1))
        assertEquals(-1 to "fade_of_high", RangeFadeRules.entrySignal(listOf(bar(11, 5, 55185.0, 55120.0, 55150.0)), rng, at(11, 3), 1))
    }
}
