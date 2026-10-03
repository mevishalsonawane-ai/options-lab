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
        assertEquals("Nifty on 2026-09-30: open 100.00, high 110.00, low 90.00, close 104.00.", Lookback.prevDay(Market.NIFTY, bars, d2))
        // On a Saturday after Thursday 1 Oct, "yesterday" is Thursday (the last session before today), not Wednesday.
        assertTrue(Lookback.prevDay(Market.NIFTY, bars, java.time.LocalDate.of(2026, 10, 3))!!.startsWith("Nifty on 2026-10-01"))
        assertTrue(Lookback.priceAt(Market.NIFTY, bars, LocalTime.of(11, 30), yesterday = true, today = java.time.LocalDate.of(2026, 10, 3))!!.contains("on 2026-10-01"))
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

class HinglishReasonTest {
    @Test fun hinglishReachesTheReasoning() {
        assertEquals(60, Moves.asked(Hinglish.normalize("pichle ghante nifty kitna gira"))?.minutes)
        assertEquals(PeriodMove.Span.WEEK, PeriodMove.asked(Hinglish.normalize("is hafte banknifty kitna chadha")))
        assertEquals(PeriodMove.Span.MONTH, PeriodMove.asked(Hinglish.normalize("is mahine nifty kitna gira")))
    }
}

class OrbAlertTest {
    @Test fun aBreakIsSeen() {
        val d = java.time.LocalDate.of(2026, 10, 1)
        fun snap(px: Double) = Snapshot(Market.NIFTY, d.atTime(10, 0), true, px, 24_000.0, 24_000.0, 24_100.0, 23_950.0, 24_050.0, 23_990.0, emptyList(), null, null, emptyList(), emptyList(), emptyList())
        assertEquals(true, OpeningRange.broken(snap(24_060.0)))
        assertEquals(false, OpeningRange.broken(snap(23_980.0)))
        assertNull(OpeningRange.broken(snap(24_020.0)))
        assertEquals("Nifty broke above its opening range at 24,060.00 (the first 15 minutes' high was 24,050.00).", OpeningRange.alert(snap(24_060.0), true))
    }
}

class PayoffTest {
    @Test fun valueAtExpiry() {
        val a = Payoff.asked("what is a 24800 call worth if nifty is at 25000 at expiry")!!
        assertEquals(24_800.0, a.strike); assertTrue(a.call); assertEquals(25_000.0, a.at)
        assertTrue(Payoff.say(a).startsWith("At expiry with the index at 25,000.00, the 24,800 call is worth 200.00 points."), Payoff.say(a))
        val b = Payoff.asked("24800 pe if nifty expires at 24500, bought at 120")!!
        assertEquals(120.0, b.paid)
        val s = Payoff.say(b)
        assertTrue("worth 300.00 points" in s && "+180.00 per unit" in s && "breaks even at 24,680.00" in s, s)
        assertTrue(Payoff.say(Payoff.Ask(25_000.0, true, 24_900.0, null)).contains("expires worthless"))
        assertNull(Payoff.asked("buy 1 lot nifty 24800 ce"))
    }
}

class NewsAnswerTest {
    @Test fun anyNewsGivesTheLatestHeadlines() {
        val now = java.time.Instant.parse("2026-10-01T06:00:00Z")
        val news = listOf(
            Headline("Sensex jumps 500 points on bank rally", "l1", "Economic Times", now, 0.5, listOf(Market.SENSEX)),
            Headline("Rupee steadies as oil eases", "l2", "Livemint", now.minusSeconds(600), 0.0, emptyList()),
            Headline("Gold slips as dollar firms", "l3", "FXStreet", now.minusSeconds(60), -0.4, listOf(Market.GOLD)))
        val ira = Ira(PatternBook())
        val a = ira.answer("any news?", emptyMap(), news).text
        assertTrue(a.startsWith("The latest market headlines: \"Sensex jumps 500 points on bank rally\""), a)
        assertFalse("Gold slips" in a, "Indian markets first")
        val b = ira.answer("any news on banknifty", emptyMap(), news).text
        assertTrue(b.contains("Nothing specific on BankNifty lately.") && b.contains("Sensex jumps"), b)
        assertTrue(ira.answer("what's the news", emptyMap(), emptyList()).text.startsWith("I have no headlines yet"))
    }
}

