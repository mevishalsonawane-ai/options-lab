package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale
import kotlin.math.max

/**
 * The learning freeze (S14): when an account's total P&L (paper and live judged apart) has fallen by more than a
 * threshold since the first automatic change of the last [WINDOW_DAYS] trading days, every automatic change freezes -
 * Autopilot, the strategy lab's promotions, the health tuner and the specialists' self-tuning stop applying, while every
 * agent goes on recording. The threshold is Boss's own (Bot settings) or, by default, twice the average losing day of the
 * last [LOOKBACK_DAYS] days, at least [FLOOR_RS].
 *
 * Only Boss unfreezes it: no PIN while only paper is on, his PIN while the live account is on. After an unfreeze the days
 * up to and including it are not counted again (it does not freeze again at once on the same fall). Pure.
 */
object FreezeRule {
    const val WINDOW_DAYS = 5
    const val LOOKBACK_DAYS = 60
    const val FLOOR_RS = 3_000.0
    const val LOSS_MULT = 2.0

    enum class Account(val words: String) { PAPER("paper"), LIVE("live") }

    /** The default threshold from [daily] (net per day) up to [today]: 2x the average losing day of the last 60, at least ₹3,000. */
    fun threshold(daily: Map<LocalDate, Double>, today: LocalDate): Double {
        val from = today.minusDays(LOOKBACK_DAYS.toLong())
        val losses = daily.filter { (d, v) -> d.isAfter(from) && !d.isAfter(today) && v < 0 }.values.map { -it }
        val avg = if (losses.isEmpty()) 0.0 else losses.average()
        return max(FLOOR_RS, LOSS_MULT * avg)
    }

    /** The last [n] weekdays up to and including [today] (holidays aside), oldest first. */
    fun lastTradingDays(today: LocalDate, n: Int = WINDOW_DAYS): List<LocalDate> {
        val out = ArrayList<LocalDate>()
        var d = today
        while (out.size < n) {
            if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) out += d
            d = d.minusDays(1)
        }
        return out.reversed()
    }

    /** One account's verdict: frozen or not, the fall since [from], the threshold, and words. */
    data class Verdict(val account: Account, val frozen: Boolean, val from: LocalDate?, val fall: Double, val threshold: Double, val words: String)

    /**
     * [account] on [today]: [daily] its net per day, [changeDays] the days any agent made an automatic change, [override]
     * Boss's threshold (null or 0 or less: the default), [since] the day Boss last unfroze it (days up to it not counted).
     */
    fun check(account: Account, daily: Map<LocalDate, Double>, changeDays: Collection<LocalDate>, today: LocalDate,
              override: Double? = null, since: LocalDate? = null): Verdict {
        val th = override?.takeIf { it > 0 } ?: threshold(daily, today)
        val window = lastTradingDays(today)
        val first = changeDays.filter { it in window && (since == null || it.isAfter(since)) }.minOrNull()
            ?: return Verdict(account, false, null, 0.0, th, "no automatic change in the last $WINDOW_DAYS trading days")
        val total = daily.filter { (d, _) -> !d.isBefore(first) && !d.isAfter(today) && (since == null || d.isAfter(since)) }.values.sum()
        val fall = max(0.0, -total)
        val frozen = fall > th
        val words = "${account.words} P&L ${rs(total)} since the first automatic change on ${AgentLessons.day(first)} " +
            "(the freeze line is a fall of ${rs0(th)})"
        return Verdict(account, frozen, first, fall, th, words)
    }

    /** The state kept: frozen or not, which accounts tripped it, when, why, and the day of the last unfreeze. */
    data class State(val frozen: Boolean = false, val accounts: Set<Account> = emptySet(), val on: LocalDate? = null, val why: String = "",
                     val unfrozenOn: LocalDate? = null, val checkedOn: LocalDate? = null)

    /** [state] after today's [verdicts]: frozen when any account trips it (it stays frozen until Boss unfreezes it). */
    fun next(state: State, verdicts: List<Verdict>, today: LocalDate): State {
        val tripped = verdicts.filter { it.frozen }
        if (state.frozen) return state.copy(checkedOn = today)
        if (tripped.isEmpty()) return state.copy(checkedOn = today)
        return State(true, tripped.map { it.account }.toSet(), today, tripped.joinToString("; ") { it.words }, state.unfrozenOn, today)
    }

    /** Boss's unfreeze ([liveOn]: the live account is on, so only with [pinConfirmed]); null when refused. */
    fun unfreeze(state: State, today: LocalDate, liveOn: Boolean, pinConfirmed: Boolean): State? {
        if (!state.frozen) return state
        if (liveOn && !pinConfirmed) return null
        return State(false, emptySet(), null, "", today, state.checkedOn)
    }

    const val PIN_NEEDED = "The live account is on: unfreeze learning with your PIN (Settings → Bot settings → Learning freeze). Nothing changed."

    /** The diagnostics' and the card's line. */
    fun line(state: State): String = if (!state.frozen) "Learning: not frozen" + (state.unfrozenOn?.let { " (unfrozen by you on ${AgentLessons.day(it)})" } ?: "")
        else "Learning: FROZEN since ${state.on?.let { AgentLessons.day(it) } ?: "today"} (${state.accounts.joinToString(" and ") { it.words }}): ${state.why}"

    /** "Is learning frozen?" */
    fun say(state: State, verdicts: List<Verdict>): String {
        if (state.frozen) return "Yes, Boss: every automatic change is frozen since ${state.on?.let { AgentLessons.day(it) } ?: "today"}, because ${state.why}. " +
            "Autopilot, the strategy lab's promotions, the health tuner and the specialists' self-tuning are not applying anything; " +
            "they all keep recording. Unfreeze it in Settings → Bot settings → Learning freeze" + (" (your PIN while the live account is on).")
        val v = verdicts.joinToString(" ") { "${it.account.words.replaceFirstChar { c -> c.uppercase(Locale.ENGLISH) }}: ${it.words}." }
        return "No, Boss: learning is not frozen. $v".trim()
    }

    // ---- the file ---------------------------------------------------------------------------------------------------------

    fun encode(s: State): String = listOf("F1", s.frozen, s.accounts.joinToString(",") { it.name }, s.on ?: "", s.why.replace('|', ' ').replace('\n', ' '),
        s.unfrozenOn ?: "", s.checkedOn ?: "").joinToString("|")

    fun decode(text: String?): State {
        val p = text?.trim()?.split('|') ?: return State()
        if (p.size < 7 || p[0] != "F1") return State()
        return runCatching {
            State(p[1].toBoolean(), p[2].split(',').filter { it.isNotBlank() }.map { Account.valueOf(it) }.toSet(), p[3].takeIf { it.isNotBlank() }?.let(LocalDate::parse),
                p[4], p[5].takeIf { it.isNotBlank() }?.let(LocalDate::parse), p[6].takeIf { it.isNotBlank() }?.let(LocalDate::parse))
        }.getOrDefault(State())
    }

    private fun rs(x: Double): String = (if (x < 0) "−₹" else "+₹") + String.format(Locale.ENGLISH, "%,.0f", kotlin.math.abs(x))
    private fun rs0(x: Double): String = "₹" + String.format(Locale.ENGLISH, "%,.0f", x)
}
