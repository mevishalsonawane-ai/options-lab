package com.optionslab.ira

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * The order watch's workers (Boss, 10 Oct: "many agents in parallel, nothing waits on unrelated work"): each worker is a
 * coroutine of its own in [scope] (a supervisor scope: one worker failing never cancels another), on its own schedule, with
 * a time limit, restarted after a failure with a growing pause ([backoffMs]), and its state kept for the diagnostics'
 * "Workers" section ([statuses]).
 *
 * Two kinds of work:
 *  - [start]: a worker on its own clock ([Spec.nextDelayMs]; null = asleep while its market is closed). A run past
 *    [Spec.timeoutMs] is cancelled when [Spec.cancelOnTimeout] (reads, words: nothing is lost), else let finish and shown as
 *    [State.OVERRUN] - anything that may place an order is never cut between the order and its booking.
 *  - [group]: a set of steps started side by side from a pass (the arms after the safety steps), each in its own try, the
 *    caller waiting [group]'s `waitMs` at most. A step still running then is left to finish by itself (never cancelled:
 *    it may be mid-order) and is not started again until it has ([State.OVERRUN]); the caller goes on.
 *
 * Failures are kept (class name only, never a message: it can carry a URL or a token) and reported through [onError];
 * every run's duration goes to [onTimed]. Nothing here places, changes or closes an order.
 */
