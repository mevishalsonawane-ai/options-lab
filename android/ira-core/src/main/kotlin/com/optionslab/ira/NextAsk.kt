package com.optionslab.ira

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Jarvis learning which question Boss usually asks next (learning round 27, 2026-10-05), from what the app already keeps:
 * Boss's routine log ([Routine.Seen] - each question's key, "NIFTY|levels" or "ACCOUNT|pnl", and the minute it was
 * asked; never a word). Nothing new is recorded; only the moment Boss last asked to undo it is kept ([Log]).
 *
 * In the last [WINDOW_DAYS] days (after that moment), each market question in the log followed by another question of a
 * different kind within [GAP_MINUTES] minutes - the very next one noted - is one follow-up. A follow-up asked after a kind
 * at least [MIN_TIMES] times, on at least [MIN_DAYS] days, in at least [SHARE] of the times that kind was asked, and more
 * than any other follow-up of it, is learned ([learned]). One the first answer already gives (the levels after an
 * overview, which says them) is never counted.
 *
 * Boss's own answer to that kind then ends with ONE short offer ([offer], [line]): "BankNifty's levels next, Boss?" -
 * never on a locked phone, never after an order, a command or words that act, and not when he asked it in the last
 * [RECENT_MINUTES] minutes or the answer already holds it. The answer is never given unasked: only Boss's bare "yes" as
 * his very next words, within [YES_MINUTES] minutes, asks that very question ([taken]) - a question checked again to be no
 * order and no command ([Routine.safe]); any other words end the offer, and a yes while something else waits for his yes
 * or Confirm is never taken as it.
 *
 * Nothing learned here acts: one phrase of words and one understood "yes". "What do I usually ask next?" names what was
 * learned ([Request.WHICH]; his habit, so on an unlocked phone only); "stop offering what I ask next" ([UNDO],
 * [Request.RESET]) undoes it and starts the count afresh. Shown in the Learnings ledger with that undo, and reset by "undo
 * everything you learned this week". Pure: the app keeps the routine log and the reset moment.
 */
object NextAsk {
    /** Only the last this many days count. */
    const val WINDOW_DAYS = 30L
    /** A question asked within this many minutes after another is its follow-up. */
    const val GAP_MINUTES = 3L
    /** The follow-up asked at least this many times after the kind... */
    const val MIN_TIMES = 4
    /** ...on at least this many days... */
    const val MIN_DAYS = 3
    /** ...and in at least this share of the times the kind was asked. */
    const val SHARE = 0.5
    /** Not offered when Boss asked it himself this recently. */
    const val RECENT_MINUTES = 10L
    /** Boss's "yes" counts within this many minutes of the offer. */
    const val YES_MINUTES = 2L

    const val UNDO = "stop offering what I ask next"
    const val LOCKED = "Unlock the phone for that, Boss."

    /** When Boss last asked to undo it: nothing noted up to then counts. */
    data class Log(val resetAt: LocalDateTime? = null)

    /** After [after], Boss asked [next] [times] of the [of] times he asked it, on [days] days, the newest on [newest]. */
    data class Record(val after: String, val next: String, val times: Int, val of: Int, val days: Int, val newest: LocalDate) {
        /** "Nifty's levels". */
        val afterPhrase: String get() = Routine.about(after) ?: "that question"
        /** "BankNifty's levels". */
        val phrase: String get() = Routine.about(next) ?: "your usual next question"
        fun say(): String = "after $afterPhrase you asked about $phrase next $times of the $of times, on $days days"
    }

    /** The topics an answer of a market kind already says (an overview says the price, the trend, the levels and patterns). */
    private fun says(kind: String): Set<String> = when (kind) {
        "overview" -> setOf("overview", "trend", "levels", "patterns")
        else -> setOf(kind)
    }

    private fun split(key: String): Pair<String, String>? = key.split("|").takeIf { it.size == 2 }?.let { it[0] to it[1] }

    /** A market question's key (one Jarvis answers with a market read), never the account's. */
    private fun market(key: String): Boolean = !key.startsWith("ACCOUNT|") && Habits.question(key) != null

    /** Does the answer to [after] already give [next] (the same index, a part that answer says)? */
    fun covers(after: String, next: String): Boolean {
        if (after == next) return true
        if (!market(after) || !market(next)) return false
        val (am, ak) = split(after) ?: return false
        val (nm, nk) = split(next) ?: return false
        return am == nm && nk in says(ak)
    }

