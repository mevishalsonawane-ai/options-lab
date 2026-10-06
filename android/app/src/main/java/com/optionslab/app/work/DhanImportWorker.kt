package com.optionslab.app.work

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.launch
import com.optionslab.app.data.DhanSource

/**
 * Importing a Dhan data pack (zip parts Boss picked with the system file picker) into the Dhan store, in WorkManager so
 * a long import is not cut when he leaves the page: in the foreground with a quiet progress notice, like the download
 * ([DhanWorker]). No token: a picked pack uses no network at all; "Import from GitHub" ([startGithub]) downloads the public
 * pack's parts over HTTPS from raw.githubusercontent.com alone (no credential), then imports them the same way. Stopping it
 * keeps what was already stored (each file whole). IraGoldAlgo never runs it.
 */
class DhanImportWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    /** What [start] did: began the import, found one already queued or running (nothing taken), or had nothing to do. */
    enum class Started { STARTED, BUSY, NOTHING }

    companion object {
        private const val NAME = "dhan.import"
        private const val URIS = "uris"
        /** The sha256 of the GitHub pack's index Boss confirmed: present only for an import from GitHub. */
        private const val GITHUB = "github"
        private const val NOTICE_ID = Notifier.ID_HARVEST + 41
        /** WorkManager's input is small (10 KB): a pack is a few parts, so this is far above any real one. */
        const val MAX_PARTS = 40

        /** Is an import queued or running (WorkManager's own record)? Blocking: call off the main thread. */
        private fun busy(context: Context): Boolean = runCatching {
            WorkManager.getInstance(context).getWorkInfosForUniqueWork(NAME).get().any { !it.state.isFinished }
        }.getOrDefault(false) || DhanSource.importing.value.running

        /** Release every read grant an earlier import kept (one that was stopped before it ran cannot release its own). */
        private fun releaseAll(context: Context) {
            val cr = context.contentResolver
            for (p in runCatching { cr.persistedUriPermissions }.getOrDefault(emptyList())) if (p.isReadPermission)
                runCatching { cr.releasePersistableUriPermission(p.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }

        /**
         * Start importing [uris] (keeping read access to them for the run). BUSY when an import is already queued or
         * running: the new pick is NOT taken (no grant is kept for it) and Boss is told to wait. Blocking: call off the
         * main thread.
         */
        fun start(context: Context, uris: List<Uri>): Started {
            if (com.optionslab.app.BuildConfig.GOLD || uris.isEmpty()) return Started.NOTHING
            if (busy(context)) return Started.BUSY
            val picked = uris.distinct().take(MAX_PARTS)
            // Only this import's files keep a grant: whatever an earlier, stopped one left is released first.
            releaseAll(context)
            for (u in picked) runCatching { context.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val req = OneTimeWorkRequestBuilder<DhanImportWorker>()
                .setInputData(workDataOf(URIS to picked.map { it.toString() }.toTypedArray()))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, req)
            return Started.STARTED
        }

        /**
         * Download the data pack from GitHub and import it - the pack whose index Boss confirmed ([indexSha]; a changed
         * index is not downloaded). [wifiOnly]: wait for an unmetered network, as the Download button does. Shares the
         * import's unique work, so it never runs beside a picked import. Blocking: call off the main thread.
         */
        fun startGithub(context: Context, indexSha: String, wifiOnly: Boolean): Started {
            if (com.optionslab.app.BuildConfig.GOLD || indexSha.isEmpty()) return Started.NOTHING
            if (busy(context)) return Started.BUSY
            val req = OneTimeWorkRequestBuilder<DhanImportWorker>()
                .setInputData(workDataOf(GITHUB to indexSha))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, req)
            return Started.STARTED
        }

        /** Stop an import that is running (or queued), and let go of the picked files. */
        fun stop(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
            releaseAll(context)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val pr = DhanSource.importing.value
        val text = when {
            pr.running && pr.downloading -> "From GitHub: part ${pr.part} of ${pr.parts} · ${com.optionslab.ira.dhan.Files.sizeText(pr.read)} of ${com.optionslab.ira.dhan.Files.sizeText(pr.size)}"
            pr.running -> "Part ${pr.part} of ${pr.parts} · ${pr.files} files · ${com.optionslab.ira.dhan.Files.sizeText(pr.bytes)}"
            else -> "Checking and storing the pack on this phone"
        }
        val n = Notifier.builder(applicationContext, Notifier.LIVE, "Importing Dhan market data", text, "cabinet")
            .setOngoing(true).setOnlyAlertOnce(true).setAutoCancel(false).setSilent(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        return ForegroundInfo(NOTICE_ID, n, type)
    }

    override suspend fun doWork(): Result {
        if (com.optionslab.app.BuildConfig.GOLD) return Result.success()
        val github = inputData.getString(GITHUB)
        val uris = inputData.getStringArray(URIS)?.mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }
        if (github == null && uris == null) return Result.success()
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
                try {
                    // From GitHub: public data over HTTPS, no token; a stopped download resumes from the cached parts.
                    if (github != null) DhanSource.importFromGithub(github) else DhanSource.importPack(uris.orEmpty())
                } finally { tick.cancel() }
            }
        } finally {
            for (u in uris.orEmpty()) runCatching { applicationContext.contentResolver.releasePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        // A refused or failed import is not retried: its summary is on the page, and Boss picks the files again.
        return Result.success()
    }
}
