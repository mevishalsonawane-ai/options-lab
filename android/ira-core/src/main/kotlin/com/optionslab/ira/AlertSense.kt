package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Jarvis learning which of his unasked alerts are worth saying aloud (Jarvis learning from his own outcomes, round 4):
 * each alert he speaks by himself is kept by its kind and minute (never its words), and what Boss did next decides it -
 * asked something or opened the app within [REACT_MIN] minutes ([Reaction.FOLLOWED]), muted Jarvis within [MUTE_MIN]
 * minutes ([Reaction.MUTED], counted twice), or nothing (ignored). A kind Boss keeps ignoring is said aloud less often:
 *
 *  - [Level.FEWER]: every other one aloud;
 *  - [Level.HOLD]: one in [HOLD_EVERY] aloud (the ones still said are how he notices Boss caring again).
 *
 * The alerts not said aloud are still written in the chat: nothing is lost, only the voice is quieter. It only ever
 * says less, never more; only the kinds in [LEARNED] (market colour) are ever held back - a safety warning (live prices
 * stopped, the relay down, loss-limit heads-ups, cool-off, square-off and expiry heads-ups, overtrading, a word before
 * an order) is always said. It never acts and never touches an order. "Which alerts do you hold back?" names them;
 * "say everything again" starts afresh ([reset]). Only the last [WINDOW_DAYS] days count. Pure.
 */
object AlertSense {
    const val WINDOW_DAYS = 14L
    /** Boss asking something or opening the app this soon after an alert: he followed it up. */
    const val REACT_MIN = 5L
    /** Boss muting Jarvis this soon after an alert: it bothered him. */
    const val MUTE_MIN = 3L
    /** As if this many alerts of a kind had been said, half of them followed up: a few never decide alone. */
    const val PRIOR = 2.0
    /** Alerts of a kind decided (with the prior) before it is said less often, and the share followed up under which. */
    const val FEWER_MIN = 4
    const val FEWER_RATE = 0.34
    const val HOLD_MIN = 6
    const val HOLD_RATE = 0.2
    /** Held back: one in this many still said aloud. */
    const val HOLD_EVERY = 4
    /** Alerts kept at most (the window bounds it anyway). */
    const val MAX_KEPT = 400

    /**
     * The only kinds ever said less often (by their automation names): market colour Boss can read in the chat. Anything
     * else - every safety warning, anything about his own positions, money or orders - is always said.
     */
    val LEARNED: Map<String, String> = linkedMapOf(
        "GAP" to "the opening gap plan", "ORB" to "opening range breaks", "MOMENTS" to "market moments (gap fills, yesterday's high or low)",
        "VIX" to "fear (VIX) spikes", "OI" to "open interest walls moving", "EXPIRYDAY" to "the expiry-day companion",
        "USUAL" to "your usual question at its hour")

    /** Named when Boss asks what is held back: never held, whatever he does. */
    const val NEVER = "live prices stopped, the relay down, loss-limit heads-ups, cool-off, the square-off and expiry heads-ups, overtrading and a word before an order"

    enum class Reaction { FOLLOWED, MUTED }
    enum class Boss { ASKED, OPENED, MUTED }
    enum class Level { NORMAL, FEWER, HOLD }

    /** One alert said aloud: its kind, when, and what Boss did (null: nothing yet). */
    data class Said(val kind: String, val at: LocalDateTime, val reaction: Reaction? = null)

    /**
     * What is kept: the alerts said aloud, those held back (kind and minute), how many of each kind were held since the
     * last one said, and when Boss last said "say everything again" (nothing before counts).
     */
    data class Log(val said: List<Said> = emptyList(), val held: List<Pair<String, LocalDateTime>> = emptyList(),
                   val skipped: Map<String, Int> = emptyMap(), val resetAt: LocalDateTime? = null)

    fun learned(kind: String): Boolean = kind in LEARNED

    private fun trim(log: Log, now: LocalDateTime): Log {
        val from = now.minusDays(WINDOW_DAYS)
        return log.copy(said = log.said.filter { it.at.isAfter(from) }.takeLast(MAX_KEPT), held = log.held.filter { it.second.isAfter(from) }.takeLast(MAX_KEPT))
    }

