package com.optionslab.ira

import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Jarvis learning why Boss turns his trade ideas down (learning round 21, 2026-10-05). When Boss rejects a suggested
 * trade and says why - in the "no" itself ("no, too late in the day") or as his very next words within
 * [AFTER_MINUTES] minutes ("it's too late in the day") - only the reason's kind ([Reason]) and the time are noted: never
 * his words, never the idea, never his account.
 *
 * A reason he gave for at least [MIN_TIMES] rejections in the last [WINDOW_DAYS] days (since the last reset) is learned
 * ([learned]). The next idea Jarvis asks him about where that reason fits ([applies]: "too late in the day" from the
 * hour he first said it, "expiry day" on an expiry day, the rest always) then states it up front, in ONE line, before
 * the yes-or-no ([upFront]): "Before you answer, Boss: you turned down 3 of my ideas lately as too late in the day -
 * it's 14:40 now."
 *
 * Speech only: the idea, its gates, its size, its approval card and Boss's Approve or reject are exactly as before - the
 * line never holds an idea back, never answers for him and never arms, trades, switches or confirms anything. Said on
 * an unlocked phone only (the app checks). "Why do I turn down your ideas?" names the reasons ([Request.WHICH]);
 * "don't remind me why I turn your ideas down" ([UNDO], [Request.RESET]) undoes it and starts the count afresh. Shown in
 * the Learnings ledger with that undo and reset by "undo everything you learned this week". Pure: the app keeps the log.
 */
object TurnDowns {
    /** A reason counts when given for at least this many rejections... */
    const val MIN_TIMES = 2
    /** ...in the last this many days. */
    const val WINDOW_DAYS = 30L
    /** Boss's reason after a rejection counts within this many minutes of it (his very next words only). */
    const val AFTER_MINUTES = 3L
    /** Notes kept at most. */
    const val KEEP = 60
    /** A reason is at most this many words. */
    const val WORDS = 14

    const val UNDO = "don't remind me why I turn your ideas down"

    /** The kinds of reason Boss gives, as he says them ([said]: what the up-front line says, "as ..."). */
    enum class Reason(val key: String, val phrase: String, val said: String, pattern: String) {
        EXPIRY("expiry", "an expiry day", "it being an expiry day",
            "expiry (day|hai|ka din|wala din|today)|its (an )?expiry|on (an )?expiry|aaj expiry"),
        LATE("late", "too late in the day", "too late in the day",
            "(too|very|bahut|kaafi|way) late|late in the day|(market|it) (is )?(about to |going to )?(close|closing)|(markets|its) (about to |going to )?(close|closing)|" +
                "(near|nearly|close to) (the )?close|end of the day|der ho gayi|late ho (gaya|gayi)"),
        RISK("risk", "too risky", "too risky",
            "(too|very|bahut|kaafi|zyada) risky|too much risk|risk (is )?too (high|much|big)|zyada risk|too (big|expensive|costly)|" +
                "premium (is )?too (high|much)|(bahut )?(mehenga|mehnga)"),
        TREND("trend", "against the trend", "against the trend",
            "against the trend|trend (is )?against|wrong (side|direction)|trend ke (against|khilaf|ulta)|counter ?trend"),
        CHOPPY("choppy", "the market being choppy", "the market being choppy",
            "choppy|sideways|range ?bound|no (clear )?(direction|trend)"),
        ALREADY("already", "already having a position", "you already having a position",
            "already (have|holding|got|in)( a| one| my| the)?( position| trade| one)?|one is enough|pehle se (position|trade)"),
        NEWS_OLD("news", "the news being old", "the news being old",
            "old news|news is (old|stale)|(already )?priced in|purani (news|khabar)");

        internal val rx = Regex(" ($pattern) ")
    }

    /** One rejection's reason, and when ([Reason.key] only - never Boss's words). */
    data class Note(val at: LocalDateTime, val reason: String)

    /** The reasons noted, and when Boss last asked not to be reminded (nothing before it counts). */
    data class Log(val notes: List<Note> = emptyList(), val resetAt: LocalDateTime? = null)

    private fun norm(text: String) = " " + text.lowercase().replace("'", "").replace("’", "")
        .replace(rx("[^a-z0-9 ]"), " ").replace(rx("\\s+"), " ").trim() + " "

