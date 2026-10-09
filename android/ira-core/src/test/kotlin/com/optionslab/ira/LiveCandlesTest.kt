package com.optionslab.ira

import com.optionslab.engine.Upstox
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Local candles ([LiveCandles]): minutes built from stream ticks by the exchange's stamp - the boundary, out-of-order
 * ticks, a stream drop, gaps (never decided on), the feed as the source of truth (reconcile, backfill, overlay) and the
 * 5- / 15-minute folds.
 */
class LiveCandlesTest {
    private val tok = 256265L
    /** 11:00:00 IST on a session day, in epoch seconds (a multiple of 15 minutes). */
    private val t0 = 1_791_694_800L

    private fun bar(sec: Long, o: Double, h: Double, l: Double, c: Double) = Upstox.Bar(sec, o, h, l, c, 0, 0)

    /** A run of ticks that began in the minute before [t0] (so [t0]'s minute is whole). */
    private fun warm(lc: LiveCandles) { lc.record(tok, 100.0, t0 - 5) }

    @Test fun aTickOnTheBoundaryOpensTheNewMinuteAndClosesTheOld() {
        val lc = LiveCandles()
        warm(lc)
        assertFalse(lc.record(tok, 101.0, t0 - 1))
        // hh:mm:00 belongs to the new minute: the first tick of it closes the last one.
        assertTrue(lc.record(tok, 102.0, t0))
        lc.record(tok, 104.0, t0 + 20)
        lc.record(tok, 99.0, t0 + 40)
        lc.record(tok, 103.0, t0 + 59)
        val c = lc.candle(tok, t0)!!
        assertEquals(listOf(102.0, 104.0, 99.0, 103.0), listOf(c.open, c.high, c.low, c.close))
        assertTrue(c.whole)
        // The run's first minute is partial: its open is not known.
        assertFalse(lc.candle(tok, t0 - 60)!!.whole)
        // 11:00 is not closed until a tick of 11:01 arrives (or the clock passes 11:01).
        assertNull(lc.closedRun(tok, t0, t0 + 59))
        assertEquals(1, lc.closedRun(tok, t0, t0 + 60)!!.size)
        assertTrue(lc.record(tok, 103.5, t0 + 60))
        assertEquals(103.0, lc.closedRun(tok, t0, t0 + 60)!!.single().close)
        assertEquals(t0 + 60, lc.latest(tok))
    }

    @Test fun outOfOrderTicksUpdateTheirOwnMinuteAndNeverTheCloseBackwards() {
        val lc = LiveCandles()
        warm(lc)
        lc.record(tok, 100.0, t0 + 10)
        lc.record(tok, 101.0, t0 + 30)
        // An older stamp arriving late: counts in its minute's high/low; the close stays the newer one.
        lc.record(tok, 105.0, t0 + 20)
        lc.record(tok, 95.0, t0 + 5)
        val c = lc.candle(tok, t0)!!
        assertEquals(105.0, c.high); assertEquals(95.0, c.low)
        assertEquals(101.0, c.close)
        assertEquals(95.0, c.open, "the earliest stamp is the open")
        // A late tick of the previous minute after the new one began updates that minute, not the new one.
        lc.record(tok, 110.0, t0 + 61)
        lc.record(tok, 99.0, t0 + 50)
        assertEquals(110.0, lc.candle(tok, t0 + 60)!!.open)
        assertEquals(99.0, lc.candle(tok, t0)!!.close)
        // Nonsense is ignored.
        assertFalse(lc.record(tok, Double.NaN, t0 + 62))
        assertFalse(lc.record(tok, -1.0, t0 + 62))
        assertFalse(lc.record(tok, 100.0, 0))
        assertEquals(110.0, lc.candle(tok, t0 + 60)!!.high)
    }

    @Test fun aMissingMinuteIsAGapAndIsNeverDecidedOn() {
        val lc = LiveCandles()
        warm(lc)
        lc.record(tok, 100.0, t0 + 1)
        // Nothing at all in 11:01: 11:02's ticks arrive.
        lc.record(tok, 101.0, t0 + 121)
        lc.record(tok, 102.0, t0 + 181)
        assertNull(lc.closedRun(tok, t0, t0 + 181), "11:01 is missing")
        val feed = listOf(bar(t0 - 60, 99.0, 100.0, 99.0, 100.0))
        val o = lc.overlay(tok, feed, t0 + 181)
        assertEquals(LiveCandles.Source.FEED, o.source)
        assertEquals(feed, o.bars)
        assertEquals(0, o.added)
    }

