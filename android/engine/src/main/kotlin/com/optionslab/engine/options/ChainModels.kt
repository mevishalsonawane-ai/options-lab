package com.optionslab.engine.options

/*
 * Broker-independent inputs and the results of every IraAlgo option-chain
 * screen. Results carry numbers ROUNDED exactly as IraAlgo's JSON rounds them
 * (Python's round-half-even on the binary value, see [pyRound]), because that
 * is what its screens show and because a few of its figures are computed from
 * already-rounded ones; anything not rounded by IraAlgo is left unrounded.
 */

/**
 * One side of one strike, as a quote. [prevOi] is the previous session's
 * closing OI, used by the OI-change views; [lotSize] the contract multiplier.
 */
data class OptLeg(
    val symbol: String,
    val ltp: Double,
    val bid: Double? = null,
    val ask: Double? = null,
    val oi: Long = 0,
    val volume: Long = 0,
    val prevOi: Long? = null,
    val lotSize: Int? = null,
)

/** A strike of the chain; a side is null where no contract is listed. */
data class ChainRow(val strike: Double, val ce: OptLeg?, val pe: OptLeg?)

/** A strike's moneyness labels as the option chain shows them: ATM, ITMn, OTMn. */
data class StrikeLabel(val strike: Double, val ceLabel: String, val peLabel: String)

/** A (time, value) point. Times are epoch seconds, as IraAlgo's charts use. */
data class TimeValue(val time: Long, val value: Double)

// ---------------------------------------------------------------- OI tracker / max pain

data class OiStrike(val strike: Double, val ceOi: Long, val peOi: Long)

/** Totals and put-call ratios. PCRs are rounded to 2 dp, 0 when the call side is empty. */
data class Pcr(
    val totalCeOi: Long,
    val totalPeOi: Long,
    val totalCeVolume: Long,
    val totalPeVolume: Long,
    val pcrOi: Double,
    val pcrVolume: Double,
)

/** `get_oi_data`: the OI tracker screen. */
data class OiData(
    val spotPrice: Double?,
    val futuresPrice: Double?,
    val atmStrike: Double?,
    val lotSize: Int,
    val pcr: Pcr,
    val chain: List<OiStrike>,
)

/** Writers' loss if the underlying settles at [strike]. Pains in rupees x OI; [totalPainCr] in crores. */
data class Pain(val strike: Double, val cePain: Double, val pePain: Double, val totalPain: Double, val totalPainCr: Double)

/** `calculate_max_pain`. */
data class MaxPain(val maxPainStrike: Double, val painData: List<Pain>, val oi: OiData)

// ---------------------------------------------------------------- OI profile

/** A strike of the OI butterfly and its daily change. */
data class OiProfileStrike(val strike: Double, val ceOi: Long, val peOi: Long, val ceOiChange: Double, val peOiChange: Double)

/** `get_oi_profile_data` minus the futures candles, which are data, not computation. */
data class OiProfile(val spotPrice: Double?, val atmStrike: Double?, val lotSize: Int, val chain: List<OiProfileStrike>)

// ---------------------------------------------------------------- straddles

/** Close prices of one strike's call and put, keyed by candle time (epoch seconds). */
data class StrikeCloses(val ce: Map<Long, Double>, val pe: Map<Long, Double>)

/** One point of the dynamic ATM straddle chart. */
data class StraddlePoint(
    val time: Long,
    val spot: Double,
    val atmStrike: Double,
    val cePrice: Double,
    val pePrice: Double,
    val straddle: Double,
    val syntheticFuture: Double,
)

data class StraddleChart(val daysToExpiry: Long, val series: List<StraddlePoint>)

data class StraddlePnlPoint(
    val time: Long,
    val pnl: Double,
    val spot: Double,
    val atmStrike: Double,
    val entryStrike: Double,
    val cePrice: Double,
    val pePrice: Double,
    val straddle: Double,
    val syntheticFuture: Double,
    val adjustments: Int,
)

enum class StraddleTradeType { ENTRY, ADJUSTMENT, EXIT }

/** A trade-log line. The exit/old-strike fields are set only on ADJUSTMENT. */
data class StraddleTrade(
    val time: Long,
    val type: StraddleTradeType,
    val strike: Double,
    val cePrice: Double,
    val pePrice: Double,
    val straddle: Double,
    val spot: Double,
    val legPnl: Double,
    val cumulativePnl: Double,
    val oldStrike: Double? = null,
    val exitCe: Double? = null,
    val exitPe: Double? = null,
    val exitStraddle: Double? = null,
)

/** `get_custom_straddle_simulation`. [totalPnl] is realised P&L over the simulated days. */
data class CustomStraddle(
    val quantity: Int,
    val pnlSeries: List<StraddlePnlPoint>,
    val trades: List<StraddleTrade>,
    val totalPnl: Double,
    val totalAdjustments: Int,
    val maxPnl: Double,
    val minPnl: Double,
)

