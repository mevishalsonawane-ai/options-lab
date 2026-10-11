package com.optionslab.ira

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDate
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.roundToLong

/**
 * The phone's compact market-history vault (TODO S5, 11 Oct): each trading day's 1-minute bars and end-of-day option chains,
 * kept under the app's noBackupFilesDir (never in a backup), capped, oldest day dropped first.
 *
 * What the phone already kept before the vault (none of it changes; the vault is an addition with its own cap):
 *  - the harvest store ([com.optionslab.engine.Olx] partitions: files/bars/<U>/<d>.olx and files/expiry/NIFTY/<d>.olx) -
 *    NIFTY and BANKNIFTY 1-minute index bars and their chains (3 expiries) for the strategy lab; it is not capped;
 *  - the market recorder's encrypted day files (the chain at ATM +/-15 every 5 minutes, the futures, the news);
 *  - the move recorder's gzip files of each big move (5 minutes either side, 1-second bars);
 *  - the order flow's shadow log (noBackupFilesDir/orderflow/shadow.log, 4 MB, rotated once);
 *  - the chart's in-memory candle cache and the option-chain read cache (memory only, gone on restart);
 *  - no Dhan store: the Dhan source was removed (TODO S6 waits on a fresh token).
 *
 * Layout: <root>/<yyyy-MM-dd>/<family>.ihv - one file per day per family ([Family]): "idx" (the six indices incl. India VIX),
 * "fut" (index futures with OI), "opt-<U>" (the strikes the app watched, ATM +/-5 of the active expiries), "chain-<U>" (the
 * end-of-day chain: LTP, OI, IV a strike), "mcx" (MCX near-month futures). Each file is [HistoryCodec]'s delta-encoded binary,
 * gzip'd. Written atomically (a temporary file renamed over), so a run stopped part-way leaves whole files only, and a
 * family already on disk is never fetched again ([has]) - the keeper resumes where it stopped.
 *
 * Reads for the lab, the pre-flight and the manager's counterfactuals: [bars] (a symbol's day) and [chain] (an underlying's
 * end-of-day chain). Thread-safe; plain JVM (no Android): the app gives it the directory.
 */
class HistoryVault(val root: File, capMb: Int = DEFAULT_CAP_MB) {
    /** The cap in bytes; set within [MIN_CAP_MB]..[MAX_CAP_MB] (the Bot settings' choice). */
    @Volatile var capBytes: Long = clampMb(capMb) * MB
        private set

    fun setCapMb(mb: Int) { capBytes = clampMb(mb) * MB; enforceCap() }

    /** TEST ONLY: a cap in bytes (below the settings' minimum). */
    internal fun capBytesForTest(b: Long) { capBytes = b }
    val capMb: Int get() = (capBytes / MB).toInt()

    object Family {
        const val INDEX = "idx"
        const val FUTURES = "fut"
        const val MCX = "mcx"
        fun options(underlying: String) = "opt-" + underlying.uppercase()
        fun chain(underlying: String) = "chain-" + underlying.uppercase()
        fun isChain(f: String) = f.startsWith("chain-")
    }

