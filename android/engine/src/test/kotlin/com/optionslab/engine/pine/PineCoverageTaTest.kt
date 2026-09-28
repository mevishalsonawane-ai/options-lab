package com.optionslab.engine.pine

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Every ta.* function against values worked out by hand or by an independent formula. */
class PineCoverageTaTest {
    private val H = "indicator(\"ta\")\n"
    private fun p(expr: String, b: List<Pine.Bar>, pre: String = ""): DoubleArray = PC.plot("$H$pre\nplot($expr)", b)
    private fun near(e: Double, a: Double, tol: Double = 1e-9) = PC.near(e, a, tol)

    /** Candles with their own close and volume (open = previous close, range 1 point outside). */
    private fun cv(vararg cv: Pair<Double, Double>) = cv.mapIndexed { i, (c, v) ->
        val o = if (i == 0) c else cv[i - 1].first
        Pine.Bar(PC.T0 + i * 300L, o, maxOf(o, c) + 1, minOf(o, c) - 1, c, v)
    }

    private fun wma(x: List<Double>): Double { var s = 0.0; var n = 0.0; x.forEachIndexed { i, v -> s += v * (i + 1); n += i + 1 }; return s / n }

    @Test fun vwmaWeighsByVolume() {
        val b = cv(1.0 to 100.0, 2.0 to 300.0, 4.0 to 100.0, 5.0 to 0.0, 6.0 to 0.0)
        val r = p("ta.vwma(close, 2)", b)
        near(Double.NaN, r[0])
        near((1 * 100.0 + 2 * 300.0) / 400, r[1])
        near((2 * 300.0 + 4 * 100.0) / 400, r[2])
        near(4.0, r[3])                         // (4*100 + 5*0) / 100
        near(Double.NaN, r[4])                  // no volume in the window
    }

    @Test fun hullMovingAverageMatchesItsDefinition() {
        val c = listOf(3.0, 1.0, 4.0, 1.0, 5.0, 9.0, 2.0, 6.0, 5.0, 3.0)
        val r = p("ta.hma(close, 4)", PC.bars(*c.toDoubleArray()))
        // hma(4) = wma(2 * wma(src, 2) - wma(src, 4), 2)
        val raw = c.indices.map { i -> if (i < 3) Double.NaN else 2 * wma(c.subList(i - 1, i + 1)) - wma(c.subList(i - 3, i + 1)) }
        for (i in c.indices) {
            val want = if (i < 4) Double.NaN else wma(raw.subList(i - 1, i + 1))
            near(want, r[i])
        }
        // A length of 1 uses windows of 1 throughout: the source itself.
        assertEquals(c, p("ta.hma(close, 1)", PC.bars(*c.toDoubleArray())).toList())
    }

    @Test fun highestLowestAndTheirBars() {
        val c = doubleArrayOf(5.0, 9.0, 7.0, 3.0, 4.0)
        val b = PC.bars(*c)
        assertEquals(listOf(Double.NaN, Double.NaN, 9.0, 9.0, 7.0).toString(), p("ta.highest(close, 3)", b).toList().toString())
        assertEquals(listOf(Double.NaN, Double.NaN, 5.0, 3.0, 3.0).toString(), p("ta.lowest(close, 3)", b).toList().toString())
        // Bars back to the extreme, as a negative offset.
        assertEquals(-1.0, p("ta.highestbars(close, 3)", b)[2])
        assertEquals(-2.0, p("ta.highestbars(close, 3)", b)[4])
        assertEquals(0.0, p("ta.lowestbars(close, 3)", b)[3])
        // With just a length they read the highs and lows.
        assertEquals(b.subList(2, 5).maxOf { it.high }, p("ta.highest(3)", b)[4])
        assertEquals(b.subList(2, 5).minOf { it.low }, p("ta.lowest(3)", b)[4])
        assertEquals(-2.0, p("ta.highestbars(3)", b)[4])      // high of bar 2 (open 9 + 1)
        assertEquals(0.0, p("ta.lowestbars(3)", b)[3])
        // Ties go to the most recent bar.
        assertEquals(0.0, p("ta.highestbars(close, 2)", PC.bars(2.0, 2.0))[1])
    }

    @Test fun stdevAndVarianceBiasedAndNot() {
        val c = doubleArrayOf(2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0)
        val b = PC.bars(*c)
        near(2.0, p("ta.stdev(close, 8)", b)[7])
        near(4.0, p("ta.variance(close, 8)", b)[7])
        near(32.0 / 7, p("ta.variance(close, 8, false)", b)[7])
        near(sqrt(32.0 / 7), p("ta.stdev(close, 8, biased = false)", b)[7])
        // A length of 1 with biased = false does not divide by zero.
        near(0.0, p("ta.stdev(close, 1, false)", b)[7])
        near(Double.NaN, p("ta.stdev(close, 8)", b)[6])
    }

