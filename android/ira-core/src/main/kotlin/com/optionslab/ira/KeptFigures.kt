package com.optionslab.ira

import java.time.LocalTime

/**
 * Speed, round 4: an account question ("what's my P&L", "my positions") answered from the figures read a moment ago,
 * instead of waiting for a fresh read of the paper book's prices and Zerodha (each up to 8 s) every time. Pure.
 *
 * Root cause (Boss, 5 Oct: "Answer is taking a lot after question is asked"): the kept figures counted for 30 s only,
 * and since battery round 11 they are read ahead every 2 minutes with the screen off - so most questions read afresh
 * and waited. Now:
 *  - kept under [FRESH_MS]: said as now (as before);
 *  - kept under [SERVE_MS]: said at once, with "as of HH:MM" once older than [NOTE_AFTER_MS], and read again behind;
 *  - older, or not all kept: read afresh - waited on [WAIT_MS] at most when a kept copy under [STAND_IN_MS] can stand in
 *    (then said with its time), else waited on as before.
 * Anything done (an order, a command) clears the kept figures, so they are never said over a change Jarvis made.
 * Words only: nothing here places, changes or guards anything.
 */
object KeptFigures {
    const val FRESH_MS = 30_000L
    /** Figures kept this long say their time ("As of 14:05") - anything older than [FRESH_MS] (review, 5 Oct). */
    const val NOTE_AFTER_MS = FRESH_MS
    const val SERVE_MS = 5 * 60_000L
    const val WAIT_MS = 3_000L
    const val STAND_IN_MS = 30 * 60_000L

    enum class Use { KEPT, KEPT_AND_REFRESH, READ }

    /** What to do with the asked sections' kept ages (ms; null: that section is not kept). */
    fun use(ages: List<Long?>): Use {
        if (ages.isEmpty() || ages.any { it == null || it < 0 }) return Use.READ
        val oldest = ages.maxOf { it!! }
        return when {
            oldest < FRESH_MS -> Use.KEPT
            oldest < SERVE_MS -> Use.KEPT_AND_REFRESH
            else -> Use.READ
        }
    }

    /** A kept copy may stand in for a fresh read that is slow (every section kept, none older than [STAND_IN_MS]). */
    fun standIn(ages: List<Long?>): Boolean = ages.isNotEmpty() && ages.all { it != null && it in 0 until STAND_IN_MS }

    /** The oldest of the ages (ms), or null. */
    fun oldest(ages: List<Long?>): Long? = ages.filterNotNull().maxOrNull()

    /**
     * Said before an answer from figures [ageMs] old at [now] (the phone's clock): null under [NOTE_AFTER_MS]. [slow]:
     * the fresh read did not come in time. It ends with a colon, so a one-sentence spoken answer still carries the figures.
     */
    fun note(ageMs: Long, now: LocalTime, slow: Boolean = false): String? {
        if (ageMs < NOTE_AFTER_MS && !slow) return null
        val at = now.minusNanos(ageMs.coerceAtLeast(0L) * 1_000_000L)
        val hhmm = "%02d:%02d".format(java.util.Locale.ENGLISH, at.hour, at.minute)
        return if (slow) "As of $hhmm, Boss (your account is slow to answer just now):"
        else "As of $hhmm, Boss (I'm reading your account again now):"
    }

    /** [text] with [note] before it (unchanged when there is none). */
    fun dress(text: String, note: String?): String = if (note.isNullOrBlank()) text else "$note $text"
}

/**
 * Speed, round 4: how long the voice may wait for the on-device model before speaking. Pure.
 *
 * Root cause (Boss's diagnostics, 5 Oct: "3.8 s to the answer and 33.4 s for the voice to start", the model not loaded):
 * between an answer handed to the voice and its first sound, a Hindi reply waited for the model to load and translate it
 * (bounded only by the model's 30 s cutoff), and a free-form question's model passes started at once, loading the model
 * and holding the fast cores while the speech engine tried to make "One moment, Boss" into sound.
 */
object ModelWait {
    /** A Hindi translation is waited on this long at most, and only when the model is already loaded; else English now. */
    const val HINDI_MS = 2_500L
    /** Free-form words: both model passes (what was meant, then a short reply) within this, in all. */
    const val FREE_FORM_MS = 12_000L
    /** The first pass's own share (the reading of what was meant). */
    const val FIRST_PASS_MS = 8_000L
    /** A second pass is started only with this much of [FREE_FORM_MS] left. */
    const val MIN_PASS_MS = 3_000L
    /** The voice's holding line ("One moment") is given this long to make its first sound before the model starts. */
    const val HOLD_FIRST_MS = 2_500L

    /** How long to wait for a Hindi translation: 0 (none: say it in English now) unless the model is loaded. */
    fun hindiWait(on: Boolean, loaded: Boolean): Long = if (on && loaded) HINDI_MS else 0L

    /** What is left of the free-form budget after [spentMs]. */
    fun left(spentMs: Long): Long = (FREE_FORM_MS - spentMs.coerceAtLeast(0L)).coerceAtLeast(0L)

    /** Is a second model pass worth starting with [leftMs] left? */
    fun another(leftMs: Long): Boolean = leftMs >= MIN_PASS_MS

    /** When to say "One moment, Boss" for free-form words: at once when the model must load first, else after 0.6 s. */
    fun holdAfter(loaded: Boolean): Long = if (loaded) 600L else 0L

    /**
     * Does the model wait (up to [HOLD_FIRST_MS]) for "One moment" to start sounding? Only for a [heard] question (a typed
     * one has no line to wait for) with the model still to load ([loaded]: no load competes with the voice, and "One
     * moment" is not said before 0.6 s anyway), while Jarvis is [listening] and not muted (review, 5 Oct).
     */
    fun waitForHold(heard: Boolean, loaded: Boolean, listening: Boolean): Boolean = heard && !loaded && listening
}
