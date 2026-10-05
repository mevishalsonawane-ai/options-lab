package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

/**
 * The few-sessions move record (market intelligence, round 37): "how far does Nifty usually move in 3 sessions?", "how often
 * does Nifty move 2% in 3 days?", "how often does BankNifty stay within 1.5% over 4 sessions?", "3 day move record for
 * Sensex", "teen din mein nifty kitna chalta hai". The question a trader holding an option for a few sessions asks: from the
 * whole sessions of 1-minute candles on the phone, every stretch of the number of sessions asked ([MIN_N] to [MAX_N]) that
 * follows a whole session, each joined to the one before it by [Comebacks.follows] and [Comebacks.whole] only (a missing or
 * cut session is never bridged; the newest [MAX_STRETCHES] before today): how far the stretch got above and below the close
 * it started from (its highs and lows) and where it ended. How often it got the size asked ([DEFAULT_PCT]% when none is, or
 * points) or more away - above only, below only, both sides, neither - how often it ended that far away, the median reach
 * on each side and on the farther side with its middle half, the median end either way and how many ended up, and the
 * biggest stretch. The stretches overlap, and that is said, so they are never passed off as separate tries. Beside it the
 * newest stretch on the phone ("ended" only for a whole session; today while it trades is "so far"). A record of past
 * sessions on this phone, said with its counts and plainly when there are too few; never a forecast or advice; nothing
 * acts. A single day's move from the close before stays [DayAfter]'s and the day reads', from the open [OpenReach]'s, a
 * calendar week [WeekRange]'s, the last few days as they went [PeriodMove]'s, a run of closes [Streak]'s, expiry the expiry
 * reads'. Pure.
 */
object MultiDay {
    /**
     * What was asked: [n] sessions, the size in % ([pct]) or in points ([pts], then [pct] unused); [asked] the % said when it
     * was outside [MIN_PCT] to [MAX_PCT] (then [pct] is [DEFAULT_PCT], and the answer says so).
     */
    data class Q(val n: Int, val pct: Double = DEFAULT_PCT, val pts: Double? = null, val asked: Double? = null)

    /** One stretch: [n] whole sessions to [end], after the whole session [from] that closed at [base]. */
    data class Stretch(val from: LocalDate, val end: LocalDate, val base: Double, val high: Double, val low: Double, val close: Double) {
        /** How far above the starting close it got, %. */
        val upPct: Double get() = (high - base) / base * 100
        /** How far below the starting close it got, % (a positive figure). */
        val downPct: Double get() = (base - low) / base * 100
        /** The farther of the two. */
        val farPct: Double get() = maxOf(upPct, downPct)
        /** Where it ended against the starting close, %. */
        val endPct: Double get() = (close - base) / base * 100
    }

    /** The record for one size: [n] stretches; above only, below only, both sides, neither; ended that far away; ended up. */
    data class Record(val n: Int, val aboveOnly: Int, val belowOnly: Int, val both: Int, val neither: Int, val endedAway: Int, val endedUp: Int,
                      val medianUp: Double, val medianDown: Double, val medianFar: Double, val farLow: Double, val farHigh: Double, val medianEnd: Double) {
        val reached: Int get() = aboveOnly + belowOnly + both
    }

    /** The number of sessions a stretch may have. */
    const val MIN_N = 2
    const val MAX_N = 10
    /** The sizes counted, % of the starting close. */
    const val MIN_PCT = 0.2
    const val MAX_PCT = 10.0
    /** The size when none is asked, %. */
    const val DEFAULT_PCT = 1.0
    /** Fewer stretches than this: too few to say anything. */
    const val MIN_STRETCHES = 20
    /** Fewer stretches than this: said as a small record. */
    const val FEW_STRETCHES = 60
    /** At most this many of the newest stretches are read. */
    const val MAX_STRETCHES = 250
    /** A neighbour session counts only when at most this many days away (a weekend and a holiday). */
    const val MAX_DAYS_APART = 4L
    private val CLOSE = LocalTime.of(15, 30)
    /** The indices the record is kept for (gold trades round the clock, India VIX is not traded). */
    internal val INDICES = listOf(Market.NIFTY, Market.BANKNIFTY, Market.FINNIFTY, Market.SENSEX)

    const val NOTE = "A record of past sessions on this phone, Boss, not a forecast."
    const val NOT_HERE = "I keep the few-sessions move record for Nifty, BankNifty, FinNifty and Sensex only, Boss: gold trades round the clock, so it has no close to count from, and India VIX is not traded."

