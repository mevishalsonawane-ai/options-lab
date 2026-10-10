package com.optionslab.engine.sandbox

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Honest paper fills (08 Oct, research/X3_AUDIT.md): a real order pays the bid/ask spread and the paper account did not,
 * so it booked about Rs 96 a round trip more than real fills. With [SandboxConfig.paperSpread] on (the app's paper account,
 * always), every aggressive paper fill pays the half-spread:
 *
 *  - a BUY fills at price x (1 + hs), a SELL at price x (1 - hs), rounded to the tick AGAINST the order;
 *  - hs is the quote's own best bid/ask when it has both ((ask - bid) / 2 / mid), else the measured default for the
 *    underlying ([DEFAULTS], research/HUNT_H24.md), else [UNKNOWN] (stock options, anything not listed);
 *  - the desktop's own slippage (stops 10 bps, a MARKET fill with no book 5 bps) is not added on top: the fill moves by
 *    max(slippage, hs), the rule the research uses (X3 issue #2);
 *  - a MARKET order that crossed a real book already fills at the ask (buy) or bid (sell): nothing more is added;
 *  - a resting LIMIT fills only when the market trades through it, at its limit, with no spread.
 *
 * Also the rule for a price too old to fill on ([STALE_SECONDS], [isStale]) and the conservative exit price
 * when nothing fresher exists ([conservativeExit]). Pure.
 */
object PaperSpread {
    /** Measured half-spreads of near-ATM index options (fraction of the mid): HUNT_H24, Dhan chain 06 Oct 2026; SENSEX assumed. */
    val DEFAULTS: Map<String, Double> = linkedMapOf(
        "BANKNIFTY" to 0.0016, "NIFTY" to 0.0016, "MIDCPNIFTY" to 0.0021, "FINNIFTY" to 0.0042, "SENSEX" to 0.0020,
    )

    /** Options on stocks, or any underlying not in [DEFAULTS]. */
    const val UNKNOWN = 0.0030

    /** A quote's book wider than this (a junk or one-sided snapshot) is not trusted: the default is used instead. */
    const val MAX_BOOK = 0.05

    /** A paper fill never uses a price older than this when the stream is down. */
    const val STALE_SECONDS = 60L

    private val TEN_K = BigDecimal(10_000)
    private val SYMBOL = Regex("^([A-Z&-]+?)\\d{2}(JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)\\d{2}")

    /** The underlying in a paper symbol (NIFTY29SEP2624500PE -> NIFTY); null when it does not read as one. */
    fun underlying(symbol: String): String? = SYMBOL.find(symbol.uppercase())?.groupValues?.get(1)

    /** The measured default half-spread for [symbol]'s underlying, else [UNKNOWN]. */
    fun defaultHalfSpread(symbol: String): Double = underlying(symbol)?.let { DEFAULTS[it] } ?: UNKNOWN

    /** The half-spread a real bid and ask show; null when either side is missing, they are crossed or wider than [MAX_BOOK]. */
    fun fromBook(bid: Double, ask: Double): Double? {
        if (!(bid > 0 && ask > 0 && ask >= bid && bid.isFinite() && ask.isFinite())) return null
        val mid = (bid + ask) / 2
        val hs = (ask - bid) / 2 / mid
        return hs.takeIf { it <= MAX_BOOK }
    }

    /** The price before the spread for a fill that crossed [quote]'s book: its mid, else its LTP, else [fallback]. */
    fun mid(quote: Quote?, fallback: Double): Double = when {
        quote == null -> fallback
        fromBook(quote.bid, quote.ask) != null -> (quote.bid + quote.ask) / 2
        quote.ltp > 0 -> quote.ltp
        else -> fallback
    }

    /** The half-spread a fill in [symbol] pays: from [quote]'s book when it has one, else the default. */
    fun halfSpread(symbol: String, quote: Quote?): Double =
        quote?.let { fromBook(it.bid, it.ask) } ?: defaultHalfSpread(symbol)

    /**
     * [halfSpread] for a fill on [exchange] at [at] (IST): an MCX fill with no usable book pays the MCX default for its
     * contract and hour ([mcxHalfSpread]); every other exchange exactly as [halfSpread].
     */
    fun halfSpread(symbol: String, quote: Quote?, exchange: String, at: java.time.LocalTime): Double =
        if (exchange.uppercase() != "MCX") halfSpread(symbol, quote)
        else quote?.let { fromBook(it.bid, it.ask) } ?: mcxHalfSpread(symbol, at)

    // ---- MCX (research/MCX_GUIDE.md 1b, MCX_INTRADAY.md: 70-80% of option volume trades after 17:00) -------------------

    /** MCX's evening session (US hours), where the option books are deep: from here the narrower default applies. */
    val MCX_EVENING: java.time.LocalTime = java.time.LocalTime.of(17, 0)

    /**
     * Assumed half-spreads of near-ATM MCX options (fraction of the mid): (from 17:00, before 17:00). Crude ATM spreads were
     * measured live at 0.19-0.29% (NN_CRUDE, STRAD_CRUDE); GOLDM, SILVERM and NATURALGAS were never measured, so these
     * are on the safe (wide) side until the market recorder has logged them.
     */
    val MCX_OPTION_DEFAULTS: Map<String, Pair<Double, Double>> = linkedMapOf(
        "CRUDEOIL" to (0.0030 to 0.0060), "NATURALGAS" to (0.0030 to 0.0060),
        "GOLDM" to (0.0040 to 0.0080), "SILVERM" to (0.0040 to 0.0080),
    )

    /** Any other MCX option (the minis' small premiums, the thin books): (from 17:00, before). */
    val MCX_OPTION_UNKNOWN: Pair<Double, Double> = 0.0060 to 0.0120

    /** An MCX future (one tick is 0.01-0.05% of the price on the liquid ones). */
    const val MCX_FUTURE = 0.0003

    /** The MCX default half-spread for [symbol] (a paper symbol: NAME + DDMMMYY + strike + CE/PE, or + FUT) at [at]. */
    fun mcxHalfSpread(symbol: String, at: java.time.LocalTime): Double {
        val s = symbol.uppercase()
        if (s.endsWith("FUT")) return MCX_FUTURE
        val (evening, day) = underlying(s)?.let { MCX_OPTION_DEFAULTS[it] } ?: MCX_OPTION_UNKNOWN
        return if (at.isBefore(MCX_EVENING)) day else evening
    }

    /**
     * [price] moved against the order by max([floorBps], [hs]) and rounded to [tick] against the order (a BUY up, a SELL
     * down), never under one tick.
     */
    fun fill(price: BigDecimal, action: String, hs: Double, floorBps: BigDecimal, tick: BigDecimal): BigDecimal {
        if (price.signum() <= 0) return price
        val rate = BigDecimal(hs.coerceAtLeast(0.0).toString()).max(floorBps.divide(TEN_K))
        val buy = action.uppercase() == "BUY"
        val moved = if (buy) price + price * rate else price - price * rate
        return toTick(moved, buy, tick)
    }

    /** [price] on the [tick] grid, rounded against the order; at least one tick. */
    fun toTick(price: BigDecimal, buy: Boolean, tick: BigDecimal): BigDecimal {
        val t = if (tick.signum() > 0) tick else BigDecimal("0.05")
        val steps = price.divide(t, 0, if (buy) RoundingMode.CEILING else RoundingMode.FLOOR)
        val out = steps.multiply(t).max(t)
        return out.setScale(2, RoundingMode.HALF_EVEN)
    }

    /** The rupees a fill at [fill] paid over [reference] (the price before the spread), for [quantity] x [contractValue]; never negative. */
    fun charged(reference: BigDecimal, fill: BigDecimal, quantity: Int, contractValue: BigDecimal = BigDecimal.ONE): BigDecimal =
        (fill - reference).abs().multiply(BigDecimal(kotlin.math.abs(quantity))).multiply(contractValue).setScale(2, RoundingMode.HALF_EVEN)

    /** How old (seconds) the close of the minute that started at [barStartSec] is at [nowSec]; 0 while that minute runs. */
    fun ageSeconds(barStartSec: Long, nowSec: Long): Long = (nowSec - (barStartSec + 60)).coerceAtLeast(0)

    /** True when the last minute's close (the minute started at [barStartSec]) is older than [STALE_SECONDS] at [nowSec]. */
    fun isStale(barStartSec: Long, nowSec: Long): Boolean = ageSeconds(barStartSec, nowSec) > STALE_SECONDS

    /** Said wherever paper results are shown (Home's "Paper since" line, the day report): what the figures now include. */
    const val NOTE = "paper fills include the bid/ask spread"

    /** "Spread charged Rs 10.50" for a fill's details; null when the fill paid none. */
    fun chargedLine(rupees: Double): String? =
        if (rupees.isFinite() && rupees >= 0.005) "Spread charged Rs " + "%,.2f".format(java.util.Locale.ENGLISH, rupees) else null

    /** The diagnostics line: "paper spread charged today: Rs 52.50 over 3 fills". */
    fun dayLine(rupees: Double, fills: Int): String =
        "paper spread charged today: Rs " + "%,.2f".format(java.util.Locale.ENGLISH, if (rupees.isFinite()) rupees else 0.0) +
            " over $fills fill" + (if (fills == 1) "" else "s")

    /**
     * The price an exit fills at when only a stale candle exists and no fresher quote can be read: a SELL at the last
     * minute's low, a BUY at its high (the worse side for the order); the [last] close when that side is missing.
     */
    fun conservativeExit(action: String, last: Double, low: Double, high: Double): Double =
        if (action.uppercase() == "BUY") (high.takeIf { it > 0 && it.isFinite() } ?: last) else (low.takeIf { it > 0 && it.isFinite() } ?: last)
}
