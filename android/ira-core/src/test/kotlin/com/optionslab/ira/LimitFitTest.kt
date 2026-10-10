package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LimitFitTest {
    @Test fun aBuyAtOrAboveTheOfferFillsAtOnce() {
        assertEquals("Fills at once, at about the offer 100.50.", LimitFit.note("BUY", "LIMIT", 100.5, 100.0, 100.5, 100.2))
        assertEquals("Fills at once, at about the offer 100.50.", LimitFit.note("BUY", "LIMIT", 101.0, 100.0, 100.5, 100.2))
        assertEquals("Fills at once, at about the bid 100.00.", LimitFit.note("SELL", "LIMIT", 99.0, 100.0, 100.5, 100.2))
    }

    @Test fun aLimitFarBeyondTheMarketIsCheckedForATypo() {
        assertEquals("Fills at once, at about the offer 100.50. Your limit is 49% above the offer: check it is not a typo.",
            LimitFit.note("BUY", "LIMIT", 150.0, 100.0, 100.5, 100.2))
        assertEquals("Waits 50% below the bid 100.00: it fills only if the price comes to it. That is far from the market: check it is not a typo.",
            LimitFit.note("BUY", "LIMIT", 50.0, 100.0, 100.5, 100.2))
    }

    @Test fun aLimitAwayFromTheMarketWaits() {
        assertEquals("Waits 2.0% below the bid 100.00: it fills only if the price comes to it.", LimitFit.note("BUY", "LIMIT", 98.0, 100.0, 100.5, 100.2))
        assertEquals("Waits 1.5% above the offer 100.50: it fills only if the price comes to it.", LimitFit.note("SELL", "LIMIT", 102.0, 100.0, 100.5, 100.2))
    }

    @Test fun insideTheSpreadWaitsForTheOtherSide() {
        assertEquals("Waits inside the spread (offer 100.50): it fills when a seller meets it.", LimitFit.note("BUY", "LIMIT", 100.2, 100.0, 100.5, 100.2))
        assertEquals("Waits inside the spread (bid 100.00): it fills when a buyer meets it.", LimitFit.note("SELL", "LIMIT", 100.3, 100.0, 100.5, 100.2))
    }

    @Test fun aWideSpreadIsSaid() {
        assertEquals("Fills at once, at about the offer 11.00. The spread is wide: 1.00 (9.5% of the price).", LimitFit.note("BUY", "LIMIT", 11.0, 10.0, 11.0, 10.5))
        assertEquals("Market order: it fills at about the offer 11.00, not the last 10.50. The spread is wide: 1.00 (9.5% of the price).",
            LimitFit.note("BUY", "MARKET", null, 10.0, 11.0, 10.5))
        assertEquals("Market order: it fills at about the bid 10.00, not the last 10.50. The spread is wide: 1.00 (9.5% of the price).",
            LimitFit.note("SELL", "MARKET", null, 10.0, 11.0, 10.5))
    }

    @Test fun aMarketOrderOnATightSpreadSaysNothing() {
        assertNull(LimitFit.note("BUY", "MARKET", null, 100.0, 100.5, 100.2))
    }

    @Test fun noQuoteSidesShowing() {
        assertEquals("Market order with no offer showing: the fill price is not known (last 100.20).", LimitFit.note("BUY", "MARKET", null, 100.0, null, 100.2))
        assertEquals("Market order with no bid showing: the fill price is not known.", LimitFit.note("SELL", "MARKET", null, 0.0, 100.5, null))
        assertEquals("No bid or offer showing; your limit is 20% above the last 100.00: check it is not a typo.", LimitFit.note("BUY", "LIMIT", 120.0, null, null, 100.0))
        assertNull(LimitFit.note("BUY", "LIMIT", 101.0, null, null, 100.0))
        assertNull(LimitFit.note("BUY", "LIMIT", 101.0, null, null, null))
    }

    @Test fun stopsAndMissingPricesSayNothing() {
        assertNull(LimitFit.note("BUY", "SL", 101.0, 100.0, 100.5, 100.2))
        assertNull(LimitFit.note("SELL", "SL-M", null, 100.0, 100.5, 100.2))
        assertNull(LimitFit.note("BUY", "LIMIT", null, 100.0, 100.5, 100.2))
        assertNull(LimitFit.note("BUY", "LIMIT", Double.NaN, 100.0, 100.5, 100.2))
    }

    @Test fun spreadNeedsBothSides() {
        assertNull(LimitFit.spread(null, 1.0))
        assertNull(LimitFit.spread(2.0, 1.0))
        assertEquals(0.5 to 0.5 / 100.25, LimitFit.spread(100.0, 100.5))
    }
}
