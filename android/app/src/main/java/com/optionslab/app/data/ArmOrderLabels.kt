package com.optionslab.app.data

import com.optionslab.engine.IST
import com.optionslab.engine.Kite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 15 Nov (audit of Boss's diagnostics: 27 live orders Zerodha rejected, "Insufficient funds", were shown with no arm): which arm
 * sent a live order, noted BEFORE it is sent, so an order Zerodha rejects is still shown with its arm.
 *
 * Before, an arm's label was written only once Zerodha's answer gave the order's id ([Strategies.tagOwner]). An order Zerodha
 * answered with an error but still put in its order book as REJECTED (the id never reached the phone) had no label at all.
 *
 * On the order path this is memory only: a few fields under a short lock - no disk, no vault, no network, nothing that waits.
 * - [sending]: the order's contract, side, quantity and tag with the arm's label, before it goes.
 * - [answered]: Zerodha gave its id: the label is kept by that id at once.
 * - [refused]: Zerodha answered with a refusal and no id: the try may now name a REJECTED order found in the order book.
 * - [labelOf] (the order review and the diagnostics, never the order path): a REJECTED order with nothing filled, the same
 *   contract, side, quantity and tag, placed within [BEFORE_MS] before to [AFTER_MS] after a refused try, takes that try's label -
 *   one order a try, the nearest in time. It is then also written to the app's order labels in the background ([keep]), so a
 *   restart keeps it.
 *
 * A reply that was LOST (the order may exist) is never matched here: the arm's own look finds that order and labels it.
 * Bounded: the notes of an earlier day are dropped with the first order of a new day, and never more than [KEPT] of each.
 */
object ArmOrderLabels {
    /** How many tries, and how many ids, are kept at most (a day's live orders are far fewer). */
    internal const val KEPT = 500
    /** A rejected order may be stamped this long before the phone's own send time (the two clocks differ a little)... */
    internal const val BEFORE_MS = 2 * 60_000L
    /** ...or this long after it. */
    internal const val AFTER_MS = 3 * 60_000L

    private class Try(val ticket: Long, val label: String, val symbol: String, val exchange: String, val side: String, val qty: Int,
                      val tag: String, val atMs: Long, var refused: Boolean = false, var id: String? = null)

    /** Guards the three fields below; held only for a moment, never through a write or a read of anything else. */
    private val guard = Any()
    private var day: LocalDate? = null
    private val tries = ArrayDeque<Try>()
    private val byId = LinkedHashMap<String, String>()
    private var seq = 0L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * What keeps a label found by [labelOf] past a restart: (order id, label) into the app's order labels, in the background.
     * TEST SEAM: a test puts its own recorder here. Its setter throws unless BuildConfig.DEBUG.
     */
    @Volatile internal var keep: (String, String) -> Unit = { id, label -> scope.launch { runCatching { Strategies.tagOwner("kite:$id", label) } } }
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test seam exists only in debug builds" }; field = v }

    private fun dayOf(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(IST).toLocalDate()

    /** A new day's first note drops the earlier day's. Called under [guard]. */
    private fun roll(nowMs: Long) {
        val today = dayOf(nowMs)
        if (day != today) { day = today; tries.clear(); byId.clear() }
    }

    /** The tag as Zerodha keeps it (letters and digits, 20 at most), as [Broker.findRecent] compares it. */
    private fun tagOf(tag: String): String = tag.filter { it.isLetterOrDigit() }.take(20)

    /** [o] is about to be sent for [label] ("R51 · live entry"), at [nowMs]. The ticket for [refused]. Memory only; never throws. */
    fun sending(o: Kite.Order, label: String, nowMs: Long): Long = synchronized(guard) {
        roll(nowMs)
        val t = Try(++seq, label, o.tradingSymbol, o.exchange, o.side.name, o.quantity, tagOf(o.tag), nowMs)
        tries.addLast(t)
        while (tries.size > KEPT) tries.removeFirst()
        t.ticket
    }

    /** Zerodha gave order [id] for [label]: kept by its id at once. Memory only; never throws. */
    fun answered(id: String, label: String, nowMs: Long) {
        if (id.isBlank()) return
        synchronized(guard) {
            roll(nowMs)
            byId[id] = label
            while (byId.size > KEPT) byId.remove(byId.keys.first())
        }
    }

    /**
     * True when [e] from [Broker.placeOrder] is Zerodha's own refusal (its error types end in "Exception"; a lost reply is its
     * NetworkException) - not the app's own refusal at the door ("entry_gate", "paper_only", "fingerprint", "static_ip": nothing
     * was sent, so no order of it can be in Zerodha's book), and not "not logged in".
     */
    fun zerodhaRefused(e: Throwable): Boolean = e is Broker.KiteError && e.type.endsWith("Exception") && e.type != "NetworkException"

    /** Zerodha refused the order of [ticket] (a definite answer, no id): it may now name a REJECTED order in the book. Memory only. */
    fun refused(ticket: Long) {
        synchronized(guard) { tries.firstOrNull { it.ticket == ticket }?.refused = true }
    }

    private fun rowMs(s: String): Long? =
        runCatching { LocalDateTime.parse(s.trim().replace(' ', 'T').take(19)).atZone(IST).toInstant().toEpochMilli() }.getOrNull()

    /**
     * The arm's label for order book row [row], or null when this object knows none: by its id ([answered]), else - for a
     * REJECTED order with nothing filled - the nearest refused try with the same contract, side, quantity and tag. Never on
     * the order path (a first match is handed to [keep]).
     */
    fun labelOf(row: Broker.OrderRow): String? {
        var found: String? = null
        val label = synchronized(guard) {
            byId[row.id]?.let { return@synchronized it }
            if (row.status != "REJECTED" || row.filled > 0) return@synchronized null
            val at = rowMs(row.placedAt) ?: return@synchronized null
            val t = tries.filter { it.refused && it.id == null && it.symbol == row.symbol && it.exchange == row.exchange && it.side == row.side &&
                it.qty == row.qty && it.tag == row.tag && at >= it.atMs - BEFORE_MS && at <= it.atMs + AFTER_MS }
                .minByOrNull { kotlin.math.abs(at - it.atMs) } ?: return@synchronized null
            t.id = row.id
            byId[row.id] = t.label
            while (byId.size > KEPT) byId.remove(byId.keys.first())
            found = t.label
            t.label
        }
        // Outside the lock: the background write is started, never waited for.
        found?.let { l -> runCatching { keep(row.id, l) } }
        return label
    }

    /** TEST ONLY: how many tries and ids are kept now. */
    internal fun sizesForTest(): Pair<Int, Int> = synchronized(guard) { tries.size to byId.size }

    /** TEST ONLY: everything forgotten. Throws unless BuildConfig.DEBUG. */
    internal fun resetForTest() {
        check(com.optionslab.app.BuildConfig.DEBUG) { "the test reset exists only in debug builds" }
        synchronized(guard) { day = null; tries.clear(); byId.clear(); seq = 0L }
    }
}