class ReviewNTest {
    @Test fun periodQuestionsOnAMarketAreTheMarkets() {
        for (q in listOf("how did nifty do this week", "nifty weekly performance", "banknifty this week", "how much is nifty up this month", "has nifty broken the orb", "nifty pivots"))
            assertFalse(Topic.ACCOUNT in Ask.parse(q).topics, q)
        for (q in listOf("how did my trades do this week", "what is my stop loss on nifty", "nifty alarm level", "what's my mtm"))
            assertTrue(Topic.ACCOUNT in Ask.parse(q).topics, q)
        assertFalse(Topic.WHY in Ask.parse(Hinglish.normalize("pichle ghante nifty kitna gira")).topics)
        assertFalse(Topic.ACCOUNT in Ask.parse("if i bought 24500 ce at 120 what is my profit at 24700").topics)
    }

    @Test fun memoryKeepsNoSecretAndNoEvent() {
        for (q in listOf("remember that my tpin is 123456", "remember my kite login is hunter2", "remember my api-key is sk-abc123", "remember my pwd is hunter2",
                "remember that my backup passphrase is tiger lily", "remember the event RBI policy on friday"))
            assertNull(Memory.toKeep(q), q)
        assertEquals("close all positions at 3", Memory.toKeep("remember: close all positions at 3"))
    }

    @Test fun payoffOddsVixMomentumEdges() {
        assertEquals(2.0, Payoff.asked("payoff of 25000 ce at 25200 for 2 lots")?.paid ?: 2.0, "\"for 2 lots\" is no price")
        assertNull(Payoff.asked("payoff of 25000 ce at 25200 for 2 lots")?.paid)
        val b = Payoff.asked("52000 ce worth bought at 1200 if banknifty is at 52500 at expiry")!!
        assertEquals(52_500.0, b.at); assertEquals(1_200.0, b.paid)
        assertNull(Odds.asked("chances nifty closes above 26000 this week"))
        assertFalse(VixRank.asked("what was the vix high today"))
        assertTrue(VixRank.asked("is vix too high"))
        assertFalse(Momentum.asked("is the premium too high"))
        assertTrue(Momentum.asked("is nifty too high"))
        assertEquals(50.0, Momentum.rsi(List(30) { 100.0 }))
        assertNull(Chat.smallTalk("never mind", 0)?.takeIf { it.startsWith("Good night") })
    }
}

class CommandPhrasingTest {
    @Test fun everydayCommandPhrasings() {
        fun k(s: String) = Commands.parse(s)?.kind
        for (q in listOf("close the nifty position", "close my nifty call", "exit 24500 ce", "sell my nifty position", "book profit in nifty", "book profit"))
            assertEquals(Command.Kind.CLOSE_ONE, k(q), q)
        assertEquals("nifty call", Commands.parse("close my nifty call")?.target)
        assertNull(k("sell nifty 24500 ce"), "a new sell is an order, never a close")
        assertEquals(Command.Kind.CLOSE_ALL, k("close all positions"))
        for (q in listOf("remind me when nifty hits 25000", "set alarm nifty 24500", "nifty 24500 pe alert lagao", "ping me when nifty touches 24400"))
            assertEquals(Command.Kind.ALARM_ADD, k(q), q)
        assertEquals(25_000.0, Commands.parse("remind me when nifty hits 25000")?.level)
        assertEquals("all", Commands.parse("remove all alarms")?.target)
        for (q in listOf("stop loss hit hua kya", "stop it", "stop the voice", "stop jarvis", "turn off notifications")) assertNull(k(q), q)
        assertEquals(Command.Kind.STOP_ONE, k("stop strategy 2"))
        assertEquals(Command.Kind.SET_LIMIT, k(Hinglish.normalize("max lots 5 kar do")))
        assertEquals(Command.Kind.TARGET_SET, k("target 3000 today"))
    }
}

