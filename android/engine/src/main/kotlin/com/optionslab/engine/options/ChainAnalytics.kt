package com.optionslab.engine.options

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * IraAlgo's option-chain analytics, computation only. Each function is a port
 * of one service, with the fetching stripped out: the caller supplies the
 * chain (or candle history) it already has, and gets back what the IraAlgo
 * screen shows.
 *
 * Two IraAlgo habits are kept on purpose, because its numbers depend on them:
 *  - the IV smile, vol surface and GEX invert IV with the UNDERLYING LTP as
 *    the Black-76 forward (they call `calculate_greeks` with spot), while the
 *    gamma-density view uses the synthetic forward. Pass what the screen uses.
 *  - GEX multiplies the gamma IraAlgo DISPLAYS (rounded to 6 dp), not the raw
 *    one; for NIFTY that is ~3 significant figures, and the GEX follows it.
 */
object ChainAnalytics {
    // ================================================================ strikes

    /**
     * `find_atm_strike_from_actual`: the listed strike nearest the LTP. On a
     * tie the earlier strike in [strikes] wins (the lower one, for a sorted
     * ladder), as Python's min() does. Null for an empty ladder or a NaN LTP.
     */
    fun atmStrike(ltp: Double, strikes: List<Double>): Double? {
        if (strikes.isEmpty() || !ltp.isFinite()) return null
        var best = strikes[0]
        for (k in strikes) if (abs(k - ltp) < abs(best - ltp)) best = k
        return best
    }

    /**
     * `get_strikes_with_labels`: [strikeCount] strikes either side of ATM (all
     * when null), each labelled ATM / ITMn / OTMn per side: below ATM the call
     * is ITM and the put OTM. With the ATM missing from the ladder every strike
     * comes back unlabelled.
     */
    fun strikeLabels(strikes: List<Double>, atm: Double, strikeCount: Int? = null): List<StrikeLabel> {
        val atmIndex = strikes.indexOf(atm)
        if (atmIndex < 0) return strikes.map { StrikeLabel(it, "", "") }
        val selected = if (strikeCount == null) strikes
        else strikes.subList(max(0, atmIndex - strikeCount), min(strikes.size, atmIndex + strikeCount + 1))
        return selected.map { k ->
            val pos = strikes.indexOf(k) - atmIndex
            when {
                k == atm -> StrikeLabel(k, "ATM", "ATM")
                k < atm -> StrikeLabel(k, "ITM${-pos}", "OTM${-pos}")
                else -> StrikeLabel(k, "OTM$pos", "ITM$pos")
            }
        }
    }

    /**
     * `calculate_offset_strike_from_actual`: walk the listed ladder from ATM.
     * A call's ITMn is n strikes DOWN, a put's n strikes UP; OTM the reverse.
     * Null when the walk leaves the ladder or the offset is not ATM/ITMn/OTMn.
     */
    fun offsetStrike(atm: Double, offset: String, type: OptionType, strikes: List<Double>): Double? {
        val atmIndex = strikes.indexOf(atm)
        if (strikes.isEmpty() || atmIndex < 0) return null
        val o = offset.uppercase()
        if (o == "ATM") return atm
        val n = o.drop(3).toIntOrNull() ?: return null
        val up = when {
            o.startsWith("ITM") -> if (type.isCall) -n else n
            o.startsWith("OTM") -> if (type.isCall) n else -n
            else -> return null
        }
        return strikes.getOrNull(atmIndex + up)
    }

    /**
     * `get_option_chain`'s window: the ATM from the underlying LTP, then
     * [strikeCount] strikes either side of it. [rows] must be the full listed
     * ladder, ascending.
     */
    fun chainWindow(rows: List<ChainRow>, underlyingLtp: Double, strikeCount: Int?): Pair<Double?, List<ChainRow>> {
        val strikes = rows.map { it.strike }
        val atm = atmStrike(underlyingLtp, strikes) ?: return null to emptyList()
        val keep = strikeLabels(strikes, atm, strikeCount).map { it.strike }.toSet()
        return atm to rows.filter { it.strike in keep }
    }

    /**
     * `_forward_from_chain`: the chain's own parity forward, K + C - P at the
     * ATM strike rounded to 4 dp, or the underlying LTP when either ATM leg is
     * unpriced.
     */
    fun forwardFromChain(rows: List<ChainRow>, atmStrike: Double, underlyingLtp: Double): Double {
        val row = rows.firstOrNull { it.strike == atmStrike } ?: return underlyingLtp
        val c = row.ce?.ltp ?: 0.0
        val p = row.pe?.ltp ?: 0.0
        return if (c > 0 && p > 0) pyRound(atmStrike + c - p, 4) else underlyingLtp
    }

