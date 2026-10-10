package com.optionslab.ira

import java.util.Locale
import kotlin.math.sqrt

/**
 * Self-review and auto-park (Boss, 10 Oct: "make the workers smart", part 4). Each day after the F&O close (15:45) every
 * strategy with a pinned backtest ([ForwardCheck]'s expectations: the research's TEST period, its mean and spread a trade and
 * its worst drawdown) sets its recent forward trades - PAPER and LIVE separately - against it ([review]):
 *
 *  - the mean of the last 20 and of the last 50 trades (each once at least [MIN_TRADES] exist) against the backtest's mean,
 *    in standard errors: z = (mean − μ) / (σ / √m); drifting at z ≤ −[Z_CRIT];
 *  - ForwardCheck's one-sided CUSUM (k 0.5) over the last 50, its value now at [CUSUM_H] or more: a sustained run below;
 *  - its drawdown over the recent trades against the backtest's worst: worse than [DD_X] times it ([LOTTERY_DD_X] for a lottery).
 *  A lottery (Hero: a few huge winners make its mean) is judged by drawdown alone, as ForwardCheck does.
 * The thresholds keep false alarms at or under 5% for a strategy exactly as researched, checked after every trade for 100
 * trades (DriftReviewTest, on normal and skewed synthetic trades with the pinned means and spreads).
 *
 * What it may do ([action]) only lowers risk, and it never undoes Boss's choice the other way:
 *  - drifting on LIVE: it parks itself to PAPER (its next entries go to the paper account; open live positions are still
 *    managed to their exits) and asks Boss through Requests and a notification, with the reason and the numbers;
 *  - drifting on PAPER: a warning only by default ([PaperPolicy.WARN]); with Boss's setting [PaperPolicy.PAUSE] it pauses
 *    (no new paper entries) and asks;
 *  - it NEVER un-parks, un-pauses, arms or sizes anything: only Boss does, with his PIN for Live.
 * Pure: no clock, no storage.
 */
object DriftReview {
    const val MIN_TRADES = 15
    val WINDOWS = listOf(20, 50)
    /** One-sided, about 0.05% a look; with the CUSUM and the drawdown rule at most 5% over 100 looks (calibrated in DriftReviewTest). */
    const val Z_CRIT = 3.3
    /** The recent drawdown must be worse than this many times the backtest's worst to count. */
    const val DD_X = 2.5
    /** A lottery (judged by drawdown alone) at 1.5 times its worst. */
    const val LOTTERY_DD_X = 1.5

    /** The CUSUM's level now (its last value, not any point of the window: each look is a fresh one) that counts as drift. */
    const val CUSUM_H = 6.5

    enum class Verdict(val words: String) { TOO_FEW("too few trades"), ON_TRACK("on track"), DRIFTING("drifting") }
    enum class Action { NONE, WARN, PAUSE, PARK_TO_PAPER }
    enum class PaperPolicy { WARN, PAUSE }

    /** One window's reading: the last [m] trades' mean and its z against the backtest. */
    data class Window(val size: Int, val m: Int, val mean: Double, val z: Double?)

    data class Review(
        val key: String, val name: String, val live: Boolean, val trades: Int, val verdict: Verdict, val reasons: List<String>,
        val windows: List<Window>, val cusum: Double, val drawdown: Double, val expectation: ForwardCheck.Expectation,
    ) {
        val venue: String get() = if (live) "live" else "paper"
    }

    private fun rs(x: Double) = ForwardCheck.rs(x)

    /** [nets] (the forward trades in order, oldest first; rupees as the expectation's unit) of [e] on [live] or paper. */
    fun review(e: ForwardCheck.Expectation, nets: List<Double>, live: Boolean): Review {
        val n = nets.size
        val recent = nets.takeLast(WINDOWS.max())
        val dd = ForwardCheck.drawdown(recent)
        val windows = WINDOWS.mapNotNull { w ->
            val last = nets.takeLast(w)
            if (last.size < MIN_TRADES) null else {
                val mean = last.average()
                Window(w, last.size, mean, if (e.lottery || e.sd <= 0) null else (mean - e.mean) / (e.sd / sqrt(last.size.toDouble())))
            }
        }.distinctBy { it.m }
        val cus = if (e.lottery) emptyList() else ForwardCheck.cusum(e, recent)
        if (n < MIN_TRADES) return Review(e.key, e.name, live, n, Verdict.TOO_FEW,
            listOf("$n of the $MIN_TRADES trades needed to judge"), windows, cus.lastOrNull() ?: 0.0, dd, e)
        val reasons = ArrayList<String>()
        for (w in windows) {
            val z = w.z ?: continue
            if (z <= -Z_CRIT) reasons += "the last ${w.m} trades made ${rs(w.mean)} a trade against the backtest's ${rs(e.mean)} " +
                "(${String.format(Locale.ENGLISH, "%.1f", -z)} standard errors below)"
        }
        val cusNow = cus.lastOrNull() ?: 0.0
        if (cusNow >= CUSUM_H) reasons += "a sustained run below the backtest over its last ${recent.size} trades (CUSUM " +
            String.format(Locale.ENGLISH, "%.1f", cusNow) + ", alarm at $CUSUM_H)"
        val ddX = if (e.lottery) LOTTERY_DD_X else DD_X
        if (dd < ddX * e.maxDrawdown) reasons += "a drawdown of ${rs(dd)} over its last ${recent.size} trades, past ${ddX}x the backtest's worst ${rs(e.maxDrawdown)}"
        return Review(e.key, e.name, live, n, if (reasons.isEmpty()) Verdict.ON_TRACK else Verdict.DRIFTING, reasons, windows,
            cus.lastOrNull() ?: 0.0, dd, e)
    }

