package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.security.Vault
import com.optionslab.engine.Kite
import com.optionslab.engine.KiteTicks
import com.optionslab.engine.Right
import com.optionslab.ira.LiveRecord
import com.optionslab.ira.OrderFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.zip.GZIPOutputStream

/**
 * The Data Brain's live recording (L8 Feedback; Boss, 12 Oct: "record everything we can"; the record shapes are
 * [LiveRecord]'s). Two things, both from what the app ALREADY holds in memory - the price stream's last ticks and their
 * minute paths ([KiteStream]), the day's option list ([Broker.cachedInstruments]), the last option chain read
 * ([Market.chainInMemory]) and the order flow's followed futures ([OrderFlowLive.entries]); it never reads the network:
 *
 *  - At every strategy signal ([signal]: the arms' entry decisions), every live order sent ([order]) and every fill, paper
 *    or live ([fill]): a snapshot of the market - the nearest-expiry chain at ATM +/-10 (LTP, OI, OI change, IV), the traded
 *    option's best bid/ask and five-level book, the index, its future and the basis, VIX - and the order's timing. The
 *    caller's thread (the order path, the arms' ticks) only copies the traded option's last tick from memory and adds one
 *    item to a bounded queue ([LiveRecord.Queue]: a short lock, never a wait, never the settings vault, a file or the
 *    network); the snapshot is built and written on this object's own IO scope.
 *  - While NSE is open, once a minute: NIFTY's and BANKNIFTY's minute OHLC, their futures and VIX, and the chain at ATM +/-10.
 *
 * Files: noBackupFilesDir/recordings/YYYY-MM-DD/ ([LiveRecord.MARKET_FILE] plain gzip; [LiveRecord.TRADES_FILE] Boss's own
 * records, encrypted with [Vault]; the tick zone's per-second files beside them, [TickZoneRecorder]). Kept [keepDays] days (60 by
 * default), a day's minute rows stop at 20 MB. Recording never
 * places, changes, delays or refuses an order: every step is caught, and a failure is at most a gap line.
 */
object LiveRecorder {
    private const val DIR = "recordings"
    private const val K_ON = "recordings.on"
    private const val K_KEEP = "recordings.keep"

    @Volatile private var root: File? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = LiveRecord.Queue<Pending>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val writeLock = Mutex()
    @Volatile private var loop: Job? = null

    /** When this process began recording (the health check gives a fresh start [LiveRecord.STALL_MS]). */
    @Volatile private var startedMs = 0L
    /** The last write to any recording file (epoch ms; memory). */
    @Volatile var lastWriteMs: Long? = null
        private set

    // The settings, read in the background only and kept here (the order path never reads them).
    @Volatile private var onKept: Boolean? = null
    @Volatile private var keepKept: Int? = null

    fun enabled(): Boolean = !com.optionslab.app.BuildConfig.GOLD

    fun init(context: Context) {
        if (!enabled()) return
        root = File(context.applicationContext.noBackupFilesDir, DIR)
        startedMs = System.currentTimeMillis()
        if (loop?.isActive != true) loop = scope.launch { writer() }
        scope.launch { minutes() }
    }

    /** Boss's switch (on by default). Read off the main thread. */
    var on: Boolean
        get() = onKept ?: runCatching { SecurePrefs.getBoolean(K_ON, true) }.getOrDefault(true).also { onKept = it }
        set(v) { onKept = v; runCatching { SecurePrefs.put(K_ON, v) } }

    /** Days kept ([LiveRecord.KEEP_CHOICES]; 60 by default). Read off the main thread. */
    var keepDays: Int
        get() = keepKept ?: LiveRecord.keepDays(runCatching { SecurePrefs.getInt(K_KEEP, LiveRecord.DEFAULT_KEEP_DAYS) }.getOrNull()).also { keepKept = it }
        set(v) { val d = LiveRecord.keepDays(v); keepKept = d; runCatching { SecurePrefs.put(K_KEEP, d) }; scope.launch { runCatching { rotate(today()) } } }

    // ---- the hooks: memory only on the caller's thread -----------------------------------------------------------------