    private fun norm(text: String) = " " + text.lowercase(Locale.ENGLISH).replace("’", "'").replace("'", " ")
        .replace(rx("[^a-z0-9%. ]"), " ").replace(rx("(?<!\\d)\\.|\\.(?!\\d)"), " ").replace(rx("\\s+"), " ").trim() + " "
    private fun s2(x: Double) = (if (x > 0) "+" else "") + "%.2f%%".format(Locale.ENGLISH, x)
    private fun p2(x: Double) = "%.2f%%".format(Locale.ENGLISH, x)
    private fun p1(x: Double) = "%.1f%%".format(Locale.ENGLISH, x).replace(".0%", "%")
    private fun pts(x: Double) = "%,.0f".format(Locale.ENGLISH, x)
    private fun share(k: Int, of: Int) = if (of == 0) "0%" else "%d%%".format(Locale.ENGLISH, Math.round(k * 100.0 / of))
    private fun date(d: LocalDate) = "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}"
    private fun hm(t: LocalTime) = "%02d:%02d".format(Locale.ENGLISH, t.hour, t.minute)

    // ---- the questions --------------------------------------------------------------------------------------------

    /** A number of sessions said as a word, English or Hindi ("do" only before "din", in [SPAN_HI]). */
    private val WORDS = mapOf("two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
        "do" to 2, "teen" to 3, "char" to 4, "chaar" to 4, "paanch" to 5, "panch" to 5, "chhe" to 6, "che" to 6, "saat" to 7, "aath" to 8, "nau" to 9, "das" to 10)
    private const val N = "(\\d{1,2}|two|three|four|five|six|seven|eight|nine|ten)"
    private const val N_HI = "(\\d{1,2}|two|three|four|five|six|seven|eight|nine|ten|do|teen|char|chaar|paanch|panch|chhe|che|saat|aath|nau|das)"
    /** "In 3 sessions", "over the next three trading days", "within 4 days". */
    private val SPAN = Regex(" (in|over|within|across|during|inside) (the )?(next |coming |following )?(any )?$N (trading |market )?(days|sessions|day|session) ")
    /** "3 day move", "3-session range record". */
    private val SPAN_NAMED = Regex(" $N (trading )?(day|days|session|sessions) (move|moves|range|ranges|reach|swing|swings|window|windows|stretch|stretches|holding period|period|periods|span|spans) ")
    /** "Teen din mein", "3 session me". */
    private val SPAN_HI = Regex(" $N_HI (din|dino|dinon|session|sessions|days|day) (mein|me|main|mai|ke andar|ka|ke) ")
    /**
     * "3 din me": the Hindi "me" (in) read as said, before [NOT] takes it for Boss's "me" (round 25) - only right after a
     * count of days or sessions.
     */
    private val HI_ME = Regex(" $N_HI (din|dino|dinon|session|sessions|days|day) me ")
    /** Named as a record. */
    private val NAME = Regex(" (record|records|stats|statistics|history|data) ")
    /** Asked of the record: how often, how far usually. */
    private val HOW = Regex(" (how often|how many times|how frequently|what share|what percent|what percentage|usually|normally|typically|generally|" +
        "tend to|tends to|on average|average|median|historically|odds|chance|chances|how far does|how far do|how far can|how much does|typical|" +
        "how much do|how much can|how big is|how big are|kitna|kitni|kitne|aksar|zyada tar|mostly|often) ")
    /** Getting away from the close: moving, going, rising, falling, staying within. */
    private val MOVE = Regex(" (move|moves|moved|moving|movement|go|goes|went|gone|going|get|gets|got|travel|travels|swing|swings|swung|run|runs|" +
        "reach|reaches|reached|range|ranges|rise|rises|rose|fall|falls|fell|drop|drops|dropped|rally|rallies|climb|climbs|gain|gains|" +
        "slide|slides|stay|stays|stayed|hold|holds|remain|remains|far|door|dur|chalta|chalti|jata|jaata|jati|jaati|girta|girti|badhta|badhti|" +
        "chadhta|chadhti|hilta|hilti) ")
    /** Said of one past stretch as it went: "how much did Nifty move in 3 days". */
    private val PAST_ONE = Regex(" (did|has|have|had) ")
    private val COUNTING = Regex(" (how often|how many times|kitni baar) ")
    private val SIZE = Regex(" (\\d+(?:\\.\\d+)?) ?(%|percent|per cent|pc|pct) ")
    private val POINTS = Regex(" (\\d{1,5}(?:\\.\\d+)?) ?(points|point|pts|pt) ")
    // A forecast or advice, Boss's own book, a what-if, alerts and reminders, the app's bots, stock screens, a definition, a
    // reason, today, now, this or the last few days as they went (PeriodMove's), yesterday, after something (DayAfter's), a
    // run of closes (Streak's), the gap, the open, the first or last hour, overnight, candles, a week's, month's or year's
    // move, expiry, options, gold or VIX.
    private val NOT = Regex(" (will|would|going to|gonna|tomorrow|tomorrows|kal|predict|prediction|forecast|outlook|expect|should|shall|buy|sell|" +
        "enter|exit|i|me|my|mine|we|our|if|what if|suppose|imagine|scenario|agar|remind|reminder|alert|alarm|notify|bot|bots|algo|strategy|" +
        "backtest|stock|stocks|scan|scanner|screener|shares|mean|means|meaning|define|explain|why|kyun|kyon|kyu|reason|" +
        "today|todays|aaj|aj|now|right now|abhi|so far|this|these|last|past|previous|recent|recently|pichle|pichhle|pichli|yesterday|yesterdays|" +
        "when did|after|ke baad|ke bad|next day|day after|agle din|in a row|consecutive|streak|streaks|straight|" +
        "gap|gaps|gapped|open|opening|first hour|last hour|closing hour|overnight|night|raat|intraday|candle|candles|" +
        "week|weekly|weeks|hafte|hafta|month|monthly|months|mahine|year|yearly|years|expiry|expiries|" +
        "call|calls|put|puts|premium|premiums|option|options|ce|pe|strike|strikes|straddle|strangle|theta|gold|vix|fear|" +
        "position|positions|portfolio|stop|stops|sl|news|" +
        // A day of the week ("in 3 sessions from monday", "fridays"): Weekdays' or a calendar question, not a stretch's record.
        "mondays?|tuesdays?|wednesdays?|thursdays?|fridays?|saturdays?|sundays?|somvar|mangalvar|budhvar|guruvar|shukravar|shanivar|ravivar) ")

