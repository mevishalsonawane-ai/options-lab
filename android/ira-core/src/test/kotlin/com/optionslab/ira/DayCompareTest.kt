package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DayCompareTest {
    private val today = LocalDate.of(2026, 10, 5)      // a Monday
    private val friday = LocalDate.of(2026, 10, 2)
    private val thursday = LocalDate.of(2026, 10, 1)
    private val wednesday = LocalDate.of(2026, 9, 30)

    private fun session(d: LocalDate, minutes: Int, path: (Int) -> Double): List<Candle> {
        val out = ArrayList<Candle>()
        var px = path(0)
        for (i in 0 until minutes) {
            val o = px; px = path(i + 1)
            out += Candle(d.atTime(9, 15).plusMinutes(i.toLong()), o, maxOf(o, px) + 0.5, minOf(o, px) - 0.5, px)
        }
        return out
    }

    /** A steady climb with small waves: trend-like up. */
    private fun trendUp(d: LocalDate, minutes: Int, base: Double = 24_000.0) = session(d, minutes) { i -> base + i * 1.0 + 5 * sin(2 * PI * i / 20) }
    /** Waves round the open: range-like. */
    private fun range(d: LocalDate, minutes: Int, base: Double = 24_000.0) = session(d, minutes) { i -> base + 40 * sin(2 * PI * i / 75) }

    private val ADVICE = Regex("(?i)\\b(you should|i recommend|i suggest|buy|sell|go long|go short|will (rise|fall|go|break)|likely|expect|target)\\b")

    @Test fun theQuestions() {
        mapOf(
            "How is today different from yesterday?" to DayCompare.Ref(1),
            "how is nifty today different from yesterday" to DayCompare.Ref(1),
            "how does today compare with yesterday" to DayCompare.Ref(1),
            "compare today with yesterday" to DayCompare.Ref(1),
            "compare yesterday and today" to DayCompare.Ref(1),
            "today vs yesterday" to DayCompare.Ref(1),
            "BankNifty today vs yesterday" to DayCompare.Ref(1),
            "is today more like a trend day than yesterday?" to DayCompare.Ref(1),
            "is today more like a trend day or a range day than yesterday" to DayCompare.Ref(1),
            "how is today different from last Thursday?" to DayCompare.Ref(weekday = DayOfWeek.THURSDAY),
            "compare today with last friday" to DayCompare.Ref(weekday = DayOfWeek.FRIDAY),
            "today versus monday" to DayCompare.Ref(weekday = DayOfWeek.MONDAY),
            "is today like yesterday" to DayCompare.Ref(1),
            "what's the difference between today and yesterday" to DayCompare.Ref(1),
            "how is today different from the day before yesterday" to DayCompare.Ref(2),
            "how is today different from the previous session" to DayCompare.Ref(1),
            "aaj aur kal mein kya fark hai" to DayCompare.Ref(1),
            "aaj kal se kaise alag hai" to DayCompare.Ref(1),
            "is today a trend day like yesterday" to DayCompare.Ref(1),
        ).forEach { (s, ref) -> assertEquals(ref, DayCompare.asked(s)?.other, s) }
        assertEquals(DayCompare.Focus.TREND_RANGE, DayCompare.asked("is today more like a trend day than yesterday")?.focus)
        assertEquals(DayCompare.Focus.ALL, DayCompare.asked("how is today different from yesterday")?.focus)
        listOf("what happened yesterday", "how did nifty do yesterday", "what's the structure today", "is today a trend day",
            "how is today different from a usual day", "what's different about today", "how is my pnl today vs yesterday",
            "compare my trades today with yesterday", "aaj aur kal mein kya fark hoga", "will today be like yesterday",
            "how is today's news different from yesterday", "how did nifty do on fridays", "relative strength banknifty vs nifty",
            "today vs last week", "how is gold today different from yesterday", "compare oi today with yesterday",
            "what if today is like yesterday", "buy nifty").forEach { assertNull(DayCompare.asked(it), it) }
    }

    @Test fun lastWeekdayIsBeforeToday() {
        assertEquals(thursday, DayCompare.resolve(DayCompare.Ref(weekday = DayOfWeek.THURSDAY), emptyList(), today))
        assertEquals(LocalDate.of(2026, 9, 28), DayCompare.resolve(DayCompare.Ref(weekday = DayOfWeek.MONDAY), emptyList(), today))
        val days = listOf(wednesday, thursday, friday, today)
        assertEquals(friday, DayCompare.resolve(DayCompare.Ref(1), days, today))
        assertEquals(thursday, DayCompare.resolve(DayCompare.Ref(2), days, today))
    }

    @Test fun aMorningIsSetAgainstTheSameMorning() {
        // Friday ranged all day; today climbs, 120 minutes in.
        val bars = range(friday, 375) + trendUp(today, 120)
        val said = DayCompare.answer(DayCompare.Q(DayCompare.Ref(1), DayCompare.Focus.ALL), Market.NIFTY, bars, today)
        assertTrue("today against the last session (Fri 2 Oct), both up to 11:15" in said, said)
        assertTrue("Today so far: trend-like, up" in said, said)
        assertTrue("The last session by 11:15: range-like" in said, said)
        assertTrue("Today was the more trend-like and the last session the more range-like" in said, said)
        assertTrue("By its close, the last session ended range-like" in said, said)
        assertTrue(said.endsWith(DayCompare.NOTE), said)
        assertTrue(!ADVICE.containsMatchIn(said), said)
    }

    @Test fun wholeSessionsWhenTodayIsDoneAndTheOtherWayRound() {
        val bars = trendUp(thursday, 375) + range(friday, 375) + range(today, 375, 24_380.0)
        val said = DayCompare.answer(DayCompare.Q(DayCompare.Ref(weekday = DayOfWeek.THURSDAY), DayCompare.Focus.TREND_RANGE), Market.NIFTY, bars, today)
        assertTrue("today against last Thursday (Thu 1 Oct), whole sessions" in said, said)
        assertTrue("Last Thursday was the more trend-like and today the more range-like" in said, said)
        // The gap is measured against each day's own prior close.
        assertTrue("opened" in said, said)
        assertTrue(!ADVICE.containsMatchIn(said), said)
    }

    @Test fun closeDaysAreSaidClose() {
        val bars = range(friday, 375) + range(today, 200, 24_010.0)
        val said = DayCompare.answer(DayCompare.Q(DayCompare.Ref(1), DayCompare.Focus.ALL), Market.NIFTY, bars, today)
        assertTrue("On trend against range the two are close" in said, said)
    }

    @Test fun honestWhenADayIsMissing() {
        val bars = range(friday, 375) + trendUp(today, 120)
        val noThu = DayCompare.answer(DayCompare.Q(DayCompare.Ref(weekday = DayOfWeek.THURSDAY), DayCompare.Focus.ALL), Market.NIFTY, bars, today)
        assertTrue("no Nifty candles for last Thursday (Thu 1 Oct)" in noThu, noThu)
        val early = DayCompare.answer(DayCompare.Q(DayCompare.Ref(1), DayCompare.Focus.ALL), Market.NIFTY, range(friday, 375) + trendUp(today, 10), today)
        assertTrue("It's early" in early, early)
        val none = DayCompare.answer(DayCompare.Q(DayCompare.Ref(1), DayCompare.Focus.ALL), Market.NIFTY, trendUp(today, 120), today)
        assertTrue("no Nifty session before today" in none, none)
        assertEquals(DayCompare.NOT_HERE, DayCompare.answer(DayCompare.Q(DayCompare.Ref(1), DayCompare.Focus.ALL), Market.VIX, bars, today))
        assertNotNull(DayCompare.market(emptyList()))
        assertNull(DayCompare.market(listOf(Market.VIX)))
    }

    @Test fun nothingActs() {
        listOf("compare today with yesterday", "today vs yesterday", "aaj aur kal mein kya fark hai", "how is today different from last thursday").forEach {
            val p = Ask.parse(it); assertNull(p.order, it); assertNull(p.command, it); assertTrue(!Bundle.acts(it), it)
        }
    }
}
