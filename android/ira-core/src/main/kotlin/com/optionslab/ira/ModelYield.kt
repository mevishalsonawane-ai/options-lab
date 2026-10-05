package com.optionslab.ira

/**
 * When the on-device model may use the phone's processor while Jarvis listens and speaks, and the turn rules that keep
 * Boss's speech from being cut. Pure.
 *
 * Root cause (Boss, 5 Oct, diagnostics with the model "writing true"): the model's rewrite of an answer - words for the
 * screen only; the voice always says the rule-based answer - started 1.5 s after the answer was posted whenever the voice
 * had not made a sound yet, and then held the phone's fast cores for up to its 30 s limit at almost normal priority. The
 * phone's voice (text-to-speech) and its speech recognizer were starved: the first sound came 37 s after Boss's words
 * (4.8 s + 1.5 s + the 30 s limit), and 7-9 s of Boss speaking read nothing ("speech began ... speech ended ... error 7,
 * read nothing"). Now the rewrite waits until the voice is free and gives way (is stopped) as soon as Jarvis speaks or
 * Boss starts speaking.
 */
object ModelYield {
    /** After Boss's speech ended, the recognizer still needs the processor this long for its final reading. */
    const val AFTER_SPEECH_MS = 2_500L
    /** A rewrite that could not start within this long is dropped (the answer shown stands). */
    const val MAX_WAIT_MS = 60_000L

    /**
     * Boss is speaking into a listening turn now: speech began at [speechAt] (0: not this turn) and has not ended
     * ([endAt] before it, or 0), or ended less than [AFTER_SPEECH_MS] ago, or his words last changed ([wordsAt], 0: none)
     * less than that ago (he went on after a pause). A turn begun while Jarvis talks ([inSpeech]) holds his own voice
     * heard back, not only Boss's.
     */
    fun bossSpeaking(listening: Boolean, inSpeech: Boolean, speechAt: Long, endAt: Long, now: Long, wordsAt: Long = 0L): Boolean =
        listening && !inSpeech && speechAt > 0 &&
            (endAt < speechAt || now - endAt < AFTER_SPEECH_MS || (wordsAt > 0 && now - wordsAt < AFTER_SPEECH_MS))

    /**
     * The voice needs the processor: an answer is being worked out ([thinking]) or said ([speaking]) - its first sound
     * waits on the speech engine - or Boss is speaking ([bossSpeaking]). The model's rewrite neither starts nor goes on.
     */
    fun voiceBusy(speaking: Boolean, thinking: Boolean, bossSpeaking: Boolean): Boolean = speaking || thinking || bossSpeaking

    /**
     * The watchdog may reset a listening turn opened at [listenedAt]: after [TURN_MS], but never while Boss is speaking
     * into it (a reset then loses his words) unless the turn is over [HARD_MS] old (a stuck one).
     */
    fun mayResetTurn(listenedAt: Long, bossSpeaking: Boolean, now: Long): Boolean {
        val age = now - listenedAt
        return age > TURN_MS && (!bossSpeaking || age > HARD_MS)
    }
    const val TURN_MS = 25_000L
    const val HARD_MS = 45_000L

    /**
     * Cut-in listening while Jarvis talks starts [startMs] into his SOUND, not into the call that queued the words at
     * [queuedAt]: ms from [now] until it may start, or null while no sound has come yet ([soundAt] before [queuedAt]: the
     * speech engine is still making the first sentence, and the recognizer is not opened over it).
     */
    fun cutInIn(queuedAt: Long, soundAt: Long, startMs: Long, now: Long): Long? =
        if (soundAt < queuedAt) null else maxOf(0L, soundAt + startMs - now)
}