    private class Pending(
        val kind: LiveRecord.Kind, val atMs: Long, val arm: String, val symbol: String, val underlying: String?, val side: String?,
        val qty: Int?, val price: Double?, val intended: Double?, val paper: Boolean, val exit: Boolean,
        val tick: KiteStream.Seen?, val timing: LiveRecord.Timing, val note: String? = null,
    )

    /** The traded option's last tick, if the stream has it (two map looks). */
    private fun tickOf(symbol: String, exchange: String = "NFO"): KiteStream.Seen? =
        Broker.tokenKnown("$exchange:$symbol")?.let { KiteStream.seen(it) }

    private fun offer(p: Pending) {
        if (root == null || onKept == false) return
        queue.offer(p)
        wake.trySend(Unit)
    }

    /**
     * An arm decided to enter: [arm] (its name), the option [symbol], [underlying] when known, [side] ("BUY"/"SELL" or
     * "CE"/"PE"), the [price] it saw, [paper] or live. Memory only; never throws.
     */
    fun signal(arm: String, symbol: String, underlying: String? = null, side: String? = null, price: Double? = null, paper: Boolean = true,
               exchange: String = "NFO") {
        try {
            val now = System.currentTimeMillis()
            offer(Pending(LiveRecord.Kind.SIGNAL, now, arm, symbol, underlying, side, null, price, null, paper, false, tickOf(symbol, exchange),
                LiveRecord.Timing(decidedMs = now)))
        } catch (_: Throwable) {
        }
    }

    /** A live order Zerodha accepted ([FillSlippage.sent]): the market as it was sent. Memory only; never throws. */
    fun order(o: Kite.Order, exit: Boolean, arm: String?, intended: Double?, sentMs: Long) {
        try {
            offer(Pending(LiveRecord.Kind.ORDER, System.currentTimeMillis(), arm ?: o.tag, o.tradingSymbol, null, o.side.name, o.quantity,
                o.price, intended, false, exit, tickOf(o.tradingSymbol, o.exchange),
                LiveRecord.Timing(sentMs = sentMs, exchMs = null), note = o.orderType))
        } catch (_: Throwable) {
        }
    }

    /** A fill ([FillSlippage]: live from Zerodha's answer, paper from the sandbox). Memory only; never throws. */
    fun fill(symbol: String, side: String, exit: Boolean, qty: Int, avg: Double, intended: Double?, paper: Boolean, arm: String?,
             sentMs: Long? = null, note: String? = null) {
        try {
            val now = System.currentTimeMillis()
            offer(Pending(LiveRecord.Kind.FILL, now, arm ?: if (paper) "Paper" else "Live", symbol, null, side, qty, avg, intended, paper, exit,
                tickOf(symbol), LiveRecord.Timing(sentMs = sentMs, filledMs = now), note))
        } catch (_: Throwable) {
        }
    }

    // ---- the writer ----------------------------------------------------------------------------------------------------

    private suspend fun writer() {
        for (x in wake) runCatching { drain() }
    }

    /** Every queued snapshot built from memory and written, encrypted, to today's trades file. */
    internal suspend fun drain() {
        val items = queue.drain()
        if (items.isEmpty()) return
        val lines = items.map { p -> runCatching { LiveRecord.snapshot(build(p)) }.getOrElse { LiveRecord.gap(p.atMs, "snapshot ${p.symbol}", it.javaClass.simpleName) } }
        writeTrades(today(), lines)
    }

    private fun build(p: Pending): LiveRecord.Snapshot {
        val parsed = LiveRecord.parseOption(p.symbol)
        val und = p.underlying ?: parsed?.first
        val m = und?.let { market(it, System.currentTimeMillis()) }
        val tick = p.tick ?: parsed?.let { (u, k, r) -> m?.chainIns?.firstOrNull { it.strike == k && it.right.name == r && it.name == u } }
            ?.let { KiteStream.seen(it.token) }
        return LiveRecord.Snapshot(p.kind, p.atMs, p.arm, p.symbol, und, p.side, p.qty, p.price, p.intended, p.paper, p.exit,
            m?.spot, m?.future, m?.vix, m?.expiry, m?.legs.orEmpty(), tick?.let { book(it, p.atMs) },
            p.timing.copy(exchMs = p.timing.exchMs ?: tick?.tick?.exchangeTime?.let { if (it < 10_000_000_000L) it * 1000 else it }), p.note)
    }

