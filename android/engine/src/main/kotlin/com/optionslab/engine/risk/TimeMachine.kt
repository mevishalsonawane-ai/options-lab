package com.optionslab.engine.risk

import com.optionslab.engine.orb.Arm
import com.optionslab.engine.orb.Bar
import com.optionslab.engine.orb.OrbParams
import com.optionslab.engine.orb.OrbRules
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The time machine (22 Oct 2026, borrowed from Jake Schincariol's day-trader skill, MIT): a reality check on an arm's entry,
 * done AFTER the order from the UNDERLYING's own past sessions. The entry is replayed [Ask.draws] times by bootstrap: a
 * random past session, the same time-of-day slot give or take [Ask.tolerance] bars, in at that bar's open, then the arm's
 * own stop and target in underlying points walked bar by bar with the stop checked BEFORE the target inside a bar (a bar
 * that touched both counts as the stop: the honest reading). Reported: how often the target came first, the stop first,
 * neither (the arm's own time exit or the session's end came first), against the break-even win rate 1 / (1 + R), and a
 * one-word verdict. When the arm has a replayable rule ([Rule]), the rule itself is also re-run once on each session
 * (in at the NEXT bar's open, never the signal bar's) and counted the same way ([Rerun]).
 *
 * Shadow only, by design: it decides nothing. Pure: no clock, no I/O, no orders; the caller reads the bars off the order
 * path and runs this in the background. Sessions dated [odds]'s today or later are never used (today's bars after the
 * entry would be a look-ahead; a stale store labelled with an old day is simply an old session). Bars whose day is not
 * their session's are a corrupt session and are left out.
 */
object TimeMachine {
    /** Draws per check: a phone's CPU in well under a second (the plugin's 10,000 on a PC). */
    const val DRAWS = 2_000
    /** The same time-of-day slot, give or take this many bars. */
    const val SLOT_TOLERANCE = 2
    /** Fewer past sessions than this: the odds are said with their sample size, but no verdict. */
    const val MIN_SESSIONS = 10
    /** The most sessions used (the newest): the plugin's 60 trading days. */
    const val KEEP_SESSIONS = 60
    /** The verdict needs this many decided draws (target or stop) at least. */
    const val MIN_DECIDED = 100
    /** The verdict's smallest margin either side of the break-even, in win-rate points. */
    const val MIN_MARGIN = 0.05

    enum class Verdict(val word: String) { EDGE("edge"), COIN_FLIP("coin flip"), AGAINST("against"), UNKNOWN("unknown") }

    /** One past session of the underlying: its bars, one interval, oldest first (sorted here anyway). */
    data class Session(val date: LocalDate, val bars: List<Bar>)

    /** Where a replayable rule fired on a session: the bar it decided on and the direction (+1 up, -1 down). */
    data class Signal(val bar: Int, val direction: Int)

    /** A rule re-run on one session's bars: its first signal, or null when it never fired. */
    fun interface Rule { fun signal(bars: List<Bar>): Signal? }

    /**
     * The entry to replay: [direction] +1 when the underlying must rise for the trade to win (a call bought, a future
     * bought), -1 when it must fall; [slot] the start of the bar the arm went in on; [stopPoints] and [targetPoints] in
     * underlying points (an arm with premium stops converts them with its delta first, or says so); [barMinutes] the bars'
     * interval; [exitAt] the arm's own time exit (null: the session's end); [seed] fixed by the caller, so a check can be
     * reproduced.
     */
    data class Ask(
        val direction: Int,
        val slot: LocalTime,
        val stopPoints: Double,
        val targetPoints: Double,
        val barMinutes: Int,
        val exitAt: LocalTime? = null,
        val draws: Int = DRAWS,
        val tolerance: Int = SLOT_TOLERANCE,
        val seed: Long = 0L,
    )

    /** The rule re-run once on each usable session. */
    data class Rerun(val sessions: Int, val signals: Int, val targetFirst: Int, val stopFirst: Int, val neither: Int) {
        fun line(): String = if (signals == 0) "the rule itself fired on none of the $sessions sessions"
            else "the rule itself fired on $signals of $sessions sessions: target first $targetFirst, stop first $stopFirst, neither $neither"
    }

