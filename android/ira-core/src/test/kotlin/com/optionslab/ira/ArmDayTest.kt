package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArmDayTest {
    private val day: LocalDate = LocalDate.of(2026, 10, 5)
    private fun at(h: Int, m: Int): LocalDateTime = day.atTime(h, m)
    private val now = at(13, 0)

    /** BankNifty's day as straight lines between these minutes and prices. */
    private val points = listOf(at(9, 15) to 54_300.0, at(9, 30) to 54_400.0, at(9, 45) to 54_250.0, at(10, 4) to 54_320.0, at(10, 30) to 54_380.0,
        at(10, 40) to 54_450.0, at(10, 45) to 54_480.0, at(10, 58) to 54_350.0, at(11, 0) to 54_360.0, at(11, 30) to 54_250.0,
        at(12, 30) to 54_100.0, at(13, 0) to 54_150.0)

    private fun px(t: LocalDateTime): Double {
        val i = points.indexOfLast { !it.first.isAfter(t) }
        if (i == points.size - 1) return points.last().second
        val (t0, p0) = points[i]; val (t1, p1) = points[i + 1]
        val f = java.time.Duration.between(t0, t).toMinutes().toDouble() / java.time.Duration.between(t0, t1).toMinutes()
        return p0 + (p1 - p0) * f
    }

    private val ones: List<Candle> = run {
        val prev = Candle(LocalDate.of(2026, 10, 2).atTime(15, 29), 54_010.0, 54_020.0, 54_000.0, 54_000.0)
        var t = at(9, 15); var o = 54_300.0
        val out = arrayListOf(prev)
        while (!t.isAfter(now)) { val c = px(t); out += Candle(t, o, maxOf(o, c), minOf(o, c), c); o = c; t = t.plusMinutes(1) }
        out
    }

    private val orb = BotTrades.Trade("orb", "BANKNIFTY07OCT2654400CE", "CE", 35, 400.0, at(10, 40), at(10, 35),
        exit = 340.0, exitTime = at(10, 58), why = "stop", charges = 60.0)
    private val fade = BotTrades.Trade("range_fade", "BANKNIFTY07OCT2654400PE", "PE", 35, 380.0, at(11, 0), at(10, 55),
        exit = 430.0, exitTime = at(11, 30), why = "target", charges = 60.0)
    private val liq = BotTrades.Trade("liquidity5", "BANKNIFTY14OCT2654200CE", "CE", 35, 300.0, at(12, 0), at(11, 55),
        exit = 280.0, exitTime = at(12, 20), why = "time_stop", charges = 55.0, level = 54_150.0, target = 54_300.0)
    private val bars = mapOf("BANKNIFTY" to ones)

    @Test fun theDayItsRangeAndItsBreaks() {
        val range = BotTrades.openingRange(ones, day)!!
        assertEquals(54_400.0 to 54_250.0, range)
        val line = ArmDay.dayLine("BANKNIFTY", ones, range, now)!!
        assertTrue(line.startsWith("The day on BankNifty: it opened +0.56% from the last close (54,000.00 to 54,300.00), the gap still open"), line)
        assertTrue(line.contains("the opening range was 54,400.00-54,250.00 (09:15-10:00): a 5-minute close above it at 10:35, back inside at 10:55, a close below it at 11:35"), line)
        assertTrue(line.contains("the day's high 54,480.00 at 10:45 and low 54,100.00 at 12:30"), line)
    }

    @Test fun eachTradeBesideTheIndex() {
        val range = 54_400.0 to 54_250.0
        val o = ArmDay.hold(orb, ones, range, now)
        assertEquals(ArmDay.Shape.FAILED_BREAK, o.shape)
        assertTrue(o.text.startsWith("ORB call, 10:40-10:58, -Rs 2,160 after charges, out at its stop."), o.text)
        assertTrue(o.text.contains("BankNifty 54,450.00 at the entry above the opening range high 54,400.00; its best for the call 54,480.00 at 10:45 (+30 points); " +
            "by the exit 54,350.00 (-100 points for the call): back inside the range by the exit: the break didn't hold."), o.text)
        assertTrue(o.text.contains("Since the exit BankNifty is at 54,150.00 (13:01), 200 points further against the call."), o.text)
        assertEquals(ArmDay.Shape.WON, ArmDay.hold(fade, ones, range, now).shape)
        val l = ArmDay.hold(liq, ones, range, now)
        assertEquals(ArmDay.Shape.NEVER_WENT, l.shape)
        assertTrue(l.text.contains("the index went barely 3 points the call's way") && l.text.contains("25 points further the call's way"), l.text)
        // An index that ended the hold the side's way while the option still lost: said so, never split into time or volatility.
        val p = ArmDay.hold(liq.copy(entryTime = at(12, 30), signalBar = at(12, 25), exitTime = at(12, 50)), ones, range, now)
        assertEquals(ArmDay.Shape.PREMIUM, p.shape)
        assertTrue(p.text.contains("yet the option's price lost"), p.text)
    }

    @Test fun theAnswerTiesTheLosingTradesTogether() {
        val s = ArmDay.answer(ArmDay.Q(), listOf(orb, fade, liq), bars, null, now)
        assertTrue(s.startsWith("Boss, your bots took 3 trades today (paper): 3 closed for "), s)
        assertTrue(s.contains("2 of them at a loss"), s)
        assertTrue(s.contains("\nThe day on BankNifty: "), s)
        assertTrue(s.contains("\n1. ORB call") && s.contains("\n2. Range Fade put") && s.contains("\n3. "), s)
        assertTrue(s.contains("Tied together: of the 2 losing trades, 1 was a break of the opening range the index came back inside of; " +
            "1 was a trade the index never really went the way of."), s)
        assertTrue(s.endsWith(ArmDay.NOTE), s)
        // One arm asked: its trades alone.
        val orbOnly = ArmDay.answer(ArmDay.Q("orb"), listOf(orb, fade, liq), bars, null, now)
        assertTrue(orbOnly.startsWith("Boss, ORB took 1 trade today (paper)") && !orbOnly.contains("Range Fade put"), orbOnly)
        // Not traded today; another day's trade is not today's.
        assertTrue(ArmDay.answer(ArmDay.Q("orb_sweep"), listOf(orb), bars, null, now).startsWith("ORB Sweep has not traded today, Boss"))
        assertTrue(ArmDay.answer(ArmDay.Q(), listOf(orb), bars, null, now.plusDays(1)).startsWith("None of your bots has traded today, Boss"))
        // Never a forecast or advice word.
        for (w in listOf("should", "will ", "tomorrow", "disarm", "recommend")) assertTrue(!s.lowercase().contains(w), w)
    }

    @Test fun theQuestions() {
        for ((s, bot) in listOf("why did my strategy lose today" to null, "why did ORB lose today?" to "orb", "why did my bots lose money today" to null,
            "what went wrong with range fade today" to "range_fade", "why was today a bad day for my bots" to null, "why is my strategy losing today" to null,
            "orb ka aaj loss kyun hua" to "orb", "meri strategy ne aaj loss kyun kiya" to null, "why did the liquidity bot get stopped out" to "liquidity",
            "how did the market beat my strategy today" to null, "connect my strategy's loss to what the index did" to null, "why did orb fresh lose" to "orb_fresh"))
            assertEquals(bot, ArmDay.asked(s)?.bot, s)
        for (s in listOf("why did ORB lose today?", "why did my strategy lose today", "orb ka aaj loss kyun hua"))
            assertTrue(ArmDay.asked(s) != null, s)
        for (s in listOf("why did my last trade lose", "explain my bots trades today", "why did orb take that trade", "which strategy is losing",
            "why did nifty fall", "how much did orb lose today", "will my strategy lose tomorrow", "should i stop orb", "why did my strategy lose yesterday",
            "stop orb", "why did you lose", "how are my bots doing"))
            assertNull(ArmDay.asked(s), s)
    }
}
