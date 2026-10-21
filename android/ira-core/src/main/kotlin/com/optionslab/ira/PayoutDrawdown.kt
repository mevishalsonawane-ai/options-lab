package com.optionslab.ira

import java.time.LocalDate
import kotlin.math.abs

/**
 * 21 Oct, enhancement round 4 (idea 3): a payout-aware drawdown for the account guard's kill switch.
 *
 * The guard measures the Zerodha account's drawdown from its highest equity (the opening balance plus the day's P&L). Money taken
 * out lowers the NEXT day's opening balance, so a payout reads as a drawdown from the peak, and a big enough one turns the kill
 * switch on although nothing was lost. Kite's margins say the day's payout (utilised.payout); each one seen is kept here with the
 * peak that stood when it was seen. While that peak still stands, the payouts made before today explain that much of the drop.
 *
 * It only ever explains; it never turns the kill switch off or keeps it from going on by itself (CLAUDE.md: a live change may only
 * lower risk). In shadow (the default) what it would say is logged; on, Bot settings offers Boss to lower the peak by the payouts,
 * with the PIN. Pure: no clock, no I/O.
 */
object PayoutDrawdown {
    /** One day's payout as Kite last said it, and the drawdown peak that stood when it was first seen (NaN: not known then). */
    data class Payout(val day: LocalDate, val amount: Double, val peak: Double)

    /** Days kept (the oldest dropped first). */
    const val KEEP = 60

    /**
     * [list] with [day]'s payout read as [amount] (Kite's figure for the day, as last read: review - a payout requested and then
     * cancelled the same day reads back as 0, and that removes the day, so money that never left is never counted). [peak]: the
     * drawdown peak now (kept from the day's first reading; a later reading never moves it). An unknown amount changes nothing.
     */
    fun record(list: List<Payout>, day: LocalDate, amount: Double, peak: Double): List<Payout> {
        if (!amount.isFinite()) return list
        if (amount <= 0.0) return list.filter { it.day != day }
        val old = list.firstOrNull { it.day == day }
        val next = if (old == null) Payout(day, amount, peak)
            else old.copy(amount = amount, peak = if (old.peak.isFinite()) old.peak else peak)
        return (list.filter { it.day != day } + next).sortedBy { it.day }.takeLast(KEEP)
    }

    /**
     * What was paid out before [today] while the drawdown [peak] stood where it stands now (within half a rupee): that much of a drop
     * below [peak] is money taken out, not lost. A payout seen under another peak (the peak rose since: it was set on the lower
     * balance already) or with no peak known never counts. Today's payout does not count yet: the opening balance drops tomorrow.
     */
    fun paidOutSince(list: List<Payout>, peak: Double, today: LocalDate): Double {
        if (!peak.isFinite() || peak <= 0.0) return 0.0
        return list.filter { it.day.isBefore(today) && it.peak.isFinite() && abs(it.peak - peak) < 0.5 }.sumOf { it.amount }
    }

    /** The drawdown from [peak] as a percentage, raw and with [paidOut] added back to [equity]; whether each reaches [limitPct]. */
    data class Judged(val pct: Double, val pctLessPayouts: Double, val paidOut: Double, val reached: Boolean, val reachedLessPayouts: Boolean) {
        /** The payouts alone explain why the limit was reached. */
        val explained: Boolean get() = reached && !reachedLessPayouts && paidOut > 0.0
    }

    /** Null when it cannot be judged (an unknown or non-positive peak, an unknown equity, no limit). */
    fun judge(peak: Double, equity: Double, paidOut: Double, limitPct: Double): Judged? {
        if (!peak.isFinite() || peak <= 0.0 || !equity.isFinite() || !(limitPct > 0.0)) return null
        val paid = if (paidOut.isFinite()) paidOut.coerceAtLeast(0.0) else 0.0
        val pct = (peak - equity) / peak * 100
        val less = (peak - (equity + paid)) / peak * 100
        return Judged(pct, less, paid, pct >= limitPct, less >= limitPct)
    }

    /** The peak lowered by [paidOut], never under [equity] (and never raised). */
    fun lowered(peak: Double, paidOut: Double, equity: Double): Double {
        if (!peak.isFinite() || !(paidOut > 0.0)) return peak
        val floor = if (equity.isFinite()) equity else Double.NEGATIVE_INFINITY
        return minOf(peak, maxOf(peak - paidOut, floor))
    }

    /** The payouts that [paidOutSince] counted are used once the peak is lowered by them: dropped. */
    fun used(list: List<Payout>, peak: Double, today: LocalDate): List<Payout> =
        list.filterNot { it.day.isBefore(today) && it.peak.isFinite() && abs(it.peak - peak) < 0.5 }

    /** Plain words for the diagnostics and Bot settings, from a [Judged] (null: nothing to say). */
    fun words(j: Judged?, on: Boolean): String? {
        if (j == null || !j.explained) return null
        val head = "Payouts of Rs %,.0f since the peak explain this drawdown (%.1f%%; %.1f%% counting them)".format(j.paidOut, j.pct, j.pctLessPayouts)
        return if (on) "$head. The kill switch stays on until you lower the peak by them in Bot settings (PIN) and clear it."
        else "$head. Shadow: logged only; nothing changes."
    }

    /** "day|amount|peak;..." for the settings vault; [decode] reads it back (a bad row is skipped). */
    fun encode(list: List<Payout>): String = list.joinToString(";") { "${it.day}|${it.amount}|${it.peak}" }

    fun decode(s: String?): List<Payout> = s.orEmpty().split(';').mapNotNull { row ->
        val f = row.split('|')
        if (f.size != 3) return@mapNotNull null
        val day = runCatching { LocalDate.parse(f[0]) }.getOrNull() ?: return@mapNotNull null
        val amount = f[1].toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: return@mapNotNull null
        Payout(day, amount, f[2].toDoubleOrNull() ?: Double.NaN)
    }.sortedBy { it.day }.takeLast(KEEP)
}