    // ================================================================ OI, PCR, max pain

    private fun firstLotSize(rows: List<ChainRow>): Int? {
        for (r in rows) {
            r.ce?.lotSize?.takeIf { it != 0 }?.let { return it }
            r.pe?.lotSize?.takeIf { it != 0 }?.let { return it }
        }
        return null
    }

    private fun ratio(num: Long, den: Long): Double = if (den > 0) pyRound(num.toDouble() / den, 2) else 0.0

    /** Put-call ratios by OI and by volume over the whole chain (the OI tracker's header). */
    fun pcr(rows: List<ChainRow>): Pcr {
        val ceOi = rows.sumOf { it.ce?.oi ?: 0L }
        val peOi = rows.sumOf { it.pe?.oi ?: 0L }
        val ceVol = rows.sumOf { it.ce?.volume ?: 0L }
        val peVol = rows.sumOf { it.pe?.volume ?: 0L }
        return Pcr(ceOi, peOi, ceVol, peVol, ratio(peOi, ceOi), ratio(peVol, ceVol))
    }

    /** `get_oi_data`: per-strike OI walls plus totals and PCR. Spot, futures and ATM pass through. */
    fun oiData(rows: List<ChainRow>, spotPrice: Double? = null, futuresPrice: Double? = null, atmStrike: Double? = null): OiData =
        OiData(spotPrice, futuresPrice, atmStrike, firstLotSize(rows) ?: 1, pcr(rows),
            rows.map { OiStrike(it.strike, it.ce?.oi ?: 0L, it.pe?.oi ?: 0L) })

    /**
     * `calculate_max_pain`. For each candidate settlement K*, call writers lose
     * (K* - K) x OI on every strike below it and put writers (K - K*) x OI on
     * every strike above; max pain is the K* with the least total (the first
     * one on a tie, compared at IraAlgo's 2-dp rounding). Null for an empty
     * chain or one with no positive strike.
     */
    fun maxPain(rows: List<ChainRow>, spotPrice: Double? = null, futuresPrice: Double? = null, atmStrike: Double? = null): MaxPain? {
        val oi = oiData(rows, spotPrice, futuresPrice, atmStrike)
        val chain = oi.chain.filter { it.strike > 0 }
        if (chain.isEmpty()) return null
        val pains = chain.map { cand ->
            var ce = 0.0
            var pe = 0.0
            for (s in chain) {
                if (cand.strike > s.strike && s.ceOi > 0) ce += (cand.strike - s.strike) * s.ceOi
                if (cand.strike < s.strike && s.peOi > 0) pe += (s.strike - cand.strike) * s.peOi
            }
            val total = ce + pe
            Pain(cand.strike, pyRound(ce, 2), pyRound(pe, 2), pyRound(total, 2), pyRound(total / 10_000_000, 2))
        }
        val best = pains.minBy { it.totalPain } // minBy keeps the first minimum, as Python's min()
        return MaxPain(best.strike, pains, oi)
    }

    /**
     * The OI-change butterfly of `get_oi_profile_data`: today's OI minus the
     * previous session's, per side. IraAlgo only looks up history for a leg
     * with positive OI, and reads a failed or one-day history as a previous OI
     * of 0 - so a leg with OI but no [OptLeg.prevOi] shows its whole OI as the
     * change, and a leg with zero OI shows 0.
     */
    fun oiProfile(rows: List<ChainRow>, spotPrice: Double? = null, atmStrike: Double? = null): OiProfile {
        fun change(leg: OptLeg?): Double =
            if (leg != null && leg.symbol.isNotEmpty() && leg.oi > 0) (leg.oi - (leg.prevOi ?: 0L)).toDouble() else 0.0
        return OiProfile(spotPrice, atmStrike, firstLotSize(rows) ?: 1, rows.map {
            OiProfileStrike(it.strike, it.ce?.oi ?: 0L, it.pe?.oi ?: 0L, change(it.ce), change(it.pe))
        })
    }

    /** The per-strike OI change on its own (see [oiProfile]). */
    fun oiChange(rows: List<ChainRow>): List<OiProfileStrike> = oiProfile(rows).chain

    // ================================================================ straddles

    /** IST calendar date of an epoch-seconds time. */
    fun istDate(time: Long): LocalDate = Instant.ofEpochSecond(time).atZone(OptionMath.IST).toLocalDate()

