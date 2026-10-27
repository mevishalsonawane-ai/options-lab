package com.optionslab.app.work

import android.content.Context
import com.optionslab.ira.BudgetRule
import java.time.LocalDateTime

/**
 * The battery and heat budget for nightly and background work (S18; the rule is ira-core's [BudgetRule]). The strategy
 * forge ([DiscoveryWorker]), the history vault, the weekly consolidation and Jarvis's nightly learning call
 * [allow] before they start: no when the battery is under 30% and not charging, hotter than 40 °C, in Android's power-save
 * mode, or when tonight's background CPU time is spent (the process's CPU time within the jobs' runs, at most
 * [BudgetRule.NIGHT_CPU_MS] a night). Each "no" is noted once a night in the diagnostics.
 *
 * Reads the battery's sticky broadcast and the power manager only - no settings, no network, never the order path, the
 * market watch, a stop or an exit (those never ask).
 */
object BackgroundBudget {
    @Volatile private var appContext: Context? = null
    /** The budget now ([BudgetRule.budgetOf]: "2026-10-12 day" or "2026-10-12 night"). */
    @Volatile private var night: String? = null
    @Volatile private var lastSaid: String? = null

    /** TEST ONLY: the phone's state and the CPU time instead of Android's. */
    @Volatile internal var testPhone: BudgetRule.Phone? = null
    @Volatile internal var testCpuMs: (() -> Long)? = null
    @Volatile internal var testNow: LocalDateTime? = null
    @Volatile internal var testWallMs: (() -> Long)? = null

    fun init(context: Context) { appContext = context.applicationContext; marksContext = context.applicationContext }

    /** 22 Oct: where the spent CPU is kept ([NightMarks]); set by [init] only (the tests that do not init keep nothing). */
    @Volatile private var marksContext: Context? = null

    /** The battery now from its sticky broadcast and the power manager (unknown parts null). */
    fun phone(c: Context? = appContext): BudgetRule.Phone {
        testPhone?.let { return it }
        if (c == null) return BudgetRule.Phone(null, false, null, false)
        // 27 Oct (battery audit): through Battery's 30-second cache - the history keeper asked Android twice for each of its ~62
        // pieces a run, at night while the app was cached ("excessive binder traffic", 25 Oct).
        val r = Battery.reading(c)
        val temp = r.tempTenths?.let { BudgetRule.tempOf(it) }
        return BudgetRule.Phone(r.percent, r.charging, temp, Battery.powerSave(c))
    }

    /**
     * The CPU clock of the thread asking (12 Oct): the job's own thread's CPU time, never the whole process's. The process's
     * clock counted the market watch, the price stream, the screen and Jarvis's model on every core as the job's: a history
     * vault run beside the MCX evening watch "spent tonight's 10 minutes" by 16:24 ("Kept 24 pieces of 62"). [testCpuMs]
     * stands in for it in tests (one clock).
     */
    private fun cpuMs(): Long = testCpuMs?.invoke() ?: runCatching { android.os.SystemClock.currentThreadTimeMillis() }.getOrDefault(0L)

    private const val RUN_GAP_MS = 120_000L
    /**
     * One meter per thread (each with its own CPU clock): per job, the clock at its last [allow] on that thread (a run is the
     * calls less than [RUN_GAP_MS] apart); two jobs on one thread count its CPU once (review G8-10). Tests: one meter.
     */
    private val meters = java.util.concurrent.ConcurrentHashMap<Long, BudgetRule.CpuMeter>()

    private fun meter(): BudgetRule.CpuMeter =
        meters.getOrPut(if (testCpuMs != null) 0L else Thread.currentThread().id) { BudgetRule.CpuMeter(RUN_GAP_MS) }

    /**
     * The background CPU spent in this budget so far: within a job's run (its [allow] calls between steps), its thread's CPU
     * time from one call to the next is counted; the gaps between runs are not, nor any other thread's work.
     */
    private fun spent(job: BudgetRule.Job): Long {
        roll()
        meter().ask(job, cpuMs(), wallMs())
        return total().also { keep(it, force = false) }
    }

    private fun wallMs(): Long = testWallMs?.invoke() ?: System.currentTimeMillis()

    /** A new budget starts afresh: the day's (08:00-16:00) and the night's are separate (review G8-10). */
    private fun roll() {
        val now = testNow ?: runCatching { com.optionslab.app.data.Market.now().toLocalDateTime() }.getOrElse { LocalDateTime.now() }
        val n = BudgetRule.budgetOf(now)
        if (night != n) { night = n; meters.clear(); lastSaid = null; carried = 0L; carryRead = false; savedMs = 0L }
    }

