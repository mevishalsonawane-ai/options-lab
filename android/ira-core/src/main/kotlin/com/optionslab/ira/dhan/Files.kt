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
 *
 * t is epoch seconds. One file is one request's reply (or just the header when Dhan had nothing), written whole and
 * renamed into place, so a file is either complete or absent. Plain gzip CSV: one pass reads it, any tool opens it.
 * Market prices only: nothing personal is ever written here. Pure JVM (java.io), so it is tested on the JVM.
 */
class Files(val root: File) {
    companion object {
        const val CANDLE_HEADER = "t,o,h,l,c,v,oi"
        const val OPTION_HEADER = "t,o,h,l,c,v,oi,iv,k,s"
        /** Option-chain snapshots older than this many days are deleted. */
        const val KEEP_CHAINS_DAYS = 30L

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
    fun deleteAll(): Boolean = !root.exists() || root.deleteRecursively()
}
