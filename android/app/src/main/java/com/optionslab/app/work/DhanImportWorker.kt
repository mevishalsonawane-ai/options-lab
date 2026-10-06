package com.optionslab.app.work

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.launch
import com.optionslab.app.data.DhanSource

/**
 * Importing a Dhan data pack (zip parts Boss picked with the system file picker) into the Dhan store, in WorkManager so
 * a long import is not cut when he leaves the page: in the foreground with a quiet progress notice, like the download
 * ([DhanWorker]). No network, no token: it only reads the picked files. Stopping it keeps what was already stored (each
 * file whole). IraGoldAlgo never runs it.
 */
class DhanImportWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    companion object {
        private const val NAME = "dhan.import"
        private const val URIS = "uris"
        private const val NOTICE_ID = Notifier.ID_HARVEST + 41
        /** WorkManager's input is small (10 KB): a pack is a few parts, so this is far above any real one. */
        const val MAX_PARTS = 40

        /** Start importing [uris] (keeping read access to them for the run). False when there is nothing to do. */
        fun start(context: Context, uris: List<Uri>): Boolean {
            if (com.optionslab.app.BuildConfig.GOLD || uris.isEmpty()) return false
            val picked = uris.distinct().take(MAX_PARTS)
            for (u in picked) runCatching { context.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val req = OneTimeWorkRequestBuilder<DhanImportWorker>()
                .setInputData(workDataOf(URIS to picked.map { it.toString() }.toTypedArray()))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, req)
            return true
        }

        /** Stop an import that is running. */
        fun stop(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(NAME) }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val pr = DhanSource.importing.value
        val text = if (pr.running) "Part ${pr.part} of ${pr.parts} · ${pr.files} files · ${"%.0f".format(java.util.Locale.ENGLISH, pr.bytes / 1e6)} MB"
            else "Checking and storing the pack on this phone"
        val n = Notifier.builder(applicationContext, Notifier.LIVE, "Importing Dhan market data", text, "cabinet")
            .setOngoing(true).setOnlyAlertOnce(true).setAutoCancel(false).setSilent(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        return ForegroundInfo(NOTICE_ID, n, type)
    }

    override suspend fun doWork(): Result {
        if (com.optionslab.app.BuildConfig.GOLD) return Result.success()
        val uris = inputData.getStringArray(URIS)?.mapNotNull { runCatching { Uri.parse(it) }.getOrNull() } ?: return Result.success()
        try {
            setForeground(getForegroundInfo())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            // Not allowed now: run anyway (the page shows the progress).
        }
        try {
            kotlinx.coroutines.coroutineScope {
                // The notice follows the progress (parts, files, MB) every few seconds.
                val tick = launch {
                    while (true) {
                        kotlinx.coroutines.delay(3_000)
                        runCatching { setForeground(getForegroundInfo()) }
                    }
                }
                try { DhanSource.importPack(uris) } finally { tick.cancel() }
            }
        } finally {
            for (u in uris) runCatching { applicationContext.contentResolver.releasePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        // A refused or failed import is not retried: its summary is on the page, and Boss picks the files again.
        return Result.success()
    }
}
