package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

/**
 * The morning outlook checked against the close (Jarvis reasoning, round 21). The 09:00 check says each index's outlook
 * in one line ([Outlook.brief]: the trend read, the range a usual day spans from India VIX, the pivot). Those three are
 * noted when the check makes them ([call], [made]) - the numbers only - and at the 15:45 wrap-up set against what the
 * day did ([check]), owned honestly in a sentence per index:
 *
 *  - the range: did the close stay inside the usual-day range (and did the day's high or low go past it);
 *  - the direction: a "rising" read held when the index closed above the previous close, a "falling" one when below;
 *    "no clear direction" is no call and is never counted;
 *  - the pivot: did the close stay on the side of the pivot the day opened on.
 *
 * A running record is kept ([Entry], at most [KEEP]) and "how good are your morning outlooks?" ([asked], [say]) answers
 * with counts only over the last [WINDOW] checked sessions of each index - never a verdict, never advice; nothing acts.
 * Market data only (no account), so it is said on a locked phone too. Pure: the app keeps the log.
 */
object OutlookCheck {
    /** The indices the morning check gives an outlook for. */
    val MARKETS = listOf(Market.NIFTY, Market.BANKNIFTY)
    /** Entries kept at most (two indices, about six months of sessions). */
    const val KEEP = 240
    /** The record reads at most this many checked sessions of each index. */
    const val WINDOW = 60
    /** The day is checked only once its candles reach this time (a session cut short by a failed feed is not graded). */
    val FULL_BY: LocalTime = LocalTime.of(15, 0)

    /** The direction read in the morning: [UP] "rising", [DOWN] "falling", [NONE] "no clear direction" (no call). */
    enum class Lean(val word: String) { UP("rising"), DOWN("falling"), NONE("no clear direction") }

    /**
     * One index's morning outlook for [day] and, once checked, how the day went. [base]: the previous close the outlook
     * was made from; [lo]/[hi]: the usual-day range (null without India VIX); [pivot]: the previous session's pivot.
     * [open]/[high]/[low]/[close]: the day's, null until checked.
     */
    data class Entry(
        val day: LocalDate, val market: Market, val base: Double, val lo: Double?, val hi: Double?, val lean: Lean, val pivot: Double,
        val open: Double? = null, val high: Double? = null, val low: Double? = null, val close: Double? = null,
    ) {
        val checked: Boolean get() = close != null
        /** Close inside the usual-day range, or null (not checked, or no range was set). */
        val rangeHit: Boolean? get() { val c = close ?: return null; val a = lo ?: return null; val b = hi ?: return null; return c in a..b }
        /** The direction read held, or null (not checked, or no direction was called). */
        val leanHit: Boolean? get() { val c = close ?: return null; return when (lean) { Lean.UP -> c > base; Lean.DOWN -> c < base; Lean.NONE -> null } }
        /** The close stayed on the pivot's side the day opened on, or null (not checked). */
        val pivotHit: Boolean? get() { val c = close ?: return null; val o = open ?: return null; return (o >= pivot) == (c >= pivot) }
    }

    // ---- the morning: the outlook's numbers, as [Outlook.brief] gives them ---------------------------------------

    /**
     * [m]'s outlook for [day] from its 1-minute candles [bars] (sessions before [day] only, so it is the morning's view
     * whenever it is read) and India VIX [vix] (0: unknown, then no range). The same trend, range and pivot as
     * [Outlook.brief]. Null without two earlier sessions, or for an index the morning check does not give.
     */
    fun call(m: Market, bars: List<Candle>, vix: Double, day: LocalDate): Entry? {
        if (m !in MARKETS) return null
        val days = bars.filter { it.t.toLocalDate().isBefore(day) }.groupBy { it.t.toLocalDate() }.toSortedMap()
        if (days.size < 2) return null
        val closes = days.values.map { it.last().c }
        val recent = closes.takeLast(6)
        val moves = recent.size - 1
        val ups = recent.zipWithNext().count { (a, b) -> b > a }
        val lean = when {
            moves >= 3 && ups >= moves - 1 -> Lean.UP
            moves >= 3 && ups <= 1 -> Lean.DOWN
            else -> Lean.NONE
        }
        val last = closes.last()
        val d = days.getValue(days.lastKey())
        val pv = Pivots.of(d.maxOf { it.h }, d.minOf { it.l }, d.last().c).p
        val p = if (vix > 0) ExpectedRange.points(last, vix) else null
        return Entry(day, m, last, p?.let { last - it }, p?.let { last + it }, lean, pv)
    }

