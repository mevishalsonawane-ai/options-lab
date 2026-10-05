package com.optionslab.ira

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * How a P&L figure is shown (Boss, 5 Oct: "The P&L should not decrease the charges; just add the charges below in small
 * font"): the big figure is the P&L BEFORE charges - as Zerodha's own m2m / P&L shows it - and the charges go on a small
 * line under it. Paper and Zerodha alike:
 *
 *  - Paper: the engine debits each leg's charges from the funds (the cash stays real), and the day's figure it keeps is
 *    after charges; shown, the charges are added back ([gross]) and said exactly ("Charges ₹180").
 *  - Zerodha: its P&L is already before charges; the line is an estimate from the day's filled trades with the same F&O
 *    schedule the paper account pays ([estimate], as the charges report does) - "Charges ≈ ₹180 (estimate)" - or, when
 *    Zerodha's own contract note answered for the day's orders ([ExactCharges]), its exact figure: "Charges ₹180".
 *
 * Display only. Every risk limit - the daily loss limit, the guard, Solo's and the arms' loss limits and kill
 * thresholds - keeps reading the figure AFTER charges, the safer one; nothing here feeds a limit. Pure.
 */
object PnlCharges {
    /**
     * One filled trade leg as the broker lists it: BUY / SELL, its price and quantity, and the order it belongs to. Zerodha
     * lists every exchange fill as its own trade - one order of 10 lots often fills in several pieces - but its brokerage
     * is per executed ORDER, not per fill ([perFill]). A blank [orderId] counts as its own order. [symbol], [exchange] and
     * [product] say which schedule it pays ([segment]): blank, an option's (the app's own trades). [day] ("2026-10-05")
     * is when it filled: a delivery sale's DP charge is once per scrip a day.
     */
    data class Fill(
        val side: String, val price: Double, val qty: Int, val orderId: String = "",
        val symbol: String = "", val exchange: String = "", val product: String = "", val day: String = "",
    )

    /** Which of Zerodha's schedules a trade pays (Boss, 5 Oct: "fix it for futures and stocks too"). */
    enum class Segment { OPTIONS, FUTURES, DELIVERY, INTRADAY }

    /**
     * Zerodha's rates for one schedule (2026, STT from Budget 2026, from 1 Apr 2026): STT on a buy and on a sale, the
     * exchange's fee (NSE) and stamp duty on a buy, all on the trade's value; brokerage per order, at most Rs 20.
     */
    private class Rates(val sttBuy: Double, val sttSell: Double, val exchange: Double, val stampBuy: Double, val brokeragePct: Double?, val brokerageFlat: Double)

    private val RATES = mapOf(
        // Options: STT 0.15% of the premium sold, exchange 0.03553% (NSE 0.03503% + IPFT), stamp 0.003%, Rs 20 an order.
        Segment.OPTIONS to Rates(0.0, 0.0015, 0.0003553, 0.00003, null, 20.0),
        // Futures: STT 0.05% on the sale, exchange 0.00173%, stamp 0.002%, 0.03% or Rs 20 an order, whichever is lower.
        Segment.FUTURES to Rates(0.0, 0.0005, 0.0000173, 0.00002, 0.0003, 20.0),
        // Shares held (CNC): STT 0.1% on the buy and on the sale, exchange 0.00297%, stamp 0.015%, no brokerage.
        Segment.DELIVERY to Rates(0.001, 0.001, 0.0000297, 0.00015, null, 0.0),
        // Shares bought and sold the same day (MIS): STT 0.025% on the sale, exchange 0.00297%, stamp 0.003%, 0.03% or Rs 20.
        Segment.INTRADAY to Rates(0.0, 0.00025, 0.0000297, 0.00003, 0.0003, 20.0),
    )

    /** Zerodha's DP charge on a delivery sale: Rs 13 + GST, once per scrip a day. */
    const val DP_CHARGE = 13.0
    private const val GST = 0.18
    private const val SEBI = 10.0 / 1_00_00_000
    private val DERIVATIVE = Regex("""(\d(CE|PE)|FUT)$""")

    /**
     * The schedule a trade pays: NFO / BFO - a future when its symbol ends in FUT, else an option; NSE / BSE (or no
     * exchange and a symbol that is not a contract) - shares, the same day's when the product is MIS, else held. Nothing
     * known (the app's own option trades): an option's.
     */
    fun segment(symbol: String, exchange: String, product: String): Segment {
        val sym = symbol.trim().uppercase()
        val ex = exchange.trim().uppercase()
        val shares = ex == "NSE" || ex == "BSE" || (ex.isEmpty() && sym.isNotEmpty() && !DERIVATIVE.containsMatchIn(sym))
        if (shares) return if (product.trim().uppercase() == "MIS") Segment.INTRADAY else Segment.DELIVERY
        return if (sym.endsWith("FUT")) Segment.FUTURES else Segment.OPTIONS
    }

    /** The P&L before charges from the one after them and the charges paid. */
    fun gross(net: Double, charges: Double): Double = net + charges

    /** The P&L after charges from the one before them. */
    fun net(gross: Double, charges: Double): Double = gross - charges

    /** The charges of [fills] estimated with Zerodha's schedules (brokerage, STT, exchange, SEBI, stamp, GST, DP), to the paisa. */
    fun estimate(fills: List<Fill>): Double {
        val total = perFill(fills).sumOf { it.values.sum() }
        return (total * 100).roundToLong() / 100.0
    }

    /**
     * Each fill's charges line by line, in [fills]' order, each on its own schedule ([segment]). The brokerage (and the
     * GST on it) is charged once per order, on the first fill of each [Fill.orderId]: Rs 20 for an option, 0.03% of the
     * whole order's value up to Rs 20 for a future or a same-day share trade, nothing for shares held. A delivery sale
     * pays the DP charge once per scrip a day. STT, exchange, SEBI and stamp are on value, so they are the same however
     * an order is split. (Before 5 Oct every fill paid Rs 20 + GST, and every trade the option schedule.)
     */
    fun perFill(fills: List<Fill>): List<Map<String, Double>> {
        val orderValue = HashMap<String, Double>()
        fills.forEach { f -> if (f.orderId.isNotBlank()) orderValue[f.orderId] = (orderValue[f.orderId] ?: 0.0) + abs(f.price * f.qty) }
        val seen = HashSet<String>()
        val dpSeen = HashSet<String>()
        return fills.map { f ->
            val seg = segment(f.symbol, f.exchange, f.product)
            val first = f.orderId.isBlank() || seen.add(f.orderId)
            val value = abs(f.price * f.qty)
            val sale = f.side.uppercase() != "BUY"
            val dp = seg == Segment.DELIVERY && sale && value > 0 && dpSeen.add("${f.symbol.trim().uppercase()}|${f.day}")
            legOf(seg, sale, value, if (first) orderValue[f.orderId] ?: value else null, dp)
        }
    }

    /** One fill's charges line by line, on the option schedule; [firstOfOrder] false: no brokerage (paid on the order's first fill). */
    fun legCharges(side: String, price: Double, qty: Int, firstOfOrder: Boolean): Map<String, Double> {
        val value = abs(price * qty)
        return legOf(Segment.OPTIONS, side.uppercase() != "BUY", value, if (firstOfOrder) value else null, false)
    }

    /** [orderValue] null: not the order's first fill (no brokerage). Empty for a fill of no value. */
    private fun legOf(seg: Segment, sale: Boolean, value: Double, orderValue: Double?, dp: Boolean): Map<String, Double> {
        if (!(value > 0)) return emptyMap()
        val r = RATES.getValue(seg)
        val brokerage = when {
            orderValue == null -> 0.0
            r.brokeragePct != null -> minOf(r.brokerageFlat, r.brokeragePct * orderValue)
            else -> r.brokerageFlat
        }
        val stt = value * (if (sale) r.sttSell else r.sttBuy)
        val exchange = value * r.exchange
        val sebi = value * SEBI
        val stamp = if (sale) 0.0 else value * r.stampBuy
        val dpCharge = if (dp) DP_CHARGE else 0.0
        val out = linkedMapOf("Brokerage" to brokerage, "STT" to stt, "Exchange" to exchange, "SEBI" to sebi,
            "Stamp duty" to stamp, "GST" to (brokerage + exchange + sebi + dpCharge) * GST)
        if (dp) out["DP charges"] = dpCharge
        return out
    }

    /** Is there anything to say about [charges]? (Unknown, zero or under half a paisa: no line at all.) */
    fun shown(charges: Double?): Boolean = charges != null && charges.isFinite() && charges >= 0.005

    /** "₹180", "₹1,23,456" (whole rupees, Indian grouping); under a rupee "₹0.45". */
    fun inr(x: Double): String {
        val a = abs(x)
        if (a > 0 && a < 1) return "₹" + String.format(Locale.ENGLISH, "%.2f", a)
        return "₹" + indian(a.roundToLong())
    }

    /** Indian digit grouping: 123456 -> "1,23,456". */
    fun indian(n: Long): String {
        val d = abs(n).toString()
        if (d.length <= 3) return (if (n < 0) "-" else "") + d
        val last3 = d.takeLast(3)
        var head = d.dropLast(3)
        val out = ArrayList<String>()
        while (head.length > 2) { out.add(0, head.takeLast(2)); head = head.dropLast(2) }
        if (head.isNotEmpty()) out.add(0, head)
        return (if (n < 0) "-" else "") + out.joinToString(",") + "," + last3
    }

    /**
     * The small line under a P&L figure: "Charges ₹180" (paper, exact) or "Charges ≈ ₹180 (estimate)" (Zerodha, from its
     * trades); null when there is nothing to say ([shown]).
     */
    fun line(charges: Double?, estimate: Boolean): String? {
        if (!shown(charges)) return null
        return if (estimate) "Charges ≈ ${inr(charges!!)} (estimate)" else "Charges ${inr(charges!!)}"
    }

    /** The words Jarvis says beside a P&L: "charges Rs 180" / "charges about Rs 180"; null when nothing to say. */
    fun said(charges: Double?, estimate: Boolean): String? {
        if (!shown(charges)) return null
        return "charges ${if (estimate) "about " else ""}Rs " + String.format(Locale.ENGLISH, "%,.0f", charges!!)
    }
}
