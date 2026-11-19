package com.optionslab.app.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.optionslab.app.data.AppSettings
import com.optionslab.app.data.Market
import com.optionslab.ira.ClockSteps
import com.optionslab.ira.Supervisor
import java.util.concurrent.ConcurrentHashMap

/**
 * 18 Nov 2026 (audit: the phone's diagnostics showed "watch pass (total) p95 10 min 42 s" and "stop rescue max 8 min 45 s"): the
 * clock lane. The daily loss limit, the 15:05 expiry square-off, the MCX expiry exit and the 15:20 / 15:25 flat-by ran only inside
 * the full watch pass, one step after another, and the next pass started a minute after the last one ENDED: on an expiry day a
 * pass stuck on a slow read held the 15:05 square-off back to 15:12 or later, and the loss limit minutes late.
 *
 * Now, beside the stop lane ([StopLane]) and on its own coroutine:
 *  - a round every 15 s by the clock (:01, :16, :31, :46 of each minute; [ClockSteps.nextRoundIn]), whatever the pass is doing;
 *  - each step its own supervisor step, started and never waited for ([Supervisor.kick]): a stuck one is never started again
 *    beside itself and never holds up a sibling or the next round;
 *  - the same steps as the pass ran, in the same markets: NSE's look runs all four, MCX's evening look (outside NSE's hours) the
 *    loss limit, the MCX expiry exit and the flat-by, as [Tasks.mcxTick]; none before the open, in the evening look or on a closed
 *    day ([StopLane.Mode.OFF]), as before. They run whether or not anything is held: each reads the books and Zerodha itself;
 *  - the three square-offs are one step ([SQUARE_OFFS]), one after another in the pass's order, each in the one turn
 *    ([ClockSteps.Turn]) that the loss limit's sales take too ([sales]); the loss limit runs first in a round and the square-offs after
 *    it ends (19 Nov 2026, second review);
 *  - the pass still runs them at their old place ([Tasks.clockStep]), as the same supervisor steps: never two runs of one at once;
 *  - each run's start is kept when it COMPLETES ([lastDone]); a due step with no completed run 45 s after its time while anything is
 *    held is a RISK alert, once ([ClockSteps.late]);
 *  - an exact alarm ([ClockAlarm]) at 15:04 on an expiry day and at 15:19 and 15:24 starts the watch if Android ended it, and pokes
 *    the lane for a round at once.
 * Every step keeps its own rules unchanged (its minutes, its done-for-the-day marks, its retries). Nothing here is on the order or
 * tick path; nothing runs on the main thread.
 */
internal object ClockLane {
    /** The steps' names: the same words the pass's own steps had (the diagnostics and the Order speed card keep their rows). */
    const val LOSS = "daily loss limit"
    const val EXPIRY = "expiry square-off"
    const val MCX_EXPIRY = "MCX expiry exit"
    const val FLAT_BY = "flat-by"
    /** The supervisor's group names: the lane's rounds and the pass's own runs. */
    const val GROUP = "clock lane"
    const val PASS_GROUP = "watch pass: clock steps"

    /** How long the pass waits for a clock step (its own run, or the lane's already under way): never cut, left to finish. */
    const val PASS_WAIT_MS = 20_000L

    /** NSE's look: every clock step, in the pass's order. MCX's: those [Tasks.mcxTick] ran. */
    fun stepsFor(mode: StopLane.Mode): List<String> = when (mode) {
        StopLane.Mode.OFF -> emptyList()
        StopLane.Mode.NSE -> listOf(LOSS, EXPIRY, MCX_EXPIRY, FLAT_BY)
        StopLane.Mode.MCX -> listOf(LOSS, MCX_EXPIRY, FLAT_BY)
    }

    private val turn = ClockSteps.Turn()
    /** Each step's last COMPLETED run, by its start (wall-clock ms; memory only). */
    private val done = ConcurrentHashMap<String, Long>()
    @Volatile private var told = ClockSteps.Once()

    /** The lane of the watch running now (a new run's lane replaces an old one's; [poke] reaches the newest). */
    @Volatile private var lane: ClockSteps.Lane? = null

