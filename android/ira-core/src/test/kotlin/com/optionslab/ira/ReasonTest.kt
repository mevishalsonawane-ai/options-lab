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

class ReasonTest {
    private val day = LocalDate.of(2026, 10, 1)
    /** A day of 1-minute candles from 9:15, rising 1 point a minute from 24,000. */
    private val bars = (0 until 300).map { i -> val o = 24_000.0 + i; Candle(day.atTime(9, 15).plusMinutes(i.toLong()), o, o + 2, o - 1, o + 1) }

    @Test fun windowsAreRead() {
        assertEquals(60, Moves.asked("how much did nifty move in the last hour")?.minutes)
        assertEquals(30, Moves.asked("banknifty change in the last 30 minutes")?.minutes)
        assertEquals(LocalTime.of(9, 15), Moves.asked("how is nifty doing since the open")?.since)
        assertEquals(LocalTime.of(11, 0), Moves.asked("how much has nifty moved since 11")?.since)
        assertEquals(LocalTime.of(14, 30), Moves.asked("nifty move since 2:30")?.since)
        assertNull(Moves.asked("what are the levels on nifty"))
    }

    @Test fun theMoveIsWorkedOut() {
        val s = Moves.say(Market.NIFTY, bars, Moves.Window(minutes = 60, label = "in the last hour"))!!
        assertTrue(s.startsWith("Nifty rose 60.00 points in the last hour"), s)
        assertTrue("from 24,240.00 to 24,300.00" in s, s)
        assertNull(Moves.say(Market.NIFTY, bars, Moves.Window(since = LocalTime.of(15, 0), label = "since 15:00")), "nothing after the last candle")
    }

    @Test fun marketsAreCompared() {
        assertTrue(Compare.asked("is banknifty stronger than nifty"))
        assertTrue(Compare.asked("nifty vs sensex"))
        assertFalse(Compare.asked("how is nifty"))
        fun snap(m: Market, pc: Double, price: Double) = Snapshot(m, day.atTime(15, 29), false, price, pc, price, price, price, null, null, emptyList(), null, null, emptyList(), emptyList(), emptyList())
        val s = Compare.say(listOf(Market.NIFTY, Market.BANKNIFTY), mapOf(Market.NIFTY to snap(Market.NIFTY, 24_000.0, 24_100.0), Market.BANKNIFTY to snap(Market.BANKNIFTY, 52_000.0, 51_900.0)))!!
        assertTrue(s.startsWith("Nifty is the stronger today: Nifty +0.42%, BankNifty -0.19%."), s)
    }

    @Test fun theExpectedRangeComesFromVix() {
        assertTrue(ExpectedRange.asked("what is the expected range for nifty today"))
        assertTrue(ExpectedRange.asked("how far can banknifty move today"))
        assertFalse(ExpectedRange.asked("how is nifty"))
        assertEquals(24_000 * 0.16 / Math.sqrt(252.0), ExpectedRange.points(24_000.0, 16.0), 0.001)
        assertEquals(ExpectedRange.points(24_000.0, 16.0) / 2, ExpectedRange.points(24_000.0, 16.0, 375 / 4), 0.5)
        val snap = Snapshot(Market.NIFTY, day.atTime(12, 0), true, 24_000.0, 23_900.0, 24_000.0, 24_000.0, 24_000.0, null, null, emptyList(), null, null, emptyList(), emptyList(), emptyList())
        val s = ExpectedRange.say(snap, 16.0, LocalDateTime.of(day, LocalTime.of(12, 0)))
        assertNotNull(s); assertTrue("for the rest of today" in s, s)
    }
}

class ChartsTest {
    @Test fun chartsAgreeOrNot() {
        val d = java.time.LocalDate.of(2026, 10, 1)
        fun snap(up15: Boolean, up60: Boolean) = Snapshot(Market.NIFTY, d.atTime(12, 0), true, 24_000.0, 23_900.0, 23_950.0, 24_050.0, 23_940.0, null, null,
            listOf(TrendRead(15, up15, 23_980.0, null), TrendRead(60, up60, 23_900.0, null)), null, null, listOf(Level("day high", 24_050.0)), listOf(Level("day low", 23_940.0)), emptyList())
        val ira = Ira(PatternBook())
        val agree = ira.answer("how is nifty", mapOf(Market.NIFTY to snap(true, true)), emptyList()).text
        assertTrue("Both charts point up" in agree, agree)
        assertTrue("Nearest level above: day high at 24,050.00, 50.00 away." in agree, agree)
        val split = ira.answer("how is nifty", mapOf(Market.NIFTY to snap(false, true)), emptyList()).text
        assertTrue("The charts disagree" in split, split)
    }
}

