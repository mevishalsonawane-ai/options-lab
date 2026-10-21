package com.optionslab.engine.orb

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the passed-research paper arms share ([PassedArms]): the minute grid, R51's 5-minute bars, the strike rule, the premium
 * exits in the research engine's order (stop on the low first, target on the high, the lock from the next minute, the time
 * stop at the close of its last minute, the square-off; gaps, missing minutes, a restart mid-trade) and the 40-trade bar.
 */
class PassedArmsTest {
    private val day = LocalDate.of(2026, 10, 20)
    private fun t(h: Int, m: Int): LocalDateTime = day.atTime(h, m)
    private fun bar(h: Int, m: Int, o: Double, hi: Double, lo: Double, c: Double) = Bar(t(h, m), o, hi, lo, c)
    private fun flat(h: Int, m: Int, p: Double) = bar(h, m, p, p, p, p)

    // ---- grid, bars, strikes ----------------------------------------------------------------------------------------------

    @Test fun theGridIsTheResearchs() {
        assertEquals(0, PassedArms.Session.row(9, 15))
        assertEquals(5, PassedArms.Session.row(9, 20))
        assertEquals(374, PassedArms.Session.row(15, 29))
        assertEquals(LocalTime.of(12, 0), PassedArms.Session.time(165))
        assertEquals(375, PassedArms.Session.N)
    }

    @Test fun stepsAndStrikes() {
        assertEquals(50, PassedArms.step("NIFTY")); assertEquals(100, PassedArms.step("BANKNIFTY"))
        assertEquals(50, PassedArms.step("FINNIFTY")); assertEquals(25, PassedArms.step("MIDCPNIFTY")); assertEquals(100, PassedArms.step("SENSEX"))
        // 1-ITM: the call one step below ATM, the put one above; ATM half up.
        assertEquals(24_950, PassedArms.itmStrike("NIFTY", 1, 25_010.0, 1))
        assertEquals(25_050, PassedArms.itmStrike("NIFTY", -1, 25_010.0, 1))
        assertEquals(25_000, PassedArms.itmStrike("NIFTY", 1, 25_025.0, 1))
        assertEquals(12_975, PassedArms.itmStrike("MIDCPNIFTY", 1, 13_010.0, 1))
        assertEquals(53_700, PassedArms.itmStrike("BANKNIFTY", -1, 53_400.0, 3))
    }

    @Test fun aDayOnTheGridKeepsTheFirstOfAMinuteAndSkipsBadOnes() {
        val bars = listOf(flat(9, 15, 100.0), bar(9, 16, 101.0, Double.NaN, Double.NaN, 102.0), flat(9, 15, 999.0),
            bar(9, 17, Double.NaN, 1.0, 1.0, 1.0), bar(9, 18, 0.0, 1.0, 1.0, 1.0), flat(9, 14, 5.0), flat(15, 30, 5.0),
            Bar(day.minusDays(1).atTime(9, 19), 7.0, 7.0, 7.0, 7.0), flat(15, 29, 110.0))
        val d = PassedArms.DayBars.of(day, bars)
        assertEquals(100.0, d.c[0])
        assertEquals(102.0, d.h[1]); assertEquals(101.0, d.l[1])     // missing high / low: the open and close
        assertTrue(d.c[2].isNaN() && d.c[3].isNaN() && d.c[4].isNaN())
        assertEquals(3, d.count)
        assertEquals(374, d.lastRow)
        assertEquals(100.0, d.firstOpen)
        assertEquals(110.0, d.high); assertEquals(100.0, d.low); assertEquals(110.0, d.lastClose)
        assertEquals(102.0, d.closeAtOrBefore(10))
        assertTrue(d.closeAtOrBefore(-1).isNaN())
        val cut = d.through(1)
        assertEquals(1, cut.lastRow)
        val empty = PassedArms.DayBars.of(day, emptyList())
        assertEquals(-1, empty.lastRow); assertTrue(empty.firstOpen.isNaN() && empty.high.isNaN() && empty.low.isNaN())
    }

