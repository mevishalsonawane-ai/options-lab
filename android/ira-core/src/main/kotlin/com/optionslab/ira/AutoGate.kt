package com.optionslab.ira

import java.util.Locale

/**
 * Autopilot's gate (11 Oct, "smarter learning"): what happens to each AUTO change of tonight's plan ([AutoChange.plan])
 * before anything is applied. Pure and deterministic (the pre-flight's clock aside); the app runs it off the main thread.
 *
 *  1. A lesson that holds under one condition only ("on busy days only", [AgentLessons.conditions]) on a target with no
 *     per-regime setting ([AutoChange.condCapable]): recorded as SKIPPED, not applied everywhere.
 *  2. No overfitting: every configuration Autopilot has tried counts ([AutoChange.Book.trials]) and raises the bar a lesson's
 *     q-value must clear - Bonferroni-style, [LearnStats.bar]: 0.10 / (1 + trials / 20). A paper or live change whose
 *     lesson is not strong, or whose q is over tonight's bar, is not made outright: on paper it may start an EXPERIMENT
 *     instead ([Experiments], at most two, paper only); elsewhere it waits. (The self-review's own calibrated verdicts and
 *     shadow changes, which carry no money, are not held to a lesson's bar.)
 *  3. Pre-flight: every PAPER and LIVE change left is replayed on stored history ([Preflight]); worse beyond the tolerance,
 *     or over its 200 ms budget: SKIPPED with the note "pre-flight failed" (or the budget's).
 */
object AutoGate {
    /** One change to make: its pre-flight (null: shadow, none) and its lesson's q (null: none). */
    data class Go(val change: AutoChange.Change, val preflight: Preflight.Result?, val q: Double?)

    /** One change not made, with the note the log keeps. */
    data class Skip(val change: AutoChange.Change, val note: String, val q: Double?)

    data class Routed(val apply: List<Go>, val experiments: List<AutoChange.Change>, val skipped: List<Skip>, val held: List<Pair<AutoChange.Change, String>>)

    /** Tonight's bar for a lesson's q after the trials so far. */
    fun bar(book: AutoChange.Book): Double = LearnStats.bar(book.trials, AgentLessons.BH_Q)

    /** [l]'s q-value (the night's, else its own p as a family of one). */
    fun qOf(l: AgentLessons.Lesson?): Double? = l?.let { it.q ?: AgentLessons.pValue(it) }

    private fun lessonOf(c: AutoChange.Change, lessons: Map<String, AgentLessons.Lesson>): AgentLessons.Lesson? =
        c.lessonId?.let { lessons[it] ?: lessons[AgentLessons.norm(it)] }

    /** Changes whose evidence is not a lesson's (the self-review's calibrated verdict): never held to a lesson's bar. */
    private fun ownEvidence(c: AutoChange.Change) = c.lessonId == null || c.lessonId.startsWith("drift:")

    /** Tonight's [plan] through the gate; [healthOnly]: the 12:30 pass (no experiments). */
    fun route(book: AutoChange.Book, plan: AutoChange.Plan, lessons: Map<String, AgentLessons.Lesson>, data: Preflight.Data,
              healthOnly: Boolean = false, clockNs: () -> Long = System::nanoTime): Routed {
        val apply = ArrayList<Go>(); val exps = ArrayList<AutoChange.Change>(); val skipped = ArrayList<Skip>(); val held = ArrayList<Pair<AutoChange.Change, String>>()
        val bar = bar(book)
        var running = Experiments.running(book).size
        val startedKeys = HashSet<String>()
        for (c in plan.auto) {
            val l = lessonOf(c, lessons)
            val q = qOf(l)
            if (l != null && l.cond != null && !AutoChange.condCapable(c.kind)) {
                skipped += Skip(c, "recorded, not applied: its lesson holds ${AgentLessons.condWords(l.cond)}" +
                    (l.condQ?.let { " (the split's ${AgentLessons.qWords(it)})" } ?: "") + ", and ${c.what} has no per-regime setting", q)
                continue
            }
            if (c.scope != AutoChange.Scope.SHADOW && l != null && !ownEvidence(c)) {
                val strong = l.confidence == AgentLessons.Confidence.STRONG && (q ?: 1.0) <= bar + 1e-12
                if (!strong) {
                    val why = "its lesson is ${l.confidence.word}" + (q?.let { ", ${AgentLessons.qWords(it)}" } ?: "") +
                        " against tonight's bar of ${AgentLessons.fmtQ(bar)} after ${book.trials} trial${if (book.trials == 1) "" else "s"}"
                    if (healthOnly || c.scope != AutoChange.Scope.PAPER) { held += c to "$why: not made outright"; continue }
                    val no = Experiments.cannotStart(book, c) ?: when {
                        c.key in startedKeys -> "an experiment on it starts tonight"
                        running >= Experiments.MAX_RUNNING -> "${Experiments.MAX_RUNNING} experiments are already running"
                        else -> null
                    }
                    if (no == null) { exps += c; startedKeys += c.key; running++ } else held += c to "$why; no experiment: $no"
                    continue
                }
            }
            if (c.scope == AutoChange.Scope.SHADOW) { apply += Go(c, null, q); continue }
            val r = Preflight.check(c, data, clockNs = clockNs)
            if (r.ok) apply += Go(c, r, q) else skipped += Skip(c, r.words(), q)
        }
        return Routed(apply, exps, skipped, held)
    }

