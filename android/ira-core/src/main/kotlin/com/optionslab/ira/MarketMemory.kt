package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

/**
 * Market memory (Jarvis self-improvement, 2026-10-05): the notable sessions in the candles on the phone - big opening
 * gaps, trend days, wide-range days, India VIX spikes, expiry days and big up or down days - remembered with their facts,
 * and asked about: "when did Nifty last gap down this much?", "last time VIX jumped like this, what did the day look
 * like?", "how many trend days this month?", "what happened the last 3 expiries?", "what big days do you remember?".
 * Every answer is past sessions with their dates and numbers, and how many sessions the phone holds when that is few:
 * never a forecast, never advice. Pure.
 */
object MarketMemory {
    /** An opening gap this big (% of the previous close) or more is a big gap. */
    const val BIG_GAP_PCT = 0.5
    /** A close-to-close move this big (%) or more is a big up or down day. */
    const val BIG_MOVE_PCT = 1.0
    /** A day's range this many times the average of the sessions before it is a wide-range day. */
    const val WIDE = 1.5
    /** A trend day opens within this share of its range from one end and closes within it from the other. */
    const val TREND_EDGE = 0.2
    /** Sessions averaged at most for the usual range; with fewer than [MIN_PAST] before a session it is not judged. */
    const val PAST = 20
    const val MIN_PAST = 5
    /** Fewer sessions than this on the phone: said as a short memory. */
    const val SHORT = 20
    /** The last hour of an expiry day is read from here. */
    val LAST_HOUR: LocalTime = LocalTime.of(14, 30)
    /** A session that ends before this is not a whole day (a gap in the saved candles): its range and shape are not judged. */
    private val WHOLE: LocalTime = LocalTime.of(15, 0)

    const val NOT_A_PROMISE = "Past sessions, not a promise."

    private fun n(x: Double) = "%,.2f".format(Locale.ENGLISH, x)
    private fun pts(x: Double) = "%+,.2f".format(Locale.ENGLISH, x)
    private fun pct(x: Double) = "%+.2f%%".format(Locale.ENGLISH, x)
    private fun pctAbs(x: Double) = "%.2f%%".format(Locale.ENGLISH, abs(x))
    private fun times(x: Double) = "%.1f".format(Locale.ENGLISH, x)
    private fun norm(text: String) = " " + text.lowercase().replace("%", " percent ").replace(rx("[^a-z0-9. ]"), " ")
        .replace(rx("(?<![0-9])\\.|\\.(?![0-9])"), " ").replace(rx("\\s+"), " ").trim() + " "

    /** "Friday 2026-09-12". */
    fun date(d: LocalDate) = d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + d

    /** One remembered session of an index. */
    data class Day(
        val market: Market, val day: LocalDate, val open: Double, val high: Double, val low: Double, val close: Double,
        val prevClose: Double?,
        /** The close at or before [LAST_HOUR] (null when the session has none). */
        val at1430: Double?,
        /** The average range of up to [PAST] whole sessions before it, when there are [MIN_PAST] or more. */
        val usualRange: Double?,
        /** A whole session (it ran to the close and is not today's, still trading). */
        val whole: Boolean,
        val expiry: Boolean,
        /** India VIX's close that day and its change from the session before (%), when the phone has them. */
        val vixClose: Double?, val vixPct: Double?,
    ) {
        val range: Double get() = high - low
        val gapPct: Double? get() = prevClose?.let { (open - it) / it * 100 }
        val gapPts: Double? get() = prevClose?.let { open - it }
        val changePct: Double? get() = prevClose?.let { (close - it) / it * 100 }
        val changePts: Double? get() = prevClose?.let { close - it }
        val rangeRatio: Double? get() = if (whole && usualRange != null && usualRange > 0) range / usualRange else null
        val wide: Boolean get() = (rangeRatio ?: 0.0) >= WIDE
        /** 1: a trend day up (opened near the low, closed near the high, on a range at least the usual); -1 down; 0 neither. */
        val trend: Int get() {
            val r = rangeRatio ?: return 0
            if (r < 1.0 || range <= 0) return 0
            val edge = range * TREND_EDGE
            return when {
                open - low <= edge && high - close <= edge -> 1
                high - open <= edge && close - low <= edge -> -1
                else -> 0
            }
        }
        val bigGap: Boolean get() = abs(gapPct ?: 0.0) >= BIG_GAP_PCT
        val bigMove: Boolean get() = abs(changePct ?: 0.0) >= BIG_MOVE_PCT
        val vixSpike: Boolean get() = (vixPct ?: 0.0) >= VixSpike.SPIKE_PCT
    }

