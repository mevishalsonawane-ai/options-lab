package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 15 Nov 2026 (Boss: "Yes fix it"): [PriceNow] - which price may be judged as "the price now" for a held option's stop. The five
 * FINNIFTY paper trades of 5, 11 and 13 Nov (bought near 430 - 460, "stopped" seconds later at 68.70 / 265.00 / 354.40) are the cases.
 */
class PriceNowTest {
    /** The entry minute: 09:20:00 of some day, in epoch seconds. */
    private val entry = 1_794_000_000L

    @Test fun aLastPriceCountsOnlyWhenItTradedInTheEntryMinuteOrLater() {
        assertTrue(PriceNow.lastCounts(431.0, entry + 5, entry))
        assertTrue(PriceNow.lastCounts(431.0, entry, entry))
        assertTrue(PriceNow.lastCounts(431.0, entry - PriceNow.SLACK_SEC, entry), "the clock's slack")
        assertFalse(PriceNow.lastCounts(431.0, entry - PriceNow.SLACK_SEC - 1, entry))
        // 5 Nov: FINNIFTY 26150 CE's last price 68.70 was a trade of an earlier day.
        assertFalse(PriceNow.lastCounts(68.70, entry - 86_400, entry))
        // A packet with no trade time (not full mode): nothing can be told, taken as before.
        assertTrue(PriceNow.lastCounts(431.0, null, entry)); assertTrue(PriceNow.lastCounts(431.0, 0, entry))
        for (bad in listOf(null, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) assertFalse(PriceNow.lastCounts(bad, entry + 5, entry), "$bad")
    }

    @Test fun aBookWithNoLastPriceGivesItsBidOnlyWhenSane() {
        assertEquals(430.0, PriceNow.bookBid(430.0, 430.05), "one tick wide")
        assertEquals(430.0, PriceNow.bookBid(430.0, 431.5), "inside h14's cap")
        // 5 Nov: a stray bid far under the ask is not what the option is worth.
        assertNull(PriceNow.bookBid(68.70, 460.75))
        assertNull(PriceNow.bookBid(265.0, 432.45))
        assertNull(PriceNow.bookBid(430.0, null)); assertNull(PriceNow.bookBid(null, 431.0)); assertNull(PriceNow.bookBid(null, null))
        assertNull(PriceNow.bookBid(431.0, 430.0), "crossed")
        assertNull(PriceNow.bookBid(0.0, 0.0))
    }

    @Test fun aLastPriceWithNoTradeTimeFarOutsideASaneBookIsNotThePriceNow() {
        assertTrue(PriceNow.farFromBook(68.70, null, 455.0, 455.05)); assertTrue(PriceNow.farFromBook(68.70, 0, 455.0, 455.05))
        assertTrue(PriceNow.farFromBook(600.0, null, 455.0, 455.05))
        assertFalse(PriceNow.farFromBook(450.0, null, 455.0, 455.05), "near the book")
        assertFalse(PriceNow.farFromBook(68.70, entry + 5, 455.0, 455.05), "a trade time is there: it decides")
        assertFalse(PriceNow.farFromBook(68.70, null, 68.0, 460.0), "no sane book to compare with")
        assertFalse(PriceNow.farFromBook(68.70, null, null, null)); assertFalse(PriceNow.farFromBook(null, null, 455.0, 455.05))
    }

    @Test fun aCandleCountsFromTheEntryMinuteOn() {
        assertTrue(PriceNow.candleCounts(entry, entry)); assertTrue(PriceNow.candleCounts(entry + 60, entry))
        assertFalse(PriceNow.candleCounts(entry - 60, entry)); assertFalse(PriceNow.candleCounts(null, entry))
    }

    @Test fun theLastResortQuote() {
        // No last price: the bid of a sane book, never of a wide one, whatever the candles say.
        assertEquals(430.0, PriceNow.ofQuote(430.0, true, 430.0, 430.05, null, null, entry))
        assertNull(PriceNow.ofQuote(68.70, true, 68.70, 460.75, null, entry + 60, entry))
        // A quote that only repeats the stream's last price that did not count is no price.
        assertNull(PriceNow.ofQuote(68.70, false, 455.0, 460.75, 68.70, entry + 60, entry))
        // A candle close: only from the entry minute or later.
        assertEquals(410.0, PriceNow.ofQuote(410.0, false, 0.0, 0.0, null, entry + 120, entry))
        assertEquals(410.0, PriceNow.ofQuote(410.0, false, 0.0, 0.0, 68.70, entry + 120, entry), "another price than the stale one")
        assertNull(PriceNow.ofQuote(380.0, false, 0.0, 0.0, null, entry - 240, entry), "a 09:16 close for a 09:20 entry")
        assertNull(PriceNow.ofQuote(380.0, false, 0.0, 0.0, null, null, entry))
        for (bad in listOf(null, 0.0, -5.0, Double.NaN)) assertNull(PriceNow.ofQuote(bad, false, 0.0, 0.0, null, entry + 60, entry))
    }
}
