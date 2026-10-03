package com.optionslab.ira

import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Solo: Jarvis trading by himself, on the paper account, with a professional's rules (Jarvis self-improvement,
 * 2026-10-03). Pure: the app feeds the minute bars and places the paper orders; the backtest feeds history.
 *
 * The one setup taken is the only intraday direction edge the research found in both years of real data
 * (research/COMBINED.md, OUT_OF_SAMPLE.md): a BIG 15-minute candle (body in the top 30% of the last 20 days), then,
 * within an hour, a pullback of 40% of its range on a 5-minute close that does not break the candle's low (green) /
 * high (red). The trade goes the candle's way: stop = the index through that level, target = 2x the risk, 15:10 out.
 * Everything else a professional does is the risk layer: at most 2 trades a day, done for the day after 2 losses or
 * the day's loss limit, no entries after 14:30 or in the last hour of an expiry, one trade at a time, 1 lot.
 */
object Solo {
    /** Minutes are counted from 09:15 (0) to 15:29 (374). */
    const val CUT = 355                     // 15:10: everything out
    const val LAST_ENTRY = 315              // 14:30: no new trade after this

    data class Rules(
        /** A candle is big when its body is at or above this quantile of the last 20 days' 15-minute bodies. */
        val bigQuantile: Double = 0.70,
        /** The pullback toward the level, as a share of the candle's range. */
        val depth: Double = 0.40,
        /** Minutes after the candle in which the pullback must come. */
        val window: Int = 60,
        /** Target in units of the risk. */
        val k: Double = 2.0,
        val maxPerDay: Int = 2,
        /** Losing trades after which the day is over. */
        val lossesToStop: Int = 2,
        /** Last 15-minute candle start that can trigger (14:15). */
        val lastTrigger: Int = 300,
        /** Strikes in the money (0 = at the money). */
        val itm: Int = 0,
        /** Out after this many minutes if the index has not gone [slowR] of the risk our way (null: no time stop). */
        val slowMinutes: Int? = null,
        val slowR: Double = 0.5,
    )

    /** A trade to take at minute [entryMinute] (the next minute's price). [level]: the stop; [target]: the index target. */
    data class Signal(val call: Boolean, val entryMinute: Int, val index: Double, val level: Double, val target: Double, val candleStart: Int, val why: String)

    enum class Exit { STOP, TARGET, TIME, SLOW }

    /** The body size a 15-minute candle needs to count as big, from earlier days' 15-minute candles (null: too little history). */
    fun bigBody(earlier15: List<Candle>, q: Double = Rules().bigQuantile): Double? {
        if (earlier15.size < 100) return null
        val b = earlier15.map { it.body }.sorted()
        return b[((b.size - 1) * q).toInt().coerceIn(0, b.size - 1)]
    }

    /** 15-minute candles from a day's 1-minute bars (index i = minute i from 09:15); only complete ones. */
    fun fifteen(day: List<Candle>): List<Candle> = day.chunked(15).filter { it.size == 15 }.map { c ->
        Candle(c.first().t, c.first().o, c.maxOf { it.h }, c.minOf { it.l }, c.last().c)
    }

