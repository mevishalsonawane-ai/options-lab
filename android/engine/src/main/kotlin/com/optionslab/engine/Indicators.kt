package com.optionslab.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * UT Bot Alerts, ported from Pine v4 (port of `indicators/utbot.py`).
 * Run settings: Key Value a = 2, ATR Period c = 1, Heikin Ashi off.
 */
object UtBot {
    class DirtyInput(msg: String) : IllegalArgumentException(msg)

    private fun requireClean(name: String, a: DoubleArray) {
        val bad = a.indices.filter { !a[it].isFinite() }
        if (bad.isNotEmpty()) throw DirtyInput(
            "$name has ${bad.size} non-finite value(s), first at index ${bad[0]}. The trailing stop is a " +
                "recursion and cannot absorb a gap - fill or drop it before calling.")
    }

    fun trueRange(high: DoubleArray, low: DoubleArray, close: DoubleArray): DoubleArray = DoubleArray(close.size) { i ->
        val pc = if (i == 0) close[0] else close[i - 1]
        max(high[i] - low[i], max(abs(high[i] - pc), abs(low[i] - pc)))
    }

    /** Wilder's RMA of true range, matching Pine's atr(). */
    fun atr(high: DoubleArray, low: DoubleArray, close: DoubleArray, period: Int = 1): DoubleArray {
        val tr = trueRange(high, low, close)
        if (period <= 1 || tr.isEmpty()) return tr
        val out = DoubleArray(tr.size)
        out[0] = tr[0]
        val alpha = 1.0 / period
        for (i in 1 until tr.size) out[i] = alpha * tr[i] + (1.0 - alpha) * out[i - 1]
        return out
    }

    fun trailingStop(high: DoubleArray, low: DoubleArray, close: DoubleArray, keyValue: Double = 2.0, atrPeriod: Int = 1): DoubleArray {
        requireClean("high", high); requireClean("low", low); requireClean("close", close)
        val nLoss = atr(high, low, close, atrPeriod).map { keyValue * it }
        val stop = DoubleArray(close.size)
        var prev = 0.0
        for (i in close.indices) {
            val src = close[i]
            val prevSrc = if (i > 0) close[i - 1] else close[i]
            stop[i] = when {
                src > prev && prevSrc > prev -> max(prev, src - nLoss[i])
                src < prev && prevSrc < prev -> min(prev, src + nLoss[i])
                src > prev -> src - nLoss[i]
                else -> src + nLoss[i]
            }
            prev = stop[i]
        }
        return stop
    }

    private fun crossover(a: DoubleArray, b: DoubleArray): BooleanArray = BooleanArray(a.size) { i ->
        i > 0 && a[i] > b[i] && a[i - 1] <= b[i - 1]
    }

    /** (buy, sell), one element per bar. */
    fun signals(high: DoubleArray, low: DoubleArray, close: DoubleArray, keyValue: Double = 2.0, atrPeriod: Int = 1): Pair<BooleanArray, BooleanArray> {
        val stop = trailingStop(high, low, close, keyValue, atrPeriod)
        return crossover(close, stop) to crossover(stop, close)
    }
}

/**
 * TradingView's Linear Regression Channel, made rolling (port of
 * `indicators/linreg.py`). X RUNS BACKWARDS: per=1 is the current bar, so a
 * NEGATIVE trend is an uptrend.
 */
object LinReg {
    data class Channel(
        val slope: Double, val average: Double, val intercept: Double,
        val startPrice: Double, val endPrice: Double, val stdDev: Double, val pearsonR: Double,
        val upDev: Double, val dnDev: Double,
        val upperStart: Double, val upperEnd: Double, val lowerStart: Double, val lowerEnd: Double,
        val trend: Double,
    ) { val isUptrend: Boolean get() = trend < 0 }