    /**
     * What [r] leads to: drifting live parks to paper; drifting paper warns (or pauses under [paper] PAUSE); else nothing.
     * [alreadyParked]: it was parked before - nothing new is done or asked.
     */
    fun action(r: Review, paper: PaperPolicy = PaperPolicy.WARN, alreadyParked: Boolean = false): Action = when {
        r.verdict != Verdict.DRIFTING || alreadyParked -> Action.NONE
        r.live -> Action.PARK_TO_PAPER
        paper == PaperPolicy.PAUSE -> Action.PAUSE
        else -> Action.WARN
    }

    /** The chip on Home → Strategies: "on track", "drifting", "parked", "too few trades". */
    fun chip(r: Review?, parked: Boolean): String = when {
        parked -> "parked"
        r == null -> "too few trades"
        else -> r.verdict.words
    }

    /** The numbers in one line: "last 20: −₹310 a trade (z −3.1) · last 50: ... · drawdown −₹41,200 (backtest's worst −₹29,726)". */
    fun numbers(r: Review): String = (r.windows.map { w ->
        "last ${w.m}: ${rs(w.mean)} a trade" + (w.z?.let { " (z ${String.format(Locale.ENGLISH, "%+.1f", it)})" } ?: "")
    } + "drawdown ${rs(r.drawdown)} (backtest's worst ${rs(r.expectation.maxDrawdown)})").joinToString(" · ")

    /** The request to Boss (Requests and the notification), in plain words with the numbers. */
    fun ask(r: Review, a: Action): String {
        val what = when (a) {
            Action.PARK_TO_PAPER -> "I've parked ${r.name} to PAPER: its next entries go to the paper account (its open live trades are still managed to their exits)."
            Action.PAUSE -> "I've paused ${r.name}'s new paper entries."
            Action.WARN -> "Nothing was changed; it keeps trading on paper."
            Action.NONE -> ""
        }
        val back = when (a) {
            Action.PARK_TO_PAPER -> " Put it back on Live yourself, with your PIN, if you still want it there - I never will."
            Action.PAUSE -> " Switch it back on yourself if you want it to carry on - I never will."
            else -> ""
        }
        return "${r.name} (${r.venue}) is drifting from its backtest: ${r.reasons.joinToString("; ")}. $what$back (${numbers(r)}.)".replace("  ", " ")
    }

    /** One strategy's line for Jarvis. */
    fun line(r: Review, parked: Boolean): String = "${r.name} (${r.venue}, ${r.trades} trade${if (r.trades == 1) "" else "s"}): " +
        chip(r, parked) + when {
            r.verdict == Verdict.TOO_FEW -> " - ${r.reasons.first()}."
            r.verdict == Verdict.DRIFTING -> " - " + r.reasons.joinToString("; ") + "."
            else -> " - " + numbers(r) + "."
        }

    private val ASK = Regex(" (strategies|strategy|bots?|arms?) .*(backtest|research|expected|expectation|drift|drifting|on track|health) |" +
        " (backtest|research) .*(strategies|strategy|bots?|arms?) ")

    /** "how are my strategies doing vs backtest?" */
    fun asked(text: String): Boolean {
        val t = " " + text.lowercase(Locale.ENGLISH).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim() + " "
        return ASK.containsMatchIn(t)
    }

    /** Jarvis's answer from the latest [reviews] ([parked]: the strategies parked by a review). */
    fun answer(reviews: List<Review>, parked: Set<String>, reviewedOn: String?): String {
        if (reviews.isEmpty()) return "No review yet, Boss: each strategy sets its recent trades against its backtest every day after 15:45."
        val head = "Against their backtests" + (reviewedOn?.let { " (reviewed $it)" } ?: "") + ":"
        return head + "\n" + reviews.joinToString("\n") { "• " + line(it, "${it.key}:${it.venue}" in parked) } +
            "\nA drifting live strategy parks itself to paper and asks you; it never goes back to live by itself."
    }
}