class FreshnessTest {
    @Test fun stalePricesAreSaid() {
        val d = java.time.LocalDate.of(2026, 10, 1)                                 // a Thursday
        assertTrue(Freshness.note(Market.NIFTY, d.atTime(11, 0), d.atTime(11, 12))!!.contains("12 minutes old"))
        assertNull(Freshness.note(Market.NIFTY, d.atTime(11, 0), d.atTime(11, 2)), "fresh")
        assertNull(Freshness.note(Market.NIFTY, d.atTime(15, 29), d.atTime(18, 0)), "the market is closed")
    }
}

class WhyTest {
    private val d = java.time.LocalDate.of(2026, 10, 1)
    private fun snap(m: Market, prev: Double, open: Double, price: Double) =
        Snapshot(m, d.atTime(12, 0), true, price, prev, open, maxOf(open, price), minOf(open, price), null, null, emptyList(), null, null, emptyList(), emptyList(), emptyList())

    @Test fun theStoryIsPutTogether() {
        val snaps = mapOf(
            Market.NIFTY to snap(Market.NIFTY, 24_000.0, 23_850.0, 23_800.0),
            Market.BANKNIFTY to snap(Market.BANKNIFTY, 52_000.0, 51_800.0, 51_600.0),
            Market.SENSEX to snap(Market.SENSEX, 80_000.0, 79_700.0, 79_500.0),
            Market.VIX to snap(Market.VIX, 13.0, 13.5, 14.0))
        val s = Why.story(snaps.getValue(Market.NIFTY), snaps)!!
        assertTrue("Nifty opened 150.00 points below the previous close (a gap down) and has added 50.00 more since the open." in s, s)
        assertTrue("The move is broad" in s, s)
        assertTrue("Fear is rising" in s, s)
    }

    @Test fun aMarketAloneIsSaid() {
        val snaps = mapOf(Market.NIFTY to snap(Market.NIFTY, 24_000.0, 24_000.0, 24_100.0), Market.BANKNIFTY to snap(Market.BANKNIFTY, 52_000.0, 52_000.0, 51_700.0))
        val s = Why.story(snaps.getValue(Market.BANKNIFTY), snaps)!!
        assertTrue("BankNifty is moving on its own" in s, s)
    }
}

class AllIndicesTest {
    @Test fun allIndicesAndTheStrongest() {
        assertEquals(Reasoning.INDICES, Ask.parse("levels on all indices").markets)
        assertEquals(listOf(Market.NIFTY), Ask.parse("levels on nifty").markets)
        assertTrue(Compare.asked("which index is strongest today"))
        assertEquals(Reasoning.INDICES, Compare.markets("which index is strongest today"))
        assertEquals(setOf(Market.NIFTY, Market.SENSEX), Compare.markets("nifty vs sensex").toSet())
        assertFalse(Compare.asked("what are the levels"))
    }
}

class DayStoryTest {
    @Test fun theDayIsTold() {
        val d = java.time.LocalDate.of(2026, 10, 1)
        // Up 1 a minute for an hour, then down 1 a minute for two hours.
        val bars = (0 until 180).map { i -> val p = if (i < 60) 24_000.0 + i else 24_060.0 - (i - 60); Candle(d.atTime(9, 15).plusMinutes(i.toLong()), p, p + 0.5, p - 0.5, p) }
        val s = DayStory.say(Market.NIFTY, bars)!!
        assertTrue(s.startsWith("Nifty opened at 24,000.00, made its high of 24,060.50 at 10:15, then fell to the low of 23,940.50 at 12:14."), s)
        assertTrue("near the day's low" in s, s)
        assertTrue(DayStory.asked("how has the day gone"))
        assertTrue(DayStory.asked("give me a recap of banknifty"))
        assertFalse(DayStory.asked("how is nifty"))
    }
}

class SourcesTest {
    @Test fun theFactsAreShown() {
        assertTrue(Sources.asked("how do you know that?"))
        assertTrue(Sources.asked("Jarvis, what is that based on"))
        assertFalse(Sources.asked("how do you know the levels on nifty"))
        assertEquals("I worked that out from: Nifty: last price 24,000.00; day high 24,100.00.", Sources.say(listOf("Nifty: last price 24,000.00", "day high 24,100.00")))
        assertEquals(Command.Kind.MORE, Commands.parse("say that again")?.kind)
        assertEquals(Command.Kind.MORE, Commands.parse("Jarvis, repeat that")?.kind)
    }
}

