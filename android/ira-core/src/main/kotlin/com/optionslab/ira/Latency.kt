package com.optionslab.ira

/** How long Boss waited for spoken answers (Boss, 4 Oct: "late response"): one line for the diagnostics. Pure. */
object Latency {
    const val KEEP = 50

    fun add(list: List<Long>, ms: Long): List<Long> = if (ms <= 0 || ms >= 60_000) list else (list + ms).takeLast(KEEP)

    fun say(list: List<Long>): String? {
        if (list.isEmpty()) return null
        val s = list.sorted()
        val median = s[s.size / 2]
        fun sec(ms: Long) = "%.1f s".format(java.util.Locale.ENGLISH, ms / 1000.0)
        return "Spoken answers: ${list.size}, typical wait ${sec(median)}, slowest ${sec(s.last())}, last ${sec(list.last())}."
    }
}