    // ---- 22 Oct (the overnight kills): what the budget spent survives a restart ---------------------------------------------
    // Android ended the app at 02:43 and 02:51; each restart began tonight's 10 minutes afresh (the meters are memory only),
    // so a job killed for its CPU could run again on a whole new budget. What this budget has spent is kept now ([NightMarks]:
    // the budget's name and its milliseconds) every few seconds of CPU and at the end of every run, and read back once per
    // budget by the first job thread that asks. Never on the main thread, never on the order or tick path.

    private const val K_SPENT = "budget.spent"
    /** What this budget spent before this process started (read back; 0 for a new budget). */
    @Volatile private var carried = 0L
    @Volatile private var carryRead = false
    /** The total last kept. */
    @Volatile private var savedMs = 0L

    /** This budget's spent CPU: before this process ([carried]) and in it (the meters). */
    private fun total(): Long {
        if (!carryRead) {
            val n = night
            // Read only where the marks can be (a job's thread, the place known): never a 0 taken for "nothing kept".
            if (n != null && marksContext != null && NightMarks.usable()) {
                carried = com.optionslab.ira.NightStart.carried(n, NightMarks.get(marksContext, K_SPENT))
                savedMs = maxOf(savedMs, carried)
                carryRead = true
            }
        }
        return carried + meters.values.sumOf { it.spentMs }
    }

    /** [spentMs] kept for this budget when it grew enough since the last time ([force]: at the end of a run, whenever it grew). */
    private fun keep(spentMs: Long, force: Boolean) {
        val n = night ?: return
        if (!carryRead) return          // never written over a kept figure not yet read back
        if (!(if (force) spentMs > savedMs else com.optionslab.ira.NightStart.saveDue(savedMs, spentMs))) return
        savedMs = spentMs
        NightMarks.put(marksContext, K_SPENT, com.optionslab.ira.NightStart.carryEncode(n, spentMs))
    }

    /**
     * [job]'s run has ended (call it in a finally): the CPU since its last [allow] is counted however long that was, so a
     * run longer than [RUN_GAP_MS] between two asks (or one that asks only at its start) is not free.
     */
    fun done(job: BudgetRule.Job) {
        roll()
        meter().done(job, cpuMs())
        keep(total(), force = true)
    }

    /** May [job] run (or go on) now? Each call is cheap: one sticky-broadcast read and the CPU clock. */
    fun allow(job: BudgetRule.Job): Boolean {
        val d = BudgetRule.decide(job, phone(), spent(job))
        if (!d.allow && lastSaid != d.why) {
            lastSaid = d.why
            runCatching { com.optionslab.app.data.Diag.record("budget", d.why) }
        }
        return d.allow
    }

    /**
     * 22 Oct (the overnight kills, "excessive binder traffic during cached"): [allow] for a check asked very often - the forge asks
     * its stop check on every one of a backtest's 199 twins, for up to 20 ideas: thousands of battery and power-manager reads (each a
     * call into Android) a night. This one asks [allow] at most once every [everyMs] and answers from the last ask in between.
     * Memory only.
     */
    fun allowEvery(job: BudgetRule.Job, everyMs: Long = CHECK_EVERY_MS, clock: () -> Long = { android.os.SystemClock.elapsedRealtime() }): () -> Boolean =
        Throttled(everyMs, clock) { allow(job) }

    const val CHECK_EVERY_MS = 5_000L

    /** [check]'s answer, asked again only once [everyMs] have passed on [clock] since the last ask (the first call always asks). */
    internal class Throttled(private val everyMs: Long, private val clock: () -> Long, private val check: () -> Boolean) : () -> Boolean {
        private var at = 0L
        private var asked = false
        private var last = true
        @Synchronized override fun invoke(): Boolean {
            val now = clock()
            if (!asked || now - at >= everyMs) { last = check(); at = now; asked = true }
            return last
        }
    }

    /**
     * The budget now ([BudgetRule.budgetOf]) and what it has spent so far (the health check, 12 Oct): nothing is asked or counted
     * by this. 22 Oct: with what it spent before a restart (read back once, off the main thread only).
     */
    fun spentNow(): Pair<String, Long> { roll(); return (night ?: "") to total() }

    /** TEST ONLY. */
    internal fun resetForTest() {
        night = null; meters.clear(); lastSaid = null; testPhone = null; testCpuMs = null; testNow = null; testWallMs = null
        carried = 0L; carryRead = false; savedMs = 0L; marksContext = null
    }

    /** TEST ONLY: as after Android ended the app - the meters and what was read back gone, what was kept on disk still there. */
    internal fun restartForTest() { night = null; meters.clear(); lastSaid = null; carried = 0L; carryRead = false; savedMs = 0L }
}
