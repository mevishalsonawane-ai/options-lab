package com.optionslab.engine.options

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.exp

enum class Direction { BULLISH, BEARISH, NON_DIRECTIONAL }

/**
 * A template leg: strike as an offset in LISTED strikes from ATM (0 = ATM,
 * -1 = one listed strike below), expiry as an offset in listed expiries from
 * the selected one (1 = the next, for calendars and diagonals).
 */
data class TemplateLeg(val side: Side, val optionType: OptionType, val strikeOffset: Int, val lots: Int, val expiryOffset: Int = 0)

data class StrategyTemplate(
    val id: String,
    val name: String,
    val direction: Direction,
    val description: String,
    val legs: List<TemplateLeg>,
    /** Normalised spot and strike step used by the mini preview icon. */
    val referenceSpot: Double = 100.0,
    val strikeStep: Double = 4.0,
    /** A multi-expiry icon is a hand-drawn illustration, not a terminal payoff. */
    val illustrativePreview: Boolean = false,
    /** SVG path in a 100 x 40 viewBox; zero line at y = 20, profit up. */
    val payoffPath: String = "",
)

/** A template leg placed on a real chain: what the template dialog shows. */
data class ResolvedTemplateLeg(
    val leg: TemplateLeg,
    val strike: Double,
    val expiry: String,
    /** The listed contract's LTP from the loaded chain, 0 when not available. */
    val price: Double,
    val symbol: String?,
    /** template lots x the dialog's lot multiplier */
    val lots: Int,
)

data class TemplateResolution(val legs: List<ResolvedTemplateLeg>, val errors: List<String>, val strikeErrorIndexes: Set<Int>, val expiryInvalid: Boolean) {
    val ok: Boolean get() = errors.isEmpty()
}

/**
 * The Strategy Builder's named templates and how they land on a chain, ported
 * from `strategyTemplates.ts`, `templateResolution.ts` and the template dialog.
 *
 * Strikes resolve by walking the LISTED ladder from ATM, never by adding
 * `offset x step`: on an irregular ladder (wider spacing away from the money)
 * arithmetic would ask for strikes that do not exist. A walk off the loaded
 * ladder is an error, not a clamp - clamping collapses two legs onto one
 * strike and silently turns a condor into something else.
 */
object StrategyTemplates {
    private fun b(t: OptionType, off: Int, lots: Int = 1, exp: Int = 0) = TemplateLeg(Side.BUY, t, off, lots, exp)
    private fun s(t: OptionType, off: Int, lots: Int = 1, exp: Int = 0) = TemplateLeg(Side.SELL, t, off, lots, exp)
    private val CE = OptionType.CE
    private val PE = OptionType.PE

    private fun t(id: String, name: String, d: Direction, desc: String, legs: List<TemplateLeg>, illustrative: String? = null): StrategyTemplate {
        val base = StrategyTemplate(id, name, d, desc, legs, illustrativePreview = illustrative != null)
        return base.copy(payoffPath = illustrative ?: previewPath(base))
    }