    /**
     * `_cap_last_n_trading_dates`: keep the points whose IST date is among the
     * last [n] distinct dates PRESENT - so "3 days" asked on a holiday or before
     * the open still means three sessions with data.
     */
    fun <T> capLastNTradingDates(series: List<T>, n: Int, time: (T) -> Long): List<T> {
        if (series.isEmpty() || n <= 0) return series
        val keep = series.map { istDate(time(it)) }.toSortedSet().reversed().take(n).toSet()
        return series.filter { istDate(time(it)) in keep }
    }

    /** `_calculate_days_to_expiry`: whole days (floored) to 15:30 IST on expiry, never negative. */
    fun daysToExpiry(now: ZonedDateTime, expiry: LocalDate): Long {
        val secs = Duration.between(now.toInstant(), OptionMath.expiryInstant(expiry, LocalTime.of(15, 30)).toInstant()).seconds
        return max(0L, Math.floorDiv(secs, 86_400L))
    }

    /** The distinct ATM strikes an underlying series visits: the strikes whose history the straddle views need. */
    fun straddleStrikes(underlying: List<TimeValue>, strikes: List<Double>): List<Double> =
        underlying.mapNotNull { atmStrike(it.value, strikes) }.toSortedSet().toList()

    /**
     * `get_straddle_chart_data`: the dynamic ATM straddle. At each candle the
     * ATM is re-picked from the underlying close, and that strike's CE + PE
     * closes give the straddle and the synthetic future K + C - P. Candles
     * missing either leg are dropped; the series is then capped to the last
     * [days] trading dates.
     *
     * @param underlying underlying closes (epoch seconds, close)
     * @param closes per-strike option closes, at least for [straddleStrikes]
     */
    fun straddle(underlying: List<TimeValue>, strikes: List<Double>, closes: Map<Double, StrikeCloses>, days: Int, now: ZonedDateTime, expiry: LocalDate): StraddleChart {
        val series = ArrayList<StraddlePoint>()
        for (c in underlying.sortedBy { it.time }) {
            val atm = atmStrike(c.value, strikes) ?: continue
            val sd = closes[atm] ?: continue
            val ce = sd.ce[c.time] ?: continue
            val pe = sd.pe[c.time] ?: continue
            series += StraddlePoint(c.time, pyRound(c.value, 2), atm, pyRound(ce, 2), pyRound(pe, 2), pyRound(ce + pe, 2), pyRound(atm + ce - pe, 2))
        }
        return StraddleChart(daysToExpiry(now, expiry), capLastNTradingDates(series, days) { it.time })
    }

