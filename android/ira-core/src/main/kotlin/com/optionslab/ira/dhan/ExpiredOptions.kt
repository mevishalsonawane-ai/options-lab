package com.optionslab.ira.dhan

import com.optionslab.engine.IST
import com.optionslab.engine.Right
import com.optionslab.engine.Series
import com.optionslab.engine.Session
import java.time.Instant
import java.time.LocalDate
import java.util.TreeMap
import java.util.TreeSet
import kotlin.math.abs

/**
 * The read API over the stored expired options, for studies that scan every option the store holds (zero-to-hero and
 * hero-to-zero moves, the Hero expiry arm's tuning) and for the replays that take [Session]s.
 *
 * Dhan serves expired options as ROLLING series: "the near contract, n strikes from the money", each minute carrying the
 * strike it was and the index then. [scan] turns them back into one series per contract - (expiry, strike, side) - with
 * the index alongside every minute, in one pass over the files, holding one window (25 days) and the contracts still open
 * at its end in memory. The contract a minute belongs to is the near expiry on or after its day: [expiries] are the dates
 * Dhan listed (kept as they passed) joined with the expiry days [inferExpiries] reads from the data itself (on an expiry
 * day the at-the-money straddle's last price is almost all intrinsic value). Pure JVM.
 */
object ExpiredOptions {
    /** One minute of one contract: prices, volume, OI, IV, the index ([spot]) and how far from the money it was ([offset]). */
    data class Bar(val t: Long, val open: Double, val high: Double, val low: Double, val close: Double, val volume: Long, val oi: Long,
                   val iv: Double, val spot: Double, val offset: Int) {
        val day: LocalDate get() = Instant.ofEpochSecond(t).atZone(IST).toLocalDate()
        /** IST minute of the day (9:15 = 555). */
        val minute: Int get() = Instant.ofEpochSecond(t).atZone(IST).let { it.hour * 60 + it.minute }
    }

    /** One contract's minutes, oldest first, every stored day up to its expiry. */
    data class OptionSeries(val underlying: String, val flag: String, val expiry: LocalDate, val strike: Double, val right: Right,
                            val bars: List<Bar>)

    /** Below this share of the index, the at-the-money straddle's time value at the last minute marks an expiry day. */
    const val EXPIRY_TIME_VALUE = 0.0012

    /** The rolling flags kept for [u] (WEEK, MONTH). */
    fun flags(files: Files, u: String): List<String> = files.file("opt/${Plan.safe(u)}").list()?.sorted() ?: emptyList()

    /** The underlyings with expired options stored. */
    fun underlyings(files: Files): List<String> = files.file("opt").list()?.sorted() ?: emptyList()

    /** The stored windows of [u]'s [flag] series: their first day, oldest first. */
    fun windows(files: Files, u: String, flag: String): List<LocalDate> = files.dated("opt/${Plan.safe(u)}/$flag")

    private fun parseName(name: String): Pair<Right, Int>? {
        val base = name.removeSuffix(".csv.gz")
        if (base.length < 3) return null
        val r = when (base.take(2)) { "CE" -> Right.CE; "PE" -> Right.PE; else -> return null }
        return r to (base.drop(2).toIntOrNull() ?: return null)
    }

    /**
     * Expiry days read from the data: a day whose last at-the-money call and put (offset 0) were worth almost only their
     * intrinsic value - the straddle's time value under [EXPIRY_TIME_VALUE] of the index.
     */
    fun inferExpiries(files: Files, u: String, flag: String): List<LocalDate> {
        val out = TreeSet<LocalDate>()
        for (w in windows(files, u, flag)) {
            val dir = "opt/${Plan.safe(u)}/$flag/$w"
            val ce = lastOfDay(files, "$dir/CE+0.csv.gz")
            val pe = lastOfDay(files, "$dir/PE+0.csv.gz")
            for ((day, c) in ce) {
                val p = pe[day] ?: continue
                if (c.spot <= 0 || abs(c.strike - p.strike) > 1e-6) continue
                val timeValue = c.close + p.close - abs(c.spot - c.strike)
                if (timeValue / c.spot < EXPIRY_TIME_VALUE) out += day
            }
        }
        return out.toList()
    }

    private fun lastOfDay(files: Files, path: String): Map<LocalDate, DhanApi.OptionCandle> {
        val m = HashMap<LocalDate, DhanApi.OptionCandle>()
        for (l in files.lines(path)) {
            val c = files.parseOption(l) ?: continue
            val d = Instant.ofEpochSecond(c.t).atZone(IST).toLocalDate()
            val cur = m[d]
            if (cur == null || c.t > cur.t) m[d] = c
        }
        return m
    }

    /** The expiry dates for [u]'s [flag] series: listed ones joined with the inferred ones. */
    fun expiries(files: Files, u: String, flag: String): List<LocalDate> =
        (files.listedExpiries(u) + inferExpiries(files, u, flag)).distinct().sorted()

    private data class Key(val expiry: LocalDate, val strike: Double, val right: Right) : Comparable<Key> {
        override fun compareTo(other: Key): Int = compareValuesBy(this, other, { it.expiry }, { it.strike }, { it.right })
    }

