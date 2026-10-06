package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.SecurePrefs
import com.optionslab.ira.dhan.DhanApi
import com.optionslab.ira.dhan.DhanUniverse
import com.optionslab.ira.dhan.Files
import com.optionslab.ira.dhan.Plan
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    fun init(context: Context) { app = context.applicationContext }

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
        val fresh = kept.isFile && System.currentTimeMillis() - kept.lastModified() < 7L * 24 * 3600 * 1000
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

    /** How a run ended, in words for the page (no secret in it). */
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
            val todo = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Plan.todo(tasks, today) { files.has(it) } }
            val before = tasks.size - todo.size
            var fetched = 0; var failed = 0
            var stopped: String? = null
            for ((i, t) in todo.withIndex()) {
                currentCoroutineContext().ensureActive()
                _progress.value = Progress(true, stage(t), before + i, tasks.size, failed)
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
            val s = Summary(fetched, failed, 0, stopped)
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

    /** One chunk: one request, one file (written whole). */
    private suspend fun one(files: Files, t: Plan.Task, today: LocalDate) {
        val to = minOf(t.to, today.plusDays(1))
        val io = kotlinx.coroutines.Dispatchers.IO
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
                val c = DhanApi.parseCandles(fetch(DhanApi.HISTORICAL, DhanApi.historicalBody(t.id, t.segment, t.instrument, t.from, to, oi = t.group == Plan.Group.FUT)))
                kotlinx.coroutines.withContext(io) { files.writeText(t.path, files.candlesCsv(c)) }
            }
            Plan.Kind.MIN -> {
                val c = DhanApi.parseCandles(fetch(DhanApi.INTRADAY, DhanApi.intradayBody(t.id, t.segment, t.instrument, t.from, to, oi = t.group == Plan.Group.FUT)))
                kotlinx.coroutines.withContext(io) { files.writeText(t.path, files.candlesCsv(c)) }
            }
            Plan.Kind.OPT -> {
                val rows = DhanApi.parseRolling(fetch(DhanApi.ROLLING_OPTION,
                    DhanApi.rollingBody(t.id, t.segment, t.instrument, t.flag, Plan.EXPIRY_CODE, t.offset, t.call, t.from, to)))
                kotlinx.coroutines.withContext(io) { files.writeText(t.path, files.optionsCsv(rows)) }
            }
        }
    }

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

    /** Delete everything downloaded (the token stays until forgotten). Stops any download first. */
    fun deleteData(): Boolean {
        app?.let { runCatching { com.optionslab.app.work.DhanWorker.stopNow(it) } }
        return Files(root()).deleteAll()
    }

    /** Erase: the data, the token and the automatic download. */
    fun wipe() {
        app?.let { runCatching { com.optionslab.app.work.DhanWorker.auto(it, false) } }
        runCatching { deleteData() }
        runCatching { forget() }
    }
}