class OptionQuoteTest {
    @Test fun aQuoteIsRead() {
        assertEquals(OptionQuote.Ask(24_500.0, true), OptionQuote.asked("what is the premium of 24500 ce"))
        assertEquals(OptionQuote.Ask(52_000.0, false), OptionQuote.asked("banknifty 52000 put price"))
        assertEquals(OptionQuote.Ask(24_500.0, false), OptionQuote.asked("nifty 24500 pe"))
        assertNull(OptionQuote.asked("buy 1 lot nifty 24500 ce"))
        assertNull(OptionQuote.asked("what is a 24800 call worth if nifty is at 25000 at expiry"))
        assertNull(OptionQuote.asked("alert me when 24500 ce goes above 200"))
        val legs = listOf(24_400.0, 24_500.0, 24_600.0).map { k ->
            com.optionslab.engine.options.ChainRow(k, com.optionslab.engine.options.OptLeg("C$k", 120.0, bid = 119.5, ask = 120.5, oi = 150_000),
                com.optionslab.engine.options.OptLeg("P$k", 80.0)) }
        val chain = com.optionslab.engine.options.ChainSnapshot.of("NIFTY", java.time.LocalDate.of(2026, 10, 6), 24_550.0, 75, legs,
            java.time.ZonedDateTime.of(2026, 10, 1, 12, 0, 0, 0, java.time.ZoneId.of("Asia/Kolkata")))
        val q = OptionQuote.say(chain, OptionQuote.Ask(24_500.0, true), "Nifty")!!
        assertTrue(q.startsWith("Nifty 24,500 CE (expiry 2026-10-06): last 120.00, bid 119.50 / ask 120.50, open interest 150,000"), q)
        assertTrue("50.00 is intrinsic" in q && "70.00 is time value" in q, q)
        assertTrue(OptionQuote.say(chain, OptionQuote.Ask(24_600.0, true), "Nifty")!!.contains("out of the money"))
        assertTrue(OptionQuote.near(chain, OptionQuote.Ask(25_000.0, true)).contains("24,600"))
    }
}

class AuditGapsTest {
    @Test fun shorterWaysOfAsking() {
        assertEquals(30, Moves.asked("nifty last 30 minutes")?.minutes)
        assertEquals(LocalTime.of(9, 15), Moves.asked("banknifty since open")?.since)
        assertNull(Moves.asked("last 30 minutes"))
        assertEquals(LocalTime.of(11, 30), Lookback.time("nifty at 11:30"))
        assertNull(Lookback.time("alert me at 11:30"))
        assertTrue(ExpectedRange.asked("how much can nifty fall today"))
        assertEquals(true, Odds.asked("will nifty close above 25000")?.above)
        assertEquals(listOf(Market.SENSEX), Ask.parse("senseks today").markets)
    }
}

class WhatsNewTest {
    @Test fun whatsNewIsTheNews() {
        assertNull(Chat.smallTalk("what's new", 0))
        assertTrue(Topic.NEWS in Ask.parse("what's new").topics)
        assertNotNull(Chat.smallTalk("what's up", 0))
    }
}

class VixSpikeTest {
    @Test fun aSpikeIsToldOnTheWayUp() {
        val d = java.time.LocalDate.of(2026, 10, 1)
        val v = Snapshot(Market.VIX, d.atTime(11, 0), true, 15.4, 14.0, 14.0, 15.5, 14.0, null, null, emptyList(), null, null, emptyList(), emptyList(), emptyList())
        assertTrue(VixSpike.alert(v, 5.0)!!.contains("up +10.00% today at 15.40"))
        assertNull(VixSpike.alert(v, 12.0), "already above: told before")
        assertNull(VixSpike.alert(v.copy(price = 14.5), 2.0))
    }
}

class LevelInfoTest {
    @Test fun whatIsAtAPrice() {
        assertEquals(24_500.0, LevelInfo.asked("what's at 24500 on nifty"))
        assertEquals(25_000.0, LevelInfo.asked("why is 25000 important"))
        assertNull(LevelInfo.asked("is 25000 a call"), "no level word")
        assertNull(LevelInfo.asked("how is nifty"))
        val d = java.time.LocalDate.of(2026, 10, 1)
        val snap = Snapshot(Market.NIFTY, d.atTime(12, 0), true, 24_420.0, 24_480.0, 24_450.0, 24_510.0, 24_400.0, null, null, emptyList(), null, null,
            listOf(Level("yesterday's high", 24_505.0)), listOf(Level("yesterday's low", 24_300.0)), emptyList())
        val t = LevelInfo.say(snap, 24_500.0)!!
        assertTrue(t.startsWith("24,500.00 is 80.00 points above Nifty at 24,420.00."), t)
        assertTrue("yesterday's high (24,505.00)" in t && "today's high (24,510.00)" in t && "a round 500" in t, t)
    }
}

