package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.SecurePrefs
import com.optionslab.engine.IST
import com.optionslab.engine.Kite
import com.optionslab.ira.ExitDesk
import com.optionslab.ira.ExitRegistry
import com.optionslab.ira.ExitTurns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 20 Oct, enhancement round 2 (idea 1): the app's ONE exit coordinator per contract ([ExitDesk] has the why and the rules).
 *
 *  - The order door ([Broker.placeOrder] with exit, [Broker.placeGtt]) notes every exit sent, from every sender, Boss's own too;
 *    every read of Zerodha's orders and GTTs ([Broker.orders], [Broker.gtts]) ticks off those it shows.
 *  - The exit senders read their books through [orders] / [gtts] and send inside [turn]: one sender at a time on a contract,
 *    and a read missing an exit just sent from here is read again. A pass that read the books once for many contracts (the
 *    expiry square-off, MCX's expiry exit) asks [stale] for each contract instead, inside its turn.
 *  - It never changes a quantity, never refuses an exit, and never holds one back longer than [ExitTurns.WAIT_MS] plus a
 *    read again of under two seconds: past that the exit goes as before, logged. Boss's own exits never wait for a turn.
 *  - Off by default (shadow): nothing waits and nothing is read again; what it would have done is counted and logged, for the
 *    diagnostics and Bot settings. Boss switches it on in Bot settings.
 *
 * Memory only on the order path (no disk, no settings, no network of its own; OrderPathRound2Test stays 0): the switch is
 * read once in the background at start and kept.
 */
object ExitCoordinator {
    private const val K_ON = "g.exitCoordinator.on"

    @Volatile private var on = false
    @Volatile private var loaded = false
    /** Boss changed the switch in this process (the start's late read never overrides it). */
    @Volatile private var switched = false
    private val registry = ExitRegistry()
    @Volatile private var turns = ExitTurns()
    private val lock = Any()
    private var counts = ExitDesk.Counts()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class View(val on: Boolean = false, val line: String = "")
    private val _view = MutableStateFlow(View())
    /** Bot settings' card. */
    val view: StateFlow<View> = _view

    fun init(@Suppress("UNUSED_PARAMETER") context: Context) { scope.launch { load() } }

    /** The switch from the settings vault (a worker thread). Until read it is off: the shadow, as before this round. */
    internal fun load() {
        // Review: a switch Boss changed before this read landed is kept (the read never puts the old value back).
        runCatching { val saved = SecurePrefs.getBoolean(K_ON, false); if (!switched) on = saved; loaded = true }
        publish()
    }

    fun isOn(): Boolean = on

    /** Boss's switch (Bot settings). On only makes a sender wait its turn or read its book again; it never refuses an exit. */
    fun setOn(on: Boolean): String {
        this.on = on
        switched = true
        runCatching { SecurePrefs.putAllSoon(mapOf(K_ON to on)) }
        runCatching { Diag.record("exits", "Exit coordinator ${if (on) "on: one exit at a time per contract, books read again until the last exit shows" else "in shadow: logged only"} (by you)") }
        publish()
        return if (on) "Exit coordinator on: two exits on one contract never read the books at once, and a read missing an exit just sent " +
            "is read again (a few seconds at most; past that the exit goes, as before)."
        else "Exit coordinator in shadow: what it would do is logged, nothing waits."
    }

    // ---- the order door's notes (memory only; never throws) -----------------------------------------------------------------

    /** An exit order about to be sent ([Broker.placeOrder]); its ticket. Boss's own orders are "yours". */
    internal fun sending(o: Kite.Order): Long = runCatching {
        count { it.copy(sent = it.sent + 1) }
        registry.sending(ExitRegistry.Kind.ORDER, ExitDesk.key(o.exchange, o.tradingSymbol), o.side.name, o.quantity, o.orderType,
            o.tag.ifBlank { "yours" })
    }.getOrDefault(-1L)

    internal fun answered(ticket: Long, id: String) { runCatching { registry.answered(ticket, id) } }

    /** [definite]: Zerodha refused it (nothing to look for); otherwise its reply was lost (it may exist). */
    internal fun failed(ticket: Long, definite: Boolean) { runCatching { if (definite) registry.refused(ticket) else registry.lost(ticket) } }

    /** A lost reply's order found by its sender ([Broker.findRecent]). */
    internal fun adopted(o: Kite.Order, id: String) {
        runCatching { registry.adopted(ExitRegistry.Kind.ORDER, ExitDesk.key(o.exchange, o.tradingSymbol), o.side.name, o.quantity, o.orderType, id) }
    }

    /** A GTT about to be placed ([Broker.placeGtt]); its ticket. */
    internal fun sendingGtt(g: Kite.Gtt): Long = runCatching {
        val leg = g.orders.firstOrNull()
        registry.sending(ExitRegistry.Kind.GTT, ExitDesk.key(g.exchange, g.tradingSymbol), leg?.side?.name ?: "", leg?.quantity ?: 0, "GTT",
            leg?.tag?.ifBlank { null } ?: "gtt")
    }.getOrDefault(-1L)

    /** A read of the order book: every exit it shows is ticked off. */
    internal fun ordersRead(rows: List<Broker.OrderRow>) { runCatching { registry.seen(ExitRegistry.Kind.ORDER, orderRows(rows)) } }

    /** A read of the GTT list. */
    internal fun gttsRead(rows: List<Broker.GttRow>) { runCatching { registry.seen(ExitRegistry.Kind.GTT, gttRows(rows)) } }

    /** A GTT deleted from here ([Broker.deleteGtt]): never looked for again (a deleted GTT may not be listed). */
    internal fun gttDeleted(id: Long) { runCatching { registry.forget(ExitRegistry.Kind.GTT, id.toString()) } }

    private fun orderRows(rows: List<Broker.OrderRow>) =
        rows.map { ExitRegistry.Row(it.id, ExitDesk.key(it.exchange, it.symbol), it.side, it.qty, it.type, placedMs(it.placedAt)) }

    private fun gttRows(rows: List<Broker.GttRow>) = rows.map { ExitRegistry.Row(it.id.toString(), ExitDesk.key(it.exchange, it.symbol),
        it.legSides.firstOrNull() ?: "", it.legQtys.firstOrNull() ?: 0, "GTT", placedMs(it.created)) }

    private val PLACED = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private fun placedMs(s: String): Long? = runCatching { java.time.LocalDateTime.parse(s, PLACED).atZone(IST).toInstant().toEpochMilli() }.getOrNull()

    // ---- for the exit senders -------------------------------------------------------------------------------------------------

    /**
     * [block] in contract [exchange]:[symbol]'s turn for [who] (see the class note). Inline, so a sender's own early returns
     * still return from the sender.
     */
    suspend inline fun <T> turn(exchange: String, symbol: String, who: String, block: () -> T): T {
        val pass = enter(exchange, symbol, who)
        try { return block() } finally { pass.leave() }
    }

    /** [turn]'s way in (public only for the inline [turn]). Never throws: a failure is a turn not taken (the exit goes). */
    suspend fun enter(exchange: String, symbol: String, who: String): ExitTurns.Pass {
        val key = ExitDesk.key(exchange, symbol)
        return try {
            turns.enter(key, who, on) { took, other, ms -> noteTurn(key, who, took, other, ms) }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (_: Exception) { turns.none() }
    }

    /** Zerodha's orders for an exit sender on [exchange]:[symbol]: [Broker.orders], read again while it misses an exit sent from here. */
    suspend fun orders(exchange: String, symbol: String, who: String): List<Broker.OrderRow> {
        val key = ExitDesk.key(exchange, symbol)
        // Review: what is missing is judged against THIS read's rows (another worker's later read showing the exit never vouches for it).
        return ExitDesk.settle(on, { Broker.orders() }, { r -> registry.missingFrom(ExitRegistry.Kind.ORDER, key, orderRows(r)) }) { s, m -> noteRead(key, who, "orders", s, m) }
    }

    /** Zerodha's GTTs for an exit sender, as [orders] does for the order book. */
    suspend fun gtts(exchange: String, symbol: String, who: String): List<Broker.GttRow> {
        val key = ExitDesk.key(exchange, symbol)
        return ExitDesk.settle(on, { Broker.gtts() }, { r -> registry.missingFrom(ExitRegistry.Kind.GTT, key, gttRows(r)) }) { s, m -> noteRead(key, who, "GTTs", s, m) }
    }

    /**
     * For a pass that read the books once for many contracts ([read]: that whole read of the orders, not only the working ones):
     * true when an exit sent from here on [exchange]:[symbol] is not in it (sent since, or the book trailing; on: the pass leaves
     * the contract to its next look, which reads both books again). Off (shadow): logged, false.
     */
    fun stale(exchange: String, symbol: String, who: String, read: List<Broker.OrderRow>): Boolean = runCatching {
        val key = ExitDesk.key(exchange, symbol)
        val miss = registry.missingFrom(ExitRegistry.Kind.ORDER, key, orderRows(read))
        if (miss.isEmpty()) return@runCatching false
        if (!on) { noteRead(key, who, "orders", ExitDesk.Settled.SHADOW_MISSED, miss); return@runCatching false }
        count { it.copy(readAgain = it.readAgain + 1) }
        record("$who $key: its pass's read of the orders missed ${words(miss)}; left to the next look, which reads both books again")
        true
    }.getOrDefault(false)

    // ---- notes ------------------------------------------------------------------------------------------------------------

    private fun noteTurn(key: String, who: String, took: ExitTurns.Took, other: String?, ms: Long) {
        when (took) {
            ExitTurns.Took.ALONE, ExitTurns.Took.AGAIN -> Unit
            ExitTurns.Took.WAITED -> { count { it.copy(waited = it.waited + 1, waitedMs = it.waitedMs + ms) }
                record("$who $key waited $ms ms for ${other ?: "another exit"} to read the books and send") }
            ExitTurns.Took.TWO_AT_ONCE -> { count { it.copy(twoAtOnce = it.twoAtOnce + 1) }
                record("$who $key: ${other ?: "another exit"} was reading the books and sending at the same moment (shadow: on, $who would wait its turn)") }
            ExitTurns.Took.LET_THROUGH -> { count { it.copy(turnLetThrough = it.turnLetThrough + 1) }
                record("$who $key: ${other ?: "another exit"} held the contract over ${ms / 1000} s, so this exit went without waiting longer") }
        }
    }

    private fun noteRead(key: String, who: String, book: String, s: ExitDesk.Settled, miss: List<ExitRegistry.Sent>) {
        when (s) {
            ExitDesk.Settled.WHOLE -> Unit
            ExitDesk.Settled.SHADOW_MISSED -> { count { it.copy(missedInShadow = it.missedInShadow + 1) }
                record("$who $key: its read of the $book missed ${words(miss)} (shadow: on, it would read again)") }
            ExitDesk.Settled.READ_AGAIN -> { count { it.copy(readAgain = it.readAgain + 1) }
                record("$who $key: its read of the $book missed ${words(miss)}; read again until it showed") }
            ExitDesk.Settled.LET_THROUGH -> { count { it.copy(letThrough = it.letThrough + 1) }
                record("$who $key: the $book still did not show ${words(miss)} after reading again; the exit went on that read") }
        }
    }

    private fun words(miss: List<ExitRegistry.Sent>): String = miss.joinToString(", ") { e ->
        val age = System.currentTimeMillis() - e.at
        "${e.by}'s ${e.side} ${e.qty} ${e.type} sent $age ms ago" + when (e.state) {
            ExitRegistry.State.SENDING -> " (still on its way)"
            ExitRegistry.State.LOST -> " (its reply lost)"
            ExitRegistry.State.ANSWERED -> " (order ${e.id})"
        }
    }

    private fun record(text: String) { runCatching { Diag.record("exits", "Exit coordinator: $text") } }

    private fun today(): String = runCatching { Market.today().toString() }.getOrDefault("")

    private fun count(f: (ExitDesk.Counts) -> ExitDesk.Counts) {
        val d = today()
        synchronized(lock) { counts = f(if (counts.day == d) counts else ExitDesk.Counts(day = d)) }
        publish()
    }

    /** The diagnostics' and Bot settings' line (today's counts). */
    fun line(): String {
        val c = synchronized(lock) { if (counts.day == today()) counts else ExitDesk.Counts() }
        return c.line(on)
    }

    fun refresh() { if (!loaded) load() else publish() }

    private fun publish() { runCatching { _view.value = View(on, line()) } }

    /** TEST ONLY: shadow, nothing noted. */
    internal fun resetForTest(on: Boolean = false) {
        this.on = on; loaded = true; switched = false
        registry.clear()
        turns = ExitTurns()
        synchronized(lock) { counts = ExitDesk.Counts() }
        _view.value = View(on)
    }

    /** TEST ONLY: the longest wait for a turn. */
    internal fun waitForTest(ms: Long) { turns = ExitTurns(ms) }

    /** TEST ONLY: as after a restart (memory gone, the switch as saved). */
    internal fun restartForTest() { registry.clear() }

    /** TEST ONLY: the exits sent and not yet shown by a read. */
    internal fun pendingForTest(): List<ExitRegistry.Sent> = registry.pending()

    internal fun countsForTest(): ExitDesk.Counts = synchronized(lock) { counts }
}
