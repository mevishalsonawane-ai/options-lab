package com.optionslab.ira

/**
 * Event-driven checks (Boss, 9 Oct: "order latency as low as possible"): the stops, targets and exits are checked when a
 * price arrives on Zerodha's stream or a minute candle closes, not only on the watch's 15-second pass (which stays, as
 * the safety net). This is the scheduling, pure: what the stream brought is kept per instrument, the latest only
 * ([Inbox]: a burst collapses to one look); a minute or a 5-minute bar closing is told by the exchange's own stamp
 * ([Rolls]); each lane runs at most once per its gap ([Debounce]) unless a candle closed; [plan] says which lanes run now.
 *
 * The lanes (the app runs each step under that arm's own lock, with every gate as on the full pass):
 *  - [Lane.STREAM]: what reads prices only from the stream (the paper book's resting stops, the ORB arms' paper exits),
 *    at most every [STREAM_GAP_MS];
 *  - [Lane.LIVE]: exits that also read Zerodha (orders, positions), at most every [LIVE_GAP_MS] (Zerodha's rate limits);
 *  - [Lane.MINUTE]: arms that judge 1-minute candles from the candle feed, once a minute, [MINUTE_AFTER_MS] after it closes;
 *  - [Lane.BAR_CLOSE]: the armed arms' entries on a 5-minute bar, at its close.
 */
object FastLane {
    enum class Lane { STREAM, LIVE, MINUTE, BAR_CLOSE }

    const val STREAM_GAP_MS = 200L
    const val LIVE_GAP_MS = 2_000L
    /** A candle close may run a lane early, never sooner than this after its last run. */
    const val FLOOR_MS = 200L
    /** The candle feed's minute lands a few seconds after the close (as [com.optionslab.engine.orb.BarClose.AFTER_MS]). */
    const val MINUTE_AFTER_MS = 3_000L
    private const val MINUTE_GAP_MS = 50_000L
    private const val BAR_GAP_MS = 240_000L

    /** One instrument's latest tick: when it reached the phone ([atMs], phone clock) and the exchange's stamp ([exchSec]). */
    data class Ev(val token: Long, val atMs: Long, val exchSec: Long?)

    /** The latest tick per instrument until the consumer takes them ([drain]); thread-safe. */
    class Inbox {
        private val latest = LinkedHashMap<Long, Ev>()

        /** True when nothing was waiting before (the consumer should be woken). */
        @Synchronized fun offer(ev: Ev): Boolean {
            val was = latest.isEmpty()
            latest[ev.token] = ev
            return was
        }

        @Synchronized fun drain(): List<Ev> = latest.values.toList().also { latest.clear() }

        @Synchronized fun size(): Int = latest.size
    }

    /** What a look's ticks closed. */
    data class Roll(val minute: Boolean, val bar: Boolean, val closedAtMs: Long?)

    /** Each instrument's last minute (by the exchange's stamp, else the phone's arrival), to tell when one closed. */
    class Rolls {
        private val minute = HashMap<Long, Long>()

        /** The first tick of a minute after an earlier one of the same instrument closes that minute (and a bar, on 5). */
        fun see(evs: List<Ev>): Roll {
            var m = false; var bar = false; var at: Long? = null
            for (e in evs) {
                val sec = e.exchSec ?: (e.atMs / 1000)
                val now = Math.floorDiv(sec, 60L)
                val was = minute[e.token]
                if (was == null || now > was) minute[e.token] = now
                if (was != null && now > was) {
                    m = true
                    val boundary = now * 60_000L
                    at = maxOf(at ?: boundary, boundary)
                    // A 5-minute boundary lies in (was, now].
                    if (Math.floorDiv(now, 5L) > Math.floorDiv(was, 5L)) bar = true
                }
            }
            return Roll(m, bar, at)
        }
    }

    /** Each lane's last run; [due] says whether it may run now and, if so, notes it ran. */
    class Debounce {
        private val last = HashMap<Lane, Long>()

