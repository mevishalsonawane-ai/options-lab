package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The research's rules ([R12Rules]) against small hand-built minute sequences that mirror research/hunt/r12/specs.py and the
 * lock logic of lib12.replay. Columns are minutes; the entry (100) fills in column 50, which counts as minute 1 held. The
 * expected results were worked out from the Python (specs.PA/PB/VC/BE/VW/MP; lib12.rolling_mean_prev; the replay's
 * `pr = where(v & (pr < cap), pr, NEG)` with cap = floor(close × 0.99 / tick + 1e-9) × tick) and checked by running those
 * same functions on the same arrays.
 */
class R12RulesTest {
    private val base = 29_333_340L * 60_000L
    private fun at(col: Int, sec: Int = 0) = base + col * 60_000L + sec * 1_000L
    private val entryMs = at(50, 20)
    private fun bar(col: Int, h: Double, l: Double, c: Double) = TradeManager.Bar(at(col), (h + l) / 2, h, l, c)
    private fun col(c: Int) = R12Rules.col(at(c))

    // ---- PA(T 20, m 0.03): the best high from column 50 to 69 still under 103 -> exit at 69 --------------------------

    @Test fun paNoProgress() {
        // Python: PA fires where held = k - c0 + 1 >= 20 and best(c0..k) < 100 x 1.03 = 103: first at k = 69.
        val flat = (50..80).map { bar(it, 102.9, 101.9, 102.4) }
        assertEquals(col(69), R12Rules.pa(flat, 100.0, entryMs, at(81), 20, 0.03))
        // Python: one high of 103.0 at column 55 -> best is 103, not under 103: never.
        val touched = flat.map { if (it.startMs == at(55)) bar(55, 103.0, 102.0, 102.5) else it }
        assertNull(R12Rules.pa(touched, 100.0, entryMs, at(81), 20, 0.03))
        // Python: a new high after column 69 changes nothing - it already fired at 69.
        val late = flat.map { if (it.startMs == at(70)) bar(70, 110.0, 109.0, 109.5) else it }
        assertEquals(col(69), R12Rules.pa(late, 100.0, entryMs, at(81), 20, 0.03))
        // Timing (the app's): not before column 69 has closed and is loaded.
        assertNull(R12Rules.pa(flat, 100.0, entryMs, at(69, 59), 20, 0.03))
        assertNull(R12Rules.pa(flat.filter { it.startMs < at(69) }, 100.0, entryMs, at(75), 20, 0.03))
    }

    // ---- PB(a 0.12): a minute close at or under 100 x 0.88 = 88.0 -------------------------------------------------------

    @Test fun pbAdverseClose() {
        val flat = (50..69).map { bar(it, 96.0, 94.0, 95.0) }
        // Python: close 88.0 at column 57 -> 57 (<=, and 100 x (1 - 0.12) is exactly 88.0).
        assertEquals(col(57), R12Rules.pb(flat.map { if (it.startMs == at(57)) bar(57, 89.0, 87.0, 88.0) else it }, 100.0, entryMs, at(70), 0.12))
        // Python: 88.05 is not at or under 88.0 -> none.
        assertNull(R12Rules.pb(flat.map { if (it.startMs == at(57)) bar(57, 89.05, 87.05, 88.05) else it }, 100.0, entryMs, at(70), 0.12))
        // Python: a close of 87 before the entry (column 49) does not count; 88.0 at column 60 does -> 60.
        val pre = (45..69).map { bar(it, 96.0, 94.0, 95.0) }.map {
            when (it.startMs) { at(49) -> bar(49, 88.0, 86.0, 87.0); at(60) -> bar(60, 89.0, 87.0, 88.0); else -> it }
        }
        assertEquals(col(60), R12Rules.pb(pre, 100.0, entryMs, at(70), 0.12))
    }

    // ---- VC(k 3, a 1): the lock = best high − 3 option ATRs once a 1-ATR profit, raised only ------------------------------

    private val pre = (40..49).map { bar(it, 101.0, 99.0, 100.0) }      // ten minutes of range 2 before the entry

