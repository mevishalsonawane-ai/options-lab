package com.optionslab.engine

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 22 Oct audit (r51): the dated lot table ([Lots.LOT_HISTORY]) covers every index the phone trades, its rows fall on their
 * dates, and a day before coverage raises rather than guesses. The rows' dates are the nearest-expiry judgement stated in the
 * KDoc; the NIFTY and early BANKNIFTY rows are bhavcopy-verified, the rest not yet.
 */
class LotsTest {
    private val today: LocalDate = LocalDate.of(2026, 10, 22)

    @Test fun everyIndexThePhoneTradesHasTodaysLot() {
        assertEquals(65, Lots.lotSizeOn("NIFTY", today))
        assertEquals(30, Lots.lotSizeOn("BANKNIFTY", today))
        assertEquals(60, Lots.lotSizeOn("FINNIFTY", today))
        assertEquals(120, Lots.lotSizeOn("MIDCPNIFTY", today))
        assertEquals(20, Lots.lotSizeOn("SENSEX", today))
        // Case does not matter; the current lot is current until the exchange changes it (forward extrapolation).
        assertEquals(60, Lots.lotSizeOn("finnifty", LocalDate.of(2030, 1, 1)))
        for (u in listOf("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")) assertTrue(u in Lots.LOT_HISTORY, u)
    }

    @Test fun theRevisionsFallOnTheirDates() {
        // BANKNIFTY: 15, 30 from 31 Jan 2025, 35 from 27 Jun 2025, back to 30 from 31 Dec 2025.
        assertEquals(30, Lots.lotSizeOn("BANKNIFTY", LocalDate.of(2025, 1, 31)))
        assertEquals(30, Lots.lotSizeOn("BANKNIFTY", LocalDate.of(2025, 6, 26)))
        assertEquals(35, Lots.lotSizeOn("BANKNIFTY", LocalDate.of(2025, 6, 27)))
        assertEquals(35, Lots.lotSizeOn("BANKNIFTY", LocalDate.of(2025, 12, 30)))
        assertEquals(30, Lots.lotSizeOn("BANKNIFTY", LocalDate.of(2025, 12, 31)))
        // FINNIFTY: 65 then 60.
        assertEquals(65, Lots.lotSizeOn("FINNIFTY", LocalDate.of(2025, 1, 31)))
        assertEquals(65, Lots.lotSizeOn("FINNIFTY", LocalDate.of(2025, 12, 30)))
        assertEquals(60, Lots.lotSizeOn("FINNIFTY", LocalDate.of(2025, 12, 31)))
        // MIDCPNIFTY: 120, 140 from 27 Jun 2025, back to 120 from 31 Dec 2025.
        assertEquals(120, Lots.lotSizeOn("MIDCPNIFTY", LocalDate.of(2025, 1, 31)))
        assertEquals(120, Lots.lotSizeOn("MIDCPNIFTY", LocalDate.of(2025, 6, 26)))
        assertEquals(140, Lots.lotSizeOn("MIDCPNIFTY", LocalDate.of(2025, 6, 27)))
        assertEquals(140, Lots.lotSizeOn("MIDCPNIFTY", LocalDate.of(2025, 12, 30)))
        assertEquals(120, Lots.lotSizeOn("MIDCPNIFTY", LocalDate.of(2025, 12, 31)))
        // SENSEX: 20 from 31 Jan 2025, unchanged since.
        assertEquals(20, Lots.lotSizeOn("SENSEX", LocalDate.of(2025, 1, 31)))
        assertEquals(20, Lots.lotSizeOn("SENSEX", today))
        // NIFTY's verified rows are untouched.
        assertEquals(75, Lots.lotSizeOn("NIFTY", LocalDate.of(2025, 6, 27)))
        assertEquals(65, Lots.lotSizeOn("NIFTY", LocalDate.of(2025, 12, 31)))
    }

    @Test fun aDayBeforeCoverageRaisesRatherThanGuesses() {
        for (u in listOf("FINNIFTY", "MIDCPNIFTY", "SENSEX")) {
            assertEquals(LocalDate.of(2025, 1, 31), Lots.coverageStart(u), u)
            val e = assertFailsWith<Lots.NoLotSize>(u) { Lots.lotSizeOn(u, LocalDate.of(2025, 1, 30)) }
            assertTrue(e.message!!.contains("starts 2025-01-31") && e.message!!.contains("bhavcopy"), e.message)
        }
        assertEquals(LocalDate.of(2024, 1, 1), Lots.coverageStart("BANKNIFTY"))
        assertFailsWith<Lots.NoLotSize> { Lots.lotSizeOn("BANKNIFTY", LocalDate.of(2023, 12, 29)) }
        // An index the table does not know says so, and names the ones it does.
        val e = assertFailsWith<Lots.NoLotSize> { Lots.lotSizeOn("NIFTYNXT50", today) }
        assertTrue(e.message!!.contains("known: BANKNIFTY, FINNIFTY, MIDCPNIFTY, NIFTY, SENSEX"), e.message)
        assertFailsWith<Lots.NoLotSize> { Lots.coverageStart("") }
    }

    @Test fun theRowsAreInDateOrderAndEachOneMovesTheLot() {
        for ((u, rows) in Lots.LOT_HISTORY) {
            assertTrue(rows.isNotEmpty(), u)
            for (i in 1 until rows.size) {
                assertTrue(rows[i].first.isAfter(rows[i - 1].first), "$u row $i is not after row ${i - 1}")
                assertTrue(rows[i].second != rows[i - 1].second, "$u row $i repeats the lot ${rows[i].second}")
            }
            for ((_, lot) in rows) assertTrue(lot > 0, "$u has a lot of $lot")
            // The walk reads the row in force on its own date, and the row before it the day before.
            for (i in 1 until rows.size) {
                assertEquals(rows[i].second, Lots.lotSizeOn(u, rows[i].first), "$u on ${rows[i].first}")
                assertEquals(rows[i - 1].second, Lots.lotSizeOn(u, rows[i].first.minusDays(1)), "$u the day before ${rows[i].first}")
            }
        }
    }
}
