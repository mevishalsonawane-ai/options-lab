package com.optionslab.ira

import java.time.Duration
import java.time.LocalDateTime
import java.util.Locale

/**
 * Jarvis's airtime for unasked market alerts (round 6, 5 Oct: a review found he talks too much). Every unasked market
 * alert - sharp moves, VIX spikes, market moments, opening-range breaks, the gap plan, the expiry-day reads, open
 * interest walls, the watch's bursts - passes through here before it is spoken or popped up:
 *  - alerts about the same move (the same index, or Nifty and BankNifty together, the same way within minutes) are one
 *    line: those found in one pass are merged ("Same move: ..."), and a later one about a move already told aloud is not
 *    said again;
 *  - at most [PER_HOUR] market lines are spoken in any hour; past that they go to the chat (and a pop-up) only.
 * It composes with [AlertSense] (the kinds Boss keeps letting pass): what is left after the two rules above is asked of
 * it, alert by alert, before the line is made - so a kind held back by it never counts as told.
 * The chat always keeps every alert in full: this decides only what is said aloud and what pops up. Safety warnings
 * (live prices stopped, loss limits, cool-off, the relay, the square-off heads-up) never come here and are never held.
 * Words only - it never acts. Pure.
 */
object Airtime {
    /** Where an alert came from; a lower [rank] leads a merged line. */
    enum class Source(val rank: Int) { SHARPMOVE(0), VIX(1), MOMENTS(2), ORB(3), GAP(4), EXPIRYDAY(5), OI(6), WATCH(7) }

    /**
     * One unasked market alert. [subject]: the market whose price move it is about, or null when it is not about a move
     * (an open interest wall, an expiry read); [up] the move's way, or null. [spoken]: what is said aloud on its own;
     * [brief]: a short clause for a merged line. [voice] false: only ever shown (the watch's pop-ups), never spoken.
     */
    data class Alert(val source: Source, val at: LocalDateTime, val subject: Market?, val up: Boolean?,
                     val spoken: String, val brief: String, val voice: Boolean = true)

    /** An alert already said aloud or popped up: when, about what. */
    data class Told(val at: LocalDateTime, val subject: Market?, val up: Boolean?, val source: Source)

    /** What became of an alert: said; about a move already told; past the hour's lines; a kind Boss lets pass ([AlertSense]). */
    enum class How { SPOKEN, SAME_MOVE, OVER_LIMIT, LEARNED }

    /** Today's decisions, for "why so quiet?": when, the alert's brief, and what became of it. */
    data class Entry(val at: LocalDateTime, val brief: String, val how: How)

    data class State(val spoken: List<Told> = emptyList(), val shown: List<Told> = emptyList(),
                     val pending: List<Alert> = emptyList(), val log: List<Entry> = emptyList())

    /** [say]: the one line to speak, or null; [sources]: the kinds of alert in it (for [AlertSense.spoken]). */
    data class Out(val state: State, val say: String?, val sources: List<Source> = emptyList())

    /** One move: the same index the same way within this many minutes of the last time it was told. */
    const val SAME_MOVE_MIN = 10L
    /** Nifty and BankNifty (and the other indices) moving the same way within this many minutes are one market move. */
    const val TOGETHER_MIN = 5L
    /** Spoken market lines in any 60 minutes. */
    const val PER_HOUR = 4
    /** An alert not spoken within this many minutes of being found is stale and goes unsaid (it is in the chat). */
    const val STALE_MIN = 5L
    private const val LOG_MAX = 60

    private val INDICES = setOf(Market.NIFTY, Market.BANKNIFTY, Market.FINNIFTY, Market.SENSEX)

    private fun minutesApart(a: LocalDateTime, b: LocalDateTime) = Math.abs(Duration.between(a, b).toMinutes())

    /** Are [a] and [t] about the same move? Never for an alert that is not about a move. */
    fun sameMove(a: Alert, t: Told): Boolean = sameMove(a.subject, a.up, a.at, t.subject, t.up, t.at)

