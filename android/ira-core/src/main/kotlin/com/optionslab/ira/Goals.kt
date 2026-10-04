package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale

/**
 * Boss's goals over days (part 6 of "an AGI for this app"): "keep my weekly loss under 5,000", "make 20,000 this
 * month", "no more than 3 trades a day". Jarvis keeps them, says the progress each morning and when asked, warns when
 * one is close, and when a loss goal is broken offers to switch the kill switch on (asked, never done alone). A goal
 * only ever holds trading back - it never loosens a limit. Pure.
 */
object Goals {
    enum class Kind { MAX_LOSS, TARGET, MAX_TRADES }
    enum class Period(val label: String) { DAY("today"), WEEK("this week"), MONTH("this month") }

    data class Goal(val kind: Kind, val period: Period, val amount: Double) {
        fun text(): String = when (kind) {
            Kind.MAX_LOSS -> "lose no more than ${AppFacts.rs(amount).removePrefix("+")} ${period.label}"
            Kind.TARGET -> "make ${AppFacts.rs(amount).removePrefix("+")} ${period.label}"
            Kind.MAX_TRADES -> "no more than ${amount.toInt()} trade${if (amount.toInt() == 1) "" else "s"} a day"
        }
    }

    /** One closed trade: the day it closed and what it made. */
    data class Closed(val day: LocalDate, val net: Double)

    data class Status(val goal: Goal, val text: String, val broken: Boolean, val near: Boolean, val met: Boolean)

    private fun t(s: String) = " " + s.lowercase(Locale.ENGLISH).replace(",", "").replace(Regex("[^a-z0-9. ]"), " ").replace(Regex("\\s+"), " ").trim() + " "

    private fun amount(s: String): Double? = Regex(" (?:rs |inr |₹)?(\\d+(?:\\.\\d+)?) ?(k|thousand|lakh|lac)? ").find(s)?.let { m ->
        val n = m.groupValues[1].toDouble()
        when (m.groupValues[2]) { "k", "thousand" -> n * 1_000; "lakh", "lac" -> n * 100_000; else -> n }
    }

    private fun period(s: String): Period? = when {
        Regex(" (week|weekly) ").containsMatchIn(s) -> Period.WEEK
        Regex(" (month|monthly) ").containsMatchIn(s) -> Period.MONTH
        Regex(" (day|daily|today|a day|per day) ").containsMatchIn(s) -> Period.DAY
        else -> null
    }

    /** A goal set in words ("goal", "target", "keep ... under"), or null. */
    fun read(text: String): Goal? {
        val s = t(text)
        if (!Regex(" (goal|target|aim|keep my|limit my) ").containsMatchIn(s)) return null
        if (Regex(" (what|how|clear|remove|delete|forget|cancel) ").containsMatchIn(s)) return null
        // An order with a target or a stop ("buy banknifty weekly 52000 ce target 200") is never a goal.
        if (Regex(" (buy|sell|ce|pe|call|put|lot|lots|strike|stop loss|sl) ").containsMatchIn(s)) return null
        val trades = Regex(" (no more than|at most|max|maximum|only|under|below|less than) (\\d{1,2}) trades? ").find(s)
        if (trades != null) return Goal(Kind.MAX_TRADES, Period.DAY, trades.groupValues[2].toDouble()).takeIf { it.amount >= 1 }
        val p = period(s) ?: return null
        val n = amount(s)?.takeIf { it >= 100 } ?: return null
        return if (Regex(" (loss|lose|losing|drawdown) ").containsMatchIn(s)) Goal(Kind.MAX_LOSS, p, n)
            else if (Regex(" (make|earn|profit|gain|target) ").containsMatchIn(s)) Goal(Kind.TARGET, p, n) else null
    }

    fun asked(text: String): Boolean = Regex("(?i)\\b(what are|how are|show|tell me) (my|the) goals?\\b|\\bmy goals?\\b.*\\b(progress|doing|status)\\b|\\bgoal progress\\b").containsMatchIn(text)
    fun clearAsked(text: String): Boolean = Regex("(?i)\\b(clear|remove|delete|forget|cancel) (all )?(my )?goals?\\b").containsMatchIn(text)

    /** The days [p] covers up to [today]. */
    fun inPeriod(day: LocalDate, p: Period, today: LocalDate): Boolean = when (p) {
        Period.DAY -> day == today
        Period.WEEK -> !day.isBefore(today.with(DayOfWeek.MONDAY)) && !day.isAfter(today)
        Period.MONTH -> day.year == today.year && day.month == today.month && !day.isAfter(today)
    }

    /** How [g] stands, from the closed trades ([closed]) and today's trade count. */
    fun status(g: Goal, closed: List<Closed>, tradesToday: Int, today: LocalDate): Status {
        val net = closed.filter { inPeriod(it.day, g.period, today) }.sumOf { it.net }
        return when (g.kind) {
            Kind.MAX_LOSS -> {
                val lost = -minOf(net, 0.0)
                Status(g, "Loss limit ${g.period.label}: ${AppFacts.rs(net)} against ${AppFacts.rs(-g.amount)}" +
                    if (lost >= g.amount) " - BROKEN." else if (lost >= g.amount * 0.75) " - close (${(lost / g.amount * 100).toInt()}% used)." else ".",
                    broken = lost >= g.amount, near = lost >= g.amount * 0.75 && lost < g.amount, met = false)
            }
            Kind.TARGET -> Status(g, "Target ${g.period.label}: ${AppFacts.rs(net)} of ${AppFacts.rs(g.amount)}" +
                if (net >= g.amount) " - reached; protect it." else " (${(maxOf(net, 0.0) / g.amount * 100).toInt()}%).",
                broken = false, near = false, met = net >= g.amount)
            Kind.MAX_TRADES -> Status(g, "Trades today: $tradesToday of at most ${g.amount.toInt()}" +
                if (tradesToday >= g.amount) " - the day's limit is reached." else ".",
                broken = tradesToday > g.amount, near = tradesToday.toDouble() == g.amount, met = false)
        }
    }

    /** Kept: one goal of each kind and period (a new one replaces the old), at most 6. */
    fun add(goals: List<Goal>, g: Goal): List<Goal> = (goals.filterNot { it.kind == g.kind && it.period == g.period } + g).takeLast(6)

    fun say(st: List<Status>): String = if (st.isEmpty()) "You have no goals set, Boss. Say, for example, \"goal: keep my weekly loss under 5000\"."
        else "Your goals, Boss: " + st.joinToString(" ") { it.text }
}
