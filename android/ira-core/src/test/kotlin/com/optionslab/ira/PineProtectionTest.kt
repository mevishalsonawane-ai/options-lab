package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Boss's 06 Oct rule: every Pine strategy always has a stop-loss, a target and the profit lock. */
class PineProtectionTest {
    @Test fun theDefaultsAreThirtyAndSixty() {
        assertEquals(30.0, PineProtection.STOP)
        assertEquals(60.0, PineProtection.TARGET)
    }

    @Test fun missingStopAndTargetAreFilledInAndTheLockTurnedOn() {
        val e = PineProtection.normalise(0.0, 0.0, profitLock = false)
        assertEquals(30.0, e.stopPts); assertEquals(60.0, e.targetPts); assertTrue(e.profitLock)
        assertTrue(e.stopAdded); assertTrue(e.targetAdded); assertTrue(e.lockForced)
        // Unusable numbers count as none.
        val bad = PineProtection.normalise(Double.NaN, -5.0, true)
        assertEquals(30.0, bad.stopPts); assertEquals(60.0, bad.targetPts); assertFalse(bad.lockForced)
        assertEquals(30.0, PineProtection.normalise(null, 70.0, true).stopPts)
    }

    @Test fun aNonzeroStopIsNeverWidenedOrChanged() {
        val e = PineProtection.normalise(12.0, 0.0, true)
        assertEquals(12.0, e.stopPts); assertFalse(e.stopAdded)
        assertEquals(60.0, e.targetPts); assertTrue(e.targetAdded)
        val kept = PineProtection.normalise(25.0, 150.0, true)
        assertEquals(PineProtection.Exits(25.0, 150.0), kept)
    }

    @Test fun aZeroWrittenKeepsThePreviousValue() {
        val e = PineProtection.Exits.of(0.0, 0.0, false, beforeStop = 20.0, beforeTarget = 45.0)
        assertEquals(20.0, e.stopPts); assertEquals(45.0, e.targetPts)
        assertFalse(e.stopAdded); assertFalse(e.targetAdded); assertTrue(e.profitLock)
        assertEquals(30.0, PineProtection.keep(0.0, 0.0, 30.0))
    }

    @Test fun armingToTradeNeedsBoth() {
        assertEquals(PineProtection.ARM_REFUSAL, PineProtection.armRefusal("trade", 0.0, 60.0))
        assertEquals(PineProtection.ARM_REFUSAL, PineProtection.armRefusal("trade", 30.0, 0.0))
        assertEquals(PineProtection.ARM_REFUSAL, PineProtection.armRefusal("trade", null, null))
        assertTrue(PineProtection.ARM_REFUSAL.startsWith("Set a stop-loss and a target first"))
        assertNull(PineProtection.armRefusal("trade", 30.0, 60.0))
        assertNull(PineProtection.armRefusal("alert", 0.0, 0.0), "alerts only places nothing")
    }

    @Test fun typedValuesAreChecked() {
        assertEquals("Required", PineProtection.stopProblem(null))
        assertEquals("Required", PineProtection.stopProblem(0.0))
        assertNotEquals(null, PineProtection.stopProblem(2.0))
        assertNotEquals(null, PineProtection.stopProblem(900.0))
        assertNull(PineProtection.stopProblem(30.0))
        assertEquals("Required", PineProtection.targetProblem(0.0))
        assertNull(PineProtection.targetProblem(10.0))
        // A target below the stop is allowed, with a warning.
        assertTrue(PineProtection.warning(30.0, 20.0)!!.contains("below the stop"))
        assertNull(PineProtection.warning(30.0, 60.0))
    }

    @Test fun theLogLineSaysWhatWasAdded() {
        assertEquals("Pine 'EMA': stop-loss 30 and target 60 added, profit lock on — Boss's 06 Oct rule",
            PineProtection.addedNote("EMA", PineProtection.normalise(0.0, 0.0, true)))
        assertEquals("Pine 'X': target 60 added, profit lock on — Boss's 06 Oct rule",
            PineProtection.addedNote("X", PineProtection.normalise(25.0, 0.0, true)))
        assertEquals("Pine 'X': profit lock on — Boss's 06 Oct rule", PineProtection.addedNote("X", PineProtection.normalise(25.0, 50.0, false)))
        assertNull(PineProtection.addedNote("X", PineProtection.normalise(25.0, 50.0, true)))
    }

    private fun c(s: String) = Commands.parse(s)

    @Test fun jarvisRefusesToSwitchTheProtectionOff() {
        for (s in listOf("switch off profit lock for ema crossover", "turn off the profit lock", "disable profit lock on my pine script",
            "remove the stop loss from my pine script", "set target to 0 for the supertrend script", "pine script without stop loss",
            "profit lock off for supertrend")) {
            val cmd = c(s)
            assertEquals(Command.Kind.SET_REFUSED, cmd?.kind, s)
            assertEquals(PineProtection.REFUSED_TARGET, cmd?.target, s)
        }
        assertTrue(PineProtection.JARVIS_REFUSAL.contains("06 Oct rule"))
        // Other limits and the security refusal read as before.
        assertEquals(Command.Kind.SET_LIMIT, c("set max lots to 3")?.kind)
        assertEquals(Command.Kind.SET_LIMIT, c("turn off the loss limit")?.kind)
        val pin = c("change my PIN to 1234")
        assertEquals(Command.Kind.SET_REFUSED, pin?.kind); assertNull(pin?.target)
        assertFalse(PineProtection.breaksRule(" stop all scripts "))
        assertFalse(PineProtection.breaksRule(" turn off pine script ema "))
        assertFalse(PineProtection.breaksRule(" what is the profit lock "))
    }
}
