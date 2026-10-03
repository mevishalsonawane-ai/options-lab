package com.optionslab.ira

import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

private fun n(x: Double) = "%,.2f".format(Locale.ENGLISH, x)
private fun pts(x: Double) = "%+,.2f".format(Locale.ENGLISH, x)
private fun pct(x: Double) = "%+.2f%%".format(Locale.ENGLISH, x)
private fun norm(text: String) = " " + text.lowercase().replace(Regex("[^a-z0-9: ]"), " ").replace(Regex("\\s+"), " ").trim() + " "

/**
 * How far a market moved over a stretch of time (Jarvis self-improvement, 2026-10-03): "how much did Nifty move in the
 * last hour", "BankNifty since 11", "since the open", "in the last 30 minutes". Pure.
 */
object Moves {
    /** [minutes] back from the last candle, or [since] a time of day (the session's open when asked "since the open"). */
    data class Window(val minutes: Int? = null, val since: LocalTime? = null, val label: String)

    private val MOVE = Regex(" (move|moved|moving|change|changed|up|down|gain|gained|fall|fell|fallen|rise|rose|risen|how much|done|did|go|gone|went|perform|performed|doing|done today) ")

    fun asked(text: String, open: LocalTime = LocalTime.of(9, 15)): Window? {
        val t = norm(text)
        if (!MOVE.containsMatchIn(t)) return null
        Regex(" (last|past) (\\d{1,3}) (minutes|minute|mins|min) ").find(t)?.let { val m = it.groupValues[2].toInt(); if (m in 1..375) return Window(minutes = m, label = "in the last $m minutes") }
        Regex(" (last|past) (\\d) (hours|hour|hrs|hr) ").find(t)?.let { val h = it.groupValues[2].toInt(); if (h in 1..6) return Window(minutes = h * 60, label = "in the last $h hour${if (h > 1) "s" else ""}") }
        if (Regex(" (last|past|in the last|in an|in the past) (hour|one hour|1 hour) ").containsMatchIn(t)) return Window(minutes = 60, label = "in the last hour")
        if (Regex(" (last|past) half (an )?hour ").containsMatchIn(t)) return Window(minutes = 30, label = "in the last 30 minutes")
        if (Regex(" (since|from) (the )?(open|opening|morning|start|bell) ").containsMatchIn(t)) return Window(since = open, label = "since the open")
        Regex(" (since|from) (\\d{1,2})(?::| |\\.)?(\\d{2})? ?(am|pm)? ").find(t)?.let { m ->
            var h = m.groupValues[2].toInt(); val mi = m.groupValues[3].toIntOrNull() ?: 0
            val ap = m.groupValues[4]
            if (ap == "pm" && h < 12) h += 12
            if (ap.isEmpty() && h in 1..3) h += 12                      // "since 2" in a trading day is 2 pm
            if (h in 0..23 && mi in 0..59) { val at = LocalTime.of(h, mi); return Window(since = at, label = "since " + "%02d:%02d".format(Locale.ENGLISH, at.hour, at.minute)) }
        }
        return null
    }

    /** The move of [m] over [w] in its latest session's 1-minute [bars]; null when the stretch has no candles. */
    fun say(m: Market, bars: List<Candle>, w: Window): String? {
        val last = bars.lastOrNull() ?: return null
        val day = bars.filter { it.t.toLocalDate() == last.t.toLocalDate() }
        val start: LocalDateTime = w.minutes?.let { last.t.minusMinutes(it.toLong()) } ?: last.t.toLocalDate().atTime(w.since ?: return null)
        if (!start.isBefore(last.t)) return null
        val inside = day.filter { !it.t.isBefore(start) }
        val first = inside.firstOrNull() ?: return null
        val from = first.o; val to = last.c
        val hi = inside.maxOf { it.h }; val lo = inside.minOf { it.l }
        val move = to - from
        val way = if (abs(move) < from * 0.0002) "was flat" else if (move > 0) "rose ${pts(move).removePrefix("+")} points" else "fell ${pts(-move).removePrefix("+")} points"
        return "${m.label} $way ${w.label} (${pct(move / from * 100)}), from ${n(from)} to ${n(to)}; its range in that time was ${n(lo)} to ${n(hi)}."
    }
}

/** Which market is stronger today (Jarvis self-improvement, 2026-10-03): "is BankNifty stronger than Nifty?". Pure. */
object Compare {
    private val ASK = Regex(" (stronger|weaker|strongest|weakest|better|worse|outperform\\w*|underperform\\w*|compare|comparison|vs|versus|leading|lagging|which one|which is|which of) ")

    fun asked(text: String): Boolean = Market.mentioned(text).filter { it != Market.VIX }.size >= 2 && ASK.containsMatchIn(norm(text))

    /** [markets] compared by today's change; null when fewer than two have a previous close. */
    fun say(markets: List<Market>, snaps: Map<Market, Snapshot>): String? {
        val rows = markets.distinct().filter { it != Market.VIX }.mapNotNull { m -> snaps[m]?.changePct?.let { m to it } }.sortedByDescending { it.second }
        if (rows.size < 2) return null
        val (top, topPct) = rows.first(); val (low, lowPct) = rows.last()
        val gap = topPct - lowPct
        val lead = if (gap < 0.05) "${top.label} and ${low.label} are moving together today" else "${top.label} is the stronger today"
        val list = rows.joinToString(", ") { (m, p) -> "${m.label} ${pct(p)}" }
        return "$lead: $list." + if (rows.size == 2 && gap >= 0.05) " A gap of %.2f points of percentage.".format(Locale.ENGLISH, gap) else ""
    }
}

/**
 * The move to expect (Jarvis self-improvement, 2026-10-03): India VIX is the market's own guess of Nifty's yearly
 * swing, so one day's is VIX / sqrt(252), and what is left of the day scales by the square root of the time left.
 * About two days in three stay inside it. Pure.
 */
object ExpectedRange {
    private val ASK = Regex(" (expected|likely|possible|probable|implied) (range|move|movement|swing) | (how far|how much) (can|could|will|might) [a-z ]{0,20}(go|move|swing) | range (for|of) (today|the day|tomorrow) | (day s|todays|today s) range ")

    fun asked(text: String): Boolean = ASK.containsMatchIn(norm(text))

    /** Points of one standard move for the rest of the day (the whole day when [minutesLeft] is null). */
    fun points(price: Double, vix: Double, minutesLeft: Int? = null): Double {
        val day = price * vix / 100 / sqrt(252.0)
        return if (minutesLeft == null) day else day * sqrt(minutesLeft.coerceIn(1, 375) / 375.0)
    }

    fun say(s: Snapshot, vix: Double, now: LocalDateTime): String? {
        if (vix <= 0 || s.market == Market.GOLD || s.market == Market.VIX) return null
        val close = s.market.close ?: return null
        val left = if (s.trading && now.toLocalTime().isBefore(close)) java.time.Duration.between(now.toLocalTime(), close).toMinutes().toInt() else null
        val p = points(s.price, vix, left)
        val rough = if (s.market == Market.NIFTY) "" else " (India VIX measures Nifty, so for ${s.market.label} take it as rough)"
        val span = if (left != null) "for the rest of today" else "for the next session"
        return "From India VIX at %.2f, ${s.market.label}'s expected move $span is about ±${n(p)} points, roughly ${n(s.price - p)} to ${n(s.price + p)}: about two days in three stay inside it$rough."
            .format(Locale.ENGLISH, vix)
    }
}
