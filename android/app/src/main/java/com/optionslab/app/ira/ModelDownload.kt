package com.optionslab.app.ira

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.optionslab.app.R
import com.optionslab.app.work.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

/**
 * Downloads [IraModel]'s one file, started only by the owner's tap: HTTPS to Hugging Face and its CDN only (every
 * redirect is checked), on Wi-Fi or mobile data (the owner's choice), resumed where it stopped, then checked against its SHA-256 before
 * it can be used. A foreground service (data sync) with its progress and a Cancel button. Jarvis only.
 */
class ModelDownload : Service() {
    companion object {
        const val ACTION_CANCEL = "com.optionslab.app.ira.ModelDownload.CANCEL"
        private const val ID = 1051
        private const val DONE_ID = 1052

        fun start(c: Context) {
            if (!com.optionslab.app.BuildConfig.JARVIS) return
            runCatching { ContextCompat.startForegroundService(c, Intent(c, ModelDownload::class.java)) }
                .onFailure { IraModel.publish { s -> s.copy(status = IraModel.Status.FAILED, message = "Android did not let the download start; try again.") } }
        }

        fun cancel(c: Context) { runCatching { c.startService(Intent(c, ModelDownload::class.java).setAction(ACTION_CANCEL)) } }

        /** The file's SHA-256, hex. */
        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { inp -> val buf = ByteArray(1 shl 20); while (true) { val n = inp.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            val running = job?.isActive == true
            job?.cancel()
            if (running) IraModel.publish { it.copy(status = IraModel.Status.ABSENT, message = "Download stopped; it resumes from here next time.") }
            stopSelf(); return START_NOT_STICKY
        }
        if (job?.isActive == true) return START_NOT_STICKY
        try {
            ServiceCompat.startForeground(this, ID, progress(0),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        } catch (e: Exception) {
            IraModel.publish { it.copy(status = IraModel.Status.FAILED, message = "Android did not let the download run; open Jarvis and try again.") }
            stopSelf(); return START_NOT_STICKY
        }
        job = scope.launch {
            val why = runCatching { fetch() }.exceptionOrNull()
            if (why != null && isActive) IraModel.publish { it.copy(status = IraModel.Status.FAILED,
                message = (why.message ?: "The download failed") + ". Tap Download to resume.") }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun fetch() {
        val c = applicationContext
        // The model chosen when the download began (choosing another cancels this download first).
        val spec = IraModel.choice
        val part = java.io.File(c.noBackupFilesDir, "${spec.file}.part")
        val dest = java.io.File(c.noBackupFilesDir, spec.file)
        // Already on the phone (its check was not remembered): check it again, download nothing.
        if (dest.length() == spec.size && IraModel.recheck(c, spec)) return
        val cm = getSystemService(ConnectivityManager::class.java)
        if (cm.activeNetwork == null) throw IOException("No connection")
        // A part file larger than the model (an earlier overrun) can never complete: start again.
        if (part.length() > spec.size) part.delete()
        val have = part.length()
        if (c.noBackupFilesDir.usableSpace < spec.size - have + 200_000_000L) throw IOException("Not enough free space on the phone (about ${(spec.size + 200_000_000L) / 100_000_000 / 10.0} GB needed)")
        IraModel.publish { it.copy(status = IraModel.Status.DOWNLOADING, done = have, message = null) }

        var url = spec.url
        var conn: HttpsURLConnection? = null
        for (hop in 0 until 6) {
            val u = URL(url)
            if (u.protocol != "https" || !IraModel.hostAllowed(u.host)) throw IOException("Refused a download host outside Hugging Face")
            val h = u.openConnection() as HttpsURLConnection
            h.instanceFollowRedirects = false
            h.connectTimeout = 20_000; h.readTimeout = 60_000
            h.setRequestProperty("User-Agent", "IraAlgo")
            if (have > 0) h.setRequestProperty("Range", "bytes=$have-")
            val code = h.responseCode
            if (code in 300..399) { url = URL(u, h.getHeaderField("Location") ?: throw IOException("Bad redirect")).toString(); h.disconnect(); continue }
            if (code == 416 && have == spec.size) { h.disconnect(); conn = null; break }
            if (code != 200 && code != 206) { h.disconnect(); throw IOException("The server answered $code") }
            conn = h; break
        }
        var done = have
        conn?.let { h ->
            val append = h.responseCode == 206
            if (!append) done = 0
            FileOutputStream(part, append).use { out ->
                h.inputStream.use { inp ->
                    val buf = ByteArray(1 shl 20)
                    var lastNote = 0L
                    while (true) {
                        if (job?.isCancelled == true) throw IOException("Stopped")
                        val n = inp.read(buf); if (n < 0) break
                        if (done + n > spec.size) { out.close(); part.delete(); throw IOException("The file is bigger than expected; it will start again") }
                        out.write(buf, 0, n); done += n
                        val now = System.currentTimeMillis()
                        if (now - lastNote > 1_000) {
                            lastNote = now
                            IraModel.publish { it.copy(done = done) }
                            runCatching { NotificationManagerCompat.from(c).notify(ID, progress(done)) }
                        }
                    }
                }
            }
            h.disconnect()
        }
        if (part.length() != spec.size) throw IOException("The download stopped at ${part.length() / 1_000_000} MB")
        IraModel.publish { it.copy(status = IraModel.Status.VERIFYING, done = spec.size) }
        if (sha256(part) != spec.sha256) {
            part.delete()
            throw IOException("The file did not match its fingerprint and was deleted")
        }
        dest.delete()
        if (!part.renameTo(dest)) throw IOException("Could not keep the file")
        IraModel.markVerified(c, spec)                       // checked against its own fingerprint, whichever is chosen now
        if (IraModel.choice != spec) return                  // another model was chosen meanwhile: this one is kept for later
        IraModel.dropOthers(c)
        IraModel.publish { IraModel.State(status = IraModel.Status.READY, done = spec.size) }
        runCatching {
            Notifier.post(c, DONE_ID, Notifier.IRA, "Jarvis's model is ready", "Answers are now written on the phone by ${IraModel.NAME}; every number is checked.", tab = "almanac", setting = "jarvis.model")
        }
    }

    private fun progress(done: Long) = NotificationCompat.Builder(this, Notifier.VOICE)
        .setSmallIcon(R.drawable.ic_notification_art)
        .setContentTitle("Downloading Jarvis's model")
        .setContentText("${done / 1_000_000} of ${IraModel.SIZE / 1_000_000} MB · ${IraModel.NAME} from Hugging Face")
        .setProgress(1000, (done * 1000 / IraModel.SIZE).toInt(), false)
        .setOngoing(true).setOnlyAlertOnce(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentIntent(Notifier.openApp(this, "almanac"))
        .addAction(0, "Cancel", PendingIntent.getService(this, 2, Intent(this, ModelDownload::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        .build()

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