    @Test fun fiveMinuteBarsAsR51BuiltThem() {
        val ones = (0 until 12).map { r -> val p = 100.0 + r; Bar(day.atTime(9, 15).plusMinutes(r.toLong()), p, p + 0.5, p - 0.5, p + 0.2) }
            .filter { it.start.minute != 22 }                      // a missing minute inside the second bar
        val d = PassedArms.DayBars.of(day, ones)
        val b = PassedArms.bars5(d, d.lastRow)
        // Rows 0..11 known: bars 0 (09:15-09:19) and 1 (09:20-09:24) complete; bar 2 (rows 10..14) is not.
        assertEquals(2, b.size)
        assertEquals(PassedArms.Bar5(day, 0, 100.0, 104.5, 99.5, 104.2), b[0])
        assertEquals(105.0, b[1].open); assertEquals(109.2, b[1].close); assertEquals(109.5, b[1].high)
        assertEquals(t(9, 20), b[0].closesAt); assertEquals(5, b[0].entryRow)
        // A bar with no close at all is skipped.
        val gap = PassedArms.DayBars.of(day, ones.filter { it.start.minute !in 15..19 })
        assertEquals(1, PassedArms.bars5(gap, gap.lastRow).first().b)
    }

    // ---- premium exits ---------------------------------------------------------------------------------------------------

    private val r51 = DonchianArm.EXITS
    private val layer = PassedArms.PremiumExits(0.30, null, 60, listOf(0.40 to 0.10), LocalTime.of(15, 10))

    @Test fun theStopIsOnTheLowAndWinsATieWithTheTarget() {
        // Fill 100 at 10:00: stop 85, target 130. One minute touches both: the stop.
        val w = PassedArms.exitOnBars(100.0, t(10, 0), listOf(bar(10, 0, 100.0, 131.0, 84.0, 100.0)), null, t(10, 1), r51)
        assertEquals(PassedArms.ExitKind.STOP, w.exit!!.kind)
        assertEquals(85.0, w.exit!!.price, 1e-9)
        // A gap through the stop fills at the open (the worse price).
        val g = PassedArms.exitOnBars(100.0, t(10, 0), listOf(flat(10, 0, 100.0), bar(10, 1, 80.0, 82.0, 79.0, 81.0)), null, t(10, 2), r51)
        assertEquals(80.0, g.exit!!.price)
        assertEquals(t(10, 1), g.exit!!.at)
    }

    @Test fun theTargetIsALimitAtItsPrice() {
        val w = PassedArms.exitOnBars(100.0, t(10, 0), listOf(flat(10, 0, 100.0), bar(10, 1, 120.0, 140.0, 119.0, 139.0)), null, t(10, 2), r51)
        assertEquals(PassedArms.ExitKind.TARGET, w.exit!!.kind)
        assertEquals(130.0, w.exit!!.price, 1e-9)
        assertEquals(130.0, PassedArms.booked(PassedArms.ExitKind.TARGET, 133.0, 130.0))
        assertEquals(127.0, PassedArms.booked(PassedArms.ExitKind.TARGET, 127.0, 130.0))
        assertEquals(84.0, PassedArms.booked(PassedArms.ExitKind.STOP, 84.0, 130.0))
    }

    /** 20 Oct, review: a stop or lock found on a closed minute is never booked better than the walk's price (a wick that recovered). */
    @Test fun aStopOrLockIsBookedNoBetterThanTheWalk() {
        val k = PassedArms.ExitKind.values()
        // A -15% wick at 09:31 that recovered to 95 by the next look: booked at the stop's 85, not 95.
        assertEquals(85.0, PassedArms.booked(PassedArms.ExitKind.STOP, 95.0, 130.0, 85.0), 1e-9)
        // A worse fill (slippage through the stop) is booked as it came.
        assertEquals(82.0, PassedArms.booked(PassedArms.ExitKind.STOP, 82.0, 130.0, 85.0), 1e-9)
        assertEquals(110.0, PassedArms.booked(PassedArms.ExitKind.LOCK, 114.0, 130.0, 110.0), 1e-9)
        // No walk price (or an unusable one): the fill, as before.
        for (w in listOf(null, Double.NaN, 0.0, -1.0)) assertEquals(95.0, PassedArms.booked(PassedArms.ExitKind.STOP, 95.0, 130.0, w), 1e-9)
        // The target keeps its own rule (the target at most) whatever the walk says; time and square-off are the fill.
        assertEquals(130.0, PassedArms.booked(PassedArms.ExitKind.TARGET, 133.0, 130.0, 131.0), 1e-9)
        assertEquals(97.0, PassedArms.booked(PassedArms.ExitKind.TIME, 97.0, 130.0, 90.0), 1e-9)
        assertEquals(97.0, PassedArms.booked(PassedArms.ExitKind.SQUARE_OFF, 97.0, 130.0, 90.0), 1e-9)
        assertEquals(5, k.size)
    }

