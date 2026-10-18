package com.optionslab.app.data

import com.optionslab.app.security.SecurePrefs
import com.optionslab.ira.OrderSpeed
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Order speed (Boss, 9 Oct): the app's timings of each step from a signal to the fill ([OrderSpeed] keeps and says them).
 * In memory first; written to the settings vault lazily ([SecurePrefs.putAllLazy]: at most once a minute, coalesced with
 * any other write) and only when an order step or a round trip was added - never per price. Nothing here blocks, takes a
 * lock that a write holds, or reaches the network; every call is safe from any thread (the screen reads [card]).
 * Durations only: never a symbol's account, a key, a token or a client id.
 */
object OrderTiming {
    private const val KEY = "speed.day"

    /**
     * What woke an arm (a stream tick or a candle close, [FastPath]; the watch's bar-close check): when it reached the phone
     * ([triggerMs]) and the exchange's stamp of its price ([exchMs], null when unknown). [decidedAt] is set by the first
     * [decided] in that run. Carried in the coroutine context, so an order placed anywhere under it is timed from it.
     */
    class Trigger(val triggerMs: Long, val exchMs: Long?, source: OrderSpeed.Source = OrderSpeed.Source.FEED) :
        AbstractCoroutineContextElement(Trigger) {
        companion object Key : CoroutineContext.Key<Trigger>
        @Volatile var startedAt: Long = System.currentTimeMillis()
        @Volatile var decidedAt: Long = 0L
        /**
         * Where this run's prices came from (round 2): a stream tick, a local candle built from ticks, or the candle feed /
         * Zerodha's quote. Set by [FastPath] per lane, and lowered to the feed by [candles] when an arm had to read it.
         */
        @Volatile var source: OrderSpeed.Source = source
        /** An arm told where its candles came from in this run ([candles]): the first word sets it, a feed read lowers it. */
        @Volatile var candlesTold: Boolean = false
    }

    private val lock = Any()
    private var book: OrderSpeed.Book? = null
    private var fillsOnStream = 0
    private var fillsKnown = 0

    /** Today (IST) as the book's day. */
    private fun today(): String = runCatching { Market.today().toString() }.getOrDefault(java.time.LocalDate.now().toString())

    /** Today's book: read once from the vault (a cached map read), a fresh one on a new day. Under [lock]. */
    private fun bookLocked(): OrderSpeed.Book {
        val day = today()
        book?.takeIf { it.day == day }?.let { return it }
        val read = runCatching { OrderSpeed.decode(SecurePrefs.getString(KEY), day) }.getOrDefault(OrderSpeed.Book(day))
        if (book?.day != day) { fillsOnStream = 0; fillsKnown = 0 }
        book = read
        return read
    }

    /**
     * 18 Oct (speed review): the stream's socket thread is the tick path, and [lock] can be held through [bookLocked]'s vault
     * read (the day's first figure, or the first after a restart) - a vault read, and a wait behind any vault write, on the
     * thread every price arrives on. The socket thread's figures (the clock offset, the stream gap) are now only queued here,
     * lock-free, and folded into the book by the next look at it under [lock] ([withBook]): any order step, the card, the
     * health line. At most [PENDING_CAP] wait (the oldest go first); memory only, as before. The stream's drop is queued
     * the same way ([streamDropped], [step] null). Each carries its day: one from an earlier day (queued before a night with
     * no look) is dropped, never filed under today's book.
     */
    private class Pending(val step: OrderSpeed.Step?, val ms: Long, val day: String)
    private val pending = java.util.concurrent.ConcurrentLinkedQueue<Pending>()
    private val pendingCount = java.util.concurrent.atomic.AtomicInteger()
    private const val PENDING_CAP = 64

    /** From any thread, never locks or reads anything: [step]'s [ms] queued for the book. */
    private fun queue(step: OrderSpeed.Step?, ms: Long) {
        pending.offer(Pending(step, ms, today()))
        if (pendingCount.incrementAndGet() > PENDING_CAP && pending.poll() != null) pendingCount.decrementAndGet()
    }

    /** Today's book with the queued figures folded in. Under [lock]. */
    private fun withBook(): OrderSpeed.Book {
        val b = bookLocked()
        while (true) {
            val p = pending.poll() ?: break
            pendingCount.decrementAndGet()
            if (p.day != b.day) continue
            if (p.step == null) b.dropped() else b.add(p.step, false, p.ms)
        }
        return b
    }

