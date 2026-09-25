package com.optionslab.engine.portfolio

/**
 * Transaction costs as a composable schedule. Port of `portfolio/costs.py`
 * and the flat `Costs` model in `portfolio/engine.py`.
 *
 * A schedule is a list of rules, each charged on turnover, one leg, or per
 * order, with tax only on the lines marked taxed. The India delivery preset
 * reproduces a public brokerage calculator exactly.
 */
sealed interface CostModel

/** One line on a contract note. `basis` is turnover | buy | sell | order. */
data class Charge(
    val key: String,
    val label: String,
    val basis: String,
    val rate: Double = 0.0,
    val flat: Double = 0.0,
    val cap: Double = 0.0,
    val taxed: Boolean = false,
    val note: String = "",
) {
    init {
        if (basis !in BASES) throw PortfolioException(400, "unknown charge basis '$basis'; expected one of ${BASES.sorted().pyList()}")
        for ((name, value) in listOf("rate" to rate, "flat" to flat, "cap" to cap)) {
            if (!value.isFinite() || value < 0) throw PortfolioException(400, "charge $name must be finite and non-negative")
        }
    }

    fun amount(buyValue: Double, sellValue: Double, orders: Int): Double = when (basis) {
        "turnover" -> (buyValue + sellValue) * rate
        "buy" -> buyValue * rate
        "sell" -> sellValue * rate
        else -> if (orders <= 0) 0.0 else if (flat > 0) flat * orders else {
            val perOrder = (buyValue + sellValue) / orders
            var charged = perOrder * rate
            if (cap > 0) charged = minOf(charged, cap)
            charged * orders
        }
    }

    companion object { val BASES = setOf("turnover", "buy", "sell", "order") }
}

/** A market's charges plus the tax on the taxable ones and a slippage rate. */
data class CostSchedule(
    val name: String,
    val currency: String = "INR",
    val charges: List<Charge> = emptyList(),
    val taxLabel: String = "GST",
    val taxRate: Double = 0.0,
    val slippage: Double = 0.0,
) : CostModel {
    init {
        for ((n, value) in listOf("tax_rate" to taxRate, "slippage" to slippage)) {
            if (!value.isFinite() || value < 0) throw PortfolioException(400, "cost schedule $n must be finite and non-negative")
        }
    }

    /** Every line, the tax, slippage, the total and the order count. */
    fun breakdown(buyValue: Double, sellValue: Double, orders: Int): LinkedHashMap<String, Double> {
        val out = LinkedHashMap<String, Double>()
        var taxable = 0.0
        for (c in charges) {
            val v = c.amount(buyValue, sellValue, orders)
            out[c.key] = v
            if (c.taxed) taxable += v
        }
        out["tax"] = taxable * taxRate
        out["slippage"] = (buyValue + sellValue) * slippage
        out["total"] = Py.sum(out.values.toList())
        out["orders"] = orders.toDouble()
        return out
    }

    fun charge(buyValue: Double, sellValue: Double, orders: Int = 0) = breakdown(buyValue, sellValue, orders).getValue("total")

    /** Per-charge edits `{"stt": {"rate": 0.00125}}`; unknown keys and null values are ignored. */
    fun withOverrides(overrides: Map<String, Map<String, Double?>>): CostSchedule = copy(
        charges = charges.map { c ->
            val patch = overrides[c.key]
            if (patch.isNullOrEmpty()) c else c.copy(
                rate = patch["rate"] ?: c.rate,
                flat = patch["flat"] ?: c.flat,
                cap = patch["cap"] ?: c.cap,
            )
        },
    )

    companion object {
        /** NSE/BSE cash delivery; only the exchange transaction fee differs. */
        fun indiaDelivery(exchange: String = "NSE"): CostSchedule {
            val txn = if (exchange.uppercase() == "BSE") 0.0000375 else 0.0000307
            return CostSchedule(
                name = "India delivery (${exchange.uppercase()})", currency = "INR", taxLabel = "GST", taxRate = 0.18,
                charges = listOf(
                    Charge("brokerage", "Brokerage", "order", flat = 0.0, cap = 20.0, taxed = true,
                        note = "Delivery is free at most discount brokers; flat per order otherwise"),
                    Charge("stt", "STT", "turnover", rate = 0.001, note = "0.1% on both legs of a delivery trade"),
                    Charge("exchange_txn", "Exchange txn charge", "turnover", rate = txn, taxed = true,
                        note = "NSE 0.00307%, BSE 0.00375% of turnover"),
                    Charge("sebi", "SEBI charges", "turnover", rate = 10.0 / 1_00_00_000, taxed = true,
                        note = "Rs 10 per crore of turnover"),
                    Charge("stamp_duty", "Stamp duty", "buy", rate = 0.00015, note = "0.015% on the buy leg only"),
                ),
            )
        }

        fun usEquity() = CostSchedule(
            name = "US equity", currency = "USD", taxLabel = "Tax", taxRate = 0.0,
            charges = listOf(
                Charge("commission", "Commission", "order", flat = 0.0, note = "0 at most US brokers"),
                Charge("sec_fee", "SEC fee", "sell", rate = 0.0000278, note = "Sell side only"),
            ),
        )

        private val PRESETS: Map<String, () -> CostSchedule> = linkedMapOf(
            "india_delivery_nse" to { indiaDelivery("NSE") },
            "india_delivery_bse" to { indiaDelivery("BSE") },
            "us_equity" to { usEquity() },
        )

        fun scheduleFor(name: String, overrides: Map<String, Map<String, Double?>> = emptyMap()): CostSchedule {
            val factory = PRESETS[name]
                ?: throw PortfolioException(400, "unknown cost schedule '$name'; have ${PRESETS.keys.sorted().pyList()}")
            return factory().withOverrides(overrides)
        }
    }
}