    /** All 38 templates, in the grid's order (9 bullish, 9 bearish, 20 non-directional). */
    val ALL: List<StrategyTemplate> by lazy {
        val B = Direction.BULLISH
        val R = Direction.BEARISH
        val N = Direction.NON_DIRECTIONAL
        listOf(
            t("long_call", "Long Call", B, "Unlimited upside, limited downside. Best for strong bullish view.", listOf(b(CE, 0))),
            t("short_put", "Short Put", B, "Collect premium; profit if price stays above strike.", listOf(s(PE, 0))),
            t("bull_call_spread", "Bull Call Spread", B, "Buy ATM call, sell OTM call. Capped profit & loss.", listOf(b(CE, 0), s(CE, 2))),
            t("bull_put_spread", "Bull Put Spread", B, "Sell ATM put, buy OTM put. Typically opened for a net credit.", listOf(s(PE, 0), b(PE, -2))),
            t("call_ratio_back_spread", "Call Ratio Back Spread", B,
                "Sell 1 ATM call, buy 2 OTM calls. Entry may be a credit or debit; upside is unlimited if the market rallies hard.", listOf(s(CE, 0), b(CE, 2, 2))),
            t("long_synthetic", "Long Synthetic", B,
                "Buy ATM call + sell ATM put (same strike). Synthetic long futures — unlimited upside, with downside bounded when spot reaches zero.", listOf(b(CE, 0), s(PE, 0))),
            t("range_forward", "Range Forward", B,
                "Sell OTM put + buy OTM call. Bullish collar-style structure — limited downside via short put, unlimited upside via long call.", listOf(s(PE, -2), b(CE, 2))),
            t("bullish_butterfly", "Bullish Butterfly", B,
                "Call butterfly centred above spot — buy 1 ATM CE, sell 2 OTM CE, buy 1 further OTM CE. Max profit if spot rallies to the body strike.",
                listOf(b(CE, 0), s(CE, 2, 2), b(CE, 4))),
            t("bullish_condor", "Bullish Condor", B,
                "Call condor above spot — profit zone sits over a range of higher strikes. Defined risk on both ends.", listOf(b(CE, 0), s(CE, 1), s(CE, 3), b(CE, 4))),

            t("short_call", "Short Call", R, "Collect premium; profit if price stays below strike.", listOf(s(CE, 0))),
            t("long_put", "Long Put", R, "Profit grows as spot falls but is capped at zero; loss is limited.", listOf(b(PE, 0))),
            t("bear_call_spread", "Bear Call Spread", R, "Sell ATM call, buy OTM call. Typically opened for a net credit.", listOf(s(CE, 0), b(CE, 2))),
            t("bear_put_spread", "Bear Put Spread", R, "Buy ATM put, sell OTM put. Capped profit & loss.", listOf(b(PE, 0), s(PE, -2))),
            t("put_ratio_back_spread", "Put Ratio Back Spread", R,
                "Sell 1 ATM put, buy 2 OTM puts. Entry may be a credit or debit; profit grows as spot falls but is capped at zero.", listOf(s(PE, 0), b(PE, -2, 2))),
            t("short_synthetic", "Short Synthetic", R,
                "Sell ATM call + buy ATM put (same strike). Synthetic short futures — downside profit is capped at zero and upside loss is unlimited.", listOf(s(CE, 0), b(PE, 0))),
            t("risk_reversal", "Risk Reversal", R, "Buy OTM put + sell OTM call. Bearish collar — profits on downside, unlimited upside loss.", listOf(b(PE, -2), s(CE, 2))),
            t("bearish_butterfly", "Bearish Butterfly", R,
                "Put butterfly centred below spot — buy 1 ATM PE, sell 2 OTM PE, buy 1 further OTM PE. Max profit if spot falls to the body strike.",
                listOf(b(PE, 0), s(PE, -2, 2), b(PE, -4))),
            t("bearish_condor", "Bearish Condor", R,
                "Put condor below spot — profit zone sits over a range of lower strikes. Defined risk on both ends.", listOf(b(PE, 0), s(PE, -1), s(PE, -3), b(PE, -4))),

            t("long_straddle", "Long Straddle", N, "Buy ATM call + put. Profits from a large move either way.", listOf(b(CE, 0), b(PE, 0))),
            t("short_straddle", "Short Straddle", N, "Sell ATM call + put. Profits if price stays pinned near strike.", listOf(s(CE, 0), s(PE, 0))),
            t("long_strangle", "Long Strangle", N, "Buy OTM call + OTM put. Cheaper than straddle; needs bigger move.", listOf(b(PE, -2), b(CE, 2))),
            t("short_strangle", "Short Strangle", N, "Sell OTM call + OTM put. Wider profit zone than short straddle.", listOf(s(PE, -2), s(CE, 2))),
            t("jade_lizard", "Jade Lizard", N,
                "Sell OTM put + short OTM call spread. No risk on upside if credit exceeds call-spread width.", listOf(s(PE, -2), s(CE, 2), b(CE, 4))),
            t("reverse_jade_lizard", "Reverse Jade Lizard", N,
                "Sell OTM call + short OTM put spread. No risk on downside if credit exceeds put-spread width.", listOf(s(CE, 2), s(PE, -2), b(PE, -4))),
            t("call_ratio_spread", "Call Ratio Spread", N,
                "Buy 1 ATM call, sell 2 OTM calls. Peak profit at short strike; unlimited upside loss above.", listOf(b(CE, 0), s(CE, 2, 2))),
            t("put_ratio_spread", "Put Ratio Spread", N,
                "Buy 1 ATM put, sell 2 OTM puts. Peak profit at the short strike; loss below it is substantial but bounded at spot zero.", listOf(b(PE, 0), s(PE, -2, 2))),
            t("batman_strategy", "Batman Strategy", N,
                "Call ratio spread (1×2) above + Put ratio spread (1×2) below. Two-eared \"Batman\" profile — peaks at the short strikes, with bounded left-tail loss at spot zero and unlimited right-tail loss.",
                listOf(b(CE, 10), s(CE, 15, 2), b(PE, -10), s(PE, -15, 2))),
            t("long_iron_fly", "Long Iron Fly", N, "Short ATM straddle + long OTM wings. Max profit pinned at ATM.", listOf(b(PE, -2), s(PE, 0), s(CE, 0), b(CE, 2))),
            t("short_iron_fly", "Short Iron Fly", N,
                "Long ATM straddle + short OTM wings. Max profit on a big move either way; max loss pinned at ATM.", listOf(s(PE, -2), b(PE, 0), b(CE, 0), s(CE, 2))),
            t("double_fly", "Double Fly", N,
                "Two iron butterflies — one centred below spot, one above. Eight legs total: short straddle at each body strike, long CE wing above and long PE wing below. Two profit peaks at the body strikes, defined risk on both ends.",
                listOf(s(CE, -8), b(CE, -4), s(CE, 8), b(CE, 12), b(PE, -12), s(PE, -8), b(PE, 4), s(PE, 8))),
            t("long_iron_condor", "Long Iron Condor", N, "Bull put spread + bear call spread. Defined-risk range play.", listOf(b(PE, -4), s(PE, -2), s(CE, 2), b(CE, 4))),
            t("short_iron_condor", "Short Iron Condor", N,
                "Reverse of long iron condor — long wings pay off on a big move either way, short body caps upside if spot pins in the middle.",
                listOf(s(PE, -4), b(PE, -2), b(CE, 2), s(CE, 4))),
            t("double_condor", "Double Condor", N,
                "Call condor + put condor at different strikes. Two wide profit plateaus on either side of spot.",
                listOf(b(PE, -5), s(PE, -4), s(PE, -2), b(PE, -1), b(CE, 1), s(CE, 2), s(CE, 4), b(CE, 5))),
            t("call_calendar", "Call Calendar", N,
                "Sell near-expiry ATM CE, buy far-expiry ATM CE (same strike). The preview is illustrative: outcome depends on premiums, volatility, and the far leg’s residual time value.",
                listOf(s(CE, 0, exp = 0), b(CE, 0, exp = 1)), "M0,32 L25,28 L42,6 L65,18 L100,28"),
            t("put_calendar", "Put Calendar", N,
                "Sell near-expiry ATM PE, buy far-expiry ATM PE (same strike). The preview is illustrative because premiums, volatility, and residual time value determine the first-expiry result.",
                listOf(s(PE, 0, exp = 0), b(PE, 0, exp = 1)), "M0,28 L35,18 L58,6 L75,28 L100,32"),
            t("diagonal_calendar", "Diagonal Calendar", N,
                "Sell near ATM CE and buy far OTM CE. The preview is illustrative because the far call retains residual time value at the first expiry.",
                listOf(s(CE, 0, exp = 0), b(CE, 2, exp = 1)), "M0,32 L20,28 L38,14 L50,10 L62,14 L78,22 L100,28"),
            t("call_butterfly", "Call Butterfly", N, "Long call butterfly centred at ATM. Max profit if spot pins at the body strike.", listOf(b(CE, -2), s(CE, 0, 2), b(CE, 2))),
            t("put_butterfly", "Put Butterfly", N, "Long put butterfly centred at ATM. Put-side mirror of the call butterfly.", listOf(b(PE, 2), s(PE, 0, 2), b(PE, -2))),
        )
    }

