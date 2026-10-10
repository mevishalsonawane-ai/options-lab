package com.optionslab.ira

import com.optionslab.engine.Session
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The index's kept sessions read down for the market-history answers, all in one stream (speed round 13). The straddle
 * decay ([StraddleDecay]), the expiry-day last hour ([ExpiryHour]) and the at-the-money buyer's record ([AtmBuy]) each
 * streamed and decoded every session with option prices on the phone (the bundled file and every harvested day) on its
 * first ask of the day, so a second, different one of the three re-streamed the very same files before its first word.
 * Now the first of them reads the stream once into all three, and the others are found kept while the key (the day and
 * the phone's kept days) is the same; different keys (another day, a new harvested day) are read afresh, exactly as
 * reading again would give. Market prices only - nothing of Boss's account is kept here.
 */
object PastReads {
    /** One read fed a session at a time, its result taken once the stream ends. */
    interface Part<T> {
        fun add(s: Session)
        fun days(): T
    }

    /** The three reads of one stream; a read that failed throws when taken, as reading it alone would have. */
    class Read internal constructor(
        private val straddle: Result<List<StraddleDecay.Day>>,
        private val expiryHour: Result<List<ExpiryHour.Day>>,
        private val atmBuy: Result<List<AtmBuy.Day>>,
    ) {
        val straddleDays: List<StraddleDecay.Day> get() = straddle.getOrThrow()
        val expiryHourDays: List<ExpiryHour.Day> get() = expiryHour.getOrThrow()
        val atmBuyDays: List<AtmBuy.Day> get() = atmBuy.getOrThrow()
        internal val whole: Boolean get() = straddle.isSuccess && expiryHour.isSuccess && atmBuy.isSuccess
    }

    /** A part whose failure is kept to itself, so one read's fault never spoils the other two. */
    private class Guarded<T>(private val part: Part<T>) {
        private var failed: Exception? = null
        fun add(s: Session) {
            if (failed == null) try { part.add(s) } catch (e: Exception) { failed = e }
        }
        fun result(): Result<T> = failed?.let { Result.failure(it) } ?: try { Result.success(part.days()) } catch (e: Exception) { Result.failure(e) }
    }

    /** [sessions] streamed once into the three reads before [today]: each the same as its own `days` would give. */
    fun read(sessions: Sequence<Session>, today: LocalDate): Read {
        val straddle = Guarded(StraddleDecay.Reader(today))
        val hour = Guarded(ExpiryHour.Reader(today))
        val atm = Guarded(AtmBuy.Reader(today))
        for (s in sessions) {
            straddle.add(s); hour.add(s); atm.add(s)
        }
        return Read(straddle.result(), hour.result(), atm.result())
    }

    private class Held(val key: String, val read: Read)

    private val held = ConcurrentHashMap<String, Held>()
    private val locks = ConcurrentHashMap<String, Any>()
    private val streams = AtomicInteger()

    /**
     * [market]'s three reads under [key] (the day and its kept days): kept from an earlier ask with the same key, else
     * [sessions] streamed once (an ask that comes while another streams the same market waits for it rather than streaming
     * again). A read with a failed part is not kept, so the next ask tries afresh.
     */
    fun of(market: String, key: String, today: LocalDate, sessions: () -> Sequence<Session>): Read {
        held[market]?.takeIf { it.key == key }?.let { return it.read }
        synchronized(locks.computeIfAbsent(market) { Any() }) {
            held[market]?.takeIf { it.key == key }?.let { return it.read }
            streams.incrementAndGet()
            val r = read(sessions(), today)
            if (r.whole) held[market] = Held(key, r) else held.remove(market)
            return r
        }
    }

    /** How many times the sessions were streamed rather than found kept (tests: the work an answer costs). */
    val streamCount: Int get() = streams.get()

    /** Everything kept forgotten and the count reset (tests). */
    fun forget() {
        held.clear(); streams.set(0)
    }
}
