package com.optionslab.engine.options

import com.optionslab.engine.Right
import com.optionslab.engine.Series
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * Every option-chain screen, computed at once from one priced chain: the
 * Tools tab's single source. Pure, so the phone shows exactly the numbers
 * the ported IraAlgo analytics produce for these inputs.
 */
data class ChainSnapshot(
    val underlying: String,
    val expiry: LocalDate,
    val spot: Double,
    val lotSize: Int,
    val rows: List<ChainRow>,
    val atm: Double?,
    val forward: Double,
    val tYears: Double,
    val dteDays: Double,
    val labels: List<StrikeLabel>,
    val ceGreeks: List<ChainLegGreeks?>,
    val peGreeks: List<ChainLegGreeks?>,
    val pcr: Pcr,
    val maxPain: MaxPain?,
    val ivSmile: IvSmile?,
    val gex: Gex?,
    val gammaDensity: GammaDensity?,
    val synthetic: SyntheticFuture?,
) {
    /** The ATM implied vol (percent) the payoff's probability and range use. */
    val atmIv: Double? get() = ivSmile?.atmIv ?: gammaDensity?.atmIv

    val expiryCode: String get() = OptionMath.expiryCode(expiry)

    companion object {
        /**
         * Rows from per-contract minute series: the last print is the LTP and
         * the OI, volume is the day's sum. [symbols] names each (strike, right).
         */
        fun rowsFrom(series: List<Series>, symbols: Map<Pair<Double, Right>, String>, lotSize: Int): List<ChainRow> {
            fun leg(s: Series?): OptLeg? {
                if (s == null || s.size == 0) return null
                val i = s.size - 1
                // The session's first OI reading is the baseline for "OI change today"; a single reading has none.
                val first = (0 until s.size).firstOrNull { s.oi[it] > 0 }
                val prev = if (s.size > 1 && first != null && first < i) s.oi[first] else null
                return OptLeg(symbols[s.strike to s.right] ?: "", s.close[i], oi = s.oi[i], volume = s.volume?.sum() ?: 0L, prevOi = prev, lotSize = lotSize)
            }
            val by = series.filter { it.right != Right.IX }.groupBy { it.strike }
            return by.keys.sorted().map { k ->
                val side = by.getValue(k)
                ChainRow(k, leg(side.firstOrNull { it.right == Right.CE }), leg(side.firstOrNull { it.right == Right.PE }))
            }
        }

        fun of(underlying: String, expiry: LocalDate, spot: Double, lotSize: Int, rows: List<ChainRow>, now: ZonedDateTime): ChainSnapshot {
            val strikes = rows.map { it.strike }
            val atm = ChainAnalytics.atmStrike(spot, strikes)
            val t = OptionMath.timeToExpiryYears(now, expiry)
            val dte = t * 365.0
            val forward = if (atm != null) ChainAnalytics.forwardFromChain(rows, atm, spot) else spot
            val (ce, pe) = OptionMath.chainGreeks(strikes, rows.map { it.ce?.ltp }, rows.map { it.pe?.ltp }, forward, t)
            return ChainSnapshot(
                underlying, expiry, spot, lotSize, rows, atm, forward, t, dte,
                if (atm != null) ChainAnalytics.strikeLabels(strikes, atm) else emptyList(),
                ce, pe,
                ChainAnalytics.pcr(rows),
                ChainAnalytics.maxPain(rows, spotPrice = spot, atmStrike = atm),
                if (t > 0) ChainAnalytics.ivSmile(rows, spot, atm, t) else null,
                if (t > 0) ChainAnalytics.gex(rows, spot, atm, t) else null,
                if (t > 0) ChainAnalytics.gammaDensity(rows, spot, atm, forward, t, dte) else null,
                ChainAnalytics.syntheticFuture(rows, spot),
            )
        }
    }
}
