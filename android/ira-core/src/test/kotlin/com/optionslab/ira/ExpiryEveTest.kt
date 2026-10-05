package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExpiryEveTest {
    private val today = LocalDate.of(2026, 10, 5)
    private val tue = LocalDate.of(2026, 10, 6)

    @Test fun nothingExpiringSaysNothing() {
        assertNull(ExpiryEve.line(emptyList(), today, tue, true))
        assertNull(ExpiryEve.line(listOf(ExpiryEve.Leg("Paper", "X", 75)), today, null, true))
        assertNull(ExpiryEve.line(listOf(ExpiryEve.Leg("Paper", "X", 75)), today, today, true))
    }

    @Test fun legsMoneynessAndSquareOff() {
        val legs = listOf(
            ExpiryEve.Leg("Paper", "NIFTY06OCT2625000CE", 75, "NRML", "NIFTY", 25000.0, "CE", 24880.0),
            ExpiryEve.Leg("Zerodha", "NIFTY06OCT2624900PE", -75, "NRML", "NIFTY", 24900.0, "PE", 24880.0),
        )
        val s = ExpiryEve.line(legs, today, tue, true)!!
        assertTrue(s.startsWith("Expiry eve, Boss: 2 open legs of yours expire tomorrow (Tue 6 Oct)"), s)
        assertTrue(s.contains("Paper NIFTY06OCT2625000CE, 75 long NRML, 120 points out of the money (spot 24,880)"), s)
        assertTrue(s.contains("Zerodha NIFTY06OCT2624900PE, 75 short NRML, 20 points in the money"), s)
        assertTrue(s.contains("At 15:05 tomorrow the expiry square-off will close them"), s)
        assertTrue(s.contains("Index options settle in cash."), s)
        assertFalse(s.contains("delivery"), s)
        assertTrue(s.endsWith("your call, Boss."), s)
    }

    @Test fun offStockMisAndKept() {
        val legs = listOf(
            ExpiryEve.Leg("Zerodha", "RELIANCE06OCT261400CE", 500, "MIS", "RELIANCE", 1400.0, "CE", 1420.0),
            ExpiryEve.Leg("Paper", "NIFTY06OCT2624500PE", 75, "NRML", "NIFTY", 24500.0, "PE", null, keptToSettlement = true),
        )
        val off = ExpiryEve.line(legs, today, tue, false)!!
        assertTrue(off.contains("square-off is OFF: it is left to the 15:30 settlement unless you close it yourself"), off)
        assertTrue(off.contains("One is MIS"), off)
        assertTrue(off.contains("settle by delivery of the shares"), off)
        assertTrue(off.contains("how far from the strike is not known"), off)
        assertTrue(off.contains("left to the 15:30 settlement"), off)
        val on = ExpiryEve.line(legs, today, tue, true)!!
        assertTrue(on.contains("will close the other one"), on)
    }

    @Test fun nextTradingDaySkipsTheWeekend() {
        val fri = LocalDate.of(2026, 10, 9)
        assertEquals(LocalDate.of(2026, 10, 12), ExpiryEve.nextTradingDay(fri) { it.dayOfWeek.value <= 5 })
        assertEquals(tue, ExpiryEve.nextTradingDay(today) { true })
    }

    @Test fun undatedZerodhaPositionsAreNeverABothRead() {
        // Open Zerodha positions outside the instruments on the phone (SENSEX/BFO, stock options, MCX): never "both read".
        val none = ExpiryEve.answer(null, tue, true, true, 2)
        assertFalse(none.contains("both read"), none)
        assertTrue(none.contains("2 Zerodha positions I couldn't date"), none)
        assertTrue(none.contains("Boss"), none)
        val one = ExpiryEve.answer(null, tue, true, true, 1)
        assertTrue(one.contains("1 Zerodha position I couldn't date") && one.contains("check it yourself"), one)
        assertEquals("x 3 Zerodha positions I couldn't date (not in the instruments on the phone) - check those yourself, so they aren't listed.",
            ExpiryEve.answer("x", tue, true, true, 3))
        // None undated: as before.
        assertTrue(ExpiryEve.answer(null, tue, true, true, 0).contains("both read"))
        // Not read at all: the unread words, not the undated ones.
        assertTrue(ExpiryEve.answer(null, tue, false, true, 2).contains("couldn't be read just now"))
        assertNull(ExpiryEve.undatedLine(0))
    }
}
