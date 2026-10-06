package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

/**
 * The index's gap record (Jarvis market intelligence, round 14): "when Nifty gaps up over 0.5%, how often does it fill
 * the gap by 11?", "do gap downs usually fill?", "what usually happens after a gap up?", "gap fill rate for BankNifty",
 * "gap kitni baar bharta hai". From the sessions of 1-minute candles on the phone: each session that opened a gap of at
 * least the size asked (0.5% when none is said; today's own size when "a gap like today's" is asked) from the previous
 * session's close on the phone, how often the gap filled (the price came back to that close) by 10:00, by 11:00 (and by
 * any time asked) and by the close, the median time it filled, how often it never came back, and how often the session
 * closed beyond its open in the gap's direction - set beside today's own gap and whether it has filled so far.
 * A record of past days on this phone, said with its count; never a forecast or advice, nothing acts. Today's gap alone
 * stays [Gap]'s, the last time it gapped like this [MarketMemory]'s and the two-year overnight study [Study]'s. Pure.
 */
object GapRecord {
    /** What was asked: the gap's side (+1 up, -1 down, null both), its least size (percent or points), a time asked, like today's. */
    data class Q(val dir: Int?, val minPct: Double?, val minPts: Double?, val by: LocalTime?, val likeToday: Boolean)

    /** One past session's gap and how it played out. */
    data class Day(val day: LocalDate, val prevClose: Double, val open: Double, val filledAt: LocalTime?, val close: Double) {
        val gapPct: Double get() = (open - prevClose) / prevClose * 100
        val gapPts: Double get() = open - prevClose
        val up: Boolean get() = open > prevClose
        /** Closed beyond its open in the gap's direction: the gap held and was added to. */
        val ranOn: Boolean get() = if (up) close > open else close < open
    }

    /** The least gap counted when none is asked (% of the previous close). */
    const val DEFAULT_PCT = 0.5
    /** A gap smaller than this is no gap to count (also the least "like today's"). */
    const val MIN_PCT = 0.1
    /** Fewer gaps of a side than this: too few to say a record. */
    const val MIN_GAPS = 6
    /** Fewer sessions with a known previous close than this: too few to say anything. */
    const val MIN_SESSIONS = 10
    /** At most this many of the newest sessions are read. */
    const val MAX_SESSIONS = 250
    /** The previous session on the phone counts as the one before only when at most this many days earlier (a weekend and a holiday). */
    const val MAX_DAYS_APART = 4L
    private val FIRST_BY: LocalTime = LocalTime.of(9, 20)
    private val LAST_FROM: LocalTime = LocalTime.of(15, 25)
    val OPEN: LocalTime = LocalTime.of(9, 15)
    val CLOSE: LocalTime = LocalTime.of(15, 30)
    private val DEFAULT_BY = listOf(LocalTime.of(10, 0), LocalTime.of(11, 0))

    const val NOTE = "A record of past days on this phone, Boss, not a forecast."
    const val NOT_HERE = "I keep the gap record for Nifty, BankNifty, FinNifty and Sensex only, Boss: gold trades round the clock, so it has no opening gap, and India VIX is not traded."

    private fun norm(text: String) = Spaced.words(text)
    private fun n(x: Double) = "%,.2f".format(Locale.ENGLISH, x)
    private fun p2(x: Double) = "%.2f%%".format(Locale.ENGLISH, x)
    private fun p1(x: Double) = "%.1f%%".format(Locale.ENGLISH, x).replace(".0%", "%")
    private fun share(k: Int, of: Int) = "%d%%".format(Locale.ENGLISH, Math.round(k * 100.0 / of))
    private fun hm(t: LocalTime) = "%02d:%02d".format(Locale.ENGLISH, t.hour, t.minute)