    private fun sameMove(s1: Market?, up1: Boolean?, at1: LocalDateTime, s2: Market?, up2: Boolean?, at2: LocalDateTime): Boolean {
        if (s1 == null || s2 == null) return false
        if (up1 != null && up2 != null && up1 != up2) return false
        val apart = minutesApart(at1, at2)
        return if (s1 == s2) apart <= SAME_MOVE_MIN
            else s1 in INDICES && s2 in INDICES && up1 != null && up2 != null && apart <= TOGETHER_MIN
    }

    private fun told(a: Alert) = Told(a.at, a.subject, a.up, a.source)

    /**
     * A new alert (its full words already in the chat): whether to pop it up - not when a pop-up about the same move was
     * just shown - and the state with it waiting to be spoken at the end of the pass ([flush]).
     */
    fun offer(s: State, a: Alert): Pair<State, Boolean> {
        val shown = s.shown.filter { minutesApart(it.at, a.at) <= SAME_MOVE_MIN }
        val pop = shown.none { sameMove(a, it) }
        val next = s.copy(shown = if (pop) shown + told(a) else shown, pending = if (a.voice) s.pending + a else s.pending)
        return next to pop
    }

    /**
     * The end of a market pass: the waiting alerts as ONE line to speak (or null). Those about a move already told aloud
     * are dropped; the rest grouped by move, the best-ranked group's own words first, its fellows as "Same move: ...",
     * other moves as "Also: ..."; nothing when [PER_HOUR] lines were spoken in the last hour. [aloud]: the learned say
     * on each alert still in the line ([AlertSense.aloud] - asked once per alert, so it may record what it held back).
     */
    fun flush(s: State, now: LocalDateTime, aloud: (Alert) -> Boolean = { true }): Out {
        val day = now.toLocalDate()
        val spoken = s.spoken.filter { !it.at.isBefore(now.minusMinutes(60)) }
        val log = ArrayList(s.log.filter { it.at.toLocalDate() == day })
        val fresh = s.pending.filter { !it.at.isBefore(now.minusMinutes(STALE_MIN)) }
        val known = fresh.filter { a -> spoken.any { sameMove(a, it) } }
        known.forEach { log += Entry(it.at, it.brief, How.SAME_MOVE) }
        val left0 = (fresh - known.toSet()).sortedBy { it.source.rank }
        fun done(say: String?, told: List<Told>) = Out(State(spoken + told, s.shown, emptyList(), log.takeLast(LOG_MAX)), say, told.map { it.source }.distinct())
        if (left0.isEmpty()) return done(null, emptyList())
        val lines = spoken.map { it.at }.distinct().size
        if (lines >= PER_HOUR) {
            left0.forEach { log += Entry(it.at, it.brief, How.OVER_LIMIT) }
            return done(null, emptyList())
        }
        val (left, quiet) = left0.partition { a -> runCatching { aloud(a) }.getOrDefault(true) }
        quiet.forEach { log += Entry(it.at, it.brief, How.LEARNED) }
        if (left.isEmpty()) return done(null, emptyList())
        // Group by move: each alert joins the first group whose lead it shares a move with.
        val groups = ArrayList<MutableList<Alert>>()
        for (a in left) {
            val g = groups.firstOrNull { g -> g.any { b -> sameMove(a.subject, a.up, a.at, b.subject, b.up, b.at) } }
            if (g != null) g += a else groups += mutableListOf(a)
        }
        val lead = groups.first()
        val words = StringBuilder(Address.boss(lead.first().spoken.trim()))
        val same = lead.drop(1).map { it.brief.trim().trimEnd('.') }.distinct()
        if (same.isNotEmpty()) words.append(" Same move: ").append(same.joinToString("; ")).append('.')
        val also = groups.drop(1).map { it.first().brief.trim().trimEnd('.') }.distinct()
        if (also.isNotEmpty()) words.append(" Also: ").append(also.joinToString("; ")).append('.')
        if (lines + 1 >= PER_HOUR) words.append(" More market alerts this hour go to the chat only.")
        left.forEach { log += Entry(it.at, it.brief, How.SPOKEN) }
        // One line spoken (one of the hour's [PER_HOUR], all its alerts at [now]); every move in it counts as told.
        return done(words.toString(), left.map { told(it).copy(at = now) })
    }

