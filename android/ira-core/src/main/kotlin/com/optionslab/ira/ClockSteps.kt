package com.optionslab.ira

import com.optionslab.engine.NseHours
import com.optionslab.engine.risk.FlatBy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * 18 Nov 2026 (audit: the phone's diagnostics showed "watch pass (total) p95 10 min 42 s" and "stop rescue max 8 min 45 s"): the
 * steps that act at a time of day - the daily loss limit, the 15:05 expiry square-off, the MCX expiry exit and the 15:20 / 15:25
 * flat-by - ran only inside the full watch pass, one step after another, and the next pass started a minute after the last one
 * ENDED. A pass stuck on a slow read held the 15:05 square-off back to 15:12 or later. They now run on the clock lane, every 15 s
 * by the clock, each its own step that a stuck sibling cannot hold up; the pass still runs them too, at their old place.
 *
 * This file is the lane's pure part: its clock, the alarm minutes, the "not completed in time" rule and the square-offs' one turn.
 * No I/O, no Android.
 */
object ClockSteps {
    /** A round every 15 s by the clock (at :01, :16, :31 and :46 of each minute), never 15 s after the last round ended. */
    const val ROUND_MS = 15_000L
    /** A second past each 15 s mark, so a round at the minute's turn reads the new minute on every clock. */
    const val ROUND_OFFSET_MS = 1_000L
    /** A due step that has not COMPLETED this long after its time (positions open) is a RISK alert. */
    const val LATE_MS = 45_000L
    /** With nothing held and outside its minutes, a step runs once a minute (as each pass ran it), not every round. */
    const val IDLE_EVERY_MS = 60_000L
    /** How long a square-off waits for another one's turn before it leaves this round to the next. */
    const val TURN_WAIT_MS = 5_000L
    /** The alarm rings this many minutes before its step's minute, so the watch is up and running when the minute comes. */
    const val ALARM_LEAD_MIN = 1

    /** Milliseconds from [nowMs] to the next round: in (0, [roundMs]], worked out afresh each time (a clock set back cannot stall it). */
    fun nextRoundIn(nowMs: Long, roundMs: Long = ROUND_MS, offsetMs: Long = ROUND_OFFSET_MS): Long =
        roundMs - Math.floorMod(nowMs - offsetMs, roundMs)

    /** The minutes (of the day) a clock alarm rings on a trading day: before 15:05 on an expiry day, before 15:20 and 15:25 always. */
    fun alarmMinutes(expiryDay: Boolean): List<Int> = listOfNotNull(
        if (expiryDay) NseHours.EXPIRY_SQUARE_OFF - ALARM_LEAD_MIN else null,
        FlatBy.NSE_STOCK_MINUTE - ALARM_LEAD_MIN,
        FlatBy.NSE_INDEX_MINUTE - ALARM_LEAD_MIN,
    )

    /**
     * The next clock alarm strictly after [now]: the first of [alarmMinutes] on the first trading day ([trading]) from today, with
     * [expiry] telling an expiry day. Null when no trading day comes within [MAX_DAYS] (a broken calendar).
     */
    fun nextAlarm(now: ZonedDateTime, trading: (LocalDate) -> Boolean, expiry: (LocalDate) -> Boolean): ZonedDateTime? {
        var d = now.toLocalDate()
        repeat(MAX_DAYS) {
            if (runCatching { trading(d) }.getOrDefault(false)) {
                val exp = runCatching { expiry(d) }.getOrDefault(true)
                for (m in alarmMinutes(exp)) {
                    val t = d.atTime(m / 60, m % 60).atZone(now.zone)
                    if (t.isAfter(now)) return t
                }
            }
            d = d.plusDays(1)
        }
        return null
    }
    private const val MAX_DAYS = 15

    /**
     * Since when a completed run of a step is due: the later of its last completed run's start ([lastDoneMs]) or the lane's own
     * start ([activeSinceMs]) plus [everyMs] (its slowest cadence), and its scheduled minute today ([scheduledMs], null: none passed yet) - a lane started
     * late gets its own round's grace first.
     */
    fun dueAt(lastDoneMs: Long, activeSinceMs: Long, scheduledMs: Long?, everyMs: Long = ROUND_MS): Long {
        val rolling = maxOf(lastDoneMs, activeSinceMs) + everyMs
        return if (scheduledMs == null) rolling else maxOf(rolling, maxOf(scheduledMs, activeSinceMs))
    }

    /** Late: due at [dueMs], no run that started at or after it has completed ([lastDoneMs]), and [LATE_MS] gone by. */
    fun late(dueMs: Long, lastDoneMs: Long, nowMs: Long): Boolean = lastDoneMs < dueMs && nowMs - dueMs >= LATE_MS

    /** The latest of [minutes] on [day] that is at or before [now] (epoch ms), or null. */
    fun lastScheduled(day: LocalDate, minutes: List<Int>, now: ZonedDateTime): Long? = minutes
        .map { day.atTime(it / 60, it % 60).atZone(now.zone) }.filter { !it.isAfter(now) }.maxOrNull()?.toInstant()?.toEpochMilli()

    /**
     * The square-offs' one turn (the expiry square-off, the MCX expiry exit, the flat-by): they ran one after another in the pass,
     * and on the lane they still never run at the same time (two could otherwise sell one contract twice). A step waits
     * [TURN_WAIT_MS] at most for the turn, polling, never cancelled while it holds it; not had in time, the round is left (null)
     * and the next one tries again. 19 Nov 2026 (second review): the three run as one sequence holding it, and the daily loss limit's
     * sales take it too.
     */
    class Turn(private val pollMs: Long = 50L) {
        private val m = Mutex()

        /** Held now (diagnostics, tests). */
        val held: Boolean get() = m.isLocked

        /** Who holds the turn now ([take]'s `who`), null: nobody. */
        @Volatile var holder: String? = null
            private set

        /** How many [take]s with `first` are waiting now. */
        private val firstWaiting = java.util.concurrent.atomic.AtomicInteger()

        /**
         * The turn for [block], waited for [waitMs] at most (null: not had). 19 Nov 2026 (second review: a sequence that lets the turn go
         * and takes it again at once never let a polling waiter in): [first] - the daily loss limit's sales - goes before every take
         * without it: while one waits, the others hold back (within their own [waitMs]), so it has the turn as soon as it is let go.
         */
        suspend fun <T> take(waitMs: Long = TURN_WAIT_MS, who: String? = null, first: Boolean = false, block: suspend () -> T): T? {
            // tryLock and a short sleep, never lock() under a timeout: a timeout that fires as the lock is had would keep it for ever.
            var waited = 0L
            if (first) firstWaiting.incrementAndGet()
            try {
                while ((!first && firstWaiting.get() > 0) || !m.tryLock()) {
                    if (waited >= waitMs) return null
                    delay(pollMs)
                    waited += pollMs
                }
            } finally {
                if (first) firstWaiting.decrementAndGet()
            }
            holder = who
            try {
                return block()
            } finally {
                holder = null
                m.unlock()
            }
        }
    }

    /** Once per key (the day, the step, the minute): a late step is told once, not every round. Memory only. */
    class Once {
        private val said = HashSet<String>()
        @Synchronized fun first(day: LocalDate, key: String): Boolean {
            val prefix = "$day|"
            said.removeAll { !it.startsWith(prefix) }
            return said.add(prefix + key)
        }
    }

    /**
     * The lane's loop: while [active] (the watch's NSE or MCX look is on), [round] at once, then on each 15 s mark of the clock
     * ([nextRoundIn]) or at once when [poke]d (a clock alarm); [round] only starts the steps (it never waits for them), so a stuck
     * step never holds the next round back. Not active: looked at again every [pollMs]. Ends when [stop] says so (read between
     * rounds). [round] gets the round's start and since when the lane has been active (its first round's start).
     */
    class Lane(
        private val now: () -> Long = System::currentTimeMillis,
        private val roundMs: Long = ROUND_MS,
        private val offsetMs: Long = ROUND_OFFSET_MS,
        private val pollMs: Long = 1_000L,
    ) {
        private val pokes = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

        /** A round now (an alarm rang): the wait under way ends at once. Never blocks. */
        fun poke() { pokes.trySend(Unit) }

        suspend fun run(stop: () -> Boolean, active: () -> Boolean, round: suspend (startMs: Long, activeSinceMs: Long) -> Unit,
                        failed: (Throwable) -> Unit = {}) {
            var since = 0L
            while (!stop()) {
                val t = now()
                if (!active()) {
                    since = 0L
                    // A poke while not active is kept for the first active round (it rang for a step about to be due).
                    delay(pollMs)
                    continue
                }
                if (since == 0L) since = t
                try {
                    round(t, since)
                } catch (e: CancellationException) {
                    if (!kotlinx.coroutines.currentCoroutineContext().isActive) throw e
                    runCatching { failed(e) }
                } catch (e: Throwable) {
                    runCatching { failed(e) }
                }
                if (stop()) break
                kotlinx.coroutines.withTimeoutOrNull(nextRoundIn(now(), roundMs, offsetMs)) { pokes.receive() }
            }
        }
    }
}
