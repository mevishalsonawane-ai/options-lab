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
}
