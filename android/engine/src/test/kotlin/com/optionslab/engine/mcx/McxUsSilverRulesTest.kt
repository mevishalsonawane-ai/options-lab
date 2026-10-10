package com.optionslab.engine.mcx

import com.optionslab.engine.IST
import com.optionslab.engine.Upstox
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** research/HUNT_R2_US_MCX.md arm 1 (hourly.py us_on + inr_on, cand.py C2 SILVER): s, its prices, DST closes, roll skip, exits. */
class McxUsSilverRulesTest {
    private fun d(s: String) = LocalDate.parse(s)
    private val cal = McxSession.DEFAULT
    private fun m(h: Int, mm: Int) = h * 60 + mm
    private fun at(t: LocalDateTime) = t.atZone(IST).toEpochSecond()
    private fun bar(t: LocalDateTime, open: Double, close: Double = open) = Upstox.Bar(at(t), open, maxOf(open, close), minOf(open, close), close, 0, 0)

    @Test fun theRecordAndTheRulesSayItPlainly() {
        assertEquals("US-night silver (not proven, futures)", McxUsSilverRules.LABEL)
        for (w in listOf("holdout 166 trades", "+₹1,189/trade (+₹1,115/day)", "won 56%", "₹29.6k", "10 of 13 months green",
            "tripling year", "older check lost", "futures (sells too)", "margin ~₹25–35k"))
            assertTrue(w in McxUsSilverRules.RECORD, w)
        for (w in listOf("09:05", "08:30", "SILVERMIC", "sells 1 lot", "10 minutes before", "disaster stop 3%", "not part of the research"))
            assertTrue(w in McxUsSilverRules.RULES, w)
        assertEquals("SI=F", McxUsSilverRules.US_SYMBOL); assertEquals("INR=X", McxUsSilverRules.FX_SYMBOL)
        assertEquals("not part of the research", McxUsSilverRules.STOP_NOTE)
    }

    @Test fun thePreviousCloseFollowsUsSummerAndWinterTime() {
        // Fri 30 Oct 2026: US summer time still (it ends Sun 1 Nov): MCX closed 23:30. Mon 2 Nov reads it.
        assertEquals(d("2026-10-30").atTime(23, 30), McxUsSilverRules.prevClose(d("2026-11-02"), cal))
        // Mon 2 Nov: US winter time: 23:55. Tue 3 Nov reads it.
        assertEquals(d("2026-11-02").atTime(23, 55), McxUsSilverRules.prevClose(d("2026-11-03"), cal))
        // Over a holiday shut all day (Fri 2 Oct, Gandhi Jayanti): Mon 5 Oct reads Thu 1 Oct's 23:30 close.
        assertEquals(d("2026-10-01").atTime(23, 30), McxUsSilverRules.prevClose(d("2026-10-05"), cal))
        // A day with only an evening session (Dussehra, Tue 20 Oct) still closes at 23:30: Wed 21 Oct reads it.
        assertEquals(d("2026-10-20").atTime(23, 30), McxUsSilverRules.prevClose(d("2026-10-21"), cal))
        // No trading day found in the calendar's look-back: none.
        val shut = McxSession.Calendar(added = (1..45).map { d("2026-12-31").minusDays(it.toLong()) }.toSet())
        assertNull(McxUsSilverRules.prevClose(d("2026-12-31"), shut))
        assertEquals(d("2026-11-03").atTime(8, 30), McxUsSilverRules.refAt(d("2026-11-03")))
    }

