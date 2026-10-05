package com.optionslab.ira

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

/**
 * Trade replay (usefulness round 5, 2026-10-05): each of Boss's closed trades laid against the app's own minute
 * candles - how far the contract went his way and against him while he held it, what it did after he got out, and how
 * the index moved meanwhile - so he learns about his exits from facts. "Jarvis, how was my last trade?", "go over my
 * trades today", "how were my exits?". Never advice and never a forecast; it reads candles and changes nothing. Pure.
 */
object TradeReplay {
    /** A closed round trip; [direction] +1 bought first, -1 sold first; prices per unit. */
    data class Trip(val symbol: String, val direction: Int, val qty: Int, val entry: Double, val exit: Double,
                    val openedAt: LocalDateTime, val closedAt: LocalDateTime, val net: Double, val owner: String = "Manual")

    data class Replay(
        val trip: Trip,
        /** Furthest his way and furthest against him while held (the fills count too); null when no candle covers the hold. */
        val best: Double?, val bestAt: LocalDateTime?, val worst: Double?, val worstAt: LocalDateTime?,
        /** After the exit, the same day, until [afterUntil]: furthest his way and furthest against. */
        val afterBest: Double? = null, val afterBestAt: LocalDateTime? = null, val afterWorst: Double? = null, val afterUntil: LocalDateTime? = null,
        /** The index the contract is on, where it was at the entry and at the exit, and its whole day so far (%). */
        val index: Market? = null, val indexFrom: Double? = null, val indexTo: Double? = null, val indexDayPct: Double? = null,
    ) {
        /** Points taken (+ his way). */
        val taken: Double get() = (trip.exit - trip.entry) * trip.direction
        /** Points it went his way at best while held (0 or more). */
        val run: Double? get() = best?.let { ((it - trip.entry) * trip.direction).coerceAtLeast(0.0) }
        /** Points it went against him at worst while held (0 or more). */
        val heat: Double? get() = worst?.let { ((trip.entry - it) * trip.direction).coerceAtLeast(0.0) }
        /** Points beyond his exit at the best price while held (0 or more). */
        val leftInHold: Double? get() = best?.let { ((it - trip.exit) * trip.direction).coerceAtLeast(0.0) }
        /** Points further his way after he got out (0 or more). */
        val leftAfter: Double? get() = afterBest?.let { ((it - trip.exit) * trip.direction).coerceAtLeast(0.0) }
        /** Points it went against his side after he got out (0 or more): what staying in would have cost at worst. */
        val againstAfter: Double? get() = afterWorst?.let { ((trip.exit - it) * trip.direction).coerceAtLeast(0.0) }
    }

    enum class Scope { LAST, TODAY }

    private fun norm(text: String) = " " + text.lowercase().replace("'", "").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim() + " "

    private val LAST = Regex(" (how was|how did|how s|hows|how is|how about|replay|review|tell me about|walk me through|go over|go through|break down|look at|analy[sz]e) " +
        "(my|the) (last|latest|previous|most recent|recent) (trade|exit|round trip)s? ")
    // ("Review my trades" alone stays the weekly review: a review is today's only when it says so, or is of the exits.)
    private val TODAY = Regex(" (replay|go over|go through|walk me through|break down) (my|today s|todays|the) (trades|exits|round trips) " +
        "| (review|analy[sz]e) ((my|the) (trades|round trips) (of )?today|(today s|todays) (trades|round trips)|(my|today s|todays|the) exits) " +
        "| how (were|was|did|are) (my|today s|todays) exits | (how|when) did i (exit|get out) | did i (exit|get out|sell|close) (too )?(early|late|soon) " +
        "| trade replay | replay (of )?(my|today s|todays) trades? ")
    /**
     * Does [text] ask for the replay: the last trade, or today's trades? Only questions ("why did my last trade lose" is
     * the loss reasons; "close my last trade" is an order and never matches).
     */
    fun asked(text: String): Scope? {
        val t = norm(text)
        if (Regex(" why ").containsMatchIn(t)) return null
        if (LAST.containsMatchIn(t)) return Scope.LAST
        // Another period is the trade search's or the weekly review's.
        if (Regex(" (week|month|yesterday|weekly|monthly) ").containsMatchIn(t)) return null
        if (TODAY.containsMatchIn(t)) return Scope.TODAY
        return null
    }

