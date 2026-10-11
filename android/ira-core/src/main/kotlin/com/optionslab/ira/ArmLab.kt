package com.optionslab.ira

import com.optionslab.engine.Right
import com.optionslab.engine.Series
import com.optionslab.engine.Session
import com.optionslab.engine.Upstox
import com.optionslab.engine.orb.Arm
import com.optionslab.engine.orb.ArmsBacktest
import com.optionslab.engine.orb.Bar
import com.optionslab.engine.orb.LiquidityParams
import com.optionslab.engine.orb.LiquidityRules
import com.optionslab.engine.orb.OrbRules
import com.optionslab.engine.orb.ProfitLock
import com.optionslab.engine.orb.VixDivParams
import com.optionslab.engine.orb.VixDivRules
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The strategy lab replays the REAL arms (S3, 11 Oct): each arm's day followed on a stored [ShadowSim.Day] with the very rule
 * code the arm trades with, given the parameters [ArmParams] says (a variant is the real rules with other values):
 *
 *  - ORB, ORB Fresh, ORB Sweep, Range Fade: the engine's own replay of the arms ([ArmsBacktest.arm]: [OrbRules],
 *    [com.optionslab.engine.orb.SweepRules], [com.optionslab.engine.orb.RangeFadeRules], the profit-lock ladder as the
 *    resting stop) on the day's index and its ATM options, no slip (the lab's costs are [ShadowSim.net]);
 *  - Liquidity 15+5: each book as the arm runs it pass by pass (one pass a minute): the levels and the entry
 *    ([LiquidityRules.zones], [LiquidityRules.signal], [LiquidityRules.hasRoom], [LiquidityRules.entryStrike], 09:20-14:00,
 *    the earlier sessions of the last ten days for the levels, one position per book, the next expiry - a stored expiry day
 *    has only that day's options, so it is skipped), the resting premium stop on the option's minute lows, and every pass
 *    the arm's own exit decision ([LiquidityRules.exitNow]: index stop, the time stop once, the next level, a failed break,
 *    new liquidity), 15:10;
 *  - VIX divergence: the checks as the arm reads them ([VixDivRules.readings]: the minute closed at each check, the first
 *    signal of the day), the 1-ITM strike, NIFTY's resting stop on the option's low and its time stop ([VixDivRules.exit]),
 *    15:10.
 *
 * Fills: decided on a pass at a minute's start, filled at that minute's open; 1 lot; rupees after the lab's paper costs. A day
 * without the option's minutes has no trade (no approximation for a real arm). Nothing here places, changes or reads an
 * order; no clock, no I/O.
 */
object ArmLab {
    /** Liquidity's levels read the sessions of the last this-many calendar days (the arm's history read). */
    const val LIQUIDITY_DAYS = 10L
    private val SQ = OrbRules.SQUARE_OFF.hour * 60 + OrbRules.SQUARE_OFF.minute

    /** The arms the lab can replay (by the lab's strategy key). */
    val KEYS: Set<String> = ArmParams.ORB_ARMS.keys + "liquidity" + VixDivRules.SOURCE

    /**
     * Arm [key]'s virtual trades on [day] with [values] (missing: its defaults). [before]: earlier stored days of the same index
     * (Liquidity's levels read them; any order, others ignored).
     */
    fun trades(key: String, values: Map<String, Double>?, day: ShadowSim.Day, before: List<ShadowSim.Day> = emptyList()): List<ShadowSim.Trade> =
        runCatching {
            ArmParams.ORB_ARMS[key]?.let { return@runCatching orb(it, values, day) }
            when (key) {
                "liquidity" -> liquidity(values, day, before)
                VixDivRules.SOURCE -> vixDiv(values, day)
                else -> emptyList()
            }
        }.getOrDefault(emptyList())

    private fun minuteOf(t: LocalDateTime) = t.hour * 60 + t.minute

    private fun trade(d: ShadowSim.Day, entryMin: Int, exitMin: Int, dir: Int, entry: Double, exit: Double, why: String) =
        ShadowSim.Trade(d.date, entryMin, exitMin, dir, entry, exit, ShadowSim.net(d.underlying, d.lot, entry, exit), why, false)

    // ---- the ORB family -------------------------------------------------------------------------------------------------

    /** [d] as the engine's stored session: the index and its options of one expiry (on or after the day, as stored). */
    fun session(d: ShadowSim.Day): Session {
        val ix = d.index
        val expiry = if (d.expiryDay) d.date else d.date.plusDays(1)
        fun s(x: ShadowSim.Series, strike: Double, right: Right, exp: LocalDate?) =
            Series(exp, strike, right, d.lot, x.minutes, x.c, x.o, x.h, x.l, null, x.oi ?: LongArray(x.size))
        return Session(d.date, d.lot, listOf(s(ix, 0.0, Right.IX, null)) +
            d.legs.map { s(it.s, it.strike, if (it.right > 0) Right.CE else Right.PE, expiry) })
    }