class PivotsTest {
    @Test fun pivotsAreWorkedOut() {
        val lv = Pivots.of(110.0, 90.0, 100.0)
        assertEquals(100.0, lv.p); assertEquals(110.0, lv.r1); assertEquals(90.0, lv.s1); assertEquals(120.0, lv.r2); assertEquals(80.0, lv.s2)
        assertTrue(Pivots.asked("what are tomorrow's levels for nifty"))
        assertTrue(Pivots.asked("banknifty pivots"))
        assertFalse(Pivots.asked("what are the levels"))
        val d1 = java.time.LocalDate.of(2026, 9, 30); val d2 = java.time.LocalDate.of(2026, 10, 1)
        val bars = listOf(Candle(d1.atTime(9, 15), 100.0, 110.0, 90.0, 100.0), Candle(d2.atTime(9, 15), 100.0, 130.0, 95.0, 120.0))
        assertTrue(Pivots.say(Market.NIFTY, bars, trading = true)!!.contains("for today, from 2026-09-30"))
        assertTrue(Pivots.say(Market.NIFTY, bars, trading = false)!!.contains("for the next session, from 2026-10-01"))
    }
}

class LookbackTest {
    private val d1 = java.time.LocalDate.of(2026, 9, 30); private val d2 = java.time.LocalDate.of(2026, 10, 1)
    private val bars = listOf(Candle(d1.atTime(9, 15), 100.0, 110.0, 90.0, 105.0), Candle(d1.atTime(15, 29), 105.0, 106.0, 104.0, 104.0),
        Candle(d2.atTime(9, 15), 104.0, 108.0, 103.0, 107.0), Candle(d2.atTime(11, 0), 107.0, 112.0, 106.0, 111.0), Candle(d2.atTime(12, 0), 111.0, 113.0, 110.0, 112.0))

    @Test fun aTimeIsLookedUp() {
        assertEquals(LocalTime.of(11, 0), Lookback.time("where was nifty at 11 am"))
        assertEquals(LocalTime.of(14, 30), Lookback.time("what was banknifty at 2:30"))
        assertNull(Lookback.time("alert me at 25000"))
        assertNull(Lookback.time("set an alarm at 11"))
        val s = Lookback.priceAt(Market.NIFTY, bars, LocalTime.of(11, 30))!!
        assertTrue(s.startsWith("At 11:00 on 2026-10-01 Nifty was at 111.00. Since then it has moved +1.00 to 112.00."), s)
    }

    @Test fun yesterdayIsTold() {
        assertTrue(Lookback.prevAsked("what was yesterday's high on nifty"))
        assertTrue(Lookback.prevAsked("previous close of banknifty"))
        assertFalse(Lookback.prevAsked("how is nifty"))
        assertEquals("Nifty on 2026-09-30: open 100.00, high 110.00, low 90.00, close 104.00.", Lookback.prevDay(Market.NIFTY, bars, trading = true))
    }
}

class MomentumTest {
    @Test fun rsiIsRead() {
        val up = (0..40).map { 100.0 + it }
        assertEquals(100.0, Momentum.rsi(up))
        val zig = (0..40).map { if (it % 2 == 0) 100.0 else 101.0 }
        assertEquals(50.0, Momentum.rsi(zig)!!, 5.0)
        assertNull(Momentum.rsi(listOf(1.0, 2.0)))
        assertTrue(Momentum.asked("is nifty overbought"))
        assertTrue(Momentum.asked("rsi on banknifty"))
        val d = java.time.LocalDate.of(2026, 10, 1)
        val bars = (0 until 300).map { i -> val p = 24_000.0 + i; Candle(d.atTime(9, 15).plusMinutes(i.toLong()), p, p + 1, p - 1, p + 0.5) }
        val s = Momentum.say(Market.NIFTY, bars, d.atTime(14, 20))!!
        assertTrue("15-minute RSI is 100, overbought" in s, s)
    }
}

