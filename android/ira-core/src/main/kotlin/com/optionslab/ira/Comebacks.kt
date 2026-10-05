package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * The intraday comeback record (market intelligence, round 32): "when Nifty is down 1% intraday, how often does it
 * recover?", "how often does BankNifty bounce back after falling 1.5% in the day?", "does a 1% intraday rally usually
 * hold?", "intraday recovery record for Nifty", "1% girne ke baad nifty kitni baar recover karta hai". From the whole
 * sessions of 1-minute candles on the phone (the newest [MAX_SESSIONS] before today), each set against the whole session
 * before it: the days the price touched the size asked ([DEFAULT_PCT] when none is) below the previous close (a fall) or
 * above it (a rally), how often that came, the median time it first did, and how those days ended - back on the other
 * side of the previous close (a full comeback), with at least half of the day's furthest move given back, or still the
 * size asked or more away (it held) - with the median close against the previous close, and falls (or rallies) touched
 * before noon set beside those touched after. Beside today so far: whether it has touched that size yet, when, and where
 * it is now. A record of past days on this phone, said with its counts and plainly when there are too few; never a
 * forecast or advice; nothing acts. A fall's cause stays [Causes]'s, an opening gap [GapRecord]'s, a quick move now
 * [SharpMove]'s and the run of closes [Streak]'s. Pure.
 */
object Comebacks {
    /** What was asked: the side (-1 a fall, +1 a rally, null both) and the size in % of the previous close (null: [DEFAULT_PCT]). */
    data class Q(val dir: Int?, val pct: Double?)

    /** One past session against the one before it: the previous close, the day's low and high, its close, and when it first touched each side. */
    data class Day(val day: LocalDate, val prevClose: Double, val low: Double, val high: Double, val close: Double,
                   val downAt: LocalTime?, val upAt: LocalTime?) {
        val changePct: Double get() = (close - prevClose) / prevClose * 100
    }

    /** How one side's touches ended. */
    data class Side(val dir: Int, val sessions: Int, val touched: Int, val back: Int, val half: Int, val held: Int,
                    val medianClose: Double?, val medianAt: LocalTime?, val morning: Int, val morningBack: Int, val afternoon: Int, val afternoonBack: Int)

    /** The size counted when none is asked (% of the previous close). */
    const val DEFAULT_PCT = 1.0
    /** Fewer sessions with a whole one before them than this: too few to say anything. */
    const val MIN_SESSIONS = 20
    /** Fewer touches of a side than this: too few to say that side's record. */
    const val MIN_TOUCHES = 5
    /** Fewer touches than this: said as a small record. */
    const val FEW_TOUCHES = 15
    /** At most this many of the newest sessions are read. */
    const val MAX_SESSIONS = 250
    /** The session before counts only when at most this many days earlier (a weekend and a holiday). */
    const val MAX_DAYS_APART = 4L
    private val FIRST_BY = LocalTime.of(9, 20)
    private val LAST_FROM = LocalTime.of(15, 25)
    /** The exchange calendar when the app gives none: every weekday trades. */
    val WEEKDAYS: (LocalDate) -> Boolean = { it.dayOfWeek.value <= 5 }

    /**
     * Does the session on [day] follow straight on from the one on [before] - no trading day by [isTradingDay] between them
     * ([ExpiryEve.nextTradingDay])? A missing session on the phone is never bridged (round 22 review). Shared with
     * [VixBand] and [NeedsTrue.todayBeside].
     */
    internal fun follows(before: LocalDate, day: LocalDate, isTradingDay: (LocalDate) -> Boolean): Boolean {
        if (!before.isBefore(day) || ChronoUnit.DAYS.between(before, day) > 14) return false
        val next = ExpiryEve.nextTradingDay(before, isTradingDay) ?: return false
        return !next.isBefore(day)
    }

    /** The indices the record is kept for (gold trades round the clock, India VIX is not traded). */
    internal val INDICES = listOf(Market.NIFTY, Market.BANKNIFTY, Market.FINNIFTY, Market.SENSEX)

