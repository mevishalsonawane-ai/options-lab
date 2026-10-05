package com.optionslab.ira

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * What Boss does with his bots after they lose (learning round 16, 2026-10-05): which ones he keeps armed through a
 * losing day and which he switches off, and after how many losing days in a row - said as his own record ("you usually
 * disarm Range Fade after two losing days in a row - 3 of the last 4 times").
 *
 * The app notes, on each trading day in market hours, each bot's switch (armed at any time that day, or not), and whether
 * it ended the day with a loss or a profit ([Day]; names, switches and signs only - never an amount, a symbol or an order).
 * [events] walks those days: a losing day followed (within [MAX_GAP_DAYS]) by a day the phone saw is one [Event] - kept
 * armed, or switched off - at the losing run's length then (1, 2, or [RUN_CAP] and more). [habits] reads each bot's
 * events as a [Habit]: switched off at a run length most times ([USUALLY] of at least [MIN_AT] times), kept through
 * losing days most times, or no clear habit yet.
 *
 * A RECORD only, and talk only: it answers "do I usually disarm my bots after losses?" ([asked], [say]) and is shown in
 * the Learnings ledger ([Learnings.Area.ARM_HABITS]). Nothing learned here arms, disarms, stops or suggests a switch by
 * itself, and no behaviour of Jarvis's changes - so there is nothing to undo. What stays armed is Boss's call. Boss's
 * account, so never said on a locked phone (the app checks). Pure: the app keeps the days.
 */
object ArmHabits {
    /** Days kept at most. */
    const val KEEP = 180
    /** A next day further than this many calendar days on (the phone was off) is not "the next day". */
    const val MAX_GAP_DAYS = 6L
    /** Losing runs this long and longer are counted together. */
    const val RUN_CAP = 3
    /** A run length needs this many outcomes to be a habit. */
    const val MIN_AT = 2
    /** This share of outcomes one way is "usually". */
    const val USUALLY = 2.0 / 3
    /** Kept through losses: at least this many outcomes in all. */
    const val MIN_KEPT = 3
    /** Bots named at most in one answer. */
    const val SHOW = 4

    /** One trading day the phone saw: every bot it knew, those armed at any time, and those that ended the day down or up. */
    data class Day(val date: LocalDate, val known: Set<String>, val armed: Set<String>,
                   val lost: Set<String> = emptySet(), val won: Set<String> = emptySet())

    /** The days noted, oldest first. */
    data class Log(val days: List<Day> = emptyList())

    /**
     * One pass in market hours on [date]: [switches] each bot's switch now (true armed), [lost] / [won] the bots whose
     * closed trades today net a loss / a profit so far. Armed at any time that day stays armed; the day's sign is the
     * latest. Returns the same [log] when nothing changed (so nothing need be saved).
     */
    fun note(log: Log, date: LocalDate, switches: Map<String, Boolean>, lost: Set<String>, won: Set<String>): Log {
        val names = switches.keys
        val was = log.days.lastOrNull()?.takeIf { it.date == date }
        val day = if (was == null) Day(date, names, switches.filterValues { it }.keys + lost + won, lost, won)
        else Day(date, was.known + names, was.armed + switches.filterValues { it }.keys + lost + won,
            (was.lost - won) + lost, (was.won - lost) + won)
        if (day == was) return log
        val rest = log.days.filter { it.date != date && it.date.isBefore(date) }
        return Log((rest + day).sortedBy { it.date }.takeLast(KEEP))
    }

    /** After a losing run of [run] days (capped at [RUN_CAP]) on [on], the next day the bot was [kept] armed or not. */
    data class Event(val bot: String, val run: Int, val kept: Boolean, val on: LocalDate)

    /** Every losing day of every bot with the next day's switch, oldest first. */
    fun events(log: Log): List<Event> {
        val days = log.days.sortedBy { it.date }
        val out = ArrayList<Event>()
        val bots = days.flatMap { it.known }.toSortedSet()
        for (b in bots) {
            var run = 0
            for (i in days.indices) {
                val d = days[i]
                if (b !in d.known) { run = 0; continue }
                if (b !in d.armed) { run = 0; continue }
                when (b) { in d.lost -> run++; in d.won -> run = 0 }
                if (b !in d.lost) continue
                val next = days.getOrNull(i + 1) ?: continue
                if (ChronoUnit.DAYS.between(d.date, next.date) > MAX_GAP_DAYS || b !in next.known) continue
                out += Event(b, minOf(run, RUN_CAP), b in next.armed, d.date)
            }
        }
        return out.sortedBy { it.on }
    }

    /** One run length's outcomes: switched off [off] of [times]. */
    data class At(val run: Int, val times: Int, val off: Int) { val kept: Int get() = times - off }

    enum class Kind { DISARMS, KEEPS, MIXED, FEW }

    /** What Boss does with [bot] after losses: [at] the run length he usually switches it off at (DISARMS). */
    data class Habit(val bot: String, val kind: Kind, val runs: List<At>, val at: At?, val newest: LocalDate) {
        val times: Int get() = runs.sumOf { it.times }
        val off: Int get() = runs.sumOf { it.off }
    }

    fun habits(log: Log): List<Habit> = events(log).groupBy { it.bot }.map { (bot, es) ->
        val runs = es.groupBy { it.run }.toSortedMap().map { (r, xs) -> At(r, xs.size, xs.count { !it.kept }) }
        val at = runs.firstOrNull { it.times >= MIN_AT && it.off >= it.times * USUALLY }
        val times = runs.sumOf { it.times }; val off = runs.sumOf { it.off }
        val kind = when {
            at != null -> Kind.DISARMS
            times >= MIN_KEPT && times - off >= times * USUALLY -> Kind.KEEPS
            times >= MIN_KEPT -> Kind.MIXED
            else -> Kind.FEW
        }
        Habit(bot, kind, runs, at, es.maxOf { it.on })
    }.sortedWith(compareBy<Habit> { it.kind.ordinal }.thenByDescending { it.times })

    // ---- words -------------------------------------------------------------------------------------------------

    private fun days(n: Int) = when (n) { 1 -> "a losing day"; RUN_CAP -> "${word(n)} or more losing days in a row"; else -> "${word(n)} losing days in a row" }
    private fun word(n: Int) = when (n) { 1 -> "one"; 2 -> "two"; 3 -> "three"; else -> n.toString() }
    private fun times(n: Int) = if (n == 1) "once" else if (n == 2) "twice" else "$n times"

    /** The habit in one sentence, without "Boss". */
    fun line(h: Habit): String = when (h.kind) {
        Kind.DISARMS -> {
            val a = h.at!!
            val before = h.runs.filter { it.run < a.run && it.kept > 0 }
            "you usually switch ${h.bot} off after ${days(a.run)} (${a.off} of ${a.times} times)" +
                (if (before.isNotEmpty()) ", and keep it armed before that (${before.sumOf { it.kept }} of ${before.sumOf { it.times }} times)" else "")
        }
        Kind.KEEPS -> "you keep ${h.bot} armed through losing days (kept ${h.times - h.off} of ${h.times} times" +
            (h.runs.maxOfOrNull { it.run }?.takeIf { it > 1 }?.let { r -> ", even after ${days(r)}" } ?: "") + ")"
        Kind.MIXED -> "no clear habit with ${h.bot} yet: after a loss you kept it armed ${times(h.times - h.off)} and switched it off ${times(h.off)}"
        Kind.FEW -> "${h.bot} has lost on ${times(h.times)} with a day after it - too few to call a habit"
    }

    // ---- asked -------------------------------------------------------------------------------------------------

    private fun norm(text: String) = " " + spacedWords(text.lowercase().replace("'", "").replace("’", "")) + " "

    private const val LEAD = "^ (hey |ok |okay )?(jarvis )?(so )?(boss )?(tell me )?"
    private const val TAIL = "( please| boss| jarvis)* $"
    private const val OFF = "(disarm|switch off|turn off|switch (it|them|[a-z0-9 ]{1,24}) off|turn (it|them|[a-z0-9 ]{1,24}) off|stop|pause|give up on|drop|band kar)"
    private const val LOSS = "(a |two |2 |three |3 |some )?(loss|losses|losing day|losing days|losing streak|bad day|bad days|red day|red days|loss making day|losing run)"
    private val ASKED = rx(
        LEAD + "(do|did|would) i (usually |normally |always |often |tend to )?$OFF( my| the)? [a-z0-9 ]{0,30}?(after|when (it|they) (has |have )?(had )?|following) $LOSS" + TAIL + "|" +
        LEAD + "(do|did) i (usually |normally |always )?(keep|leave) ([a-z0-9 ]{1,30}) (armed|on|running)( (after|through|despite|even after) $LOSS)?" + TAIL + "|" +
        LEAD + "(when|after how many (losing )?days|after how many losses) do i (usually |normally )?$OFF( my| the)? ?[a-z0-9 ]{0,30}?" + TAIL + "|" +
        LEAD + "what do i (usually |normally )?do (with|to) (my )?(bots|strategies|arms|algos|[a-z0-9 ]{1,24}) (after|when (they|it) (lose|loses|lost)|following) ?$LOSS?" + TAIL + "|" +
        LEAD + "what do i (usually |normally )?do after $LOSS( with (my )?(bots|strategies|arms|algos|[a-z0-9 ]{1,24}))?" + TAIL + "|" +
        LEAD + "which (bots|strategies|arms|algos) do i (usually |normally |always )?(keep armed|keep on|keep running|$OFF)( after $LOSS)?" + TAIL + "|" +
        LEAD + "(what are |show me |whats )?(my )?(arming|disarming|bot|bots) (habits?|record|pattern|patterns)( after (losses|losing days))?" + TAIL + "|" +
        LEAD + "how do i (usually )?(treat|handle) (my )?(bots|strategies|arms|algos|[a-z0-9 ]{1,24}) (after|when they lose|through) ?$LOSS?" + TAIL + "|" +
        LEAD + "do i (give up on|switch off|disarm|drop) (my )?(bots|strategies|arms|algos) too (fast|soon|early|quickly)" + TAIL + "|" +
        // "How quickly do I disarm a losing bot?" (routing round 13: read as his strategies' list)
        LEAD + "how (quickly|fast|soon) do i (usually |normally )?$OFF (a |my |the )?(losing )?(bot|bots|strategy|strategies|arm|arms|algo|algos)( after $LOSS)?" + TAIL + "|" +
        "^ (jarvis )?(kya )?(main |mai |mein )?(loss|losses|loss wale din) ke baad (main |mai |mein )?([a-z0-9 ]{1,24} )?(bot|bots|strategy) (band|off) karta (hoon|hu|hun)( kya)?" + TAIL + "|" +
        "^ (jarvis )?(loss|losses) ke baad (main |mai |mein )?(bots|bot|strategies) ke saath kya karta (hoon|hu|hun)" + TAIL)

    /** "Do I usually disarm my bots after losses?", "which bots do I keep armed?", "my arming habits". */
    fun asked(text: String): Boolean = askedKept.of(text) { askedFresh(text) }

    /** The last words read (speed round 10: the hub reads them as said, then again in its order; [Kept], pure). */
    private val askedKept = Kept<Boolean>(64)

    private fun askedFresh(text: String): Boolean = ASKED.containsMatchIn(norm(text))

    const val LOCKED = "Unlock the phone for your bots' record, Boss."
    const val ONLY_RECORD = "Your record only, Boss: nothing I learn here arms, disarms or stops anything - what stays armed is your call."

    /** The answer, about the bots named in [question] when it names any of them, else all. */
    fun say(log: Log, question: String): String {
        val all = habits(log)
        val q = norm(question)
        val named = all.filter { q.contains(norm(it.bot)) }
        val hs = named.ifEmpty { all }
        if (hs.isEmpty()) return "I haven't seen any of your bots lose a day and then seen the next day yet, Boss - I note each bot's switch and " +
            "whether it ended the day up or down (never an amount), and once a few losing days have passed I can say what you usually do after them. $ONLY_RECORD"
        val clear = hs.filter { it.kind == Kind.DISARMS || it.kind == Kind.KEEPS }
        val shown = (if (named.isEmpty() && clear.isNotEmpty()) clear else hs).take(SHOW)
        val rest = hs.size - shown.size
        val lines = shown.joinToString("; ") { line(it) }
        return "Boss, from the days this phone saw: $lines" + (if (rest > 0) "; and $rest more with too few losing days or no clear habit" else "") +
            ". $ONLY_RECORD"
    }

    /** The ledger's lines: only the bots with a clear habit. */
    fun shown(log: Log): List<Habit> = habits(log).filter { it.kind == Kind.DISARMS || it.kind == Kind.KEEPS }
    fun ledgerWhat(h: Habit): String = line(h).replaceFirstChar { it.uppercase() }
    fun ledgerWhy(h: Habit): String = "from your bots' switches on the days after a loss (signs only, never amounts) - your record, it arms or disarms nothing"
}
