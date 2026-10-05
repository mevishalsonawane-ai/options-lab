package com.optionslab.ira

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * A word before Boss sends an order (usefulness round 4, 2026-10-05): when he opens an order to review just after a
 * losing trade, after two losses in a row, past his usual number of trades for a day or past his own trade goal, in
 * the session's first five minutes, or against a rule he asked Jarvis to remember, Jarvis reminds him - gently, with
 * his own record where there is enough of it. Words only: it never blocks, delays or changes the order; sending it, or
 * not, is Boss's call. No amounts (counts and minutes only), so it may sit on any screen. Opening (new) orders only; a
 * closing order is never questioned. Pure.
 */
object PreTrade {
    /** A trade opened within this many minutes of a losing trade closing counts as "just after a loss". */
    const val AFTER_LOSS_MINUTES = 15L
    /** At least this many past trading days before "your usual day" is said. */
    const val MIN_DAYS = 5
    /** At least this many past trades in a group before its record is said. */
    const val MIN_RECORD = 5
    /** At least this many busy days before how they ended is said. */
    const val MIN_BUSY = 3
    /** The session opens at 09:15; "the first five minutes" is 09:15 up to 09:20 (as in [WeekReview]). */
    const val OPEN = 9 * 60 + 15
    const val FIRST_MINUTES = 5

    private fun plural(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"
    private fun pct(won: Int, of: Int) = if (of == 0) 0 else 100 * won / of
    private fun minuteOf(t: LocalDateTime) = t.hour * 60 + t.minute

    /** Trades opened within [AFTER_LOSS_MINUTES] after one of Boss's losing trades closed, the same day. */
    internal fun afterLoss(trips: List<Insights.Trip>): List<Insights.Trip> {
        val losses = trips.filter { it.net < 0 }
        return trips.filter { t ->
            losses.any { l -> l !== t && l.closedAt.toLocalDate() == t.openedAt.toLocalDate() && !t.openedAt.isBefore(l.closedAt) &&
                !t.openedAt.isAfter(l.closedAt.plusMinutes(AFTER_LOSS_MINUTES)) }
        }
    }

    /** Boss's usual number of trades on a day he trades (the median of past days, the lower one of an even count), when there are [MIN_DAYS] of them. */
    fun usualDay(trips: List<Insights.Trip>, today: LocalDate): Int? {
        val counts = trips.filter { it.openedAt.toLocalDate().isBefore(today) }.groupBy { it.openedAt.toLocalDate() }.values.map { it.size }.sorted()
        if (counts.size < MIN_DAYS) return null
        return counts[(counts.size - 1) / 2]
    }

    /**
     * The reminders for an order Boss is about to review now. [own]: Boss's own closed trades (not the bots'), any
     * period; [notes]: what he asked Jarvis to remember (his rules, [BossRules]); [market]: the order's index, when
     * known; [maxTrades]: his own "no more than N trades a day" goal, if set. Empty: nothing to remind.
     */
    fun reminders(own: List<Insights.Trip>, now: LocalDateTime, notes: List<String> = emptyList(), market: Market? = null,
                  expiryToday: Boolean = false, maxTrades: Int? = null): List<String> {
        val out = ArrayList<String>()
        val today = now.toLocalDate()
        val past = own.filter { !it.closedAt.isAfter(now) }
        val closedToday = past.filter { it.closedAt.toLocalDate() == today }.sortedBy { it.closedAt }
        val overall = pct(past.count { it.net > 0 }, past.size)

        // Just after a loss: how long ago, two in a row, and how such trades have gone for him.
        val last = closedToday.lastOrNull()
        if (last != null && last.net < 0) {
            val ago = Duration.between(last.closedAt, now).toMinutes()
            val streak = closedToday.takeLast(2).let { it.size == 2 && it.all { t -> t.net < 0 } }
            if (ago <= AFTER_LOSS_MINUTES) {
                val soon = afterLoss(past)
                val won = soon.count { it.net > 0 }
                out += (if (streak) "Your last two trades today both lost, the last one " else "Your last trade lost, ") +
                    (if (ago < 1) "just now." else "${plural(ago.toInt(), "minute")} ago.") +
                    (if (soon.size >= MIN_RECORD && pct(won, soon.size) < overall)
                        " Trades you took that soon after a loss won $won of ${soon.size} (${pct(won, soon.size)}%), against $overall% overall." else "")
            } else if (streak) out += "Your last two trades today both lost."
        }

        // Past his own goal, or his usual day.
        val n = own.count { it.openedAt.toLocalDate() == today }
        if (maxTrades != null && maxTrades > 0 && n >= maxTrades) {
            out += "Your own goal is no more than ${plural(maxTrades, "trade")} a day; this would be number ${n + 1}."
        } else usualDay(own, today)?.let { usual ->
            if (n < usual || n < 2) return@let
            val busy = own.filter { it.openedAt.toLocalDate().isBefore(today) }.groupBy { it.openedAt.toLocalDate() }.values.filter { it.size > usual }
            val red = busy.count { d -> d.sumOf { it.net } < 0 }
            out += "This would be trade number ${n + 1} today; on a usual day you take ${plural(usual, "trade")}." +
                (if (busy.size >= MIN_BUSY && red * 2 > busy.size) " Your days with more than that ended in the red $red times out of ${busy.size}." else "")
        }

        // The session's first five minutes.
        val m = minuteOf(now)
        if (m >= OPEN && m < OPEN + FIRST_MINUTES) {
            val first = past.filter { minuteOf(it.openedAt).let { x -> x >= OPEN && x < OPEN + FIRST_MINUTES } }
            val won = first.count { it.net > 0 }
            out += "It's the first five minutes of the session, when prices jump around most." +
                (if (first.size >= MIN_RECORD && pct(won, first.size) < overall) " Your trades opened then won $won of ${first.size}." else "")
        }

        // A rule he asked me to remember.
        if (market != null) BossRules.blocks(notes, market, expiryToday, m)?.let { why -> out += why.replaceFirstChar { it.uppercase() } + "." }
        return out
    }

    /** The index a contract is on, from the broker's symbol ("BANKNIFTY25O0855000PE": BankNifty), or null. */
    fun marketOf(symbol: String): Market? {
        val s = symbol.uppercase().substringAfter(':').trim()
        return Market.entries.filter { it != Market.VIX && it != Market.GOLD }.sortedByDescending { it.name.length }.firstOrNull { s.startsWith(it.name) }
    }

    /** The reminders in one gentle note, or null when there are none. */
    fun say(reminders: List<String>): String? = if (reminders.isEmpty()) null else
        "Boss, a moment before you send this. " + reminders.joinToString(" ") + " Your call - I haven't changed anything in the order."
}