    /** When [name] last completed (its start, wall-clock ms), 0: not yet in this process. */
    fun lastDone(name: String): Long = done[name] ?: 0L

    /** The wall clock the stamps and the lane read (the app's own; a test's fake clock). */
    @Volatile internal var clock: () -> Long = System::currentTimeMillis

    /** TEST SEAM: a step's work instead of the real one ([name], the settings). Null in the app, always. */
    @Volatile internal var testWork: (suspend (String) -> Unit)? = null

    /**
     * 19 Nov 2026 (second review): the three square-offs as ONE supervisor step, run one after another in the pass's old order
     * ([squareOffsFor]: the expiry square-off, the MCX expiry exit, the flat-by), so each always runs after the one before however
     * long that takes - a slow one never starves the next (they took one shared turn each before, and a slow expiry square-off could
     * keep the flat-by from ever having it).
     */
    const val SQUARE_OFFS = "square-offs"

    /** The square-offs of a market's look, in the pass's order. */
    fun squareOffsFor(mode: StopLane.Mode): List<String> = stepsFor(mode).filter { it != LOSS }

    /** 19 Nov 2026: the square-offs wait this long at most for a loss-limit run under way to end before they start. */
    const val LOSS_FIRST_WAIT_MS = 10_000L
    /**
     * 19 Nov 2026: the loss limit's sales wait this long at most for the turn (a square-off holds it for one square-off at a time);
     * then they go (a diary line says so). A square-off waits as long for the loss limit's sales.
     */
    const val SALES_TURN_WAIT_MS = 30_000L
    /** 19 Nov 2026 (second review: the lane's run and then the pass's, a second apart): the pass skips its own run of a clock step that completed (by its start) this recently. */
    const val RECENT_MS = 15_000L

    /** When the loss limit's run under way started (wall-clock ms, 0: none; memory only), for the arms' bounded wait ([Tasks.awaitLossLimit]). */
    @Volatile internal var lossStartedAt = 0L

    /**
     * One run of [name], as the lane and the pass both run it: as safety work (its Zerodha requests first). The loss limit takes no
     * turn itself (its sales do, [sales]); a single square-off takes the turn as in [runSquareOffs]. A run that completes stamps its
     * start ([lastDone]); one that throws stamps nothing.
     */
    suspend fun run(context: Context, name: String, s: AppSettings) {
        if (name != LOSS) { inTurn(name) { runOne(context, name, s) }; return }
        val t0 = clock()
        lossStartedAt = t0
        try {
            val ok = Tasks.safely {
                val w = testWork
                if (w != null) w(name) else {
                    com.optionslab.app.data.LossBreaker.check(context)
                    // The guards as they stand now, in the shared state (memory: the settings and the breaker's record), as the pass did.
                    runCatching { com.optionslab.app.data.AppState.refreshGuards() }
                }
                true
            }
            if (ok) done.merge(name, t0) { a, b -> maxOf(a, b) }
        } finally {
            if (lossStartedAt == t0) lossStartedAt = 0L
        }
    }

    /**
     * The loss limit's sales hold the turn or wait for it now. (Held with no holder - a take or a release this instant - counts too,
     * for the wait only: never a reason to go without the turn, [inTurn].)
     */
    private fun salesQueued(): Boolean = turn.holder == LOSS || turn.firstWaiting > 0 || (turn.held && turn.holder == null)

    /** 20 Nov 2026 (third review): how long the sales and the square-offs wait for each other ([SALES_TURN_WAIT_MS]; a test's shorter). */
    @Volatile internal var salesTurnWaitMs = SALES_TURN_WAIT_MS

