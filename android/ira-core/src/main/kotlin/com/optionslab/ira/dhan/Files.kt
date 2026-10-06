package com.optionslab.ira.dhan

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.time.LocalDate
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * The Dhan store on disk, under one root folder in the app's private storage (never shared, never backed up):
 *
 *   master.csv                                 the few scrip-master rows the store needs (IDs)
 *   expiries/<U>.csv                           every expiry date Dhan has listed for the index, kept as they pass
 *   chain/<U>/<day>.json.gz                    the day's option chain (Dhan's reply, as served; the last 30 days kept)
 *   idx|fut|eq/<SYM>/day/<from>.csv.gz         daily candles        t,o,h,l,c,v,oi
 *   idx|fut|eq/<SYM>/min/<from>.csv.gz         one-minute candles   t,o,h,l,c,v,oi
 *   opt/<U>/<WEEK|MONTH>/<from>/<CE|PE><+-n>.csv.gz
 *                                              expired options, n strikes from the money:  t,o,h,l,c,v,oi,iv,k,s
 *                                              (k the strike it was that minute, s the index then)
 *   fetched.csv                                the fetch ledger ([FETCHED]): "<store path>,<yyyy-MM-dd>" per line, the IST
 *                                              date that file was last fetched from Dhan - what says a chunk is complete
 *
 * t is epoch seconds. One file is one request's reply (or just the header when Dhan had nothing), written whole and
 * renamed into place, so a file is either complete or absent. Plain gzip CSV: one pass reads it, any tool opens it.
 * Market prices only: nothing personal is ever written here. Pure JVM (java.io), so it is tested on the JVM.
 */
class Files(val root: File) {
    companion object {
        const val CANDLE_HEADER = "t,o,h,l,c,v,oi"
        const val OPTION_HEADER = "t,o,h,l,c,v,oi,iv,k,s"
        /**
         * The fetch ledger, at the store root (and, in a data pack, at dhan/fetched.csv). Plain UTF-8 text, one line per
         * candle/option file: `<store path>,<yyyy-MM-dd>` - the path relative to the store root exactly as the file's own
         * (e.g. `opt/NIFTY/WEEK/2026-08-27/CE+3.csv.gz`) and the IST calendar date the file's data was fetched from Dhan.
         * Later lines win; `<store path>,` (no date) forgets an entry. No header; blank and unreadable lines are ignored.
         * A chunk whose window is [from, to) is complete - "done" - when its file exists and its date is on or after `to`
         * (fetched once every session of the window was over). See [completeThrough] for a file with no line.
         */
        const val FETCHED = "fetched.csv"

        /** Option-chain snapshots older than this many days are deleted. */
        const val KEEP_CHAINS_DAYS = 30L

        /** Bytes for the screen: "812.4 MB", "3.62 GB". */
        fun sizeText(bytes: Long): String =
            if (bytes >= 1_000_000_000L) "%.2f GB".format(java.util.Locale.ENGLISH, bytes / 1e9) else "%.1f MB".format(java.util.Locale.ENGLISH, bytes / 1e6)

        /** A price as short as it can be written: "24612.4", "100", "0.05". */
        fun num(x: Double): String {
            if (x.isNaN() || x.isInfinite()) return "0"
            val r = Math.round(x * 100)
            if (r % 100 == 0L) return (r / 100).toString()
            return java.math.BigDecimal.valueOf(r, 2).stripTrailingZeros().toPlainString()
        }
    }

    fun file(path: String) = File(root, path)
    fun has(path: String): Boolean = file(path).isFile

