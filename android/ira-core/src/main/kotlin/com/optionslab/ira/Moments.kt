package com.optionslab.ira

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

/**
 * Market moments Jarvis tells unasked (Jarvis self-improvement, 2026-10-05): an index filling its opening gap, and an
 * index going past the previous session's high or low (with how far back that price was last seen). Each is told on the
 * move only - the caller keeps the last [Look] and passes it in, so a state already there at the first look (a restart,
 * the app opened late) is not news - and once per day per kind (the caller keeps the [Alert.key]s). Facts from the
 * snapshot and the candles on the phone only: never a forecast, never advice. Pure.
 */
object Moments {
    /** A gap smaller than this (% of the previous close) is not worth telling when it fills. */
    const val GAP_MIN_PCT = 0.2

    /** Where an index stood at one look: a gap still open, above the previous session's high, below its low. */
    data class Look(val gapOpen: Boolean, val aboveHigh: Boolean, val belowLow: Boolean)
    data class Alert(val key: String, val title: String, val text: String)

    /** One earlier session in [bars]: its day, high and low. */
    data class Day(val day: LocalDate, val high: Double, val low: Double)

    private val DATE = DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH)
    private fun px(m: Market, x: Double) = m.price(x)
    private fun pct(x: Double) = "%+.2f%%".format(Locale.ENGLISH, x)
    private fun pts(x: Double) = "%,.2f".format(Locale.ENGLISH, abs(x))

    /** The sessions in [bars] before [day], oldest first. */
    fun earlier(bars: List<Candle>, day: LocalDate): List<Day> =
        bars.filter { it.t.toLocalDate() < day }.groupBy { it.t.toLocalDate() }.toSortedMap()
            .map { (d, c) -> Day(d, c.maxOf { it.h }, c.minOf { it.l }) }

    private fun gapPct(s: Snapshot): Double? = s.prevClose?.let { (s.open - it) / it * 100 }

    /**
     * [s]'s state now, with [bars] its 1-minute candles over several days. Null for gold and VIX (no gap or range to
     * tell), for a closed market, and without a previous close or an earlier session on the phone.
     */
    fun look(s: Snapshot, bars: List<Candle>): Look? {
        if (s.market == Market.GOLD || s.market == Market.VIX || !s.trading) return null
        val prev = s.prevClose ?: return null
        val y = earlier(bars, s.at.toLocalDate()).lastOrNull() ?: return null
        val g = gapPct(s) ?: return null
        val gapOpen = abs(g) >= GAP_MIN_PCT && (if (g > 0) s.low > prev else s.high < prev)
        return Look(gapOpen, s.price > y.high, s.price < y.low)
    }

    /** The moments between [before] and [now] (both from [look] the same day); empty when nothing changed. */
    fun alerts(s: Snapshot, bars: List<Candle>, before: Look, now: Look): List<Alert> {
        val m = s.market
        val out = ArrayList<Alert>()
        val prev = s.prevClose
        val g = gapPct(s)
        if (before.gapOpen && !now.gapOpen && prev != null && g != null && abs(g) >= GAP_MIN_PCT) {
            val up = g > 0
            out += Alert("gapfill|${m.name}", "${m.label} filled its gap",
                "${m.label} has filled today's gap ${if (up) "up" else "down"}, Boss: it opened ${pts(s.open - prev)} points (${pct(g)}) " +
                    "${if (up) "above" else "below"} the previous close of ${px(m, prev)} and has come back to it. It is at ${px(m, s.price)} now.")
        }
        val days = earlier(bars, s.at.toLocalDate())
        val y = days.lastOrNull() ?: return out
        val name = y.day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        if (!before.aboveHigh && now.aboveHigh)
            out += Alert("prevhigh|${m.name}", "${m.label} above $name's high",
                "${m.label} went above $name's high of ${px(m, y.high)}, Boss: it is at ${px(m, s.price)}.${since(s, days, up = true)}")
        if (!before.belowLow && now.belowLow)
            out += Alert("prevlow|${m.name}", "${m.label} below $name's low",
                "${m.label} went below $name's low of ${px(m, y.low)}, Boss: it is at ${px(m, s.price)}.${since(s, days, up = false)}")
        return out
    }

    /**
     * How far back the price was last seen: the latest earlier session that traded at or beyond it. Said only when the
     * price is today's extreme on that side (otherwise today itself went further, and "highest since" would not be true).
     */
    private fun since(s: Snapshot, days: List<Day>, up: Boolean): String {
        if (if (up) s.price < s.high else s.price > s.low) return ""
        val word = if (up) "highest" else "lowest"
        val i = days.indexOfLast { if (up) it.high >= s.price else it.low <= s.price }
        return when {
            i < 0 -> " That is ${if (up) "above every high" else "below every low"} of the ${days.size} earlier sessions on the phone (since ${days.first().day.format(DATE)})."
            i < days.size - 1 -> " That is the $word price since ${days[i].day.format(DATE)}."
            else -> ""
        }
    }
}
