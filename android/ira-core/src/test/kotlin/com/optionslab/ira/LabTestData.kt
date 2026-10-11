package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max

/**
 * Seeded synthetic market days for the strategy lab's tests: an index's 1-minute random walk (09:15-15:29), the nearest
 * series' near-the-money options priced from it minute by minute (intrinsic plus a decaying time value, the wicks from the
 * index's wicks), OI drifting at random. [edge] plants a real edge: once a minute closes beyond the first 45 minutes' range
 * after 10:00, the index drifts that way by [edge] a minute for the next hour (an ORB continuation that is truly there).
 */
object LabTestData {
    fun days(n: Int, seed: Long, underlying: String = "BANKNIFTY", edge: Double = 0.0, legs: Boolean = true, strikes: Int = 4,
             start: LocalDate = LocalDate.of(2025, 1, 6), sigma: Double = 0.0003, vix: Boolean = false): List<ShadowSim.Day> {
        val rng = DiscoveryStats.Rng(seed)
        val out = ArrayList<ShadowSim.Day>(n)
        var d = start
        var level = if (underlying == "BANKNIFTY") 50_000.0 else 24_000.0
        var prev: ShadowSim.Day? = null
        val step = ShadowSim.stepOf(underlying)
        while (out.size < n) {
            if (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) { d = d.plusDays(1); continue }
            val m = 375
            val mins = IntArray(m) { ShadowSim.OPEN + it }
            val o = DoubleArray(m); val h = DoubleArray(m); val l = DoubleArray(m); val c = DoubleArray(m)
            var x = level * (1 + 0.003 * rng.nextGaussian())
            var orh = Double.NEGATIVE_INFINITY; var orl = Double.POSITIVE_INFINITY
            var drift = 0.0; var driftLeft = 0
            for (i in 0 until m) {
                val open = x
                val move = x * (sigma * rng.nextGaussian() + drift)
                x += move
                val wick = x * sigma * 0.5 * kotlin.math.abs(rng.nextGaussian())
                o[i] = open; c[i] = x; h[i] = max(open, x) + wick; l[i] = minOf(open, x) - wick * kotlin.math.abs(rng.nextGaussian())
                if (driftLeft > 0) { driftLeft--; if (driftLeft == 0) drift = 0.0 }
                val minute = mins[i]
                if (minute < ShadowSim.OPEN + 45) { orh = max(orh, h[i]); orl = minOf(orl, l[i]) }
                else if (edge > 0 && drift == 0.0 && driftLeft == 0 && minute in 600..780) {
                    if (c[i] > orh) { drift = edge; driftLeft = 60; orh = Double.POSITIVE_INFINITY; orl = Double.NEGATIVE_INFINITY }
                    else if (c[i] < orl) { drift = -edge; driftLeft = 60; orh = Double.POSITIVE_INFINITY; orl = Double.NEGATIVE_INFINITY }
                }
            }
            level = x
            val index = ShadowSim.Series(mins, o, h, l, c)
            val expiryDay = d.dayOfWeek == DayOfWeek.THURSDAY
            val ls = if (!legs) emptyList() else {
                val atm = floor(o[0] / step + 0.5) * step
                (-strikes..strikes).flatMap { k -> listOf(1, -1).map { r -> leg(index, atm + k * step, r, expiryDay, rng) } }
            }
            val vx = if (!vix) null else {
                var v = 13.0 + 3 * rng.nextGaussian()
                val vc = DoubleArray(m) { v += 0.02 * rng.nextGaussian(); v }
                ShadowSim.Series(mins, vc, vc, vc, vc)
            }
            val day = ShadowSim.Day(d, underlying, ShadowSim.lotOf(underlying), expiryDay, index, ls,
                prev?.let { p -> p.index.h.max() }, prev?.let { p -> p.index.l.min() }, prev?.let { p -> p.index.c.last() },
                vx, busy = rng.nextInt(2) == 0)
            out += day; prev = day
            d = d.plusDays(1)
        }
        return out
    }

    /** An option's minutes priced from the index: intrinsic plus time value, the wicks from the index's own. */
    private fun leg(ix: ShadowSim.Series, strike: Double, right: Int, expiry: Boolean, rng: DiscoveryStats.Rng): ShadowSim.Leg {
        val m = ix.size
        fun price(s: Double, i: Int): Double {
            val left = (m - i).toDouble() / m
            val tv = s * 0.004 * (if (expiry) left else 0.6 + 0.4 * left) * exp(-kotlin.math.abs(s - strike) / (s * 0.01))
            return max(0.05, max(0.0, right * (s - strike)) + tv)
        }
        val o = DoubleArray(m) { price(ix.o[it], it) }
        val c = DoubleArray(m) { price(ix.c[it], it) }
        val a = DoubleArray(m) { price(ix.h[it], it) }; val b = DoubleArray(m) { price(ix.l[it], it) }
        val h = DoubleArray(m) { maxOf(a[it], b[it], o[it], c[it]) }
        val l = DoubleArray(m) { minOf(a[it], b[it], o[it], c[it]) }
        var oi = 100_000L + rng.nextInt(50_000)
        val ois = LongArray(m) { oi += rng.nextInt(2_001) - 1_000; oi }
        return ShadowSim.Leg(strike, right, ShadowSim.Series(ix.minutes, o, h, l, c, ois))
    }
}
