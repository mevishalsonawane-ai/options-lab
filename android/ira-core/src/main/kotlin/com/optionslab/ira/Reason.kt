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

/**
 * Looking back (Jarvis self-improvement, 2026-10-03): "where was Nifty at 11 am", "BankNifty at 10:30", "yesterday's
 * high", "previous close". From the 1-minute candles on the phone. Pure.
 */
object Lookback {
    /** A time of day asked about ("at 11", "at 2:30 pm"), with a past-tense word so "alert me at 25000" is not taken. */
    fun time(text: String): LocalTime? {
        val t = norm(text)
        if (!Regex(" (was|were|where was|what was|how was|at what price|price at|level at) ").containsMatchIn(t)) return null
        val m = Regex(" at (\\d{1,2})(?::|\\.| )?(\\d{2})? ?(am|pm)? ").find(t) ?: return null
        var h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toIntOrNull() ?: 0
        val ap = m.groupValues[3]
        if (ap == "pm" && h < 12) h += 12
        if (ap.isEmpty() && h in 1..3) h += 12
        if (h !in 0..23 || mi !in 0..59) return null
        return LocalTime.of(h, mi)
    }

    fun priceAt(m: Market, bars: List<Candle>, at: LocalTime, yesterday: Boolean = false): String? {
        val days = bars.map { it.t.toLocalDate() }.distinct().sorted()
        val day = (if (yesterday) days.dropLast(1).lastOrNull() else days.lastOrNull()) ?: return null
        val bar = bars.filter { it.t.toLocalDate() == day && !it.t.toLocalTime().isAfter(at) }.lastOrNull() ?: return null
        val last = bars.last()
        val since = if (!yesterday && day == last.t.toLocalDate()) " Since then it has moved ${pts(last.c - bar.c)} to ${n(last.c)}." else ""
        return "At ${"%02d:%02d".format(Locale.ENGLISH, bar.t.hour, bar.t.minute)} on $day ${m.label} was at ${n(bar.c)}.$since"
    }

    private val PREV = Regex(" (yesterday s|yesterdays|yesterday|previous day s|previous days|previous|last session s|last sessions|prior day s) (high|low|close|closing|open|opening|range) | (high|low|close|open|range) (of |on )?(yesterday|the previous day|the last session) ")

    fun prevAsked(text: String): Boolean = PREV.containsMatchIn(norm(text))

    /** The last complete session's open, high, low and close ([trading]: today is not complete). */
    fun prevDay(m: Market, bars: List<Candle>, trading: Boolean): String? {
        val days = bars.groupBy { it.t.toLocalDate() }.toSortedMap()
        if (days.isEmpty()) return null
        val base = if (trading) days.keys.toList().dropLast(1).lastOrNull() ?: return null else days.lastKey()
        val d = days.getValue(base)
        return "${m.label} on $base: open ${n(d.first().o)}, high ${n(d.maxOf { it.h })}, low ${n(d.minOf { it.l })}, close ${n(d.last().c)}."
    }
}

/**
 * Momentum (Jarvis self-improvement, 2026-10-03): "is Nifty overbought?", "RSI on BankNifty", "how strong is the
 * momentum" - Wilder's 14-period RSI on the 15-minute and 1-hour charts, read plainly. Never a buy or sell call. Pure.
 */
object Momentum {
    private val ASK = Regex(" (rsi|overbought|oversold|over bought|over sold|momentum|stretched|too high|too low) ")

    fun asked(text: String): Boolean = ASK.containsMatchIn(norm(text))

    /** Wilder's RSI of [closes] over [period], or null with too few closes. */
    fun rsi(closes: List<Double>, period: Int = 14): Double? {
        if (closes.size <= period) return null
        var gain = 0.0; var loss = 0.0
        for (i in 1..period) { val d = closes[i] - closes[i - 1]; if (d > 0) gain += d else loss -= d }
        gain /= period; loss /= period
        for (i in period + 1 until closes.size) {
            val d = closes[i] - closes[i - 1]
            gain = (gain * (period - 1) + maxOf(d, 0.0)) / period
            loss = (loss * (period - 1) + maxOf(-d, 0.0)) / period
        }
        if (loss == 0.0) return 100.0
        return 100 - 100 / (1 + gain / loss)
    }

    private fun word(r: Double) = when {
        r >= 70 -> "overbought (above 70): rises often pause or pull back from here"
        r <= 30 -> "oversold (below 30): falls often pause or bounce from here"
        r >= 60 -> "strong, with buyers in charge"
        r <= 40 -> "weak, with sellers in charge"
        else -> "neutral"
    }

    fun say(m: Market, bars: List<Candle>, now: LocalDateTime): String? {
        val parts = listOf(15, 60).mapNotNull { mins ->
            val c = Candles.closed(Candles.fold(bars, mins, m), mins, now)
            rsi(c.map { it.c })?.let { r -> "the ${if (mins == 60) "1-hour" else "15-minute"} RSI is " + "%.0f".format(Locale.ENGLISH, r) + ", " + word(r) }
        }
        if (parts.isEmpty()) return null
        return "${m.label}: " + parts.joinToString("; ") + ". It shows how stretched the move is, not where it goes next."
    }
}