    private val GAP = Regex(" (gap|gaps|gapped|gapping|gapup|gapdown|gapups|gapdowns) ")
    private val FILL = Regex(" (fill|fills|filled|filling|cover|covers|covered|close the gap|closes the gap|closed the gap|bharta|bharti|bhar|bhara|bhari|bharte) ")
    private val HOW = Regex(" (how often|how many times|how frequently|how many days|how many of|what share|what percentage|what percent|what fraction|usually|normally|typically|generally|tends to|tend to|on average|most times|most of the time|record|rate|stats|statistics|history|historically|kitni baar|kitne din|aksar|zyada tar|mostly) ")
    private val NAME = Regex(" gap (fill|filling|fills) (rate|record|stats|statistics|history|percentage|ratio|behaviour|behavior|habits) | gap (behaviour|behavior|record|stats|statistics|history|habits) | gaps (record|stats|statistics|history) ")
    private val AFTER = Regex(" (what|how) (usually |normally |typically |generally |mostly |often )?(happens|happened|goes|go|does|do|did) ([a-z ]+ )?(after|on) (a |an |the )?(big |large |small |opening )?(gap|gaps|gapup|gapdown|gapups|gapdowns)( up| down)?( open| opening| day| days)? |" +
        " how (does|do) ([a-z ]+ )?(behave|behaves|act|acts|move|moves) (after|on) (a |an |the )?(big |large |small |opening )?(gap|gaps|gapup|gapdown)( up| down)? ")
    /** Gaps as a kind asked whether they fill ("do gaps fill on Nifty", "do big gaps fill the same day", "gap up fill hota hai kya"), or
     *  what follows one in Hinglish ("gap down ke baad kya hota hai") - routing round 11: these went to today's gap alone. */
    private val KIND = Regex(" (do|does) (big |large |small |opening |the |most |nifty |banknifty |bank nifty |sensex |finnifty )*(gaps|gap ups|gap downs|gapups|gapdowns) ([a-z ]+ )?(fill|get filled|close|get closed) |" +
        " gap( up| down)? (fill|bhar|close) (hota|hote|hoti|jata|jaata|jate|jaate|jati) (hai|hain|he|h) |" +
        " gap( up| down)? (ke baad|hone ke baad|hone par|hone pe|wale din) (kya|kaisa|kaise) (hota|hoti|rehta|chalta|chalti|karta|karti) ")
    private val AFTER_HOW = Regex(" (usually|normally|typically|generally|mostly|historically|tend to|tends to|how often|on average|aksar|record) ")
    // Forecasts, advice, Boss's own book or bots, a what-if, the last time (MarketMemory), a span of the calendar, a meaning.
    private val NOT = Regex(" (will|would|going to|gonna|tomorrow|kal|predict|prediction|forecast|should|shall|buy|sell|enter|exit|trade|trades|trading|" +
        "i|me|my|mine|we|our|what if|suppose|imagine|scenario|agar|last time|when did|last|between|mean|means|meaning|define|strategy|backtest|arm|arms|orb|news|" +
        "this week|this month|this year|last week|last month) ")
    private val UP = Regex(" (gap up|gaps up|gapped up|gapping up|gapup|gapups|gap ups|opens up|opens higher|opened higher|opened up|gap up khula|upar khula|up gap|upward gap) ")
    private val DOWN = Regex(" (gap down|gaps down|gapped down|gapping down|gapdown|gapdowns|gap downs|opens down|opens lower|opened lower|opened down|gap down khula|neeche khula|down gap|downward gap) ")
    private val LIKE_TODAY = Regex(" (like today|like today s|today s gap|todays gap|this gap|such a gap|gap like this|gap this size|gap this big|aaj jaisa|aaj ka gap|aaj jaise) ")

    /** What was asked, or null. A record of past gaps only: never a forecast, advice, Boss's own book or bots. */
    fun asked(text: String): Q? = askedKept.of(text) { askedFresh(text) }

    /** The last words read (speed round 10: the hub reads them as said, then again in its order; [Kept], pure). */
    private val askedKept = Kept<Q?>(64)

