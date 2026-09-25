package com.optionslab.engine.sandbox

import com.optionslab.engine.sandbox.SandboxRules.LOT_EXCHANGES
import com.optionslab.engine.sandbox.SandboxRules.MARKET_OPEN
import com.optionslab.engine.sandbox.SandboxRules.contractExpiry
import com.optionslab.engine.sandbox.SandboxRules.div
import com.optionslab.engine.sandbox.SandboxRules.isContractExpiredNow
import com.optionslab.engine.sandbox.SandboxRules.isFuture
import com.optionslab.engine.sandbox.SandboxRules.isOption
import com.optionslab.engine.sandbox.SandboxRules.pyDec
import com.optionslab.engine.sandbox.SandboxRules.pyStr
import com.optionslab.engine.sandbox.SandboxRules.quoteLooksStale
import com.optionslab.engine.sandbox.SandboxRules.store
import com.optionslab.engine.sandbox.SandboxRules.ts
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * IraAlgo's Sandbox (paper trading) as a deterministic state machine. Port of
 * sandbox/order_manager.py, execution_engine.py, fund_manager.py,
 * position_manager.py, holdings_manager.py, squareoff_manager.py and
 * catch_up_processor.py.
 *
 * Nothing here reads a clock, a quote feed or a database: the caller passes
 * the current instant (any zone; rules are evaluated in IST) and the quotes,
 * and gets back the next [SandboxState]. The Python's scheduled jobs become
 * calls the app makes on its own schedule:
 *
 *  - every tick / poll: [onQuotes] (execution_engine.check_and_execute_pending_orders);
 *  - every minute: [squareOffDue] (squareoff_manager.check_and_square_off, which
 *    also cancels orders on expired contracts and settles expired positions);
 *  - 00:00 IST: [settleT1] (T+1 CNC -> holdings);
 *  - session expiry (03:00): [resetDailyPnl];
 *  - the configured reset day: [resetFunds];
 *  - app start after downtime: [catchUp].
 *
 * The semantics are the Python's, including the parts that look wrong, because
 * this app must show what IraAlgo shows. The notable ones are marked
 * "Python parity" where they occur: margin reconciliation after every fill
 * counts positions only, so it frees the margin of any order still resting;
 * a reversal leaves the new position with less margin than the order blocked;
 * realized P&L on a close reaches the funds only when the position had margin
 * to release; and cancelling a reducing order that blocked nothing releases a
 * recomputed margin anyway.
 */