class Supervisor(
    private val scope: CoroutineScope,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val onTimed: (name: String, ms: Long) -> Unit = { _, _ -> },
    private val onError: (name: String, e: Throwable) -> Unit = { _, _ -> },
    /** A worker restarted after repeated failures ([SelfHeal.shouldRestart]): told once per restart. */
    private val onRestart: (name: String, failuresInRow: Int) -> Unit = { _, _ -> },
) {
    enum class State(val words: String) {
        IDLE("idle"),
        RUNNING("running"),
        OVERRUN("running past its time limit"),
        BACKING_OFF("backing off after a failure"),
        SLEEPING("asleep (its market is closed)"),
        STOPPED("stopped"),
    }

    /** One worker's or step's state for the diagnostics. Times are epoch ms; [lastError] a class name. */
    data class Status(
        val name: String,
        val group: String,
        val state: State,
        val lastStart: Long? = null,
        val lastMs: Long? = null,
        val runs: Int = 0,
        val errors: Int = 0,
        val lastError: String? = null,
        val failuresInRow: Int = 0,
        val nextAt: Long? = null,
        /** Fresh starts after repeated failures ([SelfHeal.Restart]). */
        val restarts: Int = 0,
    )

    /** A worker on its own clock ([start]). */
    class Spec(
        val name: String,
        val group: String,
        val timeoutMs: Long,
        /** True: a run past [timeoutMs] is cancelled. False: it finishes (it may be placing an order) and counts as a failure. */
        val cancelOnTimeout: Boolean,
        /** Milliseconds until the next run (0: now); null: asleep (its market is closed), asked again after [sleepMs]. */
        val nextDelayMs: suspend () -> Long?,
        val sleepMs: Long = 60_000,
        val backoffBaseMs: Long = 5_000,
        val backoffCapMs: Long = 5 * 60_000,
        /** Self-healing (10 Oct, part 2): restarted fresh after this many failures in a row (null: never). */
        val restart: SelfHeal.Restart? = null,
    )

    /** A step of a [group]: its [name] (the app's own words) and its work. */
    class Step(val name: String, val body: suspend () -> Unit)

    /** A run that went past its time limit (kept as its failure; the run itself was cancelled or let finish). */
    class TimedOut(name: String, ms: Long) : RuntimeException("$name ran past its ${ms / 1000} s limit")

    private val states = ConcurrentHashMap<String, Status>()
    private val inFlight = ConcurrentHashMap<String, Job>()
    private val workers = ConcurrentHashMap<String, Job>()

    /** Every worker and step seen, by group then name. */
    fun statuses(): List<Status> = states.values.sortedWith(compareBy({ it.group }, { it.name }))

    fun status(name: String): Status? = states[name]

    private fun update(name: String, group: String, f: (Status) -> Status) {
        states.compute(name) { _, old -> f(old ?: Status(name, group, State.IDLE)) }
    }

    /** Is [name] (a worker's run or a group's step) running now? */
    fun busy(name: String): Boolean = inFlight[name]?.isActive == true

    /**
     * Start [spec]'s worker (a second start of a name still running is a no-op, returning the running one). It runs until
     * [scope] ends or [stop]; a failure (even an Error, even in [Spec.nextDelayMs]) is kept and the worker carries on after
     * [backoffMs].
     */
    fun start(spec: Spec, body: suspend () -> Unit): Job {
        workers[spec.name]?.takeIf { it.isActive }?.let { return it }
        update(spec.name, spec.group) { it.copy(state = State.IDLE) }
        val job = scope.launch {
            var failures = 0
            try {
                while (currentCoroutineContext().isActive) {
                    val wait = try {
                        spec.nextDelayMs()
                    } catch (e: CancellationException) {
                        if (!currentCoroutineContext().isActive) throw e
                        failures++; fail(spec.name, spec.group, e, failures); -1L
                    } catch (e: Throwable) {
                        failures++; fail(spec.name, spec.group, e, failures); -1L
                    }
                    if (wait == -1L) { backOff(spec, failures); continue }
                    if (wait == null) {
                        update(spec.name, spec.group) { it.copy(state = State.SLEEPING, nextAt = clock() + spec.sleepMs) }
                        delay(spec.sleepMs)
                        continue
                    }
                    if (wait > 0) {
                        update(spec.name, spec.group) { it.copy(state = State.IDLE, nextAt = clock() + wait) }
                        delay(wait)
                    }
                    val ok = runWorker(spec, body, failures + 1)
                    if (ok) failures = 0 else {
                        failures++
                        if (restartDue(spec, failures)) failures = 0
                        backOff(spec, failures.coerceAtLeast(1))
                    }
                }
            } finally {
                update(spec.name, spec.group) { it.copy(state = State.STOPPED, nextAt = null) }
            }
        }
        workers[spec.name] = job
        return job
    }

    private val restartTimes = ConcurrentHashMap<String, ArrayDeque<Long>>()

    /**
     * Self-healing: after [Spec.restart]'s count of failures in a row the worker starts fresh (its pause back to the
     * shortest, counted and told), at most its limit an hour; past that it keeps backing off as before.
     */
    private fun restartDue(spec: Spec, failures: Int): Boolean {
        val policy = spec.restart ?: return false
        val now = clock()
        val times = restartTimes.getOrPut(spec.name) { ArrayDeque() }
        val recent = synchronized(times) { while (times.isNotEmpty() && now - times.first() > 3_600_000L) times.removeFirst(); times.size }
        if (!SelfHeal.shouldRestart(failures, recent, policy)) return false
        synchronized(times) { times.addLast(now) }
        update(spec.name, spec.group) { it.copy(restarts = it.restarts + 1, failuresInRow = 0) }
        runCatching { onRestart(spec.name, failures) }
        return true
    }

    private suspend fun backOff(spec: Spec, failures: Int) {
        val pause = backoffMs(failures, spec.backoffBaseMs, spec.backoffCapMs)
        update(spec.name, spec.group) { it.copy(state = State.BACKING_OFF, nextAt = clock() + pause) }
        delay(pause)
    }

    private fun fail(name: String, group: String, e: Throwable, inRow: Int) {
        update(name, group) { it.copy(errors = it.errors + 1, lastError = e.javaClass.simpleName, failuresInRow = inRow) }
        runCatching { onError(name, e) }
    }

    /** One run of a worker: true when it ended well within its time. */
    private suspend fun runWorker(spec: Spec, body: suspend () -> Unit, inRowIfFailed: Int): Boolean {
        val t0 = clock()
        update(spec.name, spec.group) { it.copy(state = State.RUNNING, lastStart = t0, nextAt = null) }
        var failed: Throwable? = null
        try {
            if (spec.cancelOnTimeout) {
                withTimeout(spec.timeoutMs) { body() }
            } else supervisorScope {
                val d = async { body() }
                inFlight[spec.name] = d
                if (withTimeoutOrNull(spec.timeoutMs) { d.join() } == null) {
                    failed = TimedOut(spec.name, spec.timeoutMs)
                    update(spec.name, spec.group) { it.copy(state = State.OVERRUN) }
                }
                d.await()
            }
        } catch (e: CancellationException) {
            // The worker's own end is passed on; a cancellation from inside the run (its time limit, a timeout that
            // escaped it) is kept as its failure.
            if (!currentCoroutineContext().isActive) throw e
            failed = if (spec.cancelOnTimeout && e is TimeoutCancellationException) TimedOut(spec.name, spec.timeoutMs) else e
        } catch (e: Throwable) {
            failed = e
        } finally {
            inFlight.remove(spec.name)
            val ms = clock() - t0
            runCatching { onTimed(spec.name, ms) }
            update(spec.name, spec.group) { it.copy(lastMs = ms, runs = it.runs + 1, state = State.IDLE) }
        }
        failed?.let { fail(spec.name, spec.group, it, inRowIfFailed); return false }
        update(spec.name, spec.group) { it.copy(failuresInRow = 0) }
        return true
    }

    /**
     * [steps] started side by side (each with the caller's context: its dispatcher and markers, such as an order's
     * timing trigger), each in its own try; waits [waitMs] at most for all of them. A step whose previous run is still
     * going is not started again. Returns the names still running when the wait ended (they finish by themselves).
     */
    suspend fun group(group: String, steps: List<Step>, waitMs: Long): List<String> {
        val inherited = currentCoroutineContext().minusKey(Job)
        val jobs = ArrayList<Job>()
        for (s in steps) {
            if (inFlight[s.name]?.isActive == true) {
                update(s.name, group) { it.copy(state = State.OVERRUN) }
                continue
            }
            val job = scope.launch(inherited, start = CoroutineStart.LAZY) { runStep(group, s) }
            inFlight[s.name] = job
            job.invokeOnCompletion { inFlight.remove(s.name, job) }
            job.start()
            jobs += job
        }
        withTimeoutOrNull(waitMs) { jobs.joinAll() }
        val late = steps.map { it.name }.filter { inFlight[it]?.isActive == true }
        for (n in late) update(n, group) { it.copy(state = State.OVERRUN) }
        return late
    }

    private suspend fun runStep(group: String, s: Step) {
        val t0 = clock()
        update(s.name, group) { it.copy(state = State.RUNNING, lastStart = t0) }
        var failed: Throwable? = null
        try {
            s.body()
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) throw e
            failed = e
        } catch (e: Throwable) {
            failed = e
        } finally {
            val ms = clock() - t0
            runCatching { onTimed(s.name, ms) }
            update(s.name, group) { it.copy(lastMs = ms, runs = it.runs + 1, state = State.IDLE) }
        }
        val e = failed
        if (e != null) fail(s.name, group, e, (states[s.name]?.failuresInRow ?: 0) + 1)
        else update(s.name, group) { it.copy(failuresInRow = 0) }
    }

    /** Stop every worker [start]ed here (a group's steps already running finish by themselves). */
    fun stop() {
        workers.values.forEach { it.cancel() }
        workers.clear()
    }

    companion object {
        /** The pause after the [failures]th failure in a row: [baseMs], doubled each time, at most [capMs]. */
        fun backoffMs(failures: Int, baseMs: Long, capMs: Long): Long {
            if (failures <= 0) return 0L
            var v = baseMs.coerceAtLeast(0L)
            repeat(minOf(failures - 1, 30)) { v = (v * 2).coerceAtMost(capMs) }
            return v.coerceAtMost(capMs)
        }

        /** One worker in words for the diagnostics: "Jarvis words (analysis): idle · last run 10:31:05, 1.2 s · 2 errors (IOException)". */
        fun words(s: Status, hhmmss: (Long) -> String): String = buildString {
            append(s.name).append(" (").append(s.group).append("): ").append(s.state.words)
            s.lastStart?.let { append(" · last run ").append(hhmmss(it)) }
            s.lastMs?.let { append(", ").append(StepStats.ms(it)) }
            append(" · ").append(s.runs).append(if (s.runs == 1) " run" else " runs")
            if (s.errors > 0) append(" · ").append(s.errors).append(if (s.errors == 1) " error" else " errors")
                .append(s.lastError?.let { " (last: $it)" } ?: "")
            if (s.restarts > 0) append(" · restarted ").append(s.restarts).append(if (s.restarts == 1) " time" else " times")
        }
    }
}
