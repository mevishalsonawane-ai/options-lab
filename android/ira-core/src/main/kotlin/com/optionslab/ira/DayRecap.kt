package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

/**
 * The day recap (Boss, 07 Oct 2026): "what happened on 3 Oct", "recap of yesterday", "how was Monday", "2 oct ka recap",
 * "what happened last Friday", "kal kya hua" - one compact recap of that trading day from what the phone already keeps:
 *
 *  - the market: Nifty's, BankNifty's and FinNifty's open, high, low and close that day, the gap at the open and the day's
 *    change against the session before (from the 1-minute candles Jarvis keeps; else the market recorder's minute readings);
 *  - the day's events (the calendar, the recorder's own events, Boss's) and the headlines the recorder kept that day, and
 *    the FII/DII cash figures NSE gave for that day when the recorder kept them;
 *  - the paper arms that day: Liquidity 15+5's trades (count, net after charges per lot, best and worst), Solo (midday)'s
 *    trade, Hero's trade.
 *
 * Whatever is not kept for that day is said plainly ("no candles kept for that day"), never guessed. A day still to come is
 * refused; a weekend or a market holiday is said as one (with the holiday's name and the session before it); "yesterday"
 * on a day after a weekend or holiday is the session before. Today points to the day's own answers, with the market so far.
 * On a locked phone the paper arms' lines are left out (the market's facts stay).
 *
 * Only the general recap of a day: an index named, Boss's own P&L, trades or orders, one arm, the news alone, the open or the
 * close alone - each keeps its own answer. Words only: nothing here places, closes, arms or changes anything. Pure.
 */
object DayRecap {
    // ---- the question --------------------------------------------------------------------------------------------

    /** The day as said. */
    sealed class Said {
        /** [n] days before today ("yesterday" 1, "the day before yesterday" / "parso" 2, "3 days ago"). */
        data class DaysAgo(val n: Int) : Said()
        /** A weekday: [mode] [Mode.BARE] the latest on or before today, [Mode.LAST] the latest before today, [Mode.THIS] this week's. */
        data class Weekday(val day: DayOfWeek, val mode: Mode) : Said()
        /** A date: [month] and [dayOfMonth], in [year] when said. */
        data class Dated(val month: Int, val dayOfMonth: Int, val year: Int? = null) : Said()
        /** "The 3rd": that day of this month (or of last month, when this month's is still to come). */
        data class OfMonth(val dayOfMonth: Int) : Said()
    }

    enum class Mode { BARE, LAST, THIS }

    /** What was asked: the day [said]. */
    data class Q(val said: Said)

    private fun norm(text: String) = Spaced.joined(text)

    private const val LEAD = "(?:jarvis |hey jarvis |ok jarvis |okay jarvis |boss |so |and |please |ok |okay |hi |hello |tell me |can you tell me |could you tell me |do you know )*"
    private const val TAIL = "(?: boss| jarvis| please| then| yaar| na| bro)* $"
    private const val ASK = "(?:give me |tell me |can you give me |could you give me |can i have |can i get |i want |i need |lets have |show me |what is |whats |what was |read me )?"
    private const val WD = "monday|tuesday|wednesday|thursday|friday|saturday|sunday|tues|thurs"
    private const val WDH = "somvar|somwar|mangalvar|mangalwar|budhvar|budhwar|guruvar|guruwar|brihaspativar|shukravar|shukrawar|shanivar|shaniwar|ravivar|raviwar|itwar|itvar"
    private const val MONTHS = "january|february|march|april|june|july|august|september|october|november|december|jan|feb|mar|apr|may|jun|jul|aug|sept|sep|oct|nov|dec"
    private const val NUM = "\\d{1,2}|two|three|four|five|six|seven|eight|nine|ten"
    private const val AGO = "(?:$NUM) (?:days|din) (?:ago|back|pehle|pahle)|a week ago|ek hafta pehle"
    private const val WEEKDAY = "(?:(?:last|this|past|previous|pichle|pichhle|pichla|is) )?(?:$WD|$WDH)"
    private const val DATED = "(?:the )?\\d{1,2}(?:st|nd|rd|th)?(?: of)? (?:$MONTHS)(?: \\d{4})?|(?:$MONTHS)(?: the)? \\d{1,2}(?:st|nd|rd|th)?(?: \\d{4})?|the \\d{1,2}(?:st|nd|rd|th)"
    /** A past day in English, "yesterday" aside. */
    private const val DAY_NOT_YESTERDAY = "(?:the )?day before yesterday|$AGO|$WEEKDAY|$DATED"
    private const val DAY_EN = "yesterday|$DAY_NOT_YESTERDAY"
    /** A past day in Hinglish ("kal" said of the past: with "kya hua", "ka recap", "kaisa tha"). */
    private const val DAY_HI = "kal|parso|$DAY_EN"
    private const val MKT = "(?:the )?(?:market|markets|stock market|share market)"
    private const val RECAP = "(?:recap|summary|rundown|round up|roundup|wrap up|wrapup|story|lowdown)"