    private val ASKING = rx("^ (what|why|how|is|are|was|were|can|could|should|would|will|do|does|did|when|which|who|kya|kyun|kyu|kab|kaise) ")

    /**
     * The reason Boss gave in [text] ("no, too late in the day", "because it's expiry"), or null: a short statement only -
     * never a question, an order or a command.
     */
    fun reason(text: String): Reason? {
        if ('?' in text) return null
        val t = norm(text)
        if (t.isBlank() || t.trim().split(" ").size > WORDS || ASKING.containsMatchIn(t)) return null
        val p = runCatching { Ask.parse(text) }.getOrNull() ?: return null
        if (p.order != null || p.command != null) return null
        return Reason.entries.firstOrNull { it.rx.containsMatchIn(t) }
    }

    /** [log] with Boss's [reason] for a rejection at [at] noted. */
    fun heard(log: Log, reason: Reason, at: LocalDateTime): Log =
        log.copy(notes = (log.notes + Note(at, reason.key)).sortedBy { it.at }.takeLast(KEEP))

    /** "Don't remind me why I turn your ideas down": nothing before [now] counts any more. */
    fun reset(log: Log, now: LocalDateTime): Log = Log(emptyList(), now)

    /** Is a reason given at [rejectedAt] still taken from Boss's words at [now]? */
    fun fresh(rejectedAt: LocalDateTime, now: LocalDateTime): Boolean =
        !now.isBefore(rejectedAt) && !now.isAfter(rejectedAt.plusMinutes(AFTER_MINUTES))

    /** A learned reason: given [times] times in the window, the newest at [newest], the earliest time of day [earliest]. */
    data class Record(val reason: Reason, val times: Int, val newest: LocalDateTime, val earliest: LocalTime) {
        val phrase: String get() = reason.phrase
    }

    /** The reasons learned at [now], the most given first (then the newest). Empty until one is given [MIN_TIMES] times. */
    fun learned(log: Log, now: LocalDateTime): List<Record> {
        val from = now.minusDays(WINDOW_DAYS)
        val reset = log.resetAt
        return log.notes.filter { it.at.isAfter(from) && !it.at.isAfter(now) && (reset == null || it.at.isAfter(reset)) }
            .groupBy { it.reason }
            .mapNotNull { (k, xs) ->
                val r = Reason.entries.firstOrNull { it.key == k } ?: return@mapNotNull null
                if (xs.size < MIN_TIMES) null else Record(r, xs.size, xs.maxOf { it.at }, xs.minOf { it.at.toLocalTime() })
            }
            .sortedWith(compareByDescending<Record> { it.times }.thenByDescending { it.newest }.thenBy { it.reason.ordinal })
    }

    /**
     * Does [r] fit an idea asked about at [now] ([expiry]: today is an expiry day for its index)? "Too late in the day"
     * from the hour he first gave it; "an expiry day" on one; the rest always.
     */
    fun applies(r: Record, now: LocalDateTime, expiry: Boolean): Boolean = when (r.reason) {
        Reason.LATE -> !now.toLocalTime().isBefore(r.earliest.truncatedTo(ChronoUnit.HOURS))
        Reason.EXPIRY -> expiry
        else -> true
    }

    private val HM = DateTimeFormatter.ofPattern("HH:mm")

    /** The one line said before the yes-or-no for an idea at [now], or null when no learned reason fits. Words only. */
    fun upFront(log: Log, now: LocalDateTime, expiry: Boolean): String? {
        val r = learned(log, now).firstOrNull { applies(it, now, expiry) } ?: return null
        val tail = when (r.reason) {
            Reason.LATE -> " - it's ${now.format(HM)} now"
            Reason.EXPIRY -> " - today is one"
            else -> ""
        }
        return "Before you answer, Boss: you turned down ${r.times} of my ideas lately as ${r.reason.said}$tail."
    }

    /** Said when Boss's reason is noted. */
    fun noted(r: Reason): String = "Noted, Boss: ${r.phrase}. Nothing was placed."

    // ---- asked -----------------------------------------------------------------------------------------------------

    enum class Request { WHICH, RESET }

