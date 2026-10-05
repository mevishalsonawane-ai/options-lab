package com.optionslab.ira

/**
 * Listening turns that gave no words, told apart and said plainly (Boss, 5 Oct, 09:56-09:59, diagnostics): "speech
 * began / speech ended / error 7, loudest 6 dB, read 2 word(s) mid-turn / ready" every ~10 s for two minutes, then
 * "error 7, loudest 2-10 dB, read nothing / ready" every 5 s. The first is sound with speech in it that never became
 * words for Jarvis - a TV or people across the room, or (in a turn opened while he talks, cut-in) his own voice coming
 * back through the microphone; the second is a quiet room, where the on-device recognizer ends each turn as "no match"
 * after about 5 s and is started again.
 *
 * - [echo]: words read in a turn opened while Jarvis talked that are only his own words heard back - never kept as the
 *   turn's words, never a question, never "words read mid-turn" (his name is never his own: he never says it).
 * - [restartIn]: after many empty turns in a row, the next turn starts a little later (fewer restarts and start beeps,
 *   less work for the recognizer) - never while Boss is expected to speak, and listening for "Jarvis" goes on.
 * - [say]: the voice check's line for the pattern ("I keep hearing sound but no words - likely the TV or my own voice").
 *
 * Kinds and counts only, never a word heard. Only ever listens less or lets words go: nothing acts. Pure.
 */
object EmptyTurns {
    enum class Kind {
        /** Words for Jarvis were read (the turn was his to answer or let go by the usual rules). */
        WORDS,
        /** Only his own words came back (a turn opened while he talked). */
        ECHO,
        /** The recognizer heard speech (or a few stray words without his name) and found no words for him. */
        SPEECH,
        /** No speech began: a quiet room or steady noise, the turn ended as "no match" or silence. */
        QUIET,
    }

    /** Recent turns kept for the voice check. */
    const val KEEP = 20
    /** Empty turns in a row (about a minute of 5 s turns) before the restart slows. */
    const val CALM_AFTER = 12
    /** ...and (about three minutes) before it slows again. */
    const val SLOW_AFTER = 36
    const val QUICK_MS = 200L
    const val CALM_MS = 700L
    const val SLOW_MS = 1_500L

    /** The turn's kind: words for Jarvis read ([words]), only his own words ([echoOnly]), speech that began, or none. */
    fun kind(words: Boolean, echoOnly: Boolean, speechBegan: Boolean): Kind = when {
        words -> Kind.WORDS
        echoOnly -> Kind.ECHO
        speechBegan -> Kind.SPEECH
        else -> Kind.QUIET
    }

    /** [kind] added to [recent] (the last [KEEP]). */
    fun add(recent: List<Kind>, kind: Kind): List<Kind> = (recent + kind).takeLast(KEEP)

    /** Empty turns in a row after [kind]: words reset the run, anything else adds to it. */
    fun inRow(before: Int, kind: Kind): Int = if (kind == Kind.WORDS) 0 else before + 1

    /**
     * Before the next turn after an empty one, [emptyInRow] empty turns in a row: [QUICK_MS] as before, [CALM_MS] after
     * [CALM_AFTER], [SLOW_MS] after [SLOW_AFTER]. Never slowed while Boss is [expected] to speak (after "Yes, Boss?", a
     * follow-up window, a yes or no awaited).
     */
    fun restartIn(emptyInRow: Int, expected: Boolean): Long = when {
        expected || emptyInRow < CALM_AFTER -> QUICK_MS
        emptyInRow < SLOW_AFTER -> CALM_MS
        else -> SLOW_MS
    }

    /** Stop words and yes / no words: a turn with one in it is always Boss's ([echo]). */
    val BOSS_ONLY = setOf("stop", "ruko", "bas", "chup", "cancel", "yes", "no", "haan", "nahi", "ha", "na")

    private fun words(s: String?): List<String> =
        (s ?: "").lowercase().replace(rx("[^a-z0-9 ]"), " ").split(' ').filter { it.isNotEmpty() }

    /**
     * Is [partial], read in a turn opened while Jarvis was saying [saying], only his own voice heard back? Every word
     * of a short reading (three words or fewer), three in four of a longer one, are words he is saying - and never his
     * name (he never says it, so it is always Boss's), and never with a stop word ("stop", "ruko", "bas", "chup",
     * "cancel") or a yes or no ("yes", "no", "haan", "nahi", "ha", "na") in it: Boss cutting in, or answering, is never
     * let go as Jarvis's own voice, even when Jarvis happens to be saying the same word.
     */
    fun echo(partial: String?, saying: String?): Boolean {
        val h = words(partial)
        if (h.isEmpty() || saying.isNullOrBlank() || Wake.named(partial ?: "")) return false
        if (h.any { it in BOSS_ONLY }) return false
        val said = words(saying).toSet()
        if (said.isEmpty()) return false
        val mine = h.count { it in said }
        return if (h.size <= 3) mine == h.size else mine * 4 >= h.size * 3
    }

    /**
     * The voice check's plain words for the [recent] turns (oldest first) with [emptyInRow] empty ones in a row now,
     * [cutIn] when he listens while he talks - or null when nothing stands out.
     */
    fun say(recent: List<Kind>, emptyInRow: Int, cutIn: Boolean): String? {
        val last = recent.takeLast(12)
        if (last.isEmpty()) return null
        val echo = last.count { it == Kind.ECHO }
        val speech = last.count { it == Kind.SPEECH }
        val words = last.count { it == Kind.WORDS }
        val n = last.size
        return when {
            echo >= 2 && echo >= speech ->
                "I keep hearing my own voice come back while I talk ($echo of my last $n turns" +
                    (if (cutIn) ", because cut-in listens as I speak" else "") + "): I ignore it, but a headset or a lower " +
                    "volume keeps my ears free for you."
            speech + echo >= 3 && speech + echo > words ->
                "I keep hearing sound but no words - likely the TV or my own voice ($speech of my last $n turns had speech I " +
                    "could not read" + (if (cutIn) "; cut-in is on, so I also listen while I talk" else "") + "). Say \"Jarvis\" " +
                    "close to the phone, or turn the TV down."
            emptyInRow >= CALM_AFTER ->
                "It has been quiet for a while ($emptyInRow turns with no words): I start listening a little less often to " +
                    "save battery, and still wake to \"Jarvis\"."
            else -> null
        }
    }
}