    private val lock = Any()
    /** The last few files decoded (a lab replay reads one day's symbols one after another). */
    private val decoded = object : LinkedHashMap<String, List<HistoryCodec.Series>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<HistoryCodec.Series>>?) = size > CACHE_FILES
    }

    private fun dayDir(day: LocalDate) = File(root, day.toString())
    private fun file(family: String, day: LocalDate) = File(dayDir(day), "$family$EXT")

    private fun clean(family: String): String {
        require(family.isNotEmpty() && family.all { it.isLetterOrDigit() || it == '-' || it == '_' }) { "bad family $family" }
        return family
    }

    /** Whether [family] of [day] is already kept (an empty file is not; one that cannot be decoded is removed when read). */
    fun has(family: String, day: LocalDate): Boolean = file(clean(family), day).let { it.isFile && it.length() > 0 }

    /** [series] of [day] kept as [family] (replacing what was there); the cap enforced after, [day] itself kept. Bytes written. */
    fun putBars(family: String, day: LocalDate, series: List<HistoryCodec.Series>): Long =
        write(clean(family), day, HistoryCodec.encodeBars(series))

    /** [chains] of [day] (one underlying's, as [Family.chain]). */
    fun putChains(underlying: String, day: LocalDate, chains: List<HistoryCodec.Chain>): Long =
        write(Family.chain(underlying), day, HistoryCodec.encodeChains(chains))

    private fun write(family: String, day: LocalDate, bytes: ByteArray): Long {
        synchronized(lock) {
            val dir = dayDir(day)
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create $dir")
            val tmp = File(dir, "$family$EXT.tmp")
            // Synced before the rename: a power cut leaves the old file or the whole new one, never a torn one.
            java.io.FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
            val dst = file(family, day)
            if (!tmp.renameTo(dst)) { dst.delete(); if (!tmp.renameTo(dst)) { tmp.delete(); throw IOException("cannot write $dst") } }
            decoded.remove(dst.path)
        }
        enforceCap(keep = day)
        return bytes.size.toLong()
    }

    /** The days kept, oldest first. */
    fun days(): List<LocalDate> = synchronized(lock) {
        root.listFiles()?.mapNotNull { f -> if (f.isDirectory) runCatching { LocalDate.parse(f.name) }.getOrNull() else null }.orEmpty().sorted()
    }

    /** The families kept for [day]. */
    fun families(day: LocalDate): List<String> = synchronized(lock) {
        dayDir(day).listFiles()?.filter { it.isFile && it.name.endsWith(EXT) }?.map { it.name.removeSuffix(EXT) }.orEmpty().sorted()
    }

    /** Bytes used now. */
    fun usageBytes(): Long = synchronized(lock) {
        root.listFiles()?.filter { it.isDirectory }?.sumOf { d -> d.listFiles()?.sumOf { it.length() } ?: 0L } ?: 0L
    }

    /**
     * Whole days dropped, oldest first, until the vault fits its cap; [keep] (the day just written) and the newest day are
     * never dropped. Returns the days dropped.
     */
    fun enforceCap(keep: LocalDate? = null): List<LocalDate> = synchronized(lock) {
        val dropped = ArrayList<LocalDate>()
        val ds = days().toMutableList()
        var used = usageBytes()
        // [keep] is skipped, not a stop: the next oldest day goes instead (G9-9); the newest day is never dropped.
        var i = 0
        while (used > capBytes && i < ds.size - 1) {
            val oldest = ds[i]
            if (oldest == keep) { i++; continue }
            val dir = dayDir(oldest)
            val size = dir.listFiles()?.sumOf { it.length() } ?: 0L
            dir.listFiles()?.forEach { decoded.remove(it.path); it.delete() }
            dir.delete()
            ds.removeAt(i)
            used -= size
            dropped += oldest
        }
        dropped
    }

    private fun readSeries(family: String, day: LocalDate): List<HistoryCodec.Series>? {
        val f = file(family, day)
        synchronized(lock) {
            decoded[f.path]?.let { return it }
            if (!f.isFile) return null
            val bytes = runCatching { f.readBytes() }.getOrNull() ?: return null
            val s = runCatching { HistoryCodec.decodeBars(bytes) }.getOrNull() ?: return dropUnreadable(f)
            decoded[f.path] = s
            return s
        }
    }

    /**
     * [symbol]'s 1-minute bars on [day] (oldest first), from whichever family keeps it ("NIFTY", "INDIAVIX", "NIFTY26OCTFUT",
     * "BANKNIFTY26OCT52000CE", "CRUDEOIL26NOVFUT"); empty when the vault does not have it.
     */
    fun bars(symbol: String, day: LocalDate): List<HistoryCodec.Bar> {
        val s = symbol.uppercase()
        val fams = families(day).filter { !Family.isChain(it) }
        // The likeliest family first: an option's own underlying, then the rest.
        val first = fams.filter { it.startsWith("opt-") && s.startsWith(it.removePrefix("opt-")) }.sortedByDescending { it.length }
        for (f in first + (fams - first.toSet())) {
            readSeries(f, day)?.firstOrNull { it.symbol.equals(s, ignoreCase = true) }?.let { return it.bars }
        }
        return emptyList()
    }

    /** The symbols kept on [day] in [family] (all bar families when null). */
    fun symbols(day: LocalDate, family: String? = null): List<String> =
        (if (family != null) listOf(family) else families(day).filter { !Family.isChain(it) })
            .flatMap { readSeries(it, day)?.map { s -> s.symbol }.orEmpty() }

    /** [underlying]'s end-of-day chain on [day], or null. */
    fun chain(underlying: String, day: LocalDate): HistoryCodec.Chain? {
        val f = file(Family.chain(underlying), day)
        val all = synchronized(lock) {
            val bytes = if (f.isFile) runCatching { f.readBytes() }.getOrNull() else null
            if (bytes != null) runCatching { HistoryCodec.decodeChains(bytes) }.getOrNull() ?: dropUnreadable<List<HistoryCodec.Chain>>(f) else null
        } ?: return null
        return all.firstOrNull { it.underlying.equals(underlying, ignoreCase = true) }
    }

    /**
     * A kept file that cannot be decoded (torn by a crash, damaged): removed, so [has] says no and the keeper fetches the day
     * again (market data from the source, nothing of Boss's own). Under [lock]. Always null.
     */
    private fun <T> dropUnreadable(f: File): T? {
        decoded.remove(f.path)
        f.delete()
        return null
    }

    /** "212 MB of 300 MB · 143 days (12 Mar - 10 Oct)". */
    fun usageLine(): String {
        val ds = days()
        val used = usageBytes()
        val mb = if (used < MB) "%.1f".format(java.util.Locale.ENGLISH, used / MB.toDouble()) else (used / MB).toString()
        return "$mb MB of $capMb MB · ${ds.size} day${if (ds.size == 1) "" else "s"}" +
            (if (ds.isNotEmpty()) " (${AgentLessons.day(ds.first())} - ${AgentLessons.day(ds.last())})" else "")
    }

    companion object {
        const val DEFAULT_CAP_MB = 300
        const val MIN_CAP_MB = 100
        const val MAX_CAP_MB = 1000
        const val MB = 1024L * 1024L
        const val EXT = ".ihv"
        private const val CACHE_FILES = 4
        /** The Bot settings' choices. */
        val CAP_CHOICES = listOf(100, 200, 300, 500, 750, 1000)
        fun clampMb(mb: Int): Long = mb.coerceIn(MIN_CAP_MB, MAX_CAP_MB).toLong()
    }
}

