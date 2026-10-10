package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale
import kotlin.math.abs

/**
 * Two sessions side by side (Jarvis reasoning, round 12, 2026-10-05): "how is today different from yesterday?", "is today
 * more like a trend day than yesterday?", "compare today with last Thursday", "Nifty today vs Friday", "aaj aur kal mein kya
 * fark hai". Each day is read with the day's own structure measure ([Structure.read]: the net move from the open as a share
 * of the range, where the price sits in it, how often the closes crossed the open, the 15-minute swings, the opening range,
 * the gap), and while today is still trading the other day is cut at the same minute, so a morning is set against a morning
 * (the other day's whole session is said too). Then what differs, measure against measure: which day was the more
 * trend-like and by how much, the ranges, the direction, the gaps, when the high and low were made. Facts from the 1-minute
 * candles on the phone, every number from the data: never a cause, a forecast or advice; nothing acts. The whole day's
 * story stays [MarketStory]'s, today against the usual day too; today alone stays [Structure]'s. Pure.
 */
object DayCompare {
    /** [DAY]: the earlier session alone ("how was yesterday for Nifty?"), set beside today only when today has candles. */
    enum class Focus { ALL, TREND_RANGE, DAY }

    /** The other day: [back] sessions before today (1 the last one), or the last [weekday] before today. */
    data class Ref(val back: Int = 1, val weekday: DayOfWeek? = null)

    data class Q(val other: Ref, val focus: Focus)

    /** Trend-like against range-like: a gap in the shares (net move over range) at least this large is a real difference. */
    const val SHARE_GAP = 0.15
    /** One range at least this many times the other is wider (narrower at its inverse). */
    const val WIDER = 1.3

    const val NOTE = "Facts from the candles on the phone, Boss - how the two days looked, not what comes next."
    const val NOT_HERE = "I compare days for Nifty, BankNifty, FinNifty and Sensex only, Boss: gold trades round the clock and India VIX is not traded."

