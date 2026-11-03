package com.optionslab.app.data

import com.optionslab.engine.Kite
import com.optionslab.engine.Right
import com.optionslab.engine.orb.LiveMirror
import java.util.Locale

/**
 * 3 Nov (Boss: "check all the symbols like MIDCPNIFTY which work on paper trading also work on live"): every morning, for each
 * index the arms trade (NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY on NFO, SENSEX on BFO), the at-the-money call of the nearest
 * expiry that is not today - the contract the arms would buy - is looked up at Zerodha and priced for 1 lot by Zerodha's own
 * margin calculator (POST /margins/orders: a read, nothing is placed). Said with it: whether the free money covers that lot,
 * and whether Zerodha would block a MARKET order on it (open interest under 100 lots: the arms then send a LIMIT). And whether
 * the account's MCX segment is on (the MCX arms' live copies need it). Words only; nothing changes.
 */
object IndexReadiness {
    val INDICES = listOf("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")
    /** Zerodha's rule: MIS MARKET orders are blocked on a contract with open interest under this many lots. */
    const val MARKET_OI_LOTS = 100

    data class Line(val ok: Boolean, val text: String)

    /** The morning's lines (a worker thread: it reads Zerodha). [available]: the free money, for the 1-lot comparison. */
    suspend fun check(available: Double?, mcxArmsLive: Boolean): List<Line> {
        val nfo = runCatching { Broker.instruments() }.getOrNull()
        val out = INDICES.map { idx -> runCatching { one(idx, nfo, available) }.getOrElse { Line(false, "$idx: could not be checked (${it.message?.take(120)})") } }
        val mcx = if (!mcxArmsLive) null else when (Broker.commodityEnabled()) {
            true -> Line(true, "MCX: enabled on your Zerodha account")
            false -> Line(false, "MCX: not enabled on your Zerodha account - the MCX arms' live copies are refused (Zerodha Console → Segments)")
            null -> null
        }
        return out + listOfNotNull(mcx)
    }

    private suspend fun one(idx: String, nfo: List<Kite.Instrument>?, available: Double?): Line {
        val today = Market.today()
        val list = (if (idx == "SENSEX") Broker.bfoOptionsNow() else nfo ?: error("Zerodha's contract list could not be read"))
            .filter { it.name == idx && !it.expiry.isBefore(today) }
        if (list.isEmpty()) return Line(false, "$idx: no options listed at Zerodha today")
        val expiry = list.map { it.expiry }.filter { it.isAfter(today) }.minOrNull() ?: list.minOf { it.expiry }
        val spot = Broker.indexQuote(idx, spark = false)?.last?.takeIf { it > 0 } ?: return Line(false, "$idx: no index price to find the at-the-money strike")
        val ce = list.filter { it.expiry == expiry && it.right == Right.CE }.minByOrNull { kotlin.math.abs(it.strike - spot) }
            ?: return Line(false, "$idx: no call listed for $expiry")
        val ex = if (idx == "SENSEX") "BFO" else "NFO"
        val o = Kite.Order(ce.tradingSymbol, Kite.Side.BUY, ce.lotSize, ce.lotSize, "MIS", "MARKET", null, ce.tickSize, ex, LiveMirror.TAG)
        val need = Broker.orderMargins(listOf(o)).singleOrNull()?.takeIf { it.isFinite() && it > 0 }
            ?: return Line(false, "$idx: Zerodha did not price 1 lot of ${ce.tradingSymbol}")
        val oi = runCatching { Broker.quotesFresh(listOf("$ex:${ce.tradingSymbol}"))["$ex:${ce.tradingSymbol}"]?.oi }.getOrNull()
        return line(idx, ce.tradingSymbol, ce.lotSize, need, available, oi)
    }

    /** The line for one index (pure, for the tests). */
    internal fun line(idx: String, symbol: String, lot: Int, need: Double, available: Double?, oi: Long?): Line {
        val notes = ArrayList<String>()
        val short = available != null && available.isFinite() && need > available
        if (short) notes += "1 lot needs ${rs(need)}, more than the ${rs(available!!)} free"
        if (oi != null && oi > 0 && oi < MARKET_OI_LOTS.toLong() * lot) notes += "low open interest: Zerodha blocks MARKET orders on it, the arms send a LIMIT"
        return Line(!short, "$idx: ${symbol} orderable at Zerodha, 1 lot ${rs(need)}" + if (notes.isEmpty()) "" else " (${notes.joinToString("; ")})")
    }

    private fun rs(x: Double) = "₹%,.0f".format(Locale.ENGLISH, x)
}
