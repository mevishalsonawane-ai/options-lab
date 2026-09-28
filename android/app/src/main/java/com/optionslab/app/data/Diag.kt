package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.Vault
import org.json.JSONArray
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * The owner's diagnostics diary: what the app said and did (every banner it showed, the self-test steps, the
 * relay checks), kept encrypted on the phone, never sent anywhere. "Copy diagnostics" puts a redacted report on
 * the clipboard for the owner to paste to whoever helps them; nothing leaves the phone any other way.
 *
 * No secret ever reaches it: callers hand it only words already shown on screen, and [redact] takes out
 * anything that looks like a key, token or password (long runs of letters and digits) before it is kept.
 */
object Diag {
    private const val MAX = 1_500
    private lateinit var file: File
    private var cache: ArrayDeque<String>? = null

    fun init(context: Context) { file = File(context.applicationContext.noBackupFilesDir, "diag.vault") }

    private val SECRETISH = Regex("[A-Za-z0-9_\\-+/=]{24,}")
    private val TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(com.optionslab.engine.IST)

    /** Anything that could be a key, token, secret or password is replaced; the rest is kept as said. */
    fun redact(s: String): String = SECRETISH.replace(s, "‹redacted›")

    @Synchronized
    private fun lines(): ArrayDeque<String> {
        cache?.let { return it }
        val out = ArrayDeque<String>()
        runCatching {
            val a = JSONArray(String(Vault.readFileSteady(file) ?: return@runCatching, Charsets.UTF_8))
            for (i in 0 until a.length()) out.addLast(a.getString(i))
        }
        cache = out
        return out
    }

    /** One writer thread: a banner shown from the screen's thread never waits on the disk. */
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "diag").apply { isDaemon = true } }

    /** Keep one event: "[area] text", time-stamped (IST) now, written in the background. Never throws. */
    fun record(area: String, text: String) {
        if (!::file.isInitialized) return
        val line = "${TIME.format(Instant.now())} [$area] ${redact(text.replace('\n', ' '))}"
        runCatching { writer.execute { keep(line) } }
    }

    @Synchronized
    private fun keep(line: String) {
        runCatching {
            val l = lines()
            l.addLast(line)
            while (l.size > MAX) l.removeFirst()
            Vault.writeFile(file, JSONArray(l.toList()).toString().toByteArray(Charsets.UTF_8))
        }
    }

    /** Waits for the writes queued so far (tests, and the report, read what was just recorded). */
    fun flush() { runCatching { writer.submit {}.get(5, java.util.concurrent.TimeUnit.SECONDS) } }

    /** The report the owner copies: the app's state (no identities, no keys), then the latest events and bot notes. */
    suspend fun report(): String = buildString {
        flush()
        val s = runCatching { AppSettings.load() }.getOrNull()
        append("IraAlgo diagnostics · ${TIME.format(Instant.now())} IST\n")
        append("App ${com.optionslab.app.BuildConfig.VERSION_NAME} · Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n")
        append("Mode: ${if (s?.live == true) "LIVE" else "Paper"} · real orders allowed: ${s?.allowRealOrders} · kill switch: ${s?.guardKill}\n")
        append("Market open: ${Market.isOpen()} · Zerodha linked: ${Broker.linked} · logged in: ${Broker.loggedIn}\n")
        append("Static IP set: ${StaticIp.registered != null} · relay on: ${Relay.enabled} · relay connected: ${runCatching { Relay.connected }.getOrDefault(false)}\n")
        append("\n-- Events (newest last) --\n")
        synchronized(this@Diag) { lines().toList() }.takeLast(400).forEach { append(it).append('\n') }
        append("\n-- Strategy notes --\n")
        runCatching { Strategies.log().take(40).reversed() }.getOrDefault(emptyList()).forEach {
            append("${TIME.format(Instant.ofEpochMilli(it.at))} ${it.strategy}: ${redact(it.message)}\n")
        }
        append("\n-- Pine notes --\n")
        PineAuto.log.value.takeLast(40).forEach { append("${TIME.format(Instant.ofEpochMilli(it.at))} #${it.script}: ${redact(it.text)}\n") }
    }

    @Synchronized
    fun wipe() { cache = null; if (::file.isInitialized) file.delete() }
}