    /** [log] with the morning's [entries] noted: one per index and day; a day already checked is never replaced. */
    fun made(log: List<Entry>, entries: List<Entry>): List<Entry> {
        if (entries.isEmpty()) return log
        val keys = entries.map { it.day to it.market }.toSet()
        val keep = log.filter { (it.day to it.market) !in keys || it.checked }
        val add = entries.filter { e -> keep.none { it.day == e.day && it.market == e.market } }
        return (keep + add).sortedWith(compareBy({ it.day }, { it.market.ordinal })).takeLast(KEEP)
    }

    // ---- the close: the outlook against the day -------------------------------------------------------------------

    /**
     * [log] with [day]'s unchecked outlooks set against that day's candles ([bars] by index), and the wrap-up's words
     * for what was checked now (null: nothing to check - no outlook noted, or the day's candles do not reach [FULL_BY]).
     */
    fun check(log: List<Entry>, bars: Map<Market, List<Candle>>, day: LocalDate): Pair<List<Entry>, String?> {
        val now = ArrayList<Entry>()
        val next = log.map { e ->
            if (e.day != day || e.checked) return@map e
            val d = bars[e.market].orEmpty().filter { it.t.toLocalDate() == day }
            if (d.isEmpty() || d.last().t.toLocalTime().isBefore(FULL_BY)) return@map e
            e.copy(open = d.first().o, high = d.maxOf { it.h }, low = d.minOf { it.l }, close = d.last().c).also { now += it }
        }
        if (now.isEmpty()) return next to null
        val record = tally(next)
        return next to ("Checking my 09:00 outlook against the close, Boss: " + now.joinToString(" ") { line(it) } +
            (if (record.isEmpty()) "" else " My record so far: $record."))
    }

    /** One index's outlook against its day, in a sentence. */
    fun line(e: Entry): String {
        val c = e.close ?: return ""
        val chg = (c - e.base) / e.base * 100
        val parts = ArrayList<String>()
        val lo = e.lo; val hi = e.hi
        parts += if (lo != null && hi != null) {
            val past = listOfNotNull(e.high?.takeIf { it > hi }?.let { "the day's high of ${n0(it)}" }, e.low?.takeIf { it < lo }?.let { "the day's low of ${n0(it)}" })
            (if (e.rangeHit == true) "inside the usual-day range of ${n0(lo)}-${n0(hi)}" else "outside the usual-day range of ${n0(lo)}-${n0(hi)}") +
                (if (e.rangeHit == true && past.isNotEmpty()) ", though ${past.joinToString(" and ")} went past it" else "")
        } else "with no range set this morning (India VIX was not in)"
        parts += when (e.leanHit) {
            true -> "the \"${e.lean.word}\" read held"
            false -> "the \"${e.lean.word}\" read did not hold"
            null -> "no direction was called (${e.lean.word})"
        }
        val o = e.open
        if (o != null) {
            val side = if (o >= e.pivot) "above" else "below"
            parts += if (e.pivotHit == true) "it opened $side the ${n0(e.pivot)} pivot and closed $side it"
                else "it opened $side the ${n0(e.pivot)} pivot and closed ${if (side == "above") "below" else "above"} it"
        }
        return "${e.market.label} closed at ${n0(c)} (%+.2f%%): ".format(Locale.ENGLISH, chg) + parts.joinToString("; ") + "."
    }

    /** "Nifty inside the range 14 of 20, direction 7 of 12, pivot side 11 of 20; BankNifty ...", or "" with nothing checked. */
    fun tally(log: List<Entry>): String = MARKETS.mapNotNull { m ->
        val done = log.filter { it.market == m && it.checked }.sortedBy { it.day }.takeLast(WINDOW)
        if (done.isEmpty()) return@mapNotNull null
        val r = done.mapNotNull { it.rangeHit }; val l = done.mapNotNull { it.leanHit }; val p = done.mapNotNull { it.pivotHit }
        val bits = listOfNotNull(
            r.takeIf { it.isNotEmpty() }?.let { "close inside the range ${it.count { x -> x }} of ${it.size}" },
            l.takeIf { it.isNotEmpty() }?.let { "direction read held ${it.count { x -> x }} of ${it.size}" },
            p.takeIf { it.isNotEmpty() }?.let { "pivot side held ${it.count { x -> x }} of ${it.size}" })
        if (bits.isEmpty()) null else "${m.label} ${bits.joinToString(", ")}"
    }.joinToString("; ")

    // ---- "how good are your morning outlooks?" --------------------------------------------------------------------

    private fun norm(text: String) = Spaced.joined(text)