    fun byId(id: String): StrategyTemplate? = ALL.firstOrNull { it.id == id }

    /** `templatesByDirection`: null means ALL. */
    fun byDirection(direction: Direction?): List<StrategyTemplate> = if (direction == null) ALL else ALL.filter { it.direction == direction }

    // ---------------------------------------------------------------- strike and expiry resolution

    /** `resolveStrikeOffset`: walk [offset] LISTED strikes from ATM; null if the ATM is unlisted or the walk leaves the ladder. */
    fun resolveStrikeOffset(strikes: List<Double>, atmStrike: Double, offset: Int): Double? {
        val listed = strikes.filter { it.isFinite() }.toSortedSet().toList()
        val i = listed.indexOf(atmStrike)
        if (i < 0) return null
        return listed.getOrNull(i + offset)
    }

    /** `normalizeExpiryCode`: "04-AUG-2026" and "04-AUG-26" both become "04AUG26". */
    fun normalizeExpiryCode(expiry: String): String {
        if (expiry.isEmpty()) return ""
        val parts = expiry.split("-")
        if (parts.size == 3) return parts[0] + parts[1].uppercase() + parts[2].takeLast(2)
        return expiry.replace("-", "").uppercase()
    }

    /** `canReuseChainContract`: the loaded chain's symbols serve a leg only on the same expiry. */
    fun canReuseChainContract(editedExpiry: String, loadedExpiry: String): Boolean = normalizeExpiryCode(editedExpiry) == normalizeExpiryCode(loadedExpiry)