    /**
     * `get_custom_straddle_simulation`: an intraday short ATM straddle,
     * re-centred whenever the ATM strike drifts [adjustmentPoints] or more from
     * the one held. Per IST session over the last [days] sessions with data:
     * sell the ATM straddle at the first candle both legs print; on a drift,
     * buy back the old legs and sell the new ATM (only if all four print);
     * close at the session's last candle. P&L is premium sold minus premium
     * bought, x lotSize x lots.
     */
    fun customStraddle(
        underlying: List<TimeValue>, strikes: List<Double>, closes: Map<Double, StrikeCloses>,
        days: Int = 1, adjustmentPoints: Double = 50.0, lotSize: Int = 65, lots: Int = 1,
    ): CustomStraddle {
        val quantity = lotSize * lots
        val daily = LinkedHashMap<LocalDate, MutableList<Pair<TimeValue, Double?>>>()
        for (c in underlying.sortedBy { it.time }) daily.getOrPut(istDate(c.time)) { ArrayList() } += c to atmStrike(c.value, strikes)
        fun ce(k: Double?, t: Long) = closes[k]?.ce?.get(t)
        fun pe(k: Double?, t: Long) = closes[k]?.pe?.get(t)

        var cumulative = 0.0
        var totalAdjustments = 0
        val series = ArrayList<StraddlePnlPoint>()
        val trades = ArrayList<StraddleTrade>()
        for (day in daily.keys.sorted().takeLast(max(1, days))) {
            val candles = daily.getValue(day)
            var entryStrike: Double? = null
            var entryCe = 0.0
            var entryPe = 0.0
            var dayRealized = 0.0
            var dayAdjustments = 0
            var lastUnrealized = 0.0
            for ((i, pair) in candles.withIndex()) {
                val (c, atm) = pair
                val t = c.time
                val spot = c.value
                if (atm == null || atm !in closes) continue
                val isLast = i == candles.size - 1
                if (entryStrike == null) {
                    val ac = ce(atm, t) ?: continue
                    val ap = pe(atm, t) ?: continue
                    entryStrike = atm; entryCe = ac; entryPe = ap
                    trades += StraddleTrade(t, StraddleTradeType.ENTRY, atm, pyRound(ac, 2), pyRound(ap, 2), pyRound(ac + ap, 2),
                        pyRound(spot, 2), 0.0, pyRound(cumulative, 2))
                } else if (abs(atm - entryStrike) >= adjustmentPoints) {
                    val oc = ce(entryStrike, t); val op = pe(entryStrike, t)
                    val nc = ce(atm, t); val np = pe(atm, t)
                    if (oc != null && op != null && nc != null && np != null) {
                        val legPnl = ((entryCe - oc) + (entryPe - op)) * quantity
                        dayRealized += legPnl
                        dayAdjustments += 1
                        trades += StraddleTrade(t, StraddleTradeType.ADJUSTMENT, atm, pyRound(nc, 2), pyRound(np, 2), pyRound(nc + np, 2),
                            pyRound(spot, 2), pyRound(legPnl, 2), pyRound(cumulative + dayRealized, 2),
                            oldStrike = entryStrike, exitCe = pyRound(oc, 2), exitPe = pyRound(op, 2), exitStraddle = pyRound(oc + op, 2))
                        entryStrike = atm; entryCe = nc; entryPe = np
                    }
                }
                val cc = ce(entryStrike, t)
                val cp = pe(entryStrike, t)
                val unrealized = if (cc != null && cp != null) ((entryCe - cc) + (entryPe - cp)) * quantity else lastUnrealized
                if (cc != null && cp != null) lastUnrealized = unrealized
                val total = cumulative + dayRealized + unrealized
                val atmCe = ce(atm, t) ?: 0.0
                val atmPe = pe(atm, t) ?: 0.0
                val synthetic = if (atmCe != 0.0 && atmPe != 0.0) pyRound(atm + atmCe - atmPe, 2) else pyRound(spot, 2)
                series += StraddlePnlPoint(t, pyRound(total, 2), pyRound(spot, 2), atm, entryStrike!!, pyRound(atmCe, 2), pyRound(atmPe, 2),
                    pyRound(atmCe + atmPe, 2), synthetic, totalAdjustments + dayAdjustments)
                if (isLast) {
                    val ec = ce(entryStrike, t); val ep = pe(entryStrike, t)
                    val legPnl = if (ec != null && ep != null) ((entryCe - ec) + (entryPe - ep)) * quantity else lastUnrealized
                    trades += StraddleTrade(t, StraddleTradeType.EXIT, entryStrike, pyRound(ec ?: 0.0, 2), pyRound(ep ?: 0.0, 2),
                        pyRound((ec ?: 0.0) + (ep ?: 0.0), 2), pyRound(spot, 2), pyRound(legPnl, 2), pyRound(cumulative + dayRealized + legPnl, 2))
                }
            }
            if (entryStrike != null) {
                val lastT = candles.last().first.time
                val fc = ce(entryStrike, lastT); val fp = pe(entryStrike, lastT)
                val finalLeg = if (fc != null && fp != null) ((entryCe - fc) + (entryPe - fp)) * quantity else lastUnrealized
                cumulative += dayRealized + finalLeg
            }
            totalAdjustments += dayAdjustments
        }
        val pnls = series.map { it.pnl }
        return CustomStraddle(quantity, series, trades, pyRound(cumulative, 2), totalAdjustments,
            pnls.maxOrNull()?.let { pyRound(it, 2) } ?: 0.0, pnls.minOrNull()?.let { pyRound(it, 2) } ?: 0.0)
    }

    // ================================================================ volatility

    /** IV (percent, 2 dp) as `calculate_greeks` reports it, or null for a failure or the theoretical branch. */
    private fun displayedIv(type: OptionType, forward: Double, strike: Double, tYears: Double, price: Double, ratePct: Double): Double? {
        val g = OptionMath.legGreeksRounded(type, forward, strike, tYears, price, ratePct) ?: return null
        return if (g.ivPct > 0) pyRound(g.ivPct, 2) else null
    }

