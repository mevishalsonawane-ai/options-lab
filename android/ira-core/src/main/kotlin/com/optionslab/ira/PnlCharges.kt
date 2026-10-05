package com.optionslab.ira

import com.optionslab.engine.sandbox.SandboxCosts
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
 *    schedule the paper account pays ([estimate], as the charges report does) - "Charges ≈ ₹180 (estimate)".
 *
 * Display only. Every risk limit - the daily loss limit, the guard, Solo's and the arms' loss limits and kill
 * thresholds - keeps reading the figure AFTER charges, the safer one; nothing here feeds a limit. Pure.
 */
object PnlCharges {
    /**
     * One filled trade leg as the broker lists it: BUY / SELL, its price and quantity, and the order it belongs to. Zerodha
     * lists every exchange fill as its own trade - one order of 10 lots often fills in several pieces - but its Rs 20
     * brokerage is per executed ORDER, not per fill ([perFill]). A blank [orderId] counts as its own order.
     */
    data class Fill(val side: String, val price: Double, val qty: Int, val orderId: String = "")

    /** The P&L before charges from the one after them and the charges paid. */
    fun gross(net: Double, charges: Double): Double = net + charges

    /** The P&L after charges from the one before them. */
    fun net(gross: Double, charges: Double): Double = gross - charges

    /** The charges of [fills] estimated with the F&O schedule (brokerage, STT, exchange, SEBI, stamp, GST), to the paisa. */
    fun estimate(fills: List<Fill>): Double {
        val total = perFill(fills).sumOf { it.values.sum() }
        return (total * 100).roundToLong() / 100.0
    }

    /**
     * Each fill's charges line by line, in [fills]' order, with the Rs 20 brokerage (and the GST on it) charged once per
     * order: on the first fill of each [Fill.orderId], never again on that order's later fills. STT, exchange, SEBI and
     * stamp are on value, so they are the same however an order is split. (Before 5 Oct every fill paid Rs 20 + GST, so a
     * day whose orders filled in pieces showed several times the brokerage Zerodha takes.)
     */
    fun perFill(fills: List<Fill>): List<Map<String, Double>> {
        val seen = HashSet<String>()
        return fills.map { f -> legCharges(f.side, f.price, f.qty, f.orderId.isBlank() || seen.add(f.orderId)) }
    }

    /** One fill's charges line by line; [firstOfOrder] false: no brokerage and no GST on brokerage (already paid on the order). */
    fun legCharges(side: String, price: Double, qty: Int, firstOfOrder: Boolean): Map<String, Double> {
        val all = SandboxCosts.breakdown(side, price, qty)
        if (firstOfOrder || all.isEmpty()) return all
        val brokerage = all["Brokerage"] ?: 0.0
        return LinkedHashMap(all).apply {
            this["Brokerage"] = 0.0
            this["GST"] = (all["GST"] ?: 0.0) - brokerage * 0.18
        }
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