/**
 * The vault's file format (version 1): delta-encoded integers in paise (price x 100) and seconds, zig-zag varints, the whole
 * file gzip'd. A day of a symbol's ~375 minutes is about 1-2 KB. Pure.
 *
 * Bars: "IHV1" 'B', series count; each: symbol, bar count, then per bar the time from the last (seconds), the open from the
 * last close, high/low/close from the open, the volume, the OI from the last OI.
 * Chains: "IHV1" 'C', chain count; each: underlying, time, spot, row count, then per row the expiry (epoch day, delta), the
 * strike (paise, delta), CE/PE, the LTP (paise), the OI and the IV (basis points; 0 = not known).
 */
object HistoryCodec {
    data class Bar(val epochSec: Long, val open: Double, val high: Double, val low: Double, val close: Double,
                   val volume: Long = 0, val oi: Long = 0)

    data class Series(val symbol: String, val bars: List<Bar>)

    data class ChainRow(val expiry: LocalDate, val strike: Double, val call: Boolean, val ltp: Double, val oi: Long, val iv: Double?)

    data class Chain(val underlying: String, val atEpochSec: Long, val spot: Double, val rows: List<ChainRow>)

    private val MAGIC = byteArrayOf('I'.code.toByte(), 'H'.code.toByte(), 'V'.code.toByte(), '1'.code.toByte())
    private const val BARS: Int = 'B'.code
    private const val CHAINS: Int = 'C'.code

    private fun paise(x: Double): Long = if (x.isFinite()) (x * 100.0).roundToLong() else 0L
    private fun rupees(p: Long): Double = p / 100.0

    fun encodeBars(series: List<Series>): ByteArray = gz { o ->
        o.write(MAGIC); o.write(BARS)
        writeVar(o, series.size.toLong())
        for (s in series) {
            writeStr(o, s.symbol)
            val bars = s.bars.sortedBy { it.epochSec }
            writeVar(o, bars.size.toLong())
            var t = 0L; var lastClose = 0L; var lastOi = 0L
            for (b in bars) {
                val op = paise(b.open); val hi = paise(b.high); val lo = paise(b.low); val cl = paise(b.close)
                writeZz(o, b.epochSec - t); t = b.epochSec
                writeZz(o, op - lastClose)
                writeZz(o, hi - op); writeZz(o, lo - op); writeZz(o, cl - op)
                writeVar(o, b.volume.coerceAtLeast(0))
                writeZz(o, b.oi - lastOi); lastOi = b.oi
                lastClose = cl
            }
        }
    }

