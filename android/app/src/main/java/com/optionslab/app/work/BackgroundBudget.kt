package com.optionslab.app.work

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
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

    fun init(context: Context) { appContext = context.applicationContext }

    /** The battery now from its sticky broadcast and the power manager (unknown parts null). */
    fun phone(c: Context? = appContext): BudgetRule.Phone {
        testPhone?.let { return it }
        if (c == null) return BudgetRule.Phone(null, false, null, false)
        val i: Intent? = runCatching { c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        val pct = i?.let { BudgetRule.pctOf(it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)) }
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val temp = i?.let { BudgetRule.tempOf(it.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)) }
        val saver = runCatching { c.getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode == true }.getOrDefault(false)
        return BudgetRule.Phone(pct, charging, temp, saver)
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
        return meters.values.sumOf { it.spentMs }
    }

    private fun wallMs(): Long = testWallMs?.invoke() ?: System.currentTimeMillis()

    /** A new budget starts afresh: the day's (08:00-16:00) and the night's are separate (review G8-10). */
    private fun roll() {
        val now = testNow ?: runCatching { com.optionslab.app.data.Market.now().toLocalDateTime() }.getOrElse { LocalDateTime.now() }
        val n = BudgetRule.budgetOf(now)
        if (night != n) { night = n; meters.clear(); lastSaid = null }
    }

    /**
     * [job]'s run has ended (call it in a finally): the CPU since its last [allow] is counted however long that was, so a
     * run longer than [RUN_GAP_MS] between two asks (or one that asks only at its start) is not free.
     */
    fun done(job: BudgetRule.Job) {
        roll()
        meter().done(job, cpuMs())
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
     * The budget now ([BudgetRule.budgetOf]) and what it has spent so far (the health check, 12 Oct): memory only - nothing is
     * asked or counted by this.
     */
    fun spentNow(): Pair<String, Long> { roll(); return (night ?: "") to meters.values.sumOf { it.spentMs } }

    /** TEST ONLY. */
    internal fun resetForTest() { night = null; meters.clear(); lastSaid = null; testPhone = null; testCpuMs = null; testNow = null; testWallMs = null }
}
