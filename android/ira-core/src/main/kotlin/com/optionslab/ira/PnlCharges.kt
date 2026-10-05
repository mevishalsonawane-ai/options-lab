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
    /** One filled trade leg as the broker lists it: BUY / SELL, its price and quantity. */
    data class Fill(val side: String, val price: Double, val qty: Int)

    /** The P&L before charges from the one after them and the charges paid. */
    fun gross(net: Double, charges: Double): Double = net + charges

    /** The P&L after charges from the one before them. */
    fun net(gross: Double, charges: Double): Double = gross - charges

    /** The charges of [fills] estimated with the F&O schedule (brokerage, STT, exchange, SEBI, stamp, GST), to the paisa. */
    fun estimate(fills: List<Fill>): Double {
        val total = fills.sumOf { f -> SandboxCosts.breakdown(f.side, f.price, f.qty).values.sum() }
        return (total * 100).roundToLong() / 100.0
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
