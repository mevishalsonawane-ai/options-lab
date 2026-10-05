package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * The time-to-move record (market intelligence, round 38): "how long does Nifty usually take to move 50 points?", "how
 * often does Nifty move 0.3% within 30 minutes?", "how many minutes does BankNifty take to move 200 points?", "time to
 * move record for Sensex", "nifty ko 50 point chalne mein kitna time lagta hai". The question an option buyer paying time
 * decay asks: how long the index usually takes to travel a given distance. From the whole sessions of 1-minute candles on
 * the phone (the newest [MAX_SESSIONS] before today, each read on its own and never set against another, [Comebacks.whole]
 * only), a try starts at each quarter hour from [FIRST_START] to [LAST_START] at that minute's close; its wait is the
 * minutes until a later 1-minute candle first traded the size asked ([DEFAULT_PCT]% when none is, or points) above or
 * below that price, within the same session. How often the size came within the window asked ([DEFAULT_WINDOW] minutes
 * when none is; tries whose window runs past the close are left out), the median wait, the shares within 15, 30 and 60
 * minutes, which side came first, and the morning starts against the afternoon ones. The tries of one session overlap,
 * and that is said. Beside today's starts so far ("ended" only for a whole session). A record of past sessions on this
 * phone, said with its counts and plainly when there are too few; never a forecast or advice; nothing acts. A sharp move
 * explained stays [SharpMove]'s, the busiest half hour [DayClock]'s, the reach from the open [OpenReach]'s, a few
 * sessions' move [MultiDay]'s, a big 5-minute candle [BigCandles]', the first move [FirstMove]'s. Pure.
 */
object MoveTime {
    /**
     * What was asked: the size in % ([pct]) or in points ([pts], then [pct] unused), the window in minutes; [asked] the %
     * said when it was outside [MIN_PCT] to [MAX_PCT] (then [pct] is [DEFAULT_PCT], and the answer says so).
     */
    data class Q(val pct: Double = DEFAULT_PCT, val pts: Double? = null, val window: Int = DEFAULT_WINDOW, val asked: Double? = null)

    /** One try: the session [day], its [start] and price [from]; [wait] the minutes to the size (null: not by the close); [up] the side first reached. */
    data class Try(val day: LocalDate, val start: LocalTime, val from: Double, val wait: Int?, val up: Boolean?, val room: Int)

    /**
     * The record for one window: [n] tries with that long left in the session, [hit] within it ([upFirst] / [downFirst] of
     * them), their median wait (null: over half never got there before the close), the 15, 30 and 60-minute ladder, the
     * morning and afternoon starts, and the sessions counted.
     */
    data class Record(val n: Int, val hit: Int, val upFirst: Int, val downFirst: Int, val medianWait: Int?,
                      val in15: Int, val n15: Int, val in30: Int, val n30: Int, val in60: Int, val n60: Int,
                      val amHit: Int, val am: Int, val pmHit: Int, val pm: Int, val days: Int)

    /** The sizes counted, % of the starting price. */
    const val MIN_PCT = 0.05
    const val MAX_PCT = 3.0
    /** The size when none is asked, %. */
    const val DEFAULT_PCT = 0.25
    /** The windows counted, minutes. */
    const val MIN_WINDOW = 5
    const val MAX_WINDOW = 180
    /** The window when none is asked, minutes. */
    const val DEFAULT_WINDOW = 30
    /** Fewer whole sessions than this: too few to say anything. */
    const val MIN_SESSIONS = 10
    /** Fewer whole sessions than this: said as a small record. */
    const val FEW_SESSIONS = 30
    /** At most this many of the newest whole sessions are read. */
    const val MAX_SESSIONS = 120
    /** The tries start each quarter hour from here to [LAST_START]. */
    val FIRST_START: LocalTime = LocalTime.of(9, 30)
    val LAST_START: LocalTime = LocalTime.of(15, 0)
    /** The morning starts are before this; the afternoon ones from [PM_FROM]. */
    private val AM_UNTIL = LocalTime.of(11, 30)
    private val PM_FROM = LocalTime.of(13, 0)
    private val CLOSE = LocalTime.of(15, 30)
    /** The indices the record is kept for (gold trades round the clock, India VIX is not traded). */
    internal val INDICES = listOf(Market.NIFTY, Market.BANKNIFTY, Market.FINNIFTY, Market.SENSEX)