    @Test fun aStreamDropMakesTheMinuteInProgressAndTheNextRunsFirstMinutePartial() {
        val lc = LiveCandles()
        warm(lc)
        lc.record(tok, 100.0, t0 + 1)
        lc.record(tok, 100.5, t0 + 30)
        lc.broke()
        assertFalse(lc.candle(tok, t0)!!.whole, "ticks of 11:00 may have been lost")
        lc.record(tok, 101.0, t0 + 75)       // the stream back mid-11:01: its open is unknown
        lc.record(tok, 101.5, t0 + 125)
        lc.record(tok, 102.0, t0 + 185)
        assertFalse(lc.candle(tok, t0 + 60)!!.whole)
        assertTrue(lc.candle(tok, t0 + 120)!!.whole)
        assertNull(lc.closedRun(tok, t0, t0 + 185))
        assertEquals(1, lc.closedRun(tok, t0 + 120, t0 + 185)!!.size)
        // The feed's minutes make them whole again (the source of truth), and backfill what was missed.
        lc.reconcile(tok, listOf(bar(t0, 100.0, 100.8, 99.9, 100.6), bar(t0 + 60, 100.6, 101.2, 100.4, 101.1)))
        val run = lc.closedRun(tok, t0, t0 + 185)!!
        assertEquals(3, run.size)
        assertEquals(100.8, run[0].high); assertTrue(run[0].fromFeed)
        // A feed minute is never changed by a late tick.
        lc.record(tok, 150.0, t0 + 30)
        assertEquals(100.8, lc.candle(tok, t0)!!.high)
        // The single-token drop leaves the others alone.
        lc.record(9L, 50.0, t0 + 1); lc.record(9L, 50.0, t0 + 70)
        lc.broke(tok)
        assertTrue(lc.candle(9L, t0 + 60)!!.whole)
        // Bars that are not priced are not laid over anything.
        lc.reconcile(tok, listOf(bar(t0 + 180, 0.0, 0.0, 0.0, 0.0)))
        assertNotNull(lc.candle(tok, t0 + 180))
        assertFalse(lc.candle(tok, t0 + 180)!!.fromFeed)
    }

    @Test fun theOverlayAddsWholeLocalMinutesAfterTheFeedsLastOnly() {
        val lc = LiveCandles()
        warm(lc)
        for (s in 0L until 180L step 15) lc.record(tok, 100.0 + s / 15, t0 + s)
        lc.record(tok, 120.0, t0 + 180)      // 11:03's first tick: 11:00-11:02 closed
        // The feed is at 10:59 (a few seconds behind): 11:00, 11:01, 11:02 come from the stream.
        val feed = listOf(bar(t0 - 120, 98.0, 99.0, 97.0, 98.5), bar(t0 - 60, 98.5, 100.0, 98.0, 100.0))
        val o = lc.overlay(tok, feed, t0 + 180)
        assertEquals(LiveCandles.Source.LOCAL, o.source)
        assertEquals(3, o.added)
        assertEquals(listOf(t0, t0 + 60, t0 + 120), o.bars.drop(2).map { it.epochSecond })
        assertEquals(100.0, o.bars[2].open); assertEquals(103.0, o.bars[2].close)
        assertEquals(104.0, o.bars[3].open); assertEquals(107.0, o.bars[3].close)
        // The feed already has everything closed: nothing to add, the feed's own.
        val full = feed + listOf(bar(t0, 1.0, 1.0, 1.0, 1.0), bar(t0 + 60, 1.0, 1.0, 1.0, 1.0), bar(t0 + 120, 1.0, 1.0, 1.0, 1.0))
        assertEquals(LiveCandles.Source.FEED, lc.overlay(tok, full, t0 + 180).source)
        // No feed at all, an unknown instrument, an instrument not streamed: the feed as it is.
        assertEquals(LiveCandles.Source.FEED, lc.overlay(tok, emptyList(), t0 + 180).source)
        assertEquals(LiveCandles.Source.FEED, lc.overlay(null, feed, t0 + 180).source)
        assertEquals(LiveCandles.Source.FEED, lc.overlay(77L, feed, t0 + 180).source)
        // The feed far behind (more than the most that may be added): read the feed.
        assertEquals(LiveCandles.Source.FEED, lc.overlay(tok, feed, t0 + 180, maxAdd = 2).source)
        // The clock passed 11:03's end with no later tick: 11:03 counts as closed too.
        assertEquals(4, lc.overlay(tok, feed, t0 + 245).added)
    }

    @Test fun theFoldsKeepOnlyWholeFiveAndFifteenMinuteBars() {
        val ones = (0 until 17).map { i -> Upstox.Bar(t0 + i * 60L, 100.0 + i, 101.0 + i, 99.0 + i, 100.5 + i, 10, i.toLong()) }
        val fives = LiveCandles.fold(ones, 5)
        assertEquals(listOf(t0, t0 + 300, t0 + 600), fives.map { it.epochSecond }, "11:15 has only two minutes")
        assertEquals(100.0, fives[0].open); assertEquals(105.0, fives[0].high); assertEquals(99.0, fives[0].low); assertEquals(104.5, fives[0].close)
        assertEquals(50, fives[0].volume); assertEquals(4, fives[0].oi)
        assertEquals(listOf(t0), LiveCandles.fold(ones, 15).map { it.epochSecond })
        // A missing minute inside a bar drops that bar.
        val holed = ones.filter { it.epochSecond != t0 + 360 }
        assertEquals(listOf(t0, t0 + 600), LiveCandles.fold(holed, 5).map { it.epochSecond })
        assertTrue(LiveCandles.fold(holed, 15).isEmpty())
        // Duplicates count once.
        assertEquals(LiveCandles.fold(ones, 5), LiveCandles.fold(ones + ones.take(3), 5))
    }

    @Test fun onlyTheNewestMinutesAreKept() {
        val lc = LiveCandles(keepMinutes = 3)
        for (i in 0 until 6) lc.record(tok, 100.0, t0 + i * 60L)
        assertNull(lc.candle(tok, t0))
        assertNotNull(lc.candle(tok, t0 + 300))
        lc.clear()
        assertNull(lc.latest(tok))
    }
}