    private fun add(step: OrderSpeed.Step, live: Boolean, ms: Long, keep: Boolean) {
        val text = synchronized(lock) {
            val b = withBook()
            if (!b.add(step, live, ms) || !keep) null else b.encode()
        } ?: return
        runCatching { SecurePrefs.putAllLazy(mapOf(KEY to text)) }
    }

    /**
     * The arm in the current run decided to trade now (the first call counts, and is counted by where its price came from:
     * the card's "decided from the stream / a local candle / the feed"). Safe anywhere; a no-op outside a [Trigger].
     */
    suspend fun decided() {
        val t = currentCoroutineContext()[Trigger] ?: return
        if (t.decidedAt == 0L) {
            t.decidedAt = System.currentTimeMillis()
            count(t.source)
        }
    }

    /** One decision taken on a price from [src] (memory; written with the next order step). */
    private fun count(src: OrderSpeed.Source) {
        synchronized(lock) { withBook().decidedFrom(src) }
    }

    /**
     * The candles an arm read in the current run came from [src] ([OrderSpeed.Source.LOCAL_CANDLE] or
     * [OrderSpeed.Source.FEED]): the first word sets the run's source; a later read from the feed lowers it to the feed
     * (the slower source is the one said). Safe anywhere; a no-op outside a [Trigger].
     */
    suspend fun candles(src: OrderSpeed.Source) {
        val t = currentCoroutineContext()[Trigger] ?: return
        if (!t.candlesTold) { t.candlesTold = true; t.source = src }
        else if (src == OrderSpeed.Source.FEED) t.source = src
    }

    /** A live entry's margin check took [ms] (the kept snapshot, or Zerodha's read when it was needed). */
    fun marginCheck(ms: Long) = add(OrderSpeed.Step.MARGIN_CHECK, true, ms, keep = true)

    /** A resting exchange stop was cancelled (and confirmed gone) in [ms] before an app exit. */
    fun stopCancel(ms: Long) = add(OrderSpeed.Step.STOP_CANCEL, true, ms, keep = true)

    /**
     * The price stream was down [ms] (from the drop to the first tick after it). In memory; written with the next step.
     * Called on the stream's socket thread: only queued ([queue]).
     */
    fun streamGap(ms: Long) = queue(OrderSpeed.Step.STREAM_GAP, ms)

    /** The price stream dropped. In memory; written with the next order step. On the socket thread: only queued ([queue]). */
    fun streamDropped() = queue(null, 0L)

    /**
     * An order's request starts now ([live] Zerodha, else paper): signal → decision, decision → sent and signal → sent from
     * the run's [Trigger] when there is one; the price's age from [exchSec] (the stamp of the tick the order used) or the
     * trigger's. Returns the start time for [answered].
     */
    suspend fun sending(live: Boolean, exchSec: Long? = null): Long {
        val now = System.currentTimeMillis()
        val t = currentCoroutineContext()[Trigger]
        if (t != null) {
            val decided = (if (t.decidedAt > 0L) t.decidedAt else t.startedAt).coerceIn(t.triggerMs, now)
            add(OrderSpeed.Step.SIGNAL_DECISION, live, decided - t.triggerMs, keep = true)
            add(OrderSpeed.Step.DECISION_SENT, live, now - decided, keep = true)
            add(OrderSpeed.Step.SIGNAL_SENT, live, now - t.triggerMs, keep = true)
        }
        val stamp = exchSec?.let { it * 1000 } ?: t?.exchMs
        // The exchange stamps whole seconds: half a second is added, the middle of the stamped second.
        if (stamp != null && stamp > 0) add(OrderSpeed.Step.PRICE_AGE, live, (now - stamp - 500).coerceAtLeast(0L), keep = true)
        return now
    }

    /** Zerodha's (or the paper book's) answer to the order whose request started at [startedAt]. */
    fun answered(live: Boolean, startedAt: Long) = add(OrderSpeed.Step.SENT_ACK, live, System.currentTimeMillis() - startedAt, keep = true)

    /** The fill (or the order's end) became known [ms] after the answer; [viaStream]: Zerodha's stream told it first. */
    fun fillKnown(live: Boolean, ms: Long, viaStream: Boolean) {
        if (live) synchronized(lock) {
            withBook()
            fillsKnown += 1
            if (viaStream) fillsOnStream += 1
            Unit
        }
        add(OrderSpeed.Step.ACK_FILL, live, ms, keep = true)
    }