    @Test fun theTimeStopSellsAtTheCloseOfItsLastMinute() {
        val bars = (0 until 30).map { flat(10, it, 100.0 + it * 0.1) }
        // 29 minutes closed: held.
        assertNull(PassedArms.exitOnBars(100.0, t(10, 0), bars.take(29), 102.0, t(10, 29), r51).exit)
        val w = PassedArms.exitOnBars(100.0, t(10, 0), bars, 103.0, t(10, 30), r51)
        assertEquals(PassedArms.ExitKind.TIME, w.exit!!.kind)
        assertEquals(t(10, 30), w.exit!!.at)
        assertEquals(102.9, w.exit!!.price, 1e-9)
        // The last minute's bar not in yet (a stale feed): the clock still sells, at the price now.
        val late = PassedArms.exitOnBars(100.0, t(10, 0), bars.take(10), 101.5, t(10, 31), r51)
        assertEquals(PassedArms.ExitKind.TIME, late.exit!!.kind); assertEquals(101.5, late.exit!!.price)
        // No price at all: the last bar, else the fill.
        assertEquals(100.9, PassedArms.exitOnBars(100.0, t(10, 0), bars.take(10), null, t(10, 31), r51).exit!!.price, 1e-9)
        assertEquals(100.0, PassedArms.exitOnBars(100.0, t(10, 0), emptyList(), null, t(10, 31), r51).exit!!.price)
        assertEquals(t(10, 30), PassedArms.timeExitAt(t(10, 0), r51))
        assertNull(PassedArms.timeExitAt(t(10, 0), Solo2.exits(Solo2.Setup.S1)))
    }

    @Test fun theLockIsEarnedOnAHighAndHoldsFromTheNextMinute() {
        // +40% reached at 10:01 (high 140): +10% (110) locked from 10:02; 10:02 dips to 109 - the lock sells at 110.
        val bars = listOf(flat(10, 0, 100.0), bar(10, 1, 120.0, 140.0, 118.0, 125.0), bar(10, 2, 115.0, 116.0, 109.0, 112.0))
        val w = PassedArms.exitOnBars(100.0, t(10, 0), bars, null, t(10, 3), layer)
        assertEquals(PassedArms.ExitKind.LOCK, w.exit!!.kind)
        assertEquals(110.0, w.exit!!.price, 1e-9)
        assertEquals(140.0, w.peak)
        // The minute that earned it does not use it: a dip in the same minute below 110 is no exit.
        val same = PassedArms.exitOnBars(100.0, t(10, 0), listOf(bar(10, 0, 100.0, 141.0, 105.0, 120.0)), 121.0, t(10, 1), layer)
        assertNull(same.exit); assertEquals(110.0, same.lock!!, 1e-9)
        // Between bars the price now is held against the lock.
        val now = PassedArms.exitOnBars(100.0, t(10, 0), listOf(bar(10, 0, 100.0, 141.0, 105.0, 120.0)), 108.0, t(10, 1).plusSeconds(20), layer)
        assertEquals(PassedArms.ExitKind.LOCK, now.exit!!.kind); assertEquals(108.0, now.exit!!.price)
    }

    @Test fun thePriceNowSellsAtTheStopOrTheTargetBetweenBars() {
        val stop = PassedArms.exitOnBars(100.0, t(10, 0), listOf(flat(10, 0, 100.0)), 84.0, t(10, 1).plusSeconds(30), r51)
        assertEquals(PassedArms.ExitKind.STOP, stop.exit!!.kind); assertEquals(84.0, stop.exit!!.price)
        val tgt = PassedArms.exitOnBars(100.0, t(10, 0), listOf(flat(10, 0, 100.0)), 131.0, t(10, 1).plusSeconds(30), r51)
        assertEquals(PassedArms.ExitKind.TARGET, tgt.exit!!.kind); assertEquals(130.0, tgt.exit!!.price, 1e-9)
        assertNull(PassedArms.exitOnBars(100.0, t(10, 0), listOf(flat(10, 0, 100.0)), 0.0, t(10, 1), r51).exit)
    }