    /** [kind] was just said aloud at [now] (only the [LEARNED] kinds are kept). */
    fun spoken(log: Log, kind: String, now: LocalDateTime): Log {
        if (!learned(kind)) return log
        return trim(log.copy(said = log.said + Said(kind, now), skipped = log.skipped - kind), now)
    }

    /** [kind] was held back at [now] (written in the chat only). */
    fun heldBack(log: Log, kind: String, now: LocalDateTime): Log =
        trim(log.copy(held = log.held + (kind to now), skipped = log.skipped + (kind to (log.skipped[kind] ?: 0) + 1)), now)

    /** Boss did something at [now]: the alerts said just before it are marked (a mute outweighs a follow-up). */
    fun boss(log: Log, what: Boss, now: LocalDateTime): Log {
        val minutes = if (what == Boss.MUTED) MUTE_MIN else REACT_MIN
        var changed = false
        val said = log.said.map { s ->
            val inTime = !s.at.isAfter(now) && !s.at.isBefore(now.minusMinutes(minutes))
            when {
                !inTime -> s
                what == Boss.MUTED && s.reaction != Reaction.MUTED -> { changed = true; s.copy(reaction = Reaction.MUTED) }
                what != Boss.MUTED && s.reaction == null -> { changed = true; s.copy(reaction = Reaction.FOLLOWED) }
                else -> s
            }
        }
        return if (changed) log.copy(said = said) else log
    }

    /** Is there an alert Boss's next step would mark (to skip the write when there is none)? */
    fun waiting(log: Log, now: LocalDateTime): Boolean = log.said.any { !it.at.isAfter(now) && !it.at.isBefore(now.minusMinutes(REACT_MIN)) }

    /** "Say everything again": nothing before [now] counts any more. */
    fun reset(log: Log, now: LocalDateTime): Log = log.copy(skipped = emptyMap(), resetAt = now)

    /** One kind's record: alerts decided, followed up and muted after. */
    data class Record(val kind: String, val said: Int, val followed: Int, val muted: Int) {
        val ignored: Int get() = said - followed - muted
        /** Share followed up, mutes counted twice, with the prior. */
        val rate: Double get() = (followed + PRIOR / 2) / (said + muted + PRIOR)
        val level: Level get() = when {
            said >= HOLD_MIN && rate <= HOLD_RATE -> Level.HOLD
            said >= FEWER_MIN && rate <= FEWER_RATE -> Level.FEWER
            else -> Level.NORMAL
        }
        val phrase: String get() = LEARNED[kind] ?: "alerts of that kind"
        fun say(): String = "$phrase (you followed up $followed of my last $said" + (if (muted > 0) ", and muted me after $muted" else "") + ")"
    }

    /** Every [LEARNED] kind said in the window and decided (its follow-up time passed, or followed up already). */
    fun records(log: Log, now: LocalDateTime): List<Record> {
        val window = now.minusDays(WINDOW_DAYS)
        val from = log.resetAt?.takeIf { it.isAfter(window) } ?: window
        val decided = log.said.filter { learned(it.kind) && it.at.isAfter(from) && !it.at.isAfter(now) &&
            (it.reaction != null || it.at.isBefore(now.minusMinutes(REACT_MIN))) }
        return decided.groupBy { it.kind }.map { (k, xs) ->
            Record(k, xs.size, xs.count { it.reaction == Reaction.FOLLOWED }, xs.count { it.reaction == Reaction.MUTED })
        }.sortedWith(compareByDescending<Record> { it.level }.thenBy { it.rate })
    }

    /** The kinds said less often now, quietest first. */
    fun quieter(log: Log, now: LocalDateTime): List<Record> = records(log, now).filter { it.level != Level.NORMAL }

    fun level(log: Log, kind: String, now: LocalDateTime): Level =
        if (!learned(kind)) Level.NORMAL else records(log, now).firstOrNull { it.kind == kind }?.level ?: Level.NORMAL

    /**
     * Whether [kind] is said aloud now: always, unless it is a [LEARNED] kind Boss keeps ignoring - then every other one
     * ([Level.FEWER]) or one in [HOLD_EVERY] ([Level.HOLD]). Never decides anything but the voice.
     */
    fun aloud(log: Log, kind: String, now: LocalDateTime): Boolean {
        if (!learned(kind)) return true
        val skipped = log.skipped[kind] ?: 0
        return when (level(log, kind, now)) {
            Level.NORMAL -> true
            Level.FEWER -> skipped >= 1
            Level.HOLD -> skipped >= HOLD_EVERY - 1
        }
    }

