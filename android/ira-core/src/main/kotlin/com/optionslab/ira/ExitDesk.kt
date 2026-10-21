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
    /** 21 Oct, round 3: the positions-lag guard's reads again, waits and reads together, at most this long ([settleHeld]). */
    const val LAG_BUDGET_MS = 1_200L
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

    // ---- 21 Oct, enhancement round 3 (idea 1): the positions-lag guard ---------------------------------------------------------

    /** One order of a read of the order book on a contract (this product), as the lag guard needs it. */
    data class Fill(val id: String, val side: String, val filled: Int)

    /**
     * How much of what Zerodha's ORDER book shows filled on the exit side of a held [net] the POSITIONS read does not show yet
     * (0 when it shows all of it, or when this cannot be told).
     *
     * An exit that filled shows COMPLETE in the orders a moment before the positions move; in that moment a second sender reads
     * the old position, finds nothing working (the first exit is done, not working) and sells all of it again: a short. The
     * day's fills are compared, both from today's books: [fills] (today's orders on the contract and product) against the
     * positions' own day quantity on the exit side ([dayExitQty]: Kite's day_sell_quantity for a long, day_buy_quantity for a
     * short; null when the read did not give it: never guessed). Only when one of the exits sent from here a moment ago
     * ([mine], their order ids) has filled: the guard looks after this phone's own recent fills, as round 2's idea said.
     * At most |[net]| (it never turns the position round).
     */
    fun positionsLag(net: Int, dayExitQty: Int?, fills: List<Fill>, mine: Set<String>): Int {
        if (net == 0 || dayExitQty == null) return 0
        val exitSide = if (net > 0) "SELL" else "BUY"
        val exits = fills.filter { it.side.equals(exitSide, ignoreCase = true) && it.filled > 0 }
        if (exits.none { it.id in mine }) return 0
        val lag = exits.sumOf { it.filled } - dayExitQty.coerceAtLeast(0)
        return lag.coerceIn(0, kotlin.math.abs(net))
    }

    /** [net] less [lag] toward zero: what is held once the fills the positions trail are counted. */
    fun heldLess(net: Int, lag: Int): Int = if (net > 0) (net - lag).coerceAtLeast(0) else if (net < 0) (net + lag).coerceAtMost(0) else 0

    /** What [settleHeld] did with one positions read. */
    enum class Lag { NONE, SHADOW, READ_AGAIN, SUBTRACTED }

    /**
     * The positions read for a sender, with what it still trails ([lag] of each read): read again after each of [waits] while it
     * trails a fill (switch [on]). Off (shadow): read once, and a trailing read is only noted (the sender goes on it, as before
     * this round). On: still trailing after the last wait, the read stands with that much counted as already sold (the order
     * book's fill is Zerodha's own word; it only ever LOWERS what is sent, never refuses an exit of what is still held).
     * Returns the last read and the lag left in it (0 unless [Lag.SUBTRACTED]).
     *
     * Review: the reads again stop once [budgetMs] would be passed (waits and reads together, by [clock]), so a sender inside its
     * contract's turn never holds it near [ExitTurns.WAIT_MS] (a waiting sender would then be let through beside it).
     */
    suspend fun <R> settleHeld(on: Boolean, read: suspend () -> R, lag: (R) -> Int, waits: LongArray = REREAD_MS,
                               budgetMs: Long = LAG_BUDGET_MS, clock: () -> Long = System::currentTimeMillis,
                               note: (Lag, Int) -> Unit = { _, _ -> }): Pair<R, Int> {
        var r = read()
        var l = lag(r)
        if (l <= 0) { note(Lag.NONE, 0); return r to 0 }
        if (!on) { note(Lag.SHADOW, l); return r to 0 }
        val first = l
        val t0 = clock()
        for (w in waits) {
            if (clock() - t0 + w > budgetMs) break
            delay(w)
            r = read()
            l = lag(r)
            if (l <= 0) { note(Lag.READ_AGAIN, first); return r to 0 }
        }
        note(Lag.SUBTRACTED, l)
        return r to l
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
        /** 21 Oct, round 3: positions reads that trailed an exit's fill (shadow: noted only), read again until they showed it,
         *  and those still trailing after the last read (the fill counted as sold). */
        val lagShadow: Int = 0,
        val lagReadAgain: Int = 0,
        val lagSubtracted: Int = 0,
    ) {
        /** 21 Oct, round 3: the positions-lag guard's part of the line ([lagOn]: its own switch). */
        fun lagLine(lagOn: Boolean): String =
            if (lagOn) "Positions-lag guard: on; positions read again until an exit's fill showed $lagReadAgain; fill counted as sold $lagSubtracted"
            else "Positions-lag guard: shadow (logged only); a positions read trailing an exit's fill $lagShadow (on, it would read again)"

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
 * 20 Oct, enhancement round 2: every exit this phone sent in the last [keepMs], by contract ([ExitDesk]). [missingFrom] says which
 * of them a given read of Zerodha's book does not show; the answer is about THAT read (review: one read showing an exit never
 * vouches for another read that began before it). Thread-safe; memory only; [clock] in ms. A restart starts it empty: what was
 * sent before is in Zerodha's book by then.
 */