    @Test fun stochasticAndWilliamsR() {
        val c = doubleArrayOf(10.0, 12.0, 11.0, 14.0)
        val b = PC.bars(*c)
        // stoch of close within the window's highs and lows.
        val hh = b.subList(1, 4).maxOf { it.high }; val ll = b.subList(1, 4).minOf { it.low }
        near(100 * (14.0 - ll) / (hh - ll), p("ta.stoch(close, high, low, 3)", b)[3])
        near(Double.NaN, p("ta.stoch(close, high, low, 3)", b)[1])
        // A flat window has no range.
        near(Double.NaN, p("ta.stoch(close, close, close, 2)", PC.bars(5.0, 5.0))[1])
        near(-100 * (hh - 14.0) / (hh - ll), p("ta.wpr(3)", b)[3])
        near(Double.NaN, p("ta.wpr(3)", b)[1])
        val flat = listOf(Pine.Bar(PC.T0, 5.0, 5.0, 5.0, 5.0, 1.0), Pine.Bar(PC.T0 + 300, 5.0, 5.0, 5.0, 5.0, 1.0))
        near(Double.NaN, p("ta.wpr(2)", flat)[1])
    }

    @Test fun commodityChannelIndex() {
        val c = doubleArrayOf(1.0, 2.0, 6.0)
        val m = 3.0; val md = (2.0 + 1.0 + 3.0) / 3
        near((6.0 - m) / (0.015 * md), p("ta.cci(close, 3)", PC.bars(*c))[2])
        near(Double.NaN, p("ta.cci(close, 3)", PC.bars(*c))[1])
        near(Double.NaN, p("ta.cci(close, 2)", PC.bars(4.0, 4.0))[1])     // no deviation
    }

    @Test fun vwapResetsEachDayAndTakesASource() {
        val day2 = PC.T0 + 86400
        val b = listOf(
            Pine.Bar(PC.T0, 10.0, 12.0, 8.0, 10.0, 100.0), Pine.Bar(PC.T0 + 300, 10.0, 22.0, 18.0, 20.0, 300.0),
            Pine.Bar(day2, 30.0, 31.0, 29.0, 30.0, 50.0), Pine.Bar(day2 + 300, 30.0, 31.0, 29.0, 30.0, 0.0))
        val r = p("ta.vwap(close)", b)
        near(10.0, r[0]); near((10.0 * 100 + 20.0 * 300) / 400, r[1]); near(30.0, r[2]); near(30.0, r[3])
        // Without a source: the typical price (h + l + c) / 3, also as the plain name ta.vwap.
        near(((12.0 + 8 + 10) / 3 * 100 + (22.0 + 18 + 20) / 3 * 300) / 400, p("ta.vwap()", b)[1])
        near(((12.0 + 8 + 10) / 3 * 100 + (22.0 + 18 + 20) / 3 * 300) / 400, p("ta.vwap", b)[1])
        // No volume yet on the day: na.
        val z = listOf(Pine.Bar(PC.T0, 10.0, 11.0, 9.0, 10.0, 0.0))
        near(Double.NaN, p("ta.vwap(close)", z)[0])
        near(Double.NaN, p("ta.vwap", z)[0])
    }

    @Test fun vwapCalledTwiceOnABarDoesNotDoubleCount() {
        val b = PC.bars(10.0, 20.0, volume = 100.0)
        val r = PC.plot("${H}f(x) => ta.vwap(x)\nv = 0.0\nfor i = 0 to 1\n    v := f(close)\nplot(v)", b)
        near(15.0, r[1])
    }

    @Test fun barssinceAndValuewhen() {
        val b = PC.bars(1.0, 5.0, 2.0, 6.0, 3.0, 4.0)
        val cond = "close > 4"
        assertEquals(listOf(Double.NaN, 0.0, 1.0, 0.0, 1.0, 2.0).toString(), p("ta.barssince($cond)", b).toList().toString())
        assertEquals(listOf(Double.NaN, 5.0, 5.0, 6.0, 6.0, 6.0).toString(), p("ta.valuewhen($cond, close, 0)", b).toList().toString())
        assertEquals(listOf(Double.NaN, Double.NaN, Double.NaN, 5.0, 5.0, 5.0).toString(), p("ta.valuewhen($cond, close, 1)", b).toList().toString())
        // Evaluated twice on one bar (in a loop), the bar's own value is replaced, not added.
        val r = PC.plot("${H}v = 0.0\nfor i = 0 to 2\n    v := ta.valuewhen(close > 4, close + i, 1)\nplot(v)", b)
        near(7.0, r[3])                          // occurrence 1 at bar 3 is bar 1's close (5) + i (2)
    }