class OddsTest {
    @Test fun theOddsAreWorkedOut() {
        assertEquals(0.5, Odds.phi(0.0), 1e-6)
        assertEquals(0.8413, Odds.phi(1.0), 1e-3)
        assertEquals(0.1587, Odds.phi(-1.0), 1e-3)
        val a = Odds.asked("what are the chances nifty closes above 24,500 today")!!
        assertTrue(a.above); assertEquals(24_500.0, a.level)
        assertEquals(false, Odds.asked("probability banknifty ends below 51000")?.above)
        assertNull(Odds.asked("how is nifty"))
        val d = java.time.LocalDate.of(2026, 10, 1)
        val snap = Snapshot(Market.NIFTY, d.atTime(12, 0), true, 24_000.0, 23_900.0, 24_000.0, 24_000.0, 24_000.0, null, null, emptyList(), null, null, emptyList(), emptyList(), emptyList())
        val s = Odds.say(snap, 16.0, Odds.Ask(true, 24_000.0), d.atTime(12, 0))!!
        assertTrue(s.startsWith("Going by India VIX, there is about a 50% chance Nifty closes above 24,000.00 today"), s)
    }
}

class RangeAndPeriodTest {
    @Test fun theOpeningRangeIsRead() {
        val d = java.time.LocalDate.of(2026, 10, 1)
        val snap = Snapshot(Market.NIFTY, d.atTime(12, 0), true, 24_100.0, 24_000.0, 24_000.0, 24_120.0, 23_990.0, 24_050.0, 23_990.0, emptyList(), null, null, emptyList(), emptyList(), emptyList())
        assertTrue(OpeningRange.asked("has nifty broken the opening range"))
        assertTrue(OpeningRange.say(snap)!!.contains("above it, 50.00 over the opening high"))
    }

    @Test fun aWeekIsTold() {
        // Mon 28 Sep .. Thu 1 Oct 2026, with Fri 25 Sep before it.
        val days = listOf(25, 28, 29, 30).map { java.time.LocalDate.of(2026, 9, it) } + java.time.LocalDate.of(2026, 10, 1)
        val bars = days.mapIndexed { i, d -> Candle(d.atTime(9, 15), 100.0 + i, 102.0 + i, 99.0 + i, 101.0 + i) }
        assertEquals(PeriodMove.Span.WEEK, PeriodMove.asked("how did nifty do this week"))
        assertEquals(PeriodMove.Span.MONTH, PeriodMove.asked("how is banknifty doing this month"))
        assertNull(PeriodMove.asked("what are the levels this week"))
        val s = PeriodMove.say(Market.NIFTY, bars, PeriodMove.Span.WEEK)!!
        assertTrue(s.startsWith("Nifty this week: +4.00 points"), s)
        assertTrue("over 4 sessions" in s, s)
    }
}

class BriefingTest {
    @Test fun aBriefing() {
        assertTrue(Briefing.asked("Jarvis, brief me"))
        assertTrue(Briefing.asked("what do I need to know today"))
        assertFalse(Briefing.asked("brief mode on"))
        val d = java.time.LocalDate.of(2026, 10, 1)
        fun snap(m: Market, prev: Double, price: Double) = Snapshot(m, d.atTime(12, 0), true, price, prev, prev, price, prev, null, null, emptyList(), null, null, emptyList(), emptyList(), emptyList())
        val s = Briefing.say(mapOf(Market.NIFTY to snap(Market.NIFTY, 24_000.0, 24_120.0), Market.BANKNIFTY to snap(Market.BANKNIFTY, 52_000.0, 51_900.0),
            Market.VIX to snap(Market.VIX, 13.0, 14.0)), d.atTime(12, 0), listOf("Event today: RBI policy."))!!
        assertTrue(s.startsWith("Nifty 24,120.00 (+0.50%), BankNifty 51,900.00 (-0.19%). Nifty is the strongest, BankNifty the weakest."), s)
        assertTrue("Fear is rising" in s && s.endsWith("Event today: RBI policy."), s)
    }
}

class VixRankTest {
    @Test fun vixAgainstItsPast() {
        val d0 = java.time.LocalDate.of(2025, 10, 1)
        val bars = (0 until 100).map { i -> val v = 10.0 + i * 0.1; Candle(d0.plusDays(i.toLong()).atTime(15, 29), v, v, v, v) }   // 10.0 .. 19.9
        assertEquals(50.0, VixRank.rank(bars, 15.0)!!.first)
        assertTrue(VixRank.say(bars, 19.5)!!.contains("high: fear is well above usual"))
        assertTrue(VixRank.asked("is vix high"))
        assertTrue(VixRank.asked("how high is india vix"))
        assertFalse(VixRank.asked("how is nifty"))
        assertNull(VixRank.rank(bars.take(5), 12.0))
    }
}
