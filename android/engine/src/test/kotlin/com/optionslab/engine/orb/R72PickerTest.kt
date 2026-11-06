package com.optionslab.engine.orb

import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** R72 picker's edge cases (synthetic): missing data, no history, the put side, short daily files, the threshold. */
class R72PickerTest {
    private val day = LocalDate.of(2026, 11, 6)          // a Friday
    private val n = PassedArms.Session.N
    private fun f(x: DoubleArray, name: String) = x[R72Picker.FEATS.indexOf(name)]

    /** A session of minutes: a gentle rise from [base], every row present up to [upTo]. */
    private fun dayBars(d: LocalDate, base: Double, upTo: Int = n - 1, step: Double = 0.5): PassedArms.DayBars {
        val o = DoubleArray(n) { Double.NaN }; val h = o.copyOf(); val l = o.copyOf(); val c = o.copyOf()
        for (r in 0..upTo) { o[r] = base + r * step; c[r] = o[r] + step; h[r] = c[r] + 1; l[r] = o[r] - 1 }
        return PassedArms.DayBars(d, o, h, l, c)
    }

    /** [k] earlier sessions of 5-minute bars, zig-zagging so pivots exist. */
    private fun hist(k: Int, base: Double): List<PassedArms.Bar5> = (k downTo 1).flatMap { back ->
        val d = day.minusDays(back.toLong())
        (0 until 75).map { b -> val m = base + 20 * kotlin.math.sin(b / 4.0); PassedArms.Bar5(d, b, m, m + 5, m - 5, m + 1) }
    }

    private fun daily(k: Int, start: Double): List<R72Picker.Daily> =
        (k downTo 1).map { R72Picker.Daily(day.minusDays(it.toLong()), start + it, start + it * 1.01 + (it % 3)) }

    private fun input(side: Int = 1, er: Int = 60, syms: Map<String, R72Picker.Sym>, vix: PassedArms.DayBars? = null,
                      vixPrev: Double = Double.NaN, vixDaily: List<R72Picker.Daily> = emptyList()) =
        R72Picker.Input("NIFTY", day, er, side, 1, 4, syms, vix, vixPrev, vixDaily)

    @Test fun nothingKnownGivesNaNsAndTheDefaultsAndStillAScore() {
        val x = R72Picker.features(input(syms = emptyMap()))
        assertTrue(f(x, "day_move").isNaN() && f(x, "vix").isNaN() && f(x, "ema_slope").isNaN() && f(x, "breadth5").isNaN())
        assertEquals(0.05, f(x, "sr_ahead")); assertEquals(0.05, f(x, "piv_ahead")); assertEquals(0.0, f(x, "sr_crossed"))
        assertEquals(60.0, f(x, "tod")); assertEquals(4.0, f(x, "f_dow")); assertEquals(0.0, f(x, "f_breadth"))
        val s = R72Picker.score(x)
        assertTrue(s > 0 && s < 1)
    }

    @Test fun aFullCallAndAPutGiveFiniteFeaturesWithOppositeSigns() {
        val syms = R72Picker.INDICES.associateWith { R72Picker.Sym(hist(25, 20000.0), dayBars(day, 20010.0), daily(300, 20000.0), 20010.0) }
        val vix = dayBars(day, 14.0, step = 0.01)
        val call = R72Picker.features(input(1, syms = syms, vix = vix, vixPrev = 13.5, vixDaily = daily(80, 13.0)))
        val put = R72Picker.features(input(-1, syms = syms, vix = vix, vixPrev = 13.5, vixDaily = daily(80, 13.0)))
        for (name in listOf("day_move", "from_open", "twap_dist", "ema_dist", "ret5d")) {
            assertTrue(f(call, name).isFinite(), name); assertEquals(-f(call, name), f(put, name), 1e-12, name)
        }
        assertTrue(f(call, "dvix").isFinite() && f(call, "f_rv").isFinite() && f(call, "f_vixchg").isFinite() && f(call, "f_gap").isFinite())
        assertEquals(1.0, f(call, "breadth5")); assertEquals(5.0, f(call, "f_breadth")); assertEquals(0.0, f(put, "f_breadth"))
        assertTrue(f(call, "f_vixreg") == 0.0 || f(call, "f_vixreg") == 2.0)
        assertTrue(R72Picker.score(call) in 0.0..1.0 && R72Picker.score(put) in 0.0..1.0)
    }

