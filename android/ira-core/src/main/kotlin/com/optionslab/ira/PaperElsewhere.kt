package com.optionslab.ira

import java.time.LocalDateTime

/**
 * 20 Nov 2026 (audit, B5 item 4, "wrong prices and records"): the price and time a paper arm books for a trade that left the paper
 * book without its own sale ("closed_elsewhere": the 15:15 or expiry square-off, the loss limit, Boss's close, the contract's
 * expiry, a reset). Record only: nothing here places, cancels or sells anything. Pure.
 *
 * Before, every arm took the LAST closing fill of the symbol since its entry, of any product and any arm, and its entry price when
 * there was none. So two arms on one option booked each other's sale; a NRML trade booked an MIS sale of the same option; and a
 * ticket that expired worthless (the expiry settlement makes no fill) or a reset trade was booked flat at its entry, which flattered
 * the forward tests. Now, in order:
 *  1. [How.SALE]: the last closing fill of the trade's own product since its entry that OTHER trades have not booked in full
 *     already (one square-off fill can close two arms' lots: each books it for its own quantity, never past the fill's);
 *  2. [How.EXPIRY]: the contract expired with no such fill - the paper book's own settlement price, at the session's close;
 *  3. [How.LAST_PRICE]: the last price seen recently (a reset, a book set aside), at the time asked;
 *  4. null: nothing known - the caller keeps its old answer (its entry price), as before.
 */
object PaperElsewhere {
    enum class How { SALE, EXPIRY, LAST_PRICE }

    /** One closing fill of the symbol in the paper book: its order, product, price, time, charges and [quantity]. */
    data class Sale(val orderId: String, val product: String, val price: Double, val at: LocalDateTime, val charges: Double,
                    val quantity: Int = Int.MAX_VALUE)

    /** What is booked: [price] at [at] with [charges]; [orderId] the fill it came from (a [How.SALE] only). */
    data class Exit(val price: Double, val at: LocalDateTime, val charges: Double, val how: How, val orderId: String? = null)

    /**
     * The exit of a trade entered at [since] in [product] (null: any): from [sales] (the symbol's closing fills, any order),
     * skipping those other trades already booked in full ([takenByOther]: the quantity of an order id they booked); else, when
     * [expired], the paper book's [settledPrice] at [settledAt]; else [lastPrice] (when a real price) at [now]; else null.
     */
    fun pick(sales: List<Sale>, product: String?, since: LocalDateTime, takenByOther: (String) -> Int,
             expired: Boolean, settledPrice: Double?, settledAt: LocalDateTime?, lastPrice: Double?, now: LocalDateTime): Exit? {
        // ([sales] in the book's order, oldest first, as before: the last one that fits.)
        val sale = sales.lastOrNull {
            !it.at.isBefore(since) && (product == null || it.product.equals(product, ignoreCase = true)) && takenByOther(it.orderId) < it.quantity
        }
        if (sale != null) return Exit(sale.price, sale.at, sale.charges, How.SALE, sale.orderId)
        if (expired && settledPrice != null && settledPrice.isFinite() && settledPrice >= 0 && settledAt != null)
            return Exit(settledPrice, if (settledAt.isBefore(since)) since else settledAt, 0.0, How.EXPIRY)
        if (lastPrice != null && lastPrice.isFinite() && lastPrice > 0) return Exit(lastPrice, now, 0.0, How.LAST_PRICE)
        return null
    }
}
