package com.optionslab.ira

import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs

/**
 * 20 Oct, enhancement round 1 (idea 4): Boss's account stop rule (-Rs 40,000 from the best, or -Rs 13,000 in a calendar
 * month; CLAUDE.md) over the WHOLE Zerodha account, not only the arms' live record ([LiquidityStopRule] in ArmLive and the ORB
 * family). Each trading day's figure is Zerodha's own day P&L (the positions' m2m, before charges) as last read by the app;
 * the walk is the same as [LiquidityStopRule.check] with one "trade" a day.
 *
 * It only ever blocks a bot's NEW live entry, never an exit, never Boss's own order, and never sizes anything. In shadow by
 * default (CLAUDE.md: a new rule that could skip a live trade starts logged only): the entry door then only records what it
 * would refuse, until Boss switches it on. Pure.
 */
object AccountStop {
    /** One trading day: Zerodha's day P&L as last read ([net], rupees before charges). */
    data class Day(val day: LocalDate, val net: Double)

    /** Days kept (a little over a year: the month and the drawdown since the first day kept). */
    const val KEEP = 400

    /** [days] with [day]'s figure set to [net] (a later read of the same day replaces the earlier), oldest first. */
    fun record(days: List<Day>, day: LocalDate, net: Double): List<Day> {
        if (!net.isFinite()) return days
        return (days.filter { it.day != day } + Day(day, net)).sortedBy { it.day }.takeLast(KEEP)
    }

    /**
     * The rule over [days] on [today] (from [Vetting.START], as the arms' rule, and from [since] when Boss restarted the count).
     * Once reached it stays reached (as the arms' rule) until Boss restarts the count.
     */
    fun status(days: List<Day>, today: LocalDate, since: LocalDate? = null): LiquidityStopRule.Status = LiquidityStopRule.check(
        days.filter { since == null || !it.day.isBefore(since) }.map { LiquidityStopRule.Trade(it.day.toEpochDay() * 86_400_000L, it.day, it.net) }, today)

    /** The day a restart made on [today] counts from: the next day (today's figure so far is what reached it). */
    fun restartFrom(today: LocalDate): LocalDate = today.plusDays(1)

    /** The door's words when the rule is reached ([st] stopped), else null. */
    fun refusal(st: LiquidityStopRule.Status): String? =
        if (st.stopped) "refused: the account stop rule (${st.why?.replace("from its best", "from the account's best")})" else null

    private fun rs(x: Double) = (if (x < 0) "-Rs " else "Rs ") + "%,.0f".format(Locale.ENGLISH, abs(x))

    /** The diagnostics' and Bot settings' line; [on]: Boss switched it on (else in shadow). */
    fun line(st: LiquidityStopRule.Status, on: Boolean, shadowRefused: Int): String {
        val mode = if (on) "on: the bots' new live entries are refused once it is reached" else "in shadow: logged only"
        val now = if (st.trades == 0) "no Zerodha day recorded yet"
            else "drawdown ${rs(st.drawdown)} of -Rs 40,000, this month ${rs(st.month)} of -Rs 13,000 (${st.trades} day${if (st.trades == 1) "" else "s"}, Zerodha's figures before charges)"
        val reached = if (st.stopped) "; REACHED on ${st.day} (${st.why})" else ""
        val would = if (!on && shadowRefused > 0) "; $shadowRefused live ${if (shadowRefused == 1) "entry" else "entries"} would have been refused today" else ""
        return "Account stop rule ($mode): $now$reached$would."
    }

    /** One line a day: "2026-10-20=-1234.50". */
    fun encode(days: List<Day>): String = days.joinToString("\n") { "${it.day}=${"%.2f".format(Locale.ROOT, it.net)}" }

    fun decode(text: String?): List<Day> = text.orEmpty().lineSequence().mapNotNull { l ->
        val i = l.indexOf('=')
        if (i <= 0) return@mapNotNull null
        val d = runCatching { LocalDate.parse(l.substring(0, i).trim()) }.getOrNull() ?: return@mapNotNull null
        val n = l.substring(i + 1).trim().toDoubleOrNull()?.takeIf { it.isFinite() } ?: return@mapNotNull null
        Day(d, n)
    }.toList().groupBy { it.day }.map { it.value.last() }.sortedBy { it.day }.takeLast(KEEP)
}