    /**
     * [m]'s sessions in [bars] (1-minute candles over several days), oldest first. [expiries]: the days the phone knows
     * were [m]'s expiry days; [vix]: India VIX's candles (empty when none); [live]: the session on [today] is still
     * trading (its range and shape are then not judged).
     */
    fun days(m: Market, bars: List<Candle>, expiries: Set<LocalDate> = emptySet(), vix: List<Candle> = emptyList(),
             today: LocalDate? = null, live: Boolean = false): List<Day> {
        val ss = MarketStory.sessions(bars)
        val vs = MarketStory.sessions(vix).map { it.day to it.close }
        val vixAt = vs.mapIndexed { i, (d, c) -> d to (c to if (i > 0) (c - vs[i - 1].second) / vs[i - 1].second * 100 else null) }.toMap()
        val out = ArrayList<Day>()
        // The whole sessions so far, kept as they come (each session filtered every earlier one again before).
        val wholes = ArrayList<Day>()
        for ((i, s) in ss.withIndex()) {
            val whole = !s.bars.last().t.toLocalTime().isBefore(WHOLE) && !(live && s.day == today)
            val before = wholes.takeLast(PAST)
            val usual = if (before.size >= MIN_PAST) before.map { it.range }.average().takeIf { it > 0 } else null
            val v = vixAt[s.day]
            out += Day(m, s.day, s.open, s.bars.maxOf { it.h }, s.bars.minOf { it.l }, s.close, ss.getOrNull(i - 1)?.close,
                s.bars.lastOrNull { !it.t.toLocalTime().isAfter(LAST_HOUR) }?.c, usual, whole, s.day in expiries, v?.first, v?.second)
            if (whole) wholes += out.last()
        }
        return out
    }

    /** One day told in a sentence, its facts only. */
    fun describe(d: Day): String {
        val parts = ArrayList<String>()
        val label = d.market.label
        d.gapPct?.let { g ->
            parts += if (abs(g) < 0.05) "$label opened about flat at ${n(d.open)}"
                else "$label opened ${pctAbs(g)} ${if (g > 0) "higher" else "lower"} at ${n(d.open)} (a gap of ${pts(d.gapPts!!)} points)"
        } ?: run { parts += "$label opened at ${n(d.open)}" }
        parts += "ranged ${n(d.range)} points (${n(d.low)} to ${n(d.high)}" + (d.rangeRatio?.let { ", ${times(it)} times its usual" } ?: "") + ")"
        parts += "closed at ${n(d.close)}" + (d.changePct?.let { " (${pct(it)} on the day)" } ?: "")
        var s = "On ${date(d.day)} " + parts.joinToString(", ")
        when (d.trend) {
            1 -> s += " - a trend day up: it opened near its low and closed near its high"
            -1 -> s += " - a trend day down: it opened near its high and closed near its low"
        }
        s += "."
        if (d.vixPct != null && d.vixClose != null) s += " India VIX ${pct(d.vixPct)} to ${n(d.vixClose)}."
        if (d.expiry) s += " It was a ${label} expiry day" + (d.at1430?.let { "; the last hour from 14:30 moved ${pts(d.close - it)} points" } ?: "") + "."
        return s
    }

    // ---- the questions -----------------------------------------------------------------------------------------

    enum class Sort(val what: String) {
        GAP_UP("gapped up"), GAP_DOWN("gapped down"), GAP("gapped"), TREND("a trend day"), WIDE("a wide-range day"),
        VIX_SPIKE("India VIX jumped"), BIG_UP("rose"), BIG_DOWN("fell"), BIG_MOVE("moved"), EXPIRY("an expiry day")
    }

    enum class Kind { LAST, COUNT, EXPIRIES, NOTABLE }

    enum class Span(val label: String) { WEEK("this week"), LAST_WEEK("last week"), MONTH("this month"), LAST_MONTH("last month"), SESSIONS("") }