    /**
     * Every expired contract of [u]'s [flag] series, one [OptionSeries] each, in expiry order (then strike, call before
     * put): only contracts that expired before [before]. [expiries] defaults to [expiries]. One pass; a window's rows and
     * the contracts still open at its end are all that is held.
     */
    fun scan(files: Files, u: String, flag: String, before: LocalDate, expiries: List<LocalDate> = expiries(files, u, flag)): Sequence<OptionSeries> = sequence {
        val exp = TreeSet(expiries)
        val open = TreeMap<Key, TreeMap<Long, Bar>>()
        val ws = windows(files, u, flag)
        for ((i, w) in ws.withIndex()) {
            val end = w.plusDays(Plan.OPT_WINDOW_DAYS)
            val dir = files.file("opt/${Plan.safe(u)}/$flag/$w")
            for (f in dir.listFiles()?.sortedBy { it.name } ?: emptyList()) {
                val (right, offset) = parseName(f.name) ?: continue
                for (l in files.lines(f)) {
                    val c = files.parseOption(l) ?: continue
                    val bar = Bar(c.t, c.open, c.high, c.low, c.close, c.volume, c.oi, c.iv, c.spot, offset)
                    val day = bar.day
                    if (day.isBefore(w) || !day.isBefore(end)) continue     // a neighbouring window's day: read there
                    val e = exp.ceiling(day) ?: continue                       // not yet expired (no later expiry known)
                    open.getOrPut(Key(e, c.strike, right)) { TreeMap() }[c.t] = bar
                }
            }
            // Contracts expiring before the next window starts are complete.
            val next = ws.getOrNull(i + 1) ?: LocalDate.MAX
            while (open.isNotEmpty() && open.firstKey().expiry.isBefore(next)) {
                val (k, bars) = open.pollFirstEntry()
                if (k.expiry.isBefore(before)) yield(OptionSeries(u, flag, k.expiry, k.strike, k.right, bars.values.toList()))
            }
        }
        for ((k, bars) in open) if (k.expiry.isBefore(before)) yield(OptionSeries(u, flag, k.expiry, k.strike, k.right, bars.values.toList()))
    }

    // ---- as the app's sessions ----------------------------------------------------------------------------------------

    /**
     * The store's days of [u] as the app's [Session]s (the index's minutes and each stored contract's minutes that day),
     * oldest first, for days before [before] only - the replays (the ORB arms, the Strategy Lab presets) read these
     * after the bundled and harvested days. [lot] gives the lot on a day (null: the options are left out that day, the
     * index kept). One window held at a time.
     */
    fun sessions(files: Files, u: String, flag: String, before: LocalDate, lot: (LocalDate) -> Int?,
                 expiries: List<LocalDate> = expiries(files, u, flag)): Sequence<Session> = sequence {
        val exp = TreeSet(expiries)
        val minuteDir = "${Plan.Group.IDX.dir}/${Plan.safe(u)}/min"
        val minuteWindows = files.dated(minuteDir)
        var loadedWindow: LocalDate? = null
        var indexByDay: Map<LocalDate, List<DhanApi.Candle>> = emptyMap()
        fun indexOn(day: LocalDate): List<DhanApi.Candle> {
            val w = minuteWindows.lastOrNull { !it.isAfter(day) } ?: return emptyList()
            if (w != loadedWindow) {
                loadedWindow = w
                indexByDay = files.lines("$minuteDir/$w.csv.gz").mapNotNull { files.parseCandle(it) }
                    .groupBy { Instant.ofEpochSecond(it.t).atZone(IST).toLocalDate() }
            }
            return indexByDay[day] ?: emptyList()
        }
        for (w in windows(files, u, flag)) {
            val end = w.plusDays(Plan.OPT_WINDOW_DAYS)
            val byDay = TreeMap<LocalDate, HashMap<Key, TreeMap<Int, Bar>>>()
            val dir = files.file("opt/${Plan.safe(u)}/$flag/$w")
            for (f in dir.listFiles()?.sortedBy { it.name } ?: emptyList()) {
                val (right, offset) = parseName(f.name) ?: continue
                for (l in files.lines(f)) {
                    val c = files.parseOption(l) ?: continue
                    val bar = Bar(c.t, c.open, c.high, c.low, c.close, c.volume, c.oi, c.iv, c.spot, offset)
                    val day = bar.day
                    if (day.isBefore(w) || !day.isBefore(end) || !day.isBefore(before)) continue
                    val e = exp.ceiling(day) ?: continue
                    byDay.getOrPut(day) { HashMap() }.getOrPut(Key(e, c.strike, right)) { TreeMap() }[bar.minute] = bar
                }
            }
            for ((day, contracts) in byDay) {
                val series = ArrayList<Series>()
                val ix = indexOn(day).sortedBy { it.t }.distinctBy { minuteOf(it.t) }
                if (ix.isNotEmpty()) series += Series(null, 0.0, Right.IX, 0, IntArray(ix.size) { minuteOf(ix[it].t) },
                    DoubleArray(ix.size) { ix[it].close }, DoubleArray(ix.size) { ix[it].open }, DoubleArray(ix.size) { ix[it].high },
                    DoubleArray(ix.size) { ix[it].low }, LongArray(ix.size) { ix[it].volume }, LongArray(ix.size))
                val dayLot = runCatching { lot(day) }.getOrNull()
                if (dayLot != null && dayLot > 0) for ((k, m) in contracts.toSortedMap()) {
                    val b = m.values.toList()
                    series += Series(k.expiry, k.strike, k.right, dayLot, IntArray(b.size) { b[it].minute }, DoubleArray(b.size) { b[it].close },
                        DoubleArray(b.size) { b[it].open }, DoubleArray(b.size) { b[it].high }, DoubleArray(b.size) { b[it].low },
                        LongArray(b.size) { b[it].volume }, LongArray(b.size) { b[it].oi })
                }
                if (series.isNotEmpty()) yield(Session(day, null, series))
            }
        }
    }

    private fun minuteOf(t: Long) = Instant.ofEpochSecond(t).atZone(IST).let { it.hour * 60 + it.minute }
}