    private val FORMS: List<Regex> = listOf(
        // "what happened on 3 oct", "what happened yesterday", "what happened in the market last friday", "what all happened on monday"
        Regex("^ $LEAD(?:so )?(?:what|what all|what exactly) (?:happened|went on|has happened) (?:(?:in|with|to) $MKT )?(?:on )?(?<d>$DAY_EN)(?: (?:in|for|with) $MKT)?$TAIL"),
        // "recap of yesterday", "give me a recap of 3 oct", "summary for last friday", "a quick rundown of monday"
        Regex("^ $LEAD$ASK(?:a |the |me a |me the )?(?:quick |short |brief |full )?(?:day |market |session |daily )?$RECAP (?:of|for|on|from) (?:the )?(?:day |session |market |markets )?(?:on )?(?<d>$DAY_EN)$TAIL"),
        // "yesterday's recap", "monday's summary", "3 oct recap", "last friday's wrap up"
        Regex("^ $LEAD$ASK(?:the )?(?<d>$DAY_EN)s? (?:day |market |session |trading )?$RECAP$TAIL"),
        // "recap yesterday", "recap last friday", "sum up monday"
        Regex("^ $LEAD(?:recap|summarise|summarize|sum up) (?:the day |the session |the market )?(?:on |for |of )?(?<d>$DAY_EN)$TAIL"),
        // "how was monday", "how was last friday", "how was 3 oct" ("how was yesterday" keeps its own answer)
        Regex("^ $LEAD(?:so )?how was (?:the day |trading |the session )?(?:on )?(?<d>$DAY_NOT_YESTERDAY)$TAIL"),
        // "how did monday go", "how did last friday turn out"
        Regex("^ $LEAD(?:so )?how did (?:the day |trading |the session )?(?:on )?(?<d>$DAY_NOT_YESTERDAY) (?:go|turn out|pan out)$TAIL"),
        // "2 oct ka recap", "kal ka recap", "pichle shukravar ki summary batao"
        Regex("^ $LEAD(?<d>$DAY_HI) (?:ka|ki|ke) (?:din ka |market ka |session ka )?$RECAP(?: kya hai| batao| bata do| bolo| sunao| do| de do| dikhao| chahiye)?$TAIL"),
        // "kal kya hua", "parso market mein kya hua", "market mein kal kya hua", "somvar ko kya hua tha"
        Regex("^ $LEAD(?:(?:market|bazaar|bazar) (?:mein|me|main) )?(?<d>$DAY_HI) (?:ko )?(?:(?:market|bazaar|bazar) (?:mein|me|main) )?(?:kya kya|kya) (?:hua|huwa)(?: tha)?(?: batao| bata do| bolo| sunao)?$TAIL"),
        // "kal ka din kaisa tha", "somvar kaisa raha", "3 oct ka market kaisa tha"
        Regex("^ $LEAD(?<d>$DAY_HI) (?:ka din |ka market |ki market |ka session )?(?:kaisa|kaise|kaisi) (?:tha|thi|raha|rahi|gaya)$TAIL"),
    )

