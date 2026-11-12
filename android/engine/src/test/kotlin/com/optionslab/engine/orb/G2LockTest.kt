package com.optionslab.engine.orb

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * R167's G2 lock (12 Nov 2026, Boss's exception, paper test twins only): its levels solved from the fill and the charges, and the
 * rupee lock in [PassedArms.exitOnBars] - set by a minute's high, active from the next minute, only rising, the higher of the
 * floor and the trail, beside the arm's own stop, ladder, time stop and square-off.
 */
class G2LockTest {
    private val day = LocalDate.of(2026, 11, 12)
    private fun t(h: Int, m: Int): LocalDateTime = day.atTime(h, m)
    private fun bar(h: Int, m: Int, o: Double, hi: Double, lo: Double, c: Double) = Bar(t(h, m), o, hi, lo, c)
    private fun flat(h: Int, m: Int, p: Double) = bar(h, m, p, p, p, p)

    /** Charges that are a flat Rs 40 a round trip, so the levels are easy to check by hand. */
    private val flat40: (Double, Double, Int) -> Double = { _, _, _ -> 40.0 }

    // R51 / R72's exits with the lock (their -15% stop, 30-minute time stop, 15:15) and a G2 lock for 65 units filled at 100.
    private val g2 = PassedArms.RupeeLock(trigger = 124.0, keep = 108.3, trail = 1_000.0 / 65)
    private val r51 = PassedArms.PremiumExits(0.15, null, 30, PassedArms.LOCK_L25, LocalTime.of(15, 15))
    private val withG2 = r51.copy(rupeeLock = g2)

    @Test fun theLevelsNetTheirRupeesAfterChargesOnTheTickGrid() {
        // 65 units at 100, Rs 40 charges: +1,500 needs (1,540 / 65) = 23.69 above, rounded up to the tick: 123.70.
        val lv = G2Lock.levels(100.0, 65, flat40)!!
        assertEquals(123.70, lv.trigger, 1e-9)
        assertEquals(108.35, lv.keep, 1e-9)                 // (500 + 40) / 65 = 8.31 above: 108.31, rounded up to the tick
        assertEquals(1_000.0 / 65, lv.trail, 1e-12)
        assertNull(G2Lock.levels(0.0, 65, flat40)); assertNull(G2Lock.levels(100.0, 0, flat40)); assertNull(G2Lock.levels(Double.NaN, 65, flat40))
        // With the real charges the trigger nets at least +Rs 1,500 and one tick less does not.
        val real = G2Lock.levels(150.0, 65)!!
        val net = { x: Double -> (x - 150.0) * 65 - G2Lock.charges(150.0, x, 65) }
        assertTrue(net(real.trigger) >= 1_500.0 - 1e-6 && net(real.trigger - G2Lock.TICK) < 1_500.0, "${real.trigger}")
        assertTrue(net(real.keep) >= 500.0 - 1e-6 && net(real.keep - G2Lock.TICK) < 500.0, "${real.keep}")
        assertTrue(real.keep < real.trigger)
    }

    @Test fun noLockBeforeTheTriggerAndTheArmsOwnStopStillActs() {
        // Up to 123 (under the 124 trigger), then down through -15%: the stop sells, as without G2.
        val bars = listOf(flat(10, 0, 100.0), bar(10, 1, 110.0, 123.0, 109.0, 120.0), bar(10, 2, 100.0, 101.0, 84.0, 84.0))
        val w = PassedArms.exitOnBars(100.0, t(10, 0), bars, null, t(10, 3), withG2)
        assertEquals(PassedArms.ExitKind.STOP, w.exit!!.kind); assertEquals(85.0, w.exit!!.price, 1e-9)
        assertEquals(PassedArms.exitOnBars(100.0, t(10, 0), bars, null, t(10, 3), r51), w)
    }

    @Test fun onceAtTheTriggerTheFloorHoldsFromTheNextMinute() {
        // 10:01 reaches 125 (past 124): floor max(108.3, 125 - 15.38) = 109.62 from 10:02. A dip to 105 in the same minute is no exit.
        val same = PassedArms.exitOnBars(100.0, t(10, 0), listOf(flat(10, 0, 100.0), bar(10, 1, 110.0, 125.0, 105.0, 118.0)), null, t(10, 2), withG2)
        assertNull(same.exit)
        assertEquals(125.0 - 1_000.0 / 65, same.lock!!, 1e-9)
        // 10:02 falls to 90: sold by the lock at 109.62 (the research's resting fill), where the plain exits would ride on.
        val bars = listOf(flat(10, 0, 100.0), bar(10, 1, 110.0, 125.0, 105.0, 118.0), bar(10, 2, 112.0, 112.0, 90.0, 91.0))
        val w = PassedArms.exitOnBars(100.0, t(10, 0), bars, null, t(10, 3), withG2)
        assertEquals(PassedArms.ExitKind.LOCK, w.exit!!.kind); assertEquals(125.0 - 1_000.0 / 65, w.exit!!.price, 1e-9)
        // Without G2 the same ride is held by L25's +5% step (earned at +25%) and sells at 105, not 109.62.
        val plain = PassedArms.exitOnBars(100.0, t(10, 0), bars, null, t(10, 3), r51)
        assertEquals(PassedArms.ExitKind.LOCK, plain.exit!!.kind); assertEquals(105.0, plain.exit!!.price, 1e-9)
        // A gap open under the lock sells at the open.
        val gap = listOf(flat(10, 0, 100.0), bar(10, 1, 110.0, 125.0, 105.0, 118.0), bar(10, 2, 95.0, 96.0, 90.0, 91.0))
        assertEquals(95.0, PassedArms.exitOnBars(100.0, t(10, 0), gap, null, t(10, 3), withG2).exit!!.price, 1e-9)
    }

