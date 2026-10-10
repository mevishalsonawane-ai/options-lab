package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Voice, round 28: an index's signed move is said in points. Real answers (the gap, the day's story, a price looked up,
 * the market's story) run through [Aloud.say]; the chat text is the answer itself, unchanged.
 */
class MovePointsTest {
    private val d = LocalDate.of(2026, 10, 1)

    private fun snap(prev: Double?, open: Double, high: Double, low: Double, price: Double, m: Market = Market.NIFTY) =
        Snapshot(m, d.atTime(12, 0), true, price, prev, open, high, low, null, null, emptyList(), null, null, emptyList(), emptyList(), emptyList())

    @Test fun theGapsMoveFromTheOpenIsSaidInPoints() {
        val chat = Gap.say(snap(24_000.0, 24_120.0, 24_200.0, 24_050.0, 24_150.0))!!
        assertTrue("It is now 24,150.00, +30.00 from the open (+0.63% on the day)." in chat, chat)
        val said = Aloud.say(chat, Aloud.Length.FULL)
        assertTrue("It is now 24,150, plus 30 points from the open, plus 0.63 percent on the day." in said, said)
        // Already in points, and the gap's own percent: unchanged.
        assertTrue("120 points, plus 0.5 percent, above the previous close" in said, said)
        assertFalse("points points" in said, said)
        val flat = Aloud.say(Gap.say(snap(24_000.0, 24_005.0, 24_050.0, 23_990.0, 23_920.0))!!, Aloud.Length.FULL)
        assertTrue("against the previous close of 24,000, plus 5 points, no real gap" in flat, flat)
        assertTrue("minus 85 points from the open" in flat, flat)
        val hi = Aloud.say(Gap.say(snap(24_000.0, 24_005.0, 24_050.0, 23_990.0, 23_920.0))!!, Aloud.Length.FULL, hindi = true)
        assertTrue("माइनस 85 पॉइंट from the open" in hi, hi)
    }

    @Test fun theDaysStoryAndALookedUpPrice() {
        // Up 1 a minute for an hour, then down 1 a minute for two hours: 59 points under the open at the end.
        val bars = (0 until 180).map { i -> val p = if (i < 60) 24_000.0 + i else 24_060.0 - (i - 60); Candle(d.atTime(9, 15).plusMinutes(i.toLong()), p, p + 0.5, p - 0.5, p) }
        val chat = DayStory.say(Market.NIFTY, bars)!!
        assertTrue("(-59.00 from the open)" in chat, chat)
        val said = Aloud.say(chat, Aloud.Length.FULL)
        assertTrue("It is now 23,941, minus 59 points from the open, near the day's low." in said, said)

        val look = listOf(Candle(d.atTime(9, 15), 24_104.0, 24_108.0, 24_103.0, 24_107.0), Candle(d.atTime(11, 0), 24_107.0, 24_112.0, 24_106.0, 24_111.0),
            Candle(d.atTime(12, 0), 24_111.0, 24_153.0, 24_110.0, 24_152.0))
        val at = Lookback.priceAt(Market.NIFTY, look, LocalTime.of(11, 30))!!
        assertEquals("At 11:00 on 2026-10-01 Nifty was at 24,111.00. Since then it has moved +41.00 to 24,152.00.", at)
        assertTrue(Aloud.say(at, Aloud.Length.FULL).endsWith("Since then it has moved plus 41 points to 24,152."), Aloud.say(at, Aloud.Length.FULL))
    }

    @Test fun theMarketsStoryTheTrendMeasureAndTwoDaysSideBySide() {
        val story = Aloud.say("Today's market, Boss: Nifty closed at 24,212.40, +200.15 (+0.83%) from the previous close; BankNifty -0.37%.", Aloud.Length.FULL)
        assertEquals("Today's market, Boss: Nifty closed at 24,212, plus 200 points, plus 0.83 percent, from the previous close; BankNifty minus 0.37 percent.", story)
        val measure = Aloud.say("Trend or range: trend-like so far, up. The measure: the net move from the open (+120.50) is 60% of the day's range of 200.00 points.", Aloud.Length.FULL)
        assertTrue("the net move from the open, plus 121 points, is 60 percent" in measure, measure)
        val two = Aloud.say("They moved opposite ways from the open: +85.20 today, -120.40 yesterday.", Aloud.Length.FULL)
        assertTrue(two.endsWith("from the open: plus 85.2 points today, minus 120 points yesterday."), two)
        val net = Aloud.say("Today: trend-like - net +85.20 from the open, 60% of a 200.00-point range.", Aloud.Length.FULL)
        assertTrue("net plus 85.2 points from the open" in net, net)
    }

    @Test fun percentRupeesGoldAndUnitsAlreadySaidAreLeftAlone() {
        for ((chat, said) in listOf(
            "Nifty closed +0.83% from the previous close." to "Boss, Nifty closed plus 0.83 percent from the previous close.",
            "Your P&L today is +Rs 2,575, Boss." to "Your P&L today is plus 2,575 rupees, Boss.",
            "Nifty opened about flat (+5.00 points) today." to "Boss, Nifty opened about flat, plus 5 points, today.",
            "Gold is at 2,410.00, +12.00 from the open." to "Boss, Gold is at 2,410, plus 12 from the open.",
        )) assertEquals(said, Aloud.say(chat, Aloud.Length.FULL), chat)
        // Said twice, as the speech engine may: nothing more changes.
        val once = Aloud.say(Gap.say(snap(24_000.0, 24_120.0, 24_200.0, 24_050.0, 24_150.0))!!, Aloud.Length.FULL)
        assertEquals(once, Pauses.shape(SayAs.figures(once, Aloud.hindi(once))))
    }

    @Test fun twoDaysSideBySideShapedTwiceKeepsOneUnit() {
        val line = "They moved opposite ways from the open: +85.20 today, -120.40 yesterday."
        val once = SayAs.figures(line, false)
        val twice = SayAs.figures(once, false)
        assertEquals(once, twice)
        assertFalse(twice.contains("points points"), twice)
    }
}