class BigPictureTest {
    @Test fun theDailyTrend() {
        val d0 = java.time.LocalDate.of(2026, 7, 1)
        val bars = (0 until 60).map { i -> val c = 24_000.0 + i * 10; Candle(d0.plusDays(i.toLong()).atTime(15, 29), c, c + 5, c - 5, c) }
        val s = BigPicture.say(Market.NIFTY, bars)!!
        assertTrue(s.startsWith("Nifty at 24,590.00 is above both averages, with the 20-day over the 50-day: an uptrend."), s)
        assertTrue(BigPicture.asked("what's the bigger picture on nifty"))
        assertTrue(BigPicture.asked("is nifty above its 20 day moving average"))
        assertNull(BigPicture.say(Market.NIFTY, bars.take(10)))
    }
}

class SinceLastTest {
    @Test fun theChangeSinceLastAsked() {
        assertTrue(SinceLast.asked("what changed"))
        assertTrue(SinceLast.asked("Jarvis, what's changed since I last asked"))
        assertFalse(SinceLast.asked("what changed in the settings"))
        val d = java.time.LocalDate.of(2026, 10, 1)
        val now = Snapshot(Market.NIFTY, d.atTime(12, 30), true, 24_060.0, 24_000.0, 24_000.0, 24_100.0, 23_990.0, null, null, emptyList(), null, null, emptyList(), emptyList(), emptyList())
        assertEquals("Since you asked 30 minutes ago, Nifty is up 60.00 points: 24,000.00 then, 24,060.00 now (+0.25%).", SinceLast.say(Market.NIFTY, 24_000.0, d.atTime(12, 0), now))
    }
}

class TogetherTest {
    @Test fun inStepOrNot() {
        assertEquals(1.0, Together.corr(listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0), listOf(2.0, 4.0, 6.0, 8.0, 10.0, 12.0))!!, 1e-9)
        assertEquals(-1.0, Together.corr(listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0), listOf(6.0, 5.0, 4.0, 3.0, 2.0, 1.0))!!, 1e-9)
        assertTrue(Together.asked("is banknifty moving with nifty today"))
        assertFalse(Together.asked("is nifty moving"))
        val d = java.time.LocalDate.of(2026, 10, 1)
        val r = java.util.Random(7)
        var p = 24_000.0
        val a = (0 until 120).map { i -> p += r.nextGaussian() * 5; Candle(d.atTime(9, 15).plusMinutes(i.toLong()), p, p, p, p) }
        val b = a.map { it.copy(o = it.o * 2.1, h = it.h * 2.1, l = it.l * 2.1, c = it.c * 2.1) }
        assertTrue(Together.say(Market.NIFTY, a, Market.BANKNIFTY, b)!!.contains("moving closely together"))
    }
}

class RealisedTest {
    @Test fun realisedAgainstImplied() {
        val d = java.time.LocalDate.of(2026, 10, 1)
        val flat = (0 until 120).map { i -> val p = 24_000.0 + (i % 2); Candle(d.atTime(9, 15).plusMinutes(i.toLong()), p, p, p, p) }
        val rv = Realised.annualised(flat)!!
        assertTrue(rv < 5.0, "$rv")
        assertTrue(Realised.say(Market.NIFTY, flat, 15.0)!!.contains("premiums look dear for buyers"))
        assertTrue(Realised.asked("are options expensive today"))
        assertTrue(Realised.asked("what is the realised volatility"))
        assertFalse(Realised.asked("how is nifty"))
    }
}

