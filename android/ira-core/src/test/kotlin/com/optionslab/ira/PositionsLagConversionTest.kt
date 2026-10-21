package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 21 Oct, enhancement round 5 (idea 3): the positions-lag guard across a same-day NRML/MIS conversion
 * ([ExitDesk.positionsLagAcross]): a fill a conversion moved to the other product's row is never read as the positions trailing.
 */
class PositionsLagConversionTest {
    private fun sell(id: String, n: Int) = ExitDesk.Fill(id, "SELL", n)
    private fun buy(id: String, n: Int) = ExitDesk.Fill(id, "BUY", n)

    @Test fun aRealLagStillShowsWithNoConversion() {
        // NRML long 150 bought today, our exit sold 75 a moment ago; the positions still show day sells 0 in every row.
        val nrml = listOf(buy("e", 150), sell("x", 75))
        assertEquals(75, ExitDesk.positionsLagAcross(150, 0, nrml, setOf("x"), null, allDayExitQty = 0, allFills = nrml))
    }

    @Test fun aFillMovedToTheOtherProductByAConversionIsNotALag() {
        // Our NRML exit sold 75 (filled); Boss converted, and the positions show that sell in the MIS row: NRML day sells 0,
        // MIS day sells 75. The NRML row alone reads 75 "trailing"; the whole contract shows all of it.
        val nrml = listOf(buy("e", 150), sell("x", 75))
        assertEquals(75, ExitDesk.positionsLag(75, 0, nrml, setOf("x")))
        assertEquals(0, ExitDesk.positionsLagAcross(75, 0, nrml, setOf("x"), null, allDayExitQty = 75, allFills = nrml))
    }

    @Test fun aRealLagBesideAConversionCountsOnlyTheRealPart() {
        // NRML sells 75 + 75 (both ours, filled); the positions show 75 in the MIS row (moved), the other 75 nowhere yet.
        val nrml = listOf(buy("e", 225), sell("x", 75), sell("y", 75))
        assertEquals(150, ExitDesk.positionsLag(150, 0, nrml, setOf("y")))
        assertEquals(75, ExitDesk.positionsLagAcross(150, 0, nrml, setOf("y"), null, allDayExitQty = 75, allFills = nrml))
    }

    @Test fun theOtherProductsOwnOrdersAndRowsBalance() {
        // MIS bought and sold 50 of its own (both in its orders and its row); our NRML exit 75 trails in the NRML row.
        val nrml = listOf(sell("x", 75))
        val all = nrml + listOf(buy("m1", 50), sell("m2", 50))
        assertEquals(75, ExitDesk.positionsLagAcross(150, 0, nrml, setOf("x"), null, allDayExitQty = 50, allFills = all))
    }

    @Test fun theWholeContractUnknownFallsBackToTheProductsOwn() {
        val nrml = listOf(sell("x", 75))
        assertEquals(75, ExitDesk.positionsLagAcross(150, 0, nrml, setOf("x"), null, allDayExitQty = null, allFills = nrml))
    }

    @Test fun nothingOfOursFilledIsNeverALagAndAShortWorksTheSameWay() {
        assertEquals(0, ExitDesk.positionsLagAcross(150, 0, listOf(sell("web", 75)), setOf("x"), null, 0, listOf(sell("web", 75))))
        assertEquals(0, ExitDesk.positionsLagAcross(0, 0, listOf(sell("x", 75)), setOf("x"), null, 0, listOf(sell("x", 75))))
        // A short: the exit side is BUY. Moved to the other row: none; trailing: 50.
        val f = listOf(buy("x", 50))
        assertEquals(0, ExitDesk.positionsLagAcross(-50, 0, f, setOf("x"), null, allDayExitQty = 50, allFills = f))
        assertEquals(50, ExitDesk.positionsLagAcross(-100, 0, f, setOf("x"), null, allDayExitQty = 0, allFills = f))
    }

    @Test fun aRestingStopThatFilledAMomentAgoWakesItByItsUpdateTime() {
        val now = 1_000_000_000L
        val f = listOf(ExitDesk.Fill("sl", "SELL", 75, updatedAt = now - 2_000))
        assertEquals(75, ExitDesk.positionsLagAcross(150, 0, f, emptySet(), now, allDayExitQty = 0, allFills = f))
        assertEquals(0, ExitDesk.positionsLagAcross(75, 0, f, emptySet(), now, allDayExitQty = 75, allFills = f))
    }

    @Test fun theLineSaysConversionsOnlyWhenThereWereAny() {
        val c = ExitDesk.Counts(day = "2026-10-21", lagConversion = 2)
        assertEquals(true, c.lagLine(false).endsWith("; a fill moved to the other product by a conversion (not counted as trailing) 2"))
        assertEquals(false, ExitDesk.Counts().lagLine(true).contains("conversion"))
    }
}