    @Test fun badPricesAndMissingVixMinutesAreUnknownNotZero() {
        val syms = mapOf("NIFTY" to R72Picker.Sym(hist(3, 20000.0), dayBars(day, 20010.0), listOf(R72Picker.Daily(day.minusDays(1), 1.0, 0.0)), 20010.0))
        val vix = dayBars(day, 14.0, upTo = 10)
        vix.o[0] = Double.NaN
        val x = R72Picker.features(input(syms = syms, vix = vix, vixPrev = 0.0))
        assertTrue(f(x, "f_gap").isNaN(), "a zero previous close")
        assertTrue(f(x, "dvix").isNaN(), "no previous VIX")
        assertTrue(f(x, "f_vixchg").isNaN(), "no VIX open")
        assertTrue(f(x, "ret5d").isNaN(), "fewer than five sessions")
        val bare = mapOf("NIFTY" to R72Picker.Sym(emptyList(), dayBars(day, 20010.0), emptyList(), Double.NaN))
        assertTrue(f(R72Picker.features(input(syms = bare)), "ema_slope").isNaN(), "fewer than 25 bars")
        assertTrue(f(x, "f_rv").isNaN() && f(x, "f_vixreg").isNaN())
        val v2 = dayBars(day, 14.0, upTo = 10)
        assertTrue(f(R72Picker.features(input(syms = syms, vix = v2, vixPrev = 13.0)), "f_vixchg").isNaN(), "VIX's minute before entry is missing")
    }

    @Test fun anIndexWithoutTodayOrHistoryIsLeftOutOfBreadth() {
        val syms = mapOf("NIFTY" to R72Picker.Sym(hist(2, 100.0), dayBars(day, 50.0), emptyList(), Double.NaN),
            "BANKNIFTY" to R72Picker.Sym(emptyList(), dayBars(day, 50.0), emptyList(), Double.NaN),
            "FINNIFTY" to R72Picker.Sym(hist(2, 10.0), null, emptyList(), Double.NaN),
            "SENSEX" to R72Picker.Sym(hist(2, 10.0), dayBars(day, 50.0, upTo = -1), emptyList(), Double.NaN))
        assertEquals(0.0, f(R72Picker.features(input(syms = syms)), "breadth5"))      // NIFTY only: below yesterday
    }

    @Test fun bars5SkipsEmptyBarsAndStopsBeforeTheEntryRow() {
        val d = dayBars(day, 100.0, upTo = 30)
        for (r in 5..9) { d.c[r] = Double.NaN; d.h[r] = Double.NaN; d.l[r] = Double.NaN }
        val b = R72Picker.bars5(d, 20)
        assertEquals(listOf(0, 2, 3), b.map { it.b })
        assertEquals(d.o[10], b[1].open); assertEquals(d.c[14], b[1].close)
        assertEquals(6, R72Picker.bars5(d).size)
    }

    @Test fun theLevelHelpersAreR150s() {
        assertEquals(listOf(100.05 to 2, 105.0 to 1), R72Picker.group(listOf(100.1, 105.0, 100.0)).map { (l, k) -> Math.round(l * 100) / 100.0 to k })
        assertTrue(R72Picker.group(emptyList()).isEmpty())
        val bars = (0 until 20).map { PassedArms.Bar5(day, it, 0.0, if (it == 10) 9.0 else 1.0, if (it == 4) -9.0 else 0.0, 0.0) }
        assertEquals(listOf(9.0), R72Picker.pivotPrices(bars, 8).filter { it > 1 })
        assertTrue(-9.0 !in R72Picker.pivotPrices(bars, 8), "before the window's first bar")
        assertEquals(listOf(10.0, 13.0, 7.0, 16.0, 4.0), R72Picker.floorPivots(13.0, 7.0, 10.0))
    }

    @Test fun theDailyFeaturesNeedTheirSessions() {
        assertTrue(R72Picker.rvRank(List(270) { 100.0 + it }).isNaN())
        assertTrue(R72Picker.rvRank(List(271) { 100.0 + it }) in 0.0..1.0)
        assertTrue(R72Picker.vixRegime(List(60) { 10.0 }).isNaN())
        assertEquals(0.0, R72Picker.vixRegime(List(60) { 10.0 + it } + 5.0))
        assertEquals(2.0, R72Picker.vixRegime(List(60) { 10.0 + it } + 50.0))
        assertEquals(2.0, R72Picker.vixRegime(listOf(1.0, 2.0, 3.0, 2.5), 3))      // odd window: the middle one
        assertTrue(R72Picker.vixRegime(List(60) { 10.0 } + Double.NaN).isNaN())
        val e = R72Picker.ema(doubleArrayOf(Double.NaN, 2.0, 4.0))
        assertTrue(e[0].isNaN()); assertEquals(2.0, e[1]); assertEquals(2.0 + 2.0 * 2 / 21, e[2], 1e-12)
    }

    @Test fun theThresholdTheLineAndAWrongVector() {
        assertTrue(R72Picker.takes(R72Picker.THRESHOLD)); assertFalse(R72Picker.takes(0.4655))
        assertEquals("NIFTY call 10:35: picker score 0.512 - would take", R72Picker.line("NIFTY", 1, LocalTime.of(10, 35), 0.5123))
        assertEquals("SENSEX put 09:20: picker score 0.300 - would skip", R72Picker.line("SENSEX", -1, LocalTime.of(9, 20), 0.3))
        assertFailsWith<IllegalArgumentException> { R72Picker.score(DoubleArray(3)) }
    }
}
