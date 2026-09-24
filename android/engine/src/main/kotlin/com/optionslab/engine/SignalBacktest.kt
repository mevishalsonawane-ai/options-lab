package com.optionslab.engine

import kotlin.math.abs

/**
 * Backtest an underlying-signal strategy that trades options. Port of
 * `strategy/signal_backtest.py`.
 *
 * Buy signal -> buy an ATM CALL. Sell signal -> buy an ATM PUT. Exit on the
 * opposite signal or at the session close. NEXT-BAR ENTRY, ONE CONTRACT PER
 * TRADE, COSTS ALWAYS, NO INVENTED PRICES.
 */
object SignalBacktest {
    class AmbiguousChain(msg: String) : IllegalArgumentException(msg)

    data class Bars(val minutes: IntArray, val open: DoubleArray, val high: DoubleArray, val low: DoubleArray, val close: DoubleArray)

    data class Trade(
        val entryMinute: Int, val exitMinute: Int, val direction: String, val right: Right, val strike: Double,
        val contractId: String, val entryPx: Double, val exitPx: Double,
        val underlyingEntry: Double, val underlyingExit: Double,
        val grossPnl: Double, val cost: Double, val netPnl: Double, val barsHeld: Int, val exitReason: String,
    )

    fun run(
        bars: Bars, chain: List<Series>, buys: BooleanArray, sells: BooleanArray,
        lotSize: Int, lots: Int = 1, regime: String = "quoted", maxStrikeDistancePct: Double = 0.02,
        underlying: String = "NIFTY",
    ): List<Trade> {
        require(buys.size == sells.size && sells.size == bars.close.size) { "signal arrays must align with bars" }
        val opts = chain.filter { it.right != Right.IX }
        val expiries = opts.mapNotNull { it.expiry }.toSortedSet()
        if (expiries.size > 1) throw AmbiguousChain(
            "chain carries ${expiries.size} expiries ${expiries.take(3)}; select one before backtesting so the traded contract is chosen, not stumbled into")
        val byKey = opts.associateBy { it.strike to it.right }
        val strikes = opts.map { it.strike }.distinct().sorted()
        val trades = ArrayList<Trade>()

        fun quote(s: Series, minute: Int): Double? {
            val i = s.lastAtOrBefore(minute)
            return if (i >= 0) s.close[i] else null
        }

        data class Pos(val entry: Int, val direction: String, val right: Right, val strike: Double, val series: Series, val px: Double, val spot: Double, val bar: Int)

        fun closeOut(p: Pos, minute: Int, spot: Double, reason: String, i: Int) {
            val exitPx = quote(p.series, minute) ?: return
            val qty = lotSize * lots
            val gross = (exitPx - p.px) * qty
            val rt = Costs.roundTrip(p.px, lotSize, lots, regime)
            trades += Trade(p.entry, minute, p.direction, p.right, p.strike, p.series.contractId(underlying),
                p.px, exitPx, p.spot, spot, gross, rt.total, gross - rt.total, i - p.bar, reason)
        }

        var open: Pos? = null
        val n = bars.close.size
        for (i in 0 until n) {
            val ts = bars.minutes[i]
            val spot = bars.close[i]
            if (open != null && i > 0) {
                val opp = if (open.direction == "long_call") sells[i - 1] else buys[i - 1]
                if (opp) { closeOut(open, ts, spot, "opposite_signal", i); open = null }
            }
            if (i == 0 || open != null) continue
            val wantCall = buys[i - 1]
            val wantPut = sells[i - 1]
            if (!(wantCall || wantPut) || strikes.isEmpty()) continue
            val right = if (wantCall) Right.CE else Right.PE
            val entrySpot = bars.open[i]
            val k = strikes.minBy { abs(it - entrySpot) }
            if (abs(k - entrySpot) / entrySpot > maxStrikeDistancePct) continue
            val s = byKey[k to right] ?: continue
            val px = quote(s, ts) ?: continue
            if (px <= 0) continue
            open = Pos(ts, if (wantCall) "long_call" else "long_put", right, k, s, px, entrySpot, i)
        }
        open?.let { closeOut(it, bars.minutes[n - 1], bars.close[n - 1], "session_close", n - 1) }
        return trades
    }
}

/**
 * Signed option order flow (port of `features/flow.py`): per minute, the sum
 * over the chain of sign(close_t - close_{t-1}) * volume, calls +1, puts -1.
 * An unchanged price contributes zero however large the volume.
 */
object Flow {
    fun signedFlow(chain: List<Series>, normalise: Boolean = false): Map<Int, Double> {
        val flow = java.util.TreeMap<Int, Double>()
        val traded = java.util.TreeMap<Int, Double>()
        for (s in chain) {
            val side = when (s.right) { Right.CE -> 1.0; Right.PE -> -1.0; Right.IX -> continue }
            val vol = s.volume ?: continue
            for (i in 0 until s.size) {
                val tick = if (i == 0) 0.0 else Math.signum(s.close[i] - s.close[i - 1])
                val m = s.minutes[i]
                flow.merge(m, tick * side * vol[i], Double::plus)
                traded.merge(m, vol[i].toDouble(), Double::plus)
            }
        }
        if (!normalise) return flow
        return flow.mapValues { (m, f) -> val t = traded[m] ?: 0.0; if (t == 0.0) 0.0 else f / t }
    }
}

/** Regime gates (port of `features/gates.py`): where a signal may trade, not what it says. */
object Gates {
    const val SESSION_MINUTES = 375
    const val LIQUID_THRESHOLD = 0.80
    class Expired(msg: String) : IllegalArgumentException(msg)

    fun dte(session: java.time.LocalDate, expiry: java.time.LocalDate): Int {
        val d = (expiry.toEpochDay() - session.toEpochDay()).toInt()
        if (d < 0) throw Expired("contract expired $expiry, session is $session")
        return d
    }

    fun dteBucket(days: Int): String {
        if (days < 0) throw Expired("dte $days is negative; the contract already expired")
        for ((edge, label) in listOf(0 to "0", 1 to "1", 3 to "2-3", 7 to "4-7", 21 to "8-21")) if (days <= edge) return label
        return "22+"
    }

    fun atmThetaSharePerDay(dte: Int): Double {
        require(dte > 0) { "theta share is undefined on expiry day - T is 0 and the option settles rather than decays." }
        val t = dte / 365.0
        return (1.0 / (2.0 * t)) / 365.0
    }

    fun spreadInThetaMinutes(spreadPts: Double, premium: Double, dte: Int): Double {
        val decay = atmThetaSharePerDay(dte) * premium
        require(decay > 0) { "non-positive decay" }
        return spreadPts / (decay / SESSION_MINUTES)
    }

    fun timeOfDayBucket(minute: Int): String = when {
        minute < 10 * 60 -> "open"
        minute < 14 * 60 + 30 -> "midday"
        else -> "close"
    }

    fun liquid(tradedFraction: Double, threshold: Double = LIQUID_THRESHOLD): Boolean {
        require(!tradedFraction.isNaN()) { "traded_fraction is NaN; compute it before gating" }
        require(tradedFraction in 0.0..1.0) { "traded_fraction $tradedFraction is outside [0, 1]" }
        return tradedFraction >= threshold
    }
}