class ReviewOTest {
    @Test fun questionsAndNewOrdersStayWhatTheyAre() {
        fun k(s: String) = Commands.parse(Hinglish.normalize(s))?.kind
        for (q in listOf("nifty 24500 ce ka premium batana", "nifty 25000 ke upar jayega ya nahi batana", "alert me at 10 30 if nifty goes above 25000x",
                "remind me at 10 30 to check nifty", "tell me where nifty was at 11 30", "tell me nifty price at 11:30"))
            assertTrue(k(q) != Command.Kind.ALARM_ADD || Commands.parse(Hinglish.normalize(q))?.level !in listOf(10.0, 11.0, 24500.0), q)
        assertNull(k("nifty 24500 ce ka premium batana"))
        assertEquals(25_000.0, Commands.parse("alert me at 10 30 if nifty goes above 25000")?.level)
        assertNull(k("sell the nifty 24500 ce 2 lots"))
        assertNull(k("sell the 24500 call"))
        assertEquals(Command.Kind.CLOSE_ONE, k("sell my nifty position"))
        assertNull(k("nifty target 25000 today"))
        assertEquals(Command.Kind.TARGET_SET, k("target 3000 today"))
        assertEquals(Command.Kind.ALARM_ADD, k("nifty 24500 pe alert set kar do"))
        assertEquals(Command.Kind.ALARM_ADD, k("nifty 25000 cross kare to batana"))
        for (q in listOf("stop when nifty hits 25000", "stop sending me news", "stop the music")) assertNull(k(q), q)
    }

    @Test fun oddsLookbackLevelEdges() {
        assertNotNull(Odds.asked("what are the chances nifty goes above 25000"))
        assertNull(Odds.asked("will nifty be above 25000 at 2 pm"))
        assertNotNull(Odds.asked("will nifty close above 25000"))
        assertEquals(LocalTime.of(11, 30), Lookback.time("Nifty at 11:30?"))
        assertEquals(24_500.0, LevelInfo.asked("what is at 24500 on nifty"))
    }
}

class RoutingAuditTest {
    private fun k(s: String) = Commands.parse(s)?.kind

    @Test fun aCloseWithLotsIsNeverANewSell() {
        for (q in listOf("square off 1 lot nifty 24500 ce", "square off 2 lots of my nifty 24500 ce", "sell 1 lot of my nifty 24500 call", "sell my 1 lot nifty 24500 ce")) {
            assertNull(Ask.parse(q).order, q)
            assertEquals(Command.Kind.CLOSE_ONE, k(q), q)
            assertTrue(Commands.parse(q)?.lots in setOf(1, 2), "a part close is marked, and the app refuses it: $q")
        }
        assertNull(Commands.parse("close the nifty 24500 ce position")?.lots)
        assertEquals(Command.Kind.CLOSE_ALL, k("sell everything"))
        assertEquals(Command.Kind.CLOSE_ALL, k("sell all"))
        // A new sell is still a new sell.
        assertTrue(Ask.parse("sell 1 lot nifty 24500 ce").order != null)
    }

    @Test fun questionsAndOtherWordsAreNotCommands() {
        assertNull(k("will you go live"))
        assertEquals(Command.Kind.CLOSE_ONE, k(Hinglish.normalize("mera nifty position band karo")))
        for (q in listOf("start the timer", "start recording", "run the backtest")) assertTrue(k(q) != Command.Kind.START_ONE, q)
        assertTrue(OptionQuote.asked(Hinglish.normalize("24500 ce kitne ka hai")) != null)
    }
}

class RoutingAuditTwoTest {
    @Test fun alarmsAndHindiWordsAreHeard() {
        val a = Commands.parse("tell me when nifty crosses 25000")!!
        assertEquals(Command.Kind.ALARM_ADD, a.kind); assertEquals(25000.0, a.level)
        assertEquals(Command.Kind.ALARM_ADD, Commands.parse("let me know when nifty is above 25000")?.kind)
        assertNull(Commands.parse("tell me when the market opens"))
        assertEquals(Command.Kind.ALARM_REMOVE, Commands.parse(Hinglish.normalize("alarm hata do"))?.kind)
        assertTrue(Hinglish.normalize("aaj kitna kamaya").contains("did i make"))
        assertTrue(Briefing.asked("morning briefing"))
    }
}

