package com.optionslab.app.data

import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.sandbox.Trade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 18 Oct, review: a Night (R3) position Boss closed by hand (or an exit-all) before 09:16 is booked as closed elsewhere at that
 * sale, never sold again (a SELL on a flat paper book opened a naked short nothing manages), and the 09:16 sale never sells
 * more than the paper book still holds.
 */
class NightArmClosedElsewhereTest : RobolectricTest() {
    private val entryDay = LocalDate.of(2026, 10, 14)
    private val entry = entryDay.atTime(15, 20)
    private val pos = NightArm.Pos("NIFTY", "NIFTY26O2025000CE", "CE", 65, 100.0, entryDay, entry, "P1", charges = 20.0)
    private val now = LocalDateTime.of(2026, 10, 15, 9, 16)

    private fun trade(id: String, action: String, price: String, at: LocalDateTime, symbol: String = pos.symbol, charges: String = "15") =
        Trade("T$id", "O$id", symbol, "NFO", action, 65, BigDecimal(price), "NRML", null, at, BigDecimal(charges))

    @Test fun stillHeldIsNotClosedElsewhere() {
        assertNull(NightArm.closedElsewhere(pos, 65, emptyList(), now))
        assertNull(NightArm.closedElsewhere(pos, 1, emptyList(), now))
    }

    @Test fun aHandCloseIsBookedAtThatSaleWithItsCharges() {
        val trades = listOf(
            trade("0", "BUY", "100", entry),
            // An older sale of the same symbol before this entry is not this trade's exit.
            trade("1", "SELL", "80", entry.minusDays(2)),
            trade("2", "SELL", "112.5", entry.plusMinutes(5)),
            // Another symbol's later sale is not it either.
            trade("3", "SELL", "300", entry.plusMinutes(8), symbol = "NIFTY26O2025000PE"),
        )
        val done = NightArm.closedElsewhere(pos, 0, trades, now)!!
        assertEquals("closed_elsewhere", done.why)
        assertEquals(112.5, done.exit!!, 1e-9)
        assertEquals(entry.plusMinutes(5), done.exitTime)
        assertEquals(35.0, done.charges, 1e-9)
        assertEquals((112.5 - 100.0) * 65 - 35.0, done.net!!, 1e-9)
    }

    @Test fun noSaleFoundBooksItAtItsEntryNow() {
        // Edge: the sale is not in the kept trades (or a short is held: negative): flat at its entry, never sold again.
        for (held in listOf(0, -65)) {
            val done = NightArm.closedElsewhere(pos, held, emptyList(), now)!!
            assertEquals(100.0, done.exit!!, 1e-9)
            assertEquals(now, done.exitTime)
            assertEquals(20.0, done.charges, 1e-9)
        }
    }

    @Test fun theSaleNeverSellsMoreThanIsHeld() {
        assertEquals(65, NightArm.sellQty(65, 65))
        assertEquals(65, NightArm.sellQty(65, 130))
        assertEquals(65, NightArm.sellQty(130, 65))
        assertEquals(0, NightArm.sellQty(65, -65))
    }
}