    const val NOTE = "A record of past sessions on this phone, Boss, not a forecast."
    const val NOT_HERE = "I keep the time-to-move record for Nifty, BankNifty, FinNifty and Sensex only, Boss: gold trades round the clock and India VIX is not traded."

    private fun norm(text: String) = " " + text.lowercase(Locale.ENGLISH).replace("’", "'").replace("'", " ")
        .replace(rx("[^a-z0-9%. ]"), " ").replace(rx("(?<!\\d)\\.|\\.(?!\\d)"), " ").replace(rx("\\s+"), " ").trim() + " "
    private fun p2(x: Double) = "%.2f%%".format(Locale.ENGLISH, x)
    private fun pts(x: Double) = "%,.0f".format(Locale.ENGLISH, x)
    private fun count(x: Int) = "%,d".format(Locale.ENGLISH, x)
    private fun share(k: Int, of: Int) = if (of == 0) "0%" else "%d%%".format(Locale.ENGLISH, Math.round(k * 100.0 / of))
    private fun date(d: LocalDate) = "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}"
    private fun hm(t: LocalTime) = "%02d:%02d".format(Locale.ENGLISH, t.hour, t.minute)
    private fun mins(m: Int) = if (m == 1) "1 minute" else "$m minutes"
    private fun sizeText(pct: Double) = "%.2f".format(Locale.ENGLISH, pct).trimEnd('0').trimEnd('.') + "%"

    // ---- the questions --------------------------------------------------------------------------------------------