class TouchOddsTest {
    @Test fun theOddsOfReachingALevelAreAboutTwiceTheClose() {
        val a = Odds.asked("will nifty cross 24,200 today")!!
        assertTrue(a.touch); assertEquals(24_200.0, a.level)
        assertTrue(Odds.asked("odds of banknifty touching 51000")!!.touch)
        assertNull(Odds.asked("will it cross 24200"), "no market named")
        assertNull(Odds.asked("will nifty cross 25000 by expiry"))
        assertNull(Odds.asked("will nifty cross 25000 tomorrow"))
        assertEquals(false, Odds.asked("will nifty close above 24200")?.touch)
        val d = java.time.LocalDate.of(2026, 10, 1)
        val snap = Snapshot(Market.NIFTY, d.atTime(12, 0), true, 24_000.0, 23_900.0, 24_000.0, 24_000.0, 24_000.0, null, null, emptyList(), null, null, emptyList(), emptyList(), emptyList())
        val close = Odds.say(snap, 16.0, Odds.Ask(true, 24_200.0), d.atTime(12, 0))!!
        val touch = Odds.say(snap, 16.0, a, d.atTime(12, 0))!!
        fun pct(s: String) = Regex("about a (\\d+)% chance").find(s)!!.groupValues[1].toInt()
        assertTrue(touch.contains("trades up to 24,200.00 at some point today"), touch)
        assertTrue(pct(touch) in (2 * pct(close) - 2)..(2 * pct(close) + 2), "$touch / $close")
        assertTrue(Odds.say(snap, 16.0, Odds.Ask(true, 24_005.0, touch = true), d.atTime(12, 0))!!.contains("already"))
        // Never an alarm or an order.
        assertNull(Commands.parse("will nifty cross 24200 today"))
        assertNull(Ask.parse("will nifty cross 24200 today").order)
    }
}

class ReviewPTest {
    @Test fun alarmsByDirectionWordsAndHinglishLevels() {
        for (q in listOf("tell me when nifty drops below 24000", "tell me when nifty falls under 24000", "tell me when nifty goes to 25000", "tell me when nifty drops to 24000"))
            assertEquals(Command.Kind.ALARM_ADD, Commands.parse(q)?.kind, q)
        assertEquals(Command.Kind.ALARM_ADD, Commands.parse("tell me when banknifty falls 1 percent from here")?.kind)
        val h = Commands.parse(Hinglish.normalize("nifty 25000 pe pahunche to batana"))!!
        assertEquals(Command.Kind.ALARM_ADD, h.kind); assertEquals(25000.0, h.level)
        assertNull(Commands.parse("tell me when the market opens"))
        // "band karo" is a close only for a position or trade named just before it.
        assertTrue(Commands.parse(Hinglish.normalize("aaj trade band karo"))?.kind != Command.Kind.CLOSE_ONE)
        assertTrue(Commands.parse(Hinglish.normalize("position size band karo"))?.kind != Command.Kind.CLOSE_ONE)
    }

    @Test fun touchOddsKnowTheDaysRangeAndAccountWordsAreNotQuotes() {
        val d = java.time.LocalDate.of(2026, 10, 1)
        val snap = Snapshot(Market.NIFTY, d.atTime(12, 0), true, 24_950.0, 24_800.0, 24_850.0, 25_020.0, 24_840.0, null, null, emptyList(), null, null, emptyList(), emptyList(), emptyList())
        assertTrue(Odds.say(snap, 16.0, Odds.asked("will nifty cross 25000 today")!!, d.atTime(12, 0))!!.contains("already traded at 25,000.00"))
        val late = Odds.say(snap, 16.0, Odds.Ask(true, 26_000.0, touch = true), d.atTime(15, 29))!!
        assertFalse(late.contains("about a under") || late.contains("about a over"), late)
        for (q in listOf("how much loss on nifty 24500 ce", "how much margin for nifty 24500 ce", "how much will i make if nifty 24500 ce doubles"))
            assertNull(OptionQuote.asked(q), q)
        assertNotNull(OptionQuote.asked(Hinglish.normalize("24500 ce kitne ka hai")))
    }
}

