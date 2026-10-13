package com.optionslab.app.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.optionslab.app.data.GitHubSync
import com.optionslab.ira.CloudSync
import java.util.concurrent.TimeUnit

/**
 * GitHub sync's worker (TODO G1/G2, 13 Oct; [GitHubSync] does the work, [CloudSync] has the rules). Four kinds:
 *
 *  - EVENING (every hour, WorkManager constraints: unmetered Wi-Fi AND charging): from 15:45, once a day, the waiting recorded
 *    days and the phone backup. A day missed waits for the next evening.
 *  - BACKUP (one-off, a few minutes after the last strategy or Pine change, unmetered Wi-Fi): the phone backup only.
 *  - NOW ("Upload now" on the card, unmetered Wi-Fi): both, but a day's recordings never between 09:00 and 15:45 of a trading day.
 *  - CHECK (twice a day, no constraint, no network): the problems and the token's renewal reminder.
 *
 * A failure is retried with exponential backoff (from 15 minutes), at most [MAX_TRIES] times a run. Never on the main
 * thread, never on the order or tick path. Not in IraGoldAlgo.
 */
class GitHubSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        if (!GitHubSync.enabled()) return Result.success()
        val kind = inputData.getString(KIND) ?: EVENING
        if (kind == CHECK || !GitHubSync.ready()) {
            runCatching { GitHubSync.check() }
            return Result.success()
        }
        if (kind == EVENING && !GitHubSync.eveningDue()) return Result.success()
        val ok = try {
            GitHubSync.run(withBackup = true, withDays = kind != BACKUP) { !isStopped }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { false }
        if (ok && kind == EVENING) runCatching { GitHubSync.eveningDone() }
        return if (ok || runAttemptCount >= MAX_TRIES) Result.success() else Result.retry()
    }

    companion object {
        const val KIND = "kind"
        const val EVENING = "evening"
        const val BACKUP = "backup"
        const val NOW = "now"
        const val CHECK = "check"
        const val MAX_TRIES = 4
        private const val N_EVENING = "github.sync.evening"
        private const val N_BACKUP = "github.sync.backup"
        private const val N_NOW = "github.sync.now"
        private const val N_CHECK = "github.sync.check"

        private fun data(kind: String): Data = Data.Builder().putString(KIND, kind).build()

        private fun wifi(charging: Boolean = false): Constraints =
            Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresCharging(charging).build()

        /** From the app's start (a background thread): the evening run and the twice-daily check, kept as they are (KEEP). */
        fun schedule(context: Context) {
            if (!GitHubSync.enabled()) return
            val wm = WorkManager.getInstance(context)
            val evening = PeriodicWorkRequestBuilder<GitHubSyncWorker>(1, TimeUnit.HOURS)
                .setConstraints(wifi(charging = true))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .setInputData(data(EVENING)).build()
            wm.enqueueUniquePeriodicWork(N_EVENING, ExistingPeriodicWorkPolicy.KEEP, evening)
            val check = PeriodicWorkRequestBuilder<GitHubSyncWorker>(12, TimeUnit.HOURS).setInputData(data(CHECK)).build()
            wm.enqueueUniquePeriodicWork(N_CHECK, ExistingPeriodicWorkPolicy.KEEP, check)
        }

        /** A strategy or Pine script changed: the backup [CloudSync.DEBOUNCE_MIN] minutes after the LAST change (REPLACE). */
        fun backupSoon(context: Context) {
            val req = OneTimeWorkRequestBuilder<GitHubSyncWorker>()
                .setInitialDelay(CloudSync.DEBOUNCE_MIN, TimeUnit.MINUTES)
                .setConstraints(wifi())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .setInputData(data(BACKUP)).build()
            WorkManager.getInstance(context).enqueueUniqueWork(N_BACKUP, ExistingWorkPolicy.REPLACE, req)
        }

        /** "Upload now" (Boss's tap): as soon as the phone is on unmetered Wi-Fi. */
        fun now(context: Context) {
            val req = OneTimeWorkRequestBuilder<GitHubSyncWorker>()
                .setConstraints(wifi())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .setInputData(data(NOW)).build()
            WorkManager.getInstance(context).enqueueUniqueWork(N_NOW, ExistingWorkPolicy.KEEP, req)
        }
    }
}
