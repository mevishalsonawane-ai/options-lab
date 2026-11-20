package com.optionslab.ira

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 20 Nov 2026 (audit, B5 item 4): what a paper trade closed elsewhere is booked at. */
class PaperElsewhereTest {
    private val day = LocalDateTime.of(2026, 11, 20, 0, 0)
    private fun at(h: Int, m: Int) = day.withHour(h).withMinute(m)
    private val entry = at(10, 0)
    private val now = at(15, 40)
    private val none: (String) -> Int = { 0 }

    private fun pick(sales: List<PaperElsewhere.Sale>, product: String? = "MIS", taken: (String) -> Int = none, expired: Boolean = false,
                     settled: Double? = null, last: Double? = null) =
        PaperElsewhere.pick(sales, product, entry, taken, expired, settled, at(15, 30), last, now)

    @Test fun theLastSaleOfItsOwnProductSinceItsEntryIsBooked() {
        val sales = listOf(
            PaperElsewhere.Sale("o1", "MIS", 90.0, at(9, 40), 20.0),           // before the entry
            PaperElsewhere.Sale("o2", "MIS", 120.0, at(14, 0), 21.0),
            PaperElsewhere.Sale("o3", "NRML", 130.0, at(15, 0), 22.0),         // another product (Night's NRML)
        )
        val x = pick(sales)!!
        assertEquals(PaperElsewhere.Exit(120.0, at(14, 0), 21.0, PaperElsewhere.How.SALE, "o2"), x)
        // Any product when the caller does not say.
        assertEquals("o3", pick(sales, product = null)!!.orderId)
        // The same product, case aside.
        assertEquals("o3", pick(sales, product = "nrml")!!.orderId)
    }

    @Test fun aSaleOtherTradesAlreadyBookedInFullIsNotTakenAgain() {
        val sales = listOf(PaperElsewhere.Sale("a", "MIS", 110.0, at(13, 0), 20.0, 65), PaperElsewhere.Sale("b", "MIS", 150.0, at(14, 0), 20.0, 130))
        // "b" closed two arms' lots at once (130): one arm booked 65 of it, so the other still books it.
        assertEquals("b", pick(sales, taken = { if (it == "b") 65 else 0 })!!.orderId)
        // Booked in full by others: the one before it.
        assertEquals("a", pick(sales, taken = { if (it == "b") 130 else 0 })!!.orderId)
        assertNull(pick(sales, taken = { 1_000 }))
    }

    @Test fun anExpiredContractWithNoSaleIsBookedAtTheSettlementNotItsEntry() {
        val x = pick(emptyList(), expired = true, settled = 0.0, last = 55.0)!!
        assertEquals(PaperElsewhere.Exit(0.0, at(15, 30), 0.0, PaperElsewhere.How.EXPIRY), x)
        // Not expired: the settlement is not read.
        assertEquals(PaperElsewhere.How.LAST_PRICE, pick(emptyList(), expired = false, settled = 0.0, last = 55.0)!!.how)
        // A settlement time before the entry (a bad row) is booked at the entry's time, never before it.
        assertEquals(entry, PaperElsewhere.pick(emptyList(), "MIS", entry, none, true, 3.0, at(9, 0), null, now)!!.at)
        // No settlement price, or not a real one: on to the last price.
        assertEquals(55.0, pick(emptyList(), expired = true, settled = null, last = 55.0)!!.price, 0.0)
        assertEquals(55.0, pick(emptyList(), expired = true, settled = Double.NaN, last = 55.0)!!.price, 0.0)
        assertEquals(55.0, pick(emptyList(), expired = true, settled = -1.0, last = 55.0)!!.price, 0.0)
        assertEquals(55.0, PaperElsewhere.pick(emptyList(), "MIS", entry, none, true, 3.0, null, 55.0, now)!!.price, 0.0)
    }

    @Test fun withNoSaleAndNoSettlementTheLastPriceSeenIsBookedElseNothing() {
        assertEquals(PaperElsewhere.Exit(77.0, now, 0.0, PaperElsewhere.How.LAST_PRICE), pick(emptyList(), last = 77.0))
        assertNull(pick(emptyList()), "nothing known: the caller's old answer stands")
        assertNull(pick(emptyList(), last = 0.0))
        assertNull(pick(emptyList(), last = Double.NaN))
    }
}
