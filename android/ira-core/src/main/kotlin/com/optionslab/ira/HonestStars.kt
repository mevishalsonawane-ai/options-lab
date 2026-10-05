package com.optionslab.ira

import java.time.LocalDateTime

/**
 * Jarvis learning which of his own confidence scores do not hold up (learning round 18, 2026-10-05). Every trade idea he
 * puts to Boss carries a score, 1 to 5 ([Confidence]); each idea is kept with that score and, each evening, scored on the
 * real option prices - what it made or would have made, taken, approved, rejected or lapsed ([JarvisTrades.Suggestion]).
 * Per score, the newest [RECENT] scored ideas of the last [WINDOW_DAYS] days ([records]) say how often that score worked.
 *
 * A score that sounds sure but has not been borne out - 4 or 5 out of 5 working under half the time, or 3 out of 5 under
 * [POOR] - over at least [MIN] scored ideas ([Record.honest]) is said ALOUD with its record beside it: "Confidence 4 out
 * of 5 - but my 4 out of 5 ideas have been about a coin toss: 4 of the last 10 worked." ([aloud]). Only the spoken
 * question changes: the score itself, the chat, the approval card, whether he asks, acts alone or how much he takes stay
 * exactly as they were (what acts on his record is [ActAlone] and [SelfCalibration], not this). A score that holds up, or
 * too few scored, is said plainly, as before.
 *
 * "How honest are your confidence scores?" ([Request.WHICH]) gives every score's record; "say your confidence plainly"
 * ([UNDO], [Request.RESET]) stops the record being added and starts the count afresh (only ideas after it count). Shown in
 * the Learnings ledger with that undo and reset by "undo everything you learned this week". Counts only, never an amount,
 * a symbol or a headline; Jarvis's ideas and Boss's answers to them, so said on an unlocked phone only. Nothing here acts.
 * Pure: the app keeps the suggestions and the reset time.
 */
object HonestStars {
    /** Only ideas of the last this many days count. */
    const val WINDOW_DAYS = 60L
    /** At most the newest this many scored ideas of one score. */
    const val RECENT = 20
    /** Scored ideas of one score before its record is said. */
    const val MIN = 8
    /** A 4 or 5 out of 5 working under this share is said with its record. */
    const val SURE = 0.5
    /** A 3 out of 5 only when it worked under this share (3 is middling, not a claim of being sure). */
    const val POOR = 0.35

    const val UNDO = "say your confidence plainly"

    /** One scored idea: when it came, the score said with it, whether it made money on the real option prices. */
    data class Scored(val at: LocalDateTime, val stars: Int, val worked: Boolean)

    /** The suggestions that knew their score and have been scored (points known). */
    fun of(suggestions: List<JarvisTrades.Suggestion>): List<Scored> = suggestions.mapNotNull { s ->
        val st = s.stars?.takeIf { it in 1..5 } ?: return@mapNotNull null
        val p = s.points?.takeIf { it.isFinite() } ?: return@mapNotNull null
        Scored(s.at, st, p > 0)
    }

    /** One score's record: how many of its newest scored ideas worked. */
    data class Record(val stars: Int, val worked: Int, val n: Int, val newest: LocalDateTime) {
        val rate: Double get() = if (n == 0) 0.0 else worked.toDouble() / n
        /** Said with its record aloud: a score that sounds surer than its ideas have been. */
        val honest: Boolean get() = n >= MIN && when {
            stars >= 4 -> rate < SURE
            stars == 3 -> rate < POOR
            else -> false
        }
        val phrase: String get() = "$stars out of 5"
        /** The honest word for how it has gone. */
        val word: String get() = if (rate < POOR) "have not held up" else "have been about a coin toss"
        fun say(): String = "$phrase: $worked of the last $n worked"
    }

