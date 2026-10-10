package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BatteryRound16Test {
    /** A session of watch passes (one a minute, 09:15 to 15:30) reading NIFTY and BANKNIFTY for the widget: Kite requests counted. */
    private fun session(spark: Boolean): Int {
        val kept = HashMap<String, Int>()
        var requests = 0
        for (minute in 555 until 930) for (sym in listOf("NIFTY", "BANKNIFTY")) {
            requests += IndexSpark.requests(spark, kept[sym], minute, keptEmpty = false)
            if (IndexSpark.candles(spark, kept[sym], minute, keptEmpty = false)) kept[sym] = minute
        }
        return requests
    }

    @Test fun theWatchReadsTheQuoteAloneHalfTheRequests() {
        val before = session(spark = true)
        val after = session(spark = false)
        assertEquals(1_500, before)
        assertEquals(750, after)
    }

    @Test fun aScreenThatDrawsTheSparkStillReadsItOnceAMinute() {
        assertTrue(IndexSpark.candles(wanted = true, keptMinute = null, minute = 600, keptEmpty = false))
        assertTrue(IndexSpark.candles(wanted = true, keptMinute = 599, minute = 600, keptEmpty = false))
        assertFalse(IndexSpark.candles(wanted = true, keptMinute = 600, minute = 600, keptEmpty = false))
        // An empty read is tried again in the same minute, as before.
        assertTrue(IndexSpark.candles(wanted = true, keptMinute = 600, minute = 600, keptEmpty = true))
    }

    @Test fun noSparkWantedNeverReadsTheCandles() {
        for (kept in listOf(null, 599, 600)) for (empty in listOf(true, false)) {
            assertFalse(IndexSpark.candles(wanted = false, keptMinute = kept, minute = 600, keptEmpty = empty))
            assertEquals(1, IndexSpark.requests(wanted = false, keptMinute = kept, minute = 600, keptEmpty = empty))
        }
    }
}
