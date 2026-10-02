package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

/** How busy a market is today against its own recent days, at the same point of the day. */
enum class Mood(val word: String) { QUIET("quiet"), NORMAL("normal"), BUSY("busy"), WILD("wild") }

/** The trend tracker on one chart: up or down, its line, and the candle where it last turned (null: no turn seen). */
data class TrendRead(val minutes: Int, val up: Boolean, val line: Double, val since: LocalDateTime?)

data class Level(val name: String, val price: Double)

/**
 * What Ira knows about one market right now. Every number an answer quotes comes from here (or from the news and the
 * pattern book), so an answer can always be checked against it.
 */
data class Snapshot(
    val market: Market,
    val at: LocalDateTime,                 // the last 1-minute bar
    val trading: Boolean,                  // the market is open at [at] + 1 minute
    val price: Double,
    val prevClose: Double?,
    val open: Double, val high: Double, val low: Double,
    val openingHigh: Double?, val openingLow: Double?,   // the first 15 minutes (indices)
    val trends: List<TrendRead>,
    val mood: Mood?, val rangeRatio: Double?,            // today's range against the usual by this time of day
    val above: List<Level>, val below: List<Level>,      // nearest levels, closest first
    val patterns: List<Pattern>,                         // today's, newest first
) {
    val change: Double? get() = prevClose?.let { price - it }
    val changePct: Double? get() = prevClose?.let { (price - it) / it * 100 }
    fun trend(minutes: Int): TrendRead? = trends.firstOrNull { it.minutes == minutes }
}

/** Builds a [Snapshot] from a market's 1-minute history. Pure. */
object Brain {
    const val OPENING_MINUTES = 15
    val CHARTS = listOf(15, 60)
    const val MIN_CANDLES = 30
    const val MOOD_DAYS = 20

    fun read(h: History): Snapshot? {
        val bars = h.bars
        if (bars.isEmpty()) return null
        val last = bars.last()
        val today = last.t.toLocalDate()
        val days = h.days
        val todayBars = bars.filter { it.t.toLocalDate() == today }
        val prevDay = days.lastOrNull { it < today }
        val prevBars = prevDay?.let { d -> bars.filter { it.t.toLocalDate() == d } }.orEmpty()
        val fed = last.t.plusMinutes(1)

        val trends = CHARTS.mapNotNull { m ->
            val c = Candles.closed(Candles.fold(bars, m, h.market), m, fed)
            if (c.size < MIN_CANDLES) null else {
                val (dir, line) = Candles.tracker(c)
                val i = c.size - 1
                var j = i
                while (j > 0 && dir[j - 1] == dir[i]) j--
                TrendRead(m, dir[i] == 1, line[i], if (j > 0) c[j].t else null)
            }
        }

        val opening = if (h.market.open != null) todayBars.take(OPENING_MINUTES) else emptyList()
        val (mood, ratio) = mood(h, today, todayBars)

        // Levels: yesterday's high, low and close; today's opening range; swing points of the last five days on 15m.
        val price = last.c
        val levels = ArrayList<Level>()
        if (prevBars.isNotEmpty()) {
            levels += Level("yesterday's high", prevBars.maxOf { it.h })
            levels += Level("yesterday's low", prevBars.minOf { it.l })
            levels += Level("yesterday's close", prevBars.last().c)
        }
        if (opening.size == OPENING_MINUTES && todayBars.size > OPENING_MINUTES) {
            levels += Level("opening-range high", opening.maxOf { it.h })
            levels += Level("opening-range low", opening.minOf { it.l })
        }
        val recentFrom = days.takeLast(5).firstOrNull()
        val c15 = Candles.closed(Candles.fold(bars.filter { recentFrom == null || !it.t.toLocalDate().isBefore(recentFrom) }, 15, h.market), 15, fed)
        val (sh, sl) = Candles.swings(c15, 3)
        sh.forEach { levels += Level("swing high of ${when_(c15[it].t, h.market)}", c15[it].h) }
        sl.forEach { levels += Level("swing low of ${when_(c15[it].t, h.market)}", c15[it].l) }
        val above = levels.filter { it.price > price }.sortedBy { it.price - price }.distinctBy { "%.1f".format(Locale.ENGLISH, it.price) }.take(3)
        val below = levels.filter { it.price < price }.sortedBy { price - it.price }.distinctBy { "%.1f".format(Locale.ENGLISH, it.price) }.take(3)

        // Today's patterns on the 15 and 60-minute charts, newest first.
        val patterns = listOf(15, 60).flatMap { m ->
            val c = Candles.closed(Candles.fold(bars, m, h.market), m, fed)
            val first = c.indexOfFirst { it.t.toLocalDate() == today }
            if (first < 0) emptyList() else Patterns.scan(c, m, first)
        }.sortedByDescending { it.at }

        return Snapshot(
            h.market, last.t, h.market.trading(fed), price, prevBars.lastOrNull()?.c,
            todayBars.first().o, todayBars.maxOf { it.h }, todayBars.minOf { it.l },
            opening.takeIf { it.size == OPENING_MINUTES }?.maxOf { it.h }, opening.takeIf { it.size == OPENING_MINUTES }?.minOf { it.l },
            trends, mood, ratio, above, below, patterns,
        )
    }

    /** Today's range so far against the median range of the last [MOOD_DAYS] days over the same number of minutes. */
    private fun mood(h: History, today: LocalDate, todayBars: List<Candle>): Pair<Mood?, Double?> {
        val n = todayBars.size
        if (n < 5) return null to null
        val past = h.days.filter { it < today }.takeLast(MOOD_DAYS).map { d -> h.day(d).take(n) }.filter { it.size == n }
        if (past.size < 5) return null to null
        val usual = past.map { b -> b.maxOf { it.h } - b.minOf { it.l } }.sorted()[past.size / 2]
        if (usual <= 0) return null to null
        val ratio = (todayBars.maxOf { it.h } - todayBars.minOf { it.l }) / usual
        val mood = when {
            ratio < 0.6 -> Mood.QUIET
            ratio < 1.3 -> Mood.NORMAL
            ratio < 2.0 -> Mood.BUSY
            else -> Mood.WILD
        }
        return mood to ratio
    }

    /** A time as Ira says it: "11:15" for the indices (IST), "11:15 UTC (16:45 IST)" for gold; another day adds its date. */
    fun when_(t: LocalDateTime, m: Market, today: LocalDate? = null): String {
        val hm = "%02d:%02d".format(t.hour, t.minute)
        val day = if (today != null && t.toLocalDate() == today) "" else "${t.dayOfMonth} ${t.month.getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH)} "
        return if (m.open == null) { val ist = t.plusMinutes(330); "$day$hm UTC (%02d:%02d IST)".format(ist.hour, ist.minute) } else "$day$hm"
    }
}