    /** Never this: an index named, Boss's own account, an arm, the news alone, a forecast - each has its own answer. */
    private val NOT = Regex(" (my|mine|our|we|mera|mere|meri|maine|hamara|pnl|p l|profit|loss|trade|trades|traded|order|orders|position|positions|" +
        "bot|bots|arm|arms|liquidity|solo|hero|orb|strategy|strategies|news|headline|headlines|expiry|gap|vix|fii|fiis|dii|diis|will|would|should|" +
        "tomorrow|next|today|todays|aaj) ")

    private val ORDINAL = Regex("(\\d{1,2})(?:st|nd|rd|th)?")
    private val WORDS = mapOf("two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10)

    private fun month(s: String): Int = when (s.take(3)) {
        "jan" -> 1; "feb" -> 2; "mar" -> 3; "apr" -> 4; "may" -> 5; "jun" -> 6; "jul" -> 7; "aug" -> 8; "sep" -> 9; "oct" -> 10; "nov" -> 11; else -> 12
    }

    private fun weekday(w: String): DayOfWeek? = when (w) {
        "monday", "somvar", "somwar" -> DayOfWeek.MONDAY
        "tuesday", "tues", "mangalvar", "mangalwar" -> DayOfWeek.TUESDAY
        "wednesday", "budhvar", "budhwar" -> DayOfWeek.WEDNESDAY
        "thursday", "thurs", "guruvar", "guruwar", "brihaspativar" -> DayOfWeek.THURSDAY
        "friday", "shukravar", "shukrawar" -> DayOfWeek.FRIDAY
        "saturday", "shanivar", "shaniwar" -> DayOfWeek.SATURDAY
        "sunday", "ravivar", "raviwar", "itwar", "itvar" -> DayOfWeek.SUNDAY
        else -> null
    }

    /** The day in [d] (the words a form matched as the day), or null when it does not read as one. */
    internal fun said(d: String): Said? {
        val w = d.trim().split(' ').filter { it.isNotEmpty() }
        if (w.isEmpty()) return null
        when (d.trim()) {
            "yesterday", "yesterdays", "kal" -> return Said.DaysAgo(1)
            "parso", "day before yesterday", "the day before yesterday" -> return Said.DaysAgo(2)
            "a week ago", "ek hafta pehle" -> return Said.DaysAgo(7)
        }
        if (w.size == 3 && (w[1] == "days" || w[1] == "din")) {
            val n = w[0].toIntOrNull() ?: WORDS[w[0]] ?: return null
            return if (n in 1..60) Said.DaysAgo(n) else null
        }
        val last = w.last()
        weekday(last)?.let { dow ->
            val mode = when (w.getOrNull(w.size - 2)) {
                null -> Mode.BARE
                "this", "is" -> Mode.THIS
                else -> Mode.LAST
            }
            return Said.Weekday(dow, mode)
        }
        val body = w.filter { it != "the" && it != "of" }
        val year = body.lastOrNull()?.takeIf { it.length == 4 }?.toIntOrNull()
        val core = if (year != null) body.dropLast(1) else body
        val mon = core.firstOrNull { Regex("^($MONTHS)$").matches(it) }
        val num = core.firstNotNullOfOrNull { ORDINAL.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() } ?: return null
        if (num !in 1..31) return null
        return if (mon != null) Said.Dated(month(mon), num, year) else if (core.size == 1) Said.OfMonth(num) else null
    }

    /** Is a general recap of one day asked? Null when not. */
    fun asked(text: String): Q? = askedKept.of(text) { askedFresh(text) }
    private val askedKept = Kept<Q?>(64)

    private fun askedFresh(text: String): Q? {
        val t = norm(text)
        if (NOT.containsMatchIn(t) || Market.mentioned(text).isNotEmpty()) return null
        for (f in FORMS) {
            val m = f.find(t) ?: continue
            val d = m.groups["d"]?.value ?: continue
            return said(d)?.let { Q(it) }
        }
        return null
    }

    // ---- which day -----------------------------------------------------------------------------------------------

    /** The date [said] names at [today], or null when it names none (31 Feb). A date with no year still to come within a month is that one (refused); further on, last year's. */
    fun dayOf(said: Said, today: LocalDate): LocalDate? = when (said) {
        is Said.DaysAgo -> today.minusDays(said.n.toLong())
        is Said.Weekday -> when (said.mode) {
            Mode.BARE -> generateSequence(today) { it.minusDays(1) }.first { it.dayOfWeek == said.day }
            Mode.LAST -> generateSequence(today.minusDays(1)) { it.minusDays(1) }.first { it.dayOfWeek == said.day }
            Mode.THIS -> today.with(DayOfWeek.MONDAY).plusDays((said.day.value - 1).toLong())
        }
        is Said.Dated -> runCatching {
            if (said.year != null) LocalDate.of(said.year, said.month, said.dayOfMonth) else {
                val d = LocalDate.of(today.year, said.month, said.dayOfMonth)
                if (d.isAfter(today.plusDays(31))) LocalDate.of(today.year - 1, said.month, said.dayOfMonth) else d
            }
        }.getOrNull()
        is Said.OfMonth -> runCatching { today.withDayOfMonth(said.dayOfMonth) }.getOrNull()?.takeIf { !it.isAfter(today) }
            ?: runCatching { today.minusMonths(1).withDayOfMonth(said.dayOfMonth) }.getOrNull()
    }

    /** What the recap is of. */
    sealed class Plan {
        /** The words named no real date. */
        object Unknown : Plan()
        /** [day] is still to come. */
        data class Future(val day: LocalDate) : Plan()
        /** [day] is today. */
        data class Today(val day: LocalDate) : Plan()
        /** [day] had no session: [why] ("a weekend", "a market holiday (Diwali)"); [before] the session before it. */
        data class Closed(val day: LocalDate, val why: String, val before: LocalDate?) : Plan()
        /** A session on [day]; [from] the day asked when that had none ([fromWhy]) and the session before it is recapped. */
        data class Session(val day: LocalDate, val from: LocalDate? = null, val fromWhy: String? = null) : Plan()
    }

    /** At most this many days back for the session before a closed day. */
    private const val LOOK_BACK = 12

    /** The session before [day] ([trading] says which days are), or null within [LOOK_BACK] days. */
    fun sessionBefore(day: LocalDate, trading: (LocalDate) -> Boolean): LocalDate? =
        (1..LOOK_BACK).asSequence().map { day.minusDays(it.toLong()) }.firstOrNull { runCatching { trading(it) }.getOrDefault(false) }

    /** Why [day] had no session: its holiday's name ([holiday]), else a weekend, else a market holiday. */
    fun closedWhy(day: LocalDate, holiday: (LocalDate) -> String?): String {
        val name = runCatching { holiday(day) }.getOrNull()?.takeIf { it.isNotBlank() }
        return when {
            name != null -> "a market holiday ($name)"
            day.dayOfWeek == DayOfWeek.SATURDAY || day.dayOfWeek == DayOfWeek.SUNDAY -> "a weekend"
            else -> "a market holiday"
        }
    }

    /**
     * The day [q] names at [today], against the calendar ([trading], [holiday]'s names): still to come, today, a day with no
     * session (said "yesterday": the session before it is recapped), or a session.
     */
    fun plan(q: Q, today: LocalDate, trading: (LocalDate) -> Boolean, holiday: (LocalDate) -> String?): Plan {
        val day = dayOf(q.said, today) ?: return Plan.Unknown
        if (day.isAfter(today)) return Plan.Future(day)
        if (day == today) return Plan.Today(day)
        if (runCatching { trading(day) }.getOrDefault(true)) return Plan.Session(day)
        val why = closedWhy(day, holiday)
        val before = sessionBefore(day, trading)
        if (q.said == Said.DaysAgo(1) && before != null) return Plan.Session(before, day, why)
        return Plan.Closed(day, why, before)
    }

    // ---- the facts -------------------------------------------------------------------------------------------------

    private val OPEN: LocalTime = LocalTime.of(9, 15)
    private val CLOSE: LocalTime = LocalTime.of(15, 30)

    /** The indices recapped, in the order said. */
    val MARKETS: List<Market> = listOf(Market.NIFTY, Market.BANKNIFTY, Market.FINNIFTY)

    /** An index that day: [open], [high], [low], [close] (the last while the day runs), the session before's [prevClose]; [recorder]: from the recorder's minute readings. */
    data class Index(val market: Market, val open: Double, val high: Double, val low: Double, val close: Double,
                     val prevClose: Double? = null, val recorder: Boolean = false)

    /** One paper trade of an arm that day: its [symbol], bought at [at], its [net] after charges (null: still open), its exit [why]. */
    data class Paper(val symbol: String, val at: LocalTime, val net: Double?, val why: String? = null)

    /**
     * What the recap of [day] is made of ([today]: when asked). [from]/[fromWhy]: the day asked had no session (this is the
     * one before). [partial]: today, still running. [indices] the indices read; [events] the day's events; [news] the
     * headlines the recorder kept that day (null: no recorder file for it); [flows] the FII/DII figures for that day. The
     * arms (null: not read): [liquidity] Liquidity 15+5's rows that day, [solo] Solo (midday)'s trades, [hero] Hero's -
     * each with the first day of its record ([liquidityFrom], [soloFrom], [heroFrom]; null: none yet). [locked]: the
     * phone is locked (the arms are left out).
     */
    data class Facts(
        val day: LocalDate, val today: LocalDate,
        val from: LocalDate? = null, val fromWhy: String? = null, val partial: Boolean = false,
        val indices: List<Index> = emptyList(), val events: List<Events.Event> = emptyList(),
        val news: List<NewsImpact.Seen>? = null, val flows: List<Flows.Flow> = emptyList(),
        val liquidity: List<LiquidityRecord.Row>? = null, val liquidityFrom: LocalDate? = null,
        val solo: List<Paper>? = null, val soloFrom: LocalDate? = null,
        val hero: List<Paper>? = null, val heroFrom: LocalDate? = null,
        val locked: Boolean = false,
    )

    private fun sessionBars(bars: List<Candle>, day: LocalDate): List<Candle> = bars
        .filter { it.t.toLocalDate() == day && !it.t.toLocalTime().isBefore(OPEN) && it.t.toLocalTime().isBefore(CLOSE) }
        .sortedBy { it.t }

    /** The close of the last session before [day] in [bars] (its last 1-minute close), or null. */
    fun prevClose(bars: List<Candle>, day: LocalDate): Double? {
        val before = bars.filter { it.t.toLocalDate().isBefore(day) && !it.t.toLocalTime().isBefore(OPEN) && it.t.toLocalTime().isBefore(CLOSE) }
        return before.maxByOrNull { it.t }?.c?.takeIf { it > 0 }
    }

    /** [m] on [day] from its 1-minute [bars] (null: no candle of that day kept). */
    fun index(m: Market, bars: List<Candle>, day: LocalDate): Index? {
        val s = sessionBars(bars, day)
        if (s.isEmpty() || s.first().o <= 0) return null
        return Index(m, s.first().o, s.maxOf { it.h }, s.minOf { it.l }, s.last().c, prevClose(bars, day))
    }

    /**
     * [m] on that day from the market recorder's minute readings ("S,time,index,last" in [lines], [MarketRecord.spot]),
     * 09:15-15:30 only (null: none kept); [prevClose] from wherever it is known.
     */
    fun indexFromRecorder(m: Market, lines: List<String>, prevClose: Double?): Index? {
        val pts = lines.asSequence().filter { it.startsWith("S,") }.mapNotNull { l ->
            val f = runCatching { MarketRecord.fields(l) }.getOrNull() ?: return@mapNotNull null
            if (f.size < 4 || f[2] != m.name) return@mapNotNull null
            val t = runCatching { LocalTime.parse(f[1]) }.getOrNull() ?: return@mapNotNull null
            val v = f[3].toDoubleOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            if (t.isBefore(OPEN) || !t.isBefore(CLOSE)) null else t to v
        }.sortedBy { it.first }.toList()
        if (pts.isEmpty()) return null
        return Index(m, pts.first().second, pts.maxOf { it.second }, pts.minOf { it.second }, pts.last().second, prevClose, recorder = true)
    }

    /** The headlines in the recorder's [lines] ("N" records), as first seen. */
    fun headlines(lines: List<String>): List<NewsImpact.Seen> = lines.mapNotNull { runCatching { NewsImpact.headline(it) }.getOrNull() }

    /** The events for [day] in the recorder's [lines] ("E,day,time,name,owner", [MarketRecord.event]). */
    fun eventsOf(lines: List<String>, day: LocalDate): List<Events.Event> = lines.filter { it.startsWith("E,") }.mapNotNull { l ->
        val f = runCatching { MarketRecord.fields(l) }.getOrNull() ?: return@mapNotNull null
        if (f.size < 4 || f[1] != day.toString() || f[3].isBlank()) return@mapNotNull null
        Events.Event(day, f[3], owner = f.getOrNull(4) == "1")
    }

    private val NSE_DATE = DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH)

