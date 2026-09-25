package com.optionslab.engine.risk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ProtectionTest {
    private fun long(stop: Double?, trail: Double?, best: Double, target: Double? = null) = Protection.Spec(1, stop, trail, target, best)
    private fun short(stop: Double?, trail: Double?, best: Double, target: Double? = null) = Protection.Spec(-1, stop, trail, target, best)

    @Test fun trailingLongOnlyTightens() {
        var s = long(Protection.initialStop(1, 200.0, null, 20.0), 20.0, 200.0)
        assertEquals(180.0, s.stop!!, 1e-9)
        s = Protection.next(s, 230.0)
        assertEquals(210.0, s.stop!!, 1e-9)
        s = Protection.next(s, 215.0)          // falls back: the stop stays
        assertEquals(210.0, s.stop!!, 1e-9)
        assertEquals(230.0, s.best, 1e-9)
        assertEquals("stop", Protection.hit(s, 209.95))
    }

    @Test fun trailingShortOnlyTightens() {
        var s = short(Protection.initialStop(-1, 100.0, null, 10.0), 10.0, 100.0)
        assertEquals(110.0, s.stop!!, 1e-9)
        s = Protection.next(s, 80.0)
        assertEquals(90.0, s.stop!!, 1e-9)
        s = Protection.next(s, 95.0)
        assertEquals(90.0, s.stop!!, 1e-9)
        assertEquals("stop", Protection.hit(s, 90.0))
    }

    @Test fun fixedStopNeverMoves() {
        val s = Protection.next(long(150.0, null, 200.0, target = 260.0), 250.0)
        assertEquals(150.0, s.stop!!, 1e-9)
        assertEquals("target", Protection.hit(s, 260.0))
        assertNull(Protection.hit(s, 200.0))
    }

    @Test fun ticksRoundAwayFromThePrice() {
        assertEquals(180.3, Protection.onTick(180.33, 0.05, down = true), 1e-9)
        assertEquals(180.35, Protection.onTick(180.33, 0.05, down = false), 1e-9)
        assertEquals(180.35, Protection.onTick(180.35, 0.05, down = true), 1e-9)
    }

    @Test fun validation() {
        assertNotNull(Protection.validate(1, 200.0, 210.0, null, null))     // stop above a long
        assertNotNull(Protection.validate(1, 200.0, null, null, 190.0))     // target below a long
        assertNotNull(Protection.validate(-1, 200.0, 190.0, null, null))    // stop below a short
        assertNotNull(Protection.validate(1, 200.0, null, 0.0, null))
        assertNotNull(Protection.validate(1, 200.0, null, null, null))
        assertNull(Protection.validate(1, 200.0, 180.0, null, 240.0))
        assertNull(Protection.validate(-1, 200.0, null, 15.0, 150.0))
    }
}
