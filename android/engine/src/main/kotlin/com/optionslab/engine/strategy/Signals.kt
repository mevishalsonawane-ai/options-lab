package com.optionslab.engine.strategy

import java.time.ZonedDateTime

/**
 * The single order decision point's pure rules. Port of
 * `services/strategy_module/order_dispatch.py`.
 */
object OrderRules {
    /** Exits are always MARKET: a limit exit that does not fill is not an exit. */
    const val EXIT_PRICETYPE = "MARKET"

    val DERIVATIVE_EXCHANGES_FOR_PRODUCT = setOf("NFO", "BFO", "MCX", "CDS", "BCD", "NCDEX", "NCO")

    /**
     * The venue's spelling of the product the strategy asked for. One product
     * covers every leg, so a basket with a cash leg and an option leg could
     * not be given a value both accept; it is read as intent instead: MIS is
     * intraday everywhere, anything else is carry, NRML on a derivatives
     * venue and CNC on cash. No leg is ever sent a product its venue refuses.
     */
    fun productForExchange(product: String?, exchange: String?): String {
        if ((product ?: "").uppercase() == "MIS") return "MIS"
        return if ((exchange ?: "").uppercase() in DERIVATIVE_EXCHANGES_FOR_PRODUCT) "NRML" else "CNC"
    }

    /**
     * The action that closes a leg, from its own recorded side. The original
     * read the configured side, which defaulted to B, so a rule-driven exit on
     * a short leg SOLD again and doubled the position.
     */
    fun exitAction(position: String?): String = when ((position ?: "").uppercase()) {
        "B" -> "SELL"
        "S" -> "BUY"
        else -> throw IllegalArgumentException("Cannot derive an exit action from position ${Py.repr(position)}")
    }

    /** The order payload in the placement services' shape; quantity is a string, as the rest of that path uses. */
    fun buildOrder(
        symbol: String, exchange: String, action: String, quantity: Int, product: String, strategyName: String,
        pricetype: String = "MARKET", price: Double = 0.0, triggerPrice: Double = 0.0,
    ): Map<String, String> = linkedMapOf(
        "symbol" to symbol,
        "exchange" to exchange,
        "action" to action.uppercase(),
        "quantity" to quantity.toString(),
        "product" to productForExchange(product, exchange),
        "pricetype" to pricetype,
        "price" to Py.str(if (price == 0.0) 0L else price),
        "trigger_price" to Py.str(if (triggerPrice == 0.0) 0L else triggerPrice),
        "strategy" to strategyName,
    )
}

/**
 * Signal-mode vocabulary and gates. Port of the pure parts of
 * `services/strategy_module/signals.py`: which actions exist, the direction
 * filter, which leg an alert names, the leg's own side filter, the trading
 * window, the carry-short refusal and the signal leg's quantity.
 */
object Signals {
    val SIGNAL_ACTIONS = listOf("long_entry", "long_exit", "short_entry", "short_exit")
    val BATCH_ACTIONS = listOf("start", "stop")
    val ENTRIES = setOf("long_entry", "short_entry")

    fun actionsFor(kind: StrategyKind): List<String> = if (kind == StrategyKind.SIGNAL) SIGNAL_ACTIONS else BATCH_ACTIONS

    /** Either a finished answer ([result]) or the leg and side to act on. */
    class Gate(val result: SignalResult?, val leg: LegDef?, val side: String?)

