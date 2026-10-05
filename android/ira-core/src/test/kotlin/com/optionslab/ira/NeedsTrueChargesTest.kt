package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reasoning, round 31: the premium (and index) move that just covers a bought option's round-trip charges. */
class NeedsTrueChargesTest {
    private val at = LocalDateTime.of(2026, 10, 6, 13, 15)          // a Tuesday
    private val put = PositionHealth.Pos("Paper", "NIFTY2610824500PE", 75, 80.0, 60.0, "NIFTY", 24500.0, "PE", LocalDate.of(2026, 10, 8),
        spot = 24650.0, theta = -6.0)
    private val call = PositionHealth.Pos("Zerodha", "SENSEX26OCT82000CE", 20, 200.0, 230.0, "SENSEX", 82000.0, "CE", LocalDate.of(2026, 10, 8),
        spot = 81900.0)
    private val ADVICE = Regex("(?i)\\b(should|recommend|suggest|will (rise|fall)|likely|exit|hold on)\\b")

    @Test fun roundTripByHand() {
        // Buy 75 at 80 (6,000), sell 75 at 81 (6,075): Rs 20 an order, STT 0.15% of the sale, exchange 0.03553%, SEBI Rs 10 a
        // crore, stamp 0.003% of the buy, GST 18% on brokerage + exchange + SEBI.
        val buy = 20 + 6000 * 0.0003553 + 6000 * 1e-6 + 6000 * 0.00003 + (20 + 6000 * 0.0003553 + 6000 * 1e-6) * 0.18
        val sell = 20 + 6075 * 0.0015 + 6075 * 0.0003553 + 6075 * 1e-6 + (20 + 6075 * 0.0003553 + 6075 * 1e-6) * 0.18
        assertEquals(buy + sell, NeedsTrue.roundTrip("NIFTY2610824500PE", "NFO", 75, 80.0, 81.0), 1e-9)
    }

    @Test fun exitToCoverIsAFixedPoint() {
        val (sell, c) = NeedsTrue.exitToCover("NIFTY2610824500PE", "NFO", 75, 80.0)!!
        // The sale at that price just covers the round trip's charges.
        assertEquals(c, (sell - 80.0) * 75, 1e-6)
        assertEquals(c, NeedsTrue.roundTrip("NIFTY2610824500PE", "NFO", 75, 80.0, sell), 1e-6)
        assertTrue(c > 55 && c < 70, c.toString())
        // A bigger quantity spreads the Rs 40 of brokerage: fewer points each.
        val (sell10, _) = NeedsTrue.exitToCover("NIFTY2610824500PE", "NFO", 750, 80.0)!!
        assertTrue(sell10 - 80 < sell - 80)
        assertNull(NeedsTrue.exitToCover("X", "NFO", 0, 80.0))
        assertNull(NeedsTrue.exitToCover("X", "NFO", 75, 0.0))
    }

    @Test fun aBoughtPutSaid() {
        val s = NeedsTrue.chargesCover(put, at)
        assertNotNull(s)
        val (sell, c) = NeedsTrue.exitToCover(put.symbol, "NFO", 75, 80.0)!!
        val d = NeedsTrue.deltaNow(put, at)!!
        fun f(x: Double) = "%,.2f".format(Locale.ENGLISH, x)
        assertEquals("Charges: a round trip of 75 at your 80.00 comes to about Rs ${"%,.0f".format(Locale.ENGLISH, c)} on Zerodha's F&O schedule " +
            "(one order each way, an estimate), so it has to sell at about ${f(sell)} just to cover them - ${f(sell - 80)} above your average; " +
            "at its delta of ${"%.2f".format(Locale.ENGLISH, kotlin.math.abs(d))} now, that is about ${f((sell - 80) / kotlin.math.abs(d))} Nifty points down. " +
            "It is at 60.00 now, ${f(sell - 60)} below that. Held to expiry, the breakeven with them is about ${f(24500 - sell)} rather than 24,420.00 " +
            "(the exercise's own charges aside).", s)
        assertFalse(ADVICE.containsMatchIn(s), s)
    }

    @Test fun aBoughtSensexCallAboveIt() {
        val s = NeedsTrue.chargesCover(call, at)!!
        assertTrue(s.contains("Sensex points up"), s)
        val (sell, _) = NeedsTrue.exitToCover(call.symbol, "BFO", 20, 200.0)!!
        assertTrue(s.contains("It is at 230.00 now, ${"%,.2f".format(Locale.ENGLISH, 230 - sell)} above that."), s)
        assertTrue(s.contains("about ${"%,.2f".format(Locale.ENGLISH, 82000 + sell)} rather than 82,200.00"), s)
        // No spot: no delta, so the premium points alone.
        val n = NeedsTrue.chargesCover(call.copy(spot = null), at)!!
        assertFalse(n.contains("delta"), n)
        assertTrue(n.contains("above your average. It is at 230.00"), n)
    }

    @Test fun notForASellerFuturesOrNothing() {
        assertNull(NeedsTrue.chargesCover(put.copy(qty = -75), at))
        assertNull(NeedsTrue.chargesCover(put.copy(right = null, strike = null, symbol = "NIFTY26OCTFUT"), at))
        assertNull(NeedsTrue.chargesCover(put.copy(avg = 0.0), at))
    }

    @Test fun saidInTheNeedsAnswer() {
        val l = NeedsTrue.lines("where is my breakeven", listOf(put), emptyMap(), at)
        val i = l.indexOfFirst { it.startsWith("Charges: a round trip of 75") }
        assertTrue(i > 1, l.toString())
        assertTrue(l.any { it.startsWith("That is at expiry, from your average price, charges left out but for the charges line;") }, l.toString())
        // Spot unknown: still said.
        assertTrue(NeedsTrue.lines("my breakeven", listOf(put.copy(spot = null)), emptyMap(), at).any { it.startsWith("Charges: ") })
        // A seller alone: no charges line, and the closing line says charges are left out.
        val short = NeedsTrue.lines("where is my breakeven", listOf(put.copy(qty = -75)), emptyMap(), at)
        assertTrue(short.none { it.startsWith("Charges: ") }, short.toString())
        assertTrue(short.any { it.startsWith("That is at expiry, from your average price, charges left out; before") }, short.toString())
    }
}