/**
 * The odds of a level by the close (Jarvis self-improvement, 2026-10-03): "what are the chances Nifty closes above
 * 24,500?" - the VIX-implied move for the time left, as a normal spread around the price. A rough guide, not a forecast,
 * and never advice. Pure.
 */
object Odds {
    private val ASK = Regex(" (chance|chances|probability|odds|likely|likelihood) [a-z ]{0,40}?(close|closes|closing|end|ends|finish|finishes|stay|stays|be|go|goes|settle|settles) (above|over|below|under) (\\d{2,6}(?:\\.\\d+)?) ")

    data class Ask(val above: Boolean, val level: Double)

    fun asked(text: String): Ask? {
        val t = norm(text.replace(",", ""))
        val m = ASK.find(t) ?: return null
        return Ask(m.groupValues[3] in setOf("above", "over"), m.groupValues[4].toDouble())
    }

    /** The standard normal cumulative distribution (Abramowitz-Stegun 26.2.17, to about 1e-7). */
    fun phi(z: Double): Double {
        val t = 1 / (1 + 0.2316419 * abs(z))
        val d = 0.3989422804014327 * kotlin.math.exp(-z * z / 2)
        val p = d * t * (0.319381530 + t * (-0.356563782 + t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))))
        return if (z >= 0) 1 - p else p
    }

    fun say(s: Snapshot, vix: Double, a: Ask, now: LocalDateTime): String? {
        if (vix <= 0 || s.market == Market.GOLD || s.market == Market.VIX) return null
        val close = s.market.close ?: return null
        val left = if (s.trading && now.toLocalTime().isBefore(close)) java.time.Duration.between(now.toLocalTime(), close).toMinutes().toInt() else null
        val sigma = ExpectedRange.points(s.price, vix, left)
        if (sigma <= 0) return null
        val pAbove = 1 - phi((a.level - s.price) / sigma)
        val p = if (a.above) pAbove else 1 - pAbove
        val pct = (p * 100).let { if (it < 1) "under 1" else if (it > 99) "over 99" else "%.0f".format(Locale.ENGLISH, it) }
        val span = if (left != null) "today" else "next session"
        return "Going by India VIX, there is about a $pct% chance ${s.market.label} closes ${if (a.above) "above" else "below"} ${n(a.level)} $span: " +
            "it is ${n(abs(a.level - s.price))} points ${if (a.level >= s.price) "above" else "below"} the price, against an expected move of about ±${n(sigma)}. A rough guide from option prices, not a forecast."
    }
}

/** Where the price stands against the first 15 minutes (Jarvis self-improvement, 2026-10-03). Pure. */
object OpeningRange {
    private val ASK = Regex(" (opening range|first 15 minutes|first fifteen minutes|orb|opening high|opening low|broken the open|break the open|broke the open) ")

    fun asked(text: String): Boolean = ASK.containsMatchIn(norm(text))

    /** True: above the opening range; false: below it; null: inside it (or no range yet). */
    fun broken(s: Snapshot): Boolean? {
        val hi = s.openingHigh ?: return null; val lo = s.openingLow ?: return null
        return if (s.price > hi) true else if (s.price < lo) false else null
    }

    /** The alert for a break just seen (once per side per day, the caller keeps track). */
    fun alert(s: Snapshot, up: Boolean): String =
        "${s.market.label} broke ${if (up) "above" else "below"} its opening range at ${n(s.price)} " +
            "(the first 15 minutes' ${if (up) "high was ${n(s.openingHigh ?: s.price)}" else "low was ${n(s.openingLow ?: s.price)}"})."

    fun say(s: Snapshot): String? {
        val hi = s.openingHigh ?: return null; val lo = s.openingLow ?: return null
        val where = when {
            s.price > hi -> "above it, ${n(s.price - hi)} over the opening high: an upside break so far"
            s.price < lo -> "below it, ${n(lo - s.price)} under the opening low: a downside break so far"
            else -> "still inside it: no break yet"
        }
        return "${s.market.label}'s opening range (first 15 minutes) is ${n(lo)} to ${n(hi)}; at ${n(s.price)} it is $where." +
            if (s.high > hi && s.low < lo) " Both sides have been broken today, which often means a choppy day." else ""
    }
}

/** How a market did over a week or a month (Jarvis self-improvement, 2026-10-03). Pure. */
object PeriodMove {
    enum class Span(val label: String) { WEEK("this week"), LAST_WEEK("last week"), MONTH("this month") }