    @Test fun theSquareOffAndARestartFindTheSameAnswer() {
        val x = PassedArms.PremiumExits(null, null, null, emptyList(), LocalTime.of(15, 15))
        assertNull(PassedArms.exitOnBars(100.0, t(15, 0), listOf(flat(15, 0, 101.0)), 101.0, t(15, 14), x).exit)
        val sq = PassedArms.exitOnBars(100.0, t(15, 0), listOf(flat(15, 0, 101.0), flat(15, 15, 90.0)), 99.0, t(15, 15), x)
        assertEquals(PassedArms.ExitKind.SQUARE_OFF, sq.exit!!.kind); assertEquals(99.0, sq.exit!!.price)
        // A restart reads the same bars again (unsorted, a duplicate, a missing minute, bars before the entry): the same exit.
        val bars = listOf(flat(10, 3, 90.0), flat(9, 59, 50.0), flat(10, 0, 100.0), bar(10, 2, 86.0, 86.0, 84.0, 85.0), flat(10, 0, 100.0),
            bar(10, 4, Double.NaN, Double.NaN, Double.NaN, Double.NaN))
        val a = PassedArms.exitOnBars(100.0, t(10, 0), bars, null, t(10, 5), r51)
        val b = PassedArms.exitOnBars(100.0, t(10, 0), bars.reversed(), null, t(10, 5), r51)
        assertEquals(a, b)
        assertEquals(t(10, 2), a.exit!!.at)
        // Not a price: nothing.
        assertNull(PassedArms.exitOnBars(0.0, t(10, 0), bars, 1.0, t(10, 5), r51).exit)
        // Bars still forming (not closed by now) are not read.
        assertNull(PassedArms.exitOnBars(100.0, t(10, 0), listOf(bar(10, 0, 100.0, 100.0, 50.0, 60.0)), 100.0, t(10, 0).plusSeconds(30), r51).exit)
    }

    // ---- the forward bar ----------------------------------------------------------------------------------------------------

    // ---- R64's 30-60-100 lock (A4) ------------------------------------------------------------------------------------------

    private val a4 = PassedArms.withLock(DonchianArm.EXITS)

    @Test fun theLockKeepsTheArmsOwnStopAndTimeAndDropsTheTarget() {
        assertEquals(PassedArms.PremiumExits(0.15, null, 30, PassedArms.LOCK_306100, LocalTime.of(15, 15)), a4)
        val oov = PassedArms.withLock(OovArm.EXITS)
        assertEquals(0.30, oov.stop); assertNull(oov.target); assertEquals(60, oov.tmaxMinutes); assertEquals(PassedArms.LOCK_306100, oov.steps)
        assertEquals(LocalTime.of(15, 10), oov.squareOff)
        for (s in Solo2.Setup.entries) {
            val x = PassedArms.withLock(Solo2.exits(s))
            assertNull(x.target); assertEquals(PassedArms.LOCK_306100, x.steps)
            assertEquals(Solo2.exits(s).stop, x.stop); assertEquals(Solo2.exits(s).tmaxMinutes, x.tmaxMinutes)
        }
        assertNull(PassedArms.targetLevel(100.0, a4))
        // The steps: breakeven (NaN) once +30%, +30% once +60%, +60% once +100%.
        assertTrue(PassedArms.LOCK_306100[0].second.isNaN())
        assertEquals(listOf(0.30, 0.60, 1.00), PassedArms.LOCK_306100.map { it.first })
    }

    @Test fun aBigMoverIsHeldPastThirtyPercentWithTheLock() {
        // +40% at 10:01: the research exits sell at the +30% target; the lock holds it to the 30-minute time exit.
        val bars = listOf(flat(10, 0, 100.0), bar(10, 1, 120.0, 140.0, 118.0, 135.0)) + (2 until 30).map { flat(10, it, 136.0) }
        assertEquals(PassedArms.ExitKind.TARGET, PassedArms.exitOnBars(100.0, t(10, 0), bars, null, t(10, 30), r51).exit!!.kind)
        val w = PassedArms.exitOnBars(100.0, t(10, 0), bars, null, t(10, 30), a4, breakeven = 101.0)
        assertEquals(PassedArms.ExitKind.TIME, w.exit!!.kind); assertEquals(136.0, w.exit!!.price)
        assertEquals(101.0, w.lock!!, 1e-9)
    }

    @Test fun theLockIsBreakevenPlusChargesFromTheNextMinuteNeverTheSameMinute() {
        // 10:01 reaches +31% and dips to 95 in the same minute: the lock is not active yet and the -15% stop (85) is not hit.
        val bars = listOf(flat(10, 0, 100.0), bar(10, 1, 100.0, 131.0, 95.0, 120.0), bar(10, 2, 102.0, 103.0, 100.5, 101.0))
        val held = PassedArms.exitOnBars(100.0, t(10, 0), bars.take(2), null, t(10, 2), a4, breakeven = 101.0)
        assertNull(held.exit); assertEquals(101.0, held.lock!!, 1e-9)
        // 10:02 dips under breakeven 101: sold at 101 (a lock).
        val w = PassedArms.exitOnBars(100.0, t(10, 0), bars, null, t(10, 3), a4, breakeven = 101.0)
        assertEquals(PassedArms.ExitKind.LOCK, w.exit!!.kind); assertEquals(101.0, w.exit!!.price, 1e-9)
        // No breakeven given: the fill plus ProfitLock's fallback 0.3%.
        assertEquals(100.3, PassedArms.exitOnBars(100.0, t(10, 0), bars.take(2), null, t(10, 2), a4).lock!!, 1e-9)
    }

