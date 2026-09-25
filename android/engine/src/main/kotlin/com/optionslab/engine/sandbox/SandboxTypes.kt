package com.optionslab.engine.sandbox

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * The data the paper-trading engine works on. Port of IraAlgo's sandbox tables
 * (database/sandbox_db.py) and of the dicts its managers return.
 *
 * Money is BigDecimal because the Python is Decimal throughout. Every stored
 * amount is kept exactly as the Python's database hands it back: SQLite keeps
 * the Decimal as a float and SQLAlchemy reads it with "%.2f" (pnl_percent with
 * "%.4f"), so the state here holds that rounded value and never the unrounded
 * intermediate. Getting this wrong is not cosmetic - margin reconciliation
 * compares these rounded amounts to the paisa.
 *
 * Timestamps are IST wall clock (IST has no DST, so wall clock and instant are
 * interchangeable). The Python mixes clocks - `updated_at` is the database's
 * UTC `CURRENT_TIMESTAMP` while order and trade stamps are IST - and compares
 * each correctly, so one clock here gives the same comparisons.
 */

/** Order vocabulary, spelled exactly as the Python stores and returns it. */
object OrderStatus {
    const val OPEN = "open"
    const val TRIGGER_PENDING = "trigger pending"
    const val COMPLETE = "complete"
    const val CANCELLED = "cancelled"
    const val REJECTED = "rejected"
}

/**
 * Every value in IraAlgo's `sandbox_config` defaults (sandbox_db.init_default_config),
 * plus the SESSION_EXPIRY_TIME environment variable the session boundary uses.
 * Kept as the same strings the Python parses, so a bad value fails the same way.
 */
data class SandboxConfig(
    val startingCapital: BigDecimal = BigDecimal("10000000.00"),
    /** "Never" disables the automatic fund reset; otherwise an English weekday ("Sunday"). */
    val resetDay: String = "Never",
    val resetTime: String = "00:00",
    val nseBseSquareOffTime: String = "15:15",
    val cdsBcdSquareOffTime: String = "16:45",
    val mcxSquareOffTime: String = "23:30",
    val ncdexSquareOffTime: String = "17:00",
    val equityMisLeverage: BigDecimal = BigDecimal("5"),
    val equityCncLeverage: BigDecimal = BigDecimal("1"),
    val futuresLeverage: BigDecimal = BigDecimal("10"),
    val optionBuyLeverage: BigDecimal = BigDecimal("1"),
    val optionSellLeverage: BigDecimal = BigDecimal("1"),
    /** "expiry_day_close" (settle from the exchange close on expiry day) or "next_day". */
    val expirySettlementTiming: String = "expiry_day_close",
    /** "ltp" (settle an expired option at its last LTP) or "zero". */
    val optionExpirySettlement: String = "ltp",
    /** Daily session boundary, the SESSION_EXPIRY_TIME the Python reads from the environment. */
    val sessionExpiryTime: String = "03:00",
) {
    companion object {
        /** Build from `sandbox_config` keys, as the Python's get_config would read them. */
        fun fromConfigMap(values: Map<String, String>): SandboxConfig {
            val d = SandboxConfig()
            fun s(k: String, def: String) = values[k] ?: def
            fun n(k: String, def: BigDecimal) = values[k]?.let { BigDecimal(it.trim()) } ?: def
            return SandboxConfig(
                startingCapital = n("starting_capital", d.startingCapital),
                resetDay = s("reset_day", d.resetDay),
                resetTime = s("reset_time", d.resetTime),
                nseBseSquareOffTime = s("nse_bse_square_off_time", d.nseBseSquareOffTime),
                cdsBcdSquareOffTime = s("cds_bcd_square_off_time", d.cdsBcdSquareOffTime),
                mcxSquareOffTime = s("mcx_square_off_time", d.mcxSquareOffTime),
                ncdexSquareOffTime = s("ncdex_square_off_time", d.ncdexSquareOffTime),
                equityMisLeverage = n("equity_mis_leverage", d.equityMisLeverage),
                equityCncLeverage = n("equity_cnc_leverage", d.equityCncLeverage),
                futuresLeverage = n("futures_leverage", d.futuresLeverage),
                optionBuyLeverage = n("option_buy_leverage", d.optionBuyLeverage),
                optionSellLeverage = n("option_sell_leverage", d.optionSellLeverage),
                expirySettlementTiming = s("expiry_settlement_timing", d.expirySettlementTiming),
                optionExpirySettlement = s("option_expiry_settlement", d.optionExpirySettlement),
                sessionExpiryTime = s("session_expiry_time", d.sessionExpiryTime),
            )
        }
    }

    /**
     * MIS square-off time per exchange (squareoff_manager.SquareOffManager).
     * Exchanges missing here (NCO, CRYPTO, the index segments) are never
     * squared off and never refuse MIS orders after hours.
     */
    val squareOffTimes: Map<String, LocalTime> by lazy {
        val nse = parseSquareOff(nseBseSquareOffTime)
        val cds = parseSquareOff(cdsBcdSquareOffTime)
        linkedMapOf(
            "NSE" to nse, "BSE" to nse, "NFO" to nse, "BFO" to nse,
            "CDS" to cds, "BCD" to cds,
            "MCX" to parseSquareOff(mcxSquareOffTime),
            "NCDEX" to parseSquareOff(ncdexSquareOffTime),
        )
    }

    private fun parseSquareOff(text: String): LocalTime =
        SandboxRules.parseHhMm(text) ?: LocalTime.of(15, 15)
}

