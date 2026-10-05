package com.optionslab.ira

import com.optionslab.engine.sandbox.SandboxCosts
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Boss, 5 Oct: the P&L is shown before charges, the charges on a small line under it. */
class PnlChargesTest {
    @Test fun grossIsNetPlusTheCharges() {
        assertEquals(2_575.0, PnlCharges.gross(2_395.0, 180.0))
        assertEquals(-1_020.0, PnlCharges.gross(-1_200.0, 180.0), "a loss before charges is smaller")
        assertEquals(2_395.0, PnlCharges.net(2_575.0, 180.0))
        assertEquals(500.0, PnlCharges.gross(500.0, 0.0))
    }

    @Test fun theZerodhaEstimateIsTheChargesReportsSchedule() {
        val fills = listOf(PnlCharges.Fill("BUY", 100.0, 75), PnlCharges.Fill("SELL", 120.0, 75))
        val each = fills.map { SandboxCosts.breakdown(it.side, it.price, it.qty).values.sum() }
        assertEquals(Math.round(each.sum() * 100) / 100.0, PnlCharges.estimate(fills))
        // The same as the paper account pays for those legs, to the paisa.
        val paid = SandboxCosts.charge("BUY", BigDecimal("100"), 75) + SandboxCosts.charge("SELL", BigDecimal("120"), 75)
        assertEquals(paid.toDouble(), PnlCharges.estimate(fills), 0.011)
        assertEquals(0.0, PnlCharges.estimate(emptyList()))
    }

    @Test fun theSmallLine() {
        assertEquals("Charges ₹180", PnlCharges.line(180.4, estimate = false))
        assertEquals("Charges ≈ ₹180 (estimate)", PnlCharges.line(179.6, estimate = true))
        assertEquals("Charges ₹1,23,457", PnlCharges.line(123_456.7, estimate = false))
        assertEquals("Charges ₹0.45", PnlCharges.line(0.45, estimate = false))
        assertNull(PnlCharges.line(0.0, estimate = false))
        assertNull(PnlCharges.line(null, estimate = true))
        assertNull(PnlCharges.line(Double.NaN, estimate = false))
        assertFalse(PnlCharges.shown(0.004))
        assertTrue(PnlCharges.shown(0.005))
    }

    @Test fun whatJarvisSays() {
        assertEquals("charges Rs 180", PnlCharges.said(180.0, estimate = false))
        assertEquals("charges about Rs 1,234", PnlCharges.said(1_234.4, estimate = true))
        assertNull(PnlCharges.said(0.0, estimate = false))
    }

    @Test fun indianGrouping() {
        assertEquals("999", PnlCharges.indian(999))
        assertEquals("1,000", PnlCharges.indian(1_000))
        assertEquals("12,34,567", PnlCharges.indian(1_234_567))
        assertEquals("-1,00,000", PnlCharges.indian(-100_000))
    }

    @Test
    fun brokerageIsPerOrderNotPerFill() {
        // One 10-lot buy order that Zerodha filled in 5 pieces, and one sell order in 4: Rs 20 brokerage twice, not 9 times.
        val buys = List(5) { PnlCharges.Fill("BUY", 100.0, 150, "B1") }
        val sells = List(4) { PnlCharges.Fill("SELL", 110.0, 187, "S1") } + PnlCharges.Fill("SELL", 110.0, 2, "S1")
        val split = PnlCharges.estimate(buys + sells)
        val whole = PnlCharges.estimate(listOf(PnlCharges.Fill("BUY", 100.0, 750, "B1"), PnlCharges.Fill("SELL", 110.0, 750, "S1")))
        assertEquals(whole, split, 0.02)
        // Per fill, as before 5 Oct, it was about 7 x Rs 23.6 more.
        val old = (buys + sells).sumOf { SandboxCosts.breakdown(it.side, it.price, it.qty).values.sum() }
        assertTrue(old - split > 7 * 23.0)
        // Only the first fill of an order pays the brokerage; a fill with no order id is its own order.
        val legs = PnlCharges.perFill(buys)
        assertEquals(20.0, legs[0].getValue("Brokerage"))
        assertTrue(legs.drop(1).all { it.getValue("Brokerage") == 0.0 && it.getValue("GST") < 1.0 })
        assertEquals(40.0, PnlCharges.perFill(listOf(PnlCharges.Fill("BUY", 1.0, 1), PnlCharges.Fill("BUY", 1.0, 1))).sumOf { it.getValue("Brokerage") })
    }
}