    @Test fun theLockSteps60And100AndOnlyRises() {
        // +65% at 10:01, +105% at 10:02 (lows stay high): 160 locked from 10:03; 10:03 dips to 150 -> sold at 160, never 130.
        val bars = listOf(flat(10, 0, 100.0), bar(10, 1, 160.0, 165.0, 158.0, 162.0), bar(10, 2, 170.0, 205.0, 168.0, 190.0),
            bar(10, 3, 175.0, 176.0, 150.0, 151.0))
        val w = PassedArms.exitOnBars(100.0, t(10, 0), bars, null, t(10, 4), a4, breakeven = 101.0)
        assertEquals(PassedArms.ExitKind.LOCK, w.exit!!.kind); assertEquals(160.0, w.exit!!.price, 1e-9)
        // A gap through the lock fills at the open (the worse price).
        val gap = bars.take(3) + bar(10, 3, 140.0, 141.0, 139.0, 140.0)
        assertEquals(140.0, PassedArms.exitOnBars(100.0, t(10, 0), gap, null, t(10, 4), a4, breakeven = 101.0).exit!!.price)
        // Between bars, the price now under the lock sells (a lock).
        val now = PassedArms.exitOnBars(100.0, t(10, 0), bars.take(3), 150.0, t(10, 3), a4, breakeven = 101.0)
        assertEquals(PassedArms.ExitKind.LOCK, now.exit!!.kind)
    }

    @Test fun theOwnStopStillSellsWithTheLock() {
        val w = PassedArms.exitOnBars(100.0, t(10, 0), listOf(flat(10, 0, 100.0), bar(10, 1, 99.0, 100.0, 84.0, 86.0)), null, t(10, 2), a4, breakeven = 101.0)
        assertEquals(PassedArms.ExitKind.STOP, w.exit!!.kind); assertEquals(85.0, w.exit!!.price, 1e-9)
        // No bars yet, no price: nothing; the square-off still sells.
        assertNull(PassedArms.exitOnBars(100.0, t(10, 0), emptyList(), null, t(10, 0), a4, breakeven = 101.0).exit)
        assertEquals(PassedArms.ExitKind.SQUARE_OFF, PassedArms.exitOnBars(100.0, t(12, 0), emptyList(), 100.0, t(14, 30),
            PassedArms.withLock(Solo2.exits(Solo2.Setup.S1)), breakeven = 101.0).exit!!.kind)
    }

    // ---- R66's L25: +5% locked once +25%, on top of A4 (16 Oct, Boss's choice: "Select the best") ---------------------------

    private val l25 = PassedArms.withLock(DonchianArm.EXITS, l25 = true)

    @Test fun l25AddsOneStepBeforeTheA4LadderAndKeepsTheRest() {
        assertEquals(PassedArms.withLock(DonchianArm.EXITS), PassedArms.withLock(DonchianArm.EXITS, l25 = false))
        assertEquals(listOf(0.25 to 0.05), PassedArms.LOCK_L25.take(1))
        assertEquals(PassedArms.LOCK_306100, PassedArms.LOCK_L25.drop(1))
        assertEquals(PassedArms.PremiumExits(0.15, null, 30, PassedArms.LOCK_L25, LocalTime.of(15, 15)), l25)
        for (x in listOf(OovArm.EXITS) + Solo2.Setup.entries.map { Solo2.exits(it) }) {
            val w = PassedArms.withLock(x, l25 = true)
            assertNull(w.target); assertEquals(PassedArms.LOCK_L25, w.steps); assertEquals(x.stop, w.stop); assertEquals(x.tmaxMinutes, w.tmaxMinutes)
            assertEquals(x.squareOff, w.squareOff)
        }
    }