    @Test fun aPriceIsTheBarStartingThenElseTheLastCloseWithinTwoHours() {
        val t = d("2026-10-08").atTime(8, 30)
        val bars = listOf(bar(t.minusMinutes(10), 30.0, 30.5), bar(t.minusMinutes(5), 30.6, 30.8), bar(t, 31.0, 31.2))
        assertEquals(31.0, McxUsSilverRules.priceAt(bars, at(t)), "the open of the bar starting then")
        assertEquals(30.8, McxUsSilverRules.priceAt(bars.dropLast(1), at(t)), "else the close of the one before")
        assertEquals(30.8, McxUsSilverRules.priceAt(listOf(bar(t.minusHours(2), 30.0, 30.8)), at(t)), "two hours old: still used")
        assertNull(McxUsSilverRules.priceAt(listOf(bar(t.minusHours(2).minusMinutes(5), 30.0, 30.8)), at(t)), "older: none")
        assertNull(McxUsSilverRules.priceAt(emptyList(), at(t)))
        assertNull(McxUsSilverRules.priceAt(listOf(bar(t.plusMinutes(5), 31.0)), at(t)), "only later bars: none (no look-ahead)")
        // A zero open at the minute falls back to the bar before; a zero close is no price.
        assertEquals(30.8, McxUsSilverRules.priceAt(listOf(bar(t.minusMinutes(5), 30.6, 30.8), Upstox.Bar(at(t), 0.0, 1.0, 0.0, 1.0, 0, 0)), at(t)))
        assertNull(McxUsSilverRules.priceAt(listOf(Upstox.Bar(at(t.minusMinutes(5)), 1.0, 1.0, 0.0, 0.0, 0, 0)), at(t)))
    }

    @Test fun sIsTheSilverMoveTimesTheRupeeMoveAndAMissingPriceSkips() {
        val s = McxUsSilverRules.signal(31.0, 30.0, 88.2, 88.0)!!
        assertEquals(ln(31.0 / 30.0) + ln(88.2 / 88.0), s, 1e-12)
        assertEquals(1, McxUsSilverRules.side(s))
        // Silver down 1% while the rupee weakens 0.2%: down overall, a sell.
        assertEquals(-1, McxUsSilverRules.side(McxUsSilverRules.signal(29.7, 30.0, 88.176, 88.0)!!))
        // Silver flat, the rupee alone decides.
        assertEquals(1, McxUsSilverRules.side(McxUsSilverRules.signal(30.0, 30.0, 88.1, 88.0)!!))
        assertEquals(0, McxUsSilverRules.side(McxUsSilverRules.signal(30.0, 30.0, 88.0, 88.0)!!))
        assertNull(McxUsSilverRules.signal(null, 30.0, 88.0, 88.0))
        assertNull(McxUsSilverRules.signal(31.0, null, 88.0, 88.0))
        assertNull(McxUsSilverRules.signal(31.0, 30.0, null, 88.0))
        assertNull(McxUsSilverRules.signal(31.0, 30.0, 88.0, null))
        assertNull(McxUsSilverRules.signal(31.0, 0.0, 88.0, 88.0))
        assertNull(McxUsSilverRules.signal(31.0, 30.0, Double.NaN, 88.0))
    }

    @Test fun theWholeSignalFromChartHistoryAcrossTheWinterChange() {
        // Tue 3 Nov 2026 (US winter time): the previous close is Mon 2 Nov 23:55 IST (18:25 UTC); 08:30 IST is 03:00 UTC.
        val today = d("2026-11-03")
        val prev = McxUsSilverRules.prevClose(today, cal)!!
        val si = listOf(bar(prev.minusMinutes(5), 32.0), bar(prev, 32.4), bar(McxUsSilverRules.refAt(today), 32.0))
        val fx = listOf(bar(prev, 88.0), bar(McxUsSilverRules.refAt(today).minusMinutes(5), 88.1, 88.1))
        val s = McxUsSilverRules.signal(
            McxUsSilverRules.priceAt(si, McxUsSilverRules.epoch(McxUsSilverRules.refAt(today))), McxUsSilverRules.priceAt(si, McxUsSilverRules.epoch(prev)),
            McxUsSilverRules.priceAt(fx, McxUsSilverRules.epoch(McxUsSilverRules.refAt(today))), McxUsSilverRules.priceAt(fx, McxUsSilverRules.epoch(prev)))!!
        assertEquals(ln(32.0 / 32.4) + ln(88.1 / 88.0), s, 1e-12)
        assertEquals(-1, McxUsSilverRules.side(s), "silver down more than the rupee's fall: a sell")
        // Read at 23:30 instead (the summer close), the same bars would have said 32.0: the DST close matters.
        assertEquals(32.0, McxUsSilverRules.priceAt(si, McxUsSilverRules.epoch(d("2026-11-02").atTime(23, 50))))
    }

