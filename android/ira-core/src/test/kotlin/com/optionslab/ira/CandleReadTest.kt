package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Synthetic candles for the Candle agent's tests (20 Oct 2026). */
object CandleSynth {
    /** [days] sessions of [tf]-minute candles from 09:15 to 15:30, a seeded random walk from [start]; weekends skipped. */
    fun days(first: LocalDate, days: Int, tf: Int = 5, start: Double = 24_000.0, seed: Long = 7, step: Double = 8.0, vol: Boolean = false): List<CandleRead.Bar> {
        val r = java.util.Random(seed)
        val out = ArrayList<CandleRead.Bar>()
        var d = first
        var px = start
        var made = 0
        while (made < days) {
            if (d.dayOfWeek.value <= 5) {
                var t = d.atTime(9, 15)
                while (t.isBefore(d.atTime(15, 30))) {
                    val o = px
                    val c = o + r.nextGaussian() * step
                    val h = maxOf(o, c) + Math.abs(r.nextGaussian()) * step / 2
                    val l = minOf(o, c) - Math.abs(r.nextGaussian()) * step / 2
                    out += CandleRead.Bar(t, o, h, l, c, if (vol) 1_000.0 + r.nextInt(500) else null)
                    px = c
                    t = t.plusMinutes(tf.toLong())
                }
                made++
            }
            d = d.plusDays(1)
        }
        return out
    }

    fun bar(t: LocalDateTime, o: Double, h: Double, l: Double, c: Double, v: Double? = null) = CandleRead.Bar(t, o, h, l, c, v)
}

class CandleReadTest {
    private val day = LocalDate.of(2026, 10, 19)

    @Test fun anatomyMeasuresBodyWicksCloseAndColour() {
        val b = CandleSynth.bar(day.atTime(10, 0), 100.0, 110.0, 90.0, 105.0)
        val a = CandleRead.anatomy(b, 10.0, null)
        assertEquals(0.25, a.bodyPct, 1e-9); assertEquals(0.25, a.upperPct, 1e-9); assertEquals(0.5, a.lowerPct, 1e-9)
        assertEquals(0.75, a.closeAt, 1e-9); assertEquals(1, a.colour); assertEquals(2.0, a.size!!, 1e-9)
        val flat = CandleRead.anatomy(CandleSynth.bar(day.atTime(10, 0), 100.0, 100.0, 100.0, 100.0), null, null)
        assertEquals(0, flat.colour); assertEquals(0.5, flat.closeAt); assertNull(flat.size)
        assertEquals(-1, CandleRead.anatomy(CandleSynth.bar(day.atTime(10, 0), 100.0, 101.0, 95.0, 96.0), 0.0, null).colour)
    }

    @Test fun atrIsThroughThePreviousCandleAndMissingForTheFirstFourteen() {
        val bars = (0 until 20).map { CandleSynth.bar(day.atTime(9, 15).plusMinutes(it * 5L), 100.0, 102.0, 98.0, 100.0) }
        val a = CandleRead.atrPrev(bars)
        for (i in 0 until 14) assertNull(a[i], "$i")
        assertEquals(4.0, a[14]!!, 1e-9)
        assertEquals(4.0, a[19]!!, 1e-9)
        assertTrue(CandleRead.atrPrev(bars.take(3)).all { it == null })
        assertTrue(CandleRead.atrPrev(emptyList()).isEmpty())
    }

    /** 30 calm candles then [last]: ATR is about 4 points. */
    private fun withLast(last: CandleRead.Bar, prev: List<CandleRead.Bar> = emptyList()): List<CandleRead.Read> {
        val calm = (0 until 30).map { CandleSynth.bar(day.atTime(9, 15).plusMinutes(it * 5L), 100.0, 102.0, 98.0, 100.5) }
        return CandleRead.readAll("NIFTY", 5, calm + prev + last)
    }

    @Test fun theR87ShapesKeepTheirThresholds() {
        val t = day.atTime(12, 0)
        // A big tail: lower wick >= 50% of a candle >= 1 ATR.
        val tail = withLast(CandleSynth.bar(t, 100.0, 101.0, 92.0, 100.5)).last()
        assertTrue("big_lower_wick" in tail.shapes, "${tail.shapes}")
        // A marubozu: body >= 85%, range >= 0.8 ATR.
        val maru = withLast(CandleSynth.bar(t, 100.0, 106.1, 99.9, 106.0)).last()
        assertEquals("marubozu_green", maru.primary)
        val doji = withLast(CandleSynth.bar(t, 100.0, 104.0, 96.0, 100.2)).last()
        assertTrue("doji" in doji.shapes && "long_legged_doji" in doji.shapes, "${doji.shapes}")
        val tiny = withLast(CandleSynth.bar(t, 100.0, 100.5, 99.8, 100.3)).last()
        assertTrue("tiny_range" in tiny.shapes)
        assertFalse(tiny.notable)
        assertTrue(maru.notable)
    }

