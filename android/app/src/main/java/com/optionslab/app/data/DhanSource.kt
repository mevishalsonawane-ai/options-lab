package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.SecurePrefs
import com.optionslab.ira.dhan.DhanApi
import com.optionslab.ira.dhan.DhanUniverse
import com.optionslab.ira.dhan.Files
import com.optionslab.ira.dhan.GithubPack
import com.optionslab.ira.dhan.Plan
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.io.IOException
import java.net.URL
import java.time.LocalDate
import javax.net.ssl.HttpsURLConnection

/**
 * Dhan as a MARKET-DATA source, downloaded into the app's own storage for Jarvis and the replays to learn from.
 *
 * Dhan is never a broker here: every request goes through [DhanApi.requireAllowed] (the data paths only - candles, expired
 * options, the option chain and its expiry list, quotes), and the scrip master is the one file fetched from Dhan's image
 * host, without the token. No order, position, holding, funds or margin call exists in this app.
 *
 * The client ID and access token are typed by Boss on the Dhan data page and kept in the settings vault ([SecurePrefs],
 * Keystore-encrypted, in no-backup storage; the "dhan." prefix is never in a backup - [com.optionslab.ira.Upkeep.PRIVATE]).
 * The token is sent only as Dhan's "access-token" header to api.dhan.co; it is never logged, shown, put in a message, an
 * error, a notification, a crash report or an exported file. Only its expiry DATE is shown.
 *
 * The data lives under files/dhan ([Files], app-private, plain gzip CSV - market prices only). Downloads run only when Boss
 * taps Download, or - if he turns it on - by themselves on Wi-Fi while charging ([com.optionslab.app.work.DhanWorker]).
 * A data pack can also be imported from the public GitHub repo ([importFromGithub]): plain HTTPS to raw.githubusercontent.com
 * alone ([GithubPack.urlAllowed]), with no token or credential of any kind.
 * IraGoldAlgo has none of it.
 */
object DhanSource {
    private const val K_CLIENT = "dhan.clientId"
    private const val K_TOKEN = "dhan.token"
    const val K_AUTO = "dhan.auto"
    private const val K_IDX_MIN = "dhan.indexMinuteYears"
    private const val K_OPT = "dhan.optionYears"
    private const val K_FUT = "dhan.futures"
    private const val K_STOCKS = "dhan.stocks"
    private const val K_STOCK_MIN = "dhan.stockMinuteYears"
    private const val K_STOCK_OPT = "dhan.stockOptions"
    const val K_LAST = "dhan.last"
    const val K_RESEARCH = "dhan.research"

    private var app: Context? = null

    fun init(context: Context) {
        val c = context.applicationContext
        app = c
        // Older versions kept a learned graph beside the store: its folder and any queued build go, off the main thread.
        kotlin.concurrent.thread(name = "dhan-cleanup") { runCatching { File(c.filesDir, "neuro").deleteRecursively(); listOf("neuro.after", "neuro.full", "neuro.now").forEach { androidx.work.WorkManager.getInstance(c).cancelUniqueWork(it) } } }
    }

    private fun ctx(): Context = app ?: throw IllegalStateException("not started")

    /** The store's folder: app-private files, never shared. */
    fun root(): File = File(ctx().filesDir, "dhan")

    /** The store, or null when nothing is downloaded (or in IraGoldAlgo). */
    fun filesOrNull(): Files? {
        if (com.optionslab.app.BuildConfig.GOLD || app == null) return null
        return Files(root()).takeIf { it.root.isDirectory }
    }

    // ---- the credentials -----------------------------------------------------------------------------------------------

    val configured: Boolean get() = !com.optionslab.app.BuildConfig.GOLD &&
        !SecurePrefs.getString(K_CLIENT).isNullOrEmpty() && !SecurePrefs.getString(K_TOKEN).isNullOrEmpty()

    /** The client ID's last four digits, for the screen ("••••1234"). */
    fun clientShown(): String? = SecurePrefs.getString(K_CLIENT)?.takeLast(4)?.let { "••••$it" }

    /** The day the token stops working (a date only), or null when unknown. */
    fun tokenExpiry(): LocalDate? = SecurePrefs.getString(K_TOKEN)?.let { DhanApi.tokenExpiry(it) }

    /** Keep the client ID and token (typed on the page). An error to show, or null when saved. Nothing is logged. */
    fun save(clientId: String, token: String): String? {
        if (com.optionslab.app.BuildConfig.GOLD) return "IraGoldAlgo keeps no Dhan data."
        val id = clientId.trim(); val tk = token.trim()
        if (!DhanApi.clientIdOk(id)) return "The Dhan client ID is digits only."
        if (!DhanApi.tokenShapeOk(tk)) return "That does not look like a Dhan access token."
        SecurePrefs.putAll(mapOf(K_CLIENT to id, K_TOKEN to tk))
        return null
    }

