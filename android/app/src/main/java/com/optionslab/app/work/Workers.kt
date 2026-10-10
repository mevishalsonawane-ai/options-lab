package com.optionslab.app.work

import com.optionslab.app.data.Market
import com.optionslab.ira.StepStats
import com.optionslab.ira.Supervisor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The order watch's workers (Boss, 10 Oct: "many agents in parallel, all sharing the same memory, nothing waiting on
 * unrelated work"), and the timing of every step they run.
 *
 *  - Safety first, in order, in each pass: the price stream, paper fills, the daily loss limit (and, after the arms, the
 *    stops and targets, the expiry square-off, the MCX expiry exit, the sweep, the cards and the beat).
 *  - The arms side by side ([Tasks] runs them as a [Supervisor.group]): ORB / Liquidity / Hero, Night, VIX divergence and
 *    the Pine scripts; after the square-off the MCX paper arms, the strategies, Jarvis's trades and Solo. Each in its own
 *    try, each under its own lock as before; one still running past [ARMS_WAIT_MS] is left to finish (never cut mid-order)
 *    and the pass goes on. Every new position still goes through the one door ([com.optionslab.ira.EntryGate] in
 *    [com.optionslab.app.data.Broker.placeOrder]) and the one-index claims ([com.optionslab.app.data.AutoExposure]).
 *  - Jarvis's words-only checks: a worker of their own at the lowest priority ([Supervisor.start]).
 *  - The event-driven lanes stay as they are ([com.optionslab.app.data.FastPath]).
 *
 * Everything runs on the shared IO pool (no thread of its own per worker). Memory only.
 */
object WatchWorkers {
    /** Today's timing of every named step ([StepStats]): the diagnostics and the Order speed card say the slowest. */
    val stats = StepStats()

    /** How long a pass waits for its arms (they finish by themselves after that). */
    const val ARMS_WAIT_MS = 45_000L
    /** How long the 15-second stop check waits for the arms' exits. */
    const val EXITS_WAIT_MS = 20_000L

    /** One watch run's workers: its supervisor and the job they all run under. */
    class Run internal constructor(val supervisor: Supervisor, internal val job: Job)

    @Volatile private var current: Run? = null
    /** The last run's workers, kept for the diagnostics after the watch ended. */
    @Volatile private var last: Run? = null

    /** The supervisor of the watch running now (null: none, or a test's pass: everything then runs in order). */
    val supervisor: Supervisor? get() = current?.supervisor

    /** Jarvis's words run as a worker of the watch running now (the pass then does not start its own lane for them). */
    val wordsByWorker: Boolean get() = current?.supervisor?.status(WORDS)?.let { it.state != Supervisor.State.STOPPED } == true

    /** The words worker's name. */
    const val WORDS = "Jarvis words"

    /** Today's date as text, worked out once a minute (the hot paths time every step: no date arithmetic per step). */
    @Volatile private var dayKept: Pair<Long, String>? = null

    private fun day(): String {
        val minute = System.currentTimeMillis() / 60_000L
        dayKept?.let { (m, d) -> if (m == minute) return d }
        return Market.today().toString().also { dayKept = minute to it }
    }

    fun timed(name: String, ms: Long) {
        runCatching { stats.record(name, ms, day()) }
    }

    /**
     * A watch run starts its workers in [scope] (its own coroutine's): they end with it ([end]). A newer run replaces an
     * older one as the current; each run ends only its own.
     */
    fun begin(scope: CoroutineScope): Run {
        val job = SupervisorJob(scope.coroutineContext[Job])
        val s = Supervisor(CoroutineScope(scope.coroutineContext + job + kotlinx.coroutines.Dispatchers.IO),
            onTimed = { n, ms -> timed(n, ms) }, onError = { n, e -> Tasks.stepFailed(n, e) })
        return Run(s, job).also { current = it; last = it }
    }

    /** [run]'s normal end (the day's end): arms still finishing an entry or an exit get up to [graceMs] first. */
    suspend fun drain(run: Run, graceMs: Long = 60_000L) {
        run.supervisor.stop()
        withTimeoutOrNull(graceMs) { while (run.job.children.any { it.isActive }) kotlinx.coroutines.delay(100) }
    }

    /** [run] ended (or was stopped): its workers end with it. */
    fun end(run: Run) {
        run.supervisor.stop()
        run.job.cancel()
        synchronized(this) { if (current === run) current = null }
    }

    /** Is the battery saver on? Workers that only talk slow down then. */
    fun saver(context: android.content.Context?): Boolean =
        runCatching { context?.getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode == true }.getOrDefault(false)

    private val HHMMSS = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")

    /** The diagnostics' "Workers" section: each worker and step, its state, last run, duration, errors. */
    fun diagLines(): List<String> {
        val s = (current ?: last)?.supervisor
        val zone = Market.now().zone
        val rows = s?.statuses().orEmpty().map { st ->
            Supervisor.words(st) { java.time.Instant.ofEpochMilli(it).atZone(zone).format(HHMMSS) }
        }
        val head = when {
            current != null -> "Workers (the watch is running):"
            last != null -> "Workers (the watch has ended; as they last were):"
            else -> "Workers: the watch has not run in this process yet"
        }
        val fast = "Event-driven lanes (stream, live exits, minute, bar close): " +
            if (runCatching { com.optionslab.app.data.FastPath.running }.getOrDefault(false)) "running" else "off"
        return listOf(head) + rows.map { "  $it" } + listOf("  $fast")
    }

    /** The slowest steps today, in one line. */
    fun slowestLine(n: Int = 5): String = StepStats.line(stats.slowest(day(), n))

    /**
     * The Order speed card's lines (10 Oct): the whole watch pass, the 15-second stop check and the arms' groups as timed
     * today (p50 / p95 / max), then the slowest steps. Memory only.
     */
    fun speedLines(): List<String> {
        val rows = stats.rows(day()).associateBy { it.name }
        val main = listOf(Tasks.PASS_TOTAL, "15-second stop check (total)", "arms (together)", "arms after the square-off (together)",
            "bar-close entry check (together)", "15-second stop check (together)")
            .mapNotNull { rows[it]?.let { r -> StepStats.words(r) } }
        return main + slowestLine()
    }

    /** TEST ONLY. */
    internal fun resetForTest() { current?.let { end(it) }; current = null; last = null; dayKept = null; stats.clear() }
}