    @Test fun l25ReachedTwentyFivePercentThenBackToFiveSellsAtAboutFive() {
        // +26% at 10:01, then back down through 105 at 10:02: sold at 105 (+5%), a lock. A4 alone holds it (its stop is 85).
        val bars = listOf(flat(10, 0, 100.0), bar(10, 1, 110.0, 126.0, 108.0, 120.0), bar(10, 2, 112.0, 113.0, 98.0, 99.0)) +
            (3 until 30).map { flat(10, it, 99.0) }
        val w = PassedArms.exitOnBars(100.0, t(10, 0), bars, null, t(10, 30), l25, breakeven = 101.0)
        assertEquals(PassedArms.ExitKind.LOCK, w.exit!!.kind); assertEquals(105.0, w.exit!!.price, 1e-9); assertEquals(t(10, 2), w.exit!!.at)
        val a = PassedArms.exitOnBars(100.0, t(10, 0), bars, null, t(10, 30), a4, breakeven = 101.0)
        assertEquals(PassedArms.ExitKind.TIME, a.exit!!.kind); assertEquals(99.0, a.exit!!.price)
        // Under +25% (124.90): no lock yet.
        assertNull(PassedArms.exitOnBars(100.0, t(10, 0), listOf(flat(10, 0, 100.0), bar(10, 1, 110.0, 124.9, 108.0, 120.0)), null, t(10, 2), l25).lock)
    }

    @Test fun l25IsEarnedByAMinutesHighAndActsFromTheNextMinute() {
        // 10:01 reaches +26% and dips to 104 in the same minute: not sold (the step acts from 10:02); 105 is held.
        val bars = listOf(flat(10, 0, 100.0), bar(10, 1, 110.0, 126.0, 104.0, 118.0))
        val w = PassedArms.exitOnBars(100.0, t(10, 0), bars, null, t(10, 2), l25, breakeven = 101.0)
        assertNull(w.exit); assertEquals(105.0, w.lock!!, 1e-9)
        // Between bars, the price now under it sells (a lock), at the price now.
        val now = PassedArms.exitOnBars(100.0, t(10, 0), bars, 104.5, t(10, 2).plusSeconds(20), l25, breakeven = 101.0)
        assertEquals(PassedArms.ExitKind.LOCK, now.exit!!.kind); assertEquals(104.5, now.exit!!.price, 1e-9)
    }

    @Test fun l25AGapThroughItSellsAtThatMinutesOpen() {
        val bars = listOf(flat(10, 0, 100.0), bar(10, 1, 110.0, 126.0, 108.0, 120.0), bar(10, 2, 100.0, 101.0, 96.0, 97.0))
        val w = PassedArms.exitOnBars(100.0, t(10, 0), bars, null, t(10, 3), l25, breakeven = 101.0)
        assertEquals(PassedArms.ExitKind.LOCK, w.exit!!.kind); assertEquals(100.0, w.exit!!.price, 1e-9)
    }

    @Test fun l25ThenSixtyPercentLocksThirtyAndTheLockNeverLowers() {
        // +26% at 10:01, +62% at 10:02: +30% (130) locked from 10:03; 10:03 dips to 120 -> sold at 130.
        val bars = listOf(flat(10, 0, 100.0), bar(10, 1, 110.0, 126.0, 108.0, 120.0), bar(10, 2, 130.0, 162.0, 128.0, 150.0),
            bar(10, 3, 140.0, 141.0, 120.0, 121.0))
        val w = PassedArms.exitOnBars(100.0, t(10, 0), bars, null, t(10, 4), l25, breakeven = 101.0)
        assertEquals(PassedArms.ExitKind.LOCK, w.exit!!.kind); assertEquals(130.0, w.exit!!.price, 1e-9)
        // +31% reached (the breakeven step, 101): the lock stays at +5% (105), never back down to breakeven.
        val b31 = listOf(flat(10, 0, 100.0), bar(10, 1, 110.0, 131.0, 108.0, 120.0), bar(10, 2, 110.0, 111.0, 102.0, 103.0))
        val k = PassedArms.exitOnBars(100.0, t(10, 0), b31, null, t(10, 3), l25, breakeven = 101.0)
        assertEquals(PassedArms.ExitKind.LOCK, k.exit!!.kind); assertEquals(105.0, k.exit!!.price, 1e-9)
        // A breakeven above +5% (costly charges on a cheap option): the breakeven step raises it.
        assertEquals(106.0, PassedArms.exitOnBars(100.0, t(10, 0), b31.take(2), null, t(10, 2), l25, breakeven = 106.0).lock!!, 1e-9)
        // A lock seen earned before (restart, the bars lost): it holds; a lower step later never lowers it.
        val kept = PassedArms.exitOnBars(100.0, t(10, 0), listOf(flat(10, 0, 100.0)), 104.0, t(10, 5), l25, known = t(10, 2) to 105.0, breakeven = 101.0)
        assertEquals(PassedArms.ExitKind.LOCK, kept.exit!!.kind); assertEquals(105.0, kept.lock!!, 1e-9)
    }

