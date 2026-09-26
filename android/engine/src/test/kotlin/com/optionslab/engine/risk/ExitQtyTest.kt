package com.optionslab.engine.risk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExitQtyTest {
    @Test fun remainingPrefersKitePending() {
        assertEquals(30, ExitQty.remaining(60, 30))
        assertEquals(15, ExitQty.remaining(60, 30, pending = 15))
        assertEquals(0, ExitQty.remaining(60, 90))
    }

    @Test fun sendableNeverExceedsHeldMinusWorking() {
        assertEquals(60, ExitQty.sendable(held = 90, working = 30, want = 90, lot = 30))
        assertEquals(30, ExitQty.sendable(held = 60, working = 0, want = 30, lot = 30))
        // A short: the sign of what is held does not matter.
        assertEquals(30, ExitQty.sendable(held = -60, working = 30, want = 60, lot = 30))
        // A partial manual close: send what is left, not the strategy's full quantity.
        assertEquals(30, ExitQty.sendable(held = 30, working = 0, want = 90, lot = 30))
    }

    @Test fun sendableIsZeroWhenCoveredAndRoundsDownToLots() {
        assertEquals(0, ExitQty.sendable(held = 30, working = 30, want = 30, lot = 30))
        assertEquals(0, ExitQty.sendable(held = 30, working = 45, want = 30, lot = 30))
        assertEquals(0, ExitQty.sendable(held = 0, working = 0, want = 30, lot = 30))
        assertEquals(60, ExitQty.sendable(held = 75, working = 15, want = 90, lot = 30))
        assertEquals(30, ExitQty.sendable(held = 70, working = 15, want = 90, lot = 30))
        assertEquals(0, ExitQty.sendable(held = 20, working = 0, want = 30, lot = 30))
        assertEquals(0, ExitQty.sendable(held = 60, working = -5, want = 0, lot = 30))
    }

    @Test fun shrinkToFollowsWhatIsStillHeld() {
        assertNull(ExitQty.shrinkTo(orderQty = 60, filled = 0, held = 60, lot = 30))
        assertNull(ExitQty.shrinkTo(orderQty = 60, filled = 30, held = 30, lot = 30))
        // The owner closed one of two lots by hand: the exit comes down to one lot.
        assertEquals(30, ExitQty.shrinkTo(orderQty = 60, filled = 0, held = -30, lot = 30))
        // The other exit part-filled first: what filled stays, the rest follows the position.
        assertEquals(60, ExitQty.shrinkTo(orderQty = 90, filled = 30, held = 30, lot = 30))
        assertEquals(0, ExitQty.shrinkTo(orderQty = 60, filled = 0, held = 0, lot = 30))
    }
}