    private val MONTHS = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")

    private fun expiryDay(expiry: String): LocalDate? {
        val m = Regex("^(\\d{1,2})([A-Z]{3})(\\d{2})$").find(normalizeExpiryCode(expiry)) ?: return null
        val month = MONTHS.indexOf(m.groupValues[2])
        if (month < 0) return null
        return LocalDate.of(2000 + m.groupValues[3].toInt(), month + 1, 1).plusDays(m.groupValues[1].toLong() - 1)
    }

    /** `resolveExpiryOffset`: the listed expiry [offset] places after [baseExpiry], chronologically; null if there is none. */
    fun resolveExpiryOffset(expiries: List<String>, baseExpiry: String, offset: Int): String? {
        val ordered = expiries.distinct().mapNotNull { e -> expiryDay(e)?.let { e to it } }.sortedBy { it.second }
        val i = ordered.indexOfFirst { it.first == baseExpiry }
        if (i < 0) return null
        return ordered.getOrNull(i + offset)?.first
    }

    /**
     * `validateTemplateStrikeTopology`: every leg resolved; legs sharing an
     * offset share a strike; distinct offsets map to distinct strikes in the
     * same order. Returns IraAlgo's error messages (empty = valid).
     */
    fun validateStrikeTopology(legs: List<Pair<Int, Double?>>): List<String> {
        if (legs.any { it.second == null }) return listOf("One or more template strikes are outside the loaded option chain.")
        val byOffset = LinkedHashMap<Int, Double>()
        for ((off, strike) in legs) {
            val existing = byOffset[off]
            if (existing != null && existing != strike) return listOf("Legs with the same offset must resolve to the same strike.")
            byOffset[off] = strike!!
        }
        val ordered = byOffset.entries.sortedBy { it.key }
        for (i in 1 until ordered.size) {
            if (ordered[i - 1].value >= ordered[i].value) return listOf("Distinct template offsets must resolve to distinct, ordered strikes.")
        }
        return emptyList()
    }