    /** Each score's record at [now] (the last [WINDOW_DAYS] days, after [resetAt]), highest score first. */
    fun records(scored: List<Scored>, now: LocalDateTime, resetAt: LocalDateTime? = null): List<Record> {
        val from = now.minusDays(WINDOW_DAYS)
        return scored.filter { it.at.isAfter(from) && !it.at.isAfter(now) && (resetAt == null || it.at.isAfter(resetAt)) }
            .groupBy { it.stars }
            .map { (st, xs) ->
                val newest = xs.sortedByDescending { it.at }.take(RECENT)
                Record(st, newest.count { it.worked }, newest.size, newest.first().at)
            }
            .sortedByDescending { it.stars }
    }

    /** The scores said with their record aloud now. */
    fun honest(scored: List<Scored>, now: LocalDateTime, resetAt: LocalDateTime? = null): List<Record> =
        records(scored, now, resetAt).filter { it.honest }

    /**
     * The spoken "Confidence N out of 5." of a trade idea: plain, as it always was, or - for a score in [honest] - with its
     * record beside it. The score is never changed.
     */
    fun aloud(stars: Int, honest: List<Record>): String {
        val r = honest.firstOrNull { it.stars == stars && it.honest } ?: return "Confidence $stars out of 5."
        return "Confidence $stars out of 5 - but my ${r.phrase} ideas ${r.word}: ${r.worked} of the last ${r.n} worked."
    }

    // ---- asked -----------------------------------------------------------------------------------------------------

    enum class Request { WHICH, RESET }

    private fun norm(text: String) = " " + spacedWords(text.lowercase().replace("'", "").replace("’", "")) + " "

    private const val LEAD = "^ (hey |ok |okay )?(jarvis )?(so )?(please )?(can you |could you |would you )?(tell me )?"
    private const val TAIL = "( please| boss| jarvis| now| again| from now on| really| honestly)* $"
    private const val SCORE = "(confidence|confidence (scores?|stars|ratings?|levels?|numbers?)|(star|stars) ratings?|sureness|stars)"
    private const val N = "(1|2|3|4|5|one|two|three|four|five)"

    private val WHICH = rx(LEAD + "how (honest|reliable|accurate|trustworthy|good|right|calibrated|well calibrated) (is|are) (your |jarviss )$SCORE" + TAIL + "|" +
        LEAD + "(is|are) (your |jarviss )$SCORE (honest|reliable|accurate|right|calibrated|any good|worth anything|trustworthy|real)" + TAIL + "|" +
        LEAD + "(can|should) i (trust|believe|go by) (your |jarviss )$SCORE" + TAIL + "|" +
        LEAD + "(does|is) (your )?$N out of (5|five) (mean anything|worth anything|any good|reliable|honest|really $N out of (5|five))" + TAIL + "|" +
        LEAD + "how often (does|do) (your )?$N out of (5|five)( ideas| trades| calls)? (work|come good|come true|pay off|hold up)" + TAIL + "|" +
        LEAD + "(which|what) $SCORE (do you|have you) (qualify|qualified|add (your )?records? to|say with (your |its )?records?|not trust)" + TAIL + "|" +
        LEAD + "(why|how come) (do|did) you (say|add) (it was |its |that it was )?(a coin toss|your record|the record)( (with|to|after) (the |your )?$SCORE)?" + TAIL + "|" +
        LEAD + "(tumhara|aapka|tera) confidence (kitna )?(sahi|sach|bharosemand|theek) (hai|hota hai|nikalta hai)( kya)?" + TAIL + "|" +
        // Round 14: "do your 5 star ideas actually work", "tumhare confidence stars kitne sahi hain".
        LEAD + "(do|does) (your )?$N (star|stars|out of (5|five))( ideas?| trades?| calls?| picks?)? (actually |really )?(work|come good|pay off|hold up)" + TAIL + "|" +
        LEAD + "(tumhare|aapke|tere|tumhara|aapka|tera) $SCORE (kitne|kitna) (sahi|sach|theek|bharosemand) (hain|hai|hote hain|hota hai|nikalte hain)( kya)?" + TAIL)
    private val RESET = rx(LEAD + "(say|give|tell me) (your |the )?$SCORE (plainly|plain|as it is|without (the |your )?records?|only)" + TAIL + "|" +
        LEAD + "just (say|give) (me )?(your |the )?$SCORE" + TAIL + "|" +
        LEAD + "(dont|do not|stop|no need to) (add|adding|say|saying|give|giving|qualify|qualifying) (your |the )?(records?|coin toss)( (with|to|after|on) (your |the )?$SCORE)?( any ?more)?" + TAIL + "|" +
        LEAD + "(dont|do not|stop|no need to) (qualify|qualifying) (your |the )?$SCORE( any ?more)?" + TAIL + "|" +
        LEAD + "confidence (sirf|seedha|bas) (bolo|batao)" + TAIL)

