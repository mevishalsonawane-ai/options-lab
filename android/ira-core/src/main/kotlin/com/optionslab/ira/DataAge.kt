package com.optionslab.ira

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

/**
 * Jarvis knowing when his own information is old (Jarvis learning, round 5: a data freshness self-check). Each market
 * answer is checked against the age of what it rests on - the live prices, the 1-minute candles, the news, the option
 * chain - and only while the market trades (a closed market's prices are expected to be old):
 *
 *  - [Level.OLD]: the answer starts with how old ("My prices are 95 seconds old, Boss.") and each sentence's first price
 *    figure is marked with the minute it is from;
 *  - [Level.STALE] prices: he says so instead of quoting them as now ([dress] with `withhold`).
 *
 * Each check is also kept by the day ([Log]: how many checks, how many found old, the spells of old data and how long
 * they lasted, the answers that carried a warning) for the nightly review and for "is your data fresh?" / "how old are
 * your prices?". Facts only: it only ever adds caution, never acts, never touches an order. Pure.
 */
object DataAge {
    enum class Source(val word: String, val oldSec: Long, val staleSec: Long) {
        /** The last live price: refreshed every minute while Jarvis listens. */
        PRICES("prices", 90, 300),
        /** The last 1-minute candle (the charts and patterns are built from them). */
        CANDLES("candles", 180, 600),
        /** When the news feeds were last read (every 5 minutes in market hours). */
        NEWS("news", 900, 2700),
        /** The option chain's last minute. */
        CHAIN("option chain", 300, 1200),
    }

    enum class Level { FRESH, OLD, STALE }

    /** The newest headline this old in market hours is said ("the last headline I have is from 40 minutes ago"). */
    const val HEADLINE_OLD_MIN = 30L
    /** A spell of old data this close to the last one of its source is the same spell. */
    const val SPELL_GAP_SEC = 300L
    /** Days kept; spells kept a day. */
    const val KEEP_DAYS = 14
    const val MAX_SPELLS = 60

    /**
     * One check: [what] it is about ("Nifty", or "" for the news), the minute its data is from ([dataAt]), its age in
     * seconds when checked, whether the market traded then ([live]: only then can it be old), and whether a fetch had just
     * been tried ([tried]: data still old after it means the feed itself is behind). [newest]: the news's newest headline.
     */
    data class Check(val source: Source, val what: String, val dataAt: LocalDateTime?, val ageSec: Long, val live: Boolean,
                     val tried: Boolean = false, val newest: LocalDateTime? = null) {
        val level: Level get() = if (!live) Level.FRESH else level(source, ageSec)
    }

    fun level(source: Source, ageSec: Long): Level = when {
        ageSec >= source.staleSec -> Level.STALE
        ageSec >= source.oldSec -> Level.OLD
        else -> Level.FRESH
    }

    private fun age(at: LocalDateTime, now: LocalDateTime): Long = Duration.between(at, now).seconds.coerceAtLeast(0)

    /**
     * The live prices of [what]: as old as the earlier of the last fetch ([fetchedAt]) and the end of the last 1-minute
     * bar ([lastBar] + 1 minute) - a fetch that brought no new bar does not make the price newer. Null without either.
     */
    fun prices(what: String, fetchedAt: LocalDateTime?, lastBar: LocalDateTime?, now: LocalDateTime, live: Boolean, tried: Boolean = false): Check? {
        val barEnd = lastBar?.plusMinutes(1)
        val at = when {
            fetchedAt != null && barEnd != null -> minOf(fetchedAt, barEnd)
            else -> fetchedAt ?: barEnd ?: return null
        }
        return Check(Source.PRICES, what, at, age(at, now), live, tried)
    }

    /** The last 1-minute candle of [what] (it closed at [lastBar] + 1 minute). */
    fun candles(what: String, lastBar: LocalDateTime?, now: LocalDateTime, live: Boolean, tried: Boolean = false): Check? {
        val at = lastBar?.plusMinutes(1) ?: return null
        return Check(Source.CANDLES, what, at, age(at, now), live, tried)
    }

    /** The news, read last at [readAt], its newest headline from [newest]. Null when it was never read. */
    fun news(readAt: LocalDateTime?, newest: LocalDateTime?, now: LocalDateTime, live: Boolean): Check? {
        val at = readAt ?: return null
        return Check(Source.NEWS, "", at, age(at, now), live, newest = newest)
    }

    /** The option chain of [what], its last minute [dataAt]. */
    fun chain(what: String, dataAt: LocalDateTime?, now: LocalDateTime, live: Boolean): Check? {
        val at = dataAt ?: return null
        return Check(Source.CHAIN, what, at, age(at, now), live)
    }

