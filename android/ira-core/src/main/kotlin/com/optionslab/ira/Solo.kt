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
        /** Learning: trade only while the setup's last [recentN] signals (index result, before costs) averaged at least [recentMinR]; null: always. */
        val recentN: Int? = null,
        val recentMinR: Double = 0.0,
        /** No entry once the day's range so far is this many times a normal day's (null: no such rule). */
        val maxRangeUsed: Double? = null,
        /** The profit lock (Boss's rule, 3 Oct): the app's ladder on the index - see [lock]. */
        val profitLock: Boolean = true,
        /** The lock's rungs: (share of the target reached, share of the target locked in). */
        val ladder: List<Pair<Double, Double>> = LADDER,
        /** A stop-loss on the option's premium as a share of the entry (0.25 = out 25% down); null: none. */
        val premiumStop: Double? = null,
        /** Or the premium stop from the trade's own index risk: entry - [stopDelta] x the points to the index stop. */
        val stopDelta: Double? = null,
    )

    /** A trade to take at minute [entryMinute] (the next minute's price). [level]: the stop; [target]: the index target. */
    data class Signal(val call: Boolean, val entryMinute: Int, val index: Double, val level: Double, val target: Double, val candleStart: Int, val why: String)

    enum class Exit { STOP, TARGET, TIME, SLOW, LOCK, PREMIUM_STOP }

    /** The option's stop-loss price for a trade bought at [premium] (null: no premium stop in [r]). */
    fun premiumStop(s: Signal, premium: Double, r: Rules): Double? = when {
        r.premiumStop != null -> premium * (1 - r.premiumStop)
        r.stopDelta != null -> premium - r.stopDelta * abs(s.index - s.level)
        else -> null
    }?.coerceAtLeast(0.05)

    /** The app's profit-lock ladder: (share of the target reached, share of the target locked in). */
    val LADDER = listOf(0.25 to 0.0, 0.50 to 0.25, 0.75 to 0.50)

    /**
     * The index level the profit lock holds, from the best move our way so far ([best], index points), or null below
     * the first rung: a quarter of the way to the target moves the stop to the entry, half locks a quarter, three
     * quarters lock half.
     */
    fun lock(s: Signal, best: Double, ladder: List<Pair<Double, Double>> = LADDER): Double? {
        val t = abs(s.target - s.index)
        if (t <= 0) return null
        val rung = ladder.lastOrNull { best >= it.first * t - 1e-9 } ?: return null
        return if (s.call) s.index + rung.second * t else s.index - rung.second * t
    }

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
            val t = day[0].t.toLocalDate().atTime(9, 15).plusMinutes(s.toLong()).toLocalTime()
            return Signal(up, now + 1, ix, lvl, tgt, s,
                "a big ${if (up) "green" else "red"} 15-minute candle at %02d:%02d (body %.0f pts) pulled back %.0f%% without breaking its %s"
                    .format(Locale.ENGLISH, t.hour, t.minute, abs(body), r.depth * 100, if (up) "low" else "high"))
        }
        return null
    }

    /**
     * What Solo is watching at minute [now]: each big candle still waiting for its pullback (within its hour, level not
     * broken), as "a big green candle at 10:15: buy if it comes back to about X without breaking Y". Empty: nothing set up.
     */
    fun watching(day: List<Candle>, now: Int, big: Double, label: String, r: Rules = Rules()): List<String> {
        val out = ArrayList<String>()
        for (s in 0..r.lastTrigger step 15) {
            val e = s + 15
            if (e > now + 1 || e >= day.size) break
            if (now >= e + r.window || now + 1 > LAST_ENTRY) continue
            val c = day.subList(s, e)
            val o = c.first().o; val cl = c.last().c; val h = c.maxOf { it.h }; val l = c.minOf { it.l }
            val body = cl - o
            if (abs(body) < big || body == 0.0) continue
            val up = body > 0
            val lvl = if (up) l else h
            if ((e..minOf(now, day.lastIndex)).any { m -> if (up) day[m].l <= lvl else day[m].h >= lvl }) continue
            // Its one chance has passed (a pullback close already came, taken or not): no longer waiting.
            if ((e..minOf(now, day.lastIndex)).any { m -> m % 5 == 4 && (if (up) cl - day[m].c else day[m].c - cl) >= r.depth * (h - l) }) continue
            val entry = if (up) cl - r.depth * (h - l) else cl + r.depth * (h - l)
            val open = day[0].t.toLocalDate().atTime(9, 15)
            val t = open.plusMinutes(s.toLong()).toLocalTime()
            val until = open.plusMinutes(minOf(e + r.window, LAST_ENTRY).toLong()).toLocalTime()
            out += "a big ${if (up) "green" else "red"} 15-minute candle at %02d:%02d: I buy a %s if %s comes back to about %,.0f without %s %,.0f (until %02d:%02d)"
                .format(Locale.ENGLISH, t.hour, t.minute, if (up) "call" else "put", label, entry, if (up) "falling below" else "rising above", lvl, until.hour, until.minute)
        }
        return out
    }

    /**
     * A short read of the day before a trade, the way a trader sizes it up: where the price is against the open and the
     * day's range, and how much of a normal day's range is already used ([typicalRange]: the average of earlier days).
     */
    fun read(day: List<Candle>, typicalRange: Double?, label: String): String {
        if (day.isEmpty()) return ""
        val o = day.first().o; val px = day.last().c
        val h = day.maxOf { it.h }; val l = day.minOf { it.l }
        val move = px - o
        val where = if (h - l <= 0) 0.5 else (px - l) / (h - l)
        val pos = when { where >= 0.75 -> "near the day's high"; where <= 0.25 -> "near the day's low"; else -> "mid-range" }
        val used = typicalRange?.takeIf { it > 0 }?.let { " The day has used %.0f%%".format(Locale.ENGLISH, (h - l) / it * 100) + " of a normal day's range." } ?: ""
        return "%s is %s %,.0f points from the open, %s.".format(Locale.ENGLISH, label, if (move >= 0) "up" else "down", abs(move), pos) + used
    }

    /** How an open trade ends at minute [m] (null: still open). The stop is checked first, as a careful trader assumes. */
    fun exit(s: Signal, bar: Candle, m: Int, best: Double = 0.0, r: Rules = Rules()): Exit? = when {
        if (s.call) bar.l <= s.level else bar.h >= s.level -> Exit.STOP
        if (s.call) bar.h >= s.target else bar.l <= s.target -> Exit.TARGET
        // The lock earned by the best price BEFORE this minute (a rung counts from the next minute on).
        r.profitLock && lock(s, best, r.ladder)?.let { lv -> if (s.call) bar.l <= lv else bar.h >= lv } == true -> Exit.LOCK
        m >= CUT -> Exit.TIME
        // A trade that has not moved our way in time is a wrong read: out before the option decays.
        r.slowMinutes != null && m - s.entryMinute >= r.slowMinutes && best < r.slowR * abs(s.index - s.level) -> Exit.SLOW
        else -> null
    }

    /** The best index move our way in [bar] since entry, carried forward. */
    fun favour(s: Signal, bar: Candle, best: Double) = maxOf(best, if (s.call) bar.h - s.index else s.index - bar.l)

    /**
     * The setup's index result in R for every signal of a finished day (no option, no costs): the record Solo learns
     * from. Each signal is followed to its stop, its target or 15:10, one at a time.
     */
    fun shadow(day: List<Candle>, big: Double, r: Rules = Rules()): List<Double> {
        val out = ArrayList<Double>()
        var busy = -1
        var m = 4
        while (m < LAST_ENTRY && day.size > CUT) {
            val s = signal(day, m, big, busy, r)
            if (s == null) { m++; continue }
            var x = CUT; var res: Double? = null
            val risk = abs(s.index - s.level)
            for (j in s.entryMinute..CUT) {
                when (exit(s, day[j], j)) {
                    Exit.STOP -> { res = -1.0; x = j }
                    Exit.TARGET -> { res = r.k; x = j }
                    else -> {}
                }
                if (res != null) break
            }
            out += res ?: ((if (s.call) 1 else -1) * (day[CUT].c - s.index) / risk)
            busy = x; m = x + 1
        }
        return out
    }

    /**
     * The shadow record over finished sessions (in date order, 1-minute bars from 09:15): each day's signals judged with
     * the "big" threshold of the 20 days before it - the same learning the backtest does.
     */
    fun learn(sessions: List<List<Candle>>, r: Rules = Rules()): List<Double> {
        val out = ArrayList<Double>()
        for (i in sessions.indices) {
            val big = bigBody(sessions.subList(maxOf(0, i - 20), i).flatMap { fifteen(it) }, r.bigQuantile) ?: continue
            out += shadow(sessions[i], big, r)
        }
        return out
    }

    /** How the setup has been doing lately in one market, in a line: its recent results and whether Solo trades it. */
    fun form(recent: List<Double>, r: Rules, label: String): String {
        val n = r.recentN ?: return "$label: traded on every signal (no learning)"
        if (recent.size < n) return "$label: only ${recent.size} of the $n signals it learns from studied yet - trading"
        val last = recent.takeLast(n)
        val hits = last.count { it >= r.k - 1e-9 }
        return "%s: its last %d signals averaged %+.2f R (%d reached the target) - %s".format(Locale.ENGLISH, label, n, last.average(), hits,
            if (working(recent, r)) "trading" else "standing aside")
    }

    /** Whether the setup is working lately: the mean of its last [Rules.recentN] shadow results (true when too few yet). */
    fun working(recent: List<Double>, r: Rules): Boolean {
        val n = r.recentN ?: return true
        if (recent.size < n) return true
        return recent.takeLast(n).average() >= r.recentMinR
    }

    /** Whether the day still has room to move: its range up to minute [now] under [Rules.maxRangeUsed] x [typical]. */
    fun roomLeft(day: List<Candle>, now: Int, typical: Double?, r: Rules): Boolean {
        val k = r.maxRangeUsed ?: return true
        if (typical == null || typical <= 0 || now < 0) return true
        val part = day.subList(0, minOf(now + 1, day.size))
        return part.maxOf { it.h } - part.minOf { it.l } < k * typical
    }

    /**
     * Why a trade ended as it did, the way a trader reviews it: how long it ran, how far it got toward the target, and,
     * when the option and the index disagree, that the option's price (time decay, falling volatility) did it.
     */
    fun review(s: Signal, day: List<Candle>, exitMinute: Int, how: Exit, entryPremium: Double, exitPremium: Double?, label: String): String {
        val x = exitMinute.coerceIn(s.entryMinute, day.lastIndex)
        var best = 0.0
        for (j in s.entryMinute..x) best = favour(s, day[j], best)
        val toTarget = abs(s.target - s.index)
        val got = if (toTarget > 0) (best / toTarget * 100).coerceIn(0.0, 100.0) else 0.0
        val mins = x - s.entryMinute
        val moved = (if (s.call) 1 else -1) * (day[x].c - s.index)
        val way = if (s.call) "up" else "down"
        val bestPart = "at best it went %,.0f points our way (%.0f%%".format(Locale.ENGLISH, best, got) + " of the way to the target)"
        val main = when (how) {
            Exit.TARGET -> "%s reached the target in %d minutes.".format(Locale.ENGLISH, label, mins)
            Exit.STOP -> "%s went back through the candle's %s %d minutes after entry; ".format(Locale.ENGLISH, label, if (s.call) "low" else "high", mins) +
                bestPart + (if (got < 25) ": the pullback was not over - it turned into a reversal." else ": it had the move, then gave it all back.")
            Exit.TIME -> "By 15:10 %s had moved %+,.0f points our way; ".format(Locale.ENGLISH, label, moved) + bestPart + ": the follow-through never came."
            Exit.SLOW -> "No follow-through within %d minutes; ".format(Locale.ENGLISH, mins) + bestPart + "."
            Exit.LOCK -> "The profit lock closed it %d minutes in; ".format(Locale.ENGLISH, mins) + bestPart + "."
            Exit.PREMIUM_STOP -> "The option's stop-loss was hit %d minutes in while %s had moved %+,.0f points our way; ".format(Locale.ENGLISH, mins, label, moved) + bestPart + "."
        }
        // The option against the index: an index move our way with a premium that still fell is the option's price at work.
        val optionNote = exitPremium?.let { xp ->
            val chg = (xp - entryPremium) / entryPremium * 100
            if (moved > 0 && chg < 0) " The index moved %s our way but the option lost %.0f%%".format(Locale.ENGLISH, way, -chg) + ": time decay and falling volatility outweighed the move." else null
        } ?: ""
        return main + optionNote
    }

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
        val learned = ArrayList<Double>()                   // the setup's shadow results on earlier days
        val ranges = ArrayDeque<Double>()                   // earlier days' ranges (high - low)
        var n = 0
        for (d in days) {
            n++
            val big = bigBody(earlier.flatten(), r.bigQuantile)
            if (big != null && d.index.size >= CUT + 1 && working(learned, r)) {
                var book = Day()
                var busy = -1
                var m = 4
                while (m < LAST_ENTRY) {
                    if (book.canTrade(r, lossLimit) != null) break
                    val s = signal(d.index, m, big, busy, r)
                    if (s == null) { m++; continue }
                    if (!roomLeft(d.index, s.entryMinute - 1, ranges.takeIf { it.isNotEmpty() }?.average(), r)) { m++; continue }
                    // The last hour of an expiry: no new option bought (it decays to nothing).
                    if (d.date == d.expiry && s.entryMinute >= 315) break
                    // The wanted strike, or (when the data does not hold it) the nearest one it holds on that side.
                    val want = strike(s.index, step, s.call, r.itm)
                    val k = if (d.options.containsKey(want to s.call)) want
                        else d.options.keys.filter { it.second == s.call }.minByOrNull { abs(it.first - want) }?.first ?: want
                    val leg = d.options[k to s.call]
                    val inBar = leg?.get(s.entryMinute)
                    if (leg == null || inBar == null) { m++; continue }
                    val ep = inBar.o + slip
                    var x = CUT; var how = Exit.TIME
                    var best = 0.0
                    var lockBest = 0.0
                    val ps = premiumStop(s, ep, r)
                    var stopFill: Double? = null
                    for (j in s.entryMinute..CUT) {
                        // The option's own stop-loss first (a resting order): filled at the stop, or the open if it gapped through.
                        val ob = leg[j]
                        if (ps != null && ob != null && ob.l <= ps) { x = j; how = Exit.PREMIUM_STOP; stopFill = minOf(ps, ob.o) - slip; break }
                        val e = exit(s, d.index[j], j, best, r)
                        lockBest = best
                        best = favour(s, d.index[j], best)
                        if (e == null) continue
                        x = j; how = e; break
                    }
                    val outBar = (x downTo s.entryMinute).firstNotNullOfOrNull { leg[it] } ?: inBar
                    val xp = stopFill ?: (outBar.c - slip)
                    val net = (xp - ep) * lot - costs
                    val risk = abs(s.index - s.level)
                    val rr = when (how) { Exit.STOP -> -1.0; Exit.TARGET -> r.k; Exit.TIME, Exit.SLOW, Exit.PREMIUM_STOP -> (if (s.call) 1 else -1) * (d.index[x].c - s.index) / risk
                        Exit.LOCK -> (if (s.call) 1 else -1) * ((lock(s, lockBest, r.ladder) ?: s.index) - s.index) / risk }
                    out += Trade(d.date, s.call, k, s.entryMinute, x, how, ep, xp, net, rr)
                    book = book.after(net)
                    busy = x
                    m = x + 1
                }
            }
            if (big != null && r.recentN != null && d.index.size > CUT) learned += shadow(d.index, big, r)
            ranges.addLast(d.index.maxOf { it.h } - d.index.minOf { it.l }); if (ranges.size > 20) ranges.removeFirst()
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
