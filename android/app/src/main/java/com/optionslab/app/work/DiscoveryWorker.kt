package com.optionslab.app.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.optionslab.app.data.Market
import com.optionslab.app.data.VariantRunner
import java.util.concurrent.TimeUnit

/**
 * The strategy lab's worker (11 Oct): every hour, with the battery not low (a WorkManager constraint).
 *
 *  - After F&O's close, once the day's minutes are stored: the day's settle ([VariantRunner.settleIfDue]) - the shadow
 *    variants and the forward candidates over the day, as virtual trades. Never in market hours.
 *  - The nightly forge ([VariantRunner.forgeIfDue]) only when [allowed]: charging AND idle (screen off), or after 18:00 -
 *    never in market hours. WorkManager's constraints only AND, so the "or" is checked here, from the system's own battery
 *    and power state. The forge stops when WorkManager stops this worker.
 *
 * No network, no order, no setting. Not in IraGoldAlgo.
 */
class DiscoveryWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        if (!VariantRunner.enabled()) return Result.success()
        guarded { VariantRunner.settleIfDue() }
        val now = Market.now()
        if (allowed(charging(applicationContext), idle(applicationContext), now.hour * 60 + now.minute, Market.isTradingDay(now.toLocalDate())))
            try {
                // 22 Oct: the battery / heat budget asked at most every 5 s (it was asked on every twin of every backtest: thousands of
                // calls into Android a night, "excessive binder traffic"); WorkManager's stop is still seen at once.
                val budget = BackgroundBudget.allowEvery(com.optionslab.ira.BudgetRule.Job.FORGE)
                guarded { VariantRunner.forgeIfDue(now.toLocalDate()) { isStopped || !budget() } }
            } finally { runCatching { BackgroundBudget.done(com.optionslab.ira.BudgetRule.Job.FORGE) } }
        return Result.success()
    }

    companion object {
        private const val NAME = "strategylab.discovery"
        const val EVENING = 18 * 60
        const val MARKET_FROM = 9 * 60
        const val MARKET_UNTIL = 15 * 60 + 45

        /**
         * [block]'s value, or null when it failed - but a cancellation (WorkManager stopping the worker) goes on up, never
         * swallowed into "the next step and a success" (G7-14).
         */
        internal suspend fun <T> guarded(block: suspend () -> T): T? = try { block() } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) { null }

        /**
         * May the forge run now? Charging and idle, or from 18:00 - and never between 09:00 and 15:45 of a trading day,
         * whatever the battery says (nothing may slow the arms).
         */
        fun allowed(charging: Boolean, idle: Boolean, minute: Int, tradingDay: Boolean): Boolean {
            if (tradingDay && minute in MARKET_FROM until MARKET_UNTIL) return false
            return (charging && idle) || minute >= EVENING
        }

        fun charging(c: Context): Boolean = runCatching {
            c.getSystemService(android.os.BatteryManager::class.java)?.isCharging == true
        }.getOrDefault(false)

        /** Idle: the screen is off (not interactive) or the system says it is idling. */
        fun idle(c: Context): Boolean = runCatching {
            val pm = c.getSystemService(android.os.PowerManager::class.java) ?: return@runCatching false
            !pm.isInteractive || pm.isDeviceIdleMode
        }.getOrDefault(false)

        /** From the app's start: hourly, kept as it is (KEEP); the battery must not be low. */
        fun schedule(context: Context) {
            if (!VariantRunner.enabled()) return
            val req = PeriodicWorkRequestBuilder<DiscoveryWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            runCatching { WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req) }
        }
    }
}
