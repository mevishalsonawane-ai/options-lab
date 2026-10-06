package com.optionslab.engine.orb

import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The expiry-day Hero arm's pure rules on synthetic minute data (times are the minute each bar closes). */
class HeroRulesTest {
    private fun t(h: Int, m: Int): LocalTime = LocalTime.of(h, m)

    /** Every minute from 11:45 to 15:29, flat index and straddle, then [edit] for the day's story. */
    private fun day(index: (LocalTime) -> Double = { 25_000.0 }, straddle: (LocalTime) -> Double? = { 40.0 }):
        Pair<Map<LocalTime, Double>, Map<LocalTime, Double>> {
        val spot = HashMap<LocalTime, Double>(); val st = HashMap<LocalTime, Double>()
        var u = t(11, 45)
        while (u.isBefore(t(15, 30))) { spot[u] = index(u); straddle(u)?.let { st[u] = it }; u = u.plusMinutes(1) }
        return spot to st
    }

    @Test fun theArmIsPaperOnlyNotProvenAndNeverAnOrbArm() {
        assertTrue(HeroRules.ARM.paperOnly); assertTrue(HeroRules.ARM.hero)
        assertFalse(HeroRules.ARM.sweep || HeroRules.ARM.fade || HeroRules.ARM.liquidity)
        assertTrue(HeroRules.ARM !in OrbRules.ARMS)
        assertNull(ProfitLock.targetOf(HeroRules.ARM))
        assertEquals("Hero", HeroRules.OWNER)
        assertEquals("Not proven — paper only", HeroRules.NOT_PROVEN)
        assertEquals("Hero (expiry)", HeroRules.ARM.label)
    }

    @Test fun expiryDayComesFromTheMasterNeverTheWeekday() {
        val tue = LocalDate.of(2026, 10, 6)
        assertTrue(HeroRules.isExpiryDay(tue, listOf(tue, tue.plusDays(7))))
        assertFalse(HeroRules.isExpiryDay(tue, listOf(tue.plusDays(7), tue.plusDays(14))), "a Tuesday that is not an expiry")
        assertTrue(HeroRules.isExpiryDay(tue.plusDays(2), listOf(tue.minusDays(5), tue.plusDays(2))), "a holiday-shifted Thursday expiry")
        assertFalse(HeroRules.isExpiryDay(tue, emptyList()), "no master: never")
    }

    @Test fun firesUpWhenTheStraddleExpandsAndTheIndexRises() {
        // Straddle 40 from 12:00, low 30 at 12:30, 36 (20% above the low) from 13:50; index +0.4% from 13:40.
        val (spot, st) = day(index = { if (it.isBefore(t(13, 40))) 25_000.0 else 25_100.0 },
            straddle = { when { it == t(12, 30) -> 30.0; it.isBefore(t(13, 50)) -> 32.0; else -> 36.0 } })
        val s = HeroRules.scan(spot, st, t(13, 50))
        assertEquals(1, s.signal, s.toString()); assertEquals("fire_up", s.why)
        assertEquals(0.20, s.stExp!!, 1e-9); assertEquals(0.004, s.mom!!, 1e-9)
    }

    @Test fun firesDownWhenTheIndexFalls() {
        val (spot, st) = day(index = { if (it.isBefore(t(14, 0))) 25_000.0 else 24_930.0 },
            straddle = { if (it.isBefore(t(14, 0))) 30.0 else 35.0 })
        assertEquals(-1, HeroRules.scan(spot, st, t(14, 5)).signal)
    }