    /** What a market answer about [topics] rests on: the prices, the candles (patterns alone) and the news. */
    fun sources(topics: Set<Topic>): Set<Source> {
        val out = LinkedHashSet<Source>()
        val prices = topics.any { it in setOf(Topic.OVERVIEW, Topic.WHY, Topic.TREND, Topic.LEVELS, Topic.VOLATILITY, Topic.ADVICE) }
        if (prices) out += Source.PRICES
        if (Topic.PATTERNS in topics && !prices) out += Source.CANDLES
        if (Topic.NEWS in topics || Topic.WHY in topics) out += Source.NEWS
        return out
    }

    /** "95 seconds", "7 minutes", "over an hour", "3 hours". */
    fun ageText(sec: Long): String = when {
        sec < 120 -> "$sec seconds"
        sec < 3600 -> "${sec / 60} minutes"
        sec < 7200 -> "over an hour"
        else -> "${sec / 3600} hours"
    }

    private fun hm(t: LocalDateTime) = "%02d:%02d".format(Locale.ENGLISH, t.hour, t.minute)

    /** "10:35", or "15:29 on 2 Oct" for another day. */
    private fun clock(t: LocalDateTime, now: LocalDateTime): String =
        if (t.toLocalDate() == now.toLocalDate()) hm(t)
        else hm(t) + " on " + t.dayOfMonth + " " + t.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }

    private fun headlineOld(c: Check, now: LocalDateTime): Boolean =
        c.live && c.newest != null && age(c.newest, now) >= HEADLINE_OLD_MIN * 60

    private fun headline(c: Check, now: LocalDateTime): String = "the last headline I have is from ${ageText(age(c.newest!!, now))} ago"

    private fun whose(c: Check) = if (c.what.isBlank()) "" else "${c.what} "

    /**
     * What is said before an answer resting on [c], or null when it is fresh (or the market is not trading). [withheld]:
     * stale prices are not quoted (the note says so); otherwise the answer follows, its figures marked.
     */
    fun note(c: Check, now: LocalDateTime, withheld: Boolean = true): String? {
        val lvl = c.level
        return when (c.source) {
            Source.PRICES -> when (lvl) {
                Level.FRESH -> null
                Level.OLD -> "My prices are ${ageText(c.ageSec)} old, Boss."
                Level.STALE -> "My ${whose(c)}prices are ${ageText(c.ageSec)} old, Boss" + (c.dataAt?.let { " (the last from ${clock(it, now)})" } ?: "") +
                    (if (withheld) ", too old to quote as now - " else ", so take these figures as from then - ") +
                    (if (c.tried) "the live feed is behind." else if (withheld) "I'm fetching fresh ones: ask me again in a moment." else "I'm fetching fresh ones.")
            }
            Source.CANDLES -> if (lvl == Level.FRESH) null else
                "My last ${whose(c)}candle closed at ${clock(c.dataAt!!, now)}, Boss, ${ageText(c.ageSec)} ago" +
                    (if (lvl == Level.STALE) " - the chart may have moved since." else ".")
            Source.NEWS -> {
                val parts = ArrayList<String>()
                if (lvl != Level.FRESH) parts += "I last read the news ${ageText(c.ageSec)} ago" + (if (lvl == Level.STALE) " - the feeds have not answered since" else "")
                if (headlineOld(c, now)) parts += headline(c, now)
                if (parts.isEmpty()) null else parts.joinToString(", and ").replaceFirstChar { it.uppercase() } + ", Boss."
            }
            Source.CHAIN -> if (lvl == Level.FRESH) null else
                "The ${whose(c)}option chain I have is from ${clock(c.dataAt!!, now)}, Boss, ${ageText(c.ageSec)} old."
        }
    }

    /** An answer as said: its text, the age note put first (null: all fresh), and whether the prices were held back. */
    data class Dressed(val text: String, val note: String?, val withheld: Boolean, val marked: Int = 0)

    /** A figure written as a price ("24,612.40", "$4,214.70"), not a percentage or a move in points. */
    private val PRICE = Regex("(?<![\\d.,:/-])\\$?(?:\\d{1,3}(?:,\\d{2,3})+|\\d{3,})\\.\\d{2}(?![\\d%])(?!\\s*(?:%|percent|per cent|points?\\b|pts\\b))(?! \\(as of)")
    private val SENTENCE_END = Regex("[.!?](\\s|$)|\\n")

    /** [text] with each sentence's first price figure marked " (as of 10:41)". */
    fun mark(text: String, at: LocalDateTime, now: LocalDateTime): Pair<String, Int> {
        val tag = " (as of ${clock(at, now)})"
        val sb = StringBuilder()
        var last = 0
        var prevEnd = -1
        var n = 0
        for (m in PRICE.findAll(text)) {
            val first = prevEnd < 0 || SENTENCE_END.containsMatchIn(text.substring(prevEnd, m.range.first))
            prevEnd = m.range.last + 1
            if (!first) continue
            sb.append(text, last, m.range.last + 1).append(tag)
            last = m.range.last + 1
            n++
        }
        sb.append(text, last, text.length)
        return sb.toString() to n
    }

    /**
     * [text] as said given [checks]: fresh, as it is; old, the age first and the price figures marked; stale prices (with
     * [withhold]), the age and why instead of the answer - never a stale price passed off as now.
     */
    fun dress(text: String, checks: List<Check>, now: LocalDateTime, withhold: Boolean = true): Dressed {
        val stale = checks.filter { it.source == Source.PRICES && it.level == Level.STALE }
        if (withhold && stale.isNotEmpty()) {
            val said = stale.mapNotNull { note(it, now) }.joinToString(" ")
            return Dressed(said, said, true)
        }
        val notes = checks.mapNotNull { note(it, now, withheld = false) }.distinct()
        if (notes.isEmpty()) return Dressed(text, null, false)
        val priceAt = checks.filter { it.source == Source.PRICES && it.level != Level.FRESH }.mapNotNull { it.dataAt }.minOrNull()
        val (body, n) = if (priceAt != null) mark(text, priceAt, now) else text to 0
        val note = notes.joinToString(" ")
        return Dressed("$note $body", note, false, n)
    }

    // ---- the day's record ---------------------------------------------------------------------------------------

    /** A stretch of old data of one source: from when it turned old to the last check that found it so, and its worst age. */
    data class Spell(val source: Source, val from: LocalDateTime, val to: LocalDateTime, val worstSec: Long) {
        val seconds: Long get() = Duration.between(from, to).seconds.coerceAtLeast(0)
    }

    /** One day: checks made, found old (or stale), market answers given, with an age note, and withheld; the spells. */
    data class Day(val day: LocalDate, val checks: Int = 0, val old: Int = 0, val answers: Int = 0, val warned: Int = 0,
                   val withheld: Int = 0, val spells: List<Spell> = emptyList())

    data class Log(val days: List<Day> = emptyList()) {
        fun day(d: LocalDate): Day? = days.firstOrNull { it.day == d }
    }

    private fun update(log: Log, now: LocalDateTime, f: (Day) -> Day): Log {
        val d = now.toLocalDate()
        val was = log.day(d) ?: Day(d)
        val days = (log.days.filter { it.day != d } + f(was)).sortedBy { it.day }
        return Log(days.filter { !it.day.isBefore(d.minusDays(KEEP_DAYS - 1L)) })
    }

    private fun spellOf(c: Check, now: LocalDateTime): Spell {
        val start = now.toLocalDate().atStartOfDay()
        val turned = c.dataAt?.plusSeconds(c.source.oldSec) ?: now
        val from = maxOf(start, minOf(turned, now))
        return Spell(c.source, from, now, c.ageSec)
    }

    private fun addSpells(spells: List<Spell>, checks: List<Check>, now: LocalDateTime): List<Spell> {
        var out = spells
        for (c in checks.filter { it.level != Level.FRESH }) {
            val s = spellOf(c, now)
            val i = out.indexOfLast { it.source == c.source }
            val prev = out.getOrNull(i)
            out = if (prev != null && !s.from.isAfter(prev.to.plusSeconds(SPELL_GAP_SEC)))
                out.toMutableList().also { it[i] = Spell(c.source, minOf(prev.from, s.from), maxOf(prev.to, s.to), maxOf(prev.worstSec, s.worstSec)) }
            else (out + s).takeLast(MAX_SPELLS)
        }
        return out
    }

    /** [checks] made at [now] (by the watch or for an answer): counted, and old data kept as spells. Only live checks count. */
    fun observe(log: Log, checks: List<Check>, now: LocalDateTime): Log {
        val live = checks.filter { it.live }
        if (live.isEmpty()) return log
        return update(log, now) { d ->
            d.copy(checks = d.checks + live.size, old = d.old + live.count { it.level != Level.FRESH }, spells = addSpells(d.spells, live, now))
        }
    }

    /** A market answer given at [now] on [checks]: [warned] it carried an age note, [withheld] its prices were not quoted. */
    fun answered(log: Log, checks: List<Check>, warned: Boolean, withheld: Boolean, now: LocalDateTime): Log {
        if (checks.none { it.live }) return log
        val seen = observe(log, checks, now)
        return update(seen, now) { d -> d.copy(answers = d.answers + 1, warned = d.warned + (if (warned) 1 else 0), withheld = d.withheld + (if (withheld) 1 else 0)) }
    }

    private fun times(n: Int) = when (n) { 1 -> "once"; 2 -> "twice"; else -> "$n times" }
    private fun minutes(sec: Long) = (sec / 60).let { if (it <= 1) "about a minute" else "$it minutes" }

    /** "my prices were old 3 times, 9 minutes in all (the longest 6 minutes, from 11:02)", or null. */
    private fun spellLine(spells: List<Spell>, source: Source): String? {
        val xs = spells.filter { it.source == source }
        if (xs.isEmpty()) return null
        val total = xs.sumOf { it.seconds }
        val longest = xs.maxByOrNull { it.seconds }!!
        val what = when (source) {
            Source.PRICES -> "my prices were old"; Source.CANDLES -> "my candles were behind"
            Source.NEWS -> "my news was out of date"; Source.CHAIN -> "my option chain was old"
        }
        return "$what ${times(xs.size)}" + (if (xs.size == 1) ", for ${minutes(total)}" else ", ${minutes(total)} in all") +
            (if (xs.size > 1) " (the longest ${minutes(longest.seconds)}, from ${hm(longest.from)})" else " (from ${hm(longest.from)})") +
            (if (longest.worstSec >= source.staleSec) ", at worst ${ageText(longest.worstSec)} old" else "")
    }

    /** The day's record in a few words, or null when nothing was checked. */
    fun dayLines(log: Log, day: LocalDate): List<String> {
        val d = log.day(day) ?: return emptyList()
        val out = Source.entries.mapNotNull { spellLine(d.spells, it) }.toMutableList()
        if (d.warned > 0) out += "${d.warned} of my ${d.answers} market answer${if (d.answers == 1) "" else "s"} carried an age warning" +
            (if (d.withheld > 0) ", and ${d.withheld} I would not quote" else "")
        return out
    }

    /** For the evening self-review: how often and how long his data was old today (nothing when it never was). */
    fun review(log: Log, day: LocalDate): List<String> {
        val d = log.day(day) ?: return emptyList()
        if (d.spells.isEmpty() && d.warned == 0) return emptyList()
        return dayLines(log, day).let { lines -> if (lines.isEmpty()) emptyList() else listOf("today " + lines.joinToString("; ")) }
    }

    private val ASKED = Regex("(?i)\\b(is|are) (your|the) (data|prices?|news|feed|quotes?|numbers|information|info|chain|option chain)( still)? " +
        "(fresh|live|current|up ?to ?date|recent|old|stale|delayed|lagging|behind|real ?time)\\b|" +
        "\\bhow (old|fresh|recent|stale|current|up ?to ?date|late|delayed) (is|are) (your|the) (data|prices?|news|feed|quotes?|numbers|information|info|chain|option chain|candles?)\\b|" +
        "\\bwhen did you last (get|read|update|fetch|check|refresh) (your |the )?(prices?|data|news|feed|quotes?|chain|option chain|candles?)\\b|" +
        "\\b(data|price|feed|news) (freshness|age)\\b")

    /** "Is your data fresh?", "how old are your prices?", "when did you last read the news?". */
    fun asked(text: String): Boolean = ASKED.containsMatchIn(text)

    /** One line on [c] as it stands, fresh or not (for "is your data fresh?"). */
    fun state(c: Check, now: LocalDateTime): String {
        val from = c.dataAt?.let { clock(it, now) } ?: "?"
        val tail = when {
            !c.live -> ""
            c.level == Level.STALE -> " - stale"
            c.level == Level.OLD -> " - getting old"
            else -> " - fresh"
        }
        return when (c.source) {
            Source.PRICES -> "my ${whose(c)}prices are ${ageText(c.ageSec)} old (from $from)$tail"
            Source.CANDLES -> "my last ${whose(c)}candle closed at $from$tail"
            Source.NEWS -> "I read the news ${ageText(c.ageSec)} ago$tail" + (c.newest?.let { n -> ", the newest headline from ${clock(n, now)}" } ?: "")
            Source.CHAIN -> "the ${whose(c)}option chain I last read is from $from$tail"
        }
    }

    /** "Is your data fresh?": each source's age now, then today's record. [trading]: the market trades now. */
    fun say(checks: List<Check>, log: Log, now: LocalDateTime, trading: Boolean): String {
        val now0 = if (checks.isEmpty()) "I have no market data on this phone yet, Boss."
            else "Boss, " + checks.joinToString("; ") { state(it, now) } + "."
        val closed = if (!trading) " The market is not trading now, so older prices are expected." else ""
        val today = dayLines(log, now.toLocalDate())
        val record = when {
            today.isNotEmpty() -> " Today " + today.joinToString("; ") + "."
            log.day(now.toLocalDate())?.checks?.let { it > 0 } == true -> " My data has been fresh every time I checked today."
            else -> ""
        }
        return now0 + closed + record + " I say so whenever an answer rests on old data, and I never quote stale prices as now."
    }
}
