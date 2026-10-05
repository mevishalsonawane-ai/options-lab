package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MomentsTest {
    /** Friday 2 October 2026; the earlier sessions run Monday 28 September to Thursday 1 October. */
    private val today = LocalDate.of(2026, 10, 2)

    private fun snap(prev: Double?, open: Double, high: Double, low: Double, price: Double, trading: Boolean = true, m: Market = Market.NIFTY) =
        Snapshot(m, today.atTime(11, 0), trading, price, prev, open, high, low, null, null, emptyList(), null, null, emptyList(), emptyList(), emptyList())

    /** One high and one low candle per earlier session, from 28 September: (high, low) pairs. */
    private fun days(vararg hl: Pair<Double, Double>) = hl.flatMapIndexed { i, (h, l) ->
        val d = LocalDate.of(2026, 9, 28).plusDays(i.toLong())
        listOf(Candle(d.atTime(10, 0), h, h, h, h), Candle(d.atTime(14, 0), l, l, l, l))
    }

    private val bars = days(24_400.0 to 24_100.0, 24_300.0 to 24_000.0, 24_250.0 to 23_950.0, 24_200.0 to 23_900.0)

    @Test fun aGapUpFillingIsToldOnTheMove() {
        // Opened 0.5% above Thursday's close of 24,000; the low had stayed above it, now it has come back.
        val before = Moments.look(snap(24_000.0, 24_120.0, 24_150.0, 24_050.0, 24_060.0), bars)!!
        assertTrue(before.gapOpen)
        val s = snap(24_000.0, 24_120.0, 24_150.0, 23_995.0, 23_998.0)
        val now = Moments.look(s, bars)!!
        val a = Moments.alerts(s, bars, before, now).single()
        assertEquals("gapfill|NIFTY", a.key)
        assertEquals("Nifty has filled today's gap up, Boss: it opened 120.00 points (+0.50%) above the previous close of 24,000.00 " +
            "and has come back to it. It is at 23,998.00 now.", a.text)
        // Nothing new at the next look.
        assertTrue(Moments.alerts(s, bars, now, now).isEmpty())
    }

    @Test fun aSmallGapIsNotWorthTelling() {
        val l = Moments.look(snap(24_000.0, 24_020.0, 24_050.0, 24_010.0, 24_030.0), bars)!!
        assertTrue(!l.gapOpen, "0.08% is below the threshold")
    }

    @Test fun aBreakOfThePreviousHighSaysHowFarBack() {
        val s0 = snap(24_100.0, 24_110.0, 24_190.0, 24_090.0, 24_180.0)
        val before = Moments.look(s0, bars)!!
        val s = snap(24_100.0, 24_110.0, 24_320.0, 24_090.0, 24_320.0)
        val now = Moments.look(s, bars)!!
        val a = Moments.alerts(s, bars, before, now).single()
        assertEquals("prevhigh|NIFTY", a.key)
        assertEquals("Nifty above Thursday's high", a.title)
        assertEquals("Nifty went above Thursday's high of 24,200.00, Boss: it is at 24,320.00. That is the highest price since 28 September.", a.text)
    }

    @Test fun aBreakBelowEveryLowOnThePhone() {
        val before = Moments.look(snap(24_100.0, 24_050.0, 24_120.0, 23_950.0, 23_960.0), bars)!!
        val s = snap(24_100.0, 24_050.0, 24_120.0, 23_850.0, 23_850.0)
        val a = Moments.alerts(s, bars, before, Moments.look(s, bars)!!).single()
        assertEquals("Nifty went below Thursday's low of 23,900.00, Boss: it is at 23,850.00. " +
            "That is below every low of the 4 earlier sessions on the phone (since 28 September).", a.text)
    }

    @Test fun noHighestSinceWhenTodayWentFurtherEarlier() {
        val before = Moments.look(snap(24_100.0, 24_110.0, 24_500.0, 24_090.0, 24_150.0), bars)!!
        val s = snap(24_100.0, 24_110.0, 24_500.0, 24_090.0, 24_260.0)
        val a = Moments.alerts(s, bars, before, Moments.look(s, bars)!!).single()
        assertEquals("Nifty went above Thursday's high of 24,200.00, Boss: it is at 24,260.00.", a.text)
    }

    @Test fun alreadyThereAtTheFirstLookIsNotNews() {
        // The caller skips the first look; a state unchanged between looks gives nothing.
        val s = snap(24_100.0, 24_110.0, 24_320.0, 24_090.0, 24_320.0)
        val l = Moments.look(s, bars)!!
        assertTrue(Moments.alerts(s, bars, l, l).isEmpty())
    }

    @Test fun nothingForGoldVixAClosedMarketOrMissingData() {
        assertNull(Moments.look(snap(2_400.0, 2_420.0, 2_430.0, 2_410.0, 2_425.0, m = Market.GOLD), bars))
        assertNull(Moments.look(snap(14.0, 14.5, 15.0, 14.0, 14.8, m = Market.VIX), bars))
        assertNull(Moments.look(snap(24_000.0, 24_120.0, 24_150.0, 24_050.0, 24_060.0, trading = false), bars))
        assertNull(Moments.look(snap(null, 24_120.0, 24_150.0, 24_050.0, 24_060.0), bars))
        assertNull(Moments.look(snap(24_000.0, 24_120.0, 24_150.0, 24_050.0, 24_060.0), emptyList()))
    }
}
