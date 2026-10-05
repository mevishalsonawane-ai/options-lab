package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PositionHealthTest {
    private val today = LocalDate.of(2026, 10, 6)
    private val at = LocalDateTime.of(2026, 10, 6, 13, 15)
    private val ce = PositionHealth.Pos("Paper", "NIFTY26O0624900CE", 75, 100.0, 120.0, "NIFTY", 24900.0, "CE", today,
        spot = 24950.0, avgRange = 200.0, rangeDays = 10, theta = -12.5, bid = 119.5, ask = 120.5, firstSpread = 0.5, firstSpreadWhen = "10:12 today",
        stop = 90.0, target = 180.0)
    private val pe = PositionHealth.Pos("Zerodha", "BANKNIFTY26OCT56000PE", -30, 300.0, 250.0, "BANKNIFTY", 56000.0, "PE", LocalDate.of(2026, 10, 28),
        spot = 56400.0, theta = -20.0)
    private val stock = PositionHealth.Pos("Zerodha", "RELIANCE26OCT2900CE", 500, 40.0, 60.0, "RELIANCE", 2900.0, "CE", today, spot = 2950.0)

    @Test fun asked() {
        for (q in listOf("check my positions", "Jarvis, position health", "kya meri positions theek hain", "are my positions okay?",
                "health check on my positions", "meri positions ka haal batao", "how healthy are my positions"))
            assertTrue(PositionHealth.asked(q), q)
        for (q in listOf("show my positions", "what is my p&l on my positions", "close my positions", "which of my positions is losing most",
                "what happens to my positions if nifty falls 100 points", "how is nifty"))
            assertFalse(PositionHealth.asked(q), q)
    }

    @Test fun routedToTheAccountAlone() {
        for (q in listOf("check my positions", "position health", "kya meri positions theek hain", "are my positions ok")) {
            val p = Ask.parse(q)
            assertEquals(setOf(Topic.ACCOUNT), p.topics, q)
            assertNull(p.order, q); assertNull(p.command, q); assertNull(Intents.quick(q), q)
            assertEquals(setOf(Section.HEALTH), AppAnswers.sections(q), q)
        }
        // Unchanged: the plain list and the ranking.
        assertEquals(setOf(Section.POSITIONS), AppAnswers.sections("show my positions"))
        assertEquals(setOf(Section.RANK), AppAnswers.sections("Which of my positions is losing most?"))
    }

    @Test fun eachPositionsFacts() {
        val l = PositionHealth.lines(listOf(pe, ce), at)
        assertEquals("Health check on your 2 open positions, Boss, at 13:15: 1 expires today.", l[0])
        // Expiring today first.
        assertEquals("Paper NIFTY26O0624900CE, 75 long at 100.00, now 120.00; expires today at 15:30, 2h 15m from now; " +
            "time decay about -Rs 937.50 a day on the position, about -Rs 150.00 a trading hour (2h 15m of trading left today); " +
            "spot 24,950.00 is 50 points above the 24,900 strike - 0.25 of an average day's range (200 points over the last 10 sessions): in the money; " +
            "bid 119.50, ask 120.50: spread 1.00 (0.8% of the price), against 0.50 when first noted (10:12 today): wider; " +
            "stop 90.00, 30.00 under the price (25%), target 180.00, 60.00 above the price (50%).", l[1])
        assertEquals("Zerodha BANKNIFTY26OCT56000PE, 30 short at 300.00, now 250.00; expires Wed 28 Oct, in 22 days; " +
            "time decay about +Rs 600.00 a day on the position, about +Rs 96.00 a trading hour (2h 15m of trading left today); " +
            "spot 56,400.00 is 400 points above the 56,000 strike: out of the money; no bid-ask quote just now; no stop or target set in the app.", l[2])
        assertEquals("Expiring today in the money: Paper NIFTY26O0624900CE (50 points in).", l[3])
        assertTrue(l[4].startsWith("Index options are cash-settled"))
        assertEquals("Facts from the app and the quotes now, not advice - your call, Boss.", l.last())
        assertFalse(l.any { Regex("(?i)\\b(should|recommend|suggest)\\b").containsMatchIn(it) })
        assertEquals(listOf("You have no open positions, Boss: nothing to check."), PositionHealth.lines(emptyList(), at))
    }

    @Test fun stockOptionsSettleByDelivery() {
        val l = PositionHealth.lines(listOf(stock), at)
        assertTrue(l.any { it.startsWith("Stock options are physically settled") }, l.toString())
        assertTrue(PositionHealth.settlement("NIFTY").startsWith("Index options are cash-settled"))
        assertTrue(PositionHealth.settlement(null).contains("can't say"))
    }

    @Test fun timeToExpiry() {
        assertEquals("expires tomorrow (Wed 7 Oct)", PositionHealth.toExpiry(today.plusDays(1), at))
        assertEquals("expired today", PositionHealth.toExpiry(today, at.withHour(15).withMinute(40)))
        assertEquals(0L, PositionHealth.minutesLeft(at.withHour(16)))
    }

    @Test fun spokenHasNoAmountsOrSymbols() {
        val s = PositionHealth.spoken(listOf(ce, pe, stock), today)!!
        assertEquals("Boss, a 14:45 check: two of your positions expire today, all of them in the money. Index options are cash-settled. " +
            "One is a stock option: those settle by delivery of the shares. One of them has no stop or target set. The details are in the chat - your call, Boss.", s)
        assertFalse(Overheard.holdsAccount(s))
        assertNull(PositionHealth.spoken(listOf(pe), today))
        val otm = PositionHealth.spoken(listOf(ce.copy(spot = 24800.0, stop = null, target = null)), today)!!
        assertTrue(otm.contains("one of your positions expires today, none of them in the money just now. It has no stop or target set."), otm)
        assertFalse(Overheard.holdsAccount(PositionHealth.lines(listOf(ce), at).first()))
        assertTrue(PositionHealth.due(14 * 60 + 45)); assertFalse(PositionHealth.due(14 * 60 + 55))
    }

    @Test fun averageRange() {
        val bars = (1..4).flatMap { d -> (0 until 375).map { i ->
            val p = 100.0 + i * d / 375.0
            Candle(LocalDateTime.of(2026, 10, d, 9, 15).plusMinutes(i.toLong()), p, p + 0.5, p - 0.5, p) } }
        val (r, n) = PositionHealth.avgRange(bars, LocalDate.of(2026, 10, 4))!!
        assertEquals(3, n)
        assertEquals(listOf(1, 2, 3).map { d -> 374.0 * d / 375 + 1 }.average(), r, 1e-9)   // day d: high to low, 374d/375 + 1
        assertNull(PositionHealth.avgRange(bars, LocalDate.of(2026, 10, 2)))
    }
}
