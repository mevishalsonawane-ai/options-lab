package com.optionslab.ira

import com.optionslab.engine.strategy.Json
import kotlin.math.roundToLong

/**
 * Zerodha's own charges for the day (its virtual contract note, Kite Connect's POST /charges/orders) instead of the
 * estimate [PnlCharges.estimate] makes from the fills (Boss doubted the ~Rs 2,500 a day the estimate showed). The app
 * sends the day's COMPLETE orders - each with the quantity it executed and its average price - and adds up the
 * `charges.total` Zerodha answers for each. Shown as "Charges ₹X" ([PnlCharges.line] with estimate = false); anything
 * missing or odd in the answer makes it null, and the estimate is shown as before.
 *
 * Display only, like [PnlCharges]: no risk limit reads it. Pure: building the request, reading the answer, and when to
 * ask again; the app makes the call.
 */
object ExactCharges {
    /** One order of the day's order book, as Kite lists it. */
    data class Order(val orderId: String, val exchange: String, val symbol: String, val side: String, val variety: String,
                     val product: String, val orderType: String, val status: String, val filled: Int, val avgPrice: Double)

    /** No new ask sooner than this after the last (a new order completing within the minute waits for the next one). */
    const val MIN_GAP_MS = 60_000L

    /** The same set of orders is asked about again only after this long (a failed ask is not repeated every minute). */
    const val RETRY_MS = 15 * 60_000L

    /**
     * The orders Zerodha is asked about: COMPLETE ones that executed something at a price, each once (by order id),
     * in order-id order. An order not COMPLETE, or one that filled nothing, is left out.
     */
    fun billable(orders: List<Order>): List<Order> =
        orders.filter { it.status.equals("COMPLETE", ignoreCase = true) && it.filled > 0 && it.avgPrice.isFinite() && it.avgPrice > 0 && it.orderId.isNotBlank() }
            .distinctBy { it.orderId }.sortedBy { it.orderId }

    /**
     * Do the [billable] orders account for every fill of the day? False when some other order executed something - one
     * still open with part filled, one cancelled or rejected after a part fill: its charges would be missing from
     * Zerodha's answer, so the day's figure would not be the whole day's and the estimate is shown instead.
     */
    fun whole(orders: List<Order>): Boolean = orders.none { !it.status.equals("COMPLETE", ignoreCase = true) && it.filled > 0 }

    /** What a kept answer is keyed on: the billable orders' ids, sorted ("" for none). */
    fun key(billable: List<Order>): String = billable.map { it.orderId }.sorted().joinToString(",")

    /** The JSON body of POST /charges/orders for [billable]: the executed quantity and average price of each. */
    fun requestJson(billable: List<Order>): String = Json.write(billable.map { o ->
        linkedMapOf<String, Any?>(
            "order_id" to o.orderId, "exchange" to o.exchange, "tradingsymbol" to o.symbol, "transaction_type" to o.side,
            "variety" to o.variety.ifBlank { "regular" }, "product" to o.product, "order_type" to o.orderType,
            "quantity" to o.filled, "average_price" to o.avgPrice,
        )
    })

    /**
     * The day's total from Zerodha's answer - the whole reply ({"status":"success","data":[...]}) or its data array -
     * to the paisa: each order's `charges.total`, added up. Null when the reply is an error, cannot be read, does not
     * answer for exactly the orders asked about ([askedIds], when given), or any order has no finite total.
     */
    fun total(response: String, askedIds: Collection<String>? = null): Double? {
        val parsed = runCatching { Json.parse(response) }.getOrNull() ?: return null
        val rows: List<*> = when (parsed) {
            is List<*> -> parsed
            is Map<*, *> -> {
                if (parsed["status"] != "success") return null
                parsed["data"] as? List<*> ?: return null
            }
            else -> return null
        }
        if (rows.isEmpty()) return null
        var sum = 0.0
        val answered = ArrayList<String>()
        for (r in rows) {
            val row = r as? Map<*, *> ?: return null
            val charges = row["charges"] as? Map<*, *> ?: return null
            val t = (charges["total"] as? Number)?.toDouble() ?: return null
            if (!t.isFinite() || t < 0) return null
            sum += t
            row["order_id"]?.let { answered += it.toString() }
        }
        if (askedIds != null) {
            if (rows.size != askedIds.size) return null
            if (answered.size == rows.size && answered.toSet() != askedIds.toSet()) return null
        }
        return (sum * 100).roundToLong() / 100.0
    }

    /**
     * May the app ask Zerodha now? Never within [MIN_GAP_MS] of the last ask; the same orders ([sameAsked]: the key
     * last asked about is this one, answered or not) only after [RETRY_MS].
     */
    fun mayAsk(sameAsked: Boolean, lastAskMs: Long, nowMs: Long): Boolean {
        val since = nowMs - lastAskMs
        if (lastAskMs > 0 && since in 0 until MIN_GAP_MS) return false
        return !sameAsked || lastAskMs <= 0 || since !in 0 until RETRY_MS
    }

    /** Does an answer kept for [keptIds] cover every order of [orderIds] (the day's fills)? Never for no fills. */
    fun covers(keptIds: Set<String>, orderIds: Collection<String>): Boolean {
        val ids = orderIds.filter { it.isNotBlank() }
        return ids.isNotEmpty() && keptIds.containsAll(ids)
    }

    // ---- what the widgets keep beside Zerodha's P&L (usefulness, round 35) ------------------------------------------

    /** The day's charges as a widget shows them under Zerodha's P&L: [value] rupees, an [estimate] or Zerodha's exact figure. */
    data class Shown(val value: Double, val estimate: Boolean)

    /**
     * What a widget keeps after a reading: Zerodha's [exact] figure (kept for every order of the day's fills - the widget
     * never asks Zerodha itself) wins over the [estimate] from the day's trades; with neither, what was [kept] earlier
     * today stays - but a kept exact figure, when this reading [checked] for one and it no longer covers the day (an
     * order filled since), is said as an estimate from then on: never an old figure called exact. Null: nothing to show.
     */
    fun widgetNext(kept: Shown?, exact: Double?, estimate: Double?, checked: Boolean): Shown? {
        exact?.takeIf { it.isFinite() }?.let { return Shown(it, estimate = false) }
        estimate?.takeIf { it.isFinite() }?.let { return Shown(it, estimate = true) }
        val k = kept?.takeIf { it.value.isFinite() } ?: return null
        return if (checked && !k.estimate) k.copy(estimate = true) else k
    }

    /** The widget's kept charges for [day]: "2026-10-05|180.00|x" (x: Zerodha's exact figure; e: an estimate). */
    fun encodeDay(day: String, s: Shown): String =
        String.format(java.util.Locale.ROOT, "%s|%.2f|%s", day, s.value, if (s.estimate) "e" else "x")

    /**
     * The charges [encodeDay] kept, when they are [today]'s; null for none, another day's, or a damaged record. An older
     * build's "2026-10-05|180.00" (no third field) was always an estimate.
     */
    fun decodeDay(raw: String?, today: String): Shown? {
        val f = raw?.split("|") ?: return null
        if (f.size !in 2..3 || f[0] != today) return null
        val v = f[1].toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
        return Shown(v, estimate = f.getOrNull(2) != "x")
    }
}