    /** "How honest are your confidence scores?" or "say your confidence plainly", else null. */
    fun asked(text: String): Request? = askedKept.of(text) { askedFresh(text) }

    /** The last words read (speed round 10: the hub reads them as said, then again in its order; [Kept], pure). */
    private val askedKept = Kept<Request?>(64)

    private fun askedFresh(text: String): Request? {
        val t = norm(text)
        return when {
            RESET.containsMatchIn(t) -> Request.RESET
            WHICH.containsMatchIn(t) -> Request.WHICH
            else -> null
        }
    }

    const val ONLY_VOICE = "Only my voice adds it: the score stays as worked out, the chat and the approval card are unchanged, and the record never changes whether I ask, act or how much I take."
    const val LOCKED = "Unlock the phone for my ideas' record, Boss."

    /** "How honest are your confidence scores?": each score's record, and the ones said with it aloud. */
    fun say(scored: List<Scored>, now: LocalDateTime, resetAt: LocalDateTime? = null): String {
        val all = records(scored, now, resetAt)
        if (all.isEmpty()) return "None of my scored trade ideas knew its confidence yet, Boss" +
            (if (resetAt != null) " since you asked me to say it plainly" else "") +
            ". Each evening my ideas are scored on the real option prices; once a score has $MIN, I'll tell you how it held up."
        val counted = all.filter { it.n >= MIN }
        val few = all.filter { it.n < MIN }
        val lines = if (counted.isEmpty()) "" else " " + counted.joinToString("; ") { it.say() } + "."
        val fewLine = if (few.isEmpty()) "" else " Too few yet at " + few.joinToString(", ") { it.phrase } + " to go by."
        val honest = all.filter { it.honest }
        val head = "My trade ideas by the confidence I gave them, in the last $WINDOW_DAYS days, Boss (scored on the real option prices, taken or not):"
        val tail = if (honest.isEmpty())
            " Every score with enough behind it holds up well enough, so I say it plainly. If a 4 or 5 out of 5 works under half the time, I'll say its record beside it aloud."
        else " So aloud I say " + honest.joinToString(" and ") { it.phrase } + " with its record beside it. $ONLY_VOICE Say \"$UNDO\" to stop."
        return head + lines + fewLine + tail
    }

    /** "Say your confidence plainly". */
    fun sayReset(scored: List<Scored>, now: LocalDateTime, resetAt: LocalDateTime? = null): String =
        if (honest(scored, now, resetAt).isEmpty()) "I already say my confidence plainly, Boss. I'll start my count afresh from now."
        else "Done, Boss: I'll say my confidence plainly, and my count starts afresh from now - only ideas from here on count."

    /** The ledger's lines for [r]. */
    fun ledgerWhat(r: Record): String = "${r.phrase}: said aloud with its record beside it"
    fun ledgerWhy(r: Record): String = "only ${r.worked} of my last ${r.n} ideas at that score worked on the real option prices, in the last $WINDOW_DAYS days; " +
        "the score, the chat and what I do are unchanged"
}