    private fun book(s: KiteStream.Seen, atMs: Long): LiveRecord.Book {
        val t = s.tick
        fun lv(l: List<KiteTicks.Level>?) = l.orEmpty().take(5).map { LiveRecord.Level(it.price, it.qty, it.orders) }
        return LiveRecord.Book(t.last.takeIf { it > 0 }, t.bid, t.ask, t.bidQty, t.askQty, lv(t.depth?.bids), lv(t.depth?.asks), (atMs - s.at).coerceAtLeast(0))
    }

    /** One index's market from memory: spot, future, VIX, the nearest expiry and its chain at ATM +/-10. */
    private class Mkt(val spot: Double?, val future: Double?, val vix: Double?, val expiry: LocalDate?, val legs: List<LiveRecord.Leg>,
                      val chainIns: List<Kite.Instrument>, val missing: List<String>)

    /** The day's option list by index, nearest expiry only (rebuilt when the list or the day changes). */
    @Volatile private var insMemo: Triple<Any, LocalDate, Map<String, List<Kite.Instrument>>>? = null

    private fun nearest(und: String, day: LocalDate): List<Kite.Instrument> {
        val all = runCatching { Broker.cachedInstruments() }.getOrNull() ?: return emptyList()
        val memo = insMemo?.takeIf { it.first === all && it.second == day } ?: Triple<Any, LocalDate, Map<String, List<Kite.Instrument>>>(all, day,
            all.filter { !it.expiry.isBefore(day) && (it.right == Right.CE || it.right == Right.PE) }.groupBy { it.name }
                .mapValues { (_, l) -> l.minOf { it.expiry }.let { e -> l.filter { it.expiry == e } } }).also { insMemo = it }
        return memo.third[und].orEmpty()
    }

    /** First OI seen today per contract ("und|expiry|strike|right" -> (oi, "HH:mm")). */
    private val firstOi = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String>>()
    @Volatile private var firstOiDay: LocalDate? = null

