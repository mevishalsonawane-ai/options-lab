package com.optionslab.ira

/**
 * "Thinking" on the globe means one thing only: Jarvis is working on Boss's own question (Boss, 7 Oct: the globe said
 * THINKING at 09:05 with the market closed, the microphone off and nothing asked). Background work - the market read
 * every minute (a slow network read too), the on-device model writing a note of its own - never shows it. And it never
 * sticks: thinking with no answer left to wait for, or longer than [MAX_MS], ends. Pure.
 */
object GlobeThinking {
    /** No question is shown as thought about longer than this (a slow answer is told "still working" long before). */
    const val MAX_MS = 45_000L
    /** Thinking just begun: the answer's own work may not have started yet. */
    const val GRACE_MS = 2_000L

    /** The globe's states, as the orb draws them. */
    const val REST = 0
    const val LISTENING = 1
    const val THINKING = 2
    const val ANSWERING = 3

    /**
     * Thinking that should end now (back to listening, or rest): [since] when it began (elapsed ms; 0 or less = not
     * known), [working] the answer to Boss's question still being worked out, [speaking] Jarvis is talking (then it is
     * not stuck thinking - the speech's own end moves it on).
     */
    fun stale(now: Long, since: Long, working: Boolean, speaking: Boolean): Boolean {
        if (speaking) return false
        if (since <= 0L) return !working
        val age = now - since
        if (age >= MAX_MS || age < 0L) return true
        return !working && age >= GRACE_MS
    }

    /**
     * What the globe shows: [typed] the typed exchange (0 none, else its state), else the voice's state [voice] - its
     * thinking only while [voiceStale] is false (thinking for [MAX_MS] or more). [drafting]: Boss is typing a question
     * (listening). [askedWork]: work Boss asked for himself and waits on (his backtest). Background work is not an
     * argument here on purpose: it never shows as thinking.
     */
    fun globe(typed: Int, voice: Int, voiceStale: Boolean = false, drafting: Boolean = false, askedWork: Boolean = false): Int {
        if (typed in LISTENING..ANSWERING) return typed
        val v = if (voice == THINKING && voiceStale) REST else voice.coerceIn(REST, ANSWERING)
        return when {
            v != REST -> v
            drafting -> LISTENING
            askedWork -> THINKING
            else -> REST
        }
    }
}
