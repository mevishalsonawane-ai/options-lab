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
 *  - [Lane.LIVE]: exits of what is held at Zerodha. Round 2: while every live instrument has a fresh stream tick
 *    ([State.liveFresh]) it is decided from the stream at the stream lane's pace ([STREAM_GAP_MS]) - only an order goes
 *    over REST; otherwise it reads Zerodha, at most every [LIVE_GAP_MS] (Zerodha's rate limits);
 *  - [Lane.MINUTE]: arms that judge 1-minute candles: at the first tick after the minute's close when the local candles
 *    ([LiveCandles]) can stand for the feed ([State.localCandles]), and again [MINUTE_AFTER_MS] after the close from the
 *    candle feed (the fallback, and the source of truth);
 *  - [Lane.BAR_CLOSE]: the armed arms' entries on a 5-minute bar, at its close.
 *
 * Lanes are independent (round 2): a lane still running ([Plan] is given the [busy] ones) is never started again beside
 * itself and never holds back another lane - a slow Zerodha read in one cannot stall the stream's checks.
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
        /** Every instrument held at Zerodha has a stream tick at most [LiveQuote.FRESH_MS] old: exits decide from the stream. */
        val liveFresh: Boolean = false,
        /** The local candles are being built for what the minute arms read: the minute lane may run at the close itself. */
        val localCandles: Boolean = false,
    )

    /**
     * [minuteAtMs]: when the minute lane is due (at once on local candles, else the close plus [MINUTE_AFTER_MS]);
     * [minuteAgainMs]: its second run from the feed after a run on local candles (null: none).
     */
    data class Plan(val lanes: Set<Lane>, val triggerMs: Long?, val triggerExchMs: Long?, val minuteAtMs: Long?, val retryMs: Long?,
                    val minuteAgainMs: Long? = null) {
        val idle: Boolean get() = lanes.isEmpty() && minuteAtMs == null && retryMs == null && minuteAgainMs == null
    }

    /** The live lane's gap: the stream's when every live instrument is fresh on it, else Zerodha's ([LIVE_GAP_MS]). */
    fun liveGap(st: State): Long = if (st.liveFresh) STREAM_GAP_MS else LIVE_GAP_MS

    /** The lanes running now (thread-safe): a lane is never started beside itself, and a busy one holds back no other. */
    class Busy {
        private val running = HashSet<Lane>()
        /** True when [lane] was free (it is now marked running). */
        @Synchronized fun start(lane: Lane): Boolean = running.add(lane)
        @Synchronized fun end(lane: Lane) { running.remove(lane) }
        @Synchronized fun now(): Set<Lane> = running.toSet()
    }

    /**
     * The lanes to run for [evs] (drained, latest per instrument) at [nowMs], given [st]; only [relevant] instruments
     * (held, or an index an arm reads) wake anything. The trigger is the earliest relevant tick's arrival (and its exchange
     * stamp, in ms). [Plan.minuteAtMs]: when the minute lane is due (the close plus [MINUTE_AFTER_MS]). [Plan.retryMs]: a
     * lane held back by its gap is due again in this long (the consumer looks again then, even with no new tick).
     */
    fun plan(evs: List<Ev>, rolls: Rolls, gate: Debounce, nowMs: Long, st: State, relevant: (Long) -> Boolean): Plan =
        plan(evs, rolls, gate, nowMs, st, emptySet(), relevant)

    /** [plan] with the lanes still running ([busy]): never started beside themselves, looked at again after their gap. */
    fun plan(evs: List<Ev>, rolls: Rolls, gate: Debounce, nowMs: Long, st: State, busy: Set<Lane>,
             relevant: (Long) -> Boolean): Plan {
        val mine = evs.filter { relevant(it.token) }
        if (mine.isEmpty()) return Plan(emptySet(), null, null, null, null)
        val roll = rolls.see(mine)
        val first = mine.minByOrNull { it.atMs }!!
        val lanes = LinkedHashSet<Lane>()
        var retry: Long? = null
        fun hold(lane: Lane, gap: Long) { val l = gate.left(lane, nowMs, gap).coerceAtLeast(if (lane in busy) gap else 0); if (l > 0) retry = minOf(retry ?: l, l) }
        // A lane still running is looked at again after its gap (the latest ticks are kept for it); the others go on.
        fun try_(lane: Lane, gap: Long, force: Boolean) {
            if (lane in busy) { hold(lane, gap); return }
            if (gate.due(lane, nowMs, gap, force)) lanes += lane else hold(lane, gap)
        }
        if (st.holding && st.covered) try_(Lane.STREAM, STREAM_GAP_MS, roll.minute)
        // The live lane's looks also mark the paper holdings: only when those are streamed too (no candle feed read per tick).
        if (st.liveHolding && (st.covered || !st.holding)) try_(Lane.LIVE, liveGap(st), roll.minute)
        if (roll.bar && st.armed && Lane.BAR_CLOSE !in busy && gate.due(Lane.BAR_CLOSE, nowMs, BAR_GAP_MS)) lanes += Lane.BAR_CLOSE
        val minute = roll.minute && st.minuteArms && gate.due(Lane.MINUTE, nowMs, MINUTE_GAP_MS)
        val feedAt = maxOf(nowMs, (roll.closedAtMs ?: nowMs) + MINUTE_AFTER_MS)
        val minuteAt = if (!minute) null else if (st.localCandles) nowMs else feedAt
        val again = if (minute && st.localCandles && feedAt > nowMs) feedAt else null
        return Plan(lanes, first.atMs, first.exchSec?.let { it * 1000 }, minuteAt, retry, again)
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