    /** The follow-ups learned at [today] from [log] (Routine's), one a kind, the strongest first. */
    fun learned(log: List<Routine.Seen>, nl: Log, today: LocalDate): List<Record> {
        val reset = nl.resetAt
        val seen = log.filter { s ->
            val d = s.at.toLocalDate()
            !d.isAfter(today) && d.isAfter(today.minusDays(WINDOW_DAYS)) && (reset == null || s.at.isAfter(reset))
        }.sortedBy { it.at }
        val asked = HashMap<String, Int>()
        val pairs = HashMap<Pair<String, String>, MutableList<LocalDate>>()
        seen.forEachIndexed { i, a ->
            if (!market(a.key)) return@forEachIndexed
            asked[a.key] = (asked[a.key] ?: 0) + 1
            val b = seen.getOrNull(i + 1) ?: return@forEachIndexed
            val gap = Duration.between(a.at, b.at).toMinutes()
            if (gap < 0 || gap > GAP_MINUTES || covers(a.key, b.key) || Routine.question(b.key) == null) return@forEachIndexed
            pairs.getOrPut(a.key to b.key) { ArrayList() } += a.at.toLocalDate()
        }
        return pairs.entries.groupBy { it.key.first }.mapNotNull { (after, es) ->
            val best = es.maxByOrNull { it.value.size } ?: return@mapNotNull null
            val times = best.value.size
            val of = asked[after] ?: 0
            val days = best.value.distinct()
            if (times < MIN_TIMES || days.size < MIN_DAYS || times < SHARE * of) return@mapNotNull null
            if (es.any { it !== best && it.value.size >= times }) return@mapNotNull null
            if (!Routine.safe(best.key.second)) return@mapNotNull null
            Record(after, best.key.second, times, of, days.size, days.max())
        }.sortedWith(compareByDescending<Record> { it.times }.thenBy { it.after })
    }

    /**
     * The follow-up to offer at the end of Boss's answer to [question] at [now], or null. Never on a [locked] phone, never
     * for an order, a command or anything but a market question, nor when that answer already gives it or [log] shows he
     * asked it in the last [RECENT_MINUTES] minutes. Never while anything else waits for Boss's yes or Confirm ([waiting]:
     * a pending action - a stop, an exit, a news trade - or Jarvis's own yes-or-no question still open): a bare "yes" can
     * only ever be for ONE thing, and an offer said then could have his yes meant for it approve the older request.
     */
    fun offer(records: List<Record>, question: String, log: List<Routine.Seen>, now: LocalDateTime, locked: Boolean,
              waiting: Boolean = false): Record? {
        if (!mayOffer(locked, waiting) || records.isEmpty()) return null
        val q = runCatching { Ask.parse(question) }.getOrNull() ?: return null
        if (q.command != null || q.order != null || Topic.ORDER in q.topics || Topic.COMMAND in q.topics) return null
        val key = runCatching { Routine.key(question) }.getOrNull()?.takeIf { market(it) } ?: return null
        val r = records.firstOrNull { it.after == key } ?: return null
        if (!Routine.safe(r.next)) return null
        // The answer already gives it: another index named in the question, or a part it says.
        val (nm, nk) = split(r.next) ?: ("" to "")
        if (market(r.next)) {
            val named = q.markets.map { it.name }.ifEmpty { listOf(Market.NIFTY.name) }
            val kinds = q.topics.mapNotNull { t -> KIND[t] }.flatMap { says(it) }.toSet()
            if (nm in named && nk in kinds) return null
        }
        if (log.any { it.key == r.next && !it.at.isAfter(now) && Duration.between(it.at, now).toMinutes() < RECENT_MINUTES }) return null
        return r
    }

    /**
     * May an answer end with an offer at all: never on a [locked] phone, never while anything waits for Boss's yes or
     * Confirm ([waiting]: actions pending, or Jarvis's yes-or-no window open).
     */
    fun mayOffer(locked: Boolean, waiting: Boolean): Boolean = !locked && !waiting

    private val KIND = mapOf(Topic.LEVELS to "levels", Topic.TREND to "trend", Topic.PATTERNS to "patterns", Topic.NEWS to "news",
        Topic.VOLATILITY to "volatility", Topic.WHY to "why", Topic.OVERVIEW to "overview")

