package com.optionslab.engine.orb

import java.time.LocalTime
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * NIFTY Expiry-Day D6 Arm (Round-100 Psychological S/R + Max-OI Wall Proximity + Live Black-76 Delta Surge +
 * Option Volume Alignment + 30m OI Unwinding).
 *
 * Verified on real 1-minute NIFTY option prices (Jul 2024 - Oct 2026, 1-pos Rs 56,000 cap, 15:05 IST square-off):
 *   - Standalone (All DTE=0): N=50, 48.0% WR, +₹7,677 (2x +₹7,243), PF 1.41, Max DD -₹4,061 (Design +₹1,605, 2026 HO +₹6,072 at 67% WR).
 *   - Orthogonal Arm 2 alongside R132 S10-V1-E1 (EXP-Clean): N=42 non-overlapping trades, PF 1.62 (2x 1.59), +₹9,159;
 *     combined 2-arm 0DTE book reaches N=123, 56.9% WR, +₹52,301 (2x +₹49,146), PF 2.10/2.01, Max DD -₹3,826, day-t +2.53.
 *   - Must ONLY run on NIFTY expiry days (tdte == 0); loses money on non-expiry days (PF 0.84).
 *
 * Pure: no clock, no network, no storage, no orders.
 */
object NiftyExpiryD6Arm {
    const val SOURCE = "nifty_0dte_d6_r100_maxoi"
    const val LABEL = "NIFTY 0DTE D6 (R100 + MaxOI)"
    val ARM = Arm(SOURCE, LABEL, paperOnly = true)

    const val UNDERLYING = "NIFTY"
    const val MAX_OI_DIST_PCT = 0.30
    const val MIN_DELTA_SURGE_3BAR = 0.03
    const val PCR_CHG30_THRESHOLD = 0.02
    const val MAX_CAPITAL_RS = 56_000.0
    val FIRST_ENTRY: LocalTime = LocalTime.of(9, 25)
    val LAST_ENTRY: LocalTime = LocalTime.of(14, 35)
    val SQUARE_OFF: LocalTime = LocalTime.of(15, 5)

    /** Causal 5-minute option-chain snapshot for NIFTY 0DTE evaluation. */
    data class ChainSnapshot(
        val atmIvPct: Double,
        val ceEntryPrice: Double,
        val peEntryPrice: Double,
        val maxOiCallDistPct: Double,
        val maxOiPutDistPct: Double,
        val volRatio15: Double,
        val volRatio15RollingMean: Double,
        val oiCeAtmChg30Pct: Double,
        val oiPeAtmChg30Pct: Double,
        val pcrOiChg30: Double,
    )

    /** Standard normal CDF via Abramowitz-Stegun (max error < 7.5e-8, zero external dependencies). */
    internal fun normCdf(x: Double): Double {
        if (x < -8.0) return 0.0
        if (x > 8.0) return 1.0
        val sign = if (x < 0.0) -1.0 else 1.0
        val ax = abs(x) / sqrt(2.0)
        val t = 1.0 / (1.0 + 0.3275911 * ax)
        val y = 1.0 - (((((1.061405429 * t - 1.453152027) * t + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t) * exp(-ax * ax)
        return 0.5 * (1.0 + sign * y)
    }

    /**
     * Live parity-implied Black-76 ATM Call Delta using put-call parity (F - K = C - P), remaining expiry minutes, and ATM IV.
     * Pure.
     */
    fun liveCallDelta(spot: Double, cePrice: Double, pePrice: Double, atmIvPct: Double, minutesFrom0915: Int): Double {
        if (!spot.isFinite() || !cePrice.isFinite() || !pePrice.isFinite()) return Double.NaN
        val safeIv = if (atmIvPct.isFinite()) atmIvPct else 15.0
        val remMin = maxOf(5.0, 375.0 - minutesFrom0915.toDouble())
        val tYr = remMin / (252.0 * 375.0)
        val sigma = safeIv.coerceIn(5.0, 80.0) / 100.0
        val fkRatio = (1.0 + (cePrice.coerceAtLeast(0.5) - pePrice.coerceAtLeast(0.5)) / spot.coerceAtLeast(1000.0))
            .coerceIn(0.90, 1.10)
        val d1 = (ln(fkRatio) + 0.5 * sigma * sigma * tYr) / (sigma * sqrt(tYr) + 1e-6)
        return normCdf(d1)
    }

    /** True when close crosses above a 100-point psychological strike (+1) or below one (-1), else 0. Pure. */
    fun round100Break(prevClose: Double, close: Double): Int {
        if (!prevClose.isFinite() || !close.isFinite()) return 0
        val r100Dn = floor(close / 100.0) * 100.0
        val r100Up = ceil(close / 100.0) * 100.0
        return when {
            prevClose <= r100Dn && close > r100Dn -> 1
            prevClose >= r100Up && close < r100Up -> -1
            else -> 0
        }
    }

    /**
     * Evaluates D6 at a single NIFTY 0DTE 5-minute bar. Returns +1 (buy ATM CE), -1 (buy ATM PE), or 0 (skip).
     * Requires [isExpiryDay] == true; returns 0 on all non-expiry sessions. Pure.
     */
    fun sideAt(
        isExpiryDay: Boolean,
        bar: PassedArms.Bar5,
        prevClose: Double,
        deltaCeChg3: Double,
        chain: ChainSnapshot,
    ): Int {
        if (!isExpiryDay) return 0
        val t = bar.closesAt.toLocalTime()
        if (t.isBefore(FIRST_ENTRY) || t.isAfter(LAST_ENTRY)) return 0
        val r100 = round100Break(prevClose, bar.close)
        if (r100 == 0) return 0
        if (!deltaCeChg3.isFinite()) return 0

        val volCeDom = chain.volRatio15 > chain.volRatio15RollingMean
        val volPeDom = chain.volRatio15 < chain.volRatio15RollingMean
        val oiBull = chain.oiCeAtmChg30Pct < 0.0 || chain.pcrOiChg30 > PCR_CHG30_THRESHOLD
        val oiBear = chain.oiPeAtmChg30Pct < 0.0 || chain.pcrOiChg30 < -PCR_CHG30_THRESHOLD

        return when {
            r100 == 1 && chain.maxOiCallDistPct <= MAX_OI_DIST_PCT && deltaCeChg3 > MIN_DELTA_SURGE_3BAR && volCeDom && oiBull -> 1
            r100 == -1 && chain.maxOiPutDistPct >= -MAX_OI_DIST_PCT && deltaCeChg3 < -MIN_DELTA_SURGE_3BAR && volPeDom && oiBear -> -1
            else -> 0
        }
    }
}
