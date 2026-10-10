package com.optionslab.ira

/**
 * Smart scheduling for the watch's workers (Boss, 10 Oct: "make the workers smart", part 1). Each worker says what wakes it
 * ([Spec.wakeOn]: a 1- or 5-minute candle closing for its instruments, the price near a level it watches, its signal window
 * opening, a news / event window, an order update, a position change) and how long it may sleep at most ([Spec.maxSleepMs]).
 * The [Scheduler] answers, at each look, whether the worker is due ([Scheduler.decide]): only when something it waits for
 * happened since its last run (and its adaptive minimum gap has passed), or it has slept its longest. Each answer is counted
 * (wakes by cause, skips) for the diagnostics.
 *
 * Never skipped, whatever the scheduler would say:
 *  - a worker holding a position (its exits): [Pace.holding] runs it every look, as before;
 *  - the safety steps (paper fills, the daily loss limit, stops and targets, the square-offs) are never given to it.
 * Skipping only ever means "nothing new to decide": a worker's next look is at most [Spec.maxSleepMs] away, and the watch's
 * own safety-net pass ([safetyNetMs]) keeps running.
 *
 * The minimum gap adapts ([minIntervalMs]): half when the market is busy (R11's rule: the 30-minute range or realised
 * volatility at 2 SD or more, [MarketBrain.Regime]) or a held position is near its level, double when the market is quiet and
 * nothing is held. Outside its market hours a worker sleeps ([Pace.inHours] false: skipped, counted as asleep).
 *
 * Candle closes need no hook: they are read from the clock (a minute boundary [SETTLE_MS] past). Every other event is
 * [Scheduler.post]ed by whoever sees it (the stream's order update, the brain's window opening). Thread-safe; the clock is
 * given. Memory only; nothing here places, changes or closes an order.
 */
object WorkerWake {
    enum class Cause(val words: String) {
        CANDLE_1M("a 1-minute candle closed"),
        CANDLE_5M("a 5-minute candle closed"),
        NEAR_LEVEL("the price is near a level it watches"),
        SIGNAL_WINDOW("its signal window opened"),
        NEWS("a news or event window"),
        ORDER_UPDATE("an order update"),
        POSITION_CHANGE("a position changed"),
        MAX_SLEEP("its longest sleep passed"),
        HOLDING("it holds a position"),
        FIRST("its first look"),
    }

    /** How long after a candle's boundary it counts as closed (the last ticks of the minute are in), as the bar-close check. */
    const val SETTLE_MS = 3_000L

    /** The safety-net pass's bounds and defaults (seconds): 15 s while anything is held (never looser), else Boss's 15-60 s. */
    const val NET_HOLDING_SEC = 15
    const val NET_MIN_SEC = 15
    const val NET_MAX_SEC = 60
    const val NET_FLAT_DEFAULT_SEC = 30

    /**
     * The safety-net pass's interval: [NET_HOLDING_SEC] while any position is open (exits are never checked less often than
     * before, whatever the setting), else [flatSec] kept within [NET_MIN_SEC]..[NET_MAX_SEC].
     */
    fun safetyNetMs(anyOpen: Boolean, flatSec: Int = NET_FLAT_DEFAULT_SEC): Long =
        if (anyOpen) NET_HOLDING_SEC * 1_000L else flatSec.coerceIn(NET_MIN_SEC, NET_MAX_SEC) * 1_000L

    /** A worker's wake rules. [instruments] empty: any instrument's event wakes it. */
    data class Spec(
        val name: String,
        val wakeOn: Set<Cause>,
        val maxSleepMs: Long,
        val minIntervalMs: Long = 0L,
        val instruments: Set<String> = emptySet(),
    )

    /** What the scheduler knows of the moment: in the worker's hours, holding, near a level, the market busy or quiet. */
    data class Pace(
        val inHours: Boolean = true,
        val holding: Boolean = false,
        val nearLevel: Boolean = false,
        val busy: Boolean = false,
        val quiet: Boolean = false,
        /** The worker's signal window is open now (e.g. Hero 13:30-14:45 on an expiry day). */
        val inWindow: Boolean = false,
    )

    /**
     * The adaptive minimum gap: [baseMs] halved when the market is busy or a held position is near its level, doubled
     * when it is quiet and nothing is held, else as given.
     */
    fun minIntervalMs(baseMs: Long, p: Pace): Long = when {
        baseMs <= 0L -> 0L
        p.busy || p.nearLevel -> baseMs / 2
        p.quiet && !p.holding -> baseMs * 2
        else -> baseMs
    }

    /** One answer: run now ([run]) and why ([cause]); or not ([cause] null, [asleep] when out of its hours). */
    data class Decision(val run: Boolean, val cause: Cause?, val asleep: Boolean = false)

    /** A worker's counts today: its wakes by cause, the looks it skipped (nothing new) and slept (out of hours). */
    data class Counts(val wakes: Map<Cause, Int> = emptyMap(), val skips: Int = 0, val asleep: Int = 0, val lastRun: Long? = null) {
        val wakeTotal: Int get() = wakes.values.sum()
    }

    /** The last candle boundary of [periodMs] counted as closed at [nowMs] (boundary + [SETTLE_MS] <= now). */
    fun lastClose(nowMs: Long, periodMs: Long): Long {
        val b = nowMs - Math.floorMod(nowMs, periodMs)
        return if (nowMs - b >= SETTLE_MS) b else b - periodMs
    }

    class Scheduler(private val clock: () -> Long = { System.currentTimeMillis() }) {
        private class W(val spec: Spec) {
            var lastRun: Long? = null
            val pending = LinkedHashSet<Cause>()
            val wakes = HashMap<Cause, Int>()
            var skips = 0
            var asleep = 0
        }