    private const val LEAD = "^ (hey |ok |okay )?(jarvis )?(so )?(please )?(can you |could you |would you )?(tell me )?"
    private const val TAIL = "( please| boss| jarvis| now| any ?more| from now on| again| up front| first)* $"
    private const val IDEAS = "(your |my |the |those |these )?(trade )?(ideas?|trades?|suggestions?|setups?|picks?)"
    private const val TURN = "(turn down|turned down|turning down|reject|rejected|rejecting|say no to|said no to|decline|declined|declining|pass on|passed on)"
    private const val TURN_ = "(turn|turned|turning|reject|rejected|say no to|said no to|decline|declined)"

    private val WHICH = rx(LEAD + "why (do|did) i (usually |normally |always |often |keep )?$TURN $IDEAS" + TAIL + "|" +
        LEAD + "why (do|did) i (usually |normally |always |often |keep )?(turn|turned|turning) $IDEAS down" + TAIL + "|" +
        LEAD + "(what|which) (reasons?|excuses?) do i (usually |normally |always |often )?(give|have|say)( you)?( for)?( $TURN)? $IDEAS( down)?" + TAIL + "|" +
        LEAD + "what (reason|reasons|line) (do|did) you (say|tell me|give)( me)? (up front|before i answer|first)( on| about| with)?( $IDEAS)?" + TAIL + "|" +
        LEAD + "why (did|do) you (remind me|tell me) (why|how) i $TURN_( it| that| them| $IDEAS)?( down)?" + TAIL + "|" +
        LEAD + "(main|mai) (tumhare|aapke|tumhara|aapka) (idea|ideas|trade|trades|suggestion) (kyun|kyu) (reject|mana) (karta|kar deta) (hoon|hu|hun)" + TAIL)
    private val RESET = rx(LEAD + "(dont|do not|no need to|quit|stop) (remind|reminding|tell|telling) me (why|what) i $TURN_( $IDEAS)?( down)?" + TAIL + "|" +
        LEAD + "(dont|do not|no need to|quit|stop) (remind me of|tell me|say|mention|telling me|saying) (my|your) reasons( for $TURN $IDEAS)?" + TAIL + "|" +
        LEAD + "(forget|unlearn) (why|the reasons?) i $TURN_( $IDEAS)?( down)?" + TAIL + "|" +
        LEAD + "(mere|meri|meray) (reasons?|wajah|karan) (mat|na) (yaad dilao|batao|bolo)" + TAIL)

    /** "Why do I turn down your ideas?" or "don't remind me why I turn your ideas down", else null. */
    fun asked(text: String): Request? {
        val t = norm(text)
        return when {
            RESET.containsMatchIn(t) -> Request.RESET
            WHICH.containsMatchIn(t) -> Request.WHICH
            else -> null
        }
    }

    /** "Why do I turn down your ideas?" on a locked phone: his own habits are said on an unlocked one only. */
    const val LOCKED = "Unlock the phone for the reasons you turn my ideas down for, Boss."

    const val ONLY_WORDS ="It is one line before I ask you: the idea, its checks and your Approve or reject are just as before, and nothing I learn acts."

    private fun named(rs: List<Record>): String = rs.map { "${it.phrase} (${it.times} times)" }.let {
        if (it.size <= 1) it.joinToString("") else it.dropLast(1).joinToString(", ") + " and " + it.last()
    }

    /** "Why do I turn down your ideas?". */
    fun say(log: Log, now: LocalDateTime): String {
        val rs = learned(log, now)
        if (rs.isEmpty()) return "I haven't learned why you turn my ideas down yet, Boss: I note the reason you give when you say no - its kind only - " +
            "and wait for the same one $MIN_TIMES times in $WINDOW_DAYS days. Until then I ask as I always did."
        return "Boss, in the last $WINDOW_DAYS days you turned my ideas down as ${named(rs)}. When one fits a new idea I say it up front, " +
            "before I ask. $ONLY_WORDS Say \"$UNDO\" to undo it."
    }

    /** "Don't remind me why I turn your ideas down". */
    fun sayReset(log: Log, now: LocalDateTime): String =
        if (learned(log, now).isEmpty()) "I don't remind you of your reasons, Boss. I'll start my count afresh from now."
        else "Done, Boss: I ask about my ideas as I used to, with no reason of yours said up front, and my count starts afresh from now."

    /** The ledger's lines for [r]. */
    fun ledgerWhat(r: Record): String = "${r.phrase}: said up front before I ask you about an idea it fits"
    fun ledgerWhy(r: Record): String = "you turned down ${r.times} of my ideas for it in the last $WINDOW_DAYS days; the idea and your answer are unchanged"
}
