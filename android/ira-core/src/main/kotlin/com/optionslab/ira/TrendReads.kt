package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

/**
 * An honest record of Jarvis's own trend and range reads (reasoning, round 14): "how often were your trend reads right
 * this month?", "how accurate are your structure reads?", "did your trend calls hold?", "tumhare trend reads kitne sahi
 * the". Each time he tells Boss, in session, that an index's day is trend-like up, trend-like down or range-like
 * ([Structure]'s own measure), the call is noted - the index, the minute, the kind and the price then; never a word Boss
 * said. After the close the same measure is taken over the whole day and the call is scored: HELD (the day ended the kind
 * he called), TURNED (it ended another kind) or MIXED (it ended in between, neither). A read in the last [LATE_MINUTES]
 * of the session is not noted (the close is then nearly known), nor is one said after the session, nor an "in between"
 * read (it is no call). A read said again the same day counts once; a changed read is a second call, scored too.
 *
 * His calls, not Boss's; facts about his own record, never a forecast or advice. Nothing here acts or changes what he says
 * elsewhere. Pure.
 */
object TrendReads {
    enum class Kind(val label: String, val way: Int) { UP("trend-like up", 1), DOWN("trend-like down", -1), RANGE("range-like", 0) }
    enum class Verdict { HELD, TURNED, MIXED, NONE }
    enum class Span(val words: String) { TODAY("today"), WEEK("this week"), MONTH("this month"), LAST_MONTH("last month"), ALL("on this phone") }

    /** Fewer scored calls than this: "too few to go by". */
    const val FEW = 5
    /** A read this close to the session's end is not noted. */
    const val LATE_MINUTES = 15L
    /** A read is noted only from candles at most this old (minutes). */
    const val FRESH_MINUTES = 10L
    /** The day is scored this long after the close (late candles). */
    const val SETTLE_AFTER = 15L
    /** Without a candle this close to the session's end, the day is not scored (NONE). */
    const val END_SLACK = 15L
    const val KEEP_DAYS = 120L
    const val KEEP = 600

    /** One read Jarvis gave: [at] the minute said (as of its newest candle), [price] the index then. */
    data class Call(val market: Market, val at: LocalDateTime, val kind: Kind, val price: Double,
                    val verdict: Verdict? = null, val ended: Kind? = null, val close: Double? = null) {
        val day: LocalDate get() = at.toLocalDate()
        val key: String get() = "${market.name}|$day|${kind.name}"
        val settled: Boolean get() = verdict != null
    }

    private fun kindOf(dayKind: Int?): Kind? = when (dayKind) { 1 -> Kind.UP; -1 -> Kind.DOWN; 0 -> Kind.RANGE; else -> null }

    /**
     * The call in [m]'s structure read from [bars] at [now], or null when it is no call: not in session, too late in it, the
     * candles too old, or the day in between.
     */
    fun call(m: Market, bars: List<Candle>, now: LocalDateTime): Call? {
        val close = m.close ?: return null
        if (m == Market.VIX || !m.trading(now)) return null
        if (!now.toLocalTime().isBefore(close.minusMinutes(LATE_MINUTES))) return null
        val r = Structure.read(m, bars, now.toLocalDate()) ?: return null
        val asOf = r.at.plusMinutes(1)
        if (asOf.plusMinutes(FRESH_MINUTES).isBefore(now) || asOf.toLocalTime().isAfter(close.minusMinutes(LATE_MINUTES))) return null
        val k = kindOf(r.dayKind) ?: return null
        return Call(m, asOf, k, r.last)
    }

    /** [log] with [new] added (said again the same day: once), old ones dropped. */
    fun add(log: List<Call>, new: Call?, today: LocalDate): List<Call> {
        if (new == null || log.any { it.key == new.key }) return prune(log, today)
        return prune(log + new, today)
    }

    fun prune(log: List<Call>, today: LocalDate): List<Call> =
        log.filter { !it.day.isBefore(today.minusDays(KEEP_DAYS)) }.sortedBy { it.at }.takeLast(KEEP)

    /** [log] with [m]'s calls on finished sessions scored on [bars] (1-minute, any days), [now] the time now. */
    fun settle(log: List<Call>, m: Market, bars: List<Candle>, now: LocalDateTime): List<Call> {
        val close = m.close ?: return log
        if (log.none { it.market == m && !it.settled }) return log
        return log.map { c ->
            if (c.market != m || c.settled) return@map c
            val end = c.day.atTime(close)
            if (now.isBefore(end.plusMinutes(SETTLE_AFTER))) return@map c          // the session is not over yet
            val upTo = bars.filter { !it.t.toLocalDate().isAfter(c.day) }
            val last = upTo.lastOrNull { it.t.toLocalDate() == c.day }
            if (last == null || last.t.isBefore(end.minusMinutes(END_SLACK))) {
                // No candles to the close on the phone: never guessed. Waits a day for late candles.
                return@map if (now.isAfter(end.plusDays(1))) c.copy(verdict = Verdict.NONE) else c
            }
            val r = Structure.read(m, upTo, c.day) ?: return@map c.copy(verdict = Verdict.NONE)
            val ended = kindOf(r.dayKind)
            c.copy(verdict = when (ended) { null -> Verdict.MIXED; c.kind -> Verdict.HELD; else -> Verdict.TURNED }, ended = ended, close = r.last)
        }
    }