    /** The one short phrase at the end of the answer: an offer, never the answer. */
    fun line(r: Record): String = r.phrase.replaceFirstChar { it.uppercase() } + " next, Boss?"

    /**
     * Boss's words [said] at [now] after [next] was offered at [offeredAt]: the question to ask, or null. Only a bare yes
     * ([MorningAsks.yes]) within [YES_MINUTES], on an unlocked phone, and never while anything else waits for his yes or
     * Confirm ([waiting]).
     */
    fun taken(next: String, offeredAt: LocalDateTime, said: String, waiting: Boolean, locked: Boolean, now: LocalDateTime): String? {
        if (waiting || locked || !MorningAsks.yes(said)) return null
        if (now.isBefore(offeredAt) || now.isAfter(offeredAt.plusMinutes(YES_MINUTES))) return null
        if (!Routine.safe(next)) return null
        return Routine.question(next)
    }

    /** "Stop offering what I ask next" at [now]: nothing noted up to then counts. */
    fun reset(now: LocalDateTime): Log = Log(now)

    // ---- asked -----------------------------------------------------------------------------------------------------

    enum class Request { WHICH, RESET }

    private fun norm(text: String) = " " + spacedWords(text.lowercase().replace("'", "").replace("’", "")) + " "

    private const val LEAD = "^ (hey |ok |okay )?(jarvis )?(so )?(please )?(can you |could you |would you )?(tell me )?"
    private const val TAIL = "( please| boss| jarvis| now| any ?more| from now on| again| for me)* $"
    /** What is offered: the next question, a follow-up, what Boss asks next. */
    private const val WHAT = "((the |my |a |that |your )?(usual )?(next questions?|follow ?ups?|follow ?up questions?)|what (i|to) ask( you)? next|whats next|what s next|what is next|what comes next|" +
        // Understanding round 25: "what next" said short, and the offers named as offers.
        "what next|(the |your )?(next question|follow ?up) (offers?|suggestions?))"
    private const val PARTS = "(the levels|levels|the trend|trend|patterns|the patterns|the news|news|volatility|the overview|an overview|my p ?l|the p ?l|my pnl|pnl|an answer|your answer|your answers|each answer)"

    private val WHICH = rx(LEAD + "what do i (usually |normally |always |mostly )?ask( you)? next" + TAIL + "|" +
        LEAD + "what do i (usually |normally |always |mostly )?ask( you)? after $PARTS" + TAIL + "|" +
        LEAD + "(what|which) follow ?ups? do i (usually |normally |always )?ask" + TAIL + "|" +
        LEAD + "(what|which) (follow ?ups?|next questions?) (do you|have you) (offer|offered|learned|learnt|noticed)( me)?" + TAIL + "|" +
        LEAD + "what do you offer( me)? (after|at the end of) $PARTS" + TAIL + "|" +
        LEAD + "why (do|did) you (keep )?(offer|offering|suggest|suggesting|ask|asking)( me)? $WHAT" + TAIL + "|" +
        LEAD + "why (do|did) you (end|finish) (your |the |each )?answers? with a question" + TAIL + "|" +
        LEAD + "(main|mai) (uske|iske|us ke|is ke) baad (aksar |usually |mostly )?(kya|kaunsa sawal|kaun sa sawal) (poochta|puchta|pucchta) (hoon|hu|hun)" + TAIL + "|" +
        LEAD + "(main|mai) (aksar |usually |mostly )?(agla|aage) (kya|kaunsa sawal|kaun sa sawal) (poochta|puchta|pucchta) (hoon|hu|hun)" + TAIL)