    /** Alerts kept to the chat today (any reason). */
    fun heldToday(s: State, now: LocalDateTime): Int = s.log.count { it.at.toLocalDate() == now.toLocalDate() && it.how != How.SPOKEN }

    /** Lines spoken in the last hour (each spoken line holds one or more alerts, all told at its moment). */
    fun spokenLastHour(s: State, now: LocalDateTime): Int =
        s.spoken.filter { !it.at.isBefore(now.minusMinutes(60)) }.map { it.at }.distinct().size

    /**
     * A watch alert ([Watch.check]) as an airtime alert (shown only, never spoken), or null for one that is not about
     * the market (an arm losing half the day's loss limit is a safety warning: it is never held).
     */
    fun ofWatch(a: Watch.Alert, at: LocalDateTime): Alert? {
        val k = a.key.split('|')
        return when (k[0]) {
            "vix-day", "vix-jump" -> Alert(Source.WATCH, at, Market.VIX, true, a.text, a.title, voice = false)
            "day", "burst" -> {
                val m = runCatching { Market.valueOf(k.getOrNull(1) ?: "") }.getOrNull() ?: return null
                val up = if (k[0] == "day") k.getOrNull(2) == "up" else !a.title.contains(" -")
                Alert(Source.WATCH, at, m, up, a.text, a.title, voice = false)
            }
            else -> null
        }
    }

    private const val LEAD = "(jarvis |hey jarvis |ok jarvis |boss |so |and |please )*"
    private val ASKED = Regex("^ $LEAD(why (are you|were you|have you been|so) (so )?(quiet|silent)( today| lately| this hour)?|" +
        "(what|which) (market )?alerts (did you|have you) (hold|held|keep|kept|skip|skipped)( back)?( today)?|" +
        "(did you|have you) (hold|held|keep|kept|skip|skipped) (back )?(any )?(market )?alerts( today)?|" +
        "how many (market )?alerts (did you|have you) (say|said|speak|spoken|give|given)( today| this hour)?|" +
        "(what did you|what have you) (not say|not tell me|keep to the chat)( today)?)( boss| jarvis)? $")

    private fun norm(text: String) = " " + text.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim() + " "

    /** "Why so quiet?", "did you hold back any alerts?" */
    fun asked(text: String): Boolean = ASKED.containsMatchIn(norm(text))

    /** The answer: what was said aloud today and what went to the chat only, and why (the last few held, with times). */
    fun say(s: State, now: LocalDateTime): String {
        val today = s.log.filter { it.at.toLocalDate() == now.toLocalDate() }
        val spoke = today.count { it.how == How.SPOKEN }
        val same = today.filter { it.how == How.SAME_MOVE }
        val over = today.filter { it.how == How.OVER_LIMIT }
        if (today.isEmpty()) return "No market alerts today yet, Boss. I say at most $PER_HOUR an hour aloud, one line per move; every alert is in the chat in full."
        val sb = StringBuilder("Boss, today $spoke market alert${if (spoke == 1) "" else "s"} went into what I said aloud")
        val learned = today.filter { it.how == How.LEARNED }
        if (same.isEmpty() && over.isEmpty() && learned.isEmpty()) return sb.append(", and none was held back. Every alert is in the chat in full.").toString()
        sb.append("; ")
        val parts = ArrayList<String>()
        if (same.isNotEmpty()) parts += "${same.size} went to the chat only because I had just told you about the same move"
        if (over.isNotEmpty()) parts += "${over.size} because I had already said $PER_HOUR in that hour"
        if (learned.isNotEmpty()) parts += "${learned.size} of a kind you usually let pass"
        sb.append(parts.joinToString("; ")).append(". Kept to the chat: ")
        val held = (same + over + learned).sortedBy { it.at }.takeLast(5)
        sb.append(held.joinToString("; ") { "${it.brief.trim().trimEnd('.')} (${"%02d:%02d".format(Locale.ENGLISH, it.at.hour, it.at.minute)})" }).append('.')
        return sb.toString()
    }
}
