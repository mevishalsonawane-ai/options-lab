package com.optionslab.app.data

import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.strategy.Event
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 20 Nov 2026 (minor fixes, item 2): a Strategy Module leg whose exit is refused look after look (a square-off took it over) is told
 * once per stuck episode, then at most every 15 minutes; another leg, another run, or the run started again is told at once; every
 * other alert is told as before.
 */
class StrategiesStuckAlertTest : RobolectricTest() {
    private val min = 60_000L
    private fun rejected(leg: Int) = Event("leg_exit_rejected", "Exit rejected on leg $leg: already on its way out", "critical", leg)

    @Test fun aStuckExitIsToldOnceThenEveryFifteenMinutes() {
        val run = "Stuck test ${System.nanoTime()}"
        assertTrue(Strategies.tellsNow(run, rejected(1), 0L))
        for (m in 1L..14L) assertFalse("minute $m", Strategies.tellsNow(run, rejected(1), m * min))
        assertTrue("the reminder", Strategies.tellsNow(run, rejected(1), 15 * min))
        assertTrue("another leg", Strategies.tellsNow(run, rejected(2), 15 * min))
        assertTrue("a stop refused is its own episode", Strategies.tellsNow(run, Event("run_stop_failed", "Stop refused", "critical"), 15 * min))
        assertFalse(Strategies.tellsNow(run, Event("run_stop_failed", "Stop refused", "critical"), 16 * min))
        // Every other alert as before.
        assertTrue(Strategies.tellsNow(run, Event("overall_sl_hit", "-5000", "critical"), 16 * min))
        assertTrue(Strategies.tellsNow(run, Event("overall_sl_hit", "-5000", "critical"), 16 * min))
        // The run stopped: its next stuck exit is a new episode, told at once.
        assertTrue(Strategies.tellsNow(run, Event("run_stopped", "stopped"), 17 * min))
        assertTrue(Strategies.tellsNow(run, rejected(1), 17 * min))
    }

    /** Review: each kind its own episode; a command's warning never starts a quiet period; a new entry on the leg starts afresh. */
    @Test fun kindsWarningsAndANewEntryEachKeepTheAlertsTold() {
        val run = "Stuck test b ${System.nanoTime()}"
        assertTrue(Strategies.tellsNow(run, rejected(3), 0L))
        // A late fill's reconciliation alert on the same leg is another kind: told.
        assertTrue(Strategies.tellsNow(run, Event("run_stop_failed", "A late broker entry fill for leg 3", "critical", 3), min))
        // Boss's own command answered with a warning: said, and the engine's critical refusal on leg 4 still told after it.
        assertTrue(Strategies.tellsNow(run, Event("leg_exit_rejected", "That leg is not open", "warn", 4), min))
        assertTrue(Strategies.tellsNow(run, Event("leg_exit_rejected", "That leg is not open", "warn", 4), min))
        assertTrue(Strategies.tellsNow(run, rejected(4), 2 * min))
        // Leg 3 re-entered: its next stuck exit is a new episode, told at once (not 15 minutes after the first).
        assertFalse(Strategies.tellsNow(run, rejected(3), 5 * min))
        assertTrue(Strategies.tellsNow(run, Event("leg_entry_placed", "Entry placed", "info", 3), 6 * min))
        assertTrue(Strategies.tellsNow(run, rejected(3), 7 * min))
        assertFalse("leg 4's episode is untouched", Strategies.tellsNow(run, rejected(4), 7 * min))
    }
}
