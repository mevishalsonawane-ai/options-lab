package com.optionslab.ira

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 20 Oct, enhancement round 2 (idea 1): ONE exit coordinator per contract, for every exit sender (the 15:05 expiry square-off,
 * the arms' live trades, ORB and Liquidity, Pine, the strategies, the protections, MCX's expiry exit, Boss's own exits).
 *
 * Every sender already reads Zerodha's orders first, then the positions, and sends only what is held less what is already on its
 * way out (ExitQty.sendable). Two gaps were left, and each could sell one contract twice (a short):
 *  1. Two senders at once: both read the books before either's order was in them, and both sold all that is held.
 *  2. The book trails the order: an exit sent a moment ago is not in the next read yet, so it was not subtracted.
 *
 * The coordinator closes both, and only by making each sender's own reads true; it never decides a quantity itself:
 *  - [ExitTurns]: one sender at a time on a contract, from its books' read to its order's answer. Waited for at most
 *    [ExitTurns.WAIT_MS]; past that the exit goes anyway (logged), so it can never hold back a needed exit for long.
 *  - [ExitRegistry]: every exit this phone sent (orders and GTTs, all senders, Boss's own too) until a read of Zerodha's book
 *    shows it. [settle]: a read missing one of them is read again, a few times, briefly; still missing (a lost reply that never
 *    became an order), the read stands as it is and the exit goes, logged. It never adds to or subtracts from a quantity.
 *
 * Both start in SHADOW (CLAUDE.md: anything that could skip or resize a live trade starts logged only, behind a switch that is
 * off): off, nothing waits and nothing is read again; what it would have done is only counted and logged. Memory only (no disk,
 * no settings, no network of its own): it sits on the order path. Pure, apart from the coroutine lock.
 */
object ExitDesk {
    /** How long a sent exit is looked for in the books before it is let go (a lost reply that never became an order). */
    const val KEEP_MS = 60_000L
    /** The waits before each read again of a book that is missing an exit sent from here (about 1.8 s in all). */
    val REREAD_MS = longArrayOf(300, 600, 900)

    /** The contract's key: the exchange and the symbol (both products, both sides: coarse, so it is never too narrow). */
    fun key(exchange: String, symbol: String): String = "${exchange.uppercase()}:${symbol.uppercase()}"

    /** What [settle] did with one read. */
    enum class Settled { WHOLE, READ_AGAIN, SHADOW_MISSED, LET_THROUGH }

    /**
     * The read from [read] in which none of [missing] is left: read again after each of [waits] while one is (switch [on]).
     * Off (shadow): read once, and a read missing one is only noted. On: still missing after the last wait, that read stands
     * (the exit is let through, as before the coordinator) and noted. [note] gets the outcome and what was missing.
     */
    suspend fun <R> settle(on: Boolean, read: suspend () -> R, missing: (R) -> List<ExitRegistry.Sent>,
                           waits: LongArray = REREAD_MS, note: (Settled, List<ExitRegistry.Sent>) -> Unit = { _, _ -> }): R {
        var r = read()
        var miss = missing(r)
        if (miss.isEmpty()) { note(Settled.WHOLE, miss); return r }
        if (!on) { note(Settled.SHADOW_MISSED, miss); return r }
        val first = miss
        for (w in waits) {
            delay(w)
            r = read()
            miss = missing(r)
            if (miss.isEmpty()) { note(Settled.READ_AGAIN, first); return r }
        }
        note(Settled.LET_THROUGH, miss)
        return r
    }

    /** One day's counts, for the diagnostics. */
    data class Counts(
        val day: String = "",
        val sent: Int = 0,
        val twoAtOnce: Int = 0,
        val waited: Int = 0,
        val waitedMs: Long = 0,
        val missedInShadow: Int = 0,
        val readAgain: Int = 0,
        val letThrough: Int = 0,
        val turnLetThrough: Int = 0,
    ) {
        fun line(on: Boolean): String = buildString {
            append("Exit coordinator: ").append(if (on) "on" else "shadow (logged only)").append("; exits sent today $sent")
            if (on) {
                append("; waited for another exit on the same contract $waited")
                if (waited > 0) append(" (${waitedMs / waited} ms on average)")
                append("; books read again until the last exit showed $readAgain; let through $letThrough")
                if (turnLetThrough > 0) append("; let through after the longest wait $turnLetThrough")
            } else {
                append("; two exits on one contract at once $twoAtOnce; a book read missing an exit just sent $missedInShadow")
                append(" (on, each would wait or read again)")
            }
        }
    }
}

/**
 * 20 Oct, enhancement round 2: every exit this phone sent, by contract, until a read of Zerodha's book shows it ([ExitDesk]).
 * Thread-safe; memory only; [clock] in ms. A restart starts it empty: what was sent before is in Zerodha's book by then.
 */
class ExitRegistry(private val keepMs: Long = ExitDesk.KEEP_MS, private val clock: () -> Long = System::currentTimeMillis) {
    enum class Kind { ORDER, GTT }
    /** SENDING: its request is on its way. ANSWERED: Zerodha gave its id. LOST: its reply was lost (it may or may not exist). */
    enum class State { SENDING, ANSWERED, LOST }

    data class Sent(val ticket: Long, val kind: Kind, val key: String, val side: String, val qty: Int, val type: String,
                    val by: String, val at: Long, val id: String? = null, val state: State = State.SENDING)

    /** One row of a read of Zerodha's book: an order (or a GTT, [Kind.GTT]). [placedAt]: when Zerodha placed it (ms), if known. */
    data class Row(val id: String, val key: String, val side: String, val qty: Int, val type: String, val placedAt: Long? = null)

    private val lock = Any()
    private val sent = LinkedHashMap<Long, Sent>()
    private var next = 0L

    /** An exit about to be sent; its ticket. */
    fun sending(kind: Kind, key: String, side: String, qty: Int, type: String, by: String): Long = synchronized(lock) {
        prune()
        val t = ++next
        sent[t] = Sent(t, kind, key, side.uppercase(), qty, type.uppercase(), by, clock())
        t
    }

    /** Zerodha answered with its id. */
    fun answered(ticket: Long, id: String) = synchronized(lock) {
        sent[ticket]?.let { sent[ticket] = it.copy(id = id, state = State.ANSWERED) }
        Unit
    }

    /** Its reply was lost: it may exist; looked for by its fields until [keepMs]. */
    fun lost(ticket: Long) = synchronized(lock) {
        sent[ticket]?.let { if (it.state == State.SENDING) sent[ticket] = it.copy(state = State.LOST) }
        Unit
    }

    /** Zerodha refused it (or it was never sent): nothing to look for. */
    fun refused(ticket: Long) = synchronized(lock) { sent.remove(ticket); Unit }

    /** A lost reply's order found in the book under [id] (the sender's own look): it is that order now. */
    fun adopted(kind: Kind, key: String, side: String, qty: Int, type: String, id: String) = synchronized(lock) {
        val e = sent.values.firstOrNull { it.state == State.LOST && it.kind == kind && it.key == key && it.side == side.uppercase() &&
            it.qty == qty && it.type == type.uppercase() }
        if (e != null) sent[e.ticket] = e.copy(id = id, state = State.ANSWERED)
        Unit
    }

    /**
     * A read of the book ([rows], of [kind]): every sent one it shows is done with (the book will not lose it). A lost reply is
     * taken as shown by a row of the same contract, side, size and type placed since a minute before it was sent, whose id is
     * not another one's.
     */
    fun seen(kind: Kind, rows: Collection<Row>) = synchronized(lock) {
        if (sent.isEmpty()) return@synchronized
        val ids = rows.mapTo(HashSet()) { it.id }
        val known = sent.values.mapNotNullTo(HashSet()) { it.id }
        val gone = ArrayList<Long>()
        for (e in sent.values) {
            if (e.kind != kind) continue
            when (e.state) {
                State.ANSWERED -> if (e.id in ids) gone += e.ticket
                State.LOST -> if (rows.any { r -> r.key == e.key && r.side.uppercase() == e.side && r.qty == e.qty &&
                        r.type.uppercase() == e.type && r.id !in known && (r.placedAt == null || r.placedAt >= e.at - 60_000) }) gone += e.ticket
                State.SENDING -> Unit
            }
        }
        gone.forEach { sent.remove(it) }
        prune()
    }

    /** Those of [kind] on contract [key] not shown by a read yet (on their way, or answered but not in the book yet). */
    fun unseen(kind: Kind, key: String): List<Sent> = synchronized(lock) {
        prune()
        sent.values.filter { it.kind == kind && it.key == key }
    }

    /** All not shown yet (any contract). */
    fun pending(): List<Sent> = synchronized(lock) { prune(); sent.values.toList() }

    fun clear() = synchronized(lock) { sent.clear() }

    /** Older than [keepMs]: let go (a request never takes that long; a lost reply never shown never became an order). */
    private fun prune() {
        val now = clock()
        sent.values.removeIf { now - it.at > keepMs }
    }
}
/**
 * 20 Oct, enhancement round 2: one exit sender at a time on a contract ([ExitDesk]). [enter] gives a [Pass], left with
 * [Pass.leave] (the app wraps it as an inline function, so a sender's own early returns still work). Re-entrant within one
 * coroutine (a sender inside its turn that puts its stop back takes no second turn). Waited for at most [waitMs]; past that the
 * sender goes anyway.
 */
class ExitTurns(private val waitMs: Long = WAIT_MS) {
    companion object { const val WAIT_MS = 4_000L }

    /** What a turn did: [ALONE] nobody else; [AGAIN] the same sender's turn already; [WAITED] it waited for another;
     *  [TWO_AT_ONCE] another was in (shadow: not waited for); [LET_THROUGH] it waited the longest and went anyway. */
    enum class Took { ALONE, AGAIN, WAITED, TWO_AT_ONCE, LET_THROUGH }

    internal class Holder(val job: Job?, val who: String) { var depth = 1 }

    /** A turn taken; [leave] it once (a second call does nothing). */
    class Pass internal constructor(private val turns: ExitTurns?, private val key: String, private val holder: Holder?,
                                    private val mutex: Mutex?, val took: Took) {
        private var left = false
        fun leave() {
            if (left) return
            left = true
            val h = holder ?: return
            if (--h.depth > 0) return
            turns?.release(key, h, mutex)
        }
    }

    private val locks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private val holders = java.util.concurrent.ConcurrentHashMap<String, Holder>()

    /**
     * Contract [key]'s turn for [who]. [on] false (shadow): never waits; another inside is only noted. [note] gets what happened,
     * the other sender's name (if any) and the ms waited.
     */
    suspend fun enter(key: String, who: String, on: Boolean, note: (Took, String?, Long) -> Unit = { _, _, _ -> }): Pass {
        val job = currentCoroutineContext()[Job]
        holders[key]?.let { h -> if (job != null && h.job === job) { h.depth++; note(Took.AGAIN, null, 0); return Pass(this, key, h, null, Took.AGAIN) } }
        if (!on) {
            val mine = Holder(job, who)
            val other = holders.putIfAbsent(key, mine)
            if (other != null) { note(Took.TWO_AT_ONCE, other.who, 0); return Pass(null, key, null, null, Took.TWO_AT_ONCE) }
            note(Took.ALONE, null, 0)
            return Pass(this, key, mine, null, Took.ALONE)
        }
        val m = locks.getOrPut(key) { Mutex() }
        val t0 = System.currentTimeMillis()
        val free = m.tryLock()
        val other = if (free) null else holders[key]?.who
        val got = free || (withTimeoutOrNull(waitMs) { m.lock(); true } ?: false)
        val took = when { free -> Took.ALONE; got -> Took.WAITED; else -> Took.LET_THROUGH }
        note(took, other, System.currentTimeMillis() - t0)
        if (!got) return Pass(null, key, null, null, took)
        val mine = Holder(job, who)
        holders[key] = mine
        return Pass(this, key, mine, m, took)
    }

    private fun release(key: String, h: Holder, m: Mutex?) {
        holders.remove(key, h)
        m?.unlock()
    }

    /** A turn not taken (the sender goes as before): what a failure to take one gives. */
    fun none(): Pass = Pass(null, "", null, null, Took.LET_THROUGH)

    /** Who is inside each turn now (diagnostics). */
    fun busy(): Map<String, String> = holders.mapValues { it.value.who }
}
