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
        val start: LocalDateTime = w.minutes?.let { last.t.minusMinutes(it.toLong() - 1) } ?: last.t.toLocalDate().atTime(w.since ?: return null)
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

/** The four indices Jarvis follows. */
object Reasoning {
    val INDICES = listOf(Market.NIFTY, Market.BANKNIFTY, Market.FINNIFTY, Market.SENSEX)
}

/** Which market is stronger today (Jarvis self-improvement, 2026-10-03): "is BankNifty stronger than Nifty?". Pure. */
object Compare {
    private val ASK = Regex(" (stronger|weaker|strongest|weakest|better|worse|outperform\\w*|underperform\\w*|compare|comparison|vs|versus|leading|lagging|which one|which is|which of) ")
    /** "Which index is strongest today?" - no market named: all four compared. */
    private val ANY = Regex(" (which|what) (index|indices|market|markets)( is| are)? (the )?(strongest|weakest|best|worst|leading|lagging|stronger|weaker)| (strongest|weakest|best performing|worst performing) (index|market) ")

    fun asked(text: String): Boolean {
        val t = norm(text)
        return Market.mentioned(text).filter { it != Market.VIX }.size >= 2 && ASK.containsMatchIn(t) || ANY.containsMatchIn(t)
    }

    /** The markets to compare for [text]: those named, else the four indices. */
    fun markets(text: String): List<Market> = Market.mentioned(text).filter { it != Market.VIX }.takeIf { it.size >= 2 } ?: Reasoning.INDICES

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
    private val ASK = Regex(" (expected|likely|possible|probable|implied) (range|move|movement|swing) | (how far|how much) (can|could|will|might) [a-z ]{0,20}(go|move|swing) ")

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

/** How fresh the prices are (Jarvis self-improvement, 2026-10-03): said when the market is open and they lag. Pure. */
object Freshness {
    const val STALE_MINUTES = 5L

    /** A warning when [at] (the last candle) is [STALE_MINUTES] or more behind [now] while [m] trades, else null. */
    fun note(m: Market, at: LocalDateTime, now: LocalDateTime): String? {
        if (!m.trading(now)) return null
        val behind = java.time.Duration.between(at, now).toMinutes()
        if (behind < STALE_MINUTES) return null
        val age = if (behind >= 60) "over an hour" else "$behind minutes"
        return "Careful, Boss: the last ${m.label} price I have is $age old - the live feed is behind."
    }
}

/**
 * Why a market is moving, from the evidence on the phone (Jarvis self-improvement, 2026-10-03): the opening gap and what
 * has happened since, whether the other indices move the same way (broad) or not (this one alone), and whether fear (VIX)
 * is rising or easing. The news lines are added by the answer itself. Pure.
 */
object Why {
    private val INDICES = Reasoning.INDICES

    fun story(s: Snapshot, snaps: Map<Market, Snapshot>): String? {
        if (s.market == Market.VIX) return null
        val parts = ArrayList<String>()
        val prev = s.prevClose
        if (prev != null && prev > 0) {
            val gap = s.open - prev
            if (abs(gap) / prev >= 0.002) {
                val since = s.price - s.open
                val after = when {
                    abs(since) / prev < 0.001 -> "and has held there since"
                    (since > 0) == (gap > 0) -> "and has added ${n(abs(since))} more since the open"
                    abs(since) >= abs(gap) -> "and has more than filled that gap since"
                    else -> "and has given back ${n(abs(since))} of it since the open"
                }
                parts += "${s.market.label} opened ${n(abs(gap))} points ${if (gap > 0) "above" else "below"} the previous close (a gap ${if (gap > 0) "up" else "down"}) $after."
            }
        }
        if (s.market in INDICES) {
            val mine = s.changePct
            val others = INDICES.filter { it != s.market }.mapNotNull { m -> snaps[m]?.changePct?.let { m to it } }
            if (mine != null && others.isNotEmpty() && abs(mine) >= 0.1) {
                val same = others.count { (it.second > 0) == (mine > 0) && abs(it.second) >= 0.05 }
                val way = if (mine > 0) "up" else "down"
                parts += when {
                    same == others.size -> "The move is broad: ${others.joinToString(", ") { "${it.first.label} ${pct(it.second)}" }} as well, so it is the whole market, not ${s.market.label} alone."
                    same == 0 -> "${s.market.label} is moving on its own: ${others.joinToString(", ") { "${it.first.label} ${pct(it.second)}" }}, so the reason is likely in its own stocks."
                    else -> "$same of ${others.size} other indices are $way too (${others.joinToString(", ") { "${it.first.label} ${pct(it.second)}" }})."
                }
            }
        }
        snaps[Market.VIX]?.changePct?.let { v ->
            if (v >= 5) parts += "Fear is rising: India VIX is ${pct(v)} today, so traders are paying up for protection."
            else if (v <= -5) parts += "Fear is easing: India VIX is ${pct(v)} today."
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }
}

/**
 * The day told as a story (Jarvis self-improvement, 2026-10-03): "how has the day gone?", "recap", "Nifty so far" - the
 * open, when the high and the low were made (which came first), and where it is now within the day's range. Pure.
 */
object DayStory {
    private val ASK = Regex(" (recap|story|so far|how has the day|how did the day|how has today|how was the day|how did today|day summary|session so far|today s session|todays session|wrap up|wrap) ")

