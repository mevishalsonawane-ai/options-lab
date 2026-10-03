package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

/**
 * "How did my Thursday expiry trades do this month?" (the owner's wish, 2026-10-03): the owner's round trips filtered
 * by weekday, expiry day, time of day, index and period, then counted. Pure.
 */
object TradeSearch {
    data class Filter(val weekday: DayOfWeek? = null, val expiryOnly: Boolean = false, val from: LocalDate? = null, val to: LocalDate? = null,
                      val startMinute: Int? = null, val endMinute: Int? = null, val market: Market? = null, val label: String = "") {
        val any: Boolean get() = weekday != null || expiryOnly || from != null || startMinute != null || market != null
    }

    private val DAYS = mapOf("monday" to DayOfWeek.MONDAY, "tuesday" to DayOfWeek.TUESDAY, "wednesday" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY, "friday" to DayOfWeek.FRIDAY)

    /** Does [text] ask to search the owner's own trades? ("my ... trades" with a filter word). */
    fun asked(text: String): Boolean {
        val t = " " + text.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ") + " "
        return Regex(" (my|did i|i ) ").containsMatchIn(t) && Regex(" (trade|trades|trading) ").containsMatchIn(t) && parse(text, LocalDate.of(2026, 1, 5)).any
    }

    fun parse(text: String, today: LocalDate): Filter {
        val t = " " + text.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ") + " "
        val day = DAYS.entries.firstOrNull { (w, _) -> t.contains(" $w ") || t.contains(" ${w}s ") }?.value
        val expiry = Regex(" expiry ").containsMatchIn(t)
        val (from, to, period) = when {
            t.contains(" this week ") -> Triple(today.with(DayOfWeek.MONDAY), today, "this week")
            t.contains(" last week ") -> today.with(DayOfWeek.MONDAY).minusWeeks(1).let { Triple(it, it.plusDays(6), "last week") }
            t.contains(" this month ") -> Triple(today.withDayOfMonth(1), today, "this month")
            t.contains(" last month ") -> today.withDayOfMonth(1).minusMonths(1).let { Triple(it, it.plusMonths(1).minusDays(1), "last month") }
            t.contains(" today ") -> Triple(today, today, "today")
            else -> Triple(null, null, "")
        }
        val (s, e, part) = when {
            Regex(" (morning|first hour|opening) ").containsMatchIn(t) -> Triple(9 * 60 + 15, 11 * 60, "in the morning")
            Regex(" (afternoon|post lunch|after lunch) ").containsMatchIn(t) -> Triple(12 * 60 + 30, 15 * 60 + 30, "in the afternoon")
            Regex(" (last hour|closing hour) ").containsMatchIn(t) -> Triple(14 * 60 + 30, 15 * 60 + 30, "in the last hour")
            else -> Triple(null, null, "")
        }
        val m = Market.mentioned(text).firstOrNull()
        val label = listOfNotNull(day?.let { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }, if (expiry) "expiry-day" else null, m?.label)
            .joinToString(" ").ifEmpty { "" } + " trades" + listOf(part, period).filter { it.isNotEmpty() }.joinToString("") { " $it" }
        return Filter(day, expiry, from, to, s, e, m, label.trim())
    }

    data class Trip(val symbol: String, val openedAt: LocalDateTime, val net: Double)

    fun answer(trips: List<Trip>, f: Filter, isExpiry: (Trip) -> Boolean, marketOf: (String) -> Market?): String {
        val hit = trips.filter { tr ->
            val d = tr.openedAt.toLocalDate(); val min = tr.openedAt.hour * 60 + tr.openedAt.minute
            (f.weekday == null || d.dayOfWeek == f.weekday) && (!f.expiryOnly || isExpiry(tr)) &&
                (f.from == null || !d.isBefore(f.from)) && (f.to == null || !d.isAfter(f.to)) &&
                (f.startMinute == null || min >= f.startMinute) && (f.endMinute == null || min < f.endMinute) &&
                (f.market == null || marketOf(tr.symbol) == f.market)
        }
        if (hit.isEmpty()) return "No ${f.label} found."
        val won = hit.count { it.net > 0 }
        return "${hit.size} ${f.label}: $won won, ${hit.size - won} lost, net ${AppFacts.rs(hit.sumOf { it.net })}" +
            " (best ${AppFacts.rs(hit.maxOf { it.net })}, worst ${AppFacts.rs(hit.minOf { it.net })})."
    }
}

/** Your best and worst time of day (the owner's wish, 2026-10-03), from your own trades, by the hour they opened. Pure. */
object TimeOfDay {
    private val SLOTS = listOf(9 * 60 + 15 to 10 * 60, 10 * 60 to 11 * 60, 11 * 60 to 12 * 60, 12 * 60 to 13 * 60, 13 * 60 to 14 * 60, 14 * 60 to 15 * 60 + 30)
    const val MIN_TRADES = 3

    private fun hhmm(m: Int) = "%02d:%02d".format(Locale.ENGLISH, m / 60, m % 60)