        fun due(lane: Lane, nowMs: Long, gapMs: Long, force: Boolean = false): Boolean {
            val was = last[lane]
            val gap = if (force) minOf(gapMs, FLOOR_MS) else gapMs
            if (was != null && nowMs - was in 0 until gap) return false
            last[lane] = nowMs
            return true
        }

        /** How long until [lane] may run again after its gap (0: now). */
        fun left(lane: Lane, nowMs: Long, gapMs: Long): Long {
            val was = last[lane] ?: return 0
            return (gapMs - (nowMs - was)).coerceIn(0, gapMs)
        }
    }

    /** What the app holds and has armed now (read by the app before each look). */
    data class State(
        /** Anything held or working on paper or by an arm (exits to check). */
        val holding: Boolean,
        /** Something held at Zerodha that an app exit watches (reads Zerodha). */
        val liveHolding: Boolean,
        /** An arm armed for entries on the bar close. */
        val armed: Boolean,
        /** Every paper holding has a fresh stream price (so the stream lane reads no feed). */
        val covered: Boolean,
        /** An arm that judges 1-minute candles is armed or holds (the minute lane has work). */
        val minuteArms: Boolean,
    )

    data class Plan(val lanes: Set<Lane>, val triggerMs: Long?, val triggerExchMs: Long?, val minuteAtMs: Long?, val retryMs: Long?) {
        val idle: Boolean get() = lanes.isEmpty() && minuteAtMs == null && retryMs == null
    }

    /**
     * The lanes to run for [evs] (drained, latest per instrument) at [nowMs], given [st]; only [relevant] instruments
     * (held, or an index an arm reads) wake anything. The trigger is the earliest relevant tick's arrival (and its exchange
     * stamp, in ms). [Plan.minuteAtMs]: when the minute lane is due (the close plus [MINUTE_AFTER_MS]). [Plan.retryMs]: a
     * lane held back by its gap is due again in this long (the consumer looks again then, even with no new tick).
     */
    fun plan(evs: List<Ev>, rolls: Rolls, gate: Debounce, nowMs: Long, st: State, relevant: (Long) -> Boolean): Plan {
        val mine = evs.filter { relevant(it.token) }
        if (mine.isEmpty()) return Plan(emptySet(), null, null, null, null)
        val roll = rolls.see(mine)
        val first = mine.minByOrNull { it.atMs }!!
        val lanes = LinkedHashSet<Lane>()
        var retry: Long? = null
        fun hold(lane: Lane, gap: Long) { val l = gate.left(lane, nowMs, gap); if (l > 0) retry = minOf(retry ?: l, l) }
        if (st.holding && st.covered) {
            if (gate.due(Lane.STREAM, nowMs, STREAM_GAP_MS, roll.minute)) lanes += Lane.STREAM else hold(Lane.STREAM, STREAM_GAP_MS)
        }
        if (st.liveHolding && st.covered) {
            if (gate.due(Lane.LIVE, nowMs, LIVE_GAP_MS, roll.minute)) lanes += Lane.LIVE else hold(Lane.LIVE, LIVE_GAP_MS)
        }
        if (roll.bar && st.armed && gate.due(Lane.BAR_CLOSE, nowMs, BAR_GAP_MS)) lanes += Lane.BAR_CLOSE
        val minuteAt = if (roll.minute && st.minuteArms && gate.due(Lane.MINUTE, nowMs, MINUTE_GAP_MS))
            maxOf(nowMs, (roll.closedAtMs ?: nowMs) + MINUTE_AFTER_MS) else null
        return Plan(lanes, first.atMs, first.exchSec?.let { it * 1000 }, minuteAt, retry)
    }

    /**
     * When to warm the order route ahead of a bar close the armed arms decide on: [leadMs] before the next 5-minute
     * boundary after [nowMs] (the next one's when that moment has passed).
     */
    fun warmAt(nowMs: Long, leadMs: Long = 5_000L): Long {
        val bar = 5 * 60_000L
        val next = nowMs - Math.floorMod(nowMs, bar) + bar
        return if (next - leadMs > nowMs) next - leadMs else next + bar - leadMs
    }
}
