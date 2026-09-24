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
        if (f.exists()) return Olx.read(f.inputStream()).firstOrNull()
        val asset = "bars_${u.lowercase()}.olx"
        if (app.assets.list("")?.contains(asset) != true) return null
        var hit: Session? = null
        Olx.forEachUntil(app.assets.open(asset)) { s -> if (s.day == day) { hit = s; false } else true }
        return hit
    }

    /**
     * Upsert new series into a day partition, keyed on (expiry, strike, right)
     * and minute - a retried harvest converges instead of double-counting.
     */
    @Synchronized
    fun upsertDay(u: String, day: LocalDate, incoming: List<Series>) {
        if (incoming.isEmpty()) return
        val existing = barSession(u, day)?.series ?: emptyList()
        val byKey = LinkedHashMap<Triple<LocalDate?, Double, com.optionslab.engine.Right>, Series>()
        for (s in existing) byKey[Triple(s.expiry, s.strike, s.right)] = s
        for (s in incoming) {
            val k = Triple(s.expiry, s.strike, s.right)
            byKey[k] = byKey[k]?.let { merge(it, s) } ?: s
        }
        val dir = barsDir(u).apply { mkdirs() }
        atomicWrite(File(dir, "$day.olx")) { Olx.write(it, listOf(Session(day, null, byKey.values.toList()))) }
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

    fun manifest(u: String): List<Manifest.Entry> {
        val f = File(root, "manifest_$u.csv")
        if (f.exists()) return Manifest.parseCsv(f.readText())
        val asset = "manifest_${u.lowercase()}.csv"
        return if (app.assets.list("")?.contains(asset) == true) Manifest.parseCsv(app.assets.open(asset).bufferedReader().readText()) else emptyList()
    }

    /** Record one session, never downgrading a same_day chain on a later re-fetch. */
    @Synchronized
    fun recordManifest(u: String, e: Manifest.Entry) {
        val cur = manifest(u)
        val prior = cur.firstOrNull { it.session == e.session }
        val entry = if (e.scope == Manifest.BACKFILL && prior?.scope == Manifest.SAME_DAY)
            e.copy(scope = Manifest.SAME_DAY, collectedOn = prior.collectedOn) else e
        File(root, "manifest_$u.csv").writeText(Manifest.toCsv(Manifest.upsert(cur, entry)))
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
        root.listFiles { f -> f.name.startsWith("manifest_") || f.name == "contracts.json" }?.forEach { it.delete() }
        invalidate()
    }

    fun deviceBytes(): Long = listOf(File(root, "expiry"), File(root, "bars")).sumOf { d -> d.walkTopDown().filter { it.isFile }.sumOf { it.length() } }

    private fun atomicWrite(target: File, write: (java.io.OutputStream) -> Unit) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.outputStream().use(write)
        if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
    }
}
