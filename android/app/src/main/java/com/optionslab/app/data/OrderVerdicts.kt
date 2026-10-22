package com.optionslab.app.data

import com.optionslab.engine.Kite
import com.optionslab.engine.risk.OrderFingerprint
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Order fingerprints on the risk verdicts (22 Oct 2026, borrowed from Jake Schincariol's day-trader skill, MIT; the rule is the
 * engine's [OrderFingerprint]). The check that clears an order (Boss's review with the account guard and Zerodha's limits, an
 * arm's live gate, ORB's and Pine's live entry checks, a strategy's, and on paper the guard's look) stamps a verdict with the
 * fingerprint of the exact order it judged. At the moment the order is sent ([Broker.placeOrder] for a real one, [Paper.place]
 * for a paper one) the sender recomputes the fingerprint of what it is about to send and refuses when it differs or the
 * verdict is not from today, says plainly which field changed, and logs it. Refusing only lowers risk, so it applies live
 * and on paper alike; an exit is never refused by it (closing lowers risk).
 *
 * Memory only on the order path: a string hash, two counters and the last refusal per source (for the tickets and the
 * diagnostics); the log line goes through [Diag.record]'s queue and the banner through [com.optionslab.app.work.Alerts].
 */
object OrderVerdicts {
    /** The source name of a live order as both the stamper and the sender see it: the arm's key, else the tag's owner. */
    fun sourceOf(o: Kite.Order, arm: String?): String = arm ?: when (o.tag) {
        "iraalgo" -> "manual"
        else -> o.tag
    }

    /** The fields of a real order about to be sent: [stop] / [target] what the sender will rest or watch, when it knows them. */
    fun liveFields(o: Kite.Order, day: LocalDate, source: String, stop: Double? = null, target: Double? = null): OrderFingerprint.Fields =
        OrderFingerprint.Fields(o.tradingSymbol, o.side.name, o.quantity, o.product, o.orderType, o.price?.takeIf { o.hasPrice },
            o.triggerPrice?.takeIf { o.hasTrigger }, stop, target, day, live = true, source = source)

    /** The fields of a paper order ([Paper.place]'s arguments): the quantity is [lots] of the contract's lot. */
    fun paperFields(c: Paper.Contract, action: String, lots: Int, priceType: String, product: String, price: Double?, trigger: Double?,
                    day: LocalDate, source: String, stop: Double? = null, target: Double? = null): OrderFingerprint.Fields =
        OrderFingerprint.Fields(c.symbol, action, lots * c.lotSize.coerceAtLeast(1), product, priceType,
            price?.takeIf { priceType == "LIMIT" || priceType == "SL" }, trigger?.takeIf { priceType == "SL" || priceType == "SL-M" },
            stop, target, day, live = false, source = source)

    /** A check's verdict on [fields]: passed with no [reasons], refused with them. Counted (memory). */
    fun stamp(fields: OrderFingerprint.Fields, reasons: List<String>, by: String): OrderFingerprint.Verdict {
        stamped.incrementAndGet()
        return OrderFingerprint.Verdict(fields, passed = reasons.isEmpty(), reasons = reasons, by = by)
    }

    /** A passed check ([stamp] with no reasons). */
    fun cleared(fields: OrderFingerprint.Fields, by: String): OrderFingerprint.Verdict = stamp(fields, emptyList(), by)

    /**
     * The sender's look right before the send: null when [v] clears [now]; else why not, after it was said (a banner), logged
     * (the diagnostics' "fingerprint" lines) and kept for [now]'s source. The caller then refuses the order.
     */
    fun refusal(v: OrderFingerprint.Verdict?, now: OrderFingerprint.Fields): String? {
        val why = OrderFingerprint.refusal(v, now) ?: run { matched[now.source] = now.fingerprint(); lastRefusal.remove(now.source); return null }
        refused.incrementAndGet()
        val text = "${if (now.live) "Real" else "Paper"} ${now.side} ${now.qty} ${now.symbol} (${now.source}) NOT sent: $why."
        lastRefusal[now.source] = text
        runCatching { Diag.record("fingerprint", text) }
        runCatching { com.optionslab.app.work.Alerts.error(text, "Order fingerprint") }
        return why
    }

    private val stamped = AtomicInteger()
    private val refused = AtomicInteger()
    private val matched = ConcurrentHashMap<String, String>()
    private val lastRefusal = ConcurrentHashMap<String, String>()

    /** The last fingerprint a send of [source] matched (for a ticket's gate line), or null. */
    fun lastMatched(source: String): String? = matched[source]

    /** The last refusal of [source], or null. */
    fun lastRefused(source: String): String? = lastRefusal[source]

    /** The diagnostics' line. */
    fun diagLine(): String = "Order fingerprints: ${stamped.get()} verdict(s) stamped, ${refused.get()} send(s) refused since the app started" +
        (lastRefusal.values.lastOrNull()?.let { "; last refusal: $it" } ?: "")

    /** TEST: the counters and the last outcomes. */
    internal fun resetForTest() { stamped.set(0); refused.set(0); matched.clear(); lastRefusal.clear() }
}