class AlarmByMarketTest {
    @Test fun aMarketsAlarmsAreRemovedByName() {
        val a = Commands.parse("remove the nifty alarm")!!
        assertEquals(Command.Kind.ALARM_REMOVE, a.kind); assertEquals(Market.NIFTY, a.market); assertNull(a.target)
        val b = Commands.parse("clear all banknifty alerts")!!
        assertEquals(Command.Kind.ALARM_REMOVE, b.kind); assertEquals(Market.BANKNIFTY, b.market); assertEquals("all", b.target)
        assertEquals(Market.SENSEX, Commands.parse(Hinglish.normalize("sensex alarm hata do"))?.market)
        // Unchanged: no market named.
        assertNull(Commands.parse("remove all alarms")?.market)
        assertEquals("all", Commands.parse("remove all alarms")?.target)
        assertEquals(2, Commands.parse("delete alarm 2")?.number)
        // Never a removal of something else.
        assertTrue(Commands.parse("cancel the nifty order")?.kind != Command.Kind.ALARM_REMOVE)
        assertTrue(Commands.parse("alert me when nifty goes above 25000")?.kind == Command.Kind.ALARM_ADD)
    }
}

class HindiOddsTest {
    @Test fun hindiLevelQuestionsGetTheOdds() {
        for (q in listOf("kya nifty aaj 25000 cross karega", "nifty 25000 pahunchega kya", "kya banknifty 51000 tak jayega", "nifty aaj 25000 touch karega")) {
            val t = Ask.parse(q).text
            assertEquals(true, Odds.asked(t)?.touch, "$q -> $t")
            assertNull(Commands.parse(t), q); assertNull(Ask.parse(q).order, q)
        }
        assertEquals(false, Odds.asked(Ask.parse("nifty 25000 ke upar band hoga kya").text)?.touch)
        assertEquals(false, Odds.asked(Ask.parse("nifty 24000 ke neeche band hoga kya").text)?.above)
        // An alarm in Hindi stays an alarm.
        assertEquals(Command.Kind.ALARM_ADD, Commands.parse(Hinglish.normalize("nifty 25000 pe alert lagao"))?.kind)
    }
}

class ReviewQTest {
    @Test fun removalByMarketIsOnlyWhereTheMarketIsInTheRemoval() {
        fun k(s: String) = Commands.parse(s)
        assertEquals(Command.Kind.CANCEL_ALL, k("cancel all orders and alerts on nifty")?.kind)
        for (q in listOf("cancel my nifty order and alert me above 25000", "cancel nifty order and set alert at 25000",
                "alert me when nifty goes above 25000 and cancel the old alert", "remove nifty stop loss and alert me when it falls below 24000"))
            assertEquals(Command.Kind.ALARM_ADD, k(q)?.kind, q)
        for (q in listOf("cancel the nifty order alert", "clear the nifty strategy alerts")) assertTrue(k(q)?.kind != Command.Kind.ALARM_REMOVE, q)
        assertNull(k("remove all alarms and set one on nifty above 25000")?.market)
        assertNull(k("remove all my alerts on banknifty and nifty")?.market)
        assertEquals(Market.NIFTY, k("remove the nifty alarm")?.market)
        assertEquals(Market.SENSEX, k("remove alarms on sensex")?.market)
        assertEquals("last", k("remove the last nifty alarm")?.target)
        assertEquals(Market.NIFTY, k("remove the last nifty alarm")?.market)
        // Hindi: an alarm stays an alarm.
        assertEquals(Command.Kind.ALARM_ADD, k(Hinglish.normalize("alert lagao nifty 25000 cross karega"))?.kind)
        assertEquals(true, Odds.asked(Ask.parse("nifty 25000 pahunchega kya").text)?.touch)
    }
}

class OrderIdTest {
    @Test fun ordersAreSaidWithTheirIds() {
        val l = AppFacts.orders("Paper", listOf(AppFacts.OrderLine("09:31", "NIFTY25O0724500CE", "BUY", 75, "COMPLETE", 120.5, "Jarvis solo · entry", id = "PAPER-00001234")), byWho = false)
        assertTrue(l.any { it.contains("(Jarvis solo · entry), order #00001234.") }, l.toString())
    }
}