    /** An NSE date ("06-Oct-2026", or "2026-10-06"), or null. */
    private fun nseDate(s: String): LocalDate? = runCatching { LocalDate.parse(s.trim(), NSE_DATE) }.getOrNull()
        ?: runCatching { LocalDate.parse(s.trim()) }.getOrNull()
        ?: runCatching { LocalDate.parse(s.trim().lowercase(Locale.ENGLISH).replaceFirstChar { it.uppercase() }, NSE_DATE) }.getOrNull()

    /**
     * The FII/DII cash figures NSE gave for [day] in the recorder's [lines] ("X,date,who,buy,sell,net", [MarketRecord.flow]),
     * the last of each kind, FII first (empty: none for that day - a row of another day is never taken for it).
     */
    fun flowsFor(lines: List<String>, day: LocalDate): List<Flows.Flow> = lines.filter { it.startsWith("X,") }.mapNotNull { l ->
        val f = runCatching { MarketRecord.fields(l) }.getOrNull() ?: return@mapNotNull null
        if (f.size < 6 || nseDate(f[1]) != day) return@mapNotNull null
        val net = f[5].toDoubleOrNull() ?: return@mapNotNull null
        Flows.Flow(f[2], f[1], f[3].toDoubleOrNull() ?: 0.0, f[4].toDoubleOrNull() ?: 0.0, net)
    }.associateBy { it.who }.values.filter { it.who == "FII" || it.who == "DII" }.sortedBy { if (it.who == "FII") 0 else 1 }