    /** What was asked, or null: the record of stretches of a few whole sessions only, never a forecast or advice. */
    fun asked(text: String): Q? = askedKept.of(text) { askedFresh(text) }

    /** The last words read (the hub reads them as said, then again in its order; [Kept], pure). */
    private val askedKept = Kept<Q?>(64)

    private fun askedFresh(text: String): Q? {
        val t = norm(text).let { x -> HI_ME.replace(x) { m -> m.value.removeSuffix("me ") + "mein " } }
        if (NOT.containsMatchIn(t)) return null
        if (Market.mentioned(text).any { it == Market.GOLD || it == Market.VIX }) return null
        if (PAST_ONE.containsMatchIn(t) && !COUNTING.containsMatchIn(t)) return null
        val span = SPAN.find(t)?.groupValues?.get(5) ?: SPAN_NAMED.find(t)?.groupValues?.get(1) ?: SPAN_HI.find(t)?.groupValues?.get(1) ?: return null
        val n = span.toIntOrNull() ?: WORDS[span] ?: return null
        if (n < MIN_N || n > MAX_N) return null
        if (!(NAME.containsMatchIn(t) && SPAN_NAMED.containsMatchIn(t)) && !(HOW.containsMatchIn(t) && MOVE.containsMatchIn(t))) return null
        val p = POINTS.find(t)?.groupValues?.get(1)?.toDoubleOrNull()
        if (p != null && p > 0) return Q(n, pts = p)
        val size = SIZE.find(t)?.groupValues?.get(1)?.toDoubleOrNull() ?: return Q(n)
        return if (size >= MIN_PCT && size <= MAX_PCT) Q(n, size) else Q(n, DEFAULT_PCT, asked = size)
    }

    /** The index asked about (Nifty when none is named), or null for gold or India VIX alone. */
    fun market(markets: List<Market>): Market? {
        if (markets.isEmpty()) return Market.NIFTY
        return markets.firstOrNull { it in INDICES }
    }

    // ---- the record -----------------------------------------------------------------------------------------------