    /**
     * `get_iv_smile_data`: call and put IV per strike (spot as the forward, see
     * the class note), the ATM IV (mean of both sides when both exist), and a
     * 25-delta-style skew: the put IV nearest 5% below ATM minus the call IV
     * nearest 5% above it, each from the correct side of ATM.
     *
     * @param tYears time to the chain's expiry ([OptionMath.timeToExpiryYears])
     */
    fun ivSmile(rows: List<ChainRow>, spotPrice: Double, atmStrike: Double?, tYears: Double, ratePct: Double = 0.0): IvSmile? {
        if (spotPrice <= 0) return null
        var atmCe: Double? = null
        var atmPe: Double? = null
        val chain = rows.map { r ->
            val ceIv = r.ce?.takeIf { it.symbol.isNotEmpty() && it.ltp > 0 }?.let { displayedIv(OptionType.CE, spotPrice, r.strike, tYears, it.ltp, ratePct) }
            val peIv = r.pe?.takeIf { it.symbol.isNotEmpty() && it.ltp > 0 }?.let { displayedIv(OptionType.PE, spotPrice, r.strike, tYears, it.ltp, ratePct) }
            if (r.strike == atmStrike) { atmCe = ceIv; atmPe = peIv }
            IvSmileStrike(r.strike, ceIv, peIv)
        }
        val c = atmCe
        val p = atmPe
        val atmIv = when {
            c != null && p != null -> pyRound((c + p) / 2, 2)
            else -> c ?: p
        }
        var skew: Double? = null
        if (atmStrike != null && atmStrike != 0.0 && chain.isNotEmpty()) {
            val d = atmStrike * 0.05
            val put = chain.sortedBy { abs(it.strike - (atmStrike - d)) }.firstOrNull { it.strike < atmStrike && it.peIv != null }?.peIv
            val call = chain.sortedBy { abs(it.strike - (atmStrike + d)) }.firstOrNull { it.strike > atmStrike && it.ceIv != null }?.ceIv
            if (put != null && call != null) skew = pyRound(put - call, 2)
        }
        return IvSmile(spotPrice, atmStrike, atmIv, skew, chain)
    }

    /**
     * `get_vol_surface_data`: an OTM-convention IV grid (the call at and above
     * the first expiry's ATM, the put below it) over the strikes all expiries
     * share within [strikeCount] of their own ATM - or, when fewer than 3 are
     * shared, the first expiry's window. IV is inverted against the underlying
     * LTP (see the class note). [dte] is days to the cut-off, 1 dp.
     */
    fun volSurface(underlyingLtp: Double, expiries: List<SurfaceExpiry>, strikeCount: Int, now: ZonedDateTime,
                   cutoff: LocalTime = OptionMath.DEFAULT_EXPIRY_TIME, ratePct: Double = 0.0): VolSurface? {
        data class Win(val e: SurfaceExpiry, val strikes: List<Double>, val atm: Double)
        val wins = expiries.mapNotNull { e ->
            val listed = e.chain.map { it.strike }
            if (listed.isEmpty()) return@mapNotNull null
            val atm = atmStrike(underlyingLtp, listed) ?: return@mapNotNull null
            val i = listed.indexOf(atm)
            Win(e, listed.subList(max(0, i - strikeCount), min(listed.size, i + strikeCount + 1)), atm)
        }
        if (wins.isEmpty()) return null
        var common = wins.map { it.strikes.toSet() }.reduce { a, b -> a intersect b }.sorted()
        if (common.size < 3) common = wins[0].strikes.sorted()
        val atm = wins[0].atm
        val surface = ArrayList<List<Double?>>()
        val info = ArrayList<SurfaceExpiryInfo>()
        for (w in wins) {
            val bySt = w.e.chain.associateBy { it.strike }
            val expiryAt = OptionMath.expiryInstant(w.e.expiry, cutoff)
            val t = OptionMath.timeToExpiry(now, expiryAt).first
            surface += common.map { k ->
                val type = if (k >= atm) OptionType.CE else OptionType.PE
                val leg = bySt[k]?.let { if (type.isCall) it.ce else it.pe }
                val ltp = leg?.ltp ?: 0.0
                if (ltp <= 0) null else displayedIv(type, underlyingLtp, k, t, ltp, ratePct)
            }
            // IraAlgo compares the naive IST expiry with the server's naive wall clock.
            val secs = Duration.between(now.withZoneSameInstant(OptionMath.IST).toLocalDateTime(),
                expiryAt.toLocalDateTime()).let { it.seconds + it.nano / 1e9 }
            info += SurfaceExpiryInfo(w.e.code, pyRound(max(0.0, secs / 86_400), 1))
        }
        return VolSurface(underlyingLtp, atm, common, info, surface)
    }

    // ================================================================ gamma

