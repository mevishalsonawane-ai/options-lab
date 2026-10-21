package com.optionslab.ira

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test
import java.time.LocalDate

/**
 * 21 Oct (Boss's 11:09 diagnostics, stream LIVE): the Candle agent called a healthy live 1-minute series stale. The rule now
 * waits at least [CandleAgent.STALE_MIN_MINUTES] minutes for any candle; 5- and 15-minute candles keep their old limits.
 */
class CandleStaleTest {
    private val day = LocalDate.of(2026, 10, 21)

    @Test fun aOneMinuteSeriesFromTheLastBatchIsNotStale() {
        // Fetched at 11:04 (newest closed minute 11:03), read again from the cache at 11:09: was stale, now is not.
        assertFalse(CandleAgent.stale(day.atTime(11, 3), 1, day.atTime(11, 9), open = true))
        // Even with the cache's full 10 minutes and the untrusted newest minute.
        assertFalse(CandleAgent.stale(day.atTime(10, 56), 1, day.atTime(11, 9), open = true))
    }

    @Test fun aOneMinuteSeriesTrulyBehindIsStillStale() {
        assertTrue(CandleAgent.stale(day.atTime(10, 50), 1, day.atTime(11, 9), open = true))
        assertTrue(CandleAgent.stale(day.atTime(10, 9), 1, day.atTime(11, 9), open = true))
    }

    @Test fun fiveAndFifteenMinuteLimitsAreUnchanged() {
        // 5m: stale once newest + 25 minutes is before now (was the same).
        assertFalse(CandleAgent.stale(day.atTime(11, 0), 5, day.atTime(11, 25), open = true))
        assertTrue(CandleAgent.stale(day.atTime(11, 0), 5, day.atTime(11, 26), open = true))
        // 15m: newest + 75 minutes.
        assertFalse(CandleAgent.stale(day.atTime(10, 0), 15, day.atTime(11, 15), open = true))
        assertTrue(CandleAgent.stale(day.atTime(10, 0), 15, day.atTime(11, 16), open = true))
    }

    @Test fun neverStaleWhileTheMarketIsShut() {
        assertFalse(CandleAgent.stale(day.minusDays(1).atTime(15, 29), 1, day.atTime(11, 9), open = false))
    }
}
