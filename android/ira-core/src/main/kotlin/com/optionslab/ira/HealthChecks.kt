package com.optionslab.ira

import java.time.LocalDateTime
import java.util.Locale

/**
 * The app's own health, judged (12 Oct): what the health book, the speed watchdog, the background budget, the order-speed
 * timings and the diary already measured, turned into problems Boss sees ([OpenProblems]) instead of lines in the diagnostics.
 * On 12 Oct every one of these was measured and none was told:
 *
 *  - [stalls]: the screen stuck 3.9 s in BinderProxy.transactNative (the health book kept it; its tuner acts only on three
 *    tunable cadences, and "is the app healthy?" counted stalls only from three a day);
 *  - [appStart]: the app's start took 2 s (the "Speed:" line only);
 *  - [budget]: the night's background CPU spent 24 minutes into the night, the history vault starved (one diary line);
 *  - [paperOrders]: paper orders took seconds from decision to sent (the order speed card mixed them with live);
 *  - [unreadable]: a worker's failure named "ij1" (the diary only).
 *
 * Each returns at most one finding with the numbers and what to do; nothing here changes anything. Pure.
 */
object HealthChecks {
    /** A stall of the screen this long or more is a problem (the health book keeps them from 0.7 s). */
    const val STALL_MS = 1_000L
    /** ... serious from this long. */
    const val STALL_SERIOUS_MS = 3_000L
    /** The app's start (Application.onCreate) this long or more. */
    const val START_MS = 1_500L
    /** More than this share of a background budget spent ... */
    const val BUDGET_SHARE = 0.5
    /** ... before this minute of the day budget (15:30, NSE's cash close) or of the night budget (20:00: its first four hours). */
    const val DAY_CHECKPOINT = 15 * 60 + 30
    const val NIGHT_CHECKPOINT = 20 * 60
    /** Paper orders, decision to sent: a typical (median) time this long or more, over at least [PAPER_MIN_N] orders. */
    const val PAPER_ORDER_MS = 1_000L
    const val PAPER_MIN_N = 3

    data class Finding(val key: String, val severity: OpenProblems.Severity, val text: String, val fix: String)

    private fun sec(ms: Long) = String.format(Locale.ENGLISH, "%.1f s", ms / 1000.0)

    /** The day's worst stall of [STALL_MS] or more, with where it was stuck, and how many there were. */
    fun stalls(d: HealthBook.Day?): Finding? {
        if (d == null) return null
        // The newest samples are kept with their place; the day's longest is kept even when its sample has rolled off.
        val worst = d.samples.filter { it.ms >= STALL_MS }.maxByOrNull { it.ms }
            ?: if (d.longestMs >= STALL_MS) HealthBook.Stall(0L, d.longestMs, "an unrecorded place") else return null
        val n = d.samples.count { it.ms >= STALL_MS }
        val binder = worst.where.contains("Binder", true)
        return Finding("health:stall:${d.day}", if (worst.ms >= STALL_SERIOUS_MS) OpenProblems.Severity.SERIOUS else OpenProblems.Severity.WARN,
            "The screen froze ${sec(worst.ms)} in ${worst.where}" + (if (n > 1) " ($n freezes of 1 s or more today)" else "") +
                (if (binder) ": a call into Android's system made on the screen's thread" else ""),
            if (binder) "a developer fix: move that call off the main thread" else "a developer fix: move that work off the main thread")
    }

    /** The app's start took [ms] (its slowest step [slowest], "Jarvis's memory 900 ms"). */
    fun appStart(ms: Long, slowest: String?): Finding? {
        if (ms < START_MS) return null
        return Finding("health:start", if (ms >= 2 * START_MS) OpenProblems.Severity.SERIOUS else OpenProblems.Severity.WARN,
            "The app took ${sec(ms)} to start" + (slowest?.let { " (slowest step: $it)" } ?: ""),
            "a developer fix: load that step on a background thread after the first screen")
    }

    /**
     * The background budget [budget] ("2026-10-12 day" / "2026-10-12 night", [BudgetRule.budgetOf]) with [spentMs] of [capMs]
     * spent at [now]: a problem when more than [BUDGET_SHARE] is gone before its checkpoint (the history vault then starves).
     */
    fun budget(budget: String, spentMs: Long, capMs: Long, now: LocalDateTime): Finding? {
        if (capMs <= 0 || spentMs <= BUDGET_SHARE * capMs) return null
        val night = budget.endsWith("night")
        val minute = now.hour * 60 + now.minute
        val early = if (night) minute in BudgetRule.NIGHT_FROM_HOUR * 60 until NIGHT_CHECKPOINT else minute < DAY_CHECKPOINT
        if (!early) return null
        val pct = Math.round(100.0 * spentMs / capMs)
        return Finding("health:budget:$budget", if (spentMs >= capMs) OpenProblems.Severity.SERIOUS else OpenProblems.Severity.WARN,
            "The background time ${if (night) "for tonight" else "for today"} is $pct% spent by ${String.format(Locale.ENGLISH, "%02d:%02d", now.hour, now.minute)} " +
                "(${spentMs / 60_000} of ${capMs / 60_000} min of CPU): the history vault, the order review and Jarvis's learning may not get their turn",
            "keep the phone charging overnight; a developer should find which job spent it (Diagnostics, budget lines)")
    }

    /** Paper orders today: decision to sent typically [medianMs] over [n] orders. */
    fun paperOrders(medianMs: Long?, n: Int): Finding? {
        if (medianMs == null || n < PAPER_MIN_N || medianMs < PAPER_ORDER_MS) return null
        return Finding("health:paper-orders", if (medianMs >= 5 * PAPER_ORDER_MS) OpenProblems.Severity.SERIOUS else OpenProblems.Severity.WARN,
            "Paper orders took ${sec(medianMs)} typical from decision to sent today ($n orders): a profit lock or stop can be missed",
            "a developer fix: nothing slow (a vault write, a network read) may hold the paper book while an order waits")
    }

    /** Failures named only by a shrunk class name ("Jarvis words: ij1" to its count). */
    fun unreadable(names: Map<String, Int>): Finding? {
        if (names.isEmpty()) return null
        val top = names.entries.sortedByDescending { it.value }.take(3).joinToString(", ") { "${it.key} (${it.value}x)" }
        return Finding("health:unreadable", OpenProblems.Severity.WARN,
            "A failure was named only by a shrunk class name, so no one can tell what failed: $top",
            "a developer fix: keep exception class names in the release build (-keepnames)")
    }
}