    const val NOTE = "A record of past days on this phone, Boss, not a forecast."
    const val NOT_HERE = "I keep the comeback record for Nifty, BankNifty, FinNifty and Sensex only, Boss: gold trades round the clock, so it has no previous close to count from, and India VIX is not traded."

    private fun norm(text: String) = " " + text.lowercase(Locale.ENGLISH).replace("’", "'").replace("'", " ")
        .replace(rx("[^a-z0-9%. ]"), " ").replace(rx("(?<!\\d)\\.|\\.(?!\\d)"), " ").replace(rx("\\s+"), " ").trim() + " "
    private fun s2(x: Double) = (if (x > 0) "+" else "") + "%.2f%%".format(Locale.ENGLISH, x)
    private fun p1(x: Double) = "%.1f%%".format(Locale.ENGLISH, x).replace(".0%", "%")
    private fun share(k: Int, of: Int) = if (of == 0) "0%" else "%d%%".format(Locale.ENGLISH, Math.round(k * 100.0 / of))
    private fun hm(t: LocalTime) = "%02d:%02d".format(Locale.ENGLISH, t.hour, t.minute)
    private fun date(d: LocalDate) = "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}"

    // ---- the questions --------------------------------------------------------------------------------------------

    /** Coming back from a move, or the move holding (or given back), in English and Hinglish. */
    private val TURN = Regex(" (recover|recovers|recovered|recovering|recovery|recoveries|bounce back|bounces back|bounced back|bouncing back|" +
        "come back|comes back|came back|coming back|comeback|comebacks|rebound|rebounds|rebounded|rebounding|claw back|claws back|clawed back|" +
        "win back|wins back|won back|give up|gives up|gave up|give it up|gives it up|pare|pares|pared|give back|gives back|gave back|give it back|gives it back|give it all back|gives it all back|giveback|" +
        "fade|fades|faded|fading|hold|holds|held|holding|sustain|sustains|sustained|stick|sticks|end green|ends green|end red|ends red|" +
        "finish green|finishes green|finish red|finishes red|end in the green|ends in the green|end in the red|ends in the red|" +
        "wapas|waapas|vapas|vaapas|recover karta|recover karti|recover hota|recover hoti|tikta|tikti|tikta hai) ")
    /** The move itself said intraday-sized: a size in %, "intraday", "in the day", "din mein". */
    private val SIZE = Regex(" \\d+(\\.\\d+)? ?(%|percent|per cent|pc|pct) ")
    private val INTRADAY = Regex(" (intraday|intra day|in the day|during the day|in a day|within the day|in the session|during the session|din mein|din me|din main) ")
    /** Named as a record: "comeback record", "intraday recovery record", "dip recovery stats". */
    private val NAME = Regex(" (comeback|comebacks|recovery|recoveries|dip recovery|intraday recovery|rally fade|rally hold|fall recovery) " +
        "(record|records|stats|statistics|history|rate|odds|habit|habits) ")
    /** Asked of the record: how often, usually, odds. */
    private val HOW = Regex(" (how often|how many times|how many days|how frequently|what share|what percent|what percentage|what fraction|" +
        "usually|normally|typically|generally|tend to|tends to|on average|most times|historically|record|history|stats|statistics|odds|" +
        "chance|chances|probability|kitni baar|kitne din|kitne baar|aksar|zyada tar|mostly|often) ")
    private val DOWN = Regex(" (down|fall|falls|fell|falling|fallen|drop|drops|dropped|dropping|dip|dips|dipped|dipping|slide|slides|slid|sliding|" +
        "sink|sinks|sank|sinking|selloff|sell off|girta|girti|girte|girne|gira|giri|gire|gir|neeche|niche|tootne|tutne|toota|tuta) ")
    private val UP = Regex(" (up|rise|rises|rose|rising|risen|rally|rallies|rallied|rallying|jump|jumps|jumped|jumping|surge|surges|surged|" +
        "gain|gains|gained|climb|climbs|climbed|climbing|chadhta|chadhti|chadhne|chadha|chadhe|upar|badhne|badhta|badhti) ")
    /** "Does Nifty recover...", "does it bounce back...": asked as a habit when said with a size. */
    private val HABIT = Regex("^ (does|do) (nifty|bank nifty|banknifty|finnifty|fin nifty|sensex|the market|the index|it|they|indices|the indices) ")
    /** "Bounce back up" / "comes back up": the way back, never the move asked of. */
    private val BACK_UP = Regex(" (back|wapas|waapas|vapas|vaapas) up ")
    /** "Give up a 1% gain" is giving it back, never a move up. */
    private val GIVE_UP = Regex(" (give|gives|gave) (it )?up ")
    // A forecast or advice, Boss's own book, a what-if, alerts and reminders, the app's bots, stock screens, a definition, a
    // reason, today or now (the day's own read), the open, gaps, candles, the last hour, yesterday's levels, a week's, month's
    // or year's move, expiry, options, gold or VIX.
    private val NOT = Regex(" (will|would|going to|gonna|tomorrow|kal|next|predict|prediction|forecast|outlook|expect|should|shall|buy|sell|enter|exit|" +
        "trade|trades|trading|i|me|my|mine|we|our|what if|suppose|imagine|scenario|agar|remind|reminder|alert|alarm|notify|bot|bots|algo|strategy|backtest|" +
        "stock|stocks|scan|scanner|screener|share|shares|mean|means|meaning|define|explain|why|kyun|kyon|kyu|reason|" +
        "today|todays|aaj|aj|now|right now|abhi|so far|this morning|since|open|opens|opened|opening|gap|gaps|gapped|candle|candles|" +
        "first hour|last hour|closing hour|lunch|yesterday|yesterdays|yesterday s|previous day|prior day|pdh|pdl|week|weekly|weeks|hafte|month|monthly|year|yearly|" +
        "expiry|expiries|call|calls|put|puts|premium|premiums|option|options|ce|pe|strike|gold|vix|fear|position|positions|portfolio|stop|stops|sl) ")

