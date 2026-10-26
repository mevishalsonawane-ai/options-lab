package com.optionslab.engine.orb

import java.time.LocalDate
import java.time.LocalTime

/**
 * "R51 Donchian 20" (15 Oct 2026, Boss: "Both"): research R51's one pass, R44-donchian20-RT (research/HUNT_R51_CANCELLED_RECENT.md,
 * the decision table and Amendment A; code research/hunt/r51/fam_r44.py + r44/ind.py `donchian`), as a PAPER arm, 1 lot, its
 * own book, never live. It passed every gate, and it is thin: cleaned final test 1 Apr - 13 Oct 2026, 1,787 trades, +Rs 55,439,
 * twin p 0.002, BH q 0.025; at twice the spread +Rs 5,757; NIFTY lost; FINNIFTY carried it (reported apart: [REPORT_APART]).
 *
 *   signal   the INDEX's 5-minute bars (09:15-anchored, continuous across sessions, [PassedArms.bars5]): a close above the
 *            highest high of the previous 20 bars buys the call; a close below the lowest low of the previous 20 bars buys
 *            the put ([signal]). The previous bars run into the previous session(s), as the research's continuous series did.
 *   entry    the 1-ITM option of the nearest expiry ([PassedArms.itmStrike] on the index close at the signal bar's last
 *            minute) at the next bar's open, i.e. as soon as the bar has closed; the research filled within 2 minutes of it,
 *            else no fill ([FILL_SLACK_MINUTES]); entries 09:20 - 15:10 ([FIRST_ENTRY], [LAST_ENTRY])
 *   size     1 lot; one position a side per index, at most 2 entries a side a day per index ([MAX_PER_SIDE])
 *   days     never on that index's own expiry day
 *   exits    -15% stop, +30% target on the premium, out 30 minutes after the entry, square-off 15:15 ([EXITS])
 *   indices  NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY and SENSEX, as tested (the paper account takes SENSEX's BSE options since
 *            15 Oct: the Hero ticket's door). The FINNIFTY-only profit was found after the read: trading FINNIFTY alone would
 *            be a new rule, so all five run and FINNIFTY is reported apart.
 *   forward  [PassedArms.ForwardBar]: at 40 paper trades it switches itself off if the net is below 0 or below the cost of
 *            twice the spread.
 *   trend    16 Oct (research R73 T7, Boss: "Update normal Donchian ... T7 supertrend agree"): with the host's switch on, a break
 *            is taken only when Supertrend (10, 3) on the same 5-minute index series points the same way at the signal bar's
 *            close ([supertrendAgrees]). [signals] itself is unchanged (Solo 2.0's S2 reads it); the filter is applied where
 *            this arm decides its own entries. R73: final 1,670 trades, +Rs 1,34,743 vs Donchian's +Rs 81,845 on the same days.
 *
 * Pure: no clock, no network, no orders.
 */
object DonchianArm {
    const val SOURCE = "r51_donchian20"
    const val LABEL = "R51 Donchian 20"
    const val RESEARCH_ID = "R51 (R44-donchian20-RT)"
    val ARM = Arm(SOURCE, LABEL, paperOnly = true)
    val UNDERLYINGS = listOf("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")
    /** Reported apart (the pass depends on it, found after the read). */
    const val REPORT_APART = "FINNIFTY"

    const val LOOKBACK = 20
    const val MONEY = 1
    const val LOTS = 1
    const val MAX_PER_SIDE = 2
    /** The research filled at the minute after the bar, or up to 2 minutes later; never after. */
    const val FILL_SLACK_MINUTES = 2L
    val FIRST_ENTRY: LocalTime = LocalTime.of(9, 20)
    val LAST_ENTRY: LocalTime = LocalTime.of(15, 10)
    val SQUARE_OFF: LocalTime = LocalTime.of(15, 15)
    val EXITS = PassedArms.PremiumExits(stop = 0.15, target = 0.30, tmaxMinutes = 30, squareOff = SQUARE_OFF)

    const val RULES = "On the index's 5-minute bars: a close above the highest high of the previous 20 bars buys the 1-ITM call, a close " +
        "below the lowest low buys the 1-ITM put (nearest expiry), at the next bar's open, 09:20-15:10; stop −15%, target +30% on the " +
        "premium, out after 30 minutes, square-off 15:15; one position a side, at most 2 entries a side a day; never on the index's " +
        "own expiry day; NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX; 1 lot; paper only"
    const val RECORD = "Research R51 final test (cleaned): 1,787 trades, +₹55,439, p 0.002, q 0.025 - thin: +₹5,757 at twice the spread, " +
        "NIFTY lost, FINNIFTY carried it (reported apart)"