    @Test fun noFireWithoutBothConditionsOrOutsideTheWindow() {
        // Straddle expanded but the index flat.
        val (spot1, st1) = day(straddle = { if (it.isBefore(t(14, 0))) 30.0 else 36.0 })
        assertEquals("no_momentum", HeroRules.scan(spot1, st1, t(14, 5)).why)
        // Index moved 0.4% but the straddle only 10% up.
        val (spot2, st2) = day(index = { if (it.isBefore(t(14, 0))) 25_000.0 else 25_100.0 }, straddle = { if (it.isBefore(t(14, 0))) 30.0 else 33.0 })
        assertEquals("straddle_not_expanded", HeroRules.scan(spot2, st2, t(14, 5)).why)
        // Both, but before 13:30 and after 14:45.
        val (spot3, st3) = day(index = { if (it.isBefore(t(13, 20))) 25_000.0 else 25_100.0 }, straddle = { if (it.isBefore(t(13, 20))) 30.0 else 36.0 })
        assertEquals("before_window", HeroRules.scan(spot3, st3, t(13, 29)).why)
        assertEquals("after_window", HeroRules.scan(spot3, st3, t(14, 46)).why)
        assertEquals(1, HeroRules.scan(spot3, st3, t(13, 30)).signal, "13:30 is inside")
        val (spot4, st4) = day(index = { if (it.isBefore(t(14, 40))) 25_000.0 else 25_100.0 }, straddle = { if (it.isBefore(t(14, 40))) 30.0 else 36.0 })
        assertEquals(1, HeroRules.scan(spot4, st4, t(14, 45)).signal, "14:45 is inside")
    }

    @Test fun theLowCountsFromNoonAndSkipsInvalidMinutes() {
        // A 20 straddle at 11:50 (before noon) is not the low; an invalid minute (absent) never sets it.
        val (spot, st) = day(index = { if (it.isBefore(t(14, 0))) 25_000.0 else 25_100.0 },
            straddle = { when { it == t(11, 50) -> 20.0; it == t(12, 10) -> null; it.isBefore(t(14, 0)) -> 30.0; else -> 36.0 } })
        assertEquals(0.20, HeroRules.scan(spot, st, t(14, 5)).stExp!!, 1e-9)
    }

    @Test fun staleDataSkipsTheBarAndStandsDownAfterFiveInARow() {
        val gap = (0L..5L).map { t(13, 40).plusMinutes(it) }.toSet()        // 6 minutes with no straddle
        val (spot, st) = day(index = { if (it.isBefore(t(14, 0))) 25_000.0 else 25_100.0 },
            straddle = { if (it in gap) null else if (it.isBefore(t(14, 0))) 30.0 else 36.0 })
        assertEquals("skipped_stale_bar", HeroRules.scan(spot, st, t(13, 42)).why)
        val after = HeroRules.scan(spot, st, t(14, 5))
        assertTrue(after.standDown); assertEquals(0, after.signal); assertEquals("stood_down_stale_data", after.why)
        // Five in a row is still allowed.
        val five = (0L..4L).map { t(13, 40).plusMinutes(it) }.toSet()
        val (spot2, st2) = day(index = { if (it.isBefore(t(14, 0))) 25_000.0 else 25_100.0 },
            straddle = { if (it in five) null else if (it.isBefore(t(14, 0))) 30.0 else 36.0 })
        assertEquals(1, HeroRules.scan(spot2, st2, t(14, 5)).signal)
        // A missing index bar is a skipped bar too (the feed stopped: the minutes after it are missing).
        val dead = spot.filterKeys { it.isBefore(t(13, 50)) }
        assertTrue(HeroRules.scan(dead, st, t(13, 58)).standDown)
    }

    @Test fun aLegThatHasNotTradedForTwoMinutesIsStale() {
        val bars = mapOf(t(13, 0) to HeroRules.Leg(10.0, 100), t(13, 1) to HeroRules.Leg(10.5, 0), t(13, 2) to HeroRules.Leg(10.6, 0))
        assertEquals(10.6, HeroRules.legPrice(bars, t(13, 2)))
        assertEquals(10.6, HeroRules.legPrice(bars, t(13, 2)), "two minutes since the last trade: still valid")
        assertNull(HeroRules.legPrice(bars, t(13, 3)), "three minutes: stale")
        assertNull(HeroRules.legPrice(bars, t(12, 59)), "nothing yet")
    }