/** A flat round-trip rate: `bps` of traded value plus `slippage`. */
data class FlatCosts(val bps: Double = 0.0, val slippage: Double = 0.0) : CostModel {
    init {
        for ((name, value) in listOf("bps" to bps, "slippage" to slippage)) {
            if (!value.isFinite() || value < 0) throw PortfolioException(400, "flat cost $name must be finite and non-negative")
        }
    }

    val total get() = (bps / 10_000.0) + slippage
}

internal fun List<String>.pyList() = joinToString(", ", "[", "]") { "'$it'" }

/**
 * The cost inputs both services accept. `costModel` is "indian_equity" (any
 * value other than "flat_bps" is treated as it, as IraAlgo does) or "flat_bps".
 */
data class CostInputs(
    val costModel: String = "indian_equity",
    val costExchange: String = "NSE",
    val chargeOverrides: Map<String, Map<String, Double?>> = emptyMap(),
    val gstRate: Double? = null,
    val costBps: Double = 0.0,
    val slippage: Double = 0.0,
) {
    /**
     * `_build_costs`. `brokerage` is the simple-field brokerage (portfolio:
     * a fraction; SIP: already converted from percent) and `brokerageFlat` the
     * SIP's flat per-order amount; either one wins over the preset unless the
     * overrides name brokerage themselves.
     */
    internal fun build(brokerageRate: Double, brokerageFlat: Double = 0.0): CostModel {
        if (costModel == "flat_bps") return FlatCosts(costBps, slippage)
        val overrides = LinkedHashMap(chargeOverrides)
        if ((brokerageRate != 0.0 || brokerageFlat != 0.0) && "brokerage" !in overrides) {
            overrides["brokerage"] = mapOf("flat" to brokerageFlat, "rate" to brokerageRate, "cap" to 20.0)
        }
        val schedule = CostSchedule.scheduleFor("india_delivery_${costExchange.lowercase()}", overrides)
        return schedule.copy(slippage = slippage, taxRate = gstRate ?: schedule.taxRate)
    }
}