    fun asked(text: String): Span? {
        val t = norm(text)
        if (!Regex(" (do|did|done|doing|move|moved|perform|performed|change|changed|up|down|gain|fall|how much|how was|how is|how has) ").containsMatchIn(t)) return null
        return when {
            Regex(" last week ").containsMatchIn(t) -> Span.LAST_WEEK
            Regex(" (this week|the week|weekly|week so far) ").containsMatchIn(t) -> Span.WEEK
            Regex(" (this month|the month|monthly|month so far) ").containsMatchIn(t) -> Span.MONTH
            else -> null
        }
    }

    fun say(m: Market, bars: List<Candle>, span: Span): String? {
        val days = bars.groupBy { it.t.toLocalDate() }.toSortedMap()
        if (days.isEmpty()) return null
        val last = days.lastKey()
        val monday = last.with(java.time.DayOfWeek.MONDAY)
        val (from, to) = when (span) {
            Span.WEEK -> monday to last
            Span.LAST_WEEK -> monday.minusWeeks(1) to monday.minusDays(1)
            Span.MONTH -> last.withDayOfMonth(1) to last
        }
        val inSpan = days.filterKeys { !it.isBefore(from) && !it.isAfter(to) }
        if (inSpan.isEmpty()) return null
        // From the close before the span began (else the span's first open) to its last close.
        val before = days.headMap(inSpan.keys.first()).values.lastOrNull()?.last()?.c
        val start = before ?: inSpan.values.first().first().o
        val end = inSpan.values.last().last().c
        val hi = inSpan.values.flatten().maxOf { it.h }; val lo = inSpan.values.flatten().minOf { it.l }
        val mv = end - start
        return "${m.label} ${span.label}: ${pts(mv)} points (${pct(mv / start * 100)}), from ${n(start)} to ${n(end)}, in a range of ${n(lo)} to ${n(hi)} over ${inSpan.size} session${if (inSpan.size > 1) "s" else ""}."
    }
}

/**
 * A briefing on demand (Jarvis self-improvement, 2026-10-03): "brief me", "what do I need to know", "catch me up" - the
 * indices' moves, the strongest and weakest, the expected range and the day's events, in a few sentences. Pure.
 */
object Briefing {
    private val ASK = Regex("^ (brief me|give me a brief(ing)?|briefing|catch me up|what do i need to know|what should i know|what s important|whats important|market briefing|quick update|bring me up to speed|update me on everything)( today| now| jarvis)? $")

    fun asked(text: String): Boolean = ASK.containsMatchIn(norm(text).replace(Regex("^ (jarvis|hey jarvis|ok jarvis|boss) "), " "))

    fun say(snaps: Map<Market, Snapshot>, now: LocalDateTime, events: List<String>): String? {
        val idx = Reasoning.INDICES.mapNotNull { m -> snaps[m]?.let { s -> s.changePct?.let { m to (s to it) } } }
        if (idx.isEmpty()) return null
        val parts = ArrayList<String>()
        parts += idx.joinToString(", ") { (m, sp) -> "${m.label} ${n(sp.first.price)} (${pct(sp.second)})" } + "."
        if (idx.size >= 2) {
            val best = idx.maxBy { it.second.second }; val worst = idx.minBy { it.second.second }
            if (best.second.second - worst.second.second >= 0.1) parts += "${best.first.label} is the strongest, ${worst.first.label} the weakest."
        }
        snaps[Market.VIX]?.let { v -> snaps[Market.NIFTY]?.let { nf -> ExpectedRange.say(nf, v.price, now)?.let { parts += it } } }
        snaps[Market.VIX]?.changePct?.let { if (it >= 5) parts += "Fear is rising (VIX ${pct(it)})." else if (it <= -5) parts += "Fear is easing (VIX ${pct(it)})." }
        parts += events.take(2)
        return parts.joinToString(" ")
    }
}

/** India VIX against its own past (Jarvis self-improvement, 2026-10-03): "is VIX high?". Pure. */
object VixRank {
    private val ASK = Regex(" (vix|india vix|volatility index|fear gauge|fear index) (is |s )?(high|low|elevated|normal|calm|usual)| (is|s) (the )?(india )?vix (high|low|elevated|normal)|how (high|low) is (the )?(india )?vix | vix (percentile|rank|history) ")

    fun asked(text: String): Boolean = ASK.containsMatchIn(norm(text))

    /** Where [now] sits among the daily closes of the last [days] sessions in [bars] (0..100), with the session count. */
    fun rank(bars: List<Candle>, now: Double, days: Int = 250): Pair<Double, Int>? {
        val closes = bars.groupBy { it.t.toLocalDate() }.toSortedMap().values.map { it.last().c }.takeLast(days)
        if (closes.size < 20) return null
        return closes.count { it < now } * 100.0 / closes.size to closes.size
    }

    fun say(bars: List<Candle>, now: Double): String? {
        val (r, n) = rank(bars, now) ?: return null
        val word = when { r >= 80 -> "high: fear is well above usual, options are dear"; r <= 20 -> "low: the market is calm, options are cheap"; else -> "around its usual level" }
        return "India VIX at %.2f is higher than %.0f%% of the last $n sessions' closes - $word.".format(Locale.ENGLISH, now, r)
    }
}
