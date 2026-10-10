package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Jarvis learning the times of day Boss checks his P&L (learning round 31, 2026-10-06) - "around 11:00 and 14:30" - and
 * reading his account ahead just before them, so the answer at that time is as of now, not "as of" two minutes ago.
 *
 * From what the app already keeps: his routine log ([Routine.Seen], the question's key and minute only, never his words).
 * Nothing new is recorded; only the time Boss last asked to undo it is kept ([Log]). Of his own P&L asks
 * ([Routine.account]'s key, not those just after a loss of his) in market hours on weekdays in the last [WINDOW_DAYS] days
 * (after the undo), a half hour in which he asked on at least [MIN_DAYS] days, and on at least [SHARE] of the days he asked
 * for his P&L at all, is learned - at most [MAX_TIMES] of them, the time being the middle of each day's first ask in it, to
 * five minutes ([learned]).
 *
 * What it changes: from [LEAD_MIN] minutes before to [AFTER_MIN] minutes after each such time, while the market is open
 * and Jarvis is listening (his listening loop is what reads ahead; nothing runs when he is not), the account is read ahead
 * on every 30 s pass rather than about every 2 minutes with the screen off and nothing held ([AccountWarmPace]'s quiet
 * pace) - [quiet]. With the screen on it is read on every pass already, so nothing changes then. Display only: the same
 * read the loop makes anyway, kept in memory for the next question; never a word said unasked, never a figure changed,
 * never an order, an arm, a setting or a reminder. Nothing learned acts or places anything. Market shut: nothing
 * ([OffHoursWarmPace] keeps its own pace). Never in IraGoldAlgo; Jarvis only.
 *
 * "When do I usually check my P&L?" / "do you read my account ahead?" names the times and why ([Request.WHEN]; his habit,
 * so on an unlocked phone only); "stop getting my P&L ready" / "forget when I check my P&L" ([UNDO], [Request.RESET])
 * undoes it and counts afresh from then - a habit's undo, never a STOP of an arm ([Commands]' habit undo). Shown in the
 * Learnings ledger with that undo, and reset by "undo everything you learned this week". Pure: the app keeps the log and
 * the undo time.
 */
object CheckTimes {
    /** Only the last this many days count (the routine log keeps eight weeks). */
    const val WINDOW_DAYS = 20L
    /** In that half hour on at least this many days... */
    const val MIN_DAYS = 5
    /** ...and on at least this share of the days he asked for his P&L at all. */
    const val SHARE = 0.5
    /** At most this many times a day are learned. */
    const val MAX_TIMES = 3
    /** The account is read on every pass from this many minutes before a learned time... */
    const val LEAD_MIN = 2
    /** ...to this many after it (his asks spread a little around the middle). */
    const val AFTER_MIN = 8

    /** Market hours, by the minute of the day (09:15 to 15:30). */
    const val OPEN_MIN = 9 * 60 + 15
    const val CLOSE_MIN = 15 * 60 + 30

    /** The routine log's key for Boss's P&L ([Routine.key]). */
    const val KEY = "ACCOUNT|pnl"

    const val UNDO = "stop getting my P&L ready"
    const val LOCKED = "Unlock the phone for that, Boss."
    const val RESET_LOCKED = "Done, Boss: I'll read your account ahead at my usual pace, and my count starts afresh from now."

    /** When Boss last asked to undo it: his asks up to then no longer count. */
    data class Log(val resetAt: LocalDateTime? = null)

    /** A time learned: [minute] of the day, asked in its half hour on [days] of the [ofDays] days he asked for his P&L, the newest on [newest]. */
    data class Record(val minute: Int, val days: Int, val ofDays: Int, val newest: LocalDate) {
        val clock: String get() = clock(minute)
        val phrase: String get() = "around $clock"
        fun say(): String = "around $clock on $days of the $ofDays days you asked for your P&L"
    }

    /** "11:00", "14:30". */
    fun clock(minute: Int): String = "%02d:%02d".format(java.util.Locale.ENGLISH, minute / 60, minute % 60)

    private fun minuteOf(t: LocalDateTime) = t.hour * 60 + t.minute

    private fun inMarket(t: LocalDateTime): Boolean =
        t.dayOfWeek != DayOfWeek.SATURDAY && t.dayOfWeek != DayOfWeek.SUNDAY && minuteOf(t) in OPEN_MIN until CLOSE_MIN

    /** The times learned at [today] from [log] (the routine log), earliest first; empty: the usual pace all day. */
    fun learned(log: List<Routine.Seen>, undo: Log, today: LocalDate): List<Record> {
        val from = today.minusDays(WINDOW_DAYS - 1)
        val reset = undo.resetAt
        val asks = log.filter { s ->
            s.key == KEY && !s.afterLoss && inMarket(s.at) && !s.at.toLocalDate().isBefore(from) && !s.at.toLocalDate().isAfter(today) &&
                (reset == null || s.at.isAfter(reset))
        }
        val ofDays = asks.map { it.at.toLocalDate() }.distinct().size
        if (ofDays < MIN_DAYS) return emptyList()
        val out = ArrayList<Record>()
        var left = asks
        while (out.size < MAX_TIMES && left.isNotEmpty()) {
            // The half hour (two neighbouring quarters) asked in on the most days.
            val best = (OPEN_MIN / 15 until CLOSE_MIN / 15).map { b -> left.filter { minuteOf(it.at) / 15 in b..b + 1 } }
                .maxByOrNull { s -> s.map { it.at.toLocalDate() }.distinct().size } ?: break
            val days = best.map { it.at.toLocalDate() }.distinct()
            if (days.size < MIN_DAYS || days.size < SHARE * ofDays) break
            val firsts = best.groupBy { it.at.toLocalDate() }.values.map { d -> d.minOf { minuteOf(it.at) } }.sorted()
            out += Record(firsts[firsts.size / 2] / 5 * 5, days.size, ofDays, days.max())
            left = left - best.toSet()
        }
        return out.sortedBy { it.minute }
    }

    /** Is [now] within a learned time's span ([LEAD_MIN] before to [AFTER_MIN] after), in market hours? */
    fun near(times: List<Record>, now: LocalDateTime): Boolean {
        if (!inMarket(now)) return false
        val m = minuteOf(now)
        return times.any { m in (it.minute - LEAD_MIN)..(it.minute + AFTER_MIN) }
    }

    /**
     * The listening loop's [AccountWarmPace] quiet flag with the habit applied: [quiet] (screen off, nothing held or armed)
     * is lifted only while the market is [open] and [now] is near a learned time - then the account is read ahead on every
     * pass. Never quieter than asked: a pass that was not quiet stays so. Reads only.
     */
    fun quiet(quiet: Boolean, open: Boolean, times: List<Record>, now: LocalDateTime): Boolean =
        quiet && !(open && near(times, now))

    /** "Stop getting my P&L ready" at [now]: his asks up to now no longer count. */
    fun reset(now: LocalDateTime): Log = Log(now)

    // ---- asked -----------------------------------------------------------------------------------------------------

    enum class Request { WHEN, RESET }

    private const val LEAD = "^ (hey |ok |okay )?(jarvis )?(so )?(please )?(can you |could you |would you )?(tell me )?"
    private const val TAIL = "( please| boss| jarvis| now| again| from now on| for me| every day| each day)* $"
    private const val PNL = "(p l|pnl|p and l|profit and loss|mtm|m2m)"
    private const val MY = "(my |the )"
    private const val ACCOUNT = "($MY?$PNL|${MY}account|${MY}positions?|${MY}p l and positions|${MY}pnl and positions)"
    private const val AHEAD = "(ahead|in advance|before i ask|early|beforehand|ahead of time)"
    private const val READY = "(ready|prepared|loaded|warmed up|warm)"
    private const val HCHECK = "(check|dekh|dekhta|dekhte|check kar|check karta|check karte|poochta|puchta|poochte|puchte)"

    private val WHEN = rx(LEAD + "(when|what time|at what time|what times|at what times) do i (usually |normally |mostly |generally )?(check|look at|ask (you )?(for|about)|ask) $ACCOUNT( during the day| in the day| each day| every day)?" + TAIL + "|" +
        LEAD + "(when|what time|what times) (do|did) you (read|get|fetch|load|check|warm up|prepare) $ACCOUNT $AHEAD" + TAIL + "|" +
        LEAD + "(do|did|why do|why did) you (read|get|fetch|load|warm up|prepare) $ACCOUNT $AHEAD" + TAIL + "|" +
        LEAD + "(why|how) (is|was) $ACCOUNT (already |always )?$READY( so fast| so quickly| before i asked)?" + TAIL + "|" +
        LEAD + "(do|did|why do|why did) you (get|keep) $ACCOUNT $READY( for me)?( before i ask| in advance| ahead)?" + TAIL + "|" +
        LEAD + "(main |mai |me )?$PNL (kab|kitne baje) $HCHECK( karta hoon| karta hu| hoon| hu)?" + TAIL + "|" +
        LEAD + "(main |mai |me )?(kab|kitne baje) $PNL $HCHECK( karta hoon| karta hu| hoon| hu)?" + TAIL + "|" +
        // Understanding round 30: "p&l kab dekhta hu main", "kab dekhta hoon pnl", "main kab check karta hoon apna p&l".
        LEAD + "(main |mai |me )?(mera |apna )?$PNL (kab|kitne baje) $HCHECK( karta hoon| karta hu| hoon| hu)?( main| mai)?" + TAIL + "|" +
        LEAD + "(main |mai |me )?(kab|kitne baje) $HCHECK( karta hoon| karta hu| hoon| hu)? (mera |apna )?$PNL( main| mai)?" + TAIL)

    /**
     * Only a clear undo: "stop getting my P&L ready", "stop reading my account ahead", "don't prepare my P&L in advance",
     * "forget when I check my P&L", "p&l pehle se mat padho". Never a hush, an arm's stop or a P&L question.
     */
    private val RESET = rx(LEAD + "(stop|dont|do not|no more) (getting|get|keeping|keep|preparing|prepare|loading|load|warming up|warm up) $ACCOUNT $READY( in advance| ahead| before i ask)?" + TAIL + "|" +
        LEAD + "(stop|dont|do not|no more) (reading|read|fetching|fetch|loading|load|preparing|prepare|checking|check|warming up|warm up|getting|get) $ACCOUNT $AHEAD" + TAIL + "|" +
        LEAD + "(stop|dont|do not|no more) (reading|read|fetching|fetch|loading|load|preparing|prepare|warming up|warm up|getting|get) $AHEAD $ACCOUNT" + TAIL + "|" +
        LEAD + "forget (when|what time|what times|the times?) i (usually |normally )?(check|look at|ask (you )?(for|about)|ask) $ACCOUNT" + TAIL + "|" +
        LEAD + "forget (the |your )(usual )?$PNL (check )?times?" + TAIL + "|" +
        LEAD + "(read|get) $ACCOUNT (only )?when i ask( for it)?" + TAIL + "|" +
        LEAD + "(mera |meri )?($PNL|account|positions?) pehle se (mat|na) (padho|lo|nikalo|laao|lao|check karo|tayyar karo|ready karo)" + TAIL)

    /** "When do I usually check my P&L?" or "stop getting my P&L ready", else null. */
    fun asked(text: String): Request? = askedKept.of(text) { askedFresh(text) }

    private val askedKept = Kept<Request?>(64)

    /** Every kept reading forgotten (tests). */
    internal fun forgetAsked() = askedKept.clear()

    private fun askedFresh(text: String): Request? {
        val t = Spaced.joined(text)
        return when {
            RESET.containsMatchIn(t) -> Request.RESET
            WHEN.containsMatchIn(t) -> Request.WHEN
            else -> null
        }
    }

    const val ONLY_READ = "I only read your account a little earlier and keep it for your question: nothing is said unasked, and nothing ever places, changes or closes a trade."

    private fun list(rs: List<Record>): String {
        val names = rs.map { it.clock }
        return if (names.size <= 1) names.joinToString("") else names.dropLast(1).joinToString(", ") + " and " + names.last()
    }

    /** "When do I usually check my P&L?". */
    fun say(rs: List<Record>): String =
        if (rs.isEmpty()) "I don't see a usual time yet, Boss. If you ask for your P&L in the same half hour on $MIN_DAYS days or more - " +
            "at least half the days you ask for it - I'll read your account ahead just before then while the market is open, so it's ready."
        else "Boss, you usually check your P&L " + rs.joinToString("; ") { it.say() } + " in the last $WINDOW_DAYS days - so while the market is " +
            "open and I'm listening, I read your account ahead from $LEAD_MIN minutes before ${list(rs)}. $ONLY_READ Say \"$UNDO\" to undo it."

    /** "Stop getting my P&L ready". */
    fun sayReset(rs: List<Record>): String =
        if (rs.isEmpty()) "I wasn't reading your account ahead at any set time, Boss. I'll start my count afresh from now."
        else "Done, Boss: I'll read your account ahead at my usual pace, not before ${list(rs)}, and my count starts afresh from now."

    /** The ledger's lines for [r]. */
    fun ledgerWhat(r: Record): String = "your account read ahead just before ${r.clock}, when you usually check your P&L"
    fun ledgerWhy(r: Record): String = "${r.say()} in the last $WINDOW_DAYS days; market hours only, read ahead only - nothing said or done"
}
