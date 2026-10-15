package com.optionslab.ira

import com.optionslab.engine.orb.HeroRules
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pre-trade checks' review fixes (13 Oct 2026): the danger meter read before and after the open, VIX only from a fresh
 * snapshot, expiry-day minutes, the time-of-day edges, NaN / infinite inputs, Hero's window from highs and lows, the
 * half-size switch on Hero's lots, and the shadow notes' pruning.
 */
class PreTradeReviewTest {
    private val wed = LocalDate.of(2026, 10, 14)            // a Wednesday; Tuesday 13 Oct is the previous session
    private val weekdays: (LocalDate) -> Boolean = { it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY }
    private val w = RangeModel.Window(57_100.0, 57_000.0, 57_160.0, 57_000.0, 57_050.0, 11 * 60)

    // ---- the danger meter before and after the open ----

    @Test fun preOpenReadingIsProvisionalThenTheReReadAfterTheOpenHasTheGap() {
        val prev = DangerMeter.previousSession(wed, weekdays)
        assertEquals(wed.minusDays(1), prev)
        // 09:00: the last bars are yesterday's; VIX closed at 12 after 11 the day before (+9%: "up").
        val bnYesterday = DangerMeter.Quote(prev!!.atTime(15, 29), 57_000.0, 56_800.0, 56_900.0)
        val vixYesterday = DangerMeter.Quote(prev.atTime(15, 29), 12.0, 11.0, 11.2)
        val morning = DangerMeter.inputs(wed, prev, bnYesterday, vixYesterday, event = false, expiry = true)
        assertNull(morning.gapPct)
        assertEquals(12.0, morning.vixPrev)
        assertEquals(1.0 / 11.0, morning.vixChange!!, 1e-12)
        val r0 = DangerMeter.read(morning)
        assertTrue(r0.provisional)
        assertTrue(r0.line().contains("provisional, gap not known yet"), r0.line())
        assertFalse(r0.used.any { it.feature == "gap" })
        // 09:16: today's first bar is in. BANKNIFTY opened 0.05% above its close (a small gap, the worst bucket); VIX now
        // trades today, so its change is no longer in the snapshot and the morning's is kept.
        val bnOpen = DangerMeter.Quote(wed.atTime(9, 15), 57_040.0, 57_000.0, 57_028.5)
        val vixOpen = DangerMeter.Quote(wed.atTime(9, 15), 12.4, 12.0, 12.3)
        val after = DangerMeter.inputs(wed, prev, bnOpen, vixOpen, event = false, expiry = true, earlier = morning)
        assertEquals(0.05, after.gapPct!!, 1e-9)
        assertEquals(12.0, after.vixPrev)
        assertEquals(morning.vixChange, after.vixChange)
        val r1 = DangerMeter.read(after)
        assertFalse(r1.provisional)
        assertTrue(r1.unknown.isEmpty())
        assertEquals("small", r1.used.first { it.feature == "gap" }.bucket)
        assertTrue(r1.score < r0.score, "the small gap pulls the score down: ${r0.score} -> ${r1.score}")
        assertFalse(r1.line().contains("provisional"), r1.line())
        assertTrue(r1.line().contains("the simulated arms made"), r1.line())
        // Without a morning read, the 09:16 read alone still has the gap; VIX's change is then simply unknown.
        val alone = DangerMeter.read(DangerMeter.inputs(wed, prev, bnOpen, vixOpen, event = false, expiry = true))
        assertFalse(alone.provisional)
        assertEquals(listOf("VIX change"), alone.unknown)
    }