        private val workers = LinkedHashMap<String, W>()

        /** [spec] added (or its rules replaced; its counts and last run kept). */
        @Synchronized fun register(spec: Spec) {
            val old = workers[spec.name]
            val w = W(spec)
            if (old != null) { w.lastRun = old.lastRun; w.pending += old.pending; w.wakes += old.wakes; w.skips = old.skips; w.asleep = old.asleep }
            workers[spec.name] = w
        }

        @Synchronized fun names(): List<String> = workers.keys.toList()

        /**
         * [cause] happened (for [instrument]; null: for every worker waiting on it): each worker that waits on it and follows
         * that instrument (or any) is due at its next look. Cheap: a few set adds under one lock.
         */
        @Synchronized fun post(cause: Cause, instrument: String? = null) {
            for (w in workers.values) {
                if (cause !in w.spec.wakeOn) continue
                if (instrument != null && w.spec.instruments.isNotEmpty() && w.spec.instruments.none { it.equals(instrument, ignoreCase = true) }) continue
                w.pending += cause
            }
        }

        /** The clock's own events since [since]: a 1- or 5-minute candle closed after it (the worker waiting on one). */
        private fun clockCause(spec: Spec, since: Long, now: Long): Cause? {
            if (Cause.CANDLE_5M in spec.wakeOn && lastClose(now, 300_000L) + SETTLE_MS > since) return Cause.CANDLE_5M
            if (Cause.CANDLE_1M in spec.wakeOn && lastClose(now, 60_000L) + SETTLE_MS > since) return Cause.CANDLE_1M
            return null
        }

        /**
         * Is [name] due now under [pace]? Holding: always (its exits). Out of its hours: no (asleep). Never run: yes. Else
         * the first of: its window open (when it waits on one), a near level, a posted event, a candle closed since its last
         * run - each only once its adaptive minimum gap passed - or its longest sleep passed. An unknown worker is always
         * due (nothing is ever skipped by mistake). Counted.
         */
        @Synchronized fun decide(name: String, pace: Pace = Pace(), now: Long = clock()): Decision {
            val w = workers[name] ?: return Decision(true, Cause.FIRST)
            val d = judge(w, pace, now)
            when {
                d.run -> w.wakes.merge(d.cause!!, 1, Int::plus)
                d.asleep -> w.asleep++
                else -> w.skips++
            }
            return d
        }

        private fun judge(w: W, pace: Pace, now: Long): Decision {
            if (pace.holding) return Decision(true, Cause.HOLDING)
            if (!pace.inHours) return Decision(false, null, asleep = true)
            val last = w.lastRun ?: return Decision(true, Cause.FIRST)
            val since = now - last
            if (since < 0) return Decision(true, Cause.FIRST)              // the clock went back: look again
            if (since >= w.spec.maxSleepMs) return Decision(true, Cause.MAX_SLEEP)
            if (since < minIntervalMs(w.spec.minIntervalMs, pace)) return Decision(false, null)
            if (pace.inWindow && Cause.SIGNAL_WINDOW in w.spec.wakeOn) return Decision(true, Cause.SIGNAL_WINDOW)
            if (pace.nearLevel && Cause.NEAR_LEVEL in w.spec.wakeOn) return Decision(true, Cause.NEAR_LEVEL)
            w.pending.firstOrNull()?.let { return Decision(true, it) }
            clockCause(w.spec, last, now)?.let { return Decision(true, it) }
            return Decision(false, null)
        }

        /** [name] ran (by the scheduler's word or another lane's: a stream or bar-close lane): its events are used up. */
        @Synchronized fun ran(name: String, at: Long = clock()) {
            val w = workers[name] ?: return
            w.lastRun = at
            w.pending.clear()
        }

        /** Is anything posted waiting for a worker (the watch's quiet wait ends early for it)? */
        @Synchronized fun anyPending(): Boolean = workers.values.any { it.pending.isNotEmpty() }

        @Synchronized fun counts(): Map<String, Counts> = workers.mapValues { (_, w) -> Counts(HashMap(w.wakes), w.skips, w.asleep, w.lastRun) }

        /** A new day: the counts start again (rules and last runs kept). */
        @Synchronized fun resetCounts() { workers.values.forEach { it.wakes.clear(); it.skips = 0; it.asleep = 0 } }
    }

    /** One worker's counts in words: "ORB arms: 42 wakes (5-minute candle 30, holding 12) · 118 skipped · 20 asleep". */
    fun words(name: String, c: Counts): String = buildString {
        append(name).append(": ").append(c.wakeTotal).append(if (c.wakeTotal == 1) " wake" else " wakes")
        if (c.wakes.isNotEmpty()) append(" (").append(c.wakes.entries.sortedByDescending { it.value }
            .joinToString(", ") { "${short(it.key)} ${it.value}" }).append(")")
        append(" · ").append(c.skips).append(" skipped (nothing new)")
        if (c.asleep > 0) append(" · ").append(c.asleep).append(" asleep")
    }

    private fun short(c: Cause): String = when (c) {
        Cause.CANDLE_1M -> "1-minute candle"; Cause.CANDLE_5M -> "5-minute candle"; Cause.NEAR_LEVEL -> "near a level"
        Cause.SIGNAL_WINDOW -> "signal window"; Cause.NEWS -> "news window"; Cause.ORDER_UPDATE -> "order update"
        Cause.POSITION_CHANGE -> "position change"; Cause.MAX_SLEEP -> "longest sleep"; Cause.HOLDING -> "holding"; Cause.FIRST -> "first look"
    }
}
