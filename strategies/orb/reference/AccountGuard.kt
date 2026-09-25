package com.iraalgo.app.rules

import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.abs

data class Intent(val symbol: String, val action: String, val qty: Int, val price: Double?)

/** The account as it stands. [openPositions]: net quantity per symbol, flat symbols absent. */
data class AccountState(
    val openPositions: Map<String, Int>,
    val tradesToday: Int,
    val totalPnl: Double,
    val startingCapital: Double,
    val equity: Double,
    val peakEquity: Double,
    val minutesToSquareOff: Int?,
)

/** Zero means "not configured" for money limits; for the two counts it genuinely means none. */
data class Limits(
    val maxConcurrentPositions: Int,
    val maxTradesToday: Int,
    val maxDailyLoss: Double,
    val maxDrawdownPct: Double,
    val maxOrderValue: Double,
    val maxSymbolExposure: Double,
    val entryCutoffMinutes: Int,
    val tradingEnabled: Boolean = true,
)

data class Verdict(val allowed: Boolean, val reason: String = "")

/**
 * Port of services/risk/account_guard.check_account_order, in its order: the
 * kill switch wins over everything, a risk-reducing order passes every limit,
 * and the entry cutoff answers last so it never masks a limit that says
 * something about the account's condition.
 */
object AccountGuard {
    private val ALLOWED = Verdict(true)
    private val MC = MathContext.DECIMAL128
    private fun d(x: Double) = BigDecimal(x.toString())

    fun check(intent: Intent, state: AccountState, limits: Limits, killSwitch: Boolean): Verdict {
        if (killSwitch) return Verdict(false, "kill_switch")
        if (!limits.tradingEnabled) return Verdict(false, "trading_disabled")
        if (reducibleQuantity(intent, state) == intent.qty) return ALLOWED
        unboundedLoss(intent, state)?.let { return it }
        return exposureLimits(intent, state, limits)
    }

    /** Units of this order that close existing exposure (a SELL against a long, a BUY against a short). */
    fun reducibleQuantity(intent: Intent, state: AccountState): Int {
        val net = state.openPositions[intent.symbol] ?: 0
        if (net == 0) return 0
        val closing = (net > 0 && intent.action == "SELL") || (net < 0 && intent.action == "BUY")
        return if (closing) minOf(intent.qty, abs(net)) else 0
    }

    // Stricter than Python by one case: a SELL covered by a protective long option
    // (a spread) is refused here too. The arms only ever buy options.
    private fun unboundedLoss(intent: Intent, state: AccountState): Verdict? {
        if (intent.action != "SELL" || !(intent.symbol.endsWith("CE") || intent.symbol.endsWith("PE"))) return null
        val openingShort = intent.qty - maxOf(state.openPositions[intent.symbol] ?: 0, 0)
        return if (openingShort > 0) Verdict(false, "unbounded_loss") else null
    }

    private fun exposureLimits(intent: Intent, state: AccountState, limits: Limits): Verdict {
        val loss = d(state.totalPnl).negate()
        if (limits.maxDailyLoss > 0 && loss >= d(limits.maxDailyLoss)) return Verdict(false, "daily_loss_limit")

        val pct = d(limits.maxDrawdownPct)
        if (limits.maxDrawdownPct > 0 && state.startingCapital > 0) {
            val drawdown = loss.divide(d(state.startingCapital), MC).multiply(BigDecimal(100))
            if (drawdown >= pct) return Verdict(false, "max_drawdown")
        }
        if (limits.maxDrawdownPct > 0 && state.peakEquity > 0 && state.equity > 0) {
            val peak = d(state.peakEquity)
            val fromPeak = peak.subtract(d(state.equity)).divide(peak, MC).multiply(BigDecimal(100))
            if (fromPeak >= pct) return Verdict(false, "max_drawdown")
        }

        val net = state.openPositions[intent.symbol] ?: 0
        if (net == 0 && state.openPositions.size >= limits.maxConcurrentPositions) {
            return Verdict(false, "max_concurrent_positions")
        }
        if (state.tradesToday >= limits.maxTradesToday) return Verdict(false, "max_trades_today")

        val needsPrice = limits.maxOrderValue > 0 || limits.maxSymbolExposure > 0
        if (needsPrice && intent.price == null) return Verdict(false, "no_reference_price")
        if (intent.price != null) {
            val price = d(intent.price)
            val orderValue = price.multiply(BigDecimal(intent.qty))
            if (limits.maxOrderValue > 0 && orderValue > d(limits.maxOrderValue)) return Verdict(false, "max_order_value")
            if (limits.maxSymbolExposure > 0) {
                val held = BigDecimal(abs(net)).multiply(price)
                if (held.add(orderValue) > d(limits.maxSymbolExposure)) return Verdict(false, "max_symbol_exposure")
            }
        }

        val minutes = state.minutesToSquareOff
        if (limits.entryCutoffMinutes > 0 && minutes != null && minutes <= limits.entryCutoffMinutes) {
            return Verdict(false, "past_entry_cutoff")
        }
        return ALLOWED
    }
}