    // ---- the question ----

    private fun norm(text: String) = " " + text.lowercase(Locale.ENGLISH).replace("’", "'").replace("'", " ").replace(rx("[^a-z0-9 ]"), " ")
        .replace(rx("\\s+"), " ").trim() + " "

    private const val WHO = "(your|ur|jarvis|jarvis s|jarvis s own|your own|tumhare|tumhari|aapke|aapki|tera|tere)"
    private const val WHAT = "(trend|trend and range|trend or range|range|structure|day type|day kind|trend day|range day)"
    private const val CALLS = "(reads?|calls?|views?)"
    private val ASKED = Regex(
        // "how often were your trend reads right", "how accurate are your structure reads", "how good have your trend calls been"
        " how (often|accurate|good|right|reliable|well) (are|is|were|was|have|has) $WHO $WHAT $CALLS( been)?( right| correct| accurate| on the mark| working)? |" +
        " how (often|many times) (are|were|have been|is|was) $WHO $WHAT $CALLS (right|correct|wrong|accurate) |" +
        " how often (do|did) $WHO $WHAT $CALLS (hold|come right|turn out right|go right|work|play out) |" +
        // "were your trend reads right this month", "did your trend calls hold", "did your structure reads hold up"
        " (were|are|did|do|have) $WHO $WHAT $CALLS (right|correct|accurate|hold|holds|held|hold up|come true|work out|play out|turn out right) |" +
        // "your trend read record", "jarvis trend call accuracy", "trend read hit rate"
        " $WHO $WHAT (read|call|reads|calls) (record|accuracy|hit rate|track record|score|scorecard) |" +
        " $WHAT (read|call|reads|calls) (record|accuracy|hit rate|track record|scorecard) |" +
        " (score|check|grade|mark) $WHO $WHAT $CALLS |" +
        // Hinglish: "tumhare trend reads kitne sahi the", "trend calls kaise rahe", "trend read kitni baar sahi nikla"
        " $WHO? ?$WHAT $CALLS (kitne|kitni baar|kitni bar|kaise) (sahi|theek|thik)? ?(the|thi|tha|rahe|rahi|nikle|nikla|nikli|hue|hain) |" +
        " $WHAT $CALLS (kaise|kitne sahi) (rahe|the|nikle|hain) ")

    fun asked(text: String): Boolean = ASKED.containsMatchIn(norm(text))

    /** The span asked of: today, this week, this month, last month, or everything kept. */
    fun span(text: String): Span {
        val t = norm(text)
        return when {
            rx(" (last|previous|pichle|pichhle) (month|mahine|mahina) ").containsMatchIn(t) -> Span.LAST_MONTH
            rx(" (this month|month|mahine|is mahine|this mahina) ").containsMatchIn(t) -> Span.MONTH
            rx(" (this week|week|hafte|is hafte) ").containsMatchIn(t) -> Span.WEEK
            rx(" (today|aaj) ").containsMatchIn(t) -> Span.TODAY
            else -> Span.ALL
        }
    }

    private fun inSpan(c: Call, s: Span, today: LocalDate): Boolean = when (s) {
        Span.TODAY -> c.day == today
        Span.WEEK -> !c.day.isBefore(today.minusDays((today.dayOfWeek.value - 1).toLong()))
        Span.MONTH -> c.day.year == today.year && c.day.month == today.month
        Span.LAST_MONTH -> today.minusMonths(1).let { c.day.year == it.year && c.day.month == it.month }
        Span.ALL -> true
    }

    private fun hm(t: LocalDateTime) = "%02d:%02d".format(Locale.ENGLISH, t.hour, t.minute)
    private fun date(d: LocalDate) = "${d.dayOfMonth} ${d.month.name.take(3).lowercase(Locale.ENGLISH).replaceFirstChar { it.uppercase() }}"
    private fun pts(x: Double) = "%+,.0f".format(Locale.ENGLISH, x)

    /** A share of the scored calls, said "3 of 5". */
    private fun of(cs: List<Call>) = "${cs.count { it.verdict == Verdict.HELD }} of ${cs.size}"

