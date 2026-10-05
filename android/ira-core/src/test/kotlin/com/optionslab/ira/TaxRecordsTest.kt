package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaxRecordsTest {
    private val today = LocalDate.of(2026, 10, 5)

    /** A trade closed on [date], bought [qty] at [entry] and sold at [exit] (sold first when [direction] is -1). */
    private fun t(date: LocalDate, symbol: String, entry: Double, exit: Double, qty: Int = 75, charges: Double = 40.0, direction: Int = 1) =
        LocalDateTime.of(date, LocalTime.of(10, 0)).let { TaxRecords.trade(symbol, direction, qty, entry, exit, it, it.plusMinutes(20), charges) }

    @Test fun asked() {
        for (q in listOf("what's my F&O turnover this year?", "Jarvis, what is my turnover for this financial year", "my fno turnover",
                "how much turnover did I do", "my tax summary", "F&O turnover for tax audit", "what's my realised P&L for this financial year",
                "my p&l for last financial year", "mera turnover kitna hai", "turnover for FY 2025-26"))
            assertTrue(TaxRecords.asked(q), q)
        for (q in listOf("how much tax on my trades", "what is my gst", "how much did I pay in charges this month", "how was my month",
                "what is nifty's turnover today", "export my trades for tax", "what is brokerage"))
            assertFalse(TaxRecords.asked(q), q)
        for (q in listOf("export my trades for tax", "Jarvis, export my trades", "download my trades as csv for my CA", "send my trades to my CA",
                "share my trade list for ITR", "tax ke liye trades export karo", "export my trades for FY 2025-26"))
            assertTrue(TaxRecords.exportAsked(q), q)
        for (q in listOf("what's my F&O turnover this year?", "export the backtest", "send an order", "share the chart", "how was my week"))
            assertFalse(TaxRecords.exportAsked(q), q)
    }

    @Test fun routedAsAnAccountQuestionThatNeverActs() {
        assertEquals(setOf(Topic.ACCOUNT), Ask.parse("what's my F&O turnover this year?").topics)
        assertEquals(setOf(Section.TAX), AppAnswers.sections("what's my F&O turnover this year?"))
        assertEquals(setOf(Section.TAX), AppAnswers.sections("my tax summary"))
        // "How much tax on my trades" stays the charges' question.
        assertEquals(setOf(Section.CHARGES), AppAnswers.sections("how much tax on my trades"))
        for (q in listOf("export my trades for tax", "what's my F&O turnover this year?", "tax ke liye trades export karo", "send my trades to my CA")) {
            val p = Ask.parse(q)
            assertNull(p.order, q); assertNull(p.command, q)
            assertFalse(Bundle.acts(q), q)
        }
        assertEquals("Unlock the phone to hear your account.", LockRule.refuse(locked = true, acts = false, account = true, boss = false))
    }

    @Test fun financialYears() {
        assertEquals(TaxRecords.Fy(2026), TaxRecords.fyOf(today))
        assertEquals(TaxRecords.Fy(2025), TaxRecords.fyOf(LocalDate.of(2026, 3, 31)))
        assertEquals(TaxRecords.Fy(2026), TaxRecords.fyOf(LocalDate.of(2026, 4, 1)))
        assertEquals("FY 2026-27", TaxRecords.Fy(2026).label)
        assertEquals("FY 2099-00", TaxRecords.Fy(2099).label)
        assertEquals(TaxRecords.Fy(2026), TaxRecords.fy("what's my turnover this year", today))
        assertEquals(TaxRecords.Fy(2025), TaxRecords.fy("my turnover last financial year", today))
        assertEquals(TaxRecords.Fy(2025), TaxRecords.fy("turnover for FY 2025-26", today))
        assertEquals(TaxRecords.Fy(2024), TaxRecords.fy("turnover for 2024-25", today))
        assertEquals(TaxRecords.Fy(2024), TaxRecords.fy("turnover for fy24-25", today))
        // A year not yet begun is not taken.
        assertEquals(TaxRecords.Fy(2026), TaxRecords.fy("turnover for FY 2030-31", today))
    }

    @Test fun segmentsAndTurnover() {
        assertEquals(TaxRecords.Segment.OPTION, TaxRecords.segment("NIFTY26OCT24500CE"))
        assertEquals(TaxRecords.Segment.OPTION, TaxRecords.segment("BANKNIFTY2610751000PE"))
        assertEquals(TaxRecords.Segment.FUTURE, TaxRecords.segment("NIFTY26OCTFUT"))
        assertEquals(TaxRecords.Segment.OTHER, TaxRecords.segment("RELIANCE"))
        val w = listOf(
            t(today, "NIFTY26OCT24500CE", 100.0, 120.0),                          // +1500
            t(today, "NIFTY26OCT24500PE", 100.0, 80.0),                           // -1500
            t(today, "NIFTY26OCTFUT", 24500.0, 24480.0, qty = 75, direction = -1), // short: +1500
            t(today, "RELIANCE", 2900.0, 2800.0, qty = 10),                        // -1000, not F&O
        )
        assertEquals(4500.0, TaxRecords.turnover(w), 1e-6)
        // A short: bought back lower; the buy value is the exit's.
        val s = w[2]
        assertEquals(24480.0 * 75, s.buyValue, 1e-6); assertEquals(24500.0 * 75, s.sellValue, 1e-6); assertEquals(1500.0, s.gross, 1e-6)
    }

    @Test fun yearLines() {
        val z = listOf(
            t(LocalDate.of(2026, 3, 30), "NIFTY26MAR22000CE", 100.0, 200.0),      // last FY: left out
            t(LocalDate.of(2026, 4, 10), "NIFTY26APR22500CE", 100.0, 120.0),      // +1500 - 40
            t(LocalDate.of(2026, 4, 20), "NIFTY26APR22500PE", 100.0, 90.0),       // -750 - 40
            t(LocalDate.of(2026, 9, 1), "NIFTY26SEP25000CE", 50.0, 70.0),         // +1500 - 40
        )
        val p = listOf(t(LocalDate.of(2026, 5, 2), "NIFTY26MAY23000CE", 100.0, 110.0))
        val lines = TaxRecords.lines(z, p, TaxRecords.Fy(2026), today)
        val all = lines.joinToString("\n")
        assertTrue(lines[0].startsWith("Zerodha, FY 2026-27 (1 Apr 2026 to 5 Oct 2026, so far): 3 closed trades (3 option trades)"), lines[0])
        assertTrue(all.contains("F&O turnover, estimated: Rs 3,750"), all)
        assertTrue(all.contains("confirm it with your CA"), all)
        assertTrue(all.contains("+Rs 2,250 before charges, Rs 120 in charges, +Rs 2,130 after"), all)
        assertTrue(all.contains("by month (after charges): Apr 2026 +Rs 670, Sep 2026 +Rs 1,460."), all)
        assertTrue(all.contains("Paper (practice, not real money): 1 trade"), all)
        assertTrue(all.contains(TaxRecords.CONFIRM), all)
        assertTrue(all.contains("export my trades for tax"), all)
        assertFalse(all.contains("advice:") && !all.contains("not tax advice"), all)
        // No Zerodha trades: the paper account's, said to be practice.
        val paperOnly = TaxRecords.lines(null, p, TaxRecords.Fy(2026), today).joinToString("\n")
        assertTrue(paperOnly.contains("No Zerodha trades recorded") && paperOnly.contains("Paper, FY 2026-27"), paperOnly)
        // Nothing at all.
        assertEquals("No closed trades recorded in FY 2025-26 (1 Apr 2025 to 31 Mar 2026), Boss.",
            TaxRecords.lines(emptyList(), emptyList(), TaxRecords.Fy(2025), today)[0])
        assertEquals(listOf(YearMonth.of(2026, 4) to 670.0, YearMonth.of(2026, 9) to 1460.0),
            TaxRecords.byMonth(TaxRecords.inYear(z, TaxRecords.Fy(2026), today)))
    }

    @Test fun csvHoldsOnlyTheTrades() {
        val z = listOf(
            t(LocalDate.of(2026, 9, 1), "NIFTY26SEP25000CE", 50.0, 70.0),
            t(LocalDate.of(2026, 4, 10), "NIFTY26APR22500PE", 100.0, 90.0),
            t(LocalDate.of(2026, 3, 30), "NIFTY26MAR22000CE", 100.0, 200.0),
            t(LocalDate.of(2026, 5, 1), "=HYPERLINK(1)", 10.0, 11.0, qty = 1),
        )
        val csv = TaxRecords.csv("Zerodha", z, TaxRecords.Fy(2026), today).trimEnd().split("\n")
        assertEquals(TaxRecords.HEADER, csv[0])
        assertEquals(4, csv.size)
        assertEquals("2026-04-10,2026-04-10,Zerodha,NIFTY26APR22500PE,option,75,7500.00,6750.00,-750.00,40.00,-790.00,750.00", csv[1])
        // A cell is never read as a formula, and a comma never splits it.
        assertEquals("2026-05-01,2026-05-01,Zerodha,'=HYPERLINK(1),other,1,10.00,11.00,1.00,40.00,-39.00,", csv[2])
        assertTrue(csv[3].startsWith("2026-09-01,"))
        assertEquals("trades-FY2026-27-zerodha.csv", TaxRecords.fileName(TaxRecords.Fy(2026), "Zerodha"))
        val asked = TaxRecords.offer("Zerodha", 3, TaxRecords.Fy(2026))!!
        assertTrue(asked.startsWith("Boss, shall I") && asked.contains("no order IDs, account IDs or keys") && !asked.contains("Rs"), asked)
        assertNull(TaxRecords.offer("Zerodha", 0, TaxRecords.Fy(2026)))
        assertTrue(TaxRecords.done("Zerodha", 3, TaxRecords.Fy(2026)).contains("confirm with your CA"))
    }
}
