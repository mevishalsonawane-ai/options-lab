package com.optionslab.app.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.optionslab.app.data.NeuroGraphJob

/**
 * Builds the NeuroGraph from the Dhan data in WorkManager, battery-friendly:
 *  - after a download or an import ([after]): an incremental update (only the new days) once the battery is not low; if
 *    a full rebuild is needed it is left to a second request that runs only while the phone is charging;
 *  - when Boss taps Rebuild graph ([now]): a full rebuild at once, in the foreground with a quiet notice.
 * A stopped build resumes where it was (the builder's state is saved as it goes). No network; nothing here trades.
 * IraGoldAlgo never runs it.
 */
class NeuroWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    companion object {
        private const val AFTER = "neuro.after"
        private const val FULL = "neuro.full"
        private const val NOW = "neuro.now"
        private const val K_FULL = "full"
        private const val K_MANUAL = "manual"
        private const val NOTICE_ID = Notifier.ID_HARVEST + 42

        /** After new data: incremental now (battery not low), queued behind a build already running. */
        fun after(context: Context) {
            if (com.optionslab.app.BuildConfig.GOLD) return
            val req = OneTimeWorkRequestBuilder<NeuroWorker>()
                .setInputData(workDataOf(K_FULL to false, K_MANUAL to false))
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(AFTER, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }

        /** A full rebuild that waits for the charger (and a battery that is not low). */
        fun whenCharging(context: Context) {
            if (com.optionslab.app.BuildConfig.GOLD) return
            val req = OneTimeWorkRequestBuilder<NeuroWorker>()
                .setInputData(workDataOf(K_FULL to true, K_MANUAL to false))
                .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(FULL, ExistingWorkPolicy.KEEP, req)
        }

        /** Boss tapped Rebuild graph: a full rebuild now. */
        fun now(context: Context) {
            if (com.optionslab.app.BuildConfig.GOLD) return
            val req = OneTimeWorkRequestBuilder<NeuroWorker>()
                .setInputData(workDataOf(K_FULL to true, K_MANUAL to true))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, req)
        }

        fun stop(context: Context) {
            val wm = WorkManager.getInstance(context)
            wm.cancelUniqueWork(NOW); wm.cancelUniqueWork(AFTER); wm.cancelUniqueWork(FULL)
        }

        private fun charging(context: Context): Boolean =
            runCatching { (context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager).isCharging }.getOrDefault(false)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val n = Notifier.builder(applicationContext, Notifier.LIVE, "Learning from the Dhan data", "Building the NeuroGraph on this phone", "cabinet")
            .setOngoing(true).setOnlyAlertOnce(true).setAutoCancel(false).setSilent(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        return ForegroundInfo(NOTICE_ID, n, type)
    }

    override suspend fun doWork(): Result {
        if (com.optionslab.app.BuildConfig.GOLD) return Result.success()
        val full = inputData.getBoolean(K_FULL, false)
        val manual = inputData.getBoolean(K_MANUAL, false)
        if (manual || full) {
            try {
                setForeground(getForegroundInfo())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // Not allowed now: build anyway; a build cut short resumes where it was.
            }
        }
        return try {
            val charging = charging(applicationContext)
            val fullNeeded = !full && runCatching { NeuroGraphJob.needsFull() }.getOrNull() != null
            // A needed full rebuild runs now only on the charger; otherwise the new days now, the full rebuild on the charger.
            NeuroGraphJob.run(full = full, allowFull = full || charging)
            if (fullNeeded && !charging) whenCharging(applicationContext)
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }
}