    @Test fun risingAndFalling() {
        val b = PC.bars(1.0, 2.0, 3.0, 2.0, 1.0)
        assertEquals(listOf(0.0, 0.0, 1.0, 0.0, 0.0), p("ta.rising(close, 2) ? 1 : 0", b).toList())
        assertEquals(listOf(0.0, 0.0, 0.0, 0.0, 1.0), p("ta.falling(close, 2) ? 1 : 0", b).toList())
    }

    @Test fun directionalMovementIndex() {
        // Rising by 1 a bar: +DM 1, -DM 0, true range 3 -> +DI 33.3, -DI 0, ADX 100.
        val b = PC.bars(*DoubleArray(8) { it + 1.0 })
        val pre = "[p, m, a] = ta.dmi(3, 3)"
        val plus = p("p", b, pre); val minus = p("m", b, pre); val adx = p("a", b, pre)
        near(Double.NaN, plus[2]); near(100.0 / 3, plus[3]); near(0.0, minus[3])
        near(Double.NaN, adx[4]); near(100.0, adx[5]); near(100.0, adx[7])
        // Flat candles: no movement either way, and ADX treats the zero sum as 1.
        val flat = List(8) { Pine.Bar(PC.T0 + it * 300L, 5.0, 6.0, 4.0, 5.0, 1.0) }
        near(0.0, p("p", flat, pre)[7]); near(0.0, p("m", flat, pre)[7]); near(0.0, p("a", flat, pre)[7])
        // Falling: -DI takes over.
        val fall = PC.bars(*DoubleArray(8) { 10.0 - it })
        near(0.0, p("p", fall, pre)[5]); near(100.0 / 3, p("m", fall, pre)[5])
    }

    @Test fun pivotsWithTwoAndThreeArguments() {
        val c = doubleArrayOf(1.0, 3.0, 2.0, 0.5, 2.0, 4.0)
        val b = PC.bars(*c)
        val ph = p("ta.pivothigh(close, 1, 1)", b)
        near(3.0, ph[2]); assertTrue(ph.withIndex().all { (i, v) -> i == 2 || v.isNaN() }, ph.toList().toString())
        val pl = p("ta.pivotlow(close, 1, 1)", b)
        near(0.5, pl[4]); assertTrue(pl.withIndex().all { (i, v) -> i == 4 || v.isNaN() }, pl.toList().toString())
        // Two arguments: the highs and lows.
        val hl = listOf(PC.bar(0, 3.0, 4.0, 2.0, 3.0), PC.bar(1, 3.0, 9.0, 1.0, 3.0), PC.bar(2, 3.0, 5.0, 2.5, 3.0))
        near(9.0, p("ta.pivothigh(1, 1)", hl)[2])
        near(1.0, p("ta.pivotlow(1, 1)", hl)[2])
        // An na candidate is not a pivot.
        near(Double.NaN, p("ta.pivothigh(x, 1, 1)", PC.bars(1.0, 2.0, 3.0), "x = bar_index == 1 ? na : close")[2])
        // A plateau on the left counts (<=), on the right it does not (<).
        near(2.0, p("ta.pivothigh(close, 1, 1)", PC.bars(2.0, 2.0, 1.0))[2])
        near(Double.NaN, p("ta.pivothigh(close, 1, 1)", PC.bars(1.0, 2.0, 2.0))[2])
        near(Double.NaN, p("ta.pivotlow(close, 1, 1)", PC.bars(1.0, 0.0, 0.0))[2])
    }

    @Test fun linearRegression() {
        val b = PC.bars(1.0, 2.0, 3.0, 5.0)
        near(3.0, p("ta.linreg(close, 3, 0)", b)[2])
        near(2.0, p("ta.linreg(close, 3, 1)", b)[2])
        // 2, 3, 5: slope 1.5, mean 10/3 -> at the last point 10/3 + 1.5.
        near(10.0 / 3 + 1.5, p("ta.linreg(close, 3, 0)", b)[3])
        near(Double.NaN, p("ta.linreg(close, 3, 0)", b)[1])
        // One point: no slope.
        near(5.0, p("ta.linreg(close, 1, 0)", b)[3])
    }