    /**
     * Only a clear undo, always naming what is offered: "stop offering what I ask next", "don't suggest the next
     * question", "no more follow-up offers", "agla sawal offer mat karo". Never a market question ("what's next for
     * Nifty", "levels next") and never a stop of an arm ([Commands]' habit undo).
     */
    private val RESET = rx(LEAD + "(stop|quit) (offering|suggesting|asking)( me)? $WHAT" + TAIL + "|" +
        LEAD + "(dont|do not|no need to) (offer|suggest|ask)( me)? $WHAT( after (your |each |an )?answers?)?" + TAIL + "|" +
        LEAD + "no more (next question|follow ?up|follow ?up question) offers" + TAIL + "|" +
        LEAD + "(stop|dont|do not) (end|ending|finish|finishing) (your |the |each )?answers? with (a |the )?(next )?questions?" + TAIL + "|" +
        LEAD + "(agla|aage ka) sawal (mat|na) (poocho|pucho|offer karo|bolo|suggest karo)" + TAIL + "|" +
        LEAD + "(agla|aage ka) sawal offer (mat|na) (karo|kiya karo)" + TAIL + "|" +
        // Understanding round 25: "stop the follow up questions", "turn off follow ups", "no more follow ups", "don't end with a
        // question", "undo the next question thing", and in Hinglish with "mujhe" or "next question" ("follow up band karo").
        LEAD + "(stop|quit|turn off|switch off|disable|no more|no) $WHAT" + TAIL + "|" +
        LEAD + "(stop|dont|do not) (end|ending|finish|finishing) with (a |the )?(next )?questions?" + TAIL + "|" +
        LEAD + "undo (the )?(next question|follow ?up|follow ?up question|what i ask next)( thing| offers?| feature| habit)?" + TAIL + "|" +
        "^ (jarvis )?(mujhe |muje )?(agla|aage ka|next|agle) (sawal|sawaal|question|questions) (mat|na) (poocho|pucho|puchho|offer karo|bolo|suggest karo|do)" + TAIL + "|" +
        "^ (jarvis )?(mujhe |muje )?(agla|aage ka|next|agle) (sawal|sawaal|question|questions) offer (mat|na) (karo|kiya karo)" + TAIL + "|" +
        "^ (jarvis )?(ye |yeh |the )?(follow ?ups?|follow ?up questions?|follow ?up (sawal|sawaal)|next questions?) (band karo|band kar do|band kardo|mat karo|mat do|nahi chahiye|off karo)" + TAIL + "|" +
        // (As [Hinglish.normalize] turns "band karo" round: "follow up band karo" -> "stop follow up".)
        "^ (jarvis )?stop (the |your )?(follow ?ups?|follow ?up questions?|next questions?)" + TAIL)

    /** "What do I usually ask next?" or "stop offering what I ask next", else null. */
    fun asked(text: String): Request? = askedKept.of(text) { askedFresh(text) }

    private val askedKept = Kept<Request?>(64)

    private fun askedFresh(text: String): Request? {
        val t = norm(text)
        return when {
            RESET.containsMatchIn(t) -> Request.RESET
            WHICH.containsMatchIn(t) -> Request.WHICH
            else -> null
        }
    }

    const val ONLY_WORDS = "It is one short question at the end of the answer: I never answer it unasked, your \"yes\" only asks it, and nothing I learn acts."

    private fun named(rs: List<Record>): String = rs.take(3).map { it.say() }.let {
        if (it.size <= 1) it.joinToString("") else it.dropLast(1).joinToString("; ") + "; and " + it.last()
    }

    /** "What do I usually ask next?". */
    fun say(rs: List<Record>): String =
        if (rs.isEmpty()) "I haven't learned what you ask next yet, Boss: I wait for a question you ask within $GAP_MINUTES minutes after " +
            "another at least $MIN_TIMES times, on $MIN_DAYS days and at least half the time. Until then my answers end as they always did."
        else "Boss, in the last $WINDOW_DAYS days ${named(rs)}. So my answer to it ends by offering that one - say \"yes\" and I'll tell you. " +
            "$ONLY_WORDS Say \"$UNDO\" to undo it."

    /** "Stop offering what I ask next". */
    fun sayReset(rs: List<Record>): String =
        if (rs.isEmpty()) "I don't offer you a next question, Boss. I'll start my count afresh from now."
        else "Done, Boss: my answers end as they used to, with nothing offered next, and my count starts afresh from now."

    /** The undo on a locked phone: done all the same, in words that never name what was learned (or whether). */
    const val RESET_LOCKED = "Done, Boss: nothing offered at the end of my answers, and my count starts afresh from now."

    /** The ledger's lines for [r]. */
    fun ledgerWhat(r: Record): String = "${r.phrase.replaceFirstChar { it.uppercase() }} offered next after ${r.afterPhrase}, in one short question"
    fun ledgerWhy(r: Record): String = "${r.say()} in the last $WINDOW_DAYS days; never answered unasked - your \"yes\" asks it"
}
