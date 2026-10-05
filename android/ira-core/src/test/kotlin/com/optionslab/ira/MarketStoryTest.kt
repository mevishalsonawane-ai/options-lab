package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MarketStoryTest {
    /** Monday 5 October 2026; ten earlier sessions on the weekdays before it. */
    private val today = LocalDate.of(2026, 10, 5)
    private val past = generateSequence(today.minusDays(1)) { it.minusDays(1) }.filter { it.dayOfWeek.value <= 5 }.take(10).toList().reversed()

    /** A session of 1-minute candles from 09:15, the price at minute i given by [px]. */
    private fun session(d: LocalDate, minutes: Int = 375, px: (Int) -> Double): List<Candle> {
        var prev = px(0)
        return (0 until minutes).map { i ->
            val c = px(i); val o = if (i == 0) c else prev; prev = c
            Candle(d.atTime(9, 15).plusMinutes(i.toLong()), o, maxOf(o, c) + 0.5, minOf(o, c) - 0.5, c)
        }
    }

    /** Usual days: a 100-point swing around a base that rises 10 points a day. */
    private fun usual(base: Double = 24_000.0) = past.flatMapIndexed { k, d -> session(d) { i -> base + k * 10 + 50 * sin(2 * PI * i / 375) } }

    /** Straight lines between (minute, price) points. */
    private fun path(vararg p: Pair<Int, Double>): (Int) -> Double = { i ->
        val j = p.indexOfLast { it.first <= i }.coerceAtMost(p.size - 2)
        val (a, x) = p[j]; val (b, y) = p[j + 1]
        x + (y - x) * (i - a).toDouble() / (b - a)
    }

    private val nifty = usual()
    private val prevClose = nifty.last().c
    private val P = prevClose
    // A 0.5% gap up that fills at 10:15, a fall, a sharp 15 minutes from 13:15 and a rally into the close.
    private val niftyToday = session(today, px = path(0 to P * 1.005, 60 to P - 10, 150 to P - 150, 240 to P - 100, 255 to P + 20, 374 to P + 200))
    private val bank = usual(51_000.0)
    private val bankToday = session(today) { i -> bank.last().c - i * 0.5 }          // drifts down all day
    private val vixPast = past.flatMapIndexed { k, d -> session(d) { 13.0 + if (k % 2 == 0) 0.05 else -0.05 } }
    private val vixToday = session(today) { i -> 13.0 + i * 1.6 / 374 }

    private val bars = mapOf(Market.NIFTY to nifty + niftyToday, Market.BANKNIFTY to bank + bankToday, Market.VIX to vixPast + vixToday)
    private val evening = today.atTime(15, 40)

    /** 13:20 IST, inside the 13:15-13:30 move; and one at 11:00, outside it. */
    private val news = listOf(
        Headline("RBI keeps the repo rate unchanged", "l1", "Reuters", today.atTime(13, 20).toInstant(ZoneOffset.ofHoursMinutes(5, 30)), 0.3, emptyList()),
        Headline("Gold edges higher", "l2", "Mint", today.atTime(13, 22).toInstant(ZoneOffset.ofHoursMinutes(5, 30)), 0.1, listOf(Market.GOLD)),
        Headline("Monsoon update", "l3", "PTI", today.atTime(11, 0).toInstant(ZoneOffset.ofHoursMinutes(5, 30)), 0.0, emptyList()),
    )

    @Test fun theQuestionsItAnswers() {
        for (s in listOf("What happened today?", "what happened in the market today", "Jarvis, what happened in the stock market today?",
                "what all happened today", "how did the markets do today", "tell me what happened today boss"))
            assertEquals(MarketStory.Kind.STORY, MarketStory.asked(s), s)
        for (s in listOf("What's different about today?", "what is unusual about today", "is today an unusual day", "was today a normal day",
                "how is today different from a usual day", "anything unusual today", "today vs a usual day"))
            assertEquals(MarketStory.Kind.DIFFERENT, MarketStory.asked(s), s)
        // An index named, a why, the owner's day, "what changed since I last asked": other answers' questions.
        for (s in listOf("what happened to nifty today", "why did the market fall today", "how did my day go", "what's different since I last asked",
                "what happened", "recap", "how is the market", "what happened today in my account", "is banknifty unusual today",
                // "How was the market today" is the trade check's question, asked before this one.
                "how was the market today"))
            assertNull(MarketStory.asked(s), s)
    }

    @Test fun itTakesNoQuestionFromTheBranchesBeforeIt() {
        for (s in listOf("what happened today", "what happened in the market today", "how did the markets do today",
                "what's different about today", "is today an unusual day", "today vs a usual day")) {
            assertTrue(!DaySummary.asked(s) && !SelfWhy.asked(s) && !Outlook.asked(s) && !SinceLast.asked(s) && !Briefing.asked(s) &&
                !Reminder.asked(s) && !Lessons.asked(s) && !Vetting.asked(s) && !Goals.asked(s) && !Sources.asked(s) && !SelfCheck.asked(s), s)
            val p = Ask.parse(s)
            assertTrue(p.order == null && p.command == null, s)
            assertTrue(p.topics.none { it in setOf(Topic.ACCOUNT, Topic.COMMAND, Topic.BACKTEST, Topic.SUGGEST, Topic.TRADE_CHECK, Topic.ADVICE) }, "$s: ${p.topics}")
        }
    }

    @Test fun todayIsReadAgainstTheSessionsBefore() {
        val r = MarketStory.read(Market.NIFTY, nifty + niftyToday, today)!!
        assertEquals(10, r.past)
        assertEquals(0.5, r.gapPct!!, 0.001)
        assertEquals(today.atTime(10, 11), r.gapFilledAt)
        assertTrue(r.rangeRatio!! > 3, "a 350-point day against a 100-point usual: ${r.rangeRatio}")
        assertTrue(r.newHigh && r.newLow, "a 24,290 high and a 23,939 low: past every earlier session")
        assertNull(MarketStory.read(Market.NIFTY, nifty, today), "no session today on the phone")
    }

    @Test fun theDaysStoryAfterTheClose() {
        val s = MarketStory.story(bars, news, evening)!!
        assertTrue(s.startsWith("Today's market, Boss: Nifty closed at"), s)
        assertTrue("(+0.83%) from the previous close" in s, s)
        assertTrue("BankNifty -" in s, s)
        assertTrue("gap up of 0.50%, which filled at 10:" in s, s)
        assertTrue("The low of 23,938.66 came at 11:45 and the high of 24,289.66 at 15:29." in s, s)
        assertTrue("The biggest 15-minute move was +112.56 points (+0.47%) from 13:15 to 13:30." in s, s)
        // The headline out in that window (not gold's, not the 11:00 one), as timing only.
        assertTrue("\"RBI keeps the repo rate unchanged\" (Reuters, 13:20)" in s, s)
        assertTrue("Gold edges" !in s && "Monsoon" !in s, s)
        assertTrue("times its average of" in s && "wider than usual" in s, s)
        assertTrue("India VIX ended at 14.60, +12.74% on the day, a bigger change than its average" in s, s)
        // The lead's move is said once (not again as an unusual fact); BankNifty's, against its usual, is added.
        assertEquals(1, Regex("Nifty closed").findAll(s).count { s.substring(maxOf(0, it.range.first - 4), it.range.first) != "Bank" }, s)
        assertTrue("BankNifty closed -0.37% from the previous close" in s, s)
        assertTrue(Regex("\\b(because|due to|caused|will|should|buy|sell)\\b").find(s) == null, "facts only: $s")
    }

    @Test fun duringTheDayItIsSoFar() {
        val cut = bars.mapValues { (_, b) -> b.filter { it.t <= today.atTime(12, 0) } }
        val s = MarketStory.story(cut, emptyList(), today.atTime(12, 1))!!
        assertTrue(s.startsWith("The market so far, Boss: Nifty is at"), s)
        assertTrue(" so far is " in s, s)
        // The range is compared with the same 166 minutes of the earlier days, not their whole sessions.
        val r = MarketStory.read(Market.NIFTY, cut.getValue(Market.NIFTY), today)!!
        assertTrue(r.avgRange!! < 101, "${r.avgRange}")
    }

    @Test fun whatIsDifferentAboutToday() {
        val s = MarketStory.different(bars, evening)!!
        assertTrue(s.startsWith("What's different about today, Boss: "), s)
        assertTrue("Nifty closed +0.83% from the previous close" in s && "India VIX rose 12.74% to 14.60" in s, s)
        assertTrue(s.split(". ").size <= 5, "four facts at most: $s")
    }

    @Test fun anOrdinaryDayHasNothingToSay() {
        val quiet = session(today) { i -> prevClose - 10 + 50 * sin(2 * PI * i / 375) }
        val s = MarketStory.different(mapOf(Market.NIFTY to nifty + quiet), evening)!!
        assertTrue(s.startsWith("Nothing stands out today, Boss: ranges, moves and gaps are within their usual sizes over the last 10 sessions"), s)
        assertEquals("Nothing unusual stood out in the market today. Ask me \"what happened in the market today\" for the whole story, Boss.",
            MarketStory.wrapLine(mapOf(Market.NIFTY to nifty + quiet), evening))
    }

    @Test fun tooFewSessionsAreNotCompared() {
        val two = mapOf(Market.NIFTY to nifty.filter { it.t.toLocalDate() >= past[8] } + niftyToday)
        assertEquals("I have too few earlier sessions on the phone to say what a usual day looks like, Boss.", MarketStory.different(two, evening))
        assertNull(MarketStory.wrapLine(two, evening))
        assertNotNull(MarketStory.story(two, emptyList(), evening))
    }

    @Test fun theWrapUpLineLeadsWithWhatStoodOutMost() {
        val w = MarketStory.wrapLine(bars, evening)!!
        assertTrue(w.endsWith("Ask me \"what happened in the market today\" for the whole story, Boss."), w)
        assertTrue(w.startsWith("Nifty"), w)
        // Which index led and which lagged ([Breadth.leadership]).
        assertTrue("Of the indices, Nifty led (+0.83%) and BankNifty lagged" in w, w)
    }

    @Test fun theTrendTrackerTurnsAreTimed() {
        val t = MarketStory.turns(Market.NIFTY, nifty + niftyToday, today, LocalDateTime.of(today, java.time.LocalTime.of(15, 40)))
        assertTrue(t.all { it.first.toLocalDate() == today && it.first.minute % 15 == 0 }, "$t")
    }
}