/**
 * What the symbol master says about an instrument. The Python classifies
 * option/future by the symbol's suffix and exchange (utils/symbol_utils), not
 * by [instrumentType]; [expiry] is only the fallback when the expiry cannot be
 * parsed from the symbol, exactly as get_contract_expiry does.
 */
data class Instrument(
    val symbol: String,
    val exchange: String,
    val instrumentType: String = "",
    val lotSize: Int = 1,
    val tickSize: Double = 0.05,
    val expiry: LocalDate? = null,
    val strike: Double? = null,
    /** Multiplier on P&L (0.01 for a crypto perpetual); 1 for every Indian contract. */
    val contractValue: Double = 1.0,
)

/** get_symbol_info: null means "Symbol X not found on Y". */
fun interface InstrumentMaster {
    fun lookup(symbol: String, exchange: String): Instrument?

    companion object {
        fun of(instruments: Collection<Instrument>): InstrumentMaster {
            val byKey = instruments.associateBy { it.symbol to it.exchange }
            return InstrumentMaster { s, e -> byKey[s to e] }
        }
    }
}

/**
 * A quote as the Python's quotes service returns it. Zero means "not
 * available": bid/ask 0 falls back to LTP for a MARKET fill, and high/low 0
 * disables the stale-quote check.
 */
data class Quote(
    val ltp: Double,
    val bid: Double = 0.0,
    val ask: Double = 0.0,
    val high: Double = 0.0,
    val low: Double = 0.0,
    val open: Double = 0.0,
    val prevClose: Double = 0.0,
    val volume: Long = 0,
)

/**
 * placeorder's payload. Nullable because the Python validates presence first
 * ("Missing required field: quantity") and treats 0 and "" as missing, and
 * upper-cases action/exchange/price type/product but never the symbol.
 */
data class OrderRequest(
    val symbol: String?,
    val exchange: String?,
    val action: String?,
    val quantity: Int?,
    val priceType: String? = "MARKET",
    val product: String? = "MIS",
    val price: Double? = null,
    val triggerPrice: Double? = null,
    val strategy: String = "",
)

/** modify_order's new_data: only the keys that are present are applied. */
data class OrderChange(
    val quantity: Int? = null,
    val price: Double? = null,
    val triggerPrice: Double? = null,
)

data class Funds(
    val totalCapital: BigDecimal,
    val availableBalance: BigDecimal,
    val usedMargin: BigDecimal,
    /** All-time realized P&L. */
    val realizedPnl: BigDecimal,
    /** Realized today; zeroed at the session boundary. */
    val todayRealizedPnl: BigDecimal,
    val unrealizedPnl: BigDecimal,
    val totalPnl: BigDecimal,
    val lastResetDate: LocalDateTime,
    val resetCount: Int,
    val updatedAt: LocalDateTime,
)

data class Order(
    val orderId: String,
    val strategy: String?,
    val symbol: String,
    val exchange: String,
    val action: String,
    val quantity: Int,
    val price: BigDecimal?,
    val triggerPrice: BigDecimal?,
    val priceType: String,
    val product: String,
    val status: String,
    val averagePrice: BigDecimal?,
    val filledQuantity: Int,
    val pendingQuantity: Int,
    val rejectionReason: String?,
    /** Exactly what was debited at placement; this, not a recomputation, is released or moved to the position. */
    val marginBlocked: BigDecimal,
    val orderTimestamp: LocalDateTime,
    val updateTimestamp: LocalDateTime,
)

data class Trade(
    val tradeId: String,
    val orderId: String,
    val symbol: String,
    val exchange: String,
    val action: String,
    val quantity: Int,
    val price: BigDecimal,
    val product: String,
    val strategy: String?,
    val timestamp: LocalDateTime,
)

/**
 * One row per (symbol, exchange, product), signed quantity, kept at zero
 * quantity after a close so the day's realized P&L still shows. [updatedAt]
 * follows the Python's `onupdate` - it moves only when a stored value actually
 * changes - because the session filters and the catch-up read it.
 */
data class Position(
    val symbol: String,
    val exchange: String,
    val product: String,
    val quantity: Int,
    val averagePrice: BigDecimal,
    val ltp: BigDecimal?,
    val pnl: BigDecimal,
    val pnlPercent: BigDecimal,
    val accumulatedRealizedPnl: BigDecimal,
    val todayRealizedPnl: BigDecimal,
    val marginBlocked: BigDecimal,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)

/** A T+1-settled CNC holding. */
data class Holding(
    val symbol: String,
    val exchange: String,
    val quantity: Int,
    val averagePrice: BigDecimal,
    val ltp: BigDecimal?,
    val pnl: BigDecimal,
    val pnlPercent: BigDecimal,
    val settlementDate: LocalDate,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)

