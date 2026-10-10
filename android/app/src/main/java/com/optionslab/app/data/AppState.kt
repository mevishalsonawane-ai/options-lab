package com.optionslab.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The watch's shared memory (Boss, 10 Oct: "all the workers share the same in-memory state"): the latest of what the app
 * already read, published once by whoever read it, for every worker and screen to look at instead of asking again -
 * Zerodha's positions, orders and margins as last read (each with when its read began), the account's and the paper
 * book's P&L as the watch last worked them out, and the guards' states (kill switch, daily loss limit, day stop, day
 * locks). Memory only (never the vault, never a lock any bot holds); every value says how old it is, and a reader that
 * needs a fresh figure to place, change or close an order still reads it fresh (Zerodha's reads are shared by workers
 * asking at once: [Broker.SHARE_MS]).
 */
object AppState {
    /** A value and when it was read (epoch ms). */
    data class Stamped<T>(val value: T, val at: Long) {
        fun ageMs(now: Long = System.currentTimeMillis()): Long = now - at
    }

    /** The guards as last looked at (by the watch's safety steps, off the main thread). */
    data class Guards(
        val killSwitch: Boolean = false,
        val lossLimitTripped: Boolean = false,
        val stoppedToday: Boolean = false,
        val dayLockPaper: Boolean = false,
        val dayLockLive: Boolean = false,
        val at: Long = 0L,
    )

    /** The day's P&L as the watch last worked it out: Zerodha's (null: not read) and the paper book's after charges. */
    data class Pnl(val account: Double?, val paperDay: Double?, val at: Long)

    private val _positions = MutableStateFlow<Stamped<Broker.Positions>?>(null)
    val positions: StateFlow<Stamped<Broker.Positions>?> = _positions.asStateFlow()
    private val _orders = MutableStateFlow<Stamped<List<Broker.OrderRow>>?>(null)
    val orders: StateFlow<Stamped<List<Broker.OrderRow>>?> = _orders.asStateFlow()
    private val _funds = MutableStateFlow<Stamped<Broker.Funds>?>(null)
    val funds: StateFlow<Stamped<Broker.Funds>?> = _funds.asStateFlow()
    private val _guards = MutableStateFlow(Guards())
    val guards: StateFlow<Guards> = _guards.asStateFlow()
    private val _pnl = MutableStateFlow<Pnl?>(null)
    val pnl: StateFlow<Pnl?> = _pnl.asStateFlow()

    /** Only a newer read replaces an older one (two workers' reads may end in either order). */
    private fun <T> newer(f: MutableStateFlow<Stamped<T>?>, v: T, at: Long) {
        while (true) {
            val cur = f.value
            if (cur != null && cur.at > at) return
            if (f.compareAndSet(cur, Stamped(v, at))) return
        }
    }

    fun publishPositions(p: Broker.Positions, at: Long) = newer(_positions, p, at)
    fun publishOrders(o: List<Broker.OrderRow>, at: Long) = newer(_orders, o, at)
    fun publishFunds(f: Broker.Funds, at: Long) = newer(_funds, f, at)

    fun publishPnl(account: Double?, paperDay: Double?) {
        val prev = _pnl.value
        _pnl.value = Pnl(account ?: prev?.account, paperDay ?: prev?.paperDay, System.currentTimeMillis())
    }

    /**
     * The guards looked at now (the settings and the breaker's record are in memory once read; the bot's day stop is its
     * lock-free hint). Called by the watch's safety steps, never on the main thread.
     */
    fun refreshGuards() {
        val s = runCatching { AppSettings.load() }.getOrNull()
        _guards.value = Guards(
            killSwitch = s?.guardKill ?: _guards.value.killSwitch,
            lossLimitTripped = runCatching { LossBreaker.trippedToday() }.getOrDefault(_guards.value.lossLimitTripped),
            stoppedToday = runCatching { Strategies.stopHint() != null }.getOrDefault(false),
            dayLockPaper = runCatching { DayLockGuard.active(false) != null }.getOrDefault(false),
            dayLockLive = runCatching { DayLockGuard.active(true) != null }.getOrDefault(false),
            at = System.currentTimeMillis(),
        )
    }

    /** The diagnostics' line: how old each shared figure is and the guards as last looked at. */
    fun line(now: Long = System.currentTimeMillis()): String {
        fun age(at: Long?): String = at?.let { com.optionslab.ira.StepStats.ms((now - it).coerceAtLeast(0)) + " ago" } ?: "not read"
        val g = _guards.value
        val guards = if (g.at == 0L) "not looked at yet" else listOfNotNull(
            "kill switch ${if (g.killSwitch) "ON" else "off"}",
            if (g.lossLimitTripped) "daily loss limit reached" else null,
            if (g.stoppedToday) "bot stopped for today" else null,
            if (g.dayLockPaper) "day lock (paper)" else null,
            if (g.dayLockLive) "day lock (Zerodha)" else null,
        ).joinToString(", ") + " (${age(g.at)})"
        return "Shared state: Zerodha positions ${age(_positions.value?.at)} · orders ${age(_orders.value?.at)} · " +
            "margins ${age(_funds.value?.at)} · guards: $guards"
    }

    /** TEST ONLY: nothing left from an earlier test. */
    internal fun resetForTest() {
        _positions.value = null; _orders.value = null; _funds.value = null; _guards.value = Guards(); _pnl.value = null
    }
}