    /**
     * A square-off's [body] in the turn. 20 Nov 2026 (third review): the wait is worked out at every look - long ([salesTurnWaitMs])
     * while the loss limit's sales hold or wait for the turn, else [ClockSteps.TURN_WAIT_MS] - so a square-off later in the sequence is
     * never left out because a sale queued after it began to wait. Waited that long behind the sales (a sale stuck at Zerodha), it goes
     * anyway, as the sales do behind a square-off ([sales]), and the diary says so: a stuck sale never keeps the 15:05 square-off or
     * the flat-by back for more than that. Null: the turn not had from another square-off (the next round tries again).
     */
    private suspend fun <T> inTurn(name: String, body: suspend () -> T): T? {
        val r = turn.take({ if (salesQueued()) salesTurnWaitMs else ClockSteps.TURN_WAIT_MS }, name) { Result.success(body()) }
        if (r != null) return r.getOrThrow()
        // Only behind the sales holding the turn (third review): behind another square-off (the sales merely queued) never.
        if (turn.holder != LOSS) return null
        runCatching { Heartbeat.diary("$name: the daily loss limit's sales held the turn ${salesTurnWaitMs / 1000} s; it goes now") }
        return body()
    }

    /**
     * The square-offs of [mode]'s look, one after another in the pass's order ([SQUARE_OFFS]): after a loss-limit run under way has
     * ended ([LOSS_FIRST_WAIT_MS] at most, so a hung one never holds them for ever), each in the turn for its own run (so a tripped
     * loss limit's sales, first in line, slot in between; 20 Nov 2026: behind a sale stuck past [salesTurnWaitMs] a square-off goes
     * anyway, [inTurn]); a turn not had from another square-off, the rest of the sequence is left to the next round, in order. Stamps [SQUARE_OFFS] only when every one completed; each stamps its own name ([runOne]).
     */
    suspend fun runSquareOffs(context: Context, s: AppSettings, mode: StopLane.Mode) {
        val t0 = clock()
        val sup = WatchWorkers.supervisor
        var waited = 0L
        while (sup != null && sup.busy(LOSS) && waited < LOSS_FIRST_WAIT_MS) { kotlinx.coroutines.delay(100); waited += 100 }
        var all = true
        for (n in squareOffsFor(mode)) {
            val ok = inTurn(n) { runOne(context, n, s) }
            if (ok == null) {
                all = false
                if (t0 - turnToldAt >= 60_000L || t0 < turnToldAt) {
                    // One diary line a minute at most (a long sale keeps the turn every round).
                    turnToldAt = t0
                    runCatching { Heartbeat.diary("$n waited for the turn (${turn.holder ?: "?"}); the next round tries again") }
                }
                break
            }
            if (!ok) all = false
        }
        if (all) done.merge(SQUARE_OFFS, t0) { a, b -> maxOf(a, b) }
    }

