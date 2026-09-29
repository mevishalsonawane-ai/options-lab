package com.optionslab.engine.orb

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ORB Sweep: a wick through the opening range that closes back inside is faded; exits -40 / +80 / 15:10. */
class SweepRulesTest {
    private val day = LocalDate.of(2026, 9, 24)
    private fun at(h: Int, m: Int) = LocalDateTime.of(day, LocalTime.of(h, m))
    private val rng = 55200.0 to 55000.0
    private fun bar(h: Int, m: Int, high: Double, low: Double, close: Double) = Bar(at(h, m), close, high, low, close)

    @Test fun theArmIsPaperOnlyAndASweep() {
        assertTrue(SweepRules.ARM.sweep); assertTrue(SweepRules.ARM.paperOnly); assertFalse(SweepRules.ARM.freshOnly)
        assertFalse(OrbRules.ORB.sweep); assertFalse(OrbRules.ORB.paperOnly)
        assertTrue(SweepRules.ARM !in OrbRules.ARMS)                         // the ORB forward test's arms are unchanged
    }

    @Test fun aSweepOfTheHighThatClosesBackInsideBuysThePut() {
        assertEquals(-1 to "sweep_of_high", SweepRules.entrySignal(listOf(bar(10, 30, 55260.0, 55150.0, 55180.0)), rng, null, 0))
    }

    @Test fun aSweepOfTheLowThatClosesBackInsideBuysTheCall() {
        assertEquals(1 to "sweep_of_low", SweepRules.entrySignal(listOf(bar(11, 0, 55050.0, 54940.0, 55020.0)), rng, null, 0))
    }

    @Test fun aRealBreakoutIsNotASweep() {
        // closes beyond the level: that is the ORB's break, not a failed one
        assertEquals(0 to "no_sweep", SweepRules.entrySignal(listOf(bar(10, 30, 55300.0, 55150.0, 55250.0)), rng, null, 0))
        assertEquals(0 to "no_sweep", SweepRules.entrySignal(listOf(bar(10, 30, 55150.0, 55050.0, 55100.0)), rng, null, 0))
    }

    @Test fun onlyDecisionBarsAndTheDayLimitAndTheCooldown() {
        assertEquals(0 to "no_decision_bar", SweepRules.entrySignal(listOf(bar(10, 0, 55260.0, 55150.0, 55180.0)), rng, null, 0))
        assertEquals(0 to "no_decision_bar", SweepRules.entrySignal(listOf(bar(14, 30, 55260.0, 55150.0, 55180.0)), rng, null, 0))
        assertEquals(0 to "no_decision_bar", SweepRules.entrySignal(emptyList(), rng, null, 0))
        assertEquals(0 to "day_limit_reached", SweepRules.entrySignal(listOf(bar(11, 0, 55260.0, 55150.0, 55180.0)), rng, null, 2))
        assertEquals(0 to "cooling_down_after_exit", SweepRules.entrySignal(listOf(bar(11, 0, 55260.0, 55150.0, 55180.0)), rng, at(11, 2), 1))
        assertEquals(-1 to "sweep_of_high", SweepRules.entrySignal(listOf(bar(11, 5, 55260.0, 55150.0, 55180.0)), rng, at(11, 2), 1))
    }

    @Test fun exitsAreMinusFortyPlusEightyAndTheSquareOff() {
        assertNull(SweepRules.exitReason(200.0, 200.0, at(11, 0)))
        assertEquals("stop", SweepRules.exitReason(200.0, 160.0, at(11, 0)))
        assertNull(SweepRules.exitReason(200.0, 240.0, at(11, 0)))                 // +40 is the ORB's target, not this one
        assertEquals("target", SweepRules.exitReason(200.0, 280.0, at(11, 0)))
        assertEquals("session_end", SweepRules.exitReason(200.0, 210.0, at(15, 10)))
        assertNull(SweepRules.exitReason(30.0, 1.0, at(11, 0)))                    // no stop at or below zero
    }
}
