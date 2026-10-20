package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The paper ticket's risk notices (20 Oct, Boss's 19 Oct diagnostics: each said twice, and "-0.12% above"). */
class TicketRiskTest {
    private val day = LocalDate.of(2026, 10, 19)
    private fun due(spot: Double?, said: Set<String> = emptySet(), today: LocalDate = day, side: String = "SELL", right: String = "PE",
                    expiry: LocalDate = day) =
        TicketRisk.due("NIFTY", side, right, 25_200.0, 25_000.0, expiry, day, today, spot, 0.0025, said)

    @Test fun farAboveTheBreakevenSaysNothing() {
        assertTrue(due(25_400.0).isEmpty())
    }

    @Test fun nearTheBreakevenSaysAboveOnceAndInTheMoneyOnce() {
        val n = due(25_050.0)
        assertEquals(listOf("risk.near|2026-10-19", "risk.itm|2026-10-19"), n.map { it.key })
        assertEquals("Index nearing your breakeven", n[0].title)
        assertEquals("NIFTY 25,050.0 is 0.20% above the 25,000.0 breakeven of the 25200 put.", n[0].text)
        assertEquals("NIFTY 25,050.0 is below the 25200 strike. The loss is unbounded below here.", n[1].text)
        // Said already (kept across the 13:33 restart): nothing again, even lower.
        assertTrue(due(24_970.0, n.map { it.key }.toSet()).isEmpty())
    }

    @Test fun belowTheBreakevenSaysBelowNeverMinusAbove() {
        val n = due(24_970.0, setOf("risk.itm|2026-10-19"))
        assertEquals(1, n.size)
        assertEquals("Index below your breakeven", n[0].title)
        assertEquals("NIFTY 24,970.0 is 0.12% below the 25,000.0 breakeven of the 25200 put: the paper ticket is losing.", n[0].text)
        assertTrue("-" !in n[0].text.substringBefore(" below"))
    }

    @Test fun aNewTicketIsWarnedAgain() {
        val old = setOf("risk.near|2026-10-12", "risk.itm|2026-10-12")
        assertEquals(2, due(25_050.0, old).size)
    }

    @Test fun onlyAShortPutToItsExpiryWithAPrice() {
        assertTrue(due(25_050.0, side = "BUY").isEmpty())
        assertTrue(due(25_050.0, right = "CE").isEmpty())
        // An unsettled ticket after its expiry (a holiday moved it, the app was off): not watched as live.
        assertTrue(due(25_050.0, today = day.plusDays(1)).isEmpty())
        // On its expiry day: watched.
        assertEquals(2, due(25_050.0, expiry = day).size)
        // No price, a stale or broken one: nothing said.
        assertTrue(due(null).isEmpty())
        assertTrue(due(Double.NaN).isEmpty())
        assertTrue(due(0.0).isEmpty())
    }

    @Test fun theKeysRoundTripAndKeepTheNewestTickets() {
        val keys = (1..25).flatMap { listOf("risk.near|2026-09-%02d".format(it), "risk.itm|2026-09-%02d".format(it)) }.toSet()
        val back = TicketRisk.decode(TicketRisk.encode(keys))
        assertEquals(40, back.size)
        assertTrue("risk.itm|2026-09-25" in back && "risk.near|2026-09-01" !in back)
        assertEquals(emptySet(), TicketRisk.decode(null))
        assertEquals(emptySet(), TicketRisk.decode(""))
        assertEquals(setOf("risk.near|2026-10-19"), TicketRisk.decode("risk.near|2026-10-19,junk, "))
    }
}