    fun orb(arm: Arm, values: Map<String, Double>?, d: ShadowSim.Day): List<ShadowSim.Trade> {
        if (!d.optionBars || d.underlying != OrbRules.UNDERLYING) return emptyList()
        val t = ArmsBacktest.arm(session(d), arm, ArmParams.orb(arm.source, values), slip = 0.0) ?: return emptyList()
        return t.mapNotNull { x ->
            val e = x.entryTime ?: return@mapNotNull null; val o = x.exitTime ?: return@mapNotNull null
            trade(d, minuteOf(e), minuteOf(o), if (x.right == "CE") 1 else -1, x.entry, x.exit, x.why)
        }
    }

    // ---- Liquidity 15+5 --------------------------------------------------------------------------------------------------

    /** The day's minutes as engine bars (09:15-15:29, as the arm reads them). */
    fun ones(d: ShadowSim.Day): List<Bar> {
        val s = d.index
        val out = ArrayList<Bar>(s.size)
        for (i in 0 until s.size) {
            val m = s.minutes[i]
            if (m < ShadowSim.OPEN || m > 15 * 60 + 29) continue
            out += Bar(d.date.atTime(m / 60, m % 60), s.o[i], s.h[i], s.l[i], s.c[i])
        }
        return out
    }

    /**
     * One book's day, worked out once (it does not depend on the exit parameters): the folded bars, their levels, the signals,
     * and which bars miss their last minute ([lastMissing]: held back for two minutes after they end, as the arm's chart).
     */
    class Book(val tf: Int, val bars: List<Bar>, val zones: List<LiquidityRules.Zone>, val today: List<Decision>, val lastMissing: BooleanArray) {
        /** How many of [bars] the arm's chart holds as closed at minute [t] of [day] (a prefix: only the newest can be held back). */
        fun closed(day: LocalDate, t: Int): Int {
            val now = day.atTime(t / 60, t % 60)
            var n = bars.size
            while (n > 0 && bars[n - 1].start.plusMinutes(tf.toLong()).isAfter(now)) n--
            while (n > 0 && lastMissing[n - 1] && now.isBefore(bars[n - 1].start.plusMinutes(tf + 2L))) n--
            return n
        }
    }

    /** A decision bar of today: its index in [Book.bars], when it is decided (its close, or 2 minutes later when its last minute is missing), the entry it gives. */
    class Decision(val i: Int, val at: Int, val signal: LiquidityRules.Signal?, val strike: Int?)

    /** The sessions [before] [d] that the arm's history read covers, oldest first. */
    fun history(d: ShadowSim.Day, before: List<ShadowSim.Day>): List<ShadowSim.Day> =
        before.filter { it.underlying == d.underlying && it.date.isBefore(d.date) && !it.date.isBefore(d.date.minusDays(LIQUIDITY_DAYS)) }
            .sortedBy { it.date }.distinctBy { it.date }

    /** [arm]'s book on [d] (kept on the day: every variant of a night reads the same one). */
    fun book(arm: Arm, d: ShadowSim.Day, before: List<ShadowSim.Day>): Book {
        val hist = history(d, before)
        val key = "liq|${arm.source}|${hist.size}|${hist.firstOrNull()?.date}"
        @Suppress("UNCHECKED_CAST")
        return d.memo.getOrPut(key) { makeBook(arm, d, hist) } as Book
    }

    private fun makeBook(arm: Arm, d: ShadowSim.Day, hist: List<ShadowSim.Day>): Book {
        val tf = LiquidityRules.minutesOf(arm)
        val und = d.underlying
        val all = hist.flatMap { ones(it) } + ones(d)
        val have = all.mapTo(HashSet()) { it.start }
        val bars = LiquidityRules.fold(all, tf)
        // The levels of all the bars give each bar the arm's own reading of the bars up to it: a zone counts at a bar only
        // once known, and a later break is not yet one (as LiquidityOverlay.skips reads them).
        val zones = LiquidityRules.zones(bars)
        val out = ArrayList<Decision>()
        val missing = BooleanArray(bars.size) { bars[it].start.plusMinutes(tf - 1L) !in have }
        for ((i, b) in bars.withIndex()) {
            if (b.start.toLocalDate() != d.date) continue
            val end = minuteOf(b.start) + tf
            val at = if (!missing[i]) end else end + 2
            var sig: LiquidityRules.Signal? = null
            var strike: Int? = null
            if (i + 1 >= 2 * LiquidityRules.SWING_LOOKBACK + 2 && LiquidityRules.mayEnterAt(b.start.plusMinutes(tf.toLong()))) {
                val s = LiquidityRules.signal(bars.subList(0, i + 1), zones)
                if (s != null && LiquidityRules.hasRoom(s, b.close, und)) { sig = s; strike = LiquidityRules.entryStrike(s.side, b.close, und) }
            }
            out += Decision(i, at, sig, strike)
        }
        return Book(tf, bars, zones, out, missing)
    }

