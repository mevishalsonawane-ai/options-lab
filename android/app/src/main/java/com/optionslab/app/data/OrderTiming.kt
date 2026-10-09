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
    class Trigger(val triggerMs: Long, val exchMs: Long?) : AbstractCoroutineContextElement(Trigger) {
        companion object Key : CoroutineContext.Key<Trigger>
        @Volatile var startedAt: Long = System.currentTimeMillis()
        @Volatile var decidedAt: Long = 0L
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

    private fun add(step: OrderSpeed.Step, live: Boolean, ms: Long, keep: Boolean) {
        val text = synchronized(lock) {
            val b = bookLocked()
            if (!b.add(step, live, ms) || !keep) null else b.encode()
        } ?: return
        runCatching { SecurePrefs.putAllLazy(mapOf(KEY to text)) }
    }

    /** The arm in the current run decided to trade now (the first call counts). Safe anywhere; a no-op outside a [Trigger]. */
    suspend fun decided() {
        val t = currentCoroutineContext()[Trigger] ?: return
        if (t.decidedAt == 0L) t.decidedAt = System.currentTimeMillis()
    }

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
            bookLocked()
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
        if (exchSec <= 0 || atMs - clockAt < 5_000) return
        clockAt = atMs
        add(OrderSpeed.Step.CLOCK_OFFSET, false, atMs - (exchSec * 1000 + 500), keep = false)
    }

    /** The card's lines, its warnings, and the relay's typical round trip (null: none timed today). */
    data class Card(val lines: List<String>, val warnings: List<String>, val relayMedianMs: Long?)

    fun card(): Card = synchronized(lock) {
        val b = bookLocked()
        val relay = b.all(OrderSpeed.Step.RELAY_PING).takeIf { it.isNotEmpty() }?.let { OrderSpeed.median(it) }
        Card(OrderSpeed.lines(b, fillsOnStream, fillsKnown), OrderSpeed.warnings(b), relay)
    }

    /** The diagnostics export's line. */
    fun diagLine(): String = synchronized(lock) { OrderSpeed.diagLine(bookLocked()) }

    /** TEST ONLY (and a wipe): forget the in-memory book. */
    internal fun forget() = synchronized(lock) { book = null; fillsOnStream = 0; fillsKnown = 0 }
}
