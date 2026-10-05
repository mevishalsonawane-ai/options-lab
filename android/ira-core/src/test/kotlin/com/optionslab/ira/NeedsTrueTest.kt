package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NeedsTrueTest {
    private val at = LocalDateTime.of(2026, 10, 6, 13, 15)          // a Tuesday
    private val thu = LocalDate.of(2026, 10, 8)
    private val put = PositionHealth.Pos("Paper", "NIFTY2610824500PE", 75, 80.0, 60.0, "NIFTY", 24500.0, "PE", thu,
        spot = 24650.0, avgRange = 200.0, rangeDays = 10, theta = -6.0)
    private val shortCall = PositionHealth.Pos("Zerodha", "BANKNIFTY26OCT56000CE", -30, 300.0, 250.0, "BANKNIFTY", 56000.0, "CE",
        LocalDate.of(2026, 10, 28), spot = 55500.0, theta = -20.0)
    private val fut = PositionHealth.Pos("Zerodha", "RELIANCE26OCTFUT", 500, 2900.0, 2950.0)

    /** Weekday sessions of 375 flat 1-minute candles, one price each, oldest first, all before [at]. */
    private fun sessions(prices: List<Double>): List<Candle> {
        val days = ArrayList<LocalDate>()
        var d = LocalDate.of(2026, 10, 5)
        while (days.size < prices.size) { d = d.minusDays(1); if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) days += d }
        days.reverse()
        return days.zip(prices).flatMap { (day, p) -> (0 until 375).map { i ->
            Candle(LocalDateTime.of(day, LocalTime.of(9, 15)).plusMinutes(i.toLong()), p, p, p, p) } }
    }

    @Test fun asked() {
        for (q in listOf("for my 24500 put to work, what needs to happen?", "Jarvis, what has to happen for my call to pay off?",
                "what would have to be true for my position to make money", "where is my breakeven?", "how far is my breakeven",
                "what does my 24500 put need", "meri put ke liye kya hona chahiye", "for my banknifty call to make money what has to happen",
                "what needs to happen for my 24,500 pe", "breakeven of my positions"))
            assertTrue(NeedsTrue.asked(q), q)
        for (q in listOf("check my positions", "what if nifty falls 1%", "what happens to my p&l if nifty moves 100 points",
                "what is a breakeven", "close my 24500 put", "what needs to happen today", "how is nifty", "make the case for buying a put",
                "show my positions", "buy 1 lot nifty 24500 pe", "what's my p&l"))
            assertFalse(NeedsTrue.asked(q), q)
    }

    @Test fun routedToTheAccountAlone() {
        for (q in listOf("for my 24500 put to work, what needs to happen?", "where is my breakeven", "meri put ke liye kya hona chahiye")) {
            val p = Ask.parse(q)
            assertEquals(setOf(Topic.ACCOUNT), p.topics, q)
            assertNull(p.order, q); assertNull(p.command, q)
            assertEquals(setOf(Section.NEED), AppAnswers.sections(q), q)
        }
        assertEquals(setOf(Section.HEALTH), AppAnswers.sections("check my positions"))
    }

    @Test fun pickTheNamedOne() {
        assertEquals(NeedsTrue.Pick(24500.0, "PE", null), NeedsTrue.pick("for my 24500 put to work"))
        assertEquals(NeedsTrue.Pick(24500.0, "PE", null), NeedsTrue.pick("my 24,500 pe"))
        assertEquals(NeedsTrue.Pick(24500.0, "PE", null), NeedsTrue.pick("my 24500pe"))
        assertEquals(NeedsTrue.Pick(null, "CE", "BANKNIFTY"), NeedsTrue.pick("my bank nifty call"))
        assertEquals(NeedsTrue.Pick(), NeedsTrue.pick("where is my breakeven"))
    }

    @Test fun sessionsLeft() {
        val week = { d: LocalDate -> d.dayOfWeek.value <= 5 }
        assertEquals(3, NeedsTrue.sessionsLeft(at, thu, week))                       // Tue's rest, Wed, Thu
        assertEquals(2, NeedsTrue.sessionsLeft(at.withHour(16), thu, week))          // after the close: Wed, Thu
        assertEquals(2, NeedsTrue.sessionsLeft(at, thu) { week(it) && it != LocalDate.of(2026, 10, 7) })   // a holiday
        assertEquals(0, NeedsTrue.sessionsLeft(at.withHour(16), at.toLocalDate(), week))
        assertEquals(6, NeedsTrue.sessionsLeft(at, LocalDate.of(2026, 10, 13), week))   // over the weekend
    }

    @Test fun stretches() {
        val bars = sessions(listOf(100.0, 110.0, 99.0, 121.0))
        // Two sessions from 13:15: from day i's price to day i+1's close.
        assertEquals(listOf(0.1, -0.1, round(121.0 / 99 - 1)), NeedsTrue.stretches(bars, at.toLocalDate(), 2, LocalTime.of(13, 15)).map { round(it) })
        // After the close: from the previous close to the close two sessions later.
        assertEquals(listOf(round(99.0 / 100 - 1), round(121.0 / 110 - 1)), NeedsTrue.stretches(bars, at.toLocalDate(), 2, null).map { round(it) })
        assertTrue(NeedsTrue.stretches(bars, at.toLocalDate(), 0, null).isEmpty())
        // Short sessions are not counted.
        assertTrue(NeedsTrue.stretches(bars.filter { it.t.minute % 2 == 0 }, at.toLocalDate(), 1, LocalTime.NOON).isEmpty())
    }

    private fun round(x: Double) = Math.round(x * 1e6) / 1e6

    @Test fun aLongPutWorkedThrough() {
        // 20 past sessions, two at 24,000 then two at 23,700: over 3 sessions (day i to day i+2) the move is -1.25% or +1.27%.
        val bars = mapOf("NIFTY" to sessions((0 until 20).map { if (it % 4 < 2) 24000.0 else 23700.0 }))
        val l = NeedsTrue.lines("for my 24500 put to work, what needs to happen?", listOf(put, shortCall, fut), bars, at)
        assertEquals("What has to be true for your position, Boss, worked from the facts:", l[0])
        assertEquals("Paper NIFTY2610824500PE, 75 long at 80.00: for it to be in profit at expiry (Thu 8 Oct), Nifty has to be below 24,420 - " +
            "the 24,500 strike less the 80.00 paid.", l[1])
        assertEquals("Nifty is at 24,650.00 now: that is a fall of 230 points (0.93%) needed in the 3 sessions left (today's rest counted).", l[2])
        assertEquals("Typical: its average day's range is 200 points (last 10 sessions), so the distance is 1.2 of a day's range; " +
            "its median move over 3 sessions from this time of day on the phone is 308 points (1.25%) either way.", l[3])
        // 18 stretches of 3 sessions; the 10 starting at 24,000 end at 23,700 (-1.25%): a fall of 0.93% or more.
        assertEquals("A fall of 0.93% or more within 3 sessions: 10 of the last 18 overlapping stretches on the phone (56%).", l[4])
        assertEquals("Time: decay about -Rs 450.00 a day on the position (6.00 a unit) - each day without the move costs a buyer about that.", l[5])
        // Round 30: the move that covers the rest of today's decay, beside the (flat) record - it never went that far.
        assertTrue(l[6].startsWith("Covering today's decay: about Rs 162 of decay is still to come on the position today " +
            "(one day's theta spread over the session, 2 h 15 min left). At its delta of "), l[6])
        assertTrue(l[6].contains("On the record from 13:15, it went that far down before the close on 0 of the last 20 whole sessions on the phone (0%), " +
            "and was still that far down at the close on 0 (0%)."), l[6])
        assertTrue(l[7].startsWith("That is at expiry, from your average price, charges left out"))
        assertEquals("Facts and arithmetic, not a forecast or advice - your call, Boss.", l.last())
        assertFalse(l.any { Regex("(?i)\\b(should|recommend|suggest|will (rise|fall)|likely)\\b").containsMatchIn(it) })
    }

    @Test fun aShortCallAndFuturesEach() {
        val l = NeedsTrue.lines("where is my breakeven", listOf(put, shortCall, fut), emptyMap(), at)
        assertEquals("What has to be true for each of your 3 open positions, Boss, worked from the facts:", l[0])
        val call = l.indexOfFirst { it.startsWith("Zerodha BANKNIFTY26OCT56000CE") }
        assertEquals("Zerodha BANKNIFTY26OCT56000CE, 30 short at 300.00: for it to be in profit at expiry (Wed 28 Oct), BankNifty has to be below 56,300 - " +
            "the 56,000 strike plus the 300.00 received; all of the premium is kept at or below 56,000.", l[call])
        assertEquals("BankNifty is at 55,500.00 now: 800 points (1.44%) on the right side already, so it needs BankNifty not to rise more than that " +
            "in the 17 sessions left (today's rest counted).", l[call + 1])
        assertEquals("Too few past stretches of 17 sessions on the phone (0) to say how often a move that size happened.", l[call + 2])
        assertEquals("Time: decay about +Rs 600.00 a day on the position (20.00 a unit) - each quiet day pays a seller about that.", l[call + 3])
        assertTrue(l.contains("Zerodha RELIANCE26OCTFUT, 500 long at 2,900.00: it is in profit while its price is above 2,900.00, your average - " +
            "50.00 above it now, at 2,950.00 (charges left out)."), l.toString())
    }

    @Test fun nothingOrNotThatOne() {
        assertEquals(listOf("You have no open positions, Boss, so there is nothing that has to happen for one."),
            NeedsTrue.lines("where is my breakeven", emptyList(), emptyMap(), at))
        assertEquals(listOf("None of your open positions is the 24,000 call, Boss. Open now: Paper NIFTY2610824500PE."),
            NeedsTrue.lines("for my 24000 call to work what needs to happen", listOf(put), emptyMap(), at))
        // Spot unknown, or expired.
        assertTrue(NeedsTrue.lines("my breakeven", listOf(put.copy(spot = null)), emptyMap(), at).contains("Nifty's price is not known just now, so not how far that is."))
        assertTrue(NeedsTrue.lines("my breakeven", listOf(put.copy(expiry = at.toLocalDate())), emptyMap(), at.withHour(16))
            .any { it.startsWith("It has expired") })
    }
}