    /**
     * Everything decided before a run is touched: the action, the strategy's
     * direction, the target leg, the leg's accepted side, and the window.
     * A direction or side mismatch is a refusal the operator should see; being
     * outside the window is a no-op answered with a note.
     */
    fun gate(def: StrategyDef, action: String, legId: Int?, symbol: String?, exchange: String?, now: ZonedDateTime): Gate {
        if (action !in SIGNAL_ACTIONS) return Gate(SignalResult(false, error = "Unknown signal action: ${Py.repr(action)}"), null, null)
        // Upstream refuses this one step earlier, in the webhook validator
        // (`actions_for`); a batch strategy is controlled by start and stop.
        if (def.kind != StrategyKind.SIGNAL) {
            return Gate(SignalResult(false, error = "A batch strategy accepts ${BATCH_ACTIONS.joinToString(" and ")}, not ${Py.repr(action)}"), null, null)
        }
        val side = if (action.startsWith("long")) "long" else "short"
        val allowed = when (def.direction) {
            Direction.BOTH -> setOf("long", "short")
            Direction.LONG_ONLY -> setOf("long")
            Direction.SHORT_ONLY -> setOf("short")
        }
        if (side !in allowed) {
            return Gate(SignalResult(false, error = "This strategy is ${def.direction.wire}; a $side signal is not accepted"), null, null)
        }
        val leg = findLeg(def, legId, symbol, exchange) ?: return Gate(SignalResult(false, error = "No leg matches this signal"), null, null)
        val legSide = leg.side?.wire ?: "both"
        if (legSide != "both" && legSide != side) {
            return Gate(SignalResult(false, legId = leg.id, error = "Leg ${leg.id} only accepts $legSide signals"), null, null)
        }
        windowNote(def, action, now)?.let { return Gate(SignalResult(true, note = it, legId = leg.id), null, null) }
        return Gate(null, leg, side)
    }

    /**
     * Why a signal is outside an intraday strategy's trading window, if it is.
     * Everything stops at `exit_time`; entries may not start before
     * `entry_time`, but exits may, so a position carried in from a previous
     * session is always closable.
     */
    fun windowNote(def: StrategyDef, action: String, now: ZonedDateTime): String? {
        if (def.strategyType != StrategyType.INTRADAY) return null
        val t = now.withZoneSameInstant(IST).toLocalTime()
        if (def.exitTime != null && !t.isBefore(def.exitTime)) return "outside_trading_window"
        if (action in ENTRIES && def.entryTime != null && t.isBefore(def.entryTime)) return "outside_entry_window"
        return null
    }

    /** `legId` wins; the symbol fallback serves an alert template reused across strategies. */
    fun findLeg(def: StrategyDef, legId: Int?, symbol: String?, exchange: String?): LegDef? {
        if (legId != null) return def.legs.firstOrNull { it.id == legId }
        if (!symbol.isNullOrEmpty()) {
            val want = symbol.uppercase()
            val wantExchange = (exchange ?: "").uppercase()
            return def.legs.firstOrNull {
                (it.symbol ?: "").uppercase() == want && (wantExchange.isEmpty() || (it.exchange ?: "").uppercase() == wantExchange)
            }
        }
        return null
    }

    /**
     * Why this short cannot be opened, or null. Cash is sold short intraday
     * and never carried: under anything but MIS it reaches the venue as CNC, a
     * naked short delivery. Checked before any flip so a refusal liquidates
     * nothing.
     */
    fun uncarryableShort(def: StrategyDef, leg: LegDef, side: String): String? {
        if (side != "short") return null
        val product = def.product.wire.uppercase()
        if (product == "MIS") return null
        val exchange = (leg.exchange ?: "").uppercase()
        if (exchange.isEmpty() || exchange in MasterContract.DERIVATIVE_EXCHANGES) return null
        return "cash cannot be held short overnight, and product $product carries the position. Use MIS for an intraday short."
    }

    /** A signal leg ready to enter: its contract, the quantity sent, and the lot count configured. */
    class SignalLegSpec(val symbol: String, val exchange: String, val quantity: Int, val lots: Int, val lotSize: Int?)

    /**
     * A signal leg as run state wants it, or why not. The contract must exist
     * (a futures leg named by its base once produced a plausible quantity and
     * sent the literal base to the broker), and the quantity is resolved here:
     * lots times the master contract's lot size.
     */
    fun resolveSignalLeg(leg: LegDef, side: String, contract: MasterContract): Pair<SignalLegSpec?, String?> {
        val symbol = leg.symbol
        val exchange = leg.exchange
        val rawQty = leg.qty
        if (symbol.isNullOrEmpty() || exchange.isNullOrEmpty() || rawQty == null || rawQty == 0) {
            return null to "symbol, exchange and quantity are all required"
        }
        val s = symbol.uppercase()
        val e = exchange.uppercase()
        if (!contract.contractExists(s, e)) return null to "$s is not a contract on $e"
        val mode = leg.qtyMode?.wire ?: "units"
        val q = contract.resolveQuantity(rawQty.toLong(), mode, s, e)
        if (q.error != null) return null to q.error
        return SignalLegSpec(s, e, q.quantity!!, if (mode == "lots") rawQty else 1, q.lotSize) to null
    }
}