    /**
     * The signal at minute [now] (a 5-minute close: now % 5 == 4), from today's 1-minute bars up to [now], or null.
     * [busyUntil]: the minute the last trade ended (no overlap). Incremental and the same live and in the backtest.
     */
    fun signal(day: List<Candle>, now: Int, big: Double, busyUntil: Int = -1, r: Rules = Rules()): Signal? {
        if (now % 5 != 4 || now >= day.size || now <= busyUntil || now + 1 > LAST_ENTRY) return null
        for (s in 0..r.lastTrigger step 15) {
            val e = s + 15
            if (e > now) break
            if (now >= e + r.window) continue
            val c = day.subList(s, e)
            val o = c.first().o; val cl = c.last().c; val h = c.maxOf { it.h }; val l = c.minOf { it.l }
            val body = cl - o
            if (abs(body) < big || body == 0.0) continue
            val up = body > 0
            val lvl = if (up) l else h
            val rng = h - l
            // The level must hold from the candle's end to now.
            if ((e..now).any { m -> if (up) day[m].l <= lvl else day[m].h >= lvl }) continue
            fun pulled(m: Int) = if (up) cl - day[m].c >= r.depth * rng else day[m].c - cl >= r.depth * rng
            if (!pulled(now)) continue
            // Only the first pullback close after the candle (or after the last trade) is an entry.
            val from = maxOf(e, busyUntil + 1)
            if ((from until now).any { m -> m % 5 == 4 && pulled(m) }) continue
            val ix = day[now].c
            val risk = abs(ix - lvl)
            if (risk <= 0) continue
            val tgt = if (up) ix + r.k * risk else ix - r.k * risk
            val t = c.first().t.toLocalTime()
            return Signal(up, now + 1, ix, lvl, tgt, s,
                "a big ${if (up) "green" else "red"} 15-minute candle at %02d:%02d (body %.0f pts) pulled back %.0f%% without breaking its %s"
                    .format(Locale.ENGLISH, t.hour, t.minute, abs(body), r.depth * 100, if (up) "low" else "high"))
        }
        return null
    }

    /** How an open trade ends at minute [m] (null: still open). The stop is checked first, as a careful trader assumes. */
    fun exit(s: Signal, bar: Candle, m: Int, best: Double = 0.0, r: Rules = Rules()): Exit? = when {
        if (s.call) bar.l <= s.level else bar.h >= s.level -> Exit.STOP
        if (s.call) bar.h >= s.target else bar.l <= s.target -> Exit.TARGET
        m >= CUT -> Exit.TIME
        // A trade that has not moved our way in time is a wrong read: out before the option decays.
        r.slowMinutes != null && m - s.entryMinute >= r.slowMinutes && best < r.slowR * abs(s.index - s.level) -> Exit.SLOW
        else -> null
    }

    /** The best index move our way in [bar] since entry, carried forward. */
    fun favour(s: Signal, bar: Candle, best: Double) = maxOf(best, if (s.call) bar.h - s.index else s.index - bar.l)

    /** The day's risk book: whether a new trade may be taken. */
    data class Day(val trades: Int = 0, val losses: Int = 0, val pnl: Double = 0.0) {
        fun canTrade(r: Rules, lossLimit: Double): String? = when {
            trades >= r.maxPerDay -> "already ${r.maxPerDay} trades today"
            losses >= r.lossesToStop -> "${r.lossesToStop} losses today: done for the day"
            pnl <= -lossLimit -> "the day's loss limit is reached"
            else -> null
        }
        fun after(net: Double) = Day(trades + 1, losses + if (net < 0) 1 else 0, pnl + net)
    }

    /** The strike to buy: at the money, or [Rules.itm] steps in the money. */
    fun strike(index: Double, step: Int, call: Boolean, itm: Int = 0): Int {
        val atm = (Math.round(index / step) * step).toInt()
        return if (call) atm - itm * step else atm + itm * step
    }

    // ---- the backtest (the same rules over history, with real option prices) --------------------------------------

    /** One historical day: index minutes (0..374) and option minutes by (strike, CE/PE); missing minutes are null. */
    class HistDay(val date: LocalDate, val expiry: LocalDate, val index: List<Candle>, val options: Map<Pair<Int, Boolean>, Array<Candle?>>)

    data class Trade(val date: LocalDate, val call: Boolean, val strike: Int, val entryMinute: Int, val exitMinute: Int, val exit: Exit,
                     val entryPremium: Double, val exitPremium: Double, val net: Double, val r: Double)

