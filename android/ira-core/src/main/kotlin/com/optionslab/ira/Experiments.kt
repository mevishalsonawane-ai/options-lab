package com.optionslab.ira

import java.time.LocalDate

/**
 * Paper experiments (11 Oct, "smarter learning"): when a lesson is only building - or strong but not past tonight's raised
 * bar ([AutoGate]) - Autopilot does not change the paper setting outright; it may start an EXPERIMENT instead.
 *
 *  - Paper only, never live or shadow ([eligible]); at most [MAX_RUNNING] at a time; one per target.
 *  - A/B by alternation: from its start, the eligible paper trades of that strategy are taken in time order and alternate -
 *    the 1st, 3rd, 5th... are the CONTROL (the current setting), the 2nd, 4th, 6th... the VARIANT (the proposed one). The
 *    setting itself is never switched: each variant trade is judged on its shadow copy - the trade manager's records carry
 *    both ends of every managed trade (the manager's way and the original rules', settled on the option's 1-minute wicks),
 *    and a paused strategy's trade is simply not taken - the same replay as the pre-flight's ([Preflight.cases]).
 *  - Each pair (a control then a variant) gives the variant's lead in rupees; the pairs go through the same sequential test
 *    as a probation ([LearnStats.Sprt]), with alpha shared by the trials so far ([LearnStats.bar]): the more Autopilot has
 *    tried, the more a variant must show.
 *  - WON: it graduates - the paper change is made as a normal AUTO change (pre-flight and probation as ever); when it is the
 *    trade manager acting on a strategy that may act on live, the live twin is only ever asked (a live raise, the PIN).
 *  - LOST: it ends, its target rests [AutoChange.REST_DAYS] days, and Jarvis keeps a negative lesson of it
 *    ([AgentLessons.experimentResult]). Undecided after [MAX_PAIRS] pairs or [MAX_DAYS] trading days: it ends, nothing done.
 *
 * Every experiment shows in the Autopilot log as "experiment". Pure; the app keeps them in Autopilot's own log file.
 */
object Experiments {
    const val MAX_RUNNING = 2
    const val MAX_PAIRS = 40
    const val MAX_DAYS = 20
    /** Who a graduated experiment's change is recorded by. */
    const val BY = "experiment"

    enum class Status(val words: String) { RUNNING("running"), WON("won"), LOST("lost"), ENDED("ended undecided") }

    /** What may be tried as an experiment: the changes a paper trade's shadow copy can judge. */
    val KINDS = setOf(AutoChange.Kind.SPECIALIST_MUTE, AutoChange.Kind.MANAGER_MODE, AutoChange.Kind.PAPER_ENTRIES, AutoChange.Kind.FLOW_MODE)

    data class Experiment(
        val id: String, val change: AutoChange.Change, val startedOn: LocalDate, val startedAtMs: Long, val status: Status,
        /** Pairs so far and the variant's lead over the control, rupees (summed over the pairs). */
        val pairs: Int = 0, val lead: Double = 0.0,
        /** The sequential test in words. */
        val test: String = "",
        val closedOn: LocalDate? = null, val note: String = "",
    )

    /** Paper only, a kind a shadow copy can judge, never a NEVER. */
    fun eligible(c: AutoChange.Change): Boolean = c.scope == AutoChange.Scope.PAPER && c.kind in KINDS && !AutoChange.never(c)

    fun running(book: AutoChange.Book): List<Experiment> = book.experiments.filter { it.status == Status.RUNNING }

    /** Why [c] cannot start now (null: it can). */
    fun cannotStart(book: AutoChange.Book, c: AutoChange.Change): String? = when {
        c.scope != AutoChange.Scope.PAPER -> "experiments are paper only"
        !eligible(c) -> "this kind of change cannot be tried on a paper trade's shadow copy"
        running(book).any { it.change.key == c.key } -> "an experiment on it is running"
        running(book).size >= MAX_RUNNING -> "$MAX_RUNNING experiments are already running"
        else -> null
    }