    data class Odds(
        /** Draws asked for, and those that found a bar at the slot (a short session has none at 15:00). */
        val draws: Int,
        val used: Int,
        /** Usable past sessions, and how many distinct ones the draws landed on. */
        val sessions: Int,
        val sessionsHit: Int,
        val targetFirst: Int,
        val stopFirst: Int,
        val neither: Int,
        /** Target over stop, and the break-even win rate 1 / (1 + R). NaN when R cannot be told. */
        val r: Double,
        val breakEven: Double,
        val verdict: Verdict,
        /** Why there is no verdict, or a caveat (null: none). */
        val why: String?,
        val rerun: Rerun?,
        val slot: LocalTime,
        val tolerance: Int,
    ) {
        val targetPct: Double get() = pct(targetFirst)
        val stopPct: Double get() = pct(stopFirst)
        val neitherPct: Double get() = pct(neither)
        /** The win rate among the decided draws (target or stop), what the break-even is compared with. NaN with none decided. */
        val decidedWinRate: Double get() = (targetFirst + stopFirst).let { if (it == 0) Double.NaN else targetFirst.toDouble() / it }
        private fun pct(n: Int): Double = if (used == 0) 0.0 else 100.0 * n / used

        /** One plain line for a ticket, the timeline and Jarvis. */
        fun line(): String {
            val head = "Time machine (${verdict.word})"
            if (used == 0) return "$head: ${why ?: "no past sessions to replay"}"
            val be = if (breakEven.isNaN()) "break-even not known" else "break-even ${pctText(breakEven * 100)}% (R ${"%.2f".format(r)})"
            val body = "target first ${pctText(targetPct)}%, stop first ${pctText(stopPct)}%, neither ${pctText(neitherPct)}%" +
                " over ${"%,d".format(used)} draws from $sessionsHit of $sessions sessions at ${hhmm(slot)} ±$tolerance bars; $be"
            val tail = listOfNotNull(why, rerun?.line()).joinToString("; ").let { if (it.isEmpty()) "" else "; $it" }
            return "$head: $body$tail."
        }
    }

    private fun pctText(x: Double): String = if (x.isNaN()) "?" else x.roundToInt().toString()
    private fun hhmm(t: LocalTime): String = "%02d:%02d".format(t.hour, t.minute)

    /** The sessions usable for [today]: dated before it, bars on their own day, deduplicated by date, the newest [keep]. */
    fun usable(sessions: List<Session>, today: LocalDate, keep: Int = KEEP_SESSIONS): List<Session> =
        sessions.asSequence()
            .filter { it.date.isBefore(today) && it.bars.isNotEmpty() && it.bars.all { b -> b.start.toLocalDate() == it.date } }
            .map { Session(it.date, it.bars.sortedBy { b -> b.start }) }
            .groupBy { it.date }.map { it.value.last() }
            .sortedBy { it.date }.takeLast(keep)

    /** What happened after an entry at [entry] on [bars] from index [from]: +1 the target came first, -1 the stop, 0 neither. */
    fun walk(bars: List<Bar>, from: Int, entry: Double, ask: Ask): Int {
        val stop = if (ask.direction > 0) entry - ask.stopPoints else entry + ask.stopPoints
        val target = if (ask.direction > 0) entry + ask.targetPoints else entry - ask.targetPoints
        for (j in from until bars.size) {
            val b = bars[j]
            if (ask.exitAt != null && !b.start.toLocalTime().isBefore(ask.exitAt)) return 0
            if (ask.direction > 0) {
                if (b.low <= stop) return -1          // the stop is checked before the target inside a bar
                if (b.high >= target) return 1
            } else {
                if (b.high >= stop) return -1
                if (b.low <= target) return 1
            }
        }
        return 0
    }