    /** How long: "how long does Nifty take", "how many minutes", "kitna time lagta hai". */
    private val TIME_ASK = Regex(" (how long|how much time|how many minutes|how many mins|kitna time|kitni der|kitne minute|kitne minutes|" +
        "kitna samay|kitna waqt|kitna vakt|kitne der|" +
        // Understanding round 26: "how fast / how quickly", "time taken for Nifty to move", "average time", "kitni jaldi",
        // "kitne time mein".
        "how fast|how quickly|how quick|how soon|time taken|time it takes|time needed|time required|(average|typical|usual|normal|median) time|" +
        "time to (move|travel|cover|go|do|make|run)|time for (\\w+ ){1,2}to|kitni jaldi|kitne jaldi|kitne time|kitne samay|kitne waqt) ")
    /** Asked of the record: how often, usually. */
    private val HOW = Regex(" (how often|how many times|how frequently|what share|what percent|what percentage|usually|normally|typically|generally|" +
        "tend to|tends to|on average|average|median|historically|odds|chance|chances|kitni baar|kitni bar|aksar|zyada tar|mostly|often) ")
    /** Travelling the distance. */
    private val MOVE = Regex(" (move|moves|moved|moving|movement|go|goes|travel|travels|travelling|traveling|cover|covers|swing|swings|run|runs|" +
        "rise|rises|fall|falls|drop|drops|rally|rallies|climb|climbs|gain|gains|slide|slides|reach|reaches|chalne|chalta|chalti|hilne|hilta|" +
        "jaane|jane|jata|jaata|girne|chadhne|badhne) " +
        // "How long for BankNifty to do 200 points", "take for 50 points" (round 26): a verb right before the size.
        "| (do|does|make|makes|take|takes|for|clock|clocks|hit|hits) (a |an )?\\d{1,5}(\\.\\d+)? ?(points|point|pts|pt|%|percent|per cent|pc|pct) ")
    /** "Kitni der me", "30 minute me": the Hindi "me" (in) after a time, read as said before [NOT] takes it for Boss's (round 26). */
    private val HI_ME = Regex(" (der|time|samay|waqt|vakt|minute|minutes|mins|min|ghante|ghanta) me ")
    /** Named as a record. */
    private val NAME = Regex(" (time to move|move time|move speed|time to travel|minutes to move) (record|records|stats|statistics|history|data) ")
    private val PAST_ONE = Regex(" (did|has|have|had) ")
    private val COUNTING = Regex(" (how often|how many times|kitni baar|kitni bar) ")
    private val SIZE = Regex(" (\\d+(?:\\.\\d+)?) ?(%|percent|per cent|pc|pct) ")
    private val POINTS = Regex(" (\\d{1,5}(?:\\.\\d+)?) ?(points|point|pts|pt) ")
    private val MINUTES = Regex(" (\\d{1,3}) ?(minutes|minute|mins|min|minat|mint) ")
    private val HALF_HOUR = Regex(" (half an hour|half hour|aadhe ghante|adhe ghante|aadha ghanta|adha ghanta) ")
    private val ONE_HOUR = Regex(" (an hour|one hour|1 hour|ek ghante|ek ghanta|1 ghante|1 ghanta) ")
    private val TWO_HOURS = Regex(" (two hours|2 hours|do ghante|2 ghante) ")
    // A forecast or advice, Boss's own book, a what-if, alerts and reminders, the app's bots, stock screens, a definition, a
    // reason, today, now, one past move, days or sessions (MultiDay's), the open, the first or last hour or the close, the
    // gap, a comeback, candles, a week, a month, expiry, options, gold or VIX, and Jarvis's or the app's own speed.
    private val NOT = Regex(" (will|would|going to|gonna|tomorrow|tomorrows|kal|predict|prediction|forecast|outlook|expect|should|shall|buy|sell|" +
        "enter|exit|i|me|my|mine|we|our|if|what if|suppose|imagine|scenario|agar|remind|reminder|alert|alarm|notify|bot|bots|algo|strategy|" +
        "backtest|stock|stocks|scan|scanner|screener|shares|mean|means|meaning|define|explain|why|kyun|kyon|kyu|reason|" +
        "today|todays|aaj|aj|now|right now|abhi|so far|this|that|last|past|previous|recent|recently|yesterday|yesterdays|when did|" +
        "after|before|ke baad|ke bad|day|days|daily|session|sessions|din|dino|dinon|open|opening|first|close|closing|lunch|morning|afternoon|" +
        "overnight|night|gap|gaps|recover|recovers|recovery|comeback|bounce|fill|fills|candle|candles|bar|bars|" +
        "week|weekly|weeks|hafte|month|monthly|months|year|years|expiry|expiries|" +
        "call|calls|put|puts|premium|premiums|option|options|ce|pe|strike|strikes|straddle|strangle|theta|gold|vix|fear|" +
        "position|positions|portfolio|stop|stops|sl|target|news|order|orders|reply|answer|respond|load|loads|login|connect|you|jarvis|app) ")

    /** What was asked, or null: the record of how long the index took to travel a distance, never a forecast or advice. */
    fun asked(text: String): Q? = askedKept.of(text) { askedFresh(text) }

    /** The last words read (the hub reads them as said, then again in its order; [Kept], pure). */
    private val askedKept = Kept<Q?>(64)

    private fun window(t: String): Int? {
        MINUTES.find(t)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return when {
            HALF_HOUR.containsMatchIn(t) -> 30
            TWO_HOURS.containsMatchIn(t) -> 120
            ONE_HOUR.containsMatchIn(t) -> 60
            else -> null
        }
    }