    @Test fun theTrailRisesWithTheBestPriceAndNeverFalls() {
        // 124.5 at 10:01 (floor 109.12), 140 at 10:02 (trail 124.62), 130 at 10:03 (the lock stays 124.62): sold at 124.62 at 10:04.
        val bars = listOf(flat(10, 0, 100.0), bar(10, 1, 120.0, 124.5, 119.0, 123.0), bar(10, 2, 130.0, 140.0, 129.0, 138.0),
            bar(10, 3, 131.0, 131.0, 126.0, 130.0), bar(10, 4, 125.0, 125.0, 120.0, 121.0))
        val held = PassedArms.exitOnBars(100.0, t(10, 0), bars.take(4), null, t(10, 4), withG2)
        assertNull(held.exit); assertEquals(140.0 - 1_000.0 / 65, held.lock!!, 1e-9)
        val w = PassedArms.exitOnBars(100.0, t(10, 0), bars, null, t(10, 5), withG2)
        assertEquals(PassedArms.ExitKind.LOCK, w.exit!!.kind); assertEquals(140.0 - 1_000.0 / 65, w.exit!!.price, 1e-9)
    }

    @Test fun theHigherOfTheLadderAndG2Holds() {
        // +100% (200): the ladder's +60% step (160) is above G2's trail (200 - 15.38 = 184.6)? No: G2's is higher and holds.
        val big = listOf(flat(10, 0, 100.0), bar(10, 1, 150.0, 200.0, 149.0, 199.0))
        assertEquals(200.0 - 1_000.0 / 65, PassedArms.exitOnBars(100.0, t(10, 0), big, null, t(10, 2), withG2).lock!!, 1e-9)
        // A wide trail (Rs 5,000 for 65 units = 76.9 points): the ladder's +60% (160) is higher and holds.
        val wide = r51.copy(rupeeLock = PassedArms.RupeeLock(124.0, 108.3, 5_000.0 / 65))
        assertEquals(160.0, PassedArms.exitOnBars(100.0, t(10, 0), big, null, t(10, 2), wide).lock!!, 1e-9)
    }

    @Test fun theTimeStopTheSquareOffAndTheMissingMinutesAreUnchanged() {
        // Never at the trigger: the 30-minute time stop sells exactly as without G2.
        val bars = (0 until 30).map { flat(10, it, 110.0) }
        assertEquals(PassedArms.exitOnBars(100.0, t(10, 0), bars, null, t(10, 30), r51),
            PassedArms.exitOnBars(100.0, t(10, 0), bars, null, t(10, 30), withG2))
        assertEquals(PassedArms.ExitKind.TIME, PassedArms.exitOnBars(100.0, t(10, 0), bars, null, t(10, 30), withG2).exit!!.kind)
        // A minute with no prices neither earns nor sells.
        val gap = listOf(flat(10, 0, 100.0), bar(10, 1, Double.NaN, Double.NaN, Double.NaN, Double.NaN))
        assertNull(PassedArms.exitOnBars(100.0, t(10, 0), gap, null, t(10, 2), withG2).lock)
        // The price now under an earned lock sells between bars at the lock (a resting order's fill).
        val earned = listOf(flat(10, 0, 100.0), bar(10, 1, 110.0, 125.0, 110.0, 120.0))
        val now = PassedArms.exitOnBars(100.0, t(10, 0), earned, 100.0, t(10, 2).plusSeconds(10), withG2)
        assertEquals(PassedArms.ExitKind.LOCK, now.exit!!.kind); assertEquals(125.0 - 1_000.0 / 65, now.exit!!.price, 1e-9)
    }

    @Test fun itsWordsSayFailedGatesAndPaperOnly() {
        assertEquals("failed gates, Boss's exception", G2Lock.STATUS)
        for (w in listOf("never bought or copied live", "keeps today's exits", "₹1,500", "₹500", "₹1,000")) assertTrue(w in G2Lock.RULE, w)
        for (w in listOf("49.1%", "45.8%", "48.9%", "44.9%", "p 0.002", "no less profit")) assertTrue(w in G2Lock.RECORD, w)
    }
}