    /**
     * "How often were your trend reads right?": his calls in [span] on [markets] (empty: all) - held, turned, in between at
     * the close; by kind and by the time of day; the turned ones named. [today] the date now. Facts about his own record.
     */
    fun say(log: List<Call>, markets: List<Market>, span: Span, today: LocalDate): String {
        val mine = log.filter { (markets.isEmpty() || it.market in markets) && inSpan(it, span, today) }
        val where = if (markets.isEmpty()) "" else " on " + markets.joinToString(" and ") { it.label }
        val scored = mine.filter { it.verdict == Verdict.HELD || it.verdict == Verdict.TURNED || it.verdict == Verdict.MIXED }
        val waiting = mine.count { !it.settled }
        val unchecked = mine.count { it.verdict == Verdict.NONE }
        val wait = if (waiting == 0) "" else " $waiting read${if (waiting == 1) " is" else "s are"} waiting for the close to be scored."
        if (scored.isEmpty()) return "I have no trend or range reads$where ${span.words} scored yet, Boss." + wait +
            " Each time I tell you in session that a day is trend-like or range-like, I note the index, the minute and the kind " +
            "(never your words), and after the close I check how the day actually ended by the same measure."
        val held = scored.count { it.verdict == Verdict.HELD }
        val turned = scored.filter { it.verdict == Verdict.TURNED }
        val mixed = scored.count { it.verdict == Verdict.MIXED }
        val parts = ArrayList<String>()
        parts += "My trend and range reads$where ${span.words}, Boss: $held of ${scored.size} matched how the day ended" +
            (if (turned.isNotEmpty()) ", ${turned.size} turned into another kind of day" else "") +
            (if (mixed > 0) ", $mixed ended in between - neither a clear trend nor a clear range" else "") + "." +
            (if (scored.size < FEW) " Too few to go by yet." else "")
        val byKind = Kind.values().mapNotNull { k -> scored.filter { it.kind == k }.takeIf { it.isNotEmpty() }?.let { "${k.label} ${of(it)}" } }
        if (byKind.size > 1) parts += "By what I called: " + byKind.joinToString("; ") + "."
        if (scored.size >= FEW) {
            val early = scored.filter { it.at.toLocalTime().isBefore(java.time.LocalTime.of(11, 0)) }
            val mid = scored.filter { !it.at.toLocalTime().isBefore(java.time.LocalTime.of(11, 0)) && it.at.toLocalTime().isBefore(java.time.LocalTime.of(13, 30)) }
            val late = scored.filter { !it.at.toLocalTime().isBefore(java.time.LocalTime.of(13, 30)) }
            val byTime = listOf("before 11:00" to early, "11:00 to 13:30" to mid, "after 13:30" to late).filter { it.second.isNotEmpty() }
            if (byTime.size > 1) parts += "By when I said it: " + byTime.joinToString("; ") { "${it.first} ${of(it.second)}" } + "."
        }
        // The turned ones named, newest first: the read, when, how the day ended, and how far the index went after it.
        val trendTurned = turned.sortedByDescending { it.at }.take(3)
        if (trendTurned.isNotEmpty()) parts += "Turned: " + trendTurned.joinToString("; ") { c ->
            "${c.market.label} ${date(c.day)}, read ${c.kind.label} at ${hm(c.at)}, ended ${c.ended?.label ?: "in between"}" +
                (c.close?.let { " (${pts(it - c.price)} points from my read to the close)" } ?: "")
        } + "."
        val tail = ArrayList<String>()
        if (unchecked > 0) tail += "$unchecked could not be checked (no candles to the close on the phone)."
        if (waiting > 0) tail += wait.trim()
        tail += "Scored by the same measure I read with (the net move from the open as a share of the day's range); reads in the " +
            "last $LATE_MINUTES minutes and \"in between\" reads are not counted, and a read said again the same day counts once. " +
            "My record, not a forecast."
        return (parts + tail).joinToString(" ")
    }

    // ---- saved as plain text: one call a line (market, minute, kind, price, verdict, how the day ended, the close) ----

    fun save(log: List<Call>): String = log.joinToString("\n") { c ->
        listOf(c.market.name, c.at, c.kind.name, "%.2f".format(Locale.ENGLISH, c.price), c.verdict?.name ?: "-", c.ended?.name ?: "-",
            c.close?.let { "%.2f".format(Locale.ENGLISH, it) } ?: "-").joinToString("|")
    }

    fun load(text: String): List<Call> = text.lineSequence().mapNotNull { line ->
        runCatching {
            val p = line.split('|')
            Call(Market.valueOf(p[0]), LocalDateTime.parse(p[1]), Kind.valueOf(p[2]), p[3].toDouble(),
                p[4].takeIf { it != "-" }?.let { Verdict.valueOf(it) }, p[5].takeIf { it != "-" }?.let { Kind.valueOf(it) },
                p[6].takeIf { it != "-" }?.toDouble())
        }.getOrNull()
    }.toList()
}