    @Test fun theFortyTradeBarIsJudgedOnce() {
        val F = PassedArms.ForwardBar
        assertEquals(PassedArms.ForwardBar.Verdict.RUNNING, F.judge(List(39) { -100.0 }, List(39) { 10.0 }).verdict)
        val lost = F.judge(List(40) { -1.0 }, List(40) { 1.0 })
        assertEquals(PassedArms.ForwardBar.Verdict.FAILED_NET, lost.verdict)
        assertTrue(F.switchOff(lost.verdict))
        // Positive, but not by more than the spread it paid: at twice the spread it loses.
        val thin = F.judge(List(40) { 10.0 }, List(40) { 11.0 })
        assertEquals(PassedArms.ForwardBar.Verdict.FAILED_SPREAD, thin.verdict)
        assertEquals(-40.0, thin.netAtTwiceSpread, 1e-9)
        assertTrue(F.switchOff(thin.verdict))
        val ok = F.judge(List(40) { 100.0 }, List(40) { 20.0 })
        assertEquals(PassedArms.ForwardBar.Verdict.PASSED, ok.verdict)
        assertFalse(F.switchOff(ok.verdict)); assertFalse(F.switchOff(PassedArms.ForwardBar.Verdict.RUNNING))
        assertEquals(40, ok.wins)
        // Boss switched it back on after the verdict: not judged again.
        assertEquals(PassedArms.ForwardBar.Verdict.RUNNING, F.judge(List(45) { -100.0 }, List(45) { 1.0 }, base = 40).verdict)
        assertEquals("Forward test: 40 of 40 paper trades, net ₹4,000 after charges, at twice the spread ₹3,200", F.line(ok))
        assertTrue(F.line(F.judge(List(41) { -1.0 }, emptyList())).contains("(41 in all)"))
        assertTrue(F.BAR.contains("40 paper trades") && F.BAR.contains("twice the spread"))
    }

    @Test fun theBarIsTheFirstFortyTradesSoALaterDipNeverReJudgesIt() {
        val F = PassedArms.ForwardBar
        val nets = List(40) { 100.0 } + List(40) { -200.0 }
        val c = F.judge(nets, List(80) { 10.0 })
        assertEquals(PassedArms.ForwardBar.Verdict.PASSED, c.verdict)
        assertEquals(4_000.0, c.judgedNet, 1e-9); assertEquals(400.0, c.judgedSpread, 1e-9)
        assertEquals(-4_000.0, c.net, 1e-9)
        assertEquals(PassedArms.ForwardBar.Verdict.FAILED_NET, F.judge(List(40) { -1.0 } + List(10) { 100.0 }, emptyList()).verdict)
    }

    @Test fun aLockSeenEarnedBeforeHoldsWhenTheBarsThatEarnedItAreGone() {
        // An earlier look saw +10% locked (110) by 10:05; now only bars from 10:06 are in (the feed lost the earlier ones).
        val known = t(10, 5) to 110.0
        val w = PassedArms.exitOnBars(100.0, t(10, 0), listOf(bar(10, 6, 112.0, 113.0, 109.0, 111.0)), null, t(10, 7), layer, known)
        assertEquals(PassedArms.ExitKind.LOCK, w.exit!!.kind); assertEquals(110.0, w.exit!!.price, 1e-9)
        // Bars from before the time it was seen are never judged against it.
        assertNull(PassedArms.exitOnBars(100.0, t(10, 0), listOf(bar(10, 2, 100.0, 101.0, 99.0, 100.0)), null, t(10, 3), layer, t(10, 5) to 110.0).exit)
        // No bars, but the price now: held against it too.
        assertEquals(PassedArms.ExitKind.LOCK, PassedArms.exitOnBars(100.0, t(10, 0), emptyList(), 108.0, t(10, 9), layer, known).exit!!.kind)
        // A lower known lock never lowers one the bars earn.
        val hi = PassedArms.exitOnBars(100.0, t(10, 0), listOf(bar(10, 6, 106.0, 141.0, 106.0, 120.0)), 120.0, t(10, 7), layer, t(10, 6) to 105.0)
        assertEquals(110.0, hi.lock!!, 1e-9)
    }

    // ---- 16 Oct: a second trade on the same option the same day ------------------------------------------------------------

