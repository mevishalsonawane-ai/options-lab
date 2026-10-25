package com.optionslab.ira

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Why Android ended the app's process, in plain words (16 Oct). Boss's diagnostics showed "the app's process had ended while
 * the watch ran" four times in one session (09:14, 11:13, 12:28, 13:27) with "no crash seen, no ANR": the app's own crash
 * handler sees only Kotlin/Java crashes, never a native crash (the language model, a native library), Android's
 * low-memory killer, a stop for excessive background use or a force stop. Android keeps its own record of each ending
 * (ApplicationExitInfo, Android 11+); the app reads it at its next start and the diary says which one it was.
 *
 * Pure: the codes are Android's (ApplicationExitInfo.REASON_*, ActivityManager.RunningAppProcessInfo.IMPORTANCE_*), passed
 * in as numbers so this needs no Android. Words and numbers only: Android's own description is reduced to plain characters.
 */
object ExitReason {
    /** ApplicationExitInfo.REASON_LOW_MEMORY. */
    const val LOW_MEMORY = 3
    const val CRASH = 4
    const val CRASH_NATIVE = 5
    const val ANR = 6
    const val EXCESSIVE_RESOURCE_USAGE = 9

    fun reason(code: Int): String = when (code) {
        1 -> "the app ended itself"
        2 -> "a system signal ended it"
        LOW_MEMORY -> "Android's low-memory killer (the phone was short of memory)"
        CRASH -> "a crash in the app"
        CRASH_NATIVE -> "a native crash (the language model or a native library)"
        ANR -> "Android closed it as not responding (ANR)"
        7 -> "it failed to start"
        8 -> "a permission was changed"
        EXCESSIVE_RESOURCE_USAGE -> "Android stopped it for using too much CPU or battery in the background"
        10 -> "stopped by the user (force stop or swiped away)"
        11 -> "stopped by the user (the app was stopped in Settings)"
        12 -> "a process it depends on ended"
        13 -> "Android ended it (other system reason)"
        14 -> "Android's freezer ended it"
        15 -> "the app's package state changed"
        16 -> "the app was updated"
        else -> "an unknown reason ($code)"
    }

    /** What the process was to Android when it ended (its importance). */
    fun importance(i: Int): String = when {
        i <= 100 -> "on screen"
        i <= 125 -> "running the foreground watch"
        i <= 200 -> "visible"
        i <= 230 -> "perceptible"
        i <= 300 -> "a background service"
        i <= 325 -> "on screen, phone asleep"
        i <= 350 -> "heavy background"
        i <= 400 -> "cached (in the background, nothing running)"
        else -> "gone"
    }

    /** Ending the watch's own way: a crash, a native crash or an ANR is the app's fault; the rest are Android's. */
    fun appFault(code: Int): Boolean = code == CRASH || code == CRASH_NATIVE || code == ANR

    private val PLAIN = Regex("[^A-Za-z0-9 _.,:()/-]")
    private val HMS = DateTimeFormatter.ofPattern("dd MMM HH:mm:ss", java.util.Locale.ENGLISH)

    /**
     * The diary's line for one ending: when, why, what the process was then, its memory (pss/rss in kB, 0: not known) and
     * Android's own short description, cleaned. "process ended 16 Oct 09:14:39: Android's low-memory killer (...) · it was
     * running the foreground watch · memory 412 MB (rss 655 MB)".
     */
    fun line(atMs: Long, zone: ZoneId, code: Int, importance: Int, pssKb: Long, rssKb: Long, description: String?): String {
        val mem = if (pssKb > 0 || rssKb > 0)
            " · memory ${if (pssKb > 0) "${pssKb / 1024} MB" else "?"}${if (rssKb > 0) " (rss ${rssKb / 1024} MB)" else ""}" else ""
        val why = description?.replace(PLAIN, "")?.trim()?.take(80)?.takeIf { it.isNotEmpty() }?.let { " · Android: $it" }.orEmpty()
        return "process ended ${HMS.withZone(zone).format(Instant.ofEpochMilli(atMs))}: ${reason(code)} · it was ${importance(importance)}$mem$why"
    }

    /** The endings newer than [sinceMs] (the last one told), oldest first, at most [max]: (timestamp, item). */
    fun <T> fresh(all: List<Pair<Long, T>>, sinceMs: Long, max: Int = 5): List<Pair<Long, T>> =
        all.filter { it.first > sinceMs }.sortedBy { it.first }.takeLast(max)
}
