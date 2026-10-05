package com.optionslab.app.ira

import com.optionslab.app.data.Broker
import com.optionslab.ira.OrderWhy
import java.time.LocalTime

/**
 * "Why was my last order cancelled?" ([OrderWhy]): today's orders as the app holds them - the paper book's (with the reason
 * the app noted when it cancelled one itself, [com.optionslab.app.data.Paper.cancelReason], and the time it ended) and,
 * with a Zerodha session, Zerodha's (with its status message) - each with who placed it. Reads only: nothing is placed,
 * changed or cancelled. Boss's account, so the hub asks for it only on an unlocked phone; not in IraGoldAlgo.
 */
object IraOrderWhy {
    private const val ZERODHA_MS = 8_000L

    /** "HH:mm" from a "yyyy-MM-dd HH:mm:ss" (or ISO) stamp. */
    private fun hm(stamp: String): LocalTime? = runCatching { LocalTime.parse(stamp.drop(11).take(5)) }.getOrNull()

    suspend fun answer(asked: OrderWhy.Asked): String {
        val today = com.optionslab.app.data.Market.today()
        val owners = runCatching { com.optionslab.app.data.Strategies.owners() }.getOrDefault(emptyMap())
        val orders = ArrayList<OrderWhy.Ord>()
        val notes = ArrayList<String>()
        val paper = runCatching { com.optionslab.app.data.Paper.state.orders }.getOrNull()
        if (paper == null) notes += "The paper account did not open just now."
        paper.orEmpty().filter { it.orderTimestamp.toLocalDate() == today }.sortedBy { it.orderTimestamp }.forEach {
            val ended = it.status == "cancelled" || it.status == "rejected"
            orders += OrderWhy.Ord("Paper", it.orderTimestamp.toLocalTime(), it.symbol, it.action, it.quantity, it.status.uppercase(),
                it.averagePrice?.toDouble() ?: 0.0, owners["paper:${it.orderId}"] ?: it.strategy?.takeIf { s -> s.isNotBlank() },
                it.rejectionReason?.takeIf { r -> r.isNotBlank() },
                runCatching { com.optionslab.app.data.Paper.cancelReason(it.orderId) }.getOrNull(),
                if (ended) it.updateTimestamp.toLocalTime() else null, it.product, it.priceType)
        }
        if (runCatching { Broker.loggedIn }.getOrDefault(false)) {
            val z = Broker.within(ZERODHA_MS) { Broker.orders() }
            if (z == null) notes += "Zerodha did not answer just now, so its orders are not included."
            else z.filter { it.placedAt.startsWith(today.toString()) }.sortedBy { it.placedAt }.forEach {
                orders += OrderWhy.Ord("Zerodha", hm(it.placedAt), it.symbol, it.side, it.qty, it.status, it.avg,
                    owners["kite:${it.id}"] ?: com.optionslab.app.data.Origins.fromTag(it.tag.takeIf { t -> t.isNotBlank() }) ?: it.tag.takeIf { t -> t.isNotBlank() },
                    it.message.takeIf { m -> m.isNotBlank() }, null, null, it.product, it.type)
            }
        } else if (runCatching { Broker.linked }.getOrDefault(false)) notes += "Zerodha is not logged in today, so only the paper orders were read."
        // Oldest first across both accounts, as OrderWhy reads them.
        return OrderWhy.answer(asked, orders.sortedBy { it.time ?: LocalTime.MIN }, notes)
    }
}