    // ---- words -------------------------------------------------------------------------------------------------------

    const val UNKNOWN = "I couldn't tell which day that is, Boss - say it like \"what happened on 3 Oct\" or \"recap of last Friday\"."
    const val LOCKED_ARMS = "Unlock the phone for the paper arms' trades that day."
    const val SLOW = "That day is taking too long to read just now, Boss - ask again in a moment."
    /** The day's own answers, pointed to when today is asked. */
    val TODAY_ASKS = listOf("what happened in the market today", "why didn't liquidity trade today", "what did solo do today", "what did hero do today")

    private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    private fun day(d: LocalDate, today: LocalDate): String = if (d.year == today.year) d.format(DAY) else "${d.format(DAY)} ${d.year}"
    private fun short(d: LocalDate) = "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}"
    private fun n(x: Double) = "%,.2f".format(Locale.ENGLISH, x)
    private fun pct(x: Double) = "%+.2f%%".format(Locale.ENGLISH, x)
    private fun hm(t: LocalTime) = "%02d:%02d".format(Locale.ENGLISH, t.hour, t.minute)
    private fun rs(x: Double) = LiquidityRecord.rs(x)

    /** What the recap says when there is no session to read (still to come, no such day, no session that day). */
    fun say(p: Plan, today: LocalDate): String? = when (p) {
        is Plan.Unknown -> UNKNOWN
        is Plan.Future -> "${day(p.day, today)} is still to come, Boss - I can only recap a day that has happened."
        is Plan.Closed -> "${day(p.day, today)} was ${p.why}, Boss - the market had no session, so there is nothing to recap." +
            (p.before?.let { " The session before it was ${day(it, today)} - ask \"what happened on ${short(it)}\"." } ?: "")
        is Plan.Today, is Plan.Session -> null
    }