    /**
     * `get_gex_data`: per strike, GEX = gamma x OI x lot size for each side
     * (gamma from `calculate_greeks` against spot, at IraAlgo's 6-dp display
     * rounding), net = call - put, and totals summed over the rounded rows.
     * A leg is skipped (gamma 0) without a positive LTP and OI.
     */
    fun gex(rows: List<ChainRow>, spotPrice: Double, atmStrike: Double?, tYears: Double, ratePct: Double = 0.0, futuresPrice: Double? = null): Gex? {
        if (spotPrice <= 0) return null
        var lotSize: Int? = null
        val chain = rows.map { r ->
            fun side(leg: OptLeg?, type: OptionType): Triple<Long, Double, Double> {
                if (leg == null || leg.symbol.isEmpty()) return Triple(0L, 0.0, 0.0)
                val lot = leg.lotSize?.takeIf { it != 0 } ?: 1
                if (lotSize == null) lotSize = lot
                if (!(leg.ltp > 0 && leg.oi > 0)) return Triple(leg.oi, 0.0, 0.0)
                val gamma = OptionMath.legGreeksRounded(type, spotPrice, r.strike, tYears, leg.ltp, ratePct)?.greeks?.gamma ?: 0.0
                return Triple(leg.oi, gamma, gamma * leg.oi * lot)
            }
            val (ceOi, ceG, ceGex) = side(r.ce, OptionType.CE)
            val (peOi, peG, peGex) = side(r.pe, OptionType.PE)
            GexStrike(r.strike, ceOi, peOi, pyRound(ceG, 6), pyRound(peG, 6), pyRound(ceGex, 2), pyRound(peGex, 2), pyRound(ceGex - peGex, 2))
        }
        val tCe = chain.sumOf { it.ceOi }
        val tPe = chain.sumOf { it.peOi }
        return Gex(spotPrice, futuresPrice, atmStrike, lotSize ?: 1, ratio(tPe, tCe), tCe, tPe,
            pyRound(chain.fold(0.0) { a, s -> a + s.ceGex }, 2), pyRound(chain.fold(0.0) { a, s -> a + s.peGex }, 2),
            pyRound(chain.fold(0.0) { a, s -> a + s.netGex }, 2), chain)
    }

    private const val DAYS_PER_YEAR = 365.0
    private const val INTRADAY_T_YEARS = 1.0 / DAYS_PER_YEAR
    private const val FALLBACK_IV = 0.15

    private fun safeIv(price: Double, f: Double, k: Double, r: Double, t: Double, type: OptionType): Double? {
        if (!(price > 0) || f <= 0 || k <= 0 || t <= 0) return null
        val iv = OptionMath.impliedVol(price, type, f, k, t, r) ?: return null
        return if (!iv.isFinite() || iv <= 0 || iv > 5) null else iv
    }

    private fun safeGamma(type: OptionType, f: Double, k: Double, t: Double, r: Double, sigma: Double): Double {
        if (!(sigma > 0) || f <= 0 || k <= 0 || t <= 0) return 0.0
        val g = OptionMath.greeks(type, f, k, t, r, sigma).gamma
        return if (!g.isFinite() || g < 0) 0.0 else g
    }