class Sandbox(
    val config: SandboxConfig = SandboxConfig(),
    private val instruments: InstrumentMaster,
) {
    companion object {
        /** Quote map key, Kite style: "NSE:RELIANCE". */
        fun key(symbol: String, exchange: String): String = "$exchange:$symbol"

        private val ZERO2: BigDecimal = BigDecimal("0.00")
        private val ZERO4: BigDecimal = BigDecimal("0.0000")
        private val HUNDRED = BigDecimal("100")
        private val PENDING = setOf(OrderStatus.OPEN, OrderStatus.TRIGGER_PENDING)
    }

    /** initialize_funds: a fresh account holding the configured starting capital. */
    fun newState(now: ZonedDateTime): SandboxState {
        val t = now.istWall()
        val cap = store(config.startingCapital)
        return SandboxState(
            Funds(cap, cap, ZERO2, ZERO2, ZERO2, ZERO2, ZERO2, t, 0, t.truncatedTo(ChronoUnit.SECONDS)),
        )
    }

    // ---- orders -------------------------------------------------------------

    /**
     * place_order. [quote] is the quote the app just fetched (the Python's
     * prefetched_quote); without one a MARKET order is priced from the
     * position's last LTP and waits for [onQuotes] to fill, and with neither
     * it is refused. A MARKET order, a marketable LIMIT, or an SL/SL-M whose
     * trigger is already met fills immediately unless the quote looks stale.
     */
    fun place(state: SandboxState, request: OrderRequest, quote: Quote?, now: ZonedDateTime): Outcome<OrderResult> =
        tx(state, now) { placeOrder(request, quote) }

    /**
     * modify_order: quantity (lot-checked), price (LIMIT/SL only) and trigger
     * (SL/SL-M only) of an order still open or trigger pending. Python parity:
     * the blocked margin is not recomputed.
     */
    fun modify(state: SandboxState, orderId: String, change: OrderChange, now: ZonedDateTime): Outcome<OrderResult> =
        tx(state, now) { modifyOrder(orderId, change) }

    /**
     * cancel_order: releases the margin the order blocked. [quote] is used only
     * by the Python's fallback for an order that blocked nothing and has no
     * price (an SL-M), where it recomputes a margin to release from the LTP.
     */
    fun cancel(state: SandboxState, orderId: String, now: ZonedDateTime, quote: Quote? = null): Outcome<OrderResult> =
        tx(state, now) { cancelOrder(orderId, quote) }

    /** close_position: a MARKET order for the opposite of the position, strategy AUTO_SQUARE_OFF. */
    fun closePosition(state: SandboxState, symbol: String, exchange: String, product: String, quote: Quote?, now: ZonedDateTime): Outcome<OrderResult> =
        tx(state, now) { closePositionTx(symbol, exchange, product, quote) }

    /**
     * One pass of the execution engine over every open and trigger-pending
     * order, each against the quote for its symbol ([key]); orders without a
     * quote are left alone.
     */
    fun onQuotes(state: SandboxState, quotes: Map<String, Quote>, now: ZonedDateTime): Outcome<Unit> =
        tx(state, now) {
            for (id in pendingOrders().map { it.orderId }) {
                val o = order(id) ?: continue
                val q = quotes[key(o.symbol, o.exchange)] ?: continue
                processOrder(id, q)
            }
        }

    /**
     * check_and_square_off: past each exchange's square-off time, cancel open
     * MIS orders; cancel any open order on an expired contract; settle expired
     * F&O positions; then close every open MIS position at market. Quotes
     * price the closing orders.
     */
    fun squareOffDue(state: SandboxState, now: ZonedDateTime, quotes: Map<String, Quote>): Outcome<Unit> =
        tx(state, now) { checkAndSquareOff(quotes) }

    /**
     * cleanup_expired_contracts: settle open F&O positions whose contract has
     * expired (per expiry_settlement_timing) at the settlement price
     * (option_expiry_settlement: last LTP or zero; futures at LTP, else average).
     * Keep positions marked to market via [positionBook] so the LTP is current.
     */
    fun settleExpiries(state: SandboxState, now: ZonedDateTime): Outcome<Unit> = tx(state, now) { cleanupExpiredContracts() }

    /** process_t1_settlement: CNC positions opened before today move into holdings. Returns how many settled. */
    fun settleT1(state: SandboxState, now: ZonedDateTime): Outcome<Int> = tx(state, now) { t1Settlement() }

    /** The session-boundary job: zero today's realized P&L on the funds and every position. */
    fun resetDailyPnl(state: SandboxState, now: ZonedDateTime): Outcome<Unit> = tx(state, now) {
        editFundsBulk { today = BigDecimal.ZERO }
        for (i in positions.indices) positions[i] = positions[i].copy(todayRealizedPnl = ZERO2, updatedAt = dbNow)
    }

    /** reset_all_user_funds: back to starting capital, positions and holdings deleted. */
    fun resetFunds(state: SandboxState, now: ZonedDateTime): Outcome<Unit> = tx(state, now) { resetFundsTx() }

    /**
     * run_catch_up_tasks for a restart after downtime: settle MIS positions
     * left over from an earlier session (P&L to all-time only, not today), run
     * a missed T+1, and zero stale "today" P&L. GTT catch-up and the daily
     * snapshot backfill are not part of this port.
     */
    fun catchUp(state: SandboxState, now: ZonedDateTime): Outcome<Unit> = tx(state, now) {
        catchUpMisSquareOff()
        // Python parity: this gate compares the IST created_at with IST midnight
        // converted to UTC (18:30 the day before), so a CNC position opened
        // after 18:30 yesterday does not trigger the catch-up T+1 by itself.
        val gate = now.toLocalDate().atStartOfDay().minusHours(5).minusMinutes(30)
        if (positions.any { it.product == "CNC" && it.createdAt < gate }) t1Settlement()
        catchUpDailyPnlReset()
    }

    /** reconcile_margin(auto_fix=True): set used margin to the sum of open positions' margins. */
    fun reconcileMargin(state: SandboxState, now: ZonedDateTime): Outcome<BigDecimal> =
        tx(state, now) { reconcile() ?: BigDecimal.ZERO }

    // ---- views --------------------------------------------------------------

    /** get_funds. May apply the weekly reset first, so it returns a state. */
    fun funds(state: SandboxState, now: ZonedDateTime): Outcome<FundsView> = tx(state, now) {
        checkAndResetFunds()
        val f = funds
        FundsView(
            availableCash = f.availableBalance.toDouble(), collateral = 0.0,
            m2mUnrealized = f.unrealizedPnl.toDouble(), m2mRealized = f.todayRealizedPnl.toDouble(),
            totalRealizedPnl = f.realizedPnl.toDouble(), todayRealizedPnl = f.todayRealizedPnl.toDouble(),
            utilisedDebits = f.usedMargin.toDouble(), grossExposure = f.usedMargin.toDouble(),
            totalPnl = f.totalPnl.toDouble(), lastReset = ts(f.lastResetDate), resetCount = f.resetCount,
        )
    }

    /**
     * get_open_positions(update_mtm=True): the current session's positions
     * (plus NRML carried forward), expired contracts settled on the way, open
     * ones marked to [quotes], and the funds' unrealized P&L updated.
     */
    fun positionBook(state: SandboxState, now: ZonedDateTime, quotes: Map<String, Quote> = emptyMap()): Outcome<PositionBook> =
        tx(state, now) { openPositions(quotes) }

    /** get_orderbook: this session's orders, newest first, with the broker-style statistics. */
    fun orderBook(state: SandboxState, now: ZonedDateTime): OrderBook {
        val start = SandboxRules.lastSessionExpiry(config.sessionExpiryTime, now.istWall())
        val shown = state.orders.filter { !it.orderTimestamp.isBefore(start) }.sortedByDescending { it.orderTimestamp }
        return OrderBook(shown.map(::orderRow), OrderStatistics(
            totalBuyOrders = shown.count { it.action == "BUY" },
            totalSellOrders = shown.count { it.action == "SELL" },
            totalCompletedOrders = shown.count { it.status == OrderStatus.COMPLETE },
            totalOpenOrders = shown.count { it.status == OrderStatus.OPEN },
            totalRejectedOrders = shown.count { it.status == OrderStatus.REJECTED },
            totalTriggerPendingOrders = shown.count { it.status == OrderStatus.TRIGGER_PENDING },
        ))
    }

    /** get_order_status, any session. */
    fun orderStatus(state: SandboxState, orderId: String): OrderRow? = state.orders.firstOrNull { it.orderId == orderId }?.let(::orderRow)

    /** get_tradebook: this session's fills, newest first. */
    fun tradeBook(state: SandboxState, now: ZonedDateTime): List<TradeRow> {
        val start = SandboxRules.lastSessionExpiry(config.sessionExpiryTime, now.istWall())
        return state.trades.filter { !it.timestamp.isBefore(start) }.sortedByDescending { it.timestamp }.map { t ->
            val price = t.price.toDouble()
            TradeRow(
                tradeId = t.tradeId, orderId = t.orderId, symbol = t.symbol, exchange = t.exchange,
                action = t.action, quantity = t.quantity, averagePrice = SandboxRules.round2(price),
                price = SandboxRules.round2(price), tradeValue = SandboxRules.round2(price * kotlin.math.abs(t.quantity)),
                product = t.product, strategy = t.strategy ?: "", timestamp = ts(t.timestamp),
                charges = t.charges.toDouble(),
            )
        }
    }

    /** get_holdings(update_mtm=True). */
    fun holdings(state: SandboxState, now: ZonedDateTime, quotes: Map<String, Quote> = emptyMap()): Outcome<HoldingsBook> =
        tx(state, now) { holdingsBookTx(quotes) }

    private fun orderRow(o: Order) = OrderRow(
        orderId = o.orderId, symbol = o.symbol, exchange = o.exchange, action = o.action, quantity = o.quantity,
        price = o.price.orZero(), triggerPrice = o.triggerPrice.orZero(), priceType = o.priceType,
        product = o.product, status = o.status, averagePrice = o.averagePrice.orZero(),
        filledQuantity = o.filledQuantity, pendingQuantity = o.pendingQuantity,
        rejectionReason = o.rejectionReason ?: "", timestamp = ts(o.orderTimestamp), strategy = o.strategy ?: "",
    )

    private fun BigDecimal?.orZero(): Double = if (this == null || signum() == 0) 0.0 else toDouble()

    // ---- the working copy ---------------------------------------------------

    private fun <T> tx(state: SandboxState, now: ZonedDateTime, body: Ledger.() -> T): Outcome<T> {
        val l = Ledger(state, now)
        val r = l.body()
        return Outcome(l.snapshot(), r, l.events.toList())
    }

    /**
     * One call's mutable copy of the state. The Python mutates rows and
     * commits; this mutates lists and freezes them into the next state. Each
     * write stores the value rounded as the database would, and moves
     * `updated_at` only when a value changed, as SQLAlchemy's `onupdate` does.
     */
    private inner class Ledger(s: SandboxState, nowZ: ZonedDateTime) {
        val now: LocalDateTime = nowZ.istWall()
        /** The database clock: `CURRENT_TIMESTAMP` has whole seconds. */
        val dbNow: LocalDateTime = now.truncatedTo(ChronoUnit.SECONDS)
        var funds = s.funds
        val orders = s.orders.toMutableList()
        val trades = s.trades.toMutableList()
        val positions = s.positions.toMutableList()
        val holdings = s.holdings.toMutableList()
        var orderSeq = s.orderSeq
        var tradeSeq = s.tradeSeq
        val events = mutableListOf<SandboxEvent>()

        fun snapshot() = SandboxState(funds, orders.toList(), trades.toList(), positions.toList(), holdings.toList(), orderSeq, tradeSeq)

        fun order(id: String): Order? = orders.firstOrNull { it.orderId == id }

        /** Open and trigger-pending orders in the Python's query order: by status via its index, then placement. */
        fun pendingOrders(): List<Order> = orders.filter { it.status in PENDING }.sortedBy { it.status }
        fun putOrder(o: Order) { orders[orders.indexOfFirst { it.orderId == o.orderId }] = o }
        fun positionIndex(symbol: String, exchange: String, product: String) =
            positions.indexOfFirst { it.symbol == symbol && it.exchange == exchange && it.product == product }
        fun position(symbol: String, exchange: String, product: String) =
            positionIndex(symbol, exchange, product).let { if (it < 0) null else positions[it] }

        // ---- funds (fund_manager.FundManager) --------------------------------

        inner class FundsDraft(f: Funds) {
            var totalCapital = f.totalCapital; var available = f.availableBalance; var used = f.usedMargin
            var realized = f.realizedPnl; var today = f.todayRealizedPnl; var unrealized = f.unrealizedPnl
            var total = f.totalPnl; var lastReset = f.lastResetDate; var resetCount = f.resetCount
        }

        fun editFunds(block: FundsDraft.() -> Unit) {
            val o = funds
            val d = FundsDraft(o).apply(block)
            val changed = listOf(
                d.totalCapital to o.totalCapital, d.available to o.availableBalance, d.used to o.usedMargin,
                d.realized to o.realizedPnl, d.today to o.todayRealizedPnl, d.unrealized to o.unrealizedPnl, d.total to o.totalPnl,
            ).any { (a, b) -> a.compareTo(b) != 0 } || d.lastReset != o.lastResetDate || d.resetCount != o.resetCount
            funds = Funds(
                store(d.totalCapital), store(d.available), store(d.used), store(d.realized), store(d.today),
                store(d.unrealized), store(d.total), d.lastReset, d.resetCount, if (changed) dbNow else o.updatedAt,
            )
        }

        /** A bulk `query.update()`: the row's updated_at moves whatever the values were. */
        fun editFundsBulk(block: FundsDraft.() -> Unit) {
            editFunds(block)
            funds = funds.copy(updatedAt = dbNow)
        }

        fun checkMargin(required: BigDecimal): String? {
            if (funds.availableBalance >= required) return null
            val shortage = required - funds.availableBalance
            return "Insufficient funds. Required: ₹${pyStr(required)}, Available: ₹${pyStr(funds.availableBalance)}, Shortage: ₹${pyStr(shortage)}"
        }

        /** block_margin: positive amounts only, never past the available balance. */
        fun blockMargin(amount: BigDecimal): String? {
            if (amount.signum() <= 0) return "Block amount must be positive, got ${pyStr(amount)}"
            if (funds.availableBalance < amount) {
                return "Insufficient funds. Required: ₹${pyStr(amount)}, Available: ₹${pyStr(funds.availableBalance)}"
            }
            editFunds { available -= amount; used += amount }
            return null
        }

        /**
         * release_margin: refuses a negative amount or more than is reserved -
         * an over-release would invent cash - and in that case credits no P&L
         * either. The P&L goes to the balance, all-time and today's realized.
         */
        fun releaseMargin(amount: BigDecimal, realizedPnl: BigDecimal): Boolean {
            if (amount.signum() < 0) return false
            if (amount > funds.usedMargin) return false
            editFunds {
                used -= amount
                available = available + amount + realizedPnl
                realized += realizedPnl
                today += realizedPnl
                total = realized + unrealized
            }
            return true
        }

        /** transfer_margin_to_holdings: T+1 moves the cost out of used margin without crediting cash. */
        fun transferMarginToHoldings(amount: BigDecimal): Boolean {
            if (amount.signum() <= 0 || amount > funds.usedMargin) return false
            editFunds { used -= amount }
            return true
        }

        fun creditSaleProceeds(amount: BigDecimal): Boolean {
            if (amount.signum() <= 0) return false
            editFunds { available += amount }
            return true
        }

        fun updateUnrealizedPnl(u: BigDecimal) = editFunds { unrealized = u; total = realized + u }

        /** calculate_margin_required: |qty| x price / leverage, or why not. */
        fun marginRequired(symbol: String, exchange: String, product: String, quantity: Int, price: BigDecimal, action: String?): Pair<BigDecimal?, String> {
            instruments.lookup(symbol, exchange) ?: return null to "Symbol not found"
            val tradeValue = BigDecimal(kotlin.math.abs(quantity)).multiply(price)
            return div(tradeValue, SandboxRules.leverage(config, symbol, exchange, product, action)) to "Margin calculated successfully"
        }

        /**
         * reconcile_margin: the correction the Python runs after every fill
         * whose position margins do not add up to used margin. Python parity:
         * it counts positions only (GTTs are not ported), so a resting order's
         * blocked margin is freed here - and freed again if it is cancelled.
         */
        fun reconcile(): BigDecimal? {
            val total = positions.filter { it.quantity != 0 }.fold(BigDecimal.ZERO) { a, p -> a + p.marginBlocked }
            val discrepancy = funds.usedMargin - total
            if (discrepancy.signum() == 0) return null
            editFunds { used = total; available += discrepancy }
            events += SandboxEvent.MarginReconciled(discrepancy)
            return discrepancy
        }

        fun validateMarginConsistency() {
            val total = positions.filter { it.quantity != 0 }.fold(BigDecimal.ZERO) { a, p -> a + p.marginBlocked }
            if (funds.usedMargin.compareTo(total) != 0) reconcile()
        }

        fun checkAndResetFunds() {
            if (config.resetDay.lowercase() == "never") return
            val dayName = now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
            if (dayName != config.resetDay) return
            val t = SandboxRules.parseHhMm(config.resetTime) ?: return
            val resetToday = now.toLocalDate().atTime(t)
            if (!now.isBefore(resetToday) && funds.lastResetDate.isBefore(resetToday)) resetFundsTx()
        }

        fun resetFundsTx() {
            val cap = config.startingCapital
            editFunds {
                totalCapital = cap; available = cap; used = BigDecimal.ZERO; realized = BigDecimal.ZERO
                today = BigDecimal.ZERO; unrealized = BigDecimal.ZERO; total = BigDecimal.ZERO
                lastReset = now; resetCount += 1
            }
            positions.clear()
            holdings.clear()
            events += SandboxEvent.FundsReset(funds.resetCount)
        }

        // ---- positions --------------------------------------------------------

        inner class PositionDraft(p: Position) {
            var quantity = p.quantity; var averagePrice = p.averagePrice; var ltp = p.ltp; var pnl = p.pnl
            var pnlPercent = p.pnlPercent; var accumulated = p.accumulatedRealizedPnl
            var today = p.todayRealizedPnl; var margin = p.marginBlocked
        }

        fun editPosition(i: Int, block: PositionDraft.() -> Unit) {
            val o = positions[i]
            val d = PositionDraft(o).apply(block)
            fun ne(a: BigDecimal?, b: BigDecimal?) = if (a == null || b == null) a !== b else a.compareTo(b) != 0
            val changed = d.quantity != o.quantity || ne(d.averagePrice, o.averagePrice) || ne(d.ltp, o.ltp) ||
                ne(d.pnl, o.pnl) || ne(d.pnlPercent, o.pnlPercent) || ne(d.accumulated, o.accumulatedRealizedPnl) ||
                ne(d.today, o.todayRealizedPnl) || ne(d.margin, o.marginBlocked)
            positions[i] = o.copy(
                quantity = d.quantity, averagePrice = store(d.averagePrice), ltp = d.ltp?.let { store(it) },
                pnl = store(d.pnl), pnlPercent = store(d.pnlPercent, 4), accumulatedRealizedPnl = store(d.accumulated),
                todayRealizedPnl = store(d.today), marginBlocked = store(d.margin),
                updatedAt = if (changed) dbNow else o.updatedAt,
            )
        }

        fun contractValue(symbol: String, exchange: String): BigDecimal =
            pyDec(instruments.lookup(symbol, exchange)?.contractValue?.takeIf { it != 0.0 } ?: 1.0)

        /** _calculate_realized_pnl. */
        fun realizedPnl(oldQuantity: Int, avg: BigDecimal, closeQuantity: Int, closePrice: BigDecimal, cv: BigDecimal): BigDecimal {
            val q = BigDecimal(closeQuantity)
            return if (oldQuantity > 0) (closePrice - avg) * q * cv else (avg - closePrice) * q * cv
        }

        /**
         * _update_position: net a fill into the (symbol, exchange, product)
         * position. Opening or adding carries the order's blocked margin onto
         * the position; reducing releases it pro rata with the realized P&L;
         * a reversal releases all of it and gives the new side
         * order_margin x excess/order_qty. Python parity: that order margin was
         * already computed for the excess only, so the reversed position holds
         * less than was blocked and the reconciliation below frees the rest.
         */
        fun updatePosition(o: Order, px: BigDecimal) {
            val signed = if (o.action == "BUY") o.quantity else -o.quantity
            val orderMargin = o.marginBlocked
            val i = positionIndex(o.symbol, o.exchange, o.product)
            if (i < 0) {
                positions += Position(
                    symbol = o.symbol, exchange = o.exchange, product = o.product, quantity = signed,
                    averagePrice = store(px), ltp = store(px), pnl = ZERO2, pnlPercent = ZERO4,
                    accumulatedRealizedPnl = ZERO2, todayRealizedPnl = ZERO2, marginBlocked = store(orderMargin),
                    createdAt = now, updatedAt = dbNow,
                )
            } else {
                val p = positions[i]
                val old = p.quantity
                val final = old + signed
                when {
                    old == 0 -> editPosition(i) {
                        quantity = signed; averagePrice = px; ltp = px; pnl = BigDecimal.ZERO
                        pnlPercent = BigDecimal.ZERO; margin = orderMargin
                    }
                    final == 0 -> {
                        val realized = realizedPnl(old, p.averagePrice, kotlin.math.abs(signed), px, contractValue(o.symbol, o.exchange))
                        val release = p.marginBlocked
                        // Python parity: with nothing to release, the P&L never reaches the funds.
                        if (release.signum() > 0) releaseMargin(release, realized)
                        editPosition(i) {
                            accumulated += realized; today += realized; quantity = 0; margin = BigDecimal.ZERO
                            ltp = px; pnl = today; pnlPercent = BigDecimal.ZERO
                        }
                    }
                    (old > 0 && final > old) || (old < 0 && final < old) -> {
                        val totalValue = BigDecimal(kotlin.math.abs(old)) * p.averagePrice + BigDecimal(kotlin.math.abs(signed)) * px
                        val totalQuantity = kotlin.math.abs(old) + kotlin.math.abs(signed)
                        editPosition(i) {
                            quantity = final; averagePrice = div(totalValue, BigDecimal(totalQuantity)); ltp = px
                            margin = p.marginBlocked + orderMargin
                        }
                    }
                    else -> {
                        val reduced = minOf(kotlin.math.abs(old), kotlin.math.abs(signed))
                        val realized = realizedPnl(old, p.averagePrice, reduced, px, contractValue(o.symbol, o.exchange))
                        val current = p.marginBlocked
                        val release = current * div(BigDecimal(reduced), BigDecimal(kotlin.math.abs(old)))
                        if (release.signum() > 0) releaseMargin(release, realized)
                        val remaining = current - release
                        editPosition(i) {
                            accumulated += realized; today += realized
                            if (kotlin.math.abs(signed) > kotlin.math.abs(old)) {
                                val rest = kotlin.math.abs(signed) - kotlin.math.abs(old)
                                quantity = if (o.action == "BUY") rest else -rest
                                averagePrice = px
                                margin = orderMargin * div(BigDecimal(rest), BigDecimal(kotlin.math.abs(signed)))
                            } else {
                                quantity = final; margin = remaining
                            }
                            ltp = px
                        }
                    }
                }
            }
            validateMarginConsistency()
        }

        // ---- order placement (order_manager.OrderManager) ---------------------

        fun err(message: String, code: Int = 400, orderId: String? = null) = OrderResult(false, code, orderId, message)

        fun validate(r: OrderRequest): String? {
            val fields = listOf(
                "symbol" to r.symbol, "exchange" to r.exchange, "action" to r.action,
                "quantity" to r.quantity?.takeIf { it != 0 }?.toString(), "price_type" to r.priceType, "product" to r.product,
            )
            for ((name, v) in fields) if (v.isNullOrEmpty()) return "Missing required field: $name"
            if (r.action!!.uppercase() !in listOf("BUY", "SELL")) return "Invalid action. Must be BUY or SELL"
            val pt = r.priceType!!.uppercase()
            if (pt !in listOf("MARKET", "LIMIT", "SL", "SL-M")) return "Invalid price_type. Must be MARKET, LIMIT, SL, or SL-M"
            val product = r.product!!.uppercase()
            if (product !in listOf("CNC", "NRML", "MIS")) return "Invalid product. Must be CNC, NRML, or MIS"
            val exchange = r.exchange!!.uppercase()
            if (exchange in SandboxRules.EQUITY_EXCHANGES && product == "NRML") {
                return "NRML product not allowed for $exchange equity segment. Use CNC for delivery or MIS for intraday."
            }
            if (exchange in SandboxRules.DERIVATIVE_EXCHANGES && product == "CNC") {
                return "CNC product not allowed for $exchange derivatives segment. Use NRML for carryforward or MIS for intraday."
            }
            if (r.quantity!! <= 0) return "Quantity must be positive"
            if (pt == "LIMIT" || pt == "SL") {
                if (r.price == null || r.price == 0.0) return "${r.priceType} orders require price"
                if (r.price <= 0) return "Price must be positive"
            }
            if (pt == "SL" || pt == "SL-M") {
                if (r.triggerPrice == null || r.triggerPrice == 0.0) return "${r.priceType} orders require trigger_price"
                if (r.triggerPrice <= 0) return "Trigger price must be positive"
            }
            if (exchange !in SandboxRules.VALID_EXCHANGES) return "Invalid exchange. Must be one of ${SandboxRules.VALID_EXCHANGES.joinToString(", ")}"
            return null
        }

        fun nextOrderId(): String {
            orderSeq += 1
            return "%02d%02d%02d%08d".format(now.year % 100, now.monthValue, now.dayOfMonth, orderSeq)
        }

        fun nextTradeId(): String {
            tradeSeq += 1
            return "TRADE-%04d%02d%02d-%02d%02d%02d-%08x".format(
                now.year, now.monthValue, now.dayOfMonth, now.hour, now.minute, now.second, tradeSeq,
            )
        }

        fun placeOrder(r: OrderRequest, prefetched: Quote?): OrderResult {
            validate(r)?.let { return err(it) }
            val symbol = r.symbol!!
            val exchange = r.exchange!!.uppercase()
            val action = r.action!!.uppercase()
            val quantity = r.quantity!!
            val priceType = r.priceType!!.uppercase()
            val product = r.product!!.uppercase()
            // Drop what the price type does not use, so a stale form value is never stored.
            val price = if (priceType == "MARKET" || priceType == "SL-M") null else r.price?.takeIf { it != 0.0 }?.let(::pyDec)
            val trigger = if (priceType == "MARKET" || priceType == "LIMIT") null else r.triggerPrice?.takeIf { it != 0.0 }?.let(::pyDec)

            val inst = instruments.lookup(symbol, exchange) ?: return err("Symbol $symbol not found on $exchange")
            if (exchange in LOT_EXCHANGES) {
                val lot = if (inst.lotSize == 0) 1 else inst.lotSize
                if (quantity % lot != 0) return err("Quantity must be in multiples of lot size $lot")
            }

            // MIS after square-off (or before 09:00) may only reduce an open position.
            if (product == "MIS") {
                val sq = config.squareOffTimes[exchange]
                val t = now.toLocalTime()
                if (sq != null && (!t.isBefore(sq) || t.isBefore(MARKET_OPEN))) {
                    val open = positions.firstOrNull { it.symbol == symbol && it.exchange == exchange && it.product == product && it.quantity != 0 }
                    val reducing = open != null && ((action == "BUY" && open.quantity < 0) || (action == "SELL" && open.quantity > 0))
                    if (!reducing) {
                        return err("MIS orders cannot be placed after square-off time (%02d:%02d IST). Trading resumes at 09:00 AM IST.".format(sq.hour, sq.minute))
                    }
                }
            }

            // CNC sells need shares: the SIGNED day position plus holdings, so the
            // same shares cannot be sold twice before T+1 reduces the holding.
            var cncRejection: String? = null
            if (action == "SELL" && product == "CNC") {
                val posQty = position(symbol, exchange, product)?.quantity ?: 0
                val h = holdings.firstOrNull { it.symbol == symbol && it.exchange == exchange }
                val holdQty = if (h != null && h.quantity > 0) h.quantity else 0
                val available = posQty + holdQty
                cncRejection = when {
                    available <= 0 -> "Cannot sell $symbol in CNC. No positions or holdings available. CNC (delivery) requires existing shares. Use MIS for intraday short selling."
                    quantity > available -> "Cannot sell $quantity shares of $symbol in CNC. Only $available shares available (Position: $posQty, Holdings: $holdQty)"
                    else -> null
                }
            }

            var marginPrice: BigDecimal? = null
            var cached: Quote? = null
            var triggerMet = false
            val quoteLtp = prefetched?.ltp?.takeIf { it != 0.0 && it.isFinite() }?.let(::pyDec)?.takeIf { it.signum() > 0 }
            when (priceType) {
                "MARKET" -> {
                    val fallback = position(symbol, exchange, product)?.ltp?.takeIf { it.signum() > 0 }
                    when {
                        quoteLtp != null -> { marginPrice = quoteLtp; cached = prefetched }
                        fallback != null -> marginPrice = fallback
                        else -> return err("Cannot place MARKET order for $symbol - unable to fetch current price. Please try again later or use LIMIT order with a specific price.")
                    }
                }
                "LIMIT" -> {
                    marginPrice = price
                    if (quoteLtp != null && ((action == "BUY" && quoteLtp <= price) || (action == "SELL" && quoteLtp >= price))) cached = prefetched
                }
                else -> {
                    marginPrice = trigger
                    if (quoteLtp != null && ((action == "BUY" && quoteLtp >= trigger) || (action == "SELL" && quoteLtp <= trigger))) {
                        triggerMet = true
                        if (priceType == "SL-M") cached = prefetched
                        else if ((action == "BUY" && quoteLtp <= price) || (action == "SELL" && quoteLtp >= price)) cached = prefetched
                    }
                }
            }
            if (marginPrice == null || marginPrice.signum() <= 0) {
                return err("Invalid price for margin calculation. Please provide valid price/trigger_price for $priceType order")
            }

            val (required, why) = marginRequired(symbol, exchange, product, quantity, marginPrice, action)
            if (required == null) return err("Unable to calculate margin: $why")

            // Only new exposure needs margin: a reducing order blocks nothing, a
            // reversing one blocks for the excess.
            var toBlock: BigDecimal = required
            val existing = position(symbol, exchange, product)
            if (existing != null && existing.quantity != 0 &&
                ((existing.quantity > 0 && action == "SELL") || (existing.quantity < 0 && action == "BUY"))
            ) {
                val held = kotlin.math.abs(existing.quantity)
                toBlock = if (quantity <= held) BigDecimal.ZERO
                else marginRequired(symbol, exchange, product, quantity - held, marginPrice, action).first!!
            }

            // A CNC sell of owned shares blocks nothing; every other order does.
            val shouldBlock = action == "BUY" || isOption(symbol, exchange) || isFuture(symbol, exchange) || product == "MIS" || product == "NRML"
            if (shouldBlock) {
                if (toBlock.signum() > 0) {
                    checkMargin(toBlock)?.let { return err(it) }
                    blockMargin(toBlock)?.let { return err(it) }
                }
            } else {
                toBlock = BigDecimal.ZERO
            }

            val orderId = nextOrderId()
            if (cncRejection != null) {
                orders += Order(
                    orderId = orderId, strategy = r.strategy, symbol = symbol, exchange = exchange, action = action,
                    quantity = quantity, price = (if (priceType == "MARKET") marginPrice else price)?.let { store(it) },
                    triggerPrice = trigger?.let { store(it) }, priceType = priceType, product = product,
                    status = OrderStatus.REJECTED, averagePrice = null, filledQuantity = 0, pendingQuantity = 0,
                    rejectionReason = cncRejection, marginBlocked = ZERO2, orderTimestamp = now, updateTimestamp = dbNow,
                )
                events += SandboxEvent.OrderUpdate(orderId, OrderStatus.REJECTED, cncRejection)
                return err(cncRejection, orderId = orderId)
            }

            // An SL/SL-M whose trigger has not been touched rests in the stop-loss
            // book ("trigger pending"), not the regular book ("open").
            val status = if ((priceType == "SL" || priceType == "SL-M") && !triggerMet) OrderStatus.TRIGGER_PENDING else OrderStatus.OPEN
            orders += Order(
                orderId = orderId, strategy = r.strategy, symbol = symbol, exchange = exchange, action = action,
                quantity = quantity, price = (if (priceType == "MARKET") marginPrice else price)?.let { store(it) },
                triggerPrice = trigger?.let { store(it) }, priceType = priceType, product = product, status = status,
                averagePrice = null, filledQuantity = 0, pendingQuantity = quantity, rejectionReason = null,
                marginBlocked = store(toBlock), orderTimestamp = now, updateTimestamp = dbNow,
            )
            events += SandboxEvent.OrderUpdate(orderId, status)

            if (priceType == "MARKET" || (cached != null && priceType in listOf("LIMIT", "SL", "SL-M"))) {
                if (cached != null && quoteLooksStale(cached)) cached = null
                if (cached != null) {
                    val ltp = pyDec(cached.ltp)
                    if (priceType == "MARKET") processOrder(orderId, cached)
                    // A marketable LIMIT fills at the LTP (price improvement), as does a triggered stop.
                    else if (ltp.signum() > 0) executeOrder(orderId, ltp)
                }
            }
            return OrderResult(true, 200, orderId)
        }

        fun modifyOrder(orderId: String, c: OrderChange): OrderResult {
            val o = order(orderId) ?: return err("Order $orderId not found", 404)
            if (o.status !in PENDING) return err("Cannot modify order in ${o.status} status")
            var quantity = o.quantity
            var pending = o.pendingQuantity
            if (c.quantity != null) {
                val inst = instruments.lookup(o.symbol, o.exchange)
                if (inst != null && o.exchange in LOT_EXCHANGES) {
                    val lot = if (inst.lotSize == 0) 1 else inst.lotSize
                    if (c.quantity % lot != 0) return err("Quantity must be in multiples of lot size $lot")
                }
                quantity = c.quantity; pending = c.quantity
            }
            var price = o.price
            if (c.price != null && c.price != 0.0) {
                if (o.priceType !in listOf("LIMIT", "SL")) return err("${o.priceType} orders do not accept a price")
                price = store(pyDec(c.price))
            }
            var trigger = o.triggerPrice
            if (c.triggerPrice != null && c.triggerPrice != 0.0) {
                if (o.priceType !in listOf("SL", "SL-M")) return err("${o.priceType} orders do not accept a trigger_price")
                trigger = store(pyDec(c.triggerPrice))
            }
            putOrder(o.copy(quantity = quantity, pendingQuantity = pending, price = price, triggerPrice = trigger, updateTimestamp = now))
            return OrderResult(true, 200, orderId, "Order modified successfully")
        }

        fun cancelOrder(orderId: String, quote: Quote?): OrderResult {
            val o = order(orderId) ?: return err("Order $orderId not found", 404)
            if (o.status !in PENDING) return err("Cannot cancel order in ${o.status} status")
            putOrder(o.copy(status = OrderStatus.CANCELLED, updateTimestamp = now))
            if (o.marginBlocked.signum() > 0) {
                releaseMargin(o.marginBlocked, BigDecimal.ZERO)
            } else if (instruments.lookup(o.symbol, o.exchange) != null) {
                // Python parity: the fallback for "old orders without margin_blocked"
                // also catches a reducing order that blocked nothing, and releases
                // a recomputed margin for it.
                val wouldHaveBlocked = o.action == "BUY" || isOption(o.symbol, o.exchange) || isFuture(o.symbol, o.exchange) ||
                    o.product == "MIS" || o.product == "NRML"
                if (wouldHaveBlocked) {
                    val price = o.price?.takeIf { it.signum() != 0 }
                        ?: quote?.ltp?.takeIf { it != 0.0 && it.isFinite() }?.let(::pyDec)
                        ?: BigDecimal.ZERO
                    if (price.signum() > 0) {
                        val m = marginRequired(o.symbol, o.exchange, o.product, o.quantity, price, o.action).first
                        if (m != null && m.signum() != 0) releaseMargin(m, BigDecimal.ZERO)
                    }
                }
            }
            events += SandboxEvent.OrderUpdate(orderId, OrderStatus.CANCELLED)
            return OrderResult(true, 200, orderId, "Order cancelled successfully")
        }

        // ---- execution (execution_engine.ExecutionEngine) ---------------------

        /**
         * _process_order. MARKET fills at the ask (buy) or bid (sell), falling
         * back to LTP; LIMIT fills at its limit once the LTP crosses it; a
         * released SL fills at the LTP inside its limit; SL-M at the LTP.
         */
        fun processOrder(orderId: String, q: Quote) {
            val o = order(orderId) ?: return
            val existing = trades.firstOrNull { it.orderId == orderId }
            if (existing != null) {
                if (o.status == OrderStatus.OPEN) {
                    putOrder(o.copy(status = OrderStatus.COMPLETE, averagePrice = existing.price, filledQuantity = o.quantity, pendingQuantity = 0, updateTimestamp = now))
                    events += SandboxEvent.Fill(orderId, existing.tradeId, o.symbol, o.exchange, o.action, o.quantity, existing.price.toDouble(), o.product)
                }
                return
            }
            val ltp = pyDec(q.ltp); val bid = pyDec(q.bid); val ask = pyDec(q.ask)
            if (ltp.signum() <= 0) return
            if (quoteLooksStale(q)) return
            if (o.status == OrderStatus.TRIGGER_PENDING) { processTriggerPending(o, ltp); return }
            val buy = o.action == "BUY"
            val fill: BigDecimal? = when (o.priceType) {
                "MARKET" -> if (buy) (if (ask.signum() > 0) ask else ltp) else (if (bid.signum() > 0) bid else ltp)
                "LIMIT" -> if ((buy && ltp <= o.price!!) || (!buy && ltp >= o.price!!)) o.price else null
                "SL" -> if ((buy && ltp >= o.triggerPrice!! && ltp <= o.price!!) || (!buy && ltp <= o.triggerPrice!! && ltp >= o.price!!)) ltp else null
                "SL-M" -> if ((buy && ltp >= o.triggerPrice!!) || (!buy && ltp <= o.triggerPrice!!)) ltp else null
                else -> null
            }
            val usedBook = o.priceType == "MARKET" && (if (buy) ask.signum() > 0 else bid.signum() > 0)
            if (fill != null) executeOrder(orderId, fill, usedBook)
        }

        /**
         * _process_trigger_pending_order: once touched, SL-M fills at the LTP;
         * SL fills too if its limit is also met on this tick, else it moves to
         * the regular book as "open".
         */
        fun processTriggerPending(o: Order, ltp: BigDecimal) {
            val buy = o.action == "BUY"
            if (!((buy && ltp >= o.triggerPrice!!) || (!buy && ltp <= o.triggerPrice!!))) return
            if (o.priceType == "SL-M") { executeOrder(o.orderId, ltp); return }
            if ((buy && ltp <= o.price!!) || (!buy && ltp >= o.price!!)) { executeOrder(o.orderId, ltp); return }
            putOrder(o.copy(status = OrderStatus.OPEN, updateTimestamp = now))
            events += SandboxEvent.OrderUpdate(o.orderId, OrderStatus.OPEN)
        }

        /**
         * _execute_order: one full fill (the sandbox never partially fills).
         * With slippage configured (the desktop's sandbox/slippage.py), a stop
         * fill and a MARKET fill that found no bid/ask move against the order;
         * with charges on (sandbox/charges.py), the leg's cost is debited now.
         */
        fun executeOrder(orderId: String, rawPx: BigDecimal, usedBook: Boolean = false) {
            val o = order(orderId) ?: return
            val px = SandboxCosts.fillPrice(rawPx, o.action, o.priceType, usedBook, o.price, config)
            val tradeId = nextTradeId()
            val charge = if (config.chargesEnabled) SandboxCosts.charge(o.action, px, o.quantity, contractValue(o.symbol, o.exchange)) else BigDecimal.ZERO
            if (charge.signum() > 0) editFunds { available -= charge; realized -= charge; today -= charge; total = realized + unrealized }
            trades += Trade(tradeId, o.orderId, o.symbol, o.exchange, o.action, o.quantity, store(px), o.product, o.strategy, now, if (charge.signum() > 0) store(charge) else BigDecimal.ZERO)
            val done = o.copy(status = OrderStatus.COMPLETE, averagePrice = store(px), filledQuantity = o.quantity, pendingQuantity = 0, updateTimestamp = now)
            putOrder(done)
            updatePosition(done, px)
            events += SandboxEvent.Fill(orderId, tradeId, o.symbol, o.exchange, o.action, o.quantity, px.toDouble(), o.product)
        }

        fun closePositionTx(symbol: String, exchange: String, product: String, quote: Quote?): OrderResult {
            val p = position(symbol, exchange, product) ?: return err("No open position found for $symbol", 404)
            val r = placeOrder(
                OrderRequest(symbol, exchange, if (p.quantity > 0) "SELL" else "BUY", kotlin.math.abs(p.quantity), "MARKET", product, strategy = "AUTO_SQUARE_OFF"),
                quote,
            )
            return if (r.ok) OrderResult(true, 200, r.orderId, "Position close order placed for $symbol") else r
        }

        // ---- square-off, expiry, settlement ---------------------------------

        fun checkAndSquareOff(quotes: Map<String, Quote>) {
            val t = now.toLocalTime()
            val sq = config.squareOffTimes
            for (o in pendingOrders().filter { it.product == "MIS" }) {
                val cut = sq[o.exchange] ?: continue
                if (!t.isBefore(cut)) cancelOrder(o.orderId, quotes[key(o.symbol, o.exchange)])
            }
            for (o in pendingOrders()) {
                val expiry = contractExpiry(o.symbol, o.exchange, instruments.lookup(o.symbol, o.exchange))
                if (isContractExpiredNow(expiry, o.exchange, now, config)) cancelOrder(o.orderId, quotes[key(o.symbol, o.exchange)])
            }
            cleanupExpiredContracts()
            val due = positions.filter { it.product == "MIS" && it.quantity != 0 }.filter { p ->
                val cut = sq[p.exchange]
                cut != null && !t.isBefore(cut)
            }
            for (p in due) {
                val r = closePositionTx(p.symbol, p.exchange, p.product, quotes[key(p.symbol, p.exchange)])
                events += SandboxEvent.SquareOff(p.symbol, p.exchange, p.product, r)
            }
        }

        fun isExpired(p: Position): Boolean =
            isContractExpiredNow(contractExpiry(p.symbol, p.exchange, instruments.lookup(p.symbol, p.exchange)), p.exchange, now, config)

        fun cleanupExpiredContracts() {
            // Visited as the Python's query returns them: through the exchange
            // index, so grouped by exchange. The order matters when an earlier
            // settlement leaves too little used margin for a later one.
            val due = positions.filter { it.exchange in LOT_EXCHANGES && it.quantity != 0 && isExpired(it) }.sortedBy { it.exchange }
            for (p in due) settleExpired(positionIndex(p.symbol, p.exchange, p.product))
        }

        /** get_expiry_settlement_price. */
        fun settlementPrice(p: Position): BigDecimal {
            val ltp = p.ltp ?: BigDecimal.ZERO
            return if (p.symbol.endsWith("CE") || p.symbol.endsWith("PE")) {
                if (config.optionExpirySettlement == "ltp" && ltp.signum() > 0) ltp else BigDecimal.ZERO
            } else if (ltp.signum() > 0) ltp else p.averagePrice
        }

        /**
         * _settle_expired_position. The close P&L goes through release_margin,
         * so it is refused along with the margin if used margin has already
         * been reconciled below the position's margin (Python parity). The
         * row's updated_at is then set to 00:00 UTC of expiry day, which the
         * Python does to hide the row from later sessions.
         */
        fun settleExpired(i: Int) {
            val p = positions[i]
            val settle = settlementPrice(p)
            val qty = BigDecimal(kotlin.math.abs(p.quantity))
            val closePnl = if (p.quantity > 0) (settle - p.averagePrice) * qty else (p.averagePrice - settle) * qty
            val total = p.accumulatedRealizedPnl + closePnl
            releaseMargin(p.marginBlocked, closePnl)
            editPosition(i) { quantity = 0; ltp = settle; pnl = total; accumulated = total; margin = BigDecimal.ZERO }
            val expiry = contractExpiry(p.symbol, p.exchange, instruments.lookup(p.symbol, p.exchange)) ?: now.toLocalDate()
            positions[i] = positions[i].copy(updatedAt = expiry.atStartOfDay(java.time.ZoneOffset.UTC).withZoneSameInstant(SandboxRules.IST).toLocalDateTime())
            events += SandboxEvent.ExpirySettled(p.symbol, p.exchange, p.product, store(settle), store(closePnl))
        }

        /**
         * process_t1_settlement: CNC positions created before today's IST
         * midnight become holdings. A long moves its cost (qty x average) out
         * of used margin; a short (a sale of held shares) reduces the holding
         * and credits the proceeds at the position's average.
         */
        fun t1Settlement(): Int {
            val cutoff = now.toLocalDate().atStartOfDay()
            val due = positions.filter { it.product == "CNC" && it.createdAt.isBefore(cutoff) }
            var settled = 0
            for (p in due) {
                positions.removeAt(positionIndex(p.symbol, p.exchange, p.product))
                if (p.quantity == 0) continue
                val amount = BigDecimal(kotlin.math.abs(p.quantity)) * p.averagePrice
                val hi = holdings.indexOfFirst { it.symbol == p.symbol && it.exchange == p.exchange }
                if (hi >= 0) {
                    val h = holdings[hi]
                    val newQty = h.quantity + p.quantity
                    var avg = h.averagePrice
                    if (p.quantity > 0) {
                        val totalValue = BigDecimal(kotlin.math.abs(h.quantity)) * h.averagePrice + amount
                        val totalQty = kotlin.math.abs(h.quantity) + kotlin.math.abs(p.quantity)
                        if (totalQty > 0) avg = store(div(totalValue, BigDecimal(totalQty)))
                        transferMarginToHoldings(amount)
                    } else {
                        creditSaleProceeds(amount)
                    }
                    if (newQty == 0) holdings.removeAt(hi)
                    else holdings[hi] = h.copy(quantity = newQty, averagePrice = avg, ltp = p.ltp, updatedAt = dbNow)
                } else {
                    holdings += Holding(
                        symbol = p.symbol, exchange = p.exchange, quantity = p.quantity, averagePrice = p.averagePrice,
                        ltp = p.ltp ?: p.averagePrice, pnl = ZERO2, pnlPercent = ZERO4, settlementDate = now.toLocalDate(),
                        createdAt = now, updatedAt = dbNow,
                    )
                    transferMarginToHoldings(amount)
                }
                settled++
            }
            if (settled > 0) events += SandboxEvent.T1Settled(settled)
            return settled
        }

        /**
         * catch_up_mis_squareoff: an MIS position untouched since before the
         * last session boundary is closed at its last LTP (else average). Its
         * P&L goes to all-time realized only, never to today's.
         */
        fun catchUpMisSquareOff() {
            val boundary = SandboxRules.lastSessionExpiry(config.sessionExpiryTime, now)
            val stale = positions.filter { it.product == "MIS" && it.quantity != 0 && it.updatedAt.isBefore(boundary) }
            for (p in stale) {
                if (p.exchange !in config.squareOffTimes) continue
                val settle = p.ltp?.takeIf { it.signum() > 0 } ?: p.averagePrice
                val cv = instruments.lookup(p.symbol, p.exchange)?.contractValue?.takeIf { it != 0.0 }?.let(::pyDec) ?: BigDecimal("1.0")
                val qty = BigDecimal(kotlin.math.abs(p.quantity))
                val pnl = if (p.quantity > 0) (settle - p.averagePrice) * qty * cv else (p.averagePrice - settle) * qty * cv
                val blocked = p.marginBlocked
                editFunds {
                    available = available + blocked + pnl
                    used -= blocked
                    realized += pnl
                    total = realized + unrealized
                    if (used.signum() < 0) used = BigDecimal.ZERO
                }
                editPosition(positionIndex(p.symbol, p.exchange, p.product)) {
                    quantity = 0; margin = BigDecimal.ZERO; this.pnl = pnl; accumulated += pnl; today = BigDecimal.ZERO
                }
            }
        }

        fun catchUpDailyPnlReset() {
            val boundary = SandboxRules.lastSessionExpiry(config.sessionExpiryTime, now)
            val positionsStale = positions.any { it.todayRealizedPnl.signum() != 0 && it.updatedAt.isBefore(boundary) }
            val fundsStale = funds.todayRealizedPnl.signum() != 0 && funds.updatedAt.isBefore(boundary)
            if (!positionsStale && !fundsStale) return
            for (i in positions.indices) {
                if (positions[i].updatedAt.isBefore(boundary)) positions[i] = positions[i].copy(todayRealizedPnl = ZERO2, updatedAt = dbNow)
            }
            if (funds.updatedAt.isBefore(boundary)) editFundsBulk { today = BigDecimal.ZERO }
        }

        // ---- position and holdings books ------------------------------------

        fun openPositions(quotes: Map<String, Quote>): PositionBook {
            val boundary = SandboxRules.lastSessionExpiry(config.sessionExpiryTime, now)
            val shown = mutableListOf<Int>()
            // The Python's query runs through the (user_id, product) index, so
            // rows come back grouped by product: CNC, MIS, NRML.
            for (i in positions.indices.sortedBy { positions[it].product }) {
                // Catch-up for a missed 03:00 reset. Written without touching
                // updated_at, or yesterday's closed rows would reappear today.
                if (positions[i].todayRealizedPnl.signum() != 0 && positions[i].updatedAt.isBefore(boundary)) {
                    positions[i] = positions[i].copy(todayRealizedPnl = ZERO2)
                }
                val p = positions[i]
                val include = if (!p.updatedAt.isBefore(boundary)) p.quantity != 0 || p.todayRealizedPnl.signum() != 0
                else p.product == "NRML" && p.quantity != 0
                if (include) shown += i
            }
            for (i in shown) {
                val p = positions[i]
                if (contractExpiry(p.symbol, p.exchange, instruments.lookup(p.symbol, p.exchange)) == null || p.quantity == 0) continue
                if (isExpired(p)) settleExpired(i)
            }
            for (i in shown) {
                val p = positions[i]
                if (p.quantity == 0) continue
                val q = quotes[key(p.symbol, p.exchange)] ?: continue
                val ltpNow = pyDec(q.ltp)
                if (ltpNow.signum() <= 0) continue
                val cv = contractValue(p.symbol, p.exchange)
                val unrealized = if (p.quantity > 0) (ltpNow - p.averagePrice) * BigDecimal(p.quantity) * cv
                else (p.averagePrice - ltpNow) * BigDecimal(kotlin.math.abs(p.quantity)) * cv
                val pct = when {
                    p.averagePrice.signum() <= 0 -> BigDecimal.ZERO
                    p.quantity > 0 -> div(ltpNow - p.averagePrice, p.averagePrice) * HUNDRED
                    else -> div(p.averagePrice - ltpNow, p.averagePrice) * HUNDRED
                }
                editPosition(i) { ltp = ltpNow; pnl = unrealized; pnlPercent = pct }
            }

            var totalUnrealized = BigDecimal.ZERO
            var totalToday = BigDecimal.ZERO
            var totalPnlToday = BigDecimal.ZERO
            val rows = shown.map { i ->
                val p = positions[i]
                val unrealized = p.pnl
                val today = p.todayRealizedPnl
                val rowTotal = if (p.quantity != 0) { totalUnrealized += unrealized; today + unrealized } else today
                totalToday += today
                totalPnlToday += rowTotal
                val cv = instruments.lookup(p.symbol, p.exchange)?.contractValue?.takeIf { it != 0.0 } ?: 1.0
                val (pct, avg) = if (p.quantity != 0) {
                    val investment = (p.averagePrice * BigDecimal(p.quantity) * pyDec(cv)).abs()
                    (if (investment.signum() > 0) div(rowTotal, investment) * HUNDRED else BigDecimal.ZERO) to p.averagePrice.toDouble()
                } else BigDecimal.ZERO to 0.0
                PositionRow(
                    symbol = p.symbol, exchange = p.exchange, product = p.product, quantity = p.quantity,
                    averagePrice = avg, ltp = p.ltp.orZero(), pnl = rowTotal.toDouble(), pnlPercent = pct.toDouble(),
                    unrealizedPnl = unrealized.toDouble(), todayRealizedPnl = today.toDouble(),
                    totalPnlToday = rowTotal.toDouble(), lotSize = cv,
                )
            }
            updateUnrealizedPnl(totalUnrealized)
            return PositionBook(rows, totalPnlToday.toDouble(), totalUnrealized.toDouble(), totalToday.toDouble(), totalPnlToday.toDouble())
        }

        fun holdingsBookTx(quotes: Map<String, Quote>): HoldingsBook {
            val shown = holdings.indices.filter { holdings[it].quantity != 0 }
            for (i in shown) {
                val h = holdings[i]
                val q = quotes[key(h.symbol, h.exchange)] ?: continue
                val ltp = pyDec(q.ltp)
                if (ltp.signum() <= 0) continue
                val pnl = (ltp - h.averagePrice) * BigDecimal(kotlin.math.abs(h.quantity))
                val pct = if (h.averagePrice.signum() <= 0) BigDecimal.ZERO else div(ltp - h.averagePrice, h.averagePrice) * HUNDRED
                holdings[i] = h.copy(ltp = store(ltp), pnl = store(pnl), pnlPercent = store(pct, 4), updatedAt = dbNow)
            }
            var totalPnl = BigDecimal.ZERO; var totalValue = BigDecimal.ZERO; var totalInvestment = BigDecimal.ZERO
            val rows = shown.map { i ->
                val h = holdings[i]
                totalPnl += h.pnl
                val current = if (h.ltp != null && h.ltp.signum() != 0) BigDecimal(kotlin.math.abs(h.quantity)) * h.ltp else BigDecimal.ZERO
                totalValue += current
                totalInvestment += BigDecimal(kotlin.math.abs(h.quantity)) * h.averagePrice
                HoldingRow(
                    symbol = h.symbol, exchange = h.exchange, product = "CNC", quantity = h.quantity,
                    averagePrice = h.averagePrice.toDouble(), ltp = h.ltp.orZero(), pnl = h.pnl.toDouble(),
                    pnlPercent = h.pnlPercent.toDouble(), currentValue = current.toDouble(), settlementDate = h.settlementDate.toString(),
                )
            }
            val pct = if (totalInvestment.signum() > 0) div(totalPnl, totalInvestment) * HUNDRED else BigDecimal.ZERO
            return HoldingsBook(rows, totalValue.toDouble(), totalInvestment.toDouble(), totalPnl.toDouble(), pct.toDouble())
        }
    }
}