    /** One index's line: its open (and the gap), high, low and close (and the day's change). */
    fun indexLine(i: Index, partial: Boolean = false): String {
        val gap = i.prevClose?.let { pc ->
            val pts = i.open - pc
            val p = pts / pc * 100
            if (abs(p) < 0.05) " (a flat open)" else " (gap ${if (pts > 0) "up" else "down"} ${n(abs(pts))}, ${pct(p)})"
        }.orEmpty()
        val change = if (i.prevClose != null) "${pct((i.close - i.prevClose) / i.prevClose * 100)} on the day"
            else "${pct((i.close - i.open) / i.open * 100)} from its open"
        val end = if (partial) "last ${n(i.close)} ($change so far)" else "close ${n(i.close)} ($change)"
        return "${i.market.label}: open ${n(i.open)}$gap, high ${n(i.high)}, low ${n(i.low)}, $end" +
            (if (i.recorder) " - from the recorder's minute readings" else "") + "."
    }

    private fun eventsLine(f: Facts): String? {
        val es = f.events.filter { it.day == f.day && (!it.owner || !f.locked) }.distinctBy { it.name.lowercase(Locale.ENGLISH) }
        if (es.isEmpty()) return null
        return "Events that day: " + es.joinToString("; ") { it.name + if (it.owner) " (added by you)" else "" } + "."
    }