    /**
     * The template dialog's resolution: each leg's strike walked from ATM on
     * the loaded chain (or an [overrides] strike by leg index), its expiry
     * walked from [expiry] through [expiries], and - on the loaded expiry only -
     * the listed contract and its LTP. Collects the dialog's error messages.
     */
    fun resolve(template: StrategyTemplate, chain: List<ChainRow>, atmStrike: Double, expiry: String, expiries: List<String>,
                lotMultiplier: Int = 1, overrides: Map<Int, Double> = emptyMap()): TemplateResolution {
        val strikes = chain.map { it.strike }.toSortedSet().toList()
        val topology = ArrayList<Pair<Int, Double?>>()
        val errors = ArrayList<String>()
        val bad = LinkedHashSet<Int>()
        var expiryInvalid = false
        val legs = template.legs.mapIndexed { idx, leg ->
            val resolvedStrike = overrides[idx] ?: resolveStrikeOffset(strikes, atmStrike, leg.strikeOffset)
            topology += leg.strikeOffset to resolvedStrike
            val listedExpiry = resolveExpiryOffset(expiries, expiry, leg.expiryOffset)
            if (listedExpiry == null) {
                expiryInvalid = true
                errors += if (leg.expiryOffset > 0) "A later expiry is required for ${template.name}."
                else "The selected expiry cannot resolve every leg in ${template.name}."
            }
            val resolvedExpiry = listedExpiry ?: expiry
            val strike = resolvedStrike ?: atmStrike
            if (resolvedStrike == null) bad += idx
            val canUseChain = resolvedExpiry == expiry
            val contract = if (canUseChain) chain.firstOrNull { it.strike == strike }?.let { if (leg.optionType.isCall) it.ce else it.pe } else null
            if (canUseChain && resolvedStrike != null && contract == null) {
                bad += idx
                errors += "The required ${leg.optionType} contract at ${Payoff.jsNumber(strike)} is not available in the loaded option chain."
            }
            ResolvedTemplateLeg(leg, strike, resolvedExpiry, contract?.ltp ?: 0.0, contract?.symbol, leg.lots * lotMultiplier)
        }
        val topologyErrors = validateStrikeTopology(topology)
        if (topologyErrors.isNotEmpty()) bad += template.legs.indices
        errors += topologyErrors
        return TemplateResolution(legs, errors.distinct(), bad, expiryInvalid)
    }

    // ---------------------------------------------------------------- the mini payoff icon

    private const val PREVIEW_ATM_TIME_VALUE_STEPS = 1.2
    private const val PREVIEW_TIME_VALUE_WIDTH_STEPS = 3.0
    private const val PREVIEW_MIN_WINDOW_STEPS = 3.0
    private const val PREVIEW_MARGIN_RATIO = 0.22
    private const val PREVIEW_AMPLITUDE = 16.0
    private const val PREVIEW_ZERO_Y = 20.0
    private const val PREVIEW_MIN_SIDE_SPAN = 0.35

    private fun previewStrike(t: StrategyTemplate, leg: TemplateLeg) = t.referenceSpot + leg.strikeOffset * t.strikeStep

    /**
     * A synthetic premium, convex in the strike: time value decays
     * exponentially away from spot, and its kink at the money offsets the
     * intrinsic kink. Convexity is what makes butterflies debits and iron
     * condors credits in the icon.
     */
    private fun previewPremium(t: StrategyTemplate, leg: TemplateLeg): Double {
        val k = previewStrike(t, leg)
        val intrinsic = if (leg.optionType.isCall) maxOf(0.0, t.referenceSpot - k) else maxOf(0.0, k - t.referenceSpot)
        val width = PREVIEW_TIME_VALUE_WIDTH_STEPS * t.strikeStep
        return intrinsic + PREVIEW_ATM_TIME_VALUE_STEPS * t.strikeStep * exp(-abs(k - t.referenceSpot) / width)
    }

    /** `previewValue`: terminal P&L of the normalised template net of the synthetic premium. */
    fun previewValue(t: StrategyTemplate, spot: Double): Double {
        val s = maxOf(0.0, spot)
        return t.legs.fold(0.0) { total, leg ->
            val k = previewStrike(t, leg)
            val intrinsic = if (leg.optionType.isCall) maxOf(0.0, s - k) else maxOf(0.0, k - s)
            total + (if (leg.side == Side.BUY) 1.0 else -1.0) * leg.lots * (intrinsic - previewPremium(t, leg))
        }
    }

    private fun previewStrikes(t: StrategyTemplate) = t.legs.map { previewStrike(t, it) }.distinct().sorted()

