package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * The paper arms' week, said once on Sunday evening (usefulness round 24, 2026-10-05): each arm's paper trades of the week
 * just ended - trades, wins and net - beside its two-year BankNifty test put as rupees a week ([TradeCheck.RECORD], one
 * lot), and where its paper test stands ([Vetting]: [Vetting.MIN_TRADES] trades, a net profit, a profit factor of
 * [Vetting.MIN_PF], a worst run within [Vetting.MAX_DD_SHARE] of its gains) - which arm is on track to pass it, which is
 * not, which already held up or failed. The numbers go in the chat; aloud only plain words (no amount), and a locked
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

    /** Where an arm's paper test stands. */
    enum class Track { ON_TRACK, NOT_YET, BEHIND, HELD_UP, FAILED, NO_TRADES }

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

    /** Where [a]'s paper test stands on all its closed paper trades so far. */
    fun track(a: Arm): Track {
        val nets = a.paper.sortedBy { it.day }.map { it.net }
        if (nets.isEmpty()) return Track.NO_TRADES
        val v = Vetting.judge(a.name, nets)
        return when (v.state) {
            Vetting.State.HELD_UP -> Track.HELD_UP
            Vetting.State.FAILED -> Track.FAILED
            Vetting.State.TESTING -> {
                val gain = nets.filter { it > 0 }.sum()
                when {
                    v.net <= 0 -> Track.BEHIND
                    (v.pf == null || v.pf >= Vetting.MIN_PF) && v.maxDd <= gain * Vetting.MAX_DD_SHARE && v.trades < Vetting.MIN_TRADES -> Track.ON_TRACK
                    else -> Track.NOT_YET
                }
            }
        }
    }

    private fun testWords(a: Arm, n: Int): String {
        val v = Vetting.judge(a.name, a.paper.sortedBy { it.day }.map { it.net })
        val pf = v.pf?.let { "%.2f".format(Locale.ENGLISH, it) }
        return when (track(a)) {
            Track.NO_TRADES -> "its paper test has not started (0 of ${Vetting.MIN_TRADES} trades)"
            Track.HELD_UP -> "it held up on its paper test (${trades(n)}, ${rs(v.net)})"
            Track.FAILED -> "it failed its paper test (${trades(n)}, ${rs(v.net)})"
            Track.ON_TRACK -> "paper test $n of ${Vetting.MIN_TRADES}, ${rs(v.net)} so far: on track to pass"
            Track.BEHIND -> "paper test ${minOf(n, Vetting.MIN_TRADES)} of ${Vetting.MIN_TRADES}, ${rs(v.net)} so far: not on track, down overall"
            Track.NOT_YET -> "paper test ${minOf(n, Vetting.MIN_TRADES)} of ${Vetting.MIN_TRADES}, ${rs(v.net)} so far: up, not convincingly yet" +
                (pf?.let { " (profit factor $it, ${"%.1f".format(Locale.ENGLISH, Vetting.MIN_PF)} needed)" } ?: "")
        }
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
            chat.append("- ${a.name} (${if (a.armed) "armed" else "off"}): $head$test; ${testWords(a, a.paper.size)}.\n")
            aloud += if (w.isEmpty()) "${a.name}, no trades" else "${a.name}, ${trades(w.size)}, $won won, " +
                when { net > 0 -> "up for the week"; net < 0 -> "down for the week"; else -> "flat for the week" }
        }
        val onTrack = order.filter { track(it) == Track.ON_TRACK }
        val passLine = if (onTrack.isEmpty()) "No arm is on track to pass its ${Vetting.MIN_TRADES}-trade paper test now."
            else "On track to pass its ${Vetting.MIN_TRADES}-trade paper test: " + onTrack.joinToString(", ") { "${it.name} (${it.paper.size} of ${Vetting.MIN_TRADES})" } + "."
        chat.append(passLine).append(' ').append(NOTE)
        val passAloud = if (onTrack.isEmpty()) "No arm is on track to pass its paper test now."
            else "On track for its paper test: " + onTrack.joinToString(", ") { "${it.name}, ${it.paper.size} of ${Vetting.MIN_TRADES}" } + "."
        return Said(chat.toString().trim(), "Boss, your arms' paper week. " + aloud.joinToString(". ") + ". " + passAloud + " The numbers are in the chat.")
    }
}