    /** At most this many headlines are named (those that mattered first), each cut to [TITLE_MAX] characters. */
    const val HEADLINES = 3
    private const val TITLE_MAX = 90

    private fun title(s: String): String = s.trim().let { if (it.length <= TITLE_MAX) it else it.take(TITLE_MAX - 3).trimEnd() + "..." }

    private fun newsLine(news: List<NewsImpact.Seen>): String? {
        if (news.isEmpty()) return null
        val seen = news.distinctBy { it.link.ifBlank { it.title }.trim() }
        val picked = (seen.filter { it.matters }.sortedBy { it.firstSeen } + seen.filter { !it.matters }.sortedBy { it.firstSeen })
            .take(HEADLINES).sortedBy { it.firstSeen }
        val count = if (seen.size > picked.size) " (${seen.size} recorded)" else ""
        return "Headlines that day$count: " + picked.joinToString("; ") { "${hm(it.firstSeen)} ${title(it.title)}" } + "."
    }

    private fun flowsLine(flows: List<Flows.Flow>): String? =
        if (flows.none { it.who == "FII" || it.who == "DII" }) null else Flows.lines(flows).first()

    /** What is not kept for that day, said plainly (null: everything is). */
    private fun missingLine(f: Facts): String? {
        val gone = ArrayList<String>()
        val read = f.indices.map { it.market }.toSet()
        val noCandles = MARKETS.filter { it !in read }
        if (read.isNotEmpty() && noCandles.isNotEmpty()) gone += "candles for " + noCandles.joinToString(" and ") { it.label }
        if (f.news.isNullOrEmpty()) gone += "headlines"
        if (f.flows.none { it.who == "FII" || it.who == "DII" } && f.day != f.today) gone += "FII/DII figures"
        if (gone.isEmpty()) return null
        return "Not kept on the phone for that day: " + gone.joinToString(", ") + "."
    }

    private fun startsWords(first: LocalDate?, day: LocalDate, today: LocalDate, what: String): String = when {
        first == null -> " - $what holds no trades yet"
        first.isAfter(day) -> " - $what starts on ${day(first, today)}"
        else -> ""
    }

    private fun liqTrade(r: LiquidityRecord.Row): String =
        "${hm(r.trade.entryTime.toLocalTime())} ${r.book} ${if (r.trade.right.equals("PE", true)) "PE" else "CE"} ${rs(r.perLot)} a lot"