    private fun n(x: Double) = "%,.2f".format(Locale.ENGLISH, x)
    private fun pts(x: Double) = "%+,.2f".format(Locale.ENGLISH, x)
    private fun pct(x: Double) = "%+.2f%%".format(Locale.ENGLISH, x)
    private fun hm(t: LocalDateTime) = "%02d:%02d".format(Locale.ENGLISH, t.hour, t.minute)
    private fun hm(t: LocalTime) = "%02d:%02d".format(Locale.ENGLISH, t.hour, t.minute)
    private fun date(d: LocalDate) = d.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() } + " " + d.dayOfMonth + " " +
        d.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
    private fun share(x: Double) = "${(x * 100).toInt()}%"

    private val WEEKDAYS = mapOf("monday" to DayOfWeek.MONDAY, "tuesday" to DayOfWeek.TUESDAY, "wednesday" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY, "friday" to DayOfWeek.FRIDAY)

    /** Lower case, words only, the index names and a few fillers taken out ("how is Nifty's day today ..." -> "how is today ..."). */
    private fun norm(text: String): String {
        var t = Spaced.words(text)
        for (a in Market.ALIASES_LONGEST_FIRST) t = t.replace(" $a ", " ")
        t = t.replace(rx(" (jarvis|hey|ok|boss|please|so|the|market|markets|index|indices|s|day s|session s|ka|ki|ke) "), " ")
        t = t.replace(rx(" (jarvis|hey|ok|boss|please|so|the|market|markets|index|indices|s|day s|session s|ka|ki|ke) "), " ")
        return t.replace(rx("\\s+"), " ")
    }

    // A forecast ("kal kya hoga"), advice, Boss's own book, the chain or the news, a what-if: never this.
    private const val NOT = " (will|would|going to|gonna|tomorrow|predict|prediction|forecast|should|shall|buy|sell|enter|exit|" +
        "hoga|hogi|honge|i|me|my|mine|we|our|pnl|p l|profit|profits|loss|losses|trade|trades|position|positions|" +
        "news|headline|headlines|oi|open interest|chain|pcr|max pain|if|suppose|imagine|week|month|usually|average|usual|normal|typical) "
    private const val REF = "(day before yesterday|day before|yesterday|yesterdays|yday|kal|parso|parson|previous day|previous session|" +
        "last session|last trading day|session before|(last |on |this past |past )?(monday|tuesday|wednesday|thursday|friday)s?)"
    private const val TODAY = "(today|todays|aaj|aj)"
    private const val KIND = "(trend|trending|trendy|range|ranging|range bound|rangebound|sideways|choppy|directional|one way)"
    private val ASK = listOf(
        // "How is today different from yesterday?", "how was today unlike Thursday?"
        "^ (how|in what way|what way) (is|was) $TODAY (different|differ|unlike|similar|compared)( to| from| with| than)? $REF ",
        "^ (how does|how did) $TODAY (compare|differ|stack up|look)( to| with| from| against| next to)? $REF ",
        // "Compare today with yesterday", "compare yesterday and today", "comparison between today and Friday"
        "^ compare $TODAY( and| with| to| against| vs| versus)? $REF ",
        "^ compare $REF( and| with| to| against| vs| versus)? $TODAY ",
        "^ (comparison|compare) (of |between )?$TODAY (and|with|vs|versus) $REF ",
        // "Today vs yesterday", "Friday versus today"
        "^ $TODAY (vs|versus|against|compared to|compared with|or) $REF( on| for| in)? $",  // ("today versus yesterday on Sensex": the index taken out leaves its "on"; round 12)
        "^ $REF (vs|versus|against|compared to|compared with) $TODAY( on| for| in)? $",
        // "Is today more like a trend day than yesterday?", "is today more of a range day or a trend day than Thursday?"
        "^ (is|was) $TODAY (more|less) (like |of )?(a |an )?$KIND( day)?( or (a |an )?$KIND( day)?)? (than|compared to|compared with|vs) $REF ",
        "^ (is|was) $TODAY (like|similar to|same as|different from|any different from|different to|different than) $REF ",
        "^ (is|was) $TODAY (a |an )?$KIND day like $REF ",
        // "What's different between today and yesterday?", "what's different about today from Monday?"
        "^ (what|whats|what is|what are) (difference|differences|different) between $TODAY and $REF ",
        "^ (what|whats|what is) (different|changed) (about )?$TODAY (from|than|compared to|compared with|since|vs) $REF ",
        "^ (difference|differences) between $TODAY and $REF ",
        // Hinglish: "aaj aur kal mein kya fark hai", "aaj kal se kaise alag hai", "kal aur aaj mein farak"
        "^ $TODAY (aur|or|vs) $REF (me |mein |main )?(kya |kitna )?(fark|farak|farq|difference|antar|alag)",
        "^ $REF (aur|or|vs) $TODAY (me |mein |main )?(kya |kitna )?(fark|farak|farq|difference|antar|alag)",
        "^ $TODAY $REF se (kaise|kitna|kya|kaisa) (alag|different|farak|fark)",
    )

    /**
     * The earlier session alone, as a whole question (routing round 13: "how was yesterday for Nifty" got Boss's own
     * history): "how was Nifty yesterday", "how did BankNifty do on Friday", "how was the market yesterday", "kal Nifty
     * kaisa tha". Only with an index or the market named - a bare "how was yesterday" may be Boss's own day.
     */
    private val ONE_DAY = listOf(
        "^ how (was|did|has) (day |session )?(go |do |move |end |look |close |finish )?$REF( day| session)?( go| do| move| end| look| looked| like| close| finish| turn out| shape up)?( for| on| in)? $",
        "^ $REF (day |session |din )?(kaisa|kaisi|kaise) (tha|thi|the|raha|rahi|rahe|gaya|gayi|hua|gaya tha|raha tha)( din)? $",
    )
    private val MARKET_NAMED = Regex("(?i)\\b(market|markets|bazaar|bazar|index|indices)\\b")

    /** What was asked, or null: today against one earlier session, as a whole question. Never a forecast or another span. */
    fun asked(text: String): Q? = askedKept.of(text) { askedFresh(text) }

    /** The last words read (speed round 10: the hub reads them as said, then again in its order; [Kept], pure). */
    private val askedKept = Kept<Q?>(64)

    private fun askedFresh(text: String): Q? {
        if (Market.mentioned(text).any { it == Market.GOLD }) return null
        val t = norm(text)
        if (rx(NOT).containsMatchIn(t)) return null
        // (A weekday said in the plural - "how did Nifty do on Fridays" - is the weekday record's, never one day.)
        val one = ONE_DAY.any { rx(it).containsMatchIn(t) } && (Market.mentioned(text).isNotEmpty() || MARKET_NAMED.containsMatchIn(text)) &&
            !rx(" (mondays|tuesdays|wednesdays|thursdays|fridays) ").containsMatchIn(t)
        if (!one && ASK.none { rx(it).containsMatchIn(t) }) return null
        val focus = if (one) Focus.DAY else if (rx(" $KIND ").containsMatchIn(t)) Focus.TREND_RANGE else Focus.ALL
        val wd = rx(" (monday|tuesday|wednesday|thursday|friday)s? ").find(t)?.groupValues?.get(1)?.let { WEEKDAYS[it] }
        val ref = when {
            wd != null -> Ref(weekday = wd)
            rx(" (day before yesterday|day before|parso|parson) ").containsMatchIn(t) -> Ref(back = 2)
            else -> Ref(back = 1)
        }
        return Q(ref, focus)
    }

    /** The index asked about (Nifty when none is named), or null for India VIX alone. */
    fun market(markets: List<Market>): Market? = Structure.market(markets)

    /** The date [ref] names from [today], given the [days] with candles on the phone; null when there is none. */
    fun resolve(ref: Ref, days: List<LocalDate>, today: LocalDate): LocalDate? {
        val wd = ref.weekday
        if (wd != null) return (1..7L).map { today.minusDays(it) }.first { it.dayOfWeek == wd }
        return days.filter { it.isBefore(today) }.sortedDescending().getOrNull(ref.back - 1)
    }

    /** One day read up to [cut] (the minute it stops; null the whole session). */
    private fun readAt(m: Market, bars: List<Candle>, day: LocalDate, cut: LocalTime?): Structure.Read? =
        Structure.read(m, bars.filter { val d = it.t.toLocalDate(); d.isBefore(day) || (d == day && (cut == null || it.t.toLocalTime().isBefore(cut))) }, day)

    private fun kindWord(r: Structure.Read): String = when (r.dayKind) {
        1 -> "trend-like, up"
        -1 -> "trend-like, down"
        0 -> "range-like"
        else -> "in between - neither a clear trend nor a clear range"
    }

    private fun part(m: Market, t: LocalDateTime): String {
        val open = m.open ?: return ""; val close = m.close ?: return ""
        val tm = t.toLocalTime()
        return when {
            tm.isBefore(open.plusHours(1)) -> "in the first hour"
            !tm.isBefore(close.minusHours(1)) -> "in the last hour"
            else -> "mid-session"
        }
    }

    private fun gapPct(r: Structure.Read): Double? = r.prevClose?.takeIf { it > 0 }?.let { (r.open - it) / it * 100 }

    /** One day's measures in a sentence. */
    private fun dayLine(name: String, r: Structure.Read, full: Boolean): String {
        val bits = ArrayList<String>()
        bits += "${kindWord(r)} - net ${pts(r.net)} from the open, ${share(r.share)} of a ${n(r.range)}-point range, ${if (full) "closing" else "the price"} ${share(r.place)} of the way up it"
        bits += "the 1-minute closes crossed the open ${r.openCrosses} time${if (r.openCrosses == 1) "" else "s"}"
        bits += "high ${n(r.high.price)} at ${hm(r.high.at)}, low ${n(r.low.price)} at ${hm(r.low.at)}"
        gapPct(r)?.let { bits += "opened ${pct(it)} from the prior close" }
        r.fifteen.label?.let { bits += "$it on 15-minute candles" }
        return name.replaceFirstChar { it.uppercase() } + ": " + bits.joinToString("; ") + "."
    }

    /** Which of the two was the more trend-like, by the measure, said with both numbers. */
    internal fun verdict(aName: String, a: Structure.Read, bName: String, b: Structure.Read): String {
        val d = a.share - b.share
        val both = "the net move was ${share(a.share)} of the range $aName against ${share(b.share)} $bName, and the closes crossed the open " +
            "${a.openCrosses} time${if (a.openCrosses == 1) "" else "s"} against ${b.openCrosses}"
        if (abs(d) < SHARE_GAP) return "On trend against range the two are close: $both."
        val (more, less) = if (d > 0) aName to bName else bName to aName
        val crossesAgree = if (d > 0) a.openCrosses <= b.openCrosses else b.openCrosses <= a.openCrosses
        return if (crossesAgree) "${more.replaceFirstChar { it.uppercase() }} was the more trend-like and $less the more range-like: $both."
            else "Mixed on trend against range: $both - $more moved further for its range, but crossed its open more often."
    }

    /** The other differences, measure against measure (each only when it differs). */
    private fun contrasts(m: Market, aName: String, a: Structure.Read, bName: String, b: Structure.Read): List<String> {
        val out = ArrayList<String>()
        if (a.range > 0 && b.range > 0) {
            val q = a.range / b.range
            out += when {
                q >= WIDER -> "${aName.replaceFirstChar { it.uppercase() }}'s range was ${"%.1f".format(Locale.ENGLISH, q)} times $bName's (${n(a.range)} against ${n(b.range)} points)."
                q <= 1 / WIDER -> "${aName.replaceFirstChar { it.uppercase() }}'s range was narrower - ${n(a.range)} against ${n(b.range)} points (${"%.1f".format(Locale.ENGLISH, q)} times)."
                else -> "The ranges were similar: ${n(a.range)} against ${n(b.range)} points."
            }
        }
        val sa = a.net.compareTo(0.0); val sb = b.net.compareTo(0.0)
        if (sa != 0 && sb != 0 && sa != sb) out += "They moved opposite ways from the open: ${pts(a.net)} $aName, ${pts(b.net)} $bName."
        val ga = gapPct(a); val gb = gapPct(b)
        if (ga != null && gb != null && (abs(ga) >= 0.2 || abs(gb) >= 0.2) && (ga.compareTo(0.0) != gb.compareTo(0.0) || abs(ga - gb) >= 0.2))
            out += "The opens: ${pct(ga)} from the prior close $aName, ${pct(gb)} $bName."
        val ph = part(m, a.high.at) to part(m, b.high.at); val pl = part(m, a.low.at) to part(m, b.low.at)
        if (ph.first != ph.second || pl.first != pl.second)
            out += "The high came ${ph.first} $aName and ${ph.second} $bName; the low ${pl.first} $aName and ${pl.second} $bName."
        val la = a.fifteen.label; val lb = b.fifteen.label
        if (la != null && lb != null && la != lb) out += "On 15-minute candles: $la $aName, $lb $bName."
        return out
    }

    /**
     * The answer to [q] for [m] from its 1-minute [bars] (several days) on [today]. While today is still trading (its newest
     * candle before the last minute of the session) the other day is cut at the same minute; its whole session is said too.
     */
    fun answer(q: Q, m: Market, bars: List<Candle>, today: LocalDate): String {
        if (m.open == null || m.close == null || m == Market.VIX) return NOT_HERE
        val days = bars.map { it.t.toLocalDate() }.distinct().sorted()
        val other = resolve(q.other, days, today)
            ?: return "There is no ${m.label} session before today on the phone, Boss, so there is nothing to set today against."
        val otherName = when {
            q.other.weekday != null -> "last ${q.other.weekday.name.lowercase().replaceFirstChar { it.uppercase() }} (${date(other)})"
            other == today.minusDays(1) -> "yesterday (${date(other)})"
            q.other.back == 1 -> "the last session (${date(other)})"
            else -> "the session before last (${date(other)})"
        }
        if (other !in days) return "There are no ${m.label} candles for $otherName on the phone, Boss - a market holiday, or before what the phone keeps - so I can't set today against it."
        if (q.focus == Focus.DAY) return oneDay(m, bars, today, other, otherName)
        val todays = bars.filter { it.t.toLocalDate() == today }.sortedBy { it.t }
        if (todays.size < Structure.MIN_BARS) return if (todays.isEmpty()) "There are no ${m.label} candles for today on the phone yet, Boss, so there is nothing to compare."
            else "It's early for ${m.label}, Boss: only ${todays.size} minute${if (todays.size == 1) "" else "s"} of candles today, too few to compare (I need ${Structure.MIN_BARS})."
        val lastMinute = todays.last().t.toLocalTime()
        val inProgress = lastMinute.isBefore(m.close.minusMinutes(1))
        val cut = if (inProgress) lastMinute.plusMinutes(1) else null
        val a = readAt(m, bars, today, null)
            ?: return "I could not read today's ${m.label} candles, Boss."
        val b = readAt(m, bars, other, cut)
            ?: return "There are too few ${m.label} candles for $otherName${cut?.let { " up to ${hm(it)}" } ?: ""} on the phone to compare, Boss."
        val shortName = otherName.substringBefore(" (")
        val head = "${m.label}, today against $otherName" + (if (cut != null) ", both up to ${hm(cut)}" else ", whole sessions") + ", Boss."
        val lines = ArrayList<String>()
        lines += head
        lines += dayLine("today" + (if (cut != null) " so far" else ""), a, cut == null)
        lines += dayLine(shortName + (if (cut != null) " by ${hm(cut)}" else ""), b, cut == null)
        lines += verdict("today", a, shortName, b)
        if (q.focus == Focus.ALL) lines += contrasts(m, "today", a, shortName, b)
        if (cut != null) readAt(m, bars, other, null)?.let { f ->
            lines += "By its close, $shortName ended ${kindWord(f)}: net ${pts(f.net)} from the open, " +
                "${share(f.share)} of a ${n(f.range)}-point range; today's is not finished."
        }
        lines += "I call a day trend-like when the net move from the open is ${(Structure.TREND_SHARE * 100).toInt()}% or more of its range with the price in the outer quarter, " +
            "range-like at ${(Structure.RANGE_SHARE * 100).toInt()}% or less."
        lines += NOTE
        return lines.joinToString(" ")
    }

    /** The earlier session alone, whole; then, when today has enough candles, which of the two is the more trend-like so far. */
    private fun oneDay(m: Market, bars: List<Candle>, today: LocalDate, other: LocalDate, otherName: String): String {
        val b = readAt(m, bars, other, null)
            ?: return "There are too few ${m.label} candles for $otherName on the phone to read it, Boss."
        val shortName = otherName.substringBefore(" (")
        val lines = ArrayList<String>()
        lines += "${m.label}'s ${otherName.removePrefix("the ")}, the whole session, Boss."
        lines += dayLine(shortName, b, true)
        val todays = bars.filter { it.t.toLocalDate() == today }.sortedBy { it.t }
        if (todays.size >= Structure.MIN_BARS) {
            val lastMinute = todays.last().t.toLocalTime()
            val cut = if (lastMinute.isBefore(m.close!!.minusMinutes(1))) lastMinute.plusMinutes(1) else null
            val a = readAt(m, bars, today, null)
            val bc = if (cut != null) readAt(m, bars, other, cut) else b
            if (a != null && bc != null) lines += (if (cut != null) "Up to ${hm(cut)} on both days: " else "Against today's whole session: ") +
                verdict("today", a, shortName, bc).replaceFirstChar { it.lowercase() }
        }
        lines += "I call a day trend-like when the net move from the open is ${(Structure.TREND_SHARE * 100).toInt()}% or more of its range with the price in the outer quarter, " +
            "range-like at ${(Structure.RANGE_SHARE * 100).toInt()}% or less."
        lines += NOTE
        return lines.joinToString(" ")
    }
}