    @Test fun theHammerIsNamedByTheTrendBeforeIt() {
        val a = CandleRead.anatomy(CandleSynth.bar(day.atTime(11, 0), 100.0, 100.5, 90.0, 102.0 - 0.5), 5.0, null)
        // lower wick 10 of 10.5 range? build exactly: o 100, c 102, h 102.5, l 90 -> body 2/12.5 = 16%, upper 4%, lower 80%.
        val b = CandleSynth.bar(day.atTime(11, 0), 100.0, 102.5, 90.0, 102.0)
        val an = CandleRead.anatomy(b, 5.0, null)
        assertTrue(a.colour != 0)
        fun with(trend: Int) = CandleRead.shapes(listOf(b), 0, an, trend, null, null, arrayOf(5.0))
        assertTrue("hammer" in with(-1)); assertTrue("hanging_man" in with(1)); assertTrue("hammer_shape_flat" in with(0))
    }

    @Test fun withoutAtrOnlyTheColourIsKnown() {
        val b = CandleSynth.bar(day.atTime(9, 15), 100.0, 110.0, 90.0, 105.0)
        val reads = CandleRead.readAll("NIFTY", 5, listOf(b))
        assertEquals(listOf("green"), reads.single().shapes)
        assertNull(reads.single().anatomy.atr)
    }

    @Test fun twoCandlePatternsNeverSpanTwoSessions() {
        val calm = (0 until 30).map { CandleSynth.bar(day.atTime(9, 15).plusMinutes(it * 5L), 100.0, 102.0, 98.0, 100.5) }
        val red = CandleSynth.bar(day.atTime(15, 25), 101.0, 101.5, 97.0, 97.5)
        val green = CandleSynth.bar(day.plusDays(1).atTime(9, 15), 97.0, 103.0, 96.9, 102.5)
        val r = CandleRead.readAll("NIFTY", 5, calm + red + green).last()
        assertFalse("bull_engulfing" in r.shapes, "${r.shapes}")
        val sameDay = CandleSynth.bar(day.atTime(15, 30), 97.0, 103.0, 96.9, 102.5)
        val r2 = CandleRead.readAll("NIFTY", 5, calm + red + sameDay).last()
        assertTrue("bull_engulfing" in r2.shapes, "${r2.shapes}")
    }

    @Test fun aStopRunIsAWickPastTheDayHighThatClosesBack() {
        val t = day.atTime(12, 0)
        val r = withLast(CandleSynth.bar(t, 100.0, 103.5, 99.5, 101.0)).last()
        assertEquals("fp_stop_run_up", r.primary)
        assertTrue("new_day_high" in r.context.tags)
    }

    @Test fun contextHasTheDayYesterdayOpeningRangeRoundNumbersAndChain() {
        val bars = CandleSynth.days(day.minusDays(3), 2, 5, start = 24_010.0)          // Friday 16 Oct, then Monday 19 Oct
        val todayIdx = bars.indexOfFirst { it.t.toLocalDate() == day }
        val strikes = listOf(CandleRead.Strike(23_900.0, 100, 9_000, ceOiChg = -50, peOiChg = 500), CandleRead.Strike(24_000.0, 3_000, 4_000),
            CandleRead.Strike(24_300.0, 12_000, 200, ceOiChg = -10, peOiChg = 5))
        val reads = CandleRead.readAll("NIFTY", 5, bars, around = { d ->
            if (d == day) CandleRead.Around(strikes, gamma = "short", expiry = true) else CandleRead.Around()
        })
        val first = reads.first { it.bar.t == day.atTime(9, 15) }
        assertNull(first.context.orHigh, "no opening range before 09:30")
        val r = reads.first { it.bar.t == day.atTime(11, 0) }
        val prev = bars.filter { it.t.toLocalDate() == day.minusDays(3) }
        assertEquals(prev.maxOf { it.h }, r.context.prevHigh!!, 1e-9)
        assertEquals(prev.last().c, r.context.prevClose!!, 1e-9)
        val orBars = bars.filter { it.t.toLocalDate() == day && it.t.isBefore(day.atTime(9, 30)) }
        assertEquals(orBars.maxOf { it.h }, r.context.orHigh!!, 1e-9)
        assertEquals(bars.subList(todayIdx, bars.indexOfFirst { it.t == day.atTime(11, 0) }).maxOf { it.h }, r.context.dayHighBefore!!, 1e-9)
        assertTrue("expiry_day" in r.context.tags && "short_gamma" in r.context.tags && "bull_oi_shift" in r.context.tags, "${r.context.tags}")
        assertEquals(13_200.0 / 15_100.0, r.context.pcr!!, 1e-9)
        if (r.bar.c <= 24_300.0) assertEquals(24_300.0, r.context.callWall)
        assertNull(r.context.vwap, "an index has no volume: no VWAP of its own")
        // Support under the close, resistance over it, each with its source.
        val s = r.context.support!!; val res = r.context.resistance!!
        assertTrue(s.price < r.bar.c && res.price > r.bar.c)
        assertTrue(s.distAtr!! < 0 && res.distAtr!! > 0)
        assertTrue(r.context.vah != null && r.context.val_ != null && r.context.vah!! > r.context.val_!!)
    }