    /**
     * [trip] against [option] (the contract's minute candles, start times) and [index] (its index's minute candles),
     * up to [now].
     */
    fun of(trip: Trip, option: List<Candle>, index: List<Candle>? = null, market: Market? = null, now: LocalDateTime? = null): Replay {
        val inMin = trip.openedAt.truncatedTo(ChronoUnit.MINUTES); val outMin = trip.closedAt.truncatedTo(ChronoUnit.MINUTES)
        val long = trip.direction >= 0
        val held = option.filter { !it.t.isBefore(inMin) && !it.t.isAfter(outMin) }
        var best: Double? = null; var bestAt: LocalDateTime? = null; var worst: Double? = null; var worstAt: LocalDateTime? = null
        if (held.isNotEmpty()) {
            val hi = held.maxByOrNull { it.h }!!; val lo = held.minByOrNull { it.l }!!
            val (b, bt) = if (long) hi.h to hi.t else lo.l to lo.t
            val (w, wt) = if (long) lo.l to lo.t else hi.h to hi.t
            // The fills themselves are prices it traded at: the best is never short of the exit, the worst never of the entry.
            val fillBest = if (long) maxOf(trip.entry, trip.exit) else minOf(trip.entry, trip.exit)
            val fillWorst = if (long) minOf(trip.entry, trip.exit) else maxOf(trip.entry, trip.exit)
            if ((b - fillBest) * trip.direction >= 0) { best = b; bestAt = bt } else { best = fillBest; bestAt = if (fillBest == trip.exit) trip.closedAt else trip.openedAt }
            if ((fillWorst - w) * trip.direction >= 0) { worst = w; worstAt = wt } else { worst = fillWorst; worstAt = if (fillWorst == trip.entry) trip.openedAt else trip.closedAt }
        }
        val day = trip.closedAt.toLocalDate()
        val after = option.filter { it.t.isAfter(outMin) && it.t.toLocalDate() == day && (now == null || !it.t.isAfter(now)) }
        var aBest: Double? = null; var aBestAt: LocalDateTime? = null; var aWorst: Double? = null; var until: LocalDateTime? = null
        if (after.isNotEmpty()) {
            val hi = after.maxByOrNull { it.h }!!; val lo = after.minByOrNull { it.l }!!
            if (long) { aBest = hi.h; aBestAt = hi.t; aWorst = lo.l } else { aBest = lo.l; aBestAt = lo.t; aWorst = hi.h }
            until = after.last().t.plusMinutes(1)
        }
        var from: Double? = null; var to: Double? = null; var dayPct: Double? = null
        if (index != null) {
            val ix = index.filter { now == null || !it.t.isAfter(now) }.sortedBy { it.t }
            from = ix.firstOrNull { !it.t.isBefore(inMin) }?.o
            to = ix.lastOrNull { !it.t.isAfter(outMin) && !it.t.isBefore(inMin) }?.c
            if (from == null || to == null) { from = null; to = null }
            val today = ix.filter { it.t.toLocalDate() == day }
            val base = ix.lastOrNull { it.t.toLocalDate().isBefore(day) }?.c ?: today.firstOrNull()?.o
            val last = today.lastOrNull()?.c
            if (base != null && last != null && base > 0) dayPct = (last / base - 1) * 100
        }
        return Replay(trip, best, bestAt, worst, worstAt, aBest, aBestAt, aWorst, until, market, from, to, dayPct)
    }

    private fun px(x: Double) = "%,.2f".format(Locale.ENGLISH, x)
    private fun pts(x: Double) = "%.2f".format(Locale.ENGLISH, x)
    private fun signed(x: Double) = "%+.2f".format(Locale.ENGLISH, x)
    private fun pct(x: Double) = "%+.2f%%".format(Locale.ENGLISH, x)
    private fun hm(t: LocalDateTime) = "%02d:%02d".format(Locale.ENGLISH, t.hour, t.minute)
    private fun rs(x: Double) = (if (x < 0) "-Rs " else "+Rs ") + "%,.2f".format(Locale.ENGLISH, abs(x))
    private fun amt(x: Double) = "Rs " + "%,.2f".format(Locale.ENGLISH, abs(x))
    private fun ix(x: Double) = "%,.0f".format(Locale.ENGLISH, x)

    private fun opened(t: Trip) = if (t.direction >= 0) "bought" else "sold"
    private fun closed(t: Trip) = if (t.direction >= 0) "sold" else "bought back"
    private fun by(t: Trip) = if (t.owner.startsWith("Manual")) "" else " (${t.owner})"

