package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale
import kotlin.math.abs

/**
 * What the market recorder has saved, read back for Boss's "Market data" viewer (06 Oct 2026): one day file's lines
 * ([MarketRecord] / [RecorderFeeds] formats) into the day's index levels, index futures (OI, basis, the OI build-up),
 * the futures' book (spread and buy/sell imbalance per [SLOT_MIN] minutes), the headlines with the moves after them
 * ([NewsImpact]), GIFT Nifty before the open against NIFTY's own open, and NSE's participant-wise OI; and, once
 * [MIN_DAYS] days are recorded, the running "first lessons". Information only, after the fact; nothing here acts.
 *
 * Tolerant by design: a broken line, a missing field or a half-written day is skipped, never thrown; a day with nothing
 * recorded is an empty [Day]. Pure: the app decrypts the file and hands the lines in.
 */
object RecorderStats {
    /** The book's slots (minutes). */
    const val SLOT_MIN = 15
    /** The lessons need this many recorded days. */
    const val MIN_DAYS = 20
    /** FII bars shown. */
    const val FII_DAYS = 10
    /** NIFTY's open: the first level recorded from 09:15, if it is no later than this. */
    val OPEN: LocalTime = LocalTime.of(9, 15)
    val OPEN_BY: LocalTime = LocalTime.of(9, 17, 59)
    /** NIFTY's close: the last level recorded, if it is no earlier than this. */
    val CLOSE_FROM: LocalTime = LocalTime.of(15, 25)

    data class FuturePoint(val t: LocalTime, val last: Double, val oi: Long?, val basis: Double?)
    data class DepthPoint(val t: LocalTime, val what: String, val spread: Double?, val buyQty: Long?, val sellQty: Long?)
    data class GiftPoint(val t: LocalTime, val last: Double)
    data class GapNote(val t: LocalTime?, val what: String, val why: String)

    /** One day file read back. [records]: the lines that are data (not the header, not a gap). */
    data class Day(
        val date: LocalDate?,
        val levels: Map<String, List<NewsImpact.Level>>,
        /** Index future name -> its minutes; [symbols] maps each future's symbol to its name. */
        val futures: Map<String, List<FuturePoint>>,
        val symbols: Map<String, String>,
        val depth: List<DepthPoint>,
        val news: List<NewsImpact.Seen>,
        val gift: List<GiftPoint>,
        val participants: List<RecorderFeeds.Participants>,
        val gaps: List<GapNote>,
        val records: Int,
        /** Frames that could not be opened (a torn or damaged part of the file). */
        val bad: Int,
    ) {
        val empty: Boolean get() = records == 0
        /** Something is missing: unreadable parts or recorded gaps. */
        val partial: Boolean get() = bad > 0 || gaps.isNotEmpty()
    }

    private fun time(s: String): LocalTime? = runCatching { LocalTime.parse(s) }.getOrNull()

