package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * What changed in the arms' results this week against last (Jarvis reasoning, round 23): "what's changed in my arms'
 * results this week vs last?", "how are my bots doing this week compared to last week?", "my strategies week on week",
 * "mere bots ka is hafte vs pichle hafte". Each arm's closed paper trades from its own book, by the day each closed:
 * this week so far (Monday to today) against last week (Monday to Sunday) - trades, wins and win rate, net after charges,
 * the average losing trade - with the change in net, all arms together, and the arm whose net changed most named.
 *
 * A part week is said as one beside a whole one; a week with fewer than [FEW] trades is said to be few. Facts and
 * arithmetic only - never a verdict on the rules or advice: what stays armed is Boss's call, and nothing here arms, stops,
 * places or closes anything. Boss's account, so never on a locked phone. Pure.
 */
object ArmChange {
    /** One closed paper trade: the day it closed and its rupees after charges. */
    data class Trade(val day: LocalDate, val net: Double)

    /** One arm: its name as the app shows it, its switch now, its closed paper trades (any order). */
    data class Arm(val name: String, val armed: Boolean, val paper: List<Trade>)

    /** One week of an arm: trades, wins, net, and the average losing trade (null: no loser). */
    data class Week(val trades: Int, val wins: Int, val net: Double, val avgLoss: Double?) {
        val winRate: Int? get() = if (trades == 0) null else Math.round(100.0 * wins / trades).toInt()
    }

    /** One arm's two weeks; [change] is this week's net less last week's. */
    data class Row(val name: String, val armed: Boolean, val now: Week, val before: Week) {
        val change: Double get() = now.net - before.net
    }

    /** A week with fewer trades than this is said to be few: one trade is a big share of it. */
    const val FEW = 5

    const val NOTE = "Facts from your arms' own paper book, Boss - counts and arithmetic, not a verdict on the rules and not advice; what stays armed is your call."
    const val LOCKED = "Unlock the phone for your arms' results, Boss."

    private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    private fun rs(x: Double) = (if (x < 0) "-Rs " else "+Rs ") + "%,.0f".format(Locale.ENGLISH, abs(x))
    private fun trades(k: Int) = if (k == 1) "1 trade" else "$k trades"
    private fun norm(text: String) = " " + text.lowercase(Locale.ENGLISH).replace("’", "").replace("'", "")
        .replace(rx("[^a-z0-9 ]"), " ").replace(rx("\\s+"), " ").trim() + " "

    // ---- the question ---------------------------------------------------------------------------------------------

    private const val MY = "(my |mere |meri |mera |hamare |our |the )"
    private const val ARMS = "(arms|arm|bots|bot|strategies|strategy|algos|algo|paper arms)"
    private const val THIS = "(this week|is week|is hafte|iss hafte|is hafta|this weeks)"
    private const val LAST = "(last week|previous week|the week before|pichle hafte|pichhle hafte|pichla hafta|pichle week|last weeks)"
    private const val VS = "(vs|versus|against|compared to|compared with|over|and|aur|se|ke mukable|ke mukabale)"
    private val ASK = listOf(
        // "What's changed in my arms' results this week vs last?", "what changed in my bots this week"
        " (what|whats) (has |is )?(changed|change|different) (in|with|for|about) $MY$ARMS s? ?(results|performance|numbers|record|trades|pnl|p l)? ?(this week|week on week|since last week|from last week|vs last week|versus last week|against last week|compared to last week)",
        // "How are my bots doing this week compared to last week?", "how did my arms do this week vs last"
        " how (are|did|is|were) $MY$ARMS (doing|do|perform|performing|done) $THIS $VS (the )?($LAST|last|previous) ",
        // "my arms this week vs last week", "my bots' results this week against last"
        " $MY$ARMS s? ?(results |performance |numbers |pnl |record )?$THIS $VS (the )?($LAST|last|previous) ",
        // "my strategies week on week", "week on week for my arms", "arms week over week"
        " $MY?$ARMS s? ?(results |performance |numbers |pnl |record )?(week on week|week over week|wow) ",
        " (week on week|week over week) (change |changes |results |numbers )?(for|of|in) $MY$ARMS ",
        // "compare my arms this week with last week", "compare my bots week on week"
        " compare $MY$ARMS (results |performance |numbers )?($THIS (with|to|against|and|vs) (the )?($LAST|last)|week on week|week over week) ",
        // Hinglish: "mere bots ka is hafte vs pichle hafte", "mere arms mein is hafte kya badla", "pichle hafte se mere bots kaise hain"
        " $MY$ARMS (ka|ki|ke|mein|me|main) $THIS (vs |versus |aur |ke mukable |ke mukabale )?(kya badla|$LAST)",
        " $MY$ARMS (ka|ki|ke|mein|me|main) (is hafte |iss hafte )?(pichle hafte se|last week se) (kya badla|kya farak|kya fark|kya change)",
        " $LAST (se|ke mukable|ke mukabale) $MY$ARMS (mein|me|main|ka|ki|ke) (kya badla|kya farak|kya fark|kya change|kaise)",
        // "is hafte mere bots kaise rahe pichle hafte ke mukable" (understanding round 20)
        " $THIS $MY$ARMS (kaise|kaisa|kaisi) (rahe|raha|rahi|chale|chala|chali|hain|hai)( hain| hai)? $LAST (ke mukable|ke mukabale|ke muqable|ke muqabale|se|ke comparison mein|ke compare mein)",
    ).map { rx(it) }
    // A switch, a forecast, advice, one day or one trade, the backtest or the week ahead.
    private val NOT = rx(" (stop it|switch off|switch on|turn off|turn on|disarm|should|shall|will|would|next week|agle hafte|" +
        "backtest|back test|today|todays|aaj|yesterday|kal|this trade|that trade|what if|suppose|agar) ")

