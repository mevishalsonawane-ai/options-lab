package com.optionslab.engine.signals

import com.optionslab.engine.Right
import com.optionslab.engine.signals.CallParser.Draft
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A reading checked against reality before it counts ([CallCheck]): the grid, the distance from spot, the numbers, the expiry. */
class CallCheckTest {
    private val on = LocalDate.of(2026, 10, 15)
    private val ok = Draft("SENSEX", 73100.0, Right.CE, null, CallParser.Trig.ABOVE, 280.0, null, 250.0, null, listOf(310.0, 345.0, 400.0))

    @Test fun theSampleAgainstASensexNear73000Passes() {
        assertEquals(emptyList(), CallCheck.problems(ok, on, known = true, spot = 72_950.0, listed = true))
        // Nothing to check against: still no problems (the phone cannot tell).
        assertEquals(emptyList(), CallCheck.problems(ok, on, known = null, spot = null, listed = null))
    }

    @Test fun eachProblemIsNamed() {
        fun p(d: Draft, spot: Double? = 73_000.0, known: Boolean? = true, listed: Boolean? = null) = CallCheck.problems(d, on, known, spot, listed)
        assertEquals(listOf("no index or stock named", "no strike", "no CE / PE", "no entry price"), p(Draft(), spot = null))
        assertEquals(listOf("strike 73150 is not on SENSEX's grid (every 100)"), p(ok.copy(strike = 73150.0)))
        assertEquals(listOf("strike 80000 is 10% from SENSEX's 73000"), p(ok.copy(strike = 80000.0)))
        assertEquals(listOf("XYZ is not an index or a stock the app knows"), p(ok.copy(underlying = "XYZ"), spot = null, known = false))
        assertEquals(listOf("strike 73100 is not a listed TCS strike"), p(ok.copy(underlying = "TCS"), spot = null, listed = false))
        // A stock may be 20% away.
        assertEquals(emptyList(), p(ok.copy(underlying = "TCS", strike = 4000.0), spot = 3500.0, listed = true))
        assertEquals(listOf("the entry is not a price"), p(ok.copy(trigger = 0.0, targets = emptyList(), stop = null)))
        assertEquals(listOf("the entry 30000 is not an option premium for SENSEX", "a target is not above the entry", "the stop is not below the entry"),
            p(ok.copy(trigger = 30000.0, stop = 31000.0)))
        assertEquals(listOf("the targets are not in rising order"), p(ok.copy(targets = listOf(345.0, 310.0, 400.0))))
        assertEquals(listOf("the stop is not a price"), p(ok.copy(stop = -5.0)))
        assertEquals(listOf("the expiry 2026-10-14 had already passed"), p(ok.copy(expiry = LocalDate.of(2026, 10, 14))))
        assertEquals(emptyList(), p(ok.copy(trig = CallParser.Trig.CMP, trigger = null, stop = null)))
        assertTrue(p(ok, spot = 0.0).isEmpty())
    }

    @Test fun theNearestListedExpiry() {
        val l = listOf(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 13))
        assertEquals(LocalDate.of(2026, 10, 15), CallCheck.nearestExpiry(on, l))
        assertEquals(LocalDate.of(2026, 10, 20), CallCheck.nearestExpiry(on.plusDays(1), l))
        assertNull(CallCheck.nearestExpiry(on.plusDays(30), l))
    }
}