    /** [b] is the session right after [a]: the next trading day by [isTradingDay] ([Comebacks.follows]), at most [MAX_DAYS_APART] days on. */
    private fun next(a: MarketStory.Session, b: MarketStory.Session, isTradingDay: (LocalDate) -> Boolean) =
        ChronoUnit.DAYS.between(a.day, b.day) <= MAX_DAYS_APART && Comebacks.follows(a.day, b.day, isTradingDay)

    private fun stretch(ss: List<MarketStory.Session>, i: Int, n: Int) = ss.subList(i + 1, i + n + 1).let { w ->
        Stretch(ss[i].day, w.last().day, ss[i].close, w.maxOf { s -> s.bars.maxOf { it.h } }, w.minOf { s -> s.bars.minOf { it.l } }, w.last().close)
    }

    /**
     * The stretches of [n] whole sessions before [today] in [bars], each after a whole session and every session the one
     * right after the one before it ([Comebacks.whole], [Comebacks.follows]: a missing session is never bridged); newest
     * [MAX_STRETCHES], oldest first. Neighbouring stretches share sessions.
     */
    fun past(bars: List<Candle>, today: LocalDate, n: Int, isTradingDay: (LocalDate) -> Boolean = Comebacks.WEEKDAYS): List<Stretch> {
        val ss = MarketStory.sessions(bars).filter { it.day.isBefore(today) && it.bars.isNotEmpty() }
        val out = ArrayList<Stretch>()
        for (i in 0 until ss.size - n) {
            val ok = (i..i + n).all { j -> Comebacks.whole(ss[j]) && ss[j].close > 0 && (j == i || next(ss[j - 1], ss[j], isTradingDay)) }
            if (ok) out += stretch(ss, i, n)
        }
        return out.takeLast(MAX_STRETCHES)
    }

    private fun median(xs: List<Double>): Double { val s = xs.sorted(); return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }
    private fun quartile(xs: List<Double>, q: Double): Double { val s = xs.sorted(); return s[((s.size - 1) * q).toInt().coerceIn(0, s.size - 1)] }

    /** [xs]' record for a size of [pct]% of each starting close, or [pts] points when given. */
    fun record(xs: List<Stretch>, pct: Double, pts: Double? = null): Record {
        fun line(s: Stretch) = if (pts != null) pts / s.base * 100 else pct
        var aboveOnly = 0; var belowOnly = 0; var both = 0; var neither = 0
        for (s in xs) {
            val up = s.upPct >= line(s); val down = s.downPct >= line(s)
            when {
                up && down -> both++
                up -> aboveOnly++
                down -> belowOnly++
                else -> neither++
            }
        }
        val far = xs.map { it.farPct }
        return Record(xs.size, aboveOnly, belowOnly, both, neither, xs.count { abs(it.endPct) >= line(it) }, xs.count { it.endPct > 0 },
            median(xs.map { it.upPct }), median(xs.map { it.downPct }), median(far), quartile(far, 0.25), quartile(far, 0.75),
            median(xs.map { abs(it.endPct) }))
    }

    /**
     * The newest stretch of [n] sessions on the phone, ending with its newest session (today's while it trades: [live]);
     * [whole] when that session ended whole, else cut at [last].
     */
    data class Newest(val s: Stretch, val live: Boolean, val whole: Boolean, val last: LocalTime)

    /** The newest stretch, when the phone has [n] sessions after a whole one, each right after the one before. */
    fun newest(bars: List<Candle>, today: LocalDate, now: LocalDateTime, n: Int, isTradingDay: (LocalDate) -> Boolean = Comebacks.WEEKDAYS): Newest? {
        val ss = MarketStory.sessions(bars).filter { it.bars.isNotEmpty() && !it.day.isAfter(today) }
        val k = ss.size - 1
        if (k - n < 0) return null
        // Every session but the newest whole; each the one right after the one before.
        for (j in k - n..k) {
            if (j < k && (!Comebacks.whole(ss[j]) || ss[j].close <= 0)) return null
            if (j > k - n && !next(ss[j - 1], ss[j], isTradingDay)) return null
        }
        val lastS = ss[k]
        val live = lastS.day == today && now.toLocalDate() == today && now.toLocalTime().isBefore(CLOSE)
        return Newest(stretch(ss, k - n, n), live, !live && Comebacks.whole(lastS), lastS.bars.last().t.toLocalTime())
    }