    fun asked(text: String): Boolean = ASK.containsMatchIn(norm(text))

    private fun hm(t: LocalDateTime) = "%02d:%02d".format(Locale.ENGLISH, t.hour, t.minute)

    fun say(m: Market, bars: List<Candle>): String? {
        val last = bars.lastOrNull() ?: return null
        val day = bars.filter { it.t.toLocalDate() == last.t.toLocalDate() }
        if (day.size < 15) return null
        val open = day.first(); val hi = day.maxBy { it.h }; val lo = day.minBy { it.l }
        val range = hi.h - lo.l
        val where = if (range <= 0) "flat" else ((last.c - lo.l) / range).let { f -> when {
            f >= 0.8 -> "near the day's high"; f <= 0.2 -> "near the day's low"; else -> "in the middle of the day's range" } }
        val path = if (hi.t.isBefore(lo.t))
            "made its high of ${n(hi.h)} at ${hm(hi.t)}, then fell to the low of ${n(lo.l)} at ${hm(lo.t)}"
        else "dipped to its low of ${n(lo.l)} at ${hm(lo.t)}, then climbed to the high of ${n(hi.h)} at ${hm(hi.t)}"
        val net = last.c - open.o
        return "${m.label} opened at ${n(open.o)}, $path. It is now ${n(last.c)} (${pts(net)} from the open), $where."
    }
}

/** "How do you know that?" (Jarvis self-improvement, 2026-10-03): the facts the last answer was built from. Pure. */
object Sources {
    private val ASK = Regex("^ (how do you know( that| this)?|where did you get (that|this)( from)?|what is that based on|what s that based on|whats that based on|source|sources|your source|show (me )?your (work|working|sources)|why do you say (that|so)|how did you work (that|it) out|based on what) $")

    fun asked(text: String): Boolean = ASK.containsMatchIn(norm(text).replace(Regex("^ (jarvis|hey jarvis|ok jarvis|boss) "), " "))

    fun say(facts: List<String>): String =
        if (facts.isEmpty()) "That answer came from my own rules, Boss, not from figures I can list."
        else "I worked that out from: " + facts.take(8).joinToString("; ") + "."
}

/**
 * Classic pivot levels (Jarvis self-improvement, 2026-10-03): "Nifty pivots", "levels for tomorrow". From the last full
 * session's high, low and close: P = (H+L+C)/3, R1 = 2P-L, S1 = 2P-H, R2 = P+(H-L), S2 = P-(H-L). Pure.
 */
object Pivots {
    private val ASK = Regex(" (pivot|pivots|pivot points?|cpr|tomorrow s levels|tomorrows levels|levels for tomorrow|tomorrow levels|next session levels|levels for the next session|levels for monday) ")

    fun asked(text: String): Boolean = ASK.containsMatchIn(norm(text))

    data class Levels(val r2: Double, val r1: Double, val p: Double, val s1: Double, val s2: Double)

    fun of(h: Double, l: Double, c: Double): Levels {
        val p = (h + l + c) / 3
        return Levels(p + (h - l), 2 * p - l, p, 2 * p - h, p - (h - l))
    }

    /**
     * Pivots for the session after the last complete one in [bars]. While [trading], today's candles are not complete,
     * so today's pivots come from the day before.
     */
    fun say(m: Market, bars: List<Candle>, trading: Boolean): String? {
        val days = bars.groupBy { it.t.toLocalDate() }.toSortedMap()
        if (days.isEmpty()) return null
        val base = if (trading) days.keys.toList().dropLast(1).lastOrNull() ?: return null else days.lastKey()
        val d = days.getValue(base)
        val lv = of(d.maxOf { it.h }, d.minOf { it.l }, d.last().c)
        val forWhat = if (trading) "today" else "the next session"
        return "${m.label}'s classic pivots for $forWhat, from $base's high, low and close: R2 ${n(lv.r2)}, R1 ${n(lv.r1)}, pivot ${n(lv.p)}, S1 ${n(lv.s1)}, S2 ${n(lv.s2)}. " +
            "Above the pivot buyers have the edge; R1 and S1 are the usual first stops."
    }
}
