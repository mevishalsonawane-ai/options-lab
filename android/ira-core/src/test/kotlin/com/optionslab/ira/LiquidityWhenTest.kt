package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Liquidity 15+5 by day and time: the weekday, expiry and entry-time splits, the small-sample guard, the words and the question. */
class LiquidityWhenTest {
    private val ADVICE = Regex("(?i)\\b(you should|i recommend|i suggest|consider|switch to|increase|raise|reduce|better to|safe to|avoid)\\b")
    private val mon: LocalDate = LocalDate.of(2026, 10, 5)   // a Monday
    private val exp: LocalDate = LocalDate.of(2026, 10, 27)  // a Tuesday: BankNifty's October expiry in these trades

    /** A closed 2-lot BankNifty paper trade on [day] at [h]:[m] on the option expiring [expiry] that made [perLot] a lot. */
    private fun t(day: LocalDate, h: Int, m: Int, perLot: Double, expiry: LocalDate? = exp, symbol: String? = null,
                  source: String = "liquidity15", live: Boolean = false): BotTrades.Trade {
        val at = LocalDateTime.of(day, LocalTime.of(h, m))
        val sym = symbol ?: expiry?.let { OrbRulesSymbol.of(it) } ?: "BANKNIFTY26OCT56000CE"
        return BotTrades.Trade(source, sym, "CE", 70, 300.0, at, at.minusMinutes(5), 300.0 + perLot / 35.0, at.plusMinutes(20),
            "next_liquidity", 0.0, live, lot = 35)
    }

    private object OrbRulesSymbol {
        fun of(d: LocalDate) = "BANKNIFTY" + String.format("%02d", d.dayOfMonth) +
            listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")[d.monthValue - 1] +
            String.format("%02d", d.year % 100) + "56000CE"
    }

    private fun rows(vararg ts: BotTrades.Trade) = LiquidityRecord.rows(ts.toList())

    @Test fun theOptionsExpiryFromItsOwnSymbol() {
        val r = rows(t(mon, 10, 0, 100.0))[0]
        assertEquals(exp, LiquidityWhen.expiryOf(r))
        // Another format (a monthly Kite symbol has no day: "26OCT56" would read as 2056) or none: not readable.
        assertNull(LiquidityWhen.expiryOf(rows(t(mon, 10, 0, 100.0, symbol = "BANKNIFTY26OCT56000CE"))[0]))
        assertNull(LiquidityWhen.expiryOf(rows(t(mon, 10, 0, 100.0, symbol = "BANKNIFTY2610756000CE"))[0]))
        assertNull(LiquidityWhen.expiryOf(rows(t(mon, 10, 0, 100.0, symbol = "BANKNIFTY31FEB2656000CE"))[0]))
        assertNull(LiquidityWhen.expiryOf(rows(t(mon, 10, 0, 100.0, symbol = "BANKNIFTY01OCT2656000CE"))[0]))   // before the trade
    }

    @Test fun theWeekdayAndTimeSplits() {
        val book = rows(t(mon, 9, 35, 100.0), t(mon, 9, 59, -50.0), t(mon.plusDays(2), 10, 0, 200.0), t(mon.plusDays(7), 13, 45, -80.0),
            // Not the arm's paper: another arm, a live trade.
            t(mon, 10, 0, 999.0, source = "orb"), t(mon, 10, 0, 999.0, live = true))
        val days = LiquidityWhen.byWeekday(book)
        assertEquals(listOf("Mon", "Wed"), days.map { it.label })
        assertEquals(3, days[0].n); assertEquals(1, days[0].wins); assertEquals(-30.0, days[0].perLot, 1e-6)
        val times = LiquidityWhen.byTime(book)
        assertEquals(listOf("09:30-10:00", "10:00-10:30", "13:30-14:00"), times.map { it.label })
        assertEquals(2, times[0].n); assertEquals(0.5, times[0].winRate); assertEquals(50.0, times[0].perLot, 1e-6)
        assertEquals(9 * 60 + 30, LiquidityWhen.bucketOf(book[0]))
    }

    @Test fun theExpirySplitFromTheBooksOwnExpiries() {
        // On 27 Oct (October's expiry) it bought November's option; 27 Oct is known an expiry from the October trades.
        val nov = LocalDate.of(2026, 11, 24)
        val book = rows(t(mon, 10, 0, 100.0), t(exp, 10, 0, -40.0, expiry = nov), t(exp, 11, 0, -60.0, expiry = nov), t(nov.minusDays(1), 10, 0, 30.0, expiry = nov))
        val e = LiquidityWhen.byExpiry(book)
        assertEquals(4, e.readable)
        assertEquals(2, e.expiry.n); assertEquals(-100.0, e.expiry.perLot, 1e-6)
        assertEquals(2, e.other.n); assertEquals(2, e.other.wins)
        // No readable expiry: nothing split.
        val none = LiquidityWhen.byExpiry(rows(t(mon, 10, 0, 100.0, symbol = "BANKNIFTY26OCT56000CE")))
        assertEquals(0, none.readable)
    }