    /**
     * One square-off's run (its turn held): as safety work, timed under its own name; a run that completes stamps its start and
     * returns true. One that throws is told and returns false, and the next square-off still runs.
     */
    private suspend fun runOne(context: Context, name: String, s: AppSettings): Boolean {
        val t0 = clock()
        val el0 = android.os.SystemClock.elapsedRealtime()
        val ok = try {
            Tasks.safely {
                val w = testWork
                if (w != null) w(name) else when (name) {
                    EXPIRY -> com.optionslab.app.data.ExpirySquareOff.maybeRun(context, s)
                    MCX_EXPIRY -> com.optionslab.app.data.McxGuard.maybeRun(context, s)
                    FLAT_BY -> com.optionslab.app.data.FlatBySquareOff.maybeRun(context, s)
                    else -> Unit
                }
                true
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Our own end is passed on; a cancellation from inside its work (a timeout that escaped it) is its failure.
            if (kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.isActive == false) throw e
            runCatching { Tasks.stepFailed(name, e) }
            false
        } catch (e: Throwable) {
            runCatching { Tasks.stepFailed(name, e) }
            false
        } finally {
            runCatching { WatchWorkers.timed(name, android.os.SystemClock.elapsedRealtime() - el0) }
        }
        if (ok) done.merge(name, t0) { a, b -> maxOf(a, b) }
        return ok
    }

    /**
     * 19 Nov 2026 (second review: the loss limit's sales could overlap a square-off's on the same legs): [block] - the loss limit's
     * sales - in the square-offs' turn, waited for [SALES_TURN_WAIT_MS] at most; not had by then (a square-off hung), they go
     * anyway (a tripped limit always sells) and the diary says so. 20 Nov 2026 (third review): called around the exit sends only, with
     * the strategies' lock already held ([com.optionslab.app.data.Strategies.stop]) - never while waiting for that lock, and not at
     * all when nothing is running.
     */
    suspend fun <T> sales(who: String, block: suspend () -> T): T {
        // First in line ([ClockSteps.Turn.take] first): the square-offs hold back while it waits, so it has the turn between two of them.
        val r = turn.take(salesTurnWaitMs, who, first = true) { Result.success(block()) }
        if (r != null) return r.getOrThrow()
        runCatching { Heartbeat.diary("$who: a square-off held the turn ${salesTurnWaitMs / 1000} s (${turn.holder ?: "?"}); its sales go now") }
        return block()
    }

    /** 19 Nov 2026 (second review): a square-off holds the turn now (it is closing positions); the stop rescue waits. */
    fun squareOffRunning(): Boolean = turn.holder.let { it != null && it != LOSS }

    /** TEST ONLY: whether the loss limit's sales hold or wait for the turn now. */
    internal fun salesQueuedForTest(): Boolean = salesQueued()

    /** TEST ONLY: the loss limit's sales waiting for the turn (first in line) for [waitMs] at most, selling nothing. */
    internal suspend fun queueSalesForTest(waitMs: Long) { turn.take(waitMs, LOSS, first = true) { } }

    /** TEST ONLY: the turn held by [who] until [release] completes. */
    internal suspend fun holdTurnForTest(who: String, release: kotlinx.coroutines.CompletableDeferred<Unit>) { turn.take(60_000L, who) { release.await() } }

    /** When a square-off last told the diary it could not have its turn (wall-clock ms; memory only). */
    @Volatile private var turnToldAt = 0L

    /** [name] as a supervisor step (the lane's and the pass's: one name, so never two runs at once); [mode]: the square-offs' look. */
    fun step(context: Context, name: String, s: AppSettings, mode: StopLane.Mode = StopLane.Mode.NSE): Supervisor.Step =
        Supervisor.Step(name) { if (name == SQUARE_OFFS) runSquareOffs(context, s, mode) else run(context, name, s) }

    /** A round now (a clock alarm rang): memory only, never blocks; nothing when no watch runs (the alarm starts one). */
    fun poke() { lane?.poke() }

    /**
     * The lane until [stop] (read between rounds). [mode] is the watch loop's market ([StopLane.Control.mode]); [held] whether
     * anything is held (memory only, unknown counts as held); [supervisor] the watch's workers. The clock is the tests' seam.
     */
    suspend fun runLane(context: Context, stop: () -> Boolean, mode: () -> StopLane.Mode, held: () -> Boolean,
                    supervisor: () -> Supervisor?, now: () -> Long = { clock() }) {
        val l = ClockSteps.Lane(now = now)
        lane = l
        try {
            l.run(stop = stop, active = { mode() != StopLane.Mode.OFF }, failed = { e -> Tasks.stepFailed(GROUP, e) }, round = { t, since ->
                val m = mode()
                val names = stepsFor(m)
                if (names.isNotEmpty()) {
                    val heldNow = runCatching { held() }.getOrDefault(true)
                    val armsBusy = runCatching { Tasks.armsBusy() }.getOrDefault(false)
                    val due = names.filter { n -> dueThisRound(n, m, t, heldNow, armsBusy) }
                    if (due.isNotEmpty()) {
                        // Settings read fresh each round that runs a step, as each pass reads them (the kill switch never stops
                        // these: they only lower risk).
                        val s = AppSettings.load()
                        // 19 Nov 2026 (second review): the loss limit first, then the square-offs as one step in their order (it
                        // waits for the loss limit to end, [runSquareOffs]).
                        val sq = due.filter { it != LOSS }
                        val steps = listOfNotNull(if (LOSS in due) step(context, LOSS, s) else null,
                            if (sq.isNotEmpty()) step(context, SQUARE_OFFS, s, m) else null)
                        val sup = supervisor()
                        // No workers (never in the service): one after another here, as the pass would.
                        val started = if (sup != null) sup.kick(GROUP, steps) else steps.map { st -> runCatching { st.body() }; st.name }
                        started.forEach { n -> if (n == SQUARE_OFFS) { squareOffsFor(m).forEach { lastStart[it] = t } } else { lastStart[n] = t } }
                    }
                    runCatching { lateCheck(context, m, names, t, since, heldNow) }
                }
            })
        } finally {
            if (lane === l) lane = null
        }
    }

    /** When the lane last started each step (wall-clock ms; memory only). */
    private val lastStart = ConcurrentHashMap<String, Long>()

    /** How long the lane leaves its square-offs to a pass waiting on an arms group (the arms' own exits first, as in the pass). */
    const val ARMS_YIELD_MS = 30_000L

    /**
     * Whether the lane starts [name] in the round at [t]: every round while anything is held ([heldNow]) or inside the step's own
     * minutes (the expiry square-off from 15:05 to F&O's close; the flat-by while a segment's is due) - they read the books and
     * Zerodha themselves, so the holding hints never hold them back there; else once a minute, as each pass ran them. A square-off
     * is left to the pass while it waits on an arms group ([armsBusy], [ARMS_YIELD_MS] at most), so it still comes after the arms'
     * own exits.
     */
    private fun dueThisRound(name: String, mode: StopLane.Mode, t: Long, heldNow: Boolean, armsBusy: Boolean): Boolean {
        if (name != LOSS && armsBusy) return false
        if (heldNow || t - (lastStart[name] ?: 0L) >= ClockSteps.IDLE_EVERY_MS || t < (lastStart[name] ?: 0L)) return true
        val now = java.time.Instant.ofEpochMilli(t).atZone(Market.now().zone)
        val day = now.toLocalDate()
        val minute = now.hour * 60 + now.minute
        return when (name) {
            EXPIRY -> mode == StopLane.Mode.NSE && Market.isTradingDay(day) &&
                minute >= com.optionslab.engine.NseHours.EXPIRY_SQUARE_OFF && minute < Market.foClose(day)
            FLAT_BY -> runCatching { com.optionslab.app.data.FlatBySquareOff.dueNow(day, minute).isNotEmpty() }.getOrDefault(true)
            else -> false
        }
    }

    /** The scheduled minutes today a step must have completed after (null: rolling only, every round). */
    private fun scheduled(name: String, mode: StopLane.Mode, now: java.time.ZonedDateTime): Long? {
        val day = now.toLocalDate()
        if (!Market.isTradingDay(day) && mode == StopLane.Mode.NSE) return null
        return when (name) {
            EXPIRY -> if (now.hour * 60 + now.minute < Market.foClose(day)) ClockSteps.lastScheduled(day, listOf(com.optionslab.engine.NseHours.EXPIRY_SQUARE_OFF), now) else null
            FLAT_BY -> ClockSteps.lastScheduled(day, if (mode == StopLane.Mode.MCX)
                listOf(com.optionslab.engine.risk.FlatBy.minute(com.optionslab.engine.risk.FlatBy.Segment.MCX, day, com.optionslab.app.data.FlatBySquareOff.mcxCalendar()))
                else listOf(com.optionslab.engine.risk.FlatBy.NSE_STOCK_MINUTE, com.optionslab.engine.risk.FlatBy.NSE_INDEX_MINUTE), now)
            else -> null
        }
    }

    /** A due step not completed 45 s after its time, with anything held: one RISK alert (and a diary line) per step and time. */
    private fun lateCheck(context: Context, mode: StopLane.Mode, names: List<String>, nowMs: Long, since: Long, held: Boolean) {
        if (!held) return
        val now = java.time.Instant.ofEpochMilli(nowMs).atZone(Market.now().zone)
        for (name in names) {
            // 19 Nov 2026 (second review): each judged by its own due time, whoever holds the turn (a square-off starved behind a slow
            // one is late too, and said).
            val last = lastDone(name)
            val due = ClockSteps.dueAt(last, since, runCatching { scheduled(name, mode, now) }.getOrNull(), ClockSteps.IDLE_EVERY_MS)
            if (!ClockSteps.late(due, last, nowMs)) continue
            if (!told.first(now.toLocalDate(), "$name|$due")) continue
            val hhmmss = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
            val dueText = java.time.Instant.ofEpochMilli(due).atZone(now.zone).format(hhmmss)
            val lastText = if (last > 0L) java.time.Instant.ofEpochMilli(last).atZone(now.zone).format(hhmmss) else "not yet today"
            val text = "The $name check due at $dueText has not completed (last completed: $lastText) and positions are open. " +
                "It keeps trying every 15 s; check your positions in Trade."
            runCatching { com.optionslab.app.data.Diag.record("risk", text) }
            runCatching { Notifier.post(context, NOTE_ID, Notifier.RISK, "Safety check late", text, "trade") }
        }
    }

    /** The late-check notice's id. */
    private const val NOTE_ID = 2037

    /** The diagnostics' line: each clock step's last completed run. */
    fun line(): String {
        val zone = runCatching { Market.now().zone }.getOrNull() ?: return "Clock lane: -"
        val f = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
        return "Clock lane (last completed): " + listOf(LOSS, EXPIRY, MCX_EXPIRY, FLAT_BY).joinToString(" · ") { n ->
            "$n " + (done[n]?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).format(f) } ?: "-")
        }
    }

    /** TEST ONLY. */
    internal fun resetForTest() { done.clear(); lastStart.clear(); lossStartedAt = 0L; salesTurnWaitMs = SALES_TURN_WAIT_MS; testWork = null; lane = null; clock = System::currentTimeMillis; told = ClockSteps.Once() }
}

