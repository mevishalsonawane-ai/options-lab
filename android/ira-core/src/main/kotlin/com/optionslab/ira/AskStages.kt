package com.optionslab.ira

/**
 * Speed, round 4 (Boss, 5 Oct: "Answer is taking a lot after question is asked"): where one question's wait goes, stage
 * by stage, for the diagnostics' "Speed (asks):" line. Durations only - never the question, never an answer. Pure;
 * thread-safe (the voice's progress arrives on the speech engine's thread).
 *
 * The stages of one question, from Boss's words read (voice) or the question sent (typed):
 *  - heard → routed: waiting for the questions' lane (shown apart as "waiting in line") and the hub reading the words,
 *    choosing the reader and building any answer it builds at once;
 *  - routed → answered: an answer built after the routing (the account read, the model, the network);
 *  - answered → spoken: the answer shaped for speech and handed to the voice, to its first sound.
 * A question that was never spoken (typed and not read aloud, muted) is kept with the stages it reached.
 */
class AskStages(private val keep: Int = KEEP) {
    enum class Stage { STARTED, ROUTED, ANSWERED, SPOKEN }

    /** One question's stages (ms; null: not reached or not timed). */
    data class Done(val voice: Boolean, val line: Long?, val routed: Long?, val answered: Long?, val spoken: Long?)

    private class Open(val id: Long, val heard: Long, val voice: Boolean) {
        var started = UNSET; var routed = UNSET; var answered = UNSET; var spoken = UNSET
    }

    private var next = 1L
    private val open = LinkedHashMap<Long, Open>()
    private val done = ArrayDeque<Done>()

    /** A question heard or sent at [at] (elapsed ms); its id for [mark] / [finish]. */
    @Synchronized fun begin(at: Long, voice: Boolean): Long {
        val id = next++
        open[id] = Open(id, at, voice)
        // Only a few at once (a voice turn and a typed one): the oldest left open is closed as it stands.
        while (open.size > OPEN_MAX) close(open.keys.first())
        return id
    }

    /** Question [id] reached [stage] at [at]; a mark out of order, repeated or for a closed question is ignored. */
    @Synchronized fun mark(id: Long, stage: Stage, at: Long) {
        val o = open[id] ?: return
        when (stage) {
            Stage.STARTED -> if (o.started == UNSET && at >= o.heard) o.started = at
            Stage.ROUTED -> if (o.routed == UNSET && at >= o.heard) o.routed = at
            Stage.ANSWERED -> if (o.answered == UNSET && o.routed != UNSET && at >= o.routed) o.answered = at
            Stage.SPOKEN -> if (o.spoken == UNSET && o.answered != UNSET && at >= o.answered) { o.spoken = at; close(id) }
        }
    }

    /** Question [id] is over (answered and not to be spoken, or dropped): kept with the stages it reached. */
    @Synchronized fun finish(id: Long) { if (open.containsKey(id)) close(id) }

    private fun close(id: Long) {
        val o = open.remove(id) ?: return
        fun span(a: Long, b: Long): Long? = if (a == UNSET || b == UNSET) null else (b - a).takeIf { it in 0 until MAX_MS }
        val d = Done(o.voice, span(o.heard, o.started), span(o.heard, o.routed), span(o.routed, o.answered), span(o.answered, o.spoken))
        if (d.routed == null) return            // never taken in (cancelled): nothing to learn from it
        done.addLast(d)
        while (done.size > keep) done.removeFirst()
    }

    /** Everything timed or open dropped (the reset hook between tests). */
    @Synchronized fun clear() { open.clear(); done.clear() }

    /** The questions timed so far, oldest first. */
    @Synchronized fun recent(): List<Done> = done.toList()

    /** The diagnostics' line (durations only). */
    fun line(): String = say(recent())

    companion object {
        const val KEEP = 30
        private const val UNSET = Long.MIN_VALUE
        const val OPEN_MAX = 4
        /** A stage this long or longer is not one question's (a clock jump, a lost turn). */
        const val MAX_MS = 120_000L

        val LABELS = listOf("heard→routed", "routed→answered", "answered→spoken")

        private fun sec(ms: Long) = "%.1f s".format(java.util.Locale.ENGLISH, ms / 1000.0)
        private fun median(xs: List<Long>): Long? = if (xs.isEmpty()) null else xs.sorted()[xs.size / 2]

        /** The slowest stage by its typical time (ties: the later stage), or null when nothing is timed. */
        fun slowest(all: List<Done>): Pair<String, Long>? {
            val typical = listOf(median(all.mapNotNull { it.routed }), median(all.mapNotNull { it.answered }), median(all.mapNotNull { it.spoken }))
            var best: Pair<String, Long>? = null
            typical.forEachIndexed { i, t -> if (t != null && (best == null || t >= best!!.second)) best = LABELS[i] to t }
            return best
        }

        fun say(all: List<Done>): String {
            if (all.isEmpty()) return "Speed (asks): no questions timed yet."
            val voice = all.count { it.voice }
            fun part(label: String, xs: List<Long>): String? = median(xs)?.let { "$label ${sec(it)}" }
            val routed = all.mapNotNull { it.routed }
            val line = median(all.mapNotNull { it.line })
            val parts = listOfNotNull(
                part(LABELS[0], routed)?.let { p -> if (line != null) "$p (${sec(line)} waiting in line)" else p },
                part(LABELS[1], all.mapNotNull { it.answered }),
                part(LABELS[2], all.mapNotNull { it.spoken }))
            val worst = listOfNotNull(routed.maxOrNull(), all.mapNotNull { it.answered }.maxOrNull(), all.mapNotNull { it.spoken }.maxOrNull())
            val slow = slowest(all)?.let { (n, t) -> " · slowest stage: $n (typical ${sec(t)})" } ?: ""
            val last = all.last().let { d -> listOfNotNull(d.routed, d.answered, d.spoken).sum() }
            return "Speed (asks): last ${all.size} (${voice} spoken, ${all.size - voice} typed), typical " + parts.joinToString(", ") +
                slow + (worst.maxOrNull()?.let { " · longest single stage ${sec(it)}" } ?: "") + " · last question ${sec(last)} in all."
        }
    }
}
