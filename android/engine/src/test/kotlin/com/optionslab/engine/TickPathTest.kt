package com.optionslab.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TickPathTest {
    @Test fun theHighSinceALookIsEveryTickAfterIt() {
        val p = TickPath()
        assertNull(p.highSince(0))
        p.record(300.0, 10_000)
        p.record(315.0, 10_400)       // a spike inside one second
        p.record(306.0, 11_200)
        p.record(304.0, 14_000)
        assertEquals(315.0, p.highSince(9_000))
        assertEquals(306.0, p.highSince(10_500), "the bucket holding the look itself is the look's own")
        assertNull(p.highSince(14_000))
        // Junk is ignored.
        p.record(Double.NaN, 15_000); p.record(-1.0, 15_000); p.record(0.0, 15_000)
        assertEquals(3, p.size())
    }

    @Test fun aRestingSellStopFillsAtItsTriggerOrTheFirstPriceUnderIt() {
        val p = TickPath()
        p.record(320.0, 1_000)
        p.record(311.0, 2_000)
        p.record(309.0, 2_500)        // touched 310 inside a second that opened above it
        p.record(330.0, 5_000)
        assertEquals(310.0, p.sellStopFill(0, 310.0))
        assertNull(p.sellStopFill(3_000, 310.0), "after the dip: not reached")
        p.record(305.0, 6_000)        // a second that opened under the stop: filled at that price
        assertEquals(305.0, p.sellStopFill(5_500, 310.0))
    }

    @Test fun aWholeMinuteIsBuiltFromItsTicks() {
        val p = TickPath()
        assertNull(p.minute(0), "nothing kept")
        p.record(100.0, 59_000)                         // the minute before
        p.record(101.0, 60_000); p.record(104.0, 75_500); p.record(99.5, 90_000); p.record(102.0, 90_400); p.record(103.0, 119_999)
        p.record(110.0, 120_000)                        // the next minute
        val m = p.minute(60_000)!!
        assertEquals(listOf(101.0, 104.0, 99.5, 103.0), m.toList())
        assertNull(p.minute(180_000), "no tick in it")
        // The ticks kept must reach back to the minute's start, or its open is not known.
        val late = TickPath(); late.record(101.0, 75_000)
        assertNull(late.minute(60_000))
    }

    @Test fun onlyTheLastFewMinutesAreKeptAndLateTicksJoinTheLastBucket() {
        val p = TickPath(keepMs = 10_000, bucketMs = 1_000)
        for (s in 0 until 30) p.record(100.0 + s, s * 1_000L)
        assertEquals(11, p.size())
        assertEquals(129.0, p.highSince(0))
        p.record(500.0, 1_000)        // stamped in the past (a clock step): counted in the newest bucket
        assertEquals(11, p.size())
        assertEquals(500.0, p.highSince(25_000))
    }

    /**
     * 18 Oct (speed): the looks now walk the kept buckets in place from a binary search instead of filtering them into a new
     * list. Checked against that plain filter on random paths (gaps, bursts, late ticks), at every kind of moment: before,
     * on, inside and after the kept buckets, and exactly on a bucket's start.
     */
    @Test fun theInPlaceLooksAnswerExactlyAsTheFilteredOnesDid() {
        val rnd = java.util.Random(11)
        repeat(200) {
            val p = TickPath(keepMs = 20_000, bucketMs = 1_000)
            val seen = ArrayList<LongArray>()                 // (bucket start, price bits) for the plain reference
            var at = 50_000L + rnd.nextInt(5_000)
            repeat(rnd.nextInt(120)) {
                at += when (rnd.nextInt(4)) { 0 -> 0L; 1 -> rnd.nextInt(300).toLong(); 2 -> rnd.nextInt(3_000).toLong(); else -> -rnd.nextInt(2_000).toLong() }
                val px = 100.0 + rnd.nextInt(400) * 0.05
                p.record(px, at)
                seen += longArrayOf(at, px.toRawBits())
            }
            // The plain reference: the same buckets rebuilt the plain way, then filtered (the code before 18 Oct).
            val buckets = rebuild(seen, 20_000, 1_000)
            assertEquals(buckets.size, p.size())
            for (q in 40_000L..(at + 3_000) step 250) {
                val from = q - Math.floorMod(q, 1_000L)
                val after = buckets.filter { it[0] > from }
                assertEquals(after.maxOfOrNull { it[2] }, p.highSince(q), "highSince($q)")
                for (trig in listOf(100.0, 105.0, 110.0, 200.0)) {
                    assertEquals(after.firstOrNull { it[3] <= trig + 1e-9 }?.let { minOf(trig, it[1]) }, p.sellStopFill(q, trig), "fill($q, $trig)")
                }
                val ms = q - Math.floorMod(q, 60_000L)
                val inside = buckets.filter { it[0] >= ms && it[0] < ms + 60_000 }
                val want = if (buckets.isEmpty() || buckets.first()[0] > ms || inside.isEmpty()) null
                    else listOf(inside.first()[1], inside.maxOf { it[2] }, inside.minOf { it[3] }, inside.last()[4])
                assertEquals(want, p.minute(ms)?.toList(), "minute($ms)")
            }
        }
    }

    /** The buckets [TickPath] keeps for [seen] ticks: (start, open, high, low, close), the plain way. */
    private fun rebuild(seen: List<LongArray>, keepMs: Long, bucketMs: Long): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        for ((at, bits) in seen.map { it[0] to it[1] }) {
            val px = Double.fromBits(bits)
            val key = (at - Math.floorMod(at, bucketMs)).toDouble()
            val last = out.lastOrNull()
            if (last != null && key <= last[0]) { last[2] = maxOf(last[2], px); last[3] = minOf(last[3], px); last[4] = px }
            else out += doubleArrayOf(key, px, px, px, px)
            val cut = out.last()[0] - keepMs
            while (out.first()[0] < cut) out.removeAt(0)
        }
        return out
    }

    /** 18 Oct (final review F4): the lowest price since a look, for a shadow stop judged on a wick. */
    @Test fun theLowSinceALookIsEveryTickAfterIt() {
        val p = TickPath()
        assertNull(p.lowSince(0))
        p.record(300.0, 10_000)
        p.record(285.0, 10_400)       // a dip inside one second
        p.record(296.0, 11_200)
        p.record(298.0, 14_000)
        assertEquals(285.0, p.lowSince(9_000))
        assertEquals(296.0, p.lowSince(10_500), "the bucket holding the look itself is the look's own")
        assertNull(p.lowSince(14_000))
    }
}
