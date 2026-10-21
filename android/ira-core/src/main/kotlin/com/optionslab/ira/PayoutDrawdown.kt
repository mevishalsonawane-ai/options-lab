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

    // ---- 21 Oct, enhancement round 5 (idea 5): did the payout really leave? -------------------------------------------------

    /**
     * One day's balances as Kite's margins read them: [opening], the day's opening balance (the first read of the day), and
     * [expectedNext], what the NEXT day's opening balance should be if no money left or came in: the opening plus the day's
     * realised P&L, less the premium of options bought and still held, plus money added in the day (the day's last read). Both
     * NaN when not known. Approximate: charges are not in it (they make the next opening a little lower).
     */
    data class Balance(val day: LocalDate, val opening: Double, val expectedNext: Double)

    /** Days of balances kept. */
    const val KEEP_BALANCES = 10

    /** [list] with [day]'s read: its first opening balance kept, its expected next opening the latest. A bad figure changes nothing. */
    fun recordBalance(list: List<Balance>, day: LocalDate, opening: Double, expectedNext: Double): List<Balance> {
        val okOpen = opening.isFinite() && opening > 0.0
        val okNext = expectedNext.isFinite()
        if (!okOpen && !okNext) return list
        val old = list.firstOrNull { it.day == day }
        val next = Balance(day, if (old != null && old.opening.isFinite()) old.opening else if (okOpen) opening else Double.NaN,
            if (okNext) expectedNext else old?.expectedNext ?: Double.NaN)
        return (list.filter { it.day != day } + next).sortedBy { it.day }.takeLast(KEEP_BALANCES)
    }

    /** What the balances say of one payout. */
    enum class Left { CONFIRMED, NOT_SEEN, UNKNOWN }

    /** Slack for charges and rounding: Rs 500, or 5% of the payout if more. */
    const val LEFT_TOLERANCE_RS = 500.0
    const val LEFT_TOLERANCE_PCT = 5.0

    /**
     * Whether payout [p] really left the account: the first opening balance read AFTER its day is lower than its day's
     * [Balance.expectedNext] by at least the payout (less [LEFT_TOLERANCE_RS] or [LEFT_TOLERANCE_PCT]). A payout cancelled or
     * rejected after its day's last read leaves the next opening where it was: [Left.NOT_SEEN]. No read of its day's balances or
     * of a later opening yet (today's payout, the app not opened since): [Left.UNKNOWN]. Money added the next morning before the
     * read, or a profit the realised figure missed, can hide a real payout (then it is NOT_SEEN: the offer waits, never the reverse).
     */
    fun left(p: Payout, balances: List<Balance>): Left {
        val d = balances.firstOrNull { it.day == p.day }?.takeIf { it.expectedNext.isFinite() } ?: return Left.UNKNOWN
        val n = balances.filter { it.day.isAfter(p.day) && it.opening.isFinite() && it.opening > 0.0 }.minByOrNull { it.day }
            ?: return Left.UNKNOWN
        val drop = d.expectedNext - n.opening
        val tol = maxOf(LEFT_TOLERANCE_RS, p.amount * LEFT_TOLERANCE_PCT / 100)
        return if (drop >= p.amount - tol) Left.CONFIRMED else Left.NOT_SEEN
    }

    /** The payouts [paidOutSince] counts, split by [left]: (confirmed, not seen leaving, not known yet), in rupees. */
    data class Split(val confirmed: Double, val notSeen: Double, val unknown: Double) {
        val all: Double get() = confirmed + notSeen + unknown
    }

    fun paidOutSplit(list: List<Payout>, balances: List<Balance>, peak: Double, today: LocalDate): Split {
        if (!peak.isFinite() || peak <= 0.0) return Split(0.0, 0.0, 0.0)
        val counted = list.filter { it.day.isBefore(today) && it.peak.isFinite() && abs(it.peak - peak) < 0.5 }
        val by = counted.groupBy { left(it, balances) }.mapValues { (_, ps) -> ps.sumOf { it.amount } }
        return Split(by[Left.CONFIRMED] ?: 0.0, by[Left.NOT_SEEN] ?: 0.0, by[Left.UNKNOWN] ?: 0.0)
    }

    /**
     * Plain words when the payouts would explain the drawdown ([all], every payout counted) but those confirmed by the next
     * opening balance do not ([confirmed]): nothing is offered until they are. Null when there is nothing to say.
     */
    fun waitingWords(all: Judged?, confirmed: Judged?, split: Split, on: Boolean): String? {
        if (all == null || !all.explained || (confirmed != null && confirmed.explained)) return null
        val head = "Payouts of Rs %,.0f since the peak would explain this drawdown, but the next day's opening balance has not shown ".format(all.paidOut) +
            (if (split.notSeen > 0) "Rs %,.0f of them leaving".format(split.notSeen) else "them leaving yet") +
            (if (split.unknown > 0 && split.notSeen > 0) " (Rs %,.0f not read yet)".format(split.unknown) else "")
        return if (on) "$head. Nothing is offered until it does; the kill switch stays on." else "$head. Shadow: logged only; nothing changes."
    }

    /** "day|opening|expectedNext;..." for the settings vault; [decodeBalances] reads it back (a bad row is skipped). */
    fun encodeBalances(list: List<Balance>): String = list.joinToString(";") { "${it.day}|${it.opening}|${it.expectedNext}" }

    fun decodeBalances(s: String?): List<Balance> = s.orEmpty().split(';').mapNotNull { row ->
        val f = row.split('|')
        if (f.size != 3) return@mapNotNull null
        val day = runCatching { LocalDate.parse(f[0]) }.getOrNull() ?: return@mapNotNull null
        val o = f[1].toDoubleOrNull() ?: Double.NaN
        val e = f[2].toDoubleOrNull() ?: Double.NaN
        if (!o.isFinite() && !e.isFinite()) return@mapNotNull null
        Balance(day, o, e)
    }.sortedBy { it.day }.takeLast(KEEP_BALANCES)

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
