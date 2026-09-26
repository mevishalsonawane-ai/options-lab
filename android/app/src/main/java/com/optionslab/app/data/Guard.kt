package com.optionslab.app.data

import com.optionslab.app.security.SecurePrefs
import com.optionslab.engine.risk.AccountGuard

/**
 * The account-wide guard (engine: [AccountGuard]) applied to this app's two
 * accounts. It builds the account picture from what is already loaded - the
 * paper snapshot or the Zerodha positions and funds - so a check never has to
 * go to the network, and it keeps each account's highest equity so drawdown
 * is measured from the peak as well as from the capital.
 */
object Guard {
    /** Zerodha's instrument list by trading symbol, parsed once a day (the file is large). */
    @Volatile private var ins: Pair<java.time.LocalDate, Map<String, com.optionslab.engine.Kite.Instrument>>? = null
    private fun instruments(): Map<String, com.optionslab.engine.Kite.Instrument> {
        ins?.let { if (it.first == Market.today()) return it.second }
        val m = Broker.cachedInstruments().orEmpty().associateBy { it.tradingSymbol }
        if (m.isNotEmpty()) ins = Market.today() to m
        return m
    }

    private fun peakKey(live: Boolean) = if (live) "guard.peak.live" else "guard.peak.paper"

    /** Record today's equity and return the highest seen (the peak only rises). */
    private fun peak(live: Boolean, equity: Double): Double {
        val next = AccountGuard.nextPeak(SecurePrefs.getDouble(peakKey(live), equity), equity)
        if (next != SecurePrefs.getDouble(peakKey(live), Double.NaN)) SecurePrefs.put(peakKey(live), next)
        return next
    }

    /** Start the peak again from today's equity (after a paper reset, or when the owner asks). */
    fun resetPeak(live: Boolean) = SecurePrefs.put(peakKey(live), null)

    fun paperAccount(snap: Paper.Snapshot): AccountGuard.Account {
        val capital = Paper.capital.toDouble()
        val f = snap.funds
        val equity = capital + f.totalPnl
        val today = Market.today().toString()
        return AccountGuard.Account(
            capital = capital, equity = equity, peakEquity = peak(false, equity),
            dayPnl = f.todayRealizedPnl + f.m2mUnrealized,
            holdings = snap.positions.positions.filter { it.quantity != 0 }.map { p ->
                val c = Paper.contractOf(p.symbol)
                AccountGuard.Holding(p.symbol, p.quantity, c?.lotSize ?: p.lotSize.toInt().coerceAtLeast(1), c?.underlying, c?.expiry, c?.right?.name)
            },
            ordersToday = snap.orders.orders.count { it.timestamp.startsWith(today) },
            minuteOfDay = Market.minuteNow(),
        )
    }

    fun liveAccount(book: Broker.Positions, funds: Broker.Funds?, ordersToday: Int): AccountGuard.Account {
        val ins = instruments()
        val dayPnl = book.m2m
        // Zerodha's opening balance is the day's capital; equity is that plus today's mark-to-market.
        val capital = funds?.openingBalance?.takeIf { it > 0 } ?: funds?.net ?: 0.0
        val equity = capital + dayPnl
        return AccountGuard.Account(
            capital = capital, equity = equity, peakEquity = peak(true, equity), dayPnl = dayPnl,
            holdings = book.net.filter { it.qty != 0 }.map { p ->
                val i = ins[p.symbol]
                AccountGuard.Holding(p.symbol, p.qty, i?.lotSize ?: 1, i?.name, i?.expiry, i?.right?.name)
            },
            ordersToday = ordersToday,
            minuteOfDay = Market.minuteNow(),
        )
    }

    fun paperOrder(c: Paper.Contract, action: String, lots: Int, price: Double) =
        AccountGuard.Order(c.symbol, action.uppercase(), lots * c.lotSize, c.lotSize, price, c.underlying, c.expiry, c.right.name)

    fun liveOrder(o: com.optionslab.engine.Kite.Order): AccountGuard.Order {
        val i = instruments()[o.tradingSymbol]
        return AccountGuard.Order(o.tradingSymbol, o.side.name, o.quantity, o.lotSize, o.price ?: 0.0, i?.name, i?.expiry, i?.right?.name)
    }

    /** The account as it will be once [o] has filled: how the later legs of a basket are judged. */
    fun after(a: AccountGuard.Account, o: AccountGuard.Order): AccountGuard.Account {
        val signed = if (o.side.equals("BUY", true)) o.qty else -o.qty
        val held = a.holdings.firstOrNull { it.symbol == o.symbol }
        val rest = a.holdings.filter { it.symbol != o.symbol }
        val next = (held?.qty ?: 0) + signed
        val h = if (next == 0) rest else rest + AccountGuard.Holding(o.symbol, next, o.lot, o.underlying, o.expiry, o.right)
        return a.copy(holdings = h, ordersToday = a.ordersToday + 1)
    }

    /**
     * As the desktop's account_risk_service does: an entry refused for drawdown also
     * turns the kill switch on, so nothing else opens until the owner clears it.
     */
    fun onRefusal(refusals: List<String>) {
        if (refusals.none { it.startsWith("Drawdown limit") }) return
        val s = AppSettings.load()
        if (!s.guardKill) AppSettings.save(s.copy(guardKill = true))
    }

    /** Refusals for [order] against [account] under the owner's limits; empty = it may go. */
    fun check(order: AccountGuard.Order, account: AccountGuard.Account?, exit: Boolean = false, paper: Boolean = false): List<String> =
        // Only a LIVE drawdown engages the (shared) kill switch: a paper test running into its own
        // drawdown must not block live exits or the live expiry square-off.
        judge(order, account, exit, paper).also { if (!exit && !paper) onRefusal(it) }

    private fun judge(order: AccountGuard.Order, account: AccountGuard.Account?, exit: Boolean, paper: Boolean): List<String> {
        val limits = AppSettings.load().guardLimits(paper)
        val killed = listOf("The kill switch is on: no orders at all until it is cleared.")
        // An exit is only ever stopped by the kill switch, even when the account cannot be read.
        if (exit) return if (limits.killSwitch) killed else emptyList()
        // Without the account the limits cannot be judged, so entries wait.
        if (account == null) return if (limits.killSwitch) killed
            else listOf("The account could not be read to check its limits; try again in a moment.")
        return AccountGuard.refusals(order, account, limits)
    }
}
