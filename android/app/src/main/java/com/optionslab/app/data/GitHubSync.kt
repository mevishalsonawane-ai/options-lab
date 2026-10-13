package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.SecretBox
import com.optionslab.app.security.SecurePrefs
import com.optionslab.ira.CloudRun
import com.optionslab.ira.CloudSync
import com.optionslab.ira.GitHubReleases
import com.optionslab.ira.LiveSeal
import com.optionslab.ira.OpenProblems
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The phone's GitHub sync (TODO G1 and G2, 13 Oct; the rules are [CloudSync], one run is [CloudRun]):
 *
 *  - G1: each recorded day's folder ([LiveRecorder]: the market file, the tick zone, the order-flow minutes and Boss's trades,
 *    still encrypted as they are) zipped with a manifest, sealed with the backup passphrase's key ([LiveSeal]) and uploaded
 *    to the release `live-YYYY-MM`. research/data/decrypt_live.py opens it on a PC with the passphrase.
 *  - G2: the phone backup ([Backup.createSealed], the same passphrase) to the release `phone-backup` each evening, and a
 *    few minutes after a strategy or Pine script is added or changed ([changed]); old ones pruned. "Restore from GitHub"
 *    lists them ([backups]) and opens one through [Backup.open] / [Backup.restore], with every restore rule as it is.
 *
 * The token (a fine-grained GitHub token for options-lab only) is kept twice, never readable in the vault:
 *  - sealed with Boss's PIN ([SecretBox], as the Zerodha API secret) - the copy the PIN can always open;
 *  - sealed with a key in this phone's Keystore that never leaves it - the copy the evening worker opens with nobody there.
 *    Should the Keystore lose its key, the card asks for the PIN once to make it again ([reopen]).
 * Neither copy, nor the passphrase's keys, is in a backup ("github." is [com.optionslab.ira.Upkeep.PRIVATE]) or in the
 * diagnostics ([diagLine] has no secret), and no error carries the token ([CloudSync.redact]).
 *
 * Runs only from [com.optionslab.app.work.GitHubSyncWorker] (WorkManager: after 15:45, on unmetered Wi-Fi while charging)
 * and the card's buttons, always off the main thread. Nothing here is on the order or tick path: [changed] only starts a
 * coroutine. Not in IraGoldAlgo.
 */
object GitHubSync {
    private const val K_TOKEN_PIN = "github.token.pin"
    private const val K_TOKEN_DEV = "github.token.dev"
    private const val K_EXPIRY = "github.token.expiry"
    private const val K_LOGIN = "github.token.login"
    private const val K_KEY_LIVE = "github.key.live"
    private const val K_KEY_BACKUP = "github.key.backup"
    private const val K_DONE = "github.live.done"
    private const val K_LAST = "github.last"
    private const val K_LAST_OK = "github.lastOk"
    private const val K_LAST_BACKUP = "github.lastBackup"
    private const val K_EVENING = "github.evening"

    const val PROBLEM = "github:sync"
    const val PROBLEM_TOKEN = "github:token"
    private const val AGENT = "GitHub sync"
    private const val OUT_DIR = "github-out"

    @Volatile private var app: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runLock = Mutex()
    private val extras = CopyOnWriteArrayList<(LocalDate) -> List<File>>()

    fun enabled(): Boolean = !com.optionslab.app.BuildConfig.GOLD

    /** Keeps the context only (no read, no write). */
    fun init(context: Context) { if (enabled()) app = context.applicationContext }

    // ---- hooks ---------------------------------------------------------------------------------------------------------

    /**
     * More files for a recorded day's upload (for example the nightly lessons bundle), beside the day's own folder, which
     * is always sent whole. [provider] runs on the upload's thread and returns files that exist; register once at start.
     */
    fun addDayFiles(provider: (LocalDate) -> List<File>) { extras += provider }

    /**
     * A strategy or Pine script was added or changed: the phone backup goes to GitHub a few minutes after the last change
     * ([CloudSync.DEBOUNCE_MIN], on unmetered Wi-Fi). Memory only on the caller's thread: a coroutine looks at the setup.
     */
    fun changed() {
        val c = app ?: return
        scope.launch { runCatching { if (ready()) com.optionslab.app.work.GitHubSyncWorker.backupSoon(c) } }
    }

    // ---- the token -----------------------------------------------------------------------------------------------------

    /** A token is saved. Reads the vault: off the main thread. */
    val tokenSet: Boolean get() = SecurePrefs.getString(K_TOKEN_PIN) != null

