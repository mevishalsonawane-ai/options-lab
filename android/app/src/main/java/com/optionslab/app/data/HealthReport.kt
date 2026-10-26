package com.optionslab.app.data

import android.content.Context
import android.content.Intent
import com.optionslab.ira.HealthBook
import com.optionslab.ira.HealthExport
import java.io.File
import java.time.Instant
import java.time.LocalDate

/**
 * The exportable health report (S17; the text and its redaction are ira-core's [HealthExport]): the last 14 days of stalls
 * with their places ([AppHealth]'s book), the ANRs and crashes with their stack traces - Android's exit records' traces on
 * Android 11 and later (ApplicationExitInfo.getTraceInputStream), and the app's own sealed crash file (frames only) - and
 * the order path's timings ([OrderTiming]). Every line is redacted before it is written: tokens, keys, the PIN, account and
 * client ids, the account holder's name, e-mail addresses and phone numbers never leave. Written to the cache's exports/
 * folder (the exports FileProvider's only folder) as a .txt inside a .zip and handed to Android's share sheet - only when
 * Boss taps "Share health report" (Settings → Diagnostics). Reads only.
 */
object HealthReport {
    private const val SUB = "exports/health"
    private val HHMM = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
    private fun hhmm(ms: Long) = Instant.ofEpochMilli(ms).atZone(com.optionslab.engine.IST).format(HHMM)

    /** Android's exit traces (11+): an ANR's thread dump, a native crash's tombstone text; at most [n], newest first. */
    private fun exitTraces(ctx: Context, n: Int = 8): List<HealthExport.Trace> {
        if (android.os.Build.VERSION.SDK_INT < 30) return emptyList()
        return runCatching {
            val am = ctx.getSystemService(android.app.ActivityManager::class.java) ?: return@runCatching emptyList()
            am.getHistoricalProcessExitReasons(ctx.packageName, 0, 16).mapNotNull { x ->
                val kind = when (x.reason) {
                    android.app.ApplicationExitInfo.REASON_ANR -> HealthBook.ExitKind.ANR
                    android.app.ApplicationExitInfo.REASON_CRASH -> HealthBook.ExitKind.CRASH
                    android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> HealthBook.ExitKind.NATIVE_CRASH
                    else -> null
                } ?: return@mapNotNull null
                // At most 256 KB of a trace is read (an ANR dump holds every thread); the report keeps its top lines.
                val text = runCatching { x.traceInputStream?.use { s -> String(s.readNBytesCompat(256 * 1024), Charsets.UTF_8) } }.getOrNull()
                    ?: x.description?.let { "(no trace kept by Android) $it" } ?: return@mapNotNull null
                HealthExport.Trace(x.timestamp, kind, text)
            }.take(n)
        }.getOrDefault(emptyList())
    }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < max) { val r = read(buf, 0, minOf(buf.size, max - out.size())); if (r < 0) break; out.write(buf, 0, r) }
        return out.toByteArray()
    }

    /** The app's own sealed crash file (class names and frames, no messages), as a trace. */
    private fun crashFile(ctx: Context): HealthExport.Trace? = runCatching {
        val f = File(ctx.filesDir, com.optionslab.app.IraAlgoApp.CRASH_FILE)
        if (!f.isFile) return@runCatching null
        val text = com.optionslab.app.security.Vault.readFile(f)?.toString(Charsets.UTF_8) ?: return@runCatching null
        HealthExport.Trace(f.lastModified(), HealthBook.ExitKind.CRASH, text)
    }.getOrNull()

    /** Values the app knows are secret, removed wherever they appear (the key's and the account's own words). */
    private fun known(): List<String> = listOfNotNull(
        runCatching { Broker.apiKey }.getOrNull(), runCatching { Broker.userId }.getOrNull(), runCatching { Broker.userName }.getOrNull())

    /** The report's text, redacted (a worker thread). */
    fun text(ctx: Context): String {
        AppHealth.ensureLoaded()
        val traces = exitTraces(ctx) + listOfNotNull(crashFile(ctx))
        val order = runCatching { OrderTiming.card().lines + OrderTiming.card().warnings }.getOrDefault(emptyList()) +
            // Round 4: slippage per fill and the Zerodha sync check (no rupee totals beyond a unit's median and p90).
            listOf(runCatching { FillSlippage.diagLine() }.getOrElse { "Slippage: could not read" },
                runCatching { ZerodhaSync.line() }.getOrElse { "Zerodha sync: could not read" })
        val today = runCatching { Market.today() }.getOrElse { LocalDate.now(com.optionslab.engine.IST) }
        val device = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}"
        return HealthExport.build(AppHealth.book(), traces, order, today, com.optionslab.app.BuildConfig.COMMIT, device, ::hhmm, known())
    }

    /** 26 Oct: the cache folder the last report was written under (the erase finds it without a context). */
    @Volatile private var cacheRoot: File? = null

    /** 26 Oct: the reports' folder (the cache's exports/health/); null before anything knows the app's folders. */
    private fun folder(): File? = (cacheRoot ?: com.optionslab.app.ira.IraHub.appContext()?.cacheDir)?.let { File(it, SUB) }

    /**
     * 26 Oct: "Erase everything" ([PersonalStores]): the reports written for sharing (redacted, but still the phone's own
     * diagnostics and order timings) deleted. Nothing is kept in memory; never reads the vault.
     */
    fun wipe() { folder()?.let { runCatching { it.deleteRecursively() } } }

    /** 26 Oct: the reports' folder. */
    internal fun filesForErase(): List<File> = listOfNotNull(folder())

    /** The report zipped in the cache's exports/health/ (older ones removed). A worker thread. */
    fun write(ctx: Context): File {
        val text = text(ctx)
        val today = runCatching { Market.today() }.getOrElse { LocalDate.now(com.optionslab.engine.IST) }
        cacheRoot = ctx.cacheDir
        val dir = File(ctx.cacheDir, SUB).apply { mkdirs() }
        dir.listFiles()?.forEach { runCatching { it.delete() } }
        val name = HealthExport.fileName(today)
        val zip = File(dir, name.removeSuffix(".txt") + ".zip")
        java.util.zip.ZipOutputStream(zip.outputStream().buffered()).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry(name)); z.write(text.toByteArray(Charsets.UTF_8)); z.closeEntry()
        }
        runCatching { Diag.record("health", "health report written (${text.length} characters, redacted)") }
        return zip
    }

    /** Writes the report and opens the share sheet on it (Boss's tap; a worker thread, the sheet started on the main thread). */
    suspend fun share(ctx: Context): String {
        val zip = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { write(ctx) }
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".exports", zip)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "IraAlgo health report")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Share the health report").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { ctx.startActivity(chooser) }
        return "Health report ready to share: the last ${HealthExport.DAYS} days, secrets removed."
    }
}
