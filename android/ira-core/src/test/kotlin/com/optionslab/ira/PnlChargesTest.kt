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

    private val d = "2026-10-05"

    @Test
    fun sharesBoughtAndSoldTheSameDayUnderCncAreASameDayTrade() {
        // 10 INFY bought at 1,000 and sold at 1,010 the same day, both CNC: Zerodha treats it as intraday.
        val legs = PnlCharges.perFill(listOf(
            PnlCharges.Fill("BUY", 1_000.0, 10, "B1", "INFY", "NSE", "CNC", d),
            PnlCharges.Fill("SELL", 1_010.0, 10, "S1", "INFY", "NSE", "CNC", d)))
        // Buy, Rs 10,000: brokerage 0.03% = 3, exchange 0.297, SEBI 0.01, stamp 0.003% = 0.3, GST 18% of 3.307 = 0.59526.
        assertEquals(3.0, legs[0].getValue("Brokerage"), 1e-9)
        assertEquals(0.0, legs[0].getValue("STT"), 1e-9)
        assertEquals(0.3, legs[0].getValue("Stamp duty"), 1e-9)
        assertEquals(4.20226, legs[0].values.sum(), 1e-9)
        // Sell, Rs 10,100: brokerage 3.03, STT 0.025% = 2.525, exchange 0.29997, SEBI 0.0101, GST 0.6012126; no DP charge.
        assertEquals(3.03, legs[1].getValue("Brokerage"), 1e-9)
        assertEquals(2.525, legs[1].getValue("STT"), 1e-9)
        assertFalse(legs[1].containsKey("DP charges"))
        assertEquals(6.4662826, legs[1].values.sum(), 1e-9)
        assertEquals(10.67, PnlCharges.estimate(listOf(
            PnlCharges.Fill("BUY", 1_000.0, 10, "B1", "INFY", "NSE", "CNC", d),
            PnlCharges.Fill("SELL", 1_010.0, 10, "S1", "INFY", "NSE", "CNC", d))))
        // The same as the trade under MIS, to the paisa.
        assertEquals(10.67, PnlCharges.estimate(listOf(
            PnlCharges.Fill("BUY", 1_000.0, 10, "B1", "INFY", "NSE", "MIS", d),
            PnlCharges.Fill("SELL", 1_010.0, 10, "S1", "INFY", "NSE", "MIS", d))))
        // On different days: held shares (STT 0.1% each way, DP on the sale), as before.
        val held = PnlCharges.perFill(listOf(
            PnlCharges.Fill("BUY", 1_000.0, 10, "B1", "INFY", "NSE", "CNC", "2026-10-01"),
            PnlCharges.Fill("SELL", 1_010.0, 10, "S1", "INFY", "NSE", "CNC", d)))
        assertEquals(10.0, held[0].getValue("STT"), 1e-9)
        assertEquals(13.0, held[1].getValue("DP charges"), 1e-9)
    }

    @Test
    fun aSaleBeyondTheDaysBuysIsADeliverySaleAndPaysDpOnce() {
        // 5 bought at 1,000 and 8 sold at 1,000 the same day (3 held from before), CNC.
        val legs = PnlCharges.perFill(listOf(
            PnlCharges.Fill("BUY", 1_000.0, 5, "B1", "INFY", "NSE", "CNC", d),
            PnlCharges.Fill("SELL", 1_000.0, 8, "S1", "INFY", "NSE", "CNC", d)))
        // Buy, Rs 5,000 same-day: brokerage 1.5, exchange 0.1485, SEBI 0.005, stamp 0.15, GST 18% of 1.6535 = 0.29763.
        assertEquals(1.5, legs[0].getValue("Brokerage"), 1e-9)
        assertEquals(0.15, legs[0].getValue("Stamp duty"), 1e-9)
        assertEquals(2.10113, legs[0].values.sum(), 1e-9)
        // Sell: Rs 5,000 same-day (brokerage 1.5, STT 1.25) + Rs 3,000 held (STT 3, DP 13, no brokerage).
        assertEquals(1.5, legs[1].getValue("Brokerage"), 1e-9)
        assertEquals(4.25, legs[1].getValue("STT"), 1e-9)
        assertEquals(0.2376, legs[1].getValue("Exchange"), 1e-9)
        assertEquals(13.0, legs[1].getValue("DP charges"), 1e-9)
        // GST: 18% of (1.5 + 0.1485 + 0.005) + 18% of (0.0891 + 0.003 + 13) = 0.29763 + 2.356578.
        assertEquals(2.654208, legs[1].getValue("GST"), 1e-9)
        assertEquals(21.649808, legs[1].values.sum(), 1e-9)
        assertEquals(23.75, PnlCharges.estimate(listOf(
            PnlCharges.Fill("BUY", 1_000.0, 5, "B1", "INFY", "NSE", "CNC", d),
            PnlCharges.Fill("SELL", 1_000.0, 8, "S1", "INFY", "NSE", "CNC", d))))
    }

    @Test
    fun aBuyBeyondTheDaysSalesIsHeld() {
        // 10 bought at 100, 4 sold at 110 the same day: 4 bought are same-day, 6 are held.
        val legs = PnlCharges.perFill(listOf(
            PnlCharges.Fill("BUY", 100.0, 10, "B1", "TATASTEEL", "NSE", "CNC", d),
            PnlCharges.Fill("SELL", 110.0, 4, "S1", "TATASTEEL", "NSE", "CNC", d)))
        assertEquals(0.12, legs[0].getValue("Brokerage"), 1e-9)                    // 0.03% of the Rs 400 same-day part
        assertEquals(0.6, legs[0].getValue("STT"), 1e-9)                           // 0.1% of the Rs 600 held
        assertEquals(0.012 + 0.09, legs[0].getValue("Stamp duty"), 1e-9)            // 0.003% of 400 + 0.015% of 600
        assertEquals(0.132, legs[1].getValue("Brokerage"), 1e-9)
        assertEquals(0.11, legs[1].getValue("STT"), 1e-9)                          // 0.025% of 440
        assertFalse(legs[1].containsKey("DP charges"))
        // Another scrip's sale the same day is its own: INFY sold from the demat pays DP.
        val other = PnlCharges.perFill(listOf(
            PnlCharges.Fill("BUY", 100.0, 10, "B1", "TATASTEEL", "NSE", "CNC", d),
            PnlCharges.Fill("SELL", 1_000.0, 1, "S2", "INFY", "NSE", "CNC", d)))
        assertEquals(13.0, other[1].getValue("DP charges"), 1e-9)
        assertEquals(1.0, other[0].getValue("STT"), 1e-9)
    }

    @Test
    fun commoditiesAndCurrenciesAreApproximate() {
        val seg = PnlCharges::segment
        listOf("MCX", "CDS", "BCD", "mcx").forEach { assertEquals(PnlCharges.Segment.OTHER, seg("CRUDEOIL26OCTFUT", it, "NRML"), it) }
        assertEquals(PnlCharges.Segment.OPTIONS, seg("NIFTY26OCT24500CE", "BFO", "NRML"))
        // An MCX future, Rs 6 lakh each way: Rs 20 brokerage (capped), CTT 0.01% on the sale only, stamp 0.002% on the buy.
        val fut = PnlCharges.perFill(listOf(
            PnlCharges.Fill("BUY", 6_000.0, 100, "C1", "CRUDEOIL26OCTFUT", "MCX", "NRML", d),
            PnlCharges.Fill("SELL", 6_000.0, 100, "C2", "CRUDEOIL26OCTFUT", "MCX", "NRML", d)))
        assertEquals(20.0, fut[0].getValue("Brokerage"), 1e-9)
        assertEquals(0.0, fut[0].getValue("STT"), 1e-9)
        assertEquals(12.0, fut[0].getValue("Stamp duty"), 1e-9)
        assertEquals(60.0, fut[1].getValue("STT"), 1e-9)
        assertEquals(10.38, fut[1].getValue("Exchange"), 1e-9)
        assertFalse(fut[1].containsKey("DP charges"))
        // An MCX option sold, Rs 5,000 of premium: CTT 0.05% = 2.5, brokerage 0.03% = 1.5.
        val opt = PnlCharges.perFill(listOf(PnlCharges.Fill("SELL", 50.0, 100, "C3", "CRUDEOIL26OCT6000CE", "MCX", "NRML", d))).single()
        assertEquals(2.5, opt.getValue("STT"), 1e-9)
        assertEquals(1.5, opt.getValue("Brokerage"), 1e-9)
        // A currency future sold: no STT, Rs 20 brokerage (0.03% of 83,500 is 25.05).
        val cds = PnlCharges.perFill(listOf(PnlCharges.Fill("SELL", 83.5, 1_000, "C4", "USDINR26OCTFUT", "CDS", "NRML", d))).single()
        assertEquals(0.0, cds.getValue("STT"), 1e-9)
        assertEquals(20.0, cds.getValue("Brokerage"), 1e-9)
    }
}
