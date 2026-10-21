package com.optionslab.engine.orb

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 16 Oct (R72 review): sales of an option two arms hold are booked to the right trade, each sale's charges once ([SharedSales]). */
class SharedSalesTest {
    private val t0 = LocalDateTime.of(2026, 10, 16, 9, 20)
    private fun at(m: Long) = t0.plusMinutes(m)
    private val r51 = SharedSales.Open(0, 65, 100.0, at(0))
    private val r72 = SharedSales.Open(1, 65, 101.0, at(0))
    private fun sale(id: String, qty: Int, px: Double, ch: Double, m: Long, left: Int = qty) = SharedSales.Sale(id, qty, left, px, ch, at(m))

    @Test fun anOutsideSaleOfOneLotOfTheTwoClosesTheOldestTradeAtItsPriceWithAllItsCharges() {
        val c = SharedSales.closedElsewhere(listOf(r51, r72), 65, listOf(sale("A", 65, 110.0, 20.0, 5))).single()
        assertEquals(0, c.key); assertEquals(110.0, c.price, 1e-9); assertEquals(20.0, c.charges, 1e-9); assertEquals(at(5), c.at)
        assertEquals(mapOf("A" to 65), c.took)
    }

    @Test fun theOldestIsByEntryTimeThenBookOrder() {
        val later = r51.copy(at = at(1))
        assertEquals(1, SharedSales.closedElsewhere(listOf(later, r72), 65, listOf(sale("A", 65, 110.0, 20.0, 5))).single().key)
    }

    @Test fun twoOutsideSalesOfOneLotEachCloseOneTradeEachAtItsOwnSalesPriceAndCharges() {
        val c = SharedSales.closedElsewhere(listOf(r51, r72), 0, listOf(sale("B", 65, 90.0, 12.0, 9), sale("A", 65, 110.0, 20.0, 5)))
        assertEquals(listOf(0, 1), c.map { it.key })
        assertEquals(110.0, c[0].price, 1e-9); assertEquals(20.0, c[0].charges, 1e-9); assertEquals(mapOf("A" to 65), c[0].took)
        assertEquals(90.0, c[1].price, 1e-9); assertEquals(12.0, c[1].charges, 1e-9); assertEquals(at(9), c[1].at)
        assertEquals(32.0, c.sumOf { it.charges }, 1e-9, "every sale's charges booked once")
    }

    @Test fun oneSaleOfBothLotsSplitsItsChargesInHalf() {
        val c = SharedSales.closedElsewhere(listOf(r51, r72), 0, listOf(sale("A", 130, 110.0, 30.0, 5)))
        assertEquals(2, c.size)
        assertTrue(c.all { it.price == 110.0 && it.charges == 15.0 && it.took == mapOf("A" to 65) })
    }

    @Test fun anotherArmsOwnExitIsNeverBookedAsClosedElsewhere() {
        // R72's own exit (B, wholly taken by its own record) sold the last lot after an outside sale (A) of R51's lot.
        val c = SharedSales.closedElsewhere(listOf(r51), 0, listOf(sale("A", 65, 110.0, 20.0, 5), sale("B", 65, 95.0, 9.0, 8, left = 0))).single()
        assertEquals(110.0, c.price, 1e-9); assertEquals(20.0, c.charges, 1e-9); assertEquals(mapOf("A" to 65), c.took)
    }

    @Test fun whatAnEarlierCloseTookIsNotTakenAgain() {
        // A sold both lots; R51 was booked 65 of it before: R72 takes the other 65 with half the charges.
        val c = SharedSales.closedElsewhere(listOf(r72), 0, listOf(sale("A", 130, 110.0, 30.0, 5, left = 65))).single()
        assertEquals(15.0, c.charges, 1e-9); assertEquals(mapOf("A" to 65), c.took)
    }

    @Test fun nothingWhenTheAccountHoldsWhatTheTradesAddUpToOrMore() {
        assertTrue(SharedSales.closedElsewhere(listOf(r51, r72), 130, listOf(sale("A", 65, 1.0, 1.0, 5))).isEmpty())
        assertTrue(SharedSales.closedElsewhere(listOf(r51, r72), 195, emptyList()).isEmpty())
        assertTrue(SharedSales.closedElsewhere(emptyList(), 0, emptyList()).isEmpty())
    }

    @Test fun aSaleOfPartOfTheOldestTradeClosesNothing() {
        val two = r51.copy(qty = 130)
        assertTrue(SharedSales.closedElsewhere(listOf(two, r72), 130, listOf(sale("A", 65, 110.0, 20.0, 5))).isEmpty())
    }

    @Test fun noSaleToExplainItClosesAtTheEntryWithNoChargesAndNoTime() {
        // A reset, or sales from before the trades (ignored): flat at the entry.
        val c = SharedSales.closedElsewhere(listOf(r51, r72), 0, listOf(sale("old", 65, 50.0, 5.0, -10)))
        assertEquals(2, c.size)
        assertEquals(100.0, c[0].price, 1e-9); assertEquals(0.0, c[0].charges, 1e-9); assertNull(c[0].at); assertTrue(c[0].took.isEmpty())
        assertEquals(101.0, c[1].price, 1e-9)
    }

    @Test fun aPartExplainedIsPricedAtTheEntryForTheRest() {
        val c = SharedSales.closedElsewhere(listOf(r51.copy(qty = 130)), 0, listOf(sale("A", 65, 120.0, 10.0, 5)))
        assertEquals((120.0 * 65 + 100.0 * 65) / 130, c.single().price, 1e-9)
        assertEquals(10.0, c.single().charges, 1e-9)
    }

    @Test fun aNegativeHoldingCountsAsNone() {
        assertEquals(2, SharedSales.closedElsewhere(listOf(r51, r72), -65, listOf(sale("A", 130, 110.0, 30.0, 5))).size)
    }

    @Test fun aSaleLeftAboveItsQuantityOrEmptyIsSafe() {
        val c = SharedSales.closedElsewhere(listOf(r51), 0, listOf(sale("Z", 0, 1.0, 1.0, 1), sale("A", 65, 110.0, 20.0, 5, left = 200))).single()
        assertEquals(mapOf("A" to 65), c.took); assertEquals(20.0, c.charges, 1e-9)
    }

    @Test fun aZeroQuantityTradeStopsTheWalk() {
        assertTrue(SharedSales.closedElsewhere(listOf(r51.copy(qty = 0), r72), 0, emptyList()).isEmpty())
    }

    @Test fun whatWasTakenIsWrittenAndReadBack() {
        assertNull(SharedSales.write(emptyMap()))
        assertNull(SharedSales.write(mapOf("A" to 0)))
        val s = SharedSales.write(mapOf("26101600000001" to 65, "26101600000002" to 30))!!
        assertEquals("26101600000001:65,26101600000002:30", s)
        assertEquals(mapOf("26101600000001" to 65, "26101600000002" to 30), SharedSales.read(s))
        assertEquals(emptyMap<String, Int>(), SharedSales.read(null))
        assertEquals(emptyMap<String, Int>(), SharedSales.read(""))
        assertEquals(mapOf("A" to 3), SharedSales.read("A:1,bad,:4,B:x,A:2"))
    }
}