    @Test fun vcVolatilityTrail() {
        val bars = pre + listOf(bar(50, 101.0, 99.0, 100.5), bar(51, 104.0, 100.0, 103.5), bar(52, 106.0, 103.0, 105.0),
            bar(53, 107.3, 104.1, 106.9), bar(54, 105.0, 101.0, 102.0))
        // Python (VC then the replay's running max), the lock after each column 50..54:
        //   50: best 101, ATR 2: 1 < 1 x 2, not armed -> none
        //   51: best 104, ATR 2 -> 104 − 6 = 98.0
        //   52: best 106, ATR (8 x 2 + 2 + 4) / 10 = 2.2 -> 106 − 6.6 = 99.4
        //   53: best 107.3, ATR 2.3 -> 100.4, floored to the tick as 100.35 (100.4 / 0.05 is 2007.999...)
        //   54: best 107.3, ATR (6 x 2 + 2 + 4 + 3 + 3.2) / 10 = 2.42 -> 100.04, floored 100.0: under 100.35, the lock never falls
        val want = listOf(null, 98.0, 99.4, 100.35, 100.35)
        for ((i, c) in (50..54).withIndex()) {
            val got = R12Rules.vc(bars, 100.0, entryMs, at(c + 1), 3.0, 1.0)
            if (want[i] == null) assertNull(got, "column $c") else assertEquals(want[i]!!, got!!, 1e-9, "column $c")
        }
        // Small moves never arm it. Python: 50 and 51 none; 52: best 101.9, ATR (8 x 2 + 1 + 1.5) / 10 = 1.85, armed
        // (1.9 >= 1.85) -> 101.9 − 5.55 = 96.35.
        val quiet = pre + listOf(bar(50, 100.5, 99.5, 100.0), bar(51, 101.5, 100.0, 101.0), bar(52, 101.9, 100.5, 101.2))
        assertNull(R12Rules.vc(quiet, 100.0, entryMs, at(52), 3.0, 1.0))
        assertEquals(96.35, R12Rules.vc(quiet, 100.0, entryMs, at(53), 3.0, 1.0)!!, 1e-9)
        // A proposal at or above 99% of its own minute's close is dropped. Python (k 0.5): column 51 proposes 110 − 1 = 109.0,
        // over the cap floor(104 x 0.99) = 102.95 -> no lock.
        val spike = pre + listOf(bar(50, 101.0, 99.0, 100.5), bar(51, 110.0, 104.0, 104.0))
        assertNull(R12Rules.vc(spike, 100.0, entryMs, at(52), 0.5, 1.0))
    }

    // ---- BE(b 0.15): breakeven plus charges (100.6) once the best high is 115 ----------------------------------------------

    @Test fun beBreakevenLock() {
        // Python: column 51's high 115 >= 100 x 1.15 -> ceil_tick(100.6) = 100.6 from then on.
        val up = listOf(bar(50, 105.0, 99.0, 104.0), bar(51, 115.0, 104.0, 112.0), bar(52, 113.0, 108.0, 110.0))
        assertNull(R12Rules.be(up, 100.0, entryMs, at(51), 0.15, 100.6))
        assertEquals(100.6, R12Rules.be(up, 100.0, entryMs, at(52), 0.15, 100.6)!!, 1e-9)
        assertEquals(100.6, R12Rules.be(up, 100.0, entryMs, at(53), 0.15, 100.6)!!, 1e-9)
        // Python: 114.95 is short of 115 -> none.
        assertNull(R12Rules.be(listOf(bar(50, 105.0, 99.0, 104.0), bar(51, 114.95, 104.0, 112.0)), 100.0, entryMs, at(52), 0.15, 100.6))
        // Python: column 50 reaches 115 but closes at 101 (cap 99.95 < 100.6: dropped); column 51 closes 113 -> 100.6.
        val capped = listOf(bar(50, 115.0, 99.0, 101.0), bar(51, 114.0, 100.0, 113.0))
        assertNull(R12Rules.be(capped, 100.0, entryMs, at(51), 0.15, 100.6))
        assertEquals(100.6, R12Rules.be(capped, 100.0, entryMs, at(52), 0.15, 100.6)!!, 1e-9)
    }