    @Test fun theGapNeedsTodaysBarAndUsablePrices() {
        assertNull(DangerMeter.gap(null, wed))
        assertNull(DangerMeter.gap(DangerMeter.Quote(wed.minusDays(1).atTime(15, 29), 1.0, 1.0, 1.0), wed))
        assertNull(DangerMeter.gap(DangerMeter.Quote(wed.atTime(9, 15), 1.0, null, 1.0), wed))
        assertNull(DangerMeter.gap(DangerMeter.Quote(wed.atTime(9, 15), 1.0, 0.0, 1.0), wed))
        assertNull(DangerMeter.gap(DangerMeter.Quote(wed.atTime(9, 15), 1.0, 100.0, Double.NaN), wed))
        assertNull(DangerMeter.gap(DangerMeter.Quote(wed.atTime(9, 15), 1.0, Double.POSITIVE_INFINITY, 100.0), wed))
        assertEquals(-1.0, DangerMeter.gap(DangerMeter.Quote(wed.atTime(9, 20), 99.0, 100.0, 99.0), wed)!!, 1e-12)
        // A NaN gap handed in directly counts as unknown, never as a bucket.
        assertTrue(DangerMeter.read(DangerMeter.Inputs(Double.NaN, 12.0, Double.NaN, false, false, DayOfWeek.MONDAY)).unknown.containsAll(listOf("the gap", "VIX change")))
    }

    // ---- VIX only from a fresh snapshot ----

    @Test fun aStaleVixSnapshotIsUnknownNotYesterdays() {
        val prev = DangerMeter.previousSession(wed, weekdays)
        // Days old: Friday's snapshot read on Wednesday.
        val friday = DangerMeter.Quote(LocalDate.of(2026, 10, 9).atTime(15, 29), 12.0, 11.0, 11.0)
        assertEquals(DangerMeter.Vix(null, null), DangerMeter.vix(friday, wed, prev))
        val i = DangerMeter.inputs(wed, prev, null, friday, event = false, expiry = false)
        assertNull(i.vixPrev); assertNull(i.vixChange)
        assertTrue(DangerMeter.read(i).unknown.containsAll(listOf("VIX", "VIX change")))
        // Today's bar: its previous close; the previous session's: its price and change.
        assertEquals(DangerMeter.Vix(11.0, null), DangerMeter.vix(friday.copy(at = wed.atTime(10, 0)), wed, prev))
        assertEquals(12.0, DangerMeter.vix(friday.copy(at = prev!!.atTime(15, 29)), wed, prev).prev)
        // No usable prices: unknown.
        assertEquals(DangerMeter.Vix(null, null), DangerMeter.vix(friday.copy(at = prev.atTime(15, 29), price = Double.NaN, prevClose = 0.0), wed, prev))
        assertEquals(DangerMeter.Vix(null, null), DangerMeter.vix(null, wed, prev))
        assertEquals(DangerMeter.Vix(null, null), DangerMeter.vix(friday, wed, null))
    }

    @Test fun overAHolidayGapTheSessionBeforeTheHolidayCountsAndNothingOlder() {
        // Friday 16 Oct a holiday: on Monday 19 Oct the previous session is Thursday 15 Oct.
        val holiday = LocalDate.of(2026, 10, 16)
        val trading: (LocalDate) -> Boolean = { weekdays(it) && it != holiday }
        val mon = LocalDate.of(2026, 10, 19)
        val prev = DangerMeter.previousSession(mon, trading)
        assertEquals(LocalDate.of(2026, 10, 15), prev)
        val thu = DangerMeter.Quote(prev!!.atTime(15, 29), 13.0, 12.0, 12.0)
        assertEquals(13.0, DangerMeter.vix(thu, mon, prev).prev)
        assertEquals(1.0 / 12.0, DangerMeter.vix(thu, mon, prev).change!!, 1e-12)
        // Wednesday's snapshot is two sessions old: not used.
        assertNull(DangerMeter.vix(thu.copy(at = LocalDate.of(2026, 10, 14).atTime(15, 29)), mon, prev).prev)
        // No session at all in the 30 days before: none.
        assertNull(DangerMeter.previousSession(mon) { false })
    }