    /** [m]'s record from its 1-minute candles over many days, for [q], at [now] on [today]; [isTradingDay] the app's calendar. */
    fun answer(q: Q, m: Market, bars: List<Candle>, today: LocalDate, now: LocalDateTime, isTradingDay: (LocalDate) -> Boolean = Comebacks.WEEKDAYS): String {
        val n = q.n.coerceIn(MIN_N, MAX_N)
        val xs = past(bars, today, n, isTradingDay)
        if (xs.size < MIN_STRETCHES)
            return "I have only ${xs.size} stretch${if (xs.size == 1) "" else "es"} of $n whole ${m.label} sessions in a row on the phone, Boss - " +
                "too few to say how far $n sessions usually go (I need $MIN_STRETCHES)."
        val r = record(xs, q.pct, q.pts)
        val size = if (q.pts != null) "${pts(q.pts)} points" else p1(q.pct)
        val lines = ArrayList<String>()
        // The lead carries the key figure (ShortAnswer's line).
        lines += "Over $n sessions, ${m.label} went $size or more from the close it started from on ${r.reached} of the last ${r.n} stretches on this phone " +
            "(${share(r.reached, r.n)}), and ended $size or more away on ${r.endedAway} (${share(r.endedAway, r.n)})."
        if (q.asked != null)
            lines += "I count sizes of ${p1(MIN_PCT).removeSuffix("%")} to ${p1(MAX_PCT)} only, so that is ${p1(q.pct)}, not the ${p1(q.asked)} asked."
        lines += "Above that close only on ${r.aboveOnly}, below it only on ${r.belowOnly}, on both sides on ${r.both}, and never that far on ${r.neither} " +
            "(stretches ending ${date(xs.first().end)} to ${date(xs.last().end)})."
        lines += "The median stretch reached ${p2(r.medianUp)} above its starting close and ${p2(r.medianDown)} below it, and ${p2(r.medianFar)} on its farther side " +
            "(the middle half ${p2(r.farLow)} to ${p2(r.farHigh)}); it ended a median ${p2(r.medianEnd)} away either way, and higher on ${r.endedUp} (${share(r.endedUp, r.n)})."
        val big = xs.maxBy { it.farPct }
        val bigSide = if (big.upPct >= big.downPct) "above" else "below"
        lines += "The biggest was the $n sessions to ${date(big.end)}, ${p2(big.farPct)} $bigSide ${date(big.from)}'s close, ending ${s2(big.endPct)} on it."
        lines += "The stretches overlap - each session sits in up to $n of them - so they are not ${r.n} separate tries." +
            if (r.n < FEW_STRETCHES) " And ${r.n} is a small record, so a few more would move these shares." else ""
        newest(bars, today, now, n, isTradingDay)?.let { lines += newestLine(m, xs, it, n) }
        lines += NOTE
        return lines.joinToString(" ")
    }

    /**
     * Where the newest stretch [s] sits among the record's [xs]: the share that reached less far, by
     * [DayAfter.percentile]'s rule (rounded down, 100 only when every one did - never "farther than 100%"). The newest
     * stretch itself, when it is in the record, is not compared with itself.
     */
    internal fun rankText(xs: List<Stretch>, s: Stretch): String {
        val others = xs.filter { !(it.from == s.from && it.end == s.end) }
        if (others.isEmpty()) return "the first stretch of its kind in this record"
        val pct = DayAfter.rank(others.count { it.farPct < s.farPct }, others.size)
        return if (pct >= 100) "a farther reach than every other stretch in this record"
            else "a farther reach than $pct% of the other stretches in this record"
    }

    /** The newest stretch, and the share of the record's stretches that reached less far. */
    private fun newestLine(m: Market, xs: List<Stretch>, nw: Newest, n: Int): String {
        val s = nw.s
        val reach = "reaching ${p2(s.upPct)} above it and ${p2(s.downPct)} below"
        val rank = rankText(xs, s)
        // "Ended" only for a whole session; today while it trades is "so far"; candles that stop early say when they stop.
        return when {
            nw.live -> "Counting today, the newest $n sessions have taken ${m.label} ${s2(s.endPct)} from ${date(s.from)}'s close so far, $reach - $rank; the stretch is not over."
            nw.whole -> "The newest $n sessions on the phone, to ${date(s.end)}, ended ${s2(s.endPct)} from ${date(s.from)}'s close, $reach - $rank."
            else -> "The newest $n sessions on the phone, to ${date(s.end)}, were ${s2(s.endPct)} from ${date(s.from)}'s close at ${hm(nw.last)}, where its candles stop, $reach - $rank."
        }
    }
}
