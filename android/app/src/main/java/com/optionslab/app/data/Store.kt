package com.optionslab.app.data

import android.content.Context
import com.optionslab.engine.BacktestReport
import com.optionslab.engine.ExpiryPut
import com.optionslab.engine.Manifest
import com.optionslab.engine.Olx
import com.optionslab.engine.Series
import com.optionslab.engine.Session
import java.io.File
import java.time.LocalDate

/**
 * Where the data lives.
 *
 *   bundled   assets/expiry_nifty.olx       the PC's 170 expiry chains
 *             assets/bars_<u>.olx           the PC's harvested partitions
 *   device    files/expiry/NIFTY/<d>.olx    expiry chains captured on this phone
 *             files/bars/<U>/<d>.olx        partitions harvested on this phone
 *             files/manifest_<U>.csv
 *
 * Market data is public and stored plain; everything personal is in the vault.
 * A device partition for a day the bundle also holds replaces it - it was
 * collected later, through the same upsert, so it is a superset.
 */
object Store {
    private lateinit var app: Context
    private val root get() = app.filesDir

    fun init(context: Context) { app = context.applicationContext }

    // ---- expiry chains ------------------------------------------------------

    private fun expiryDir() = File(root, "expiry/NIFTY")

    fun deviceExpiryDays(): List<LocalDate> =
        expiryDir().listFiles { f -> f.name.endsWith(".olx") }?.mapNotNull { runCatching { LocalDate.parse(it.name.removeSuffix(".olx")) }.getOrNull() }?.sorted()
            ?: emptyList()

    /** Streams every expiry session: the PC's cache, then this phone's captures. */
    fun expirySessions(includeDevice: Boolean): Sequence<Session> {
        val device = if (includeDevice) deviceExpiryDays() else emptyList()
        val deviceSet = device.toSet()
        val bundled = Olx.sequence(app.assets.open("expiry_nifty.olx"), float32Prices = true).filter { it.day !in deviceSet }
        return bundled + device.asSequence().flatMap { d -> Olx.sequence(File(expiryDir(), "$d.olx").inputStream()) }
    }

    /** How many series this phone's capture of [day]'s expiring chain holds (0 when none, or unreadable). */
    fun deviceExpirySize(day: LocalDate): Int = runCatching {
        File(expiryDir(), "$day.olx").takeIf { it.exists() }?.inputStream()?.use { Olx.read(it).firstOrNull()?.series?.size }
    }.getOrNull() ?: 0

    fun writeExpiry(session: Session) {
        val dir = expiryDir().apply { mkdirs() }
        atomicWrite(File(dir, "${session.day}.olx")) { Olx.write(it, listOf(session)) }
    }

    // ---- harvested partitions ---------------------------------------------

    private fun barsDir(u: String) = File(root, "bars/$u")

    fun deviceBarDays(u: String): List<LocalDate> =
        barsDir(u).listFiles { f -> f.name.endsWith(".olx") }?.mapNotNull { runCatching { LocalDate.parse(it.name.removeSuffix(".olx")) }.getOrNull() }?.sorted()
            ?: emptyList()

    fun barSessions(u: String): Sequence<Session> {
        val device = deviceBarDays(u)
        val deviceSet = device.toSet()
        val asset = "bars_${u.lowercase()}.olx"
        val bundled = if (app.assets.list("")?.contains(asset) == true)
            Olx.sequence(app.assets.open(asset)).filter { it.day !in deviceSet } else emptySequence()
        // Not sorted: sorting a sequence materialises it, and the point is to
        // stream. Bundled days are ascending and every device day follows them.
        return bundled + device.asSequence().flatMap { d -> Olx.sequence(File(barsDir(u), "$d.olx").inputStream()) }
    }

