package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MarketMemoryTest {
    /** Monday 5 October 2026 and the weekdays before it. */
    private val today = LocalDate.of(2026, 10, 5)
    private fun weekdays(k: Int, upTo: LocalDate = today.minusDays(1)) =
        generateSequence(upTo) { it.minusDays(1) }.filter { it.dayOfWeek.value <= 5 }.take(k).toList().reversed()

    /** A session from its open, high, low and close (four candles: the open, the high/low, 14:30 and the close). */
    private fun session(d: LocalDate, o: Double, h: Double, l: Double, c: Double, at1430: Double = (o + c) / 2, end: Int = 29): List<Candle> = listOf(
        Candle(d.atTime(9, 15), o, maxOf(o, o), minOf(o, o), o),
        Candle(d.atTime(11, 0), o, h, l, (h + l) / 2),
        Candle(d.atTime(14, 30), at1430, at1430, at1430, at1430),
        Candle(d.atTime(15, end), c, c, c, c),
    )

    /** A quiet session: opens at [p], a 100-point range around it, closes at [p]. */
    private fun quiet(d: LocalDate, p: Double) = session(d, p, p + 50, p - 50, p)

    /** Fifteen quiet sessions at 24,000 before [days]' notable ones. */
    private val past = weekdays(25)

    private fun nifty(vararg special: Pair<Int, (LocalDate) -> List<Candle>>): List<Candle> {
        val map = special.toMap()
        return past.flatMapIndexed { i, d -> map[i]?.invoke(d) ?: quiet(d, 24_000.0) }
    }

    @Test fun aDaysShapeIsRead() {
        val bars = nifty(
            // A gap down of 1% that trended down on a wide range.
            20 to { d -> session(d, 23_760.0, 23_780.0, 23_400.0, 23_420.0) },
        )
        val days = MarketMemory.days(Market.NIFTY, bars)
        assertEquals(25, days.size)
        val d = days[20]
        assertEquals(-1.0, d.gapPct!!, 1e-9)
        assertEquals(-1, d.trend)
        assertTrue(d.wide, "380 points against a usual 100")
        assertTrue(d.bigGap); assertTrue(d.bigMove)
        assertEquals(0, days[10].trend); assertFalse(days[10].wide)
        assertNull(days[2].usualRange, "too early on the phone to have a usual range")
    }

    @Test fun aSessionCutShortOrStillTradingIsNotJudged() {
        val bars = past.take(10).flatMap { quiet(it, 24_000.0) } + session(past[10], 24_000.0, 24_400.0, 23_990.0, 24_390.0, end = 0).dropLast(1)
        val days = MarketMemory.days(Market.NIFTY, bars)
        assertFalse(days.last().whole); assertNull(days.last().rangeRatio); assertEquals(0, days.last().trend)
        val live = MarketMemory.days(Market.NIFTY, past.take(11).flatMap { quiet(it, 24_000.0) }, today = past[10], live = true)
        assertFalse(live.last().whole)
    }

    // ---- reading the question ----------------------------------------------------------------------------------

    @Test fun theQuestionsAreRead() {
        val g = MarketMemory.asked("When did Nifty last gap down this much?")!!
        assertEquals(MarketMemory.Kind.LAST, g.kind); assertEquals(MarketMemory.Sort.GAP_DOWN, g.sort); assertTrue(g.like)
        val v = MarketMemory.asked("Last time VIX jumped like this, what did the day look like?")!!
        assertEquals(MarketMemory.Kind.LAST, v.kind); assertEquals(MarketMemory.Sort.VIX_SPIKE, v.sort); assertTrue(v.like)
        val c = MarketMemory.asked("how many trend days this month?")!!
        assertEquals(MarketMemory.Kind.COUNT, c.kind); assertEquals(MarketMemory.Sort.TREND, c.sort); assertEquals(MarketMemory.Span.MONTH, c.span)
        val e = MarketMemory.asked("what happened the last 3 expiries?")!!
        assertEquals(MarketMemory.Kind.EXPIRIES, e.kind); assertEquals(3, e.count)
        assertEquals(1, MarketMemory.asked("how did the last expiry go")!!.count)
        assertEquals(3, MarketMemory.asked("last few expiries on banknifty")!!.count)
        assertEquals(2, MarketMemory.asked("the last two expiry days")!!.count)
        val p = MarketMemory.asked("when did nifty last gap up 1%")!!
        assertEquals(MarketMemory.Sort.GAP_UP, p.sort); assertEquals(1.0, p.pct)
        assertEquals(200.0, MarketMemory.asked("last time banknifty fell 200 points in a day")!!.points)
        assertEquals(MarketMemory.Sort.BIG_DOWN, MarketMemory.asked("when did nifty last fall 2% in a day")!!.sort)
        assertEquals(MarketMemory.Sort.WIDE, MarketMemory.asked("when was the last wide range day")!!.sort)
        val w = MarketMemory.asked("how many gap downs in the last 20 sessions")!!
        assertEquals(MarketMemory.Span.SESSIONS, w.span); assertEquals(20, w.count); assertEquals(MarketMemory.Sort.GAP_DOWN, w.sort)
        assertEquals(MarketMemory.Span.LAST_MONTH, MarketMemory.asked("how many vix spikes last month")!!.span)
        assertEquals(MarketMemory.Kind.NOTABLE, MarketMemory.asked("what big days do you remember?")!!.kind)
        assertEquals(MarketMemory.Kind.NOTABLE, MarketMemory.asked("Jarvis, notable sessions on nifty")!!.kind)
        assertEquals(MarketMemory.Kind.COUNT, MarketMemory.asked("how many times did nifty open lower this week")!!.kind)
    }

    @Test fun forecastsAdviceAndBossOwnAreNotThis() {
        for (q in listOf(
            "will nifty gap down tomorrow", "what usually happens after a gap down", "should I sell after a trend day",
            "how did I do on the last expiry", "my trades on the last 3 expiries", "what does trend day mean",
            "when will vix spike again", "chances of a trend day today", "how is expiry going", "how is today's expiry going",
            "last time gold fell 2% in a day", "when did nifty last fall", "how many days up this month",
        )) assertNull(MarketMemory.asked(q), q)
    }

    /** The earlier branches keep their own questions, and these questions are not theirs. */
    @Test fun theEarlierBranchesKeepTheirQuestions() {
        val theirs = listOf(
            "what happened in the market today", "what's different about today", "is today an unusual day",
            "where was nifty at 11", "yesterday's high on nifty", "previous day's close",
            "how did nifty do this week", "how did banknifty do last week", "how much is nifty up this month",
            "how many days in a row has nifty gone up", "banknifty losing streak", "is nifty at a new high",
            "did nifty gap up today", "has banknifty filled its gap", "how much did nifty fall in the last hour",
            "nifty since the open", "what changed since last time", "is vix high", "explain this move",
        )
        for (q in theirs) assertNull(MarketMemory.asked(q), q)
        val mine = listOf(
            "when did nifty last gap down this much", "last time vix jumped like this, what did the day look like",
            "how many trend days this month", "what happened the last 3 expiries", "how many gap down days this month",
            "how many big down days last week", "when was the last trend day on banknifty", "what big days do you remember",
        )
        for (q in mine) {
            assertNotNull(MarketMemory.asked(q), q)
            assertNull(MarketStory.asked(q), q); assertNull(SharpMove.asked(q), q); assertFalse(ExpiryDay.asked(q), q)
            assertNull(Lookback.time(q), q); assertFalse(Lookback.prevAsked(q), q)
            assertFalse(Outlook.asked(q), q)
            assertNull(Glossary.explain(q), q)
            assertFalse(SinceLast.asked(q), q)
            val parsed = Ask.parse(q)
            for (t in listOf(Topic.ACCOUNT, Topic.ADVICE, Topic.BACKTEST, Topic.TRADE_CHECK, Topic.SUGGEST)) assertFalse(t in parsed.topics, "$q: $t")
            assertNull(parsed.order, q); assertNull(parsed.command, q)
        }
    }

    // ---- the answers -----------------------------------------------------------------------------------------

    @Test fun theLastGapDownAsBigAsToday() {
        val bars = nifty(
            12 to { d -> session(d, 23_760.0, 23_800.0, 23_700.0, 23_750.0) },      // a 1% gap down
            18 to { d -> session(d, 23_880.0, 23_950.0, 23_850.0, 23_900.0) },      // 0.5%: smaller than today's
        ) + session(today, 23_808.0, 23_850.0, 23_780.0, 23_800.0)                  // today: -0.8%
        val days = MarketMemory.days(Market.NIFTY, bars, today = today, live = true)
        val s = MarketMemory.answer(MarketMemory.asked("when did nifty last gap down this much")!!, days, today)!!
        assertTrue(s.startsWith("The last session with a gap down as big as today's (0.80%) was ${MarketMemory.date(past[12])} (13 sessions ago): a gap down of 1.00%."), s)
        assertTrue("Nifty opened 1.00% lower at 23,760.00 (a gap of -240.00 points)" in s, s)
        assertTrue("That is from the 26 sessions of Nifty on the phone (since ${past.first()})." in s, s)
        assertTrue(s.endsWith(MarketMemory.NOT_A_PROMISE), s)
    }

    @Test fun nothingAsBigSaysTheBiggest() {
        val bars = nifty(18 to { d -> session(d, 23_880.0, 23_950.0, 23_850.0, 23_900.0) }) + session(today, 23_500.0, 23_550.0, 23_450.0, 23_500.0)
        val days = MarketMemory.days(Market.NIFTY, bars, today = today, live = true)
        val s = MarketMemory.answer(MarketMemory.asked("when did nifty last gap down this much")!!, days, today)!!
        assertTrue(s.startsWith("Nothing like that in the 25 sessions of Nifty on the phone (since ${past.first()}), Boss: no session with a gap down as big as today's (2.08%)."), s)
        assertTrue("The biggest there was a gap down of 0.50% on ${MarketMemory.date(past[18])}." in s, s)
    }

    @Test fun lastTimeVixJumpedLikeThis() {
        val vix = past.flatMapIndexed { i, d -> quiet(d, if (i == 14) 15.0 else if (i == 15) 15.0 else 13.0).map { it.copy(o = it.o / 1, h = it.c, l = it.c) } } +
            quiet(today, 14.6).map { it.copy(h = it.c, l = it.c) }
        // Session 14: VIX 13 -> 15 (+15.4%); today 13 -> 14.6 (+12.3%).
        val bars = nifty(14 to { d -> session(d, 23_900.0, 23_920.0, 23_500.0, 23_520.0) }) + session(today, 24_000.0, 24_050.0, 23_950.0, 24_000.0)
        val days = MarketMemory.days(Market.NIFTY, bars, vix = vix, today = today, live = true)
        val s = MarketMemory.answer(MarketMemory.asked("last time vix jumped like this, what did the day look like?")!!, days, today)!!
        assertTrue(s.startsWith("The last session with an India VIX jump as big as today's (12.31%) was ${MarketMemory.date(past[14])} (11 sessions ago): India VIX up 15.38%."), s)
        assertTrue("a trend day down" in s, s)
        assertTrue("India VIX +15.38% to 15.00." in s, s)
    }

    @Test fun noVixCandlesIsSaid() {
        val days = MarketMemory.days(Market.NIFTY, nifty(), today = today)
        val s = MarketMemory.answer(MarketMemory.asked("when did vix last spike")!!, days, today)!!
        assertTrue(s.startsWith("Nothing like that"), s)
        assertTrue("I have no India VIX candles for those sessions." in s, s)
    }

    @Test fun countingTrendDaysThisMonth() {
        // October 2026 begins on a Thursday: the 1st and 2nd are on the phone, then today (still trading).
        val oct1 = past.indexOf(LocalDate.of(2026, 10, 1)); val oct2 = past.indexOf(LocalDate.of(2026, 10, 2))
        val sept = past.indexOf(LocalDate.of(2026, 9, 30))
        val bars = nifty(
            oct1 to { d -> session(d, 24_000.0, 24_310.0, 23_990.0, 24_300.0) },    // up
            oct2 to { d -> quiet(d, 24_300.0) },
            sept to { d -> session(d, 24_000.0, 24_010.0, 23_700.0, 23_710.0) },   // down, last month
        ) + session(today, 24_300.0, 24_700.0, 24_290.0, 24_690.0)
        val days = MarketMemory.days(Market.NIFTY, bars, today = today, live = true)
        val s = MarketMemory.answer(MarketMemory.asked("how many trend days this month?")!!, days, today)!!
        assertEquals("Nifty had 1 trend day this month (of 3 sessions): 2026-10-01 (up). " +
            "1 of those sessions can't be judged (still trading, cut short, or too early on the phone to have a usual range). " + MarketMemory.NOT_A_PROMISE, s)
        val l = MarketMemory.answer(MarketMemory.asked("how many trend days last month")!!, days, today)!!
        assertTrue(l.startsWith("Nifty had 1 trend day last month (of "), l)
        assertTrue("2026-09-30 (down)" in l, l)
        assertFalse("only start" in l, "the phone has all of September: $l")
        // A memory that starts mid-month says so.
        val short = MarketMemory.days(Market.NIFTY, past.takeLast(8).flatMap { quiet(it, 24_000.0) }, today = today)
        val g = MarketMemory.answer(MarketMemory.asked("how many gap downs last month")!!, short, today)!!
        assertTrue(g.startsWith("Nifty had 0 gap downs of 0.5% or more last month (of 6 sessions)."), g)
        assertTrue("The phone's Nifty sessions only start on ${past[17]}." in g, g)
    }

    @Test fun countingWithASizeAndSessions() {
        val bars = nifty(
            20 to { d -> session(d, 24_000.0, 24_010.0, 23_500.0, 23_520.0) },     // -2.0%
            22 to { d -> session(d, 24_000.0, 24_010.0, 23_700.0, 23_750.0) },     // -1.04%
        )
        val days = MarketMemory.days(Market.NIFTY, bars, today = today)
        val s = MarketMemory.answer(MarketMemory.asked("how many times did nifty fall 1.5% in the last 10 sessions")!!, days, today)!!
        assertTrue(s.startsWith("Nifty had 1 session with a fall of 1.50% or more in the last 10 sessions (of 10 sessions): ${past[20]} (-2.00%)."), s)
        val all = MarketMemory.answer(MarketMemory.asked("how many big down days")!!, days, today)!!
        assertTrue(all.startsWith("Nifty had 2 days down 1.0% or more in the 25 sessions of Nifty on the phone (since ${past.first()}):"), all)
    }

    @Test fun theLastThreeExpiries() {
        val ex = setOf(past[24], past[19], past[14], past[9])
        val bars = nifty(24 to { d -> session(d, 24_000.0, 24_200.0, 23_950.0, 24_150.0, at1430 = 24_050.0) })
        val days = MarketMemory.days(Market.NIFTY, bars, expiries = ex, today = today)
        val s = MarketMemory.answer(MarketMemory.asked("what happened the last 3 expiries?")!!, days, today)!!
        assertTrue(s.startsWith("Nifty's last 3 expiries, Boss: ${MarketMemory.date(past[24])}: closed +0.63% at 24,150.00, range 250.00 points (2.5 times usual), the last hour from 14:30 +100.00 points, a trend day up."), s)
        assertTrue(MarketMemory.date(past[14]) in s && MarketMemory.date(past[9]) !in s, s)
        val more = MarketMemory.answer(MarketMemory.Ask(MarketMemory.Kind.EXPIRIES, MarketMemory.Sort.EXPIRY, count = 8), days, today)!!
        assertTrue(more.startsWith("I have only 4 Nifty expiry days on the phone, Boss."), more)
        val none = MarketMemory.answer(MarketMemory.asked("last few expiries")!!, MarketMemory.days(Market.NIFTY, bars, today = today), today)!!
        assertTrue(none.startsWith("I don't have any Nifty expiry day I'm sure of in the 25 sessions"), none)
    }

    @Test fun notableSessionsAndAShortMemory() {
        val few = weekdays(8)
        val bars = few.flatMapIndexed { i, d -> if (i == 7) session(d, 23_760.0, 23_780.0, 23_400.0, 23_420.0) else quiet(d, 24_000.0) }
        val days = MarketMemory.days(Market.NIFTY, bars, today = today)
        val s = MarketMemory.answer(MarketMemory.asked("what big days do you remember")!!, days, today)!!
        assertTrue(s.startsWith("From the 8 sessions of Nifty on the phone (since ${few.first()}) - a short memory, Boss: 1 big gap of 0.5% or more (latest -1.00% on ${few[7]}); 1 trend day"), s)
        assertTrue("The biggest: On ${MarketMemory.date(few[7])}" in s, s)
        assertTrue(s.endsWith(MarketMemory.NOT_A_PROMISE), s)
        val calm = MarketMemory.answer(MarketMemory.Ask(MarketMemory.Kind.NOTABLE), MarketMemory.days(Market.NIFTY, few.flatMap { quiet(it, 24_000.0) }), today)!!
        assertTrue(calm.startsWith("Nothing stands out in the 8 sessions"), calm)
    }

    @Test fun neverAForecastNorAdvice() {
        val bars = nifty(20 to { d -> session(d, 23_760.0, 23_780.0, 23_400.0, 23_420.0) }) + session(today, 23_808.0, 23_850.0, 23_780.0, 23_800.0)
        val days = MarketMemory.days(Market.NIFTY, bars, expiries = setOf(past[20]), today = today, live = true)
        val qs = listOf("when did nifty last gap down this much", "how many trend days this month", "what happened the last 3 expiries",
            "what big days do you remember", "when was the last wide range day", "how many gap ups in the last 20 sessions")
        for (q in qs) {
            val s = MarketMemory.answer(MarketMemory.asked(q)!!, days, today)!!
            assertFalse(Regex("(?i)\\b(will|should|buy|sell|expect|likely|tomorrow)\\b").containsMatchIn(s), "$q: $s")
            assertTrue(s.endsWith(MarketMemory.NOT_A_PROMISE), "$q: $s")
        }
    }
}