    /**
     * What was asked. [like]: "this much", "like this" - the reference is the latest session's own figure; [pct] or
     * [points]: a size given; [count]: how many expiries, or how many sessions back ([Span.SESSIONS]).
     */
    data class Ask(val kind: Kind, val sort: Sort? = null, val like: Boolean = false, val pct: Double? = null,
                   val points: Double? = null, val count: Int? = null, val span: Span? = null)

    private const val LEAD = "(jarvis |hey jarvis |ok jarvis |boss |so |and |tell me |please )*"
    /** A forecast, a study of what follows, advice, or Boss's own trades: never this. */
    private val NOT = Regex(" (will|would|tomorrow|next|predict|prediction|forecast|should|shall|usually|typically|normally|after a|after the|chance|chances|odds|probability|likely|i|me|my|mine|we|our|backtest|strategy|mean|means|meaning|define) ")
    private val LIKE = Regex(" (this much|that much|so much|as much|like this|like that|like today|this big|that big|as big|so big|this hard|such a|like it did today|as hard) ")
    private val WHEN_LAST = Regex(" when (did|was|were|has|had|have) [a-z ]{0,30}?last | when (was|is) the (last|previous|most recent) | (the )?last time | (the )?previous time | last (gap|gapped|trend|wide|big|vix|fear|day|session)[a-z ]{0,30}?(was|when|on) | when (did|was|were)[a-z ]{0,30}? (before|previously) | last (\\w+ ){0,3}(gap|gaps|gapped|gap up|gap down|trend day|wide range day|vix spike) $" +
        // Hinglish and "remember when" (routing audit, 5 Oct): "Nifty last kab gap down hua", "pichli baar VIX kab uchla".
        "| (last|pichli|pichhli|aakhri) (baar|bar|time|dafa) | last kab | kab (last|pichli baar|aakhri baar) |^ (do you )?remember when ")
    private val COUNTING = Regex(" how many | how often | number of | count | count of | kitne | kitni ")
    private val NOTABLE = Regex("^ $LEAD(what |which )?(big |notable |memorable |unusual |biggest )(days|sessions|market days)( do you remember| have there been| on the phone| you remember| in memory)?( lately| recently)?( boss| jarvis)? $|" +
        "^ $LEAD(do you remember|remember) (any )?(big|notable|unusual|memorable) (days|sessions)( lately| recently)?( boss| jarvis)? $|" +
        "^ $LEAD(market memory|your market memory|what does your market memory say|notable sessions|notable days)( boss| jarvis)? $|" +
        "^ $LEAD(what|which) (days|sessions) stood out( lately| recently)?( boss| jarvis)? $")