    // ---- words -------------------------------------------------------------------------------------------------------------

    /** The Autopilot log's header: "Trials so far: 12 · tonight's bar q ≤ 0.06 · 1 experiment running". */
    fun header(book: AutoChange.Book): String {
        val run = Experiments.running(book).size
        return "Trials so far: ${book.trials} · tonight's bar q ≤ ${AgentLessons.fmtQ(bar(book))}" +
            (if (run > 0) " · $run experiment${if (run == 1) "" else "s"} running" else "")
    }

    /**
     * "How sure are you about X?": the lessons about X with their n, q and confidence against tonight's bar; Autopilot's
     * newest change about X with its sequential test and pre-flight; and any experiment on it.
     */
    fun sayHowSure(lessons: AgentLessons.Book, book: AutoChange.Book, subject: String): String {
        val s = subject.lowercase(Locale.ENGLISH).trim().removePrefix("the ").removePrefix("your ").removePrefix("my ").trim()
        val ask = AgentLessons.subject(s)?.let { (topic, tag) -> AgentLessons.Ask(AgentLessons.AskKind.ABOUT, topic, tag, s) }
        val tag = ask?.tag
        val words = s.removeSuffix(" specialist").removeSuffix(" specialists").trim()
        val ls = (if (ask != null) AgentLessons.about(lessons, ask) else emptyList()).ifEmpty {
            AgentLessons.ranked(AgentLessons.active(lessons).filter { words.length > 2 && it.name.lowercase(Locale.ENGLISH).contains(words) })
        }.take(3)
        fun about(what: String, target: String): Boolean {
            val w = what.lowercase(Locale.ENGLISH); val t = target.lowercase(Locale.ENGLISH)
            return (words.length > 2 && (words in w || words in t)) || (tag != null && (t.split(':').contains(tag.lowercase(Locale.ENGLISH)) || w.contains(tag.replace('_', ' '))))
        }
        val e = book.entries.lastOrNull { it.status != AutoChange.Status.DECLINED && about(it.change.what, it.change.target) }
        val x = book.experiments.lastOrNull { about(it.change.what, it.change.target) }
        if (ls.isEmpty() && e == null && x == null) return "I have no lesson, change or experiment about $subject yet, Boss, so I can't say how sure I am."
        val bar = bar(book)
        val parts = ArrayList<String>()
        for (l in ls) {
            val ev = AgentLessons.evidence(l)
            val q = qOf(l)
            parts += "${AgentLessons.statement(l)}: ${ev.n} ${l.topic.unit}" + (if (l.kind == AgentLessons.Kind.RUPEES) ", ${AgentLessons.rs(ev.rs)}" else "") +
                (q?.let { ", ${AgentLessons.qWords(it)}" } ?: "") + " - ${l.confidence.word}" +
                (q?.let { if (it <= bar + 1e-12) " and past tonight's bar of ${AgentLessons.fmtQ(bar)}" else ", not past tonight's bar of ${AgentLessons.fmtQ(bar)} (it could still be luck)" } ?: "")
        }
        if (e != null) parts += "my change to ${e.change.what} (${e.change.from} → ${e.change.to}) is ${e.status.words}" +
            (if (e.test.isNotEmpty()) "; its test: ${e.test}" else "") + (if (e.preflight.isNotEmpty()) "; ${e.preflight}" else "") +
            (if (e.note.isNotEmpty() && e.status != AutoChange.Status.PROBATION) " (${e.note})" else "")
        if (x != null) parts += "the paper experiment on ${x.change.what} is ${x.status.words}: ${x.pairs} pair${if (x.pairs == 1) "" else "s"}, the variant " +
            "${AgentLessons.rs(x.lead)} ${if (x.lead >= 0) "ahead" else "behind"}" + (if (x.test.isNotEmpty()) "; ${x.test}" else "")
        return "How sure I am about $subject, Boss: " + parts.joinToString(" ") { "• $it." } +
            " (q is the false-discovery rate: how many lessons this strong could be luck among all I hold. Trials so far: ${book.trials}.)"
    }
}