// ---------------------------------------------------------------- volatility

data class IvSmileStrike(val strike: Double, val ceIv: Double?, val peIv: Double?)

/** `get_iv_smile_data`. IVs in percent; [skew] = 5%-OTM put IV minus 5%-OTM call IV. */
data class IvSmile(val spotPrice: Double, val atmStrike: Double?, val atmIv: Double?, val skew: Double?, val chain: List<IvSmileStrike>)

/** One expiry of the surface input: its whole listed chain. */
data class SurfaceExpiry(val code: String, val expiry: java.time.LocalDate, val chain: List<ChainRow>)

data class SurfaceExpiryInfo(val date: String, val dte: Double)

/** `get_vol_surface_data`: surface[expiry][strike] in percent, null where no IV. */
data class VolSurface(
    val underlyingLtp: Double,
    val atmStrike: Double,
    val strikes: List<Double>,
    val expiries: List<SurfaceExpiryInfo>,
    val surface: List<List<Double?>>,
)

// ---------------------------------------------------------------- gamma

data class GexStrike(
    val strike: Double,
    val ceOi: Long,
    val peOi: Long,
    val ceGamma: Double,
    val peGamma: Double,
    val ceGex: Double,
    val peGex: Double,
    val netGex: Double,
)

/** `get_gex_data`: GEX = gamma x OI x lot size; net = call - put. */
data class Gex(
    val spotPrice: Double,
    val futuresPrice: Double?,
    val atmStrike: Double?,
    val lotSize: Int,
    val pcrOi: Double,
    val totalCeOi: Long,
    val totalPeOi: Long,
    val totalCeGex: Double,
    val totalPeGex: Double,
    val totalNetGex: Double,
    val chain: List<GexStrike>,
)

data class GammaDensityStrike(val strike: Double, val ceOi: Long, val peOi: Long, val iv: Double?, val densityIntraday: Double, val densityExpiry: Double)

/** Expected-move band: spot +/- 1 and 2 sigma. */
data class SigmaBand(val sigmaMove: Double, val oneSigmaLow: Double, val oneSigmaHigh: Double, val twoSigmaLow: Double, val twoSigmaHigh: Double)

/** `calculate_gamma_density`. [intradayBand] is the one shown on the stat cards. */
data class GammaDensity(
    val spotPrice: Double,
    val forwardPrice: Double,
    val atmStrike: Double?,
    val atmIv: Double,
    val dteDays: Double,
    val interestRate: Double,
    val peakIntradayStrike: Double?,
    val peakExpiryStrike: Double?,
    val intradayBand: SigmaBand,
    val expiryBand: SigmaBand,
    val chain: List<GammaDensityStrike>,
)

// ---------------------------------------------------------------- synthetic future, arbitrage

/** `calculate_synthetic_future`: K + C - P at the ATM strike (2 dp), and its basis over spot. */
data class SyntheticFuture(val underlyingLtp: Double, val atmStrike: Double, val price: Double, val basis: Double)

/** A futures contract from the master contract list. */
data class FutContract(
    val symbol: String,
    val exchange: String,
    val underlying: String,
    val expiry: String,
    val lotSize: Int? = null,
    val tickSize: Double? = null,
)

/** A calendar pair: near month against next (`near-next`) or third (`near-third`). */
data class CalendarPair(val id: String, val underlying: String, val exchange: String, val type: String, val near: FutContract, val far: FutContract)

data class ArbitrageUniverse(val pairs: List<CalendarPair>, val symbols: List<Pair<String, String>>, val underlyingCount: Int)

/** A live quote; a non-positive bid/ask/ltp is treated as absent. [ts] in epoch millis. */
data class Quote(val bid: Double? = null, val ask: Double? = null, val ltp: Double? = null, val ts: Long? = null)

enum class SpreadDirection { SHORT_SPREAD, LONG_SPREAD }

/** One row of the calendar-spread scanner. [spreadPct] is the best executable credit over the near mid. */
data class SpreadRow(
    val pair: CalendarPair,
    val nearMid: Double?,
    val farMid: Double?,
    val rawSpread: Double?,
    val bestCredit: Double?,
    val direction: SpreadDirection?,
    val spreadPct: Double?,
    val fresh: Boolean,
    val liquid: Boolean,
) {
    val hasData: Boolean get() = spreadPct != null
}

// ---------------------------------------------------------------- multi-strike OI

/** One Strategy Builder leg's OI history. */
data class LegOiSeries(val symbol: String, val side: String, val strike: Double?, val optionType: OptionType?, val expiry: String?, val series: List<TimeValue>)

data class LegOi(val symbol: String, val side: String, val strike: Double?, val optionType: OptionType?, val expiry: String?, val hasOi: Boolean, val series: List<TimeValue>)

data class MultiStrikeOi(val underlyingAvailable: Boolean, val underlyingSeries: List<TimeValue>, val legs: List<LegOi>)
