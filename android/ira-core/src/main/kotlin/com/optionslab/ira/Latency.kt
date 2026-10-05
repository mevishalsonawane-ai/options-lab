package com.optionslab.ira

/** How long Boss waited for spoken answers (Boss, 4 Oct: "late response"): one line for the diagnostics. Pure. */
object Latency {
    const val KEEP = 50

    fun add(list: List<Long>, ms: Long): List<Long> = if (ms <= 0 || ms >= 60_000) list else (list + ms).takeLast(KEEP)

    /**
     * Jarvis noticing he is slow (his own initiative): the last three spoken answers each took over [SLOW_MS] and he is
     * not on the fastest model - a suggestion only; the choice stays Boss's.
     */
    const val SLOW_MS = 5_000L
    /** How the suggestion begins (a note beside answers, never taken for one). */
    const val NUDGE = "Boss, my last three answers each took"

    fun suggestFaster(list: List<Long>, onFastest: Boolean): String? =
        if (onFastest || list.size < 3 || list.takeLast(3).any { it <= SLOW_MS }) null
        else "$NUDGE over ${SLOW_MS / 1000} seconds. The fastest AI model (Qwen2.5 0.5B) would answer quicker: " +
            "Settings, Voice and AI model. It's your choice; nothing changes until you pick it."

    fun say(list: List<Long>): String? {
        if (list.isEmpty()) return null
        val s = list.sorted()
        val median = s[s.size / 2]
        fun sec(ms: Long) = "%.1f s".format(java.util.Locale.ENGLISH, ms / 1000.0)
        return "Spoken answers: ${list.size}, typical wait ${sec(median)}, slowest ${sec(s.last())}, last ${sec(list.last())}."
    }

    /** The first word is due this soon after Boss's last word (Voice, round 22), for an answer the phone works out itself. */
    const val FIRST_WORD_MS = 1_500L

    /**
     * [say], and where the wait went (Voice, round 22): [voice] holds, for the last spoken answers (the newest of [list],
     * in step), the part from the reply handed to the voice to its first sound (the speech engine) - the rest is the
     * answer worked out and its words shaped. Also how many of those first words came within [FIRST_WORD_MS]. [voice]
     * empty or longer than [list]: [say] alone.
     */
    fun say(list: List<Long>, voice: List<Long>): String? {
        val base = say(list) ?: return null
        if (voice.isEmpty() || voice.size > list.size) return base
        val recent = list.takeLast(voice.size)
        val sv = voice.sorted()
        val answer = recent.zip(voice) { t, v -> (t - v).coerceAtLeast(0L) }.sorted()
        fun sec(ms: Long) = "%.1f s".format(java.util.Locale.ENGLISH, ms / 1000.0)
        val fast = recent.count { it <= FIRST_WORD_MS }
        return "$base Typically ${sec(answer[answer.size / 2])} to the answer and ${sec(sv[sv.size / 2])} for the voice to start; " +
            "first word within ${sec(FIRST_WORD_MS)}: $fast of ${recent.size}."
    }

    /** [add] for the voice's part ([say]): kept in step with the waits ([ms] the voice's part of the wait [took]). */
    fun addVoice(voice: List<Long>, waits: List<Long>, took: Long, ms: Long): List<Long> =
        if (took <= 0 || took >= 60_000) voice else (voice + ms.coerceIn(0L, took)).takeLast(KEEP).takeLast(waits.size)

    /** "How fast are you?", "how long do you take to answer", "kitna time lagta hai": his own answer times, aloud. */
    fun asked(text: String): Boolean =
        rx("(?i)^\\W*(jarvis,?\\s+)?(how (fast|quick|quickly|slow) (are you|do you answer|are you answering|are your answers)|" +
            "how long do you take( to answer)?|are you (slow|fast)( today)?|your (speed|answer time|response time)|" +
            "tum kitna time (lagate|lete) ho|jawab (mein|me) kitna time( lagta hai)?|kitna time lagta hai (jawab (mein|me)|tumhe))\\W*$").containsMatchIn(text)

    /** The spoken form of [say]: typical and slowest wait, and on which model ([model] its name). */
    fun spoken(list: List<Long>, model: String, onFastest: Boolean = true): String {
        if (list.isEmpty()) return "I haven't timed a spoken answer yet, Boss. Ask me something by voice first."
        val s = list.sorted()
        fun sec(ms: Long) = "%.1f".format(java.util.Locale.ENGLISH, ms / 1000.0)
        return "Over my last ${if (list.size == 1) "spoken answer" else "${list.size} spoken answers"}, Boss, you waited about ${sec(s[s.size / 2])} seconds, " +
            "${sec(s.last())} at the slowest. I'm on $model." +
            (if (!onFastest && s[s.size / 2] > SLOW_MS) " The fastest model (Qwen2.5 0.5B) would answer quicker: Settings, Voice and AI model. Your choice, Boss." else "")
    }
}
