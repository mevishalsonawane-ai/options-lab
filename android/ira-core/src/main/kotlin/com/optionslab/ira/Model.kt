package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** One candle. Times are the market's own clock: IST for the Indian indices, UTC for gold. */
data class Candle(val t: LocalDateTime, val o: Double, val h: Double, val l: Double, val c: Double) {
    val green: Boolean get() = c > o
    val red: Boolean get() = c < o
    val body: Double get() = kotlin.math.abs(c - o)
    val range: Double get() = h - l
}

/** The markets Ira knows. [unit] is how a price is written; [open]/[close] the cash session (null: around the clock on weekdays). */
enum class Market(val label: String, val unit: String, val open: LocalTime?, val close: LocalTime?, val aliases: List<String>) {
    NIFTY("Nifty", "", LocalTime.of(9, 15), LocalTime.of(15, 30), listOf("nifty", "nifty 50", "nifty50", "nf", "nifti", "nifty fifty", "nifty fifty index", "nifty 50 index")),
    BANKNIFTY("BankNifty", "", LocalTime.of(9, 15), LocalTime.of(15, 30), listOf("banknifty", "bank nifty", "bnf", "bank", "nifty bank", "nifti bank", "bank nifti", "bang nifty", "bankniftee")),
    FINNIFTY("FinNifty", "", LocalTime.of(9, 15), LocalTime.of(15, 30), listOf("finnifty", "fin nifty", "finnity", "fin", "finn nifty", "fin nifti", "nifty fin", "nifty financial services", "fin niftee")),
    SENSEX("Sensex", "", LocalTime.of(9, 15), LocalTime.of(15, 30), listOf("sensex", "bse", "sense x", "sensecs", "senseks", "sensexx", "sensex 30", "sen sex")),
    VIX("India VIX", "", LocalTime.of(9, 15), LocalTime.of(15, 30), listOf("vix", "india vix", "volatility index", "fear")),
    GOLD("Gold", "$", null, null, listOf("gold", "xau", "xauusd", "bullion"));

    /** A price as people write it: "24,612.40" or "$4,214.70". */
    fun price(v: Double): String = unit + "%,.2f".format(java.util.Locale.ENGLISH, v)

    /** Is the market trading at [t] (its own clock)? Gold: weekdays except 21:00-22:00 UTC, Friday until 21:00. */
    fun trading(t: LocalDateTime): Boolean {
        val weekday = t.dayOfWeek != DayOfWeek.SATURDAY && t.dayOfWeek != DayOfWeek.SUNDAY
        if (!weekday) return false
        if (open == null || close == null) {
            if (t.hour == 21) return false
            return !(t.dayOfWeek == DayOfWeek.FRIDAY && t.hour >= 21)
        }
        val tm = t.toLocalTime()
        return !tm.isBefore(open) && tm.isBefore(close)
    }

    companion object {
        /** The markets a piece of text mentions, longest alias first so "bank nifty" is not read as "nifty". */
        fun mentioned(text: String): List<Market> = named.of(text) {
            var s = " " + text.lowercase().replace(NOT_WORD, " ") + " "
            val found = LinkedHashSet<Market>()
            for ((key, m) in KEYS) if (s.contains(key)) { found += m; s = s.replace(key, " ") }
            found.toList()
        }

        /** The last words read (speed round 10: read some forty times for one question; [Kept], pure). */
        private val named = Kept<List<Market>>(64)

        private val NOT_WORD = Regex("[^a-z0-9 ]")
        /** Every alias as " alias " with its market, longest first: worked out once, not at every reading. */
        private val KEYS: List<Pair<String, Market>> by lazy { entries.flatMap { m -> m.aliases.map { it to m } }.sortedByDescending { it.first.length }.map { (a, m) -> " $a " to m } }
        /** Every alias, longest first ([FollowUp.onlyMarkets]). */
        internal val ALIASES_LONGEST_FIRST: List<String> by lazy { entries.flatMap { it.aliases }.sortedByDescending { it.length } }
    }
}

/** A market's candles: 1-minute bars, oldest first, possibly spanning many days. */
class History(val market: Market, bars: List<Candle>) {
    val bars: List<Candle> = bars.sortedBy { it.t }
    /** The days in [bars], in order: worked out once (the candles never change), not at every look (speed round 9). */
    val days: List<LocalDate> by lazy { bars.map { it.t.toLocalDate() }.distinct() }
    fun day(d: LocalDate): List<Candle> = bars.filter { it.t.toLocalDate() == d }
}