    /** Remove the client ID and token (the downloaded data stays until deleted). */
    fun forget() { SecurePrefs.putAll(mapOf(K_CLIENT to null, K_TOKEN to null)) }

    // ---- what to download ------------------------------------------------------------------------------------------------

    fun choice(): Plan.Choice = Plan.Choice(
        indexMinuteYears = SecurePrefs.getInt(K_IDX_MIN, 5).coerceIn(0, 5),
        optionYears = SecurePrefs.getInt(K_OPT, 2).coerceIn(0, 5),
        futures = SecurePrefs.getBoolean(K_FUT, true),
        stocks = SecurePrefs.getBoolean(K_STOCKS, true),
        stockMinuteYears = SecurePrefs.getInt(K_STOCK_MIN, 1).coerceIn(0, 5),
        stockOptions = SecurePrefs.getBoolean(K_STOCK_OPT, false),
    )

    fun setChoice(c: Plan.Choice) = SecurePrefs.putAll(mapOf(K_IDX_MIN to c.indexMinuteYears, K_OPT to c.optionYears, K_FUT to c.futures,
        K_STOCKS to c.stocks, K_STOCK_MIN to c.stockMinuteYears, K_STOCK_OPT to c.stockOptions))

    /** The replays (ORB arms, Strategy Lab presets) also read the downloaded days. On unless Boss turns it off. */
    val research: Boolean get() = !com.optionslab.app.BuildConfig.GOLD && SecurePrefs.getBoolean(K_RESEARCH, true)

    // ---- progress ---------------------------------------------------------------------------------------------------------

    data class Progress(val running: Boolean = false, val stage: String = "", val done: Int = 0, val total: Int = 0, val failed: Int = 0) {
        val fraction: Float get() = if (total <= 0) 0f else done.toFloat() / total
    }

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> get() = _progress

    /** The token was refused (expired or wrong): the run stopped; Boss renews it on the page. */
    class TokenRefused : IOException("Dhan refused the access token: it may have expired. Enter a new one on the Dhan data page.")

    private val running = Mutex()

    // ---- the network --------------------------------------------------------------------------------------------------------

    private class Reply(val code: Int, val body: String, val retryAfter: Long?)

    private var lastAt = 0L
    private var lastChainAt = 0L

    private suspend fun gap(chain: Boolean) {
        val now = System.currentTimeMillis()
        val wait = if (chain) lastChainAt + DhanApi.CHAIN_GAP_MS - now else lastAt + DhanApi.DATA_GAP_MS - now
        if (wait > 0) delay(wait)
        if (chain) lastChainAt = System.currentTimeMillis() else lastAt = System.currentTimeMillis()
    }