    private val WORDS = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
        "a couple of" to 2, "couple of" to 2, "few" to 3, "a few" to 3)

    private fun sort(t: String): Sort? = when {
        rx(" (gap|gaps|gapped) (up|higher) | gapup | gap ups | (opened|open|opens) (higher|up) ").containsMatchIn(t) -> Sort.GAP_UP
        rx(" (gap|gaps|gapped) (down|lower) | gapdown | gap downs | (opened|open|opens) (lower|down) ").containsMatchIn(t) -> Sort.GAP_DOWN
        rx(" (gap|gaps|gapped|big gap|big gaps|gap days|gap day) ").containsMatchIn(t) -> Sort.GAP
        rx(" (vix|india vix|fear|fear gauge) [a-z ]{0,12}?(jump|jumped|jumps|jumping|spike|spiked|spikes|spiking|surge|surged|shot up|rose|rise|rises|soared|popped)| (vix|fear) spikes? ").containsMatchIn(t) -> Sort.VIX_SPIKE
        rx(" (trend day|trend days|trending day|trending days|trendy day|one way day|one way days|one sided day|one sided days) ").containsMatchIn(t) -> Sort.TREND
        rx(" (wide range|wide ranging|wide days|wide day|big range|big ranges|range expansion|range day|range days|volatile day|volatile days|wild day|wild days) ").containsMatchIn(t) -> Sort.WIDE
        rx(" (expiry|expiries|expiry day|expiry days|expiry session|expiry sessions) ").containsMatchIn(t) -> Sort.EXPIRY
        rx(" (fell|fall|falls|fallen|dropped|drop|drops|crashed|crash|tanked|lost|slumped|big down day|big red day|down day|red day|down days|red days) ").containsMatchIn(t) -> Sort.BIG_DOWN
        rx(" (rose|rise|rises|risen|rallied|rally|surged|jumped|gained|soared|big up day|big green day|up day|green day|up days|green days) ").containsMatchIn(t) -> Sort.BIG_UP
        rx(" (big move|big moves|big day|big days|moved) ").containsMatchIn(t) -> Sort.BIG_MOVE
        else -> null
    }

    private fun span(t: String): Pair<Span?, Int?> {
        rx(" (last|past|previous) (\\d{1,3}|[a-z]+) (sessions|trading days|days|trading sessions) ").find(t)?.let { m ->
            val k = m.groupValues[2].toIntOrNull() ?: WORDS[m.groupValues[2]]
            if (k != null && k in 1..250) return Span.SESSIONS to k
        }
        return when {
            rx(" last week ").containsMatchIn(t) -> Span.LAST_WEEK to null
            rx(" (this week|the week|week so far) ").containsMatchIn(t) -> Span.WEEK to null
            rx(" last month ").containsMatchIn(t) -> Span.LAST_MONTH to null
            rx(" (this month|the month|month so far) ").containsMatchIn(t) -> Span.MONTH to null
            else -> null to null
        }
    }

    /** The question read, or null when it is not a market-memory question (or is a forecast, advice, or Boss's own). */
    fun asked(text: String): Ask? {
        val t = norm(text)
        if (NOTABLE.containsMatchIn(t.replace(rx(" (on |for )?(nifty|banknifty|bank nifty|finnifty|fin nifty|sensex) "), " "))) return Ask(Kind.NOTABLE)
        if (NOT.containsMatchIn(t)) return null
        // Only an index's sessions (gold trades round the clock: no session to remember this way).
        if (Market.mentioned(text).contains(Market.GOLD)) return null
        val so = sort(t) ?: return null
        // "What happened the last 3 expiries", "how did the last expiry go", "previous expiry days".
        if (so == Sort.EXPIRY) {
            if (rx(" (today|today s|todays|this expiry|current expiry|now) ").containsMatchIn(t)) return null
            val m = rx(" (last|past|previous|recent|pichle|pichhle|pichli) (\\d{1,2}|[a-z]+(?: of)?)? ?(expiry|expiries|expiry days|expiry sessions) ").find(t)
                ?: rx(" (last|past|previous|recent) (a few|a couple of|couple of) (expiry|expiries|expiry days|expiry sessions) ").find(t)
                ?: rx(" (recent|previous) (expiry|expiries) ").find(t)
            if (m == null) {
                if (COUNTING.containsMatchIn(t) || !WHEN_LAST.containsMatchIn(t)) return null
                return Ask(Kind.EXPIRIES, Sort.EXPIRY, count = 1)
            }
            val w = m.groupValues.getOrNull(2).orEmpty().trim()
            val k = w.toIntOrNull() ?: WORDS[w] ?: if (rx(" (expiries|expiry days|expiry sessions) ").containsMatchIn(t)) 3 else 1
            return Ask(Kind.EXPIRIES, Sort.EXPIRY, count = k.coerceIn(1, 8))
        }
        val size = rx(" (\\d{1,2}(?:\\.\\d{1,2})?) (percent|per cent|pc) ").find(t)?.groupValues?.get(1)?.toDoubleOrNull()
        val points = if (size == null) rx(" (\\d{2,4}) (points|point|pts|pt) ").find(t)?.groupValues?.get(1)?.toDoubleOrNull() else null
        if (COUNTING.containsMatchIn(t) && !rx(" in a row | consecutive | straight ").containsMatchIn(t)) {
            // "How many days up this month" without a size is a run of closes or the month's move, not a remembered kind.
            if ((so == Sort.BIG_UP || so == Sort.BIG_DOWN || so == Sort.BIG_MOVE) && size == null && points == null && !rx(" big ").containsMatchIn(t)) return null
            val (sp, k) = span(t)
            return Ask(Kind.COUNT, so, pct = size, points = points, count = k, span = sp)
        }
        if (!WHEN_LAST.containsMatchIn(t)) return null
        // "When did Nifty last fall" alone is too loose (the last red close): a size, "this much" or "in a day" is needed.
        val like = LIKE.containsMatchIn(t)
        if ((so == Sort.BIG_UP || so == Sort.BIG_DOWN || so == Sort.BIG_MOVE) && size == null && points == null && !like &&
            !rx(" (in a day|in one day|in a session|in one session|in a single day|single day|big|sharply|hard) ").containsMatchIn(t)) return null
        return Ask(Kind.LAST, so, like = like, pct = size, points = points)
    }

    // ---- the answers ---------------------------------------------------------------------------------------------

    private fun held(days: List<Day>): String {
        val k = days.size
        val first = days.firstOrNull()?.day ?: return "no sessions"
        return "the $k session${if (k == 1) "" else "s"} of ${days.first().market.label} on the phone (since $first)" +
            if (k < SHORT) " - a short memory" else ""
    }

    private fun matches(d: Day, s: Sort): Boolean = when (s) {
        Sort.GAP_UP -> (d.gapPct ?: 0.0) >= BIG_GAP_PCT
        Sort.GAP_DOWN -> (d.gapPct ?: 0.0) <= -BIG_GAP_PCT
        Sort.GAP -> d.bigGap
        Sort.TREND -> d.trend != 0
        Sort.WIDE -> d.wide
        Sort.VIX_SPIKE -> d.vixSpike
        Sort.BIG_UP -> (d.changePct ?: 0.0) >= BIG_MOVE_PCT
        Sort.BIG_DOWN -> (d.changePct ?: 0.0) <= -BIG_MOVE_PCT
        Sort.BIG_MOVE -> d.bigMove
        Sort.EXPIRY -> d.expiry
    }

    /** The day's own size for [s] (% unless [points]): the gap, the close-to-close move or VIX's change. */
    private fun size(d: Day, s: Sort, points: Boolean = false): Double? = when (s) {
        Sort.GAP_UP, Sort.GAP_DOWN, Sort.GAP -> if (points) d.gapPts else d.gapPct
        Sort.BIG_UP, Sort.BIG_DOWN, Sort.BIG_MOVE -> if (points) d.changePts else d.changePct
        Sort.VIX_SPIKE -> d.vixPct
        else -> null
    }

    private fun sizeWord(s: Sort, x: Double, points: Boolean): String {
        val v = if (points) "${n(abs(x))} points" else pctAbs(x)
        return when (s) {
            Sort.GAP_UP -> "a gap up of $v"; Sort.GAP_DOWN -> "a gap down of $v"; Sort.GAP -> "a gap of $v"
            Sort.BIG_UP -> "a rise of $v"; Sort.BIG_DOWN -> "a fall of $v"; Sort.BIG_MOVE -> "a move of $v"
            Sort.VIX_SPIKE -> "India VIX up $v"
            else -> v
        }
    }

    /** Is [x] at least [ref] the way [s] goes (a fall or gap down is at least as negative)? */
    private fun asBig(s: Sort, x: Double, ref: Double): Boolean = when (s) {
        Sort.GAP_DOWN, Sort.BIG_DOWN -> x <= -abs(ref)
        Sort.GAP, Sort.BIG_MOVE -> abs(x) >= abs(ref)
        else -> x >= abs(ref)
    }

    /**
     * The answer to [a] from [days] ([days]' own market; built by [days]). [today]: the calendar day now - "last" looks
     * before it, and "this much" is today's (else the latest session's) figure. Null when there is nothing to read.
     */
    fun answer(a: Ask, days: List<Day>, today: LocalDate): String? {
        if (days.isEmpty()) return null
        return when (a.kind) {
            Kind.NOTABLE -> notable(days)
            Kind.EXPIRIES -> expiries(days, a.count ?: 3, today)
            Kind.COUNT -> count(a, days, today)
            Kind.LAST -> last(a, days, today)
        }
    }

    private fun last(a: Ask, days: List<Day>, today: LocalDate): String? {
        val s = a.sort ?: return null
        val label = days.first().market.label
        // "This much": the latest session's own figure (today's when the phone has today).
        val refDay = days.last()
        val refWhen = if (refDay.day == today) "today" else "on ${refDay.day}"
        val points = a.points != null
        var ref: Double? = a.pct ?: a.points
        var refText: String? = null
        var note = ""
        if (ref == null && a.like) {
            val own = size(refDay, s, points)
            val sameWay = own != null && abs(own) >= 0.05 && when (s) {
                Sort.GAP_UP, Sort.BIG_UP, Sort.VIX_SPIKE -> own > 0
                Sort.GAP_DOWN, Sort.BIG_DOWN -> own < 0
                else -> true
            }
            if (sameWay) { ref = own; refText = "as big as ${if (refDay.day == today) "today's" else "the ${refDay.day} session's"} (${pctAbs(own!!)})" }
            else if (own != null) note = " (${if (s == Sort.VIX_SPIKE) "India VIX" else label} $refWhen is ${pct(own)}, not that way, so I looked for a big one)"
        }
        // Without a size: the usual bar for a big one; trend, wide-range and expiry days have none.
        val bar = ref ?: when (s) {
            Sort.GAP_UP, Sort.GAP_DOWN, Sort.GAP -> BIG_GAP_PCT
            Sort.BIG_UP, Sort.BIG_DOWN, Sort.BIG_MOVE -> BIG_MOVE_PCT
            Sort.VIX_SPIKE -> VixSpike.SPIKE_PCT
            else -> null
        }
        // "Last" looks before today (and before the session "this much" is measured on).
        val pool = if (refDay.day == today || a.like) days.dropLast(1) else days
        val hit = pool.lastOrNull { d -> if (bar == null) matches(d, s) else size(d, s, points)?.let { asBig(s, it, bar) } == true }
        val kind = when {
            bar == null -> s.what
            refText != null -> KIND.getValue(s) + " " + refText
            else -> sizeWord(s, bar, points) + " or more"
        }
        if (hit == null) {
            val biggest = if (bar != null) pool.mapNotNull { d -> size(d, s, points)?.let { d to it } }
                .filter { (_, x) -> when (s) { Sort.GAP_DOWN, Sort.BIG_DOWN -> x < 0; Sort.GAP_UP, Sort.BIG_UP, Sort.VIX_SPIKE -> x > 0; else -> true } }
                .maxByOrNull { abs(it.second) } else null
            val most = biggest?.let { (d, x) -> " The biggest there was ${sizeWord(s, x, points)} on ${date(d.day)}." } ?: ""
            val missing = if (s == Sort.VIX_SPIKE && pool.none { it.vixPct != null }) " I have no India VIX candles for those sessions." else ""
            return "Nothing like that in ${held(pool.ifEmpty { days })}, Boss: no session with $kind$note.$most$missing $NOT_A_PROMISE"
        }
        val ago = days.size - 1 - days.indexOf(hit)
        val agoWord = if (ago == 1) "1 session ago" else "$ago sessions ago"
        val own = size(hit, s, points)?.let { ": ${sizeWord(s, it, points)}" } ?: ""
        val head = if (bar == null) "The last ${s.what.removePrefix("an ").removePrefix("a ")} for $label was ${date(hit.day)} ($agoWord)"
            else "The last session with $kind$note was ${date(hit.day)} ($agoWord)$own"
        return "$head. ${describe(hit)} That is from ${held(days)}. $NOT_A_PROMISE"
    }

    /** What a size is of, for "a gap down as big as today's". */
    private val KIND = mapOf(Sort.GAP_UP to "a gap up", Sort.GAP_DOWN to "a gap down", Sort.GAP to "a gap", Sort.BIG_UP to "a rise",
        Sort.BIG_DOWN to "a fall", Sort.BIG_MOVE to "a move", Sort.VIX_SPIKE to "an India VIX jump")

    /** The sessions in [a]'s span, how it is said, and the day the span starts (null: no calendar start). */
    private fun spanDays(a: Ask, days: List<Day>, today: LocalDate): Triple<List<Day>, String, LocalDate?> {
        val monday = today.with(DayOfWeek.MONDAY)
        val first = today.withDayOfMonth(1)
        fun between(from: LocalDate, until: LocalDate) = days.filter { !it.day.isBefore(from) && it.day.isBefore(until) }
        return when (a.span) {
            Span.WEEK -> Triple(between(monday, today.plusDays(1)), "this week", monday)
            Span.LAST_WEEK -> Triple(between(monday.minusWeeks(1), monday), "last week", monday.minusWeeks(1))
            Span.MONTH -> Triple(between(first, today.plusDays(1)), "this month", first)
            Span.LAST_MONTH -> Triple(between(first.minusMonths(1), first), "last month", first.minusMonths(1))
            Span.SESSIONS -> days.takeLast(a.count ?: PAST).let { Triple(it, "in the last ${it.size} sessions", null) }
            null -> Triple(days, "in ${held(days)}", null)
        }
    }

    private fun count(a: Ask, days: List<Day>, today: LocalDate): String? {
        val s = a.sort ?: return null
        val label = days.first().market.label
        val (inSpan, where, start) = spanDays(a, days, today)
        if (inSpan.isEmpty()) return "I have no ${label} sessions $where on the phone, Boss (${held(days)})."
        val bar = a.pct ?: a.points
        val hits = inSpan.filter { d -> if (bar == null) matches(d, s) else size(d, s, a.points != null)?.let { asBig(s, it, bar) } == true }
        val kind = when {
            bar != null -> "session${if (hits.size == 1) "" else "s"} with ${sizeWord(s, bar, a.points != null)} or more"
            s == Sort.TREND -> "trend day${if (hits.size == 1) "" else "s"}"
            s == Sort.WIDE -> "wide-range day${if (hits.size == 1) "" else "s"} (${times(WIDE)} times the usual range or more)"
            s == Sort.VIX_SPIKE -> "day${if (hits.size == 1) "" else "s"} with India VIX up ${VixSpike.SPIKE_PCT.toInt()}% or more"
            s == Sort.EXPIRY -> "expiry day${if (hits.size == 1) "" else "s"}"
            s == Sort.GAP_UP -> "gap up${if (hits.size == 1) "" else "s"} of ${BIG_GAP_PCT}% or more"
            s == Sort.GAP_DOWN -> "gap down${if (hits.size == 1) "" else "s"} of ${BIG_GAP_PCT}% or more"
            s == Sort.GAP -> "opening gap${if (hits.size == 1) "" else "s"} of ${BIG_GAP_PCT}% or more"
            s == Sort.BIG_UP -> "day${if (hits.size == 1) "" else "s"} up ${BIG_MOVE_PCT}% or more"
            s == Sort.BIG_DOWN -> "day${if (hits.size == 1) "" else "s"} down ${BIG_MOVE_PCT}% or more"
            else -> "day${if (hits.size == 1) "" else "s"} moving ${BIG_MOVE_PCT}% or more"
        }
        val of = if (a.span == null) "" else " (of ${inSpan.size} session${if (inSpan.size == 1) "" else "s"})"
        val parts = ArrayList<String>()
        parts += "$label had ${hits.size} $kind $where$of" + if (hits.isEmpty()) "." else ": " + hits.takeLast(6).joinToString(", ") { d ->
            d.day.toString() + when {
                s == Sort.TREND -> if (d.trend > 0) " (up)" else " (down)"
                s == Sort.WIDE -> " (${times(d.rangeRatio ?: 0.0)} times)"
                s == Sort.EXPIRY -> d.changePct?.let { " (${pct(it)})" } ?: ""
                else -> size(d, s, a.points != null)?.let { " (${if (a.points != null) pts(it) else pct(it)})" } ?: ""
            }
        } + (if (hits.size > 6) ", and earlier ones" else "") + "."
        // Whole-day kinds need a usual range and a finished session.
        if (s == Sort.TREND || s == Sort.WIDE) {
            val unjudged = inSpan.count { it.rangeRatio == null }
            if (unjudged > 0) parts += "$unjudged of those sessions can't be judged (still trading, cut short, or too early on the phone to have a usual range)."
        }
        if (s == Sort.VIX_SPIKE && inSpan.none { it.vixPct != null }) parts += "I have no India VIX candles for those sessions."
        if (s == Sort.EXPIRY && hits.isEmpty()) parts += "I only know an expiry day from the option candles saved that day."
        if (start != null && days.first().day.isAfter(start)) parts += "The phone's ${label} sessions only start on ${days.first().day}."
        else if (a.span == Span.SESSIONS && inSpan.size < (a.count ?: 0)) parts += "That is all the phone has (${held(days)})."
        parts += NOT_A_PROMISE
        return parts.joinToString(" ")
    }

    private fun expiries(days: List<Day>, k: Int, today: LocalDate): String {
        val label = days.first().market.label
        // Only finished expiry days: today's, still trading, is the expiry-day companion's.
        val ex = days.filter { it.expiry && it.whole && !it.day.isAfter(today) }
        if (ex.isEmpty()) return "I don't have any ${label} expiry day I'm sure of in ${held(days)}, Boss: I only know an expiry day from the option candles saved that day."
        val got = ex.takeLast(k)
        val head = if (got.size < k) "I have only ${got.size} ${label} expiry day${if (got.size == 1) "" else "s"} on the phone, Boss." else
            "${label}'s last ${if (k == 1) "expiry" else "$k expiries"}, Boss:"
        val lines = got.reversed().map { d ->
            val bits = ArrayList<String>()
            d.changePct?.let { bits += "closed ${pct(it)} at ${n(d.close)}" } ?: run { bits += "closed at ${n(d.close)}" }
            bits += "range ${n(d.range)} points" + (d.rangeRatio?.let { " (${times(it)} times usual)" } ?: "")
            d.gapPct?.takeIf { abs(it) >= 0.2 }?.let { bits += "opened with a ${pctAbs(it)} gap ${if (it > 0) "up" else "down"}" }
            d.at1430?.let { bits += "the last hour from 14:30 ${pts(d.close - it)} points" }
            if (d.trend != 0) bits += "a trend day ${if (d.trend > 0) "up" else "down"}"
            d.vixPct?.let { bits += "India VIX ${pct(it)}" }
            "${date(d.day)}: " + bits.joinToString(", ") + "."
        }
        return (listOf(head) + lines + NOT_A_PROMISE).joinToString(" ")
    }

    private fun notable(days: List<Day>): String {
        val label = days.first().market.label
        val parts = ArrayList<String>()
        parts += "From ${held(days)}, Boss:"
        fun latest(s: Sort) = days.lastOrNull { matches(it, s) }
        val gaps = days.filter { it.bigGap }
        val bits = ArrayList<String>()
        if (gaps.isNotEmpty()) bits += "${gaps.size} big gap${if (gaps.size == 1) "" else "s"} of ${BIG_GAP_PCT}% or more (latest ${pct(gaps.last().gapPct!!)} on ${gaps.last().day})"
        val trend = days.filter { it.trend != 0 }
        if (trend.isNotEmpty()) bits += "${trend.size} trend day${if (trend.size == 1) "" else "s"} (latest ${trend.last().day}, ${if (trend.last().trend > 0) "up" else "down"})"
        val wide = days.filter { it.wide }
        if (wide.isNotEmpty()) bits += "${wide.size} wide-range day${if (wide.size == 1) "" else "s"} (latest ${wide.last().day}, ${times(wide.last().rangeRatio!!)} times usual)"
        val big = days.filter { it.bigMove }
        if (big.isNotEmpty()) bits += "${big.size} day${if (big.size == 1) "" else "s"} moving ${BIG_MOVE_PCT}% or more (latest ${pct(big.last().changePct!!)} on ${big.last().day})"
        latest(Sort.VIX_SPIKE)?.let { v -> bits += "${days.count { it.vixSpike }} India VIX jump${if (days.count { it.vixSpike } == 1) "" else "s"} of ${VixSpike.SPIKE_PCT.toInt()}% or more (latest ${pct(v.vixPct!!)} on ${v.day})" }
        val ex = days.filter { it.expiry && it.whole }
        if (ex.isNotEmpty()) bits += "${ex.size} expiry day${if (ex.size == 1) "" else "s"} (latest ${ex.last().day})"
        if (bits.isEmpty()) return "Nothing stands out in ${held(days)}, Boss: no big gap, trend day, wide-range day, big move or VIX jump. $NOT_A_PROMISE"
        parts += bits.joinToString("; ") + "."
        // The single biggest day by its close-to-close move.
        days.filter { it.changePct != null }.maxByOrNull { abs(it.changePct!!) }?.takeIf { it.bigMove }?.let { parts += "The biggest: ${describe(it)}" }
        parts += "Ask me about any of them, like \"when did $label last gap down this much?\". $NOT_A_PROMISE"
        return parts.joinToString(" ")
    }
}