    /** R73 T7: Supertrend's ATR length and band multiple (research/hunt/r73/r73rules.py `supertrend_dir`). */
    const val ST_ATR = 10
    const val ST_MULT = 3.0
    const val ST_RESEARCH = "R73 T7"
    /**
     * Earlier sessions the series must hold for Supertrend to be judged; fewer: not judged, so the break is not taken. 16 Oct
     * (R73 review): the research ran Supertrend on one continuous series of every session, so the host feeds it every earlier
     * session it already holds in memory (about 11, its 16-day prefetch), not only the 2 Donchian's own signals read. Over
     * that many bars the Wilder ATR has settled and the direction has usually flipped since the start, so where the series
     * began rarely matters (not measured against the research's own series). 1 is the floor below which there is no warm-up.
     */
    const val ST_MIN_SESSIONS = 1
    const val ST_RULE = "a break is taken only when Supertrend (10, 3) on the same 5-minute index bars points the same way at the " +
        "signal bar's close (R73 T7)"
    /** What a filtered break Supertrend cannot judge does (16 Oct, R73 review: said wherever the filter is described). */
    const val ST_UNJUDGED_RULE = "a break it cannot judge (no earlier session's bars in, or no direction yet) is not taken"

    /**
     * 16 Oct (R73 review): the arm's rules as the row and Jarvis say them, with the Supertrend filter when it is on ([stOn]):
     * the plain rules otherwise.
     */
    fun rules(stOn: Boolean): String = if (stOn) "$RULES. Supertrend filter on (your ask, paper only): $ST_RULE; $ST_UNJUDGED_RULE" else RULES

    const val ST_RECORD = "Research R73 T7 final test: 1,670 trades, +₹1,34,743 vs Donchian's +₹81,845 on the same days " +
        "(design +₹50,425 vs +₹9,998); random-entry q 0.013, random-skip q 0.018; it skipped 162 losers (₹1,70,175) and " +
        "111 winners (₹1,61,923)"

    /**
     * Supertrend ([n], [mult]) on [series] (oldest first): +1 up, -1 down, 0 until its ATR exists, for each bar. R73's
     * `supertrend_dir` line for line - Wilder ATR (the first value the mean of the first [n] true ranges), bands
     * (high + low) / 2 ± [mult] × ATR, the usual flip - with one guard: a bar with a missing price is left out (0), as the
     * research's series had no such bar. Pure.
     */
    fun supertrend(series: List<PassedArms.Bar5>, n: Int = ST_ATR, mult: Double = ST_MULT): IntArray = supertrendLines(series, n, mult).dir

    /**
     * 26 Oct (R105 lead 7, the parity test): [supertrend]'s working lines per bar of [series] - the direction, the Wilder ATR and
     * the two final bands (NaN where none: before the ATR exists, or a bar left out for a missing price), so they can be held to
     * R73's own numbers. Pure.
     */
    class Lines(val dir: IntArray, val atr: DoubleArray, val up: DoubleArray, val dn: DoubleArray)

    fun supertrendLines(series: List<PassedArms.Bar5>, n: Int = ST_ATR, mult: Double = ST_MULT): Lines {
        val out = IntArray(series.size)
        val atrOut = DoubleArray(series.size) { Double.NaN }
        val upOut = DoubleArray(series.size) { Double.NaN }
        val dnOut = DoubleArray(series.size) { Double.NaN }
        val ok = series.indices.filter { val x = series[it]; !x.high.isNaN() && !x.low.isNaN() && !x.close.isNaN() }
        val m = ok.size
        if (m == 0) return Lines(out, atrOut, upOut, dnOut)
        val h = DoubleArray(m) { series[ok[it]].high }; val l = DoubleArray(m) { series[ok[it]].low }; val c = DoubleArray(m) { series[ok[it]].close }
        val tr = DoubleArray(m)
        tr[0] = h[0] - l[0]
        for (i in 1 until m) tr[i] = maxOf(h[i] - l[i], kotlin.math.abs(h[i] - c[i - 1]), kotlin.math.abs(l[i] - c[i - 1]))
        val atr = DoubleArray(m) { Double.NaN }
        if (m >= n) {
            atr[n - 1] = (0 until n).sumOf { tr[it] } / n
            for (i in n until m) atr[i] = (atr[i - 1] * (n - 1) + tr[i]) / n
        }
        var up = Double.NaN; var dn = Double.NaN; var cur = 0
        for (i in 0 until m) {
            if (atr[i].isNaN()) continue
            val mid = (h[i] + l[i]) / 2.0
            val bu = mid - mult * atr[i]; val bd = mid + mult * atr[i]
            if (cur == 0) { up = bu; dn = bd; cur = if (c[i] >= mid) 1 else -1 }
            else {
                up = if (c[i - 1] > up) maxOf(bu, up) else bu
                dn = if (c[i - 1] < dn) minOf(bd, dn) else bd
                if (cur == -1 && c[i] > dn) cur = 1 else if (cur == 1 && c[i] < up) cur = -1
            }
            out[ok[i]] = cur
            atrOut[ok[i]] = atr[i]; upOut[ok[i]] = up; dnOut[ok[i]] = dn
        }
        return Lines(out, atrOut, upOut, dnOut)
    }