    @Test fun theMeterOnASundayOrAHolidayReadsWithoutCrashing() {
        val sun = DangerMeter.read(DangerMeter.Inputs(0.5, 13.0, 0.0, event = false, expiry = false, day = DayOfWeek.SUNDAY))
        assertFalse(sun.used.any { it.feature == "dow" }, "no Sunday sessions in the model: the weekday counts as neutral")
        assertEquals(5, sun.used.size)
        assertEquals(DangerMeter.Level.NORMAL, sun.level)
        // A weekday holiday is read as that weekday (nothing trades; the morning's closed-day path does not read it).
        assertEquals(6, DangerMeter.read(DangerMeter.Inputs(0.5, 13.0, 0.0, false, false, DayOfWeek.FRIDAY)).used.size)
        // Sunday's previous session is Friday: Friday's VIX counts, a Thursday's does not.
        val sunday = LocalDate.of(2026, 10, 18)
        val prev = DangerMeter.previousSession(sunday, weekdays)
        assertEquals(LocalDate.of(2026, 10, 16), prev)
        val i = DangerMeter.inputs(sunday, prev, null, DangerMeter.Quote(prev!!.atTime(15, 29), 12.0, 12.0, 12.0), false, false)
        assertEquals(DayOfWeek.SUNDAY, i.day)
        assertEquals(12.0, i.vixPrev)
        assertTrue(DangerMeter.read(i).provisional)
    }

    // ---- expiry day, time of day, NaN and infinity ----

    @Test fun expiryDayNearTheCloseCountsOneMinuteNeverZero() {
        val expiry = wed
        assertEquals(1.0, OptionDelta.minutesTo(expiry, expiry.atTime(15, 29)))
        assertEquals(0.0, OptionDelta.minutesTo(expiry, LocalDateTime.of(expiry, java.time.LocalTime.of(15, 29, 30))))
        assertEquals(-5.0, OptionDelta.minutesTo(expiry, expiry.atTime(15, 35)))
        assertEquals(2 * 1440.0 + 60, OptionDelta.minutesTo(expiry.plusDays(2), expiry.atTime(14, 30)))
        for (m in listOf(1.0, 0.0, -5.0)) {
            val atm = OptionDelta.of(25_000.0, 25_000.0, m, 2.0, 12.0, true)!!
            assertTrue(atm.isFinite() && atm in 0.0..1.0, "ATM call at $m minutes: $atm")
            assertTrue(OptionDelta.delta(25_100.0, 25_000.0, m, 0.12, true) > 0.99)
            assertTrue(OptionDelta.delta(24_900.0, 25_000.0, m, 0.12, true) < 0.01)
            assertTrue(OptionDelta.price(25_000.0, 25_000.0, m, 0.12, false).isFinite())
        }
        assertTrue(OptionDelta.delta(25_000.0, 25_000.0, Double.NaN, 0.12, true).isFinite())
    }

    @Test fun pointsBeforeNineThirtyAndAfterThreeUseTheEdgeSlots() {
        fun at(m: Int) = RangeModel.points("NIFTY", w.copy(price = 25_000.0, high30 = 25_040.0, low30 = 25_000.0, high60 = 25_060.0, low60 = 25_000.0, minuteOfDay = m), 12.0)!!
        assertEquals(at(9 * 60 + 30), at(9 * 60 + 16), 1e-9)
        assertEquals(at(9 * 60 + 30), at(9 * 60 + 15), 1e-9)
        assertEquals(at(15 * 60), at(15 * 60 + 10), 1e-9)
        assertEquals(at(15 * 60), at(15 * 60 + 29), 1e-9)
        assertEquals(at(15 * 60), at(23 * 60), 1e-9)
        assertEquals(at(9 * 60 + 30), at(0), 1e-9)
        for (m in listOf(0, 9 * 60 + 16, 15 * 60 + 25, 24 * 60)) assertTrue(at(m) > 0 && at(m).isFinite())
        assertEquals(RangeModel.tod("BANKNIFTY", 15 * 60), RangeModel.tod("BANKNIFTY", 15 * 60 + 20))
    }