    /** The kinds held back now (for the review's "since yesterday"). */
    fun quietKeys(log: Log, now: LocalDateTime): Set<String> = quieter(log, now).map { it.kind }.toSet()

    enum class Request { WHICH, ALL }

    private val WHICH = Regex("(?i)\\b(which|what) (alerts?|warnings?|things?|kinds?)( do| are| have)? you (hold(ing)? back|holding back|keep(ing)? (back|quiet)|not say(ing)?|skip(ping)?|say(ing)? less( often)?)\\b|" +
        "\\b(what|which) (are you|do you) (hold(ing)? back|keep(ing)? quiet about)\\b|\\bwhich alerts? (are|do) you (skip|mute|silence)\\b")
    private val ALL = Regex("(?i)^\\W*(jarvis\\W+)?(please\\W+)?(say|speak|tell me) (everything|every alert|all (your |the )?alerts?)( (again|aloud|out loud))+\\W*(boss|please|jarvis)?\\W*$|" +
        "\\bstop holding (back )?(alerts?|anything)\\b|\\b(don'?t|do not) hold (any )?(alerts?|anything) back\\b")

    /** "Which alerts do you hold back?" or "say everything again", else null. */
    fun asked(text: String): Request? = when {
        ALL.containsMatchIn(text) -> Request.ALL
        WHICH.containsMatchIn(text) -> Request.WHICH
        else -> null
    }

    /** "Which alerts do you hold back?". */
    fun say(log: Log, now: LocalDateTime): String {
        val q = quieter(log, now)
        if (q.isEmpty()) return "I hold nothing back, Boss: I say every alert aloud as it comes. If you keep letting a kind pass without a word, " +
            "I'll say it less often - never a safety warning ($NEVER)."
        val hold = q.filter { it.level == Level.HOLD }
        val fewer = q.filter { it.level == Level.FEWER }
        val parts = ArrayList<String>()
        if (hold.isNotEmpty()) parts += "I say only one in $HOLD_EVERY of these aloud: " + hold.joinToString("; ") { it.say() }
        if (fewer.isNotEmpty()) parts += "every other one of these: " + fewer.joinToString("; ") { it.say() }
        return "Boss, " + parts.joinToString("; and ") + ", in the last $WINDOW_DAYS days. The rest still go in the chat - only my voice is quieter. " +
            "I never hold back a safety warning ($NEVER). Say \"say everything again\" and I'll speak them all."
    }

    /** "Say everything again". */
    fun sayAll(log: Log, now: LocalDateTime): String =
        if (quieter(log, now).isEmpty()) "I'm already saying every alert aloud, Boss. I'll start my count afresh from now."
        else "Done, Boss: I'll say every alert aloud again and start my count afresh from now."

    /**
     * For the evening self-review: what is said less often now, how many were held back today, and the kinds said aloud
     * again since [before] (yesterday's [quietKeys]; null: not known).
     */
    fun review(log: Log, now: LocalDateTime, before: Set<String>? = null): List<String> {
        val out = ArrayList<String>()
        val q = quieter(log, now)
        val today: LocalDate = now.toLocalDate()
        val heldToday = log.held.count { it.second.toLocalDate() == today && learned(it.first) && (log.resetAt == null || it.second.isAfter(log.resetAt)) }
        val fresh = if (before == null) emptyList() else q.filter { it.kind !in before }
        if (fresh.isNotEmpty()) out += "I now say fewer of " + fresh.take(2).joinToString(" and ") { it.say() } + " aloud - they stay in the chat"
        if (heldToday > 0) out += "I kept $heldToday alert${if (heldToday == 1) "" else "s"} to the chat only today - you rarely follow those up"
        if (before != null) {
            val now2 = q.map { it.kind }.toSet()
            val back = before.filter { it !in now2 && learned(it) }.take(2).map { LEARNED.getValue(it) }
            if (back.isNotEmpty()) out += "I say " + back.joinToString(" and ") + " aloud every time again - you followed them up lately, or asked me to"
        }
        return out
    }
}
