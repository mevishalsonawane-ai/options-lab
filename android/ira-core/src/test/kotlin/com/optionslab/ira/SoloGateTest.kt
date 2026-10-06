package com.optionslab.ira

import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SoloGateTest {
    private fun rec(scored: Int, hit: Double, n: Double, bandHit: Double, band: String = "70-100% sure") =
        SoloGate.Record(scored, hit, band, n, bandHit)

    @Test fun theIndexsRollingRecordMustBe58PercentOverAtLeast200() {
        assertEquals("watching: only 120 confident BankNifty guesses scored so far (it trades from 200, at 58% right or better)",
            SoloGate.why("BankNifty", rec(120, 0.70, 80.0, 60.0)))
        assertEquals("watching: its last 400 confident BankNifty guesses were 49% right (it trades only from 58%)",
            SoloGate.why("BankNifty", rec(400, 0.49, 80.0, 60.0)))
        assertNotNull(SoloGate.why("Nifty", rec(400, 0.5799, 80.0, 60.0)), "57.99% is not 58%")
        assertNull(SoloGate.why("Nifty", rec(200, 0.58, 80.0, 60.0)))
    }

    @Test fun theBandItTradesInMustHaveEarnedItToo() {
        // 06 Oct: 70-100% sure, right 43% - inverted calibration: watching.
        assertEquals("watching: when 70-100% sure on Nifty it was right only 43% of 86 times (it trades a band only from 58%)",
            SoloGate.why("Nifty", rec(400, 0.62, 86.0, 0.43 * 86)))
        assertEquals("watching: when 60-65% sure on Nifty it has only 32 scored guesses (it trades a band from 50)",
            SoloGate.why("Nifty", rec(400, 0.62, 32.0, 30.0, "60-65% sure")))
        assertNull(SoloGate.why("Nifty", rec(400, 0.62, 50.0, 29.0)))
    }

    @Test fun neverOnTheIndexsExpiryDayNorAfter1445() {
        assertNull(SoloGate.clock("Nifty", LocalTime.of(14, 44), expiryDay = false))
        assertEquals("watching: no new trades after 14:45", SoloGate.clock("Nifty", LocalTime.of(14, 45), expiryDay = false))
        assertEquals("watching: no new trades after 14:45", SoloGate.clock("Nifty", LocalTime.of(15, 0), expiryDay = null))
        assertEquals("watching: BankNifty expires today - Solo never buys options on an expiry day",
            SoloGate.clock("BankNifty", LocalTime.of(11, 0), expiryDay = true))
        assertTrue(SoloGate.clock("Nifty", LocalTime.of(11, 0), expiryDay = null)!!.contains("could not be read"), "unknown: no trade")
    }

    /** [n] confident guesses at [p], right on [right] of every [of]. */
    private fun Learner.guesses(p: Double, right: Int, of: Int, i: Int) =
        learn(DoubleArray(Learner.DIM), up = if (i % of < right) p > 0.5 else p < 0.5, guess = p)

    @Test fun theLearnersGateReadsItsOwnRecordAndTheBand() {
        // Boss's 06 Oct numbers: 53% overall - watching, whatever the band.
        val weak = Learner()
        repeat(400) { weak.guesses(0.62, 53, 100, it) }
        assertTrue(weak.gate(0.62, "Nifty")!!.startsWith("watching: its last 400 confident Nifty guesses were 53% right"), weak.gate(0.62, "Nifty"))
        assertTrue(weak.say("Nifty").contains("watching: its last 400"), weak.say("Nifty"))
        // A good record overall whose 70-100% band runs backwards: the 60-65% band trades, the 70-100% band watches.
        val inverted = Learner()
        repeat(400) { i -> if (i % 4 == 3) inverted.guesses(0.80, 3, 7, i / 4) else inverted.guesses(0.62, 7, 10, i) }
        assertTrue(inverted.hitRate >= 0.58, "${inverted.hitRate}")
        assertNull(inverted.gate(0.62, "Nifty"), inverted.gate(0.62, "Nifty"))
        val g = inverted.gate(0.80, "Nifty")!!
        assertTrue(g.startsWith("watching: when 70-100% sure on Nifty it was right only 4"), g)
        assertEquals("60-65% sure", inverted.bandName(0.38))
        assertEquals("65-70% sure", inverted.bandName(0.67))
        assertEquals("70-100% sure", inverted.bandName(0.95))
        // Too few scored: watching.
        val young = Learner()
        repeat(50) { young.guesses(0.62, 9, 10, it) }
        assertTrue(young.gate(0.62, "Nifty")!!.startsWith("watching: only 50 confident Nifty guesses"))
    }
}
