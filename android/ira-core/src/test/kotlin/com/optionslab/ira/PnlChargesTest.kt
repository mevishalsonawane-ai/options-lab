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

    @Test
    fun eachTradePaysItsOwnSchedule() {
        val seg = PnlCharges::segment
        assertEquals(PnlCharges.Segment.OPTIONS, seg("NIFTY26OCT24500CE", "NFO", "NRML"))
        assertEquals(PnlCharges.Segment.FUTURES, seg("NIFTY26OCTFUT", "NFO", "NRML"))
        assertEquals(PnlCharges.Segment.DELIVERY, seg("INFY", "NSE", "CNC"))
        assertEquals(PnlCharges.Segment.INTRADAY, seg("INFY", "NSE", "MIS"))
        assertEquals(PnlCharges.Segment.OPTIONS, seg("", "", ""))
        assertEquals(PnlCharges.Segment.DELIVERY, seg("RELIANCE", "", ""))

        // A future: 1 lot of Nifty (75) at 25,000 bought and sold the same day = Rs 18.75 lakh each way.
        val fut = PnlCharges.perFill(listOf(
            PnlCharges.Fill("BUY", 25_000.0, 75, "F1", "NIFTY26OCTFUT", "NFO", "NRML", "2026-10-05"),
            PnlCharges.Fill("SELL", 25_100.0, 75, "F2", "NIFTY26OCTFUT", "NFO", "NRML", "2026-10-05")))
        assertEquals(20.0, fut[0].getValue("Brokerage"), 1e-9)                      // 0.03% of 18.75 lakh is 562: capped at Rs 20
        assertEquals(0.0, fut[0].getValue("STT"), 1e-9)
        assertEquals(25_100.0 * 75 * 0.0005, fut[1].getValue("STT"), 1e-9)          // 0.05% on the sale, not 0.15%
        assertEquals(25_000.0 * 75 * 0.00002, fut[0].getValue("Stamp duty"), 1e-9)
        assertEquals(25_000.0 * 75 * 0.0000173, fut[0].getValue("Exchange"), 1e-9)

        // Shares held: no brokerage, STT 0.1% both ways, stamp 0.015% on the buy, DP once per scrip a day on the sale.
        val held = PnlCharges.perFill(listOf(
            PnlCharges.Fill("BUY", 1_500.0, 10, "S1", "INFY", "NSE", "CNC", "2026-10-01"),
            PnlCharges.Fill("SELL", 1_600.0, 5, "S2", "INFY", "NSE", "CNC", "2026-10-05"),
            PnlCharges.Fill("SELL", 1_600.0, 5, "S3", "INFY", "NSE", "CNC", "2026-10-05")))
        assertTrue(held.all { it.getValue("Brokerage") == 0.0 })
        assertEquals(15.0, held[0].getValue("STT"), 1e-9)
        assertEquals(8.0, held[1].getValue("STT"), 1e-9)
        assertEquals(1_500.0 * 10 * 0.00015, held[0].getValue("Stamp duty"), 1e-9)
        assertEquals(13.0, held[1].getValue("DP charges"), 1e-9)
        assertFalse(held[2].containsKey("DP charges"), "DP once per scrip a day")

        // Same-day shares: 0.03% of the order up to Rs 20 (a small order pays less), STT 0.025% on the sale only.
        val day = PnlCharges.perFill(listOf(
            PnlCharges.Fill("BUY", 500.0, 20, "M1", "TATASTEEL", "NSE", "MIS", "2026-10-05"),
            PnlCharges.Fill("SELL", 505.0, 20, "M2", "TATASTEEL", "NSE", "MIS", "2026-10-05")))
        assertEquals(500.0 * 20 * 0.0003, day[0].getValue("Brokerage"), 1e-9)       // Rs 3, under the Rs 20 cap
        assertEquals(0.0, day[0].getValue("STT"), 1e-9)
        assertEquals(505.0 * 20 * 0.00025, day[1].getValue("STT"), 1e-9)

        // An option is unchanged: the same as the paper account's schedule.
        val opt = PnlCharges.Fill("SELL", 120.0, 75, "O1", "NIFTY26OCT24500CE", "NFO", "NRML")
        assertEquals(SandboxCosts.breakdown("SELL", 120.0, 75).values.sum(), PnlCharges.perFill(listOf(opt)).single().values.sum(), 1e-9)
    }
}
