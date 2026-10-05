package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * Jarvis learning which items of his 09:00 morning check Boss fixes and which he leaves as they are (learning round 17,
 * 2026-10-05). Each trading morning the check's failing items are kept by their key only ([key]; never the words, an
 * amount or an account figure), and the next morning decides each one: gone then ([Outcome.FIXED]) or still failing
 * ([Outcome.LEFT]). An item Boss has left as it was at least [MIN_LEFT] times, in at least [SHARE] of its outcomes in
 * the last [WINDOW_DAYS] days ([Record.brief]), is - on a morning it is still failing from the morning before - no longer
 * read out item by item aloud: the spoken check names it in one short tail ("and one you usually leave as it is - the
 * holiday list - is in the chat") instead ([aloud]).
 *
 * Only ever said SHORTER, never more, never left uncounted ("3 things need you" stays 3), and only by the voice: the
 * chat note, the pop-up and the notification keep every item as written. Only the items in [LEARNED] are ever shortened;
 * a safety item - Zerodha not logged in, the kill switch, the static IP, the relay, the guards, the live prices, the
 * Zerodha connection, the option contracts - or any item it does not know is always read out in full. An item fixed the
 * morning before (a fresh failure) is read out in full too. "Which morning items do you skip?" names them and what Boss
 * fixes ([Request.WHICH]); "say the whole morning check again" ([UNDO], [Request.RESET]) starts the count afresh. Shown
 * in the Learnings ledger with that undo and reset by "undo everything you learned this week". Nothing learned here
 * acts: it fixes, changes, arms or skips nothing in the check itself. Pure: the app keeps the log.
 */
object MorningSense {
    /** Only the mornings in the last this many days count. */
    const val WINDOW_DAYS = 30L
    /** The next morning within this many days decides a failure (a weekend and a holiday between). */
    const val GAP_DAYS = 5L
    /** Left as it was at least this many times before it is said briefly. */
    const val MIN_LEFT = 3
    /** And in at least this share of its outcomes. */
    const val SHARE = 0.75
    /** Mornings kept at most. */
    const val KEEP = 60

    /** The only items ever said briefly (key to how they are named): none of them is a safety item. */
    val LEARNED: Map<String, String> = linkedMapOf(
        "HOLIDAYS" to "the holiday list",
        "INSTRUMENTS" to "Zerodha's instrument list",
        "BOTS" to "the bots against the ones you usually arm",
        "SELF_CHECK" to "my own self-check",
    )

    /** Named when Boss asks: never said briefly, whatever he does. */
    const val NEVER = "Zerodha not logged in, the kill switch, the static IP, the relay, the guards, the live prices, the Zerodha connection and the option contracts"

    const val UNDO = "say the whole morning check again"

    /** The morning check's line prefixes (as DailyReports.morning writes them, the mark taken off) to their keys. */
    private val PREFIXES: List<Pair<String, String>> = listOf(
        "Zerodha not logged in" to "LOGIN", "Zerodha logged in" to "LOGIN",
        "NSE holiday list" to "HOLIDAYS",
        "Option contracts" to "CONTRACTS", "Today's option contracts" to "CONTRACTS",
        "Zerodha instrument list" to "INSTRUMENTS",
        "Kill switch" to "KILL",
        "Zerodha did not answer" to "CONNECTION", "Zerodha connection" to "CONNECTION",
        "Not on your registered static IP" to "STATIC_IP", "On your registered static IP" to "STATIC_IP",
        "Static IP:" to "STATIC_IP",
        "Relay server:" to "RELAY",
        "Guards:" to "GUARDS",
        "Bots:" to "BOTS",
        "No live prices" to "PRICES", "Live prices" to "PRICES",
        "Self-check:" to "SELF_CHECK",
        "Margin:" to "MARGIN", "Overnight positions:" to "CARRIED",
    )

    /** The key of one morning-check [line] ("✗ ", "✓ " or "• " taken off), or null for one it does not know (always said in full). */
    fun key(line: String): String? {
        val t = line.trim().removePrefix("✗ ").removePrefix("✓ ").removePrefix("• ").trim()
        return PREFIXES.firstOrNull { t.startsWith(it.first, ignoreCase = true) }?.second
    }

    fun learned(key: String?): Boolean = key != null && key in LEARNED

    /** One trading morning: the [LEARNED] items failing at its check. */
    data class Morning(val day: LocalDate, val failed: Set<String>)

    /** The mornings kept, and when Boss last asked for the whole check again (no morning before it counts). */
    data class Log(val mornings: List<Morning> = emptyList(), val resetAt: LocalDateTime? = null)

    /** [log] with [day]'s check noted from its failing [lines] (a retried check replaces the day's; past [KEEP] dropped). */
    fun noted(log: Log, day: LocalDate, lines: List<String>): Log {
        val failed = lines.mapNotNull { key(it) }.filter { learned(it) }.toSet()
        val kept = log.mornings.filter { it.day != day && it.day.isAfter(day.minusDays(WINDOW_DAYS + GAP_DAYS)) } + Morning(day, failed)
        return log.copy(mornings = kept.sortedBy { it.day }.takeLast(KEEP))
    }

    /** "Say the whole morning check again": nothing before [now] counts any more. */
    fun reset(log: Log, now: LocalDateTime): Log = log.copy(resetAt = now)

    enum class Outcome { FIXED, LEFT }

    /** One item's record: left as it was and fixed by the next morning, the newest morning it was left. */
    data class Record(val key: String, val left: Int, val fixed: Int, val newest: LocalDate?) {
        val decided: Int get() = left + fixed
        /** Said briefly when still failing from the morning before. */
        val brief: Boolean get() = key in LEARNED && left >= MIN_LEFT && left >= SHARE * decided
        val phrase: String get() = LEARNED[key] ?: key.lowercase()
        fun say(): String = "$phrase (fixed by the next morning $fixed of the $decided times it failed)"
    }

    private fun counted(log: Log, today: LocalDate): List<Morning> {
        val from = today.minusDays(WINDOW_DAYS)
        val reset = log.resetAt
        return log.mornings.filter { it.day.isAfter(from) && !it.day.isAfter(today) && (reset == null || it.day.atTime(9, 0).isAfter(reset)) }
            .sortedBy { it.day }
    }

    /** Every [LEARNED] item that failed and was decided by a next morning, in the window (since the reset), most left first. */
    fun records(log: Log, today: LocalDate): List<Record> {
        val ms = counted(log, today)
        val outcomes = ArrayList<Triple<String, Outcome, LocalDate>>()
        ms.zipWithNext().forEach { (a, b) ->
            if (ChronoUnit.DAYS.between(a.day, b.day) in 1..GAP_DAYS)
                a.failed.filter { learned(it) }.forEach { k -> outcomes += Triple(k, if (k in b.failed) Outcome.LEFT else Outcome.FIXED, b.day) }
        }
        return outcomes.groupBy { it.first }.map { (k, xs) ->
            Record(k, xs.count { it.second == Outcome.LEFT }, xs.count { it.second == Outcome.FIXED },
                xs.filter { it.second == Outcome.LEFT }.maxOfOrNull { it.third })
        }.sortedWith(compareByDescending<Record> { it.brief }.thenByDescending { it.left }.thenBy { it.key })
    }

    /** The items Boss usually leaves as they are (said briefly when still failing from the morning before). */
    fun usuallyLeft(log: Log, today: LocalDate): List<Record> = records(log, today).filter { it.brief }

    /**
     * The keys said briefly at [today]'s check: items Boss usually leaves that failed on the last morning before today
     * (within [GAP_DAYS]). Worked out before today's morning is noted; a fresh failure is never among them.
     */
    fun briefToday(log: Log, today: LocalDate): Set<String> {
        val left = usuallyLeft(log, today).map { it.key }.toSet()
        if (left.isEmpty()) return emptySet()
        val before = counted(log, today).lastOrNull { it.day.isBefore(today) } ?: return emptySet()
        if (ChronoUnit.DAYS.between(before.day, today) > GAP_DAYS) return emptySet()
        return left.filter { it in before.failed }.toSet()
    }

    private fun plural(n: Int) = "$n thing${if (n == 1) "" else "s"}"

    /**
     * The spoken "N things need you" part of the morning check: [failing] (the failing lines to be said, mark taken off)
     * read out one by one, except those whose [key] is in [brief] - named together in one short tail. [bad] is the
     * check's own count and is said as it is. With nothing brief, exactly the words the check always said.
     */
    fun aloud(bad: Int, failing: List<String>, brief: Set<String>): String {
        val (short, full) = failing.partition { l -> key(l)?.let { learned(it) && it in brief } == true }
        // The check's own words (its "need" whatever the count), so nothing changes until something is learned.
        val head = "${plural(bad)} need you"
        if (short.isEmpty()) return "$head: " + failing.joinToString(". ") + "."
        val names = short.mapNotNull { key(it) }.distinct().map { LEARNED.getValue(it) }
        val list = if (names.size == 1) names[0] else names.dropLast(1).joinToString(", ") + " and " + names.last()
        val one = short.size == 1
        val tail = (if (one) "one you usually leave as it is" else "${short.size} you usually leave as they are") + " - $list - " + (if (one) "is" else "are") + " in the chat."
        return if (full.isEmpty()) "$head: $tail" else "$head: " + full.joinToString(". ") + ". And " + tail
    }

    // ---- asked -----------------------------------------------------------------------------------------------------

    enum class Request { WHICH, RESET }

    private fun norm(text: String) = " " + text.lowercase().replace("'", "").replace("’", "")
        .replace(rx("[^a-z0-9 ]"), " ").replace(rx("\\s+"), " ").trim() + " "

    private const val LEAD = "^ (hey |ok |okay )?(jarvis )?(so )?(please )?(can you |could you |would you )?(tell me )?"
    private const val TAIL = "( please| boss| jarvis| now| aloud| again| today| from now on| every day| every morning)* $"
    private const val CHECK = "(the |my |your )?(morning|9 ?am|9 00|09 00|nine oclock) (check|checklist|check up|checkup|report|briefing)"
    private const val ITEMS = "(items?|things?|checks?|warnings?|problems?|parts?)"
    private const val SKIP = "(skip|skipping|leave out|leaving out|shorten|shortening|cut|cutting|say briefly|saying briefly|keep short|keeping short|" +
        "not say|not saying|not read( out)?|not reading( out)?|hold back|holding back)"

    private val WHICH = rx(LEAD + "(which|what) $ITEMS? ?(do you|are you|have you) $SKIP( (from|in|of|out of) $CHECK)" + TAIL + "|" +
        LEAD + "(which|what) (of the |of my )?$CHECK $ITEMS (do you|are you) $SKIP" + TAIL + "|" +
        LEAD + "(which|what) (of the |of my )?$CHECK $ITEMS do i (usually |actually )?(fix|ignore|leave|leave as (it is|they are)|not fix)" + TAIL + "|" +
        LEAD + "(why|how come) (is|was) $CHECK (so )?(short|shorter|brief|briefer|quicker)" + TAIL + "|" +
        LEAD + "(what|which) (do i|have i) (usually )?(fix|ignore|leave) (in|from) $CHECK" + TAIL + "|" +
        LEAD + "morning (check|report) (mein|me) (kya|kaun se|kaunse|kon se) (chhod|chod|chhot|skip kar)(te|ti) ho" + TAIL)
    private val RESET = rx(LEAD + "(say|read|give me|tell me|speak) (the |my |your )?(whole|full|entire|complete) (morning|9 ?am|9 00|09 00|nine oclock) (check|checklist|check up|checkup|report|briefing)" + TAIL + "|" +
        LEAD + "(say|read|speak) (every|each|all the|all) (morning )?(check )?$ITEMS (in|of|at) $CHECK( out)?" + TAIL + "|" +
        LEAD + "(dont|do not|stop|no need to) $SKIP (any |anything |any items |items |things )?(in |from |of )?$CHECK( any ?more)?" + TAIL + "|" +
        LEAD + "(poora|pura) morning (check|report) (bolo|sunao|batao)" + TAIL)

    /** "Which morning items do you skip?" or "say the whole morning check again", else null. */
    fun asked(text: String): Request? {
        val t = norm(text)
        return when {
            RESET.containsMatchIn(t) -> Request.RESET
            WHICH.containsMatchIn(t) -> Request.WHICH
            else -> null
        }
    }

    const val ONLY_VOICE = "Only my voice is shorter: the chat, the pop-up and the notification keep every item, the count of what needs you stays whole, and nothing I learn fixes or changes anything."

    /** "Which morning items do you skip?": the items said briefly, what Boss fixes, and what is never shortened. */
    fun say(log: Log, today: LocalDate): String {
        val all = records(log, today)
        val left = all.filter { it.brief }
        val fixes = all.filter { !it.brief && it.fixed > 0 }.sortedByDescending { it.fixed }
        val fixLine = if (fixes.isEmpty()) "" else " What you usually fix by the next morning: " + fixes.take(3).joinToString("; ") { it.say() } + "."
        if (left.isEmpty()) return "I read out every failing item of the 09:00 check aloud, Boss." + fixLine +
            " If you keep leaving one of the minor ones as it is, morning after morning, I'll name it in a few words instead - never a safety item ($NEVER)."
        return "Boss, in the spoken 09:00 check I name these in a few words when they are still failing from the morning before, because you usually leave them as they are: " +
            left.take(3).joinToString("; ") { it.say() } + ", in the last $WINDOW_DAYS days." + fixLine +
            " I always read out a safety item in full ($NEVER), and an item that was fixed the morning before. $ONLY_VOICE Say \"$UNDO\" to undo it."
    }

    /** "Say the whole morning check again". */
    fun sayReset(log: Log, today: LocalDate): String =
        if (usuallyLeft(log, today).isEmpty()) "I already read out every failing item of the morning check, Boss. I'll start my count afresh from now."
        else "Done, Boss: I'll read out every failing item of the 09:00 check again, and my count starts afresh from now."

    /** The ledger's lines for [r]. */
    fun ledgerWhat(r: Record): String = "${r.phrase}: named in a few words in the spoken 09:00 check when still failing from the morning before"
    fun ledgerWhy(r: Record): String = "you left it as it was ${r.left} of the ${r.decided} times it failed, in the last $WINDOW_DAYS days; " +
        "the chat keeps every item, safety items are always read in full"
}
