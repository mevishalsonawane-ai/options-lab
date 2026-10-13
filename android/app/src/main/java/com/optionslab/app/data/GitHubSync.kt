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
 *  - Where: the private, data-only repository mevishalsonawane-ai/IraAlgo ([CloudSync.DEFAULT_REPO]; Boss, 13 Oct), the
 *    card's "Data repository" field. Never options-lab, whatever is typed ([CloudSync.repoRefused]): a token that can write
 *    to options-lab could push code CI signs, or replace the `latest-apk` asset. A token that reaches options-lab is refused
 *    when it is pasted ([GitHubReleases.reachesCode]).
 *  - G1: each recorded day's folder ([LiveRecorder]: the market file, the tick zone, the order-flow minutes and Boss's trades,
 *    still encrypted as they are) zipped with a manifest, sealed with the backup passphrase's key ([LiveSeal]) and uploaded
 *    to the release `live-YYYY-MM`. research/data/decrypt_live.py opens it on a PC with the passphrase.
 *  - G2: the phone backup ([Backup.createSealed], the same passphrase) to the release `phone-backup` each evening, and
 *    after a strategy or Pine script is added or changed ([changed]; from the evening gate, charging); old ones pruned. "Restore from GitHub"
 *    lists them ([backups]) and opens one through [Backup.open] / [Backup.restore], with every restore rule as it is.
 *  - G3: the evening run's day's diagnostics ([DiagDayHost], [com.optionslab.ira.DiagDay]): one small redacted file,
 *    `diag-YYYY-MM-DD.json.gz` in the release `diag-YYYY-MM`, replacing that day's file on a re-run. Not sealed: nothing
 *    personal is in it (no ids, names or rupees), and the research sessions read it without the passphrase.
 *
 * The token (a fine-grained GitHub token for the data repository only, Contents read and write, 90 days):
 *  - sealed with Boss's PIN ([SecretBox], as the Zerodha API secret). Everything Boss does by hand with it (Restore from
 *    GitHub: the list and the download) opens this copy with the PIN ([openRestore]).
 *  - The evening upload runs with nobody there, so it cannot ask for the PIN. The trade-off (13 Oct review): no PIN-less copy
 *    at all would mean no automatic upload; a Keystore key that needs the screen unlocked would fail on a locked phone on
 *    charge, which is exactly when it runs. The safer design kept: ONE device-bound copy for the upload worker only
 *    ([workerToken], used by [runNow] and nothing else), sealed by a Keystore key that never leaves this phone, and bound to
 *    the data repository (the repository is the cipher's additional data, so it opens for nothing else, and options-lab is
 *    refused before it is opened). The token itself is the real limit: made for IraAlgo only, it cannot touch the app's code
 *    or its releases even if this copy were taken. The old copy that any screen could open without the PIN
 *    ("github.token.dev", Keystore "ol.github.v1") is gone and removed from phones that had it ([check]).
 *    Should the Keystore lose its key, the card asks for the PIN once to make the worker's copy again ([reopen]).
 * Neither copy, nor the passphrase's keys, is in a backup ("github." is [com.optionslab.ira.Upkeep.PRIVATE]) or in the
 * diagnostics ([diagLine] has no secret), never logged, and no error carries the token ([CloudSync.redact]).
 *
 * Runs only from [com.optionslab.app.work.GitHubSyncWorker] (WorkManager: after the MCX evening close, 15:45 on a day without
 * an MCX session ([eveningFrom], G4, 13 Oct), or a missed evening the next morning before 08:00; on unmetered Wi-Fi while
 * charging; "Upload now" is Boss's tap: unmetered Wi-Fi only) and the card's buttons, always off the main thread. While GitHub's last
 * answer refused the token (401/403), the automatic runs build no backup and send nothing: Problems found says
 * "GitHub token invalid" ([CloudSync.authRefused]). Nothing here is on the order or tick path: [changed] only starts a
 * coroutine. Not in IraGoldAlgo.
 */
object GitHubSync {
    private const val K_TOKEN_PIN = "github.token.pin"
    /** The upload worker's own copy, bound to the data repository ([workerToken]). */
    private const val K_TOKEN_WORKER = "github.token.worker"
    /** The removed PIN-less copy (13 Oct review): deleted where found ([check]). */
    private const val K_TOKEN_OLD = "github.token.dev"
    /** The data repository ("owner/name"), saved with the token. */
    private const val K_REPO = "github.repo"
    /** Set when an old options-lab token was forgotten: Problems found asks for a new one until it is pasted. */
    private const val K_DROPPED = "github.token.dropped"
    private const val K_EXPIRY = "github.token.expiry"
    private const val K_LOGIN = "github.token.login"
    private const val K_KEY_LIVE = "github.key.live"
    private const val K_KEY_BACKUP = "github.key.backup"
    private const val K_DONE = "github.live.done"
    private const val K_LAST = "github.last"
    private const val K_LAST_OK = "github.lastOk"
    private const val K_LAST_BACKUP = "github.lastBackup"
    private const val K_EVENING = "github.evening"
    /** The last day whose diagnostics went up (G3): the card's "last day uploaded". */
    private const val K_DIAG = "github.diag.day"

    const val PROBLEM = "github:sync"
    const val PROBLEM_TOKEN = "github:token"
    private const val AGENT = "GitHub sync"
    private const val OUT_DIR = "github-out"

    @Volatile private var app: Context? = null
    /** GitHub over HTTPS; a test gives a fake. */
    @Volatile internal var http: GitHubReleases.Http = HttpsGitHub
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
     * A strategy or Pine script was added or changed: the phone backup goes to GitHub [CloudSync.DEBOUNCE_MIN] minutes after
     * the last change, never before the evening gate ([eveningFrom]: after MCX's close), on unmetered Wi-Fi while charging
     * ([CloudSync.backupDelayMin]). Memory only on the
     * caller's thread: a coroutine looks at the setup.
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

    /** The data repository saved with the token, or null (none yet, or one that is refused). */
    fun repo(): Pair<String, String>? =
        CloudSync.parseRepo(SecurePrefs.getString(K_REPO))?.takeIf { (o, r) -> CloudSync.repoRefused(o, r) == null }

    /** Everything set for an upload. */
    fun ready(): Boolean = enabled() && tokenSet && keyReady && repo() != null

    /** GitHub refused the token on the last try (401/403): the automatic runs wait for a new token. */
    fun authBlocked(): Boolean = CloudSync.authRefused(CloudSync.Last.decode(SecurePrefs.getString(K_LAST)))

    val login: String? get() = SecurePrefs.getString(K_LOGIN)

    val expiry: LocalDate? get() = SecurePrefs.getString(K_EXPIRY)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    /**
     * Checks [token] with GitHub for the data repository [repoText] ("owner/name"; options-lab is refused): GET /user, that it
     * can see that repository, and that it can NOT reach options-lab (a token made for more than the data repository is
     * refused). Keeps it sealed by [pin] (verified by the caller) and the upload worker's device-bound copy, with [expiry]
     * (Boss's date; GitHub's own when that is earlier). Returns the GitHub user name. Throws with words for Boss (never the
     * token). Network: off the main thread.
     */
    suspend fun saveToken(token: String, expiry: LocalDate, pin: CharArray, repoText: String = CloudSync.DEFAULT_REPO): String =
        withContext(Dispatchers.IO) {
            val t = token.trim()
            if (!CloudSync.tokenLooksRight(t)) throw IllegalArgumentException("That is not a fine-grained GitHub token (it starts with github_pat_).")
            val (owner, name) = CloudSync.parseRepo(repoText) ?: throw IllegalArgumentException("Enter the data repository as owner/name, for example ${CloudSync.DEFAULT_REPO}.")
            CloudSync.repoRefused(owner, name)?.let { throw IllegalArgumentException(it) }
            val gh = GitHubReleases(http, t, owner, name)
            val user = gh.user()
            if (!gh.canSeeRepo()) throw IOException("This token cannot see $owner/$name. Make it for that repository only, with Contents: read and write.")
            // GitHub's answer that decided is added (13 Oct review), so a refusal Boss did not expect can be diagnosed. It is
            // GitHub's status and words only, cut and redacted ([GitHubReleases.lastProbe]): never the token.
            if (gh.reachesCode()) throw IOException("This token can also reach ${CloudSync.CODE_REPO} (the app's code). Delete it on GitHub and make one for $owner/$name ONLY." +
                (gh.lastProbe?.let { " (GitHub said: $it)" } ?: ""))
            val kept = CloudSync.expiryKept(expiry, user.expires)
            val m = HashMap<String, Any?>()
            m[K_TOKEN_PIN] = SecretBox.seal(t, pin)
            m[K_TOKEN_WORKER] = WorkerKey.seal(t, "$owner/$name")
            m[K_TOKEN_OLD] = null
            m[K_DROPPED] = null
            m[K_REPO] = "$owner/$name"
            m[K_EXPIRY] = kept?.toString()
            m[K_LOGIN] = user.login
            // A new token: the pause after a refused one ends (the next automatic run tries it).
            if (authBlocked()) m[K_LAST] = null
            SecurePrefs.putAll(m)
            runCatching { check() }
            user.login
        }

    /** The worker's copy could not be opened (the Keystore lost its key): made again from the PIN copy. False: wrong PIN. */
    suspend fun reopen(pin: CharArray): Boolean = withContext(Dispatchers.IO) {
        val (owner, name) = repo() ?: return@withContext false
        val t = SecurePrefs.getString(K_TOKEN_PIN)?.let { SecretBox.open(it, pin) } ?: return@withContext false
        SecurePrefs.put(K_TOKEN_WORKER, WorkerKey.seal(t, "$owner/$name"))
        true
    }

    /** The upload worker's copy opens (false: [reopen] needed). Opens and drops it: nothing is kept. */
    fun workerCopyOk(): Boolean = repo()?.let { (o, r) -> workerToken(o, r) != null } ?: false

    /** Forgets the token (every copy), its repository and expiry. The passphrase's keys and the upload record stay. */
    fun forgetToken() {
        SecurePrefs.putAll(mapOf(K_TOKEN_PIN to null, K_TOKEN_WORKER to null, K_TOKEN_OLD to null, K_REPO to null, K_EXPIRY to null, K_LOGIN to null))
        runCatching { ProblemsHost.clear(PROBLEM); ProblemsHost.clear(PROBLEM_TOKEN) }
    }

    /**
     * The upload worker's copy of the token, for [owner]/[repo] only: null when it is not that repository's copy, the
     * repository is refused (options-lab), or the Keystore lost its key. Only [runNow] calls this.
     */
    private fun workerToken(owner: String, repo: String): String? {
        if (CloudSync.repoRefused(owner, repo) != null) return null
        return SecurePrefs.getString(K_TOKEN_WORKER)?.let { runCatching { WorkerKey.open(it, "$owner/$repo") }.getOrNull() }
    }

    /** The PIN copy, opened with [pin] for something Boss does by hand. Throws with words for Boss. */
    private fun pinToken(pin: CharArray): String =
        SecurePrefs.getString(K_TOKEN_PIN)?.let { SecretBox.open(it, pin) } ?: throw IOException("Not the PIN the token was sealed with. Paste a new token instead.")

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

    /**
     * [day]'s evening gate, the earliest minute of every automatic upload (G4, 13 Oct, Boss: "after the commodities close"):
     * MCX's close from the app's MCX calendar ([McxMarket.calendar]: 23:30, or 23:55 in US winter time), 15:45 on a day MCX
     * does not trade (an MCX holiday, a weekend), and the safe 23:55 when the calendar cannot be read
     * ([CloudSync.eveningFrom]; an unreadable holiday file already falls back to the built-in list, which takes any weekday
     * not listed as an MCX day: a later gate, never an earlier one). The calendar's first read opens its small holiday file,
     * then it is kept in memory: called only from the worker's thread and [changed]'s coroutine, never the main thread, the
     * order or the tick path.
     */
    fun eveningFrom(day: LocalDate): Int {
        val w = runCatching { McxMarket.calendar().window(day) }
        return CloudSync.eveningFrom(w.isSuccess, w.getOrNull())
    }

    /**
     * The evening whose automatic run is due at [now] (from [from], the gate of [now]'s day; a missed one until 09:00 the next
     * morning), or null: none, or already done ([CloudSync.eveningDueDay]). The minute and the day come from the same [now],
     * so a check at 23:59:59 cannot mix two days.
     */
    fun eveningDueDay(now: java.time.ZonedDateTime, from: Int): LocalDate? =
        CloudSync.eveningDueDay(now.hour * 60 + now.minute, SecurePrefs.getString(K_EVENING)?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            now.toLocalDate(), from)

    /**
     * The evening [day]'s run worked. Kept as that evening, not the day it ended (G4): a run that began at 23:58 and ended
     * after midnight must not hide the next day's own evening run.
     */
    fun eveningDone(day: LocalDate) = SecurePrefs.put(K_EVENING, day.toString())

    /**
     * One run: the waiting recorded days when [withDays] (never in a trading day's 09:00-15:45), [diagDay]'s diagnostics when
     * given (G3, [CloudSync.diagDay]) and the phone backup when [withBackup]. One run at a time. True when everything it tried
     * worked. [goOn]: false once the worker is stopped.
     */
    suspend fun run(withBackup: Boolean, withDays: Boolean, manual: Boolean = false, eveningFrom: Int = CloudSync.EVENING,
                    diagDay: LocalDate? = null, goOn: () -> Boolean = { true }): Boolean =
        runLock.withLock { withContext(Dispatchers.IO) { runNow(withBackup, withDays, manual, eveningFrom, diagDay, goOn) } }

    /**
     * [manual]: Boss tapped "Upload now" - the only run that tries after GitHub refused the token. An automatic run builds
     * no backup and sends nothing while it is refused (13 Oct review).
     */
    private suspend fun runNow(withBackup: Boolean, withDays: Boolean, manual: Boolean, eveningFrom: Int, diagDay: LocalDate?,
                               goOn: () -> Boolean): Boolean {
        val c = app ?: return false
        if (!ready()) return false
        if (!manual && authBlocked()) return false
        val (owner, name) = repo() ?: return false
        val token = workerToken(owner, name) ?: run {
            note(CloudSync.Last(System.currentTimeMillis(), false, 0, "upload", "the token needs your PIN once (Data & Harvest → GitHub sync)"))
            return false
        }
        val gh = try { GitHubReleases(http, token, owner, name) } catch (e: IllegalArgumentException) { return false }
        val liveKey = LiveSeal.Key.decode(SecurePrefs.getString(K_KEY_LIVE))
        val bk = backupKey()
        if (liveKey == null || bk == null) return false
        val now = Market.now()
        val minute = now.hour * 60 + now.minute
        val made = if (withBackup) runCatching { Backup.createSealed(c, bk.second, bk.first) } else null
        bk.second.fill(0)
        val dir = File(c.noBackupFilesDir, OUT_DIR).apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        // G4 (13 Oct, and its review): the clock is read again before each day's file and before the backup
        // ([CloudSync.sendNow]). No day's file once a trading day's 09:00-15:45 has begun, by hand or not; an automatic run
        // sends nothing new once its window (the gate [eveningFrom] to 08:00 the next morning) has ended. What is left waits
        // for the next evening.
        val sendNow = { dayFile: Boolean ->
            Market.now().let { t ->
                CloudSync.sendNow(t.hour * 60 + t.minute, Market.isTradingDay(t.toLocalDate()), manual, dayFile, eveningFrom)
            }
        }
        val phone = Phone(liveKey, dir, made) { goOn() && sendNow(true) }
        val sync = CloudRun(gh, phone)
        val outcomes = ArrayList<CloudSync.Last>()
        try {
            // A token that can reach options-lab (its access widened on GitHub after it was pasted) is refused before anything
            // is sent: recorded as refused (403), so the automatic runs pause and Problems found says "GitHub token invalid".
            // When the check itself fails (a rate limit, no network), nothing is sent this run (13 Oct review: it used to
            // go on as if the answer were "no"). That is not a refused token: noted in diagnostics only, the runs not paused.
            val reaches = try { gh.reachesCode() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                runCatching { Diag.record("github", CloudSync.redact("upload skipped this time: the ${CloudSync.CODE_REPO} check failed (${e.message?.take(120) ?: e.javaClass.simpleName})")) }
                return false
            }
            if (reaches) {
                note(CloudSync.Last(System.currentTimeMillis(), false, 0, "upload",
                    "the token can reach ${CloudSync.CODE_REPO}: make one for ${owner}/${name} only", 403))
                runCatching { check() }
                return false
            }
            if (withDays && CloudSync.liveAllowed(minute, Market.isTradingDay(now.toLocalDate())))
                sync.live(now.toLocalDate(), minute)?.let { o -> outcomes += lastOf(o, gh) }
            // G3: the day's diagnostics, small and redacted, made here on the worker's thread (never the order or tick path).
            // A report that cannot be made is a diary line only, never a failed run: the evening run would otherwise try
            // again (and send the backup again) every hour until 08:00. A failed upload counts like any other.
            if (diagDay != null && goOn() && sendNow(false) && outcomes.none { CloudSync.authRefused(it) }) {
                val report = try { DiagDayHost.report(diagDay) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                    runCatching { Diag.record("github", CloudSync.redact("diagnostics $diagDay: could not be made (${e.javaClass.simpleName})")) }
                    null
                }
                if (report != null) {
                    val o = sync.diag(diagDay) { report }
                    if (o.ok) runCatching {
                        val kept = SecurePrefs.getString(K_DIAG)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                        SecurePrefs.put(K_DIAG, CloudSync.diagKept(kept, diagDay).toString())
                    }
                    outcomes += lastOf(o, gh)
                }
            }
            // The same refused token is not tried again in the same run.
            if (withBackup && goOn() && sendNow(false) && outcomes.none { CloudSync.authRefused(it) })
                outcomes += lastOf(sync.backup(now.toLocalDateTime().withNano(0)), gh)
        } finally {
            liveKey.wipe()
            made?.getOrNull()?.fill(0)
            dir.listFiles()?.forEach { it.delete() }
            // Every outcome updates "last that worked"; the last try shown is the run's first failure (a backup that
            // worked must not hide a day that failed).
            outcomes.forEach { note(it) }
            outcomes.firstOrNull { !it.ok }?.let { SecurePrefs.put(K_LAST, it.encode()) }
        }
        runCatching { check() }
        return outcomes.all { it.ok }
    }

    /**
     * The run's [o] as kept. GitHub answers 404, not 401/403, when a fine-grained token lost its access to a private
     * repository: when the token no longer sees the data repository, that 404 is kept as refused (403).
     */
    private fun lastOf(o: CloudRun.Outcome, gh: GitHubReleases): CloudSync.Last {
        val code = if (!o.ok && o.code == 404 && runCatching { !gh.canSeeRepo() }.getOrDefault(false)) 403 else o.code
        return CloudSync.Last(System.currentTimeMillis(), o.ok, o.bytes, o.what, o.why, code)
    }

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
        /** Only after the upload worked: the phone's backup reminder rests (13 Oct review). */
        override fun backedUp(at: java.time.LocalDateTime) = Backup.markOffPhone()

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

    /** Restore from GitHub, opened with Boss's PIN: the backups on GitHub, newest first, and a download of one. */
    class Restore internal constructor(private val gh: GitHubReleases, val list: List<Saved>) {
        /** One backup's bytes (an IRABK3 file: [Backup.open] with the passphrase). Network: off the main thread. */
        suspend fun download(s: Saved): ByteArray = withContext(Dispatchers.IO) { gh.download(s.id) }
    }

    /**
     * Opens Restore from GitHub with the token's PIN copy ([pin] verified by the caller): never the worker's copy. Lists the
     * backups (newest first). Network: off the main thread.
     */
    suspend fun openRestore(pin: CharArray): Restore = withContext(Dispatchers.IO) {
        val (owner, name) = repo() ?: throw IOException("Paste the GitHub token for the data repository first.")
        val gh = GitHubReleases(http, pinToken(pin), owner, name)
        Restore(gh, CloudRun(gh, NoPhone).backups().map { (a, at) -> Saved(a.id, at, a.size) })
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
            CloudSync.Last.decode(SecurePrefs.getString(K_LAST_OK)), CloudSync.Last.decode(SecurePrefs.getString(K_LAST_BACKUP)), waiting,
            repo()?.let { "${it.first}/${it.second}" } ?: CloudSync.DEFAULT_REPO,
            SecurePrefs.getString(K_DIAG)?.let { runCatching { LocalDate.parse(it) }.getOrNull() })
    }

    fun card(s: CloudSync.Status): List<Pair<String, String>> = CloudSync.card(s, ::toDay, Market.today())

    /**
     * Problems found: a failed upload or none for two days ([CloudSync.problem]), and the token's renewal reminder from 7 days
     * before it expires (a notification once a day). Cleared when all is well. Off the main thread.
     */
    fun check() {
        if (!enabled()) return
        dropOldCopies()
        if (SecurePrefs.getString(K_DROPPED) != null && !tokenSet) { reportDropped(); return }   // stands until a new token
        val s = status()
        val today = Market.today()
        val p = CloudSync.problem(s, today, ::toDay)
        if (p != null) ProblemsHost.report(PROBLEM, AGENT, OpenProblems.Severity.WARN, p, "Settings → Data & Harvest → GitHub sync")
        else ProblemsHost.clear(PROBLEM)
        val e = when {
            !s.tokenSet -> null
            repo() == null -> "GitHub sync now uploads to ${CloudSync.DEFAULT_REPO}, never options-lab: paste a new token made for that repository only."
            else -> CloudSync.expiryNote(s.expiry, today)
        }
        if (e != null) ProblemsHost.report(PROBLEM_TOKEN, AGENT, OpenProblems.Severity.WARN, e, "See MAINTENANCE.md, \"GitHub token\"", notify = true)
        else ProblemsHost.clear(PROBLEM_TOKEN)
    }

    /**
     * The 13 Oct review's clean-up, off the main thread: the PIN-less copy any screen could open goes, with its Keystore key;
     * a token saved before the data repository existed (made for options-lab) is forgotten, so Boss pastes one for IraAlgo.
     */
    private fun dropOldCopies(): Boolean {
        if (SecurePrefs.getString(K_TOKEN_OLD) != null) SecurePrefs.put(K_TOKEN_OLD, null)
        runCatching { WorkerKey.dropOld() }
        if (SecurePrefs.getString(K_TOKEN_PIN) == null || SecurePrefs.getString(K_REPO) != null) return false
        SecurePrefs.putAll(mapOf(K_TOKEN_PIN to null, K_TOKEN_WORKER to null, K_EXPIRY to null, K_LOGIN to null, K_DROPPED to "1"))
        runCatching { Diag.record("github", "the options-lab token was forgotten: paste one for ${CloudSync.DEFAULT_REPO}") }
        return true
    }

    private fun reportDropped() {
        runCatching { ProblemsHost.report(PROBLEM_TOKEN, AGENT, OpenProblems.Severity.WARN,
            "GitHub sync now uploads to ${CloudSync.DEFAULT_REPO}, never options-lab. The old token was removed from the phone: " +
                "delete it on GitHub too, and paste a new one made for ${CloudSync.DEFAULT_REPO} only.",
            "See MAINTENANCE.md, \"GitHub token\"", notify = true) }
    }

    /** One line for Copy diagnostics: states and dates only, never the token, a key or a user name. */
    fun diagLine(): String {
        if (!enabled()) return "GitHub sync: not in this app"
        val s = status()
        val t = s.lastTry?.let { "${toDay(it.atMs)} ${if (it.ok) "ok" else "failed: ${it.why}"}" } ?: "none"
        return CloudSync.redact("GitHub sync: token ${if (s.tokenSet) "set" + (s.expiry?.let { ", expires $it" } ?: "") else "not set"} · " +
            "to ${s.repo} · " + (if (s.tokenSet && CloudSync.authRefused(s.lastTry)) "token invalid (GitHub refused it) · " else "") +
            "passphrase ${if (s.keyReady) "set" else "not set"} · last $t · last backup ${s.lastBackupOk?.let { toDay(it.atMs) } ?: "none"} · " +
            "diagnostics ${s.lastDiagDay ?: "none"} · " +
            "days waiting ${s.waiting}")
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    /**
     * An AES key in this phone's Keystore that never leaves it, for the upload worker's copy of the token only. Its own alias:
     * nothing else uses it, and erasing it only means the PIN is asked once ([reopen]). The copy is bound to the data
     * repository: "owner/name" is the cipher's additional data, so it opens only for the repository it was saved for.
     * No user authentication on the key, on purpose: the worker runs on a locked phone on charge (see the class comment).
     */
    private object WorkerKey {
        private const val STORE = "AndroidKeyStore"
        private const val ALIAS = "ol.github.worker.v1"
        /** The removed PIN-less copy's key (13 Oct review). */
        private const val OLD_ALIAS = "ol.github.v1"

        private fun aad(repo: String) = "github-upload-worker|$repo".toByteArray(Charsets.UTF_8)

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

        fun seal(plain: String, repo: String): String {
            val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply { init(javax.crypto.Cipher.ENCRYPT_MODE, key()) }
            c.updateAAD(aad(repo))
            return "w1:${hex(c.iv)}:${hex(c.doFinal(plain.toByteArray(Charsets.UTF_8)))}"
        }

        fun open(blob: String, repo: String): String? {
            val p = blob.split(":")
            if (p.size != 3 || p[0] != "w1") return null
            val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                .apply { init(javax.crypto.Cipher.DECRYPT_MODE, key(), javax.crypto.spec.GCMParameterSpec(128, unhex(p[1]))) }
            c.updateAAD(aad(repo))
            return String(c.doFinal(unhex(p[2])), Charsets.UTF_8)
        }

        @Synchronized fun dropOld() {
            val ks = java.security.KeyStore.getInstance(STORE).apply { load(null) }
            if (ks.containsAlias(OLD_ALIAS)) ks.deleteEntry(OLD_ALIAS)
        }
    }
}

/**
 * GitHub over HTTPS for [GitHubReleases]: the system's certificate authorities only (the app's network security config),
 * HTTPS only, the token sent to GitHub's API hosts only ([TOKEN_HOSTS]; a download from GitHub's file store goes without it),
 * redirects never followed here (the client decides who sees the token), a body streamed with its length known, the answer
 * read up to the request's cap ([readCapped]). Nothing is logged, and no error names the address or a header. Blocking: a
 * background thread.
 */
internal object HttpsGitHub : GitHubReleases.Http {
    val TOKEN_HOSTS = setOf("api.github.com", "uploads.github.com")

    /**
     * Why [r] is refused before anything is sent, or null: not HTTPS, or the token (an Authorization header) bound for a host
     * other than GitHub's API. The words never carry the token or the address.
     */
    fun refused(r: GitHubReleases.Request): String? {
        val u = runCatching { java.net.URI(r.url) }.getOrNull() ?: return "refusing a request with a bad address"
        if (!"https".equals(u.scheme, ignoreCase = true) || u.host.isNullOrEmpty()) return "refusing a non-HTTPS request"
        if (r.headers.keys.any { it.equals("Authorization", ignoreCase = true) } && u.host.lowercase(java.util.Locale.ROOT) !in TOKEN_HOSTS)
            return "refusing to send the token beyond GitHub's API"
        return null
    }

    /** [input] read whole, at most [max] bytes (more: an error, nothing kept). */
    fun readCapped(input: java.io.InputStream, max: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(1 shl 16)
        var total = 0L
        while (true) {
            val n = input.read(buf); if (n < 0) break
            total += n
            if (total > max) throw IOException("GitHub's answer is bigger than expected")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    override fun send(r: GitHubReleases.Request): GitHubReleases.Response {
        refused(r)?.let { throw IOException(it) }
        val c = java.net.URL(r.url).openConnection() as? javax.net.ssl.HttpsURLConnection ?: throw IOException("refusing a non-HTTPS request")
        try {
            c.instanceFollowRedirects = false
            try { c.requestMethod = r.method } catch (e: java.net.ProtocolException) {
                // Android's connection takes PATCH (an asset's rename); only a plain JVM's refuses it: the override header there.
                if (r.method != "PATCH") throw e
                c.requestMethod = "POST"; c.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            }
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
            val body = stream?.use { readCapped(it, r.maxBytes) } ?: ByteArray(0)
            return GitHubReleases.Response(code, headers, body)
        } finally { c.disconnect() }
    }
}
