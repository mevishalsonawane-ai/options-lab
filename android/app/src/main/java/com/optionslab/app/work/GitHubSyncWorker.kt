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
 * GitHub sync's worker (TODO G1/G2, 13 Oct; [GitHubSync] does the work, [CloudSync.plan] decides what a run does). Four kinds:
 *
 *  - EVENING (every hour, WorkManager constraints: unmetered Wi-Fi AND charging; with that evening's diagnostics, G3): once a day from the evening gate
 *    ([GitHubSync.eveningFrom]; G4, 13 Oct, Boss: "after the commodities close"): after MCX's evening close (23:30, or 23:55
 *    in US winter time, from the app's MCX calendar), 15:45 on a day without an MCX session, 23:55 when the calendar cannot
 *    be read. The waiting recorded days and the phone backup. An evening missed (the phone was not charging) still runs the
 *    next morning until 08:00, once ([CloudSync.eveningDueDay]); a day missed waits for the next evening.
 *  - BACKUP (one-off, after a strategy or Pine change): the phone backup only, under the same gates as EVENING (13 Oct
 *    review): unmetered Wi-Fi AND charging, and not before the evening gate (it waits until then, [CloudSync.backupDelayMin]);
 *    a job held past midnight still runs until 08:00 ([CloudSync.autoSendAllowed]).
 *  - NOW ("Upload now", only from Boss's tap on the card; unchanged by G4): both, at any hour and without charging, still
 *    only on unmetered Wi-Fi; a day's recordings never between 09:00 and 15:45 of a trading day. The only kind that tries
 *    after GitHub refused the token.
 *  - CHECK (twice a day, no constraint, no network): the problems and the token's renewal reminder.
 *
 * A failure is retried with exponential backoff (from 15 minutes), [MAX_TRIES] tries a run in all ([CloudSync.retry]), never
 * after GitHub refused the token. Never on the main thread, never on the order or tick path. Not in IraGoldAlgo.
 */
class GitHubSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        if (!GitHubSync.enabled()) return Result.success()
        val kind = inputData.getString(KIND) ?: EVENING
        // One clock read for the minute, the day and the gate (G4, 13 Oct): the MCX calendar is read here, on the worker's thread.
        val now = com.optionslab.app.data.Market.now()
        val from = GitHubSync.eveningFrom(now.toLocalDate())
        val evening = if (kind == EVENING) GitHubSync.eveningDueDay(now, from) else null
        val plan = CloudSync.plan(kind, now.hour * 60 + now.minute, evening != null, GitHubSync.ready(), GitHubSync.authBlocked(),
            eveningFrom = from)
        if (plan == null) {
            runCatching { GitHubSync.check() }
            return Result.success()
        }
        // G3: the evening run's day's diagnostics (Upload now: today's so far, replaced by the evening run).
        val diagDay = CloudSync.diagDay(kind, evening, now.hour * 60 + now.minute, now.toLocalDate(), from)
        val ok = try {
            GitHubSync.run(withBackup = plan.backup, withDays = plan.days, manual = kind == NOW, eveningFrom = from, diagDay = diagDay) { !isStopped }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { false }
        // Kept as the evening the run was for, even when it ended after midnight (G4).
        if (ok && evening != null) runCatching { GitHubSync.eveningDone(evening) }
        return if (CloudSync.retry(ok, runAttemptCount, GitHubSync.authBlocked())) Result.retry() else Result.success()
    }

    companion object {
        const val KIND = "kind"
        const val EVENING = CloudSync.KIND_EVENING
        const val BACKUP = CloudSync.KIND_BACKUP
        const val NOW = CloudSync.KIND_NOW
        const val CHECK = CloudSync.KIND_CHECK
        const val MAX_TRIES = CloudSync.MAX_TRIES
        private const val N_EVENING = "github.sync.evening"
        private const val N_BACKUP = "github.sync.backup"
        private const val N_NOW = "github.sync.now"
        private const val N_CHECK = "github.sync.check"

        internal fun data(kind: String): Data = Data.Builder().putString(KIND, kind).build()

        /** Unmetered Wi-Fi always; charging for every automatic kind (only Boss's "Upload now" goes without). */
        internal fun wifi(charging: Boolean): Constraints =
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

        /**
         * A strategy or Pine script changed: the backup [CloudSync.DEBOUNCE_MIN] minutes after the LAST change (REPLACE), never
         * before today's evening gate ([GitHubSync.eveningFrom]: after MCX's close, 15:45 without MCX; G4), on unmetered Wi-Fi
         * while charging. Reads the MCX calendar: from [GitHubSync.changed]'s background coroutine, never the main thread.
         */
        fun backupSoon(context: Context) {
            val now = com.optionslab.app.data.Market.now()
            val delay = CloudSync.backupDelayMin(now.hour * 60 + now.minute, GitHubSync.eveningFrom(now.toLocalDate()))
            val req = OneTimeWorkRequestBuilder<GitHubSyncWorker>()
                .setInitialDelay(delay, TimeUnit.MINUTES)
                .setConstraints(wifi(charging = true))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .setInputData(data(BACKUP)).build()
            WorkManager.getInstance(context).enqueueUniqueWork(N_BACKUP, ExistingWorkPolicy.REPLACE, req)
        }

        /** "Upload now" (Boss's tap, the card only): as soon as the phone is on unmetered Wi-Fi, charging or not. */
        fun now(context: Context) {
            val req = OneTimeWorkRequestBuilder<GitHubSyncWorker>()
                .setConstraints(wifi(charging = false))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .setInputData(data(NOW)).build()
            WorkManager.getInstance(context).enqueueUniqueWork(N_NOW, ExistingWorkPolicy.KEEP, req)
        }
    }
}