    @Test fun nanAndInfinityGiveNullNeverANumber() {
        val nan = Double.NaN; val inf = Double.POSITIVE_INFINITY
        for (v in listOf(nan, inf, -inf)) assertNull(RangeModel.points("BANKNIFTY", w, v), "vix $v")
        for (x in listOf(nan, inf)) {
            assertNull(RangeModel.points("BANKNIFTY", w.copy(price = x), 12.0), "price $x")
            assertNull(RangeModel.points("BANKNIFTY", w.copy(high30 = x), 12.0), "high30 $x")
            assertNull(RangeModel.points("BANKNIFTY", w.copy(low30 = x), 12.0), "low30 $x")
            assertNull(RangeModel.points("BANKNIFTY", w.copy(high60 = x), 12.0), "high60 $x")
            assertNull(RangeModel.points("BANKNIFTY", w.copy(low60 = -x), 12.0), "low60 ${-x}")
        }
        // A window: bars with a NaN or infinite side are left out; none usable, or no usable price: null.
        val bars = listOf(101.0 to 99.0, nan to 98.0, inf to 97.0, 103.0 to nan, 102.0 to 100.0)
        val x = RangeModel.window(bars, 1, 101.0, 600)!!
        assertEquals(102.0, x.high30); assertEquals(99.0, x.low30)
        assertNull(RangeModel.window(listOf(nan to nan, inf to 1.0), 1, 1.0, 600))
        assertNull(RangeModel.window(listOf(2.0 to 1.0), 1, nan, 600))
        assertNull(RangeModel.window(listOf(2.0 to 1.0), 1, inf, 600))
        assertNull(RangeModel.window(listOf(1.0 to 2.0), 1, 1.5, 600))       // a high below its low
        // The charges check: no usable premium costs 0 (never a thrown error); a NaN / infinite move is "not known".
        for (p in listOf(nan, inf, -inf)) assertEquals(0.0, ChargesCheck.costPerUnit(p, 30), "premium $p")
        for ((e, d) in listOf(nan to 0.5, inf to 0.5, 150.0 to nan, 150.0 to inf)) {
            val r = ChargesCheck.read(400.0, 30, e, d)
            assertNull(r.ratio, "move $e delta $d"); assertFalse(r.wouldSkip)
            assertTrue(r.line().contains("not known"), r.line())
            assertNull(SmartStop.points(e, d))
        }
        assertNull(OptionDelta.of(nan, 25_000.0, 600.0, 100.0, 12.0, true)?.takeIf { !it.isFinite() })
        assertNull(OptionDelta.impliedVol(inf, 25_000.0, 25_000.0, 600.0, true))
        assertNull(OptionDelta.of(25_000.0, 25_000.0, 600.0, 1e9, inf, true))
        assertEquals(0.5, OptionDelta.delta(inf, 25_000.0, 600.0, 0.12, true))
    }

    // ---- Hero's window from highs and lows ----

    @Test fun heroWindowReadsEachMinutesHighAndLow() {
        // Sixty NIFTY minutes, each closing at 25,000 + i with a 20-point high-low around the close.
        val minutes = (0 until 60).map { i -> Triple(25_010.0 + i, 24_990.0 + i, 25_000.0 + i) }
        val hl = RangeModel.window(minutes.map { RangeModel.highLow(it.first, it.second, it.third) }, 1, 25_059.0, 14 * 60)!!
        val closes = RangeModel.window(minutes.map { it.third to it.third }, 1, 25_059.0, 14 * 60)!!
        assertEquals(25_069.0, hl.high30); assertEquals(25_020.0, hl.low30)
        assertEquals(25_069.0, hl.high60); assertEquals(24_990.0, hl.low60)
        assertEquals(25_059.0, closes.high30); assertEquals(25_030.0, closes.low30)
        assertTrue(RangeModel.points("NIFTY", hl, 12.0)!! > RangeModel.points("NIFTY", closes, 12.0)!!)
        // A bar with no usable high / low counts its close alone; the close is always inside.
        assertEquals(10.0 to 10.0, RangeModel.highLow(0.0, 0.0, 10.0))
        assertEquals(10.0 to 10.0, RangeModel.highLow(Double.NaN, 9.0, 10.0))
        assertEquals(10.0 to 10.0, RangeModel.highLow(9.0, 11.0, 10.0))
        assertEquals(10.5 to 9.0, RangeModel.highLow(10.0, 9.0, 10.5))
        assertEquals(12.0 to 9.0, RangeModel.highLow(12.0, 9.0, 10.0))
    }

