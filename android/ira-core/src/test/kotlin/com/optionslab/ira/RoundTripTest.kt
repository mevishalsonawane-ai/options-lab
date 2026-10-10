package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RoundTripTest {
    private fun near(expected: Double, actual: Double) = assertEquals(expected, actual, 1e-6)

    // Option, 75 at 100 (value 7,500 a side). Buy: brokerage 20, exchange 2.66475, SEBI 0.0075, stamp 0.225,
    // GST 0.18 x 22.67225 = 4.081005 -> 26.978255. Sell: 20, STT 11.25, 2.66475, 0.0075, GST 4.081005 -> 38.003255.
    @Test fun anOption() {
        val c = RoundTrip.cost("BUY", 100.0, 75, "NIFTY25OCT25000CE", "NFO", "NRML")!!
        near(64.98151, c.charges)
        near(64.98151 / 75, c.points)
        assertEquals("Round trip ≈ ₹65 (to buy and sell back at this price); needs +0.87 points on the premium to cover it",
            RoundTrip.line("BUY", 100.0, 75, "NIFTY25OCT25000CE", "NFO", "NRML"))
        assertEquals("Round trip ≈ ₹65 (to sell and buy back at this price); needs the premium to fall 0.87 points to cover it",
            RoundTrip.line("SELL", 100.0, 75, "NIFTY25OCT25000CE", "NFO", "NRML"))
    }

    // Future, 75 at 25,000 (value 18,75,000 a side). Brokerage min(20, 562.50) = 20. Buy: exchange 32.4375, SEBI 1.875,
    // stamp 37.5, GST 0.18 x 54.3125 = 9.77625 -> 101.58875. Sell: 20, STT 937.5, 32.4375, 1.875, 9.77625 -> 1,001.58875.
    @Test fun aFuture() {
        near(1103.1775, RoundTrip.cost("BUY", 25000.0, 75, "NIFTY25OCTFUT", "NFO", "NRML")!!.charges)
        assertEquals("Round trip ≈ ₹1,103 (to buy and sell back at this price); needs +14.71 points on the price to cover it",
            RoundTrip.line("BUY", 25000.0, 75, "NIFTY25OCTFUT", "NFO", "NRML"))
    }

    // Held shares, 10 at 1,500 (value 15,000 a side), sold another day. Buy: STT 15, exchange 0.4455, SEBI 0.015,
    // stamp 2.25, GST 0.18 x 0.4605 = 0.08289 -> 17.79339. Sell: STT 15, 0.4455, 0.015, DP 13, GST 0.18 x 13.4605 = 2.42289
    // -> 30.88339.
    @Test fun aDeliveryShare() {
        near(48.67678, RoundTrip.cost("BUY", 1500.0, 10, "INFY", "NSE", "CNC")!!.charges)
        assertEquals("Round trip ≈ ₹49 (to buy and sell back at this price); needs +4.87 points on the price to cover it",
            RoundTrip.line("BUY", 1500.0, 10, "INFY", "NSE", "CNC"))
    }

    // Same-day shares, 10 at 1,500. Brokerage min(20, 4.50) = 4.5. Buy: 4.5, exchange 0.4455, SEBI 0.015, stamp 0.45,
    // GST 0.18 x 4.9605 = 0.89289 -> 6.30339. Sell: 4.5, STT 3.75, 0.4455, 0.015, 0.89289 -> 9.60339.
    @Test fun anIntradayShare() {
        near(15.90678, RoundTrip.cost("BUY", 1500.0, 10, "INFY", "NSE", "MIS")!!.charges)
        assertEquals("Round trip ≈ ₹16 (to buy and sell back at this price); needs +1.59 points on the price to cover it",
            RoundTrip.line("BUY", 1500.0, 10, "INFY", "NSE", "MIS"))
    }

    @Test fun nothingToSay() {
        assertNull(RoundTrip.line("BUY", null, 75, "NIFTY25OCT25000CE", "NFO", "NRML"))
        assertNull(RoundTrip.line("BUY", 0.0, 75, "NIFTY25OCT25000CE", "NFO", "NRML"))
        assertNull(RoundTrip.line("BUY", 100.0, 0, "NIFTY25OCT25000CE", "NFO", "NRML"))
        // A commodity (GOLD on MCX): approximate rates, no line.
        assertNull(RoundTrip.line("BUY", 72000.0, 1, "GOLDM25NOVFUT", "MCX", "NRML"))
    }
}