    /**
     * `calculate_gamma_density`: gamma x OI per strike (calls plus puts) at two
     * horizons - one calendar day ("intraday", sharpening the ATM wall) and the
     * real time to expiry - with IV inverted per side against the synthetic
     * [forward] (spot when null), and 1- and 2-sigma expected-move bands around
     * SPOT from the ATM IV. The ATM IV is the mean of the ATM strike's side IVs,
     * else the upper median of all strike IVs, else 15%.
     *
     * @param tYears, dteDays from [OptionMath.timeToExpiry] (0 when expired)
     */
    fun gammaDensity(rows: List<ChainRow>, spotPrice: Double, atmStrike: Double?, forward: Double?, tYears: Double, dteDays: Double, ratePct: Double = 0.0): GammaDensity? {
        if (!(spotPrice > 0) || rows.isEmpty()) return null
        val tIntra = if (tYears > 0) min(tYears, INTRADAY_T_YEARS) else INTRADAY_T_YEARS
        val r = ratePct / 100.0
        val f = forward?.takeIf { it != 0.0 } ?: spotPrice
        data class S(val k: Double, val ceOi: Long, val peOi: Long, val ceIv: Double?, val peIv: Double?, val iv: Double?)
        val valid = ArrayList<Double>()
        var atmIv: Double? = null
        val strikes = rows.filter { it.strike > 0 }.map { row ->
            val k = row.strike
            val ceIv = safeIv(row.ce?.ltp ?: 0.0, f, k, r, tYears, OptionType.CE)
            val peIv = safeIv(row.pe?.ltp ?: 0.0, f, k, r, tYears, OptionType.PE)
            val sides = listOfNotNull(ceIv, peIv)
            val iv = if (sides.isEmpty()) null else sides.sum() / sides.size
            if (iv != null) valid += iv
            if (atmStrike != null && k == atmStrike && iv != null) atmIv = iv
            S(k, row.ce?.oi ?: 0L, row.pe?.oi ?: 0L, ceIv, peIv, iv)
        }
        val atm = atmIv ?: if (valid.isNotEmpty()) valid.sorted()[valid.size / 2] else FALLBACK_IV
        var maxIntra = 0.0
        var maxExp = 0.0
        var peakIntra: Double? = null
        var peakExp: Double? = null
        val chain = strikes.map { s ->
            val cs = s.ceIv ?: atm
            val ps = s.peIv ?: atm
            val dExp = safeGamma(OptionType.CE, f, s.k, tYears, r, cs) * s.ceOi + safeGamma(OptionType.PE, f, s.k, tYears, r, ps) * s.peOi
            val dIntra = safeGamma(OptionType.CE, f, s.k, tIntra, r, cs) * s.ceOi + safeGamma(OptionType.PE, f, s.k, tIntra, r, ps) * s.peOi
            if (dExp > maxExp) { maxExp = dExp; peakExp = s.k }
            if (dIntra > maxIntra) { maxIntra = dIntra; peakIntra = s.k }
            GammaDensityStrike(s.k, s.ceOi, s.peOi, s.iv?.let { pyRound(it * 100, 2) }, dIntra, dExp)
        }
        fun band(sigma: Double) = SigmaBand(pyRound(sigma, 2), pyRound(spotPrice - sigma, 2), pyRound(spotPrice + sigma, 2),
            pyRound(spotPrice - 2 * sigma, 2), pyRound(spotPrice + 2 * sigma, 2))
        return GammaDensity(pyRound(spotPrice, 2), pyRound(f, 2), atmStrike, pyRound(atm * 100, 2), pyRound(dteDays, 2), pyRound(ratePct, 2),
            peakIntra, peakExp, band(spotPrice * atm * sqrt(INTRADAY_T_YEARS)), band(spotPrice * atm * sqrt(max(tYears, 1e-9))), chain)
    }

    // ================================================================ synthetic future

    /**
     * `calculate_synthetic_future`: ATM from the underlying LTP over the listed
     * strikes, then K + C - P from its LTPs (2 dp), and the basis over the LTP
     * (the cost of carry). Null when the ATM strike lacks either contract.
     */
    fun syntheticFuture(rows: List<ChainRow>, underlyingLtp: Double): SyntheticFuture? {
        val atm = atmStrike(underlyingLtp, rows.map { it.strike }) ?: return null
        val row = rows.first { it.strike == atm }
        val c = row.ce?.ltp ?: return null
        val p = row.pe?.ltp ?: return null
        val price = atm + c - p
        return SyntheticFuture(underlyingLtp, atm, pyRound(price, 2), price - underlyingLtp)
    }

    // ================================================================ calendar arbitrage

    val ARBITRAGE_EXCHANGES = listOf("NFO", "MCX", "BFO", "CDS")
    private const val MAX_LEGS = 3
    private const val FRESH_MS = 6000L

    private val EXPIRY_FORMATS: List<DateTimeFormatter> = listOf("dd-MMM-yy", "dd-MMM-yyyy").map {
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(it).toFormatter(Locale.ENGLISH)
    }

    /** `_parse_expiry`: DD-MMM-YY or DD-MMM-YYYY; anything else sorts last. */
    fun parseFutExpiry(expiry: String?): LocalDate {
        val s = expiry?.trim()?.uppercase() ?: return LocalDate.MAX
        if (s.isEmpty()) return LocalDate.MAX
        for (f in EXPIRY_FORMATS) runCatching { return LocalDate.parse(s, f) }
        return LocalDate.MAX
    }

