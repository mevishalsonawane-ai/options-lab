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
        assertEquals(430.0, PriceNow.bookBid(430.0, 431.5))
        // Review: a wide but real book still prices a stop (an entry's 0.5% test would refuse these).
        assertEquals(95.0, PriceNow.bookBid(95.0, 96.0)); assertEquals(60.0, PriceNow.bookBid(60.0, 66.0))
        assertEquals(85.0, PriceNow.bookBid(85.0, 100.0), "at the edge"); assertNull(PriceNow.bookBid(84.9, 100.0))
        assertNull(PriceNow.bookBid(354.40, 427.50), "13 Nov")
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

    @Test fun aLiveStopsPriceIsTheBookWhenTheLastPriceIsFarFromIt() {
        assertEquals(455.0, PriceNow.stopLast(68.70, 455.0, 455.05), "an old last price: the sane book's bid")
        assertEquals(450.0, PriceNow.stopLast(450.0, 455.0, 455.05), "near the book: the last price")
        assertEquals(68.70, PriceNow.stopLast(68.70, 68.0, 460.0), "no sane book: the last price, as before")
        assertEquals(68.70, PriceNow.stopLast(68.70, null, null))
    }

    @Test fun thePricePathTakesNewTradesOnly() {
        val now = entry + 600
        // No trade time: every packet, as before.
        assertTrue(PriceNow.newTrade(430.0, null, false, null, null, now)); assertTrue(PriceNow.newTrade(430.0, 0, true, 430.0, 0, now))
        // A contract's first packet: only a recent trade (68.70 from yesterday adds nothing).
        assertFalse(PriceNow.newTrade(68.70, now - 86_400, false, null, null, now))
        assertTrue(PriceNow.newTrade(430.0, now - 5, false, null, null, now))
        assertFalse(PriceNow.newTrade(430.0, now - PriceNow.RECENT_SEC - 1, false, null, null, now))
        // The same trade sent again with a changed book: not new. A later trade, or another price in the same second: new.
        assertFalse(PriceNow.newTrade(68.70, now - 86_400, true, 68.70, now - 86_400, now))
        assertTrue(PriceNow.newTrade(431.0, now, true, 68.70, now - 86_400, now))
        assertTrue(PriceNow.newTrade(431.5, now, true, 431.0, now, now))
        assertFalse(PriceNow.newTrade(431.0, now, true, 431.0, now, now))
        assertTrue(PriceNow.newTrade(431.0, now, true, 431.0, null, now), "the packet before had no trade time: a recent trade counts")
        assertFalse(PriceNow.newTrade(68.70, now - 86_400, true, 0.0, null, now), "after a packet with no last price, an old trade still adds nothing")
        for (bad in listOf(0.0, -1.0, Double.NaN)) assertFalse(PriceNow.newTrade(bad, now, true, 431.0, now - 1, now))
    }

    @Test fun aLastPriceOfAnotherSessionIsNeverACandle() {
        assertTrue(PriceNow.anotherSession(entry - 86_400, entry)); assertTrue(PriceNow.anotherSession(entry - PriceNow.SESSION_SEC - 1, entry))
        assertFalse(PriceNow.anotherSession(entry - 3_600, entry)); assertFalse(PriceNow.anotherSession(null, entry)); assertFalse(PriceNow.anotherSession(0, entry))
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