    // ---- the half-size switch on Hero's lots ----

    @Test fun halfSizeHalvesHerosPaperLotsOnlyWhenOnAndOnACarefulDay() {
        val planned = HeroRules.lots(2.0, 65)
        assertEquals(38, planned)
        val careful = DangerMeter.read(DangerMeter.Inputs(0.05, 13.0, 0.0, event = false, expiry = true, day = DayOfWeek.FRIDAY)).level
        assertEquals(DangerMeter.Level.CAREFUL, careful)
        assertEquals(19, DangerMeter.lots(planned, careful, halfOn = true))
        assertEquals(planned, DangerMeter.lots(planned, careful, halfOn = false))
        assertEquals(planned, DangerMeter.lots(planned, DangerMeter.Level.NORMAL, halfOn = true))
        // The provisional 09:00 reading may say careful; it never raises lots either way.
        for (lv in DangerMeter.Level.values()) for (n in listOf(0, 1, 2, 3, planned)) assertTrue(DangerMeter.lots(n, lv, true) <= maxOf(n, 1))
        // Hero has no live path (HeroRules: paper only), so the switch never reaches a live order.
        assertTrue(HeroRules.ARM.paperOnly)
    }

    // ---- the shadow notes' pruning and wording ----

    @Test fun notesKeepSevenDaysBeforeTodayAndAtMostThreeHundred() {
        val old = (0 until 5).map { wed.minusDays(8) to "old$it" }
        val edge = listOf(wed.minusDays(7) to "edge")
        val many = (0 until 310).map { wed to "n$it" }
        val kept = PreTradeNote.prune(old + edge + many, wed)
        assertEquals(PreTradeNote.MAX, kept.size)
        assertEquals("n10", kept.first().second)                       // the newest 300 of today's
        assertEquals("n309", kept.last().second)
        val few = PreTradeNote.prune(old + edge + many.take(3), wed)
        assertEquals(listOf("edge", "n0", "n1", "n2"), few.map { it.second })
        assertTrue(PreTradeNote.prune(emptyList<Pair<LocalDate, String>>(), wed).isEmpty())
        assertTrue(PreTradeNote.prune(many, wed, max = 0).isEmpty())
    }

    @Test fun theReviewSummaryGivesTheSamplesAndFlagsHero() {
        val orb = PreTradeNote("orb", "ORB", 10 * 60 + 10, ChargesCheck.read(400.0, 30, 150.0, 0.55), 82.5, 40.0)
        val hero = PreTradeNote(HeroRules.ARM.source, HeroRules.ARM.label, 14 * 60, ChargesCheck.read(3.0, 1300, 10.0, 0.05), 0.5, 1.8)
        val s = PreTradeNote.summary(listOf(orb)).single()
        assertTrue(s.startsWith("Charges check (shadow, R17 — failed its holdout test on 499 trades, nothing skipped)"), s)
        assertTrue(s.contains("failed its holdout test on 15 trades"), s)
        assertFalse(s.contains("Hero"), s)
        val h = PreTradeNote.summary(listOf(orb, hero)).single()
        assertTrue(h.contains("Hero's R17 sample is tiny (8 design, 2 holdout trades): too few to judge."), h)
        assertTrue(hero.line().endsWith("(Hero's R17 sample is tiny: 8 design, 2 holdout trades)"), hero.line())
        assertFalse(orb.line().contains("Hero"))
        assertNotNull(DangerMeter.MODEL.firstOrNull { it.feature == "dow" && it.bucket == "Sat" })
    }
}