    private fun askedFresh(text: String): Q? {
        val t = norm(text)
        if (!GAP.containsMatchIn(t) || NOT.containsMatchIn(t)) return null
        if (Market.mentioned(text).any { it == Market.GOLD }) return null
        val ok = NAME.containsMatchIn(t) || (FILL.containsMatchIn(t) && HOW.containsMatchIn(t)) ||
            (AFTER.containsMatchIn(t) && AFTER_HOW.containsMatchIn(t)) || KIND.containsMatchIn(t)
        if (!ok) return null
        val up = UP.containsMatchIn(t); val down = DOWN.containsMatchIn(t)
        val dir = if (up && !down) 1 else if (down && !up) -1 else null
        val raw = text.lowercase(Locale.ENGLISH)
        val pct = Regex("(\\d+(?:\\.\\d+)?)\\s*(%|percent|per cent|pc\\b)").find(raw)?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it in 0.05..10.0 }
        val pts = if (pct != null) null else Regex("(\\d+(?:\\.\\d+)?)\\s*(points|point|pts)\\b").find(raw)?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it > 0 }
        return Q(dir, pct, pts, by(raw), LIKE_TODAY.containsMatchIn(t))
    }

    /** "by 11", "by 11:30", "by 1 pm", "by noon", "11 baje tak": a time within the session (not its open), or null. */
    fun by(raw: String): LocalTime? {
        if (rx("\\bby (noon|lunch|midday|mid day)\\b").containsMatchIn(raw)) return LocalTime.NOON
        val m = rx("\\bby (\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.|o'?\\s?clock)?").find(raw)
            ?: rx("\\b(\\d{1,2})(?:[:.](\\d{2}))?\\s*(baje)\\s*tak\\b").find(raw) ?: return null
        var h = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues[2].toIntOrNull() ?: 0
        val ampm = m.groupValues[3]
        if (ampm.startsWith("p") && h < 12) h += 12
        else if (!ampm.startsWith("a") && h in 1..3) h += 12
        if (h !in 0..23 || min !in 0..59) return null
        val t = LocalTime.of(h, min)
        return if (t.isAfter(OPEN) && !t.isAfter(CLOSE)) t else null
    }

    /** The index asked about (Nifty when none is named), or null for gold or India VIX alone. */
    fun market(markets: List<Market>): Market? {
        val m = markets.firstOrNull() ?: return Market.NIFTY
        return if (m == Market.GOLD || m == Market.VIX) markets.firstOrNull { it != Market.GOLD && it != Market.VIX } else m
    }

    private fun whole(s: MarketStory.Session) = !s.bars.first().t.toLocalTime().isAfter(FIRST_BY) && !s.bars.last().t.toLocalTime().isBefore(LAST_FROM)

    /** When [bars] (one session, in time order) first came back to [prevClose] from an open on the [up] side, or null. */
    private fun fill(bars: List<Candle>, prevClose: Double, up: Boolean): LocalTime? =
        bars.firstOrNull { if (up) it.l <= prevClose else it.h >= prevClose }?.t?.toLocalTime()

    /**
     * The past sessions before [today] in [bars] (whole ones, opened by 09:20 and running to 15:25 or later) whose
     * previous session on the phone was whole and at most [MAX_DAYS_APART] days earlier, newest [MAX_SESSIONS], oldest first.
     */
    fun past(bars: List<Candle>, today: LocalDate): List<Day> {
        val ss = MarketStory.sessions(bars).filter { it.day.isBefore(today) && it.bars.isNotEmpty() }
        val out = ArrayList<Day>()
        for (i in 1 until ss.size) {
            val prev = ss[i - 1]; val s = ss[i]
            if (!whole(prev) || !whole(s) || ChronoUnit.DAYS.between(prev.day, s.day) > MAX_DAYS_APART || prev.close <= 0) continue
            val up = s.open > prev.close
            out += Day(s.day, prev.close, s.open, if (s.open == prev.close) s.bars.first().t.toLocalTime() else fill(s.bars, prev.close, up), s.close)
        }
        return out.takeLast(MAX_SESSIONS)
    }

    /** Today's gap from the last whole session before it on the phone, with when it filled so far, or null. */
    fun today(bars: List<Candle>, today: LocalDate): Day? {
        val ss = MarketStory.sessions(bars)
        val s = ss.lastOrNull { it.day == today }?.takeIf { it.bars.isNotEmpty() && !it.bars.first().t.toLocalTime().isAfter(FIRST_BY) } ?: return null
        val prev = ss.lastOrNull { it.day.isBefore(today) }?.takeIf { whole(it) && ChronoUnit.DAYS.between(it.day, today) <= MAX_DAYS_APART } ?: return null
        if (prev.close <= 0) return null
        return Day(today, prev.close, s.open, fill(s.bars, prev.close, s.open > prev.close), s.close)
    }

    private fun median(xs: List<Int>): Int { val s = xs.sorted(); return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }

    /** The least gap counted (% of the previous close) and how it is said. */
    private fun least(q: Q, now: Day?): Pair<Double, String> {
        q.minPct?.let { return maxOf(it, MIN_PCT) to p1(maxOf(it, MIN_PCT)) }
        if (q.likeToday && now != null && abs(now.gapPct) >= MIN_PCT) {
            val f = floor(abs(now.gapPct) * 10) / 10
            return f to "${p1(f)} (today's is ${p2(abs(now.gapPct))})"
        }
        return DEFAULT_PCT to p1(DEFAULT_PCT)
    }

    /** [q] answered for [m] from [bars] (1-minute candles over several days) at [now] on [today]. */
    fun answer(q: Q, m: Market, bars: List<Candle>, today: LocalDate, now: LocalDateTime): String {
        val days = past(bars, today)
        if (days.size < MIN_SESSIONS)
            return "I have only ${days.size} ${m.label} session${if (days.size == 1) "" else "s"} on the phone with the previous close beside them, Boss - too few to say how its gaps usually play out (I need $MIN_SESSIONS)."
        val nowDay = today(bars, today)
        val (pct, said) = least(q, nowDay)
        val pts = q.minPts
        val bys = (DEFAULT_BY + listOfNotNull(q.by)).filter { it.isBefore(LocalTime.of(15, 29)) }.distinct().sorted()
        val dirs = q.dir?.let { listOf(it) } ?: listOf(1, -1)
        val lines = ArrayList<String>()
        for (d in dirs) {
            val side = if (d > 0) "up" else "down"
            val size = if (pts != null) "${n(pts)} points or more" else "$said or more"
            val gaps = days.filter { (it.up == (d > 0)) && it.open != it.prevClose && if (pts != null) abs(it.gapPts) >= pts else abs(it.gapPct) >= pct }
            val head = "Over the last ${days.size} ${m.label} sessions on this phone, it gapped $side $size on ${gaps.size}"
            if (gaps.size < MIN_GAPS) {
                lines += "$head - too few to say how such gaps play out (I need $MIN_GAPS)."
                continue
            }
            val k = gaps.size
            val byText = bys.joinToString(", ") { b -> val c = gaps.count { g -> g.filledAt?.isBefore(b) == true }; "by ${hm(b)} on $c (${share(c, k)})" }
            val filled = gaps.filter { it.filledAt != null }
            val never = k - filled.size
            lines += "$head. The gap filled - the price came back to the previous close - $byText, and by the close on ${filled.size} (${share(filled.size, k)})."
            val ranOn = gaps.count { it.ranOn }
            val med = if (filled.isNotEmpty()) median(filled.map { it.filledAt!!.toSecondOfDay() / 60 }).let { LocalTime.of(it / 60, it % 60) } else null
            lines += (med?.let { "When it filled, the median time was ${hm(it)}. " } ?: "") +
                "On $never of the $k (${share(never, k)}) it never came back that day, and on $ranOn (${share(ranOn, k)}) it closed ${if (d > 0) "above" else "below"} its open."
        }
        nowDay?.let { lines += todayLine(m, it, now, today) }
        lines += NOTE
        return lines.joinToString(" ")
    }

    private fun todayLine(m: Market, d: Day, now: LocalDateTime, today: LocalDate): String {
        val g = d.gapPct
        if (abs(g) < MIN_PCT) return "Today ${m.label} opened about flat (${"%+.2f%%".format(Locale.ENGLISH, g)} from the previous close of ${n(d.prevClose)})."
        val head = "Today ${m.label} opened ${p2(abs(g))} ${if (d.up) "up" else "down"} (${n(d.open)} against the previous close of ${n(d.prevClose)})"
        val live = now.toLocalDate() == today && now.toLocalTime().isBefore(CLOSE)
        return when {
            d.filledAt != null -> "$head; the gap filled at ${hm(d.filledAt)}."
            live -> "$head; the gap has not filled so far."
            else -> "$head; the gap did not fill."
        }
    }
}