    private fun askedFresh(text: String): Q? {
        val t = norm(text).let { x -> HI_ME.replace(x) { m -> m.value.removeSuffix("me ") + "mein " } }
        if (NOT.containsMatchIn(t)) return null
        if (Market.mentioned(text).any { it == Market.GOLD || it == Market.VIX }) return null
        if (PAST_ONE.containsMatchIn(t) && !COUNTING.containsMatchIn(t)) return null
        val p = POINTS.find(t)?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it > 0 }
        val pc = SIZE.find(t)?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it > 0 }
        val sized = p != null || pc != null
        val w = window(t)
        val named = NAME.containsMatchIn(t)
        val ok = named || (sized && MOVE.containsMatchIn(t) && (TIME_ASK.containsMatchIn(t) || (w != null && HOW.containsMatchIn(t))))
        if (!ok) return null
        if (w != null && (w < MIN_WINDOW || w > MAX_WINDOW)) return null
        val win = w ?: DEFAULT_WINDOW
        if (p != null) return Q(pts = p, window = win)
        if (pc == null) return Q(window = win)
        return if (pc >= MIN_PCT && pc <= MAX_PCT) Q(pc, window = win) else Q(DEFAULT_PCT, window = win, asked = pc)
    }

    /** The index asked about (Nifty when none is named), or null for gold or India VIX alone. */
    fun market(markets: List<Market>): Market? {
        if (markets.isEmpty()) return Market.NIFTY
        return markets.firstOrNull { it in INDICES }
    }

    // ---- the record -----------------------------------------------------------------------------------------------

    /** The quarter-hour starts of a session. */
    private val STARTS: List<LocalTime> = generateSequence(FIRST_START) { it.plusMinutes(15) }.takeWhile { !it.isAfter(LAST_START) }.toList()

    /** Each start's try in [s]'s 1-minute candles, for a size of [pct]% of the starting price, or [pts] points when given. */
    internal fun tries(s: MarketStory.Session, pct: Double, pts: Double?): List<Try> {
        val bars = s.bars
        val out = ArrayList<Try>()
        val end = bars.last().t.toLocalTime()
        for (st in STARTS) {
            val i = bars.indexOfFirst { it.t.toLocalTime() == st }
            if (i < 0) continue
            val from = bars[i].c
            if (from <= 0) continue
            val d = pts ?: (from * pct / 100)
            var wait: Int? = null; var up: Boolean? = null
            for (j in i + 1 until bars.size) {
                val b = bars[j]
                val hiHit = b.h >= from + d; val loHit = b.l <= from - d
                if (hiHit || loHit) {
                    wait = ((b.t.toLocalTime().toSecondOfDay() - st.toSecondOfDay()) / 60)
                    up = if (hiHit && loHit) null else hiHit
                    break
                }
            }
            out += Try(s.day, st, from, wait, up, (end.toSecondOfDay() - st.toSecondOfDay()) / 60)
        }
        return out
    }

    /** The whole sessions before [today] in [bars], newest [MAX_SESSIONS], oldest first ([Comebacks.whole]). */
    fun sessions(bars: List<Candle>, today: LocalDate): List<MarketStory.Session> =
        MarketStory.sessions(bars).filter { it.day.isBefore(today) && Comebacks.whole(it) }.takeLast(MAX_SESSIONS)

    /** Every try in the whole sessions before [today]. */
    fun past(bars: List<Candle>, today: LocalDate, pct: Double, pts: Double? = null): List<Try> =
        sessions(bars, today).flatMap { tries(it, pct, pts) }

    private fun within(x: Try, w: Int) = x.wait != null && x.wait <= w

    /** [xs]' record for a window of [w] minutes; a try counts for a window only when its session ran that long after it. */
    fun record(xs: List<Try>, w: Int): Record {
        val ws = xs.filter { it.room >= w }
        val hits = ws.filter { within(it, w) }
        val waits = ws.map { it.wait ?: Int.MAX_VALUE }.sorted()
        val med = if (waits.isEmpty()) null else waits[(waits.size - 1) / 2].takeIf { it != Int.MAX_VALUE }
        fun ladder(m: Int): Pair<Int, Int> { val ys = xs.filter { it.room >= m }; return ys.count { within(it, m) } to ys.size }
        val (in15, n15) = ladder(15); val (in30, n30) = ladder(30); val (in60, n60) = ladder(60)
        val am = ws.filter { it.start.isBefore(AM_UNTIL) }; val pm = ws.filter { !it.start.isBefore(PM_FROM) }
        return Record(ws.size, hits.size, hits.count { it.up == true }, hits.count { it.up == false }, med,
            in15, n15, in30, n30, in60, n60, am.count { within(it, w) }, am.size, pm.count { within(it, w) }, pm.size,
            xs.map { it.day }.distinct().size)
    }

    /** [m]'s record from its 1-minute candles over many days, for [q], at [now] on [today]. */
    fun answer(q: Q, m: Market, bars: List<Candle>, today: LocalDate, now: LocalDateTime): String {
        val w = q.window.coerceIn(MIN_WINDOW, MAX_WINDOW)
        val ss = sessions(bars, today)
        if (ss.size < MIN_SESSIONS)
            return "I have only ${ss.size} whole ${m.label} session${if (ss.size == 1) "" else "s"} on the phone, Boss - " +
                "too few to say how long it usually takes to move (I need $MIN_SESSIONS)."
        val xs = ss.flatMap { tries(it, q.pct, q.pts) }
        val r = record(xs, w)
        if (r.n == 0) return "None of the ${m.label} sessions on the phone had a start with ${mins(w)} left before the close, Boss."
        val size = if (q.pts != null) "${pts(q.pts)} points" else sizeText(q.pct)
        val lines = ArrayList<String>()
        val wait = r.medianWait?.let { "the median wait was ${mins(it)}" } ?: "more than half of those tries never got that far before the close"
        // The lead carries the key figure (ShortAnswer's line).
        lines += "${m.label} moved $size either way within ${mins(w)} on ${count(r.hit)} of the last ${count(r.n)} quarter-hour starts on this phone " +
            "(${share(r.hit, r.n)}), and $wait."
        if (q.asked != null)
            lines += "I count sizes of ${sizeText(MIN_PCT)} to ${sizeText(MAX_PCT)} only, so that is ${sizeText(q.pct)}, not the ${sizeText(q.asked)} asked."
        if (q.pts != null) {
            val avg = xs.map { it.from }.average()
            lines += "At the average start price of about ${pts(avg)}, ${pts(q.pts)} points is ${p2(q.pts / avg * 100)}."
        }
        lines += "A try starts at each quarter hour from ${hm(FIRST_START)} to ${hm(LAST_START)} at that minute's close, over ${r.days} whole sessions " +
            "(${date(ss.first().day)} to ${date(ss.last().day)}); the size came within 15 minutes on ${share(r.in15, r.n15)}, within 30 on ${share(r.in30, r.n30)} " +
            "and within an hour on ${share(r.in60, r.n60)}."
        if (r.hit > 0) lines += "Of those that got there within ${mins(w)}, it was above the start first on ${r.upFirst} and below it first on ${r.downFirst}."
        if (r.am > 0 && r.pm > 0)
            lines += "Starts before ${hm(AM_UNTIL)} got there within ${mins(w)} on ${share(r.amHit, r.am)} of ${count(r.am)}; starts from ${hm(PM_FROM)} on ${share(r.pmHit, r.pm)} of ${count(r.pm)}."
        lines += "The tries in one session overlap, so they are not ${count(r.n)} separate chances." +
            if (r.days < FEW_SESSIONS) " And ${r.days} sessions is a small record, so a few more would move these shares." else ""
        todayLine(m, bars, today, now, q, w)?.let { lines += it }
        lines += NOTE
        return lines.joinToString(" ")
    }

    /** Today's starts whose window has passed: how many moved the size within it ("so far" while it trades, "ended" only whole). */
    private fun todayLine(m: Market, bars: List<Candle>, today: LocalDate, now: LocalDateTime, q: Q, w: Int): String? {
        val s = MarketStory.sessions(bars).lastOrNull { it.day == today && it.bars.isNotEmpty() } ?: return null
        val ts = tries(s, q.pct, q.pts).filter { it.room >= w }
        if (ts.isEmpty()) return null
        val k = ts.count { within(it, w) }
        val live = now.toLocalDate() == today && now.toLocalTime().isBefore(CLOSE)
        val of = "$k of the ${ts.size} quarter-hour start${if (ts.size == 1) "" else "s"} with ${mins(w)} behind ${if (ts.size == 1) "it" else "them"}"
        return when {
            live -> "Today so far, $of moved that far within the window (${share(k, ts.size)})."
            Comebacks.whole(s) -> "Today's session ended with $of moving that far within the window (${share(k, ts.size)})."
            else -> "Today's candles stop at ${hm(s.bars.last().t.toLocalTime())}; up to there, $of moved that far within the window (${share(k, ts.size)})."
        }
    }
}