    private const val LEAD = "^ (ok |okay |so |jarvis |boss |hey |acha |accha |haan |tell me |be honest |honestly )*"
    private const val TAIL = "( so far| lately| recently| today| this week| this month| really| actually| boss| jarvis| please| yaar| kya| na)* $"
    private const val YOUR = "(your|jarviss|tumhara|tumhare|aapka|aapke|tera|tere)"
    private const val WHAT = "(morning |09 00 |9 am |subah ka |subah ke )?(outlook|outlooks|outlook calls?|morning calls?|morning reads?|morning views?)"
    private val ASK = rx(
        LEAD + "how (good|accurate|right|reliable|useful|correct|often right) (is|are|have been|were|was) $YOUR $WHAT" + TAIL + "|" +
        LEAD + "(is|are|were|was) $YOUR $WHAT (any good|accurate|reliable|right|correct|useful|worth anything)" + TAIL + "|" +
        LEAD + "(did|has|have) $YOUR (todays |this mornings )?$WHAT (hold|held|hold up|held up|work|worked|come true|come good|play out|played out|go right|gone right|turn out right)" + TAIL + "|" +
        LEAD + "(what is |whats |show me |give me )?$YOUR $WHAT (record|hit rate|track record|score|scorecard|tally)" + TAIL + "|" +
        LEAD + "how (often|many times) (is|are|was|were|have been) $YOUR $WHAT (right|correct|accurate)" + TAIL + "|" +
        LEAD + "(how did|how was) $YOUR (todays |this mornings )?$WHAT( do| turn out| go)?" + TAIL + "|" +
        LEAD + "(check|grade|score) $YOUR $WHAT( against the close)?" + TAIL + "|" +
        LEAD + "$YOUR (subah ka |subah ke |morning )?(outlook|outlooks) (kitna|kitne) (sahi|theek|accurate|sach) (hai|hain|hota hai|hote hain|tha|the|nikla|nikle|nikalta hai|nikalte hain)" + TAIL + "|" +
        LEAD + "(aaj ka |aaj subah ka )?subah ka outlook (sahi|theek|accurate) (tha|nikla|raha)" + TAIL)

    /** "How good are your morning outlooks?", "did your outlook hold today?", "tumhara outlook kitna sahi hota hai". */
    fun asked(text: String): Boolean = askedKept.of(text) { ASK.containsMatchIn(norm(text)) }

    private val askedKept = Kept<Boolean>(64)

    /**
     * The record in counts: today's check first when made ([today]), then each index over its last [WINDOW] checked
     * sessions. Never a verdict or advice.
     */
    fun say(log: List<Entry>, today: LocalDate): String {
        val done = log.filter { it.checked }
        val todays = done.filter { it.day == today }
        val pending = log.any { it.day == today && !it.checked }
        if (done.isEmpty()) return "I have not checked a morning outlook against a close yet, Boss: each index's outlook is noted at the 09:00 check " +
            "and set against the close at the 15:45 wrap-up." + (if (pending) " Today's is noted and is checked at 15:45." else "")
        val parts = ArrayList<String>()
        if (todays.isNotEmpty()) parts += "Today's outlook against the close, Boss: " + todays.joinToString(" ") { line(it) }
        else if (pending) parts += "Today's outlook is noted, Boss, and is checked at the 15:45 wrap-up."
        val since = done.groupBy { it.market }.values.minOfOrNull { l -> l.sortedBy { it.day }.takeLast(WINDOW).first().day }
        val sessions = done.map { it.day }.distinct().sorted().takeLast(WINDOW).size
        parts += (if (parts.isEmpty()) "Boss, my" else "My") + " morning outlooks against the close over the last $sessions checked session${if (sessions == 1) "" else "s"}" +
            (since?.let { " (since $it)" } ?: "") + ": ${tally(done)}."
        parts += "The usual-day range is set to hold about two days in three; a \"no clear direction\" morning is no call and is not counted. Counts only."
        return parts.joinToString(" ")
    }

    // ---- the log on the phone ------------------------------------------------------------------------------------

    /** One entry a line: day;market;base;lo;hi;lean;pivot;open;high;low;close (empty for null). */
    fun encode(log: List<Entry>): String = log.joinToString("\n") { e ->
        listOf(e.day.toString(), e.market.name, e.base.toString(), e.lo?.toString() ?: "", e.hi?.toString() ?: "", e.lean.name, e.pivot.toString(),
            e.open?.toString() ?: "", e.high?.toString() ?: "", e.low?.toString() ?: "", e.close?.toString() ?: "").joinToString(";")
    }

    /** [encode]'s lines back; an unreadable line is dropped. */
    fun decode(s: String): List<Entry> = s.lines().filter { it.isNotBlank() }.mapNotNull { line ->
        runCatching {
            val f = line.split(";")
            fun d(i: Int) = f.getOrNull(i)?.takeIf { it.isNotEmpty() }?.toDouble()
            Entry(LocalDate.parse(f[0]), Market.valueOf(f[1]), f[2].toDouble(), d(3), d(4), Lean.valueOf(f[5]), f[6].toDouble(), d(7), d(8), d(9), d(10))
        }.getOrNull()
    }

    private fun n0(x: Double) = "%,.0f".format(Locale.ENGLISH, x)
}