    @Test fun picksTheNearestOtmOptionPricedOneToFive() {
        val c = listOf(
            HeroRules.Candidate(25_050.0, 9.0, 9.05, true),     // too dear
            HeroRules.Candidate(25_100.0, 4.2, 4.25, true),     // the pick
            HeroRules.Candidate(25_150.0, 2.1, 2.15, true),
            HeroRules.Candidate(25_200.0, 0.8, 0.85, true),     // too cheap
            HeroRules.Candidate(24_950.0, 3.0, 3.05, true))     // ITM for a call
        assertEquals(25_100.0, HeroRules.pick(1, 25_020.0, c)!!.strike)
        assertEquals(24_950.0, HeroRules.pick(-1, 25_020.0, c)!!.strike)
        // Nothing in the band: no trade.
        assertNull(HeroRules.pick(1, 25_020.0, listOf(HeroRules.Candidate(25_100.0, 6.0, 6.1, true), HeroRules.Candidate(25_150.0, 0.5, 0.55, true))))
        // Not traded in the last 5 bars, or no ask: skipped for the next one.
        assertEquals(25_150.0, HeroRules.pick(1, 25_020.0, listOf(HeroRules.Candidate(25_100.0, 4.0, 4.05, false), HeroRules.Candidate(25_150.0, 2.0, 2.05, true)))!!.strike)
        assertEquals(25_150.0, HeroRules.pick(1, 25_020.0, listOf(HeroRules.Candidate(25_100.0, 4.0, null, true), HeroRules.Candidate(25_150.0, 2.0, 2.05, true)))!!.strike)
        // Stale: a further-OTM strike priced well above it means this LTP is old.
        assertEquals(25_150.0, HeroRules.pick(1, 25_020.0, listOf(HeroRules.Candidate(25_100.0, 1.5, 1.55, true), HeroRules.Candidate(25_150.0, 2.0, 2.05, true)))!!.strike)
    }

    @Test fun limitPriceIsAskPlusATickCappedAndNeverAMarketPrice() {
        assertEquals(4.30, HeroRules.limitPrice(4.25, 4.20), 1e-9)                // ask + 1 tick
        assertEquals(1.60, HeroRules.limitPrice(1.50, 1.45), 1e-9)                // under Rs 2: 2 ticks
        assertEquals(3.70, HeroRules.limitPrice(5.00, 3.00), 1e-9)                // capped at 3 x 1.2 + 0.10
        assertTrue(HeroRules.limitPrice(4.99, 4.99) * 100 % 5 < 1e-6, "on the 0.05 tick")
    }

    @Test fun lotsFromTheBudgetAndZeroSkips() {
        assertEquals(17, HeroRules.lots(4.30, 65))                                 // 5000 / 279.5
        assertEquals(76, HeroRules.lots(1.0, 65))
        assertEquals(0, HeroRules.lots(80.0, 65), "a lot above the budget: skip, never rounded up")
        assertEquals(0, HeroRules.lots(4.0, 0), "lot size unknown: no trade")
        assertTrue(HeroRules.lots(5.0, 65) * 5.0 * 65 <= HeroRules.BUDGET)
    }

    @Test fun oneEntryADayAndTheExitAt1505() {
        assertTrue(HeroRules.mayEnter(0)); assertFalse(HeroRules.mayEnter(1))
        assertFalse(HeroRules.exitDue(t(15, 4))); assertTrue(HeroRules.exitDue(t(15, 5))); assertTrue(HeroRules.exitDue(t(15, 25)))
        assertEquals(15 * 60 + 5, HeroRules.EXIT_AT.hour * 60 + HeroRules.EXIT_AT.minute)
        assertEquals(0.15, HeroRules.exitLimit(0.20), 1e-9)
        assertEquals(0.05, HeroRules.exitLimit(0.05), 1e-9, "never below a tick")
    }

    @Test fun disarmsAfterTwelveLosingDaysOrFiftyThousand() {
        assertNull(HeroRules.killReason(List(11) { -4_000.0 }))
        assertNotNull(HeroRules.killReason(List(12) { -4_000.0 }))
        assertNull(HeroRules.killReason(List(11) { -4_000.0 } + 100.0 + List(3) { -1_000.0 }), "a win breaks the run")
        assertNotNull(HeroRules.killReason(listOf(-30_000.0, 5_000.0, -25_000.0)), "Rs 50,000 in all")
        assertNull(HeroRules.killReason(listOf(-30_000.0, 5_000.0, -20_000.0)))
        assertNull(HeroRules.killReason(emptyList()))
    }
}