    /** Asked how the arms' results this week stand against last week's: never a switch, a forecast or advice. */
    fun asked(text: String): Boolean {
        val t = norm(text)
        if (NOT.containsMatchIn(t)) return false
        return ASK.any { it.containsMatchIn(t) }
    }

    // ---- the reading ----------------------------------------------------------------------------------------------

    /** This week (Monday to [today]) and last week (Monday to Sunday). */
    fun weeks(today: LocalDate): Pair<Pair<LocalDate, LocalDate>, Pair<LocalDate, LocalDate>> {
        val monday = today.with(DayOfWeek.MONDAY)
        val lastMonday = monday.minusWeeks(1)
        return (monday to today) to (lastMonday to monday.minusDays(1))
    }

    /** [ts]'s trades closed between [from] and [to] (both included), as a week. */
    fun week(ts: List<Trade>, from: LocalDate, to: LocalDate): Week {
        val w = ts.filter { !it.day.isBefore(from) && !it.day.isAfter(to) }
        val losers = w.filter { it.net < 0 }
        return Week(w.size, w.count { it.net > 0 }, w.sumOf { it.net }, if (losers.isEmpty()) null else losers.sumOf { it.net } / losers.size)
    }

    /** Each arm armed now or with a closed trade in either week: its two weeks, the biggest change in net first. */
    fun rows(arms: List<Arm>, today: LocalDate): List<Row> {
        val (thisWeek, lastWeek) = weeks(today)
        return arms.map { a -> Row(a.name, a.armed, week(a.paper, thisWeek.first, thisWeek.second), week(a.paper, lastWeek.first, lastWeek.second)) }
            .filter { it.armed || it.now.trades > 0 || it.before.trades > 0 }
            .sortedWith(compareBy<Row>({ -abs(it.change) }, { it.name }))
    }

    private fun weekWords(w: Week): String = if (w.trades == 0) "no closed paper trades" else
        "${trades(w.trades)}, ${w.wins} won (${w.winRate}%), ${rs(w.net)}" +
            (w.avgLoss?.let { ", average loss ${rs(it)}" } ?: ", no losing trade")

    /** The weekdays from Monday to [today] (at most 5). */
    internal fun weekdaysIn(today: LocalDate): Int = minOf(today.dayOfWeek.value, 5)

    /** The answer: each arm's week against last week, all arms together, the biggest change named, and the note. */
    fun answer(arms: List<Arm>, today: LocalDate): String {
        val rows = rows(arms, today)
        val (thisWeek, lastWeek) = weeks(today)
        if (rows.none { it.now.trades > 0 || it.before.trades > 0 })
            return "Your arms have no closed paper trades this week or last, Boss (${thisWeek.first.format(DAY)} to ${today.format(DAY)}, and ${lastWeek.first.format(DAY)} to ${lastWeek.second.format(DAY)}) - nothing to set side by side."
        val out = StringBuilder("Boss, your arms' paper results this week so far (${thisWeek.first.format(DAY)} to ${today.format(DAY)}) against last week (${lastWeek.first.format(DAY)} to ${lastWeek.second.format(DAY)}):\n")
        for (r in rows) {
            val off = if (r.armed) "" else " (off now)"
            if (r.now.trades == 0 && r.before.trades == 0) { out.append("- ${r.name}$off: no closed paper trades either week.\n"); continue }
            out.append("- ${r.name}$off: this week ${weekWords(r.now)}; last week ${weekWords(r.before)}. Net ${rs(r.change)} on last week.\n")
        }
        val nowAll = rows.sumOf { it.now.net }; val beforeAll = rows.sumOf { it.before.net }
        val nowTrades = rows.sumOf { it.now.trades }; val beforeTrades = rows.sumOf { it.before.trades }
        out.append("All arms together: ${trades(nowTrades)}, ${rs(nowAll)} this week; ${trades(beforeTrades)}, ${rs(beforeAll)} last week (${rs(nowAll - beforeAll)}).")
        val top = rows.firstOrNull { it.change != 0.0 }
        if (top != null) {
            out.append(" The biggest change: ${top.name}, net ${rs(top.before.net)} last week to ${rs(top.now.net)} this week (${rs(top.change)})")
            val wr = top.now.winRate; val wrBefore = top.before.winRate
            if (wr != null && wrBefore != null && wr != wrBefore) out.append(", win rate $wrBefore% to $wr%")
            val al = top.now.avgLoss; val alBefore = top.before.avgLoss
            if (al != null && alBefore != null) out.append(", average loss ${rs(alBefore)} to ${rs(al)}")
            out.append('.')
        }
        val days = weekdaysIn(today)
        if (days < 5) out.append(" This week is $days weekday${if (days == 1) "" else "s"} in, last week was 5 - a part week beside a whole one.")
        val few = rows.filter { (it.now.trades in 1 until FEW) || (it.before.trades in 1 until FEW) }.map { it.name }
        if (few.isNotEmpty()) out.append(" Under $FEW trades in a week (${few.joinToString(", ")}): each trade is a big share of that week's numbers.")
        return out.append(' ').append(NOTE).toString()
    }
}