    // ---- VW(z 0.5, p 5): the index 1.5 under a VWAP of 100 with SD 2 (beyond 0.5 SD) for 5 minutes ---------------------

    private fun minutes(wrong: Iterable<Int>, side: Int = 1): List<R12Rules.IndexMinute> {
        val w = wrong.toSet()
        return (40..70).map { R12Rules.IndexMinute(at(it), if (it in w) 100.0 - side * 1.5 else 100.0, 100.0, 2.0) }
    }

    @Test fun vwVwapPersist() {
        // Python: wrong at 52..56 -> the 5th in a row is 56.
        assertEquals(col(56), R12Rules.vw(minutes(52..56), 1, entryMs, at(71), 0.5, 5))
        // Python: 4 in a row, a break, 4 more -> none.
        assertNull(R12Rules.vw(minutes((52..55) + (57..60)), 1, entryMs, at(71), 0.5, 5))
        // Python: the run counts from before the entry (47..51) -> 51.
        assertEquals(col(51), R12Rules.vw(minutes(47..51), 1, entryMs, at(71), 0.5, 5))
        // A put: above VWAP is its wrong side -> 56.
        assertEquals(col(56), R12Rules.vw(minutes(52..56, side = -1), -1, entryMs, at(71), 0.5, 5))
        // Not before the 5th minute has closed.
        assertNull(R12Rules.vw(minutes(52..56), 1, entryMs, at(56, 30), 0.5, 5))
    }

    // ---- MP(prior, p 5): the prior day's value area 90-100 -----------------------------------------------------------------

    private fun index(vararg at: Pair<Int, Double>): List<R12Rules.IndexMinute> {
        val m = at.toMap()
        return (40..70).map { R12Rules.IndexMinute(at(it), m[it] ?: 95.0) }
    }

    @Test fun mpBackInsidePriorValue() {
        // Python: above 100 at column 49 (the minute before the entry), back inside from 50 -> 5 minutes at 54.
        assertEquals(col(54), R12Rules.mp(index(49 to 101.0), 1, entryMs, at(71), 100.0, 90.0, 5))
        // Python: never outside -> none.
        assertNull(R12Rules.mp(index(), 1, entryMs, at(71), 100.0, 90.0, 5))
        // Python: outside at 55, back inside 56..60 -> 60.
        assertEquals(col(60), R12Rules.mp(index(55 to 101.0), 1, entryMs, at(71), 100.0, 90.0, 5))
        // A put: below 90 at 55 -> 60.
        assertEquals(col(60), R12Rules.mp(index(55 to 89.0), -1, entryMs, at(71), 100.0, 90.0, 5))
        // No prior value area: nothing.
        assertNull(R12Rules.mp(index(49 to 101.0), 1, entryMs, at(71), null, null, 5))
    }

    @Test fun oiAndDeltaProxyAgainstTheTrade() {
        // Python: side x z <= −z0.
        assertTrue(R12Rules.oi(-1.6, 1, 1.5)); assertTrue(R12Rules.oi(-1.5, 1, 1.5))
        assertFalse(R12Rules.oi(-1.6, -1, 1.5)); assertTrue(R12Rules.oi(1.6, -1, 1.5))
        assertFalse(R12Rules.oi(null, 1, 1.5), "no reading: no vote")
        assertTrue(R12Rules.dp(-2.0, 1, 2.0)); assertFalse(R12Rules.dp(-1.99, 1, 2.0)); assertFalse(R12Rules.dp(Double.NaN, 1, 2.0))
        assertTrue(R12Rules.xvw(101.0, 100.0, 2.0, 1, 0.5)); assertFalse(R12Rules.xvw(100.9, 100.0, 2.0, 1, 0.5))
        assertTrue(R12Rules.xdp(0.5, 1, 0.5)); assertFalse(R12Rules.xdp(0.5, -1, 0.5))
    }
}