    @Test fun medianAndSum() {
        val b = PC.bars(5.0, 1.0, 3.0, 2.0)
        near(3.0, p("ta.median(close, 3)", b)[2])
        near(2.5, p("ta.median(close, 2)", b)[3])
        near(Double.NaN, p("ta.median(close, 3)", b)[1])
        near(5.0, p("math.sum(close, 2)", b)[3])
        near(Double.NaN, p("math.sum(close, 2)", b)[0])
    }

    @Test fun rmaWmaAndEmaWithGaps() {
        val b = PC.bars(1.0, 2.0, 3.0, 4.0)
        near(2.0, p("ta.rma(close, 3)", b)[2])
        near(2.0 + (4.0 - 2.0) / 3, p("ta.rma(close, 3)", b)[3])
        near((2.0 * 1 + 3.0 * 2 + 4.0 * 3) / 6, p("ta.wma(close, 3)", b)[3])
        // An na inside the seed window keeps the average na; an na later holds the last value.
        val pre = "x = bar_index == 1 ? na : close"
        near(Double.NaN, p("ta.ema(x, 2)", PC.bars(1.0, 2.0, 3.0), pre)[1])
        near(Double.NaN, p("ta.ema(x, 2)", PC.bars(1.0, 2.0, 3.0), pre)[2])
        val later = "x = bar_index == 2 ? na : close"
        val r = p("ta.ema(x, 2)", PC.bars(1.0, 2.0, 3.0, 4.0), later)
        near(1.5, r[1]); near(1.5, r[2]); near(2.0 / 3 * 4 + 1.5 / 3, r[3])
    }

    @Test fun rsiEdges() {
        // Only gains: 100. Only losses: 0. Too few bars: na.
        near(100.0, p("ta.rsi(close, 2)", PC.bars(1.0, 2.0, 3.0, 4.0))[3])
        near(0.0, p("ta.rsi(close, 2)", PC.bars(4.0, 3.0, 2.0, 1.0))[3])
        near(Double.NaN, p("ta.rsi(close, 2)", PC.bars(1.0, 2.0))[1])
    }

    @Test fun trueRangeAtrAndHandleNa() {
        val b = PC.bars(10.0, 12.0)
        near(Double.NaN, p("ta.tr", b)[0]); near(Double.NaN, p("ta.tr(false)", b)[0])
        near(b[0].high - b[0].low, p("ta.tr(true)", b)[0])
        near(maxOf(b[1].high - b[1].low, abs(b[1].high - 10.0), abs(b[1].low - 10.0)), p("ta.tr", b)[1])
        near(maxOf(b[1].high - b[1].low, abs(b[1].high - 10.0), abs(b[1].low - 10.0)), p("ta.tr(handle_na = true)", b)[1])
        near(((b[0].high - b[0].low) + p("ta.tr", b)[1]) / 2, p("ta.atr(2)", b)[1])
    }

    @Test fun crossesInBothDirections() {
        val b = PC.bars(1.0, 3.0, 1.0, 1.0)
        assertEquals(listOf(0.0, 1.0, 0.0, 0.0), p("ta.crossover(close, 2) ? 1 : 0", b).toList())
        assertEquals(listOf(0.0, 0.0, 1.0, 0.0), p("ta.crossunder(close, 2) ? 1 : 0", b).toList())
        assertEquals(listOf(0.0, 1.0, 1.0, 0.0), p("ta.cross(close, 2) ? 1 : 0", b).toList())
        // Touching and staying is not a cross.
        assertEquals(listOf(0.0, 0.0, 0.0), p("ta.cross(close, 2) ? 1 : 0", PC.bars(2.0, 2.0, 2.0)).toList())
    }

    @Test fun changeMomentumAndRateOfChange() {
        val b = PC.bars(2.0, 4.0, 3.0)
        assertEquals(listOf(Double.NaN, 2.0, -1.0).toString(), p("ta.change(close)", b).toList().toString())
        assertEquals(listOf(Double.NaN, Double.NaN, 1.0).toString(), p("ta.change(close, 2)", b).toList().toString())
        near(1.0, p("ta.mom(close, 2)", b)[2])
        near(50.0, p("ta.roc(close, 2)", b)[2])
        near(Double.NaN, p("ta.roc(close, 1)", PC.bars(0.0, 1.0))[1])        // from zero
        // A boolean source: did it change?
        assertEquals(listOf(0.0, 1.0, 0.0, 1.0), p("ta.change(close > 3) ? 1 : 0", PC.bars(2.0, 4.0, 5.0, 1.0)).toList())
    }