    /** The whole replay of one trade, a finished sentence a line; [account] "Paper" or "Zerodha". */
    fun lines(account: String, r: Replay): List<String> {
        val t = r.trip
        val minutes = java.time.Duration.between(t.openedAt, t.closedAt).toMinutes()
        val out = ArrayList<String>()
        out += "$account trade ${hm(t.openedAt)} to ${hm(t.closedAt)}: ${opened(t)} ${t.qty} ${t.symbol} at ${px(t.entry)}, ${closed(t)} at ${px(t.exit)} " +
            "(${signed(r.taken)} points in $minutes min), net ${rs(t.net)}${by(t)}."
        val run = r.run; val heat = r.heat
        if (run == null || heat == null) {
            out += "The minute candles of ${t.symbol} could not be read for that time, so only the fills are known."
        } else {
            val up = if (t.direction >= 0) "above" else "below"; val down = if (t.direction >= 0) "below" else "above"
            out += "While you held it: " +
                (if (run > 0) "at best ${px(r.best!!)} at ${hm(r.bestAt!!)} (${pts(run)} points your way)" else "it never went $up your entry") + "; " +
                (if (heat > 0) "at worst ${px(r.worst!!)} at ${hm(r.worstAt!!)} (${pts(heat)} points against you${if (t.entry > 0) ", " + "%.0f".format(Locale.ENGLISH, 100 * heat / t.entry) + "% of the entry price" else ""})"
                 else "it never went $down your entry") + "."
            val left = r.leftInHold ?: 0.0
            out += when {
                run <= 0.0 -> "There was no gain to take while you held it: it went only against you."
                r.taken > 0 -> "You kept ${pts(r.taken)} of the ${pts(run)} points it gave while held (${"%.0f".format(Locale.ENGLISH, 100 * r.taken / run)}%)" +
                    (if (left > 0) "; ${pts(left)} more were there at its best, ${amt(left * t.qty)} on ${t.qty}." else ": you got out at its best.")
                else -> "It was ${pts(run)} points your way at ${hm(r.bestAt!!)}, and you closed ${pts(abs(r.taken))} points " +
                    (if (r.taken < 0) "against you" else "at your entry") + ": ${amt(left * t.qty)} on ${t.qty} between its best and your exit."
            }
        }
        val la = r.leftAfter; val aa = r.againstAfter
        if (la != null && aa != null && r.afterUntil != null) {
            out += "After you got out, to ${hm(r.afterUntil)}: " +
                (if (la > 0) "it went ${pts(la)} points further your way (to ${px(r.afterBest!!)} at ${hm(r.afterBestAt!!)}, ${amt(la * t.qty)} on ${t.qty})"
                 else "it never got back past your exit") + "; " +
                (if (aa > 0) "at worst ${pts(aa)} points the other way (${px(r.afterWorst!!)})" else "it never went the other way past your exit") + "."
        }
        val m = r.index
        if (m != null && r.indexFrom != null && r.indexTo != null && r.indexFrom > 0) {
            out += "${m.label} while you held: ${pct((r.indexTo / r.indexFrom - 1) * 100)} (${ix(r.indexFrom)} to ${ix(r.indexTo)})" +
                (r.indexDayPct?.let { "; ${pct(it)} on the day" } ?: "") + "."
        }
        return out
    }

    /** One line per trade, for a day's replay. */
    fun short(r: Replay): String {
        val t = r.trip
        val head = "${hm(t.openedAt)}-${hm(t.closedAt)} ${opened(t)} ${t.symbol} ${px(t.entry)} to ${px(t.exit)} (${signed(r.taken)})${by(t)}"
        val run = r.run; val heat = r.heat
        val hold = if (run == null || heat == null) "no candles for the hold" else "while held best ${signed(run)}, worst ${signed(-heat)}"
        val after = r.leftAfter?.let { la -> if (la > 0) "${pts(la)} more your way after you got out" else "never better after you got out" }
        return "$head: $hold" + (after?.let { "; $it" } ?: "") + "."
    }

    /** What the day's replays say about the exits, as counts (no advice). */
    fun exits(replays: List<Replay>): List<String> {
        val out = ArrayList<String>()
        val covered = replays.filter { it.run != null }
        if (covered.isEmpty()) return out
        val gave = covered.filter { (it.run ?: 0.0) > 0 }
        val kept = gave.filter { it.taken > 0 }.map { it.taken / it.run!! }
        if (kept.isNotEmpty()) out += "In the winning exits you kept ${"%.0f".format(Locale.ENGLISH, 100 * kept.average())}% of the best move while held, on average."
        val turned = gave.count { it.taken <= 0 }
        if (turned > 0) out += "$turned of ${covered.size} went your way first and closed flat or against you."
        val after = replays.filter { it.leftAfter != null }
        val further = after.count { (it.leftAfter ?: 0.0) > 0 && (it.leftAfter ?: 0.0) >= (it.againstAfter ?: 0.0) }
        if (after.isNotEmpty()) out += "$further of ${after.size} went further your way than against after you got out."
        covered.maxByOrNull { it.heat ?: 0.0 }?.takeIf { (it.heat ?: 0.0) > 0 }?.let { d ->
            out += "The deepest against you while held: ${pts(d.heat!!)} points on ${d.trip.symbol} (${hm(d.trip.openedAt)})." }
        return out
    }

    /** The answer's lines: the last trade in full, or the day's trades a line each with what the exits show. */
    fun answer(account: String, scope: Scope, replays: List<Replay>): List<String> {
        if (replays.isEmpty()) return listOf(if (scope == Scope.LAST) "$account: no closed trade to replay yet." else "$account: no closed trades today to replay.")
        val note = "From the app's own minute candles: what happened, not advice."
        if (scope == Scope.LAST) return lines(account, replays.maxByOrNull { it.trip.closedAt }!!) + note
        val sorted = replays.sortedBy { it.trip.closedAt }
        return listOf("$account: ${sorted.size} closed trade${if (sorted.size == 1) "" else "s"} today, net ${rs(sorted.sumOf { it.trip.net })}.") +
            sorted.map { short(it) } + exits(sorted) + note
    }
}