    /**
     * [barSessions] with the days downloaded from Dhan before the first of them in front ([DhanSource.researchSessions]),
     * oldest first: the longer record the replays learn from (the ORB arms, the Strategy Lab presets). Paper research only.
     * A Dhan file that cannot be read ends the Dhan part there; the bundled and harvested days always follow.
     */
    fun researchSessions(u: String): Sequence<Session> {
        val first = barDays(u).firstOrNull() ?: Market.today()
        val older = sequence {
            try {
                yieldAll(DhanSource.researchSessions(u, first))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        return older + barSessions(u)
    }

    fun barDays(u: String): List<LocalDate> {
        val out = sortedSetOf<LocalDate>()
        val asset = "bars_${u.lowercase()}.olx"
        if (app.assets.list("")?.contains(asset) == true) {
            // Bundled day list, recorded once rather than decoded each time.
            manifest(u).forEach { out += it.session }
        }
        out += deviceBarDays(u)
        return out.toList()
    }

    fun barSession(u: String, day: LocalDate): Session? {
        val f = File(barsDir(u), "$day.olx")
        if (f.exists()) return f.inputStream().use { Olx.read(it).firstOrNull() }
        val asset = "bars_${u.lowercase()}.olx"
        if (app.assets.list("")?.contains(asset) != true) return null
        var hit: Session? = null
        app.assets.open(asset).use { Olx.forEachUntil(it) { s -> if (s.day == day) { hit = s; false } else true } }
        return hit
    }

    /**
     * Upsert new series into a day partition, keyed on (expiry, strike, right)
     * and minute - a retried harvest converges instead of double-counting.
     */
    @Synchronized
    fun upsertDay(u: String, day: LocalDate, incoming: List<Series>) {
        if (incoming.isEmpty()) return
        val f = File(barsDir(u), "$day.olx")
        val existing = try {
            barSession(u, day)?.series ?: emptyList()
        } catch (e: Exception) {
            if (!f.exists()) throw e
            // A truncated or corrupt partition (a power cut mid-write): set it aside, never overwrite it,
            // and start the day afresh. The done-lists vouched for bars that were in it, so they go too.
            f.renameTo(File(f.parentFile, "${f.name}.corrupt.${System.currentTimeMillis()}"))
            synchronized(harvestLock) { File(root, "harvest/$u").listFiles()?.forEach { it.delete() } }
            emptyList()
        }
        val byKey = LinkedHashMap<Triple<LocalDate?, Double, com.optionslab.engine.Right>, Series>()
        for (s in existing) byKey[Triple(s.expiry, s.strike, s.right)] = s
        for (s in incoming) {
            val k = Triple(s.expiry, s.strike, s.right)
            byKey[k] = byKey[k]?.let { merge(it, s) } ?: s
        }
        barsDir(u).mkdirs()
        atomicWrite(f) { Olx.write(it, listOf(Session(day, null, byKey.values.toList()))) }
    }

    private fun merge(old: Series, new: Series): Series {
        val rows = java.util.TreeMap<Int, Int>()   // minute -> index, new wins
        val src = HashMap<Int, Series>()
        for (i in 0 until old.size) { rows[old.minutes[i]] = i; src[old.minutes[i]] = old }
        for (i in 0 until new.size) { rows[new.minutes[i]] = i; src[new.minutes[i]] = new }
        val mins = rows.keys.toIntArray()
        fun col(sel: (Series) -> DoubleArray?) = DoubleArray(mins.size) { j -> val s = src.getValue(mins[j]); sel(s)?.get(rows.getValue(mins[j])) ?: s.close[rows.getValue(mins[j])] }
        fun lcol(sel: (Series) -> LongArray?) = LongArray(mins.size) { j -> val s = src.getValue(mins[j]); sel(s)?.get(rows.getValue(mins[j])) ?: 0L }
        return Series(new.expiry, new.strike, new.right, new.lot.takeIf { it > 0 } ?: old.lot, mins,
            col { it.close }, col { it.open }, col { it.high }, col { it.low }, lcol { it.volume }, lcol { it.oi })
    }

    // ---- manifest -----------------------------------------------------------

    // Its own lock, not the object's: a manifest read must never wait behind a minutes-long backtest.
    private val manifestLock = Any()

    /** Read under the lock [recordManifest] writes under, so a reader never sees a file mid-replace. */
    fun manifest(u: String): List<Manifest.Entry> {
        val f = File(root, "manifest_$u.csv")
        synchronized(manifestLock) { if (f.exists()) return Manifest.parseCsv(f.readText()) }
        val asset = "manifest_${u.lowercase()}.csv"
        return if (app.assets.list("")?.contains(asset) == true) Manifest.parseCsv(app.assets.open(asset).bufferedReader().readText()) else emptyList()
    }

    /** Record one session, never downgrading a same_day chain on a later re-fetch. */
    fun recordManifest(u: String, e: Manifest.Entry) = synchronized(manifestLock) {
        val cur = manifest(u)
        val prior = cur.firstOrNull { it.session == e.session }
        val entry = if (e.scope == Manifest.BACKFILL && prior?.scope == Manifest.SAME_DAY)
            e.copy(scope = Manifest.SAME_DAY, collectedOn = prior.collectedOn) else e
        val csv = Manifest.toCsv(Manifest.upsert(cur, entry))
        atomicWrite(File(root, "manifest_$u.csv")) { it.write(csv.toByteArray(Charsets.UTF_8)) }
    }

    // ---- harvest progress ---------------------------------------------------

    private val harvestLock = Any()
    private fun harvestedFile(u: String, day: LocalDate) = File(root, "harvest/$u/$day.done")

    /** Contracts (instrument keys) whose bars for the [day] harvest are already in the partitions. */
    fun harvested(u: String, day: LocalDate): Set<String> = synchronized(harvestLock) {
        val f = harvestedFile(u, day)
        if (f.exists()) f.readLines().filter { it.isNotBlank() }.toSet() else emptySet()
    }

    /** Called only once the keys' bars are upserted, so a retried harvest can skip them. Older days' lists go. */
    fun markHarvested(u: String, day: LocalDate, keys: Collection<String>) {
        if (keys.isEmpty()) return
        synchronized(harvestLock) {
            val f = harvestedFile(u, day)
            val dir = f.parentFile!!.apply { mkdirs() }
            dir.listFiles { x -> x.name.endsWith(".done") && x.name != f.name }?.forEach { it.delete() }
            val all = ((if (f.exists()) f.readLines() else emptyList()) + keys).filter { it.isNotBlank() }.distinct()
            atomicWrite(f) { it.write(all.joinToString("\n").toByteArray(Charsets.UTF_8)) }
        }
    }

    fun provenanceCsv(): String = app.assets.open("provenance.csv").bufferedReader().readText()

    // ---- backtest cache -----------------------------------------------------

    private var cacheKey: String? = null
    private var cached: BacktestReport? = null

    /** Streams every session through the engine; nothing holds the whole record. */
    @Synchronized
    fun backtest(s: AppSettings, progress: (Int, Int) -> Unit = { _, _ -> }): BacktestReport {
        val key = listOf(s.params(), s.holdout, s.capital, s.survive, s.includeDeviceSessions, deviceExpiryDays().size).toString()
        if (key == cacheKey) cached?.let { return it }
        val p = s.params()
        val days = ArrayList<LocalDate>()
        val trades = ArrayList<ExpiryPut.Trade>()
        val skips = ArrayList<ExpiryPut.Skip>()
        val total = 170 + (if (s.includeDeviceSessions) deviceExpiryDays().size else 0)
        for (session in expirySessions(s.includeDeviceSessions)) {
            days += session.day
            val (t, k) = ExpiryPut.runBacktest(listOf(session), p)
            trades += t; skips += k
            progress(days.size, total)
        }
        val report = BacktestReport.assemble(days, trades, skips, p, s.holdout, s.capital, s.survive)
        cacheKey = key
        cached = report
        return report
    }

    fun invalidate() { cacheKey = null; cached = null }

    fun wipeDeviceData() {
        File(root, "expiry").deleteRecursively()
        File(root, "bars").deleteRecursively()
        File(root, "harvest").deleteRecursively()
        root.listFiles { f -> f.name.startsWith("manifest_") || f.name == "contracts.json" }?.forEach { it.delete() }
        invalidate()
    }

    fun deviceBytes(): Long = listOf(File(root, "expiry"), File(root, "bars")).sumOf { d -> d.walkTopDown().filter { it.isFile }.sumOf { it.length() } }

    /**
     * Temp file, flushed to disk, then renamed over the target, and the rename synced: a crash or a
     * power cut leaves the old file or the new one, and a file written after this one (a .done
     * marker after the bars it vouches for) can never survive without it.
     */
    private fun atomicWrite(target: File, write: (java.io.OutputStream) -> Unit) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        java.io.FileOutputStream(tmp).use { fos ->
            // The writer may close what it is handed (Olx wraps it in GZIP): the file stays open to be synced.
            val buffered = fos.buffered(1 shl 16)
            val keepOpen = object : java.io.FilterOutputStream(buffered) {
                override fun write(b: ByteArray, off: Int, len: Int) = buffered.write(b, off, len)
                override fun close() = flush()
            }
            write(keepOpen)
            keepOpen.flush()
            fos.fd.sync()
        }
        if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
        com.optionslab.app.security.Vault.syncDir(target.parentFile)
    }
}