    fun liquidity(values: Map<String, Double>?, d: ShadowSim.Day, before: List<ShadowSim.Day>): List<ShadowSim.Trade> {
        if (!d.optionBars || d.expiryDay || d.underlying !in LiquidityRules.UNDERLYINGS) return emptyList()
        val p = ArmParams.liquidity(values)
        val out = ArrayList<ShadowSim.Trade>()
        for (arm in LiquidityRules.BOOKS) if (LiquidityRules.underlyingOf(arm) == d.underlying) out += liquidityBook(arm, p, d, book(arm, d, before))
        return out.sortedBy { it.entryMin }
    }

    /** One book's trades: a pass a minute; exits first (the resting stop, then the arm's exit decision), then the decision. */
    private fun liquidityBook(arm: Arm, p: LiquidityParams, d: ShadowSim.Day, bk: Book): List<ShadowSim.Trade> {
        val tf = bk.tf
        val und = d.underlying
        val ix = d.index
        val out = ArrayList<ShadowSim.Trade>()
        val decided = HashSet<Int>()
        var pos: Held? = null
        var di = 0                                           // today's decisions not yet closed by this pass
        val first = ShadowSim.OPEN + 1
        for (t in first..15 * 60 + 30) {
            // Exits on the pass at minute t: the minute t-1 has closed.
            pos?.let { h ->
                val m = t - 1
                val j = h.leg.atOrBefore(m).takeIf { it >= 0 && h.leg.minutes[it] == m }
                if (j != null && m >= h.entryMin) {
                    // The resting stop (and the lock it was moved up to) fills inside the minute, on its low.
                    val rest = ProfitLock.restingStop(h.entry, h.base, h.lockRef, h.peak, h.cost)
                    val hit = rest?.let { ProfitLock.sellStopFill(it, h.leg.o[j], h.leg.l[j]) }
                    if (hit != null) {
                        out += trade(d, h.entryMin, m, h.side, h.entry, hit, if (h.base != null && rest <= h.base) "stop" else "profit_lock")
                        pos = null
                    } else h.peak = maxOf(h.peak, h.leg.h[j])
                }
            }
            pos?.let { h ->
                val now = d.date.atTime(t / 60, t % 60)
                val fresh = h.leg.atOrBefore(t - 1).takeIf { it >= 0 }?.let { h.leg.c[it] }
                val why = if (t >= SQ) "session_end" else {
                    val n = bk.closed(d.date, t)
                    val bars = bk.bars.subList(0, n)
                    val since = ArrayList<Bar>()
                    for (i in ix.atOrAfter(h.entryMin) until ix.size) {
                        if (ix.minutes[i] >= t) break
                        since += Bar(d.date.atTime(ix.minutes[i] / 60, ix.minutes[i] % 60), ix.o[i], ix.h[i], ix.l[i], ix.c[i])
                    }
                    val look = LiquidityRules.exitNow(h.side, h.level, h.target, h.signalBar, h.entry, h.entryTime, now, bars, since, und, fresh,
                        h.timed, p) { bk.zones.filter { it.known < n } }
                    h.timed = look.timed
                    look.why
                }
                if (why != null) {
                    out += trade(d, h.entryMin, t, h.side, h.entry, priceAt(h.leg, t) ?: h.entry, why)
                    pos = null
                }
            }
            // The decision: the newest closed bar not yet decided, when flat and inside the arm's window.
            while (di < bk.today.size && bk.today[di].at <= t) di++
            if (pos != null || di == 0 || t < 9 * 60 + 20 || t > 15 * 60 + 12 || t >= SQ) continue
            val dec = bk.today[di - 1]
            if (!decided.add(dec.i)) continue
            val s = dec.signal ?: continue
            val strike = dec.strike ?: continue
            val leg = d.leg(strike.toDouble(), s.side)?.s ?: continue
            val fill = priceAt(leg, t) ?: continue
            val bar = bk.bars[dec.i]
            pos = Held(s.side, s.level, s.target, bar.start, fill, d.date.atTime(t / 60, t % 60), t, leg,
                LiquidityRules.stopTrigger(fill, p.stopPct), p.lockTarget(fill), ProfitLock.roundTripPerUnit(fill, d.lot))
        }
        pos?.let { h -> out += trade(d, h.entryMin, h.leg.minutes.last(), h.side, h.entry, h.leg.c.last(), "last_bar") }
        return out
    }