    /** The bootstrap odds of [ask] from [sessions] for [today]; [rule]: the arm's own rule to re-run too, when it has one. */
    fun odds(sessions: List<Session>, ask: Ask, today: LocalDate, rule: Rule? = null): Odds {
        val past = usable(sessions, today)
        val r = if (ask.stopPoints > 0 && ask.targetPoints > 0 && ask.stopPoints.isFinite() && ask.targetPoints.isFinite()) ask.targetPoints / ask.stopPoints else Double.NaN
        val breakEven = if (r.isNaN()) Double.NaN else 1.0 / (1.0 + r)
        fun none(why: String) = Odds(ask.draws, 0, past.size, 0, 0, 0, 0, r, breakEven, Verdict.UNKNOWN, why, null, ask.slot, ask.tolerance)
        if (r.isNaN()) return none("stop or target is not a positive number of points, so R cannot be told")
        if (past.isEmpty()) return none("no past sessions of the underlying on the phone")
        if (ask.draws <= 0 || ask.barMinutes <= 0) return none("nothing to draw")

        val rng = Random(ask.seed)
        var used = 0; var target = 0; var stop = 0; var neither = 0
        val hit = HashSet<LocalDate>()
        repeat(ask.draws) {
            val s = past[rng.nextInt(past.size)]
            val off = rng.nextInt(-ask.tolerance, ask.tolerance + 1)
            val slotMinute = ask.slot.toSecondOfDay() / 60 + off * ask.barMinutes
            // A slot before midnight or past it: no bar there, as a session that ended early has none.
            if (slotMinute < 0 || slotMinute >= 24 * 60) return@repeat
            val at = LocalTime.of(slotMinute / 60, slotMinute % 60)
            val i = s.bars.indexOfFirst { it.start.toLocalTime() == at }
            if (i < 0) return@repeat
            used++; hit += s.date
            when (walk(s.bars, i, s.bars[i].open, ask)) { 1 -> target++; -1 -> stop++; else -> neither++ }
        }
        val rerun = rule?.let { rerun(past, ask, it) }
        val decided = target + stop
        val verdict: Verdict
        var why: String? = null
        if (used == 0) { verdict = Verdict.UNKNOWN; why = "no past session has a bar at ${hhmm(ask.slot)} ±${ask.tolerance} bars" }
        else if (past.size < MIN_SESSIONS) { verdict = Verdict.UNKNOWN; why = "only ${past.size} past sessions (${MIN_SESSIONS} needed for a verdict)" }
        else if (decided < MIN_DECIDED) { verdict = Verdict.UNKNOWN; why = "only $decided draws reached the stop or the target" }
        else {
            // The sample behind the odds is the sessions, not the draws: the margin is two standard errors of the
            // break-even rate over the distinct sessions drawn, never under five points.
            val margin = maxOf(MIN_MARGIN, 2.0 * sqrt(breakEven * (1 - breakEven) / hit.size))
            val win = target.toDouble() / decided
            verdict = when {
                win >= breakEven + margin -> Verdict.EDGE
                win <= breakEven - margin -> Verdict.AGAINST
                else -> Verdict.COIN_FLIP
            }
            if (neither > decided) why = "most draws reached neither, so the time exit decides more than the stop or the target"
        }
        return Odds(ask.draws, used, past.size, hit.size, target, stop, neither, r, breakEven, verdict, why, rerun, ask.slot, ask.tolerance)
    }

    private fun rerun(past: List<Session>, ask: Ask, rule: Rule): Rerun {
        var signals = 0; var target = 0; var stop = 0; var neither = 0
        for (s in past) {
            val sig = runCatching { rule.signal(s.bars) }.getOrNull() ?: continue
            // In at the next bar's open, never the signal bar's (the look-ahead that once faked a profit in Replay).
            val from = sig.bar + 1
            if (sig.bar < 0 || from >= s.bars.size || sig.direction == 0) continue
            signals++
            when (walk(s.bars, from, s.bars[from].open, ask.copy(direction = sig.direction))) { 1 -> target++; -1 -> stop++; else -> neither++ }
        }
        return Rerun(past.size, signals, target, stop, neither)
    }

    /**
     * The ORB family's rule ([OrbRules.entrySignal], the arm's own params) on one session's index bars: the first break
     * after the opening range, as [com.optionslab.engine.orb.Replay.day] finds it (no cooling-down, no re-entry cap:
     * only the first signal of the day is re-run).
     */
    fun orbRule(arm: Arm, p: OrbParams = OrbParams.of(arm)): Rule = Rule { bars ->
        val rng = OrbRules.openingRange(bars, p.orEnd)
        var found: Signal? = null
        if (rng != null) for (k in bars.indices) {
            val (d, _) = OrbRules.entrySignal(bars.subList(0, k + 1), rng, arm, null, p = p)
            if (d != 0) { found = Signal(k, d); break }
        }
        found
    }
}