    /** One POST to a data path. Only [DhanApi.ALLOWED_PATHS] can be reached; the token goes in its header and nowhere else. */
    private fun post(path: String, body: String): Reply {
        val url = DhanApi.requireAllowed(path)
        val client = SecurePrefs.getString(K_CLIENT) ?: throw TokenRefused()
        val token = SecurePrefs.getString(K_TOKEN) ?: throw TokenRefused()
        val c = URL(url).openConnection() as? HttpsURLConnection ?: throw IOException("refusing a non-HTTPS request")
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 20_000
            c.readTimeout = 90_000
            c.instanceFollowRedirects = false
            c.useCaches = false
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("access-token", token)
            c.setRequestProperty("client-id", client)
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
            return Reply(code, text, c.getHeaderField("Retry-After")?.trim()?.toLongOrNull())
        } finally { c.disconnect() }
    }

    /** [post] with the rate limit, retries on 429 / 5xx / a dropped connection (exponential backoff), "" for no data. */
    private suspend fun fetch(path: String, body: String, chain: Boolean = false): String {
        var last: Exception? = null
        for (attempt in 0 until DhanApi.MAX_ATTEMPTS) {
            currentCoroutineContext().ensureActive()
            gap(chain)
            val r = try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { post(path, body) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: TokenRefused) {
                throw e
            } catch (e: IOException) {
                last = e; delay(DhanApi.backoffMs(attempt, null, Math.random())); continue
            }
            if (r.code in 200..299) return r.body
            when (DhanApi.classify(r.code, r.body)) {
                DhanApi.Failure.RETRY -> { last = IOException("Dhan is busy (${r.code})"); delay(DhanApi.backoffMs(attempt, r.retryAfter, Math.random())) }
                DhanApi.Failure.AUTH -> throw TokenRefused()
                DhanApi.Failure.NO_DATA -> return ""
                DhanApi.Failure.REFUSED -> throw IOException("Dhan refused that request (${r.code})")
            }
        }
        throw (last as? IOException) ?: IOException("Dhan did not answer")
    }

    /** The scrip master's few rows the store needs: downloaded again once a week (streamed, never held whole). */
    private suspend fun master(files: Files, today: LocalDate): DhanUniverse.Master? {
        val kept = files.file("master.csv")
        // A kept subset from before the IDX_I fix (no "v2" header) is read again: its index IDs were the options' underlying ones.
        val fresh = kept.isFile && System.currentTimeMillis() - kept.lastModified() < 7L * 24 * 3600 * 1000 &&
            runCatching { kept.bufferedReader().use { it.readLine() }?.trim() == DhanUniverse.MASTER_HEADER }.getOrDefault(false)
        if (fresh) return runCatching { DhanUniverse.readMaster(kept.readText()) }.getOrNull()
        val got = runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                check(DhanApi.urlAllowed(DhanApi.MASTER_URL))
                val c = URL(DhanApi.MASTER_URL).openConnection() as HttpsURLConnection
                try {
                    c.connectTimeout = 20_000; c.readTimeout = 120_000; c.instanceFollowRedirects = false; c.useCaches = false
                    if (c.responseCode !in 200..299) throw IOException("the scrip master was not served (${c.responseCode})")
                    c.inputStream.bufferedReader(Charsets.UTF_8).use { DhanUniverse.parseMaster(it, DhanUniverse.allConstituents().toSet(), today) }
                } finally { c.disconnect() }
            }
        }.getOrNull()
        if (got != null && got.indexIds.isNotEmpty()) files.writeText("master.csv", DhanUniverse.writeMaster(got), gzip = false)
        return got ?: runCatching { DhanUniverse.readMaster(kept.readText()) }.getOrNull()
    }

    // ---- the download -----------------------------------------------------------------------------------------------------

    /**
     * How a run ended, in words for the page (no secret in it): [left] is every chunk still not done after the run - the
     * failed ones and any not reached - so "all done" is said only when nothing is left.
     */
    data class Summary(val fetched: Int, val failed: Int, val left: Int, val stopped: String?) {
        fun say(today: LocalDate): String = "$today: $fetched chunk(s) downloaded" +
            (if (failed > 0) ", $failed failed (tried again next time)" else "") + (if (left > 0) ", $left left" else ", all done") +
            (stopped?.let { " - $it" } ?: "")
    }

    /**
     * Download what is missing (and refresh what reaches today), in the plan's order, resuming where the last run stopped.
     * Throws [TokenRefused] when the token is refused. One run at a time.
     */
    suspend fun run(): Summary {
        check(!com.optionslab.app.BuildConfig.GOLD) { "IraGoldAlgo keeps no Dhan data" }
        if (!configured) throw TokenRefused()
        if (!running.tryLock()) return Summary(0, 0, 0, "already running")
        try {
            val today = Market.today()
            val files = Files(root())
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { files.root.mkdirs(); files.dropPartials() }
            _progress.value = Progress(true, "Reading Dhan's instrument list", 0, 1)
            val master = master(files, today)
            val tasks = Plan.tasks(master, choice(), today)
            // Done = complete: fetched after its window ended (the fetch ledger), on the exchange calendar ([Files.done]).
            val todo = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                Plan.todo(tasks, today) { t -> files.done(t, today) { d -> runCatching { Market.isTradingDay(d) }.getOrDefault(d.dayOfWeek.value <= 5) } }
            }
            val before = tasks.size - todo.size
            var fetched = 0; var failed = 0; var reached = 0
            var stopped: String? = null
            for ((i, t) in todo.withIndex()) {
                currentCoroutineContext().ensureActive()
                _progress.value = Progress(true, stage(t), before + i, tasks.size, failed)
                reached = i + 1
                try {
                    one(files, t, today)
                    fetched++
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: TokenRefused) {
                    stopped = "Dhan refused the token"; throw e
                } catch (e: DhanApi.NotAllowed) {
                    throw e
                } catch (_: Exception) {
                    failed++
                }
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { files.pruneChains(today) }
            // Left: the failed chunks and any the run did not reach (the open windows fetched now are refreshed again anyway).
            val s = Summary(fetched, failed, failed + (todo.size - reached), stopped)
            runCatching { SecurePrefs.put(K_LAST, s.say(today)) }
            return s
        } catch (e: TokenRefused) {
            runCatching { SecurePrefs.put(K_LAST, "${Market.today()}: stopped - Dhan refused the access token (renew it)") }
            throw e
        } finally {
            _progress.value = Progress(false)
            running.unlock()
        }
    }

    private fun stage(t: Plan.Task): String = when (t.kind) {
        Plan.Kind.EXPIRIES -> "${t.symbol} expiry dates"
        Plan.Kind.CHAIN -> "${t.symbol} option chain"
        Plan.Kind.DAY -> "${t.symbol} daily candles from ${t.from.year}"
        Plan.Kind.MIN -> "${t.symbol} minute candles, ${t.from}"
        Plan.Kind.OPT -> "${t.symbol} expired options, ${t.from}"
    }

    /**
     * One chunk: one request, one file (written whole), and its fetch date in the ledger ([Files.markFetched]). A candle or
     * option file already stored (a window still open, or fetched before it ended) is refreshed from its last stored
     * candle's day and the reply merged in ([Files.mergedCandles]): not the whole window again.
     */
    private suspend fun one(files: Files, t: Plan.Task, today: LocalDate) {
        val to = minOf(t.to, today.plusDays(1))
        val io = kotlinx.coroutines.Dispatchers.IO
        val from = if (t.kind == Plan.Kind.DAY || t.kind == Plan.Kind.MIN || t.kind == Plan.Kind.OPT)
            kotlinx.coroutines.withContext(io) { files.resumeFrom(t.path, t.from) } else t.from
        when (t.kind) {
            Plan.Kind.EXPIRIES -> {
                val dates = DhanApi.parseExpiries(fetch(DhanApi.EXPIRY_LIST, DhanApi.expiryListBody(t.id, t.segment)))
                kotlinx.coroutines.withContext(io) { files.addExpiries(t.symbol, dates) }
            }
            Plan.Kind.CHAIN -> {
                val expiry = files.listedExpiries(t.symbol).firstOrNull { !it.isBefore(today) } ?: return
                val text = fetch(DhanApi.OPTION_CHAIN, DhanApi.chainBody(t.id, t.segment, expiry), chain = true)
                if (text.isNotBlank()) kotlinx.coroutines.withContext(io) { files.writeText(t.path, text) }
            }
            Plan.Kind.DAY -> {
                val c = DhanApi.parseCandles(fetch(DhanApi.HISTORICAL, DhanApi.historicalBody(t.id, t.segment, t.instrument, from, to, oi = t.group == Plan.Group.FUT)))
                kotlinx.coroutines.withContext(io) { files.writeText(t.path, files.candlesCsv(files.mergedCandles(t.path, c))); files.markFetched(t.path, today) }
            }
            Plan.Kind.MIN -> {
                val c = DhanApi.parseCandles(fetch(DhanApi.INTRADAY, DhanApi.intradayBody(t.id, t.segment, t.instrument, from, to, oi = t.group == Plan.Group.FUT)))
                kotlinx.coroutines.withContext(io) { files.writeText(t.path, files.candlesCsv(files.mergedCandles(t.path, c))); files.markFetched(t.path, today) }
            }
            Plan.Kind.OPT -> {
                val rows = DhanApi.parseRolling(fetch(DhanApi.ROLLING_OPTION,
                    DhanApi.rollingBody(t.id, t.segment, t.instrument, t.flag, Plan.EXPIRY_CODE, t.offset, t.call, from, to)))
                kotlinx.coroutines.withContext(io) { files.writeText(t.path, files.optionsCsv(files.mergedOptions(t.path, rows))); files.markFetched(t.path, today) }
            }
        }
    }

    // ---- importing a data pack -------------------------------------------------------------------------------------------------

    const val K_IMPORT_LAST = "dhan.importLast"

    /**
     * An import's progress, for the page and the notice: parts done, files and megabytes unpacked. [downloading]: the
     * parts are being downloaded from GitHub ([read] of [size] bytes) before the import itself.
     */
    data class ImportProgress(val running: Boolean = false, val stage: String = "", val part: Int = 0, val parts: Int = 0,
                              val files: Int = 0, val bytes: Long = 0, val read: Long = 0, val size: Long = 0,
                              val downloading: Boolean = false) {
        val fraction: Float get() = if (size <= 0) (if (parts <= 0) 0f else (part - 1).coerceAtLeast(0).toFloat() / parts) else (read.toFloat() / size).coerceIn(0f, 1f)
    }

    private val _importing = MutableStateFlow(ImportProgress())
    val importing: StateFlow<ImportProgress> get() = _importing

    /** Counts the compressed bytes read from the picked file, for the progress bar. */
    private class Counting(input: java.io.InputStream, val onRead: (Long) -> Unit) : java.io.FilterInputStream(input) {
        override fun read(): Int = super.read().also { if (it >= 0) onRead(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) onRead(it.toLong()) }
        override fun skip(n: Long): Long = super.skip(n).also { if (it > 0) onRead(it) }
    }

    /**
     * Import a Dhan data pack from the zip parts Boss picked ([uris], read through the system file picker's grant; nothing
     * is fetched from the network and the token is never read). Every part is streamed and checked ([com.optionslab.ira.dhan.PackImport]):
     * only the store's own files under dhan/, within the size caps, matching pack-manifest.json's checksums when it is
     * there; then merged into files/dhan without replacing a newer file. One run at a time, never beside a download.
     * Returns the summary to show; throws only on cancellation.
     */
    suspend fun importPack(uris: List<android.net.Uri>): String {
        if (com.optionslab.app.BuildConfig.GOLD) return "IraGoldAlgo keeps no Dhan data."
        if (uris.isEmpty()) return "No file was chosen."
        if (!running.tryLock()) return "A Dhan download or import is running: stop it first, then import."
        try {
            val cr = ctx().contentResolver
            val inputs = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                uris.map { u -> PackInput(displayName(u) ?: u.lastPathSegment ?: "", sizeOf(u)) { cr.openInputStream(u) } }
            }
            val result = importInputs(inputs)
            runCatching { SecurePrefs.put(K_IMPORT_LAST, "${Market.today()}: $result") }
            return result
        } finally {
            _importing.value = ImportProgress(false)
            running.unlock()
        }
    }

    /** One part to import: its name (the parts go in name order), its size for the progress (null: unknown) and its bytes. */
    private class PackInput(val name: String, val size: Long?, val open: () -> java.io.InputStream?)

    /**
     * The import itself, over [inputs] (picked files, or the parts downloaded from GitHub - plain files in the app's
     * cache): the caller holds [running]. Returns the summary to show; throws only on cancellation. The staging folder
     * is removed either way.
     */
    private suspend fun importInputs(inputs: List<PackInput>): String {
        val ctxJob = currentCoroutineContext()
        val imp = com.optionslab.ira.dhan.PackImport(Files(root()))
        try {
            return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                // Parts in name order (-01, -02, ...); their sizes for the progress bar.
                val named = inputs.sortedBy { it.name }
                val total = named.sumOf { it.size ?: 0L }.takeIf { named.all { n -> n.size != null } } ?: 0L
                var read = 0L
                val stop = { ctxJob[kotlinx.coroutines.Job]?.isActive == false }
                imp.begin()
                _importing.value = ImportProgress(true, "Checking the pack", 1, named.size, 0, 0, 0, total)
                try {
                    for ((i, n) in named.withIndex()) {
                        val input = n.open() ?: throw com.optionslab.ira.dhan.PackImport.Refused("part ${i + 1} could not be opened")
                        imp.readPart(Counting(input) { read += it }, i + 1, named.size, progress = { pr ->
                            _importing.value = ImportProgress(true, pr.stage, pr.part, pr.parts, pr.files, pr.bytes, read, total)
                        }, cancelled = stop)
                    }
                    _importing.value = _importing.value.copy(stage = "Storing on this phone")
                    val r = imp.finish(progress = { pr -> _importing.value = _importing.value.copy(stage = pr.stage, files = pr.files) }, cancelled = stop)
                    val size = runCatching { Files(root()).bytes() }.getOrDefault(0L)
                    r.say() + ". The Dhan data now takes " + Files.sizeText(size) + "."
                } catch (_: com.optionslab.ira.dhan.PackImport.Cancelled) {
                    imp.abort(); "Import stopped. Files already stored stay; nothing half-written was kept."
                } catch (e: com.optionslab.ira.dhan.PackImport.Refused) {
                    imp.abort(); "Import refused - nothing was changed: ${e.message}"
                } catch (_: java.io.IOException) {
                    imp.abort(); "Import failed (is there room on the phone?) - files already stored stay whole."
                } catch (_: SecurityException) {
                    imp.abort(); "Import failed: the chosen file can no longer be read. Pick it again."
                }
            }
        } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.IO) { runCatching { imp.abort() } }
        }
    }

    // ---- importing the data pack from GitHub ----------------------------------------------------------------------------------

    /**
     * What GitHub offers: the checked index ([index]) with the sha256 of its bytes ([sha], so the run downloads exactly the
     * pack Boss confirmed), or [None] with a sentence to show.
     */
    sealed class GithubOffer {
        class Ready(val index: GithubPack.Index, val sha: String) : GithubOffer()
        class None(val message: String) : GithubOffer()
    }

    private const val NO_PACK = "No data pack on GitHub right now."

    /** The pack (or a part of it) is not on GitHub: 404. */
    private class NotOnGithub : IOException(NO_PACK)

    /** The parts are downloaded here (app cache: Android may reclaim it, and a part is then fetched again). */
    private fun packCache(): File = File(ctx().cacheDir, "dhan-pack")

    /**
     * One GET to the data pack on GitHub: only [GithubPack.urlAllowed] URLs (HTTPS, raw.githubusercontent.com, the pack's
     * folder, its index and part names), checked by the system's certificate authorities (network_security_config) like any
     * other host. No token, no credential, no cookie: the only headers are the agent, "identity" (so a Range counts the
     * file's own bytes) and the Range itself. Redirects are not followed.
     */
    private fun githubOpen(url: String, from: Long = 0): HttpsURLConnection {
        if (!GithubPack.urlAllowed(url)) throw IOException("refusing a download outside the data pack")
        val u = URL(url)
        if (u.protocol != "https" || u.host != GithubPack.HOST || u.port != -1 || u.userInfo != null || u.query != null)
            throw IOException("refusing a download outside the data pack")
        val c = u.openConnection() as? HttpsURLConnection ?: throw IOException("refusing a non-HTTPS request")
        c.connectTimeout = 20_000
        c.readTimeout = 60_000
        c.instanceFollowRedirects = false
        c.useCaches = false
        c.setRequestProperty("User-Agent", "IraAlgo")
        c.setRequestProperty("Accept-Encoding", "identity")
        GithubPack.rangeHeader(from)?.let { c.setRequestProperty("Range", it) }
        return c
    }

    /** At most [cap] bytes of [input] (closed here), or null when it holds more. */
    private fun readCapped(input: java.io.InputStream, cap: Int): ByteArray? = input.use { s ->
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val r = s.read(buf); if (r < 0) break
            if (out.size() + r > cap) return null
            out.write(buf, 0, r)
        }
        out.toByteArray()
    }

    /** Read and check the pack's index on GitHub (64 KB at most). Blocking: call off the main thread. Never throws. */
    fun githubIndex(): GithubOffer {
        if (com.optionslab.app.BuildConfig.GOLD) return GithubOffer.None("IraGoldAlgo keeps no Dhan data.")
        return try {
            val c = githubOpen(GithubPack.INDEX_URL)
            try {
                when (val code = c.responseCode) {
                    404 -> GithubOffer.None(NO_PACK)
                    in 200..299 -> {
                        val bytes = readCapped(c.inputStream, GithubPack.MAX_INDEX_BYTES)
                        if (bytes == null) GithubOffer.None("The data pack's list on GitHub is too large; it was not read.")
                        else GithubOffer.Ready(GithubPack.parseIndex(String(bytes, Charsets.UTF_8)), GithubPack.sha256(java.io.ByteArrayInputStream(bytes)))
                    }
                    else -> GithubOffer.None("GitHub did not serve the data pack's list ($code). Try again later.")
                }
            } finally { c.disconnect() }
        } catch (e: GithubPack.Bad) {
            GithubOffer.None("The data pack on GitHub cannot be used: ${e.message}")
        } catch (_: Exception) {
            GithubOffer.None("Could not reach GitHub. Check the connection and try again.")
        }
    }

    /**
     * Download the data pack from GitHub - only if its index is still the one Boss confirmed ([indexSha]) - into the app's
     * cache, part by part (resumed with a Range where a part stopped; each checked against its listed size and sha256;
     * retried with backoff), then import the parts exactly as picked ones ([importInputs]) and delete them. Free space for
     * the parts and their unpacking is checked first. Holds the download/import lock throughout. The Dhan token is never
     * read here. Returns the summary to show; throws only on cancellation (the parts downloaded so far stay in the cache,
     * so the next run resumes).
     */
    suspend fun importFromGithub(indexSha: String): String {
        if (com.optionslab.app.BuildConfig.GOLD) return "IraGoldAlgo keeps no Dhan data."
        if (!running.tryLock()) return "A Dhan download or import is running: stop it first, then import."
        try {
            _importing.value = ImportProgress(true, "Reading the data pack's list on GitHub", downloading = true)
            val result = when (val offer = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { githubIndex() }) {
                is GithubOffer.None -> offer.message
                is GithubOffer.Ready ->
                    if (offer.sha != indexSha) "The data pack on GitHub changed since it was confirmed. Tap Import from GitHub again."
                    else githubRun(offer.index)
            }
            runCatching { SecurePrefs.put(K_IMPORT_LAST, "${Market.today()}: from GitHub: $result") }
            return result
        } finally {
            _importing.value = ImportProgress(false)
            running.unlock()
        }
    }

    private suspend fun githubRun(index: GithubPack.Index): String {
        val io = kotlinx.coroutines.Dispatchers.IO
        val dir = packCache()
        // Whatever an older pack left in the cache goes; this pack's parts (whole or partial) stay to resume.
        val cached = kotlinx.coroutines.withContext(io) {
            dir.mkdirs()
            for (s in GithubPack.strays(dir.list()?.toList() ?: emptyList(), index)) File(dir, s).deleteRecursively()
            index.parts.sumOf { p -> File(dir, p.name).length().coerceAtMost(p.size) }
        }
        val need = GithubPack.spaceNeeded(index, cached)
        val free = kotlinx.coroutines.withContext(io) { minOf(dir.usableSpace, ctx().filesDir.usableSpace) }
        if (free < need) return "Not enough room on this phone: the pack needs about ${Files.sizeText(need)} free, and ${Files.sizeText(free)} is free. Nothing was imported."
        val got = ArrayList<File>()
        var before = 0L
        try {
            for ((i, p) in index.parts.withIndex()) {
                got += downloadPart(dir, p, i + 1, index.parts.size, before, index.totalBytes)
                before += p.size
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: NotOnGithub) {
            return "A part of the data pack is not on GitHub right now. Nothing was imported; try again later."
        } catch (_: GithubPack.Bad) {
            return "A part downloaded from GitHub did not match its checksum, even after trying again. Nothing was imported."
        } catch (_: IOException) {
            return "The download from GitHub stopped (connection or room on the phone). Nothing was imported; tap Import from GitHub to resume where it stopped."
        }
        try {
            val inputs = got.map { f -> PackInput(f.name, f.length()) { f.inputStream() } }
            return "${index.parts.size} part(s) downloaded and checked. " + importInputs(inputs)
        } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + io) { runCatching { dir.deleteRecursively() } }
        }
    }

    /**
     * Part [n] of [parts] into [dir]: resumed from what is cached, until it is whole and matches its listed sha256. A
     * dropped connection or a mismatch is tried again with backoff, [GithubPack.MAX_ATTEMPTS] times in all.
     */
    private suspend fun downloadPart(dir: File, p: GithubPack.Part, n: Int, parts: Int, before: Long, total: Long): File {
        val io = kotlinx.coroutines.Dispatchers.IO
        val f = File(dir, p.name)
        var last: IOException? = null
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val have = kotlinx.coroutines.withContext(io) {
                GithubPack.resumeFrom(f.length(), p.size).also { if (it == 0L && f.exists()) f.delete() }
            }
            if (have == p.size) {
                _importing.value = ImportProgress(true, "Checking part $n of $parts", n, parts, 0, 0, before + have, total, downloading = true)
                if (kotlinx.coroutines.withContext(io) { GithubPack.partOk(f, p) }) return f
                kotlinx.coroutines.withContext(io) { f.delete() }
                last = GithubPack.Bad("part $n did not match its checksum")
            } else {
                try {
                    fetchPart(f, p, have, n, parts, before, total)
                    continue                                 // whole now: checked at the top
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: NotOnGithub) {
                    throw e
                } catch (e: IOException) {
                    last = e
                }
            }
            if (++attempt >= GithubPack.MAX_ATTEMPTS) throw last ?: IOException("part $n could not be downloaded")
            delay(GithubPack.backoffMs(attempt - 1, Math.random()))
        }
    }

    /**
     * One request for part [p] from byte [have]: appended on a 206 that continues exactly there, written afresh on a 200.
     * Returns only when the file holds all [GithubPack.Part.size] bytes; never writes past it. Cancelling closes the
     * connection at once (a hung read does not run out its timeout).
     */
    private suspend fun fetchPart(f: File, p: GithubPack.Part, have: Long, n: Int, parts: Int, before: Long, total: Long) {
        val job = currentCoroutineContext()[kotlinx.coroutines.Job]
        val c = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { githubOpen(GithubPack.urlFor(p.name), have) }
        kotlinx.coroutines.coroutineScope {
            // Cancelling the run closes the connection at once (as Net does), so a hung read ends now.
            val closer = launch(kotlinx.coroutines.Dispatchers.Unconfined) {
                try { kotlinx.coroutines.awaitCancellation() } finally { runCatching { c.disconnect() } }
            }
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val code = c.responseCode
                    val append = when (code) {
                        206 -> {
                            if (!GithubPack.contentRangeOk(c.getHeaderField("Content-Range"), have, p.size)) { f.delete(); throw IOException("GitHub sent another range") }
                            true
                        }
                        200 -> {
                            val len = c.contentLengthLong
                            if (len >= 0 && len != p.size) throw GithubPack.Bad("part $n on GitHub is not the size its list says")
                            false
                        }
                        404 -> throw NotOnGithub()
                        416 -> { f.delete(); throw IOException("the cached part was out of step") }
                        else -> throw IOException("GitHub answered $code")
                    }
                    var done = if (append) have else 0L
                    var shown = 0L
                    java.io.FileOutputStream(f, append).use { out ->
                        c.inputStream.use { inp ->
                            val buf = ByteArray(1 shl 16)
                            while (true) {
                                if (job?.isActive == false) throw kotlinx.coroutines.CancellationException("stopped")
                                val r = inp.read(buf); if (r < 0) break
                                if (done + r > p.size) { out.close(); f.delete(); throw GithubPack.Bad("part $n is larger than its list says") }
                                out.write(buf, 0, r); done += r
                                val now = System.currentTimeMillis()
                                if (now - shown > 500) {
                                    shown = now
                                    _importing.value = ImportProgress(true, "Downloading part $n of $parts from GitHub", n, parts, 0, 0, before + done, total, downloading = true)
                                }
                            }
                        }
                        out.fd.sync()
                    }
                    if (done != p.size) throw IOException("the connection dropped")
                }
            } finally {
                closer.cancel()
                runCatching { c.disconnect() }
            }
        }
    }

    private fun displayName(u: android.net.Uri): String? = runCatching {
        ctx().contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun sizeOf(u: android.net.Uri): Long? = runCatching {
        ctx().contentResolver.query(u, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    }.getOrNull()

    // ---- reading for the replays ----------------------------------------------------------------------------------------------

    /**
     * The downloaded days of [u] before [before] as the app's sessions (index and option minutes), oldest first - for the
     * ORB arms' and Strategy Lab presets' replays, which read them ahead of the bundled and harvested days. Empty when
     * nothing is downloaded, Boss turned it off, or in IraGoldAlgo. The lot is the dated lot history's ([com.optionslab.engine.Lots]);
     * a day before it keeps the index only.
     */
    fun researchSessions(u: String, before: LocalDate): Sequence<com.optionslab.engine.Session> {
        if (!research) return emptySequence()
        val files = filesOrNull() ?: return emptySequence()
        val ix = DhanUniverse.index(u) ?: return emptySequence()
        return com.optionslab.ira.dhan.ExpiredOptions.sessions(files, u, ix.flag, before,
            lot = { d -> runCatching { com.optionslab.engine.Lots.lotSizeOn(u, d) }.getOrNull() })
    }

    // ---- deleting -------------------------------------------------------------------------------------------------------------

    /** How long [deleteData] waits for a running download or import to stop. */
    private const val STOP_WAIT_MS = 60_000L

    /**
     * Delete everything downloaded (the token stays until forgotten). Stops any download and import first and
     * WAITS until each has let go (their locks), so nothing they were writing reappears after the delete. False when they
     * did not stop in time (nothing deleted) or the folder could not be removed.
     */
    suspend fun deleteData(): Boolean {
        app?.let { runCatching { com.optionslab.app.work.DhanWorker.stopNow(it) } }
        app?.let { runCatching { com.optionslab.app.work.DhanImportWorker.stop(it) } }
        val ok = kotlinx.coroutines.withTimeoutOrNull(STOP_WAIT_MS) {
            running.lock()
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    Files(root()).deleteAll().also {
                        root().parentFile?.let { p -> File(p, root().name + ".import").deleteRecursively() }
                        runCatching { packCache().deleteRecursively() }      // parts of a GitHub download that was stopped
                    }
                }
            } finally { running.unlock() }
        }
        return ok == true
    }

    /**
     * Erase: the data, the token and the automatic download. Not suspending (it is part of erasing everything): the work is
     * stopped and the folder deleted at once, then deleted again once the stopped work has let go ([deleteData]).
     */
    fun wipe() {
        app?.let { runCatching { com.optionslab.app.work.DhanWorker.auto(it, false) } }
        app?.let { runCatching { com.optionslab.app.work.DhanWorker.stopNow(it) } }
        app?.let { runCatching { com.optionslab.app.work.DhanImportWorker.stop(it) } }
        runCatching { Files(root()).deleteAll() }
        runCatching { packCache().deleteRecursively() }
        runCatching { forget() }
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch { runCatching { deleteData() } }
    }
}
