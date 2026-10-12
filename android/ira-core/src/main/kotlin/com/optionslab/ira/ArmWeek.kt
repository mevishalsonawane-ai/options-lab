package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * The paper arms' week, said once on Sunday evening (usefulness round 24, 2026-10-05): each arm's paper trades of the week
 * just ended - trades, wins and net - beside its two-year BankNifty test put as rupees a week ([TradeCheck.RECORD], one
 * lot), and where its paper test stands ([Vetting]: against its own backtest's range, from [Vetting.START], with
 * [Vetting.MIN_TRADES] trades and [Vetting.MIN_DAYS] trading days before anything passes). The numbers go in the chat; aloud only plain words (no amount), and a locked
 * phone hears only that it is in the chat. Facts, never advice: nothing here arms, stops, places or closes anything.
 * Not in IraGoldAlgo. Pure.
 */
object ArmWeek {
    /** One closed paper trade: the day it closed and its net rupees after charges. */
    data class Trade(val day: LocalDate, val net: Double)

    /** One arm: its name as the app shows it, its switch now, and every closed paper trade of its own (any order). */
    data class Arm(val name: String, val armed: Boolean, val paper: List<Trade>, val tested: Pair<Double, Double>? = TradeCheck.RECORD[name])

    /** [chat]: the full summary with the numbers; [aloud]: plain words, no amount. */
    data class Said(val chat: String, val aloud: String)

    /** Where an arm's paper test stands ([Vetting]: against its own backtest, from [Vetting.START]). */
    enum class Track { NO_TRADES, TOO_EARLY, WITHIN, BELOW, ABOVE, PASSED, NO_BACKTEST }

    const val NOTE = "Facts from the arms' own records, Boss, not advice: I switched nothing; what stays armed is your call."

    private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    private fun rs(x: Double) = (if (x < 0) "-Rs " else "+Rs ") + "%,.0f".format(Locale.ENGLISH, abs(x))
    private fun trades(n: Int) = if (n == 1) "1 trade" else "$n trades"

    /** The Monday to Sunday week [day] falls in (on a Sunday: the week just ended). */
    fun weekOf(day: LocalDate): Pair<LocalDate, LocalDate> {
        val monday = day.minusDays((day.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong())
        return monday to monday.plusDays(6)
    }

    /** The two-year test as rupees a week (a lot, after costs): the two years' sum over 104 weeks. */
    fun testedWeek(t: Pair<Double, Double>): Double = (t.first + t.second) / 104.0

    private fun verdict(a: Arm, today: LocalDate): Vetting.Verdict =
        Vetting.judge(a.name, a.paper.map { Vetting.Trade(it.day, it.net) }, today)

    /** Where [a]'s paper test stands on [today] (its closed paper trades from [Vetting.START]). */
    fun track(a: Arm, today: LocalDate): Track {
        val v = verdict(a, today)
        if (v.trades == 0) return Track.NO_TRADES
        return when (v.state) {
            Vetting.State.TOO_EARLY -> Track.TOO_EARLY
            Vetting.State.WITHIN -> Track.WITHIN
            Vetting.State.BELOW -> Track.BELOW
            Vetting.State.ABOVE -> Track.ABOVE
            Vetting.State.PASSED -> Track.PASSED
            Vetting.State.NO_BACKTEST -> Track.NO_BACKTEST
        }
    }

    private fun testWords(a: Arm, today: LocalDate): String {
        val v = verdict(a, today)
        if (v.trades == 0) return "its paper test has no trades since ${Vetting.START.dayOfMonth} Oct yet"
        return "paper test: ${v.words()}, ${trades(v.trades)} since ${Vetting.START.dayOfMonth} Oct, ${rs(v.net)}" +
            (if (v.band != null && v.state == Vetting.State.TOO_EARLY) (if (v.inBand == true) ", within its backtest's range so far" else ", outside its backtest's range") else "")
    }

    /**
     * The week of [today] ([weekOf]) for [arms]; null when no arm is armed or has a closed paper trade (nothing to say).
     * Armed arms first, then the best week first.
     */
    fun say(arms: List<Arm>, today: LocalDate): Said? {
        val pool = arms.filter { it.armed || it.paper.isNotEmpty() }
        if (pool.isEmpty()) return null
        val (from, to) = weekOf(today)
        fun week(a: Arm) = a.paper.filter { !it.day.isBefore(from) && !it.day.isAfter(to) }
        val order = pool.sortedWith(compareBy<Arm>({ !it.armed }, { -week(it).sumOf { t -> t.net } }))
        val chat = StringBuilder("Boss, the arms' paper week (${from.format(DAY)} to ${to.format(DAY)}):\n")
        val aloud = ArrayList<String>()
        for (a in order) {
            val w = week(a)
            val net = w.sumOf { it.net }
            val won = w.count { it.net > 0 }
            val head = if (w.isEmpty()) "no paper trades this week" else "${trades(w.size)}, $won won, ${rs(net)}"
            val test = a.tested?.let { t ->
                val per = testedWeek(t)
                "; its two-year test averaged ${rs(per)} a week a lot" + if (w.isEmpty()) "" else if (net >= per) " (this week at or above it)" else " (this week below it)"
            } ?: "; no two-year test on the phone"
            chat.append("- ${a.name} (${if (a.armed) "armed" else "off"}): $head$test; ${testWords(a, today)}.\n")
            aloud += if (w.isEmpty()) "${a.name}, no trades" else "${a.name}, ${trades(w.size)}, $won won, " +
                when { net > 0 -> "up for the week"; net < 0 -> "down for the week"; else -> "flat for the week" }
        }
        val look = order.filter { track(it, today) == Track.BELOW || track(it, today) == Track.ABOVE }
        val passed = order.filter { track(it, today) == Track.PASSED }
        val rule = "Paper tests count from ${Vetting.START.dayOfMonth} Oct and need ${Vetting.MIN_TRADES} trades and ${Vetting.MIN_DAYS} trading days, judged against each arm's own backtest."
        val passLine = when {
            passed.isNotEmpty() -> "Passed (information only; nothing switched): " + passed.joinToString(", ") { it.name } + ". $rule"
            look.isNotEmpty() -> "Outside its backtest's range, to investigate: " + look.joinToString(", ") { it.name } + ". $rule"
            else -> "No arm has passed its paper test. $rule"
        }
        chat.append(passLine).append(' ').append(NOTE)
        val passAloud = when {
            passed.isNotEmpty() -> "Passed its paper test: " + passed.joinToString(", ") { it.name } + "."
            look.isNotEmpty() -> "Outside its backtest's range: " + look.joinToString(", ") { it.name } + "."
            else -> "No arm has passed its paper test."
        }
        return Said(chat.toString().trim(), "Boss, your arms' paper week. " + aloud.joinToString(". ") + ". " + passAloud + " The numbers are in the chat.")
    }
}
