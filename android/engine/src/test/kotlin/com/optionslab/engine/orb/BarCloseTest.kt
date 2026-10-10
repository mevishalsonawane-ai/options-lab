package com.optionslab.engine.orb

import com.optionslab.engine.Upstox
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** Entries within seconds of the bar close (research X1 change 2): when the watch wakes, and the feed's missing minutes. */
class BarCloseTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    private fun ms(h: Int, m: Int, s: Int = 0) = LocalDateTime.of(2026, 10, 8, h, m, s).atZone(ist).toInstant().toEpochMilli()
    private fun sec(h: Int, m: Int) = ms(h, m) / 1000

    @Test fun itWakesThreeSecondsAfterEachFiveMinuteBoundaryInIst() {
        assertEquals(ms(10, 35, 3), BarClose.nextWake(ms(10, 31, 40)))
        assertEquals(ms(10, 35, 3), BarClose.nextWake(ms(10, 35, 1)), "the boundary just gone: still ahead")
        assertEquals(ms(10, 40, 3), BarClose.nextWake(ms(10, 35, 3)), "at the wake itself: the next one")
        assertEquals(ms(9, 20, 3), BarClose.nextWake(ms(9, 15, 30)))
    }

    private fun bar(h: Int, m: Int, c: Double) = Upstox.Bar(sec(h, m), c, c + 1, c - 1, c, 10, 0)

    @Test fun theMinutesTheFeedLacksAreBuiltFromTheStream() {
        val feed = listOf(bar(10, 31, 100.0), bar(10, 32, 101.0), bar(10, 33, 102.0))
        val built = mapOf(sec(10, 34) to doubleArrayOf(102.0, 104.0, 101.5, 103.5))
        // 10:35:03: the 10:34 minute has ended; the feed does not have it yet.
        val out = BarClose.complete(feed, ms(10, 35, 3) / 1000) { built[it] }
        assertEquals(4, out.size)
        assertEquals(Upstox.Bar(sec(10, 34), 102.0, 104.0, 101.5, 103.5, 0, 0), out.last())
        // The forming minute is never built; nothing to build leaves the feed as it is.
        assertEquals(3, BarClose.complete(feed, ms(10, 34, 30) / 1000) { built[it] }.size)
        assertEquals(3, BarClose.complete(feed, ms(10, 35, 3) / 1000) { null }.size)
        assertSame(emptyList<Upstox.Bar>(), BarClose.complete(emptyList(), 0) { null })
    }

    @Test fun aGapIsNeverSkippedAndAtMostFourAreBuilt() {
        val feed = listOf(bar(10, 20, 100.0))
        val all = { _: Long -> doubleArrayOf(1.0, 2.0, 0.5, 1.5) }
        assertEquals(1 + BarClose.MAX_BUILT, BarClose.complete(feed, ms(10, 40) / 1000, all).size)
        // 10:21 cannot be built: 10:22 is not built either.
        val holes = mapOf(sec(10, 22) to doubleArrayOf(1.0, 2.0, 0.5, 1.5))
        assertEquals(1, BarClose.complete(feed, ms(10, 25) / 1000) { holes[it] }.size)
        // A bad price is not a minute.
        assertEquals(1, BarClose.complete(feed, ms(10, 25) / 1000) { doubleArrayOf(1.0, Double.NaN, 0.5, 1.0) }.size)
        assertEquals(1, BarClose.complete(feed, ms(10, 25) / 1000) { doubleArrayOf(1.0, 2.0) }.size)
    }
}