    /** [c] started as an experiment on [day] (counted as a trial). The book unchanged when it [cannotStart]. */
    fun start(book: AutoChange.Book, c: AutoChange.Change, day: LocalDate, atMs: Long): AutoChange.Book {
        if (cannotStart(book, c) != null) return book
        var k = book.experiments.size + 1
        while (book.experiments.any { it.id == "x$day#$k" }) k++
        val x = Experiment("x$day#$k", c, day, atMs, Status.RUNNING, test = "deciding: 0% of the way, 0 pairs")
        return book.copy(experiments = (book.experiments + x).takeLast(MAX_KEPT), trials = book.trials + 1)
    }

    /** Ended experiments kept in the log (running ones always stay). */
    const val MAX_KEPT = 60

    /** The alternation: case 1 control, case 2 variant, ...; each pair's day and the variant's lead (its "with" less the control's "without"). */
    fun pairs(cases: List<Preflight.Case>): List<Pair<LocalDate, Double>> {
        val out = ArrayList<Pair<LocalDate, Double>>(cases.size / 2)
        var i = 0
        while (i + 1 < cases.size) {
            val control = cases[i]; val variant = cases[i + 1]
            out += AgentLessons.dayOf(variant.atMs) to (variant.with - control.without)
            i += 2
        }
        return out
    }

    /** One experiment's night: what it now is, the change it graduates to and the live question it raises, and its pairs. */
    data class Outcome(val experiment: Experiment, val graduate: AutoChange.Change?, val ask: AutoChange.Change?, val pairs: List<Pair<LocalDate, Double>>)

    /**
     * Every running experiment judged on [day] from [data] (each from its own start). The book (a loss rests its target) and
     * what happened to each.
     */
    fun review(book: AutoChange.Book, day: LocalDate, data: Preflight.Data): Pair<AutoChange.Book, List<Outcome>> {
        val outs = ArrayList<Outcome>()
        var rest = book.rest
        val alpha = LearnStats.bar(book.trials, LearnStats.Sprt.ALPHA)
        val next = book.experiments.map { x ->
            if (x.status != Status.RUNNING) return@map x
            val cases = runCatching { Preflight.cases(x.change, data, sinceMs = x.startedAtMs) }.getOrNull().orEmpty()
            val ps = pairs(cases).take(MAX_PAIRS)
            val s = LearnStats.Sprt.run(ps.map { it.second }, alpha = alpha)
            val lead = ps.sumOf { it.second }
            val over = ps.size >= MAX_PAIRS || AutoChange.tradingDays(x.startedOn, day) >= MAX_DAYS
            val y = when {
                s.call == LearnStats.Sprt.Call.KEEP -> x.copy(status = Status.WON, closedOn = day,
                    note = "won: after ${s.n} pairs the variant led by ${AgentLessons.rs(ps.take(s.n).sumOf { it.second })}; it becomes a paper change")
                s.call == LearnStats.Sprt.Call.REVERT -> x.copy(status = Status.LOST, closedOn = day,
                    note = "lost: after ${s.n} pairs the variant trailed by ${AgentLessons.rs(ps.take(s.n).sumOf { it.second })}; nothing changed")
                over -> x.copy(status = Status.ENDED, closedOn = day, note = "ended undecided after ${ps.size} pairs; nothing changed")
                else -> x
            }.copy(pairs = ps.size, lead = lead, test = s.words().replace(" cases", " pairs").replace(" case", " pair"))
            if (y.status == Status.LOST) rest = rest + (x.change.key to day.plusDays(AutoChange.REST_DAYS))
            if (y.status != Status.RUNNING || y != x) {
                val grad = if (y.status == Status.WON) x.change.copy(reason = "won its paper experiment (${y.note.removePrefix("won: ")}); " + x.change.reason) else null
                outs += Outcome(y, grad, if (grad != null) liveTwin(grad) else null, ps)
            }
            y
        }
        return book.copy(experiments = next, rest = rest) to outs
    }