    /**
     * `get_arbitrage_universe`: per underlying on each supported exchange, the
     * three nearest futures, paired near-next and near-third. [contracts] is the
     * master contract list; non-FUT symbols and rows without a name or expiry
     * are ignored, and a symbol listed twice keeps its last row.
     */
    fun arbitrage(contracts: List<FutContract>, exchanges: List<String> = listOf("NFO", "MCX")): ArbitrageUniverse? {
        val scan = exchanges.map { it.trim().uppercase() }.filter { it.isNotEmpty() && it in ARBITRAGE_EXCHANGES }
        if (scan.isEmpty()) return null
        val pairs = ArrayList<CalendarPair>()
        val symbols = LinkedHashMap<String, Pair<String, String>>()
        var underlyings = 0
        for (ex in scan) {
            val grouped = LinkedHashMap<String, LinkedHashMap<String, FutContract>>()
            for (c in contracts.filter { it.exchange.uppercase() == ex }) {
                val sym = c.symbol.uppercase()
                if (!sym.endsWith("FUT") || c.underlying.isEmpty() || c.expiry.isEmpty()) continue
                grouped.getOrPut(c.underlying) { LinkedHashMap() }[sym] = c
            }
            for ((u, bySymbol) in grouped) {
                val legs = bySymbol.values.sortedBy { parseFutExpiry(it.expiry) }.take(MAX_LEGS)
                if (legs.size < 2) continue
                underlyings++
                for ((type, far) in listOf("near-next" to legs[1], "near-third" to legs.getOrNull(2))) {
                    if (far == null) continue
                    pairs += CalendarPair("$ex:$u:$type", u, ex, type, legs[0], far)
                    for (leg in listOf(legs[0], far)) symbols["${leg.exchange}:${leg.symbol}"] = leg.symbol to leg.exchange
                }
            }
        }
        return ArbitrageUniverse(pairs, symbols.values.toList(), underlyings)
    }

    private fun positive(x: Double?) = x?.takeIf { it > 0 }

    /** The page's `midPrice`: the book mid when two-sided, else the LTP. */
    fun midPrice(q: Quote?): Double? {
        if (q == null) return null
        val b = positive(q.bid)
        val a = positive(q.ask)
        return if (b != null && a != null) (b + a) / 2 else positive(q.ltp)
    }

    /**
     * The scanner's `computeRow`: the executable credit of each way to trade
     * the calendar - SHORT (sell far at bid, buy near at ask) or LONG (sell near
     * at bid, buy far at ask) - the better of the two as a percent of the near
     * mid, and whether both books are two-sided and fresh (< 6 s old).
     */
    fun spreadRow(pair: CalendarPair, near: Quote?, far: Quote?, nowMs: Long): SpreadRow {
        val nearMid = midPrice(near)
        val farMid = midPrice(far)
        val nb = positive(near?.bid); val na = positive(near?.ask)
        val fb = positive(far?.bid); val fa = positive(far?.ask)
        var best: Double? = null
        var dir: SpreadDirection? = null
        if (fb != null && na != null) { best = fb - na; dir = SpreadDirection.SHORT_SPREAD }
        if (nb != null && fa != null) {
            val long = nb - fa
            if (best == null || long > best) { best = long; dir = SpreadDirection.LONG_SPREAD }
        }
        val pct = if (best != null && nearMid != null && nearMid > 0) best / nearMid * 100 else null
        val raw = if (farMid != null && nearMid != null) farMid - nearMid else null
        val newest = max(near?.ts ?: 0L, far?.ts ?: 0L)
        return SpreadRow(pair, nearMid, farMid, raw, best, dir, pct, newest > 0 && nowMs - newest < FRESH_MS,
            nb != null && na != null && fb != null && fa != null)
    }

    /** The scanner's ranking: widest spread first, rows without one last (stable). */
    fun rankSpreads(rows: List<SpreadRow>): List<SpreadRow> =
        rows.sortedWith { a, b ->
            val x = a.spreadPct
            val y = b.spreadPct
            when {
                x == null && y == null -> 0
                x == null -> 1
                y == null -> -1
                else -> y.compareTo(x)
            }
        }

    // ================================================================ multi-strike OI

    /**
     * `get_multi_strike_oi_data` after fetching: each leg's OI series (2 dp),
     * flagged `hasOi` when any point is positive - a broker without OI history
     * returns zeros - then every series capped to the last [days] trading
     * dates. A null [underlying] means its history was unavailable.
     */
    fun multiStrikeOi(underlying: List<TimeValue>?, legs: List<LegOiSeries>, days: Int): MultiStrikeOi {
        val u = underlying?.map { TimeValue(it.time, pyRound(it.value, 2)) }.orEmpty()
        return MultiStrikeOi(underlying != null && underlying.isNotEmpty(), capLastNTradingDates(u, days) { it.time }, legs.map { l ->
            val s = l.series.map { TimeValue(it.time, pyRound(it.value, 2)) }
            LegOi(l.symbol, l.side, l.strike, l.optionType, l.expiry, s.any { it.value > 0 }, capLastNTradingDates(s, days) { it.time })
        })
    }
}