    /** What was asked, or null: the record of intraday falls (or rallies) and how the day ended only, never a forecast or advice. */
    fun asked(text: String): Q? = askedKept.of(text) { askedFresh(text) }

    /** The last words read (the hub reads them as said, then again in its order; [Kept], pure). */
    private val askedKept = Kept<Q?>(64)

    private fun askedFresh(text: String): Q? {
        val t = norm(text)
        if (NOT.containsMatchIn(t)) return null
        if (Market.mentioned(text).any { it == Market.GOLD || it == Market.VIX }) return null
        val named = NAME.containsMatchIn(t)
        if (!named) {
            // (Round 22: "does Nifty recover from a 1% fall?" - asked of its habit with a size, never of today, which NOT keeps out.)
            if (!TURN.containsMatchIn(t) || !(HOW.containsMatchIn(t) || HABIT.containsMatchIn(t) && SIZE.containsMatchIn(t))) return null
            if (!SIZE.containsMatchIn(t) && !INTRADAY.containsMatchIn(t)) return null
        }
        val sides = BACK_UP.replace(t, " back ").replace(GIVE_UP, " give back ")
        val down = DOWN.find(sides)?.range?.first
        val up = UP.find(sides)?.range?.first
        val dir = when {
            down != null && (up == null || down < up) -> -1
            up != null -> 1
            rx(" (recover|recovers|recovered|recovery|recoveries|bounce back|bounces back|rebound|rebounds|dip recovery|fall recovery) ").containsMatchIn(t) -> -1
            rx(" (fade|fades|faded|give back|gives back|give it back|gives it back|giveback|rally fade|rally hold) ").containsMatchIn(t) -> 1
            else -> null
        }
        if (!named && dir == null) return null
        val pct = rx("(\\d+(?:\\.\\d+)?) ?(%|percent|per cent|pc|pct) ").find(t)?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it in 0.2..5.0 }
        return Q(dir, pct)
    }

    /** The index asked about (Nifty when none is named), or null for gold or India VIX alone. */
    fun market(markets: List<Market>): Market? {
        if (markets.isEmpty()) return Market.NIFTY
        return markets.firstOrNull { it in INDICES }
    }

    // ---- the record -----------------------------------------------------------------------------------------------

    /** A whole session: its first bar no later than 09:20, its last no earlier than 15:25 (shared with [NeedsTrue.todayBeside]). */
    internal fun whole(s: MarketStory.Session) =
        s.bars.isNotEmpty() && !s.bars.first().t.toLocalTime().isAfter(FIRST_BY) && !s.bars.last().t.toLocalTime().isBefore(LAST_FROM)

    /**
     * The whole sessions before [today] in [bars] whose session before on the phone was whole, at most [MAX_DAYS_APART]
     * days earlier and the trading day just before it by [isTradingDay] (a missing session is never bridged), each with
     * when it first touched [pct] below and above that close; newest [MAX_SESSIONS], oldest first.
     */
    fun past(bars: List<Candle>, today: LocalDate, pct: Double, isTradingDay: (LocalDate) -> Boolean = WEEKDAYS): List<Day> {
        val ss = MarketStory.sessions(bars).filter { it.day.isBefore(today) && it.bars.isNotEmpty() }
        val out = ArrayList<Day>()
        for (i in 1 until ss.size) {
            val before = ss[i - 1]; val s = ss[i]
            if (!whole(before) || !whole(s) || ChronoUnit.DAYS.between(before.day, s.day) > MAX_DAYS_APART || before.close <= 0) continue
            if (!follows(before.day, s.day, isTradingDay)) continue
            val pc = before.close
            val downLine = pc * (1 - pct / 100); val upLine = pc * (1 + pct / 100)
            out += Day(s.day, pc, s.bars.minOf { it.l }, s.bars.maxOf { it.h }, s.close,
                s.bars.firstOrNull { it.l <= downLine }?.t?.toLocalTime(), s.bars.firstOrNull { it.h >= upLine }?.t?.toLocalTime())
        }
        return out.takeLast(MAX_SESSIONS)
    }

    private fun median(xs: List<Double>): Double { val s = xs.sorted(); return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }
    private fun medianTime(ts: List<LocalTime>): LocalTime = LocalTime.ofSecondOfDay(median(ts.map { it.toSecondOfDay().toDouble() }).toLong()).withSecond(0)

    /** How the days that touched [pct] on [dir]'s side (-1 below the previous close, +1 above) ended. */
    fun side(days: List<Day>, dir: Int, pct: Double): Side {
        val hit = days.filter { (if (dir < 0) it.downAt else it.upAt) != null }
        // Back: closed on the other side of the previous close. Half: closed at least half way back from the day's furthest
        // point on that side to the previous close. Held: still the size asked or more away at the close.
        fun back(d: Day) = if (dir < 0) d.close > d.prevClose else d.close < d.prevClose
        fun half(d: Day) = if (dir < 0) d.close - d.low >= 0.5 * (d.prevClose - d.low) else d.high - d.close >= 0.5 * (d.high - d.prevClose)
        fun held(d: Day) = if (dir < 0) d.close <= d.prevClose * (1 - pct / 100) else d.close >= d.prevClose * (1 + pct / 100)
        val at = hit.map { if (dir < 0) it.downAt!! else it.upAt!! }
        val am = hit.filter { (if (dir < 0) it.downAt!! else it.upAt!!).isBefore(LocalTime.NOON) }
        val pm = hit - am.toSet()
        return Side(dir, days.size, hit.size, hit.count(::back), hit.count(::half), hit.count(::held),
            if (hit.isEmpty()) null else median(hit.map { it.changePct }), if (at.isEmpty()) null else medianTime(at),
            am.size, am.count(::back), pm.size, pm.count(::back))
    }

    /** [m]'s record from its 1-minute candles over many days, for [q], at [now] on [today]; [isTradingDay] the app's calendar. */
    fun answer(q: Q, m: Market, bars: List<Candle>, today: LocalDate, now: LocalDateTime, isTradingDay: (LocalDate) -> Boolean = WEEKDAYS): String {
        val pct = q.pct ?: DEFAULT_PCT
        val days = past(bars, today, pct, isTradingDay)
        if (days.size < MIN_SESSIONS)
            return "I have only ${days.size} whole session${if (days.size == 1) "" else "s"} of ${m.label} with the one before on the phone, Boss - " +
                "too few to say how its intraday moves usually end (I need $MIN_SESSIONS)."
        val lines = ArrayList<String>()
        lines += "Over the last ${days.size} whole sessions of ${m.label} on this phone (${date(days.first().day)} to ${date(days.last().day)}), counted from each previous close:"
        for (dir in (q.dir?.let { listOf(it) } ?: listOf(-1, 1))) lines += sideLine(m, side(days, dir, pct), pct)
        todayLine(m, bars, today, now, pct, isTradingDay)?.let { lines += it }
        lines += NOTE
        return lines.joinToString(" ")
    }

    private fun sideLine(m: Market, s: Side, pct: Double): String {
        val fall = s.dir < 0
        val move = if (fall) "fell ${p1(pct)} or more below" else "rose ${p1(pct)} or more above"
        if (s.touched == 0) return "${m.label} never $move the previous close in the day."
        val head = "${m.label} $move the previous close in the day on ${s.touched} of them (${share(s.touched, s.sessions)})" +
            (s.medianAt?.let { ", first reaching it at ${hm(it)} on the median day" } ?: "") + "."
        if (s.touched < MIN_TOUCHES)
            return "$head That is too few to say how such days usually end (I need $MIN_TOUCHES)."
        val parts = ArrayList<String>()
        parts += head
        parts += "Of those, ${s.back} ended back ${if (fall) "above" else "below"} the previous close (${share(s.back, s.touched)}), " +
            "${s.half} ${if (fall) "won back at least half of the day's fall" else "gave back at least half of the day's rise"} (${share(s.half, s.touched)}), " +
            "and ${s.held} ended still ${p1(pct)} or more ${if (fall) "down" else "up"} (${share(s.held, s.touched)}); " +
            "the median such day ended ${s2(s.medianClose ?: 0.0)} on the previous close."
        if (s.morning > 0 && s.afternoon > 0)
            parts += "${if (fall) "Falls" else "Rallies"} reached before noon ended back ${if (fall) "above" else "below"} it on ${s.morningBack} of ${s.morning}, " +
                "those reached after noon on ${s.afternoonBack} of ${s.afternoon}."
        if (s.touched < FEW_TOUCHES) parts += "That is only ${s.touched} days, so a few days move these figures a lot."
        return parts.joinToString(" ")
    }

    /** Today so far, when the phone has today's session and a whole one before it: where it stands against the size asked. */
    private fun todayLine(m: Market, bars: List<Candle>, today: LocalDate, now: LocalDateTime, pct: Double, isTradingDay: (LocalDate) -> Boolean): String? {
        val ss = MarketStory.sessions(bars)
        val t = ss.lastOrNull { it.day == today }?.takeIf { it.bars.isNotEmpty() } ?: return null
        val before = ss.lastOrNull { it.day.isBefore(today) }?.takeIf { whole(it) && it.close > 0 && follows(it.day, today, isTradingDay) } ?: return null
        val pc = before.close
        val live = now.toLocalDate() == today && now.toLocalTime().isBefore(LocalTime.of(15, 30))
        val downAt = t.bars.firstOrNull { it.l <= pc * (1 - pct / 100) }?.t?.toLocalTime()
        val upAt = t.bars.firstOrNull { it.h >= pc * (1 + pct / 100) }?.t?.toLocalTime()
        val lowPct = (t.bars.minOf { it.l } - pc) / pc * 100
        val highPct = (t.bars.maxOf { it.h } - pc) / pc * 100
        val nowPct = (t.close - pc) / pc * 100
        val where = "${if (live) "is" else "ended"} ${s2(nowPct)} on the previous close"
        val touched = listOfNotNull(downAt?.let { "fell ${p1(pct)} below it at ${hm(it)}" }, upAt?.let { "rose ${p1(pct)} above it at ${hm(it)}" })
        return if (touched.isEmpty())
            "Today ${m.label} has not moved ${p1(pct)} either way from the previous close${if (live) " so far" else ""} (its low ${s2(lowPct)}, its high ${s2(highPct)}) and $where."
        else "Today ${m.label} ${touched.joinToString(" and ")}, and $where."
    }
}