    @Test fun theEntryWindowRollAndExitMinute() {
        assertFalse(McxUsSilverRules.entryWindow(m(9, 4)))
        assertTrue(McxUsSilverRules.entryWindow(m(9, 5)))
        assertTrue(McxUsSilverRules.entryWindow(m(9, 9)))
        assertFalse(McxUsSilverRules.entryWindow(m(9, 10)))
        assertFalse(McxUsSilverRules.rolled(null, d("2026-11-27")), "not known: not rolled")
        assertFalse(McxUsSilverRules.rolled(d("2026-11-27"), d("2026-11-27")))
        assertTrue(McxUsSilverRules.rolled(d("2026-08-31"), d("2026-11-27")), "the near contract changed overnight")
        assertEquals(m(23, 20), McxUsSilverRules.exitMinute(d("2026-10-08"), null))
        assertEquals(m(23, 45), McxUsSilverRules.exitMinute(d("2026-11-03"), null))
        assertEquals(m(23, 45), McxUsSilverRules.exitMinute(d("2026-11-03"), cal.window(d("2026-11-03"))))
        // An early end (the Budget Sunday's 09:00-17:00): 16:50.
        assertEquals(m(16, 50), McxUsSilverRules.exitMinute(d("2026-02-01"), cal.window(d("2026-02-01"))))
    }

    @Test fun theDisasterStopReadsTheMinutesWicksAndTheCloseComesTenMinutesEarly() {
        val day = d("2026-10-08")
        assertEquals(97.0, McxUsSilverRules.disasterLevel(100.0, 1), 1e-9)
        assertEquals(103.0, McxUsSilverRules.disasterLevel(100.0, -1), 1e-9)
        val exitAt = m(23, 20)
        fun mins(vararg x: Triple<Int, Double, Double>) = x.map { McxUsSilverRules.Minute(it.first, it.second, it.third) }
        // A long: a minute's LOW at 97 stops it; its high does not matter.
        assertEquals("disaster_stop", McxUsSilverRules.exit(1, 100.0, day, m(9, 5), mins(Triple(m(12, 0), 101.0, 96.9)), day, m(12, 1), exitAt))
        assertNull(McxUsSilverRules.exit(1, 100.0, day, m(9, 5), mins(Triple(m(12, 0), 104.0, 97.1)), day, m(12, 1), exitAt))
        // A short: a minute's HIGH at 103.
        assertEquals("disaster_stop", McxUsSilverRules.exit(-1, 100.0, day, m(9, 5), mins(Triple(m(12, 0), 103.0, 99.0)), day, m(12, 1), exitAt))
        assertNull(McxUsSilverRules.exit(-1, 100.0, day, m(9, 5), mins(Triple(m(12, 0), 102.9, 90.0)), day, m(12, 1), exitAt))
        // A minute before the entry or not yet finished is not read.
        assertNull(McxUsSilverRules.exit(1, 100.0, day, m(9, 5), mins(Triple(m(9, 4), 100.0, 50.0), Triple(m(12, 1), 100.0, 50.0)), day, m(12, 1), exitAt))
        // 10 minutes before the close: out.
        assertNull(McxUsSilverRules.exit(1, 100.0, day, m(9, 5), emptyList(), day, m(23, 19), exitAt))
        assertEquals("close_10_min", McxUsSilverRules.exit(1, 100.0, day, m(9, 5), emptyList(), day, m(23, 20), exitAt))
        // The stop first when both are due.
        assertEquals("disaster_stop", McxUsSilverRules.exit(1, 100.0, day, m(9, 5), mins(Triple(m(23, 19), 100.0, 90.0)), day, m(23, 20), exitAt))
        // Held over by a closed app: out at the next pass.
        assertEquals("close_10_min", McxUsSilverRules.exit(1, 100.0, day, m(9, 5), emptyList(), day.plusDays(1), m(9, 0), exitAt))
        assertEquals(LocalTime.of(23, 30), McxSession.close(day))
    }
}