    /** A round trip through the relay ([cold]: the first after a long quiet, handshakes included) or straight to Zerodha. */
    fun relayPing(ms: Long, cold: Boolean) = add(if (cold) OrderSpeed.Step.RELAY_COLD else OrderSpeed.Step.RELAY_PING, true, ms, keep = true)
    fun directPing(ms: Long) = add(OrderSpeed.Step.DIRECT_PING, true, ms, keep = true)

    @Volatile private var clockAt = 0L

    /**
     * A tick's exchange stamp [exchSec] that reached the phone at [atMs]: the phone clock's offset, sampled every 5 s at most,
     * kept in memory only (it goes to the vault with the next order step or round trip). Never blocks the stream's thread.
     */
    fun clock(atMs: Long, exchSec: Long) {
        // A clock stepped back since the last sample (a negative gap) samples again at once (18 Oct, review).
        if (exchSec <= 0 || atMs - clockAt in 0 until 5_000) return
        clockAt = atMs
        // On the stream's socket thread: only queued ([queue]), never [lock] or the vault.
        queue(OrderSpeed.Step.CLOCK_OFFSET, atMs - (exchSec * 1000 + 500))
    }

    @Volatile private var serverClockAt = 0L

    /**
     * Zerodha's reply to a read sent at [sentMs], answered at [gotMs], dated [dateSec] (its Date header): the phone clock's
     * offset against Zerodha's server ([OrderSpeed.offsetFromDate]), sampled every 30 s at most, memory only (16 Oct).
     */
    fun serverClock(sentMs: Long, gotMs: Long, dateSec: Long) {
        if (gotMs - serverClockAt < 30_000) return
        val off = OrderSpeed.offsetFromDate(sentMs, gotMs, dateSec) ?: return
        serverClockAt = gotMs
        add(OrderSpeed.Step.CLOCK_SERVER, false, off, keep = false)
    }

    /**
     * The phone clock's latest measured offset (ms, +: the phone ahead) and what it was measured against: Zerodha's server
     * clock (NTP-style, [serverClock]) first, else the exchange's tick stamps (one way, so it includes the network's delay).
     * Null: neither measured today. Reads no network (the Live check's clock step, on a background thread).
     */
    fun latestClockOffset(): Pair<Long, String>? = synchronized(lock) {
        val b = bookLocked()
        b.all(OrderSpeed.Step.CLOCK_SERVER).lastOrNull()?.let { return@synchronized it to "Zerodha's server clock" }
        b.all(OrderSpeed.Step.CLOCK_OFFSET).lastOrNull()?.let { it to "the exchange's tick stamps, network delay included" }
    }

    /** The card's lines, its warnings, and the relay's typical round trip (null: none timed today). */
    data class Card(val lines: List<String>, val warnings: List<String>, val relayMedianMs: Long?)

    fun card(): Card = synchronized(lock) {
        val b = withBook()
        val relay = b.all(OrderSpeed.Step.RELAY_PING).takeIf { it.isNotEmpty() }?.let { OrderSpeed.median(it) }
        Card(OrderSpeed.lines(b, fillsOnStream, fillsKnown), OrderSpeed.warnings(b), relay)
    }

    /**
     * Today's signal-to-order-sent median and how many orders it is over (the app's health record, [AppHealth]); null: none
     * timed. Read by the health worker, never on the order path.
     */
    fun signalToSent(): Pair<Long, Int>? = synchronized(lock) {
        withBook().all(OrderSpeed.Step.SIGNAL_SENT).takeIf { it.isNotEmpty() }?.let { OrderSpeed.median(it) to it.size }
    }

    /**
     * Today's PAPER decision-to-order-sent median and how many orders it is over (12 Oct: paper orders took seconds and the card
     * mixed them with live); null: none timed. Read by the health check, never on the order path.
     */
    fun paperDecisionToSent(): Pair<Long, Int>? = synchronized(lock) {
        withBook().of(OrderSpeed.Step.DECISION_SENT, false).takeIf { it.isNotEmpty() }?.let { OrderSpeed.median(it) to it.size }
    }

    /** The diagnostics export's line. */
    fun diagLine(): String = synchronized(lock) { OrderSpeed.diagLine(withBook()) }

    /** TEST ONLY (and a wipe): forget the in-memory book, the queued figures, and when the clock was last sampled (18 Oct: the next reading counts). */
    internal fun forget() = synchronized(lock) {
        book = null; fillsOnStream = 0; fillsKnown = 0; clockAt = 0L; serverClockAt = 0L
        pending.clear(); pendingCount.set(0)
    }
}