    private fun market(und: String, nowMs: Long): Mkt {
        val missing = ArrayList<String>()
        val day = today()
        if (firstOiDay != day) { firstOi.clear(); firstOiDay = day }
        val spot = Broker.indexToken(und)?.let { KiteStream.seen(it) }?.tick?.last?.takeIf { it > 0 }
        val future = runCatching { OrderFlowLive.entries() }.getOrDefault(emptyList())
            .firstOrNull { it.name == und && it.role == OrderFlow.Role.FUTURE }?.let { KiteStream.seen(it.token) }?.tick?.last?.takeIf { it > 0 }
        val vix = Broker.indexToken("INDIAVIX")?.let { KiteStream.seen(it) }?.tick?.last?.takeIf { it > 0 }
        if (spot == null) missing += "spot"
        if (future == null) missing += "future"
        if (vix == null) missing += "VIX"
        val ins = nearest(und, day)
        val mem = runCatching { Market.chainInMemory(und) }.getOrNull()
        val expiry = ins.firstOrNull()?.expiry ?: mem?.second?.expiry
        val raws = ArrayList<LiveRecord.Raw>()
        val ref = spot ?: mem?.second?.spot
        if (ref != null) {
            // The stream's ticks first (with their age), else the last chain read's price and OI.
            val near = LiveRecord.EACH_SIDE + 2
            val strikes = com.optionslab.ira.MarketRecord.around(ins.map { it.strike }, ref, near).toSet()
            val seen = HashSet<Pair<Double, String>>()
            for (o in ins) if (o.strike in strikes) KiteStream.seen(o.token)?.takeIf { it.tick.last > 0 }?.let { s ->
                raws += LiveRecord.Raw(o.strike, o.right.name, s.tick.last, s.tick.oi.takeIf { it > 0 }, (nowMs - s.at).coerceAtLeast(0), "tick")
                seen += o.strike to o.right.name
            }
            val lc = mem?.second?.takeIf { expiry == null || it.expiry == expiry }
            val readAt = mem?.first ?: nowMs
            if (lc != null) for (s in lc.series) {
                if (s.size == 0 || (s.strike to s.right.name) in seen || (s.right != Right.CE && s.right != Right.PE)) continue
                raws += LiveRecord.Raw(s.strike, s.right.name, s.close[s.size - 1], s.oi[s.size - 1].takeIf { it > 0 }, (nowMs - readAt).coerceAtLeast(0), "chain")
            }
        }
        if (raws.isEmpty()) missing += "chain"
        val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), com.optionslab.ira.MarketRecord.IST)
        val hm = String.format(java.util.Locale.ENGLISH, "%02d:%02d", now.hour, now.minute)
        val legs = LiveRecord.chain(raws, ref, now, expiry) { k, r ->
            val key = "$und|$expiry|$k|$r"
            firstOi[key] ?: raws.firstOrNull { it.strike == k && it.right == r }?.oi?.let { oi -> (oi to hm).also { firstOi.putIfAbsent(key, it) } }
        }
        return Mkt(spot, future, vix, expiry, legs, ins, missing)
    }

    // ---- once a minute while NSE is open -------------------------------------------------------------------------------

    private suspend fun minutes() {
        while (true) {
            val now = System.currentTimeMillis()
            // A few seconds past each minute, so the minute just ended is whole in the stream's paths.
            delay(60_000L - Math.floorMod(now, 60_000L) + 3_000L)
            runCatching { if (on && Market.isOpen()) minute(System.currentTimeMillis()) }
            runCatching { drain() }
        }
    }

    @Volatile private var rotatedOn: LocalDate? = null

    /** The minute that ended before [nowMs]: one row per index (internal: the tests call it). */
    internal suspend fun minute(nowMs: Long) {
        val day = today()
        if (rotatedOn != day) { rotatedOn = day; runCatching { rotate(day) } }
        val start = nowMs - Math.floorMod(nowMs, 60_000L) - 60_000L
        val t = LocalDateTime.ofInstant(Instant.ofEpochMilli(start), com.optionslab.ira.MarketRecord.IST).toLocalTime().withSecond(0).withNano(0)
        val lines = LiveRecord.MINUTE_INDICES.map { ix ->
            val m = market(ix, nowMs)
            val bar = Broker.indexToken(ix)?.let { KiteStream.minuteBar(it, start) }?.let { LiveRecord.Bar(it[0], it[1], it[2], it[3]) }
            LiveRecord.minute(LiveRecord.Minute(t, ix, bar, m.spot, m.future, m.vix, m.expiry, m.legs, m.missing + if (bar == null) listOf("bar") else emptyList()))
        }
        writeMarket(day, lines)
    }

    // ---- the files -----------------------------------------------------------------------------------------------------

    private fun today(): LocalDate = runCatching { Market.today() }.getOrElse { LocalDate.now(com.optionslab.ira.MarketRecord.IST) }

    private fun dayDir(day: LocalDate): File? = root?.let { File(it, day.toString()) }

    private fun gz(text: String): ByteArray = java.io.ByteArrayOutputStream().also { b -> GZIPOutputStream(b).use { it.write(text.toByteArray(Charsets.UTF_8)) } }.toByteArray()

    private suspend fun writeMarket(day: LocalDate, lines: List<String>) = writeLock.withLock {
        val d = dayDir(day) ?: return@withLock
        d.mkdirs()
        // The day's budget counts the market and trade files; the tick zone's raw seconds have their own ([TickZoneRecorder]).
        if (LiveRecord.overDayBudget(dirBytes(d) - File(d, com.optionslab.ira.TickZone.TICKS_FILE).length())) return@withLock
        val f = File(d, LiveRecord.MARKET_FILE)
        val head = if (f.exists()) emptyList() else listOf(LiveRecord.header(LiveRecord.MARKET_FILE, day, app()))
        java.io.FileOutputStream(f, true).use { it.write(gz((head + lines).joinToString("") { l -> l + "\n" })) }
        lastWriteMs = System.currentTimeMillis()
    }

    private suspend fun writeTrades(day: LocalDate, lines: List<String>) = writeLock.withLock {
        val d = dayDir(day) ?: return@withLock
        d.mkdirs()
        val f = File(d, LiveRecord.TRADES_FILE)
        val head = if (f.exists()) emptyList() else listOf(LiveRecord.header(LiveRecord.TRADES_FILE, day, app()))
        val blob = Vault.encrypt(gz((head + lines).joinToString("") { l -> l + "\n" }))
        java.io.FileOutputStream(f, true).use { it.write((android.util.Base64.encodeToString(blob, android.util.Base64.NO_WRAP) + "\n").toByteArray(Charsets.US_ASCII)) }
        lastWriteMs = System.currentTimeMillis()
    }

    private fun app(): String = runCatching { com.optionslab.app.BuildConfig.VERSION_NAME }.getOrDefault("")

    private fun dirBytes(d: File): Long = d.listFiles()?.sumOf { it.length() } ?: 0L

    private val DAY = Regex("^\\d{4}-\\d{2}-\\d{2}$")

    /** The day folders kept, oldest first. */
    internal fun days(): List<Pair<LocalDate, File>> = (root?.listFiles() ?: emptyArray()).filter { it.isDirectory && DAY.matches(it.name) }
        .mapNotNull { f -> runCatching { LocalDate.parse(f.name) }.getOrNull()?.let { it to f } }.sortedBy { it.first }

    /** The days past [keepDays] (and over the folder's cap) removed; never today's. */
    internal suspend fun rotate(today: LocalDate) = writeLock.withLock {
        val all = days()
        val plan = LiveRecord.rotate(all.map { LiveRecord.DayDir(it.first, dirBytes(it.second)) }, today, keepDays)
        for (d in plan.delete) all.firstOrNull { it.first == d }?.second?.deleteRecursively()
    }

    /** The card's figures (lists the folder; nothing decrypted). Off the main thread. */
    fun status(): LiveRecord.Status {
        val all = days()
        val last = lastWriteMs ?: all.flatMap { it.second.listFiles().orEmpty().toList() }.maxOfOrNull { it.lastModified() }?.takeIf { it > 0 }
        return LiveRecord.Status(all.size, all.sumOf { dirBytes(it.second) }, last, keepDays, queue.dropped)
    }

    /** Recording stopped while NSE is open and the watch runs ([alive]): an open problem; otherwise it is cleared. */
    fun healthCheck(alive: Boolean, nowMs: Long = System.currentTimeMillis()) {
        if (!enabled() || root == null) return
        val open = runCatching { Market.isOpen() }.getOrDefault(false)
        if (LiveRecord.stalled(open, alive, on, lastWriteMs, startedMs, nowMs)) {
            val mins = (nowMs - maxOf(lastWriteMs ?: 0L, startedMs)) / 60_000
            ProblemsHost.report(PROBLEM, "Live recording", com.optionslab.ira.OpenProblems.Severity.WARN,
                "Market recording stopped while the market is open: nothing written for $mins minutes. Today's minutes are being lost.",
                "Open the app; if it repeats, the recorder needs a developer fix (trading is not affected)")
        } else ProblemsHost.clear(PROBLEM)
    }

    const val PROBLEM = "recording:stopped"

    /**
     * Every kept day in one zip in the cache's exports/ folder (the share sheet's only folder), as stored: the market files
     * plain, Boss's trade files still encrypted. Only the recordings folder is read: no setting, token or key. A worker thread.
     */
    fun exportZip(ctx: Context): File {
        val dir = File(ctx.cacheDir, "exports/recordings").apply { mkdirs() }
        dir.listFiles()?.forEach { runCatching { it.delete() } }
        val zip = File(dir, "iraalgo-recordings-${today()}.zip")
        java.util.zip.ZipOutputStream(zip.outputStream().buffered()).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("README.txt")); z.write(LiveRecord.README.toByteArray(Charsets.UTF_8)); z.closeEntry()
            for ((day, f) in days()) for (file in f.listFiles().orEmpty().sortedBy { it.name }) {
                if (file.name != LiveRecord.MARKET_FILE && file.name != LiveRecord.TRADES_FILE) continue
                z.putNextEntry(java.util.zip.ZipEntry("$day/${file.name}"))
                file.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
        return zip
    }

    /**
     * Every kept day in one zip written to [out] (the file Boss picked with the system picker, after Boss's PIN; 12 Oct): the
     * market files, the tick zone's files ([com.optionslab.ira.TickZone]) as stored, and Boss's trade files DECRYPTED here, on
     * this phone, into plain JSON lines ("YYYY-MM-DD/trades.jsonl"); nothing is sent anywhere by the app. A line that cannot
     * be opened is written as a gap line, never invented. Only the recordings folder is read: no setting, token or key.
     * Returns the days written. A worker thread.
     */
    fun exportDecrypted(out: java.io.OutputStream): Int {
        val plain = setOf(LiveRecord.MARKET_FILE, com.optionslab.ira.TickZone.TICKS_FILE, com.optionslab.ira.TickZone.FLOW_FILE)
        var n = 0
        java.util.zip.ZipOutputStream(out.buffered()).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("README.txt"))
            z.write((LiveRecord.DECRYPTED_README + "\n" + com.optionslab.ira.TickZone.README + "\n").toByteArray(Charsets.UTF_8))
            z.closeEntry()
            for ((day, f) in days()) {
                val files = f.listFiles().orEmpty().sortedBy { it.name }
                for (file in files) when (file.name) {
                    in plain -> {
                        z.putNextEntry(java.util.zip.ZipEntry("$day/${file.name}"))
                        file.inputStream().use { it.copyTo(z) }
                        z.closeEntry()
                    }
                    LiveRecord.TRADES_FILE -> {
                        z.putNextEntry(java.util.zip.ZipEntry("$day/trades.jsonl"))
                        file.bufferedReader(Charsets.US_ASCII).useLines { lines ->
                            for (line in lines) {
                                if (line.isBlank()) continue
                                val text = runCatching {
                                    val blob = Vault.decrypt(android.util.Base64.decode(line, android.util.Base64.NO_WRAP))
                                    java.util.zip.GZIPInputStream(blob.inputStream()).use { it.readBytes() }
                                }.getOrNull()
                                if (text != null) z.write(text)
                                else z.write((LiveRecord.gap(0L, "trades line", "could not be decrypted") + "\n").toByteArray(Charsets.UTF_8))
                            }
                        }
                        z.closeEntry()
                    }
                }
                if (files.any { it.name in plain || it.name == LiveRecord.TRADES_FILE }) n++
            }
        }
        return n
    }

    /** Erase everything recorded (Boss's erase-everything). */
    fun wipe() {
        queue.drain()
        runCatching { TickZoneRecorder.wipe() }
        firstOi.clear()
        lastWriteMs = null
        runCatching { root?.deleteRecursively() }
    }

    /** TEST ONLY: recording into [context]'s folder without the writer and minute loops (the test drains by hand). */
    internal fun attachForTest(context: Context) {
        check(com.optionslab.app.BuildConfig.DEBUG) { "test only" }
        root = File(context.applicationContext.noBackupFilesDir, DIR)
    }

    /** TEST ONLY: not recording (the hooks add nothing). */
    internal fun detachForTest() { wipe(); root = null }

    /** TEST ONLY: the settings forgotten (read again), nothing queued, and the health clock started at [startedAt]. */
    internal fun resetForTest(startedAt: Long = System.currentTimeMillis()) {
        onKept = null; keepKept = null; startedMs = startedAt; wipe()
    }

    /** TEST ONLY: queued snapshots not yet written. */
    internal fun queuedForTest(): Int = queue.size()

    /** TEST ONLY: the folder. */
    internal fun rootForTest(): File? = root
}
