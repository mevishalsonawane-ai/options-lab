package com.optionslab.app.data

import com.optionslab.ira.ExactCharges
import java.time.LocalDate

/**
 * Zerodha's EXACT charges for the day, from its virtual contract note ([Broker.contractNote]), for the small line under
 * a Zerodha P&L: "Charges ₹X" instead of "Charges ≈ ₹X (estimate)" (Boss doubted the estimate's ~Rs 2,500 a day).
 *
 * Asked about the day's COMPLETE orders ([ExactCharges.billable]) and kept per day, keyed on that set of order ids: the
 * answer is used until another order completes, Zerodha is asked again then (never more than once a minute, and the
 * same set only after [ExactCharges.RETRY_MS]). Not logged in, the GOLD build (no Zerodha), a failed or odd answer, an
 * order part filled but not COMPLETE: null, and the estimate is shown as before. Kept in memory only (never written);
 * no token or key is read here.
 *
 * Display only: no risk limit reads it.
 */
object ZerodhaCharges {
    private class Kept(val day: String, val key: String, val ids: Set<String>, val total: Double, val sent: Int)

    @Volatile private var keptAnswer: Kept? = null
    /** "day|key" last asked about (answered or not), and when (wall clock ms; 0: never). */
    @Volatile private var askedKey: String? = null
    @Volatile private var askedAt = 0L
    private val asking = kotlinx.coroutines.sync.Mutex()

    private fun row(o: Broker.OrderRow) =
        ExactCharges.Order(o.id, o.exchange, o.symbol, o.side, o.variety, o.product, o.type, o.status, o.filled, o.avg)

    /** [orders]' rows placed on [day] (Kite lists only today's; a row with no date counts). */
    private fun ofDay(orders: List<Broker.OrderRow>, day: LocalDate): List<ExactCharges.Order> =
        orders.filter { it.placedAt.startsWith(day.toString()) || it.placedAt.length < 10 }.map(::row)

    /** The billable orders of [day], or null when the day is not whole ([ExactCharges.whole]) or has none. */
    private fun billable(orders: List<Broker.OrderRow>, day: LocalDate): List<ExactCharges.Order>? {
        val rows = ofDay(orders, day)
        if (!ExactCharges.whole(rows)) return null
        return ExactCharges.billable(rows).takeIf { it.isNotEmpty() }
    }

    /** The exact figure already kept for exactly these [orders] of [day], or null. Never asks Zerodha. */
    fun kept(orders: List<Broker.OrderRow>, day: LocalDate = Market.today()): Double? {
        if (com.optionslab.app.BuildConfig.GOLD) return null
        val bill = billable(orders, day) ?: return null
        val k = keptAnswer ?: return null
        return k.total.takeIf { k.day == day.toString() && k.key == ExactCharges.key(bill) }
    }

    /**
     * The exact figure kept for [day] when it covers every order the day's fills belong to ([orderIds]: the trades
     * kept that day), or null. Never asks Zerodha.
     */
    fun keptCovering(day: LocalDate, orderIds: Collection<String>): Double? {
        if (com.optionslab.app.BuildConfig.GOLD) return null
        val k = keptAnswer ?: return null
        // An order the app sent since the ask (a strategy's, in the background) is not in the kept answer even when the
        // kept trades have not caught up with it yet: the estimate is said then, never an old figure called exact.
        return k.total.takeIf { k.day == day.toString() && k.sent == Broker.sentToday() && ExactCharges.covers(k.ids, orderIds) }
    }

    /**
     * Zerodha's exact charges for the day's [orders] (today's order book): the kept answer when it is for these orders,
     * else Zerodha is asked when [ExactCharges.mayAsk] allows. Null (the estimate is shown) on anything else. Never
     * throws but for a cancel.
     */
    suspend fun exact(orders: List<Broker.OrderRow>, day: LocalDate = Market.today()): Double? {
        if (com.optionslab.app.BuildConfig.GOLD) return null
        val bill = billable(orders, day) ?: return null
        val key = ExactCharges.key(bill)
        keptAnswer?.let { k -> if (k.day == day.toString() && k.key == key) return k.total }
        if (!Broker.loggedIn) return null
        // One ask at a time: another caller meanwhile shows the estimate rather than wait for it.
        if (!asking.tryLock()) return null
        try {
            val now = System.currentTimeMillis()
            val dayKey = "$day|$key"
            if (!ExactCharges.mayAsk(askedKey == dayKey, askedAt, now)) return null
            askedKey = dayKey
            askedAt = now
            val ids = bill.map { it.orderId }
            val sent = Broker.sentToday()
            // A read with a real deadline ([Broker.within]): null on a failure or a timeout.
            val answer = Broker.within(15_000) { Broker.contractNote(ExactCharges.requestJson(bill)) } ?: return null
            val total = ExactCharges.total(answer, ids) ?: return null
            keptAnswer = Kept(day.toString(), key, ids.toSet(), total, sent)
            return total
        } finally {
            asking.unlock()
        }
    }

    /** Forgets every kept answer and when Zerodha was last asked (the trade book wiped; between tests). */
    fun forget() { keptAnswer = null; askedKey = null; askedAt = 0L }
}
