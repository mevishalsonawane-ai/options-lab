package com.optionslab.app.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.optionslab.app.data.DhanSource
import com.optionslab.app.security.SecurePrefs

/**
 * The Dhan market-data download, in WorkManager. It runs only:
 *  - when Boss taps Download (on Wi-Fi; over mobile data only when he chooses it on the page; in the foreground with a quiet progress notice, so it is not cut at ten
 *    minutes), or
 *  - when he has turned on the automatic download: once a day at most, and only on an unmetered (Wi-Fi) network while
 *    the phone is charging and its battery is not low.
 * A stopped run resumes where it was (the store's files are its manifest). Nothing here trades; IraGoldAlgo never runs it.
 */
class DhanWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    companion object {
        private const val NOW = "dhan.download.now"
        private const val AUTO = "dhan.download.auto"
        private const val MANUAL = "manual"
        private const val NOTICE_ID = Notifier.ID_HARVEST + 40

        /**
         * Boss tapped Download. [wifiOnly]: wait for an unmetered (Wi-Fi) network - the page asks first when the phone is
         * on mobile data, and only Boss's explicit "use mobile data" sends it over any connection.
         */
        fun now(context: Context, wifiOnly: Boolean = true) {
            if (com.optionslab.app.BuildConfig.GOLD || !DhanSource.configured) return
            val req = OneTimeWorkRequestBuilder<DhanWorker>()
                .setInputData(workDataOf(MANUAL to true))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, req)
        }

        /** The automatic download on Wi-Fi while charging: on or off. */
        fun auto(context: Context, on: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!on || com.optionslab.app.BuildConfig.GOLD) { wm.cancelUniqueWork(AUTO); SecurePrefs.put(DhanSource.K_AUTO, false); return }
            val req = PeriodicWorkRequestBuilder<DhanWorker>(24, java.util.concurrent.TimeUnit.HOURS)
                .setInputData(workDataOf(MANUAL to false))
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresCharging(true)
                    .setRequiresBatteryNotLow(true)
                    .build())
                .build()
            wm.enqueueUniquePeriodicWork(AUTO, ExistingPeriodicWorkPolicy.UPDATE, req)
            SecurePrefs.put(DhanSource.K_AUTO, true)
        }

        /** Is the phone's current network metered (mobile data)? True when it cannot tell. */
        fun metered(context: Context): Boolean = runCatching {
            (context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).isActiveNetworkMetered
        }.getOrDefault(true)

        /** Stop a download that is running now (the automatic one stays scheduled). */
        fun stopNow(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(NOW) }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val n = Notifier.builder(applicationContext, Notifier.LIVE, "Downloading Dhan market data", "Candles and expired options, kept on this phone", "cabinet")
            .setOngoing(true).setOnlyAlertOnce(true).setAutoCancel(false).setSilent(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        return ForegroundInfo(NOTICE_ID, n, type)
    }

    override suspend fun doWork(): Result {
        if (com.optionslab.app.BuildConfig.GOLD || !DhanSource.configured) return Result.success()
        if (inputData.getBoolean(MANUAL, false)) {
            try {
                setForeground(getForegroundInfo())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // Not allowed now: run anyway; a run cut short resumes where it was.
            }
        }
        return try {
            DhanSource.run()
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: DhanSource.TokenRefused) {
            Result.failure()          // Boss renews the token; retrying would only be refused again
        } catch (_: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
