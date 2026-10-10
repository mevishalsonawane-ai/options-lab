package com.optionslab.engine.risk

import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Boss's 08 Oct items 6-8: the live GTT backup, the missed-lock sweeper and the no-price failsafe. */
class SafetyTest {
    @Test fun theBackupGttRestsAtTheStopsLimitAndOnlyMovesUp() {
        // An SL at 327.50 with its limit at 311.10: the GTT waits under the limit, where the SL can no longer fill.
        assertEquals(311.1, LiveBackup.trigger(327.5, 311.1, 0.05))
        // No limit known: the bots' own (5%, at least 2 points), on the tick, rounded down.
        assertEquals(311.1, LiveBackup.trigger(327.5, null, 0.05))
        assertEquals(28.0, LiveBackup.trigger(30.0, null, 0.05))
        assertEquals(28.0, LiveBackup.trigger(30.0, 30.0, 0.05), "a limit not under the trigger is not used")
        assertNull(LiveBackup.trigger(2.0, null, 0.05), "no price left under it")
        assertNull(LiveBackup.trigger(Double.NaN, null, 0.05))
        assertNull(LiveBackup.trigger(300.0, null, 0.0))
        // Never lowered.
        assertTrue(LiveBackup.moves(null, 256.5))
        assertTrue(LiveBackup.moves(256.5, 311.1))
        assertFalse(LiveBackup.moves(311.1, 311.1))
        assertFalse(LiveBackup.moves(311.1, 256.5))
        assertFalse(LiveBackup.moves(311.1, null))
    }

    @Test fun whenOneFiresTheOtherComesOut() {
        assertEquals(LiveBackup.Step.CANCEL_STOP, LiveBackup.step("TRIGGER PENDING", "triggered"))
        assertEquals(LiveBackup.Step.CANCEL_STOP, LiveBackup.step("OPEN", "triggered"))
        assertEquals(LiveBackup.Step.DROP_GTT, LiveBackup.step("COMPLETE", "active"))
        assertEquals(LiveBackup.Step.DROP_GTT, LiveBackup.step("CANCELLED", "active"))
        assertEquals(LiveBackup.Step.DROP_GTT, LiveBackup.step(null, "active"))
        assertEquals(LiveBackup.Step.KEEP, LiveBackup.step("TRIGGER PENDING", "active"))
        assertEquals(LiveBackup.Step.KEEP, LiveBackup.step("COMPLETE", "triggered"))
        assertEquals(LiveBackup.Step.KEEP, LiveBackup.step("trigger pending", null))
    }

    @Test fun aLockThePriceIsAlreadyUnderIsSoldNow() {
        assertTrue(MissedLock.missed(320.0, 327.5))
        assertTrue(MissedLock.missed(327.5, 327.5))
        assertFalse(MissedLock.missed(327.55, 327.5))
        assertFalse(MissedLock.missed(null, 327.5))
        assertFalse(MissedLock.missed(320.0, null))
        assertFalse(MissedLock.missed(Double.NaN, 327.5))
        assertFalse(MissedLock.missed(0.0, 327.5))
        assertFalse(MissedLock.missed(320.0, Double.NaN))
        assertEquals(310.4, MissedLock.limit(320.0, 0.05), 1e-9)
        assertEquals(19.0, MissedLock.limit(20.0, 0.05), 1e-9)
        assertEquals(0.05, MissedLock.limit(0.5, 0.05), 1e-9)
        assertEquals("Lock missed at 327.50, sold at 320.00", MissedLock.say(327.5, 320.0))
        assertEquals("BANKNIFTY27OCT2654900PE: Lock missed at 799.30, sold at 798.55", MissedLock.say(799.3, 798.55, "BANKNIFTY27OCT2654900PE"))
    }

    @Test fun noPricesForTwoMinutesIsALoudWarning() {
        val t0 = 1_000_000L
        assertTrue(PriceWatch.useRest(null, t0))
        assertFalse(PriceWatch.useRest(t0 - 30_000, t0))
        assertTrue(PriceWatch.useRest(t0 - 30_001, t0))
        assertFalse(PriceWatch.alert(t0 - 120_000, t0 - 600_000, t0))
        assertTrue(PriceWatch.alert(t0 - 120_001, t0 - 600_000, t0))
        assertFalse(PriceWatch.alert(null, t0 - 60_000, t0), "just opened: the clock starts at the open")
        assertTrue(PriceWatch.alert(null, t0 - 130_000, t0))
        assertFalse(PriceWatch.alert(t0 - 900_000, t0 - 10_000, t0), "a price before the open does not count against it")
        assertEquals("Positions not protected: no prices since 14:11", PriceWatch.say(LocalTime.of(14, 11, 40)))
    }
}