    /** The backup passphrase's keys are set. */
    val keyReady: Boolean get() = SecurePrefs.getString(K_KEY_LIVE) != null && SecurePrefs.getString(K_KEY_BACKUP) != null

    /** Everything set for an upload. */
    fun ready(): Boolean = enabled() && tokenSet && keyReady

    val login: String? get() = SecurePrefs.getString(K_LOGIN)

    val expiry: LocalDate? get() = SecurePrefs.getString(K_EXPIRY)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    /**
     * Checks [token] with GitHub (GET /user, then that it can see options-lab) and keeps it sealed by [pin] (verified by the
     * caller) and by this phone's Keystore, with [expiry] (Boss's date; GitHub's own when that is earlier). Returns the
     * GitHub user name. Throws with words for Boss (never the token). Network: off the main thread.
     */
    suspend fun saveToken(token: String, expiry: LocalDate, pin: CharArray): String = withContext(Dispatchers.IO) {
        val t = token.trim()
        if (!CloudSync.tokenLooksRight(t)) throw IllegalArgumentException("That is not a fine-grained GitHub token (it starts with github_pat_).")
        val gh = GitHubReleases(HttpsGitHub, t)
        val user = gh.user()
        if (!gh.canSeeRepo()) throw IOException("This token cannot see ${CloudSync.REPO}. Make it for that repository only, with Contents: read and write.")
        val kept = CloudSync.expiryKept(expiry, user.expires)
        SecurePrefs.putAll(mapOf(K_TOKEN_PIN to SecretBox.seal(t, pin), K_TOKEN_DEV to DeviceKey.seal(t),
            K_EXPIRY to kept?.toString(), K_LOGIN to user.login))
        runCatching { check() }
        user.login
    }

    /** The device copy could not be opened (the Keystore lost its key): made again from the PIN copy. False: wrong PIN. */
    suspend fun reopen(pin: CharArray): Boolean = withContext(Dispatchers.IO) {
        val t = SecurePrefs.getString(K_TOKEN_PIN)?.let { SecretBox.open(it, pin) } ?: return@withContext false
        SecurePrefs.put(K_TOKEN_DEV, DeviceKey.seal(t))
        true
    }

    /** The device copy opens (false: [reopen] needed). */
    fun deviceCopyOk(): Boolean = token() != null

    /** Forgets the token (both copies) and its expiry. The passphrase's keys and the upload record stay. */
    fun forgetToken() {
        SecurePrefs.putAll(mapOf(K_TOKEN_PIN to null, K_TOKEN_DEV to null, K_EXPIRY to null, K_LOGIN to null))
        runCatching { ProblemsHost.clear(PROBLEM); ProblemsHost.clear(PROBLEM_TOKEN) }
    }

    private fun token(): String? = SecurePrefs.getString(K_TOKEN_DEV)?.let { runCatching { DeviceKey.open(it) }.getOrNull() }

    // ---- the backup passphrase ---------------------------------------------------------------------------------------

    /**
     * Keeps two keys stretched from [passphrase] (never the passphrase): one for the recorded days ([LiveSeal]), one for the
     * phone backups ([Backup.stretch]). Both open with the passphrase alone, on a PC or a new phone. Slow on purpose: Default.
     */
    suspend fun setPassphrase(passphrase: CharArray): Unit = withContext(Dispatchers.Default) {
        require(passphrase.size >= Backup.MIN_PASSPHRASE) { "The backup passphrase needs at least ${Backup.MIN_PASSPHRASE} characters." }
        require(Backup.strength(passphrase) >= 2) { "That passphrase is too easy to guess. Make it longer, or mix in capitals, digits or symbols." }
        val live = LiveSeal.newKey(passphrase)
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val bk = Backup.stretch(passphrase, salt)
        try {
            SecurePrefs.putAll(mapOf(K_KEY_LIVE to live.encode(), K_KEY_BACKUP to "${hex(salt)}:${hex(bk)}"))
        } finally { live.wipe(); bk.fill(0) }
        runCatching { check() }
        Unit
    }

    private fun backupKey(): Pair<ByteArray, ByteArray>? = runCatching {
        val (salt, k) = SecurePrefs.getString(K_KEY_BACKUP)!!.split(":")
        unhex(salt) to unhex(k)
    }.getOrNull()

    // ---- a run ---------------------------------------------------------------------------------------------------------

