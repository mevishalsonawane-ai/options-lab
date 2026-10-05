package com.optionslab.ira

/**
 * How Boss's cuts over Jarvis go (Boss, 5 Oct: "Jarvis stop" did not stop him): how many were heard, and how long from
 * the stop being read to his voice going silent. Durations only - never a word heard. For the diagnostics' cut-in line.
 * Pure: a tally in, a tally out.
 */
object CutStops {
    /** [heard]: cuts heard while he spoke; [ms]: the last [KEEP] times from a cut read to silence, oldest first. */
    data class Tally(val heard: Int = 0, val ms: List<Long> = emptyList())

    /** Times kept for the median. */
    const val KEEP = 20
    /** A time longer than this is no measure of the cut (the voice's stop never reported, the phone asleep). */
    const val MAX_MS = 10_000L

    /** A cut heard over him (the name, or a stop word). */
    fun heard(t: Tally): Tally = t.copy(heard = t.heard + 1)

    /** His voice went silent [ms] after the cut was read. */
    fun silent(t: Tally, ms: Long): Tally = if (ms < 0 || ms > MAX_MS) t else t.copy(ms = (t.ms + ms).takeLast(KEEP))

    /** The median time to silence, or null with none measured. */
    fun median(t: Tally): Long? {
        if (t.ms.isEmpty()) return null
        val s = t.ms.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
    }

    /** "stops heard N, stop-to-silence median X ms" (no median yet: "not measured yet"). */
    fun say(t: Tally): String =
        "stops heard ${t.heard}, stop-to-silence median " + (median(t)?.let { "$it ms" } ?: "not measured yet")
}