class ExitRegistry(private val keepMs: Long = ExitDesk.KEEP_MS, private val clock: () -> Long = System::currentTimeMillis) {
    enum class Kind { ORDER, GTT }
    /** SENDING: its request is on its way. ANSWERED: Zerodha gave its id. LOST: its reply was lost (it may or may not exist). */
    enum class State { SENDING, ANSWERED, LOST }

    /** [seen]: a read has shown it once (kept until [keepMs] all the same, for reads that began before that one). */
    data class Sent(val ticket: Long, val kind: Kind, val key: String, val side: String, val qty: Int, val type: String,
                    val by: String, val at: Long, val id: String? = null, val state: State = State.SENDING, val seen: Boolean = false)

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

    /** It is gone at Zerodha by the phone's own hand (a GTT deleted): never looked for again. */
    fun forget(kind: Kind, id: String) = synchronized(lock) { sent.values.removeIf { it.kind == kind && it.id == id }; Unit }

    /** A lost reply's order found in the book under [id] (the sender's own look): it is that order now. */
    fun adopted(kind: Kind, key: String, side: String, qty: Int, type: String, id: String) = synchronized(lock) {
        val e = sent.values.firstOrNull { it.state == State.LOST && it.kind == kind && it.key == key && it.side == side.uppercase() &&
            it.qty == qty && it.type == type.uppercase() }
        if (e != null) sent[e.ticket] = e.copy(id = id, state = State.ANSWERED)
        Unit
    }

    /**
     * The row of [rows] that shows [e], or null. An answered one by its id; a lost reply by a row of the same contract, side, size
     * and type, placed no more than [SKEW_MS] before it was sent, whose id is no other exit's sent from here (review: an earlier
     * identical exit is never taken for it); one still on its way never.
     */
    private fun shownBy(e: Sent, rows: Collection<Row>, ids: Set<String>, others: Set<String>): Row? = when (e.state) {
        State.ANSWERED -> if (e.id in ids) rows.firstOrNull { it.id == e.id } else null
        State.LOST -> rows.firstOrNull { r -> r.key == e.key && r.side.uppercase() == e.side && r.qty == e.qty && r.type.uppercase() == e.type &&
            r.id !in others && r.placedAt != null && r.placedAt >= e.at - SKEW_MS }
        State.SENDING -> null
    }

    /** A read of the book ([rows], of [kind]): what it shows is marked seen (a lost reply it shows takes that row's id). */
    fun seen(kind: Kind, rows: Collection<Row>) = synchronized(lock) {
        prune()
        if (sent.isEmpty()) return@synchronized
        val ids = rows.mapTo(HashSet()) { it.id }
        val known = sent.values.mapNotNullTo(HashSet()) { it.id }
        for (e in sent.values.toList()) {
            if (e.kind != kind) continue
            val r = shownBy(e, rows, ids, known) ?: continue
            sent[e.ticket] = e.copy(id = r.id, state = State.ANSWERED, seen = true)
        }
    }

    /** Those of [kind] on contract [key] that the read [rows] does not show (on their way, answered after it began, or lost). */
    fun missingFrom(kind: Kind, key: String, rows: Collection<Row>): List<Sent> = synchronized(lock) {
        prune()
        val mine = sent.values.filter { it.kind == kind && it.key == key }
        if (mine.isEmpty()) return@synchronized emptyList()
        val ids = rows.mapTo(HashSet()) { it.id }
        val known = sent.values.mapNotNullTo(HashSet()) { it.id }
        mine.filter { shownBy(it, rows, ids, known) == null }
    }

    /** Those of [kind] on contract [key] no read has shown yet. */
    fun unseen(kind: Kind, key: String): List<Sent> = synchronized(lock) {
        prune()
        sent.values.filter { it.kind == kind && it.key == key && !it.seen }
    }