    fun channel(
        source: DoubleArray, high: DoubleArray, low: DoubleArray, length: Int = 100,
        upperMult: Double = 2.0, lowerMult: Double = 2.0, useUpper: Boolean = true, useLower: Boolean = true,
    ): Channel {
        require(length >= 1) { "length must be >= 1" }
        require(source.size >= length) { "need at least $length bars, got ${source.size} - the channel is undefined until the window is full" }
        val n = source.size
        val win = DoubleArray(length) { source[n - 1 - it] }
        val hi = DoubleArray(length) { high[n - 1 - it] }
        val lo = DoubleArray(length) { low[n - 1 - it] }
        var sumX = 0.0; var sumY = 0.0; var sumXSqr = 0.0; var sumXY = 0.0
        for (i in 0 until length) {
            val per = (i + 1).toDouble()
            sumX += per; sumY += win[i]; sumXSqr += per * per; sumXY += win[i] * per
        }
        val denom = length * sumXSqr - sumX * sumX
        val slope = if (denom == 0.0) 0.0 else (length * sumXY - sumX * sumY) / denom
        val average = sumY / length
        val intercept = average - slope * sumX / length + slope
        val startPrice = intercept + slope * (length - 1)
        val endPrice = intercept
        val periods = length - 1
        val daY = intercept + slope * periods / 2.0
        var v = intercept
        var upDev = 0.0; var dnDev = 0.0
        var stdAcc = 0.0; var dsxx = 0.0; var dsyy = 0.0; var dsxy = 0.0
        for (j in 0..periods) {
            var price = hi[j] - v
            if (price > upDev) upDev = price
            price = v - lo[j]
            if (price > dnDev) dnDev = price
            price = win[j]
            val dxt = price - average
            val dyt = v - daY
            price -= v
            stdAcc += price * price
            dsxx += dxt * dxt; dsyy += dyt * dyt; dsxy += dxt * dyt
            v += slope
        }
        val stdDev = sqrt(stdAcc / (if (periods == 0) 1 else periods))
        val r = if (dsxx == 0.0 || dsyy == 0.0) 0.0 else dsxy / sqrt(dsxx * dsyy)
        val upOff = if (useUpper) upperMult * stdDev else upDev
        val dnOff = if (useLower) -lowerMult * stdDev else -dnDev
        return Channel(slope, average, intercept, startPrice, endPrice, stdDev, r, upDev, dnDev,
            startPrice + upOff, endPrice + upOff, startPrice + dnOff, endPrice + dnOff, sign(startPrice - endPrice))
    }

    /** `trend` at every bar; NaN until the window is full. */
    fun rollingTrend(source: DoubleArray, length: Int = 100): DoubleArray {
        val n = source.size
        val out = DoubleArray(n) { Double.NaN }
        if (length < 1 || n < length) return out
        var sumX = 0.0; var sumXSqr = 0.0
        for (p in 1..length) { sumX += p; sumXSqr += p.toDouble() * p }
        val denom = length * sumXSqr - sumX * sumX
        if (denom == 0.0) return out
        for (end in length - 1 until n) {
            var sumY = 0.0; var sumXY = 0.0
            for (i in 0 until length) {
                val y = source[end - i]
                sumY += y; sumXY += y * (i + 1)
            }
            val slope = (length * sumXY - sumX * sumY) / denom
            out[end] = sign(slope * (length - 1))
        }
        return out
    }

    /** uptrend: trend[1] >= 0 and trend < 0 -> BUY; downtrend: trend[1] <= 0 and trend > 0 -> SELL. */
    fun flipSignals(trend: DoubleArray): Pair<BooleanArray, BooleanArray> {
        val buys = BooleanArray(trend.size)
        val sells = BooleanArray(trend.size)
        for (i in 1 until trend.size) {
            val t = trend[i]; val p = trend[i - 1]
            if (t.isNaN() || p.isNaN()) continue
            buys[i] = p >= 0 && t < 0
            sells[i] = p <= 0 && t > 0
        }
        return buys to sells
    }
}
