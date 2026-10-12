package com.optionslab.ira

import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
import kotlin.math.abs

/**
 * Liquidity BANKNIFTY's live stop rule (Boss, 12 Oct; the strategy scorecard's go-live criterion 6): once its LIVE record
 * (net after costs, its BANKNIFTY books, since it went live - from [Vetting.START] and from Boss's last re-enable) has fallen
 * [MAX_DRAWDOWN] from its best, or a calendar month has come to [MAX_MONTH_LOSS] or worse, it takes no NEW live entry
 * (exits always continue) until Boss re-enables it with his PIN. The backtest's worst drawdown was -39.9k and its worst
 * holdout month -12.6k: past either, the research no longer describes what is trading.
 *
 * On paper it changes nothing; the same walk over the paper record says whether it "would have stopped". It only ever stops
 * entries - never sizes up, never re-enables by itself. Pure.
 */
object LiquidityStopRule {
    const val MAX_DRAWDOWN = 40_000.0
    const val MAX_MONTH_LOSS = 13_000.0

    /** One closed trade: when it closed ([closedMs], [day]) and its net rupees after charges. */
    data class Trade(val closedMs: Long, val day: LocalDate, val net: Double)

    /**
     * Where the rule stands: [stopped] (a limit was reached), [why] in words, the [day] it was reached; the [drawdown] now from
     * the best (0 or negative), the worst [worstDrawdown] seen, this calendar month's net [month], and the [trades] counted.
     */
    data class Status(val stopped: Boolean, val why: String?, val day: LocalDate?, val drawdown: Double, val worstDrawdown: Double,
                      val month: Double, val trades: Int) {
        /** A key for "told once": the day and the reason. */
        val key: String get() = if (stopped) "$day|$why" else ""
    }

    private fun rs(x: Double) = (if (x < 0) "-Rs " else "Rs ") + "%,.0f".format(Locale.ENGLISH, abs(x))

    /** The rule over [trades] closed at or after [sinceMs] and on or after [Vetting.START], on [today]. */
    fun check(trades: List<Trade>, today: LocalDate, sinceMs: Long = 0L): Status {
        val mine = trades.filter { it.closedMs >= sinceMs && !it.day.isBefore(Vetting.START) }.sortedBy { it.closedMs }
        var run = 0.0; var peak = 0.0; var worst = 0.0
        var why: String? = null; var on: LocalDate? = null
        val months = HashMap<YearMonth, Double>()
        for (t in mine) {
            run += t.net; if (run > peak) peak = run
            val dd = run - peak; if (dd < worst) worst = dd
            val ym = YearMonth.from(t.day)
            val m = (months[ym] ?: 0.0) + t.net; months[ym] = m
            if (why == null && dd <= -MAX_DRAWDOWN) { why = "drawdown ${rs(dd)} from its best (limit -Rs 40,000)"; on = t.day }
            if (why == null && m <= -MAX_MONTH_LOSS) { why = "${ym.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }} net ${rs(m)} (limit -Rs 13,000 a month)"; on = t.day }
        }
        return Status(why != null, why, on, run - peak, worst, months[YearMonth.from(today)] ?: 0.0, mine.size)
    }

    /** The row's line: live ([live] true) or what the paper record says. */
    fun line(s: Status, live: Boolean): String {
        val now = "drawdown ${rs(s.drawdown)} of -Rs 40,000, this month ${rs(s.month)} of -Rs 13,000"
        return when {
            live && s.stopped -> "Live stop rule reached on ${s.day} (${s.why}): no new live entries until you re-enable it with your PIN; exits continue."
            live -> "Live stop rule: $now (${s.trades} live trade${if (s.trades == 1) "" else "s"})."
            s.stopped -> "Live stop rule (paper, information only): would have stopped on ${s.day} (${s.why})."
            else -> "Live stop rule (paper, information only): would not have stopped - $now."
        }
    }

    /** The notification's words: no amounts (a lock screen may show it). */
    const val NOTICE = "Liquidity BANKNIFTY stopped taking new live entries: its live stop rule was reached. Exits continue. " +
        "Only you can re-enable it, with your PIN, on its row (Home → Strategies)."
}