    /**
     * R73 T7 for [s]: true when Supertrend on [series] (only the bars up to and including the signal bar: nothing later is read)
     * points [s]'s way at its bar, false when it points the other way; null when it cannot be judged - the bar is not in the
     * series, Supertrend has no direction yet, or fewer than [ST_MIN_SESSIONS] earlier sessions are in the series.
     */
    fun supertrendAgrees(series: List<PassedArms.Bar5>, s: Signal): Boolean? {
        val i = series.indexOfLast { it.date == s.bar.date && it.b == s.bar.b }
        if (i < 0) return null
        val upTo = series.subList(0, i + 1)
        if (upTo.map { it.date }.distinct().count { it.isBefore(s.bar.date) } < ST_MIN_SESSIONS) return null
        val d = supertrend(upTo)[i]
        return if (d == 0) null else d == s.side
    }

    /** A Donchian break on bar [bar] (the signal bar): [side] +1 buys the call, -1 the put. */
    data class Signal(val underlying: String, val side: Int, val bar: PassedArms.Bar5, val strike: Int) {
        val entryTime get() = bar.closesAt
    }

    /**
     * R44's `donchian` on a continuous series of 5-minute bars ([series], oldest first): the side at bar [i] (+1, -1, or 0),
     * comparing its close with the highest high / lowest low of bars i-20 .. i-1. Fewer than 20 bars before it, or a missing
     * price among them: no signal.
     */
    fun sideAt(series: List<PassedArms.Bar5>, i: Int, n: Int = LOOKBACK): Int {
        if (i < n || i >= series.size) return 0
        val w = series.subList(i - n, i)
        if (w.any { it.high.isNaN() || it.low.isNaN() }) return 0
        val c = series[i].close
        if (c.isNaN()) return 0
        return when { c > w.maxOf { it.high } -> 1; c < w.minOf { it.low } -> -1; else -> 0 }
    }

    /** [t] is an entry minute (09:20 .. 15:10, both included). */
    fun inWindow(t: LocalTime): Boolean = !t.isBefore(FIRST_ENTRY) && !t.isAfter(LAST_ENTRY)

    /**
     * Today's signals on [underlying] from the series [series] (the earlier sessions' 5-minute bars, then [today]'s closed
     * ones): every bar of [today] that breaks, with its entry minute in the window, oldest first, its strike from the index
     * close of the bar's last minute ([closeAtSignal]). The day's caps (one position a side, 2 a side) are [take]'s.
     */
    fun signals(underlying: String, series: List<PassedArms.Bar5>, today: LocalDate): List<Signal> {
        val out = ArrayList<Signal>()
        for (i in series.indices) {
            val b = series[i]
            if (b.date != today || !inWindow(b.closesAt.toLocalTime())) continue
            val s = sideAt(series, i)
            if (s != 0) out += Signal(underlying, s, b, PassedArms.itmStrike(underlying, s, b.close, MONEY))
        }
        return out
    }

    /**
     * May [s] be bought now? One position a side ([openSides]: the sides held on this index now) and at most 2 entries a side
     * a day ([takenToday]: entries so far today by side). The entry window and the 2-minute fill slack are checked by
     * [mayFill].
     */
    fun mayTake(s: Signal, openSides: Set<Int>, takenToday: Map<Int, Int>): String? = when {
        s.side in openSides -> "one position a side: the ${if (s.side > 0) "call" else "put"} is still held"
        (takenToday[s.side] ?: 0) >= MAX_PER_SIDE -> "2 entries a side today already"
        else -> null
    }

    /** The research filled at the bar's close or up to 2 minutes after it (else no fill): [now] is still in time. */
    fun mayFill(s: Signal, now: java.time.LocalDateTime): Boolean =
        !now.isBefore(s.entryTime) && now.isBefore(s.entryTime.plusMinutes(FILL_SLACK_MINUTES + 1))

    /** The first moment [s] may no longer fill (its fill window's end, exclusive): [mayFill]'s bound. */
    fun windowEnd(s: Signal): java.time.LocalDateTime = s.entryTime.plusMinutes(FILL_SLACK_MINUTES + 1)

