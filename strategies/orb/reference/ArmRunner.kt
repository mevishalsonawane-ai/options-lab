package com.iraalgo.app.arms

import com.iraalgo.app.EngineConfig
import com.iraalgo.app.paper.Order
import com.iraalgo.app.paper.PaperAccount
import com.iraalgo.app.paper.Position
import com.iraalgo.app.paper.Store
import com.iraalgo.app.rules.AccountGuard
import com.iraalgo.app.rules.AccountState
import com.iraalgo.app.rules.Arm
import com.iraalgo.app.rules.Intent
import com.iraalgo.app.rules.Limits
import com.iraalgo.app.rules.Bar
import com.iraalgo.app.rules.Costs
import com.iraalgo.app.rules.OrbRules
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * The two ORB paper arms, as services/ai_signals/orb_arm.py runs them:
 * [minute] decides on the last completed bar (run_orb_cycle, _enter), and
 * [priceCheck] manages open positions (_manage_open) every few seconds.
 */
class ArmRunner(
    private val market: Market,
    private val account: PaperAccount,
    private val store: Store,
    private val limits: Limits = LIMITS,
    private val clock: () -> LocalDateTime,
) {
    private var legs: Triple<LocalDate, OptionContract, OptionContract>? = null
    private val lastQuote = mutableMapOf<String, Quote>()
    private val backstop: LocalTime = LocalTime.parse(EngineConfig.BACKSTOP_SQUARE_OFF)

    private companion object {
        val LIMITS = Limits(
            EngineConfig.MAX_CONCURRENT_POSITIONS, EngineConfig.MAX_TRADES_TODAY, EngineConfig.MAX_DAILY_LOSS,
            EngineConfig.MAX_DRAWDOWN_PCT, EngineConfig.MAX_ORDER_VALUE, EngineConfig.MAX_SYMBOL_EXPOSURE,
            EngineConfig.ENTRY_CUTOFF_MINUTES,
        )
    }

    @Synchronized
    fun minute(): Map<String, String> {
        val now = clock()
        val day = now.toLocalDate()
        if (store.stoppedFor(day)) return OrbRules.ARMS.associate { it.source to "stopped_for_today" }
        return try {
            val bars = OrbRules.completed(market.indexBars(day), now)
            OrbRules.ARMS.associate { it.source to cycle(it, day, now, bars) }
        } catch (e: TokenRejected) {
            OrbRules.ARMS.associate { it.source to "session_rejected" }
        }
    }

    private fun cycle(arm: Arm, day: LocalDate, now: LocalDateTime, bars: List<Bar>): String {
        if (store.positions(day).any { it.arm == arm.source && it.exit == null }) return "holding"
        if (!now.toLocalTime().isBefore(OrbRules.SQUARE_OFF)) return "flat_after_square_off"
        val rng = OrbRules.openingRange(bars) ?: return "waiting_for_opening_range"
        val (_, ce, pe) = contracts(day, bars) ?: return "no_contract"
        val last = bars.last()
        if (!store.claimBar(arm.source, day, last.start)) return "no_decision_bar"
        val lastExit = store.positions(day).filter { it.arm == arm.source }.mapNotNull { it.exitTime }.maxOrNull()
        val (direction, why) = OrbRules.entrySignal(bars, rng, arm, lastExit)
        if (direction == 0) return why
        return enter(arm, if (direction > 0) ce else pe, last.start, day)
    }

    /** The day's strike, from the first completed bar at or after 09:20, held all day. */
    private fun contracts(day: LocalDate, bars: List<Bar>): Triple<LocalDate, OptionContract, OptionContract>? {
        legs?.let { if (it.first == day) return it }
        val ref = bars.firstOrNull { !it.start.toLocalTime().isBefore(OrbRules.STRIKE_BAR) } ?: return null
        val strike = OrbRules.atmStrike(ref.close)
        val ce = market.contract(day, strike, "CE") ?: return null
        val pe = market.contract(day, strike, "PE") ?: return null
        return Triple(day, ce, pe).also { legs = it }
    }

    private fun enter(arm: Arm, c: OptionContract, signalBar: LocalDateTime, day: LocalDate): String {
        val q = market.quote(c) ?: return "refused"                     // never enter blind
        val expected = if (q.ask > 0) q.ask else Costs.fillPrice(q.ltp, "BUY", "MARKET", false)
        // At or below Rs 40 a 40-point stop has no level: the Python arm crashes
        // there after its buy fills. Refuse before buying instead.
        if (OrbRules.stopTrigger(expected) == null) return "refused"
        if (store.stoppedFor(day)) return "stopped_for_today"          // stop pressed mid-decision
        // The laptop's account guard (services/risk/account_guard.py) on every entry.
        val verdict = AccountGuard.check(Intent(c.symbol, "BUY", c.lot, expected), accountState(day), limits, store.killSwitch())
        if (!verdict.allowed) {
            if (verdict.reason == "max_drawdown") store.setKillSwitch(true)   // as account_risk_service does
            return "guard_refused:${verdict.reason}"
        }
        val buy = account.buyMarket(arm.source, c, c.lot, q)
        if (buy.status != "complete") return "order_refused"
        val trigger = OrbRules.stopTrigger(buy.price) ?: return "refused"
        val stop = account.restStop(arm.source, c, c.lot, trigger)
        store.savePosition(Position(arm.source, c, c.lot, buy.price, clock(), signalBar, stop.id))
        lastQuote[c.symbol] = q
        return "entered"
    }

    /** The account as the guard measures it: open positions, today's orders, P&L, equity and its peak. */
    private fun accountState(day: LocalDate): AccountState {
        val open = openPositions()
        val marks = open.associate { p -> p.contract.symbol to (lastQuote[p.contract.symbol]?.ltp ?: p.entry) }
        val pnl = account.dayPnl(day, marks)
        val equity = store.cash() + open.sumOf { (marks.getValue(it.contract.symbol)) * it.qty }
        if (equity > store.peakEquity()) store.setPeakEquity(equity)
        val minutes = java.time.Duration.between(clock().toLocalTime(), backstop).toMinutes().toInt()
        return AccountState(
            openPositions = open.groupBy { it.contract.symbol }.mapValues { (_, ps) -> ps.sumOf { it.qty } },
            tradesToday = store.orders(day).size,
            totalPnl = pnl.realised + pnl.open,
            startingCapital = EngineConfig.STARTING_CAPITAL,
            equity = equity,
            peakEquity = store.peakEquity(),
            minutesToSquareOff = minutes,
        )
    }

    /** Resting stops, targets, the 15:10 exit and the 15:15 backstop, for every open position. */
    @Synchronized
    fun priceCheck(): List<Order> {
        val now = clock()
        val day = now.toLocalDate()
        val filled = mutableListOf<Order>()
        for (p in openPositions()) {
            val fresh = try { market.quote(p.contract) } catch (e: TokenRejected) { null }
            fresh?.let { lastQuote[p.contract.symbol] = it }
            val q = fresh ?: lastQuote[p.contract.symbol] ?: continue       // no price at all: hold
            if (store.stoppedFor(day)) { filled += exit(p, q, "operator_stop"); continue }
            val stop = p.stopOrderId?.let { account.checkStop(it, q) }
            if (stop != null) { close(p, stop.price, "stop"); filled += stop; continue }
            val t = now.toLocalTime()
            if (!t.isBefore(OrbRules.SQUARE_OFF) || !t.isBefore(backstop)) { filled += exit(p, q, "session_end"); continue }
            val reason = OrbRules.exitReason(p.entry, q.ltp, now)
            if (reason == "target" || (reason == "stop" && p.stopOrderId == null)) filled += exit(p, q, reason)
        }
        return filled
    }

    private fun exit(p: Position, q: Quote, why: String): Order {
        p.stopOrderId?.let { account.cancel(it) }                         // the resting stop leaves the book first
        val sell = account.sellMarket(p.arm, p.contract, p.qty, q)
        close(p, sell.price, why)
        return sell
    }

    private fun close(p: Position, price: Double, why: String) =
        store.savePosition(p.copy(exit = price, exitTime = clock(), why = why))

    fun stopForToday() = store.setStopped(clock().toLocalDate(), true)
    fun startAgain() = store.setStopped(clock().toLocalDate(), false)
    /** The last price seen per symbol, for P&L on screen and in the notification. */
    @Synchronized fun marks(): Map<String, Double> = lastQuote.mapValues { it.value.ltp }

    fun openPositions(): List<Position> = store.positions(clock().toLocalDate()).filter { it.exit == null }
}