    /** Write [text] to [path] whole: a temporary file, synced, renamed over the old one. */
    fun writeText(path: String, text: String, gzip: Boolean = path.endsWith(".gz")) {
        val f = file(path)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".part")
        java.io.FileOutputStream(tmp).use { fo ->
            if (gzip) GZIPOutputStream(fo, 1 shl 16).use { it.write(text.toByteArray(Charsets.UTF_8)); it.finish(); fo.fd.sync() }
            else { fo.write(text.toByteArray(Charsets.UTF_8)); fo.fd.sync() }
        }
        if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) { tmp.delete(); throw java.io.IOException("could not store a chunk") } }
    }

    fun readText(path: String): String? = file(path).takeIf { it.isFile }?.let { f ->
        if (f.name.endsWith(".gz")) GZIPInputStream(f.inputStream()).use { String(it.readBytes(), Charsets.UTF_8) } else f.readText()
    }

    /** The lines of a gzip CSV, header dropped; empty when the file is absent. Streams. */
    fun lines(path: String): Sequence<String> = lines(file(path))

    fun lines(f: File): Sequence<String> = sequence {
        if (!f.isFile) return@sequence
        BufferedReader(InputStreamReader(GZIPInputStream(f.inputStream(), 1 shl 16), Charsets.UTF_8)).use { r ->
            r.readLine() ?: return@use
            while (true) { val l = r.readLine() ?: break; if (l.isNotBlank()) yield(l) }
        }
    }

    // ---- the fetch ledger -------------------------------------------------------------------------------------------

    private var ledger: HashMap<String, LocalDate>? = null

    /** Parse ledger text: path -> date; later lines win, a line with no date forgets. */
    fun parseLedger(text: String, into: MutableMap<String, LocalDate> = HashMap()): MutableMap<String, LocalDate> {
        for (raw in text.lineSequence()) {
            val l = raw.trim()
            val c = l.lastIndexOf(',')
            if (c <= 0) continue
            val path = l.substring(0, c)
            val d = l.substring(c + 1)
            if (d.isEmpty()) { into.remove(path); continue }
            runCatching { LocalDate.parse(d) }.getOrNull()?.let { into[path] = it }
        }
        return into
    }

    @Synchronized private fun ledgerMap(): HashMap<String, LocalDate> =
        ledger ?: HashMap<String, LocalDate>().also { m -> file(FETCHED).takeIf { it.isFile }?.let { parseLedger(it.readText(), m) }; ledger = m }

    /** The date [path] was last fetched from Dhan (the ledger), or null when not recorded. */
    @Synchronized fun fetchedOn(path: String): LocalDate? = ledgerMap()[path]

    /** Every recorded fetch date. */
    @Synchronized fun fetched(): Map<String, LocalDate> = HashMap(ledgerMap())

    /** Read the ledger again from disk (another writer may have added to it). */
    @Synchronized fun reloadLedger() { ledger = null }

    /**
     * Record fetch dates ([entries]: path -> date, or null to forget a path), appended to the ledger and synced. A line cut
     * short by a crash is ignored when read (and the next append starts on a new line). The file is rewritten whole when
     * it has grown to over twice its entries.
     */
    @Synchronized fun markFetched(entries: Map<String, LocalDate?>) {
        if (entries.isEmpty()) return
        val m = ledgerMap()
        val f = file(FETCHED)
        f.parentFile?.mkdirs()
        val needsNl = f.isFile && f.length() > 0 && java.io.RandomAccessFile(f, "r").use { it.seek(it.length() - 1); it.read() != '\n'.code }
        val text = buildString {
            if (needsNl) append('\n')
            for ((p, d) in entries) { append(p).append(',').append(d?.toString() ?: "").append('\n') }
        }
        java.io.FileOutputStream(f, true).use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
        for ((p, d) in entries) if (d == null) m.remove(p) else m[p] = d
        val lines = runCatching { f.length() / 40 }.getOrDefault(0L)
        if (lines > 2L * m.size + 1000) writeText(FETCHED, m.toSortedMap().entries.joinToString("") { "${it.key},${it.value}\n" }, gzip = false)
    }

    fun markFetched(path: String, date: LocalDate?) = markFetched(mapOf(path to date))

    /** The first field (epoch seconds) of the last row of a gzip CSV, or null when it has none. Streams. */
    fun lastT(path: String): Long? {
        var last: Long? = null
        for (l in lines(path)) l.substringBefore(',').toLongOrNull()?.let { last = it }
        return last
    }

    /**
     * The last day through which the file at [path] - the chunk of window [from] .. [toExclusive] - is complete, or null
     * when nothing of it is known complete (absent, or empty and unrecorded):
     *  - with a ledger date F ([FETCHED]): the day before F, at most the window's last day (a session is over once its
     *    day has passed);
     *  - with none (a file from before the ledger, or imported without one), from its last candle: a minute or option
     *    file whose last candle is the closing minute (15:29 or later) of the window's last trading day ([tradingDay]: the
     *    exchange calendar when the app has it) is whole; otherwise it is complete only through the day BEFORE its last
     *    candle's day (that day may have been fetched mid-session). A daily file cannot tell a final close from a
     *    mid-session one, so it too counts only through the day before its last candle. When [record] and the check finds
     *    the window whole, the ledger gets the window's end as its date, so the file is not read again for this.
     */
    fun completeThrough(path: String, from: LocalDate, toExclusive: LocalDate, tradingDay: (LocalDate) -> Boolean = Plan.WEEKDAYS,
                        record: Boolean = false): LocalDate? {
        if (!has(path)) return null
        val last = toExclusive.minusDays(1)
        fetchedOn(path)?.let { f -> return minOf(last, f.minusDays(1)).takeIf { !it.isBefore(from) } }
        val lastT = lastT(path) ?: return null
        val lastDay = day(lastT)
        val minutes = path.contains("/min/") || path.startsWith("opt/")
        val lastSession = Plan.lastTradingDay(from, toExclusive, tradingDay)
        val closing = java.time.Instant.ofEpochSecond(lastT).atZone(com.optionslab.engine.IST).toLocalTime() >= java.time.LocalTime.of(15, 29)
        if (minutes && lastSession != null && (lastDay.isAfter(lastSession) || (lastDay == lastSession && closing))) {
            if (record) runCatching { markFetched(path, toExclusive) }
            return last
        }
        return minOf(lastDay.minusDays(1), last).takeIf { !it.isBefore(from) }
    }

    /**
     * Is [t] done: its window has ended (not open on [today]) and its file holds the whole window ([completeThrough]
     * reaches the window's last day)? An EXPIRIES / CHAIN task is never done (refreshed each run). A file with no ledger
     * line that checks whole gets one ([completeThrough]'s record), so each old file is read for this once.
     */
    fun done(t: Plan.Task, today: LocalDate, tradingDay: (LocalDate) -> Boolean = Plan.WEEKDAYS): Boolean {
        if (t.open(today)) return false
        val through = completeThrough(t.path, t.from, t.to, tradingDay, record = true) ?: return false
        return !through.isBefore(t.to.minusDays(1))
    }

    /** The day a refresh of [path] (window from [from]) is asked from: its last stored candle's day, else [from]. */
    fun resumeFrom(path: String, from: LocalDate): LocalDate = lastT(path)?.let { day(it) }?.takeIf { it.isAfter(from) } ?: from

    /**
     * The stored rows of [path] and [fresh]'s, each minute once (a fresh row replaces the stored one of its minute), oldest
     * first: a refresh asked from the last stored day appends to the file and never loses a stored row (a reply that came
     * back empty leaves the file as it was).
     */
    fun mergedCandles(path: String, fresh: List<DhanApi.Candle>): List<DhanApi.Candle> {
        val m = java.util.TreeMap<Long, DhanApi.Candle>()
        for (l in lines(path)) parseCandle(l)?.let { m[it.t] = it }
        for (c in fresh) m[c.t] = c
        return m.values.toList()
    }

    fun mergedOptions(path: String, fresh: List<DhanApi.OptionCandle>): List<DhanApi.OptionCandle> {
        val m = java.util.TreeMap<Long, DhanApi.OptionCandle>()
        for (l in lines(path)) parseOption(l)?.let { m[it.t] = it }
        for (c in fresh) m[c.t] = c
        return m.values.toList()
    }

    // ---- writing replies --------------------------------------------------------------------------------------------

    fun candlesCsv(candles: List<DhanApi.Candle>): String = buildString(candles.size * 48 + 16) {
        append(CANDLE_HEADER).append('\n')
        for (c in candles.sortedBy { it.t }.distinctBy { it.t }) {
            append(c.t).append(',').append(num(c.open)).append(',').append(num(c.high)).append(',').append(num(c.low)).append(',')
                .append(num(c.close)).append(',').append(c.volume).append(',').append(c.oi).append('\n')
        }
    }

    fun optionsCsv(rows: List<DhanApi.OptionCandle>): String = buildString(rows.size * 64 + 24) {
        append(OPTION_HEADER).append('\n')
        for (c in rows.sortedBy { it.t }.distinctBy { it.t }) {
            append(c.t).append(',').append(num(c.open)).append(',').append(num(c.high)).append(',').append(num(c.low)).append(',')
                .append(num(c.close)).append(',').append(c.volume).append(',').append(c.oi).append(',').append(num(c.iv)).append(',')
                .append(num(c.strike)).append(',').append(num(c.spot)).append('\n')
        }
    }

    // ---- reading ----------------------------------------------------------------------------------------------------

    fun parseCandle(line: String): DhanApi.Candle? {
        val f = line.split(',')
        if (f.size < 5) return null
        return runCatching {
            DhanApi.Candle(f[0].toLong(), f[1].toDouble(), f[2].toDouble(), f[3].toDouble(), f[4].toDouble(),
                f.getOrNull(5)?.toLongOrNull() ?: 0L, f.getOrNull(6)?.toLongOrNull() ?: 0L)
        }.getOrNull()
    }

    fun parseOption(line: String): DhanApi.OptionCandle? {
        val f = line.split(',')
        if (f.size < 10) return null
        return runCatching {
            DhanApi.OptionCandle(f[0].toLong(), f[1].toDouble(), f[2].toDouble(), f[3].toDouble(), f[4].toDouble(), f[5].toLong(),
                f[6].toLong(), f[7].toDouble(), f[8].toDouble(), f[9].toDouble())
        }.getOrNull()
    }

    /** The windows (their first day) under a folder of dated files or dated folders, oldest first. */
    fun dated(dir: String): List<LocalDate> = file(dir).list()?.mapNotNull { n ->
        runCatching { LocalDate.parse(n.take(10)) }.getOrNull()
    }?.distinct()?.sorted() ?: emptyList()

    /** Every candle of [symbol] in [group] at "day" or "min", oldest first, each minute once. */
    fun candles(group: Plan.Group, symbol: String, interval: String): List<DhanApi.Candle> {
        val dir = "${group.dir}/${Plan.safe(symbol)}/$interval"
        val all = java.util.TreeMap<Long, DhanApi.Candle>()
        for (d in dated(dir)) for (l in lines("$dir/$d.csv.gz")) parseCandle(l)?.let { all[it.t] = it }
        return all.values.toList()
    }

    /** The symbols kept in [group] (folder names). */
    fun symbols(group: Plan.Group): List<String> = file(group.dir).list()?.sorted() ?: emptyList()

    // ---- expiries ---------------------------------------------------------------------------------------------------

    /** The expiry dates kept for [u] (from Dhan's expiry lists, as they were listed). */
    fun listedExpiries(u: String): List<LocalDate> =
        readText("expiries/${Plan.safe(u)}.csv")?.lineSequence()?.mapNotNull { runCatching { LocalDate.parse(it.trim()) }.getOrNull() }
            ?.distinct()?.sorted()?.toList() ?: emptyList()

    /** Add [dates] to the kept list (never removes one: a listed expiry stays a fact once it has passed). */
    fun addExpiries(u: String, dates: List<LocalDate>) {
        val all = (listedExpiries(u) + dates).distinct().sorted()
        writeText("expiries/${Plan.safe(u)}.csv", all.joinToString("\n", postfix = "\n"), gzip = false)
    }

    // ---- size, manifest, delete -------------------------------------------------------------------------------------

    /** Bytes on disk. */
    fun bytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Counts by part, for the screen and for Jarvis. */
    data class Manifest(val files: Int, val bytes: Long, val indices: List<String>, val stocks: Int, val futures: List<String>,
                        val optionWindows: Map<String, Int>, val dailyFrom: Map<String, LocalDate>, val minuteFrom: Map<String, LocalDate>,
                        val lastDay: LocalDate?)

    fun manifest(): Manifest {
        var files = 0; var bytes = 0L
        root.walkTopDown().filter { it.isFile }.forEach { files++; bytes += it.length() }
        val ix = symbols(Plan.Group.IDX)
        val opt = (file("opt").list() ?: emptyArray()).associateWith { u ->
            (file("opt/$u").list() ?: emptyArray()).sumOf { flag -> (file("opt/$u/$flag").list() ?: emptyArray()).count { it.length >= 10 } }
        }.filterValues { it > 0 }
        val dayFrom = ix.mapNotNull { u -> firstDay(Plan.Group.IDX, u, "day")?.let { u to it } }.toMap()
        val minFrom = ix.mapNotNull { u -> firstDay(Plan.Group.IDX, u, "min")?.let { u to it } }.toMap()
        val last = ix.mapNotNull { u -> lastDay(Plan.Group.IDX, u, "day") }.maxOrNull()
        return Manifest(files, bytes, ix, symbols(Plan.Group.EQ).size, symbols(Plan.Group.FUT), opt.toSortedMap(), dayFrom, minFrom, last)
    }

    private fun day(t: Long) = java.time.Instant.ofEpochSecond(t).atZone(com.optionslab.engine.IST).toLocalDate()

    /** The first day with a candle (the oldest non-empty file's first line). */
    fun firstDay(group: Plan.Group, symbol: String, interval: String): LocalDate? {
        val dir = "${group.dir}/${Plan.safe(symbol)}/$interval"
        for (d in dated(dir)) lines("$dir/$d.csv.gz").firstOrNull()?.let { l -> parseCandle(l)?.let { return day(it.t) } }
        return null
    }

    fun lastDay(group: Plan.Group, symbol: String, interval: String): LocalDate? {
        val dir = "${group.dir}/${Plan.safe(symbol)}/$interval"
        for (d in dated(dir).asReversed()) lines("$dir/$d.csv.gz").lastOrNull()?.let { l -> parseCandle(l)?.let { return day(it.t) } }
        return null
    }

    /** Option-chain snapshots older than [KEEP_CHAINS_DAYS] go. */
    fun pruneChains(today: LocalDate) {
        file("chain").listFiles()?.forEach { u ->
            u.listFiles()?.forEach { f -> runCatching { LocalDate.parse(f.name.take(10)) }.getOrNull()
                ?.takeIf { it.isBefore(today.minusDays(KEEP_CHAINS_DAYS)) }?.let { f.delete() } }
        }
    }

    /** Leftover partial writes (a run stopped mid-write) go. */
    fun dropPartials() { root.walkTopDown().filter { it.isFile && it.name.endsWith(".part") }.forEach { it.delete() } }

    /** Everything downloaded goes. */
    fun deleteAll(): Boolean { synchronized(this) { ledger = null }; return !root.exists() || root.deleteRecursively() }
}
