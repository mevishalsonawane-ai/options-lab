package com.optionslab.ira

import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class WatchStoppedTest {
    private val legs = listOf(
        WatchStopped.Leg("Zerodha", "NIFTY25O0925000CE", 75, stop = 80.0, stopAtBroker = true),
        WatchStopped.Leg("Paper", "BANKNIFTY25O0757000CE", 30, by = "ORB", stop = 212.5, target = 292.5),
        WatchStopped.Leg("Paper", "FINNIFTY25O0727000PE", -65),
    )

    @Test fun nothingOpenSaysNothing() {
        assertNull(WatchStopped.text(LocalTime.of(9, 31), LocalTime.of(9, 35), emptyList(), false))
    }

    @Test fun sayWhatIsUnwatched() {
        val t = WatchStopped.text(LocalTime.of(9, 31, 40), LocalTime.of(9, 35), legs, false)!!
        assertTrue(t.startsWith("Boss, the order watch stopped: no check since 09:31 (3 minutes ago), with 3 positions open."), t)
        assertTrue(t.contains("- Paper BANKNIFTY25O0757000CE 30 (ORB): its stop 212.50 is not checked; its target 292.50 is not checked; its exit rules wait for the watch."), t)
        assertTrue(t.contains("- Paper FINNIFTY25O0727000PE short 65: no stop set."), t)
        assertTrue(t.contains("its stop 80 rests at Zerodha, so that still works"), t)
        // Paper before Zerodha.
        assertTrue(t.indexOf("Paper BANKNIFTY") < t.indexOf("Zerodha NIFTY"), t)
        assertTrue(t.contains(WatchStopped.BATTERY), t)
        assertTrue(t.endsWith(WatchStopped.NOTE), t)
    }

    @Test fun batteryAsItIs() {
        assertTrue(WatchStopped.text(null, LocalTime.of(9, 25), legs, true)!!.let { it.contains("has not run today") && it.contains(WatchStopped.BATTERY_SET) })
        assertTrue(WatchStopped.text(null, LocalTime.of(9, 25), legs, null)!!.contains(WatchStopped.BATTERY_MAYBE))
    }

    @Test fun onlySpeaksNeverAdvisesATrade() {
        val t = WatchStopped.text(LocalTime.of(10, 0), LocalTime.of(10, 6), legs, false)!!
        assertFalse(Regex("(?i)\\b(you should (buy|sell|close|exit)|i (will|have) (closed|sold|placed)(?!, moved and closed nothing))\\b").containsMatchIn(t), t)
        assertTrue(t.contains("Boss"))
    }

    @Test fun zerodhaUnreadIsSaid() {
        val t = WatchStopped.text(LocalTime.of(10, 0), LocalTime.of(10, 6), listOf(legs[1]), false, zerodhaUnread = true)!!
        assertTrue(t.contains("I could not read your Zerodha positions just now"), t)
    }

    @Test fun zerodhaUnreadWithNothingElseOpenIsStillSaid() {
        val t = WatchStopped.text(LocalTime.of(10, 0), LocalTime.of(10, 6), emptyList(), false, zerodhaUnread = true)
        assertTrue(t != null && t.startsWith("Boss, the order watch stopped: no check since 10:00 (6 minutes ago)."), t)
        assertTrue(t!!.contains(WatchStopped.UNREAD_EMPTY) && t.contains(WatchStopped.BATTERY) && t.endsWith(WatchStopped.NOTE), t)
        assertFalse(t.contains("positions open"), t)
        // Read and nothing open: still nothing said.
        assertNull(WatchStopped.text(LocalTime.of(10, 0), LocalTime.of(10, 6), emptyList(), false, zerodhaUnread = false))
    }

    @Test fun anArmStopRestingAtZerodhaIsSaidToWork() {
        // The stop and where it rests come from the same source (IraWatchStopped): an arm's trigger with its resting order.
        val t = WatchStopped.text(LocalTime.of(10, 0), LocalTime.of(10, 6),
            listOf(WatchStopped.Leg("Zerodha", "NIFTY25O0925000CE", 75, by = "ORB", stop = 61.0, stopAtBroker = true)), false)!!
        assertTrue(t.contains("its stop 61 rests at Zerodha, so that still works"), t)
        assertFalse(t.contains("kept by the app"), t)
    }

    @Test fun allStopsAtZerodha() {
        val t = WatchStopped.text(LocalTime.of(10, 0), LocalTime.of(10, 6), listOf(legs[0]), false)!!
        assertTrue(t.contains("with 1 position open") && t.contains("Every stop here rests at Zerodha"), t)
    }
}