    /**
     * 26 Oct (R105 lead 3: four breaks lost to "the fill window passed" on 21 and 22 Oct): how soon an entry check that could not
     * settle a bar comes back - a break due and not decided, or an index whose bar was not whole yet - instead of the next full
     * pass up to a minute later. Paper only (the host asks only while no arm here can be mirrored live).
     */
    const val RETRY_SECONDS = 10L

    /**
     * When the entry check should look again at [now] for work whose fill windows end at [ends] (exclusive): [RETRY_SECONDS] on,
     * or a second before the nearest window still open ends, whichever is sooner; null when none is still open (nothing to
     * retry: the window passed, or nothing was pending). Pure.
     */
    fun nextRetry(now: java.time.LocalDateTime, ends: Collection<java.time.LocalDateTime>): java.time.LocalDateTime? {
        val end = ends.filter { it.isAfter(now.plusSeconds(1)) }.minOrNull() ?: return null
        return minOf(now.plusSeconds(RETRY_SECONDS), end.minusSeconds(1))
    }

    /**
     * Whether an index read at [at] holds the whole 5-minute bar that closed last (its last minute, the one before the 5-minute
     * boundary at or before [at]): [lastMinute] is the read's last whole minute today (null: none). Before 09:20 there is no
     * such bar: true. Pure.
     */
    fun barWhole(lastMinute: LocalTime?, at: java.time.LocalDateTime): Boolean {
        val m = at.hour * 60 + at.minute
        if (m < 9 * 60 + 20) return true
        val close = 9 * 60 + 15 + ((m - (9 * 60 + 15)) / 5) * 5
        val need = LocalTime.of((close - 1) / 60, (close - 1) % 60)
        return lastMinute != null && !lastMinute.isBefore(need)
    }

    /**
     * One entry check's read of an index (22 Oct): when it read, and the last whole minute it had (null: none today); [failed]: the
     * read itself failed (nothing came back).
     */
    data class Look(val at: java.time.LocalDateTime, val lastMinute: LocalTime?, val failed: Boolean = false)

    /**
     * 22 Oct (Boss: "check why other arms did not trigger"): why [s]'s fill window passed with nothing bought, in words, so a lost
     * break is never only "missed". [tried]: the last reason an entry check inside the window could not buy it (null: none tried);
     * [looks]: the entry checks' reads of its index. In order: tried and refused; no check ran inside the window (our own pace);
     * every check inside the window lacked the bar's last minute (the feed was late); or the bar read inside the window did not
     * break and the minutes were corrected after. [others]: for a rule that reads other indices too (R72's breadth), their looks by
     * name - the first of them whose minute was late inside the window is named instead. Words only: it never changes a decision.
     */
    fun missedWhy(s: Signal, tried: String?, looks: List<Look>, others: Map<String, List<Look>> = emptyMap()): String {
        val from = s.entryTime
        val last = from.plusMinutes(FILL_SLACK_MINUTES)
        val window = "${hm(from.toLocalTime())}-${hm(last.toLocalTime())}"
        val head = "${hm(from.toLocalTime())} ${if (s.side > 0) "call" else "put"} break"
        if (tried != null) return "$head not bought: $tried (tried until ${hm(last.toLocalTime())}, the end of its fill window)"
        val inside = looks.filter { mayFill(s, it.at) }.sortedBy { it.at }
        if (inside.isEmpty()) return "$head missed: no entry check ran in its fill window ($window)"
        val barLast = from.minusMinutes(1).toLocalTime()
        fun late(l: List<Look>) = l.isNotEmpty() && l.all { it.lastMinute == null || it.lastMinute.isBefore(barLast) }
        fun lateWords(who: String, l: List<Look>): String {
            val x = l.last()
            val had = if (x.failed) "could not read it" else "had minutes to ${x.lastMinute?.let { hm(it) } ?: "none"}"
            return "$head missed: $who ${hm(barLast)} minute had not reached the phone in its fill window ($window; the last check, " +
                "${x.at.toLocalTime().withNano(0)}, $had)"
        }
        if (late(inside)) return lateWords("the index's", inside)
        for ((name, l) in others) {
            val inOther = l.filter { mayFill(s, it.at) }.sortedBy { it.at }
            if (late(inOther)) return lateWords("$name's", inOther)
        }
        return "$head missed: the bar read in its fill window ($window) did not break; the index's minutes were corrected after"
    }

    private fun hm(t: LocalTime): String = "%02d:%02d".format(java.util.Locale.ENGLISH, t.hour, t.minute)
}
