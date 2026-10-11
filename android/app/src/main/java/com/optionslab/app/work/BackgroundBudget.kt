package com.optionslab.app.work

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.optionslab.ira.BudgetRule
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicLong

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
    @Volatile private var night: LocalDate? = null
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

    private fun cpuMs(): Long = testCpuMs?.invoke() ?: runCatching { android.os.Process.getElapsedCpuTime() }.getOrDefault(0L)

    /** Per job: the CPU clock and the wall clock at its last [allow] (a run is the calls less than [RUN_GAP_MS] apart). */
    private val last = java.util.concurrent.ConcurrentHashMap<BudgetRule.Job, Pair<Long, Long>>()
    private val spentMs = AtomicLong(0)
    private const val RUN_GAP_MS = 120_000L

    /**
     * The background CPU spent this night so far: within a job's run (its [allow] calls between steps), the process's CPU
     * time from one call to the next is counted; the gaps between runs are not (the rest of the app is not the jobs').
     */
    private fun spent(job: BudgetRule.Job): Long {
        roll()
        val cpu = cpuMs()
        val wall = wallMs()
        val prev = last.put(job, cpu to wall)
        if (prev != null && wall - prev.second in 0..RUN_GAP_MS && cpu >= prev.first) spentMs.addAndGet(cpu - prev.first)
        return spentMs.get()
    }

    private fun wallMs(): Long = testWallMs?.invoke() ?: System.currentTimeMillis()

    /** A new night starts afresh. */
    private fun roll() {
        val now = testNow ?: runCatching { com.optionslab.app.data.Market.now().toLocalDateTime() }.getOrElse { LocalDateTime.now() }
        val n = BudgetRule.nightOf(now)
        if (night != n) { night = n; spentMs.set(0); last.clear(); lastSaid = null }
    }

    /**
     * [job]'s run has ended (call it in a finally): the CPU since its last [allow] is counted however long that was, so a
     * run longer than [RUN_GAP_MS] between two asks (or one that asks only at its start) is not free.
     */
    fun done(job: BudgetRule.Job) {
        roll()
        val prev = last.remove(job) ?: return
        val cpu = cpuMs()
        if (cpu >= prev.first) spentMs.addAndGet(cpu - prev.first)
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

    /** TEST ONLY. */
    internal fun resetForTest() { night = null; spentMs.set(0); last.clear(); lastSaid = null; testPhone = null; testCpuMs = null; testNow = null; testWallMs = null }
}