    fun line(trips: List<TradeSearch.Trip>): String? {
        val by = SLOTS.mapNotNull { (a, b) ->
            val l = trips.filter { val m = it.openedAt.hour * 60 + it.openedAt.minute; m in a until b }
            if (l.size < MIN_TRADES) null else Triple("${hhmm(a)}-${hhmm(b)}", l.sumOf { it.net }, l.size)
        }
        if (by.size < 2) return null
        val best = by.maxBy { it.second }; val worst = by.minBy { it.second }
        if (worst.second >= 0) return "Best time: ${best.first} (${AppFacts.rs(best.second)} over ${best.third}); every slot made money."
        return "Best time: ${best.first} (${AppFacts.rs(best.second)} over ${best.third}). Worst: ${worst.first} (${AppFacts.rs(worst.second)} over ${worst.third})."
    }
}

/**
 * A trade going nowhere (the owner's wish, 2026-10-03): open [MINUTES] minutes or more, within [BAND] of the price paid
 * while time eats it - closing is suggested (asked, never done alone). Not after 15:00 (the square-offs handle it). Pure.
 */
object StaleTrade {
    const val MINUTES = 45L
    const val BAND = 0.05

    fun stale(openedAt: LocalDateTime, now: LocalDateTime, entry: Double, ltp: Double): Boolean =
        entry > 0 && !openedAt.plusMinutes(MINUTES).isAfter(now) && now.hour * 60 + now.minute < 15 * 60 &&
            kotlin.math.abs(ltp - entry) / entry < BAND

    fun say(symbol: String, minutes: Long, entry: Double, ltp: Double): String =
        "$symbol has gone nowhere for $minutes minutes (%.2f against the %.2f you paid) while time decay eats it. Shall I close it?".format(Locale.ENGLISH, ltp, entry)
}

/** News on an index the owner holds (the owner's wish, 2026-10-03): good or bad for the side held. Pure. */
object PositionNews {
    data class Held(val symbol: String, val market: Market, val call: Boolean, val long: Boolean)

    fun say(title: String, tone: Double, held: List<Held>, markets: List<Market>): String? {
        val mine = held.filter { it.market in markets }
        if (mine.isEmpty() || kotlin.math.abs(tone) < 0.25) return null
        val up = tone > 0
        return "News on your position: \"$title\". " + mine.joinToString(" ") { h ->
            val wantsUp = h.call == h.long                      // long call or short put gains when the index rises
            "${h.symbol}: ${if (wantsUp == up) "good" else "bad"} for it (the news reads ${if (up) "positive" else "negative"} for ${h.market.label})."
        } + " A word-list reading, not a forecast."
    }
}

/** The day's target (the owner's wish, 2026-10-03): when reached, protect the gains and stop for the day. Pure. */
object DayTarget {
    fun reached(pnl: Double, target: Double?): Boolean = target != null && target > 0 && pnl >= target

    fun say(pnl: Double, target: Double): String =
        "Boss, you've hit today's target: ${AppFacts.rs(pnl)} against ${AppFacts.amt(target)}. Shall I keep it safe? Tighten your stops or close out, and call it a day."
}

/**
 * Why the owner took a trade (the owner's wish, 2026-10-03): "Jarvis, note: I bought because of the hammer at support"
 * is kept with the trade; the report card shows which reasons work. Pure.
 */
object TradeReasons {
    private val KINDS = listOf(
        "pattern" to Regex("hammer|engulfing|doji|star|harami|marubozu|pattern|candle|pin bar|inside bar"),
        "breakout" to Regex("breakout|break out|broke out|breakdown|break down|range break|orb"),
        "support/resistance" to Regex("support|resistance|level|bounce|reject"),
        "news" to Regex("news|headline|rbi|fed|budget|results|event"),
        "trend" to Regex("trend|momentum|moving average|ema|vwap"),
        "gut feel" to Regex("gut|feel|feeling|hunch|instinct|just"),
    )

    fun kind(note: String): String = note.lowercase().let { n -> KINDS.firstOrNull { it.second.containsMatchIn(n) }?.first } ?: "other"

    /** [notes]: (note, the trade's result); reasons with 3 trades or more. */
    fun lines(notes: List<Pair<String, Double>>): List<String> =
        notes.groupBy { kind(it.first) }.filter { it.value.size >= 3 }.entries.sortedByDescending { e -> e.value.sumOf { it.second } }.map { (k, l) ->
            "Trades taken for \"$k\": ${l.size}, ${l.count { it.second > 0 }} won, net ${AppFacts.rs(l.sumOf { it.second })}."
        }
}

/** The backup reminder (the owner's wish, 2026-10-03): the records live only on the phone. Pure. */
object BackupNudge {
    const val DAYS = 7L

    fun due(last: LocalDate?, today: LocalDate): Boolean = last == null || !last.plusDays(DAYS).isAfter(today)

    fun say(last: LocalDate?): String = (if (last == null) "You have never made a backup" else "Your last backup was on $last") +
        ": your trades and records live only on this phone. Make an encrypted backup in Settings, Backup."
}

/** Telling once (the owner's wish: no spam): a key said again only after [gapMinutes]. Pure. */
class Once(private val gapMinutes: Long) {
    private val last = HashMap<String, LocalDateTime>()

    @Synchronized fun allow(key: String, now: LocalDateTime): Boolean {
        val l = last[key]
        if (l != null && l.plusMinutes(gapMinutes).isAfter(now)) return false
        last[key] = now
        return true
    }
}
