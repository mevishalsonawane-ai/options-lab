package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GapStreakTest {
    private val d = LocalDate.of(2026, 10, 1)

    private fun snap(prev: Double?, open: Double, high: Double, low: Double, price: Double, trading: Boolean = true, m: Market = Market.NIFTY) =
        Snapshot(m, d.atTime(12, 0), trading, price, prev, open, high, low, null, null, emptyList(), null, null, emptyList(), emptyList(), emptyList())

    @Test fun gapQuestionsAreRead() {
        assertTrue(Gap.asked("did nifty gap up today"))
        assertTrue(Gap.asked("has BankNifty filled its gap?"))
        assertTrue(Gap.asked("nifty gapped down"))
        assertFalse(Gap.asked("will nifty gap up tomorrow"), "a forecast")
        assertFalse(Gap.asked("what usually happens after a gap up"), "the study's")
        assertFalse(Gap.asked("what does gap up mean"), "the glossary's")
        assertFalse(Gap.asked("what's the gap between nifty and banknifty"))
        assertFalse(Gap.asked("how is nifty doing"))
    }

    @Test fun anUnfilledGapUp() {
        val s = Gap.say(snap(24_000.0, 24_120.0, 24_200.0, 24_050.0, 24_150.0))!!
        assertEquals("Nifty gapped up today: it opened at 24,120.00, 120.00 points (+0.50%) above the previous close of 24,000.00. " +
            "The gap is not filled: the day's low of 24,050.00 stayed 50.00 points above the previous close. " +
            "It is now 24,150.00, +30.00 from the open (+0.63% on the day).", s)
    }

    @Test fun aFilledGapDownOnAClosedDay() {
        val s = Gap.say(snap(24_000.0, 23_900.0, 24_010.0, 23_850.0, 23_990.0, trading = false))!!
        assertTrue(s.startsWith("Nifty gapped down on 2026-10-01: it opened at 23,900.00, 100.00 points (-0.42%) below the previous close"), s)
        assertTrue("The gap was filled: the day's high of 24,010.00 reached the previous close." in s, s)
        assertTrue(s.endsWith("It closed at 23,990.00, +90.00 from the open (-0.04% on the day)."), s)
    }

    @Test fun aFlatOpenAndMissingData() {
        assertTrue(Gap.say(snap(24_000.0, 24_005.0, 24_050.0, 23_990.0, 24_020.0))!!.startsWith("Nifty opened about flat today"))
        assertNull(Gap.say(snap(null, 24_005.0, 24_050.0, 23_990.0, 24_020.0)), "no previous close")
        assertNull(Gap.say(snap(2_400.0, 2_420.0, 2_430.0, 2_410.0, 2_425.0, m = Market.GOLD)), "gold has no gap to read")
    }

    /** One candle per day: the close of each session in [closes], from 1 September. */
    private fun days(vararg closes: Double) = closes.mapIndexed { i, c -> Candle(LocalDate.of(2026, 9, 1).plusDays(i.toLong()).atTime(15, 29), c, c, c, c) }

    @Test fun streakQuestionsAreRead() {
        assertTrue(Streak.asked("how many days in a row has nifty gone up"))
        assertTrue(Streak.asked("banknifty losing streak"))
        assertTrue(Streak.asked("is nifty at a new high"))
        assertFalse(Streak.asked("will nifty make a new high tomorrow"))
        assertFalse(Streak.asked("nifty high today"))
    }

    @Test fun aRunOfHigherCloses() {
        val s = Streak.say(Market.NIFTY, days(25_000.0, 24_000.0, 24_100.0, 24_200.0, 24_300.0), live = false)!!
        assertEquals("Nifty has closed higher 3 sessions in a row: from 24,000.00 on 2026-09-02 to 24,300.00 on 2026-09-05, +300.00 points (+1.25%). " +
            "Its close of 24,300.00 is the highest since 2026-09-01, when it closed at 25,000.00.", s)
    }

    @Test fun aRunOfLowerClosesToTheLowestOnThePhone() {
        val s = Streak.say(Market.BANKNIFTY, days(52_000.0, 51_900.0, 51_800.0), live = true)!!
        assertTrue(s.startsWith("BankNifty has closed lower 2 sessions in a row (counting today so far), every session I have:"), s)
        assertTrue(s.endsWith("At 51,800.00 now it is below every close of the 3 sessions on the phone (since 2026-09-01)."), s)
        assertFalse("highest" in s, s)
    }

    @Test fun tooFewSessionsSayNothing() {
        assertNull(Streak.say(Market.NIFTY, days(24_000.0, 24_100.0), live = false))
    }

    @Test fun theseAreTheMarketsFiguresNotTheAccount() {
        for (q in listOf("has nifty filled the gap", "nifty losing streak", "did banknifty gap down today"))
            assertFalse(Topic.ACCOUNT in Ask.parse(q).topics, q)
    }
}