    @Test fun cumulativeSumSkipsNa() {
        val r = p("ta.cum(x)", PC.bars(1.0, 2.0, 3.0), "x = bar_index == 1 ? na : close")
        assertEquals(listOf(1.0, 1.0, 4.0), r.toList())
    }

    @Test fun macdAndBollingerTuples() {
        val c = DoubleArray(12) { 10.0 + it * it * 0.1 }
        val b = PC.bars(*c)
        val pre = "[m, s, h] = ta.macd(close, 2, 3, 2)"
        val m = p("m", b, pre); val s = p("s", b, pre); val h = p("h", b, pre)
        near(m[11] - s[11], h[11])
        near(Double.NaN, s[2]); assertTrue(!s[4].isNaN())
        val bb = "[mid, up, lo] = ta.bb(close, 3, 2)"
        val mid = p("mid", b, bb); val up = p("up", b, bb); val lo = p("lo", b, bb)
        val w = c.slice(9..11); val mean = w.average(); val sd = sqrt(w.sumOf { (it - mean) * (it - mean) } / 3)
        near(mean, mid[11]); near(mean + 2 * sd, up[11], 1e-9); near(mean - 2 * sd, lo[11], 1e-9)
        near(Double.NaN, mid[1]); near(Double.NaN, up[1]); near(Double.NaN, lo[1])
    }

    @Test fun supertrendFlipsWithThePrice() {
        val c = doubleArrayOf(100.0, 101.0, 102.0, 103.0, 104.0, 105.0, 95.0, 90.0, 85.0, 80.0, 90.0, 100.0, 110.0, 120.0)
        val b = PC.bars(*c)
        val pre = "[st, dir] = ta.supertrend(1, 3)"
        val st = p("st", b, pre); val dir = p("dir", b, pre)
        near(Double.NaN, st[0])                                  // no ATR yet
        near(1.0, dir[0])
        // The first line is the upper band: hl2 101.5 + ATR (2 + 3 + 3) / 3, held while the price stays under it.
        near(101.5 + 8.0 / 3, st[2]); near(st[2], st[4])
        // Up (-1) once the close clears the band, down (1) on the fall, up again on the recovery.
        assertEquals(listOf(1.0, 1.0, 1.0, 1.0, 1.0, -1.0, 1.0, 1.0, 1.0, 1.0, -1.0, -1.0, -1.0, -1.0), dir.toList())
        // Going up the line sits below the close, going down above it.
        assertTrue((2 until c.size).all { if (dir[it] < 0) st[it] < c[it] else st[it] > c[it] }, "${dir.toList()} ${st.toList()}")
        // Called twice on one bar it gives the same answer.
        val twice = PC.plot("${H}f() => ta.supertrend(1, 3)\nv = 0.0\nfor i = 0 to 1\n    [a, d] = f()\n    v := d\nplot(v)", b)
        assertEquals(dir.toList(), twice.toList())
    }

    @Test fun obvAndAccumulationDistribution() {
        val b = listOf(PC.bar(0, 10.0, 11.0, 9.0, 10.0, 100.0), PC.bar(1, 10.0, 12.0, 10.0, 12.0, 50.0),
            PC.bar(2, 12.0, 12.0, 8.0, 9.0, 20.0), PC.bar(3, 9.0, 9.0, 9.0, 9.0, 70.0))
        assertEquals(listOf(0.0, 50.0, 30.0, 30.0), p("ta.obv", b).toList())
        // mf = ((c - l) - (h - c)) / (h - l) * v; a bar with no range adds nothing.
        val ad = listOf(0.0, 50.0, ((9.0 - 8) - (12.0 - 9)) / 4 * 20, 0.0).runningFold(0.0) { a, x -> a + x }.drop(1)
        assertEquals(ad, p("ta.accdist", b).toList())
    }

    @Test fun lengthsAreChecked() {
        PC.runError("${H}plot(ta.sma(close, 0))", PC.bars(1.0), "ta.sma(): the length must be 1 or more (got 0)")
        PC.runError("${H}plot(ta.ema(close, na))", PC.bars(1.0), "ta.ema(): the length must be 1 or more (got na)")
        PC.runError("${H}plot(ta.rma(close, 5001))", PC.bars(1.0), "ta.rma(): the length must be at most 5000 (got 5001)")
        PC.runError("${H}plot(ta.atr(-2))", PC.bars(1.0), "(got -2)")
        PC.runError("${H}plot(ta.mom(close, 0))", PC.bars(1.0), "ta.mom(): the length must be 1 or more")
        // A fractional length is truncated.
        near(1.5, p("ta.sma(close, 2.9)", PC.bars(1.0, 2.0))[1])
    }
}
