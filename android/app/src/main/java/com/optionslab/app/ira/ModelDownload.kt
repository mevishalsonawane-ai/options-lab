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
 * redirect is checked), an unmetered connection only, resumed where it stopped, then checked against its SHA-256 before
 * it can be used. A foreground service (data sync) with its progress and a Cancel button. JarvisAlgo only.
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
            job?.cancel()
            IraModel.publish { it.copy(status = IraModel.Status.ABSENT, message = "Download stopped; it resumes from here next time.") }
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
        val part = IraModel.part(c)
        val dest = IraModel.file(c)
        val cm = getSystemService(ConnectivityManager::class.java)
        if (cm.activeNetwork == null) throw IOException("No connection")
        if (cm.isActiveNetworkMetered) throw IOException("Connect to Wi-Fi first: the model is ${IraModel.SIZE / 1_000_000} MB")
        val have = part.length().coerceAtMost(IraModel.SIZE)
        if (c.noBackupFilesDir.usableSpace < IraModel.SIZE - have + 200_000_000L) throw IOException("Not enough free space on the phone (about 2.3 GB needed)")
        IraModel.publish { it.copy(status = IraModel.Status.DOWNLOADING, done = have, message = null) }

        var url = IraModel.URL
        var conn: HttpsURLConnection? = null
        for (hop in 0 until 6) {
            val u = URL(url)
            if (u.protocol != "https" || !IraModel.hostAllowed(u.host)) throw IOException("Refused a download host outside Hugging Face")
            val h = u.openConnection() as HttpsURLConnection
            h.instanceFollowRedirects = false
            h.connectTimeout = 20_000; h.readTimeout = 60_000
            h.setRequestProperty("User-Agent", "JarvisAlgo")
            if (have > 0) h.setRequestProperty("Range", "bytes=$have-")
            val code = h.responseCode
            if (code in 300..399) { url = URL(u, h.getHeaderField("Location") ?: throw IOException("Bad redirect")).toString(); h.disconnect(); continue }
            if (code == 416 && have == IraModel.SIZE) { h.disconnect(); conn = null; break }
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
                        out.write(buf, 0, n); done += n
                        if (done > IraModel.SIZE) throw IOException("The file is bigger than expected")
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
        if (part.length() != IraModel.SIZE) throw IOException("The download stopped at ${part.length() / 1_000_000} MB")
        IraModel.publish { it.copy(status = IraModel.Status.VERIFYING, done = IraModel.SIZE) }
        if (sha256(part) != IraModel.SHA256) {
            part.delete()
            throw IOException("The file did not match its fingerprint and was deleted")
        }
        dest.delete()
        if (!part.renameTo(dest)) throw IOException("Could not keep the file")
        IraModel.markVerified(c)
        IraModel.publish { IraModel.State(status = IraModel.Status.READY, done = IraModel.SIZE) }
        runCatching {
            Notifier.post(c, DONE_ID, Notifier.IRA, "Jarvis's model is ready", "Answers are now written on the phone by ${IraModel.NAME}; every number is checked.", tab = "almanac")
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