    private fun previewBreakevens(t: StrategyTemplate, strikes: List<Double>): List<Double> {
        val values = strikes.map { previewValue(t, it) }
        val out = ArrayList<Double>()
        for (i in 0 until strikes.size - 1) {
            val lo = values[i]; val hi = values[i + 1]
            if ((lo < 0 && hi > 0) || (lo > 0 && hi < 0)) out += strikes[i] + lo / (lo - hi) * (strikes[i + 1] - strikes[i])
        }
        var left = 0.0
        var right = 0.0
        for (leg in t.legs) {
            val d = if (leg.side == Side.BUY) 1.0 else -1.0
            if (leg.optionType.isCall) right += d * leg.lots else left -= d * leg.lots
        }
        val first = strikes.first()
        val last = strikes.last()
        if (left != 0.0) { val c = first - values.first() / left; if (c >= 0 && c < first) out += c }
        if (right != 0.0) { val c = last - values.last() / right; if (c > last) out += c }
        return out
    }

    private fun previewWindow(t: StrategyTemplate, strikes: List<Double>): Pair<Double, Double> {
        val values = strikes.map { previewValue(t, it) }
        var low = strikes.first()
        var high = strikes.last()
        if (!(values.any { it > 0 } && values.any { it < 0 })) {
            for (c in previewBreakevens(t, strikes)) { low = minOf(low, c); high = maxOf(high, c) }
        }
        val centre = (low + high) / 2
        val span = maxOf(high - low, PREVIEW_MIN_WINDOW_STEPS * t.strikeStep)
        val half = span / 2 + span * PREVIEW_MARGIN_RATIO
        return maxOf(0.0, centre - half) to centre + half
    }

    /** JavaScript's `Number(x.toFixed(2))` printed back: half-up on the binary value, no trailing zeros. */
    private fun fixed2(x: Double): String {
        val bd = BigDecimal(abs(x)).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros()
        val s = if (bd.signum() == 0) "0" else bd.toPlainString()
        return if (x < 0 && s != "0") "-$s" else s
    }

    /** `templatePreviewPath`: the icon's SVG path, exact at the kinks (window edges plus every strike). */
    fun previewPath(t: StrategyTemplate): String {
        val strikes = previewStrikes(t)
        val (low, high) = previewWindow(t, strikes)
        val width = high - low
        val spots = (listOf(low) + strikes + high).distinct().sorted()
        val values = spots.map { previewValue(t, it) }
        val maxProfit = maxOf(0.0, values.max())
        val maxLoss = maxOf(0.0, values.maxOf { -it })
        val scale = maxOf(maxProfit, maxLoss)
        val profitScale = if (maxProfit > 0) minOf(scale, maxProfit / PREVIEW_MIN_SIDE_SPAN) else scale
        val lossScale = if (maxLoss > 0) minOf(scale, maxLoss / PREVIEW_MIN_SIDE_SPAN) else scale
        return spots.mapIndexed { i, spot ->
            val v = values[i]
            val x = if (width > 0) (spot - low) / width * 100 else 50.0
            val offset = if (scale == 0.0) 0.0 else if (v >= 0) -(v / profitScale) * PREVIEW_AMPLITUDE else (-v / lossScale) * PREVIEW_AMPLITUDE
            val y = PREVIEW_ZERO_Y + offset.coerceIn(-PREVIEW_AMPLITUDE, PREVIEW_AMPLITUDE)
            (if (i == 0) "M" else "L") + fixed2(x.coerceIn(0.0, 100.0)) + "," + fixed2(y)
        }.joinToString(" ")
    }

    /**
     * Legs for [Payoff] from a resolved template. [lotSize] is the contract
     * multiplier; [iv] percent per leg when known (else the payoff's fallback).
     */
    fun toStrategyLegs(r: TemplateResolution, underlying: String, lotSize: Int, iv: (ResolvedTemplateLeg) -> Double = { 0.0 }): List<StrategyLeg> =
        r.legs.mapIndexed { i, l ->
            StrategyLeg(side = l.leg.side, lots = l.lots, lotSize = lotSize, expiry = l.expiry, price = l.price, strike = l.strike,
                optionType = l.leg.optionType, iv = iv(l), id = "leg$i",
                symbol = l.symbol ?: Payoff.buildOptionSymbol(underlying, l.expiry, l.strike, l.leg.optionType))
        }
}
