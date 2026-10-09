package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OutlookCheckTest {
    private val today = LocalDate.of(2026, 10, 5)

    /** A day's candles from 09:15 to 15:29 opening at [open], closing at [close], with a [high] and [low] touched mid-day. */
    private fun session(d: LocalDate, open: Double, close: Double, high: Double = maxOf(open, close), low: Double = minOf(open, close)): List<Candle> =
        (0 until 375).map { i ->
            val t = LocalDateTime.of(d, LocalTime.of(9, 15)).plusMinutes(i.toLong())
            val c = if (i == 374) close else if (i == 0) open else (open + close) / 2
            Candle(t, c, if (i == 180) high else c, if (i == 181) low else c, c)
        }

    /** Six rising sessions before [today] (24,000 to 24,500), then today's session. */
    private fun bars(open: Double, close: Double, high: Double = maxOf(open, close), low: Double = minOf(open, close)): List<Candle> {
        val out = ArrayList<Candle>()
        val days = listOf(LocalDate.of(2026, 9, 25), LocalDate.of(2026, 9, 28), LocalDate.of(2026, 9, 29), LocalDate.of(2026, 9, 30),
            LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2))
        days.forEachIndexed { i, d -> val c = 24_000.0 + i * 100; out += session(d, c - 50, c) }
        out += session(today, open, close, high, low)
        return out
    }

    @Test fun theMorningCallIsTheBriefsNumbersFromEarlierSessionsOnly() {
        val b = bars(24_520.0, 24_600.0)
        val e = assertNotNull(OutlookCheck.call(Market.NIFTY, b, 14.0, today))
        assertEquals(24_500.0, e.base)
        assertEquals(OutlookCheck.Lean.UP, e.lean)
        val p = ExpectedRange.points(24_500.0, 14.0)
        assertEquals(24_500.0 - p, e.lo!!, 1e-9); assertEquals(24_500.0 + p, e.hi!!, 1e-9)
        // The same as the morning line itself (made before today's session).
        val brief = Outlook.brief(Market.NIFTY, b.filter { it.t.toLocalDate() != today }, 14.0)!!
        assertTrue("rising" in brief && "pivot %,.0f".format(java.util.Locale.ENGLISH, e.pivot) in brief, brief)
        // No VIX: no range; an index the morning check doesn't give: nothing; too few sessions: nothing.
        assertNull(OutlookCheck.call(Market.NIFTY, b, 0.0, today)!!.lo)
        assertNull(OutlookCheck.call(Market.FINNIFTY, b, 14.0, today))
        assertNull(OutlookCheck.call(Market.NIFTY, session(today, 1.0, 2.0), 14.0, today))
    }

    @Test fun theCloseIsCheckedHonestly() {
        val b = bars(24_520.0, 24_600.0, high = 24_900.0)
        val e = OutlookCheck.call(Market.NIFTY, b, 14.0, today)!!
        val (log, said) = OutlookCheck.check(OutlookCheck.made(emptyList(), listOf(e)), mapOf(Market.NIFTY to b), today)
        val c = log.single()
        assertTrue(c.checked)
        assertEquals(true, c.rangeHit); assertEquals(true, c.leanHit)
        assertNotNull(said)
        assertTrue(said.startsWith("Checking my 09:00 outlook against the close, Boss: Nifty closed at 24,600 (+0.41%): inside the usual-day range"), said)
        assertTrue("though the day's high of 24,900 went past it" in said, said)
        assertTrue("the \"rising\" read held" in said, said)
        assertTrue("My record so far: Nifty close inside the range 1 of 1, direction read held 1 of 1, pivot side held 1 of 1." in said, said)
        // Checked once: a second check says nothing.
        assertNull(OutlookCheck.check(log, mapOf(Market.NIFTY to b), today).second)
        // A miss is owned as plainly.
        val down = bars(24_520.0, 23_900.0)
        val miss = OutlookCheck.check(listOf(OutlookCheck.call(Market.NIFTY, down, 14.0, today)!!), mapOf(Market.NIFTY to down), today)
        val m = miss.first.single()
        assertEquals(false, m.rangeHit); assertEquals(false, m.leanHit); assertEquals(false, m.pivotHit)
        assertTrue("outside the usual-day range" in miss.second!! && "did not hold" in miss.second!! && "closed below it" in miss.second!!, miss.second)
    }

    @Test fun aShortDayIsNotGradedAndNoDirectionIsNoCall() {
        val b = bars(24_520.0, 24_600.0).filter { it.t.toLocalTime().isBefore(LocalTime.of(14, 0)) }
        val e = OutlookCheck.call(Market.NIFTY, b, 14.0, today)!!
        assertNull(OutlookCheck.check(listOf(e), mapOf(Market.NIFTY to b), today).second)
        val flat = OutlookCheck.Entry(today, Market.BANKNIFTY, 50_000.0, null, null, OutlookCheck.Lean.NONE, 50_000.0, 50_100.0, 50_300.0, 49_900.0, 50_200.0)
        assertNull(flat.leanHit); assertNull(flat.rangeHit); assertEquals(true, flat.pivotHit)
        assertEquals("BankNifty pivot side held 1 of 1", OutlookCheck.tally(listOf(flat)))
        assertTrue("no direction was called" in OutlookCheck.line(flat))
    }

    @Test fun theMorningNoteNeverReplacesACheckedDay() {
        val e = OutlookCheck.Entry(today, Market.NIFTY, 24_500.0, 24_300.0, 24_700.0, OutlookCheck.Lean.UP, 24_450.0)
        val checked = e.copy(open = 24_520.0, high = 24_650.0, low = 24_480.0, close = 24_600.0)
        assertEquals(listOf(checked), OutlookCheck.made(listOf(checked), listOf(e)))
        val moved = e.copy(base = 24_510.0)
        assertEquals(listOf(moved), OutlookCheck.made(listOf(e), listOf(moved)))
        val many = (0 until 300).map { e.copy(day = today.minusDays(it.toLong())) }
        assertEquals(OutlookCheck.KEEP, OutlookCheck.made(emptyList(), many).size)
        // The log round-trips.
        val log = listOf(checked, e.copy(day = today.plusDays(1), lo = null, hi = null, lean = OutlookCheck.Lean.NONE))
        assertEquals(log, OutlookCheck.decode(OutlookCheck.encode(log)))
        assertEquals(emptyList(), OutlookCheck.decode("junk\n;;;"))
    }

    @Test fun theRecordIsCountsOnly() {
        assertTrue(OutlookCheck.say(emptyList(), today).startsWith("I have not checked a morning outlook against a close yet, Boss"))
        val days = (1..4).map { today.minusDays(it.toLong()) }
        val log = days.mapIndexed { i, d ->
            OutlookCheck.Entry(d, Market.NIFTY, 24_500.0, 24_300.0, 24_700.0, OutlookCheck.Lean.UP, 24_450.0, 24_500.0, 24_800.0, 24_400.0,
                if (i % 2 == 0) 24_600.0 else 24_400.0)
        } + OutlookCheck.Entry(today, Market.NIFTY, 24_500.0, 24_300.0, 24_700.0, OutlookCheck.Lean.UP, 24_450.0)
        val s = OutlookCheck.say(log, today)
        assertTrue(s.startsWith("Today's outlook is noted, Boss, and is checked at the 15:45 wrap-up."), s)
        assertTrue("over the last 4 checked sessions (since ${today.minusDays(4)}): Nifty close inside the range 4 of 4, direction read held 2 of 4, pivot side held 2 of 4." in s, s)
        assertTrue(s.endsWith("Counts only."))
        for (w in listOf("should", "you could", "consider", "buy", "sell")) assertFalse(w in s.lowercase(), w)
    }

    @Test fun whatBossAsks() {
        for (s in listOf("how good are your morning outlooks", "how accurate are your outlooks", "did your outlook hold today",
            "are your morning outlooks any good", "what's your outlook record", "how often are your outlooks right",
            "how did your outlook do today", "tumhara outlook kitna sahi hota hai", "subah ka outlook sahi tha kya", "Jarvis, how good are your morning calls?"))
            assertTrue(OutlookCheck.asked(s), s)
        for (s in listOf("nifty outlook for tomorrow", "what's your outlook on banknifty", "monday prediction for banknifty", "how good is my trading",
            "how good are your confidence scores", "what's the plan for tomorrow", "give me the outlook"))
            assertFalse(OutlookCheck.asked(s), s)
    }
}
