package com.optionslab.ira

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * Breadth and leadership across the indices (Jarvis self-improvement, round 11, 2026-10-05): "is the rally broad or
 * narrow?", "which index is leading since the open / in the last hour?", "relative strength BankNifty vs Nifty this
 * week" (the ratio of the two and how it changed). The phone has the four indices and India VIX - no sector indices, no
 * stocks, no advance/decline counts - so breadth here is across the four indices, and the answer says so. Facts from the
 * 1-minute candles, every number from the data: never a cause, never a forecast, never advice. Pure.
 *
 * Not here: "is BankNifty stronger than Nifty?" or "which index is strongest today?" with no stretch of time ([Compare],
 * today's change), and "is BankNifty moving with Nifty?" ([Together]).
 */
object Breadth {
    val INDICES = Reasoning.INDICES
    /** A move smaller than this (% of the price) is flat. */
    const val FLAT_PCT = 0.05
    /** Strongest and weakest closer than this (points of percentage): no clear leader. */
    const val CLOSE_PCT = 0.10
    /** An index whose last candle is this many minutes behind the newest is left out (its feed stopped). */
    const val BEHIND_MIN = 5L
    /** The wrap-up names the leader and the laggard only when they are this far apart (points of percentage). */
    const val WRAP_GAP_PCT = 0.25

    const val NO_SECTORS = "I have no sector indices, stocks or advance/decline counts on the phone, Boss, so I can't say how many stocks joined in"

    enum class Kind { BREADTH, LEADERS, RELATIVE }

    enum class Span(val label: String) { TODAY("today"), WEEK("this week"), LAST_WEEK("last week"), MONTH("this month") }

    /**
     * What was asked: [markets] named (empty: the four indices), the stretch of the day ([window]) or the span of days
     * ([span]); [sectors]: sectors or stocks were asked about, which the phone has none of.
     */
    data class Asked(val kind: Kind, val markets: List<Market> = emptyList(), val window: Moves.Window? = null,
                     val span: Span? = null, val sectors: Boolean = false)

    private fun n(x: Double) = "%,.2f".format(Locale.ENGLISH, x)
    private fun pct(x: Double) = "%+.2f%%".format(Locale.ENGLISH, x)
    private fun pp(x: Double) = "%.2f".format(Locale.ENGLISH, abs(x))
    private fun hm(t: LocalDateTime) = "%02d:%02d".format(Locale.ENGLISH, t.hour, t.minute)
    private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    private fun norm(text: String) = " " + text.lowercase().replace(rx("[^a-z0-9 ]"), " ").replace(rx("\\s+"), " ").trim() + " "

    /** Boss's own, something to do, the news, a why or a forecast: never read here. */
    private val NOT_HERE = Regex(" (i|me|my|mine|we|our|buy|sell|order|orders|alert|alerts|alarm|alarms|remind|set|exit|news|headline|headlines|why|kyun|reason|predict|prediction|forecast|will|tomorrow|option chain|chain|pcr|put call|oi|open interest|strike|strikes) ")

    private val SECTORS = Regex(" (sector|sectors|sectoral|stocks|shares|advance decline|advances and declines|advances|declines|advancers|decliners|a d ratio|ad ratio|midcap|midcaps|smallcap|smallcaps|small caps|mid caps|it stocks|it sector|pharma|auto stocks|metal stocks|fmcg|realty|psu banks) ")

    private val BREADTH = Regex(" (breadth|market breadth|across the board|participation|advance decline|advances and declines|advancers|decliners|a d ratio|ad ratio|" +
        "(are|is) (all|all the|all four) (indices|index) (up|down|green|red|rising|falling|moving)|" +
        "(is|are) (it|this|the rally|the fall|the move|the market) (only|just) (one index|bank|banks|banknifty|bank nifty|financials)|" +
        "(only|just) (banks|financials|bank nifty|banknifty) (up|down|rising|falling|moving)) ")
    private val BROAD = Regex(" (broad|broad based|broadbased|broadly|narrow|narrowly|widespread) ")
    private val MOVE_WORD = Regex(" (rally|rallies|rallying|fall|falling|move|moves|moving|selloff|sell off|selling|buying|rise|rising|decline|declining|gain|gains|losses|strength|weakness|bounce|recovery|market|up|down|green|red) ")
    /** "A narrow range", "broad range": the day's range ([Structure], [MarketStory]), not breadth. */
    private val NOT_BROAD = Regex(" (range|ranges|band|spread|spreads|stop|stops|candle|candles) ")

    private val LEAD = Regex(" (leading|lagging|leader|leaders|leads|led|lags|lagged|laggard|laggards|leadership|strongest|weakest|stronger|weaker|best|worst|outperform\\w*|underperform\\w*) ")
    private val LEADERSHIP = Regex(" (leadership|who s leading|who is leading|whos leading|who leads|who led|which index led|which index is leading the (rally|fall|move|market|selloff|sell off|bounce)|" +
        "leading the (rally|fall|move|selloff|sell off|bounce)|lagging the (rally|fall|move|market)) ")

    private val RATIO = Regex(" (relative strength|relative performance|relatively stronger|relatively weaker|ratio) ")
    private val RATIO_ALONE = Regex(" (relative strength|relative performance) ")
    private val NOT_RATIO = Regex(" (put call|risk reward|reward|sharpe|win|odds|hedge|lot|lots) ")
    private val VERSUS = Regex(" (vs|versus|against|compared to|compared with|relative to|outperform\\w*|underperform\\w*|stronger|weaker|better|worse|beat|beaten|beating) ")

    private fun span(t: String): Span? = when {
        rx(" last week ").containsMatchIn(t) -> Span.LAST_WEEK
        rx(" (this week|the week|weekly|week so far|over the week) ").containsMatchIn(t) -> Span.WEEK
        rx(" (this month|the month|monthly|month so far) ").containsMatchIn(t) -> Span.MONTH
        rx(" (today|todays|today s|on the day) ").containsMatchIn(t) -> Span.TODAY
        else -> null
    }

    /** Which of the three [text] asks, or null. */
    fun asked(text: String): Asked? {
        val t = norm(text)
        if (NOT_HERE.containsMatchIn(t)) return null
        val named = Market.mentioned(text)
        if (Market.GOLD in named) return null
        val idx = named.filter { it in INDICES }
        val sectors = SECTORS.containsMatchIn(t)
        if (BREADTH.containsMatchIn(t) || (BROAD.containsMatchIn(t) && MOVE_WORD.containsMatchIn(t) && !NOT_BROAD.containsMatchIn(t)))
            return Asked(Kind.BREADTH, sectors = sectors)
        val sp = span(t)
        if ((RATIO.containsMatchIn(t) && !NOT_RATIO.containsMatchIn(t) && (idx.isNotEmpty() || RATIO_ALONE.containsMatchIn(t))) ||
            (idx.size >= 2 && VERSUS.containsMatchIn(t) && sp != null && sp != Span.TODAY))
            return Asked(Kind.RELATIVE, idx, span = sp ?: Span.WEEK)
        if (LEAD.containsMatchIn(t) || (sectors && rx(" (doing|performing|how are|how is|moving) ").containsMatchIn(t))) {
            val w = Moves.window(text)
            val markets = idx.takeIf { it.size >= 2 }.orEmpty()
            if (w != null) return Asked(Kind.LEADERS, markets, window = w, sectors = sectors)
            if (sp != null && sp != Span.TODAY) return Asked(Kind.LEADERS, markets, span = sp, sectors = sectors)
            if (LEADERSHIP.containsMatchIn(t) || sectors) return Asked(Kind.LEADERS, markets, sectors = sectors)
        }
        return null
    }

    /** One market's move over a stretch: from [from] to [to], the last candle used at [at]. */
    data class Row(val market: Market, val from: Double, val to: Double, val at: LocalDateTime) {
        val pct: Double get() = (to - from) / from * 100
    }

    /** Rows over one stretch, the newest candle [end], and the markets left out because their candles stop early. */
    data class Stretch(val rows: List<Row>, val end: LocalDateTime, val stale: List<Market>, val sessions: Int = 1, val fromClose: Boolean = true)

    /** [day]'s candles of each of [ms] in [bars] that has any. */
    private fun dayBars(ms: List<Market>, bars: Map<Market, List<Candle>>, day: LocalDate): List<Pair<Market, List<Candle>>> =
        ms.mapNotNull { m -> bars[m]?.filter { it.t.toLocalDate() == day }?.takeIf { it.isNotEmpty() }?.let { m to it } }

    /** The newest session on the phone across the indices, no later than [today]. */
    fun lastDay(bars: Map<Market, List<Candle>>, today: LocalDate): LocalDate? =
        INDICES.mapNotNull { m -> bars[m]?.lastOrNull { !it.t.toLocalDate().isAfter(today) }?.t?.toLocalDate() }.maxOrNull()

    /** Each market's move over [w] on [day], all ending at the newest candle (a market whose candles stop early left out). */
    fun window(ms: List<Market>, bars: Map<Market, List<Candle>>, day: LocalDate, w: Moves.Window): Stretch? {
        val d = dayBars(ms, bars, day)
        if (d.isEmpty()) return null
        val end = d.maxOf { it.second.last().t }
        val stale = d.filter { Duration.between(it.second.last().t, end).toMinutes() >= BEHIND_MIN }.map { it.first }
        val start = w.minutes?.let { end.minusMinutes(it.toLong() - 1) } ?: day.atTime(w.since ?: return null)
        if (!start.isBefore(end)) return Stretch(emptyList(), end, stale)
        val rows = d.filter { it.first !in stale }.mapNotNull { (m, b) ->
            val first = b.firstOrNull { !it.t.isBefore(start) } ?: return@mapNotNull null
            if (first.o <= 0) null else Row(m, first.o, b.last().c, b.last().t)
        }
        return Stretch(rows, end, stale)
    }

    /** Each market's change on [day] from the previous session's close (markets with no earlier session left out). */
    fun day(ms: List<Market>, bars: Map<Market, List<Candle>>, day: LocalDate): Stretch? {
        val d = dayBars(ms, bars, day)
        if (d.isEmpty()) return null
        val end = d.maxOf { it.second.last().t }
        val stale = d.filter { Duration.between(it.second.last().t, end).toMinutes() >= BEHIND_MIN }.map { it.first }
        val rows = d.filter { it.first !in stale }.mapNotNull { (m, b) ->
            val prev = bars[m]?.lastOrNull { it.t.toLocalDate().isBefore(day) }?.c?.takeIf { it > 0 } ?: return@mapNotNull null
            Row(m, prev, b.last().c, b.last().t)
        }
        return Stretch(rows, end, stale)
    }

    /**
     * Each market's move over [span] (the calendar's week or month around [today], or the last session for TODAY), on the
     * sessions all of [ms] have: from the close before the span (else its first open) to the latest close, all ending at
     * the same minute of the last session.
     */
    fun period(ms: List<Market>, bars: Map<Market, List<Candle>>, span: Span, today: LocalDate): Stretch? {
        val byDay = ms.mapNotNull { m -> bars[m]?.filter { !it.t.toLocalDate().isAfter(today) }?.takeIf { it.isNotEmpty() }?.let { m to it.groupBy { c -> c.t.toLocalDate() } } }
        if (byDay.size < 2) return null
        val common = byDay.map { it.second.keys }.reduce { a, b -> a intersect b }.sorted()
        if (common.isEmpty()) return null
        val monday = today.with(DayOfWeek.MONDAY)
        val (from, to) = when (span) {
            Span.TODAY -> common.last() to common.last()
            Span.WEEK -> monday to today
            Span.LAST_WEEK -> monday.minusWeeks(1) to monday.minusDays(1)
            Span.MONTH -> today.withDayOfMonth(1) to today
        }
        val inSpan = common.filter { !it.isBefore(from) && !it.isAfter(to) }
        if (inSpan.isEmpty()) return Stretch(emptyList(), today.atStartOfDay(), emptyList(), sessions = 0)
        val before = common.lastOrNull { it.isBefore(inSpan.first()) }
        val last = inSpan.last()
        val end = byDay.minOf { it.second.getValue(last).last().t }
        val rows = byDay.mapNotNull { (m, d) ->
            val start = (before?.let { d.getValue(it).last().c } ?: d.getValue(inSpan.first()).first().o).takeIf { it > 0 } ?: return@mapNotNull null
            val lastDay = d.getValue(last)
            Row(m, start, (lastDay.lastOrNull { !it.t.isAfter(end) } ?: lastDay.first()).c, end)
        }
        return Stretch(rows, end, emptyList(), inSpan.size, before != null)
    }

    /** "Sensex +0.62%, Nifty +0.55%, ... - Sensex leads and BankNifty lags, 0.72 points of percentage apart." */
    private fun ranked(rows: List<Row>): String {
        val r = rows.sortedByDescending { it.pct }
        val list = r.joinToString(", ") { "${it.market.label} ${pct(it.pct)}" }
        val gap = r.first().pct - r.last().pct
        val read = if (gap < CLOSE_PCT) "all within ${pp(gap)} points of percentage of each other, so no clear leader"
            else "${r.first().market.label} leads and ${r.last().market.label} lags, ${pp(gap)} points of percentage apart"
        return "$list - $read"
    }

    /** "As of 14:32" (with the day when it is not [today]). */
    private fun asOf(end: LocalDateTime, today: LocalDate) =
        "as of ${hm(end)}" + if (end.toLocalDate() != today) " on ${end.toLocalDate().format(DAY)} (the last session on the phone)" else ""

    private fun staleNote(stale: List<Market>, end: LocalDateTime, bars: Map<Market, List<Candle>>) =
        if (stale.isEmpty()) "" else " Left out: " + stale.joinToString(", ") { m ->
            "${m.label} (its candles stop at ${bars[m]?.lastOrNull { it.t.toLocalDate() == end.toLocalDate() }?.t?.let { hm(it) } ?: "?"})" } + "."

    private const val NO_DATA = "I don't have the indices' candles on the phone for that, Boss, so I can't say."

    fun answer(a: Asked, bars: Map<Market, List<Candle>>, today: LocalDate): String = when (a.kind) {
        Kind.BREADTH -> breadth(bars, today, a.sectors)
        Kind.LEADERS -> leaders(a, bars, today)
        Kind.RELATIVE -> relative(a, bars, today)
    }

    /** "Is the rally broad or narrow?": the four indices' changes on the day, how many go which way, and the last hour. */
    fun breadth(bars: Map<Market, List<Candle>>, today: LocalDate, sectors: Boolean = false): String {
        val d = lastDay(bars, today) ?: return NO_DATA
        val st = day(INDICES, bars, d) ?: return NO_DATA
        if (st.rows.size < 2) return "I have fewer than two indices with a previous close on the phone, Boss, so I can't say how broad the move is."
        val rows = st.rows.sortedByDescending { it.pct }
        val up = rows.filter { it.pct >= FLAT_PCT }; val down = rows.filter { it.pct <= -FLAT_PCT }
        val list = rows.joinToString(", ") { "${it.market.label} ${pct(it.pct)}" }
        val k = rows.size
        val read = when {
            up.isEmpty() && down.isEmpty() -> "there is no real move to be broad or narrow: all $k indices are within ${pp(FLAT_PCT)}% of the previous close ($list)"
            up.size == k || down.size == k -> {
                val way = if (up.size == k) "up" else "down"
                val strong = rows.maxBy { abs(it.pct) }; val weak = rows.minBy { abs(it.pct) }
                if (abs(weak.pct) >= abs(strong.pct) / 2) "the move is broad across the indices: all $k are $way, and evenly ($list)"
                else "all $k indices are $way, but unevenly: ${strong.market.label} ${pct(strong.pct)} carries it while ${weak.market.label} is only ${pct(weak.pct)} ($list)"
            }
            else -> {
                val main = rows.firstOrNull { it.market == Market.NIFTY }?.takeIf { abs(it.pct) >= FLAT_PCT }?.let { it.pct > 0 } ?: (up.size >= down.size)
                val with = if (main) up else down
                val word = if (main) "rally" else "fall"
                val rest = rows.filter { it !in with }
                if (with.size * 2 <= k) "the $word is narrow: only ${with.size} of $k indices ${if (main) "are up" else "are down"} (${with.joinToString(", ") { it.market.label }}) - $list"
                else "the $word is mostly broad: ${with.size} of $k indices ${if (main) "are up" else "are down"}, but not ${rest.joinToString(" or ") { it.market.label }} - $list"
            }
        }
        val out = ArrayList<String>()
        out += (if (sectors) "$NO_SECTORS; across the four indices, ${asOf(st.end, today)}, " else "Across the four indices ${asOf(st.end, today)}, Boss, ") + read + "."
        window(INDICES, bars, d, Moves.Window(minutes = 60, label = "in the last hour"))?.rows?.takeIf { it.size >= 2 }?.let { h ->
            val hu = h.count { it.pct >= FLAT_PCT }; val hd = h.count { it.pct <= -FLAT_PCT }
            out += "In the last hour $hu of ${h.size} rose and $hd fell: " + h.sortedByDescending { it.pct }.joinToString(", ") { "${it.market.label} ${pct(it.pct)}" } + "."
        }
        day(listOf(Market.VIX), bars, d)?.rows?.firstOrNull()?.let { v -> out += "India VIX is ${pct(v.pct)} on the day, at ${n(v.to)}." }
        if (!sectors) out += "That is breadth across the four indices only: the phone has no sector indices, stocks or advance/decline counts."
        out += "Nifty and Sensex share most of their heavyweights, as do BankNifty and FinNifty, so the four agreeing is less than it sounds."
        return out.joinToString(" ") + staleNote(st.stale, st.end, bars)
    }

    /** "Which index is leading since the open / in the last hour / this week?" (no stretch named: since the open and the last hour). */
    fun leaders(a: Asked, bars: Map<Market, List<Candle>>, today: LocalDate): String {
        val ms = a.markets.takeIf { it.size >= 2 } ?: INDICES
        val pre = if (a.sectors) "$NO_SECTORS, or which sector leads; here are the indices. " else ""
        if (a.span != null && a.span != Span.TODAY) {
            val p = period(ms, bars, a.span, today) ?: return NO_DATA
            if (p.sessions == 0) return "No sessions yet ${a.span.label} on the phone, Boss."
            if (p.rows.size < 2) return NO_DATA
            val from = if (p.fromClose) "from the close before it" else "from its first open"
            return "$pre${a.span.label.replaceFirstChar { it.uppercase() }}, Boss (${p.sessions} session${if (p.sessions > 1) "s" else ""}, $from to ${hm(p.end)} on ${p.end.toLocalDate().format(DAY)}): ${ranked(p.rows)}."
        }
        val d = lastDay(bars, today) ?: return NO_DATA
        val windows = listOfNotNull(a.window).ifEmpty { listOf(Moves.Window(since = Market.NIFTY.open, label = "since the open"), Moves.Window(minutes = 60, label = "in the last hour")) }
        val parts = ArrayList<String>()
        var end: LocalDateTime? = null; var stale: List<Market> = emptyList()
        for (w in windows) {
            val st = window(ms, bars, d, w) ?: continue
            end = st.end; stale = st.stale
            if (st.rows.size >= 2) parts += "${w.label.replaceFirstChar { it.uppercase() }}: ${ranked(st.rows)}."
        }
        val e = end ?: return NO_DATA
        if (parts.isEmpty()) return "I don't have candles from that stretch for two or more indices, Boss (${asOf(e, today)})."
        return "$pre${asOf(e, today).replaceFirstChar { it.uppercase() }}, Boss. " + parts.joinToString(" ") + staleNote(stale, e, bars)
    }

    /** "Relative strength BankNifty vs Nifty this week": the ratio of the two at the start and now, and each one's move. */
    fun relative(a: Asked, bars: Map<Market, List<Candle>>, today: LocalDate): String {
        val first = a.markets.firstOrNull() ?: Market.BANKNIFTY
        val second = a.markets.getOrNull(1) ?: if (first == Market.NIFTY) Market.BANKNIFTY else Market.NIFTY
        val span = a.span ?: Span.WEEK
        val p = period(listOf(first, second), bars, span, today) ?: return NO_DATA
        if (p.sessions == 0) return "No sessions yet ${span.label} on the phone, Boss."
        val x = p.rows.firstOrNull { it.market == first }; val y = p.rows.firstOrNull { it.market == second }
        if (x == null || y == null) return NO_DATA
        val r0 = x.from / y.from; val r1 = x.to / y.to
        val ch = (r1 / r0 - 1) * 100
        val name = "${first.label}/${second.label}"
        val label = if (span == Span.TODAY) (if (p.end.toLocalDate() == today) "today" else "on ${p.end.toLocalDate().format(DAY)}") else span.label
        val from = if (p.fromClose) "from the close before" else "from the first open"
        val read = when {
            abs(ch) < FLAT_PCT -> "the ratio barely moved (${pct(ch)}): the two kept pace"
            ch > 0 -> "${first.label} has been the stronger of the two, by ${pp(ch)}% on the ratio"
            else -> "${second.label} has been the stronger of the two, by ${pp(ch)}% on the ratio"
        }
        return "${first.label} against ${second.label} $label (${p.sessions} session${if (p.sessions > 1) "s" else ""}, $from to ${hm(p.end)}" +
            "${if (p.end.toLocalDate() != today) " on ${p.end.toLocalDate().format(DAY)}" else ""}), Boss: the $name ratio went from " +
            "%.4f to %.4f, %s. ".format(Locale.ENGLISH, r0, r1, pct(ch)) +
            "${first.label} ${pct(x.pct)} (${n(x.from)} to ${n(x.to)}), ${second.label} ${pct(y.pct)} (${n(y.from)} to ${n(y.to)}) - $read."
    }

    /**
     * For the wrap-up line: "Of the indices, BankNifty led (+1.10%) and Sensex lagged (+0.20%) on the day." - or null with
     * fewer than two or when they are within [WRAP_GAP_PCT] of each other.
     */
    fun leadership(changes: List<Pair<Market, Double>>): String? {
        if (changes.size < 2) return null
        val hi = changes.maxBy { it.second }; val lo = changes.minBy { it.second }
        if (hi.second - lo.second < WRAP_GAP_PCT) return null
        return "Of the indices, ${hi.first.label} led (${pct(hi.second)}) and ${lo.first.label} lagged (${pct(lo.second)}) on the day."
    }
}