    data class Report(val trades: List<Trade>, val days: Int) {
        val net get() = trades.sumOf { it.net }
        val wins get() = trades.count { it.net > 0 }
        val avgR get() = if (trades.isEmpty()) 0.0 else trades.sumOf { it.r } / trades.size
        val t: Double get() {
            if (trades.size < 2) return 0.0
            val m = net / trades.size
            val sd = sqrt(trades.sumOf { (it.net - m) * (it.net - m) } / (trades.size - 1))
            return if (sd == 0.0) 0.0 else m / (sd / sqrt(trades.size.toDouble()))
        }
        val maxDrawdown: Double get() {
            var peak = 0.0; var eq = 0.0; var dd = 0.0
            for (x in trades) { eq += x.net; peak = maxOf(peak, eq); dd = maxOf(dd, peak - eq) }
            return dd
        }
        fun byMonth(): Map<String, Double> = trades.groupBy { it.date.toString().take(7) }.mapValues { (_, v) -> v.sumOf { it.net } }.toSortedMap()
    }

    /**
     * Plays the rules over [days] (in date order). [step]: the strike step; [lot]: units a lot; [costs]: rupees a round
     * trip; [slip]: premium points lost on each fill; [lossLimit]: the day's loss limit in rupees.
     */
    fun backtest(days: Sequence<HistDay>, step: Int, lot: Int, r: Rules = Rules(), costs: Double = 60.0, slip: Double = 0.5,
                 lossLimit: Double = 5_000.0): Report {
        val earlier = ArrayDeque<List<Candle>>()           // the last 20 days' 15-minute candles
        val out = ArrayList<Trade>()
        var n = 0
        for (d in days) {
            n++
            val big = bigBody(earlier.flatten(), r.bigQuantile)
            if (big != null && d.index.size >= CUT + 1) {
                var book = Day()
                var busy = -1
                var m = 4
                while (m < LAST_ENTRY) {
                    if (book.canTrade(r, lossLimit) != null) break
                    val s = signal(d.index, m, big, busy, r)
                    if (s == null) { m++; continue }
                    // The last hour of an expiry: no new option bought (it decays to nothing).
                    if (d.date == d.expiry && s.entryMinute >= 315) break
                    val k = strike(s.index, step, s.call, r.itm)
                    val leg = d.options[k to s.call]
                    val inBar = leg?.get(s.entryMinute)
                    if (leg == null || inBar == null) { m++; continue }
                    val ep = inBar.o + slip
                    var x = CUT; var how = Exit.TIME
                    var best = 0.0
                    for (j in s.entryMinute..CUT) {
                        val e = exit(s, d.index[j], j, best, r)
                        best = favour(s, d.index[j], best)
                        if (e == null) continue
                        x = j; how = e; break
                    }
                    val outBar = (x downTo s.entryMinute).firstNotNullOfOrNull { leg[it] } ?: inBar
                    val xp = outBar.c - slip
                    val net = (xp - ep) * lot - costs
                    val risk = abs(s.index - s.level)
                    val rr = when (how) { Exit.STOP -> -1.0; Exit.TARGET -> r.k; Exit.TIME, Exit.SLOW -> (if (s.call) 1 else -1) * (d.index[x].c - s.index) / risk }
                    out += Trade(d.date, s.call, k, s.entryMinute, x, how, ep, xp, net, rr)
                    book = book.after(net)
                    busy = x
                    m = x + 1
                }
            }
            earlier.addLast(fifteen(d.index))
            if (earlier.size > 20) earlier.removeFirst()
        }
        return Report(out, n)
    }

    /** The report in a few lines, for Boss. */
    fun say(name: String, rep: Report): String {
        val t = rep.trades
        if (t.isEmpty()) return "$name: no trades in ${rep.days} days."
        val green = rep.byMonth().count { it.value > 0 }
        // (No "%" in the interpolated part: it is formatted on its own.)
        return "$name: ${t.size} trades in ${rep.days} days, ${rep.wins * 100 / t.size}" + "%% winners, net Rs %,.0f (avg Rs %,.0f a trade, t %.2f), index edge %+.2f R, worst drawdown Rs %,.0f, %d of %d months green."
            .format(Locale.ENGLISH, rep.net, rep.net / t.size, rep.t, rep.avgR, rep.maxDrawdown, green, rep.byMonth().size)
    }
}
