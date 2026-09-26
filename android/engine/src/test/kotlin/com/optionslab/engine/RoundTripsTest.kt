package com.optionslab.engine

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class RoundTripsTest {
    private val t0 = LocalDateTime.of(2026, 9, 24, 10, 0)
    private fun f(id: String, side: Int, qty: Int, px: Double, min: Long, ch: Double = 0.0, sym: String = "X") =
        RoundTrips.Fill(id, "o$id", sym, side, qty, px, t0.plusMinutes(min), ch)

    @Test fun simpleLongRoundTrip() {
        val t = RoundTrips.of(listOf(f("1", 1, 30, 200.0, 0, 26.3), f("2", -1, 30, 240.0, 5, 37.43))).single()
        assertEquals(1, t.direction)
        assertEquals(1200.0, t.gross, 1e-9)
        assertEquals(1200.0 - 63.73, t.net, 1e-9)
        assertEquals(listOf("1"), t.openFillIds)
        assertEquals("2", t.closeFillId)
    }

    @Test fun fifoAcrossLotsAndPartialCloses() {
        val trips = RoundTrips.of(listOf(
            f("1", 1, 30, 100.0, 0), f("2", 1, 30, 110.0, 1),   // 60 long, avg 105
            f("3", -1, 45, 120.0, 2),                           // closes 30@100 + 15@110
            f("4", -1, 15, 90.0, 3),                            // closes the last 15@110
        ))
        assertEquals(2, trips.size)
        assertEquals(45, trips[0].qty)
        assertEquals((30 * 100.0 + 15 * 110.0) / 45, trips[0].entry, 1e-9)
        assertEquals(listOf("1", "2"), trips[0].openFillIds)
        assertEquals(-300.0, trips[1].gross, 1e-9)              // (90-110) x 15
    }

    @Test fun shortAndReversal() {
        val trips = RoundTrips.of(listOf(f("1", -1, 50, 80.0, 0), f("2", 1, 75, 60.0, 1), f("3", -1, 25, 70.0, 2)))
        assertEquals(-1, trips[0].direction)
        assertEquals(1000.0, trips[0].gross, 1e-9)              // sold 80, bought 60, x50
        assertEquals(1, trips[1].direction)                     // the 25 extra became a long
        assertEquals(250.0, trips[1].gross, 1e-9)
    }

    @Test fun symbolsAreSeparateAndStats() {
        val trips = RoundTrips.of(listOf(
            f("1", 1, 10, 10.0, 0, sym = "A"), f("2", 1, 10, 20.0, 1, sym = "B"),
            f("3", -1, 10, 15.0, 2, sym = "A"), f("4", -1, 10, 12.0, 3, sym = "B"),
        ))
        val s = RoundTrips.stats(trips)
        assertEquals(2, s.trips)
        assertEquals(1, s.wins)
        assertEquals(-30.0, s.net, 1e-9)
        assertEquals(50.0 / 80.0, s.profitFactor!!, 1e-9)
        assertEquals(-80.0, s.maxDrawdown, 1e-9)
    }
}