/**
 * 18 Nov 2026 (audit: no alarm woke the watch for 15:05, 15:20 or 15:25): an exact alarm-clock alarm ([AlarmManager.setAlarmClock]:
 * never put off by Doze) one minute before the 15:05 expiry square-off on an expiry day, and before the 15:20 and 15:25 flat-by on
 * every trading day ([ClockSteps.nextAlarm]). It starts the market watch first if it is not running (the same start as the 09:14
 * alarm and the dead-man check), then asks the clock lane for a round at once, then arms the next one.
 */
object ClockAlarm {
    const val ACTION = "ol.clock"

    /** The indices whose expiry day the 15:04 alarm is for, by the exchanges' weekday rule (no contract list needed, no vault read). */
    private val EXPIRY_NAMES = listOf("NIFTY", "SENSEX", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY")

    private fun intent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 195, Intent(context, AlarmReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Whether [day] is any index's expiry day by the weekday rule (holidays shifted back; a pure calendar read). */
    internal fun expiryDay(day: java.time.LocalDate): Boolean =
        EXPIRY_NAMES.any { com.optionslab.engine.orb.ExpiryRule.isExpiry(it, day) { d -> Market.isTradingDay(d) } }

    /** The next alarm (epoch ms), or null on a broken calendar. */
    internal fun nextAt(from: java.time.ZonedDateTime = Market.now()): Long? =
        ClockSteps.nextAlarm(from, { Market.isTradingDay(it) }, ::expiryDay)?.toInstant()?.toEpochMilli()

    fun schedule(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = intent(context)
        am.cancel(pi)
        val at = nextAt() ?: return
        try {
            if (Jobs.canExact(context)) am.setAlarmClock(AlarmManager.AlarmClockInfo(at, Notifier.openApp(context)), pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    /** The alarm rang (off the main thread, [AlarmReceiver]): the watch first, then a round now, then the next alarm. */
    fun fired(context: Context) {
        // Started only when not alive in this process (its own pulse): a running watch's start is a main-thread binder call for nothing.
        if (!Heartbeat.aliveNow()) runCatching { Jobs.ensureWatch(context) }
        ClockLane.poke()
        runCatching { schedule(context) }
    }
}
