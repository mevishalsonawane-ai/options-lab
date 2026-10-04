package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InsightsTest {
    private val today = LocalDate.of(2026, 10, 2)   // a Friday
    private fun t(day: Int, hour: Int, net: Double, owner: String = "ORB", sym: String = "BANKNIFTY25O07C") =
        Insights.Trip(sym, LocalDateTime.of(2026, 9, day, hour, 20), LocalDateTime.of(2026, 9, day, hour, 50), net, owner)

    @Test fun theWeekAndThePatterns() {
        val trips = (1..30).map { i -> val d = (i % 28) + 1; t(d, if (i % 3 == 0) 14 else 10, if (i % 3 == 0) -400.0 else 150.0, if (i % 2 == 0) "Manual · Ira" else "ORB") } +
            listOf(Insights.Trip("NIFTY25O07C", LocalDateTime.of(2026, 9, 29, 10, 0), LocalDateTime.of(2026, 9, 29, 10, 30), 900.0, "Liquidity 15+5"))
        val w = Insights.week("Paper", trips, today)
        assertTrue(w[0].startsWith("Paper this week:"), w.toString())
        assertTrue(w.any { it.startsWith("Best trade: NIFTY25O07C +Rs 900 (Liquidity 15+5)") }, w.toString())
        val p = Insights.patterns("Paper", trips)
        assertTrue(p.any { it.startsWith("Entries between 14:00 and 15:00 lost the most: -Rs 4,000 over 10 trades.") }, p.toString())
        assertTrue(p.any { it.startsWith("Your own trades") }, p.toString())
        assertEquals(listOf("Paper: 3 trades so far; I need at least 10 to see patterns."), Insights.patterns("Paper", trips.take(3)))
        assertEquals(listOf("Zerodha: no trades closed this week."), Insights.week("Zerodha", emptyList(), today))
    }

    @Test fun healthNeedsEnoughTrades() {
        assertNull(Insights.health("X", (1..5).map { t(it, 10, -100.0) }))
        assertTrue(Insights.health("X", (1..12).map { t(it, 10, -100.0) })!!.startsWith("X is losing lately: -Rs 1,200 over its last 12 trades"))
        assertNull(Insights.health("X", (1..12).map { t(it, 10, 100.0) }))
    }
}
