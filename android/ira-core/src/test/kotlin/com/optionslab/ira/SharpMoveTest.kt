package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharpMoveTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    /** Monday 5 October 2026; the earlier sessions are the five days before. */
    private val today = LocalDate.of(2026, 10, 5)

    /** An earlier session of 60 candles from 09:15 that swings by 2*[step] points every 10 minutes (its usual 10-minute move). */
    private fun quietDay(d: LocalDate, base: Double, step: Double) = (0 until 60).map { i ->
        val c = base + if ((i / 10) % 2 == 0) step else -step
        Candle(d.atTime(9, 15).plusMinutes(i.toLong()), c, c, c, c)
    }

    /** Today's candles from 09:15: flat at [base] until [from] minutes in, then [per] points a minute for [len] minutes, then flat. */
    private fun todayBars(base: Double, from: Int, per: Double, len: Int, total: Int = 90) = (0 until total).map { i ->
        val c = base + per * (i - from).coerceIn(0, len)
        Candle(today.atTime(9, 15).plusMinutes(i.toLong()), c, c, c, c)
    }

    private fun history(base: Double, step: Double) = (1..5).flatMap { quietDay(today.minusDays(it.toLong() + 2), base, step) }.sortedBy { it.t }

    private fun at(h: Int, m: Int): LocalDateTime = today.atTime(h, m)

    @Test fun aSharpFallIsFoundAndItsBiggestTenMinutesTaken() {
        // Nifty flat at 25,000, then down 10 points a minute for 12 minutes from 10:15 (60 minutes in): 120 points.
        val bars = history(25_000.0, 2.0) + todayBars(25_000.0, 60, -10.0, 12)
        val mv = SharpMove.latest(Market.NIFTY, bars)!!
        assertEquals(-100.0, mv.points, 1e-9)
        assertEquals(10, mv.minutes)
        assertFalse(mv.up)
        assertTrue(mv.from >= at(10, 15) && mv.to <= at(10, 27))
        assertNotNull(mv.usualPct)
    }

    @Test fun aQuietDayHasNoSharpMove() {
        val bars = history(25_000.0, 2.0) + todayBars(25_000.0, 60, -1.0, 12)
        assertNull(SharpMove.latest(Market.NIFTY, bars))
    }

    @Test fun aMoveOnlyAFewTimesTheUsualIsNotSharpEvenPastTheFloor() {
        // A usual 10-minute move near 0.32% (wiggles of 40 points): a 100-point fall (0.4%) is not three times it.
        val bars = history(25_000.0, 40.0) + todayBars(25_000.0, 60, -10.0, 12)
        assertNull(SharpMove.latest(Market.NIFTY, bars))
    }

    @Test fun whatCoincidedIsToldAsTimingOnly() {
        val nifty = history(25_000.0, 2.0) + todayBars(25_000.0, 60, -10.0, 10)
        val bank = todayBars(52_000.0, 60, -30.0, 10)
        val fin = todayBars(24_000.0, 60, -5.0, 10)
        val sensex = todayBars(82_000.0, 60, 0.5, 10)
        val vix = todayBars(13.0, 60, 0.05, 10)
        val bars = mapOf(Market.NIFTY to nifty, Market.BANKNIFTY to bank, Market.FINNIFTY to fin, Market.SENSEX to sensex, Market.VIX to vix)
        val mv = SharpMove.latest(Market.NIFTY, nifty)!!
        assertEquals(at(10, 15), mv.from); assertEquals(at(10, 25), mv.to)
        fun h(title: String, t: LocalDateTime, ms: List<Market> = listOf(Market.NIFTY)) =
            Headline(title, "https://x", "Feed", t.atZone(zone).toInstant(), 0.0, ms)
        val news = listOf(
            h("RBI holds rates", at(10, 18), listOf(Market.BANKNIFTY)),
            h("Markets slide", at(10, 10)),
            h("Gold steady", at(10, 20), listOf(Market.GOLD)),          // only gold: left out
            h("Old story", at(9, 40)),                                    // too early: left out
            h("Late story", at(10, 31)),                                  // more than 5 minutes after: left out
        )
        val c = SharpMove.context(mv, bars, news, zone)
        assertEquals(listOf("Markets slide", "RBI holds rates"), c.news.map { it.first.title })
        val text = SharpMove.say(mv, c)
        assertEquals("Nifty fell 100.00 points (-0.40%) in 10 minutes, from 25,000.00 at 10:15 to 24,900.00 at 10:25 - " +
            "about 25 times its usual 10-minute move of 0.02%. " +
            "In the same minutes, India VIX went from 13.00 to 13.50 (+3.85%); 2 of 3 other indices moved the same way " +
            "(BankNifty -0.58%, FinNifty -0.21%, Sensex +0.01%). " +
            "Headlines published around then: 10:10 \"Markets slide\" (Feed, 5 minutes before it began); 10:18 \"RBI holds rates\" (Feed, during it). " +
            "That is what coincided, Boss - timing only: none of it says why the market moved.", text)
        assertTrue(!Regex("(?i)\\b(because|caused|due to|will|should|buy|sell)\\b").containsMatchIn(text))
        val spoken = SharpMove.spoken(mv, c)
        assertEquals("Boss, Nifty fell 0.40% in 10 minutes. In the same minutes: India VIX up 3.85%, 2 of 3 other indices moved with it, " +
            "2 headlines around then. Timing only, not a cause; the details are in the chat.", spoken)
    }

    @Test fun noHeadlineIsSaidPlainly() {
        val nifty = history(25_000.0, 2.0) + todayBars(25_000.0, 60, 10.0, 10)
        val mv = SharpMove.latest(Market.NIFTY, nifty)!!
        val text = SharpMove.say(mv, SharpMove.context(mv, mapOf(Market.NIFTY to nifty), emptyList(), zone), expiry = true)
        assertTrue(text.startsWith("Nifty rose 100.00 points (+0.40%) in 10 minutes"))
        assertTrue("No headline on the phone was published between 10:05 and 10:30." in text, text)
        assertTrue("It is Nifty's expiry day." in text)
    }

    @Test fun theWatcherTellsAMoveOnceAndNotAnOldOne() {
        val nifty = history(25_000.0, 2.0) + todayBars(25_000.0, 60, -10.0, 10)
        val mv = SharpMove.latest(Market.NIFTY, nifty)!!
        assertTrue(SharpMove.worthTelling(mv, at(10, 30), null))
        assertFalse(SharpMove.worthTelling(mv, at(11, 0), null), "found 35 minutes late: not news")
        assertFalse(SharpMove.worthTelling(mv, at(10, 30), at(10, 25)), "already told")
        assertTrue(SharpMove.worthTelling(mv.copy(to = at(11, 0)), at(11, 2), at(10, 25)))
    }

    @Test fun askedPhrasings() {
        for (q in listOf("explain this move", "Jarvis, explain that sudden fall", "what coincided with that drop", "what coincided",
                "what was going on when Nifty fell", "why did nifty suddenly fall", "any news behind this move", "why is banknifty suddenly falling"))
        {
            assertEquals(SharpMove.Ask(null), SharpMove.asked(q), q)
            // The app answers it among the market reasoning: not an order, a command, advice or an account question.
            val p = Ask.parse(q)
            assertTrue(p.order == null && p.command == null && p.pattern == null, q)
            assertTrue(p.topics.none { it in setOf(Topic.ADVICE, Topic.ACCOUNT, Topic.COMMAND, Topic.SUGGEST, Topic.BACKTEST, Topic.TRADE_CHECK) }, "$q: ${p.topics}")
        }
        assertEquals(SharpMove.Ask(LocalTime.of(11, 20)), SharpMove.asked("what happened at 11:20"))
        assertEquals(SharpMove.Ask(LocalTime.of(14, 10)), SharpMove.asked("what happened to Nifty around 2:10"))
        assertEquals(SharpMove.Ask(LocalTime.of(10, 0)), SharpMove.asked("what happened at 10 am"))
        for (q in listOf("what happened at 11:20", "what happened to Nifty around 2:10")) Ask.parse(q).let { p ->
            assertTrue(p.order == null && p.command == null && p.topics.none { it in setOf(Topic.ADVICE, Topic.ACCOUNT, Topic.COMMAND, Topic.SUGGEST) }, "$q: ${p.topics}")
        }
        for (q in listOf("why is nifty down", "what happened in the market today", "explain theta", "how much did nifty move in the last hour",
                "nifty at 11:30"))
            assertNull(SharpMove.asked(q), q)
    }

    @Test fun answerFallsBackToTheDaysBiggestMove() {
        val nifty = history(25_000.0, 2.0) + todayBars(25_000.0, 60, -0.5, 10)
        val a = SharpMove.answer(SharpMove.Ask(null), Market.NIFTY, mapOf(Market.NIFTY to nifty), emptyList(), zone)!!
        assertTrue(a.startsWith("No sharp move today, Boss (none of 0.30% or more in 10 minutes and 3 times the usual 0.02%). The day's biggest 10-minute move: Nifty fell 5.00 points"), a)
        val around = SharpMove.answer(SharpMove.Ask(LocalTime.of(10, 20)), Market.NIFTY, mapOf(Market.NIFTY to nifty), emptyList(), zone)!!
        assertTrue(around.startsWith("Around 10:20, Nifty fell 5.00 points (-0.02%) in 10 minutes, from 25,000.00 at 10:15 to 24,995.00 at 10:25"), around)
        assertNull(SharpMove.answer(SharpMove.Ask(null), Market.BANKNIFTY, mapOf(Market.NIFTY to nifty), emptyList(), zone))
    }
}
