package com.optionslab.ira

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 20 Nov 2026 (audit, B5 items 6 and 8): how old a candle may be and still be read as "now". */
class CandleFreshTest {
    private val t0 = 1_800_000_000L - 1_800_000_000L % 300   // a 5-minute boundary

    @Test fun aFiveMinuteCandleClosedWithinOneCandlePlusTheFeedsLagIsFresh() {
        // Started at t0, closed at t0 + 300.
        assertTrue(CandleFresh.pineFresh(t0, 300, t0 + 300), "just closed")
        assertTrue(CandleFresh.pineFresh(t0, 300, t0 + 300 + 300 + 90), "the next one closed but not published yet")
        assertFalse(CandleFresh.pineFresh(t0, 300, t0 + 300 + 300 + 91), "a candle missing: stale")
        // The audit's case: a candle about 17 minutes old (3 candles plus 2 minutes) is refused now.
        assertFalse(CandleFresh.pineFresh(t0, 300, t0 + 300 * 3 + 120))
    }

    @Test fun eachTimeframeIsJudgedOnItsOwnCandle() {
        assertTrue(CandleFresh.pineFresh(t0, 60, t0 + 60 + 150))
        assertFalse(CandleFresh.pineFresh(t0, 60, t0 + 60 + 151))
        assertTrue(CandleFresh.pineFresh(t0, 900, t0 + 900 + 990))
        assertFalse(CandleFresh.pineFresh(t0, 900, t0 + 900 + 991))
        assertTrue(CandleFresh.pineFresh(t0, 3_600, t0 + 3_600 + 3_690))
        assertFalse(CandleFresh.pineFresh(t0, 3_600, t0 + 3_600 + 3_691))
    }

    @Test fun aDailyChartOrABadStepIsNotJudgedHere() {
        assertTrue(CandleFresh.pineFresh(t0, 86_400, t0 + 10 * 86_400))
        assertTrue(CandleFresh.pineFresh(t0, 0, t0 + 10 * 86_400))
        assertTrue(CandleFresh.pineFresh(t0, -60, t0 + 10 * 86_400))
    }

    @Test fun solosIndexIsStaleWhenItsNewestMinuteIsOverThreeMinutesOld() {
        val now = LocalDateTime.of(2026, 11, 20, 13, 10, 40)
        assertFalse(CandleFresh.indexStale(LocalDateTime.of(2026, 11, 20, 13, 9), now))
        assertFalse(CandleFresh.indexStale(LocalDateTime.of(2026, 11, 20, 13, 7), now), "three minutes back: still read")
        assertTrue(CandleFresh.indexStale(LocalDateTime.of(2026, 11, 20, 13, 6), now))
        assertTrue(CandleFresh.indexStale(LocalDateTime.of(2026, 11, 20, 11, 0), now), "the feed stopped at 11:00")
        assertTrue(CandleFresh.indexStale(null, now), "no minute at all")
        assertFalse(CandleFresh.indexStale(LocalDateTime.of(2026, 11, 20, 13, 5), now, maxMinutes = 5))
    }
}