    /** A day file's lines (as [MarketRecord.read] returns them) back; [bad] its unreadable frames. */
    fun parse(lines: List<String>, bad: Int = 0): Day {
        var date: LocalDate? = null
        val levels = HashMap<String, MutableList<NewsImpact.Level>>()
        val futures = HashMap<String, MutableList<FuturePoint>>()
        val symbols = HashMap<String, String>()
        val depth = ArrayList<DepthPoint>()
        val news = ArrayList<NewsImpact.Seen>()
        val seenNews = HashSet<String>()
        val gift = ArrayList<GiftPoint>()
        val pLines = ArrayList<String>()
        val gaps = ArrayList<GapNote>()
        var records = 0
        for (line in lines) {
            if (line.length < 2 || line[1] != ',') continue
            when (line[0]) {
                'H' -> { if (date == null) date = MarketRecord.fields(line).getOrNull(2)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } }
                'S' -> {
                    val f = MarketRecord.fields(line)
                    val t = time(f.getOrNull(1) ?: "") ?: continue
                    val v = f.getOrNull(3)?.toDoubleOrNull()?.takeIf { it > 0 } ?: continue
                    levels.getOrPut(f[2]) { ArrayList() } += NewsImpact.Level(t, v); records++
                }
                'F' -> {
                    val f = MarketRecord.fields(line)
                    if (f.size < 10) continue
                    val t = time(f[1]) ?: continue
                    val last = f[5].toDoubleOrNull()?.takeIf { it > 0 } ?: continue
                    futures.getOrPut(f[2]) { ArrayList() } += FuturePoint(t, last, f[7].toLongOrNull(), f[9].toDoubleOrNull())
                    symbols[f[3]] = f[2]; records++
                }
                'D' -> {
                    val f = MarketRecord.fields(line)
                    if (f.size < 11) continue
                    val t = time(f[1]) ?: continue
                    depth += DepthPoint(t, f[2], f[10].toDoubleOrNull()?.takeIf { it >= 0 }, f[8].toLongOrNull(), f[9].toLongOrNull()); records++
                }
                'N' -> {
                    val h = NewsImpact.headline(line) ?: continue
                    records++
                    if (seenNews.add(h.link.ifBlank { h.title }.trim())) news += h
                }
                'I' -> {
                    val f = MarketRecord.fields(line)
                    val t = time(f.getOrNull(1) ?: "") ?: continue
                    val v = f.getOrNull(4)?.toDoubleOrNull()?.takeIf { it > 0 } ?: continue
                    gift += GiftPoint(t, v); records++
                }
                'P' -> { pLines += line; records++ }
                'G' -> {
                    val f = MarketRecord.fields(line)
                    gaps += GapNote(time(f.getOrNull(1) ?: ""), f.getOrNull(2) ?: "", f.getOrNull(3) ?: "")
                }
                'E', 'X', 'B', 'C', 'L' -> records++
            }
        }
        return Day(
            date,
            levels.mapValues { (_, v) -> v.sortedBy { it.t } },
            futures.mapValues { (_, v) -> v.sortedBy { it.t } },
            symbols, depth.sortedBy { it.t }, news, gift.sortedBy { it.t }, participants(pLines), gaps, records, bad,
        )
    }

    // ---- participant-wise OI ------------------------------------------------------------------------------------------

    /** The P records of the OI file (not the volume file), by trade date, newest first; a broken row is skipped. */
    fun participants(lines: List<String>): List<RecorderFeeds.Participants> {
        val by = LinkedHashMap<LocalDate, LinkedHashMap<String, RecorderFeeds.Participant>>()
        for (l in lines) {
            if (!l.startsWith("P,")) continue
            val f = l.split(',')
            if (f.size < 5 + RecorderFeeds.COLUMNS.size || f[3] != "oi") continue
            val d = runCatching { LocalDate.parse(f[1]) }.getOrNull() ?: continue
            val nums = f.subList(5, 5 + RecorderFeeds.COLUMNS.size).map { it.toLongOrNull() }
            if (nums.any { it == null }) continue
            by.getOrPut(d) { LinkedHashMap() }[f[4]] = RecorderFeeds.Participant(f[4], nums.map { it!! })
        }
        return by.entries.sortedByDescending { it.key }.map { (d, rows) -> RecorderFeeds.Participants(d, rows.values.toList()) }
    }

    /** FII's index-futures longs as a share of its index-futures positions (%) on one trade date. */
    data class FiiDay(val date: LocalDate, val longPct: Double)

    fun fiiLong(p: RecorderFeeds.Participants): FiiDay? {
        val fii = p.rows.firstOrNull { it.client == "FII" } ?: return null
        val long = fii.figures[0]; val short = fii.figures[1]
        if (long + short <= 0) return null
        return FiiDay(p.date, long * 100.0 / (long + short))
    }

    /** The newest [n] trade dates' FII long %, oldest first (one per date, whichever day file held it). */
    fun fiiLong(all: List<RecorderFeeds.Participants>, n: Int = FII_DAYS): List<FiiDay> =
        all.distinctBy { it.date }.mapNotNull { fiiLong(it) }.sortedBy { it.date }.takeLast(n)

    // ---- the futures --------------------------------------------------------------------------------------------------

    /** The standard reading of price against open interest. */
    enum class BuildUp(val label: String, val meaning: String) {
        LONG_BUILD_UP("Long build-up", "price up, OI up: new longs"),
        SHORT_BUILD_UP("Short build-up", "price down, OI up: new shorts"),
        LONG_UNWINDING("Long unwinding", "price down, OI down: longs closing"),
        SHORT_COVERING("Short covering", "price up, OI down: shorts closing"),
    }

    /** [dPrice] and [dOi] over the same span: null when either did not move. */
    fun buildUp(dPrice: Double, dOi: Long): BuildUp? = when {
        dPrice == 0.0 || dOi == 0L -> null
        dPrice > 0 && dOi > 0 -> BuildUp.LONG_BUILD_UP
        dPrice < 0 && dOi > 0 -> BuildUp.SHORT_BUILD_UP
        dPrice < 0 -> BuildUp.LONG_UNWINDING
        else -> BuildUp.SHORT_COVERING
    }

    /** One index future through the day: its OI and basis, and the day's build-up (first to last minute with OI). */
    data class FutureDay(
        val name: String, val oi: List<Long>, val basis: List<Double>, val firstPrice: Double, val lastPrice: Double,
        val firstOi: Long?, val lastOi: Long?, val buildUp: BuildUp?, val lastBasis: Double?,
    ) {
        val oiChange: Long? get() = if (firstOi != null && lastOi != null) lastOi - firstOi else null
        val priceChange: Double get() = lastPrice - firstPrice
    }

    fun futures(day: Day): List<FutureDay> = (MarketRecord.FUTURES + day.futures.keys.filter { it !in MarketRecord.FUTURES }.sorted()).mapNotNull { name ->
        val pts = day.futures[name]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val withOi = pts.filter { it.oi != null && it.oi > 0 }
        val first = withOi.firstOrNull(); val last = withOi.lastOrNull()
        val bu = if (first != null && last != null && first !== last) buildUp(last.last - first.last, last.oi!! - first.oi!!) else null
        FutureDay(name, withOi.map { it.oi!! }, pts.mapNotNull { it.basis }, pts.first().last, pts.last().last,
            first?.oi, last?.oi, bu, pts.lastOrNull { it.basis != null }?.basis)
    }

    /** At most [max] points, evenly picked (the first and last kept): a sparkline needs no more. */
    fun <T> thin(values: List<T>, max: Int = 120): List<T> {
        if (values.size <= max || max < 2) return values
        return (0 until max).map { i -> values[(i.toLong() * (values.size - 1) / (max - 1)).toInt()] }
    }

    // ---- the book -----------------------------------------------------------------------------------------------------

    /** (buy - sell) / (buy + sell), -1..+1: positive = more waiting to buy. Null without both or with none. */
    fun imbalance(buy: Long?, sell: Long?): Double? {
        if (buy == null || sell == null || buy < 0 || sell < 0 || buy + sell == 0L) return null
        return (buy - sell).toDouble() / (buy + sell)
    }

    /** One [SLOT_MIN]-minute slot of a future's book: the average spread and the slot's total buy/sell imbalance. */
    data class DepthSlot(val start: LocalTime, val avgSpread: Double?, val imbalance: Double?, val samples: Int)

    private fun slotOf(t: LocalTime): LocalTime = LocalTime.of(t.hour, t.minute / SLOT_MIN * SLOT_MIN)

    /** Each index future's book by slot (future name -> slots in time order); options' books are left out. */
    fun depth(day: Day): Map<String, List<DepthSlot>> {
        val out = LinkedHashMap<String, List<DepthSlot>>()
        val byName = day.depth.filter { it.what in day.symbols }.groupBy { day.symbols.getValue(it.what) }
        for (name in MarketRecord.FUTURES + byName.keys.filter { it !in MarketRecord.FUTURES }.sorted()) {
            val rows = byName[name] ?: continue
            out[name] = rows.groupBy { slotOf(it.t) }.toSortedMap().map { (start, rs) ->
                val sp = rs.mapNotNull { it.spread }
                val pairs = rs.filter { it.buyQty != null && it.sellQty != null }
                DepthSlot(start, if (sp.isEmpty()) null else sp.average(),
                    imbalance(pairs.sumOf { it.buyQty!! }.takeIf { pairs.isNotEmpty() }, pairs.sumOf { it.sellQty!! }.takeIf { pairs.isNotEmpty() }), rs.size)
            }
        }
        return out
    }

    /** "+0.12 buy-side" / "-0.30 sell-side" / "even". */
    fun imbalanceText(x: Double): String = when {
        abs(x) < 0.005 -> "even"
        x > 0 -> String.format(Locale.ENGLISH, "+%.2f buy-side", x)
        else -> String.format(Locale.ENGLISH, "%.2f sell-side", x)
    }

    // ---- GIFT Nifty against the open ----------------------------------------------------------------------------------

    /** The last GIFT Nifty reading before 09:15 that day. */
    fun giftPreOpen(day: Day): Double? = day.gift.lastOrNull { it.t.isBefore(OPEN) }?.last

    /** NIFTY's open as recorded: its first level from 09:15, if recorded by [OPEN_BY]. */
    fun open(day: Day): Double? = day.levels["NIFTY"]?.firstOrNull { !it.t.isBefore(OPEN) }?.takeIf { !it.t.isAfter(OPEN_BY) }?.value

    /** NIFTY's close as recorded: its last level, if from [CLOSE_FROM]. */
    fun close(day: Day): Double? = day.levels["NIFTY"]?.lastOrNull()?.takeIf { !it.t.isBefore(CLOSE_FROM) }?.value

    /**
     * GIFT Nifty's last pre-open reading against NIFTY's recorded open: [error] = GIFT - open (points). With the previous
     * recorded close, the gap GIFT pointed to and the gap there was. GIFT is a future: part of the error is its premium.
     */
    data class GapDay(val date: LocalDate, val gift: Double, val open: Double, val prevClose: Double?) {
        val error: Double get() = gift - open
        val predictedGap: Double? get() = prevClose?.let { gift - it }
        val actualGap: Double? get() = prevClose?.let { open - it }
    }

    // ---- the day in short, and the lessons ----------------------------------------------------------------------------

    /** What the cross-day views need from a day (kept per day, so the lessons do not re-read every file). */
    data class Summary(
        val date: LocalDate, val records: Int, val tone: NewsImpact.Tally, val headlines: Int,
        val gift: Double?, val open: Double?, val close: Double?, val participants: List<RecorderFeeds.Participants>,
    )

    fun summary(date: LocalDate, day: Day): Summary {
        val impacts = NewsImpact.impacts(day.news, day.levels)
        return Summary(day.date ?: date, day.records, NewsImpact.tally(impacts), day.news.size, giftPreOpen(day), open(day), close(day), day.participants)
    }

    /** Each recorded day's GIFT-vs-open, oldest first; the previous close only from the recorded day just before (within 5 days). */
    fun gaps(summaries: List<Summary>): List<GapDay> {
        val sorted = summaries.sortedBy { it.date }
        return sorted.mapIndexedNotNull { i, s ->
            val g = s.gift ?: return@mapIndexedNotNull null
            val o = s.open ?: return@mapIndexedNotNull null
            val prev = sorted.getOrNull(i - 1)?.takeIf { !it.date.isBefore(s.date.minusDays(5)) }?.close
            GapDay(s.date, g, o, prev)
        }
    }

    data class Lessons(
        val days: Int, val tone: NewsImpact.Tally, val gapDays: Int, val gapMae: Double?,
    ) {
        fun lines(): List<Pair<String, String>> = listOf(
            "Days recorded" to "$days",
            "News tone vs NIFTY ${NewsImpact.LESSON_HORIZON} min later" to (tone.rate?.let {
                String.format(Locale.ENGLISH, "%.0f%% went the tone's way (%d of %d headlines)", it * 100, tone.agreed, tone.n)
            } ?: "no toned headline with a recorded move yet"),
            "GIFT Nifty vs the open" to (gapMae?.let { String.format(Locale.ENGLISH, "off by %.0f points on average (%d days)", it, gapDays) }
                ?: "no day with both recorded yet"),
        )
    }

    /** Days with data (not only a header or gaps). */
    fun recordedDays(summaries: List<Summary>): Int = summaries.filter { it.records > 0 }.distinctBy { it.date }.size

    /** The first lessons, from [MIN_DAYS] recorded days on; null before. */
    fun lessons(summaries: List<Summary>): Lessons? {
        val days = summaries.filter { it.records > 0 }.distinctBy { it.date }
        if (days.size < MIN_DAYS) return null
        val tone = days.fold(NewsImpact.Tally(0, 0)) { a, s -> a + s.tone }
        val g = gaps(days)
        return Lessons(days.size, tone, g.size, if (g.isEmpty()) null else g.map { abs(it.error) }.average())
    }

    /** What the lessons panel says before and under its figures. */
    fun waiting(days: Int): String = "First lessons from $MIN_DAYS recorded days: $days so far."
    const val LESSON_CAVEAT = "Needs months before it means anything: a few weeks of headlines and opens is a small, noisy sample, " +
        "and a headline before a move does not say it caused it. GIFT Nifty is a future, so part of its difference from the open is its premium."
}
