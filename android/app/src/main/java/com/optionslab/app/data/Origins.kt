package com.optionslab.app.data

/**
 * Who placed an order, in words: the strategy, the automatic rule or the screen a hand order came from.
 *
 * The labels live in [Strategies.owners] under "paper:<order id>" / "kite:<order id>"; every path that
 * places an order records one there. An order the phone has no label for is named from its Zerodha tag
 * (each path tags its orders), and a Zerodha order with no tag at all was placed outside this app.
 *
 * Pure: no Android, so the naming is unit-tested on the JVM.
 */
object Origins {
    const val MANUAL = "Manual"
    const val EXPIRY = "Expiry square-off"
    const val AUTO_SQUARE_OFF = "Auto square-off 15:15"
    const val OUTSIDE = "Outside IraAlgo (Kite)"

    /** A hand order and the screen it was sent from: "Manual · Option chart". */
    fun manual(area: String): String = "$MANUAL · $area"

    /** Labels of rules that act on their own (not a strategy, not a hand order). */
    private val AUTOMATIC = listOf("Protection", EXPIRY, AUTO_SQUARE_OFF, "GTT")

    /** The name a Zerodha tag stands for, when the phone kept no label (an order from before, or another install). */
    fun fromTag(tag: String?): String? = when (tag) {
        null -> null
        "iraorb" -> "ORB"
        "irapine" -> "Pine"
        "iraalgostrat" -> "Strategy order"
        "iraprotect" -> "Protection"
        "iraalgoexpiry" -> EXPIRY
        "iraalgo" -> MANUAL
        "" -> OUTSIDE
        else -> OUTSIDE
    }

    /**
     * The label for [venueId] ("paper:<id>" or "kite:<id>") as shown on a row, and whether it was placed
     * by the app itself (a strategy or an automatic rule) rather than by hand. [tag] is the Zerodha order's
     * tag, null when it is not known.
     */
    fun of(owners: Map<String, String>, venueId: String, tag: String? = null): Pair<String, Boolean> {
        val label = owners[venueId] ?: (if (venueId.startsWith("kite:")) fromTag(tag) else null) ?: MANUAL
        return display(label)
    }

    /** "Strategy: ORB · entry", "Auto: Protection · stop", "Manual · Chart", "Outside IraAlgo (Kite)". */
    fun display(label: String): Pair<String, Boolean> = when {
        label == MANUAL || label.startsWith("$MANUAL ·") || label == OUTSIDE -> label to false
        label == "Strategy order" -> label to true
        AUTOMATIC.any { label == it || label.startsWith("$it ·") } -> "Auto: $label" to true
        else -> "Strategy: $label" to true
    }

    /** A strategy or rule's name without the step ("ORB · entry" -> "ORB", "Manual · Chart" -> "Manual"). */
    fun base(label: String): String = label.substringBefore(" · ").trim()

    /** One filled order on a symbol, oldest first, as [position] reads them. */
    data class Fill(val venueId: String, val tag: String?, val buy: Boolean, val qty: Int)

    /**
     * Who opened what a position holds now. The orders on the position's side are read newest first
     * until they cover the open quantity; their names are joined ("ORB", "ORB + Manual"). A closed
     * position is named by everyone who opened a trade on it today, in order ("ORB + Range Fade"): several arms
     * trade the same option in a day, and its P&L is all of theirs. Null when no order explains it (carried overnight).
     */
    fun position(owners: Map<String, String>, fills: List<Fill>, netQty: Int): String? {
        if (fills.isEmpty()) return null
        val names = LinkedHashSet<String>()
        fun name(f: Fill) = of(owners, f.venueId, f.tag).first.removePrefix("Strategy: ").removePrefix("Auto: ").let(::base)
        if (netQty == 0) {
            // A fill opens when it moves the running quantity away from zero (a buy from flat or long, a sell from flat or short).
            var running = 0
            for (f in fills) {
                val signed = if (f.buy) f.qty else -f.qty
                if (running == 0 || (running > 0) == f.buy) names += name(f)
                running += signed
            }
            return names.joinToString(" + ").ifEmpty { name(fills.first()) }
        }
        var left = kotlin.math.abs(netQty)
        for (f in fills.asReversed()) {
            if (f.buy != (netQty > 0) || f.qty <= 0) continue
            names += name(f)
            left -= f.qty
            if (left <= 0) break
        }
        return names.toList().asReversed().joinToString(" + ").ifEmpty { null }
    }

    /** A position's [position] label as a pill: "Opened by ORB + Manual"; bold when the app opened any of it. */
    fun positionDisplay(label: String): Pair<String, Boolean> =
        "Opened by $label" to label.split(" + ").any { it != MANUAL && it != OUTSIDE }

    /** [position] for a paper position, from the paper trade book (one row per fill). */
    fun paperPosition(owners: Map<String, String>, trades: List<com.optionslab.engine.sandbox.TradeRow>,
                      symbol: String, product: String, netQty: Int): String? =
        position(owners, trades.filter { it.symbol == symbol && it.product == product }.sortedBy { it.timestamp }
            .map { Fill("paper:${it.orderId}", null, it.action.equals("BUY", ignoreCase = true), it.quantity) }, netQty)

    /** [position] for a Zerodha position, from today's trades; the orders give each fill's tag. */
    fun livePosition(owners: Map<String, String>, trades: List<Broker.Trade>, orders: List<Broker.OrderRow>,
                     symbol: String, product: String, netQty: Int): String? {
        val tags = orders.associate { it.id to it.tag }
        return position(owners, trades.filter { it.symbol == symbol && it.product == product }.sortedBy { it.at }
            .map { Fill("kite:${it.orderId}", tags[it.orderId], it.side.equals("BUY", ignoreCase = true), it.qty) }, netQty)
    }
}