    /** A won trade manager experiment on a strategy that may act on live: the same raise on live, only ever asked (the PIN). */
    fun liveTwin(c: AutoChange.Change): AutoChange.Change? {
        if (c.kind != AutoChange.Kind.MANAGER_MODE || c.to != TradeManager.Mode.ACT.name) return null
        val p = c.target.split(':')
        if (p.size != 3) return null
        val f = TradeManager.familyOf(p[1]) ?: return null
        if (f.paperOnly || !f.canActLive) return null
        return c.copy(target = "mgr:${p[1]}:live", scope = AutoChange.Scope.LIVE, risk = AutoChange.Risk.RAISE,
            reason = "its paper experiment won; on live it adds risk, so it is yours to decide. " + c.reason, what = "the trade manager on ${f.name} (live)")
    }

    // ---- words -----------------------------------------------------------------------------------------------------------------

    /** "the OI specialist on Pine (paper): counts → muted · experiment · running · 6 pairs, the variant +Rs 1,200 ahead · deciding: 40% of the way". */
    fun line(x: Experiment): String = "${x.change.what}: ${x.change.from} → ${x.change.to} · experiment · ${x.status.words} · " +
        "${x.pairs} pair${if (x.pairs == 1) "" else "s"}, the variant ${AgentLessons.rs(x.lead)} ${if (x.lead >= 0) "ahead" else "behind"}" +
        (if (x.test.isNotEmpty() && x.status == Status.RUNNING) " · ${x.test}" else "")

    /** "What experiments are running?". */
    fun sayRunning(book: AutoChange.Book): String {
        val run = running(book)
        val ended = book.experiments.filter { it.status != Status.RUNNING }.takeLast(2)
        val head = if (run.isEmpty()) "No paper experiment is running, Boss." else
            "${run.size} paper experiment${if (run.size == 1) " is" else "s are"} running, Boss (at most $MAX_RUNNING, paper only):"
        val body = run.joinToString("") { x ->
            " • ${x.change.what}: ${x.change.from} → ${x.change.to} on alternate paper trades since ${AgentLessons.day(x.startedOn)}, " +
                "${x.pairs} pair${if (x.pairs == 1) "" else "s"} so far, the variant ${AgentLessons.rs(x.lead)} ${if (x.lead >= 0) "ahead" else "behind"}; ${x.test}."
        }
        val tail = if (ended.isEmpty()) "" else " Lately: " + ended.joinToString("; ") { "${it.change.what} ${it.status.words}" } + "."
        return head + body + tail + " Trials so far: ${book.trials}."
    }

    // ---- the file ------------------------------------------------------------------------------------------------------------

    private fun clean(s: String) = s.replace('|', '/').replace('\n', ' ').replace('\r', ' ').take(400)

    /** One "X|..." line (its change's fields last). */
    fun encode(x: Experiment): String {
        val sb = StringBuilder("X|")
        sb.append(clean(x.id)).append('|').append(x.startedOn).append('|').append(x.startedAtMs).append('|').append(x.status.name)
            .append('|').append(x.pairs).append('|').append(x.lead).append('|').append(clean(x.test)).append('|').append(x.closedOn ?: "")
            .append('|').append(clean(x.note))
        AutoChange.appendChange(sb, x.change)
        return sb.toString()
    }

    /** An "X|..." line's fields read back (null: not one). */
    fun decode(f: List<String>): Experiment? = runCatching {
        if (f.size < 27 || f[0] != "X") return null
        Experiment(f[1], AutoChange.changeAt(f, 10), LocalDate.parse(f[2]), f[3].toLong(), Status.valueOf(f[4]), f[5].toInt(), f[6].toDouble(),
            f[7], f[8].takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it) }, f[9])
    }.getOrNull()
}