/**
 * The whole account, one user. Immutable: every [Sandbox] call takes a state
 * and returns the next one, so the app can persist it (see [SandboxJson]) and
 * nothing here ever reads a clock or the network.
 */
data class SandboxState(
    val funds: Funds,
    val orders: List<Order> = emptyList(),
    val trades: List<Trade> = emptyList(),
    val positions: List<Position> = emptyList(),
    val holdings: List<Holding> = emptyList(),
    /** Sequence behind the order and trade ids; the Python's are random, these are replayable. */
    val orderSeq: Long = 0,
    val tradeSeq: Long = 0,
)

/** What happened during a call, for the app's UI and notifications (the Python's event bus). */
sealed interface SandboxEvent {
    data class OrderUpdate(val orderId: String, val status: String, val rejectionReason: String = "") : SandboxEvent
    data class Fill(
        val orderId: String, val tradeId: String, val symbol: String, val exchange: String,
        val action: String, val quantity: Int, val price: Double, val product: String,
    ) : SandboxEvent
    /** reconcile_margin corrected used_margin to the sum of position margins. */
    data class MarginReconciled(val released: BigDecimal) : SandboxEvent
    data class ExpirySettled(val symbol: String, val exchange: String, val product: String, val price: BigDecimal, val pnl: BigDecimal) : SandboxEvent
    data class SquareOff(val symbol: String, val exchange: String, val product: String, val result: OrderResult) : SandboxEvent
    data class T1Settled(val positions: Int) : SandboxEvent
    data class FundsReset(val resetCount: Int) : SandboxEvent
}

/** A call's next state, its answer, and what happened on the way. */
data class Outcome<T>(val state: SandboxState, val result: T, val events: List<SandboxEvent> = emptyList())

/**
 * The response of place/modify/cancel/closePosition. [httpStatus] is the code
 * the Python returns (200, 400 validation/refusal, 404 unknown order).
 */
data class OrderResult(
    val ok: Boolean,
    val httpStatus: Int,
    val orderId: String? = null,
    val message: String? = null,
)

/** get_funds. */
data class FundsView(
    val availableCash: Double,
    val collateral: Double,
    val m2mUnrealized: Double,
    val m2mRealized: Double,
    val totalRealizedPnl: Double,
    val todayRealizedPnl: Double,
    val utilisedDebits: Double,
    val grossExposure: Double,
    val totalPnl: Double,
    val lastReset: String,
    val resetCount: Int,
)

/** One row of get_open_positions. A closed row shows average 0 and pnl = today's realized, as Zerodha does. */
data class PositionRow(
    val symbol: String,
    val exchange: String,
    val product: String,
    val quantity: Int,
    val averagePrice: Double,
    val ltp: Double,
    val pnl: Double,
    val pnlPercent: Double,
    val unrealizedPnl: Double,
    val todayRealizedPnl: Double,
    val totalPnlToday: Double,
    /** The contract-value multiplier; the Python names it lot_size. */
    val lotSize: Double,
)

data class PositionBook(
    val positions: List<PositionRow>,
    val totalPnl: Double,
    val totalUnrealizedPnl: Double,
    val totalTodayRealizedPnl: Double,
    val totalPnlToday: Double,
)

data class OrderRow(
    val orderId: String,
    val symbol: String,
    val exchange: String,
    val action: String,
    val quantity: Int,
    val price: Double,
    val triggerPrice: Double,
    val priceType: String,
    val product: String,
    val status: String,
    val averagePrice: Double,
    val filledQuantity: Int,
    val pendingQuantity: Int,
    val rejectionReason: String,
    val timestamp: String,
    val strategy: String,
)

data class OrderStatistics(
    val totalBuyOrders: Int,
    val totalSellOrders: Int,
    val totalCompletedOrders: Int,
    val totalOpenOrders: Int,
    val totalRejectedOrders: Int,
    val totalTriggerPendingOrders: Int,
)

data class OrderBook(val orders: List<OrderRow>, val statistics: OrderStatistics)

data class TradeRow(
    val tradeId: String,
    val orderId: String,
    val symbol: String,
    val exchange: String,
    val action: String,
    val quantity: Int,
    val averagePrice: Double,
    val price: Double,
    val tradeValue: Double,
    val product: String,
    val strategy: String,
    val timestamp: String,
)

data class HoldingRow(
    val symbol: String,
    val exchange: String,
    val product: String,
    val quantity: Int,
    val averagePrice: Double,
    val ltp: Double,
    val pnl: Double,
    val pnlPercent: Double,
    val currentValue: Double,
    val settlementDate: String,
)

data class HoldingsBook(
    val holdings: List<HoldingRow>,
    val totalHoldingValue: Double,
    val totalInvValue: Double,
    val totalProfitAndLoss: Double,
    val totalPnlPercentage: Double,
)

/** Converts the caller's instant to the IST wall clock every rule is written in. */
internal fun ZonedDateTime.istWall(): LocalDateTime = withZoneSameInstant(SandboxRules.IST).toLocalDateTime()