    @Test fun theSmallSampleGuardAndTheTakeaway() {
        assertTrue(LiquidityWhen.said(LiquidityWhen.Cell("Mon", 3, 2, 150.0)).endsWith("(too few to tell)"))
        assertEquals("Tue 12 trades, 50% won, +Rs 600 a lot", LiquidityWhen.said(LiquidityWhen.Cell("Tue", 12, 6, 600.0)))
        assertTrue(LiquidityWhen.takeaway(listOf(LiquidityWhen.Cell("Mon", 3, 2, 150.0), LiquidityWhen.Cell("Tue", 12, 6, 600.0))).startsWith("Too few trades"))
        val two = LiquidityWhen.takeaway(listOf(LiquidityWhen.Cell("Mon", 10, 6, 1000.0), LiquidityWhen.Cell("Tue", 12, 4, -240.0), LiquidityWhen.Cell("Wed", 2, 2, 900.0)))
        assertTrue(two.startsWith("Among the buckets with 10 trades or more, Mon made the most a trade (+Rs 100 a lot) and Tue the least (-Rs 20 a lot)"), two)
        assertTrue(!ADVICE.containsMatchIn(two), two)
        assertTrue(LiquidityWhen.takeaway(listOf(LiquidityWhen.Cell("Mon", 10, 6, 100.0), LiquidityWhen.Cell("Tue", 10, 4, 100.0))).contains("none stands apart"))
        // Every split read on its own: the same trades sit in each, so a weekday is never set against "other days" or an hour.
        val weekdays = listOf(LiquidityWhen.Cell("Mon", 10, 6, 1000.0), LiquidityWhen.Cell("Tue", 12, 4, -240.0))
        val expiry = listOf(LiquidityWhen.Cell("expiry days", 5, 1, -2000.0), LiquidityWhen.Cell("other days", 17, 9, 760.0))
        val over = LiquidityWhen.takeawayOver(listOf(weekdays, expiry))
        assertEquals(LiquidityWhen.takeaway(weekdays), over)
        assertTrue("other days" !in over, over)
        assertTrue(LiquidityWhen.takeawayOver(listOf(expiry, listOf(LiquidityWhen.Cell("09:30-10:00", 22, 10, 0.0)))).startsWith("Too few trades"))
    }

    @Test fun theAnswer() {
        val book = rows(*(0 until 12).map { i -> t(mon.plusDays((i % 2).toLong()), 10, 0, if (i % 3 == 0) -60.0 else 90.0) }.toTypedArray(),
            t(exp, 13, 40, -30.0, expiry = LocalDate.of(2026, 11, 24)))
        val all = LiquidityWhen.answer(LiquidityWhen.Focus.ALL, book)
        assertTrue(all.startsWith("Boss, Liquidity 15+5's 13 closed paper trades by day and time, net a lot after charges:"), all)
        assertTrue("By weekday: Mon 6 trades" in all && "Tue 7 trades" in all, all)
        assertTrue("Expiry days against the rest: expiry days 1 trade, 0% won, -Rs 30 a lot (too few to tell); other days 12 trades" in all, all)
        assertTrue("By entry time (30-minute buckets): 10:00-10:30 12 trades, 67% won, +Rs 480 a lot; 13:30-14:00 1 trade" in all, all)
        assertTrue(all.endsWith(LiquidityWhen.END), all)
        assertTrue(!ADVICE.containsMatchIn(all), all)
        // One split alone.
        val time = LiquidityWhen.answer(LiquidityWhen.Focus.TIME, book)
        assertTrue("By weekday" !in time && "Expiry" !in time && "By entry time" in time, time)
        // No readable expiry: said, and skipped.
        val skip = LiquidityWhen.answer(LiquidityWhen.Focus.EXPIRY, rows(t(mon, 10, 0, 100.0, symbol = "BANKNIFTY26OCT56000CE")))
        assertTrue("does not carry a readable option expiry" in skip && "skipped" in skip, skip)
        // None in the book on an expiry day.
        val noneOn = LiquidityWhen.answer(LiquidityWhen.Focus.EXPIRY, rows(t(mon, 10, 0, 100.0)))
        assertTrue("None of its trades fell on an expiry day its book shows." in noneOn, noneOn)
        // Nothing at all.
        assertTrue(LiquidityWhen.answer(LiquidityWhen.Focus.ALL, emptyList()).startsWith("Liquidity 15+5 has no closed paper trade"))
    }

    @Test fun theQuestion() {
        val want = mapOf(
            "which day does liquidity do best" to LiquidityWhen.Focus.WEEKDAY, "liquidity by weekday" to LiquidityWhen.Focus.WEEKDAY,
            "liquidity kis din achha karta hai" to LiquidityWhen.Focus.WEEKDAY, "how does liquidity do on mondays" to LiquidityWhen.Focus.WEEKDAY,
            "liquidity on expiry days" to LiquidityWhen.Focus.EXPIRY, "liquidity expiry ke din kaisa karta hai" to LiquidityWhen.Focus.EXPIRY,
            "what time of entry works best for liquidity" to LiquidityWhen.Focus.TIME, "liquidity by entry time" to LiquidityWhen.Focus.TIME,
            "liquidity kis time achha karta hai" to LiquidityWhen.Focus.TIME,
            "liquidity by day and time" to LiquidityWhen.Focus.ALL, "liquidity by weekday and entry time" to LiquidityWhen.Focus.ALL,
        )
        for ((s, f) in want) assertEquals(f, LiquidityWhen.asked(s), s)
        for (s in listOf("how is liquidity doing", "what time does liquidity stop entering", "which expiry does liquidity buy",
            "does liquidity trade on expiry days", "should liquidity skip mondays", "how did liquidity do on monday", "liquidity today",
            "which day is best for my trades", "which day does hero do best", "what time does liquidity start", "how long does liquidity hold its trades",
            "liquidity by weekday and what is my pnl", "liquidity levels", "read my notes", "which day has the biggest range"))
            assertNull(LiquidityWhen.asked(s), s)
    }
}
