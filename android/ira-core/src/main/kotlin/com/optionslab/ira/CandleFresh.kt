package com.optionslab.ira

import java.time.LocalDateTime

/**
 * 20 Nov 2026 (audit, B5 items 6 and 8): how old a candle may be and still be read as "now". Pure: no clock of its own.
 *
 *  - Pine ([pineFresh]): the newest completed candle of a script's chart closed at most one candle plus [LAG_SEC] ago - the next
 *    candle may be closed but not published yet, never more. Before, 3 candles plus 2 minutes passed (about 17 minutes on 5m).
 *  - Solo 2.0's index ([indexStale]): the index's newest 1-minute bar started more than [SOLO_MAX_MINUTES] minutes before the
 *    minute now, so its stop cannot be judged on it and the premium backstop applies.
 */
object CandleFresh {
    /** How late a just-closed candle may be published by the feed (seconds). */
    const val LAG_SEC = 90L

    /** One day (seconds): a daily or longer chart is not judged here (its candle is a day old by design). */
    private const val DAY_SEC = 86_400L

    /**
     * Whether the completed candle that started at [lastStartSec] (epoch seconds) on a chart of [stepSec] seconds is still the
     * newest a feed can have at [nowSec]: closed ([lastStartSec] + [stepSec]) at most [stepSec] + [LAG_SEC] ago. A daily or longer
     * chart, or a step that is not positive, is always fresh (as before: the day check covers it).
     */
    fun pineFresh(lastStartSec: Long, stepSec: Long, nowSec: Long): Boolean {
        if (stepSec <= 0 || stepSec >= DAY_SEC) return true
        val sinceClose = nowSec - (lastStartSec + stepSec)
        return sinceClose <= stepSec + LAG_SEC
    }

    /** Solo's index minutes may be this old (from the bar's start to the minute now) and still judge its stop. */
    const val SOLO_MAX_MINUTES = 3L

    /** Whether the index's newest 1-minute bar ([lastStart]; null: none) is too old to judge a stop on at [now]. */
    fun indexStale(lastStart: LocalDateTime?, now: LocalDateTime, maxMinutes: Long = SOLO_MAX_MINUTES): Boolean =
        lastStart == null || lastStart.isBefore(now.withSecond(0).withNano(0).minusMinutes(maxMinutes))
}