    fun decodeBars(bytes: ByteArray): List<Series> = ungz(bytes) { i ->
        header(i, BARS)
        val n = readVar(i).toInt()
        val out = ArrayList<Series>(n)
        repeat(n) {
            val sym = readStr(i)
            val m = readVar(i).toInt()
            val bars = ArrayList<Bar>(m)
            var t = 0L; var lastClose = 0L; var lastOi = 0L
            repeat(m) {
                t += readZz(i)
                val op = lastClose + readZz(i)
                val hi = op + readZz(i); val lo = op + readZz(i); val cl = op + readZz(i)
                val vol = readVar(i)
                lastOi += readZz(i)
                bars += Bar(t, rupees(op), rupees(hi), rupees(lo), rupees(cl), vol, lastOi)
                lastClose = cl
            }
            out += Series(sym, bars)
        }
        out
    }

    fun encodeChains(chains: List<Chain>): ByteArray = gz { o ->
        o.write(MAGIC); o.write(CHAINS)
        writeVar(o, chains.size.toLong())
        for (c in chains) {
            writeStr(o, c.underlying)
            writeZz(o, c.atEpochSec)
            writeZz(o, paise(c.spot))
            val rows = c.rows.sortedWith(compareBy({ it.expiry }, { it.strike }, { !it.call }))
            writeVar(o, rows.size.toLong())
            var e = 0L; var k = 0L
            for (r in rows) {
                val ed = r.expiry.toEpochDay(); val kp = paise(r.strike)
                writeZz(o, ed - e); e = ed
                writeZz(o, kp - k); k = kp
                o.write(if (r.call) 0 else 1)
                writeVar(o, paise(r.ltp).coerceAtLeast(0))
                writeVar(o, r.oi.coerceAtLeast(0))
                writeVar(o, r.iv?.takeIf { it.isFinite() && it > 0 }?.let { (it * 10_000).roundToLong() + 1 } ?: 0L)
            }
        }
    }

    fun decodeChains(bytes: ByteArray): List<Chain> = ungz(bytes) { i ->
        header(i, CHAINS)
        val n = readVar(i).toInt()
        val out = ArrayList<Chain>(n)
        repeat(n) {
            val u = readStr(i)
            val at = readZz(i)
            val spot = rupees(readZz(i))
            val m = readVar(i).toInt()
            val rows = ArrayList<ChainRow>(m)
            var e = 0L; var k = 0L
            repeat(m) {
                e += readZz(i); k += readZz(i)
                val call = i.read() == 0
                val ltp = rupees(readVar(i))
                val oi = readVar(i)
                val iv = readVar(i).takeIf { it > 0 }?.let { (it - 1) / 10_000.0 }
                rows += ChainRow(LocalDate.ofEpochDay(e), rupees(k), call, ltp, oi, iv)
            }
            out += Chain(u, at, spot, rows)
        }
        out
    }

    // ---- the primitives ----

    private inline fun gz(block: (OutputStream) -> Unit): ByteArray {
        val bos = ByteArrayOutputStream(16 * 1024)
        GZIPOutputStream(bos, 16 * 1024).use { z -> val b = java.io.BufferedOutputStream(z, 16 * 1024); block(b); b.flush() }
        return bos.toByteArray()
    }

    private inline fun <T> ungz(bytes: ByteArray, block: (InputStream) -> T): T =
        java.io.BufferedInputStream(GZIPInputStream(ByteArrayInputStream(bytes), 16 * 1024), 16 * 1024).use { block(it) }

    private fun header(i: InputStream, type: Int) {
        val m = ByteArray(4)
        if (DataInputStream(i).run { readFully(m); !m.contentEquals(MAGIC) }) throw IOException("not a history vault file")
        if (i.read() != type) throw IOException("wrong history vault file type")
    }

    private fun writeVar(o: OutputStream, v0: Long) {
        var v = v0
        while (v and 0x7FL.inv() != 0L) { o.write(((v and 0x7F) or 0x80).toInt()); v = v ushr 7 }
        o.write(v.toInt())
    }

    private fun readVar(i: InputStream): Long {
        var shift = 0; var out = 0L
        while (true) {
            val b = i.read()
            if (b < 0) throw IOException("history vault file cut short")
            out = out or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return out
            shift += 7
            if (shift > 63) throw IOException("history vault file damaged")
        }
    }

    private fun writeZz(o: OutputStream, v: Long) = writeVar(o, (v shl 1) xor (v shr 63))
    private fun readZz(i: InputStream): Long = readVar(i).let { (it ushr 1) xor -(it and 1) }

    private fun writeStr(o: OutputStream, s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        writeVar(o, b.size.toLong()); o.write(b)
    }

    private fun readStr(i: InputStream): String {
        val n = readVar(i).toInt()
        if (n !in 0..4096) throw IOException("history vault file damaged")
        val b = ByteArray(n)
        DataInputStream(i).readFully(b)
        return String(b, Charsets.UTF_8)
    }

}