    @Test fun vwapComesFromVolumeOrFromTheAppAndSaysWhere() {
        val bars = CandleSynth.days(day, 1, 5, vol = true)
        val r = CandleRead.readAll("CRUDEOIL", 5, bars, sessionOpenMin = 9 * 60).last()
        assertEquals("its own volume", r.context.vwapFrom)
        val idx = CandleRead.readAll("NIFTY", 5, CandleSynth.days(day, 1, 5), around = { CandleRead.Around(vwap = 24_000.0, vwapFrom = "the future's flow") }).last()
        assertEquals(24_000.0, idx.context.vwap); assertEquals("the future's flow", idx.context.vwapFrom)
        assertNotNull(r.anatomy.volRatio)
    }

    @Test fun roundNumbersFollowTheInstrument() {
        assertEquals(100.0, CandleRead.roundStep("NIFTY", 24_000.0))
        assertEquals(500.0, CandleRead.roundStep("banknifty", 52_000.0))
        assertEquals(50.0, CandleRead.roundStep("CRUDEOIL", 5_600.0))
        assertEquals(1_000.0, CandleRead.roundStep("GOLDM", 120_000.0))
    }

    @Test fun badRowsAndFrozenPrintsAreSkippedNeverGuessed() {
        val good = CandleSynth.days(day, 1, 5).take(20)
        val bad = CandleSynth.bar(day.atTime(11, 0), Double.NaN, 1.0, 0.0, 1.0)
        val upsideDown = CandleSynth.bar(day.atTime(11, 5), 100.0, 90.0, 110.0, 100.0)
        val frozen = (0 until 6).map { CandleSynth.bar(day.atTime(11, 10).plusMinutes(it * 5L), 100.0, 100.0, 100.0, 100.0) }
        val reads = CandleRead.readAll("NIFTY", 5, good + bad + upsideDown + frozen)
        assertEquals(20 + 4, reads.size, "the first four flat ones are read, the frozen run after them is not")
        assertTrue(CandleRead.readAll("NIFTY", 5, emptyList()).isEmpty())
        assertTrue(CandleRead.readAll("NIFTY", 5, listOf(bad)).isEmpty())
    }

    @Test fun readingAfterAMarkReadsOnlyTheNewCandlesTheSameWay() {
        val bars = CandleSynth.days(day, 1, 5)
        val all = CandleRead.readAll("NIFTY", 5, bars)
        val mark = bars[40].t
        val after = CandleRead.readAll("NIFTY", 5, bars, after = mark)
        assertEquals(all.filter { it.bar.t.isAfter(mark) }, after)
    }

    /** The look-ahead guard: a candle's read never changes when later candles arrive. */
    @Test fun noReadLooksAhead() {
        val bars = CandleSynth.days(day.minusDays(2), 3, 5, seed = 11)
        val full = CandleRead.readAll("NIFTY", 5, bars).associateBy { it.bar.t }
        val r = java.util.Random(3)
        repeat(8) {
            val cut = 20 + r.nextInt(bars.size - 20)
            val part = CandleRead.readAll("NIFTY", 5, bars.subList(0, cut))
            for (p in part) assertEquals(full[p.bar.t], p, "candle ${p.bar.t} changed when the data was cut at $cut")
        }
    }

    @Test fun timeOfDayAndTheCandlesTimeAreSaidPlainly() {
        assertEquals("open", CandleRead.timeOfDay(9 * 60 + 20)); assertEquals("close", CandleRead.timeOfDay(15 * 60))
        assertEquals("evening", CandleRead.timeOfDay(19 * 60))
        assertEquals("19 Oct 10:35-10:40", CandleRead.timeOf(day.atTime(10, 35), 5))
        assertEquals("19 Oct 10:35", CandleRead.timeOf(day.atTime(10, 35), 1))
    }
}
