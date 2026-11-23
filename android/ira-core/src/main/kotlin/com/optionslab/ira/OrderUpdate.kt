package com.optionslab.ira

import com.optionslab.engine.strategy.Json

/**
 * Zerodha's order updates on the live price stream (Kite Connect's WebSocket postbacks): a text message
 * `{"type":"order","data":{...}}` for every change of an order of the account (placed, open, filled, cancelled, rejected),
 * on the same connection as the prices. [parse] reads the order's id, status, average price, filled quantity and Zerodha's
 * message; nothing else is kept (never the account id, the user or any key). Pure.
 */
object OrderUpdate {
    /** A terminal status: the order will not change again. */
    val TERMINAL = setOf("COMPLETE", "REJECTED", "CANCELLED")

    /**
     * 23 Nov 2026 ([ManualCoverRule.fillTriggers]: a manual buy looked at as soon as it fills): also the order's side ("BUY" /
     * "SELL"), exchange, product and quantity; empty (0) when the update does not give them.
     */
    data class Update(val orderId: String, val status: String, val avgPrice: Double, val filled: Int, val message: String,
                      val symbol: String = "", val tag: String = "", val side: String = "", val exchange: String = "",
                      val product: String = "", val qty: Int = 0) {
        val terminal: Boolean get() = status in TERMINAL
    }

    /** The update in [text], or null when it is not an order update (a price error, a message, anything unreadable). */
    fun parse(text: String): Update? {
        val root = runCatching { Json.parse(text) }.getOrNull() as? Map<*, *> ?: return null
        if (root["type"] != "order") return null
        val d = root["data"] as? Map<*, *> ?: return null
        val id = str(d["order_id"]) ?: return null
        val status = str(d["status"])?.uppercase() ?: return null
        return Update(id, status, num(d["average_price"]), num(d["filled_quantity"]).toInt(),
            str(d["status_message"]).orEmpty(), str(d["tradingsymbol"]).orEmpty(), str(d["tag"]).orEmpty(),
            str(d["transaction_type"])?.uppercase().orEmpty(), str(d["exchange"])?.uppercase().orEmpty(), str(d["product"])?.uppercase().orEmpty(),
            num(d["quantity"]).toInt())
    }

    private fun str(v: Any?): String? = when (v) {
        is String -> v.takeIf { it.isNotBlank() && it != "null" }
        is Number -> v.toString()
        else -> null
    }

    private fun num(v: Any?): Double = when (v) {
        is Number -> v.toDouble().takeIf { it.isFinite() } ?: 0.0
        is String -> v.toDoubleOrNull() ?: 0.0
        else -> 0.0
    }
}