    /** 21 Oct, round 3: the ids Zerodha gave the exits of [kind] on [key] sent in the last [keepMs] (shown or not yet). */
    fun recentIds(kind: Kind, key: String): Set<String> = synchronized(lock) {
        prune()
        sent.values.filter { it.kind == kind && it.key == key }.mapNotNullTo(HashSet()) { it.id }
    }

    /** All no read has shown yet (any contract). */
    fun pending(): List<Sent> = synchronized(lock) { prune(); sent.values.filter { !it.seen } }

    fun clear() = synchronized(lock) { sent.clear() }

    /** Older than [keepMs]: let go (a request never takes that long; a lost reply never shown never became an order). */
    private fun prune() {
        val now = clock()
        sent.values.removeIf { now - it.at > keepMs }
    }

    companion object {
        /** How much earlier than its send (the phone's clock) Zerodha may stamp a lost reply's order. */
        const val SKEW_MS = 5_000L
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

    /** [mutex]: the lock it holds (null: in shadow, or let through without it). */
    internal class Holder(val job: Job?, val who: String, val mutex: Mutex? = null) { var depth = 1 }

    /** A turn taken; [leave] it once (a second call does nothing). */
    class Pass internal constructor(private val turns: ExitTurns?, private val key: String, private val holder: Holder?, val took: Took) {
        private var left = false
        fun leave() {
            if (left) return
            left = true
            val h = holder ?: return
            if (--h.depth > 0) return
            turns?.release(key, h)
        }
    }

    private val locks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private val holders = java.util.concurrent.ConcurrentHashMap<String, Holder>()
    /** Senders on a contract without its lock (let through after the wait, or two at once in shadow), by contract and job:
     *  their own nested turns go straight in (review: a let-through sender putting its stop back never waits a second time). */
    private val guests = java.util.concurrent.ConcurrentHashMap<Pair<String, Job>, Holder>()

    /**
     * Contract [key]'s turn for [who]. [on] false (shadow): never waits; another inside is only noted. [note] gets what happened,
     * the other sender's name (if any) and the ms waited.
     */
    suspend fun enter(key: String, who: String, on: Boolean, note: (Took, String?, Long) -> Unit = { _, _, _ -> }): Pass {
        val job = currentCoroutineContext()[Job]
        val again = holders[key]?.takeIf { job != null && it.job === job } ?: job?.let { guests[key to it] }
        if (again != null) { again.depth++; note(Took.AGAIN, null, 0); return Pass(this, key, again, Took.AGAIN) }
        if (!on) {
            val mine = Holder(job, who)
            val other = holders.putIfAbsent(key, mine)
            if (other != null) { note(Took.TWO_AT_ONCE, other.who, 0); return Pass(this, key, guest(key, job, who), Took.TWO_AT_ONCE) }
            note(Took.ALONE, null, 0)
            return Pass(this, key, mine, Took.ALONE)
        }
        val m = locks.getOrPut(key) { Mutex() }
        val t0 = System.currentTimeMillis()
        val free = m.tryLock()
        val other = if (free) null else holders[key]?.who
        // Review: the flag is set inside the block, so a timeout firing just as the lock is taken never leaves it locked unseen
        // (withTimeoutOrNull can drop a block's result after lock() returned).
        var got = free
        if (!free) withTimeoutOrNull(waitMs) { m.lock(); got = true }
        val took = when { free -> Took.ALONE; got -> Took.WAITED; else -> Took.LET_THROUGH }
        note(took, other, System.currentTimeMillis() - t0)
        if (!got) return Pass(this, key, guest(key, job, who), took)
        val mine = Holder(job, who, m)
        holders[key] = mine
        return Pass(this, key, mine, took)
    }

    private fun guest(key: String, job: Job?, who: String): Holder? = job?.let { j -> Holder(j, who).also { guests[key to j] = it } }

    internal fun release(key: String, h: Holder) {
        val j = h.job
        if (j != null && guests.remove(key to j, h)) return
        holders.remove(key, h)
        h.mutex?.unlock()
    }

    /** A turn not taken (the sender goes as before): what a failure to take one gives. */
    fun none(): Pass = Pass(null, "", null, Took.LET_THROUGH)

    /** Who is inside each turn now (diagnostics). */
    fun busy(): Map<String, String> = holders.mapValues { it.value.who }
}
