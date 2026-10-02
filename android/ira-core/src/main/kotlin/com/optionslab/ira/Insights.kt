package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

/**
 * The owner's own trades, looked at for what keeps happening (the weekly review and "my mistakes"): the week's result,
 * best and worst trade, which strategy or hand made or lost the money, by index, by hour of entry, by weekday, losing
 * streaks, trades closed in minutes. Facts from the app's trade book; patterns need enough trades to be said. Pure.
 */
object Insights {
    data class Trip(val symbol: String, val openedAt: LocalDateTime, val closedAt: LocalDateTime, val net: Double, val owner: String)

    /** At least this many trades in a group before a pattern about it is said. */
    const val MIN_GROUP = 5

    private fun rs(x: Double) = (if (x < 0) "-Rs " else "+Rs ") + "%,.0f".format(Locale.ENGLISH, kotlin.math.abs(x))
    private fun index(sym: String) = listOf("BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX", "NIFTY").firstOrNull { sym.startsWith(it) } ?: sym

    /** This week (Monday on) in a few lines; [label] "Paper" or "Zerodha". */
    fun week(label: String, trips: List<Trip>, today: LocalDate): List<String> {
        val monday = today.with(DayOfWeek.MONDAY)
        val w = trips.filter { !it.closedAt.toLocalDate().isBefore(monday) && !it.closedAt.toLocalDate().isAfter(today) }
        if (w.isEmpty()) return listOf("$label: no trades closed this week.")
        val out = ArrayList<String>()
        out += "$label this week: ${w.size} trades, ${w.count { it.net > 0 }} won, net ${rs(w.sumOf { it.net })}."
        w.maxByOrNull { it.net }?.let { out += "Best trade: ${it.symbol} ${rs(it.net)} (${it.owner})." }
        w.minByOrNull { it.net }?.takeIf { it.net < 0 }?.let { out += "Worst trade: ${it.symbol} ${rs(it.net)} (${it.owner})." }
        val by = w.groupBy { it.owner }.mapValues { e -> e.value.sumOf { it.net } }.entries.sortedByDescending { it.value }
        if (by.size > 1) out += "By who traded: " + by.joinToString(", ") { "${it.key} ${rs(it.value)}" } + "."
        return out
    }

    /** Patterns over all [trips] ("my mistakes", "insights"). */
    fun patterns(label: String, trips: List<Trip>): List<String> {
        if (trips.size < 10) return listOf("$label: ${trips.size} trades so far; I need at least 10 to see patterns.")
        val out = ArrayList<String>()
        val total = trips.sumOf { it.net }
        out += "$label over ${trips.size} trades: ${trips.count { it.net > 0 }} won (${100 * trips.count { it.net > 0 } / trips.size}%), net ${rs(total)}."
        // Hour of entry.
        val hours = trips.groupBy { it.openedAt.hour }.filter { it.value.size >= MIN_GROUP }.mapValues { e -> e.value.sumOf { it.net } to e.value.size }
        hours.minByOrNull { it.value.first }?.takeIf { it.value.first < 0 }?.let { (h, v) ->
            out += "Entries between %02d:00 and %02d:00 lost the most: %s over %d trades.".format(Locale.ENGLISH, h, h + 1, rs(v.first), v.second) }
        hours.maxByOrNull { it.value.first }?.takeIf { it.value.first > 0 }?.let { (h, v) ->
            out += "Entries between %02d:00 and %02d:00 made the most: %s over %d trades.".format(Locale.ENGLISH, h, h + 1, rs(v.first), v.second) }
        // Index and weekday.
        trips.groupBy { index(it.symbol) }.filter { it.value.size >= MIN_GROUP }.mapValues { e -> e.value.sumOf { it.net } }.let { m ->
            if (m.size > 1) out += "By index: " + m.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key} ${rs(it.value)}" } + "." }
        trips.groupBy { it.openedAt.dayOfWeek }.filter { it.value.size >= MIN_GROUP }.mapValues { e -> e.value.sumOf { it.net } }
            .minByOrNull { it.value }?.takeIf { it.value < 0 }?.let { out += "Your weakest weekday: ${it.key.name.lowercase().replaceFirstChar { c -> c.uppercase() }} (${rs(it.value)})." }
        // Quick trades and streaks.
        val quick = trips.filter { java.time.Duration.between(it.openedAt, it.closedAt).toMinutes() < 5 }
        if (quick.size >= MIN_GROUP) out += "Trades closed within 5 minutes: ${quick.size}, net ${rs(quick.sumOf { it.net })}."
        var streak = 0; var worst = 0
        trips.sortedBy { it.closedAt }.forEach { if (it.net < 0) { streak++; worst = maxOf(worst, streak) } else streak = 0 }
        if (worst >= 3) out += "Longest losing streak: $worst trades in a row."
        // The owner's own hand against the bots.
        val hand = trips.filter { it.owner.startsWith("Manual") }; val bots = trips - hand.toSet()
        if (hand.size >= MIN_GROUP && bots.size >= MIN_GROUP) out += "Your own trades ${rs(hand.sumOf { it.net })} over ${hand.size}; the bots' ${rs(bots.sumOf { it.net })} over ${bots.size}."
        return out
    }

    /** Is a strategy still healthy? [recent]: its trades of the last 4 weeks. Null when there is too little to say. */
    fun health(name: String, recent: List<Trip>): String? {
        if (recent.size < 10) return null
        val net = recent.sumOf { it.net }
        val half = recent.sortedBy { it.closedAt }.let { it.drop(it.size / 2) }.sumOf { it.net }
        return when {
            net < 0 && half < 0 -> "$name is losing lately: ${rs(net)} over its last ${recent.size} trades, and the latest half lost too."
            net < 0 -> "$name is down ${rs(net)} over its last ${recent.size} trades, though its latest half did better."
            else -> null
        }
    }
}
