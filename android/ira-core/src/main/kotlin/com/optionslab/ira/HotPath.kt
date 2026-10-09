package com.optionslab.ira

/**
 * Order latency, round 2 (Boss, 9 Oct: "everything live and latest price, don't wait even for a sec"): the small pure
 * decisions the order path and the screens take on every price, kept here so each is tested on its own.
 *
 *  - [MarginCache]: a live entry's margin pre-check reads a kept snapshot of the account's free margin (refreshed in the
 *    background at most [MarginCache.MAX_AGE_MS] apart in market hours) and reads Zerodha synchronously only when that
 *    snapshot is missing, old, or its headroom is within [MarginCache.HEADROOM] of what the order needs.
 *  - [LiveQuote]: which price to use - the stream's tick while it is at most [LiveQuote.FRESH_MS] old, else Zerodha's
 *    quote call (the fallback), and how the screens say it ("live" / "delayed 5s").
 *  - [Coalescer]: the screens redraw on ticks, at most once every [Coalescer.GAP_MS], always with the latest (a burst
 *    ends with one redraw at most [Coalescer.GAP_MS] after its last tick).
 *  - [RouteWarm]: when to warm the order route - every [RouteWarm.LIVE_GAP_MS] while a Live arm is armed or a live position is
 *    open, and 5 s before every bar close the armed arms decide on.
 */
object MarginCache {
    /** The kept snapshot is refreshed at most this far apart in market hours (and never used when older). */
    const val MAX_AGE_MS = 30_000L
    /** Refreshed in the background this often (inside [MAX_AGE_MS]). */
    const val REFRESH_MS = 25_000L
    /** A snapshot whose headroom over the order's need is under this share of the need is read again first. */
    const val HEADROOM = 0.20

    data class Snapshot(val available: Double, val atMs: Long)

    enum class Use { CACHED, READ_NOW }

    /**
     * Whether the entry's check may use [snap] for an order needing [needed] at [nowMs]: [Use.READ_NOW] when there is no
     * snapshot, it is older than [MAX_AGE_MS] (or from the future: a clock change), the need is unknown, or the headroom
     * (available - needed) is under [HEADROOM] x needed (including short). Never trusts a stale or tight figure.
     */
    fun decide(snap: Snapshot?, needed: Double, nowMs: Long): Use {
        if (snap == null || !snap.available.isFinite()) return Use.READ_NOW
        val age = nowMs - snap.atMs
        if (age < 0 || age > MAX_AGE_MS) return Use.READ_NOW
        if (!needed.isFinite() || needed <= 0) return Use.READ_NOW
        return if (snap.available - needed < HEADROOM * needed) Use.READ_NOW else Use.CACHED
    }

    /** The background refresh is due at [nowMs] (none yet, or [REFRESH_MS] since the last). */
    fun due(snap: Snapshot?, nowMs: Long): Boolean = snap == null || nowMs - snap.atMs !in 0 until REFRESH_MS
}

object LiveQuote {
    /** A tick older than this is not live: the decision falls back to Zerodha's quote call (the 15-s pass, the REST lane). */
    const val FRESH_MS = 2_000L

    enum class Source { STREAM, REST }

    /** [tickAtMs]: when the instrument's last tick reached the phone (null: none). */
    fun pick(tickAtMs: Long?, nowMs: Long, maxAgeMs: Long = FRESH_MS): Source =
        if (tickAtMs != null && nowMs - tickAtMs in 0..maxAgeMs) Source.STREAM else Source.REST

    /** Every one of [ticksAtMs] fresh (an empty list is not: nothing is known). */
    fun allFresh(ticksAtMs: List<Long?>, nowMs: Long, maxAgeMs: Long = FRESH_MS): Boolean =
        ticksAtMs.isNotEmpty() && ticksAtMs.all { pick(it, nowMs, maxAgeMs) == Source.STREAM }

    /** The screens' small word beside a price: "live", "delayed 4s" (whole seconds), or "delayed" when unknown. */
    fun says(tickAtMs: Long?, nowMs: Long): String {
        if (tickAtMs == null) return "delayed"
        val age = nowMs - tickAtMs
        if (age in 0..FRESH_MS) return "live"
        return if (age < 0) "delayed" else "delayed ${age / 1000}s"
    }
}

/**
 * At most one emission every [gapMs], always carrying the latest: an event when the last emission is [gapMs] old or more
 * goes at once; one sooner is held and emitted at the gap's end (one trailing emission, however many events came). Not
 * thread-safe: one owner, or the caller locks.
 */
class Coalescer(private val gapMs: Long = GAP_MS) {
    private var lastEmit = Long.MIN_VALUE
    private var held = false

    /** What to do with an event: emit [now], or schedule one emission at [scheduleAt] (null: one is already scheduled). */
    data class Decision(val now: Boolean, val scheduleAt: Long?)

    /** An event at [nowMs]. */
    fun offer(nowMs: Long): Decision {
        if (lastEmit == Long.MIN_VALUE || nowMs - lastEmit >= gapMs || nowMs < lastEmit) {
            if (held) return Decision(false, null)     // the scheduled one carries it, at most [gapMs] after the last
            lastEmit = nowMs
            return Decision(true, null)
        }
        if (held) return Decision(false, null)
        held = true
        return Decision(false, lastEmit + gapMs)
    }

    /** The scheduled emission ran at [nowMs]. */
    fun fired(nowMs: Long) { lastEmit = nowMs; held = false }

    /** Whether an emission is scheduled and not yet run. */
    val pending: Boolean get() = held

    companion object {
        /** The screens redraw at most four times a second on prices. */
        const val GAP_MS = 250L
    }
}

object RouteWarm {
    /** While a Live arm is armed or a live position is open, the order route is warmed this often (Kite's limits allow it). */
    const val LIVE_GAP_MS = 20_000L
    /** Otherwise at most once a minute (the market watch's pass). */
    const val IDLE_GAP_MS = 60_000L

    /**
     * The next warm-up after [nowMs]: [LIVE_GAP_MS] after [lastMs] when [liveActive], and never later than 5 s before the
     * next 5-minute bar close the armed arms decide on ([FastLane.warmAt]) when [armed].
     */
    fun next(nowMs: Long, lastMs: Long?, liveActive: Boolean, armed: Boolean): Long? {
        val bar = if (armed) FastLane.warmAt(nowMs) else null
        val live = when {
            !liveActive -> null
            lastMs == null -> nowMs
            else -> maxOf(nowMs, lastMs + LIVE_GAP_MS)
        }
        return listOfNotNull(bar, live).minOrNull()
    }
}
