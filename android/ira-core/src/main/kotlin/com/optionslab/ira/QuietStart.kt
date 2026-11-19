package com.optionslab.ira

/**
 * 19 Nov 2026 (audit batch 2: Android ended the app at 08:15 and 08:30 for "excessive CPU / binder traffic during cached"). An
 * alarm in the small hours (the recorder's pre-open read, an hourly night job) starts the whole app afresh, and the start read
 * every position book, decrypted Jarvis's large settings and began the non-trading workers - all for a few seconds' job, after
 * which Android counted the work against a cached process and ended it.
 *
 * A start is QUIET - only the job that started it runs; the position books, the large settings and the warm-ups wait until the
 * screen opens, the market watch starts or a job asks for them - only when ALL of:
 *  - no market is open or opens within [NEAR_MIN] minutes: NSE from its watch's start (09:00) to [AFTER_MIN] past F&O's close
 *    (the evening look for a carried protection runs up to 30 minutes past it), MCX from its open to [AFTER_MIN] past its close
 *    (yesterday's session too, past midnight);
 *  - the app's own plain mark says nothing was held when it was last written ([heldMark] "0"; missing or anything else: held).
 * Anything unknown is not quiet: then the start is the full one, positions first, exactly as before. Pure.
 */
object QuietStart {
    const val NEAR_MIN = 10
    const val AFTER_MIN = 35
    /** The plain mark's value for "nothing held" (anything else, or none, counts as held). */
    const val FLAT = "0"
    const val HELD = "1"

    /** A market's session on a day, in minutes from midnight. */
    data class Window(val open: Int, val close: Int)

    /**
     * Is a start at [minute] quiet? [nse]: NSE's watch window today (from its watch's start to F&O's close), null on a closed day;
     * [mcxToday] / [mcxYesterday]: MCX's sessions (null: none), [heldMark]: the plain mark. A window that cannot be read is passed
     * as [unknown] true (not quiet).
     */
    fun quiet(minute: Int, nse: Window?, mcxToday: Window?, mcxYesterday: Window?, heldMark: String?, unknown: Boolean = false): Boolean {
        if (unknown || heldMark != FLAT) return false
        fun near(w: Window?): Boolean = w != null && minute >= w.open - NEAR_MIN && minute <= w.close + AFTER_MIN
        if (near(nse) || near(mcxToday)) return false
        // Yesterday's MCX session: its evening look can run past midnight (a close of 23:55 plus 35 minutes is 00:30).
        if (mcxYesterday != null && mcxYesterday.close + AFTER_MIN >= 24 * 60 && minute <= mcxYesterday.close + AFTER_MIN - 24 * 60) return false
        return true
    }
}
