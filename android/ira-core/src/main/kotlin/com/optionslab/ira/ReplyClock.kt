package com.optionslab.ira

/**
 * The spoken-answer wait ("Spoken answers ... wait" in the diagnostics): from Boss's words read to the FIRST SOUND of
 * the reply to them - and only that reply. Pure; thread-safe (the voice's progress arrives on another thread).
 *
 * Root cause it fixes (Boss, 5 Oct: "typical wait 37.0 s" while the turn log said "answer ready 4.8 s after the
 * words"): the time of the words was one loose value, taken by whatever "answer" or "question" next made a sound. A
 * reply never said (muted, a repeat dropped, a trade asked separately, a cut) left it standing, and a later
 * announcement or a stalled voice was then timed from an earlier turn. Now the time is bound to the one utterance the
 * reply is handed to the voice as ([queued]), and a new turn ([heard]) or a dropped reply ([drop]) clears it.
 */
class ReplyClock {
    private var pendingAt = 0L
    private var boundTo: String? = null
    private var boundAt = 0L
    /** When the bound reply was handed to the voice (elapsed ms). */
    private var queuedAt = 0L

    /** Boss's words were read at [at] (elapsed ms): the next reply is timed from here; an older one no longer is. */
    @Synchronized fun heard(at: Long) { pendingAt = at; boundTo = null; boundAt = 0L }

    /** Words were read and no reply is bound or timed yet (for "answer ready ... after the words"). */
    @Synchronized fun pendingSince(): Long = pendingAt

    /**
     * The reply to the words last [heard] is handed to the voice now as utterance [id] (its pieces are "[id].k"). Only
     * the first reply after the words is timed; words older than [MAX_MS] are not (another turn's).
     */
    @Synchronized fun queued(id: String, now: Long) {
        val at = pendingAt
        pendingAt = 0L
        if (at > 0 && now >= at && now - at < MAX_MS) { boundTo = id; boundAt = at; queuedAt = now }
    }

    /** The turn ended with nothing said for it (muted, dropped, cancelled): nothing is timed from it. */
    @Synchronized fun drop() { pendingAt = 0L; boundTo = null; boundAt = 0L }

    /**
     * Utterance (or piece) [id] made its first sound at [now]: the wait in ms when it is the bound reply's first sound,
     * else null (an announcement, "Yes, Boss?", a later piece, a reply already timed).
     */
    @Synchronized fun started(id: String?, now: Long): Long? = startedSplit(id, now)?.total

    /**
     * The wait split in two (Voice, round 22: where it goes): [total] from Boss's words to the first sound; [voice] of it
     * from the reply handed to the voice to that sound (the speech engine making the first sentence into sound); the
     * rest is the answer being worked out and the words shaped for speech.
     */
    data class Split(val total: Long, val voice: Long)

    /** [started], with the voice's part of the wait ([Split]). */
    @Synchronized fun startedSplit(id: String?, now: Long): Split? {
        val b = boundTo ?: return null
        if (id == null || !(id == b || id.startsWith("$b."))) return null
        boundTo = null
        val took = now - boundAt
        return if (took in 0 until MAX_MS) Split(took, (now - queuedAt).coerceIn(0L, took)) else null
    }

    companion object {
        /** A wait this long or longer is not a reply's (the words were another turn's). */
        const val MAX_MS = 60_000L
    }
}