    @Test fun aReEntryOnTheSameOptionIsWalkedFromItsOwnEntryAndItsOwnFill() {
        val x = DonchianArm.EXITS
        // The first trade: 09:40 at 100, out at its target (130) by 09:50. The bars of that morning stay in the feed.
        val morning = (0 until 40).map { i -> val m = t(9, 40).plusMinutes(i.toLong()); Bar(m, 100.0 + i, 101.0 + i, 99.0 + i, 100.0 + i) }
        // The re-entry: 10:20 at 122.20 (the 16 Oct trade); its target is 158.86.
        val e = 122.20
        val tgt = PassedArms.targetLevel(e, x)!!
        assertEquals(158.86, tgt, 1e-9)
        val after = (0 until 8).map { i -> bar(10, 20 + i, 122.0 + 3 * i, 123.0 + 3 * i, 121.0 + 3 * i, 122.0 + 3 * i) }
        // 10:28: the minute's high went over 159 - sold at the target (a limit: booked at 158.86), not at 158.40 or later.
        val spike = bar(10, 28, 156.0, 159.10, 155.0, 158.40)
        val w = PassedArms.exitOnBars(e, t(10, 20), morning + after + spike, 158.40, t(10, 29), x)
        assertEquals(PassedArms.ExitKind.TARGET, w.exit!!.kind); assertEquals(tgt, w.exit!!.price, 1e-9)
        // The morning's bars (all above the re-entry's stop, some above 130) never decide it: nothing before 10:20 is read.
        assertNull(PassedArms.exitOnBars(e, t(10, 20), morning, 125.0, t(10, 21), x).exit)
        // Between bars, the price now over the target sells too (no option bars at all: the stream's price alone).
        assertEquals(PassedArms.ExitKind.TARGET, PassedArms.exitOnBars(e, t(10, 20), emptyList(), 159.0, t(10, 28), x).exit!!.kind)
        // Its 30 minutes: out at 10:50 with no bars and no price at all (the clock alone).
        val time = PassedArms.exitOnBars(e, t(10, 20), emptyList(), null, t(10, 50), x).exit!!
        assertEquals(PassedArms.ExitKind.TIME, time.kind)
        assertNull(PassedArms.exitOnBars(e, t(10, 20), emptyList(), null, t(10, 49), x).exit)
        // Its stop: -15% (103.87).
        assertEquals(PassedArms.ExitKind.STOP, PassedArms.exitOnBars(e, t(10, 20), listOf(bar(10, 22, 110.0, 111.0, 103.0, 104.0)), 104.0, t(10, 23), x).exit!!.kind)
        // A lock the FIRST trade earned (seen at 09:45, level 120) is never this trade's: at 118 it is not sold on it.
        val layered = PassedArms.PremiumExits(0.15, 0.30, 30, listOf(0.10 to 0.05), x.squareOff)
        assertNull(PassedArms.exitOnBars(e, t(10, 20), emptyList(), 118.0, t(10, 21), layered, t(9, 45) to 120.0).exit)
        // One seen during this trade still holds.
        assertEquals(PassedArms.ExitKind.LOCK, PassedArms.exitOnBars(e, t(10, 20), emptyList(), 125.0, t(10, 25), layered, t(10, 22) to 128.0).exit!!.kind)
    }

    @Test fun theSpreadPaidAndTheWords() {
        // Bought 100.5 against 100 seen, sold 119.4 against 120 seen, 65 units: (0.5 + 0.6) x 65.
        assertEquals(71.5, PassedArms.spreadPaid(65, 100.5, 100.0, 119.4, 120.0, 0.0016), 1e-9)
        // Unknown reference prices: the index's half-spread each way.
        assertEquals((100.0 * 0.002 + 120.0 * 0.002) * 10, PassedArms.spreadPaid(10, 100.0, null, 120.0, 0.0, 0.002), 1e-9)
        // A fill better than the price seen pays nothing that way.
        assertEquals(0.0, PassedArms.spreadPaid(10, 99.0, 100.0, 121.0, 120.0, 0.002), 1e-9)
        assertEquals("−₹1,235", PassedArms.rs(-1234.6)); assertEquals("₹5", PassedArms.rs(5.0))
        assertEquals(15.35, PassedArms.floorTick(15.38), 1e-9)
        assertTrue(PassedArms.PASSED.startsWith("Passed research") && PassedArms.PASSED.contains("paper book") && PassedArms.PASSED.contains("Shadow"))
        assertNotNull(PassedArms.SIGNAL_INDEX)
    }
}
