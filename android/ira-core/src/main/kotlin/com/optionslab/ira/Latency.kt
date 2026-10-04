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
}