    /** A market order's price on the pass at minute [t]: that minute's open, else the last close before it (null: none yet). */
    fun priceAt(s: ShadowSim.Series, t: Int): Double? {
        val k = s.atOrAfter(t)
        if (k < s.size && s.minutes[k] == t && s.o[k] > 0) return s.o[k]
        return s.atOrBefore(t - 1).takeIf { it >= 0 }?.let { s.c[it] }?.takeIf { it > 0 }
    }

    private class Held(val side: Int, val level: Double, val target: Double?, val signalBar: LocalDateTime, val entry: Double,
                       val entryTime: LocalDateTime, val entryMin: Int, val leg: ShadowSim.Series, val base: Double?, val lockRef: Double?,
                       val cost: Double) {
        var peak = entry
        var timed = false
    }

    // ---- VIX divergence --------------------------------------------------------------------------------------------------

    /** A series' minutes as the feed's 1-minute candles (what [VixDivRules] reads). */
    fun candles(date: LocalDate, s: ShadowSim.Series): List<Upstox.Bar> = (0 until s.size).map { i ->
        val m = s.minutes[i]
        Upstox.Bar(date.atTime(m / 60, m % 60).atZone(com.optionslab.engine.IST).toEpochSecond(), s.o[i], s.h[i], s.l[i], s.c[i], 0, 0)
    }

    fun vixDiv(values: Map<String, Double>?, d: ShadowSim.Day): List<ShadowSim.Trade> {
        if (!d.optionBars || d.underlying !in VixDivRules.UNDERLYINGS) return emptyList()
        val vx = d.vix ?: return emptyList()
        val p = ArmParams.vixDiv(values)
        val index = candles(d.date, d.index)
        val vix = candles(d.date, vx)
        val first = VixDivRules.CHECKS.first()
        for (t in first until VixDivRules.CHECKS.last() + VixDivRules.ENTRY_GRACE) {
            // The pass at minute t, as the arm's: the minutes closed by then; wait for a check's own minute; the day's first
            // signal decides once (bought in its own minute or the next two, else the day is let go).
            val seenIx = index.takeWhile { it.istMinute < t }; val seenVx = vix.takeWhile { it.istMinute < t }
            if (VixDivRules.awaiting(seenIx, seenVx, t) != null) continue
            val sig = VixDivRules.firstSignal(seenIx, seenVx, t, p.indexMove) ?: continue
            if (!VixDivRules.inEntryWindow(sig, t)) return emptyList()
            val strike = VixDivRules.strike(sig.indexClose, d.underlying, sig.side)
            val leg = d.leg(strike.toDouble(), sig.side)?.s ?: return emptyList()
            val fill = priceAt(leg, t) ?: return emptyList()
            return listOf(vixExit(d, sig.side, leg, t, fill, p))
        }
        return emptyList()
    }

    private fun vixExit(d: ShadowSim.Day, side: Int, leg: ShadowSim.Series, at: Int, fill: Double, p: VixDivParams): ShadowSim.Trade {
        val nifty = VixDivRules.liquidityExit(d.underlying)
        val stop = VixDivRules.stop(fill, p.stopPct)
        val held = VixDivRules.Held(d.underlying, fill, at)
        val seen = ArrayList<VixDivRules.Minute>()
        var j = leg.atOrAfter(at)
        for (t in at + 1..15 * 60 + 30) {
            // The minute t-1: NIFTY's resting stop fills on its low; then the pass at t asks the arm's exit.
            while (j < leg.size && leg.minutes[j] < t) {
                val m = leg.minutes[j]
                if (nifty && leg.l[j] <= stop) return trade(d, at, m, side, fill, minOf(stop, leg.o[j]), "stop_15")
                seen += VixDivRules.Minute(m, leg.l[j], leg.c[j]); j++
            }
            val why = VixDivRules.exit(held, seen, t, p) ?: continue
            val px = if (j < leg.size && leg.minutes[j] == t) leg.o[j] else seen.lastOrNull()?.close ?: fill
            return trade(d, at, t, side, fill, px, why)
        }
        return trade(d, at, leg.minutes.last(), side, fill, leg.c.last(), "last_bar")
    }
}
