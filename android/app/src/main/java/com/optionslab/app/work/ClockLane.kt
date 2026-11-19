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
 *  - the three square-offs take one turn ([ClockSteps.Turn]): they ran one after another in the pass and still never run at the
 *    same time, so two of them never sell one contract twice. The loss limit takes no turn (it ran beside the stop lane's exits
 *    before too);
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
     * One run of [name], as the lane and the pass both run it: as safety work (its Zerodha requests first), a square-off in its
     * turn. A run that completes stamps its start ([lastDone]); one that throws, or could not have its turn, stamps nothing.
     */
    suspend fun run(context: Context, name: String, s: AppSettings) {
        val t0 = clock()
        val ok = Tasks.safely {
            val work: suspend () -> Unit = testWork?.let { w -> suspend { w(name) } } ?: when (name) {
                LOSS -> suspend {
                    com.optionslab.app.data.LossBreaker.check(context)
                    // The guards as they stand now, in the shared state (memory: the settings and the breaker's record), as the pass did.
                    runCatching { com.optionslab.app.data.AppState.refreshGuards() }
                    Unit
                }
                EXPIRY -> suspend { com.optionslab.app.data.ExpirySquareOff.maybeRun(context, s) }
                MCX_EXPIRY -> suspend { com.optionslab.app.data.McxGuard.maybeRun(context, s) }
                FLAT_BY -> suspend { com.optionslab.app.data.FlatBySquareOff.maybeRun(context, s) }
                else -> suspend { }
            }
            if (name == LOSS) { work(); true } else turn.take { work(); true } ?: false
        }
        if (ok) done.merge(name, t0, ::maxOf)
        else if (t0 - turnToldAt >= 60_000L || t0 < turnToldAt) {
            // One diary line a minute at most (a hung square-off keeps the turn every round).
            turnToldAt = t0
            runCatching { Heartbeat.diary("$name waited ${ClockSteps.TURN_WAIT_MS / 1000} s for another square-off to finish; the next round tries again") }
        }
    }

    /** When a square-off last told the diary it could not have its turn (wall-clock ms; memory only). */
    @Volatile private var turnToldAt = 0L

    /** [name] as a supervisor step (the lane's and the pass's: one name, so never two runs at once). */
    fun step(context: Context, name: String, s: AppSettings): Supervisor.Step = Supervisor.Step(name) { run(context, name, s) }

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
                    // Settings read fresh each round, as each pass reads them (the kill switch never stops these: they only lower risk).
                    val s = AppSettings.load()
                    val steps = names.map { step(context, it, s) }
                    val sup = supervisor()
                    // No workers (never in the service): one after another here, as the pass would.
                    if (sup != null) sup.kick(GROUP, steps) else steps.forEach { st -> runCatching { st.body() } }
                    runCatching { lateCheck(context, m, names, t, since, held) }
                }
            })
        } finally {
            if (lane === l) lane = null
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
    private fun lateCheck(context: Context, mode: StopLane.Mode, names: List<String>, nowMs: Long, since: Long, held: () -> Boolean) {
        val now = java.time.Instant.ofEpochMilli(nowMs).atZone(Market.now().zone)
        for (name in names) {
            val last = lastDone(name)
            val due = ClockSteps.dueAt(last, since, runCatching { scheduled(name, mode, now) }.getOrNull())
            if (!ClockSteps.late(due, last, nowMs)) continue
            if (!runCatching { held() }.getOrDefault(true)) continue
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
    internal fun resetForTest() { done.clear(); testWork = null; lane = null; clock = System::currentTimeMillis; told = ClockSteps.Once() }
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
