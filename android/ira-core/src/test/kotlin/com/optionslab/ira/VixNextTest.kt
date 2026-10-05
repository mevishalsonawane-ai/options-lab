package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VixNextTest {
    private val today = LocalDate.of(2026, 10, 5)   // a Monday
    private val after = LocalDateTime.of(2026, 10, 5, 18, 0)

    /** [count] weekdays before [today], oldest first. */
    private fun weekdays(count: Int): List<LocalDate> {
        val out = ArrayList<LocalDate>()
        var d = today.minusDays(1)
        while (out.size < count) {
            if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) out += d
            d = d.minusDays(1)
        }
        return out.reversed()
    }

    /** A flat session at [price] from its open to [close], one bar at 10:00 trading [range] wide around the open. */
    private fun session(d: LocalDate, price: Double, close: Double = price, range: Double = 0.0, minutes: Int = 375): List<Candle> =
        (0 until minutes).map { i ->
            val t = LocalDateTime.of(d, LocalTime.of(9, 15)).plusMinutes(i.toLong())
            val c = if (i < 30) price else close
            if (t.toLocalTime() == LocalTime.of(10, 0)) Candle(t, c, maxOf(c, price + range / 2), minOf(c, price - range / 2), c) else Candle(t, c, c, c, c)
        }

    /**
     * 22 weekdays. India VIX closes at 15 and rises 6% on days 2, 5, 8, 11 and 14 (back to 15 the day after, a fall of
     * 5.7%); Nifty opens at 24,000 every day, ranging 240 (1%) on the day after each jump and 120 (0.5%) on every other
     * day, closing 48 up (0.2%) on the day after a jump and flat otherwise.
     */
    private val jumps = setOf(2, 5, 8, 11, 14)
    private val days = weekdays(22)
    private val vix: List<Candle> = days.flatMapIndexed { i, d -> session(d, if (i in jumps) 15.9 else 15.0) }
    private val nifty: List<Candle> = days.flatMapIndexed { i, d ->
        if (i - 1 in jumps) session(d, 24_000.0, 24_048.0, 240.0) else session(d, 24_000.0, 24_000.0, 120.0)
    }

    @Test fun theQuestionsAreReadAsTheRecord() {
        assertEquals(VixNext.Q(1, 5.0), VixNext.asked("when VIX jumps 5% how big is the next day?"))
        assertEquals(VixNext.Q(1, 5.0), VixNext.asked("after a vix spike how much does nifty move the next day"))
        assertEquals(VixNext.Q(-1, 5.0), VixNext.asked("when india vix falls 5% is the next session quieter"))
        assertEquals(VixNext.Q(1, 8.0), VixNext.asked("when vix rises 8 percent how wide is banknifty's range the next day"))
        assertEquals(VixNext.Q(1, 5.0), VixNext.asked("how big is nifty's range the next day when vix jumps"))
        assertEquals(VixNext.Q(1, 5.0), VixNext.asked("when vix jumps does nifty usually fall the next day"))
        assertEquals(VixNext.Q(null, 5.0), VixNext.asked("how does a big vix move change the next day's range"))
        assertEquals(VixNext.Q(1, 5.0), VixNext.asked("vix spike record for banknifty"))
        assertNotNull(VixNext.asked("jab vix 5% uchalta hai to agle din nifty kitna chalta hai"))
    }

    @Test fun aSingleDayAForecastAdviceOrAnotherReaderIsNotTheRecord() {
        for (s in listOf("vix jumped 6% today, will tomorrow be big", "what will nifty do tomorrow after the vix spike", "last time vix jumped like this what did the day look like",
            "when did vix last spike", "should i buy options after a vix jump", "is vix high or low right now", "what is india vix", "what if vix goes to 20",
            "alert me if vix jumps 5%", "after an inside day how often does nifty's range expand the next day", "how often does gold move the next day after vix jumps",
            "how big is the next day after a gap up", "why did vix jump"))
            assertNull(VixNext.asked(s), s)
    }

    @Test fun theIndexAskedAbout() {
        assertEquals(Market.NIFTY, VixNext.market(emptyList()))
        assertEquals(Market.NIFTY, VixNext.market(listOf(Market.VIX)))
        assertEquals(Market.BANKNIFTY, VixNext.market(listOf(Market.VIX, Market.BANKNIFTY)))
        assertNull(VixNext.market(listOf(Market.GOLD)))
    }

    @Test fun eachVixDayIsPairedWithTheIndexsNextSession() {
        val ps = VixNext.pairs(nifty, vix, today)
        assertEquals(20, ps.size)   // day 0 has no VIX day before it, day 21 no next session before today
        val p = ps.first { it.day == days[2] }
        assertEquals(6.0, p.vixPct, 1e-9); assertEquals(days[3], p.next)
        assertEquals(1.0, p.rangePct, 1e-9); assertEquals(0.2, p.movePct!!, 1e-9); assertEquals(0.5, p.dayRangePct!!, 1e-9)
        // A missing index session: the VIX day before it is left out rather than paired with the session after.
        val gap = nifty.filterNot { it.t.toLocalDate() == days[3] }
        assertTrue(VixNext.pairs(gap, vix, today).none { it.day == days[2] })
    }

    @Test fun theRecordIsCountedAndSaidAsAPastRecord() {
        val a = VixNext.answer(VixNext.Q(), Market.NIFTY, nifty, vix, today, after)
        assertTrue(a.contains("Over the last 20 India VIX sessions on this phone with Nifty's next whole session beside them"), a)
        assertTrue(a.contains("the next day's range (high to low) had a median of 0.50% of the open"), a)
        assertTrue(a.contains("On the 5 days India VIX rose 5% or more, Nifty's next session ranged a median 1.00% (against 0.50% for all), " +
            "wider than that all-day median on 5 of 5 (100%; about half would be by chance alone), moved a median 0.20% from the close before, " +
            "and closed up on 5 and down on 0; the VIX days themselves had ranged a median 0.50%."), a)
        assertTrue(a.contains("The biggest VIX rise among them was +6.0%"), a)
        assertTrue(a.endsWith(VixNext.NOTE), a)
        val fall = VixNext.answer(VixNext.Q(-1, 5.0), Market.NIFTY, nifty, vix, today, after)
        assertTrue(fall.contains("On the 5 days India VIX fell 5% or more, Nifty's next session ranged a median 0.50%"), fall)
        assertFalse(fall.contains("rose 5%"), fall)
        val big = VixNext.answer(VixNext.Q(1, 8.0), Market.NIFTY, nifty, vix, today, after)
        assertTrue(big.contains("India VIX rose 8% or more on only 0 of them - too few to say how the next day went (I need 5)."), big)
        val both = VixNext.answer(VixNext.Q(null, 5.0), Market.NIFTY, nifty, vix, today, after)
        assertTrue(both.contains("rose 5% or more") && both.contains("fell 5% or more"), both)
    }

    @Test fun tooFewDaysAreSaidHonestly() {
        val a = VixNext.answer(VixNext.Q(), Market.NIFTY, nifty, vix.filter { it.t.toLocalDate().isAfter(days[12]) }, today, after)
        assertTrue(a.startsWith("I have only 7 India VIX days on the phone with Nifty's next whole session beside them"), a)
        val none = VixNext.answer(VixNext.Q(), Market.NIFTY, nifty, emptyList(), today, after)
        assertTrue(none.startsWith("I have only 0 India VIX days"), none)
    }

    @Test fun vixsLatestChangeIsSetBeside() {
        val live = vix + session(today, 15.0, 16.2, minutes = 60)
        val a = VixNext.answer(VixNext.Q(), Market.NIFTY, nifty, live, today, LocalDateTime.of(today, LocalTime.of(10, 15)))
        assertTrue(a.contains("Today India VIX is +8.0% so far at 16.20 - a move of the size asked about, with the session still on."), a)
        val b = VixNext.answer(VixNext.Q(), Market.NIFTY, nifty, vix, today, after)
        assertTrue(b.contains("India VIX's last session on the phone (2 Oct) closed +0.0% at 15.00 - under the 5% asked about."), b)
    }
}