    /** The evening run is due now (after 15:45, not yet done today). */
    fun eveningDue(): Boolean = CloudSync.eveningDue(Market.minuteNow(), SecurePrefs.getString(K_EVENING)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }, Market.today())

    fun eveningDone() = SecurePrefs.put(K_EVENING, Market.today().toString())

    /**
     * One run: the waiting recorded days when [withDays] (never in a trading day's 09:00-15:45) and the phone backup when
     * [withBackup]. One run at a time. True when everything it tried worked. [goOn]: false once the worker is stopped.
     */
    suspend fun run(withBackup: Boolean, withDays: Boolean, goOn: () -> Boolean = { true }): Boolean =
        runLock.withLock { withContext(Dispatchers.IO) { runNow(withBackup, withDays, goOn) } }

    private suspend fun runNow(withBackup: Boolean, withDays: Boolean, goOn: () -> Boolean): Boolean {
        val c = app ?: return false
        if (!ready()) return false
        val token = token() ?: run {
            note(CloudSync.Last(System.currentTimeMillis(), false, 0, "upload", "the token needs your PIN once (Data & Harvest → GitHub sync)"))
            return false
        }
        val liveKey = LiveSeal.Key.decode(SecurePrefs.getString(K_KEY_LIVE))
        val bk = backupKey()
        if (liveKey == null || bk == null) return false
        val now = Market.now()
        val minute = now.hour * 60 + now.minute
        val made = if (withBackup) runCatching { Backup.createSealed(c, bk.second, bk.first) } else null
        bk.second.fill(0)
        val dir = File(c.noBackupFilesDir, OUT_DIR).apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val phone = Phone(liveKey, dir, made, goOn)
        val sync = CloudRun(GitHubReleases(HttpsGitHub, token), phone)
        var ok = true
        try {
            if (withDays && CloudSync.liveAllowed(minute, Market.isTradingDay(now.toLocalDate())))
                sync.live(now.toLocalDate(), minute)?.let { o -> note(o.last()); ok = o.ok }
            if (withBackup && goOn()) sync.backup(now.toLocalDateTime().withNano(0)).let { o -> note(o.last()); ok = ok && o.ok }
        } finally {
            liveKey.wipe()
            made?.getOrNull()?.fill(0)
            dir.listFiles()?.forEach { it.delete() }
        }
        runCatching { check() }
        return ok
    }

    private fun CloudRun.Outcome.last() = CloudSync.Last(System.currentTimeMillis(), ok, bytes, what, why)

    private fun note(l: CloudSync.Last) {
        val m = HashMap<String, Any?>()
        m[K_LAST] = l.encode()
        if (l.ok) m[K_LAST_OK] = l.encode()
        if (l.ok && l.what == "backup") m[K_LAST_BACKUP] = l.encode()
        SecurePrefs.putAll(m)
        runCatching { Diag.record("github", if (l.ok) "${l.what}: ${CloudSync.size(l.bytes)} uploaded" else "${l.what}: failed: ${l.why}") }
    }

    /** The phone's side of a run: the recordings folder, the upload record, sealing a day, the backup made before. */
    private class Phone(private val key: LiveSeal.Key, private val dir: File, private val made: Result<ByteArray>?,
                        private val goOnNow: () -> Boolean) : CloudRun.Phone {
        override fun recorded(): Map<LocalDate, Long> = LiveRecorder.days().associate { (d, f) -> d to bytes(f) }
        override fun uploaded(): Map<LocalDate, Long> = CloudSync.doneDecode(SecurePrefs.getString(K_DONE))
        override fun markUploaded(day: LocalDate, bytes: Long) {
            SecurePrefs.put(K_DONE, CloudSync.doneEncode(uploaded() + (day to bytes)))
        }
        override fun goOn(): Boolean = goOnNow()
        override fun backup(): ByteArray = made?.getOrThrow() ?: throw IllegalStateException("no backup made")

        override fun sealDay(day: LocalDate): CloudRun.Sealed {
            val folder = LiveRecorder.days().firstOrNull { it.first == day }?.second ?: throw IOException("the day's folder is gone")
            val files = folder.walkTopDown().filter { it.isFile }.map { it to "$day/${it.relativeTo(folder).invariantSeparatorsPath}" }.toMutableList()
            extras.forEach { p -> runCatching { p(day) }.getOrDefault(emptyList()).filter { it.isFile }
                .forEach { f -> files += f to "$day/extra/${f.name}" } }
            val out = File(dir, CloudSync.liveAsset(day))
            val manifest = ArrayList<Map<String, Any?>>()
            java.util.zip.ZipOutputStream(LiveSeal.sealing(out.outputStream().buffered(1 shl 16), key)).use { z ->
                // The files are gzip already: stored, not squeezed again (no CPU spent for nothing).
                z.setLevel(java.util.zip.Deflater.NO_COMPRESSION)
                for ((f, name) in files) {
                    val md = MessageDigest.getInstance("SHA-256")
                    var n = 0L
                    z.putNextEntry(java.util.zip.ZipEntry(name))
                    f.inputStream().buffered(1 shl 16).use { input ->
                        val buf = ByteArray(1 shl 16)
                        while (true) { val r = input.read(buf); if (r < 0) break; z.write(buf, 0, r); md.update(buf, 0, r); n += r }
                    }
                    z.closeEntry()
                    manifest += mapOf("name" to name, "bytes" to n, "sha256" to hex(md.digest()))
                }
                z.putNextEntry(java.util.zip.ZipEntry("MANIFEST.json"))
                z.write(com.optionslab.engine.strategy.Json.write(mapOf("day" to day.toString(),
                    "app" to com.optionslab.app.BuildConfig.VERSION_NAME, "files" to manifest)).toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
            return CloudRun.Sealed(out.length(), { o -> out.inputStream().use { it.copyTo(o, 1 shl 16) } }, { out.delete() })
        }

        private fun bytes(f: File): Long = f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    // ---- restore from GitHub -----------------------------------------------------------------------------------------

    data class Saved(val id: Long, val at: java.time.LocalDateTime, val bytes: Long)

    /** The phone backups on GitHub, newest first. Network: off the main thread. */
    suspend fun backups(): List<Saved> = withContext(Dispatchers.IO) {
        val t = token() ?: throw IOException("The GitHub token needs your PIN once: tap \"Unlock with PIN\" first.")
        CloudRun(GitHubReleases(HttpsGitHub, t), NoPhone).backups().map { (a, at) -> Saved(a.id, at, a.size) }
    }

    /** One backup's bytes (an IRABK3 file: [Backup.open] with the passphrase). Network: off the main thread. */
    suspend fun download(s: Saved): ByteArray = withContext(Dispatchers.IO) {
        val t = token() ?: throw IOException("The GitHub token needs your PIN once: tap \"Unlock with PIN\" first.")
        GitHubReleases(HttpsGitHub, t).download(s.id)
    }

    private object NoPhone : CloudRun.Phone {
        override fun recorded() = emptyMap<LocalDate, Long>()
        override fun uploaded() = emptyMap<LocalDate, Long>()
        override fun markUploaded(day: LocalDate, bytes: Long) = Unit
        override fun sealDay(day: LocalDate): CloudRun.Sealed = throw UnsupportedOperationException()
        override fun backup(): ByteArray = throw UnsupportedOperationException()
    }

    // ---- the card, the problems, the diagnostics ---------------------------------------------------------------------

    private fun toDay(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(com.optionslab.engine.IST).toLocalDate()

    /** What the card shows. Reads the vault and lists the recordings folder: off the main thread. */
    fun status(): CloudSync.Status {
        val minute = Market.minuteNow()
        val waiting = runCatching {
            CloudSync.daysWaiting(LiveRecorder.days().associate { (d, f) -> d to f.walkTopDown().filter { it.isFile }.sumOf { it.length() } },
                CloudSync.doneDecode(SecurePrefs.getString(K_DONE)), Market.today(), minute)
        }.getOrDefault(0)
        return CloudSync.Status(tokenSet, keyReady, expiry, CloudSync.Last.decode(SecurePrefs.getString(K_LAST)),
            CloudSync.Last.decode(SecurePrefs.getString(K_LAST_OK)), CloudSync.Last.decode(SecurePrefs.getString(K_LAST_BACKUP)), waiting)
    }

    fun card(s: CloudSync.Status): List<Pair<String, String>> = CloudSync.card(s, ::toDay, Market.today())

    /**
     * Problems found: a failed upload or none for two days ([CloudSync.problem]), and the token's renewal reminder from 7 days
     * before it expires (a notification once a day). Cleared when all is well. Off the main thread.
     */
    fun check() {
        if (!enabled()) return
        val s = status()
        val today = Market.today()
        val p = CloudSync.problem(s, today, ::toDay)
        if (p != null) ProblemsHost.report(PROBLEM, AGENT, OpenProblems.Severity.WARN, p, "Settings → Data & Harvest → GitHub sync")
        else ProblemsHost.clear(PROBLEM)
        val e = if (s.tokenSet) CloudSync.expiryNote(s.expiry, today) else null
        if (e != null) ProblemsHost.report(PROBLEM_TOKEN, AGENT, OpenProblems.Severity.WARN, e, "See MAINTENANCE.md, \"GitHub token\"", notify = true)
        else ProblemsHost.clear(PROBLEM_TOKEN)
    }

    /** One line for Copy diagnostics: states and dates only, never the token, a key or a user name. */
    fun diagLine(): String {
        if (!enabled()) return "GitHub sync: not in this app"
        val s = status()
        val t = s.lastTry?.let { "${toDay(it.atMs)} ${if (it.ok) "ok" else "failed: ${it.why}"}" } ?: "none"
        return CloudSync.redact("GitHub sync: token ${if (s.tokenSet) "set" + (s.expiry?.let { ", expires $it" } ?: "") else "not set"} · " +
            "passphrase ${if (s.keyReady) "set" else "not set"} · last $t · last backup ${s.lastBackupOk?.let { toDay(it.atMs) } ?: "none"} · " +
            "days waiting ${s.waiting}")
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    /**
     * An AES key in this phone's Keystore that never leaves it, for the token's unattended copy. Its own alias: nothing else
     * uses it, and erasing it ([destroy]) only means the PIN is asked once ([reopen]).
     */
    private object DeviceKey {
        private const val STORE = "AndroidKeyStore"
        private const val ALIAS = "ol.github.v1"

        @Synchronized private fun key(): javax.crypto.SecretKey {
            val ks = java.security.KeyStore.getInstance(STORE).apply { load(null) }
            (ks.getKey(ALIAS, null) as? javax.crypto.SecretKey)?.let { return it }
            val spec = android.security.keystore.KeyGenParameterSpec.Builder(ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build()
            return javax.crypto.KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, STORE)
                .run { init(spec); generateKey() }
        }

        fun seal(plain: String): String {
            val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply { init(javax.crypto.Cipher.ENCRYPT_MODE, key()) }
            return "k1:${hex(c.iv)}:${hex(c.doFinal(plain.toByteArray(Charsets.UTF_8)))}"
        }

        fun open(blob: String): String? {
            val p = blob.split(":")
            if (p.size != 3 || p[0] != "k1") return null
            val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                .apply { init(javax.crypto.Cipher.DECRYPT_MODE, key(), javax.crypto.spec.GCMParameterSpec(128, unhex(p[1]))) }
            return String(c.doFinal(unhex(p[2])), Charsets.UTF_8)
        }
    }
}

/**
 * GitHub over HTTPS for [GitHubReleases]: the system's certificate authorities only (the app's network security config),
 * redirects never followed here (the client decides who sees the token), a body streamed with its length known, the answer
 * read up to the request's cap. Nothing is logged. Blocking: a background thread.
 */
internal object HttpsGitHub : GitHubReleases.Http {
    override fun send(r: GitHubReleases.Request): GitHubReleases.Response {
        val c = java.net.URL(r.url).openConnection() as? javax.net.ssl.HttpsURLConnection ?: throw IOException("refusing a non-HTTPS request")
        try {
            c.instanceFollowRedirects = false
            c.requestMethod = r.method
            c.connectTimeout = 20_000
            c.readTimeout = 120_000
            c.useCaches = false
            r.headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            r.body?.let { b ->
                c.doOutput = true
                c.setRequestProperty("Content-Type", b.contentType)
                c.setFixedLengthStreamingMode(b.length)
                c.outputStream.use { b.write(it) }
            }
            val code = c.responseCode
            val headers = HashMap<String, String>()
            c.headerFields.forEach { (k, v) -> if (k != null) headers[k.lowercase(java.util.Locale.ROOT)] = v?.firstOrNull() ?: "" }
            val stream = if (code >= 400) c.errorStream else runCatching { c.inputStream }.getOrNull()
            val body = stream?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(1 shl 16)
                var total = 0L
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    total += n
                    if (total > r.maxBytes) throw IOException("GitHub's answer is bigger than expected")
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            } ?: ByteArray(0)
            return GitHubReleases.Response(code, headers, body)
        } finally { c.disconnect() }
    }
}