    /** Liquidity 15+5's line: its trades that day - the count, net after charges per lot, best and worst. */
    fun liquidityLine(f: Facts): String {
        val rows = f.liquidity ?: return "Liquidity 15+5 (paper): its book could not be read just now."
        if (rows.isEmpty()) return "Liquidity 15+5 (paper): no trade that day${startsWords(f.liquidityFrom, f.day, f.today, "its paper book")}."
        val t = LiquidityRecord.tally(rows)
        if (rows.size == 1) {
            val r = rows.first()
            return "Liquidity 15+5 (paper): 1 trade - ${liqTrade(r)} after charges (${LiquidityRecord.reason(r.why)})."
        }
        val best = rows.maxByOrNull { it.perLot }!!
        val worst = rows.minByOrNull { it.perLot }!!
        return "Liquidity 15+5 (paper): ${rows.size} trades, ${t.wins} won and ${t.losses} lost, net ${rs(t.perLot)} a lot after charges " +
            "(${rs(t.net)} in all); best ${liqTrade(best)} (${LiquidityRecord.reason(best.why)}), worst ${liqTrade(worst)} (${LiquidityRecord.reason(worst.why)})."
    }

    private fun paperWords(p: Paper, exit: (String?) -> String): String =
        "${p.symbol} bought at ${hm(p.at)}, " + (p.net?.let { "net ${rs(it)} after charges (${exit(p.why)})" } ?: "still open")

    /** Solo (midday)'s line: its trade that day, or none (why it passed is kept only on the day). */
    fun soloLine(f: Facts): String {
        val ts = f.solo ?: return "Solo (midday, paper): its record could not be read just now."
        if (ts.isEmpty()) {
            val starts = startsWords(f.soloFrom, f.day, f.today, "its record")
            return "Solo (midday, paper): no trade that day$starts" + (if (starts.isEmpty()) " - why it passed is kept only on the day itself" else "") + "."
        }
        return "Solo (midday, paper): " + ts.joinToString("; ") { paperWords(it) { w -> w?.replace('_', ' ') ?: "exit not recorded" } } + "."
    }

    /** Hero's line: its trade that day, or none. */
    fun heroLine(f: Facts): String {
        val ts = f.hero ?: return "Hero (expiry, paper): its book could not be read just now."
        if (ts.isEmpty()) return "Hero (expiry, paper): no trade that day${startsWords(f.heroFrom, f.day, f.today, "its paper book")}."
        return "Hero (expiry, paper): " + ts.joinToString("; ") { paperWords(it) { w -> HeroDay.exitWords(w).substringBefore(" (") } } + "."
    }

    /** Every line of the recap, in the order said. */
    fun lines(f: Facts): List<String> {
        val out = ArrayList<String>()
        val isToday = f.day == f.today
        out += when {
            f.from != null -> "${day(f.from, f.today)} was ${f.fromWhy ?: "a day with no session"}, so here is the session before it, ${day(f.day, f.today)}, Boss:"
            isToday && f.partial -> "That's today, Boss - here is the market so far (${day(f.day, f.today)}):"
            isToday -> "That's today, Boss - here is how the market went (${day(f.day, f.today)}):"
            else -> "Recap of ${day(f.day, f.today)}, Boss:"
        }
        if (f.indices.isEmpty()) out += if (isToday) "No candles of today on the phone yet." else "No candles kept for that day on the phone - nor the recorder's index readings."
        f.indices.sortedBy { MARKETS.indexOf(it.market) }.forEach { out += indexLine(it, isToday && f.partial) }
        eventsLine(f)?.let { out += it }
        f.news?.let { newsLine(it) }?.let { out += it }
        flowsLine(f.flows)?.let { out += it }
        if (isToday) {
            out += "For the rest of today ask " + TODAY_ASKS.joinToString(", ") { "\"$it\"" } + "."
            return out
        }
        missingLine(f)?.let { out += it }
        if (f.locked) out += LOCKED_ARMS
        else {
            out += liquidityLine(f)
            out += soloLine(f)
            out += heroLine(f)
        }
        return out
    }

    /** The recap in words, one line each. */
    fun say(f: Facts): String = lines(f).joinToString("\n")
}
