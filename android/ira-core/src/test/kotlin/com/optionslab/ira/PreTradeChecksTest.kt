package com.optionslab.ira

import java.time.DayOfWeek
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PreTradeChecksTest {
    // A typical BANKNIFTY half hour: 57,000, a 100-point last 30 min, 160 over the hour, VIX 12, at 11:00.
    private val w = RangeModel.Window(57_100.0, 57_000.0, 57_160.0, 57_000.0, 57_050.0, 11 * 60)

    @Test fun theExpectedRangeIsR16sFormulaInPoints() {
        val p = RangeModel.points("BANKNIFTY", w, 12.0)!!
        // By hand: -2.491 + 0.159 tod(11:00 slot 6 = -5.9511) + 0.261 ln(100/57050) + 0.292 ln(160/57050) + 0.234 ln 12.
        val z = -2.491 + 0.159 * -5.9511 + 0.261 * kotlin.math.ln(100 / 57_050.0) + 0.292 * kotlin.math.ln(160 / 57_050.0) + 0.234 * kotlin.math.ln(12.0)
        assertEquals(kotlin.math.exp(z) * 57_050, p, 1e-6)
        assertTrue(p in 60.0..250.0, "a plausible half hour: $p")
        assertTrue(RangeModel.points("BANKNIFTY", w, 12.0, 60)!! > p)
        assertNotNull(RangeModel.points("NIFTY", w.copy(price = 25_000.0, high30 = 25_040.0, low30 = 25_000.0, high60 = 25_060.0, low60 = 25_000.0), 12.0))
    }

    @Test fun noRangeWithoutItsInputs() {
        assertNull(RangeModel.points("FINNIFTY", w, 12.0))
        assertNull(RangeModel.points("BANKNIFTY", w, null))
        assertNull(RangeModel.points("BANKNIFTY", w, 0.0))
        assertNull(RangeModel.points("BANKNIFTY", w.copy(price = 0.0), 12.0))
        assertNull(RangeModel.points("BANKNIFTY", w.copy(high30 = 1.0), 12.0))
        assertNull(RangeModel.points("BANKNIFTY", w.copy(high60 = 1.0), 12.0))
        assertNull(RangeModel.points("BANKNIFTY", w, 12.0, 45))
        assertNull(RangeModel.tod("SENSEX", 600))
        assertFalse(RangeModel.supports("SENSEX")); assertTrue(RangeModel.supports("NIFTY"))
        // A frozen feed (no range) still gives a small positive number, never a crash.
        assertTrue(RangeModel.points("BANKNIFTY", w.copy(high30 = 57_000.0, high60 = 57_000.0), 12.0)!! > 0)
    }

    @Test fun timeOfDaySlotsClampAtBothEnds() {
        assertEquals(-5.4958, RangeModel.tod("BANKNIFTY", 9 * 60 + 16))
        assertEquals(-5.9177, RangeModel.tod("BANKNIFTY", 15 * 60 + 25))
        assertEquals(-5.6353, RangeModel.tod("BANKNIFTY", 9 * 60 + 45))
    }

    @Test fun windowsFromBars() {
        val five = (0 until 14).map { 100.0 + it to 90.0 + it }
        val x = RangeModel.window(five, 5, 105.0, 600)!!
        assertEquals(113.0, x.high30); assertEquals(98.0, x.low30)     // the last 6 bars
        assertEquals(113.0, x.high60); assertEquals(92.0, x.low60)     // the last 12
        assertNull(RangeModel.window(emptyList(), 5, 1.0, 600))
        assertNull(RangeModel.window(five, 0, 1.0, 600))
        assertEquals(1, RangeModel.window(listOf(2.0 to 1.0), 120, 1.5, 600)!!.let { if (it.high60 == 2.0) 1 else 0 })
    }

    @Test fun deltaFromTheOptionsOwnPrice() {
        val atm = OptionDelta.price(50_000.0, 50_000.0, 3 * 1440.0, 0.14, true)
        val iv = OptionDelta.impliedVol(atm, 50_000.0, 50_000.0, 3 * 1440.0, true)!!
        assertEquals(0.14, iv, 1e-4)
        val d = OptionDelta.of(50_000.0, 50_000.0, 3 * 1440.0, atm, 14.0, true)!!
        assertTrue(d in 0.48..0.6, "ATM call delta $d")
        val p = OptionDelta.of(50_000.0, 50_000.0, 3 * 1440.0, OptionDelta.price(50_000.0, 50_000.0, 3 * 1440.0, 0.14, false), 14.0, false)!!
        assertTrue(p in -0.52..-0.4, "ATM put delta $p")
        // A premium no vol can give falls back to VIX; without VIX there is nothing to price with.
        assertEquals(OptionDelta.delta(50_000.0, 50_000.0, 1440.0, 0.14, true), OptionDelta.of(50_000.0, 50_000.0, 1440.0, 1e6, 14.0, true))
        assertNull(OptionDelta.of(50_000.0, 50_000.0, 1440.0, 1e6, null, true))
        assertNull(OptionDelta.impliedVol(0.0, 1.0, 1.0, 1.0, true))
        assertNull(OptionDelta.impliedVol(0.001, 50_000.0, 40_000.0, 1440.0, true))    // below intrinsic
        assertEquals(-0.5, OptionDelta.delta(0.0, 1.0, 1.0, 0.1, false))
        assertEquals(0.5, OptionDelta.delta(1.0, 1.0, 1.0, 0.0, true))
        // Deep in and far out of the money.
        assertTrue(OptionDelta.delta(60_000.0, 50_000.0, 1440.0, 0.14, true) > 0.99)
        assertTrue(OptionDelta.delta(40_000.0, 50_000.0, 1440.0, 0.14, true) < 0.01)
    }

    @Test fun chargesCheckSaysTheShareAndWouldSkipOnlyPastTenPercent() {
        val c = ChargesCheck.costPerUnit(400.0, 30)
        assertTrue(c in 2.0..6.0, "₹75-ish a round trip over 30 units plus slippage: $c")
        assertEquals(0.0, ChargesCheck.costPerUnit(0.0, 30))
        val r = ChargesCheck.read(400.0, 30, 150.0, 0.55)
        assertEquals(150 * 0.55, r.premiumMove!!, 1e-9)
        assertFalse(r.wouldSkip)
        assertTrue(r.line().startsWith("Costs take "), r.line())
        val thin = ChargesCheck.read(400.0, 30, 10.0, 0.5)
        assertTrue(thin.wouldSkip)
        assertTrue(thin.line().contains("would skip at 10% (shadow)"), thin.line())
        val none = ChargesCheck.read(400.0, 30, null, 0.5)
        assertNull(none.ratio); assertFalse(none.wouldSkip)
        assertTrue(none.line().contains("not known"))
        assertNull(ChargesCheck.read(400.0, 30, 100.0, 0.0).ratio)
    }

    @Test fun smartStopLine() {
        assertEquals(82.5, SmartStop.points(150.0, -0.55)!!, 1e-9)
        assertEquals(0.1, SmartStop.points(0.01, 0.01)!!, 1e-9)
        assertNull(SmartStop.points(null, 0.5)); assertNull(SmartStop.points(100.0, null)); assertNull(SmartStop.points(0.0, 0.5))
        assertEquals("Smart stop would be 82.5 points; fixed stop is 40.0", SmartStop.line(82.5, 40.0))
        assertTrue(SmartStop.line(82.5, null).contains("no fixed premium stop"))
        assertTrue(SmartStop.line(null, 40.0).contains("not known"))
    }

    @Test fun orderReviewSummary() {
        assertTrue(PreTradeNote.summary(emptyList()).isEmpty())
        val a = PreTradeNote("orb", "ORB", 10 * 60 + 10, ChargesCheck.read(400.0, 30, 150.0, 0.55), 82.5, 40.0)
        val b = PreTradeNote("hero", "Hero", 14 * 60, ChargesCheck.read(3.0, 1300, 10.0, 0.05), 0.5, null)
        assertTrue(a.line().startsWith("10:10 ORB: Costs take"), a.line())
        assertTrue(a.line().contains("smart stop would be 82.5"), a.line())
        val s = PreTradeNote.summary(listOf(a, b)).single()
        assertTrue(s.contains("2 paper entries") && s.contains("would have been skipped") && s.contains("wider than the fixed stop on 1 of 1"), s)
        val unknown = PreTradeNote.summary(listOf(a.copy(charges = ChargesCheck.read(400.0, 30, null, null), smartStop = null))).single()
        assertTrue(unknown.contains("1 paper entry;") && unknown.contains("not known") && !unknown.contains("Smart stop"), unknown)
    }

    @Test fun dangerMeterReadsTheDayAndSaysItsRecord() {
        val bad = DangerMeter.read(DangerMeter.Inputs(0.05, 11.0, 0.0, event = true, expiry = true, day = DayOfWeek.TUESDAY))
        assertEquals(DangerMeter.Level.PAPER_ONLY, bad.level)
        assertEquals("event", bad.worst!!.feature)
        assertTrue(bad.line().startsWith("Today: paper-only day (danger meter, 347 sessions). Worst sign: an event day"), bad.line())
        assertTrue(bad.line().contains("failed its holdout test (122 days)"))
        val good = DangerMeter.read(DangerMeter.Inputs(0.5, 13.0, 0.0, event = false, expiry = false, day = DayOfWeek.MONDAY))
        assertEquals(DangerMeter.Level.NORMAL, good.level)
        assertNull(good.worst)
        val careful = DangerMeter.read(DangerMeter.Inputs(0.05, 13.0, 0.0, event = false, expiry = true, day = DayOfWeek.FRIDAY))
        assertEquals(DangerMeter.Level.CAREFUL, careful.level, "score ${careful.score}")
        val early = DangerMeter.read(DangerMeter.Inputs(null, null, null, event = false, expiry = false, day = DayOfWeek.WEDNESDAY))
        assertEquals(listOf("the gap", "VIX", "VIX change"), early.unknown)
        assertTrue(early.provisional)
        assertTrue(early.line().startsWith("Today: normal day (provisional, gap not known yet, read again after the open; danger meter"), early.line())
        assertTrue(early.line().contains("Not known yet: VIX, VIX change (counted as neutral)."), early.line())
        // Every bucket name the morning can produce is in the model, with its sample size.
        for (d in DayOfWeek.values().filter { it != DayOfWeek.SUNDAY }) assertEquals(6, DangerMeter.read(DangerMeter.Inputs(-0.2, 15.0, 0.08, false, false, d)).used.size)
        assertEquals("large", DangerMeter.buckets(DangerMeter.Inputs(-1.0, 15.0, -0.08, false, false, DayOfWeek.MONDAY)).first["gap"])
        assertEquals("down", DangerMeter.buckets(DangerMeter.Inputs(-1.0, 11.5, -0.08, false, false, DayOfWeek.MONDAY)).first["vix_chg"])
        assertEquals("mid", DangerMeter.buckets(DangerMeter.Inputs(-1.0, 11.5, -0.08, false, false, DayOfWeek.MONDAY)).first["vix"])
    }

    @Test fun describedBuckets() {
        val words = DangerMeter.MODEL.map { DangerMeter.describe(it) }
        assertTrue(words.containsAll(listOf("a small gap", "high VIX", "VIX up over 5%", "an event day", "an expiry day", "Tuesdays", "Saturdays")), "$words")
        assertEquals("x", DangerMeter.describe(DangerMeter.Bucket("dow", "x", 1, 0.0, 0.0)))
        assertTrue(DangerMeter.MODEL.all { it.n > 0 && abs(it.dev) < abs(it.mean - DangerMeter.OVERALL) + 1 })
    }

    @Test fun halfSizeOnlyLowersPaperLots() {
        assertEquals(12, DangerMeter.lots(25, DangerMeter.Level.CAREFUL, halfOn = true))
        assertEquals(12, DangerMeter.lots(25, DangerMeter.Level.PAPER_ONLY, halfOn = true))
        assertEquals(25, DangerMeter.lots(25, DangerMeter.Level.CAREFUL, halfOn = false))     // the switch is off by default
        assertEquals(25, DangerMeter.lots(25, DangerMeter.Level.NORMAL, halfOn = true))
        assertEquals(25, DangerMeter.lots(25, null, halfOn = true))
        assertEquals(1, DangerMeter.lots(1, DangerMeter.Level.CAREFUL, halfOn = true))          // 1 lot cannot be halved
        assertEquals(1, DangerMeter.lots(3, DangerMeter.Level.CAREFUL, halfOn = true))
        for (n in 0..40) for (l in DangerMeter.Level.values()) assertTrue(DangerMeter.lots(n, l, true) <= maxOf(n, 0) || n <= 1)
    }
}
